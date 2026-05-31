package dev.langchain4j.example.codereview.agents.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.model.ToolStatus;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmReviewerTest {

    @Test
    void valid_llm_json_becomes_draft_review_result() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("""
                                {"summary":"ok","findings":[],"tool_status":[]}"""))
                        .build());

        ContentRetriever retriever = q -> List.of();
        CitationTracker tracker = new CitationTracker();
        JsonRepair repair = new JsonRepair(model, new ObjectMapper());

        LlmReviewer reviewer = new LlmReviewer(model, retriever, tracker, repair);

        ReviewContext ctx = new ReviewContext("diff", List.of(), Map.of(), Path.of("/tmp"));
        ToolFindings tools = new ToolFindings(List.of(), List.of(new ToolStatus("regex", "ok", null)));

        var draft = reviewer.review(ctx, tools);

        assertThat(draft.result().summary()).isEqualTo("ok");
        assertThat(draft.citationCandidates()).isEmpty();
    }

    @Test
    void citation_candidates_are_passed_through() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("""
                                {"summary":"ok","findings":[],"tool_status":[]}"""))
                        .build());

        Content c = Content.from(TextSegment.from("body",
                Metadata.from(Map.of(
                        "citation_id", "sql#x",
                        "source_file", "sql.txt",
                        "section", "X"))));
        ContentRetriever retriever = q -> List.of(c);
        LlmReviewer reviewer = new LlmReviewer(model, retriever, new CitationTracker(),
                new JsonRepair(model, new ObjectMapper()));

        ReviewContext ctx = new ReviewContext("diff",
                List.of(new DiffParser.FileDiff("Foo.java", List.of())),
                Map.of(), Path.of("/tmp"));
        var draft = reviewer.review(ctx, new ToolFindings(List.of(
                new Violation(Severity.CRITICAL, "Foo.java", 1, "x", "msg")), List.of()));

        assertThat(draft.citationCandidates()).hasSize(1);
        assertThat(draft.citationCandidates().get(0).id()).isEqualTo("sql#x");
    }
}
