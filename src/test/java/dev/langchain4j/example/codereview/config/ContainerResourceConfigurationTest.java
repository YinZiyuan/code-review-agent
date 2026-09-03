package dev.langchain4j.example.codereview.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerResourceConfigurationTest {

    @SuppressWarnings("unchecked")
    @Test
    void applicationContainerHasEffectiveCpuMemoryAndPidBounds() throws Exception {
        Map<String, Object> compose = new Yaml().load(Files.readString(Path.of("compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> app = (Map<String, Object>) services.get("app");

        assertThat(app.get("mem_limit")).isEqualTo("1g");
        assertThat(app.get("cpus")).isEqualTo("1.0");
        assertThat(app.get("pids_limit")).isEqualTo(256);
    }

    @SuppressWarnings("unchecked")
    @Test
    void applicationContainerForwardsTheOpenAiCompatibleEndpointAndModel() throws Exception {
        Map<String, Object> compose = new Yaml().load(Files.readString(Path.of("compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> app = (Map<String, Object>) services.get("app");
        Map<String, Object> environment = (Map<String, Object>) app.get("environment");

        assertThat(environment.get("LANGCHAIN4J_OPEN_AI_CHAT_MODEL_API_KEY"))
                .isEqualTo("${APEMIND_API_KEY:-${MOONSHOT_API_KEY:-}}");
        assertThat(environment).doesNotContainKeys("APEMIND_API_KEY", "MOONSHOT_API_KEY");
        assertThat(environment.get("LANGCHAIN4J_OPEN_AI_CHAT_MODEL_BASE_URL"))
                .isEqualTo("${LANGCHAIN4J_OPEN_AI_CHAT_MODEL_BASE_URL:-https://sub2api.apemind.ai/v1}");
        assertThat(environment.get("LANGCHAIN4J_OPEN_AI_CHAT_MODEL_MODEL_NAME"))
                .isEqualTo("${LANGCHAIN4J_OPEN_AI_CHAT_MODEL_MODEL_NAME:-gpt-5.6-sol}");
        assertThat(environment.get("LANGCHAIN4J_OPEN_AI_CHAT_MODEL_TIMEOUT"))
                .isEqualTo("${LANGCHAIN4J_OPEN_AI_CHAT_MODEL_TIMEOUT:-180s}");
        assertThat(environment.get("CODE_REVIEW_WORK_BUDGET_STAGES_REVIEW_MODEL"))
                .isEqualTo("${CODE_REVIEW_WORK_BUDGET_STAGES_REVIEW_MODEL:-180s}");
        assertThat(environment.get("CODE_REVIEW_WORK_BUDGET_EXECUTION_REVIEWER_TIMEOUT"))
                .isEqualTo("${CODE_REVIEW_WORK_BUDGET_EXECUTION_REVIEWER_TIMEOUT:-300s}");
        assertThat(environment.get("CODE_REVIEW_SERVER_WORKER_BATCH_SIZE"))
                .isEqualTo("${CODE_REVIEW_SERVER_WORKER_BATCH_SIZE:-1}");
        assertThat(environment.get("DB_POOL_ACQUISITION_TIMEOUT_MS"))
                .isEqualTo("${DB_POOL_ACQUISITION_TIMEOUT_MS:-2000}");
        assertThat(environment.get("DB_POOL_VALIDATION_TIMEOUT_MS"))
                .isEqualTo("${DB_POOL_VALIDATION_TIMEOUT_MS:-1000}");
    }

    @SuppressWarnings("unchecked")
    @Test
    void defaultModelTransportAllowsTheFullApeMindReviewDeadline() throws Exception {
        Map<String, Object> configuration = new Yaml().load(
                Files.readString(Path.of("src/main/resources/application.yml")));
        Map<String, Object> langchain4j = (Map<String, Object>) configuration.get("langchain4j");
        Map<String, Object> openAi = (Map<String, Object>) langchain4j.get("open-ai");
        Map<String, Object> chatModel = (Map<String, Object>) openAi.get("chat-model");

        assertThat(chatModel.get("timeout"))
                .isEqualTo("${LANGCHAIN4J_OPEN_AI_CHAT_MODEL_TIMEOUT:180s}");
    }

    @SuppressWarnings("unchecked")
    @Test
    void hikariAndValidatedDatabaseBoundsShareTheSameTimeoutInputs() throws Exception {
        Map<String, Object> configuration = new Yaml().load(
                Files.readString(Path.of("src/main/resources/application-server.yml")));
        Map<String, Object> spring = (Map<String, Object>) configuration.get("spring");
        Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
        Map<String, Object> hikari = (Map<String, Object>) datasource.get("hikari");
        Map<String, Object> codeReview = (Map<String, Object>) configuration.get("code-review");
        Map<String, Object> server = (Map<String, Object>) codeReview.get("server");
        Map<String, Object> database = (Map<String, Object>) server.get("database");

        assertThat(hikari.get("connection-timeout"))
                .isEqualTo("${DB_POOL_ACQUISITION_TIMEOUT_MS:2000}");
        assertThat(database.get("acquisition-timeout"))
                .isEqualTo("${DB_POOL_ACQUISITION_TIMEOUT_MS:2000}ms");
        assertThat(hikari.get("validation-timeout"))
                .isEqualTo("${DB_POOL_VALIDATION_TIMEOUT_MS:1000}");
        assertThat(database.get("validation-timeout"))
                .isEqualTo("${DB_POOL_VALIDATION_TIMEOUT_MS:1000}ms");
    }

    @Test
    void runtimeJvmHonorsContainerMemoryAndExitsOnOom() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertThat(dockerfile)
                .contains("-XX:MaxRAMPercentage=45.0")
                .contains("-XX:MaxDirectMemorySize=128m")
                .contains("-XX:+ExitOnOutOfMemoryError");
    }
}
