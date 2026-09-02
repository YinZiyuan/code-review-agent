package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.reviewops.application.DecideReviewPublication;
import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.application.ObservePullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore;
import dev.langchain4j.example.codereview.reviewops.application.RecoverExpiredReviewExecution;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunAdmissionStore;
import dev.langchain4j.example.codereview.reviewops.application.ReviewFindingMapper;
import dev.langchain4j.example.codereview.reviewops.application.SettleReviewJobFailure;
import dev.langchain4j.example.codereview.reviewops.application.github.PreparedReviewSource;
import dev.langchain4j.example.codereview.reviewops.application.github.VerifiedPullRequestEvent;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.application.jobs.BackoffPolicy;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewExecutionJobHandler;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobDispatcher;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobWorker;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ScheduledLeaseHeartbeat;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewAttemptState;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import dev.langchain4j.example.codereview.reviewops.infrastructure.jobs.PostgresDurableJobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteReviewRunPostgresIntegrationTest extends PostgresIntegrationSupport {

    private static final Instant T0 = Instant.parse("2026-09-01T06:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final String DIFF = """
            diff --git a/src/Foo.java b/src/Foo.java
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -1,0 +2,1 @@
            +String value = input;
            """;

    private JdbcTemplate jdbcTemplate;
    private JdbcReviewRunRepository reviewRuns;
    private PostgresDurableJobQueue jobs;
    private TransactionalReviewRunMutationStore mutations;

    @BeforeEach
    void setUpAdapters() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        reviewRuns = new JdbcReviewRunRepository(
                jdbcTemplate, transactions, new JsonColumnCodec(new ObjectMapper()));
        jobs = new PostgresDurableJobQueue(
                jdbcTemplate,
                transactions,
                Clock.fixed(T0, ZoneOffset.UTC),
                new RecoverExpiredReviewExecution(reviewRuns),
                new SettleReviewJobFailure(reviewRuns));
        mutations = new TransactionalReviewRunMutationStore(
                reviewRuns, jobs, new JdbcOutboxStore(jdbcTemplate), transactions);
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, durable_jobs, review_runs CASCADE");
    }

    @Test
    void exhaustedLeaseRecoveryRecyclesPreStartCrashThenSettlesFinalStartedAttempt() {
        ReviewConfigurationSnapshot configuration = new ReviewConfigurationSnapshot(
                "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", 1);
        CapturingObservationStore observations = new CapturingObservationStore();
        new ObservePullRequestRevision(
                observations, Clock.fixed(T0, ZoneOffset.UTC), configuration)
                .observe(verifiedEvent(), "a".repeat(64));
        PullRequestObservationStore.ObservationRequest admission = observations.request;
        reviewRuns.insert(admission.reviewRun());
        var jobId = jobs.enqueue(admission.executionJob());

        LeasedJob preStartCrash = jobs.leaseDue(
                "worker-a", T0, LEASE_DURATION, 1).get(0);
        expireLease(jobId);
        assertThat(jobs.recoverExpiredLeases(preStartCrash.leaseExpiresAt())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT state, attempt_count, max_attempts FROM durable_jobs WHERE id = ?",
                        jobId))
                .containsEntry("state", "READY")
                .containsEntry("attempt_count", 0)
                .containsEntry("max_attempts", 1);

        LeasedJob finalDelivery = jobs.leaseDue(
                "worker-b", preStartCrash.leaseExpiresAt(), LEASE_DURATION, 1).get(0);
        assertThat(finalDelivery.attemptCount()).isGreaterThan(preStartCrash.attemptCount());
        var stored = reviewRuns.find(admission.reviewRun().id()).orElseThrow();
        ReviewRun started = stored.reviewRun();
        Instant startedAt = jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class).toInstant();
        started.startAttempt(startedAt);
        reviewRuns.update(started, stored.version());

        Instant recoveredAt = finalDelivery.leaseExpiresAt();
        expireLease(jobId);
        assertThat(jobs.recoverExpiredLeases(recoveredAt)).isEqualTo(1);

        assertThat(admission.executionJob().maxAttempts()).isEqualTo(1);
        ReviewRun settled = reviewRuns.find(admission.reviewRun().id()).orElseThrow().reviewRun();
        assertThat(settled.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(settled.finalFailure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo("worker_interrupted");
            assertThat(failure.classification()).isEqualTo(FailureClass.TERMINAL);
        });
        assertThat(settled.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.state()).isEqualTo(ReviewAttemptState.TRANSIENT_FAILURE);
            assertThat(attempt.failure()).hasValueSatisfying(failure ->
                    assertThat(failure.code()).isEqualTo("worker_interrupted"));
        });
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT state, attempt_count, max_attempts FROM durable_jobs WHERE id = ?",
                        jobId))
                .containsEntry("state", "SUCCEEDED")
                .containsEntry("attempt_count", 1)
                .containsEntry("max_attempts", 1);
    }

    private void expireLease(java.util.UUID jobId) {
        jdbcTemplate.update("""
                UPDATE durable_jobs
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ? AND state = 'LEASED'
                """, jobId);
    }

    @Test
    void nonZeroModelUsageIsPersistedAndRoundTripsWithTheCompletedAttempt() {
        ReviewRun run = requestedRun(3);
        reviewRuns.insert(run);
        AtomicBoolean closed = new AtomicBoolean();

        ExecuteReviewRun.ExecutionOutcome outcome = executor(
                revision -> preparedSource(closed),
                new FixedTelemetryAgent(ReviewResult.empty("complete"), 211, 47),
                Clock.fixed(T0.plusSeconds(2), ZoneOffset.UTC))
                .execute(run.id());

        assertThat(outcome.status()).isEqualTo(ExecuteReviewRun.ExecutionStatus.COMPLETED);
        assertThat(closed).isTrue();
        ReviewRun persisted = reviewRuns.find(run.id()).orElseThrow().reviewRun();
        assertThat(persisted.attempts()).singleElement().satisfies(attempt ->
                assertThat(attempt.measurements()).contains(
                        new ExecutionMeasurements(0, 211, 47, java.util.Map.of())));
    }

    @Test
    void measuredDownstreamFailurePersistsAndRoundTripsNonZeroModelUsage() {
        ReviewRun run = requestedRun(1);
        reviewRuns.insert(run);
        AtomicBoolean closed = new AtomicBoolean();
        CodeReviewAgent reviewer = new CodeReviewAgent() {
            @Override
            public ReviewResult review(String request, Path sourceRoot) {
                throw new AssertionError("telemetry path is required");
            }

            @Override
            public ReviewExecution reviewWithTelemetry(String request, Path sourceRoot) {
                throw new ReviewExecutionException(
                        new IllegalArgumentException("invalid summarized output"), 333, 61);
            }
        };

        ExecuteReviewRun.ExecutionOutcome outcome = executor(
                revision -> preparedSource(closed),
                reviewer,
                Clock.fixed(T0.plusSeconds(2), ZoneOffset.UTC))
                .execute(run.id());

        assertThat(outcome.status()).isEqualTo(ExecuteReviewRun.ExecutionStatus.TERMINAL_FAILURE);
        assertThat(outcome.failure()).hasValueSatisfying(failure ->
                assertThat(failure.code()).isEqualTo("invalid_review_output"));
        assertThat(closed).isTrue();
        ReviewRun persisted = reviewRuns.find(run.id()).orElseThrow().reviewRun();
        assertThat(persisted.attempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.state()).isEqualTo(ReviewAttemptState.TERMINAL_FAILURE);
            assertThat(attempt.measurements()).contains(
                    new ExecutionMeasurements(0, 333, 61, java.util.Map.of()));
        });
    }

    @Test
    void terminalExecutionSettlementReusesTheAlreadyPersistedPresentationIntent() {
        ReviewRun run = requestedRun(1);
        CodeReviewAgent reviewer = new CodeReviewAgent() {
            @Override
            public ReviewResult review(String request, Path sourceRoot) {
                throw new IllegalArgumentException("invalid summarized output");
            }
        };

        ReviewJobWorker.WorkerCycleResult result = runExecutionWorker(run, reviewer);

        assertTerminalWorkerConvergence(run, result);
    }

    @Test
    void maxAttemptTransientExecutionSettlementReusesTheAlreadyPersistedPresentationIntent() {
        ReviewRun run = requestedRun(1);
        CodeReviewAgent reviewer = new CodeReviewAgent() {
            @Override
            public ReviewResult review(String request, Path sourceRoot) {
                throw new RuntimeException(new HttpTimeoutException("private timeout detail"));
            }
        };

        ReviewJobWorker.WorkerCycleResult result = runExecutionWorker(run, reviewer);

        assertTerminalWorkerConvergence(run, result);
    }

    @Test
    void finalPublicationAndPresentationLeaseRecoveryConvergeWithAStaleSuccessCheck() {
        ReviewRun run = requestedRun(1);
        run.startAttempt(T0.plusSeconds(1));
        run.completeReview(
                List.of(), new ExecutionMeasurements(1, 0, 0, Map.of()), T0.plusSeconds(2));
        run.drainEvents();
        run.acceptPublicationDecisions(Map.of());
        run.authorizePublication(
                new AuthoritativeRevision(run.revision().headSha()), T0.plusSeconds(3));
        run.recordPublicationProgress("success-check", Map.of());
        run.recordJobSystemFailure(new ReviewFailure(
                "github_transient",
                FailureClass.TERMINAL,
                "review job attempts exhausted"), T0.plusSeconds(4));
        reviewRuns.insert(run);
        UUID publicationJobId = jobs.enqueue(new DurableJobRequest(
                DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE,
                run.id().value(),
                1,
                T0,
                "publish:" + run.id().value()));
        LeasedJob publication = jobs.leaseDue(
                "worker-a", T0, LEASE_DURATION, 1).get(0);
        expireLease(publicationJobId);

        DurableJobQueue.LeaseRecoveryBatch publicationRecovery =
                jobs.recoverExpiredLeaseBatch(publication.leaseExpiresAt(), 1);

        assertThat(publicationRecovery.outcomes()).containsExactly(
                new DurableJobQueue.LeaseRecovery(
                        DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE,
                        DurableJobQueue.FailureDisposition.SUCCEEDED));
        LeasedJob presentation = jobs.leaseDue(
                "worker-b", publication.leaseExpiresAt(), LEASE_DURATION, 1).get(0);
        expireLease(presentation.id());

        DurableJobQueue.LeaseRecoveryBatch presentationRecovery =
                jobs.recoverExpiredLeaseBatch(presentation.leaseExpiresAt(), 1);

        assertThat(presentationRecovery.outcomes()).containsExactly(
                new DurableJobQueue.LeaseRecovery(
                        ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE,
                        DurableJobQueue.FailureDisposition.RETRY_SCHEDULED));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM durable_jobs
                WHERE idempotency_key = ? AND state = 'READY' AND attempt_count = 0
                """, Integer.class,
                "present-review-failure:" + run.id().value())).isOne();
    }

    private ReviewJobWorker.WorkerCycleResult runExecutionWorker(
            ReviewRun run, CodeReviewAgent reviewer) {
        reviewRuns.insert(run);
        jobs.enqueue(new DurableJobRequest(
                ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE,
                run.id().value(),
                run.configuration().maxReviewAttempts(),
                T0,
                "execute:" + run.id().value()));
        Clock workerClock = Clock.fixed(T0.plusSeconds(2), ZoneOffset.UTC);
        ExecuteReviewRun execution = executor(
                revision -> preparedSource(new AtomicBoolean()), reviewer, workerClock);
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try (ScheduledLeaseHeartbeat heartbeat = new ScheduledLeaseHeartbeat(
                jobs,
                workerClock,
                LEASE_DURATION,
                Duration.ofSeconds(30),
                scheduler)) {
            ReviewJobWorker worker = new ReviewJobWorker(
                    jobs,
                    new ReviewJobDispatcher(List.of(new ReviewExecutionJobHandler(execution))),
                    BackoffPolicy.exponential(
                            Duration.ofSeconds(1), Duration.ofSeconds(10), 0.0, () -> 0.0),
                    heartbeat,
                    workerClock,
                    new SimpleMeterRegistry(),
                    new ReviewJobWorker.WorkerSettings(
                            "worker-a", LEASE_DURATION, Duration.ofSeconds(30), 1));
            return worker.runOnce();
        } finally {
            scheduler.shutdownNow();
        }
    }

    private void assertTerminalWorkerConvergence(
            ReviewRun run,
            ReviewJobWorker.WorkerCycleResult result) {
        assertThat(result.dead()).isOne();
        assertThat(result.settlementFailed()).isZero();
        ReviewRun failed = reviewRuns.find(run.id()).orElseThrow().reviewRun();
        assertThat(failed.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(failed.finalFailure()).hasValueSatisfying(failure ->
                assertThat(failure.code()).isEqualTo("review_execution_failed"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM durable_jobs
                WHERE idempotency_key = ? AND state = 'READY'
                """, Integer.class, "present-review-failure:" + run.id().value())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT initial_next_attempt_at = (
                    SELECT finished_at FROM review_runs WHERE id = ?)
                FROM durable_jobs WHERE idempotency_key = ?
                """, Boolean.class, run.id().value(),
                "present-review-failure:" + run.id().value())).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT state FROM durable_jobs WHERE job_type = 'REVIEW_EXECUTION'
                """, String.class)).isEqualTo("DEAD");
    }

    private ExecuteReviewRun executor(
            dev.langchain4j.example.codereview.reviewops.application.github.ReviewSourceProvider sources,
            CodeReviewAgent agent,
            Clock clock) {
        return new ExecuteReviewRun(
                reviewRuns,
                mutations,
                sources,
                agent,
                new ReviewFindingMapper(),
                new DiffParser(),
                clock);
    }

    private static ReviewRun requestedRun(int maxAttempts) {
        return ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(
                        10, 20, 30, "0123456789abcdef0123456789abcdef01234567"),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", maxAttempts),
                T0);
    }

    private static PreparedReviewSource preparedSource(AtomicBoolean closed) {
        return new PreparedReviewSource() {
            @Override
            public String diffPatch() {
                return DIFF;
            }

            @Override
            public Path sourceRoot() {
                return Path.of("/tmp/exact-sha-source");
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    private static VerifiedPullRequestEvent verifiedEvent() {
        return new VerifiedPullRequestEvent(
                "delivery-123",
                "synchronize",
                10,
                20,
                "octo/repo",
                30,
                "0123456789abcdef0123456789abcdef01234567",
                "https://github.com/octo/repo.git",
                T0);
    }

    private static final class CapturingObservationStore implements PullRequestObservationStore {
        private ObservationRequest request;

        @Override
        public ObservationResult admit(ObservationRequest request) {
            this.request = request;
            return new ObservationResult(ObservationStatus.ADMITTED, request.reviewRun().id());
        }
    }

    private static final class FixedTelemetryAgent implements CodeReviewAgent {
        private final ReviewResult result;
        private final int inputTokens;
        private final int outputTokens;

        private FixedTelemetryAgent(ReviewResult result, int inputTokens, int outputTokens) {
            this.result = result;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        @Override
        public ReviewResult review(String request, Path sourceRoot) {
            return result;
        }

        @Override
        public ReviewExecution reviewWithTelemetry(String request, Path sourceRoot) {
            return new ReviewExecution(result, inputTokens, outputTokens);
        }
    }
}
