package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.util.Objects;
import java.util.UUID;

public final class ReviewRunJobMismatchException extends RuntimeException {

    private final ReviewRunId reviewRunId;
    private final UUID payloadReference;

    public ReviewRunJobMismatchException(ReviewRunId reviewRunId, UUID payloadReference) {
        super("Execution job payload " + payloadReference + " does not match review run " + reviewRunId);
        this.reviewRunId = Objects.requireNonNull(reviewRunId, "reviewRunId");
        this.payloadReference = Objects.requireNonNull(payloadReference, "payloadReference");
    }

    public ReviewRunId reviewRunId() {
        return reviewRunId;
    }

    public UUID payloadReference() {
        return payloadReference;
    }
}
