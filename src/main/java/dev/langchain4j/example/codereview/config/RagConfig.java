package dev.langchain4j.example.codereview.config;

import dev.langchain4j.example.codereview.infra.EmbeddingCache;
import dev.langchain4j.example.codereview.rag.Bm25Retriever;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.example.codereview.rag.HybridRetriever;
import dev.langchain4j.example.codereview.rag.KnowledgeBaseIndexer;
import dev.langchain4j.example.codereview.rag.LlmReranker;
import dev.langchain4j.model.chat.ChatModel;
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
        KnowledgeBaseIndexer indexer = new KnowledgeBaseIndexer(model, cache);
        indexer.buildOrLoad();
        return indexer;
    }

    @Bean
    public CitationTracker citationTracker() {
        return new CitationTracker();
    }

    @Bean
    public ContentRetriever contentRetriever(
            KnowledgeBaseIndexer indexer,
            EmbeddingModel embeddingModel,
            ChatModel chatModel,
            CodeReviewProperties props) {
        ContentRetriever vector = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(indexer.buildOrLoad())
                .embeddingModel(embeddingModel)
                .maxResults(props.rag().topK())
                .minScore(props.rag().minScore())
                .build();

        Bm25Retriever bm25 = indexer.getBm25Retriever();
        ContentRetriever bm25Wrapped = query -> bm25.retrieve(query, props.rag().bm25TopK());
        ContentRetriever hybrid = new HybridRetriever(vector, bm25Wrapped,
                props.rag().rrfK(), props.rag().rerankTopK());

        if (!props.rag().rerankEnabled()) {
            return hybrid;
        }
        return new LlmReranker(hybrid, chatModel, props.rag().rerankTopK());
    }
}
