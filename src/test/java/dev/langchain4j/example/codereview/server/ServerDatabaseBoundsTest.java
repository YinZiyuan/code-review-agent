package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("server")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "code-review.runtime=server",
        "code-review.server.worker.poll-interval=1h",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=250",
        "spring.datasource.hikari.validation-timeout=250",
        "code-review.server.database.statement-timeout=500ms",
        "code-review.server.database.lock-timeout=400ms",
        "code-review.server.database.transaction-timeout=1s"
})
@Import(ServerReadinessTest.TestReviewerConfiguration.class)
class ServerDatabaseBoundsTest {

    private static final String WEBHOOK_SECRET = "database-bounds-webhook-secret";
    private static final String PRIVATE_KEY = ephemeralPrivateKey();
    private static final String PREVIOUS_DOCKER_API_VERSION = System.getProperty("api.version");

    static {
        System.setProperty("api.version", "1.44");
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("code-review.server.github.app-id", () -> 123L);
        properties.add("code-review.server.github.private-key", () -> PRIVATE_KEY);
        properties.add("code-review.server.github.webhook-secret", () -> WEBHOOK_SECRET);
        properties.add("code-review.server.github.api-base-url", () -> "http://127.0.0.1:9");
        properties.add("langchain4j.open-ai.chat-model.api-key", () -> "database-bounds-model-key");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DurableJobQueue jobs;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionOperations transactions;

    @AfterAll
    static void restoreDockerApiVersion() {
        if (PREVIOUS_DOCKER_API_VERSION == null) {
            System.clearProperty("api.version");
        } else {
            System.setProperty("api.version", PREVIOUS_DOCKER_API_VERSION);
        }
    }

    @Test
    void bootUsesHikariWithDatabaseEnforcedStatementAndLockDeadlines() throws Exception {
        assertThat(dataSource.getClass().getName()).isEqualTo("com.zaxxer.hikari.HikariDataSource");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(setting(connection, "statement_timeout")).isEqualTo("500ms");
            assertThat(setting(connection, "lock_timeout")).isEqualTo("400ms");
            assertThat(setting(connection, "idle_in_transaction_session_timeout")).isEqualTo("15s");
        }
    }

    @Test
    void postgresCancelsStatementsThatExceedTheConfiguredDeadline() {
        assertCompletesWithin(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> jdbc.queryForObject("SELECT pg_sleep(2)", Object.class))
                        .isInstanceOf(QueryTimeoutException.class)
                        .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("57014")));
    }

    @Test
    void postgresCancelsConflictingLockWaitsAtTheConfiguredDeadline() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS database_bounds_lock_test (id integer PRIMARY KEY, value integer)");
        jdbc.update("INSERT INTO database_bounds_lock_test VALUES (1, 0) ON CONFLICT (id) DO UPDATE SET value = 0");

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            blocker.createStatement().executeUpdate(
                    "UPDATE database_bounds_lock_test SET value = 1 WHERE id = 1");

            assertCompletesWithin(Duration.ofSeconds(2), () ->
                    assertThatThrownBy(() -> jdbc.update(
                                    "UPDATE database_bounds_lock_test SET value = 2 WHERE id = 1"))
                            .isInstanceOf(DataAccessException.class)
                            .satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("55P03")));
            blocker.rollback();
        }
    }

    @Test
    void springTransactionTimeoutCancelsWorkEvenWhenTheSessionStatementTimeoutIsDisabled() {
        assertCompletesWithin(Duration.ofSeconds(3), () ->
                assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                            jdbc.execute("SET LOCAL statement_timeout = 0");
                            jdbc.queryForObject("SELECT pg_sleep(3)", Object.class);
                        }))
                        .isInstanceOfAny(QueryTimeoutException.class, TransactionTimedOutException.class));
    }

    @Test
    void poolSaturationBoundsAcquisitionReadinessWebhookAndLeaseHeartbeat() throws Exception {
        try (Connection first = dataSource.getConnection();
             Connection second = dataSource.getConnection()) {
            assertCompletesWithin(Duration.ofSeconds(2), () ->
                    assertThatThrownBy(dataSource::getConnection)
                            .isInstanceOf(java.sql.SQLException.class));

            assertCompletesWithin(Duration.ofSeconds(2), () -> {
                HttpResult readiness = get("/actuator/health/readiness");
                assertThat(readiness.status()).isEqualTo(503);
            });

            assertCompletesWithin(Duration.ofSeconds(2), () -> {
                HttpResult webhook = postSignedWebhook();
                assertThat(webhook.status()).isEqualTo(500);
                assertThat(webhook.body()).isEqualTo("WEBHOOK_PROCESSING_FAILED");
            });

            assertCompletesWithin(Duration.ofSeconds(2), () ->
                    assertThatThrownBy(() -> jobs.renewLease(
                                    UUID.randomUUID(), "worker-a", 1,
                                    Instant.now(), Duration.ofMinutes(1)))
                            .isInstanceOf(RuntimeException.class));
        }
    }

    private HttpResult postSignedWebhook() throws Exception {
        byte[] payload = """
                {"action":"opened","installation":{"id":41},"repository":{"id":73,"full_name":"octo/repo","clone_url":"https://github.com/octo/repo.git"},"number":12,"pull_request":{"head":{"sha":"0123456789abcdef0123456789abcdef01234567"}}}
                """.strip().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://localhost:" + port + "/webhooks/github").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("X-Hub-Signature-256", signature(payload));
        connection.setRequestProperty("X-GitHub-Delivery", "database-bounds-delivery");
        connection.setRequestProperty("X-GitHub-Event", "pull_request");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload);
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        return new HttpResult(status, input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }

    private HttpResult get(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://localhost:" + port + path).toURL().openConnection();
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        return new HttpResult(status, input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }

    private static String setting(Connection connection, String name) throws Exception {
        try (var statement = connection.prepareStatement("SELECT current_setting(?)")) {
            statement.setString(1, name);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static String sqlState(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlFailure) {
                return sqlFailure.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private static String signature(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }

    private static void assertCompletesWithin(Duration duration, ThrowingAction action) {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(duration, action::run);
    }

    private static String ephemeralPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(
                    generator.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----";
        } catch (GeneralSecurityException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private record HttpResult(int status, String body) {
    }
}
