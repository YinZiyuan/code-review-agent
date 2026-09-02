package dev.langchain4j.example.codereview.reviewops.infrastructure.metrics;

import dev.langchain4j.example.codereview.reviewops.application.ReviewOperationsTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerReviewOperationsTelemetryTest {

    @Test
    void recordsLowCardinalityLifecyclePublicationAndStageSignals() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReviewOperationsTelemetry telemetry =
                new MicrometerReviewOperationsTelemetry(registry);

        telemetry.lifecycle(
                ReviewOperationsTelemetry.LifecycleOutcome.SUPERSEDED, 2);
        telemetry.preventedStale(
                ReviewOperationsTelemetry.StaleStage.SUPERSESSION_SOURCE);
        telemetry.publication(
                ReviewOperationsTelemetry.PublicationOutcome.PUBLISHED);
        telemetry.comment(
                ReviewOperationsTelemetry.CommentOutcome.REPLACED_MISSING);
        String result = telemetry.timePublicationStage(
                ReviewOperationsTelemetry.PublicationStage.INLINE_COMMENT,
                () -> "done");

        assertThat(result).isEqualTo("done");
        assertThat(registry.get("code.review.lifecycle.transitions")
                .tag("outcome", "superseded").counter().count()).isEqualTo(2);
        assertThat(registry.get("code.review.publication.prevented.stale")
                .tag("stage", "supersession_source").counter().count()).isOne();
        assertThat(registry.get("code.review.publication.outcomes")
                .tag("outcome", "published").counter().count()).isOne();
        assertThat(registry.get("code.review.publication.comments")
                .tag("outcome", "replaced_missing").counter().count()).isOne();
        assertThat(registry.get("code.review.publication.stage")
                .tag("stage", "inline_comment").timer().count()).isOne();
        assertThat(registry.get("code.review.publication.stage")
                .tag("stage", "inline_comment").timer().totalTime(
                        java.util.concurrent.TimeUnit.NANOSECONDS))
                .isGreaterThanOrEqualTo(Duration.ZERO.toNanos());
    }
}
