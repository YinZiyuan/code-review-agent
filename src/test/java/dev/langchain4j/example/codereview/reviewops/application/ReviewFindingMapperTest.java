package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.reviewops.domain.CitationEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewFindingMapperTest {

    private final ReviewFindingMapper mapper = new ReviewFindingMapper();
    private final FileDiffSet diff = FileDiffSet.from(List.of(
            new DiffParser.FileDiff("src/Foo.java", List.of(
                    new DiffParser.AddedLine(12, "String query = input;")))));

    @Test
    void mapsPipelineFindingToNormalizedPostChangeDomainFinding() {
        dev.langchain4j.example.codereview.model.ReviewFinding pipelineFinding = finding(
                ".\\src\\Foo.java", 12, Severity.CRITICAL, Category.SECURITY,
                "  SQL   Injection ", " unsafe   query ",
                List.of(new Citation("rag-1", "owasp", "injection")));

        dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding mapped =
                mapper.map(pipelineFinding, diff);

        assertThat(mapped.location().file()).isEqualTo("src/Foo.java");
        assertThat(mapped.location().line()).isEqualTo(12);
        assertThat(mapped.location().changedLine()).isTrue();
        assertThat(mapped.content().severity()).isEqualTo(FindingSeverity.CRITICAL);
        assertThat(mapped.content().category()).isEqualTo(FindingCategory.SECURITY);
        assertThat(mapped.content().description()).isEqualTo("description");
        assertThat(mapped.content().suggestion()).isEqualTo("suggestion");
        assertThat(mapped.evidence().source()).isEqualTo("llm_reviewer");
        assertThat(mapped.evidence().citations()).containsExactly(
                new CitationEvidence("rag-1", "owasp", "injection"));
        assertThat(mapped.fingerprint().value())
                .isEqualTo("4df17b19bbf1d16d0822e7a31595284fdf3c284c068629cef90d34070d962a03");
    }

    @Test
    void calculatesNonAddedPostChangeLocationsWithoutDiscardingTheFinding() {
        var mapped = mapper.map(finding(
                "src/Foo.java", 13, Severity.WARNING, Category.STABILITY,
                "Unchecked value", "evidence", List.of()), diff);

        assertThat(mapped.location().line()).isEqualTo(13);
        assertThat(mapped.location().changedLine()).isFalse();
    }

    @Test
    void mapsEverySupportedSeverityAndCategoryByTheFixedDomainContract() {
        assertThat(List.of(Severity.CRITICAL, Severity.WARNING, Severity.SUGGESTION).stream()
                .map(severity -> mapper.map(finding(
                        "src/Foo.java", 12, severity, Category.OTHER,
                        "title", "evidence", List.of()), diff).content().severity()))
                .containsExactly(
                        FindingSeverity.CRITICAL,
                        FindingSeverity.WARNING,
                        FindingSeverity.SUGGESTION);

        assertThat(List.of(Category.SECURITY, Category.PERFORMANCE, Category.STABILITY,
                        Category.CONCURRENCY, Category.TEST, Category.STYLE, Category.OTHER).stream()
                .map(category -> mapper.map(finding(
                        "src/Foo.java", 12, Severity.WARNING, category,
                        "title", "evidence", List.of()), diff).content().category()))
                .containsExactly(
                        FindingCategory.SECURITY,
                        FindingCategory.PERFORMANCE,
                        FindingCategory.STABILITY,
                        FindingCategory.CONCURRENCY,
                        FindingCategory.TEST,
                        FindingCategory.STYLE,
                        FindingCategory.OTHER);
    }

    @Test
    void rejectsMissingOrUnsafeFileLocations() {
        assertThatThrownBy(() -> mapper.map(finding(
                null, 12, Severity.WARNING, Category.OTHER,
                "title", "evidence", List.of()), diff))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(finding(
                "../secret.txt", 12, Severity.WARNING, Category.OTHER,
                "title", "evidence", List.of()), diff))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(finding(
                "/etc/passwd", 12, Severity.WARNING, Category.OTHER,
                "title", "evidence", List.of()), diff))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingOrNonPositivePostChangeLines() {
        assertThatThrownBy(() -> mapper.map(finding(
                "src/Foo.java", null, Severity.WARNING, Category.OTHER,
                "title", "evidence", List.of()), diff))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(finding(
                "src/Foo.java", 0, Severity.WARNING, Category.OTHER,
                "title", "evidence", List.of()), diff))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static dev.langchain4j.example.codereview.model.ReviewFinding finding(
            String file, Integer line, Severity severity, Category category,
            String title, String evidence, List<Citation> citations) {
        return new dev.langchain4j.example.codereview.model.ReviewFinding(
                "F-001",
                file,
                line,
                null,
                severity,
                category,
                title,
                "description",
                "suggestion",
                evidence,
                citations,
                "llm_reviewer");
    }
}
