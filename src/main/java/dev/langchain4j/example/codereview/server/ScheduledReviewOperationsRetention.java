package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.PostgresReviewOperationsRetention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs one bounded retention batch at a time; failed cycles are retried on the next interval. */
public final class ScheduledReviewOperationsRetention {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ScheduledReviewOperationsRetention.class);

    private final PostgresReviewOperationsRetention retention;
    private final ReviewOperationsMaintenanceProperties properties;
    private final AtomicBoolean running = new AtomicBoolean();

    public ScheduledReviewOperationsRetention(
            PostgresReviewOperationsRetention retention,
            ReviewOperationsMaintenanceProperties properties) {
        this.retention = Objects.requireNonNull(retention, "retention");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Scheduled(
            fixedDelayString = "${code-review.server.maintenance.interval:1h}",
            initialDelayString = "${code-review.server.maintenance.interval:1h}")
    public void purge() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            retention.purge(properties.retentionAge(), properties.batchSize());
        } catch (RuntimeException failure) {
            LOGGER.warn("Review operations retention cycle failed");
        } finally {
            running.set(false);
        }
    }
}
