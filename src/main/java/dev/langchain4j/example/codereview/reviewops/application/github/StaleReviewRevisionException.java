package dev.langchain4j.example.codereview.reviewops.application.github;

import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;

import java.util.Objects;

/** Exact-head preparation discovered that the requested revision is no longer authoritative. */
public final class StaleReviewRevisionException extends RuntimeException {

    private final AuthoritativeRevision authoritativeRevision;

    public StaleReviewRevisionException(AuthoritativeRevision authoritativeRevision) {
        super("review revision is no longer authoritative");
        this.authoritativeRevision = Objects.requireNonNull(
                authoritativeRevision, "authoritativeRevision");
    }

    public AuthoritativeRevision authoritativeRevision() {
        return authoritativeRevision;
    }
}
