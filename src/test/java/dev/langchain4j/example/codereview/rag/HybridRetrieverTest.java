package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrieverTest {

    private static Content content(String text, String id) {
        return Content.from(TextSegment.from(text, Metadata.from("citation_id", id)));
    }

    @Test
    void rrfRanksItemInBothListsAboveSingletons() {
        ContentRetriever vector = q -> List.of(
                content("A common doc", "doc-A"),
                content("Only-vector", "doc-V")
        );
        ContentRetriever bm25 = q -> List.of(
                content("Only-bm25", "doc-B"),
                content("A common doc", "doc-A")
        );

        HybridRetriever retriever = new HybridRetriever(vector, bm25, 60, 3);
        List<Content> hits = retriever.retrieve(Query.from("anything"));

        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).textSegment().metadata().getString("citation_id"))
                .isEqualTo("doc-A");
    }

    @Test
    void deduplicatesByCitationId() {
        ContentRetriever vector = q -> List.of(content("x", "id-1"), content("x", "id-1"));
        ContentRetriever bm25 = q -> List.of();

        HybridRetriever retriever = new HybridRetriever(vector, bm25, 60, 5);

        assertThat(retriever.retrieve(Query.from("q"))).hasSize(1);
    }
}
