package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.infra.DiffParser;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record ReviewContext(
        String rawDiff,
        List<DiffParser.FileDiff> fileDiffs,
        Map<String, List<CodeSnippet>> contextByFile,
        Path sourceRoot,
        SourceContextStatus sourceContextStatus
) {
    public enum SourceContextStatus {
        COMPLETE,
        LIMIT_EXCEEDED,
        TIMED_OUT,
        CANCELLED,
        NOT_AVAILABLE
    }

    public ReviewContext(
            String rawDiff,
            List<DiffParser.FileDiff> fileDiffs,
            Map<String, List<CodeSnippet>> contextByFile,
            Path sourceRoot) {
        this(rawDiff, fileDiffs, contextByFile, sourceRoot, SourceContextStatus.COMPLETE);
    }

    public ReviewContext {
        fileDiffs = fileDiffs == null ? List.of() : List.copyOf(fileDiffs);
        TreeMap<String, List<CodeSnippet>> sorted = new TreeMap<>();
        if (contextByFile != null) {
            contextByFile.forEach((file, snippets) -> sorted.put(file, snippets == null
                    ? List.of()
                    : snippets.stream()
                    .sorted(java.util.Comparator.comparing(CodeSnippet::file)
                            .thenComparingInt(CodeSnippet::line)
                            .thenComparing(CodeSnippet::text))
                    .toList()));
        }
        contextByFile = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
        sourceContextStatus = Objects.requireNonNull(sourceContextStatus, "sourceContextStatus");
    }
}
