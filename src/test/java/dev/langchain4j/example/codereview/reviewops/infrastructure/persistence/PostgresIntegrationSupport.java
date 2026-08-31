package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public abstract class PostgresIntegrationSupport {

    private static final String DOCKER_API_VERSION_PROPERTY = "api.version";
    private static final int TESTCONTAINERS_DEFAULT_DOCKER_API_MINOR_VERSION = 32;
    private static final int DOCKER_API_OUTPUT_MAX_BYTES = 1024;
    private static final long OUTPUT_DRAINER_SHUTDOWN_TIMEOUT_MILLIS = 1000;

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    protected static HikariDataSource dataSource;
    protected static Flyway flyway;
    private static Runnable restoreDockerApiVersion = () -> {
    };

    @BeforeAll
    static void startPostgresAndMigrate() {
        restoreDockerApiVersion = configureDockerApiVersion(PostgresIntegrationSupport::dockerMinimumApiVersion);
        boolean started = false;
        try {
            POSTGRES.start();

            HikariConfig configuration = new HikariConfig();
            configuration.setJdbcUrl(POSTGRES.getJdbcUrl());
            configuration.setUsername(POSTGRES.getUsername());
            configuration.setPassword(POSTGRES.getPassword());
            dataSource = new HikariDataSource(configuration);

            flyway = Flyway.configure().dataSource(dataSource).load();
            flyway.migrate();
            started = true;
        } finally {
            if (!started) {
                restoreDockerApiVersion.run();
            }
        }
    }

    @AfterAll
    static void stopPostgres() {
        try {
            if (dataSource != null) {
                dataSource.close();
            }
            POSTGRES.stop();
        } finally {
            flyway = null;
            restoreDockerApiVersion.run();
        }
    }

    static Runnable configureDockerApiVersion(Supplier<Optional<String>> daemonMinimumApiVersion) {
        String previousApiVersion = System.getProperty(DOCKER_API_VERSION_PROPERTY);
        if (previousApiVersion == null) {
            daemonMinimumApiVersion.get()
                    .filter(PostgresIntegrationSupport::requiresApiVersionFallback)
                    .ifPresent(minimumApiVersion -> System.setProperty(DOCKER_API_VERSION_PROPERTY, minimumApiVersion));
        }
        return () -> restoreDockerApiVersion(previousApiVersion);
    }

    private static Optional<String> dockerMinimumApiVersion() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.MinAPIVersion}}")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return dockerMinimumApiVersion(process);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    static Optional<String> dockerMinimumApiVersion(Process process) {
        InputStream output = process.getInputStream();
        BoundedOutputDrainer drainer = new BoundedOutputDrainer(output);
        Thread reader = new Thread(drainer, "docker-api-version-output-drainer");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                forcefullyTerminate(process);
                return Optional.empty();
            }
            if (process.exitValue() != 0 || !awaitOutputDrainer(reader)) {
                return Optional.empty();
            }
            return drainer.capturedOutput();
        } catch (InterruptedException exception) {
            forcefullyTerminate(process);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            closeOutputAndAwaitReader(output, reader);
        }
    }

    private static boolean awaitOutputDrainer(Thread reader) {
        try {
            reader.join(OUTPUT_DRAINER_SHUTDOWN_TIMEOUT_MILLIS);
            return !reader.isAlive();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void closeOutputAndAwaitReader(InputStream output, Thread reader) {
        try {
            output.close();
        } catch (IOException exception) {
            // Closing a process stream is best effort during test cleanup.
        }
        awaitOutputDrainer(reader);
    }

    private static void forcefullyTerminate(Process process) {
        process.destroyForcibly();
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean requiresApiVersionFallback(String minimumApiVersion) {
        String[] segments = minimumApiVersion.split("\\.");
        if (segments.length != 2) {
            return false;
        }
        try {
            int major = Integer.parseInt(segments[0]);
            int minor = Integer.parseInt(segments[1]);
            return major > 1 || major == 1 && minor > TESTCONTAINERS_DEFAULT_DOCKER_API_MINOR_VERSION;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static void restoreDockerApiVersion(String previousApiVersion) {
        if (previousApiVersion == null) {
            System.clearProperty(DOCKER_API_VERSION_PROPERTY);
        } else {
            System.setProperty(DOCKER_API_VERSION_PROPERTY, previousApiVersion);
        }
    }

    private static final class BoundedOutputDrainer implements Runnable {

        private final InputStream output;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream(DOCKER_API_OUTPUT_MAX_BYTES);

        private BoundedOutputDrainer(InputStream output) {
            this.output = output;
        }

        @Override
        public void run() {
            try (output) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = output.read(buffer)) != -1) {
                    int bytesToRetain = Math.min(bytesRead, DOCKER_API_OUTPUT_MAX_BYTES - captured.size());
                    if (bytesToRetain > 0) {
                        captured.write(buffer, 0, bytesToRetain);
                    }
                }
            } catch (IOException exception) {
                // Process termination closes the stream while the drainer is still reading.
            }
        }

        private Optional<String> capturedOutput() {
            return Optional.of(new String(captured.toByteArray(), StandardCharsets.UTF_8))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty());
        }
    }
}
