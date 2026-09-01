package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubInstallationGateway.InstallationToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class GitHubRestClientTest {

    private static final String API_BASE_URL = "https://api.github.test";
    private static final Instant START = Instant.parse("2026-09-01T08:00:00Z");
    private static final String PRIVATE_KEY_MARKER = "-----BEGIN PRIVATE KEY-----";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";

    private static String privateKeyPem;

    @BeforeAll
    static void createPrivateKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var privateKey = generator.generateKeyPair().getPrivate();
        privateKeyPem = PRIVATE_KEY_MARKER + "\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    @Test
    void exchangesOnTheNumericInstallationRouteWithRequiredHeadersAndCachesUntilTheRefreshSkew() {
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);

        expectTokenExchange(server, 41L, "installation-token-one", START.plusSeconds(600));
        expectTokenExchange(server, 41L, "installation-token-two", START.plusSeconds(1_200));
        InstallationToken first = client.token(41L);

        clock.advance(Duration.ofMinutes(7));
        InstallationToken cached = client.token(41L);

        clock.advance(Duration.ofMinutes(1));
        InstallationToken refreshed = client.token(41L);

        assertThat(first.value()).isEqualTo("installation-token-one");
        assertThat(cached).isSameAs(first);
        assertThat(refreshed.value()).isEqualTo("installation-token-two");
        assertThat(refreshed).isNotSameAs(first);
        assertThat(first.toString()).doesNotContain("installation-token-one");
        server.verify();
    }

    @Test
    void boundsTheInMemoryInstallationCacheByEvictingTheEarliestExpiry() {
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofSeconds(30), 2);

        expectTokenExchange(server, 41L, "token-41-first", START.plusSeconds(300));
        expectTokenExchange(server, 42L, "token-42", START.plusSeconds(500));
        expectTokenExchange(server, 43L, "token-43", START.plusSeconds(600));
        expectTokenExchange(server, 41L, "token-41-second", START.plusSeconds(700));

        assertThat(client.token(41L).value()).isEqualTo("token-41-first");
        assertThat(client.token(42L).value()).isEqualTo("token-42");
        assertThat(client.token(43L).value()).isEqualTo("token-43");
        assertThat(client.token(41L).value()).isEqualTo("token-41-second");
        server.verify();
    }

    @Test
    void installationExchangeFailuresNeverExposeResponseTokensJwtOrPrivateKey(CapturedOutput output) {
        String responseSecret = "github_pat_response-secret";
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        server.expect(once(), requestTo(API_BASE_URL + "/app/installations/41/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"" + responseSecret + "\"}"));

        assertThatThrownBy(() -> client.token(41L))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.AUTHORIZATION);
                    assertThat(exception.retryAt()).isEmpty();
                    assertThat(exception).hasMessage("GitHub authorization failed");
                    assertThat(exception.toString())
                        .doesNotContain(responseSecret)
                        .doesNotContain(PRIVATE_KEY_MARKER)
                        .doesNotContain("Bearer");
                });
        assertThat(output.getAll())
                .doesNotContain(responseSecret)
                .doesNotContain(PRIVATE_KEY_MARKER)
                .doesNotContain("Bearer");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "500, TRANSIENT",
            "502, TRANSIENT",
            "408, TRANSIENT",
            "401, AUTHORIZATION",
            "403, AUTHORIZATION",
            "404, DETERMINISTIC_INPUT",
            "409, DETERMINISTIC_INPUT",
            "422, DETERMINISTIC_INPUT"
    })
    void classifiesRevisionHttpFailuresWithoutRetainingTheirBodies(
            int status, GitHubFailureException.Classification classification) {
        String responseSecret = "status-body-secret-" + status;
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        expectTokenExchange(server, 41L, "status-matrix-token", START.plusSeconds(600));
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"" + responseSecret + "\"}"));

        assertThatThrownBy(() -> client.requireExactPullRequestHead(revision()))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification()).isEqualTo(classification);
                    assertThat(exception.retryAt()).isEmpty();
                    assertThat(exception.toString())
                            .doesNotContain(responseSecret)
                            .doesNotContain("status-matrix-token");
                });
        server.verify();
    }

    @Test
    void mapsRetryAfterSecondsToARateLimitRetryInstant() {
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        expectTokenExchange(server, 41L, "rate-token", START.plusSeconds(600));
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "120")
                        .body("rate-limit-body-must-not-survive"));

        assertThatThrownBy(() -> client.requireExactPullRequestHead(revision()))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.RATE_LIMITED);
                    assertThat(exception.retryAt()).contains(START.plusSeconds(120));
                    assertThat(exception).hasMessage("GitHub API rate limit exceeded");
                    assertThat(exception.toString()).doesNotContain("rate-limit-body-must-not-survive");
                });
        server.verify();
    }

    @Test
    void mapsRateLimitResetOnAForbiddenResponseButKeepsOrdinaryForbiddenAsAuthorization() {
        long resetEpoch = START.plusSeconds(300).getEpochSecond();
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        expectTokenExchange(server, 41L, "rate-reset-token", START.plusSeconds(600));
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Remaining", "0")
                        .header("X-RateLimit-Reset", Long.toString(resetEpoch))
                        .body("rate-limit-secret"));

        assertThatThrownBy(() -> client.requireExactPullRequestHead(revision()))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.RATE_LIMITED);
                    assertThat(exception.retryAt()).contains(START.plusSeconds(300));
                });
        server.verify();
    }

    @Test
    void mapsAUsableRetryAfterOnForbiddenToARateLimitEvenWithRemainingQuota() {
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        expectTokenExchange(server, 41L, "secondary-rate-token", START.plusSeconds(600));
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Remaining", "42")
                        .header(HttpHeaders.RETRY_AFTER, "90")
                        .body("secondary-rate-secret"));

        assertThatThrownBy(() -> client.requireExactPullRequestHead(revision()))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.RATE_LIMITED);
                    assertThat(exception.retryAt()).contains(START.plusSeconds(90));
                    assertThat(exception.toString()).doesNotContain("secondary-rate-secret");
                });
        server.verify();
    }

    @Test
    void treatsForbiddenWithPositiveRemainingQuotaAndResetAsAuthorization() {
        long resetEpoch = START.plusSeconds(300).getEpochSecond();
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        expectTokenExchange(server, 41L, "ordinary-forbidden-token", START.plusSeconds(600));
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Remaining", "42")
                        .header("X-RateLimit-Reset", Long.toString(resetEpoch))
                        .body("ordinary-forbidden-secret"));

        assertAuthorizationFailure(
                () -> client.requireExactPullRequestHead(revision()),
                "ordinary-forbidden-secret");
        server.verify();
    }

    @Test
    void treatsForbiddenWithResetButMissingRemainingQuotaAsAuthorization() {
        long resetEpoch = START.plusSeconds(300).getEpochSecond();
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        expectTokenExchange(server, 41L, "missing-remaining-token", START.plusSeconds(600));
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Reset", Long.toString(resetEpoch))
                        .body("missing-remaining-secret"));

        assertAuthorizationFailure(
                () -> client.requireExactPullRequestHead(revision()),
                "missing-remaining-secret");
        server.verify();
    }

    @Test
    void treatsForbiddenWithMalformedRemainingQuotaAndResetAsAuthorization() {
        long resetEpoch = START.plusSeconds(300).getEpochSecond();
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        expectTokenExchange(server, 41L, "malformed-remaining-token", START.plusSeconds(600));
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Remaining", "not-a-number")
                        .header("X-RateLimit-Reset", Long.toString(resetEpoch))
                        .body("malformed-remaining-secret"));

        assertAuthorizationFailure(
                () -> client.requireExactPullRequestHead(revision()),
                "malformed-remaining-secret");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "not-an-epoch"})
    void treatsForbiddenWithZeroRemainingButNoUsableResetAsAuthorization(String resetValue) {
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        expectTokenExchange(server, 41L, "unusable-reset-token", START.plusSeconds(600));
        var forbidden = withStatus(HttpStatus.FORBIDDEN)
                .header("X-RateLimit-Remaining", "0")
                .body("unusable-reset-secret");
        if (!"missing".equals(resetValue)) {
            forbidden.header("X-RateLimit-Reset", resetValue);
        }
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andRespond(forbidden);

        assertAuthorizationFailure(
                () -> client.requireExactPullRequestHead(revision()),
                "unusable-reset-secret");
        server.verify();
    }

    @Test
    void treatsForbiddenWithMalformedRetryAfterAsAuthorization() {
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        expectTokenExchange(server, 41L, "malformed-retry-token", START.plusSeconds(600));
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header(HttpHeaders.RETRY_AFTER, "not-a-delay")
                        .body("malformed-retry-secret"));

        assertAuthorizationFailure(
                () -> client.requireExactPullRequestHead(revision()),
                "malformed-retry-secret");
        server.verify();
    }

    @Test
    void mapsNetworkIoFailuresToTransientWithoutRetainingTheTransportMessage() {
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        expectTokenExchange(server, 41L, "network-token", START.plusSeconds(600));
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andRespond(request -> {
                    throw new IOException("network-secret-must-not-survive");
                });

        assertThatThrownBy(() -> client.requireExactPullRequestHead(revision()))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.TRANSIENT);
                    assertThat(exception.retryAt()).isEmpty();
                    assertThat(exception.toString()).doesNotContain("network-secret-must-not-survive");
                });
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"503, TRANSIENT", "401, AUTHORIZATION", "404, AUTHORIZATION"})
    void classifiesInstallationTokenHttpFailures(
            int status, GitHubFailureException.Classification classification) {
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        server.expect(once(), requestTo(API_BASE_URL + "/app/installations/41/access_tokens"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)).body("token-error-secret"));

        assertThatThrownBy(() -> client.token(41L))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification()).isEqualTo(classification);
                    assertThat(exception.retryAt()).isEmpty();
                    assertThat(exception.toString()).doesNotContain("token-error-secret");
                });
        server.verify();
    }

    @Test
    void boundsSuccessfulInstallationTokenResponsesBeforeJsonParsing() {
        String secretMarker = "oversized-success-token-secret";
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        server.expect(once(), requestTo(API_BASE_URL + "/app/installations/41/access_tokens"))
                .andRespond(withSuccess(
                        secretMarker + "x".repeat(70_000), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.token(41L))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.TRANSIENT);
                    assertThat(exception).hasMessage(
                            "GitHub installation token response size limit exceeded");
                    assertThat(exception.toString()).doesNotContain(secretMarker);
                });
        server.verify();
    }

    @Test
    void neverReadsAnOversizedInstallationTokenErrorBody() {
        AtomicBoolean bodyRead = new AtomicBoolean();
        MutableClock clock = new MutableClock(START);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubRestClient client = client(builder, clock, Duration.ofMinutes(2), 8);
        server.expect(once(), requestTo(API_BASE_URL + "/app/installations/41/access_tokens"))
                .andRespond(request -> responseWhoseBodyMustNotBeRead(bodyRead));

        assertThatThrownBy(() -> client.token(41L))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.AUTHORIZATION);
                    assertThat(exception).hasMessage("GitHub authorization failed");
                });
        assertThat(bodyRead).isFalse();
        server.verify();
    }

    private static GitHubRestClient client(
            RestClient.Builder builder, Clock clock, Duration refreshSkew, int maxCacheEntries) {
        GitHubAppJwtFactory jwtFactory = new GitHubAppJwtFactory(123L, privateKeyPem, OBJECT_MAPPER, clock);
        return new GitHubRestClient(
                builder.build(), jwtFactory, OBJECT_MAPPER, clock, refreshSkew, maxCacheEntries);
    }

    private static void expectTokenExchange(
            MockRestServiceServer server, long installationId, String token, Instant expiresAt) {
        server.expect(once(), requestTo(
                        API_BASE_URL + "/app/installations/" + installationId + "/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, org.hamcrest.Matchers.startsWith("Bearer eyJ")))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess("""
                        {"token":"%s","expires_at":"%s","permissions":{"contents":"read"}}
                        """.formatted(token, expiresAt), MediaType.APPLICATION_JSON));
    }

    private static dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision revision() {
        return new dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision(
                41L, 73L, 12, HEAD_SHA);
    }

    private static ClientHttpResponse responseWhoseBodyMustNotBeRead(AtomicBoolean bodyRead) {
        return new ClientHttpResponse() {
            private final HttpHeaders headers = new HttpHeaders();
            {
                headers.setContentLength(1_000_000);
            }

            @Override
            public HttpStatusCode getStatusCode() {
                return HttpStatus.UNAUTHORIZED;
            }

            @Override
            public String getStatusText() {
                return "Unauthorized";
            }

            @Override
            public void close() {
            }

            @Override
            public InputStream getBody() {
                bodyRead.set(true);
                return new ByteArrayInputStream("must-not-be-read".getBytes());
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private static void assertAuthorizationFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            String responseSecret) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.AUTHORIZATION);
                    assertThat(exception.retryAt()).isEmpty();
                    assertThat(exception).hasMessage("GitHub authorization failed");
                    assertThat(exception.toString()).doesNotContain(responseSecret);
                });
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
