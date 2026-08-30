package dev.langchain4j.example.codereview.reviewops.domain;

import java.util.Objects;

public record DeveloperVisibleFindingReference(
        ReviewRunId reviewRunId, FindingFingerprint findingFingerprint) {
    public DeveloperVisibleFindingReference {
        Objects.requireNonNull(reviewRunId, "reviewRunId");
        Objects.requireNonNull(findingFingerprint, "findingFingerprint");
    }
}
