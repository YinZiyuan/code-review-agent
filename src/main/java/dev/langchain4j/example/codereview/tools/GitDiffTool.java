package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.GitClient;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class GitDiffTool {

    private static final int MAX_PER_FILE_CHARS = 4000;
    private static final int MAX_TOTAL_CHARS = 12000;

    private final GitClient gitClient;
    private final DiffParser diffParser;

    public GitDiffTool(GitClient gitClient, DiffParser diffParser) {
        this.gitClient = gitClient;
        this.diffParser = diffParser;
    }

    @Tool("Retrieves the git diff for a repository. Diff is split per file; oversized files are summarized.")
    public String getGitDiff(
            @P("Absolute path to the git repository") String repoPath,
            @P("Git ref to compare against, e.g. 'HEAD~1'") String ref) {
        try {
            String effectiveRef = (ref == null || ref.isBlank()) ? "HEAD~1" : ref;
            String raw = gitClient.diff(Path.of(repoPath), effectiveRef);
            if (raw.isBlank()) {
                return "No changes found when comparing against '" + effectiveRef + "'.";
            }
            List<DiffParser.FileDiff> files = diffParser.parse(raw);
            StringBuilder out = new StringBuilder();
            for (DiffParser.FileDiff file : files) {
                String section = renderFile(file, raw);
                if (out.length() + section.length() > MAX_TOTAL_CHARS) {
                    out.append("\n... [diff truncated at ").append(MAX_TOTAL_CHARS).append(" chars; ")
                            .append(files.size()).append(" files total]\n");
                    break;
                }
                out.append(section);
            }
            return out.toString();
        } catch (GitClient.GitException e) {
            return "Error running git diff: " + e.getMessage();
        }
    }

    private String renderFile(DiffParser.FileDiff file, String rawDiff) {
        String marker = "diff --git a/" + file.path() + " b/" + file.path();
        int start = rawDiff.indexOf(marker);
        if (start < 0) return "";
        int end = rawDiff.indexOf("\ndiff --git ", start + marker.length());
        String section = (end < 0) ? rawDiff.substring(start) : rawDiff.substring(start, end);

        if (section.length() <= MAX_PER_FILE_CHARS) {
            return section + "\n";
        }
        String header = section.substring(0, Math.min(400, section.length()));
        String summary = file.addedLines().stream()
                .limit(20)
                .map(l -> "+L" + l.lineNumber() + ": " + l.content())
                .collect(Collectors.joining("\n"));
        return header + "\n[... file truncated, " + file.addedLines().size() + " added lines total ...]\n"
                + summary + "\n";
    }
}
