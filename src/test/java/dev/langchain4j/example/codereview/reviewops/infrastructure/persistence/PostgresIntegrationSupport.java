package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

abstract class PostgresIntegrationSupport {

    private static final String DOCKER_API_VERSION_PROPERTY = "api.version";
    private static final int TESTCONTAINERS_DEFAULT_DOCKER_API_MINOR_VERSION = 32;

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
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.ofNullable(output).map(String::trim).filter(value -> !value.isEmpty());
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
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
}
