package dev.langchain4j.example.codereview.infra;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class GitClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    public String diff(Path repoPath, String ref) {
        if (!Files.isDirectory(repoPath)) {
            throw new GitException("Not a directory: " + repoPath);
        }
        return run(repoPath, "git", "diff", ref);
    }

    public String currentBranch(Path repoPath) {
        return run(repoPath, "git", "rev-parse", "--abbrev-ref", "HEAD").trim();
    }

    public String show(Path repoPath, String revision) {
        return run(repoPath, "git", "show", revision);
    }

    private String run(Path repoPath, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(repoPath.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new GitException("git command timed out after " + TIMEOUT);
            }
            if (p.exitValue() != 0) {
                throw new GitException("git " + String.join(" ", cmd) + " failed: " + out);
            }
            return out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new GitException("git execution error: " + e.getMessage(), e);
        }
    }

    public static class GitException extends RuntimeException {
        public GitException(String message) { super(message); }
        public GitException(String message, Throwable cause) { super(message, cause); }
    }
}
