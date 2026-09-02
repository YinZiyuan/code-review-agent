package dev.langchain4j.example.codereview.workspace;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/** Retries only marker-verified, aged cleanup obligations at startup and hourly. */
public final class ReviewWorkspaceJanitorScheduler {

    private final ReviewWorkspaceJanitor janitor;

    ReviewWorkspaceJanitorScheduler(ReviewWorkspaceJanitor janitor) {
        this.janitor = Objects.requireNonNull(janitor, "janitor");
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
    public void cleanStaleWorkspaces() {
        janitor.cleanStale();
    }
}
