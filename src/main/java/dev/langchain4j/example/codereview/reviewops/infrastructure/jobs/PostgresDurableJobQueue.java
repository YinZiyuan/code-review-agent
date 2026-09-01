package dev.langchain4j.example.codereview.reviewops.infrastructure.jobs;

import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobIntentConflictException;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ExpiredJobLeaseRecovery;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PostgresDurableJobQueue implements DurableJobQueue {

    private static final String LEASE_DUE = """
            WITH due AS (
                SELECT id
                FROM durable_jobs
                WHERE state = 'READY' AND next_attempt_at <= ?
                  AND attempt_count < max_attempts
                ORDER BY next_attempt_at, created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE durable_jobs AS job
            SET state = 'LEASED', lease_owner = ?, lease_expires_at = ?,
                attempt_count = job.attempt_count + 1,
                lease_sequence = job.lease_sequence + 1,
                updated_at = ?
            FROM due
            WHERE job.id = due.id
            RETURNING job.*
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final ExpiredJobLeaseRecovery expiredLeaseRecovery;

    public PostgresDurableJobQueue(JdbcTemplate jdbcTemplate, TransactionOperations transactions, Clock clock) {
        this(jdbcTemplate, transactions, clock,
                (expiredLease, recoveredAt) -> ExpiredJobLeaseRecovery.RecoveryAction.UNHANDLED);
    }

    public PostgresDurableJobQueue(
            JdbcTemplate jdbcTemplate,
            TransactionOperations transactions,
            Clock clock,
            ExpiredJobLeaseRecovery expiredLeaseRecovery) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expiredLeaseRecovery = Objects.requireNonNull(expiredLeaseRecovery, "expiredLeaseRecovery");
    }

    @Override
    public UUID enqueue(DurableJobRequest request) {
        Objects.requireNonNull(request, "request");
        UUID proposedId = UUID.randomUUID();
        Instant createdAt = clock.instant();
        List<UUID> ids = jdbcTemplate.query("""
                        INSERT INTO durable_jobs (
                            id, job_type, payload_reference, state, attempt_count, max_attempts,
                            next_attempt_at, initial_next_attempt_at,
                            lease_owner, lease_expires_at, last_failure_class,
                            idempotency_key, created_at, updated_at)
                        VALUES (?, ?, ?, 'READY', 0, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?)
                        ON CONFLICT (idempotency_key)
                        DO UPDATE SET idempotency_key = EXCLUDED.idempotency_key
                        WHERE durable_jobs.job_type = EXCLUDED.job_type
                          AND durable_jobs.payload_reference = EXCLUDED.payload_reference
                          AND durable_jobs.max_attempts = EXCLUDED.max_attempts
                          AND durable_jobs.initial_next_attempt_at = EXCLUDED.initial_next_attempt_at
                        RETURNING id
                        """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                proposedId,
                request.jobType(),
                request.payloadReference(),
                request.maxAttempts(),
                timestamp(request.nextAttemptAt()),
                timestamp(request.nextAttemptAt()),
                request.idempotencyKey(),
                timestamp(createdAt),
                timestamp(createdAt));
        if (ids.isEmpty()) {
            throw new DurableJobIntentConflictException(request.idempotencyKey());
        }
        return ids.get(0);
    }

    @Override
    public List<LeasedJob> leaseDue(String owner, Instant now, Duration leaseDuration, int limit) {
        owner = requireNonBlank(owner, "owner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Instant leaseExpiresAt = now.plus(leaseDuration);
        String validatedOwner = owner;
        return Objects.requireNonNull(transactions.execute(status -> jdbcTemplate.query(
                        LEASE_DUE,
                        PostgresDurableJobQueue::mapLeasedJob,
                        timestamp(now),
                        limit,
                        validatedOwner,
                        timestamp(leaseExpiresAt),
                        timestamp(now))),
                "transaction result");
    }

    @Override
    public void markSucceeded(UUID jobId, String owner, int expectedAttempt, Instant now) {
        Objects.requireNonNull(jobId, "jobId");
        owner = requireNonBlank(owner, "owner");
        Objects.requireNonNull(now, "now");
        int updated = jdbcTemplate.update("""
                        UPDATE durable_jobs
                        SET state = 'SUCCEEDED', lease_owner = NULL, lease_expires_at = NULL, updated_at = ?
                        WHERE id = ? AND state = 'LEASED' AND lease_owner = ?
                          AND lease_sequence = ? AND lease_expires_at > ?
                        """,
                timestamp(now), jobId, owner, expectedAttempt, timestamp(now));
        requireCurrentLease(updated, jobId, owner, expectedAttempt);
    }

    @Override
    public void recordFailure(UUID jobId, String owner, int expectedAttempt, FailureClass failureClass,
                              Instant nextAttemptAt, Instant now) {
        Objects.requireNonNull(jobId, "jobId");
        owner = requireNonBlank(owner, "owner");
        Objects.requireNonNull(failureClass, "failureClass");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(now, "now");
        int updated = jdbcTemplate.update("""
                        UPDATE durable_jobs
                        SET state = CASE
                                WHEN ? = 'TERMINAL' OR attempt_count >= max_attempts THEN 'DEAD'
                                ELSE 'READY'
                            END,
                            next_attempt_at = ?,
                            lease_owner = NULL,
                            lease_expires_at = NULL,
                            last_failure_class = ?,
                            updated_at = ?
                        WHERE id = ? AND state = 'LEASED' AND lease_owner = ?
                          AND lease_sequence = ? AND lease_expires_at > ?
                        """,
                failureClass.name(),
                timestamp(nextAttemptAt),
                failureClass.name(),
                timestamp(now),
                jobId,
                owner,
                expectedAttempt,
                timestamp(now));
        requireCurrentLease(updated, jobId, owner, expectedAttempt);
    }

    @Override
    public void renewLease(
            UUID jobId,
            String owner,
            int expectedAttempt,
            Instant now,
            Duration leaseDuration) {
        Objects.requireNonNull(jobId, "jobId");
        owner = requireNonBlank(owner, "owner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Instant proposedExpiry = now.plus(leaseDuration);
        int updated = jdbcTemplate.update("""
                        UPDATE durable_jobs
                        SET lease_expires_at = GREATEST(lease_expires_at, ?), updated_at = ?
                        WHERE id = ? AND state = 'LEASED' AND lease_owner = ?
                          AND lease_sequence = ? AND lease_expires_at > ?
                        """,
                timestamp(proposedExpiry),
                timestamp(now),
                jobId,
                owner,
                expectedAttempt,
                timestamp(now));
        requireCurrentLease(updated, jobId, owner, expectedAttempt);
    }

    @Override
    public int recoverExpiredLeases(Instant now) {
        Objects.requireNonNull(now, "now");
        Integer recovered = transactions.execute(status -> {
            List<LeasedJob> exhausted = jdbcTemplate.query("""
                            SELECT *
                            FROM durable_jobs
                            WHERE state = 'LEASED' AND lease_expires_at <= ?
                              AND attempt_count >= max_attempts
                            ORDER BY lease_expires_at, id
                            FOR UPDATE
                            """,
                    PostgresDurableJobQueue::mapLeasedJob,
                    timestamp(now));
            int recoveredExhausted = 0;
            for (LeasedJob expiredLease : exhausted) {
                ExpiredJobLeaseRecovery.RecoveryAction action = Objects.requireNonNull(
                        expiredLeaseRecovery.recover(expiredLease, now),
                        "expired lease recovery action");
                recoveredExhausted += settleExhaustedLease(expiredLease, action, now);
            }
            int recoveredRetryable = jdbcTemplate.update("""
                            UPDATE durable_jobs
                            SET state = 'READY',
                                lease_owner = NULL,
                                lease_expires_at = NULL,
                                updated_at = ?
                            WHERE state = 'LEASED' AND lease_expires_at <= ?
                              AND attempt_count < max_attempts
                            """,
                    timestamp(now), timestamp(now));
            return recoveredExhausted + recoveredRetryable;
        });
        return Objects.requireNonNull(recovered, "transaction result");
    }

    private int settleExhaustedLease(
            LeasedJob expiredLease,
            ExpiredJobLeaseRecovery.RecoveryAction action,
            Instant recoveredAt) {
        return jdbcTemplate.update("""
                        UPDATE durable_jobs
                        SET state = ?,
                            attempt_count = attempt_count + ?,
                            lease_owner = NULL,
                            lease_expires_at = NULL,
                            updated_at = ?
                        WHERE id = ? AND state = 'LEASED' AND lease_sequence = ?
                          AND lease_expires_at <= ?
                        """,
                switch (action) {
                    case RETRY_WITHOUT_CHARGE -> "READY";
                    case SUCCEEDED -> "SUCCEEDED";
                    case UNHANDLED -> "DEAD";
                },
                action == ExpiredJobLeaseRecovery.RecoveryAction.RETRY_WITHOUT_CHARGE ? -1 : 0,
                timestamp(recoveredAt),
                expiredLease.id(),
                expiredLease.attemptCount(),
                timestamp(recoveredAt));
    }

    private static LeasedJob mapLeasedJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LeasedJob(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("job_type"),
                resultSet.getObject("payload_reference", UUID.class),
                resultSet.getInt("lease_sequence"),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("max_attempts"),
                resultSet.getTimestamp("lease_expires_at").toInstant());
    }

    private static void requireCurrentLease(int updated, UUID jobId, String owner, int expectedAttempt) {
        if (updated == 0) {
            throw new IllegalStateException(
                    "Job " + jobId + " has no current lease for owner " + owner
                            + " at attempt " + expectedAttempt);
        }
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
