package dev.langchain4j.example.codereview.reviewops.domain;

public record PublicationReference(String artifactType, String externalId) {
    public PublicationReference {
        if (artifactType == null || artifactType.isBlank() || externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("artifactType and externalId are required");
        }
    }
}
