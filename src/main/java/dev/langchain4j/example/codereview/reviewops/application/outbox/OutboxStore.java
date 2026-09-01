package dev.langchain4j.example.codereview.reviewops.application.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * At-least-once outbox storage without claim or lease ownership.
 *
 * <p>Two concurrent pollers may load the same unpublished event, and a crash after delivery but
 * before acknowledgment may expose it again. Consumers must deduplicate by
 * {@link OutboxEvent#eventId()}; no exactly-once guarantee is made.</p>
 */
public interface OutboxStore {

    void append(OutboxEvent event);

    /**
     * Loads unpublished events in the store's stable delivery order.
     * Multiple pollers may receive the same events.
     */
    List<OutboxEvent> loadUnpublished(int limit);

    /**
     * Idempotently records the database acknowledgment for a published event.
     * Repeating the acknowledgment neither deletes the event nor changes its first published time.
     */
    void markPublished(UUID eventId, Instant publishedAt);
}
