package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.infrastructure.observability.ReviewOperationsMetrics;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Refreshes cached gauges without making metrics scrapes perform database I/O. */
public final class ScheduledReviewOperationsMetrics {

    private final ReviewOperationsMetrics metrics;
    private final ReviewOperationLogger operations;
    private final ReviewObservabilityProperties properties;
    private final Clock clock;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Instant nextRefreshAt = Instant.MIN;
    private int consecutiveFailures;

    public ScheduledReviewOperationsMetrics(
            ReviewOperationsMetrics metrics,
            ReviewOperationLogger operations,
            ReviewObservabilityProperties properties,
            Clock clock) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(
            fixedDelayString = "${code-review.server.observability.refresh-interval:15s}",
            initialDelayString = "${code-review.server.observability.refresh-interval:15s}")
    public void refresh() {
        Instant now = clock.instant();
        if (now.isBefore(nextRefreshAt)) {
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            metrics.refresh();
            consecutiveFailures = 0;
            nextRefreshAt = now.plus(properties.refreshInterval());
        } catch (RuntimeException failure) {
            consecutiveFailures++;
            nextRefreshAt = now.plus(failureBackoff());
            operations.log(
                    new ReviewCorrelation(null, null, null, null, null, null, null, null),
                    ReviewOperationLogger.Event.OBSERVABILITY,
                    ReviewOperationLogger.Outcome.FAILED,
                    ReviewOperationLogger.SafeCode.DATABASE_UNAVAILABLE);
        } finally {
            refreshing.set(false);
        }
    }

    private Duration failureBackoff() {
        Duration delay = properties.refreshInterval();
        for (int attempt = 0; attempt < consecutiveFailures; attempt++) {
            if (delay.compareTo(properties.failureBackoffMax()) >= 0) {
                return properties.failureBackoffMax();
            }
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException overflow) {
                return properties.failureBackoffMax();
            }
        }
        return delay.compareTo(properties.failureBackoffMax()) > 0
                ? properties.failureBackoffMax()
                : delay;
    }
}
