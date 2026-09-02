package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
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

    /**
     * Settles one failed delivery and returns the durable state that was actually recorded.
     * Implementations backed by an aggregate store override this method to make final job and
     * aggregate settlement one transaction.
     */
    default FailureDisposition settleFailure(
            LeasedJob job,
            String owner,
            FailureClass failureClass,
            String safeCode,
            Instant nextAttemptAt,
            Instant now) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(failureClass, "failureClass");
        if (safeCode == null || safeCode.isBlank()) {
            throw new IllegalArgumentException("safeCode must not be blank");
        }
        recordFailure(
                job.id(), owner, job.attemptCount(), failureClass, nextAttemptAt, now);
        return failureClass == FailureClass.TERMINAL
                || job.deliveryAttempt() >= job.maxAttempts()
                ? FailureDisposition.DEAD
                : FailureDisposition.RETRY_SCHEDULED;
    }

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

    enum FailureDisposition {
        RETRY_SCHEDULED,
        DEAD,
        SUCCEEDED
    }
}
