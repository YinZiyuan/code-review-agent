package dev.langchain4j.example.codereview.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitHubWebhookServerIntegrationTest {

    private static final String WEBHOOK_SECRET = "real-server's-webhook-secret";
    private static final String PRIVATE_KEY_PEM = generatedPrivateKeyPem();
    private static final int MAX_WEBHOOK_BYTES = 4096;
    private static final String PREVIOUS_DOCKER_API_VERSION = System.getProperty("api.version");

    static {
        System.setProperty("api.version", "1.44");
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void serverProperties(DynamicPropertyRegistry properties) {
        properties.add("code-review.runtime", () -> "server");
        properties.add("code-review.server.github.app-id", () -> 123L);
        properties.add("code-review.server.github.private-key", () -> PRIVATE_KEY_PEM);
        properties.add("code-review.server.github.webhook-secret", () -> WEBHOOK_SECRET);
        properties.add("code-review.server.github.max-webhook-bytes", () -> MAX_WEBHOOK_BYTES);
        properties.add("code-review.server.worker.poll-interval", () -> "1h");
        properties.add("langchain4j.open-ai.chat-model.api-key", () -> "test-model-key");
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearPersistedAdmissionFacts() {
        jdbcTemplate.execute("TRUNCATE github_deliveries, durable_jobs, review_runs CASCADE");
    }

    @AfterAll
    static void restoreDockerApiVersion() {
        if (PREVIOUS_DOCKER_API_VERSION == null) {
            System.clearProperty("api.version");
        } else {
            System.setProperty("api.version", PREVIOUS_DOCKER_API_VERSION);
        }
    }

    @Test
    void admitsASignedWebhookThroughTheRealTransactionalServerGraph() throws Exception {
        byte[] payload = interestedPayload("delivery-real-server");

        HttpURLConnection response = request(payload, false, payload.length);

        assertThat(response.getResponseCode()).isEqualTo(202);
        assertThat(read(response.getInputStream())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM github_deliveries", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM review_runs", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM durable_jobs", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload_sha256 FROM github_deliveries WHERE delivery_id = ?",
                String.class,
                "delivery-real-server"))
                .isEqualTo(sha256(payload));
    }

    @Test
    void rejectsAnOversizedDeclaredContentLengthBeforeReadingTheRequestBody() throws Exception {
        byte[] payload = "x".repeat(MAX_WEBHOOK_BYTES + 1).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection response = request(payload, false, payload.length);

        assertThat(response.getResponseCode()).isEqualTo(413);
        assertThat(read(response.getErrorStream())).isEqualTo("WEBHOOK_PAYLOAD_TOO_LARGE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM github_deliveries", Integer.class))
                .isZero();
    }

    @Test
    void rejectsAnOversizedChunkedBodyWhileItIsRead() throws Exception {
        byte[] payload = "x".repeat(MAX_WEBHOOK_BYTES + 1).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection response = request(payload, true, -1);

        assertThat(response.getResponseCode()).isEqualTo(413);
        assertThat(read(response.getErrorStream())).isEqualTo("WEBHOOK_PAYLOAD_TOO_LARGE");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM github_deliveries", Integer.class))
                .isZero();
    }

    private HttpURLConnection request(byte[] payload, boolean chunked, int declaredLength) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://localhost:" + port + "/webhooks/github").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("X-Hub-Signature-256", signature(payload));
        connection.setRequestProperty("X-GitHub-Delivery", "delivery-real-server");
        connection.setRequestProperty("X-GitHub-Event", "pull_request");
        if (chunked) {
            connection.setChunkedStreamingMode(256);
        } else {
            connection.setFixedLengthStreamingMode(declaredLength);
        }
        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload);
        }
        return connection;
    }

    private static byte[] interestedPayload(String deliveryId) {
        return ("""
                {"action":"opened","installation":{"id":41},"repository":{"id":73,"full_name":"octo/repo","clone_url":"https://github.com/octo/repo.git"},"number":12,"pull_request":{"head":{"sha":"0123456789abcdef0123456789abcdef01234567"}},"delivery_marker":"%s"}
                """.formatted(deliveryId).strip()).getBytes(StandardCharsets.UTF_8);
    }

    private static String signature(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String generatedPrivateKeyPem() {
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

    private static String read(InputStream input) throws Exception {
        return input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
}
