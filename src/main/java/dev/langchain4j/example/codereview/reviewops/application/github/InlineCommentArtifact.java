package dev.langchain4j.example.codereview.reviewops.application.github;

import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;

import java.util.Objects;

public record InlineCommentArtifact(FindingFingerprint fingerprint, String githubArtifactId) {
    public InlineCommentArtifact {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(githubArtifactId, "githubArtifactId");
        if (githubArtifactId.isBlank()) {
            throw new IllegalArgumentException("githubArtifactId must not be blank");
        }
    }
}
