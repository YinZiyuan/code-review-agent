package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.RegexAnalyzer;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import dev.langchain4j.example.codereview.analyzer.Violation;
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

    public ToolFindingsProducer(RegexAnalyzer regex, SpotBugsAnalyzer spotbugs) {
        this.regex = regex;
        this.spotbugs = spotbugs;
    }

    public ToolFindings produce(ReviewContext ctx) {
        List<Violation> all = new ArrayList<>(regex.analyze(ctx.fileDiffs()));
        List<ToolStatus> statuses = new ArrayList<>();
        statuses.add(new ToolStatus("regex", "ok", null));

        List<Violation> spotbugsViolations = spotbugs.analyzeWithSource(ctx.fileDiffs(), ctx.sourceRoot());
        if (spotbugsViolations.isEmpty()) {
            statuses.add(new ToolStatus("spotbugs", "skipped", "not buildable or not installed"));
        } else {
            statuses.add(new ToolStatus("spotbugs", "ok", null));
            all.addAll(spotbugsViolations);
        }

        return new ToolFindings(dedupe(all), statuses);
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
