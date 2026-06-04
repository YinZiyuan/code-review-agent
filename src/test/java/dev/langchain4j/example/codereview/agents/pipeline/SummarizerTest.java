package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.model.ToolRunState;
import dev.langchain4j.example.codereview.model.ToolStatus;
import dev.langchain4j.example.codereview.rag.CitationKeywordInjector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SummarizerTest {

    private final Summarizer summarizer = new Summarizer(new CitationKeywordInjector());

    @Test
    void near_duplicate_findings_are_merged_keeping_highest_severity() {
        ReviewFinding a = mk("F-001", "Foo.java", 10, Severity.WARNING,
                "SQL injection in query", List.of());
        ReviewFinding b = mk("F-002", "Foo.java", 11, Severity.CRITICAL,
                "SQL injection in query", List.of());
        ReviewResult draft = new ReviewResult("s", List.of(a, b), List.of());

        ReviewResult out = summarizer.summarize(draft,
                new ToolFindings(List.of(), List.of()), List.of());

        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void unreported_critical_violation_is_backfilled_as_finding() {
        ReviewResult draft = new ReviewResult("s", List.of(), List.of());
        Violation v = new Violation(Severity.CRITICAL, "Foo.java", 42,
                "hardcoded-credential", "Possible hardcoded credential");

        ReviewResult out = summarizer.summarize(draft,
                new ToolFindings(List.of(v), List.of()), List.of());

        assertThat(out.findings()).hasSize(1);
        assertThat(out.findings().get(0).source()).isEqualTo("regex");
        assertThat(out.findings().get(0).line()).isEqualTo(42);
    }

    @Test
    void empty_citations_are_backfilled_from_candidates() {
        Citation candidate = new Citation("sql-guidelines#parameterized-queries",
                "sql-guidelines.txt", "Parameterized Queries");
        ReviewFinding f = mk("F-001", "Foo.java", 10, Severity.WARNING,
                "SQL injection via concatenation", List.of());

        ReviewResult out = summarizer.summarize(
                new ReviewResult("s", List.of(f), List.of()),
                new ToolFindings(List.of(), List.of()),
                List.of(candidate));

        assertThat(out.findings().get(0).citations()).containsExactly(candidate);
    }

    @Test
    void tool_statuses_are_passed_through() {
        ReviewResult out = summarizer.summarize(
                new ReviewResult("s", List.of(), List.of()),
                new ToolFindings(List.of(),
                        List.of(new ToolStatus("regex", ToolRunState.RAN, null),
                                new ToolStatus("spotbugs", ToolRunState.SKIPPED_EXPECTED, "x"))),
                List.of());

        assertThat(out.toolStatus()).hasSize(2);
    }

    @Test
    void findings_sorted_by_severity_then_file_then_line() {
        ReviewFinding low = mk("F-1", "Aaa.java", 1, Severity.SUGGESTION, "a", List.of());
        ReviewFinding hi = mk("F-2", "Zzz.java", 99, Severity.CRITICAL, "z", List.of());
        ReviewResult out = summarizer.summarize(
                new ReviewResult("s", List.of(low, hi), List.of()),
                new ToolFindings(List.of(), List.of()), List.of());

        assertThat(out.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(out.findings().get(1).severity()).isEqualTo(Severity.SUGGESTION);
    }

    private ReviewFinding mk(String id, String file, int line, Severity sev,
                             String title, List<Citation> citations) {
        return new ReviewFinding(id, file, line, null, sev, Category.SECURITY,
                title, title, "fix", "evidence", citations, "llm_reviewer");
    }
}
