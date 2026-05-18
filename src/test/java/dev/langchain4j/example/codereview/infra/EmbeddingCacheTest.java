package dev.langchain4j.example.codereview.infra;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingCacheTest {

    @TempDir Path cacheDir;

    @Test
    void writesAndReadsBackStore() {
        EmbeddingCache cache = new EmbeddingCache(cacheDir);
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        store.add(Embedding.from(new float[]{0.1f, 0.2f, 0.3f}), TextSegment.from("hello"));

        cache.save("guidelines-v1", store);

        Optional<InMemoryEmbeddingStore<TextSegment>> loaded = cache.load("guidelines-v1");
        assertThat(loaded).isPresent();
        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.1f, 0.2f, 0.3f}))
                .maxResults(1)
                .build();
        assertThat(loaded.get().search(req).matches()).isNotEmpty();
    }

    @Test
    void missingKeyReturnsEmpty() {
        EmbeddingCache cache = new EmbeddingCache(cacheDir);
        assertThat(cache.load("nope")).isEmpty();
    }
}
