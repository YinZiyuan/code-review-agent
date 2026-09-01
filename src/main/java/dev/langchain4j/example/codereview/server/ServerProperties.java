package dev.langchain4j.example.codereview.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "code-review.server")
public record ServerProperties(GitHub github, Worker worker) {

    public record GitHub(
            long appId,
            String privateKey,
            String webhookSecret,
            int maxWebhookBytes
    ) {
        public GitHub {
            if (maxWebhookBytes <= 0) {
                throw new IllegalArgumentException("maxWebhookBytes must be positive");
            }
        }
    }

    public record Worker(Duration pollInterval, int batchSize) {
    }
}
