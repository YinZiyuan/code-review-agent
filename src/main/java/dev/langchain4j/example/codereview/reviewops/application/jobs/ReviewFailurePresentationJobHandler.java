package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.application.PresentReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class ReviewFailurePresentationJobHandler implements ReviewJobHandler {

    private final BiFunction<ReviewRunId, OperationFence, PresentReviewFailure.PresentationOutcome>
            present;

    public ReviewFailurePresentationJobHandler(PresentReviewFailure presentReviewFailure) {
        this.present = Objects.requireNonNull(
                presentReviewFailure, "presentReviewFailure")::present;
    }

    ReviewFailurePresentationJobHandler(
            Function<ReviewRunId, PresentReviewFailure.PresentationOutcome> present) {
        Objects.requireNonNull(present, "present");
        this.present = (id, fence) -> present.apply(id);
    }

    @Override
    public String jobType() {
        return ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE;
    }

    @Override
    public JobOutcome handle(LeasedJob job) {
        return handle(job, OperationFence.unfenced());
    }

    @Override
    public JobOutcome handle(LeasedJob job, OperationFence fence) {
        PresentReviewFailure.PresentationOutcome outcome = Objects.requireNonNull(
                present.apply(new ReviewRunId(
                                Objects.requireNonNull(job, "job").payloadReference()),
                        Objects.requireNonNull(fence, "fence")),
                "failure presentation outcome");
        return switch (outcome) {
            case PRESENTED, STALE, ALREADY_PROCESSED -> JobOutcome.succeeded();
            case NOT_FOUND -> JobOutcome.terminalFailure("review_run_not_found");
        };
    }
}
