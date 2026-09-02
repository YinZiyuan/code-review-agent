package dev.langchain4j.example.codereview.agents.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.model.ToolRunState;
import dev.langchain4j.example.codereview.model.ToolStatus;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmReviewerTest {

    @Test
    void valid_llm_json_becomes_draft_review_result() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("""
                                {"summary":"ok","findings":[],"tool_status":[]}"""))
                        .tokenUsage(new TokenUsage(83, 17))
                        .build());

        ContentRetriever retriever = q -> List.of();
        CitationTracker tracker = new CitationTracker();
        JsonRepair repair = new JsonRepair(model, new ObjectMapper());

        LlmReviewer reviewer = reviewer(model, retriever, tracker, repair);

        ReviewContext ctx = new ReviewContext("diff", List.of(), Map.of(), Path.of("/tmp"));
        ToolFindings tools = new ToolFindings(List.of(), List.of(new ToolStatus("regex", ToolRunState.RAN, null)));

        var draft = reviewer.review(ctx, tools);

        assertThat(draft.result().summary()).isEqualTo("ok");
        assertThat(draft.citationCandidates()).isEmpty();
        assertThat(draft.inputTokens()).isEqualTo(83);
        assertThat(draft.outputTokens()).isEqualTo(17);
    }

    @Test
    void citation_candidates_are_passed_through() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("""
                                {"summary":"ok","findings":[],"tool_status":[]}"""))
                        .tokenUsage(new TokenUsage(40, 8))
                        .build());

        Content c = Content.from(TextSegment.from("body",
                Metadata.from(Map.of(
                        "citation_id", "sql#x",
                        "source_file", "sql.txt",
                        "section", "X"))));
        ContentRetriever retriever = q -> List.of(c);
        LlmReviewer reviewer = reviewer(model, retriever, new CitationTracker(),
                new JsonRepair(model, new ObjectMapper()));

        ReviewContext ctx = new ReviewContext("diff",
                List.of(new DiffParser.FileDiff("Foo.java", List.of())),
                Map.of(), Path.of("/tmp"));
        var draft = reviewer.review(ctx, new ToolFindings(List.of(
                new Violation(Severity.CRITICAL, "Foo.java", 1, "x", "msg")), List.of()));

        assertThat(draft.citationCandidates()).hasSize(1);
        assertThat(draft.citationCandidates().get(0).id()).isEqualTo("sql#x");
    }

    @Test
    void formatRepairUsageIsAddedToTheMainModelUsage() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(
                        ChatResponse.builder()
                                .aiMessage(AiMessage.from(
                                        "{\"summary\":\"ok\" \"findings\":[],\"tool_status\":[]}"))
                                .tokenUsage(new TokenUsage(100, 10))
                                .build(),
                        ChatResponse.builder()
                                .aiMessage(AiMessage.from(
                                        "{\"summary\":\"ok\",\"findings\":[],\"tool_status\":[]}"))
                                .tokenUsage(new TokenUsage(25, 6))
                                .build());
        LlmReviewer reviewer = reviewer(
                model, q -> List.of(), new CitationTracker(),
                new JsonRepair(model, new ObjectMapper()));

        var draft = reviewer.review(
                new ReviewContext("diff", List.of(), Map.of(), Path.of("/tmp")),
                new ToolFindings(List.of(), List.of()));

        assertThat(draft.inputTokens()).isEqualTo(125);
        assertThat(draft.outputTokens()).isEqualTo(16);
    }

    @Test
    void exhaustedFormatRepairCarriesBothResponsesUsage() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(
                        ChatResponse.builder()
                                .aiMessage(AiMessage.from("not json"))
                                .tokenUsage(new TokenUsage(100, 10))
                                .build(),
                        ChatResponse.builder()
                                .aiMessage(AiMessage.from("still not json"))
                                .tokenUsage(new TokenUsage(25, 6))
                                .build());
        LlmReviewer reviewer = reviewer(
                model, q -> List.of(), new CitationTracker(),
                new JsonRepair(model, new ObjectMapper()));

        CodeReviewAgent.ReviewExecutionException failure = catchThrowableOfType(
                CodeReviewAgent.ReviewExecutionException.class,
                () -> reviewer.review(
                        new ReviewContext("diff", List.of(), Map.of(), Path.of("/tmp")),
                        new ToolFindings(List.of(), List.of())));

        assertThat(failure.inputTokens()).isEqualTo(125);
        assertThat(failure.outputTokens()).isEqualTo(16);
        assertThat(failure.getCause()).isInstanceOf(JsonRepair.RepairFailedException.class);
    }

    @Test
    void prompt_keeps_v3_finding_policy_without_tuning_rules() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("""
                                {"summary":"ok","findings":[],"tool_status":[]}"""))
                        .tokenUsage(new TokenUsage(30, 6))
                        .build());

        LlmReviewer reviewer = reviewer(model, q -> List.of(), new CitationTracker(),
                new JsonRepair(model, new ObjectMapper()));

        reviewer.review(new ReviewContext("diff", List.of(), Map.of(), Path.of("/tmp")),
                new ToolFindings(List.of(), List.of()));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        String prompt = ((UserMessage) captor.getValue().messages().get(0)).singleText();

        assertThat(prompt).doesNotContain("Use only the category enum values listed in the JSON schema");
        assertThat(prompt).doesNotContain("Severity calibration");
        assertThat(prompt).doesNotContain("return an empty findings array");
    }

    @Test
    void requestReservesCompletionTokensAndMetersOnlyBoundedTokenCounts() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(
                                "{\"summary\":\"ok\",\"findings\":[],\"tool_status\":[]}"))
                        .tokenUsage(new TokenUsage(30, 6))
                        .build());
        ReviewWorkBudget budget = new ReviewWorkBudgetProperties(
                null, null, null, null, null, null).toBudget();
        PromptTokenizer tokenizer = new JTokkitPromptTokenizer();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        LlmReviewer reviewer = new LlmReviewer(
                model,
                query -> List.of(),
                new CitationTracker(),
                new JsonRepair(model, new ObjectMapper()),
                new ReviewPromptAssembler(tokenizer, budget),
                budget,
                metrics);

        reviewer.review(
                new ReviewContext("+large diff line\n".repeat(10_000),
                        List.of(), Map.of(), Path.of("/tmp")),
                new ToolFindings(List.of(), List.of()));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(captor.capture());
        ChatRequest request = captor.getValue();
        String prompt = ((UserMessage) request.messages().get(0)).singleText();
        assertThat(request.maxOutputTokens())
                .isEqualTo(budget.prompt().completionReserveTokens());
        assertThat(tokenizer.count(prompt)).isLessThanOrEqualTo(budget.maxPromptTokens());
        assertThat(metrics.get("code.review.pipeline.prompt.tokens.estimated")
                .counter().count()).isPositive();
        assertThat(metrics.get("code.review.model.tokens.billed")
                .tag("direction", "input").tag("call_scope", "main_and_repair")
                .counter().count()).isEqualTo(30);
        assertThat(metrics.get("code.review.model.tokens.billed")
                .tag("direction", "output").tag("call_scope", "main_and_repair")
                .counter().count()).isEqualTo(6);
    }

    private static LlmReviewer reviewer(
            ChatModel model,
            ContentRetriever retriever,
            CitationTracker tracker,
            JsonRepair repair) {
        ReviewWorkBudget budget = new ReviewWorkBudgetProperties(
                null, null, null, null, null, null).toBudget();
        PromptTokenizer tokenizer = new JTokkitPromptTokenizer();
        return new LlmReviewer(
                model,
                retriever,
                tracker,
                repair,
                new ReviewPromptAssembler(tokenizer, budget),
                budget,
                new SimpleMeterRegistry());
    }
}
