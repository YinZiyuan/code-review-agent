package dev.langchain4j.example.codereview.reviewops.application.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * A durable fact delivered with at-least-once semantics.
 *
 * <p>Concurrent pollers and a publisher crash between downstream delivery and
 * {@link OutboxStore#markPublished(UUID, Instant)} may expose the same event more than once.
 * {@code eventId} is therefore the stable idempotency key that downstream consumers must use.
 * This contract does not provide exactly-once delivery.</p>
 *
 * <p>Payloads are canonicalized by recursively sorting object keys by their Java string order,
 * retaining array order, and emitting compact Jackson JSON. Invalid JSON is rejected. Occurrence
 * time is truncated to PostgreSQL's microsecond precision at this boundary.</p>
 */
public record OutboxEvent(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        Instant occurredAt) {

    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId");
        aggregateType = requireNonBlank(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        eventType = requireNonBlank(eventType, "eventType");
        payload = CanonicalJson.canonicalize(payload);
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
