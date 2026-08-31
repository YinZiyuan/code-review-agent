package dev.langchain4j.example.codereview.reviewops.domain;

public record FindingEvidence(String evidence, java.util.List<CitationEvidence> citations, String source) {
    public FindingEvidence {
        evidence = evidence == null ? "" : evidence;
        citations = citations == null ? java.util.List.of() : java.util.List.copyOf(citations);
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
    }
}
