package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Objects;
import java.util.UUID;

public record ReviewRunId(UUID value) {
    public ReviewRunId {
        Objects.requireNonNull(value, "value");
    }

    public static ReviewRunId newId() {
        return new ReviewRunId(UUID.randomUUID());
    }
}
