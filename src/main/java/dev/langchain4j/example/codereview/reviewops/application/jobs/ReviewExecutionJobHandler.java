package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunAdmissionStore;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class ReviewExecutionJobHandler implements ReviewJobHandler {

    private final BiFunction<ReviewRunId, OperationFence, ExecuteReviewRun.ExecutionOutcome> execute;

    public ReviewExecutionJobHandler(ExecuteReviewRun executeReviewRun) {
        this.execute = Objects.requireNonNull(executeReviewRun, "executeReviewRun")::execute;
    }

    ReviewExecutionJobHandler(
            Function<ReviewRunId, ExecuteReviewRun.ExecutionOutcome> execute) {
        Objects.requireNonNull(execute, "execute");
        this.execute = (id, fence) -> execute.apply(id);
    }

    @Override
    public String jobType() {
        return ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE;
    }

    @Override
    public JobOutcome handle(LeasedJob job) {
        return handle(job, OperationFence.unfenced());
    }

    @Override
    public JobOutcome handle(LeasedJob job, OperationFence fence) {
        Objects.requireNonNull(job, "job");
        ExecuteReviewRun.ExecutionOutcome outcome = Objects.requireNonNull(
                execute.apply(new ReviewRunId(job.payloadReference()),
                        Objects.requireNonNull(fence, "fence")),
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
