package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.model.Citation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Comparator;

public final class ReviewPromptAssembler {

    private static final String DIFF_TRUNCATED = "[diff truncated by review work budget]";
    private static final String SECTION_TRUNCATED = "[section truncated by review work budget]";

    private final PromptTokenizer tokenizer;
    private final ReviewWorkBudget budget;

    public ReviewPromptAssembler(PromptTokenizer tokenizer, ReviewWorkBudget budget) {
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public AssembledPrompt assemble(
            String system,
            ReviewContext context,
            ToolFindings tools,
            List<Citation> citations) {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tools, "tools");
        List<Citation> safeCitations = citations == null ? List.of() : citations;

        StringBuilder prompt = new StringBuilder(system);
        int maxPromptTokens = budget.maxPromptTokens();
        if (tokenizer.count(prompt.toString()) > maxPromptTokens) {
            throw new IllegalStateException("review system prompt exceeds model context budget");
        }

        DiffSelection diff = selectDiff(context.rawDiff(), budget.prompt().maxDiffTokens());
        boolean truncated = diff.truncated();
        truncated |= appendSection(prompt, "DIFF", diff.text(), maxPromptTokens);
        truncated |= appendSection(
                prompt, "TOOL FINDINGS", renderViolations(tools.violations()), maxPromptTokens);
        truncated |= appendSection(
                prompt, "CROSS-FILE CONTEXT", renderContext(context), maxPromptTokens);
        truncated |= appendSection(
                prompt, "CITATION CANDIDATES", renderCitations(safeCitations), maxPromptTokens);

        String text = prompt.toString();
        int tokens = tokenizer.count(text);
        if (tokens > maxPromptTokens) {
            throw new IllegalStateException("assembled review prompt exceeds model context budget");
        }
        return new AssembledPrompt(text, tokens, truncated);
    }

    private boolean appendSection(
            StringBuilder prompt, String name, String content, int maxPromptTokens) {
        String header = "\n\n[" + name + "]\n";
        int used = tokenizer.count(prompt.toString());
        int headerTokens = tokenizer.count(header);
        int remaining = maxPromptTokens - used - headerTokens;
        if (remaining <= 0) {
            return true;
        }
        prompt.append(header);
        if (tokenizer.count(content) <= remaining) {
            prompt.append(content);
            return false;
        }
        int markerTokens = tokenizer.count(SECTION_TRUNCATED);
        prompt.append(tokenizer.truncate(content, Math.max(0, remaining - markerTokens)));
        if (remaining >= markerTokens) {
            prompt.append(SECTION_TRUNCATED);
        }
        return true;
    }

    private DiffSelection selectDiff(String rawDiff, int maxTokens) {
        String diff = rawDiff == null ? "" : rawDiff;
        if (tokenizer.count(diff) <= maxTokens) {
            return new DiffSelection(diff, false);
        }
        List<String> chunks = diffChunks(diff);
        StringBuilder selected = new StringBuilder();
        int markerTokens = tokenizer.count(DIFF_TRUNCATED);
        int contentLimit = Math.max(0, maxTokens - markerTokens);
        for (String chunk : chunks) {
            String candidate = selected + chunk;
            if (tokenizer.count(candidate) > contentLimit) {
                if (selected.isEmpty()) {
                    selected.append(tokenizer.truncate(chunk, contentLimit));
                }
                break;
            }
            selected.append(chunk);
        }
        selected.append(DIFF_TRUNCATED);
        return new DiffSelection(selected.toString(), true);
    }

    private static List<String> diffChunks(String diff) {
        List<String> chunks = new ArrayList<>();
        StringBuilder fileHeader = new StringBuilder();
        StringBuilder hunk = null;
        for (String line : diff.split("(?<=\\n)", -1)) {
            if (line.startsWith("diff --git ")) {
                finishChunk(chunks, fileHeader, hunk);
                fileHeader = new StringBuilder(line);
                hunk = null;
            } else if (line.startsWith("@@ ")) {
                finishChunk(chunks, fileHeader, hunk);
                hunk = new StringBuilder(line);
            } else if (hunk == null) {
                fileHeader.append(line);
            } else {
                hunk.append(line);
            }
        }
        finishChunk(chunks, fileHeader, hunk);
        if (chunks.isEmpty() && !diff.isEmpty()) {
            chunks.add(diff);
        }
        return chunks;
    }

    private static void finishChunk(
            List<String> chunks, StringBuilder fileHeader, StringBuilder hunk) {
        if (hunk != null && !hunk.isEmpty()) {
            chunks.add(fileHeader.toString() + hunk);
        } else if (!fileHeader.isEmpty()) {
            chunks.add(fileHeader.toString());
        }
    }

    private static String renderViolations(List<Violation> violations) {
        if (violations.isEmpty()) {
            return "(none)";
        }
        StringBuilder rendered = new StringBuilder();
        List<Violation> stable = violations.stream()
                .sorted(Comparator.comparing(Violation::file)
                        .thenComparingInt(Violation::line)
                        .thenComparing(Violation::rule)
                        .thenComparing(violation -> violation.severity().name())
                        .thenComparing(Violation::message))
                .toList();
        for (Violation violation : stable) {
            rendered.append("- [").append(violation.severity()).append("] ")
                    .append(violation.file()).append(':').append(violation.line())
                    .append(" (").append(violation.rule()).append(") ")
                    .append(violation.message()).append('\n');
        }
        return rendered.toString();
    }

    private static String renderContext(ReviewContext context) {
        if (context.contextByFile().isEmpty()) {
            return "status=" + context.sourceContextStatus().name().toLowerCase() + "\n(none)";
        }
        StringBuilder rendered = new StringBuilder("status=")
                .append(context.sourceContextStatus().name().toLowerCase()).append('\n');
        context.contextByFile().forEach((file, snippets) -> {
            rendered.append("// for ").append(file).append('\n');
            for (CodeSnippet snippet : snippets) {
                rendered.append(snippet.file()).append(':').append(snippet.line()).append(": ")
                        .append(snippet.text()).append('\n');
            }
        });
        return rendered.toString();
    }

    private static String renderCitations(List<Citation> citations) {
        if (citations.isEmpty()) {
            return "(none)";
        }
        StringBuilder rendered = new StringBuilder();
        Comparator<String> text = Comparator.nullsFirst(Comparator.naturalOrder());
        List<Citation> stable = citations.stream()
                .sorted(Comparator.comparing(Citation::id, text)
                        .thenComparing(Citation::source, text)
                        .thenComparing(Citation::section, text))
                .toList();
        for (int index = 0; index < stable.size(); index++) {
            Citation citation = stable.get(index);
            rendered.append(index + 1).append(") id=").append(citation.id())
                    .append(" source=").append(citation.source())
                    .append(" section=").append(citation.section()).append('\n');
        }
        return rendered.toString();
    }

    public record AssembledPrompt(String text, int tokenCount, boolean truncated) {
        public AssembledPrompt {
            Objects.requireNonNull(text, "text");
            if (tokenCount < 0) {
                throw new IllegalArgumentException("tokenCount must be non-negative");
            }
        }
    }

    private record DiffSelection(String text, boolean truncated) {
    }
}
