package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationKeywordInjectorTest {

    private final CitationKeywordInjector injector = new CitationKeywordInjector();

    @Test
    void section_keyword_in_finding_text_injects_citation() {
        Citation candidate = new Citation("sql-guidelines#parameterized-queries",
                "sql-guidelines.txt", "Parameterized Queries");
        ReviewFinding finding = finding("SQL injection via concatenation",
                "Use parameterized queries instead", List.of());

        List<ReviewFinding> out = injector.inject(List.of(finding), List.of(candidate));

        assertThat(out.get(0).citations()).containsExactly(candidate);
    }

    @Test
    void existing_citation_is_preserved_not_replaced() {
        Citation existing = new Citation("security-checklist#sql-001",
                "security-checklist.txt", "SQL Injection");
        Citation candidate = new Citation("sql-guidelines#parameterized-queries",
                "sql-guidelines.txt", "Parameterized Queries");
        ReviewFinding finding = finding("SQL injection",
                "Use parameterized queries", List.of(existing));

        List<ReviewFinding> out = injector.inject(List.of(finding), List.of(candidate));

        assertThat(out.get(0).citations()).containsExactly(existing);
    }

    @Test
    void unrelated_finding_stays_empty() {
        Citation candidate = new Citation("sql-guidelines#parameterized-queries",
                "sql-guidelines.txt", "Parameterized Queries");
        ReviewFinding finding = finding("Race condition on counter",
                "Use AtomicLong", List.of());

        List<ReviewFinding> out = injector.inject(List.of(finding), List.of(candidate));

        assertThat(out.get(0).citations()).isEmpty();
    }

    private ReviewFinding finding(String title, String description, List<Citation> citations) {
        return new ReviewFinding("F-001", "Foo.java", 10, new int[]{10, 12},
                Severity.WARNING, Category.SECURITY, title, description,
                "fix it", "evidence", citations, "llm_reviewer");
    }
}
