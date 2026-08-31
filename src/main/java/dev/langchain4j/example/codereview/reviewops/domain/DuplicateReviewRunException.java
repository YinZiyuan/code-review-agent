package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Objects;

public final class DuplicateReviewRunException extends RuntimeException {
    private final ReviewRunId reviewRunId;

    public DuplicateReviewRunException(ReviewRunId reviewRunId) {
        super("review run business identity already exists for "
                + Objects.requireNonNull(reviewRunId, "reviewRunId"));
        this.reviewRunId = reviewRunId;
    }

    public ReviewRunId reviewRunId() {
        return reviewRunId;
    }
}
