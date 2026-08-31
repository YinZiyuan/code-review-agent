package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewOperationsMigrationTest extends PostgresIntegrationSupport {

    @Test
    void migratesTheReviewOperationsSchemaWithItsBusinessKeysAndDeliveryIndexes() throws SQLException {
        assertThat(tableNames()).containsExactlyInAnyOrder(
                "github_deliveries",
                "review_runs",
                "review_attempts",
                "review_findings",
                "finding_feedback",
                "durable_jobs",
                "outbox_events");
        assertThat(constraintColumns("review_runs", "u"))
                .contains(List.of(
                        "installation_id",
                        "repository_id",
                        "pull_request_number",
                        "head_sha",
                        "pipeline_version",
                        "configuration_version"));
        assertThat(constraintColumns("review_attempts", "p"))
                .contains(List.of("review_run_id", "attempt_number"));
        assertThat(constraintColumns("review_findings", "p"))
                .contains(List.of("review_run_id", "fingerprint"));
        assertThat(constraintColumns("durable_jobs", "u"))
                .contains(List.of("idempotency_key"));
        assertThat(unpublishedOutboxIndexExists()).isTrue();
    }

    @Test
    void rerunningFlywayOnTheMigratedDatabaseExecutesNoMigrations() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private List<String> tableNames() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_type = 'BASE TABLE'
                       AND table_name IN ('github_deliveries', 'review_runs', 'review_attempts', 'review_findings',
                                          'finding_feedback', 'durable_jobs', 'outbox_events')
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
    void boundedDockerApiProbeForcefullyTerminatesASilentProcess() {
        SilentProcess process = new SilentProcess();
        long startedAt = System.nanoTime();

        Optional<String> result = PostgresIntegrationSupport.dockerMinimumApiVersion(process, () -> {
            throw new AssertionError("output must not be read before a successful process exit");
        });

        assertThat(result).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
        assertThat(process.forcefullyDestroyed).isTrue();
        assertThat(process.boundedWaitCount).isEqualTo(2);
        assertThat(process.unboundedWaitCalled).isFalse();
    }

    @Test
    void noisyDockerApiProbeDrainsOverflowWithoutRetainingItOrLeavingArtifacts() throws IOException {
        Set<Path> artifactsBefore = temporaryDockerApiArtifacts();
        NoisyProcess process = new NoisyProcess("1.44" + "x".repeat(2048));
        long startedAt = System.nanoTime();

        Optional<String> result = PostgresIntegrationSupport.dockerMinimumApiVersion(process);

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
        assertThat(result.orElseThrow().getBytes(StandardCharsets.UTF_8)).hasSize(1024);
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

        @Override
        public ByteArrayOutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public ByteArrayInputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
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
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private final Runnable onClose;

        private CloseTrackingInputStream(byte[] bytes, Runnable onClose) {
            super(bytes);
            this.onClose = onClose;
        }

        @Override
        public void close() {
            onClose.run();
        }
    }
}
