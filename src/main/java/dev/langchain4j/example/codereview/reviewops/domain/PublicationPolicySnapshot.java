package dev.langchain4j.example.codereview.reviewops.domain;

public record PublicationPolicySnapshot(String version, int maxInlineComments) {
    public PublicationPolicySnapshot {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version is required");
        }
        if (maxInlineComments < 0) {
            throw new IllegalArgumentException("maxInlineComments must not be negative");
        }
    }
}
