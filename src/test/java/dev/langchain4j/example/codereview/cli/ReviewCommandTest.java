package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.reporting.MarkdownReporter;
import dev.langchain4j.example.codereview.tools.GitDiffTool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ReviewCommandTest {

    @Test
    void callsInjectedAgentAndPrintsReview(CapturedOutput output) {
        GitDiffTool gitDiffTool = mock(GitDiffTool.class);
        when(gitDiffTool.getGitDiff(".", "HEAD~1")).thenReturn("diff --git a/Foo.java b/Foo.java");
        ReviewCommand command = new ReviewCommand((request, sourceRoot) -> {
            assertThat(request).contains("diff --git");
            return ReviewResult.empty("Looks good.");
        }, new MarkdownReporter(), gitDiffTool);

        int exitCode = command.call();

        assertThat(exitCode).isZero();
        assertThat(output).contains("## Code Review Report");
        assertThat(output).doesNotContain("bean not wired yet");
    }
}
