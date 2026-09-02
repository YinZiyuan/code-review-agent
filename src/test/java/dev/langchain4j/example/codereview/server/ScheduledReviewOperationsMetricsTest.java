package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.infrastructure.observability.ReviewOperationsMetrics;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ScheduledReviewOperationsMetricsTest {

    @Test
    void exponentiallyBacksOffFailedRefreshesAndResetsAfterSuccess() {
        ReviewOperationsMetrics metrics = mock(ReviewOperationsMetrics.class);
        ReviewOperationLogger operations = mock(ReviewOperationLogger.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-09-02T03:00:00Z"));
        ReviewObservabilityProperties properties = new ReviewObservabilityProperties(
                Duration.ofSeconds(10), Duration.ofMinutes(15), Duration.ofSeconds(40));
        doThrow(new IllegalStateException("database unavailable"))
                .doThrow(new IllegalStateException("database unavailable"))
                .doNothing()
                .when(metrics).refresh();
        ScheduledReviewOperationsMetrics scheduler = new ScheduledReviewOperationsMetrics(
                metrics, operations, properties, clock);

        scheduler.refresh();
        clock.advance(Duration.ofSeconds(19));
        scheduler.refresh();
        verify(metrics, times(1)).refresh();

        clock.advance(Duration.ofSeconds(1));
        scheduler.refresh();
        clock.advance(Duration.ofSeconds(39));
        scheduler.refresh();
        verify(metrics, times(2)).refresh();

        clock.advance(Duration.ofSeconds(1));
        scheduler.refresh();
        verify(metrics, times(3)).refresh();

        clock.advance(Duration.ofSeconds(10));
        scheduler.refresh();
        verify(metrics, times(4)).refresh();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
