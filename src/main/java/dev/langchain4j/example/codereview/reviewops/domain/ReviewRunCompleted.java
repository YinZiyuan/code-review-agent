package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;
import java.util.Objects;

public record ReviewRunCompleted(ReviewRunId reviewRunId, Instant occurredAt) implements DomainEvent {
    public ReviewRunCompleted {
        Objects.requireNonNull(reviewRunId, "reviewRunId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
