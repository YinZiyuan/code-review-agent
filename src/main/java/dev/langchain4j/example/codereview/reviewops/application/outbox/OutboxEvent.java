package dev.langchain4j.example.codereview.reviewops.application.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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
        payload = requireNonBlank(payload, "payload");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
