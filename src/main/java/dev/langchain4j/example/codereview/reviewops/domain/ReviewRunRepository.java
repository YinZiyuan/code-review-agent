package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Objects;
import java.util.Optional;

public interface ReviewRunRepository {
    Optional<StoredReviewRun> find(ReviewRunId id);

    void insert(ReviewRun reviewRun);

    long update(ReviewRun reviewRun, long expectedVersion);

    record StoredReviewRun(ReviewRun reviewRun, long version) {
        public StoredReviewRun {
            Objects.requireNonNull(reviewRun, "reviewRun");
            if (version < 0) throw new IllegalArgumentException("version must be non-negative");
        }
    }
}
