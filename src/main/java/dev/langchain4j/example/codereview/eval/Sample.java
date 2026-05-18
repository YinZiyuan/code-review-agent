package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public record Sample(
        String id,
        Path directory,
        String diffPatch,
        Path sourceBeforeDir,
        Annotation annotation,
        Map<String, Object> meta
) {
    public static Sample load(Path sampleDir, ObjectMapper mapper) throws IOException {
        String diff = Files.readString(sampleDir.resolve("diff.patch"));
        Annotation annotation = mapper.readValue(Files.readString(sampleDir.resolve("annotation.json")), Annotation.class);
        Map<String, Object> meta = Map.of();
        Path metaFile = sampleDir.resolve("meta.json");
        if (Files.exists(metaFile)) {
            meta = mapper.readValue(Files.readString(metaFile), Map.class);
        }
        return new Sample(sampleDir.getFileName().toString(), sampleDir, diff,
                sampleDir.resolve("source-before"), annotation, meta);
    }

    public static ObjectMapper defaultMapper() {
        return new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
