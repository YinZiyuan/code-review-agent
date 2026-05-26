package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.ToolStatus;

import java.util.List;

public record SampleMetrics(
        String sampleId,
        int truePositives,
        int falsePositives,
        int falseNegatives,
        int severityMatches,
        int severityComparisons,
        long latencyMs,
        long inputTokens,
        long outputTokens,
        int toolCallsTotal,
        int toolCallsFailed,
        List<ToolStatus> toolStatuses
) {
    public SampleMetrics(
            String sampleId,
            int truePositives,
            int falsePositives,
            int falseNegatives,
            int severityMatches,
            int severityComparisons,
            long latencyMs,
            long inputTokens,
            long outputTokens,
            int toolCallsTotal,
            int toolCallsFailed) {
        this(sampleId, truePositives, falsePositives, falseNegatives, severityMatches, severityComparisons,
                latencyMs, inputTokens, outputTokens, toolCallsTotal, toolCallsFailed, List.of());
    }
}
