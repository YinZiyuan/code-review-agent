package dev.langchain4j.example.codereview.reviewops.domain;

public record CodeLocation(String file, int line, boolean changedLine) {
    public CodeLocation {
        if (file == null) throw new IllegalArgumentException("file is required");
        file = normalizePath(file);
        if (file.isBlank()) throw new IllegalArgumentException("file is required");
        if (line < 1) throw new IllegalArgumentException("line must be positive");
    }

    private static String normalizePath(String value) {
        String normalized = value.replace('\\', '/').replaceAll("/+", "/");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
