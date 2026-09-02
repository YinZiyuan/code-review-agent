package dev.langchain4j.example.codereview.reviewops.application.github;

import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;

import java.util.Objects;

public record InlineCommentArtifact(
        FindingFingerprint fingerprint,
        String githubArtifactId,
        Reconciliation reconciliation) {

    public InlineCommentArtifact(FindingFingerprint fingerprint, String githubArtifactId) {
        this(fingerprint, githubArtifactId, Reconciliation.CONFIRMED);
    }

    public InlineCommentArtifact {
        Objects.requireNonNull(fingerprint, "fingerprint");
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
