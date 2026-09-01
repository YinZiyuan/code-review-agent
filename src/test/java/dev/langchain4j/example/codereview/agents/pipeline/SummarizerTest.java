package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.model.ToolRunState;
import dev.langchain4j.example.codereview.model.ToolStatus;
import dev.langchain4j.example.codereview.rag.CitationKeywordInjector;
import dev.langchain4j.example.codereview.reviewops.application.FileDiffSet;
import dev.langchain4j.example.codereview.reviewops.application.ReviewFindingMapper;
import dev.langchain4j.example.codereview.reviewops.domain.FindingPublicationPolicy;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationPolicySnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
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
    void same_category_findings_at_same_location_are_deduplicated_even_with_different_titles() {
        ReviewFinding a = new ReviewFinding("F-001", "Foo.java", 10, null, Severity.WARNING,
                Category.STABILITY, "Null return", "desc a", "fix", "e", List.of(), "llm_reviewer");
        ReviewFinding b = new ReviewFinding("F-002", "Foo.java", 11, null, Severity.WARNING,
                Category.STABILITY, "Silent failure", "desc b", "fix", "e", List.of(), "llm_reviewer");

        ReviewResult out = summarizer.summarize(
                new ReviewResult("s", List.of(a, b), List.of()),
                new ToolFindings(List.of(), List.of()), List.of());

        assertThat(out.findings()).hasSize(1);
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
    void hallucinatedCitationCannotAuthorizeInlinePublication() {
        Citation hallucinated = new Citation(
                "sql-guidelines#parameterized-queries", "sql-guidelines.txt", "Parameterized Queries");
        Citation retrieved = new Citation(
                "java-concurrency#memory-model", "java-concurrency.txt", "Java Memory Model");
        ReviewFinding finding = mk(
                "F-001", "Foo.java", 10, Severity.WARNING,
                "SQL injection via concatenation", List.of(hallucinated));

        ReviewResult result = summarizer.summarize(
                new ReviewResult("s", List.of(finding), List.of()),
                new ToolFindings(List.of(), List.of()),
                List.of(retrieved));

        assertThat(result.findings().get(0).citations()).isEmpty();
        var mapped = new ReviewFindingMapper().map(
                result.findings().get(0),
                FileDiffSet.from(List.of(new DiffParser.FileDiff(
                        "Foo.java", List.of(new DiffParser.AddedLine(10, "String query = input;"))))));
        assertThat(new FindingPublicationPolicy()
                .decide(List.of(mapped), new PublicationPolicySnapshot("publish-v1", 5))
                .get(mapped.fingerprint()).tier())
                .isEqualTo(PublicationTier.CHECK_SUMMARY);
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
        ReviewFinding low = finding("F-1", "Aaa.java", 1, Category.STYLE, Severity.SUGGESTION);
        ReviewFinding hi = mk("F-2", "Zzz.java", 99, Severity.CRITICAL, "z", List.of());
        ReviewResult out = summarizer.summarize(
                new ReviewResult("s", List.of(low, hi), List.of()),
                new ToolFindings(List.of(), List.of()), List.of());

        assertThat(out.findings().get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(out.findings().get(1).severity()).isEqualTo(Severity.SUGGESTION);
    }

    @Test
    void calibrates_actionable_finding_severity_by_category_without_changing_findings() {
        ReviewFinding security = finding("F-1", "Security.java", 10, Category.SECURITY, Severity.WARNING);
        ReviewFinding stability = finding("F-2", "Stability.java", 20, Category.STABILITY, Severity.CRITICAL);
        ReviewFinding style = finding("F-3", "Style.java", 30, Category.STYLE, Severity.SUGGESTION);

        ReviewResult out = summarizer.summarize(
                new ReviewResult("s", List.of(security, stability, style), List.of()),
                new ToolFindings(List.of(), List.of()), List.of());

        assertThat(out.findings()).extracting(ReviewFinding::id)
                .containsExactlyInAnyOrder("F-1", "F-2", "F-3");
        assertThat(out.findings()).filteredOn(f -> f.id().equals("F-1"))
                .extracting(ReviewFinding::severity).containsExactly(Severity.CRITICAL);
        assertThat(out.findings()).filteredOn(f -> f.id().equals("F-2"))
                .extracting(ReviewFinding::severity).containsExactly(Severity.WARNING);
        assertThat(out.findings()).filteredOn(f -> f.id().equals("F-3"))
                .extracting(ReviewFinding::severity).containsExactly(Severity.SUGGESTION);
    }

    @Test
    void missing_category_preserves_model_severity() {
        ReviewFinding uncategorized = finding("F-1", "Foo.java", 10, null, Severity.SUGGESTION);

        ReviewResult out = summarizer.summarize(
                new ReviewResult("s", List.of(uncategorized), List.of()),
                new ToolFindings(List.of(), List.of()), List.of());

        assertThat(out.findings()).singleElement()
                .extracting(ReviewFinding::severity).isEqualTo(Severity.SUGGESTION);
    }

    private ReviewFinding finding(String id, String file, int line, Category category, Severity severity) {
        return new ReviewFinding(id, file, line, null, severity, category,
                "title", "description", "fix", "evidence", List.of(), "llm_reviewer");
    }

    private ReviewFinding mk(String id, String file, int line, Severity sev,
                             String title, List<Citation> citations) {
        return new ReviewFinding(id, file, line, null, sev, Category.SECURITY,
                title, title, "fix", "evidence", citations, "llm_reviewer");
    }
}
