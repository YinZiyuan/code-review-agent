package dev.langchain4j.example.codereview.reviewops.domain;

public record AuthoritativeRevision(String headSha) {
    public AuthoritativeRevision {
        if (headSha == null || headSha.isBlank()) {
            throw new IllegalArgumentException("headSha must not be blank");
        }
    }

    public boolean matches(PullRequestRevision revision) {
        return headSha.equals(revision.headSha());
    }
}
