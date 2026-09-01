package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubInstallationGateway.InstallationToken;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

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
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GitHub installation token request failed")
                .satisfies(exception -> assertThat(exception.toString())
                        .doesNotContain(responseSecret)
                        .doesNotContain(PRIVATE_KEY_MARKER)
                        .doesNotContain("Bearer"));
        assertThat(output.getAll())
                .doesNotContain(responseSecret)
                .doesNotContain(PRIVATE_KEY_MARKER)
                .doesNotContain("Bearer");
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
