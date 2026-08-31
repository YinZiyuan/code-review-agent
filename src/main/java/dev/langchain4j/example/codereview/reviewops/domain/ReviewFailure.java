package dev.langchain4j.example.codereview.reviewops.domain;

public record ReviewFailure(String code, FailureClass classification, String safeMessage) {
    public ReviewFailure {
        if (code == null || code.isBlank() || safeMessage == null || safeMessage.isBlank()) {
            throw new IllegalArgumentException("failure code and safeMessage are required");
        }
        java.util.Objects.requireNonNull(classification, "classification");
    }
}
