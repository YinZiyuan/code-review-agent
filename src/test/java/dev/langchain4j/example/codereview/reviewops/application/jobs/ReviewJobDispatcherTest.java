package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.application.SupersedeObsoleteReviewRuns;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewJobDispatcherTest {

    private static final LeasedJob REVIEW_JOB = new LeasedJob(
            UUID.fromString("00000000-0000-0000-0000-000000000701"),
            "REVIEW_EXECUTION",
            UUID.fromString("00000000-0000-0000-0000-000000000702"),
            4,
            5,
            Instant.parse("2026-09-01T10:05:00Z"));

    @Test
    void exactKnownJobTypeRoutesToItsHandlerOnce() {
        AtomicInteger calls = new AtomicInteger();
        ReviewJobHandler handler = handler("REVIEW_EXECUTION", job -> {
            calls.incrementAndGet();
            assertThat(job).isSameAs(REVIEW_JOB);
            return ReviewJobHandler.JobOutcome.succeeded();
        });
        ReviewJobDispatcher dispatcher = new ReviewJobDispatcher(List.of(handler));

        ReviewJobHandler.JobOutcome outcome = dispatcher.dispatch(REVIEW_JOB);

        assertThat(outcome.status()).isEqualTo(ReviewJobHandler.JobStatus.SUCCEEDED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void jobTypeMatchingIsExact() {
        AtomicInteger calls = new AtomicInteger();
        ReviewJobDispatcher dispatcher = new ReviewJobDispatcher(List.of(handler(
                "review_execution",
                job -> {
                    calls.incrementAndGet();
                    return ReviewJobHandler.JobOutcome.succeeded();
                })));

        ReviewJobHandler.JobOutcome outcome = dispatcher.dispatch(REVIEW_JOB);

        assertThat(outcome.status()).isEqualTo(ReviewJobHandler.JobStatus.TERMINAL_FAILURE);
        assertThat(outcome.safeCode()).isEqualTo("unknown_job_type");
        assertThat(calls).hasValue(0);
    }

    @Test
    void unknownJobTypeReturnsSafeTerminalFailure() {
        ReviewJobDispatcher dispatcher = new ReviewJobDispatcher(List.of());
        LeasedJob unknown = new LeasedJob(
                REVIEW_JOB.id(),
                "UNRECOGNIZED_JOB_TYPE",
                REVIEW_JOB.payloadReference(),
                REVIEW_JOB.attemptCount(),
                REVIEW_JOB.maxAttempts(),
                REVIEW_JOB.leaseExpiresAt());

        ReviewJobHandler.JobOutcome outcome = dispatcher.dispatch(unknown);

        assertThat(outcome.status()).isEqualTo(ReviewJobHandler.JobStatus.TERMINAL_FAILURE);
        assertThat(outcome.safeCode()).isEqualTo("unknown_job_type");
        assertThat(outcome.retryAt()).isEmpty();
    }

    @Test
    void duplicateHandlerRegistrationFailsAtConstruction() {
        ReviewJobHandler first = handler(
                "REVIEW_EXECUTION", job -> ReviewJobHandler.JobOutcome.succeeded());
        ReviewJobHandler duplicate = handler(
                "REVIEW_EXECUTION", job -> ReviewJobHandler.JobOutcome.succeeded());

        assertThatThrownBy(() -> new ReviewJobDispatcher(List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate review job handler: REVIEW_EXECUTION");
    }

    @Test
    void reviewExecutionHandlerMapsCompletedAndSettledRunsToJobSuccess() {
        for (ExecuteReviewRun.ExecutionStatus status : List.of(
                ExecuteReviewRun.ExecutionStatus.COMPLETED,
                ExecuteReviewRun.ExecutionStatus.ALREADY_PROCESSED,
                ExecuteReviewRun.ExecutionStatus.SUPERSEDED)) {
            AtomicReference<ReviewRunId> handled = new AtomicReference<>();
            ReviewExecutionJobHandler handler = new ReviewExecutionJobHandler(id -> {
                handled.set(id);
                return new ExecuteReviewRun.ExecutionOutcome(status, java.util.Optional.empty(),
                        java.util.Optional.empty());
            });

            ReviewJobHandler.JobOutcome outcome = handler.handle(REVIEW_JOB);

            assertThat(handler.jobType()).isEqualTo("REVIEW_EXECUTION");
            assertThat(handled.get().value()).isEqualTo(REVIEW_JOB.payloadReference());
            assertThat(outcome.status()).isEqualTo(ReviewJobHandler.JobStatus.SUCCEEDED);
        }
    }

    @Test
    void reviewExecutionHandlerPreservesRetryTimingAndTerminalClassification() {
        Instant retryAt = Instant.parse("2026-09-01T10:09:00Z");
        ReviewFailure transientFailure = new ReviewFailure(
                "github_rate_limited", FailureClass.TRANSIENT, "GitHub rate limited");
        ReviewExecutionJobHandler rateLimited = new ReviewExecutionJobHandler(id ->
                new ExecuteReviewRun.ExecutionOutcome(
                        ExecuteReviewRun.ExecutionStatus.RETRYABLE_FAILURE,
                        java.util.Optional.of(transientFailure),
                        java.util.Optional.of(retryAt)));
        ReviewFailure terminalFailure = new ReviewFailure(
                "invalid_review_output", FailureClass.TERMINAL, "review output was invalid");
        ReviewExecutionJobHandler terminal = new ReviewExecutionJobHandler(id ->
                new ExecuteReviewRun.ExecutionOutcome(
                        ExecuteReviewRun.ExecutionStatus.TERMINAL_FAILURE,
                        java.util.Optional.of(terminalFailure),
                        java.util.Optional.empty()));
        ReviewExecutionJobHandler transientJob = new ReviewExecutionJobHandler(id ->
                new ExecuteReviewRun.ExecutionOutcome(
                        ExecuteReviewRun.ExecutionStatus.RETRYABLE_FAILURE,
                        java.util.Optional.of(new ReviewFailure(
                                "model_timeout", FailureClass.TRANSIENT, "review model timed out")),
                        java.util.Optional.empty()));

        assertThat(rateLimited.handle(REVIEW_JOB))
                .isEqualTo(ReviewJobHandler.JobOutcome.rateLimited(
                        "github_rate_limited", retryAt));
        assertThat(terminal.handle(REVIEW_JOB))
                .isEqualTo(ReviewJobHandler.JobOutcome.terminalFailure(
                        "invalid_review_output"));
        assertThat(transientJob.handle(REVIEW_JOB))
                .isEqualTo(ReviewJobHandler.JobOutcome.transientFailure("model_timeout"));
    }

    @Test
    void supersessionHandlerRegistersThePersistedTypeAndMapsApplicationOutcome() {
        SupersedeObsoleteReviewRunsJobHandler completed =
                new SupersedeObsoleteReviewRunsJobHandler(id ->
                        new SupersedeObsoleteReviewRuns.SupersessionOutcome(
                                SupersedeObsoleteReviewRuns.SupersessionStatus.COMPLETED, 2));
        SupersedeObsoleteReviewRunsJobHandler missing =
                new SupersedeObsoleteReviewRunsJobHandler(id ->
                        new SupersedeObsoleteReviewRuns.SupersessionOutcome(
                                SupersedeObsoleteReviewRuns.SupersessionStatus.NOT_FOUND, 0));
        LeasedJob supersessionJob = new LeasedJob(
                REVIEW_JOB.id(),
                "SUPERSEDE_OBSOLETE_RUNS",
                REVIEW_JOB.payloadReference(),
                REVIEW_JOB.attemptCount(),
                REVIEW_JOB.maxAttempts(),
                REVIEW_JOB.leaseExpiresAt());

        assertThat(completed.jobType()).isEqualTo("SUPERSEDE_OBSOLETE_RUNS");
        assertThat(completed.handle(supersessionJob))
                .isEqualTo(ReviewJobHandler.JobOutcome.succeeded());
        assertThat(missing.handle(supersessionJob))
                .isEqualTo(ReviewJobHandler.JobOutcome.terminalFailure(
                        "supersession_source_not_found"));
    }

    private static ReviewJobHandler handler(
            String jobType,
            java.util.function.Function<LeasedJob, ReviewJobHandler.JobOutcome> behavior) {
        return new ReviewJobHandler() {
            @Override
            public String jobType() {
                return jobType;
            }

            @Override
            public JobOutcome handle(LeasedJob job) {
                return behavior.apply(job);
            }
        };
    }
}
