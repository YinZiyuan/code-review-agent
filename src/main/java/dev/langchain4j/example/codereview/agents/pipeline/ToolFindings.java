package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.model.ToolStatus;

import java.util.List;

public record ToolFindings(List<Violation> violations, List<ToolStatus> statuses) {
    public ToolFindings {
        violations = violations == null ? List.of() : List.copyOf(violations);
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }
}
