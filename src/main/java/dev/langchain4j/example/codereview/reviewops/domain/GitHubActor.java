package dev.langchain4j.example.codereview.reviewops.domain;

public record GitHubActor(long id, String login) {
    public GitHubActor {
        if (id <= 0 || login == null || login.isBlank()) {
            throw new IllegalArgumentException("valid GitHub actor is required");
        }
    }
}
