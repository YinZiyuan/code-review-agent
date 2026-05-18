package dev.langchain4j.example.codereview.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewFindingTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void deserializesFullExample() throws Exception {
        String json = """
                {
                  "id": "F-001",
                  "file": "src/main/java/UserCtrl.java",
                  "line": 42,
                  "line_range": [40, 45],
                  "severity": "CRITICAL",
                  "category": "SECURITY",
                  "title": "SQL injection",
                  "description": "User input concatenated into SQL",
                  "suggestion": "Use prepared statement",
                  "evidence": "request.getParameter('id') concatenated into query",
                  "citations": [
                    {"id": "security-checklist#sql-001", "source": "security-checklist.txt", "section": "SQL Injection"}
                  ],
                  "source": "llm_reviewer"
                }
                """;

        ReviewFinding f = mapper.readValue(json, ReviewFinding.class);

        assertThat(f.id()).isEqualTo("F-001");
        assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(f.category()).isEqualTo(Category.SECURITY);
        assertThat(f.lineRange()).containsExactly(40, 45);
        assertThat(f.citations()).hasSize(1);
        assertThat(f.citations().get(0).source()).isEqualTo("security-checklist.txt");
    }

    @Test
    void roundTripsResult() throws Exception {
        ReviewResult r = new ReviewResult(
                "Found 1 finding.",
                List.of(new ReviewFinding(
                        "F-001", "Foo.java", 10, new int[]{10, 12},
                        Severity.WARNING, Category.STYLE,
                        "Bad name", "Bad name detected", "Rename it", "the name is x1",
                        List.of(), "regex")),
                List.of(new ToolStatus("regex", "ok", null))
        );

        String json = mapper.writeValueAsString(r);
        ReviewResult parsed = mapper.readValue(json, ReviewResult.class);

        assertThat(parsed.findings()).hasSize(1);
        assertThat(parsed.findings().get(0).source()).isEqualTo("regex");
        assertThat(json).contains("tool_status");
    }

    @Test
    void rejectsUnknownSeverity() {
        String json = """
                {"id":"X","file":"a","severity":"BANANA","category":"OTHER","title":"t","description":"d","suggestion":"s","evidence":"e","source":"regex"}
                """;

        assertThatThrownBy(() -> mapper.readValue(json, ReviewFinding.class))
                .hasMessageContaining("BANANA");
    }
}
