package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.ReviewFinding;

public record MatchResult(
        ExpectedIssue expected,
        ReviewFinding agentFinding,
        boolean matched,
        double confidence,
        String judgeReason
) {
    public static MatchResult miss(ExpectedIssue expected) {
        return new MatchResult(expected, null, false, 0.0, "no candidate at expected location");
    }
}
