package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubInstallationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.CheckPresentation;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.CheckRunRequest;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.InlineCommentRequest;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.PublicationFinding;
import dev.langchain4j.example.codereview.reviewops.application.github.InlineCommentArtifact;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationReference;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class GitHubPublicationClientTest {

    private static final String API_BASE_URL = "https://api.github.test";
    private static final String API_VERSION = "2022-11-28";
    private static final String TOKEN = "installation-token-value";
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final PullRequestRevision REVISION =
            new PullRequestRevision(41, 73, 12, HEAD_SHA);
    private static final ReviewRunId RUN_ID = new ReviewRunId(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    private static final String MARKER =
            "<!-- code-review-agent:fingerprint=" + "a".repeat(64) + " -->";

    @Test
    void readsTheAuthoritativeHeadWithBoundedAuthenticatedVersionedRequest() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/pulls/12"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", API_VERSION))
                .andRespond(withSuccess(
                        "{\"head\":{\"sha\":\"" + HEAD_SHA + "\"}}",
                        MediaType.APPLICATION_JSON));

        AuthoritativeRevision result = fixture.client.authoritativeRevision(REVISION);

        assertThat(result.headSha()).isEqualTo(HEAD_SHA);
        fixture.server.verify();
    }

    @Test
    void updatesThePersistedCheckIdWithoutListingOrCreatingAnotherCheck() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/check-runs/91"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(requiredHeaders())
                .andExpect(content().json("""
                        {
                          "name":"Code Review Agent",
                          "external_id":"550e8400-e29b-41d4-a716-446655440000",
                          "status":"completed",
                          "conclusion":"success",
                          "output":{
                            "title":"Code review completed",
                            "summary":"Review completed with 1 finding.",
                            "text":"No findings were selected for publication."
                          }
                        }
                        """))
                .andRespond(withSuccess("{\"id\":91}", MediaType.APPLICATION_JSON));

        CheckRunArtifact artifact = fixture.client.upsertCheck(
                checkRequest(Optional.of("91"), List.of()));

        assertThat(artifact.githubArtifactId()).isEqualTo("91");
        fixture.server.verify();
    }

    @Test
    void findsACheckByReviewRunExternalIdAndUpdatesItInsteadOfPosting() {
        Fixture fixture = fixture();
        expectCheckList(fixture.server, 1, """
                {"total_count":2,"check_runs":[
                  {"id":77,"external_id":"other-run"},
                  {"id":91,"external_id":"550e8400-e29b-41d4-a716-446655440000"}
                ]}
                """);
        expectCheckUpdate(fixture.server, "91");

        CheckRunArtifact artifact = fixture.client.upsertCheck(
                checkRequest(Optional.empty(), List.of()));

        assertThat(artifact.githubArtifactId()).isEqualTo("91");
        fixture.server.verify();
    }

    @Test
    void createsACheckOnlyAfterReconciliationFindsNoMatchingExternalId() {
        Fixture fixture = fixture();
        expectCheckList(fixture.server, 1, "{\"total_count\":0,\"check_runs\":[]}");
        fixture.server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/check-runs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(requiredHeaders())
                .andExpect(content().json("""
                        {
                          "name":"Code Review Agent",
                          "head_sha":"0123456789abcdef0123456789abcdef01234567",
                          "external_id":"550e8400-e29b-41d4-a716-446655440000",
                          "status":"completed",
                          "conclusion":"success",
                          "output":{
                            "title":"Code review completed",
                            "summary":"Review completed with 1 finding.",
                            "text":"No findings were selected for publication."
                          }
                        }
                        """))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":92}"));

        assertThat(fixture.client.upsertCheck(
                checkRequest(Optional.empty(), List.of())).githubArtifactId())
                .isEqualTo("92");
        fixture.server.verify();
    }

    @Test
    void uncertainCheckCreationIsReconciledByExternalIdBeforeAnyRetryPost() {
        Fixture fixture = fixture();
        expectCheckList(fixture.server, 1, "{\"total_count\":0,\"check_runs\":[]}");
        fixture.server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/check-runs"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .body("uncertain-create-response-secret"));
        expectCheckList(fixture.server, 1, """
                {"total_count":1,"check_runs":[
                  {"id":93,"external_id":"550e8400-e29b-41d4-a716-446655440000"}
                ]}
                """);
        expectCheckUpdate(fixture.server, "93");

        assertThatThrownBy(() -> fixture.client.upsertCheck(
                checkRequest(Optional.empty(), List.of())))
                .isInstanceOfSatisfying(GitHubFailureException.class, failure -> {
                    assertThat(failure.classification())
                            .isEqualTo(GitHubFailureException.Classification.TRANSIENT);
                    assertThat(failure.toString())
                            .doesNotContain("uncertain-create-response-secret");
                });

        assertThat(fixture.client.upsertCheck(
                checkRequest(Optional.empty(), List.of())).githubArtifactId())
                .isEqualTo("93");
        fixture.server.verify();
    }

    @Test
    void findsAnExistingFingerprintMarkerAcrossPaginatedCommentsWithoutPosting() {
        Fixture fixture = fixture();
        expectCommentPage(fixture.server, 1, commentsWithoutMarker(100));
        expectCommentPage(fixture.server, 2,
                "[{\"id\":501,\"body\":\"existing\\n" + MARKER + "\"}]");

        InlineCommentArtifact artifact = fixture.client.reconcileInlineComment(
                inlineRequest(inlineFinding(Optional.empty())));

        assertThat(artifact.fingerprint().value()).isEqualTo("a".repeat(64));
        assertThat(artifact.githubArtifactId()).isEqualTo("501");
        fixture.server.verify();
    }

    @Test
    void returnsAPersistedConfirmedCommentWithoutAnotherRemoteRequest() {
        Fixture fixture = fixture();

        InlineCommentArtifact artifact = fixture.client.reconcileInlineComment(
                inlineRequest(inlineFinding(Optional.of(
                        new PublicationReference("github_review_comment", "501")))));

        assertThat(artifact.githubArtifactId()).isEqualTo("501");
        fixture.server.verify();
    }

    @Test
    void createsACommentAtTheExactReviewedHeadAndRightSideOnlyAfterReconciliation() {
        Fixture fixture = fixture();
        expectCommentPage(fixture.server, 1, "[]");
        fixture.server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/pulls/12/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(requiredHeaders())
                .andExpect(content().json("""
                        {
                          "body":"**WARNING · STABILITY · Issue**\\n\\nDescription\\n\\n**Evidence:** Evidence\\n\\n**Suggestion:** Suggestion\\n\\n<!-- code-review-agent:fingerprint=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa -->",
                          "commit_id":"0123456789abcdef0123456789abcdef01234567",
                          "path":"src/Foo.java",
                          "line":12,
                          "side":"RIGHT"
                        }
                        """))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":502}"));

        assertThat(fixture.client.reconcileInlineComment(
                inlineRequest(inlineFinding(Optional.empty()))).githubArtifactId())
                .isEqualTo("502");
        fixture.server.verify();
    }

    @Test
    void uncertainCommentCreationIsReconciledByMarkerBeforeAnyRetryPost() {
        Fixture fixture = fixture();
        expectCommentPage(fixture.server, 1, "[]");
        fixture.server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/pulls/12/comments"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("uncertain-comment-response-secret"));
        expectCommentPage(fixture.server, 1,
                "[{\"id\":503,\"body\":\"created despite timeout\\n" + MARKER + "\"}]");

        assertThatThrownBy(() -> fixture.client.reconcileInlineComment(
                inlineRequest(inlineFinding(Optional.empty()))))
                .isInstanceOfSatisfying(GitHubFailureException.class, failure -> {
                    assertThat(failure.classification())
                            .isEqualTo(GitHubFailureException.Classification.TRANSIENT);
                    assertThat(failure.toString())
                            .doesNotContain("uncertain-comment-response-secret");
                });

        assertThat(fixture.client.reconcileInlineComment(
                inlineRequest(inlineFinding(Optional.empty()))).githubArtifactId())
                .isEqualTo("503");
        fixture.server.verify();
    }

    @Test
    void mapsRateLimitAndServerFailuresWithoutExposingRawBodies(CapturedOutput output) {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(checkListUrl(1)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header(HttpHeaders.RETRY_AFTER, "120")
                        .body("rate-limit-response-secret"));
        fixture.server.expect(once(), requestTo(checkListUrl(1)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("server-response-secret"));

        assertThatThrownBy(() -> fixture.client.upsertCheck(
                checkRequest(Optional.empty(), List.of())))
                .isInstanceOfSatisfying(GitHubFailureException.class, failure -> {
                    assertThat(failure.classification())
                            .isEqualTo(GitHubFailureException.Classification.RATE_LIMITED);
                    assertThat(failure.retryAt()).contains(NOW.plusSeconds(120));
                    assertThat(failure.toString()).doesNotContain("rate-limit-response-secret");
                });
        assertThatThrownBy(() -> fixture.client.upsertCheck(
                checkRequest(Optional.empty(), List.of())))
                .isInstanceOfSatisfying(GitHubFailureException.class, failure -> {
                    assertThat(failure.classification())
                            .isEqualTo(GitHubFailureException.Classification.TRANSIENT);
                    assertThat(failure.toString()).doesNotContain("server-response-secret");
                });
        assertThat(output.getAll())
                .doesNotContain(TOKEN)
                .doesNotContain("rate-limit-response-secret")
                .doesNotContain("server-response-secret");
        fixture.server.verify();
    }

    @Test
    void honorsServerTimingForPrimaryAndSecondaryRateLimits() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(checkListUrl(1)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Remaining", "0")
                        .header("X-RateLimit-Reset", Long.toString(
                                NOW.plusSeconds(180).getEpochSecond()))
                        .body("primary-limit-secret"));
        fixture.server.expect(once(), requestTo(checkListUrl(1)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header(HttpHeaders.RETRY_AFTER, "120")
                        .body("secondary-limit-secret"));

        assertRateLimitedAt(fixture.client, NOW.plusSeconds(180));
        assertRateLimitedAt(fixture.client, NOW.plusSeconds(120));
        fixture.server.verify();
    }

    @Test
    void treatsAnUnusablePrimaryRateLimitSignalAsAuthorization() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(checkListUrl(1)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Remaining", "0")
                        .body("permission-secret"));

        assertThatThrownBy(() -> fixture.client.upsertCheck(
                checkRequest(Optional.empty(), List.of())))
                .isInstanceOfSatisfying(GitHubFailureException.class, failure -> {
                    assertThat(failure.classification())
                            .isEqualTo(GitHubFailureException.Classification.AUTHORIZATION);
                    assertThat(failure.retryAt()).isEmpty();
                    assertThat(failure.toString()).doesNotContain("permission-secret");
                });
        fixture.server.verify();
    }

    @Test
    void mapsPermissionRemovalAndInvalidDiffLocationToSafeTerminalClassifications() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(checkListUrl(1)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("permission-secret"));
        expectCommentPage(fixture.server, 1, "[]");
        fixture.server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/pulls/12/comments"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("invalid-location-secret"));
        assertThatThrownBy(() -> fixture.client.upsertCheck(
                checkRequest(Optional.empty(), List.of())))
                .isInstanceOfSatisfying(GitHubFailureException.class, failure -> {
                    assertThat(failure.classification())
                            .isEqualTo(GitHubFailureException.Classification.AUTHORIZATION);
                    assertThat(failure).hasMessage("GitHub authorization failed");
                    assertThat(failure.toString()).doesNotContain("permission-secret");
                });
        assertThatThrownBy(() -> fixture.client.reconcileInlineComment(
                inlineRequest(inlineFinding(Optional.empty()))))
                .isInstanceOfSatisfying(GitHubFailureException.class, failure -> {
                    assertThat(failure.classification())
                            .isEqualTo(GitHubFailureException.Classification.DETERMINISTIC_INPUT);
                    assertThat(failure).hasMessage("GitHub inline comment location was invalid");
                    assertThat(failure.toString()).doesNotContain("invalid-location-secret");
                });
        fixture.server.verify();
    }

    @Test
    void refusesAnOversizedReconciliationResponseInsteadOfBlindlyCreating() {
        Fixture fixture = fixture();
        fixture.server.expect(once(), requestTo(checkListUrl(1)))
                .andRespond(withSuccess(
                        "x".repeat(GitHubPublicationClient.MAX_LIST_RESPONSE_BYTES + 1),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.upsertCheck(
                checkRequest(Optional.empty(), List.of())))
                .isInstanceOfSatisfying(GitHubFailureException.class, failure -> {
                    assertThat(failure.classification())
                            .isEqualTo(GitHubFailureException.Classification.TRANSIENT);
                    assertThat(failure)
                            .hasMessage("GitHub publication response size limit exceeded");
                });
        fixture.server.verify();
    }

    private static Fixture fixture() {
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubInstallationGateway installations = installationId ->
                new GitHubInstallationGateway.InstallationToken(
                        TOKEN, NOW.plusSeconds(600));
        GitHubPublicationClient client = new GitHubPublicationClient(
                builder.build(),
                installations,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "Code Review Agent",
                new CheckRunFormatter(),
                new InlineCommentFormatter());
        return new Fixture(server, client);
    }

    private static void assertRateLimitedAt(
            GitHubPublicationClient client, Instant expectedRetryAt) {
        assertThatThrownBy(() -> client.upsertCheck(
                checkRequest(Optional.empty(), List.of())))
                .isInstanceOfSatisfying(GitHubFailureException.class, failure -> {
                    assertThat(failure.classification())
                            .isEqualTo(GitHubFailureException.Classification.RATE_LIMITED);
                    assertThat(failure.retryAt()).contains(expectedRetryAt);
                });
    }

    private static CheckRunRequest checkRequest(
            Optional<String> existingId, List<PublicationFinding> findings) {
        return new CheckRunRequest(
                RUN_ID,
                REVISION,
                CheckPresentation.success("Review completed with 1 finding."),
                findings,
                existingId);
    }

    private static InlineCommentRequest inlineRequest(PublicationFinding finding) {
        return new InlineCommentRequest(RUN_ID, REVISION, finding);
    }

    private static PublicationFinding inlineFinding(
            Optional<PublicationReference> existingReference) {
        return new PublicationFinding(
                new FindingFingerprint("a".repeat(64)),
                new CodeLocation("src/Foo.java", 12, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        "Issue",
                        "Description",
                        "Suggestion"),
                new FindingEvidence("Evidence", List.of(), "regex"),
                new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v1"),
                existingReference);
    }

    private static void expectCheckList(
            MockRestServiceServer server, int page, String response) {
        server.expect(once(), requestTo(checkListUrl(page)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(requiredHeaders())
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private static void expectCheckUpdate(MockRestServiceServer server, String id) {
        server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/check-runs/" + id))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(requiredHeaders())
                .andRespond(withSuccess("{\"id\":" + id + "}", MediaType.APPLICATION_JSON));
    }

    private static void expectCommentPage(
            MockRestServiceServer server, int page, String response) {
        server.expect(once(), requestTo(commentListUrl(page)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(requiredHeaders())
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private static String checkListUrl(int page) {
        return API_BASE_URL
                + "/repositories/73/commits/" + HEAD_SHA
                + "/check-runs?check_name=Code%20Review%20Agent&filter=all&per_page=100&page="
                + page;
    }

    private static String commentListUrl(int page) {
        return API_BASE_URL
                + "/repositories/73/pulls/12/comments?per_page=100&page=" + page;
    }

    private static org.springframework.test.web.client.RequestMatcher requiredHeaders() {
        return request -> {
            header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN).match(request);
            header(HttpHeaders.ACCEPT, "application/vnd.github+json").match(request);
            header("X-GitHub-Api-Version", API_VERSION).match(request);
        };
    }

    private static String commentsWithoutMarker(int count) {
        List<Map<String, Object>> comments = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            comments.add(Map.of("id", index, "body", "ordinary comment " + index));
        }
        try {
            return new ObjectMapper().writeValueAsString(comments);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private record Fixture(
            MockRestServiceServer server,
            GitHubPublicationClient client) {
    }
}
