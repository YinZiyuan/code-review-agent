package dev.langchain4j.example.codereview.eval;

import java.util.List;

public final class Metrics {

    private Metrics() { }

    public static double recall(List<SampleMetrics> ms) {
        int tp = ms.stream().mapToInt(SampleMetrics::truePositives).sum();
        int fn = ms.stream().mapToInt(SampleMetrics::falseNegatives).sum();
        return safeDiv(tp, tp + fn);
    }

    public static double precision(List<SampleMetrics> ms) {
        int tp = ms.stream().mapToInt(SampleMetrics::truePositives).sum();
        int fp = ms.stream().mapToInt(SampleMetrics::falsePositives).sum();
        return safeDiv(tp, tp + fp);
    }

    public static double fpRate(List<SampleMetrics> ms) {
        int fp = ms.stream().mapToInt(SampleMetrics::falsePositives).sum();
        int reported = ms.stream().mapToInt(s -> s.truePositives() + s.falsePositives()).sum();
        return safeDiv(fp, reported);
    }

    public static double severityAccuracy(List<SampleMetrics> ms) {
        int matches = ms.stream().mapToInt(SampleMetrics::severityMatches).sum();
        int total = ms.stream().mapToInt(SampleMetrics::severityComparisons).sum();
        return safeDiv(matches, total);
    }

    public static double avgLatencyMs(List<SampleMetrics> ms) {
        return ms.isEmpty() ? 0.0 : ms.stream().mapToLong(SampleMetrics::latencyMs).average().orElse(0.0);
    }

    public static double avgInputTokens(List<SampleMetrics> ms) {
        return ms.isEmpty() ? 0.0 : ms.stream().mapToLong(SampleMetrics::inputTokens).average().orElse(0.0);
    }

    public static double avgOutputTokens(List<SampleMetrics> ms) {
        return ms.isEmpty() ? 0.0 : ms.stream().mapToLong(SampleMetrics::outputTokens).average().orElse(0.0);
    }

    public static double toolSuccessRate(List<SampleMetrics> ms) {
        int total = ms.stream().mapToInt(SampleMetrics::toolCallsTotal).sum();
        int failed = ms.stream().mapToInt(SampleMetrics::toolCallsFailed).sum();
        return safeDiv(total - failed, total);
    }

    private static double safeDiv(int num, int den) {
        return den == 0 ? 0.0 : (double) num / den;
    }
}
