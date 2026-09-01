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
        Map<String, Double> metricsStdDev,
        ModelRuntimeMetadata modelRuntime,
        EvalTracePolicy tracePolicy,
        List<SampleTrace> traces
) {
    public EvalReport(
            String version,
            String commit,
            String tag,
            String timestamp,
            Map<String, Object> config,
            List<String> allowedInputs,
            Map<String, Double> metrics,
            List<SampleMetrics> perSample,
            List<Map<String, Double>> perRunMetrics,
            Map<String, Double> metricsStdDev) {
        this(version, commit, tag, timestamp, config, allowedInputs, metrics, perSample,
                perRunMetrics, metricsStdDev, ModelRuntimeMetadata.unknown(),
                EvalTracePolicy.evaluatorOnly(), List.of());
    }
}
