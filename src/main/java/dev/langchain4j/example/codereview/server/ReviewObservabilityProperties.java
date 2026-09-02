package dev.langchain4j.example.codereview.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "code-review.server.observability")
public record ReviewObservabilityProperties(
        Duration refreshInterval,
        Duration staleThreshold) {

    public ReviewObservabilityProperties {
        if (refreshInterval == null) {
            refreshInterval = Duration.ofSeconds(15);
        }
        if (staleThreshold == null) {
            staleThreshold = Duration.ofMinutes(15);
        }
        requirePositive(refreshInterval, "refreshInterval");
        requirePositive(staleThreshold, "staleThreshold");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
