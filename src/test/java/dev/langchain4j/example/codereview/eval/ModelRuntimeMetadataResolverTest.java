package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRuntimeMetadataResolverTest {

    @Test
    void resolvesSafeMetadataFromEffectiveOpenAiCompatibleConfiguration() throws Exception {
        String secret = "test-secret-that-must-not-be-serialized";
        MockEnvironment environment = new MockEnvironment()
                .withProperty("langchain4j.open-ai.chat-model.base-url", "https://sub2api.apemind.ai/v1")
                .withProperty("langchain4j.open-ai.chat-model.api-key", secret)
                .withProperty("langchain4j.open-ai.chat-model.model-name", "gpt-5.6-luna");

        ModelRuntimeMetadata metadata = new ModelRuntimeMetadataResolver(environment).resolve();

        assertThat(metadata.provider()).isEqualTo("openai-compatible");
        assertThat(metadata.baseUrlHost()).isEqualTo("sub2api.apemind.ai");
        assertThat(metadata.reviewerModel()).isEqualTo("gpt-5.6-luna");
        assertThat(metadata.rerankerModel()).isEqualTo("gpt-5.6-luna");
        assertThat(metadata.judgeModel()).isEqualTo("gpt-5.6-luna");
        assertThat(new ObjectMapper().writeValueAsString(metadata))
                .doesNotContain(secret)
                .doesNotContain("apiKey")
                .doesNotContain("authorization");
    }

    @Test
    void moonshotProviderDetectionRequiresADomainBoundary() {
        MockEnvironment moonshot = new MockEnvironment()
                .withProperty("langchain4j.open-ai.chat-model.base-url", "https://api.moonshot.cn/v1");
        MockEnvironment lookalike = new MockEnvironment()
                .withProperty("langchain4j.open-ai.chat-model.base-url", "https://notmoonshot.cn/v1");
        MockEnvironment uppercase = new MockEnvironment()
                .withProperty("langchain4j.open-ai.chat-model.base-url", "https://API.MOONSHOT.CN/v1");

        assertThat(new ModelRuntimeMetadataResolver(moonshot).resolve().provider()).isEqualTo("moonshot");
        assertThat(new ModelRuntimeMetadataResolver(uppercase).resolve().provider()).isEqualTo("moonshot");
        assertThat(new ModelRuntimeMetadataResolver(lookalike).resolve().provider())
                .isEqualTo("openai-compatible");
    }
}
