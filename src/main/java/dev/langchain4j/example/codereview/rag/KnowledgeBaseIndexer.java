package dev.langchain4j.example.codereview.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.example.codereview.infra.EmbeddingCache;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class KnowledgeBaseIndexer {

    public static final String CACHE_KEY = "review-guidelines-v2";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIndexer.class);
    private static final int CHUNK_MAX_CHARS = 500;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingCache cache;
    private InMemoryEmbeddingStore<TextSegment> store;
    private Bm25Retriever bm25;

    public KnowledgeBaseIndexer(EmbeddingModel embeddingModel, EmbeddingCache cache) {
        this.embeddingModel = embeddingModel;
        this.cache = cache;
    }

    public synchronized InMemoryEmbeddingStore<TextSegment> buildOrLoad() {
        List<Chunk> chunks = readChunks();
        Optional<InMemoryEmbeddingStore<TextSegment>> cached = cache.load(CACHE_KEY);
        if (cached.isPresent()) {
            this.store = cached.get();
            log.info("Loaded vector store from cache (key={})", CACHE_KEY);
        } else {
            this.store = new InMemoryEmbeddingStore<>();
            for (Chunk chunk : chunks) {
                TextSegment segment = TextSegment.from(chunk.text(), toMetadata(chunk.metadata()));
                store.add(embeddingModel.embed(segment).content(), segment);
            }
            cache.save(CACHE_KEY, store);
            log.info("Indexed {} chunk(s); cache saved.", chunks.size());
        }

        this.bm25 = new Bm25Retriever();
        this.bm25.index(chunks.stream()
                .map(chunk -> new Bm25Retriever.Doc(chunk.text(), chunk.metadata()))
                .toList());
        return store;
    }

    public InMemoryEmbeddingStore<TextSegment> getEmbeddingStore() {
        if (store == null) {
            buildOrLoad();
        }
        return store;
    }

    public Bm25Retriever getBm25Retriever() {
        if (bm25 == null) {
            buildOrLoad();
        }
        return bm25;
    }

    private record Chunk(String text, ChunkMetadata metadata) {
    }

    private List<Chunk> readChunks() {
        Path dir = toClasspathPath("review-guidelines/");
        List<Chunk> chunks = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream
                    .filter(path -> path.toString().endsWith(".txt"))
                    .sorted()
                    .toList()) {
                readFileChunks(file, chunks);
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot read guidelines", e);
        }
        return chunks;
    }

    private static void readFileChunks(Path file, List<Chunk> chunks) throws IOException {
        String fileName = file.getFileName().toString();
        String body = Files.readString(file, StandardCharsets.UTF_8);
        String section = "intro";
        StringBuilder buffer = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("## ")) {
                flush(chunks, fileName, section, buffer);
                section = line.substring(3).trim();
            } else {
                buffer.append(line).append('\n');
                if (buffer.length() > CHUNK_MAX_CHARS) {
                    flush(chunks, fileName, section, buffer);
                }
            }
        }
        flush(chunks, fileName, section, buffer);
    }

    private static void flush(List<Chunk> chunks, String file, String section, StringBuilder buffer) {
        String text = buffer.toString().trim();
        buffer.setLength(0);
        if (text.isBlank()) {
            return;
        }
        chunks.add(new Chunk(text, new ChunkMetadata(file, section, text)));
    }

    private static Metadata toMetadata(ChunkMetadata metadata) {
        return Metadata.from(Map.of(
                "source_file", metadata.sourceFile(),
                "section", metadata.section() == null ? "" : metadata.section(),
                "citation_id", metadata.citationId()
        ));
    }

    private static Path toClasspathPath(String relativePath) {
        try {
            URL url = KnowledgeBaseIndexer.class.getClassLoader().getResource(relativePath);
            if (url == null) {
                throw new RuntimeException("Resource not found: " + relativePath);
            }
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
