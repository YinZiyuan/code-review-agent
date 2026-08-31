package dev.langchain4j.example.codereview.reviewops.domain;

public record FindingContent(FindingSeverity severity, FindingCategory category,
                             String title, String description, String suggestion) {
    public FindingContent {
        java.util.Objects.requireNonNull(severity, "severity");
        java.util.Objects.requireNonNull(category, "category");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        description = description == null ? "" : description;
        suggestion = suggestion == null ? "" : suggestion;
    }
}
