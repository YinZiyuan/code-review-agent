package dev.langchain4j.example.codereview.reviewops.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

/**
 * Publishes an atomic cache built from fixed-cardinality database rollups and indexed age probes.
 * A scrape performs no database work, and a failed refresh leaves the entire prior snapshot intact.
 */
public final class ReviewOperationsMetrics {

    private static final int MAX_TRANSITION_DELTAS_PER_REFRESH = 10_000;

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
    private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.empty());

    public ReviewOperationsMetrics(
            JdbcTemplate jdbc,
            MeterRegistry registry,
            Clock clock,
            Duration staleThreshold) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.staleThreshold = requirePositive(staleThreshold, "staleThreshold");

        registerStates(registry, "code_review_queue_depth", "state", JOB_STATES,
                snapshot -> snapshot.queueDepth);
        registerStates(registry, "code_review_runs", "state", RUN_STATES,
                snapshot -> snapshot.runCount);
        registerStates(registry, "code_review_stale_runs", "state", STALE_STATES,
                snapshot -> snapshot.staleRunCount);
        registerStates(registry, "code_review_publication_findings", "tier",
                PUBLICATION_TIERS, snapshot -> snapshot.publicationCount);
        register(registry, "code_review_queue_oldest_ready_age_seconds",
                snapshot -> snapshot.oldestReadyAgeSeconds);
        // This gauge is the total still retained in database audit history. Stream B owns
        // process-lifetime token counters; the distinct name makes restart/retention semantics clear.
        register(registry, "code_review_retained_history_tokens",
                snapshot -> snapshot.inputTokens, "direction", "input");
        register(registry, "code_review_retained_history_tokens",
                snapshot -> snapshot.outputTokens, "direction", "output");
        register(registry, "code_review_publication_comments_confirmed",
                snapshot -> snapshot.confirmedComments);
        register(registry, "code_review_outbox_depth",
                snapshot -> snapshot.unpublishedOutbox, "state", "unpublished");
    }

    public void refresh() {
        jdbc.queryForObject("SELECT review_metric_flush(?)", Integer.class,
                MAX_TRANSITION_DELTAS_PER_REFRESH);
        Map<RollupKey, Long> rollups = new HashMap<>();
        jdbc.queryForList("""
                        SELECT metric_name, dimension_value, metric_value
                        FROM review_operations_metric_rollup
                        """)
                .forEach(row -> rollups.put(
                        new RollupKey(row.get("metric_name").toString(),
                                row.get("dimension_value").toString()),
                        number(row.get("metric_value"))));

        Timestamp oldestReady = jdbc.queryForObject(
                "SELECT min(next_attempt_at) FROM durable_jobs WHERE state = 'READY'",
                Timestamp.class);

        Map<String, Long> staleRuns = new LinkedHashMap<>();
        jdbc.queryForList("""
                        SELECT state, count(*) AS metric_value
                        FROM review_runs
                        WHERE state IN ('RUNNING', 'PUBLISHING') AND state_entered_at < ?
                        GROUP BY state
                        """,
                Timestamp.from(clock.instant().minus(staleThreshold)))
                .forEach(row -> staleRuns.put(
                        row.get("state").toString(), number(row.get("metric_value"))));

        long readyAgeSeconds = oldestReady == null ? 0 : Math.max(0,
                Duration.between(oldestReady.toInstant(), clock.instant()).toSeconds());
        Snapshot replacement = new Snapshot(
                dimensions(rollups, "durable_jobs", JOB_STATES),
                dimensions(rollups, "review_runs", RUN_STATES),
                dimensions(staleRuns, STALE_STATES),
                dimensions(rollups, "publication_findings", PUBLICATION_TIERS),
                readyAgeSeconds,
                rollup(rollups, "retained_history_tokens", "input"),
                rollup(rollups, "retained_history_tokens", "output"),
                rollup(rollups, "publication_comments", "confirmed"),
                rollup(rollups, "outbox", "unpublished"));
        current.set(replacement);
    }

    private void registerStates(
            MeterRegistry registry,
            String name,
            String tagName,
            List<String> states,
            java.util.function.Function<Snapshot, Map<String, Long>> values) {
        states.forEach(state -> register(
                registry, name,
                snapshot -> values.apply(snapshot).getOrDefault(state, 0L),
                tagName, state));
    }

    private void register(
            MeterRegistry registry,
            String name,
            ToDoubleFunction<Snapshot> value,
            String... tags) {
        Gauge.builder(name, current, reference -> value.applyAsDouble(reference.get()))
                .tags(tags)
                .register(registry);
    }

    private static Map<String, Long> dimensions(
            Map<RollupKey, Long> rollups,
            String metric,
            List<String> allowedDimensions) {
        Map<String, Long> values = new LinkedHashMap<>();
        allowedDimensions.forEach(dimension ->
                values.put(dimension, rollup(rollups, metric, dimension)));
        return Map.copyOf(values);
    }

    private static Map<String, Long> dimensions(
            Map<String, Long> observed,
            List<String> allowedDimensions) {
        Map<String, Long> values = new LinkedHashMap<>();
        allowedDimensions.forEach(dimension ->
                values.put(dimension, observed.getOrDefault(dimension, 0L)));
        return Map.copyOf(values);
    }

    private static long rollup(
            Map<RollupKey, Long> rollups,
            String metric,
            String dimension) {
        return rollups.getOrDefault(new RollupKey(metric, dimension), 0L);
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

    private record RollupKey(String metric, String dimension) {
    }

    private record Snapshot(
            Map<String, Long> queueDepth,
            Map<String, Long> runCount,
            Map<String, Long> staleRunCount,
            Map<String, Long> publicationCount,
            long oldestReadyAgeSeconds,
            long inputTokens,
            long outputTokens,
            long confirmedComments,
            long unpublishedOutbox) {

        private static Snapshot empty() {
            return new Snapshot(
                    Map.of(), Map.of(), Map.of(), Map.of(),
                    0, 0, 0, 0, 0);
        }
    }
}
