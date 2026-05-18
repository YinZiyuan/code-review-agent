package dev.langchain4j.example.codereview.infra;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class EmbeddingCache {

    private final Path cacheDir;

    public EmbeddingCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create cache dir: " + cacheDir, e);
        }
    }

    public void save(String key, InMemoryEmbeddingStore<TextSegment> store) {
        Path file = cacheDir.resolve(sanitize(key) + ".json");
        try {
            Files.writeString(file, store.serializeToJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot write cache file: " + file, e);
        }
    }

    public Optional<InMemoryEmbeddingStore<TextSegment>> load(String key) {
        Path file = cacheDir.resolve(sanitize(key) + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(InMemoryEmbeddingStore.fromJson(json));
        } catch (IOException e) {
            throw new RuntimeException("Cannot read cache file: " + file, e);
        }
    }

    private String sanitize(String key) {
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
