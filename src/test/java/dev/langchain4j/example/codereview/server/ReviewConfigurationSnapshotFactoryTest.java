package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.config.CodeReviewProperties;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewConfigurationSnapshotFactoryTest {

    @Test
    void derivesActualModelAndStableConfigurationHashFromRuntimeReviewSettings() {
        MockEnvironment environment = modelEnvironment("kimi-k2.5", "secret-a");
        ReviewIdentityProperties identity = identity("budget-sha256-a");

        ReviewConfigurationSnapshot first = create(environment, properties(3), identity, worker());
        ReviewConfigurationSnapshot repeated = create(environment, properties(3), identity, worker());

        assertThat(first.modelName()).isEqualTo("kimi-k2.5");
        assertThat(first.pipelineVersion()).isEqualTo("pipeline-v3");
        assertThat(first.policyVersion()).isEqualTo("policy-v1");
        assertThat(first.maxReviewAttempts()).isEqualTo(3);
        assertThat(first.configurationVersion())
                .matches("cfg-sha256-[0-9a-f]{64}")
                .isEqualTo(repeated.configurationVersion());
    }

    @Test
    void modelRagRetryPolicyPromptAndWorkBudgetChangesAlterBusinessIdentity() {
        ReviewConfigurationSnapshot baseline = create(
                modelEnvironment("kimi-k2.5", "secret-a"),
                properties(3), identity("budget-sha256-a"), worker());

        assertDifferent(baseline, create(
                modelEnvironment("kimi-k2.6", "secret-a"),
                properties(3), identity("budget-sha256-a"), worker()));
        assertDifferent(baseline, create(
                modelEnvironment("kimi-k2.5", "secret-a"),
                properties(4), identity("budget-sha256-a"), worker()));
        assertDifferent(baseline, create(
                modelEnvironment("kimi-k2.5", "secret-a"),
                properties(3), new ReviewIdentityProperties(
                        "pipeline-v3", "review-prompt-v2", "policy-v1", "moonshot-cn-v1",
                        "budget-sha256-a", 3, 5), worker()));
        assertDifferent(baseline, create(
                modelEnvironment("kimi-k2.5", "secret-a"),
                properties(3), new ReviewIdentityProperties(
                        "pipeline-v3", "review-prompt-v1", "policy-v2", "moonshot-cn-v1",
                        "budget-sha256-a", 3, 6), worker()));
        assertDifferent(baseline, create(
                modelEnvironment("kimi-k2.5", "secret-a"),
                properties(3), identity("budget-sha256-b"), worker()));
        assertDifferent(baseline, create(
                modelEnvironment("kimi-k2.5", "secret-a"),
                properties(3), new ReviewIdentityProperties(
                        "pipeline-v3", "review-prompt-v1", "policy-v1", "moonshot-cn-v1",
                        "budget-sha256-a", 4, 5), worker()));
        assertDifferent(baseline, create(
                modelEnvironment("kimi-k2.5", "secret-a"),
                properties(3), new ReviewIdentityProperties(
                        "pipeline-v3", "review-prompt-v1", "policy-v1", "moonshot-us-v1",
                        "budget-sha256-a", 3, 5), worker()));
    }

    @Test
    void directlyInjectedWorkBudgetIdentityOverridesTheLegacyPropertySeam() {
        MockEnvironment environment = modelEnvironment("kimi-k2.5", "secret-a");
        ReviewIdentityProperties identity = identity("legacy-budget-property");
        ReviewConfigurationSnapshot first = new ReviewConfigurationSnapshotFactory(environment)
                .create(properties(3), identity, worker(), () -> "budget-computed-by-stream-b-a");
        ReviewConfigurationSnapshot second = new ReviewConfigurationSnapshotFactory(environment)
                .create(properties(3), identity, worker(), () -> "budget-computed-by-stream-b-b");

        assertDifferent(first, second);
    }

    @Test
    void rejectsCredentialBearingModelEndpointsWithoutEchoingSecrets() {
        for (String endpoint : new String[]{
                "https://user:password-secret@api.example.test/v1",
                "https://api.example.test/v1?api_key=query-secret",
                "https://api.example.test/v1/token=path-secret",
                "https://api.example.test/v1/sk-abcdef1234567890"
        }) {
            MockEnvironment environment = modelEnvironment("kimi-k2.5", "secret-a")
                    .withProperty("langchain4j.open-ai.chat-model.base-url", endpoint);

            assertThatThrownBy(() -> create(
                    environment, properties(3), identity("budget-sha256-a"), worker()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("model base URL must not contain credentials")
                    .hasMessageNotContaining("password-secret")
                    .hasMessageNotContaining("query-secret")
                    .hasMessageNotContaining("path-secret");
        }
    }

    @Test
    void rejectsEndpointUrisAsExplicitDeploymentIdentity() {
        assertThatThrownBy(() -> new ReviewIdentityProperties(
                "pipeline-v3", "review-prompt-v1", "policy-v1",
                "https://api.example.test/v1/token=secret", "budget", 3, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelDeploymentIdentity");
    }

    @Test
    void secretsDoNotEnterTheHashOrPersistedSnapshot() {
        String firstSecret = "database-password-and-api-key-a";
        String secondSecret = "database-password-and-api-key-b";

        ReviewConfigurationSnapshot first = create(
                modelEnvironment("kimi-k2.5", firstSecret),
                properties(3), identity("budget-sha256-a"), worker());
        ReviewConfigurationSnapshot second = create(
                modelEnvironment("kimi-k2.5", secondSecret),
                properties(3), identity("budget-sha256-a"), worker());

        assertThat(first).isEqualTo(second);
        assertThat(first.toString())
                .doesNotContain(firstSecret)
                .doesNotContain(secondSecret)
                .doesNotContain("api-key", "password", "private-key", "webhook-secret");
    }

    private static ReviewConfigurationSnapshot create(
            MockEnvironment environment,
            CodeReviewProperties properties,
            ReviewIdentityProperties identity,
            ServerProperties.Worker worker) {
        return new ReviewConfigurationSnapshotFactory(environment)
                .create(properties, identity, worker, identity::workBudgetIdentity);
    }

    private static void assertDifferent(
            ReviewConfigurationSnapshot baseline,
            ReviewConfigurationSnapshot changed) {
        assertThat(changed.configurationVersion())
                .isNotEqualTo(baseline.configurationVersion());
    }

    private static MockEnvironment modelEnvironment(String model, String secret) {
        return new MockEnvironment()
                .withProperty("langchain4j.open-ai.chat-model.base-url", "https://api.moonshot.cn/v1")
                .withProperty("langchain4j.open-ai.chat-model.model-name", model)
                .withProperty("langchain4j.open-ai.chat-model.temperature", "0")
                .withProperty("langchain4j.open-ai.chat-model.max-tokens", "4096")
                .withProperty("langchain4j.open-ai.chat-model.timeout", "90s")
                .withProperty("langchain4j.open-ai.chat-model.api-key", secret)
                .withProperty("spring.datasource.password", secret)
                .withProperty("code-review.server.github.private-key", secret)
                .withProperty("code-review.server.github.webhook-secret", secret);
    }

    private static CodeReviewProperties properties(int topK) {
        return new CodeReviewProperties(
                new CodeReviewProperties.Rag(
                        Path.of("/cache/location-does-not-affect-review"),
                        topK, 0.4, true, 8, 4, 60),
                new CodeReviewProperties.Orchestration(Duration.ofSeconds(60), 3),
                new CodeReviewProperties.Eval("judge-model", 1, Path.of("samples"), Path.of("reports")));
    }

    private static ReviewIdentityProperties identity(String workBudgetIdentity) {
        return new ReviewIdentityProperties(
                "pipeline-v3", "review-prompt-v1", "policy-v1", "moonshot-cn-v1",
                workBudgetIdentity, 3, 5);
    }

    private static ServerProperties.Worker worker() {
        return new ServerProperties.Worker(
                Duration.ofSeconds(1), 10, 10,
                Duration.ofMinutes(3), Duration.ofSeconds(30),
                Duration.ofSeconds(10), Duration.ofMinutes(5), 0.2);
    }
}
