package dev.langchain4j.example.codereview.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobWorker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "code-review.runtime=server",
        "code-review.server.worker.poll-interval=1h",
        "code-review.server.worker.batch-size=1",
        "code-review.server.worker.recovery-batch-size=2",
        "code-review.server.worker.lease-duration=30s",
        "code-review.server.worker.heartbeat-interval=5s",
        "code-review.server.worker.initial-backoff=10ms",
        "code-review.server.worker.max-backoff=10ms",
        "code-review.server.worker.jitter-ratio=0",
        "management.endpoints.web.exposure.include=health,metrics"
})
@Import(GitHubReviewLoopE2ETest.DeterministicReviewerConfiguration.class)
class GitHubReviewLoopE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String WEBHOOK_SECRET = "e2e-webhook-secret-never-retained";
    private static final String MODEL_KEY = "e2e-model-key-never-retained";
    private static final String INSTALLATION_TOKEN = "e2e-installation-token-never-retained";
    private static final String PRIVATE_KEY = ephemeralPrivateKey();
    private static final FakeGitHub FAKE_GITHUB = FakeGitHub.start();
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
        properties.add("code-review.server.github.api-base-url", FAKE_GITHUB::baseUrl);
        properties.add("langchain4j.open-ai.chat-model.api-key", () -> MODEL_KEY);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReviewJobWorker worker;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void resetFixture() {
        jdbcTemplate.execute("TRUNCATE github_deliveries, outbox_events, durable_jobs, review_runs CASCADE");
        FAKE_GITHUB.reset();
    }

    @AfterAll
    static void stopFixture() {
        FAKE_GITHUB.close();
        if (PREVIOUS_DOCKER_API_VERSION == null) {
            System.clearProperty("api.version");
        } else {
            System.setProperty("api.version", PREVIOUS_DOCKER_API_VERSION);
        }
    }

    @Test
    void signedWebhookPublishesExactlyOnceAndReplayCreatesNoAdditionalFactsOrArtifacts()
            throws Exception {
        byte[] payload = openedPayload("delivery-e2e-success");

        assertThat(postWebhook(payload, "delivery-e2e-success", signature(payload)))
                .isEqualTo(202);
        runUntilIdle();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM review_runs", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM review_runs", String.class))
                .isEqualTo("PUBLISHED");
        assertThat(FAKE_GITHUB.checks()).singleElement()
                .satisfies(check -> assertThat(check.headSha()).isEqualTo(HEAD_SHA));
        assertThat(FAKE_GITHUB.comments())
                .extracting(FakeGitHub.Comment::line)
                .containsExactlyInAnyOrder(1, 2);
        assertThat(FAKE_GITHUB.comments())
                .allSatisfy(comment -> assertThat(comment.commitId()).isEqualTo(HEAD_SHA));

        int jobCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM durable_jobs", Integer.class);
        assertThat(postWebhook(payload, "delivery-e2e-success", signature(payload)))
                .isEqualTo(202);
        assertThat(worker.runOnce().leased()).isZero();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM github_deliveries", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM review_runs", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM durable_jobs", Integer.class))
                .isEqualTo(jobCount);
        assertThat(FAKE_GITHUB.checks()).hasSize(1);
        assertThat(FAKE_GITHUB.comments()).hasSize(2);
    }

    @Test
    void invalidSignatureReturnsUnauthorizedWithoutPersistingOrCallingGitHub() throws Exception {
        byte[] payload = openedPayload("delivery-invalid-signature");

        assertThat(postWebhook(
                payload,
                "delivery-invalid-signature",
                "sha256=" + "0".repeat(64))).isEqualTo(401);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM github_deliveries", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM review_runs", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM durable_jobs", Integer.class))
                .isZero();
        assertThat(FAKE_GITHUB.redactedTranscript()).isEmpty();
    }

    @Test
    void expiredWorkerLeaseIsRecoveredAndCompletesTheSameReviewRun() throws Exception {
        byte[] payload = openedPayload("delivery-expired-lease");
        assertThat(postWebhook(payload, "delivery-expired-lease", signature(payload)))
                .isEqualTo(202);
        jdbcTemplate.update("""
                UPDATE durable_jobs
                SET state = 'SUCCEEDED', updated_at = now()
                WHERE job_type = 'SUPERSEDE_OBSOLETE_RUNS'
                """);
        jdbcTemplate.update("""
                UPDATE durable_jobs
                SET state = 'LEASED', attempt_count = 1, lease_sequence = 1,
                    lease_owner = 'crashed-worker', lease_expires_at = now() - interval '1 second',
                    updated_at = now() - interval '1 second'
                WHERE job_type = 'REVIEW_EXECUTION'
                """);
        double metricBefore = leaseRecoveryCount();

        ReviewJobWorker.WorkerCycleResult recovered = worker.runOnce();
        runUntilIdle();

        assertThat(recovered.recovered()).isOne();
        assertThat(leaseRecoveryCount() - metricBefore).isEqualTo(1.0);
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM review_runs", String.class))
                .isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM review_runs", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void publicationRetryAfterFirstCommentDoesNotDuplicateTheConfirmedArtifact()
            throws Exception {
        FAKE_GITHUB.failSecondCommentCreateOnce();
        byte[] payload = openedPayload("delivery-partial-publication");
        assertThat(postWebhook(payload, "delivery-partial-publication", signature(payload)))
                .isEqualTo(202);

        runUntilIdle();

        assertThat(jdbcTemplate.queryForObject("SELECT state FROM review_runs", String.class))
                .isEqualTo("PUBLISHED");
        assertThat(FAKE_GITHUB.comments())
                .extracting(FakeGitHub.Comment::line)
                .containsExactlyInAnyOrder(1, 2);
        assertThat(FAKE_GITHUB.commentAttemptLines()).containsExactly(1, 2, 2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_findings WHERE artifact_external_id IS NOT NULL",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void staleHeadDuringPublishingWithPersistedPartialProgressSupersedesWithoutNewMutation()
            throws Exception {
        FAKE_GITHUB.failSecondCommentCreateOnce();
        byte[] payload = openedPayload("delivery-stale-partial-publication");
        assertThat(postWebhook(
                payload,
                "delivery-stale-partial-publication",
                signature(payload))).isEqualTo(202);

        runUntilPersistedPartialPublication();
        int checkMutations = FAKE_GITHUB.checkMutationCount();
        int commentMutations = FAKE_GITHUB.commentAttemptLines().size();
        FAKE_GITHUB.authoritativeHead("abcdef0123456789abcdef0123456789abcdef01");
        makeRetriesDue();
        runUntilIdle();

        assertThat(jdbcTemplate.queryForObject("SELECT state FROM review_runs", String.class))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_findings WHERE artifact_external_id IS NOT NULL",
                Integer.class)).isOne();
        assertThat(FAKE_GITHUB.checkMutationCount()).isEqualTo(checkMutations);
        assertThat(FAKE_GITHUB.commentAttemptLines()).hasSize(commentMutations);
        assertThat(FAKE_GITHUB.comments()).hasSize(1);
    }

    @Test
    void logsDatabaseFactsAndRedactedHttpEvidenceDoNotRetainConfiguredSecrets(
            CapturedOutput output) throws Exception {
        byte[] payload = openedPayload("delivery-secret-proof");
        assertThat(postWebhook(payload, "delivery-secret-proof", signature(payload)))
                .isEqualTo(202);
        runUntilIdle();

        String evidence = output.getAll() + databaseEvidence() + FAKE_GITHUB.redactedTranscript();
        assertThat(evidence)
                .doesNotContain(PRIVATE_KEY)
                .doesNotContain(WEBHOOK_SECRET)
                .doesNotContain(INSTALLATION_TOKEN)
                .doesNotContain(MODEL_KEY);
    }

    private void runUntilIdle() {
        for (int cycle = 0; cycle < 24; cycle++) {
            worker.runOnce();
            makeRetriesDue();
            Integer active = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM durable_jobs WHERE state IN ('READY', 'LEASED')",
                    Integer.class);
            if (active != null && active == 0) {
                return;
            }
        }
        throw new AssertionError("review worker did not become idle");
    }

    private void runUntilPersistedPartialPublication() {
        for (int cycle = 0; cycle < 12; cycle++) {
            worker.runOnce();
            String state = jdbcTemplate.queryForObject("SELECT state FROM review_runs", String.class);
            if ("PUBLISHING".equals(state) && FAKE_GITHUB.comments().size() == 1) {
                return;
            }
            makeRetriesDue();
        }
        throw new AssertionError("review did not reach persisted partial publication");
    }

    private void makeRetriesDue() {
        jdbcTemplate.update("""
                UPDATE durable_jobs
                SET next_attempt_at = now() - interval '1 millisecond'
                WHERE state = 'READY' AND next_attempt_at > now()
                """);
    }

    private double leaseRecoveryCount() {
        var counter = meterRegistry.find("code.review.job.lease.recoveries").counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String databaseEvidence() {
        return List.of(
                        "github_deliveries",
                        "review_runs",
                        "review_attempts",
                        "review_findings",
                        "durable_jobs",
                        "outbox_events")
                .stream()
                .map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table).toString())
                .reduce("", String::concat);
    }

    private int postWebhook(byte[] payload, String deliveryId, String signature) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://localhost:" + port + "/webhooks/github").toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("X-Hub-Signature-256", signature);
        connection.setRequestProperty("X-GitHub-Delivery", deliveryId);
        connection.setRequestProperty("X-GitHub-Event", "pull_request");
        connection.getOutputStream().write(payload);
        return connection.getResponseCode();
    }

    private static byte[] openedPayload(String marker) {
        return ("""
                {"action":"opened","installation":{"id":41},"repository":{"id":73,"full_name":"octo/repo","clone_url":"https://github.com/octo/repo.git"},"number":12,"pull_request":{"head":{"sha":"%s"}},"marker":"%s"}
                """.formatted(HEAD_SHA, marker).strip()).getBytes(StandardCharsets.UTF_8);
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

    private static String ephemeralPrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                    + "\n-----END PRIVATE KEY-----";
        } catch (GeneralSecurityException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicReviewerConfiguration {

        @Bean
        @Primary
        CodeReviewAgent deterministicCodeReviewAgent() {
            ReviewResult result = new ReviewResult("deterministic e2e review", List.of(
                    finding("F-001", 1, "First issue"),
                    finding("F-002", 2, "Second issue")), List.of());
            return new CodeReviewAgent() {
                @Override
                public ReviewResult review(String request, Path sourceRoot) {
                    return result;
                }

                @Override
                public ReviewExecution reviewWithTelemetry(String request, Path sourceRoot) {
                    return new ReviewExecution(result, 17, 5);
                }
            };
        }

        private static ReviewFinding finding(String id, int line, String title) {
            return new ReviewFinding(
                    id,
                    "src/Example.java",
                    line,
                    new int[]{line, line},
                    Severity.WARNING,
                    Category.STABILITY,
                    title,
                    "Deterministic description",
                    "Apply the deterministic fix",
                    "Deterministic evidence",
                    List.of(),
                    "regex");
        }
    }

    static final class FakeGitHub implements AutoCloseable {

        private static final byte[] ARCHIVE = archive();
        private static final String DIFF = """
                diff --git a/src/Example.java b/src/Example.java
                new file mode 100644
                --- /dev/null
                +++ b/src/Example.java
                @@ -0,0 +1,2 @@
                +class Example {
                +}
                """;

        private final HttpServer server;
        private final AtomicLong ids = new AtomicLong(900);
        private final List<Check> checks = new ArrayList<>();
        private final List<Comment> comments = new ArrayList<>();
        private final List<Integer> commentAttemptLines = new ArrayList<>();
        private final List<String> redactedTranscript = new ArrayList<>();
        private String authoritativeHead = HEAD_SHA;
        private int checkMutationCount;
        private int failedCommentAttempt = -1;

        private FakeGitHub(HttpServer server) {
            this.server = server;
        }

        static FakeGitHub start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                FakeGitHub fixture = new FakeGitHub(server);
                server.createContext("/", fixture::handle);
                server.start();
                return fixture;
            } catch (IOException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        synchronized void reset() {
            ids.set(900);
            checks.clear();
            comments.clear();
            commentAttemptLines.clear();
            redactedTranscript.clear();
            authoritativeHead = HEAD_SHA;
            checkMutationCount = 0;
            failedCommentAttempt = -1;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        synchronized List<Check> checks() {
            return List.copyOf(checks);
        }

        synchronized List<Comment> comments() {
            return List.copyOf(comments);
        }

        synchronized List<Integer> commentAttemptLines() {
            return List.copyOf(commentAttemptLines);
        }

        synchronized List<String> redactedTranscript() {
            return List.copyOf(redactedTranscript);
        }

        synchronized int checkMutationCount() {
            return checkMutationCount;
        }

        synchronized void failSecondCommentCreateOnce() {
            failedCommentAttempt = 2;
        }

        synchronized void authoritativeHead(String headSha) {
            authoritativeHead = headSha;
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            synchronized (this) {
                redactedTranscript.add(method + " " + path + " "
                        + new String(body, StandardCharsets.UTF_8));
            }

            if (method.equals("POST") && path.equals("/app/installations/41/access_tokens")) {
                respond(exchange, 201, Map.of(
                        "token", INSTALLATION_TOKEN,
                        "expires_at", Instant.now().plusSeconds(3600).toString()));
                return;
            }
            if (method.equals("GET") && path.equals("/repositories/73/pulls/12")) {
                if ("application/vnd.github.diff".equals(exchange.getRequestHeaders().getFirst("Accept"))) {
                    respond(exchange, 200, DIFF.getBytes(StandardCharsets.UTF_8),
                            "application/vnd.github.diff");
                } else {
                    String head;
                    synchronized (this) {
                        head = authoritativeHead;
                    }
                    respond(exchange, 200, Map.of("head", Map.of("sha", head)));
                }
                return;
            }
            if (method.equals("GET") && path.equals("/repositories/73/zipball/" + HEAD_SHA)) {
                respond(exchange, 200, ARCHIVE, "application/zip");
                return;
            }
            if (method.equals("GET") && path.equals("/repositories/73/commits/" + HEAD_SHA + "/check-runs")) {
                List<Map<String, Object>> response;
                synchronized (this) {
                    response = checks.stream()
                            .map(check -> Map.<String, Object>of(
                                    "id", check.id(), "external_id", check.externalId()))
                            .toList();
                }
                respond(exchange, 200, Map.of("check_runs", response));
                return;
            }
            if (method.equals("POST") && path.equals("/repositories/73/check-runs")) {
                JsonNode json = JSON.readTree(body);
                Check check = new Check(
                        Long.toString(ids.incrementAndGet()),
                        json.path("head_sha").asText(),
                        json.path("external_id").asText());
                synchronized (this) {
                    checks.add(check);
                    checkMutationCount++;
                }
                respond(exchange, 201, Map.of("id", check.id()));
                return;
            }
            if (method.equals("PATCH") && path.startsWith("/repositories/73/check-runs/")) {
                synchronized (this) {
                    checkMutationCount++;
                }
                respond(exchange, 200, Map.of("id", path.substring(path.lastIndexOf('/') + 1)));
                return;
            }
            if (method.equals("GET") && path.equals("/repositories/73/pulls/12/comments")) {
                List<Map<String, Object>> response;
                synchronized (this) {
                    response = comments.stream().map(Comment::asResponse).toList();
                }
                respond(exchange, 200, response);
                return;
            }
            if (method.equals("POST") && path.equals("/repositories/73/pulls/12/comments")) {
                JsonNode json = JSON.readTree(body);
                int attempt;
                synchronized (this) {
                    commentAttemptLines.add(json.path("line").asInt());
                    attempt = commentAttemptLines.size();
                    if (attempt == failedCommentAttempt) {
                        failedCommentAttempt = -1;
                    } else {
                        attempt = -1;
                    }
                }
                if (attempt != -1) {
                    respond(exchange, 500, Map.of("message", "temporary failure"));
                    return;
                }
                Comment comment = new Comment(
                        Long.toString(ids.incrementAndGet()),
                        json.path("commit_id").asText(),
                        json.path("path").asText(),
                        json.path("line").asInt(),
                        json.path("side").asText(),
                        json.path("body").asText());
                synchronized (this) {
                    comments.add(comment);
                }
                respond(exchange, 201, Map.of("id", comment.id()));
                return;
            }
            respond(exchange, 404, Map.of("message", "not found"));
        }

        private static byte[] archive() {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
                    zip.putNextEntry(new ZipEntry("octo-repo/"));
                    zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("octo-repo/src/"));
                    zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("octo-repo/src/Example.java"));
                    zip.write("class Example {}\n".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                return bytes.toByteArray();
            } catch (IOException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        private static void respond(HttpExchange exchange, int status, Object value)
                throws IOException {
            respond(exchange, status, JSON.writeValueAsBytes(value), "application/json");
        }

        private static void respond(
                HttpExchange exchange, int status, byte[] body, String contentType)
                throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        record Check(String id, String headSha, String externalId) {
        }

        record Comment(
                String id,
                String commitId,
                String path,
                int line,
                String side,
                String body) {

            Map<String, Object> asResponse() {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("id", id);
                response.put("commit_id", commitId);
                response.put("original_commit_id", commitId);
                response.put("path", path);
                response.put("line", line);
                response.put("side", side);
                response.put("body", body);
                response.put("performed_via_github_app", Map.of("id", 123));
                return response;
            }
        }
    }
}
