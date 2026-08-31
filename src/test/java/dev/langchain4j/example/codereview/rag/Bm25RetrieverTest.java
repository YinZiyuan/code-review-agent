package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25RetrieverTest {

    @Test
    void indexAndQueryReturnsRelevantChunks() {
        Bm25Retriever retriever = new Bm25Retriever();
        retriever.index(List.of(
                new Bm25Retriever.Doc("Use PreparedStatement to avoid SQL injection.",
                        new ChunkMetadata("sql.txt", "Injection", "Use PreparedStatement to avoid SQL injection.")),
                new Bm25Retriever.Doc("Always close Streams in try-with-resources.",
                        new ChunkMetadata("io.txt", "Resources", "Always close Streams in try-with-resources.")),
                new Bm25Retriever.Doc("Validate user input before persisting.",
                        new ChunkMetadata("sql.txt", "Validation", "Validate user input before persisting."))
        ));

        List<Content> hits = retriever.retrieve(Query.from("sql injection"), 2);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).textSegment().text()).contains("SQL injection");
    }

    @Test
    void unmatchedQueryReturnsEmpty() {
        Bm25Retriever retriever = new Bm25Retriever();
        retriever.index(List.of(new Bm25Retriever.Doc("anything",
                new ChunkMetadata("x.txt", "s", "anything"))));

        assertThat(retriever.retrieve(Query.from("not-present"), 5)).isEmpty();
    }
}
