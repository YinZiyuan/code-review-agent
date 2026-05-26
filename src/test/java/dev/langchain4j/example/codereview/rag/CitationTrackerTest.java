package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CitationTrackerTest {

    private static Content content(String id, String section, String file) {
        return Content.from(TextSegment.from("body",
                Metadata.from(Map.of(
                        "citation_id", id,
                        "section", section,
                        "source_file", file))));
    }

    @Test
    void mapsContentsToCitations() {
        CitationTracker tracker = new CitationTracker();

        List<Citation> citations = tracker.toCitations(List.of(
                content("sql-injection-1", "Injection", "sql-guidelines.txt"),
                content("npe-null-safety", "Null Safety", "java-best-practices.txt")));

        assertThat(citations).extracting(Citation::id)
                .containsExactly("sql-injection-1", "npe-null-safety");
        assertThat(citations.get(1).source()).isEqualTo("java-best-practices.txt");
        assertThat(citations.get(1).section()).isEqualTo("Null Safety");
    }

    @Test
    void deduplicatesSameId() {
        CitationTracker tracker = new CitationTracker();

        List<Citation> citations = tracker.toCitations(List.of(
                content("dup", "S", "a.txt"),
                content("dup", "S", "a.txt")));

        assertThat(citations).hasSize(1);
    }
}
