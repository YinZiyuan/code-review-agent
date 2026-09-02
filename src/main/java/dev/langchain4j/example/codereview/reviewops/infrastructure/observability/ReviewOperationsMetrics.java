package dev.langchain4j.example.codereview.reviewops.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caches bounded aggregate database observations so a metrics scrape never waits for PostgreSQL.
 */
public final class ReviewOperationsMetrics {

    private static final List<String> JOB_STATES =
            List.of("READY", "LEASED", "SUCCEEDED", "DEAD");
    private static final List<String> RUN_STATES = List.of(
            "REQUESTED", "RUNNING", "COMPLETED", "PUBLISHING",
            "PUBLISHED", "FAILED", "SUPERSEDED");
    private static final List<String> STALE_STATES = List.of("RUNNING", "PUBLISHING");
    private static final List<String> PUBLICATION_TIERS =
            List.of("INLINE_COMMENT", "CHECK_SUMMARY", "RETAIN_ONLY");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration staleThreshold;
    private final Map<String, AtomicLong> queueDepth = new LinkedHashMap<>();
    private final Map<String, AtomicLong> runCount = new LinkedHashMap<>();
    private final Map<String, AtomicLong> staleRunCount = new LinkedHashMap<>();
    private final Map<String, AtomicLong> publicationCount = new LinkedHashMap<>();
    private final AtomicLong oldestReadyAgeSeconds = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong confirmedComments = new AtomicLong();
    private final AtomicLong unpublishedOutbox = new AtomicLong();

    public ReviewOperationsMetrics(
            JdbcTemplate jdbc,
            MeterRegistry registry,
            Clock clock,
            Duration staleThreshold) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.staleThreshold = requirePositive(staleThreshold, "staleThreshold");
        registerStates(registry, "code_review_queue_depth", "state", JOB_STATES, queueDepth);
        registerStates(registry, "code_review_runs", "state", RUN_STATES, runCount);
        registerStates(registry, "code_review_stale_runs", "state", STALE_STATES, staleRunCount);
        registerStates(
                registry, "code_review_publication_findings", "tier",
                PUBLICATION_TIERS, publicationCount);
        register(registry, "code_review_queue_oldest_ready_age_seconds", oldestReadyAgeSeconds);
        register(registry, "code_review_tokens", inputTokens, "direction", "input");
        register(registry, "code_review_tokens", outputTokens, "direction", "output");
        register(registry, "code_review_publication_comments_confirmed", confirmedComments);
        register(registry, "code_review_outbox_depth", unpublishedOutbox, "state", "unpublished");
    }

    public void refresh() {
        refreshGrouped("SELECT state, count(*) AS value FROM durable_jobs GROUP BY state", queueDepth);
        refreshGrouped("SELECT state, count(*) AS value FROM review_runs GROUP BY state", runCount);
        refreshStaleRuns();
        refreshGrouped("""
                SELECT publication_tier AS state, count(*) AS value
                FROM review_findings
                WHERE publication_tier IS NOT NULL
                GROUP BY publication_tier
                """, publicationCount);
        Timestamp oldestReady = jdbc.query(
                "SELECT min(next_attempt_at) FROM durable_jobs WHERE state = 'READY'",
                resultSet -> resultSet.next() ? resultSet.getTimestamp(1) : null);
        oldestReadyAgeSeconds.set(oldestReady == null ? 0 : Math.max(0,
                Duration.between(oldestReady.toInstant(), clock.instant()).toSeconds()));
        Map<String, Object> tokenTotals = jdbc.queryForMap("""
                SELECT coalesce(sum(input_tokens), 0) AS input,
                       coalesce(sum(output_tokens), 0) AS output
                FROM review_attempts
                WHERE input_tokens IS NOT NULL AND output_tokens IS NOT NULL
                """);
        inputTokens.set(number(tokenTotals.get("input")));
        outputTokens.set(number(tokenTotals.get("output")));
        confirmedComments.set(jdbc.queryForObject("""
                SELECT count(*) FROM review_findings
                WHERE publication_tier = 'INLINE_COMMENT'
                  AND artifact_type = 'REVIEW_COMMENT'
                  AND artifact_external_id IS NOT NULL
                """, Long.class));
        unpublishedOutbox.set(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class));
    }

    private void refreshStaleRuns() {
        Map<String, Long> observed = new LinkedHashMap<>();
        jdbc.queryForList("""
                        SELECT state, count(*) AS value
                        FROM review_runs
                        WHERE state IN ('RUNNING', 'PUBLISHING') AND requested_at < ?
                        GROUP BY state
                        """,
                Timestamp.from(clock.instant().minus(staleThreshold)))
                .forEach(row -> observed.put(
                        row.get("state").toString(), number(row.get("value"))));
        replaceValues(staleRunCount, observed);
    }

    private void refreshGrouped(String sql, Map<String, AtomicLong> values) {
        Map<String, Long> observed = new LinkedHashMap<>();
        jdbc.queryForList(sql).forEach(row -> observed.put(
                row.get("state").toString(), number(row.get("value"))));
        replaceValues(values, observed);
    }

    private static void replaceValues(
            Map<String, AtomicLong> targets,
            Map<String, Long> observed) {
        targets.forEach((state, value) -> value.set(observed.getOrDefault(state, 0L)));
    }

    private static void registerStates(
            MeterRegistry registry,
            String name,
            String tagName,
            List<String> states,
            Map<String, AtomicLong> values) {
        states.forEach(state -> {
            AtomicLong value = new AtomicLong();
            values.put(state, value);
            register(registry, name, value, tagName, state);
        });
    }

    private static void register(
            MeterRegistry registry,
            String name,
            AtomicLong value,
            String... tags) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .tags(tags)
                .register(registry);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
