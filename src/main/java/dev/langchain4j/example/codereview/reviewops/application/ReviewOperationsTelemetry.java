package dev.langchain4j.example.codereview.reviewops.application;

import java.util.Objects;
import java.util.function.Supplier;

/** Low-cardinality lifecycle and publication signals for the durable review loop. */
public interface ReviewOperationsTelemetry {

    ReviewOperationsTelemetry NOOP = new ReviewOperationsTelemetry() {
    };

    default void lifecycle(LifecycleOutcome outcome, int count) {
        Objects.requireNonNull(outcome, "outcome");
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
    }

    default void preventedStale(StaleStage stage) {
        Objects.requireNonNull(stage, "stage");
    }

    default void publication(PublicationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
    }

    default void comment(CommentOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
    }

    default <T> T timePublicationStage(PublicationStage stage, Supplier<T> work) {
        Objects.requireNonNull(stage, "stage");
        return Objects.requireNonNull(work, "work").get();
    }

    enum LifecycleOutcome {
        SUPERSEDED,
        FAILED
    }

    enum StaleStage {
        EXECUTION_SOURCE,
        PUBLICATION_HEAD,
        SUPERSESSION_SOURCE,
        FAILURE_PRESENTATION
    }

    enum PublicationOutcome {
        PUBLISHED,
        FAILED,
        NEUTRAL_FAILURE
    }

    enum PublicationStage {
        HEAD_LOOKUP,
        CHECK,
        INLINE_COMMENT,
        COMMENT_RETRACTION
    }

    enum CommentOutcome {
        CONFIRMED,
        RECONCILED,
        CREATED,
        REPLACED_MISSING,
        RETRACTED
    }
}
