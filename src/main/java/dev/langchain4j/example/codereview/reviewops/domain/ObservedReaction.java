package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;
import java.util.Objects;

public record ObservedReaction(long reactionId, FeedbackState classification, Instant createdAt) {
    public ObservedReaction {
        if (reactionId <= 0) {
            throw new IllegalArgumentException("reactionId must be positive");
        }
        if (classification == FeedbackState.WITHDRAWN) {
            throw new IllegalArgumentException("absence, not a reaction, represents withdrawal");
        }
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
