package dev.langchain4j.example.codereview.model;

import java.util.List;

public record ReviewResult(
        String summary,
        List<ReviewFinding> findings,
        List<ToolStatus> toolStatus
) {
    public static ReviewResult empty(String summary) {
        return new ReviewResult(summary, List.of(), List.of());
    }
}
