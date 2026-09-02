package dev.langchain4j.example.codereview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "code-review")
public record CodeReviewProperties(
        Rag rag,
        Eval eval
) {
    public record Rag(
            Path embeddingCacheDir,
            int topK,
            double minScore,
            boolean rerankEnabled,
            int bm25TopK,
            int rerankTopK,
            int rrfK
    ) { }

    public record Eval(
            String judgeModel,
            int runsPerSample,
            Path samplesDir,
            Path reportDir
    ) { }
}
