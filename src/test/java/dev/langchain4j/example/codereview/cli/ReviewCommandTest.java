package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.reporting.MarkdownReporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ReviewCommandTest {

    @Test
    void callsInjectedAgentAndPrintsReview(CapturedOutput output) {
        ReviewCommand command = new ReviewCommand(request -> {
            assertThat(request).contains("Call getGitDiff first, then checkRules");
            return ReviewResult.empty("Looks good.");
        }, new MarkdownReporter());

        int exitCode = command.call();

        assertThat(exitCode).isZero();
        assertThat(output).contains("## Code Review Report");
        assertThat(output).doesNotContain("bean not wired yet");
    }
}
