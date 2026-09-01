package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DurableJobQueue {

    /**
     * Enqueues one immutable job intent.
     *
     * <p>Reusing an idempotency key returns the original job only when {@code jobType},
     * {@code payloadReference}, {@code maxAttempts}, and the PostgreSQL-persisted initial
     * {@code nextAttemptAt} are equal. A different value in any of those fields raises
     * {@link DurableJobIntentConflictException}. Queue state, attempt count, lease facts, failure
     * facts, and audit timestamps are mutable lifecycle state and are not part of the intent.</p>
     */
    UUID enqueue(DurableJobRequest request);

    List<LeasedJob> leaseDue(String owner, Instant now, Duration leaseDuration, int limit);

    void markSucceeded(UUID jobId, String owner, int expectedAttempt, Instant now);

    void recordFailure(UUID jobId, String owner, int expectedAttempt, FailureClass failureClass,
                       Instant nextAttemptAt, Instant now);

    default void renewLease(
            UUID jobId,
            String owner,
            int expectedAttempt,
            Instant now,
            Duration leaseDuration) {
        throw new UnsupportedOperationException("lease renewal is not supported");
    }

    int recoverExpiredLeases(Instant now);

    default int recoverExpiredLeases(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("recovery limit must be positive");
        }
        return recoverExpiredLeases(now);
    }
}
