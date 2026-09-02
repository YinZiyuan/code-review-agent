package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "langchain4j.open-ai.chat-model.api-key=test",
        "spring.main.allow-bean-definition-overriding=true"
})
class PipelineCodeReviewerIT {

    @Autowired
    CodeReviewAgent agent;

    @Test
    void end_to_end_review_returns_result_with_tool_status() {
        String request = """
                Review the following diff. The full diff is below; do not call git tools.

                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,1 +1,2 @@
                 class Foo {
                +  String password = "hunter2";
                """;

        ReviewResult result = agent.review(request);

        assertThat(result.toolStatus()).isNotEmpty();
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        @Primary
        ChatModel chatModel() {
            ChatModel model = mock(ChatModel.class);
            when(model.chat(any(ChatRequest.class)))
                    .thenReturn(ChatResponse.builder()
                            .aiMessage(AiMessage.from("""
                                    {"summary":"ok","findings":[],"tool_status":[]}"""))
                            .tokenUsage(new TokenUsage(80, 12))
                            .build());
            return model;
        }

        @Bean
        @Primary
        ContentRetriever contentRetriever() {
            return query -> List.of();
        }
    }
}
