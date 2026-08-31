package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.GitClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitDiffToolTest {

    @Test
    void returnsBlankMessageWhenNoChanges() {
        GitClient git = new FakeGitClient("");
        GitDiffTool tool = new GitDiffTool(git, new DiffParser());

        String result = tool.getGitDiff("/some/repo", "HEAD~1");

        assertThat(result).contains("No changes found");
    }

    @Test
    void preservesSmallDiff() {
        String fakeDiff = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,1 +1,2 @@
                 line1
                +line2-new
                """;
        GitClient git = new FakeGitClient(fakeDiff);
        GitDiffTool tool = new GitDiffTool(git, new DiffParser());

        String result = tool.getGitDiff("/repo", "HEAD~1");
        assertThat(result).contains("+line2-new");
    }

    private static class FakeGitClient extends GitClient {
        private final String diff;

        private FakeGitClient(String diff) {
            this.diff = diff;
        }

        @Override
        public String diff(Path repoPath, String ref) {
            return diff;
        }
    }
}
