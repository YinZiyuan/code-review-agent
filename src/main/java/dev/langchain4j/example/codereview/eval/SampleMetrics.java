package dev.langchain4j.example.codereview.eval;

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
        int toolCallsFailed
) { }
