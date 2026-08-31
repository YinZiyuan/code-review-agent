package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Objects;

public record FindingFeedbackId(
        ReviewRunId reviewRunId, FindingFingerprint findingFingerprint, long actorId) {
    public FindingFeedbackId {
        Objects.requireNonNull(reviewRunId, "reviewRunId");
        Objects.requireNonNull(findingFingerprint, "findingFingerprint");
        if (actorId <= 0) {
            throw new IllegalArgumentException("actorId must be positive");
        }
    }
}
