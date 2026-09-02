package dev.langchain4j.example.codereview.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "code-review.server.maintenance")
public record ReviewOperationsMaintenanceProperties(
        Duration retentionAge,
        Integer batchSize,
        Duration interval) {

    public ReviewOperationsMaintenanceProperties {
        if (retentionAge == null) {
            retentionAge = Duration.ofDays(30);
        }
        if (batchSize == null) {
            batchSize = 500;
        }
        if (interval == null) {
            interval = Duration.ofHours(1);
        }
        requirePositive(retentionAge, "retentionAge");
        requirePositive(interval, "interval");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
