package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Bounded deletion of audit rows whose durable effect is already terminal. */
public final class PostgresReviewOperationsRetention {

    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final Clock clock;

    public PostgresReviewOperationsRetention(
            JdbcTemplate jdbc,
            TransactionOperations transactions,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PurgeResult purge(Duration retentionAge, int batchSize) {
        Objects.requireNonNull(retentionAge, "retentionAge");
        if (retentionAge.isZero() || retentionAge.isNegative()) {
            throw new IllegalArgumentException("retentionAge must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        Instant cutoff = clock.instant().minus(retentionAge);
        return Objects.requireNonNull(transactions.execute(status -> new PurgeResult(
                purgeTerminalJobs(cutoff, batchSize),
                purgePublishedOutbox(cutoff, batchSize),
                purgeHandledDeliveries(cutoff, batchSize))), "transaction result");
    }

    private int purgeTerminalJobs(Instant cutoff, int batchSize) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT id FROM durable_jobs
                    WHERE state IN ('SUCCEEDED', 'DEAD') AND updated_at < ?
                    ORDER BY updated_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                DELETE FROM durable_jobs job
                USING expired
                WHERE job.id = expired.id
                """, Timestamp.from(cutoff), batchSize);
    }

    private int purgePublishedOutbox(Instant cutoff, int batchSize) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT event_id FROM outbox_events
                    WHERE published_at IS NOT NULL AND published_at < ?
                    ORDER BY published_at, event_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                DELETE FROM outbox_events event
                USING expired
                WHERE event.event_id = expired.event_id
                """, Timestamp.from(cutoff), batchSize);
    }

    private int purgeHandledDeliveries(Instant cutoff, int batchSize) {
        return jdbc.update("""
                WITH expired AS (
                    SELECT delivery_id FROM github_deliveries
                    WHERE handled_at IS NOT NULL AND handled_at < ?
                    ORDER BY handled_at, delivery_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                DELETE FROM github_deliveries delivery
                USING expired
                WHERE delivery.delivery_id = expired.delivery_id
                """, Timestamp.from(cutoff), batchSize);
    }

    public record PurgeResult(int jobs, int outboxEvents, int deliveries) {
    }
}
