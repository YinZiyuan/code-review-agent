package dev.langchain4j.example.codereview;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RuleCheckerTool {

    @Tool("Runs static analysis on a git repository's diff to detect common Java code quality and security violations.")
    public String checkRules(
            @P("Absolute path to the git repository") String repoPath,
            @P("Git ref to compare against, e.g. 'HEAD~1'") String ref) {
        String diffContent = getDiff(repoPath, ref);
        if (diffContent.startsWith("Error")) {
            return diffContent;
        }

        List<String> violations = new ArrayList<>();
        String[] lines = diffContent.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("+") || line.startsWith("+++")) continue;
            String code = line.substring(1).trim();
            int lineNum = i + 1;

            if (code.matches(".*\\b(password|passwd|apiKey|api_key|secret|token)\\s*=\\s*\"[^\"]+\".*")) {
                violations.add("[CRITICAL] Line " + lineNum + ": Possible hardcoded credential — use environment variables or a secrets manager");
            }
            if (code.contains("System.out.println(") || code.contains("System.err.println(")) {
                violations.add("[WARNING] Line " + lineNum + ": Use SLF4J logger instead of System.out/err.println");
            }
            if (code.contains(".printStackTrace()")) {
                violations.add("[WARNING] Line " + lineNum + ": Use logger.error(msg, e) instead of e.printStackTrace()");
            }
            if (code.matches(".*catch\\s*\\(\\s*Exception\\s+\\w+\\s*\\).*")) {
                violations.add("[SUGGESTION] Line " + lineNum + ": Avoid catching generic Exception — prefer specific exception types");
            }
            if (code.matches(".*catch.*\\{\\s*\\}.*") || code.matches(".*catch.*//.*ignore.*")) {
                violations.add("[WARNING] Line " + lineNum + ": Empty or silently-ignored catch block");
            }
            if (code.matches(".*new\\s+Thread\\s*\\(.*")) {
                violations.add("[SUGGESTION] Line " + lineNum + ": Consider using ExecutorService instead of raw Thread");
            }
            if (code.contains("Thread.sleep(")) {
                violations.add("[SUGGESTION] Line " + lineNum + ": Avoid Thread.sleep() in business logic");
            }
            if (code.contains("TODO") || code.contains("FIXME")) {
                violations.add("[WARNING] Line " + lineNum + ": Unresolved TODO/FIXME — track in an issue or resolve before merging");
            }
            if (code.contains("== null") || code.contains("!= null")) {
                violations.add("[SUGGESTION] Line " + lineNum + ": Consider Optional or Objects.requireNonNull() instead of manual null checks");
            }
        }

        if (violations.isEmpty()) {
            return "✓ No rule violations found.";
        }
        return "Found " + violations.size() + " violation(s):\n" + String.join("\n", violations);
    }

    private String getDiff(String repoPath, String ref) {
        try {
            String diffRef = (ref == null || ref.isBlank()) ? "HEAD~1" : ref;
            ProcessBuilder pb = new ProcessBuilder("git", "diff", diffRef);
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output.isBlank() ? "No changes found." : output;
        } catch (Exception e) {
            return "Error running git diff: " + e.getMessage();
        }
    }
}
