package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class LlmRerankerTest {

    private static Content content(String id, String text) {
        return Content.from(TextSegment.from(text, Metadata.from("citation_id", id)));
    }

    @Test
    void reordersByLlmScore() {
        ChatModel model = Mockito.mock(ChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"scores\":[0.2,0.9,0.5]}"))
                .build();
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class))).thenReturn(response);

        ContentRetriever upstream = q -> List.of(
                content("id-A", "A text"),
                content("id-B", "B text"),
                content("id-C", "C text"));
        LlmReranker reranker = new LlmReranker(upstream, model, 2);

        List<Content> out = reranker.retrieve(Query.from("q"));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).textSegment().metadata().getString("citation_id")).isEqualTo("id-B");
        assertThat(out.get(1).textSegment().metadata().getString("citation_id")).isEqualTo("id-C");
    }

    @Test
    void onJudgeFailureReturnsOriginalOrder() {
        ChatModel model = Mockito.mock(ChatModel.class);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenThrow(new RuntimeException("judge down"));

        ContentRetriever upstream = q -> List.of(content("id-A", "A"), content("id-B", "B"));
        LlmReranker reranker = new LlmReranker(upstream, model, 5);

        List<Content> out = reranker.retrieve(Query.from("q"));

        assertThat(out).extracting(x -> x.textSegment().metadata().getString("citation_id"))
                .containsExactly("id-A", "id-B");
    }
}
