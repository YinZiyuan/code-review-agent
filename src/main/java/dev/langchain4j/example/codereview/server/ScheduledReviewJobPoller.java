package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobWorker;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScheduledReviewJobPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledReviewJobPoller.class);

    private final ReviewJobWorker worker;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public ScheduledReviewJobPoller(ReviewJobWorker worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    @Scheduled(
            fixedDelayString = "${code-review.server.worker.poll-interval:1s}",
            initialDelayString = "${code-review.server.worker.poll-interval:1s}")
    public void poll() {
        if (shutdown.get() || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.runOnce();
        } catch (RuntimeException cycleFailure) {
            LOGGER.warn("Review job polling cycle failed");
        } finally {
            polling.set(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            worker.shutdown();
        }
    }
}
