package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class RegexAnalyzer implements StaticAnalyzer {

    private record Rule(String id, Severity severity, Pattern pattern, String message) { }

    private static final List<Rule> RULES = List.of(
            new Rule("hardcoded-credential", Severity.CRITICAL,
                    Pattern.compile(".*\\b(password|passwd|apiKey|api_key|secret|token)\\s*=\\s*\"[^\"]+\".*"),
                    "Possible hardcoded credential — use env vars or a secrets manager"),
            new Rule("system-out-println", Severity.WARNING,
                    Pattern.compile(".*System\\.(out|err)\\.println\\(.*"),
                    "Use SLF4J logger instead of System.out/err.println"),
            new Rule("print-stack-trace", Severity.WARNING,
                    Pattern.compile(".*\\.printStackTrace\\(\\).*"),
                    "Use logger.error(msg, e) instead of e.printStackTrace()"),
            new Rule("catch-generic-exception", Severity.SUGGESTION,
                    Pattern.compile(".*catch\\s*\\(\\s*Exception\\s+\\w+\\s*\\).*"),
                    "Avoid catching generic Exception — prefer specific exception types"),
            new Rule("empty-catch", Severity.WARNING,
                    Pattern.compile(".*catch.*\\{\\s*\\}.*"),
                    "Empty or silently-ignored catch block"),
            new Rule("raw-thread", Severity.SUGGESTION,
                    Pattern.compile(".*new\\s+Thread\\s*\\(.*"),
                    "Consider using ExecutorService instead of raw Thread"),
            new Rule("thread-sleep", Severity.SUGGESTION,
                    Pattern.compile(".*Thread\\.sleep\\(.*"),
                    "Avoid Thread.sleep() in business logic"),
            new Rule("unresolved-todo", Severity.WARNING,
                    Pattern.compile(".*(TODO|FIXME).*"),
                    "Unresolved TODO/FIXME — track in an issue or resolve before merging"),
            new Rule("manual-null-check", Severity.SUGGESTION,
                    Pattern.compile(".*(==\\s*null|!=\\s*null).*"),
                    "Consider Optional or Objects.requireNonNull() instead of manual null checks")
    );

    @Override public String name() { return "regex"; }

    @Override
    public List<Violation> analyze(List<DiffParser.FileDiff> files) {
        List<Violation> out = new ArrayList<>();
        for (DiffParser.FileDiff file : files) {
            for (DiffParser.AddedLine added : file.addedLines()) {
                String code = added.content().trim();
                for (Rule r : RULES) {
                    if (r.pattern().matcher(code).matches()) {
                        out.add(new Violation(r.severity(), file.path(), added.lineNumber(),
                                r.id(), r.message()));
                    }
                }
            }
        }
        return out;
    }
}
