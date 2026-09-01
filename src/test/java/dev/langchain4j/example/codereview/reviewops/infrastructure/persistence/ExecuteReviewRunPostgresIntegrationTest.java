package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.application.ObservePullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore;
import dev.langchain4j.example.codereview.reviewops.application.ReviewFindingMapper;
import dev.langchain4j.example.codereview.reviewops.application.github.PreparedReviewSource;
import dev.langchain4j.example.codereview.reviewops.application.github.VerifiedPullRequestEvent;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewAttemptState;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import dev.langchain4j.example.codereview.reviewops.infrastructure.jobs.PostgresDurableJobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
                jdbcTemplate, transactions, Clock.fixed(T0, ZoneOffset.UTC));
        mutations = new TransactionalReviewRunMutationStore(
                reviewRuns, jobs, new JdbcOutboxStore(jdbcTemplate), transactions);
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, durable_jobs, review_runs CASCADE");
    }

    @Test
    void finalStartedAttemptGetsARecoveryDispatchThatFailsTheRunAndSettlesTheJob() {
        ReviewConfigurationSnapshot configuration = new ReviewConfigurationSnapshot(
                "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", 1);
        CapturingObservationStore observations = new CapturingObservationStore();
        new ObservePullRequestRevision(
                observations, Clock.fixed(T0, ZoneOffset.UTC), configuration)
                .observe(verifiedEvent(), "a".repeat(64));
        PullRequestObservationStore.ObservationRequest admission = observations.request;
        reviewRuns.insert(admission.reviewRun());
        var jobId = jobs.enqueue(admission.executionJob());

        LeasedJob crashedLease = jobs.leaseDue(
                "worker-a", T0, LEASE_DURATION, 1).get(0);
        var stored = reviewRuns.find(admission.reviewRun().id()).orElseThrow();
        ReviewRun started = stored.reviewRun();
        started.startAttempt(T0.plusSeconds(1));
        mutations.saveProgress(started, stored.version());

        Instant recoveredAt = crashedLease.leaseExpiresAt();
        assertThat(jobs.recoverExpiredLeases(recoveredAt)).isEqualTo(1);
        LeasedJob recoveryLease = jobs.leaseDue(
                "worker-b", recoveredAt, LEASE_DURATION, 1).get(0);
        AtomicInteger sourceCalls = new AtomicInteger();
        ExecuteReviewRun.ExecutionOutcome outcome = executor(
                revision -> {
                    sourceCalls.incrementAndGet();
                    throw new AssertionError("terminal recovery must not prepare source");
                },
                new FixedTelemetryAgent(ReviewResult.empty("unused"), 1, 1),
                Clock.fixed(recoveredAt.plusSeconds(1), ZoneOffset.UTC))
                .execute(admission.reviewRun().id());
        jobs.markSucceeded(
                jobId,
                "worker-b",
                recoveryLease.attemptCount(),
                recoveredAt.plusSeconds(1));

        assertThat(admission.executionJob().maxAttempts()).isEqualTo(2);
        assertThat(outcome.status()).isEqualTo(ExecuteReviewRun.ExecutionStatus.TERMINAL_FAILURE);
        assertThat(sourceCalls).hasValue(0);
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
                .containsEntry("attempt_count", 2)
                .containsEntry("max_attempts", 2);
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
