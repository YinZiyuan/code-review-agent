package dev.langchain4j.example.codereview.eval;

import java.util.List;
import java.util.Map;

public record EvalReport(
        String version,
        String commit,
        String tag,
        String timestamp,
        Map<String, Object> config,
        List<String> allowedInputs,
        Map<String, Double> metrics,
        List<SampleMetrics> perSample,
        List<Map<String, Double>> perRunMetrics,
        Map<String, Double> metricsStdDev
) { }
