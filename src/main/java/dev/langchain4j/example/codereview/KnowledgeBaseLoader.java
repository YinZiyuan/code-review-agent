package dev.langchain4j.example.codereview;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;

import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocuments;
import static dev.langchain4j.data.document.splitter.DocumentSplitters.recursive;

public class KnowledgeBaseLoader {

    public static ContentRetriever load() {
        EmbeddingModel embeddingModel = new BgeSmallEnV15QuantizedEmbeddingModel();
        EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        Path guidelinesPath = toClasspathPath("review-guidelines/");
        PathMatcher txtMatcher = FileSystems.getDefault().getPathMatcher("glob:*.txt");
        List<Document> docs = loadDocuments(guidelinesPath, txtMatcher);

        EmbeddingStoreIngestor.builder()
                .documentSplitter(recursive(500, 50))
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build()
                .ingest(docs);

        System.out.println("Knowledge base loaded: " + docs.size() + " document(s) ingested.");

        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .minScore(0.4)
                .build();
    }

    private static Path toClasspathPath(String relativePath) {
        try {
            URL url = KnowledgeBaseLoader.class.getClassLoader().getResource(relativePath);
            if (url == null) throw new RuntimeException("Resource not found: " + relativePath);
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
