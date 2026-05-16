package dev.langchain4j.example.codereview;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class GitDiffTool {

    private static final int MAX_DIFF_LENGTH = 8000;

    @Tool("Retrieves the git diff for a repository to see what code was added or removed.")
    public String getGitDiff(
            @P("Absolute path to the git repository") String repoPath,
            @P("Git ref to compare against, e.g. 'HEAD~1', 'main', or a commit hash. Use 'HEAD~1' to see the latest commit.") String ref) {
        try {
            String diffRef = (ref == null || ref.isBlank()) ? "HEAD~1" : ref;
            ProcessBuilder pb = new ProcessBuilder("git", "diff", diffRef);
            pb.directory(new File(repoPath));
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();

            if (output.isBlank()) {
                return "No changes found when comparing against '" + diffRef + "'. " +
                        "Try 'HEAD~1' to see the latest commit, or '--staged' for staged changes.";
            }

            if (output.length() > MAX_DIFF_LENGTH) {
                return output.substring(0, MAX_DIFF_LENGTH) + "\n\n...[diff truncated at " + MAX_DIFF_LENGTH + " chars]";
            }
            return output;

        } catch (Exception e) {
            return "Error running git diff: " + e.getMessage() +
                    ". Make sure the path is a valid git repository.";
        }
    }
}
