package dev.langchain4j.example.codereview.reviewops.infrastructure.metrics;

import dev.langchain4j.example.codereview.reviewops.application.ReviewOperationsTelemetry;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public final class MicrometerReviewOperationsTelemetry implements ReviewOperationsTelemetry {

    private final MeterRegistry registry;

    public MicrometerReviewOperationsTelemetry(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void lifecycle(LifecycleOutcome outcome, int count) {
        ReviewOperationsTelemetry.super.lifecycle(outcome, count);
        registry.counter(
                "code.review.lifecycle.transitions",
                "outcome", tag(outcome)).increment(count);
    }

    @Override
    public void preventedStale(StaleStage stage) {
        ReviewOperationsTelemetry.super.preventedStale(stage);
        registry.counter(
                "code.review.publication.prevented.stale",
                "stage", tag(stage)).increment();
    }

    @Override
    public void publication(PublicationOutcome outcome) {
        ReviewOperationsTelemetry.super.publication(outcome);
        registry.counter(
                "code.review.publication.outcomes",
                "outcome", tag(outcome)).increment();
    }

    @Override
    public void comment(CommentOutcome outcome) {
        ReviewOperationsTelemetry.super.comment(outcome);
        registry.counter(
                "code.review.publication.comments",
                "outcome", tag(outcome)).increment();
    }

    @Override
    public <T> T timePublicationStage(PublicationStage stage, Supplier<T> work) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(work, "work");
        return registry.timer(
                "code.review.publication.stage", "stage", tag(stage)).record(work);
    }

    private static String tag(Enum<?> value) {
        return Objects.requireNonNull(value, "metric tag")
                .name().toLowerCase(Locale.ROOT);
    }
}
