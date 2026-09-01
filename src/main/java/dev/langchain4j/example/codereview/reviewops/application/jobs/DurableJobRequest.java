package dev.langchain4j.example.codereview.reviewops.application.jobs;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DurableJobRequest(
        String jobType,
        UUID payloadReference,
        int maxAttempts,
        Instant nextAttemptAt,
        String idempotencyKey) {

    public DurableJobRequest {
        jobType = requireNonBlank(jobType, "jobType");
        Objects.requireNonNull(payloadReference, "payloadReference");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        idempotencyKey = requireNonBlank(idempotencyKey, "idempotencyKey");
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
