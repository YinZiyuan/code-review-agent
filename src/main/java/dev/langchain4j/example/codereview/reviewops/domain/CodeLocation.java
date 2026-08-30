package dev.langchain4j.example.codereview.reviewops.domain;

public record CodeLocation(String file, int line, boolean changedLine) {
    public CodeLocation {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("file is required");
        if (line < 1) throw new IllegalArgumentException("line must be positive");
    }
}
