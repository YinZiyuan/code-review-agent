package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.PublishReviewOutcome;
import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.InlineCommentArtifact;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import dev.langchain4j.example.codereview.reviewops.infrastructure.jobs.PostgresDurableJobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublishReviewOutcomePostgresIntegrationTest extends PostgresIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final String REVIEW_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String NEW_SHA = "abcdef0123456789abcdef0123456789abcdef01";

    private JdbcTemplate jdbcTemplate;
    private JdbcReviewRunRepository reviewRuns;
    private TransactionalReviewRunMutationStore mutations;

    @BeforeEach
    void setUpAdapters() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        reviewRuns = new JdbcReviewRunRepository(
                jdbcTemplate, transactions, new JsonColumnCodec(new ObjectMapper()));
        mutations = new TransactionalReviewRunMutationStore(
                reviewRuns,
                new PostgresDurableJobQueue(
                        jdbcTemplate, transactions, Clock.fixed(NOW, ZoneOffset.UTC)),
                new JdbcOutboxStore(jdbcTemplate),
                transactions);
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, durable_jobs, review_runs CASCADE");
    }

    @Test
    void staleHeadPersistsSupersededOutcomeWithoutAnyGitHubArtifactMutation() {
        ReviewRun run = completedRunWithDecision();
        reviewRuns.insert(run);
        RecordingGateway gateway = new RecordingGateway();
        PublishReviewOutcome publisher = new PublishReviewOutcome(
                reviewRuns,
                mutations,
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC));

        PublishReviewOutcome.PublicationOutcome outcome = publisher.publish(run.id());

        assertThat(outcome).isEqualTo(PublishReviewOutcome.PublicationOutcome.SUPERSEDED);
        var persisted = reviewRuns.find(run.id()).orElseThrow();
        assertThat(persisted.reviewRun().state()).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThat(persisted.reviewRun().finishedAt()).contains(NOW);
        assertThat(persisted.version()).isEqualTo(1);
        assertThat(gateway.authoritativeCalls).isOne();
        assertThat(gateway.checkMutations).isZero();
        assertThat(gateway.commentMutations).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM durable_jobs", Integer.class)).isZero();
    }

    private static ReviewRun completedRunWithDecision() {
        ReviewFinding finding = new ReviewFinding(
                new FindingFingerprint("b".repeat(64)),
                new CodeLocation("src/Foo.java", 10, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        "Issue",
                        "Description",
                        "Suggestion"),
                new FindingEvidence("Evidence", List.of(), "regex"));
        ReviewRun run = ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(10, 20, 30, REVIEW_SHA),
                new ReviewConfigurationSnapshot(
                        "pipeline-v1", "configuration-v1", "model-v1", "policy-v1", 3),
                NOW.minusSeconds(60));
        run.startAttempt(NOW.minusSeconds(30));
        run.completeReview(
                List.of(finding),
                new ExecutionMeasurements(100, 10, 2, Map.of()),
                NOW.minusSeconds(10));
        run.drainEvents();
        run.acceptPublicationDecisions(Map.of(
                finding.fingerprint(),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "policy-v1")));
        return run;
    }

    private static final class RecordingGateway implements GitHubPublicationGateway {
        private int authoritativeCalls;
        private int checkMutations;
        private int commentMutations;

        @Override
        public AuthoritativeRevision authoritativeRevision(PullRequestRevision revision) {
            authoritativeCalls++;
            return new AuthoritativeRevision(NEW_SHA);
        }

        @Override
        public CheckRunArtifact upsertCheck(CheckRunRequest request) {
            checkMutations++;
            throw new AssertionError("stale runs must not mutate a Check Run");
        }

        @Override
        public InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request) {
            commentMutations++;
            throw new AssertionError("stale runs must not mutate inline comments");
        }
    }
}
