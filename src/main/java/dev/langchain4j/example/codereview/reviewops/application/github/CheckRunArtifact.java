package dev.langchain4j.example.codereview.reviewops.application.github;

import java.util.Objects;

public record CheckRunArtifact(String githubArtifactId, Reconciliation reconciliation) {

    public CheckRunArtifact(String githubArtifactId) {
        this(githubArtifactId, Reconciliation.CONFIRMED);
    }

    public CheckRunArtifact {
        Objects.requireNonNull(githubArtifactId, "githubArtifactId");
        Objects.requireNonNull(reconciliation, "reconciliation");
        if (githubArtifactId.isBlank()) {
            throw new IllegalArgumentException("githubArtifactId must not be blank");
        }
    }

    public enum Reconciliation {
        CONFIRMED,
        RECONCILED,
        CREATED,
        REPLACED_MISSING
    }
}
