package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmReviewer {

    public record Draft(ReviewResult result, List<Citation> citationCandidates) {
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
            - Use only the category enum values listed in the JSON schema. Never output
              ad-hoc categories such as COMPILER_ERROR, BUILD, TOOL, or UNKNOWN.
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

    public LlmReviewer(ChatModel chatModel,
                       ContentRetriever retriever,
                       CitationTracker tracker,
                       JsonRepair jsonRepair) {
        this.chatModel = chatModel;
        this.retriever = retriever;
        this.tracker = tracker;
        this.jsonRepair = jsonRepair;
    }

    public Draft review(ReviewContext ctx, ToolFindings tools) {
        String query = buildQuery(ctx, tools);
        if (query.isBlank()) {
            query = ctx.rawDiff() == null || ctx.rawDiff().isBlank() ? "code review" : ctx.rawDiff();
        }
        List<Content> hits = retriever.retrieve(Query.from(query));
        List<Citation> candidates = tracker.toCitations(hits);

        String prompt = SYSTEM
                + "\n\n[DIFF]\n" + ctx.rawDiff()
                + "\n\n[TOOL FINDINGS]\n" + renderViolations(tools.violations())
                + "\n\n[CROSS-FILE CONTEXT]\n" + renderContext(ctx)
                + "\n\n[CITATION CANDIDATES]\n" + renderCitations(candidates);

        var response = chatModel.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build());
        ReviewResult result = jsonRepair.parseOrRepair(response.aiMessage().text(), ReviewResult.class);
        return new Draft(result, candidates);
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

    private String renderViolations(List<Violation> violations) {
        if (violations.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (Violation v : violations) {
            sb.append("- [").append(v.severity()).append("] ")
                    .append(v.file()).append(':').append(v.line())
                    .append(" (").append(v.rule()).append(") ")
                    .append(v.message()).append('\n');
        }
        return sb.toString();
    }

    private String renderContext(ReviewContext ctx) {
        if (ctx.contextByFile().isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        ctx.contextByFile().forEach((file, snippets) -> {
            sb.append("// for ").append(file).append('\n');
            for (CodeSnippet snippet : snippets) {
                sb.append(snippet.file()).append(':').append(snippet.line()).append(": ")
                        .append(snippet.text()).append('\n');
            }
        });
        return sb.toString();
    }

    private String renderCitations(List<Citation> citations) {
        if (citations.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < citations.size(); i++) {
            Citation c = citations.get(i);
            sb.append(i + 1).append(") id=").append(c.id())
                    .append(" source=").append(c.source())
                    .append(" section=").append(c.section()).append('\n');
        }
        return sb.toString();
    }
}
