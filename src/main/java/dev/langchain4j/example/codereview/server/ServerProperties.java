package dev.langchain4j.example.codereview.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "code-review.server")
public record ServerProperties(GitHub github, Worker worker) {

    public record GitHub(
            long appId,
            String privateKey,
            String webhookSecret,
            Integer maxWebhookBytes
    ) {
        public GitHub {
            if (maxWebhookBytes == null) {
                maxWebhookBytes = 1_048_576;
            }
            if (maxWebhookBytes <= 0) {
                throw new IllegalArgumentException("maxWebhookBytes must be positive");
            }
        }
    }

    public record Worker(
            Duration pollInterval,
            Integer batchSize,
            Duration leaseDuration,
            Duration heartbeatInterval,
            Duration initialBackoff,
            Duration maxBackoff,
            Double jitterRatio) {

        public Worker {
            if (pollInterval == null) {
                pollInterval = Duration.ofSeconds(1);
            }
            if (batchSize == null) {
                batchSize = 10;
            }
            if (leaseDuration == null) {
                leaseDuration = Duration.ofMinutes(3);
            }
            if (heartbeatInterval == null) {
                heartbeatInterval = Duration.ofSeconds(30);
            }
            if (initialBackoff == null) {
                initialBackoff = Duration.ofSeconds(10);
            }
            if (maxBackoff == null) {
                maxBackoff = Duration.ofMinutes(5);
            }
            if (jitterRatio == null) {
                jitterRatio = 0.20;
            }
            requirePositive(pollInterval, "pollInterval");
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize must be positive");
            }
            requirePositive(leaseDuration, "leaseDuration");
            requirePositive(heartbeatInterval, "heartbeatInterval");
            if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
                throw new IllegalArgumentException("heartbeatInterval must be less than leaseDuration");
            }
            requirePositive(initialBackoff, "initialBackoff");
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("maxBackoff must not be less than initialBackoff");
            }
            if (!Double.isFinite(jitterRatio) || jitterRatio < 0.0 || jitterRatio > 1.0) {
                throw new IllegalArgumentException("jitterRatio must be between zero and one");
            }
        }

        private static void requirePositive(Duration duration, String name) {
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
