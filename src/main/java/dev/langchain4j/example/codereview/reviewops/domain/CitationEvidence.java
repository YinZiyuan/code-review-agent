package dev.langchain4j.example.codereview.reviewops.domain;

public record CitationEvidence(String id, String source, String section) {
    public CitationEvidence {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("citation id is required");
    }
}
