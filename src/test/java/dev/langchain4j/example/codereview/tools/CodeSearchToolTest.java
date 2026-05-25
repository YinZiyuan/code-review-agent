package dev.langchain4j.example.codereview.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSearchToolTest {

    private final CodeSearchTool tool = new CodeSearchTool();
    private static final String FIXTURES = "src/test/resources/fixtures/code-search";

    @Test
    void findsSubstringAcrossFilesAndReportsLine() {
        String result = tool.searchCode(FIXTURES, "Foo");
        assertThat(result)
                .contains("Foo.java:")
                .contains("Bar.java:");
    }

    @Test
    void emptyResultMessageWhenNoMatches() {
        String result = tool.searchCode(FIXTURES, "definitely-not-here-zzz");
        assertThat(result).contains("No matches");
    }

    @Test
    void honorsNestedJavaFiles() {
        String result = tool.searchCode(FIXTURES, "println");
        assertThat(result).contains("nested/Baz.java");
    }

    @Test
    void capsHitsAndReportsTruncation() {
        String result = tool.searchCode(FIXTURES, "{");
        long lines = result.lines().filter(l -> l.contains(".java:")).count();
        assertThat(lines).isLessThanOrEqualTo(50);
    }
}
