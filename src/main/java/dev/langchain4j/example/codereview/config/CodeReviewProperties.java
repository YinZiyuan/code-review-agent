package dev.langchain4j.example.codereview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "code-review")
public record CodeReviewProperties(
        Rag rag,
        Orchestration orchestration,
        Eval eval
) {
    public record Rag(
            Path embeddingCacheDir,
            int topK,
            double minScore,
            boolean rerankEnabled
    ) { }

    public record Orchestration(
            Duration reviewerTimeout,
            int parallelism
    ) { }

    public record Eval(
            String judgeModel,
            int runsPerSample,
            Path samplesDir,
            Path reportDir
    ) { }
}
