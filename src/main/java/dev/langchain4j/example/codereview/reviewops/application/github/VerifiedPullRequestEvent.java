package dev.langchain4j.example.codereview.reviewops.application.github;

import java.time.Instant;
import java.util.Objects;

public record VerifiedPullRequestEvent(
        String deliveryId,
        String action,
        long installationId,
        long repositoryId,
        String repositoryFullName,
        int pullRequestNumber,
        String headSha,
        String cloneUrl,
        Instant observedAt
) {
    public VerifiedPullRequestEvent {
        requireNonblank(deliveryId, "deliveryId");
        requireNonblank(action, "action");
        if (installationId <= 0 || repositoryId <= 0 || pullRequestNumber <= 0) {
            throw new IllegalArgumentException("GitHub identities must be positive");
        }
        requireNonblank(repositoryFullName, "repositoryFullName");
        requireNonblank(headSha, "headSha");
        requireNonblank(cloneUrl, "cloneUrl");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }

    private static void requireNonblank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
