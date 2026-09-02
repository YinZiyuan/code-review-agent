package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.infrastructure.observability.ReviewOperationsMetrics;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Refreshes cached gauges without making metrics scrapes perform database I/O. */
public final class ScheduledReviewOperationsMetrics {

    private final ReviewOperationsMetrics metrics;
    private final ReviewOperationLogger operations;
    private final AtomicBoolean refreshing = new AtomicBoolean();

    public ScheduledReviewOperationsMetrics(
            ReviewOperationsMetrics metrics,
            ReviewOperationLogger operations) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Scheduled(
            fixedDelayString = "${code-review.server.observability.refresh-interval:15s}",
            initialDelayString = "${code-review.server.observability.refresh-interval:15s}")
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            metrics.refresh();
        } catch (RuntimeException failure) {
            operations.log(
                    new ReviewCorrelation(null, null, null, null, null, null, null, null),
                    ReviewOperationLogger.Event.OBSERVABILITY,
                    ReviewOperationLogger.Outcome.FAILED,
                    ReviewOperationLogger.SafeCode.DATABASE_UNAVAILABLE);
        } finally {
            refreshing.set(false);
        }
    }
}
