package dev.langchain4j.example.codereview.reviewops.application.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxStore {

    void append(OutboxEvent event);

    List<OutboxEvent> loadUnpublished(int limit);

    void markPublished(UUID eventId, Instant publishedAt);
}
