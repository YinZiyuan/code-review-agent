package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.ReviewFinding;

public interface LlmJudge {
    JudgeVerdict judge(ExpectedIssue expected, ReviewFinding agent);

    record JudgeVerdict(boolean match, double confidence, String reason) { }
}
