package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.DecideReviewPublication;
import dev.langchain4j.example.codereview.reviewops.application.PublishReviewOutcome;
import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.InlineCommentArtifact;
import dev.langchain4j.example.codereview.reviewops.application.jobs.BackoffPolicy;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobDispatcher;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobHandler;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobWorker;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ScheduledLeaseHeartbeat;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import dev.langchain4j.example.codereview.reviewops.infrastructure.jobs.PostgresDurableJobQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishReviewOutcomePostgresIntegrationTest extends PostgresIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final String REVIEW_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String NEW_SHA = "abcdef0123456789abcdef0123456789abcdef01";

    private JdbcTemplate jdbcTemplate;
    private JdbcReviewRunRepository reviewRuns;
    private TransactionalReviewRunMutationStore mutations;
    private PostgresDurableJobQueue jobs;

    @BeforeEach
    void setUpAdapters() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        reviewRuns = new JdbcReviewRunRepository(
                jdbcTemplate, transactions, new JsonColumnCodec(new ObjectMapper()));
        jobs = new PostgresDurableJobQueue(
                jdbcTemplate, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
        mutations = new TransactionalReviewRunMutationStore(
                reviewRuns, jobs,
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

    @Test
    void completedRunFailsDurablyBeforeAuthorizationFailureDeadLettersPublicationJob() {
        ReviewRun run = completedRunWithDecision();
        reviewRuns.insert(run);
        FailingGateway gateway = new FailingGateway(
                GitHubFailureException.Classification.AUTHORIZATION);
        var result = runPublicationWorker(run, gateway);

        assertThat(result.dead()).isOne();
        assertThat(result.retried()).isZero();
        assertDurableTerminalConvergence(
                run.id(), "github_authorization", null);
        assertThat(gateway.authoritativeCalls).isOne();
        assertThat(gateway.checkMutations).isZero();
        assertThat(gateway.commentMutations).isZero();
    }

    @Test
    void partiallyPublishingRunFailsDurablyBeforeDeterministicFailureDeadLettersJob() {
        ReviewRun run = completedRunWithDecision();
        run.authorizePublication(new AuthoritativeRevision(REVIEW_SHA), NOW.minusSeconds(5));
        run.recordPublicationProgress("check-existing", Map.of());
        reviewRuns.insert(run);
        FailingGateway gateway = new FailingGateway(
                GitHubFailureException.Classification.DETERMINISTIC_INPUT);
        var result = runPublicationWorker(run, gateway);

        assertThat(result.dead()).isOne();
        assertThat(result.retried()).isZero();
        assertDurableTerminalConvergence(
                run.id(), "github_deterministic_input", "check-existing");
        assertThat(gateway.authoritativeCalls).isOne();
        assertThat(gateway.checkMutations).isZero();
        assertThat(gateway.commentMutations).isZero();
    }

    @Test
    void partialCommentSuccessIsDurableAndRetryPublishesOnlyTheMissingComment() {
        ReviewRun run = completedRunWithInlineDecisions();
        reviewRuns.insert(run);
        PartialPublicationGateway gateway = new PartialPublicationGateway();
        PublishReviewOutcome publisher = new PublishReviewOutcome(
                reviewRuns,
                mutations,
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> publisher.publish(run.id()))
                .isInstanceOfSatisfying(GitHubFailureException.class, failure ->
                        assertThat(failure.classification())
                                .isEqualTo(GitHubFailureException.Classification.TRANSIENT));

        var partial = reviewRuns.find(run.id()).orElseThrow();
        assertThat(partial.version()).isEqualTo(3);
        assertThat(partial.reviewRun().state()).isEqualTo(ReviewRunState.PUBLISHING);
        assertThat(partial.reviewRun().checkRunExternalId()).contains("check-901");
        assertThat(partial.reviewRun().commentReferences()).containsOnlyKeys(
                new FindingFingerprint("a".repeat(64)));

        assertThat(publisher.publish(run.id()))
                .isEqualTo(PublishReviewOutcome.PublicationOutcome.PUBLISHED);

        var published = reviewRuns.find(run.id()).orElseThrow();
        assertThat(published.version()).isEqualTo(5);
        assertThat(published.reviewRun().state()).isEqualTo(ReviewRunState.PUBLISHED);
        assertThat(published.reviewRun().commentReferences()).containsOnlyKeys(
                new FindingFingerprint("a".repeat(64)),
                new FindingFingerprint("b".repeat(64)));
        assertThat(gateway.commentFingerprints).containsExactly(
                "a".repeat(64), "b".repeat(64), "b".repeat(64));
        assertThat(gateway.checkExistingIds).containsExactly(null, "check-901");
    }

    private ReviewJobWorker.WorkerCycleResult runPublicationWorker(
            ReviewRun run,
            GitHubPublicationGateway gateway) {
        Instant workerNow = NOW.plusSeconds(1);
        Clock workerClock = Clock.fixed(workerNow, ZoneOffset.UTC);
        jobs.enqueue(new DurableJobRequest(
                DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE,
                run.id().value(),
                3,
                NOW,
                "publish:" + run.id().value()));
        PublishReviewOutcome publisher = new PublishReviewOutcome(
                reviewRuns, mutations, gateway, workerClock);
        ReviewJobHandler handler = publicationHandler(publisher);
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try (ScheduledLeaseHeartbeat heartbeat = new ScheduledLeaseHeartbeat(
                jobs,
                workerClock,
                Duration.ofMinutes(3),
                Duration.ofSeconds(30),
                scheduler)) {
            ReviewJobWorker worker = new ReviewJobWorker(
                    jobs,
                    new ReviewJobDispatcher(List.of(handler)),
                    BackoffPolicy.exponential(
                            Duration.ofSeconds(10), Duration.ofMinutes(1), 0.0, () -> 0.0),
                    heartbeat,
                    workerClock,
                    new SimpleMeterRegistry(),
                    new ReviewJobWorker.WorkerSettings(
                            "publication-worker",
                            Duration.ofMinutes(3),
                            Duration.ofSeconds(30),
                            1));
            return worker.runOnce();
        }
    }

    private void assertDurableTerminalConvergence(
            ReviewRunId runId,
            String expectedFailureCode,
            String expectedCheckRunId) {
        ReviewRun persisted = reviewRuns.find(runId).orElseThrow().reviewRun();
        assertThat(persisted.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(persisted.finalFailure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo(expectedFailureCode);
            assertThat(failure.classification()).isEqualTo(FailureClass.TERMINAL);
            assertThat(failure.safeMessage()).isEqualTo("safe terminal head lookup failure");
        });
        if (expectedCheckRunId == null) {
            assertThat(persisted.checkRunExternalId()).isEmpty();
        } else {
            assertThat(persisted.checkRunExternalId()).contains(expectedCheckRunId);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM durable_jobs WHERE payload_reference = ?",
                String.class,
                runId.value())).isEqualTo("DEAD");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_failure_class FROM durable_jobs WHERE payload_reference = ?",
                String.class,
                runId.value())).isEqualTo("TERMINAL");
    }

    private static ReviewJobHandler publicationHandler(PublishReviewOutcome publisher) {
        return new ReviewJobHandler() {
            @Override
            public String jobType() {
                return DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE;
            }

            @Override
            public JobOutcome handle(LeasedJob job) {
                publisher.publish(new ReviewRunId(job.payloadReference()));
                return JobOutcome.succeeded();
            }
        };
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

    private static ReviewRun completedRunWithInlineDecisions() {
        ReviewFinding first = inlineFinding("a".repeat(64), "src/A.java", 10);
        ReviewFinding second = inlineFinding("b".repeat(64), "src/B.java", 20);
        ReviewRun run = ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(10, 20, 30, REVIEW_SHA),
                new ReviewConfigurationSnapshot(
                        "pipeline-v1", "configuration-v1", "model-v1", "policy-v1", 3),
                NOW.minusSeconds(60));
        run.startAttempt(NOW.minusSeconds(30));
        run.completeReview(
                List.of(first, second),
                new ExecutionMeasurements(100, 10, 2, Map.of()),
                NOW.minusSeconds(10));
        run.drainEvents();
        run.acceptPublicationDecisions(Map.of(
                first.fingerprint(),
                new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v1"),
                second.fingerprint(),
                new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v1")));
        return run;
    }

    private static ReviewFinding inlineFinding(String fingerprint, String file, int line) {
        return new ReviewFinding(
                new FindingFingerprint(fingerprint),
                new CodeLocation(file, line, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        "Issue",
                        "Description",
                        "Suggestion"),
                new FindingEvidence("Evidence", List.of(), "regex"));
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

    private static final class FailingGateway implements GitHubPublicationGateway {
        private final GitHubFailureException.Classification classification;
        private int authoritativeCalls;
        private int checkMutations;
        private int commentMutations;

        private FailingGateway(GitHubFailureException.Classification classification) {
            this.classification = classification;
        }

        @Override
        public AuthoritativeRevision authoritativeRevision(PullRequestRevision revision) {
            authoritativeCalls++;
            throw new GitHubFailureException(
                    classification, "safe terminal head lookup failure");
        }

        @Override
        public CheckRunArtifact upsertCheck(CheckRunRequest request) {
            checkMutations++;
            throw new AssertionError("head lookup failure must prevent Check mutation");
        }

        @Override
        public InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request) {
            commentMutations++;
            throw new AssertionError("head lookup failure must prevent comment mutation");
        }
    }

    private static final class PartialPublicationGateway implements GitHubPublicationGateway {
        private final ArrayList<String> commentFingerprints = new ArrayList<>();
        private final ArrayList<String> checkExistingIds = new ArrayList<>();
        private boolean secondCommentFailed;

        @Override
        public AuthoritativeRevision authoritativeRevision(PullRequestRevision revision) {
            return new AuthoritativeRevision(REVIEW_SHA);
        }

        @Override
        public CheckRunArtifact upsertCheck(CheckRunRequest request) {
            checkExistingIds.add(request.existingGitHubArtifactId().orElse(null));
            return new CheckRunArtifact("check-901");
        }

        @Override
        public InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request) {
            String fingerprint = request.finding().fingerprint().value();
            commentFingerprints.add(fingerprint);
            if (fingerprint.equals("b".repeat(64)) && !secondCommentFailed) {
                secondCommentFailed = true;
                throw new GitHubFailureException(
                        GitHubFailureException.Classification.TRANSIENT,
                        "safe transient comment failure");
            }
            return new InlineCommentArtifact(
                    request.finding().fingerprint(),
                    fingerprint.equals("a".repeat(64)) ? "comment-101" : "comment-102");
        }
    }
}
