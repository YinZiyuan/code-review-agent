package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Map;

public record ExecutionMeasurements(
        long latencyMs, int inputTokens, int outputTokens, Map<String, String> toolStates) {
    public ExecutionMeasurements {
        if (latencyMs < 0 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("measurements must be non-negative");
        }
        toolStates = toolStates == null ? Map.of() : Map.copyOf(toolStates);
    }
}
