package dev.langchain4j.example.codereview.reviewops.application.github;

import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;

import java.util.Objects;

public record InlineCommentArtifact(FindingFingerprint fingerprint, String externalId) {
    public InlineCommentArtifact {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(externalId, "externalId");
        if (externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must not be blank");
        }
    }
}
