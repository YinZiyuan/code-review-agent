package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.application.PresentReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.util.Objects;
import java.util.function.Function;

public final class ReviewFailurePresentationJobHandler implements ReviewJobHandler {

    private final Function<ReviewRunId, PresentReviewFailure.PresentationOutcome> present;

    public ReviewFailurePresentationJobHandler(PresentReviewFailure presentReviewFailure) {
        this(Objects.requireNonNull(presentReviewFailure, "presentReviewFailure")::present);
    }

    ReviewFailurePresentationJobHandler(
            Function<ReviewRunId, PresentReviewFailure.PresentationOutcome> present) {
        this.present = Objects.requireNonNull(present, "present");
    }

    @Override
    public String jobType() {
        return ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE;
    }

    @Override
    public JobOutcome handle(LeasedJob job) {
        PresentReviewFailure.PresentationOutcome outcome = Objects.requireNonNull(
                present.apply(new ReviewRunId(
                        Objects.requireNonNull(job, "job").payloadReference())),
                "failure presentation outcome");
        return switch (outcome) {
            case PRESENTED, STALE, ALREADY_PROCESSED -> JobOutcome.succeeded();
            case NOT_FOUND -> JobOutcome.terminalFailure("review_run_not_found");
        };
    }
}
