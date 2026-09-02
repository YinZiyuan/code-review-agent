package dev.langchain4j.example.codereview.analyzer;

import java.util.Objects;

public record CompilationResult(Status status, String safeReason) {

    public enum Status {
        COMPILED,
        NO_SOURCES,
        LIMIT_EXCEEDED,
        TIMED_OUT,
        CANCELLED,
        FAILED
    }

    public CompilationResult {
        status = Objects.requireNonNull(status, "status");
        if (safeReason == null || safeReason.isBlank()) {
            throw new IllegalArgumentException("safeReason must not be blank");
        }
    }

    public boolean compiled() {
        return status == Status.COMPILED;
    }
}
