package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.GitClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class GitDiffToolTest {

    @Test
    void returnsBlankMessageWhenNoChanges() {
        GitClient git = Mockito.mock(GitClient.class);
        when(git.diff(any(Path.class), eq("HEAD~1"))).thenReturn("");
        GitDiffTool tool = new GitDiffTool(git, new DiffParser());

        String result = tool.getGitDiff("/some/repo", "HEAD~1");

        assertThat(result).contains("No changes found");
    }

    @Test
    void preservesSmallDiff() {
        GitClient git = Mockito.mock(GitClient.class);
        String fakeDiff = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,1 +1,2 @@
                 line1
                +line2-new
                """;
        when(git.diff(any(), any())).thenReturn(fakeDiff);
        GitDiffTool tool = new GitDiffTool(git, new DiffParser());

        String result = tool.getGitDiff("/repo", "HEAD~1");
        assertThat(result).contains("+line2-new");
    }
}
