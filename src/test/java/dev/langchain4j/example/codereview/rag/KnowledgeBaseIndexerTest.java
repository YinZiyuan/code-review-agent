package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.infra.EmbeddingCache;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseIndexerTest {

    @TempDir Path cacheDir;

    @Test
    void returnsCachedStoreWithoutEmbeddingDocumentsAgain() {
        EmbeddingCache cache = new EmbeddingCache(cacheDir);
        InMemoryEmbeddingStore<TextSegment> cachedStore = new InMemoryEmbeddingStore<>();
        cachedStore.add(Embedding.from(new float[]{1.0f, 0.0f, 0.0f}), TextSegment.from("cached guideline"));
        cache.save(KnowledgeBaseIndexer.CACHE_KEY, cachedStore);

        KnowledgeBaseIndexer indexer = new KnowledgeBaseIndexer(new FailingEmbeddingModel(), cache);

        InMemoryEmbeddingStore<TextSegment> store = indexer.buildOrLoad();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1.0f, 0.0f, 0.0f}))
                .maxResults(1)
                .build();
        assertThat(store.search(request).matches())
                .singleElement()
                .satisfies(match -> assertThat(match.embedded().text()).isEqualTo("cached guideline"));
    }

    @Test
    void buildsStoreAndSavesItWhenCacheIsMissing() {
        EmbeddingCache cache = new EmbeddingCache(cacheDir);
        KnowledgeBaseIndexer indexer = new KnowledgeBaseIndexer(new DeterministicEmbeddingModel(), cache);

        InMemoryEmbeddingStore<TextSegment> store = indexer.buildOrLoad();

        Optional<InMemoryEmbeddingStore<TextSegment>> cached = cache.load(KnowledgeBaseIndexer.CACHE_KEY);
        assertThat(cached).isPresent();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1.0f, 0.0f, 0.0f}))
                .maxResults(1)
                .build();
        assertThat(store.search(request).matches()).isNotEmpty();
        assertThat(cached.get().search(request).matches()).isNotEmpty();
    }

    private static class DeterministicEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            return Response.from(textSegments.stream()
                    .map(segment -> Embedding.from(new float[]{1.0f, 0.0f, 0.0f}))
                    .toList());
        }
    }

    private static class FailingEmbeddingModel implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            throw new AssertionError("cached knowledge base should not be embedded again");
        }
    }
}
