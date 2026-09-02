package dev.langchain4j.example.codereview.tools;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeSearchToolTest {

    @TempDir
    Path temporaryDirectory;

    private final CodeSearchTool tool = new CodeSearchTool();
    private static final String FIXTURES = "src/test/resources/fixtures/code-search";

    @Test
    void findsSubstringAcrossFilesAndReportsLine() {
        String result = tool.grep(FIXTURES, "Foo");
        assertThat(result)
                .contains("Foo.java:")
                .contains("Bar.java:");
    }

    @Test
    void emptyResultMessageWhenNoMatches() {
        String result = tool.grep(FIXTURES, "definitely-not-here-zzz");
        assertThat(result).contains("No matches");
    }

    @Test
    void honorsNestedJavaFiles() {
        String result = tool.grep(FIXTURES, "println");
        assertThat(result).contains("nested/Baz.java");
    }

    @Test
    void capsHitsAndReportsTruncation() {
        String result = tool.grep(FIXTURES, "{");
        long lines = result.lines().filter(l -> l.contains(".java:")).count();
        assertThat(lines).isLessThanOrEqualTo(50);
    }

    @Test
    void searchesManyNeedlesWithOneBoundedStreamingRead() throws Exception {
        Path source = temporaryDirectory.resolve("One.java");
        Files.writeString(source, "class One { void usefulNeedle() {} }\n");
        List<String> needles = java.util.stream.IntStream.range(0, 2_000)
                .mapToObj(index -> "absent" + index)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        needles.add("usefulNeedle");

        CodeSearchTool.SearchResult result = tool.search(
                temporaryDirectory, needles, defaults());

        assertThat(result.status()).isEqualTo(CodeSearchTool.SearchStatus.COMPLETE);
        assertThat(result.bytesRead()).isEqualTo(Files.size(source));
        assertThat(result.filesRead()).isEqualTo(1);
        assertThat(result.hits().get("usefulNeedle")).hasSize(1);
    }

    @Test
    void rejectsAnOversizedJavaFileBeforeOpeningOrDecodingIt() throws Exception {
        Path source = temporaryDirectory.resolve("Huge.java");
        try (FileChannel channel = FileChannel.open(
                source, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(defaults().input().maxJavaSourceBytes());
            channel.write(ByteBuffer.wrap(new byte[]{'x'}));
        }

        CodeSearchTool.SearchResult result = tool.search(
                temporaryDirectory, List.of("secretNeedle"), defaults());

        assertThat(result.status()).isEqualTo(CodeSearchTool.SearchStatus.LIMIT_EXCEEDED);
        assertThat(result.bytesRead()).isZero();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void stopsBeforeReadingWhenTheReviewStageWasCancelled() {
        Thread.currentThread().interrupt();
        try {
            CodeSearchTool.SearchResult result = tool.search(
                    temporaryDirectory, List.of("anything"), defaults());
            assertThat(result.status()).isEqualTo(CodeSearchTool.SearchStatus.CANCELLED);
            assertThat(result.bytesRead()).isZero();
        } finally {
            Thread.interrupted();
        }
    }

    private static ReviewWorkBudget defaults() {
        return new ReviewWorkBudgetProperties(
                null, null, null, null, null, null).toBudget();
    }
}
