package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import dev.langchain4j.example.codereview.analyzer.StaticAnalyzer;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.GitClient;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class RuleCheckerTool {

    private final GitClient gitClient;
    private final DiffParser diffParser;
    private final List<StaticAnalyzer> analyzers;
    private final SpotBugsAnalyzer spotBugsAnalyzer;

    public RuleCheckerTool(GitClient gitClient, DiffParser diffParser,
                           List<StaticAnalyzer> analyzers,
                           SpotBugsAnalyzer spotBugsAnalyzer) {
        this.gitClient = gitClient;
        this.diffParser = diffParser;
        this.analyzers = analyzers;
        this.spotBugsAnalyzer = spotBugsAnalyzer;
    }

    @Tool("Runs all configured static analyzers on a git repo's diff. Returns violations with real file line numbers and a [tool_status] footer.")
    public String checkRules(
            @P("Absolute path to the git repository") String repoPath,
            @P("Git ref to compare against, e.g. 'HEAD~1'") String ref) {
        String diff;
        try {
            diff = gitClient.diff(Path.of(repoPath), (ref == null || ref.isBlank()) ? "HEAD~1" : ref);
        } catch (GitClient.GitException e) {
            return "Error running git diff: " + e.getMessage();
        }
        if (diff.isBlank()) return "No changes found.";

        List<DiffParser.FileDiff> files = diffParser.parse(diff);
        List<Violation> all = new ArrayList<>();
        StringBuilder status = new StringBuilder();
        for (StaticAnalyzer a : analyzers) {
            if (a == spotBugsAnalyzer) {
                continue;
            }
            all.addAll(a.analyze(files));
            status.append("[tool_status] ").append(a.name()).append("=ok\n");
        }

        List<Violation> spotBugs = spotBugsAnalyzer.analyzeWithSource(files, Path.of(repoPath));
        if (spotBugs.isEmpty()) {
            status.append("[tool_status] spotbugs=skipped (not buildable or not installed)\n");
        } else {
            status.append("[tool_status] spotbugs=ok\n");
            all.addAll(spotBugs);
        }

        StringBuilder body = new StringBuilder();
        if (all.isEmpty()) {
            body.append("No rule violations found.\n");
        } else {
            body.append("Found ").append(all.size()).append(" violation(s):\n");
            body.append(all.stream()
                    .map(v -> "[" + v.severity() + "] " + v.file() + ":" + v.line()
                            + " (" + v.rule() + ") " + v.message())
                    .collect(Collectors.joining("\n"))).append("\n");
        }
        body.append(status);
        return body.toString();
    }
}
