package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunAdmissionStore;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.util.Objects;
import java.util.function.Function;

public final class ReviewExecutionJobHandler implements ReviewJobHandler {

    private final Function<ReviewRunId, ExecuteReviewRun.ExecutionOutcome> execute;

    public ReviewExecutionJobHandler(ExecuteReviewRun executeReviewRun) {
        this(Objects.requireNonNull(executeReviewRun, "executeReviewRun")::execute);
    }

    ReviewExecutionJobHandler(
            Function<ReviewRunId, ExecuteReviewRun.ExecutionOutcome> execute) {
        this.execute = Objects.requireNonNull(execute, "execute");
    }

    @Override
    public String jobType() {
        return ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE;
    }

    @Override
    public JobOutcome handle(LeasedJob job) {
        Objects.requireNonNull(job, "job");
        ExecuteReviewRun.ExecutionOutcome outcome = Objects.requireNonNull(
                execute.apply(new ReviewRunId(job.payloadReference())),
                "review execution outcome");
        return switch (outcome.status()) {
            case COMPLETED, ALREADY_PROCESSED, SUPERSEDED -> JobOutcome.succeeded();
            case RETRYABLE_FAILURE -> outcome.retryAt()
                    .map(retryAt -> JobOutcome.rateLimited(safeCode(outcome), retryAt))
                    .orElseGet(() -> JobOutcome.transientFailure(safeCode(outcome)));
            case TERMINAL_FAILURE, NOT_FOUND ->
                    JobOutcome.terminalFailure(safeCode(outcome));
        };
    }

    private static String safeCode(ExecuteReviewRun.ExecutionOutcome outcome) {
        return outcome.failure()
                .map(failure -> failure.code())
                .orElse("review_execution_failed");
    }
}
