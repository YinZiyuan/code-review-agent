package dev.langchain4j.example.codereview.reviewops.domain;

public record PublicationDecision(PublicationTier tier, String policyVersion) {
    public PublicationDecision {
        java.util.Objects.requireNonNull(tier, "tier");
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion is required");
        }
    }
}
