package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;
import java.util.Objects;

public record FeedbackAuditEntry(
        FeedbackState previous, FeedbackState current, Instant changedAt, Long reactionId) {
    public FeedbackAuditEntry {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(changedAt, "changedAt");
    }
}
