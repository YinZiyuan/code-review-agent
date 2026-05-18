package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.ReviewFinding;

import java.util.ArrayList;
import java.util.List;

public class Matcher {

    private final LlmJudge judge;
    private final int lineTolerance;

    public Matcher(LlmJudge judge, int lineTolerance) {
        this.judge = judge;
        this.lineTolerance = lineTolerance;
    }

    public List<MatchResult> match(List<ExpectedIssue> expected, List<ReviewFinding> agentFindings) {
        List<MatchResult> results = new ArrayList<>();
        for (ExpectedIssue exp : expected == null ? List.<ExpectedIssue>of() : expected) {
            MatchResult best = MatchResult.miss(exp);
            for (ReviewFinding f : agentFindings == null ? List.<ReviewFinding>of() : agentFindings) {
                if (f.file() == null || !f.file().equals(exp.file())) continue;
                if (f.line() == null) continue;
                int low = exp.lineRange() != null && exp.lineRange().length == 2
                        ? exp.lineRange()[0] - lineTolerance
                        : exp.line() - lineTolerance;
                int high = exp.lineRange() != null && exp.lineRange().length == 2
                        ? exp.lineRange()[1] + lineTolerance
                        : exp.line() + lineTolerance;
                if (f.line() < low || f.line() > high) continue;

                LlmJudge.JudgeVerdict v = judge.judge(exp, f);
                if (v.match()) {
                    best = new MatchResult(exp, f, true, v.confidence(), v.reason());
                    break;
                }
            }
            results.add(best);
        }
        return results;
    }
}
