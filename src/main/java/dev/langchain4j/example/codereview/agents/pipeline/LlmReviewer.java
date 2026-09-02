package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmReviewer {

    public record Draft(
            ReviewResult result,
            List<Citation> citationCandidates,
            int inputTokens,
            int outputTokens) {
        public Draft {
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("model token usage must be non-negative");
            }
        }
    }

    private static final String SYSTEM = """
            You are a senior software engineer doing a code review.
            You are given: (a) a unified diff, (b) deterministic tool findings, (c) optional
            grep-style cross-file context, and (d) a numbered list of citation candidates from
            a vetted knowledge base.

            Return ONLY a single JSON object matching ReviewResult:
              {
                "summary": "1-2 sentences",
                "findings": [
                  {
                    "id": "F-001",
                    "file": "...",
                    "line": <int|null>,
                    "line_range": [<int>, <int>] or null,
                    "severity": "CRITICAL" | "WARNING" | "SUGGESTION",
                    "category": "SECURITY" | "PERFORMANCE" | "STABILITY" | "CONCURRENCY"
                                | "TEST" | "STYLE" | "OTHER",
                    "title": "<=80 chars",
                    "description": "...",
                    "suggestion": "...",
                    "evidence": "...",
                    "citations": [ { "id": "...", "source": "...", "section": "..." } ],
                    "source": "llm_reviewer"
                  }
                ],
                "tool_status": []
              }

            Rules:
            - Line numbers refer to the NEW file (post-change).
            - You MUST only put citations in 'citations[]' whose 'id' appears in the candidates
              list below. Do NOT invent citation IDs. Empty 'citations[]' is allowed.
            - Echo tool findings only when you agree with them; if you echo, set 'source' to
              the analyzer name (e.g. 'regex', 'spotbugs').
            - Leave 'tool_status' as []; the pipeline will fill it.
            - Output a single JSON object - no prose, no markdown fences.
            """;

    private final ChatModel chatModel;
    private final ContentRetriever retriever;
    private final CitationTracker tracker;
    private final JsonRepair jsonRepair;
    private final ReviewPromptAssembler promptAssembler;
    private final ReviewWorkBudget budget;
    private final MeterRegistry metrics;

    public LlmReviewer(ChatModel chatModel,
                       ContentRetriever retriever,
                       CitationTracker tracker,
                       JsonRepair jsonRepair,
                       ReviewPromptAssembler promptAssembler,
                       ReviewWorkBudget budget,
                       MeterRegistry metrics) {
        this.chatModel = chatModel;
        this.retriever = retriever;
        this.tracker = tracker;
        this.jsonRepair = jsonRepair;
        this.promptAssembler = promptAssembler;
        this.budget = budget;
        this.metrics = metrics;
    }

    public Draft review(ReviewContext ctx, ToolFindings tools) {
        String query = buildQuery(ctx, tools);
        if (query.isBlank()) {
            query = ctx.rawDiff() == null || ctx.rawDiff().isBlank() ? "code review" : ctx.rawDiff();
        }
        List<Content> hits = retriever.retrieve(Query.from(query));
        List<Citation> candidates = tracker.toCitations(hits);

        ReviewPromptAssembler.AssembledPrompt prompt =
                promptAssembler.assemble(SYSTEM, ctx, tools, candidates);
        metrics.counter("code.review.pipeline.tokens", "kind", "prompt_estimated")
                .increment(prompt.tokenCount());
        metrics.counter("code.review.pipeline.prompt", "outcome",
                        prompt.truncated() ? "truncated" : "full")
                .increment();

        var response = chatModel.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt.text()))
                .maxOutputTokens(budget.prompt().completionReserveTokens())
                .build());
        int mainInputTokens = inputTokens(response);
        int mainOutputTokens = outputTokens(response);
        try {
            JsonRepair.ParseResult<ReviewResult> parsed = jsonRepair.parseOrRepairWithUsage(
                    response.aiMessage().text(), ReviewResult.class);
            int totalInput = Math.addExact(mainInputTokens, parsed.inputTokens());
            int totalOutput = Math.addExact(mainOutputTokens, parsed.outputTokens());
            recordActualTokens(totalInput, totalOutput);
            return new Draft(
                    parsed.value(),
                    candidates,
                    totalInput,
                    totalOutput);
        } catch (JsonRepair.RepairFailedException failure) {
            int totalInput = Math.addExact(mainInputTokens, failure.inputTokens());
            int totalOutput = Math.addExact(mainOutputTokens, failure.outputTokens());
            recordActualTokens(totalInput, totalOutput);
            throw new CodeReviewAgent.ReviewExecutionException(
                    failure,
                    totalInput,
                    totalOutput);
        } catch (RuntimeException failure) {
            recordActualTokens(mainInputTokens, mainOutputTokens);
            throw new CodeReviewAgent.ReviewExecutionException(
                    failure, mainInputTokens, mainOutputTokens);
        }
    }

    private static int inputTokens(ChatResponse response) {
        if (response.tokenUsage() == null || response.tokenUsage().inputTokenCount() == null) {
            throw new IllegalStateException("model response did not include input token usage");
        }
        return response.tokenUsage().inputTokenCount();
    }

    private static int outputTokens(ChatResponse response) {
        if (response.tokenUsage() == null || response.tokenUsage().outputTokenCount() == null) {
            throw new IllegalStateException("model response did not include output token usage");
        }
        return response.tokenUsage().outputTokenCount();
    }

    private String buildQuery(ReviewContext ctx, ToolFindings tools) {
        StringBuilder sb = new StringBuilder();
        for (DiffParser.FileDiff f : ctx.fileDiffs()) {
            sb.append(f.path()).append(' ');
            for (DiffParser.AddedLine l : f.addedLines()) {
                sb.append(l.content()).append(' ');
                if (sb.length() > 1000) {
                    break;
                }
            }
            if (sb.length() > 1000) {
                break;
            }
        }
        for (Violation v : tools.violations()) {
            sb.append(v.rule()).append(' ').append(v.message()).append(' ');
            if (sb.length() > 2000) {
                break;
            }
        }
        return sb.toString().trim();
    }

    private void recordActualTokens(int inputTokens, int outputTokens) {
        metrics.counter("code.review.pipeline.tokens", "kind", "input_actual")
                .increment(inputTokens);
        metrics.counter("code.review.pipeline.tokens", "kind", "output_actual")
                .increment(outputTokens);
    }
}
