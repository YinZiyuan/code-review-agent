package dev.langchain4j.example.codereview.eval;

import java.util.List;

public record EvalTracePolicy(
        String audience,
        List<String> persistedFields,
        List<String> excludedFields
) {
    public static EvalTracePolicy evaluatorOnly() {
        return new EvalTracePolicy(
                "evaluator-only",
                List.of("traces.findings", "traces.matches", "traces.unmatched_findings", "model_runtime"),
                List.of("api_keys", "authorization_headers", "environment_variables", "agent_prompts"));
    }
}
