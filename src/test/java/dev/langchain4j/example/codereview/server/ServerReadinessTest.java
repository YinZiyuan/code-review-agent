package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.reviewops.application.PresentReviewFailure;
import dev.langchain4j.example.codereview.reviewops.application.SettleReviewJobFailure;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewFailurePresentationJobHandler;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.PostgresReviewOperationsRetention;
import dev.langchain4j.example.codereview.reviewops.infrastructure.observability.ReviewOperationsMetrics;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationPolicySnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.HttpURLConnection;
import java.net.URI;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("server")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "code-review.runtime=server",
        "code-review.server.worker.poll-interval=1h",
        "management.endpoints.web.exposure.include=health,metrics"
})
@Import(ServerReadinessTest.TestReviewerConfiguration.class)
class ServerReadinessTest {

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
        properties.add("code-review.server.github.webhook-secret", () -> "readiness-webhook-secret");
        properties.add("code-review.server.github.api-base-url", () -> "http://127.0.0.1:9");
        properties.add("langchain4j.open-ai.chat-model.api-key", () -> "readiness-model-key");
    }

    @LocalServerPort
    private int port;

    @org.springframework.beans.factory.annotation.Autowired
    private ApplicationContext context;

    @AfterAll
    static void restoreDockerApiVersion() {
        if (PREVIOUS_DOCKER_API_VERSION == null) {
            System.clearProperty("api.version");
        } else {
            System.setProperty("api.version", PREVIOUS_DOCKER_API_VERSION);
        }
    }

    @Test
    void healthIsProcessLivenessWhileReadinessRequiresDatabaseAndIntakeWiring()
            throws Exception {
        HttpResult live = get("/actuator/health");
        HttpResult ready = get("/actuator/health/readiness");
        HttpResult metrics = get("/actuator/metrics");

        assertThat(live.status()).isEqualTo(200);
        assertThat(live.body()).contains("\"status\":\"UP\"");
        assertThat(ready.status()).isEqualTo(200);
        assertThat(ready.body()).contains("\"status\":\"UP\"");
        assertThat(metrics.status()).isEqualTo(200);
        assertThat(metrics.body())
                .doesNotContain("readiness-webhook-secret")
                .doesNotContain("readiness-model-key")
                .doesNotContain(PRIVATE_KEY)
                .doesNotContain("systemEnvironment");

        POSTGRES.stop();

        HttpResult unavailable = awaitReadinessUnavailable();
        assertThat(unavailable.status()).isEqualTo(503);
        assertThat(unavailable.body()).contains("\"status\":\"DOWN\"");
        assertThat(get("/actuator/health").status()).isEqualTo(200);
    }

    @Test
    void serverWiresTerminalSettlementAndNeutralFailurePresentation() {
        assertThat(context.getBeansOfType(SettleReviewJobFailure.class)).hasSize(1);
        assertThat(context.getBeansOfType(PresentReviewFailure.class)).hasSize(1);
        assertThat(context.getBeansOfType(ReviewFailurePresentationJobHandler.class)).hasSize(1);
        assertThat(context.getBeansOfType(PostgresReviewOperationsRetention.class)).hasSize(1);
        assertThat(context.getBeansOfType(ScheduledReviewOperationsRetention.class)).hasSize(1);
        assertThat(context.getBeansOfType(ReviewOperationsMetrics.class)).hasSize(1);
        assertThat(context.getBeansOfType(ScheduledReviewOperationsMetrics.class)).hasSize(1);
        assertThat(context.getBeansOfType(ReviewOperationLogger.class)).hasSize(1);
        ReviewWorkBudget workBudget = context.getBean(ReviewWorkBudget.class);
        assertThat(context.getBean(ReviewWorkBudgetIdentityProvider.class).workBudgetIdentity())
                .isEqualTo(workBudget.configurationHash());
        ReviewConfigurationSnapshot snapshot = context.getBean(ReviewConfigurationSnapshot.class);
        assertThat(snapshot.modelName()).isEqualTo("moonshot-v1-8k");
        assertThat(snapshot.configurationVersion()).matches("cfg-sha256-[0-9a-f]{64}");
        assertThat(snapshot.toString())
                .doesNotContain("readiness-webhook-secret")
                .doesNotContain("readiness-model-key")
                .doesNotContain(PRIVATE_KEY);
        assertThat(context.getBean(PublicationPolicySnapshot.class).version())
                .isEqualTo(snapshot.policyVersion());
    }

    private HttpResult awaitReadinessUnavailable() throws Exception {
        HttpResult result = get("/actuator/health/readiness");
        for (int attempt = 0; attempt < 20 && result.status() != 503; attempt++) {
            Thread.sleep(100);
            result = get("/actuator/health/readiness");
        }
        return result;
    }

    private HttpResult get(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "http://localhost:" + port + path).toURL().openConnection();
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] body = stream == null ? new byte[0] : stream.readAllBytes();
        return new HttpResult(status, new String(body, StandardCharsets.UTF_8));
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TestReviewerConfiguration {

        @Bean
        @Primary
        CodeReviewAgent testReviewer() {
            ReviewResult result = new ReviewResult("unused", List.of(), List.of());
            return new CodeReviewAgent() {
                @Override
                public ReviewResult review(String request, Path sourceRoot) {
                    return result;
                }

                @Override
                public ReviewExecution reviewWithTelemetry(String request, Path sourceRoot) {
                    return new ReviewExecution(result, 0, 0);
                }
            };
        }
    }

    private record HttpResult(int status, String body) {
    }
}
