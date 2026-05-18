package dev.langchain4j.example.codereview.config;

import dev.langchain4j.example.codereview.infra.EmbeddingCache;
import dev.langchain4j.example.codereview.rag.KnowledgeBaseIndexer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallEnV15QuantizedEmbeddingModel();
    }

    @Bean
    public EmbeddingCache embeddingCache(CodeReviewProperties props) {
        return new EmbeddingCache(props.rag().embeddingCacheDir());
    }

    @Bean
    public KnowledgeBaseIndexer knowledgeBaseIndexer(EmbeddingModel model, EmbeddingCache cache) {
        return new KnowledgeBaseIndexer(model, cache);
    }

    @Bean
    public ContentRetriever contentRetriever(
            KnowledgeBaseIndexer indexer,
            EmbeddingModel embeddingModel,
            CodeReviewProperties props) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(indexer.buildOrLoad())
                .embeddingModel(embeddingModel)
                .maxResults(props.rag().topK())
                .minScore(props.rag().minScore())
                .build();
    }
}
