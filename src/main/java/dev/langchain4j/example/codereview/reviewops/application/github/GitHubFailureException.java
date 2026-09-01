package dev.langchain4j.example.codereview.reviewops.application.github;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class GitHubFailureException extends RuntimeException {

    public enum Classification {
        TRANSIENT,
        RATE_LIMITED,
        AUTHORIZATION,
        DETERMINISTIC_INPUT
    }

    private final Classification classification;
    private final Instant retryAt;

    public GitHubFailureException(
            Classification classification, String safeMessage, Instant retryAt) {
        super(requireSafeMessage(safeMessage));
        this.classification = Objects.requireNonNull(classification, "classification");
        this.retryAt = retryAt;
    }

    public GitHubFailureException(Classification classification, String safeMessage) {
        this(classification, safeMessage, null);
    }

    public Classification classification() {
        return classification;
    }

    public Optional<Instant> retryAt() {
        return Optional.ofNullable(retryAt);
    }

    private static String requireSafeMessage(String safeMessage) {
        if (safeMessage == null || safeMessage.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
        return safeMessage;
    }
}
