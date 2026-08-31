package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JdbcOutboxStore implements OutboxStore {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOutboxStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void append(OutboxEvent event) {
        Objects.requireNonNull(event, "event");
        jdbcTemplate.update("""
                        INSERT INTO outbox_events (
                            event_id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
                        VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                        """,
                event.eventId(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.payload(),
                timestamp(event.occurredAt()));
    }

    @Override
    public List<OutboxEvent> loadUnpublished(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbcTemplate.query("""
                        SELECT event_id, aggregate_type, aggregate_id, event_type,
                               payload::text AS payload, occurred_at
                        FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY occurred_at, event_id
                        LIMIT ?
                        """,
                JdbcOutboxStore::mapEvent,
                limit);
    }

    @Override
    public void markPublished(UUID eventId, Instant publishedAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(publishedAt, "publishedAt");
        jdbcTemplate.update("""
                        UPDATE outbox_events
                        SET published_at = ?
                        WHERE event_id = ? AND published_at IS NULL
                        """,
                timestamp(publishedAt.truncatedTo(ChronoUnit.MICROS)), eventId);
    }

    private static OutboxEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OutboxEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("aggregate_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getString("payload"),
                resultSet.getTimestamp("occurred_at").toInstant());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
