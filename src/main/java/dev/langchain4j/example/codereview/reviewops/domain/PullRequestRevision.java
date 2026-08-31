package dev.langchain4j.example.codereview.reviewops.domain;

public record PullRequestRevision(
        long installationId, long repositoryId, int pullRequestNumber, String headSha) {
    public PullRequestRevision {
        if (installationId <= 0 || repositoryId <= 0 || pullRequestNumber <= 0) {
            throw new IllegalArgumentException("GitHub identities must be positive");
        }
        if (headSha == null || headSha.isBlank()) {
            throw new IllegalArgumentException("headSha must not be blank");
        }
    }
}
