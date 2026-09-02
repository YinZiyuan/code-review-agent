package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.domain.DuplicateReviewRunException;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewOperationsMigrationTest extends PostgresIntegrationSupport {

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearReviewRuns() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("TRUNCATE TABLE review_runs CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE review_operations_metric_delta");
        jdbcTemplate.update("UPDATE review_operations_metric_rollup SET metric_value = 0");
    }

    @Test
    void migratesTheReviewOperationsSchemaWithItsBusinessKeysAndDeliveryIndexes() throws SQLException {
        assertThat(tableNames()).containsExactlyInAnyOrder(
                "github_deliveries",
                "review_runs",
                "review_attempts",
                "review_findings",
                "finding_feedback",
                "durable_jobs",
                "outbox_events",
                "review_operations_metric_rollup",
                "review_operations_metric_delta");
        assertThat(businessIdentityIndexDefinition(jdbcTemplate, "public"))
                .contains("UNIQUE INDEX")
                .contains("WHERE (state <> 'SUPERSEDED'::text)");
        assertThat(constraintColumns("review_attempts", "p"))
                .contains(List.of("review_run_id", "attempt_number"));
        assertThat(constraintColumns("review_findings", "p"))
                .contains(List.of("review_run_id", "fingerprint"));
        assertThat(constraintColumns("github_deliveries", "f"))
                .contains(List.of("review_run_id"));
        assertThat(constraintColumns("durable_jobs", "u"))
                .contains(List.of("idempotency_key"));
        assertThat(unpublishedOutboxIndexExists()).isTrue();
        assertThat(unpublishedOutboxIndexColumns())
                .containsExactly("occurred_at", "event_id");
        assertThat(indexColumns("github_deliveries", "idx_github_deliveries_received_at"))
                .containsExactly("received_at");
        assertThat(indexColumns("durable_jobs", "idx_durable_jobs_expired_lease"))
                .containsExactly("lease_expires_at", "id");
        assertThat(indexDefinition("idx_durable_jobs_expired_lease"))
                .contains("WHERE (state = 'LEASED'::text)");
        assertThat(indexColumns("durable_jobs", "idx_durable_jobs_terminal_retention"))
                .containsExactly("updated_at", "id");
        assertThat(indexColumns("outbox_events", "idx_outbox_events_published_retention"))
                .containsExactly("published_at", "event_id");
        assertThat(indexColumns("github_deliveries", "idx_github_deliveries_handled_retention"))
                .containsExactly("handled_at", "delivery_id");
        assertThat(indexColumns("review_runs", "idx_review_runs_active_state_entry"))
                .containsExactly("state", "state_entered_at");
        assertThat(indexDefinition("idx_review_runs_active_state_entry"))
                .contains("WHERE (state = ANY (ARRAY['RUNNING'::text, 'PUBLISHING'::text]))");
    }

    @Test
    void rerunningFlywayOnTheMigratedDatabaseExecutesNoMigrations() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void v8DeclaresNonTransactionalConcurrentRestartSafeIndexBuilds() throws Exception {
        String script = classpathText(
                "db/migration/V8__bound_review_operations_maintenance.sql");
        String scriptConfiguration = classpathText(
                "db/migration/V8__bound_review_operations_maintenance.sql.conf");

        assertThat(scriptConfiguration).contains("executeInTransaction=false");
        assertThat(script)
                .contains("SET statement_timeout")
                .contains("SET lock_timeout")
                .contains("DROP INDEX CONCURRENTLY IF EXISTS")
                .contains("CREATE INDEX CONCURRENTLY");
        assertThat(indexesAreValid("public", Set.of(
                "idx_durable_jobs_expired_lease",
                "idx_durable_jobs_terminal_retention",
                "idx_outbox_events_published_retention",
                "idx_github_deliveries_handled_retention")))
                .as("public index state: %s", indexState("public"))
                .isTrue();
    }

    @Test
    void v9MaintainsAuthoritativeRunStateEntryTimeAndCompactRollups() throws Exception {
        UUID runId = UUID.randomUUID();
        Instant requestedAt = Instant.parse("2026-09-02T01:00:00Z");
        jdbcTemplate.update("""
                        INSERT INTO review_runs (
                            id, installation_id, repository_id, pull_request_number, head_sha,
                            pipeline_version, configuration_version, model_name, policy_version,
                            max_review_attempts, requested_at, state)
                        VALUES (?, 1, 2, 3, ?, 'pipeline-v3', ?, 'model', 'policy-v1', 3, ?, 'REQUESTED')
                        """,
                runId, "a".repeat(40), "configuration-" + runId,
                Timestamp.from(requestedAt));

        Instant requestedStateEntry = jdbcTemplate.queryForObject(
                "SELECT state_entered_at FROM review_runs WHERE id = ?",
                Timestamp.class, runId).toInstant();
        assertThat(requestedStateEntry).isEqualTo(requestedAt);
        assertThat(metricRollup("review_runs", "REQUESTED")).isEqualTo(1);

        jdbcTemplate.update("UPDATE review_runs SET model_name = 'model-v2' WHERE id = ?", runId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state_entered_at FROM review_runs WHERE id = ?",
                Timestamp.class, runId).toInstant()).isEqualTo(requestedStateEntry);

        Instant beforeTransition = Instant.now();
        jdbcTemplate.update("UPDATE review_runs SET state = 'RUNNING' WHERE id = ?", runId);
        Instant runningStateEntry = jdbcTemplate.queryForObject(
                "SELECT state_entered_at FROM review_runs WHERE id = ?",
                Timestamp.class, runId).toInstant();
        assertThat(runningStateEntry)
                .isBetween(beforeTransition.minusMillis(100), Instant.now().plusMillis(100));
        assertThat(metricRollup("review_runs", "REQUESTED")).isZero();
        assertThat(metricRollup("review_runs", "RUNNING")).isEqualTo(1);

        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO durable_jobs (
                            id, job_type, payload_reference, state, attempt_count, max_attempts,
                            next_attempt_at, initial_next_attempt_at, idempotency_key,
                            created_at, updated_at)
                        VALUES (?, 'REVIEW_EXECUTION', ?, 'READY', 0, 3, now(), now(), ?, now(), now())
                        """,
                jobId, runId, "rollup-job-" + jobId);
        assertThat(metricRollup("durable_jobs", "READY")).isEqualTo(1);
        jdbcTemplate.update("UPDATE durable_jobs SET state = 'DEAD' WHERE id = ?", jobId);
        assertThat(metricRollup("durable_jobs", "READY")).isZero();
        assertThat(metricRollup("durable_jobs", "DEAD")).isEqualTo(1);
        jdbcTemplate.update("DELETE FROM durable_jobs WHERE id = ?", jobId);
        assertThat(metricRollup("durable_jobs", "DEAD")).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_operations_metric_rollup", Integer.class))
                .isLessThanOrEqualTo(24);
    }

    @Test
    void activeStateAgeProbeUsesItsPartialIndexWithPopulatedHistory() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO review_runs (
                    id, installation_id, repository_id, pull_request_number, head_sha,
                    pipeline_version, configuration_version, model_name, policy_version,
                    max_review_attempts, requested_at, state)
                SELECT ('00000000-0000-0000-0000-' || lpad(sequence::text, 12, '0'))::uuid,
                       1, 2, sequence, md5(sequence::text), 'pipeline-v3',
                       'configuration-' || sequence, 'model', 'policy-v1', 3,
                       now() - interval '1 hour', 'RUNNING'
                FROM generate_series(1, 2000) AS sequence
                """);
        jdbcTemplate.execute("ANALYZE review_runs");

        String plan;
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("SET enable_seqscan = off");
            try (var rows = statement.executeQuery("""
                    EXPLAIN (COSTS OFF)
                    SELECT state, count(*)
                    FROM review_runs
                    WHERE state IN ('RUNNING', 'PUBLISHING')
                      AND state_entered_at < now() - interval '15 minutes'
                    GROUP BY state
                    """)) {
                StringBuilder rendered = new StringBuilder();
                while (rows.next()) {
                    rendered.append(rows.getString(1)).append('\n');
                }
                plan = rendered.toString();
            }
        }

        assertThat(plan).contains("idx_review_runs_active_state_entry");
        assertThat(metricRollup("review_runs", "RUNNING")).isEqualTo(2_000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_operations_metric_rollup", Integer.class))
                .isLessThanOrEqualTo(24);
    }

    @Test
    void metricDeltaFoldClaimsOnlyTheRequestedBoundedBatch() {
        jdbcTemplate.update("""
                INSERT INTO review_operations_metric_delta (
                    metric_name, dimension_value, metric_delta)
                SELECT 'review_runs', 'REQUESTED', 1
                FROM generate_series(1, 10005)
                """);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_metric_flush(10000)", Integer.class)).isEqualTo(10_000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_operations_metric_delta", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT metric_value FROM review_operations_metric_rollup
                WHERE metric_name = 'review_runs' AND dimension_value = 'REQUESTED'
                """, Long.class)).isEqualTo(10_000);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_metric_flush(10000)", Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_operations_metric_delta", Integer.class)).isZero();
    }

    @Test
    void v8OnlineUpgradeLetsANewWriterFinishWhileAnOlderWriterRemainsOpen() throws Exception {
        String schema = "online_v8_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try {
            var isolatedDataSource = isolatedDataSource("online-v8-writer-test");
            Flyway v7 = flyway(isolatedDataSource, schema, "7");
            assertThat(v7.migrate().migrationsExecuted).isEqualTo(7);
            JdbcTemplate isolated = new JdbcTemplate(isolatedDataSource);
            populateMaintenanceTables(isolated, schema, 2_000);

            try (var olderWriter = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                olderWriter.setAutoCommit(false);
                olderWriter.createStatement().execute("SET search_path TO " + schema);
                olderWriter.createStatement().execute(
                        "LOCK TABLE durable_jobs IN ROW EXCLUSIVE MODE");

                Flyway latest = flyway(isolatedDataSource, schema, "8");
                CompletableFuture<Integer> migration = CompletableFuture.supplyAsync(
                        () -> latest.migrate().migrationsExecuted);
                awaitMigrationStart(migration);

                assertThat(org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                        Duration.ofSeconds(2),
                        () -> isolated.update("""
                                INSERT INTO durable_jobs (
                                    id, job_type, payload_reference, state, attempt_count,
                                    max_attempts, next_attempt_at, initial_next_attempt_at,
                                    idempotency_key, created_at, updated_at)
                                VALUES (?, 'REVIEW_EXECUTION', ?, 'READY', 0, 3,
                                        now(), now(), ?, now(), now())
                                """,
                                UUID.randomUUID(), UUID.randomUUID(),
                                "writer-during-online-index-" + UUID.randomUUID())))
                        .isEqualTo(1);

                olderWriter.commit();
                assertThat(migration.get(15, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(indexesAreValid(schema, Set.of(
                        "idx_durable_jobs_expired_lease",
                        "idx_durable_jobs_terminal_retention",
                        "idx_outbox_events_published_retention",
                        "idx_github_deliveries_handled_retention"))).isTrue();
            }
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void failedV8LockAcquisitionCanBeRepairedAndRetriedToValidIndexes() throws Exception {
        String schema = "retry_v8_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try {
            var isolatedDataSource = isolatedDataSource("retry-v8-test");
            Flyway v7 = flyway(isolatedDataSource, schema, "7");
            assertThat(v7.migrate().migrationsExecuted).isEqualTo(7);

            Flyway latest = flyway(isolatedDataSource, schema, "8");
            CompletableFuture<Integer> failedMigration;
            try (var blocker = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
                blocker.setAutoCommit(false);
                blocker.createStatement().execute("SET search_path TO " + schema);
                blocker.createStatement().execute("LOCK TABLE durable_jobs IN ACCESS EXCLUSIVE MODE");
                failedMigration = CompletableFuture.supplyAsync(
                        () -> latest.migrate().migrationsExecuted);

                assertThatThrownBy(() -> failedMigration.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(FlywayException.class)
                        .hasStackTraceContaining("lock timeout");
                blocker.rollback();
            }

            latest.repair();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertFlywayIsValid(latest);
            assertThat(indexesAreValid(schema, Set.of(
                    "idx_durable_jobs_expired_lease",
                    "idx_durable_jobs_terminal_retention",
                    "idx_outbox_events_published_retention",
                    "idx_github_deliveries_handled_retention"))).isTrue();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void v5PreservesPreexistingDeliveriesWithAnExplicitNullLegacyAssociation() throws Exception {
        String schema = "delivery_association_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            SingleConnectionDataSource isolatedDataSource =
                    new SingleConnectionDataSource(connection, true);
            Flyway v4 = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("4")
                    .load();
            assertThat(v4.migrate().migrationsExecuted).isEqualTo(4);

            JdbcTemplate isolated = new JdbcTemplate(isolatedDataSource);
            isolated.update("""
                            INSERT INTO github_deliveries (
                                delivery_id, event_name, payload_sha256, received_at, handled_at)
                            VALUES ('legacy-delivery', 'pull_request', ?, ?, ?)
                            """,
                    "a".repeat(64),
                    Timestamp.from(Instant.parse("2026-09-01T01:00:00Z")),
                    Timestamp.from(Instant.parse("2026-09-01T01:00:01Z")));

            Flyway latest = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);
            assertFlywayIsValid(latest);
            assertThat(isolated.queryForObject(
                    "SELECT review_run_id FROM github_deliveries WHERE delivery_id = 'legacy-delivery'",
                    UUID.class)).isNull();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void v6BackfillsTheMonotonicLeaseSequenceFromTheExistingAttemptCount() throws Exception {
        String schema = "lease_sequence_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            SingleConnectionDataSource isolatedDataSource =
                    new SingleConnectionDataSource(connection, true);
            Flyway v5 = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("5")
                    .load();
            assertThat(v5.migrate().migrationsExecuted).isEqualTo(5);

            JdbcTemplate isolated = new JdbcTemplate(isolatedDataSource);
            UUID jobId = UUID.randomUUID();
            Instant leasedAt = Instant.parse("2026-09-01T02:00:00Z");
            isolated.update("""
                            INSERT INTO durable_jobs (
                                id, job_type, payload_reference, state, attempt_count, max_attempts,
                                next_attempt_at, initial_next_attempt_at,
                                lease_owner, lease_expires_at, idempotency_key, created_at, updated_at)
                            VALUES (?, 'REVIEW_EXECUTION', ?, 'LEASED', 2, 3, ?, ?,
                                    'worker-a', ?, 'leased-before-v6', ?, ?)
                            """,
                    jobId,
                    UUID.randomUUID(),
                    Timestamp.from(leasedAt),
                    Timestamp.from(leasedAt),
                    Timestamp.from(leasedAt.plusSeconds(300)),
                    Timestamp.from(leasedAt.minusSeconds(60)),
                    Timestamp.from(leasedAt));

            Flyway latest = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(5);
            assertFlywayIsValid(latest);
            assertThat(isolated.queryForObject(
                    "SELECT lease_sequence FROM durable_jobs WHERE id = ?",
                    Integer.class,
                    jobId)).isEqualTo(2);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void upgradesADeployedV2SchemaWithoutChangingV1ChecksumAndRenamesTheBusinessConstraint()
            throws Exception {
        String schema = "deployed_v2_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            SingleConnectionDataSource isolatedDataSource =
                    new SingleConnectionDataSource(connection, true);

            // This fixture is byte-for-byte V1 from b8bb54d, the version that may already be deployed.
            Flyway deployedV1 = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:legacy-db/migration")
                    .load();
            assertThat(deployedV1.migrate().migrationsExecuted).isEqualTo(1);

            JdbcTemplate isolated = new JdbcTemplate(isolatedDataSource);
            assertThat(businessIdentityConstraintName(isolated, schema))
                    .isEqualTo("review_runs_installation_id_repository_id_pull_request_numb_key");

            Flyway deployedV2 = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("2")
                    .load();
            assertThat(deployedV2.migrate().migrationsExecuted).isEqualTo(1);
            assertFlywayIsValid(deployedV2);

            Flyway latest = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(8);
            assertFlywayIsValid(latest);
            assertThat(businessIdentityIndexDefinition(isolated, schema))
                    .contains("UNIQUE INDEX")
                    .contains("WHERE (state <> 'SUPERSEDED'::text)");

            JdbcReviewRunRepository repository = new JdbcReviewRunRepository(
                    isolated,
                    new TransactionTemplate(new DataSourceTransactionManager(isolatedDataSource)),
                    new JsonColumnCodec(new ObjectMapper()));
            ReviewRun original = requestedRun(ReviewRunId.newId());
            ReviewRun duplicate = requestedRun(ReviewRunId.newId());
            repository.insert(original);

            assertThatThrownBy(() -> repository.insert(duplicate))
                    .isInstanceOf(DuplicateReviewRunException.class);
            assertThat(repository.find(original.id())).isPresent();
            assertThat(repository.find(duplicate.id())).isEmpty();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void v2BackfillsAndRequiresTheInitialDurableJobSchedule() throws Exception {
        String schema = "job_intent_backfill_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            SingleConnectionDataSource isolatedDataSource =
                    new SingleConnectionDataSource(connection, true);
            Flyway v1 = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("1")
                    .load();
            v1.migrate();
            JdbcTemplate isolated = new JdbcTemplate(isolatedDataSource);
            UUID jobId = UUID.randomUUID();
            Instant originalSchedule = Instant.parse("2026-08-31T02:05:00Z");
            isolated.update("""
                            INSERT INTO durable_jobs (
                                id, job_type, payload_reference, state, attempt_count, max_attempts,
                                next_attempt_at, idempotency_key, created_at, updated_at)
                            VALUES (?, 'REVIEW_EXECUTION', ?, 'READY', 0, 3, ?, 'legacy-job', ?, ?)
                            """,
                    jobId,
                    UUID.randomUUID(),
                    Timestamp.from(originalSchedule),
                    Timestamp.from(originalSchedule.minusSeconds(1)),
                    Timestamp.from(originalSchedule.minusSeconds(1)));

            Flyway v2 = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("2")
                    .load();
            assertThat(v2.migrate().migrationsExecuted).isEqualTo(1);

            assertThat(isolated.queryForObject("""
                            SELECT initial_next_attempt_at
                            FROM durable_jobs WHERE id = ?
                            """, Timestamp.class, jobId).toInstant()).isEqualTo(originalSchedule);
            assertThat(isolated.queryForObject("""
                            SELECT is_nullable
                            FROM information_schema.columns
                            WHERE table_schema = ? AND table_name = 'durable_jobs'
                              AND column_name = 'initial_next_attempt_at'
                            """, String.class, schema)).isEqualTo("NO");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void v2RejectsLegacyJobsWhoseInitialScheduleCannotBeRecovered() throws Exception {
        String schema = "job_intent_unsafe_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            SingleConnectionDataSource isolatedDataSource =
                    new SingleConnectionDataSource(connection, true);
            Flyway v1 = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("1")
                    .load();
            v1.migrate();
            JdbcTemplate isolated = new JdbcTemplate(isolatedDataSource);
            Instant createdAt = Instant.parse("2026-08-31T02:00:00Z");
            Instant retryAt = Instant.parse("2026-08-31T02:05:00Z");
            isolated.update("""
                            INSERT INTO durable_jobs (
                                id, job_type, payload_reference, state, attempt_count, max_attempts,
                                next_attempt_at, last_failure_class, idempotency_key,
                                created_at, updated_at)
                            VALUES (?, 'REVIEW_EXECUTION', ?, 'READY', 1, 3,
                                    ?, 'TRANSIENT', 'rescheduled-legacy-job', ?, ?)
                            """,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    Timestamp.from(retryAt),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt.plusSeconds(30)));

            Flyway latest = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load();

            assertThatThrownBy(latest::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining(
                            "cannot safely infer durable_jobs.initial_next_attempt_at")
                    .hasStackTraceContaining("authoritative backfill or manual resolution");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void v2RejectsLifecycleEvidenceEvenWhenLegacyAttemptCountIsZero() throws Exception {
        String schema = "job_intent_suspect_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setSchema(schema);
            SingleConnectionDataSource isolatedDataSource =
                    new SingleConnectionDataSource(connection, true);
            Flyway v1 = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("1")
                    .load();
            v1.migrate();
            JdbcTemplate isolated = new JdbcTemplate(isolatedDataSource);
            Instant createdAt = Instant.parse("2026-08-31T02:00:00Z");
            isolated.update("""
                            INSERT INTO durable_jobs (
                                id, job_type, payload_reference, state, attempt_count, max_attempts,
                                next_attempt_at, last_failure_class, idempotency_key,
                                created_at, updated_at)
                            VALUES (?, 'REVIEW_EXECUTION', ?, 'READY', 0, 3,
                                    ?, 'TRANSIENT', 'suspect-legacy-job', ?, ?)
                            """,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    Timestamp.from(createdAt.plusSeconds(300)),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt.plusSeconds(30)));

            Flyway latest = flywayConfiguration()
                    .dataSource(isolatedDataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load();

            assertThatThrownBy(latest::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining(
                            "cannot safely infer durable_jobs.initial_next_attempt_at")
                    .hasStackTraceContaining("authoritative backfill or manual resolution");
        } finally {
            jdbcTemplate.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void rejectsPartialRootFailureThatWouldOtherwiseBeLostOnLoad() {
        UUID runId = insertRoot();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE review_runs SET failure_class = 'TERMINAL' WHERE id = ?", runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPartialAttemptMeasurementsThatWouldOtherwiseBecomeZeroOrNull() {
        UUID runId = insertRoot();
        insertStartedAttempt(runId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE review_attempts SET latency_ms = 17 WHERE review_run_id = ?", runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPartialAttemptFailureThatWouldOtherwiseBeLostOnLoad() {
        UUID runId = insertRoot();
        insertStartedAttempt(runId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE review_attempts SET failure_class = 'TRANSIENT' WHERE review_run_id = ?", runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPartialFindingPublicationDecisionThatWouldOtherwiseBeLostOnLoad() {
        UUID runId = insertRoot();
        insertFinding(runId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE review_findings
                        SET publication_policy_version = 'policy-v5'
                        WHERE review_run_id = ?
                        """, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPartialFindingPublicationReferenceThatWouldOtherwiseBeLostOnLoad() {
        UUID runId = insertRoot();
        insertFinding(runId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE review_findings
                        SET artifact_external_id = 'comment-1'
                        WHERE review_run_id = ?
                        """, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsPublicationReferenceForANonInlineDecision() {
        UUID runId = insertRoot();
        insertFinding(runId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE review_findings
                        SET publication_tier = 'CHECK_SUMMARY', publication_policy_version = 'policy-v5',
                            artifact_type = 'REVIEW_COMMENT', artifact_external_id = 'comment-1'
                        WHERE review_run_id = ?
                        """, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsArrayAndJsonNullToolStates() {
        UUID arrayRunId = insertRoot();
        UUID nullRunId = insertRoot();
        insertStartedAttempt(arrayRunId);
        insertStartedAttempt(nullRunId);

        assertThatThrownBy(() -> setMeasurements(arrayRunId, "[]"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setMeasurements(nullRunId, "null"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsObjectAndJsonNullCitations() {
        UUID objectRunId = insertRoot();
        UUID nullRunId = insertRoot();
        insertFinding(objectRunId);
        insertFinding(nullRunId);

        assertThatThrownBy(() -> setCitations(objectRunId, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> setCitations(nullRunId, "null"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsBlankValuesInsidePresentNullableGroups() {
        UUID rootFailureRunId = insertRoot();
        UUID attemptFailureRunId = insertRoot();
        UUID findingRunId = insertRoot();
        insertStartedAttempt(attemptFailureRunId);
        insertFinding(findingRunId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE review_runs
                        SET failure_code = '', failure_class = 'TERMINAL', failure_safe_message = 'safe'
                        WHERE id = ?
                        """, rootFailureRunId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE review_attempts
                        SET failure_code = 'CODE', failure_class = 'TRANSIENT', failure_safe_message = '  '
                        WHERE review_run_id = ?
                        """, attemptFailureRunId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE review_findings
                        SET publication_tier = 'INLINE_COMMENT', publication_policy_version = ' ',
                            artifact_type = 'REVIEW_COMMENT', artifact_external_id = ' '
                        WHERE review_run_id = ?
                        """, findingRunId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCompleteRootFailureOutsideTheFailedState() {
        UUID runId = insertRoot();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE review_runs
                        SET failure_code = 'CODE', failure_class = 'TERMINAL', failure_safe_message = 'safe'
                        WHERE id = ?
                        """, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCompleteMeasurementsForAStartedAttempt() {
        UUID runId = insertRoot();
        insertStartedAttempt(runId);

        assertThatThrownBy(() -> setMeasurements(runId, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsCompleteFailureForAStartedAttempt() {
        UUID runId = insertRoot();
        insertStartedAttempt(runId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE review_attempts
                        SET failure_code = 'CODE', failure_class = 'TRANSIENT', failure_safe_message = 'safe'
                        WHERE review_run_id = ?
                        """, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsFailureClassThatDisagreesWithTheAttemptState() {
        UUID runId = insertRoot();
        insertStartedAttempt(runId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE review_attempts
                        SET state = 'TRANSIENT_FAILURE', ended_at = now(),
                            latency_ms = 17, input_tokens = 2, output_tokens = 3, tool_states = '{}'::jsonb,
                            failure_code = 'CODE', failure_class = 'TERMINAL', failure_safe_message = 'safe'
                        WHERE review_run_id = ?
                        """, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertRoot() {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO review_runs (
                            id, installation_id, repository_id, pull_request_number, head_sha,
                            pipeline_version, configuration_version, model_name, policy_version,
                            max_review_attempts, requested_at, state)
                        VALUES (?, 1, 2, 3, ?, 'pipeline-v3', ?, 'model', 'policy-v5', 3, now(), 'REQUESTED')
                        """, runId, "head-" + runId, "configuration-" + runId);
        return runId;
    }

    private void insertStartedAttempt(UUID runId) {
        jdbcTemplate.update("""
                INSERT INTO review_attempts (review_run_id, attempt_number, state, started_at)
                VALUES (?, 1, 'STARTED', now())
                """, runId);
    }

    private void insertFinding(UUID runId) {
        jdbcTemplate.update("""
                        INSERT INTO review_findings (
                            review_run_id, fingerprint, file_path, post_change_line, changed_line,
                            severity, category, title, description, suggestion, evidence, source)
                        VALUES (?, ?, 'src/Test.java', 1, true,
                                'WARNING', 'STABILITY', 'title', 'description', 'suggestion', 'evidence', 'test')
                        """, runId, "a".repeat(64));
    }

    private void setMeasurements(UUID runId, String toolStatesJson) {
        jdbcTemplate.update("""
                        UPDATE review_attempts
                        SET latency_ms = 17, input_tokens = 2, output_tokens = 3,
                            tool_states = CAST(? AS jsonb)
                        WHERE review_run_id = ?
                        """, toolStatesJson, runId);
    }

    private void setCitations(UUID runId, String citationsJson) {
        jdbcTemplate.update("""
                        UPDATE review_findings
                        SET citations = CAST(? AS jsonb)
                        WHERE review_run_id = ?
                        """, citationsJson, runId);
    }

    private static String businessIdentityConstraintName(JdbcTemplate jdbcTemplate, String schema) {
        return jdbcTemplate.queryForObject("""
                        SELECT constraint_definition.conname
                        FROM pg_constraint constraint_definition
                        JOIN pg_class table_definition
                          ON table_definition.oid = constraint_definition.conrelid
                        JOIN pg_namespace schema_definition
                          ON schema_definition.oid = table_definition.relnamespace
                        CROSS JOIN unnest(constraint_definition.conkey) WITH ORDINALITY
                          AS key_columns(attribute_number, ordinality)
                        JOIN pg_attribute attribute
                          ON attribute.attrelid = table_definition.oid
                         AND attribute.attnum = key_columns.attribute_number
                        WHERE schema_definition.nspname = ?
                          AND table_definition.relname = 'review_runs'
                          AND constraint_definition.contype = 'u'
                        GROUP BY constraint_definition.oid, constraint_definition.conname
                        HAVING array_agg(attribute.attname ORDER BY key_columns.ordinality) =
                               ARRAY['installation_id', 'repository_id', 'pull_request_number',
                                     'head_sha', 'pipeline_version', 'configuration_version']::name[]
                        """, String.class, schema);
    }

    private static String businessIdentityIndexDefinition(
            JdbcTemplate jdbcTemplate, String schema) {
        return jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = ?
                  AND tablename = 'review_runs'
                  AND indexname = 'uq_review_runs_business_identity'
                """, String.class, schema);
    }

    private static void assertFlywayIsValid(Flyway flyway) {
        var validation = flyway.validateWithResult();
        assertThat(validation.validationSuccessful)
                .as(validation.getAllErrorMessages())
                .isTrue();
    }

    private javax.sql.DataSource isolatedDataSource(String applicationName) {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + separator + "ApplicationName=" + applicationName,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Flyway flyway(
            javax.sql.DataSource isolatedDataSource,
            String schema,
            String target) {
        var configuration = flywayConfiguration()
                .dataSource(isolatedDataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flywayConfiguration() {
        return Flyway.configure().configuration(Map.of(
                "flyway.postgresql.transactional.lock", "false"));
    }

    private static void populateMaintenanceTables(
            JdbcTemplate isolated,
            String schema,
            int rowCount) {
        isolated.execute("""
                INSERT INTO %s.durable_jobs (
                    id, job_type, payload_reference, state, attempt_count, max_attempts,
                    next_attempt_at, initial_next_attempt_at, lease_sequence,
                    idempotency_key, created_at, updated_at)
                SELECT md5('job-' || value)::uuid, 'REVIEW_EXECUTION',
                       md5('payload-' || value)::uuid,
                       CASE WHEN value %% 2 = 0 THEN 'LEASED' ELSE 'SUCCEEDED' END,
                       0, 3, now(), now(), 0, 'job-' || value, now(), now()
                FROM generate_series(1, %d) value
                """.formatted(schema, rowCount));
        isolated.execute("""
                INSERT INTO %s.outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, payload,
                    occurred_at, published_at)
                SELECT md5('event-' || value)::uuid, 'ReviewRun',
                       md5('aggregate-' || value)::uuid, 'ReviewRequested', '{}'::jsonb,
                       now(), CASE WHEN value %% 2 = 0 THEN now() ELSE NULL END
                FROM generate_series(1, %d) value
                """.formatted(schema, rowCount));
        isolated.execute("""
                INSERT INTO %s.github_deliveries (
                    delivery_id, event_name, payload_sha256, received_at, handled_at)
                SELECT 'delivery-' || value, 'pull_request', repeat('a', 64), now(),
                       CASE WHEN value %% 2 = 0 THEN now() ELSE NULL END
                FROM generate_series(1, %d) value
                """.formatted(schema, rowCount));
    }

    private static void awaitMigrationStart(CompletableFuture<?> migration) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (migration.isDone() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        Thread.sleep(250);
    }

    private boolean indexesAreValid(String schema, Set<String> indexNames) {
        Integer valid = jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM pg_index index_state
                        JOIN pg_class index_definition ON index_definition.oid = index_state.indexrelid
                        JOIN pg_namespace schema_definition ON schema_definition.oid = index_definition.relnamespace
                        WHERE schema_definition.nspname = ?
                          AND index_definition.relname = ANY (?)
                          AND index_state.indisvalid AND index_state.indisready
                        """,
                Integer.class,
                schema,
                indexNames.toArray(String[]::new));
        return valid != null && valid == indexNames.size();
    }

    private List<Map<String, Object>> indexState(String schema) {
        return jdbcTemplate.queryForList("""
                SELECT index_definition.relname, index_state.indisvalid, index_state.indisready
                FROM pg_index index_state
                JOIN pg_class index_definition ON index_definition.oid = index_state.indexrelid
                JOIN pg_namespace schema_definition ON schema_definition.oid = index_definition.relnamespace
                WHERE schema_definition.nspname = ?
                ORDER BY index_definition.relname
                """, schema);
    }

    private static String classpathText(String resource) throws IOException {
        try (InputStream input = ReviewOperationsMigrationTest.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing classpath resource " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private long metricRollup(String metric, String dimension) {
        jdbcTemplate.queryForObject("SELECT review_metric_flush(10000)", Integer.class);
        Long value = jdbcTemplate.queryForObject("""
                        SELECT metric_value FROM review_operations_metric_rollup
                        WHERE metric_name = ? AND dimension_value = ?
                        """, Long.class, metric, dimension);
        return value == null ? 0 : value;
    }

    private static ReviewRun requestedRun(ReviewRunId id) {
        return ReviewRun.request(
                id,
                new PullRequestRevision(101, 202, 303, "reviewed-head-sha"),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v7", "kimi-k2", "policy-v5", 3),
                Instant.parse("2026-08-31T01:00:00Z"));
    }

    private List<String> tableNames() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_type = 'BASE TABLE'
                       AND table_name IN ('github_deliveries', 'review_runs', 'review_attempts', 'review_findings',
                                          'finding_feedback', 'durable_jobs', 'outbox_events',
                                          'review_operations_metric_rollup',
                                          'review_operations_metric_delta')
                     """);
             var resultSet = statement.executeQuery()) {
            var tables = new ArrayList<String>();
            while (resultSet.next()) {
                tables.add(resultSet.getString("table_name"));
            }
            return tables;
        }
    }

    private List<List<String>> constraintColumns(String tableName, String constraintType) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT array_agg(attribute.attname ORDER BY key_columns.ordinality) AS columns
                     FROM pg_constraint constraint_definition
                     JOIN pg_class table_definition ON table_definition.oid = constraint_definition.conrelid
                     JOIN pg_namespace schema_definition ON schema_definition.oid = table_definition.relnamespace
                     CROSS JOIN unnest(constraint_definition.conkey) WITH ORDINALITY
                         AS key_columns(attribute_number, ordinality)
                     JOIN pg_attribute attribute
                         ON attribute.attrelid = table_definition.oid
                        AND attribute.attnum = key_columns.attribute_number
                     WHERE schema_definition.nspname = 'public'
                       AND table_definition.relname = ?
                       AND constraint_definition.contype = ?
                     GROUP BY constraint_definition.oid
                     """)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintType);
            try (var resultSet = statement.executeQuery()) {
                var constraints = new ArrayList<List<String>>();
                while (resultSet.next()) {
                    constraints.add(List.of((String[]) resultSet.getArray("columns").getArray()));
                }
                return constraints;
            }
        }
    }

    private Map<String, List<String>> constraintColumnsByName(
            String tableName, String constraintType) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT constraint_definition.conname,
                            array_agg(attribute.attname ORDER BY key_columns.ordinality) AS columns
                     FROM pg_constraint constraint_definition
                     JOIN pg_class table_definition ON table_definition.oid = constraint_definition.conrelid
                     JOIN pg_namespace schema_definition ON schema_definition.oid = table_definition.relnamespace
                     CROSS JOIN unnest(constraint_definition.conkey) WITH ORDINALITY
                         AS key_columns(attribute_number, ordinality)
                     JOIN pg_attribute attribute
                         ON attribute.attrelid = table_definition.oid
                        AND attribute.attnum = key_columns.attribute_number
                     WHERE schema_definition.nspname = 'public'
                       AND table_definition.relname = ?
                       AND constraint_definition.contype = ?
                     GROUP BY constraint_definition.oid, constraint_definition.conname
                     """)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintType);
            try (var resultSet = statement.executeQuery()) {
                Map<String, List<String>> constraints = new LinkedHashMap<>();
                while (resultSet.next()) {
                    constraints.put(
                            resultSet.getString("conname"),
                            List.of((String[]) resultSet.getArray("columns").getArray()));
                }
                return constraints;
            }
        }
    }

    private boolean unpublishedOutboxIndexExists() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1
                         FROM pg_indexes
                         WHERE schemaname = 'public'
                           AND tablename = 'outbox_events'
                           AND indexname = 'idx_outbox_events_unpublished'
                           AND indexdef ILIKE '%WHERE (published_at IS NULL)%'
                     )
                     """);
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }

    private List<String> unpublishedOutboxIndexColumns() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT attribute.attname
                     FROM pg_index index_metadata
                     JOIN pg_class index_definition
                       ON index_definition.oid = index_metadata.indexrelid
                     JOIN pg_class table_definition
                       ON table_definition.oid = index_metadata.indrelid
                     JOIN pg_namespace schema_definition
                       ON schema_definition.oid = table_definition.relnamespace
                     CROSS JOIN unnest(index_metadata.indkey) WITH ORDINALITY
                       AS key_columns(attribute_number, ordinality)
                     JOIN pg_attribute attribute
                       ON attribute.attrelid = table_definition.oid
                      AND attribute.attnum = key_columns.attribute_number
                     WHERE schema_definition.nspname = 'public'
                       AND table_definition.relname = 'outbox_events'
                       AND index_definition.relname = 'idx_outbox_events_unpublished'
                     ORDER BY key_columns.ordinality
                     """)) {
            try (var resultSet = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString("attname"));
                }
                return columns;
            }
        }
    }

    private List<String> indexColumns(String tableName, String indexName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT attribute.attname
                     FROM pg_index index_metadata
                     JOIN pg_class index_definition
                       ON index_definition.oid = index_metadata.indexrelid
                     JOIN pg_class table_definition
                       ON table_definition.oid = index_metadata.indrelid
                     JOIN pg_namespace schema_definition
                       ON schema_definition.oid = table_definition.relnamespace
                     CROSS JOIN unnest(index_metadata.indkey) WITH ORDINALITY
                       AS key_columns(attribute_number, ordinality)
                     JOIN pg_attribute attribute
                       ON attribute.attrelid = table_definition.oid
                      AND attribute.attnum = key_columns.attribute_number
                     WHERE schema_definition.nspname = 'public'
                       AND table_definition.relname = ?
                       AND index_definition.relname = ?
                     ORDER BY key_columns.ordinality
                     """)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            try (var resultSet = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString("attname"));
                }
                return columns;
            }
        }
    }

    private String indexDefinition(String indexName) {
        return jdbcTemplate.queryForObject("""
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'public' AND indexname = ?
                        """, String.class, indexName);
    }
}

class PostgresIntegrationSupportCompatibilityTest {

    private static final String DOCKER_API_VERSION_PROPERTY = "api.version";

    private String originalApiVersion;

    @org.junit.jupiter.api.BeforeEach
    void preserveOriginalApiVersion() {
        originalApiVersion = System.getProperty(DOCKER_API_VERSION_PROPERTY);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreOriginalApiVersion() {
        if (originalApiVersion == null) {
            System.clearProperty(DOCKER_API_VERSION_PROPERTY);
        } else {
            System.setProperty(DOCKER_API_VERSION_PROPERTY, originalApiVersion);
        }
    }

    @Test
    void preservesAnExplicitCallerApiVersionAndRestoresItAfterTheCompatibilityScope() {
        System.setProperty(DOCKER_API_VERSION_PROPERTY, "1.45");

        Runnable restore = PostgresIntegrationSupport.configureDockerApiVersion(() -> Optional.of("1.44"));

        assertThat(System.getProperty(DOCKER_API_VERSION_PROPERTY)).isEqualTo("1.45");
        restore.run();
        assertThat(System.getProperty(DOCKER_API_VERSION_PROPERTY)).isEqualTo("1.45");
    }

    @Test
    void suppliesAndRemovesTheFallbackOnlyForADaemonThatRequiresIt() {
        System.clearProperty(DOCKER_API_VERSION_PROPERTY);

        Runnable restore = PostgresIntegrationSupport.configureDockerApiVersion(() -> Optional.of("1.44"));

        assertThat(System.getProperty(DOCKER_API_VERSION_PROPERTY)).isEqualTo("1.44");
        restore.run();
        assertThat(System.getProperty(DOCKER_API_VERSION_PROPERTY)).isNull();
    }

    @Test
    void leavesApiVersionUnsetWhenTheDaemonSupportsTestcontainersDefault() {
        System.clearProperty(DOCKER_API_VERSION_PROPERTY);

        Runnable restore = PostgresIntegrationSupport.configureDockerApiVersion(() -> Optional.of("1.32"));

        assertThat(System.getProperty(DOCKER_API_VERSION_PROPERTY)).isNull();
        restore.run();
        assertThat(System.getProperty(DOCKER_API_VERSION_PROPERTY)).isNull();
    }

    @Test
    void boundedDockerApiProbeForcefullyTerminatesASilentProcessAndClosesItsOpenStream() {
        SilentProcess process = new SilentProcess();
        long startedAt = System.nanoTime();

        Optional<String> result = PostgresIntegrationSupport.dockerMinimumApiVersion(process);

        assertThat(result).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
        assertThat(process.forcefullyDestroyed).isTrue();
        assertThat(process.boundedWaitCount).isEqualTo(2);
        assertThat(process.unboundedWaitCalled).isFalse();
        assertThat(process.outputReadStarted).isTrue();
        assertThat(process.outputClosed).isTrue();
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(thread -> thread.getName().equals("docker-api-version-output-drainer") && thread.isAlive());
    }

    @Test
    void noisyDockerApiProbeDrainsOverflowWithoutRetainingItOrLeavingArtifacts() throws IOException {
        Set<Path> artifactsBefore = temporaryDockerApiArtifacts();
        NoisyProcess process = new NoisyProcess("1.44" + "x".repeat(2048));
        long startedAt = System.nanoTime();

        Optional<String> result = PostgresIntegrationSupport.dockerMinimumApiVersion(process);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
        assertThat(result.orElseThrow().getBytes(StandardCharsets.UTF_8)).hasSize(1024);
        assertThat(process.consumedBytes()).isGreaterThan(1024);
        assertThat(process.outputClosed).isTrue();
        assertThat(Thread.getAllStackTraces().keySet())
                .noneMatch(thread -> thread.getName().equals("docker-api-version-output-drainer") && thread.isAlive());
        assertThat(temporaryDockerApiArtifacts()).isEqualTo(artifactsBefore);
    }

    private static Set<Path> temporaryDockerApiArtifacts() throws IOException {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files
                    .filter(path -> path.getFileName().toString().startsWith("code-review-agent-docker-api-"))
                    .collect(Collectors.toSet());
        }
    }

    private static final class SilentProcess extends Process {

        private boolean forcefullyDestroyed;
        private int boundedWaitCount;
        private boolean unboundedWaitCalled;
        private boolean outputReadStarted;
        private boolean outputClosed;
        private final CloseTrackingBlockingInputStream output = new CloseTrackingBlockingInputStream(
                () -> outputReadStarted = true,
                () -> outputClosed = true);

        @Override
        public ByteArrayOutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return output;
        }

        @Override
        public ByteArrayInputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            unboundedWaitCalled = true;
            throw new AssertionError("probe must not wait indefinitely");
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            boundedWaitCount++;
            return forcefullyDestroyed;
        }

        @Override
        public int exitValue() {
            if (!forcefullyDestroyed) {
                throw new IllegalThreadStateException("process is still running");
            }
            return 137;
        }

        @Override
        public void destroy() {
            forcefullyDestroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            forcefullyDestroyed = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return !forcefullyDestroyed;
        }
    }

    private static final class CloseTrackingBlockingInputStream extends InputStream {

        private final Runnable onRead;
        private final Runnable onClose;
        private boolean closed;

        private CloseTrackingBlockingInputStream(Runnable onRead, Runnable onClose) {
            this.onRead = onRead;
            this.onClose = onClose;
        }

        @Override
        public synchronized int read() throws IOException {
            onRead.run();
            while (!closed) {
                try {
                    wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for stream closure", exception);
                }
            }
            return -1;
        }

        @Override
        public synchronized void close() {
            closed = true;
            onClose.run();
            notifyAll();
        }
    }

    private static final class NoisyProcess extends Process {

        private final CloseTrackingInputStream output;
        private boolean outputClosed;

        private NoisyProcess(String output) {
            this.output = new CloseTrackingInputStream(output.getBytes(StandardCharsets.UTF_8), () -> outputClosed = true);
        }

        @Override
        public ByteArrayOutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public CloseTrackingInputStream getInputStream() {
            return output;
        }

        @Override
        public ByteArrayInputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            throw new AssertionError("probe must not wait indefinitely");
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        private int consumedBytes() {
            return output.consumedBytes();
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private final Runnable onClose;
        private int consumedBytes;

        private CloseTrackingInputStream(byte[] bytes, Runnable onClose) {
            super(bytes);
            this.onClose = onClose;
        }

        @Override
        public void close() {
            onClose.run();
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int bytesRead = super.read(buffer, offset, length);
            if (bytesRead > 0) {
                consumedBytes += bytesRead;
            }
            return bytesRead;
        }

        private int consumedBytes() {
            return consumedBytes;
        }
    }
}
