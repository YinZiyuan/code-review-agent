package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresReviewOperationsRetentionTest extends PostgresIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofDays(31));
    private static final Instant RECENT = NOW.minus(Duration.ofDays(2));

    private JdbcTemplate jdbc;
    private PostgresReviewOperationsRetention retention;

    @BeforeEach
    void setUpRetention() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE github_deliveries, outbox_events, durable_jobs, review_runs CASCADE");
        retention = new PostgresReviewOperationsRetention(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void purgesOnlyOldTerminalJobsPublishedOutboxAndHandledDeliveries() {
        insertJob("old-succeeded", "SUCCEEDED", OLD);
        insertJob("old-dead", "DEAD", OLD);
        insertJob("old-ready", "READY", OLD);
        insertJob("recent-succeeded", "SUCCEEDED", RECENT);
        insertOutbox("old-published", OLD);
        insertOutbox("old-unpublished", null);
        insertOutbox("recent-published", RECENT);
        insertDelivery("old-handled", OLD);
        insertDelivery("old-unhandled", null);
        insertDelivery("recent-handled", RECENT);

        PostgresReviewOperationsRetention.PurgeResult result =
                retention.purge(Duration.ofDays(30), 10);

        assertThat(result).isEqualTo(
                new PostgresReviewOperationsRetention.PurgeResult(2, 1, 1));
        assertThat(values("durable_jobs", "idempotency_key"))
                .containsExactlyInAnyOrder("old-ready", "recent-succeeded");
        assertThat(values("outbox_events", "event_type"))
                .containsExactlyInAnyOrder("old-unpublished", "recent-published");
        assertThat(values("github_deliveries", "delivery_id"))
                .containsExactlyInAnyOrder("old-unhandled", "recent-handled");
    }

    @Test
    void limitsEachPurgeBatchAndRejectsUnsafeArguments() {
        insertJob("terminal-1", "DEAD", OLD);
        insertJob("terminal-2", "DEAD", OLD);
        insertJob("terminal-3", "DEAD", OLD);

        PostgresReviewOperationsRetention.PurgeResult first =
                retention.purge(Duration.ofDays(30), 2);
        PostgresReviewOperationsRetention.PurgeResult second =
                retention.purge(Duration.ofDays(30), 2);

        assertThat(first.jobs()).isEqualTo(2);
        assertThat(second.jobs()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> retention.purge(Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> retention.purge(Duration.ofDays(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void insertJob(String key, String state, Instant updatedAt) {
        jdbc.update("""
                        INSERT INTO durable_jobs (
                            id, job_type, payload_reference, state, attempt_count, max_attempts,
                            next_attempt_at, initial_next_attempt_at, idempotency_key,
                            created_at, updated_at)
                        VALUES (?, 'REVIEW_EXECUTION', ?, ?, 0, 3, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), UUID.randomUUID(), state,
                Timestamp.from(updatedAt), Timestamp.from(updatedAt), key,
                Timestamp.from(updatedAt), Timestamp.from(updatedAt));
    }

    private void insertOutbox(String eventType, Instant publishedAt) {
        jdbc.update("""
                        INSERT INTO outbox_events (
                            event_id, aggregate_type, aggregate_id, event_type,
                            payload, occurred_at, published_at)
                        VALUES (?, 'ReviewRun', ?, ?, '{}'::jsonb, ?, ?)
                        """,
                UUID.randomUUID(), UUID.randomUUID(), eventType,
                Timestamp.from(OLD), publishedAt == null ? null : Timestamp.from(publishedAt));
    }

    private void insertDelivery(String deliveryId, Instant handledAt) {
        jdbc.update("""
                        INSERT INTO github_deliveries (
                            delivery_id, event_name, payload_sha256, received_at, handled_at)
                        VALUES (?, 'pull_request', ?, ?, ?)
                        """,
                deliveryId, "a".repeat(64), Timestamp.from(OLD),
                handledAt == null ? null : Timestamp.from(handledAt));
    }

    private java.util.List<String> values(String table, String column) {
        return jdbc.queryForList("SELECT " + column + " FROM " + table, String.class);
    }
}
