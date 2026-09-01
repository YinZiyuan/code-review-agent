package dev.langchain4j.example.codereview.reviewops.application.github;

import java.util.Objects;

public record CheckRunArtifact(String githubArtifactId) {
    public CheckRunArtifact {
        Objects.requireNonNull(githubArtifactId, "githubArtifactId");
        if (githubArtifactId.isBlank()) {
            throw new IllegalArgumentException("githubArtifactId must not be blank");
        }
    }
}
