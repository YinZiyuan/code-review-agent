package dev.langchain4j.example.codereview.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitClientTest {

    @TempDir Path repo;
    private GitClient git;

    @BeforeEach
    void initRepo() throws Exception {
        git = new GitClient();
        runCmd("git", "init", "-q");
        runCmd("git", "config", "user.email", "test@example.com");
        runCmd("git", "config", "user.name", "Test");
        runCmd("git", "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("a.txt"), "v1\n");
        runCmd("git", "add", ".");
        runCmd("git", "commit", "-q", "-m", "v1");
        Files.writeString(repo.resolve("a.txt"), "v1\nv2\n");
        runCmd("git", "add", ".");
        runCmd("git", "commit", "-q", "-m", "v2");
    }

    private void runCmd(String... args) throws Exception {
        new ProcessBuilder(args).directory(repo.toFile()).inheritIO().start().waitFor();
    }

    @Test
    void diffAgainstHeadTildeReturnsContent() {
        String diff = git.diff(repo, "HEAD~1");
        assertThat(diff).contains("+v2");
    }

    @Test
    void diffOnEmptyReturnsBlank() {
        String diff = git.diff(repo, "HEAD");
        assertThat(diff).isBlank();
    }

    @Test
    void nonexistentRepoThrows() {
        assertThatThrownBy(() -> git.diff(Path.of("/nonexistent/path/that/does/not/exist"), "HEAD"))
                .isInstanceOf(GitClient.GitException.class);
    }
}
