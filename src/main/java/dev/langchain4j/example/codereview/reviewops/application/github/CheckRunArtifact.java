package dev.langchain4j.example.codereview.reviewops.application.github;

import java.util.Objects;

public record CheckRunArtifact(String externalId) {
    public CheckRunArtifact {
        Objects.requireNonNull(externalId, "externalId");
        if (externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must not be blank");
        }
    }
}
