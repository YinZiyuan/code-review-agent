package dev.langchain4j.example.codereview.reviewops.application.jobs;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A leased job whose {@code attemptCount} is the monotonic fencing number for this lease.
 */
public record LeasedJob(
        UUID id,
        String jobType,
        UUID payloadReference,
        int attemptCount,
        int deliveryAttempt,
        int maxAttempts,
        Instant leaseExpiresAt) {

    public LeasedJob(
            UUID id,
            String jobType,
            UUID payloadReference,
            int attemptCount,
            int maxAttempts,
            Instant leaseExpiresAt) {
        this(id, jobType, payloadReference, attemptCount, attemptCount, maxAttempts, leaseExpiresAt);
    }

    public LeasedJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(jobType, "jobType");
        if (jobType.isBlank()) {
            throw new IllegalArgumentException("jobType must not be blank");
        }
        Objects.requireNonNull(payloadReference, "payloadReference");
        if (attemptCount <= 0) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
        if (deliveryAttempt <= 0) {
            throw new IllegalArgumentException("deliveryAttempt must be positive");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (deliveryAttempt > maxAttempts) {
            throw new IllegalArgumentException("deliveryAttempt must not exceed maxAttempts");
        }
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
    }
}
