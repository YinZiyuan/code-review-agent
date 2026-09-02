package dev.langchain4j.example.codereview.reviewops.application.github;

import java.time.Instant;
import java.util.Objects;

public interface GitHubInstallationGateway {

    InstallationToken token(long installationId);

    record InstallationToken(String value, Instant expiresAt) {
        public InstallationToken {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("installation token must not be blank");
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
        }

        @Override
        public String toString() {
            return "InstallationToken[REDACTED, expiresAt=" + expiresAt + "]";
        }
    }
}
