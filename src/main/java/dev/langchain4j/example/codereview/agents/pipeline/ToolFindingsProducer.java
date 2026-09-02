package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.RegexAnalyzer;
import dev.langchain4j.example.codereview.analyzer.SpotBugsResult;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.model.ToolRunState;
import dev.langchain4j.example.codereview.model.ToolStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ToolFindingsProducer {

    private final RegexAnalyzer regex;
    private final SpotBugsAnalyzer spotbugs;
    private final ReviewWorkBudget budget;

    public ToolFindingsProducer(
            RegexAnalyzer regex, SpotBugsAnalyzer spotbugs, ReviewWorkBudget budget) {
        this.regex = regex;
        this.spotbugs = spotbugs;
        this.budget = budget;
    }

    public ToolFindings produce(ReviewContext ctx) {
        List<Violation> all = new ArrayList<>();
        List<ToolStatus> statuses = new ArrayList<>();

        try {
            all.addAll(regex.analyze(ctx.fileDiffs()));
            statuses.add(new ToolStatus("regex", ToolRunState.RAN, null));
        } catch (RuntimeException ignored) {
            statuses.add(new ToolStatus("regex", ToolRunState.FAILED, "analyzer failed"));
        }

        try {
            SpotBugsResult sb = spotbugs.analyzeWithSource(ctx.fileDiffs(), ctx.sourceRoot());
            if (!sb.ran()) {
                statuses.add(new ToolStatus("spotbugs", ToolRunState.SKIPPED_EXPECTED,
                        "not buildable or not installed"));
            } else {
                statuses.add(new ToolStatus("spotbugs", ToolRunState.RAN, null));
                all.addAll(sb.violations());
            }
        } catch (RuntimeException ignored) {
            statuses.add(new ToolStatus("spotbugs", ToolRunState.FAILED, "analyzer failed"));
        }

        List<Violation> deduped = dedupe(all);
        int findingCount = Math.min(deduped.size(), budget.input().maxFindings());
        return new ToolFindings(deduped.subList(0, findingCount), statuses);
    }

    private List<Violation> dedupe(List<Violation> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<Violation> out = new ArrayList<>();
        for (Violation v : in) {
            String key = v.file() + ":" + v.line() + ":" + v.rule();
            if (seen.add(key)) {
                out.add(v);
            }
        }
        return out;
    }
}
