package dev.langchain4j.example.codereview.reporting;

import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.model.ToolRunState;
import dev.langchain4j.example.codereview.model.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownReporterTest {

    private final MarkdownReporter reporter = new MarkdownReporter();

    @Test
    void emptyResultProducesNoIssuesSection() {
        String md = reporter.render(ReviewResult.empty("Nothing to review."));

        assertThat(md).contains("## Code Review Report");
        assertThat(md).contains("Nothing to review.");
        assertThat(md).contains("No issues found");
    }

    @Test
    void rendersFindingsSortedBySeverity() {
        ReviewResult r = new ReviewResult(
                "2 findings.",
                List.of(
                        new ReviewFinding("F-002", "B.java", 20, null, Severity.SUGGESTION,
                                Category.STYLE, "Style nit", "desc", "fix", "ev", List.of(), "regex"),
                        new ReviewFinding("F-001", "A.java", 10, null, Severity.CRITICAL,
                                Category.SECURITY, "SQL injection", "desc", "fix", "ev", List.of(), "llm_reviewer")
                ),
                List.of()
        );

        String md = reporter.render(r);

        assertThat(md.indexOf("CRITICAL")).isLessThan(md.indexOf("SUGGESTION"));
        assertThat(md).contains("A.java:10");
        assertThat(md).contains("B.java:20");
    }

    @Test
    void includesCitations() {
        ReviewResult r = new ReviewResult(
                "1 finding.",
                List.of(new ReviewFinding(
                        "F-001", "A.java", 10, null, Severity.WARNING, Category.SECURITY,
                        "title", "desc", "fix", "ev",
                        List.of(new Citation("sec#1", "security-checklist.txt", "SQL")),
                        "llm_reviewer")),
                List.of()
        );

        String md = reporter.render(r);

        assertThat(md).contains("security-checklist.txt");
        assertThat(md).contains("SQL");
    }

    @Test
    void includesToolStatus() {
        ReviewResult r = new ReviewResult(
                "ok",
                List.of(),
                List.of(new ToolStatus("spotbugs", ToolRunState.SKIPPED_EXPECTED, "project did not compile"))
        );

        String md = reporter.render(r);

        assertThat(md).contains("spotbugs");
        assertThat(md).contains("SKIPPED_EXPECTED");
        assertThat(md).contains("project did not compile");
    }
}
