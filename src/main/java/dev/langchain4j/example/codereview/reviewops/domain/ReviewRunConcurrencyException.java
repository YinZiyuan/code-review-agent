package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Objects;

public final class ReviewRunConcurrencyException extends RuntimeException {
    private final ReviewRunId reviewRunId;
    private final long expectedVersion;

    public ReviewRunConcurrencyException(ReviewRunId reviewRunId, long expectedVersion) {
        super("review run " + Objects.requireNonNull(reviewRunId, "reviewRunId")
                + " was not at expected version " + expectedVersion);
        this.reviewRunId = reviewRunId;
        this.expectedVersion = expectedVersion;
    }

    public ReviewRunId reviewRunId() {
        return reviewRunId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
