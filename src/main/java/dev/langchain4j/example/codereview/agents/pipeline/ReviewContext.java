package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.infra.DiffParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ReviewContext(
        String rawDiff,
        List<DiffParser.FileDiff> fileDiffs,
        Map<String, List<CodeSnippet>> contextByFile,
        Path sourceRoot
) {
    public ReviewContext {
        fileDiffs = fileDiffs == null ? List.of() : List.copyOf(fileDiffs);
        contextByFile = contextByFile == null ? Map.of() : Map.copyOf(contextByFile);
    }
}
