package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

abstract class PostgresIntegrationSupport {

    static {
        System.setProperty("api.version", "1.44");
    }

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    protected static HikariDataSource dataSource;

    @BeforeAll
    static void startPostgresAndMigrate() {
        POSTGRES.start();

        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(POSTGRES.getJdbcUrl());
        configuration.setUsername(POSTGRES.getUsername());
        configuration.setPassword(POSTGRES.getPassword());
        dataSource = new HikariDataSource(configuration);

        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void stopPostgres() {
        if (dataSource != null) {
            dataSource.close();
        }
        POSTGRES.stop();
    }
}
