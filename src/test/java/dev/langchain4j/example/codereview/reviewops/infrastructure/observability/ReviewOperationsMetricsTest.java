package dev.langchain4j.example.codereview.reviewops.infrastructure.observability;

import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.PostgresIntegrationSupport;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewOperationsMetricsTest extends PostgresIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

    private JdbcTemplate jdbc;
    private SimpleMeterRegistry registry;
    private ReviewOperationsMetrics metrics;

    @BeforeEach
    void setUpMetrics() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE github_deliveries, outbox_events, durable_jobs, review_runs CASCADE");
        jdbc.execute("TRUNCATE review_operations_metric_delta");
        jdbc.update("UPDATE review_operations_metric_rollup SET metric_value = 0");
        registry = new SimpleMeterRegistry();
        metrics = new ReviewOperationsMetrics(
                jdbc, registry, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15));
    }

    @Test
    void refreshesLowCardinalityQueueAggregateStaleTokenAndPublicationGauges() {
        insertJob("READY", NOW.minusSeconds(90));
        insertJob("LEASED", NOW.minusSeconds(30));
        insertJob("DEAD", NOW.minusSeconds(20));
        UUID staleRunning = insertRun("RUNNING", NOW.minus(Duration.ofMinutes(20)));
        UUID freshPublishing = insertRun("PUBLISHING", NOW.minus(Duration.ofMinutes(5)));
        insertAttempt(staleRunning, 13, 8);
        insertFinding(staleRunning, "INLINE_COMMENT", true);
        insertFinding(freshPublishing, "CHECK_SUMMARY", false);
        insertUnpublishedOutbox();

        assertThat(jdbc.queryForList("SELECT state FROM durable_jobs ORDER BY state", String.class))
                .containsExactly("DEAD", "LEASED", "READY");

        metrics.refresh();

        assertThat(gauge("code_review_queue_depth", "state", "READY")).isEqualTo(1);
        assertThat(gauge("code_review_queue_depth", "state", "LEASED")).isEqualTo(1);
        assertThat(gauge("code_review_queue_depth", "state", "DEAD")).isEqualTo(1);
        assertThat(gauge("code_review_queue_oldest_ready_age_seconds")).isEqualTo(90);
        assertThat(gauge("code_review_runs", "state", "RUNNING")).isEqualTo(1);
        assertThat(gauge("code_review_runs", "state", "PUBLISHING")).isEqualTo(1);
        assertThat(gauge("code_review_stale_runs", "state", "RUNNING")).isEqualTo(1);
        assertThat(gauge("code_review_stale_runs", "state", "PUBLISHING")).isZero();
        assertThat(gauge("code_review_retained_history_tokens", "direction", "input")).isEqualTo(13);
        assertThat(gauge("code_review_retained_history_tokens", "direction", "output")).isEqualTo(8);
        assertThat(gauge("code_review_publication_findings", "tier", "INLINE_COMMENT")).isEqualTo(1);
        assertThat(gauge("code_review_publication_findings", "tier", "CHECK_SUMMARY")).isEqualTo(1);
        assertThat(gauge("code_review_publication_comments_confirmed")).isEqualTo(1);
        assertThat(gauge("code_review_outbox_depth", "state", "unpublished")).isEqualTo(1);
    }

    @Test
    void clearsDisappearedStateAndNeverUsesIdentifiersAsMetricTags() {
        insertJob("DEAD", NOW.minusSeconds(20));
        metrics.refresh();
        jdbc.execute("DELETE FROM durable_jobs");

        metrics.refresh();

        assertThat(gauge("code_review_queue_depth", "state", "DEAD")).isZero();
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .doesNotContain(
                        "delivery_id", "review_run_id", "repository_id", "pull_request_number",
                        "head_sha", "job_id", "pipeline_version", "configuration_version");
        assertThat(registry.getMeters())
                .extracting(Meter::getId)
                .extracting(Meter.Id::getType)
                .containsOnly(Meter.Type.GAUGE);
    }

    @Test
    void staleGaugesMeasureTimeInCurrentStateInsteadOfAgeOfTheRun() {
        UUID oldRunJustStarted = insertRun("REQUESTED", NOW.minus(Duration.ofHours(2)));
        jdbc.update("UPDATE review_runs SET state = 'RUNNING' WHERE id = ?", oldRunJustStarted);
        setStateEnteredAt(oldRunJustStarted, NOW.minus(Duration.ofMinutes(5)));

        UUID runStrandedInRunning = insertRun("RUNNING", NOW.minus(Duration.ofMinutes(20)));
        setStateEnteredAt(runStrandedInRunning, NOW.minus(Duration.ofMinutes(20)));

        UUID oldRunJustPublishing = insertRun("RUNNING", NOW.minus(Duration.ofHours(1)));
        jdbc.update("UPDATE review_runs SET state = 'PUBLISHING' WHERE id = ?", oldRunJustPublishing);
        setStateEnteredAt(oldRunJustPublishing, NOW.minus(Duration.ofMinutes(2)));

        metrics.refresh();

        assertThat(gauge("code_review_stale_runs", "state", "RUNNING")).isEqualTo(1);
        assertThat(gauge("code_review_stale_runs", "state", "PUBLISHING")).isZero();
    }

    @Test
    void failedRefreshDoesNotPublishAPartialSnapshot() {
        insertJob("READY", NOW.minusSeconds(30));
        metrics.refresh();
        assertThat(gauge("code_review_queue_depth", "state", "READY")).isEqualTo(1);

        insertJob("READY", NOW.minusSeconds(20));
        jdbc.execute("ALTER TABLE review_runs RENAME TO review_runs_unavailable");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(metrics::refresh)
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(gauge("code_review_queue_depth", "state", "READY")).isEqualTo(1);
        } finally {
            jdbc.execute("ALTER TABLE review_runs_unavailable RENAME TO review_runs");
        }
    }

    @Test
    void retainedHistoryMetricsStayCompactAsAuditRowsGrow() {
        UUID runId = insertRun("COMPLETED", NOW.minusSeconds(10));
        for (int attempt = 1; attempt <= 100; attempt++) {
            insertAttempt(runId, attempt, 2, 3);
        }

        metrics.refresh();

        assertThat(gauge("code_review_retained_history_tokens", "direction", "input"))
                .isEqualTo(200);
        assertThat(gauge("code_review_retained_history_tokens", "direction", "output"))
                .isEqualTo(300);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM review_operations_metric_rollup", Integer.class))
                .isLessThanOrEqualTo(24);
    }

    private UUID insertRun(String state, Instant requestedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO review_runs (
                            id, installation_id, repository_id, pull_request_number, head_sha,
                            pipeline_version, configuration_version, model_name, policy_version,
                            max_review_attempts, requested_at, state)
                        VALUES (?, 1, 2, 3, ?, 'pipeline-v3', ?, 'model', 'policy-v1', 3, ?, ?)
                        """,
                id, "a".repeat(40), "configuration-" + id,
                Timestamp.from(requestedAt), state);
        return id;
    }

    private void insertJob(String state, Instant scheduledAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO durable_jobs (
                            id, job_type, payload_reference, state, attempt_count, max_attempts,
                            next_attempt_at, initial_next_attempt_at, idempotency_key,
                            created_at, updated_at)
                        VALUES (?, 'REVIEW_EXECUTION', ?, ?, 0, 3, ?, ?, ?, ?, ?)
                        """,
                id, UUID.randomUUID(), state,
                Timestamp.from(scheduledAt), Timestamp.from(scheduledAt), "job-" + id,
                Timestamp.from(scheduledAt), Timestamp.from(scheduledAt));
    }

    private void insertAttempt(UUID runId, int inputTokens, int outputTokens) {
        insertAttempt(runId, 1, inputTokens, outputTokens);
    }

    private void insertAttempt(UUID runId, int attempt, int inputTokens, int outputTokens) {
        jdbc.update("""
                        INSERT INTO review_attempts (
                            review_run_id, attempt_number, state, started_at, ended_at, latency_ms,
                            input_tokens, output_tokens, tool_states)
                        VALUES (?, ?, 'SUCCEEDED', ?, ?, 10, ?, ?, '{}'::jsonb)
                        """,
                runId, attempt,
                Timestamp.from(NOW.minusSeconds(2)), Timestamp.from(NOW.minusSeconds(1)),
                inputTokens, outputTokens);
    }

    private void setStateEnteredAt(UUID runId, Instant enteredAt) {
        jdbc.update("UPDATE review_runs SET state_entered_at = ? WHERE id = ?",
                Timestamp.from(enteredAt), runId);
    }

    private void insertFinding(UUID runId, String tier, boolean confirmedComment) {
        String fingerprint = java.util.HexFormat.of().formatHex(
                java.nio.ByteBuffer.allocate(32)
                        .putLong(UUID.randomUUID().getMostSignificantBits())
                        .putLong(UUID.randomUUID().getLeastSignificantBits())
                        .array());
        fingerprint = (fingerprint + "0".repeat(64)).substring(0, 64);
        jdbc.update("""
                        INSERT INTO review_findings (
                            review_run_id, fingerprint, file_path, post_change_line, changed_line,
                            severity, category, title, description, suggestion, evidence, source,
                            publication_tier, publication_policy_version,
                            artifact_type, artifact_external_id)
                        VALUES (?, ?, 'src/Test.java', 1, true, 'WARNING', 'STABILITY',
                                'title', 'description', 'suggestion', 'evidence', 'test',
                                ?, 'policy-v1', ?, ?)
                        """,
                runId, fingerprint, tier,
                confirmedComment ? "REVIEW_COMMENT" : null,
                confirmedComment ? "comment-1" : null);
    }

    private void insertUnpublishedOutbox() {
        jdbc.update("""
                        INSERT INTO outbox_events (
                            event_id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
                        VALUES (?, 'ReviewRun', ?, 'ReviewRequested', '{}'::jsonb, ?)
                        """, UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(NOW));
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }
}
