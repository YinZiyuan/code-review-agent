package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.infra.EmbeddingCache;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocuments;
import static dev.langchain4j.data.document.splitter.DocumentSplitters.recursive;

public class KnowledgeBaseIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIndexer.class);
    private static final String CACHE_KEY = "review-guidelines";

    private final EmbeddingModel embeddingModel;
    private final EmbeddingCache cache;

    public KnowledgeBaseIndexer(EmbeddingModel embeddingModel, EmbeddingCache cache) {
        this.embeddingModel = embeddingModel;
        this.cache = cache;
    }

    public InMemoryEmbeddingStore<TextSegment> buildOrLoad() {
        Optional<InMemoryEmbeddingStore<TextSegment>> cached = cache.load(CACHE_KEY);
        if (cached.isPresent()) {
            log.info("Loaded knowledge base from cache (key={})", CACHE_KEY);
            return cached.get();
        }

        log.info("Building knowledge base from scratch...");
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

        Path guidelinesPath = toClasspathPath("review-guidelines/");
        PathMatcher txtMatcher = FileSystems.getDefault().getPathMatcher("glob:*.txt");
        List<Document> docs = loadDocuments(guidelinesPath, txtMatcher);

        EmbeddingStoreIngestor.builder()
                .documentSplitter(recursive(500, 50))
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build()
                .ingest(docs);

        cache.save(CACHE_KEY, store);
        log.info("Indexed {} document(s); cache saved.", docs.size());
        return store;
    }

    private static Path toClasspathPath(String relativePath) {
        try {
            URL url = KnowledgeBaseIndexer.class.getClassLoader().getResource(relativePath);
            if (url == null) throw new RuntimeException("Resource not found: " + relativePath);
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
