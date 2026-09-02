package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.DecideReviewPublication;
import dev.langchain4j.example.codereview.reviewops.application.PublishReviewOutcome;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.util.Objects;

public final class ReviewPublicationJobHandler implements ReviewJobHandler {

    private final PublishReviewOutcome publishReviewOutcome;

    public ReviewPublicationJobHandler(PublishReviewOutcome publishReviewOutcome) {
        this.publishReviewOutcome = Objects.requireNonNull(
                publishReviewOutcome, "publishReviewOutcome");
    }

    @Override
    public String jobType() {
        return DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE;
    }

    @Override
    public JobOutcome handle(LeasedJob job) {
        return handle(job, OperationFence.unfenced());
    }

    @Override
    public JobOutcome handle(LeasedJob job, OperationFence fence) {
        PublishReviewOutcome.PublicationOutcome outcome = publishReviewOutcome.publish(
                new ReviewRunId(Objects.requireNonNull(job, "job").payloadReference()),
                Objects.requireNonNull(fence, "fence"));
        return switch (outcome) {
            case AUTHORIZED, PUBLISHED, SUPERSEDED, FAILED -> JobOutcome.succeeded();
            case NOT_READY -> JobOutcome.terminalFailure("review_run_not_ready");
            case NOT_FOUND -> JobOutcome.terminalFailure("review_run_not_found");
        };
    }
}
