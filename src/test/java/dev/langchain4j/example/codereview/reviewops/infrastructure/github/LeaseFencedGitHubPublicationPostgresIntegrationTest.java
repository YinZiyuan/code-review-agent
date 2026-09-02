package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubInstallationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.application.jobs.OperationFence;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.infrastructure.jobs.PostgresDurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LeaseFencedGitHubPublicationPostgresIntegrationTest extends PostgresIntegrationSupport {

    private static final String API_BASE_URL = "https://api.github.test";
    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final PullRequestRevision REVISION =
            new PullRequestRevision(41, 73, 12, HEAD_SHA);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);

    private JdbcTemplate jdbc;
    private PostgresDurableJobQueue jobs;

    @BeforeEach
    void setUpQueue() {
        jdbc = new JdbcTemplate(dataSource);
        jobs = new PostgresDurableJobQueue(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC));
        jdbc.execute("TRUNCATE TABLE durable_jobs");
    }

    @Test
    void recoveredSecondWorkerIsTheOnlyWorkerAllowedToCreateTheRemoteCheck() {
        Instant databaseNow = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant();
        ReviewRunId runId = ReviewRunId.newId();
        UUID jobId = jobs.enqueue(new DurableJobRequest(
                "PUBLISH_REVIEW",
                runId.value(),
                3,
                databaseNow.minusSeconds(1),
                "two-worker-publication"));
        LeasedJob first = jobs.leaseDue(
                "worker-a", databaseNow, LEASE_DURATION, 1).get(0);
        jdbc.update("""
                UPDATE durable_jobs
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, jobId);
        assertThat(jobs.recoverExpiredLeases(databaseNow)).isOne();
        LeasedJob second = jobs.leaseDue(
                "worker-b", databaseNow, LEASE_DURATION, 1).get(0);

        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String listUrl = API_BASE_URL
                + "/repositories/73/commits/" + HEAD_SHA
                + "/check-runs?check_name=Code%20Review%20Agent&app_id=1234"
                + "&filter=all&per_page=100&page=1";
        server.expect(once(), requestTo(listUrl))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"total_count\":0,\"check_runs\":[]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(listUrl))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"total_count\":0,\"check_runs\":[]}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/check-runs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":901}"));
        GitHubInstallationGateway installations = installationId ->
                new GitHubInstallationGateway.InstallationToken(
                        "installation-token", databaseNow.plusSeconds(600));
        GitHubPublicationClient github = new GitHubPublicationClient(
                builder.build(),
                installations,
                new ObjectMapper(),
                Clock.systemUTC(),
                1234,
                "Code Review Agent",
                new CheckRunFormatter(),
                new InlineCommentFormatter());
        GitHubPublicationGateway.CheckRunRequest request =
                new GitHubPublicationGateway.CheckRunRequest(
                        runId,
                        REVISION,
                        GitHubPublicationGateway.CheckPresentation.success("Review completed."),
                        List.of(),
                        Optional.empty());
        OperationFence firstFence = () -> jobs.renewLease(
                jobId,
                "worker-a",
                first.attemptCount(),
                databaseNow,
                LEASE_DURATION);
        OperationFence secondFence = () -> jobs.renewLease(
                jobId,
                "worker-b",
                second.attemptCount(),
                databaseNow,
                LEASE_DURATION);

        assertThatThrownBy(() -> github.upsertCheck(request, firstFence))
                .isInstanceOf(IllegalStateException.class);
        assertThat(github.upsertCheck(request, secondFence).githubArtifactId())
                .isEqualTo("901");
        jobs.markSucceeded(
                jobId, "worker-b", second.attemptCount(), databaseNow);

        assertThat(jdbc.queryForObject(
                "SELECT state FROM durable_jobs WHERE id = ?", String.class, jobId))
                .isEqualTo("SUCCEEDED");
        server.verify();
    }

    @Test
    void recoveredSecondWorkerIsTheOnlyWorkerAllowedToCreateTheRemoteComment() {
        Instant databaseNow = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant();
        ReviewRunId runId = ReviewRunId.newId();
        UUID jobId = jobs.enqueue(new DurableJobRequest(
                "PUBLISH_REVIEW",
                runId.value(),
                3,
                databaseNow.minusSeconds(1),
                "two-worker-comment"));
        LeasedJob first = jobs.leaseDue(
                "worker-a", databaseNow, LEASE_DURATION, 1).get(0);
        jdbc.update("""
                UPDATE durable_jobs
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, jobId);
        assertThat(jobs.recoverExpiredLeases(databaseNow)).isOne();
        LeasedJob second = jobs.leaseDue(
                "worker-b", databaseNow, LEASE_DURATION, 1).get(0);

        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String listUrl = API_BASE_URL
                + "/repositories/73/pulls/12/comments?per_page=100&page=1";
        server.expect(once(), requestTo(listUrl))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(listUrl))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/pulls/12/comments"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":902}"));
        GitHubInstallationGateway installations = installationId ->
                new GitHubInstallationGateway.InstallationToken(
                        "installation-token", databaseNow.plusSeconds(600));
        GitHubPublicationClient github = new GitHubPublicationClient(
                builder.build(),
                installations,
                new ObjectMapper(),
                Clock.systemUTC(),
                1234,
                "Code Review Agent",
                new CheckRunFormatter(),
                new InlineCommentFormatter());
        GitHubPublicationGateway.PublicationFinding finding =
                new GitHubPublicationGateway.PublicationFinding(
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
                        Optional.empty());
        GitHubPublicationGateway.InlineCommentRequest request =
                new GitHubPublicationGateway.InlineCommentRequest(runId, REVISION, finding);
        OperationFence firstFence = () -> jobs.renewLease(
                jobId,
                "worker-a",
                first.attemptCount(),
                databaseNow,
                LEASE_DURATION);
        OperationFence secondFence = () -> jobs.renewLease(
                jobId,
                "worker-b",
                second.attemptCount(),
                databaseNow,
                LEASE_DURATION);

        assertThatThrownBy(() -> github.reconcileInlineComment(request, firstFence))
                .isInstanceOf(IllegalStateException.class);
        assertThat(github.reconcileInlineComment(request, secondFence).githubArtifactId())
                .isEqualTo("902");
        jobs.markSucceeded(
                jobId, "worker-b", second.attemptCount(), databaseNow);

        assertThat(jdbc.queryForObject(
                "SELECT state FROM durable_jobs WHERE id = ?", String.class, jobId))
                .isEqualTo("SUCCEEDED");
        server.verify();
    }
}
