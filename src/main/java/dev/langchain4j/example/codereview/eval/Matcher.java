package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.ReviewFinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class Matcher {

    private final LlmJudge judge;
    private final int lineTolerance;

    public Matcher(LlmJudge judge, int lineTolerance) {
        this.judge = judge;
        this.lineTolerance = lineTolerance;
    }

    public List<MatchResult> match(List<ExpectedIssue> expected, List<ReviewFinding> agentFindings) {
        List<MatchResult> results = new ArrayList<>();
        Set<ReviewFinding> assignedFindings = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ExpectedIssue exp : expected == null ? List.<ExpectedIssue>of() : expected) {
            MatchResult best = MatchResult.miss(exp);
            for (ReviewFinding f : agentFindings == null ? List.<ReviewFinding>of() : agentFindings) {
                if (assignedFindings.contains(f)) continue;
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
                MatchResult candidate = new MatchResult(exp, f, v.match(), v.confidence(), v.reason());
                if (v.match()) {
                    best = candidate;
                    assignedFindings.add(f);
                    break;
                }
                if (best.agentFinding() == null || v.confidence() > best.confidence()) {
                    best = candidate;
                }
            }
            results.add(best);
        }
        return results;
    }
}
