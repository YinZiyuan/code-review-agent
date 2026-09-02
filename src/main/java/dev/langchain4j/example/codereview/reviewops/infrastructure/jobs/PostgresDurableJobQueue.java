package dev.langchain4j.example.codereview.reviewops.infrastructure.jobs;

import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobIntentConflictException;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ExpiredJobLeaseRecovery;
import dev.langchain4j.example.codereview.reviewops.application.jobs.FinalJobFailureSettlement;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class PostgresDurableJobQueue implements DurableJobQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresDurableJobQueue.class);
    private static final int DEFAULT_RECOVERY_LIMIT = 100;

    private static final String LEASE_DUE = """
            WITH due AS (
                SELECT id
                FROM durable_jobs
                WHERE state = 'READY' AND next_attempt_at <= CURRENT_TIMESTAMP
                  AND attempt_count < max_attempts
                ORDER BY next_attempt_at, created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE durable_jobs AS job
            SET state = 'LEASED', lease_owner = ?,
                lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                attempt_count = job.attempt_count + 1,
                lease_sequence = job.lease_sequence + 1,
                updated_at = CURRENT_TIMESTAMP
            FROM due
            WHERE job.id = due.id
            RETURNING job.*
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final ExpiredJobLeaseRecovery expiredLeaseRecovery;
    private final FinalJobFailureSettlement finalFailureSettlement;

    public PostgresDurableJobQueue(JdbcTemplate jdbcTemplate, TransactionOperations transactions, Clock clock) {
        this(jdbcTemplate, transactions, clock,
                (expiredLease, recoveredAt) -> ExpiredJobLeaseRecovery.RecoveryAction.UNHANDLED,
                (job, failureClass, safeCode, settledAt) ->
                        new FinalJobFailureSettlement.FinalFailureSettlement(
                                FailureDisposition.DEAD, List.of()));
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
        this.finalFailureSettlement = (job, failureClass, safeCode, settledAt) ->
                new FinalJobFailureSettlement.FinalFailureSettlement(
                        FailureDisposition.DEAD, List.of());
    }

    public PostgresDurableJobQueue(
            JdbcTemplate jdbcTemplate,
            TransactionOperations transactions,
            Clock clock,
            ExpiredJobLeaseRecovery expiredLeaseRecovery,
            FinalJobFailureSettlement finalFailureSettlement) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expiredLeaseRecovery = Objects.requireNonNull(
                expiredLeaseRecovery, "expiredLeaseRecovery");
        this.finalFailureSettlement = Objects.requireNonNull(
                finalFailureSettlement, "finalFailureSettlement");
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
        String validatedOwner = owner;
        return Objects.requireNonNull(transactions.execute(status -> jdbcTemplate.query(
                        LEASE_DUE,
                        PostgresDurableJobQueue::mapLeasedJob,
                        limit,
                        validatedOwner,
                        durationMillis(leaseDuration))),
                "transaction result");
    }

    @Override
    public void markSucceeded(UUID jobId, String owner, int expectedAttempt, Instant now) {
        Objects.requireNonNull(jobId, "jobId");
        owner = requireNonBlank(owner, "owner");
        Objects.requireNonNull(now, "now");
        int updated = jdbcTemplate.update("""
                        UPDATE durable_jobs
                        SET state = 'SUCCEEDED', lease_owner = NULL, lease_expires_at = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND state = 'LEASED' AND lease_owner = ?
                          AND lease_sequence = ? AND lease_expires_at > CURRENT_TIMESTAMP
                        """,
                jobId, owner, expectedAttempt);
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
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND state = 'LEASED' AND lease_owner = ?
                          AND lease_sequence = ? AND lease_expires_at > CURRENT_TIMESTAMP
                        """,
                failureClass.name(),
                timestamp(nextAttemptAt),
                failureClass.name(),
                jobId,
                owner,
                expectedAttempt);
        requireCurrentLease(updated, jobId, owner, expectedAttempt);
    }

    @Override
    public FailureDisposition settleFailure(
            LeasedJob job,
            String owner,
            FailureClass failureClass,
            String safeCode,
            Instant nextAttemptAt,
            Instant now) {
        Objects.requireNonNull(job, "job");
        owner = requireNonBlank(owner, "owner");
        Objects.requireNonNull(failureClass, "failureClass");
        if (safeCode == null || safeCode.isBlank()) {
            throw new IllegalArgumentException("safeCode must not be blank");
        }
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(now, "now");
        boolean finalDelivery = failureClass == FailureClass.TERMINAL
                || job.deliveryAttempt() >= job.maxAttempts();
        if (!finalDelivery) {
            recordFailure(
                    job.id(), owner, job.attemptCount(), failureClass, nextAttemptAt, now);
            return FailureDisposition.RETRY_SCHEDULED;
        }

        String validatedOwner = owner;
        return Objects.requireNonNull(transactions.execute(status -> {
            Instant databaseNow = databaseNow();
            lockCurrentLease(job, validatedOwner);
            FinalJobFailureSettlement.FinalFailureSettlement settlement =
                    Objects.requireNonNull(
                            finalFailureSettlement.settleFinalFailure(
                                    job, failureClass, safeCode, databaseNow),
                            "final failure settlement");
            if (settlement.disposition() == FailureDisposition.RETRY_SCHEDULED) {
                throw new IllegalStateException(
                        "final delivery settlement cannot schedule a charged retry");
            }
            settlement.followUpJobs().forEach(this::enqueue);
            int updated = jdbcTemplate.update("""
                            UPDATE durable_jobs
                            SET state = ?, next_attempt_at = ?, lease_owner = NULL,
                                lease_expires_at = NULL, last_failure_class = ?, updated_at = ?
                            WHERE id = ? AND state = 'LEASED' AND lease_owner = ?
                              AND lease_sequence = ? AND lease_expires_at > CURRENT_TIMESTAMP
                            """,
                    settlement.disposition() == FailureDisposition.SUCCEEDED
                            ? "SUCCEEDED" : "DEAD",
                    timestamp(nextAttemptAt),
                    failureClass.name(),
                    timestamp(databaseNow),
                    job.id(),
                    validatedOwner,
                    job.attemptCount());
            requireCurrentLease(updated, job.id(), validatedOwner, job.attemptCount());
            return settlement.disposition();
        }), "transaction result");
    }

    private void lockCurrentLease(LeasedJob job, String owner) {
        List<UUID> current = jdbcTemplate.query("""
                        SELECT id
                        FROM durable_jobs
                        WHERE id = ? AND state = 'LEASED' AND lease_owner = ?
                          AND lease_sequence = ? AND lease_expires_at > CURRENT_TIMESTAMP
                        FOR UPDATE
                        """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                job.id(), owner, job.attemptCount());
        requireCurrentLease(current.size(), job.id(), owner, job.attemptCount());
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
        int updated = jdbcTemplate.update("""
                        UPDATE durable_jobs
                        SET lease_expires_at = CURRENT_TIMESTAMP
                                + (? * INTERVAL '1 millisecond'),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND state = 'LEASED' AND lease_owner = ?
                          AND lease_sequence = ? AND lease_expires_at > CURRENT_TIMESTAMP
                        """,
                durationMillis(leaseDuration),
                jobId,
                owner,
                expectedAttempt);
        requireCurrentLease(updated, jobId, owner, expectedAttempt);
    }

    @Override
    public int recoverExpiredLeases(Instant now) {
        return recoverExpiredLeases(now, DEFAULT_RECOVERY_LIMIT);
    }

    @Override
    public int recoverExpiredLeases(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit <= 0) {
            throw new IllegalArgumentException("recovery limit must be positive");
        }
        int recovered = 0;
        Set<UUID> failedThisCycle = new LinkedHashSet<>();
        for (int attempted = 0; attempted < limit; attempted++) {
            AtomicReference<UUID> selectedId = new AtomicReference<>();
            try {
                Boolean recoveredOne = transactions.execute(status -> {
                    Instant databaseNow = databaseNow();
                    Optional<LeasedJob> selected = lockNextExpiredLease(
                            failedThisCycle);
                    if (selected.isEmpty()) {
                        return false;
                    }
                    LeasedJob expiredLease = selected.orElseThrow();
                    selectedId.set(expiredLease.id());
                    recoverLockedLease(expiredLease, databaseNow);
                    return true;
                });
                if (!Objects.requireNonNull(recoveredOne, "transaction result")) {
                    break;
                }
                recovered++;
            } catch (RuntimeException recoveryFailure) {
                UUID failedId = selectedId.get();
                if (failedId == null) {
                    throw recoveryFailure;
                }
                failedThisCycle.add(failedId);
                LOGGER.warn("Expired job lease recovery failed for job {}", failedId);
            }
        }
        return recovered;
    }

    private Optional<LeasedJob> lockNextExpiredLease(
            Set<UUID> excludedIds) {
        String exclusion = "";
        List<Object> arguments = new ArrayList<>();
        if (!excludedIds.isEmpty()) {
            exclusion = " AND id NOT IN ("
                    + String.join(", ", java.util.Collections.nCopies(
                            excludedIds.size(), "?"))
                    + ")";
            arguments.addAll(excludedIds);
        }
        List<LeasedJob> selected = jdbcTemplate.query("""
                        SELECT *
                        FROM durable_jobs
                        WHERE state = 'LEASED' AND lease_expires_at <= CURRENT_TIMESTAMP
                        """ + exclusion + """
                        ORDER BY lease_expires_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                        """,
                PostgresDurableJobQueue::mapLeasedJob,
                arguments.toArray());
        return selected.stream().findFirst();
    }

    private void recoverLockedLease(LeasedJob expiredLease, Instant recoveredAt) {
        int recovered;
        if (expiredLease.deliveryAttempt() >= expiredLease.maxAttempts()) {
            ExpiredJobLeaseRecovery.RecoverySettlement settlement = Objects.requireNonNull(
                    expiredLeaseRecovery.recoverWithIntents(expiredLease, recoveredAt),
                    "expired lease recovery settlement");
            settlement.followUpJobs().forEach(this::enqueue);
            recovered = settleExhaustedLease(expiredLease, settlement.action(), recoveredAt);
        } else {
            recovered = jdbcTemplate.update("""
                            UPDATE durable_jobs
                            SET state = 'READY',
                                lease_owner = NULL,
                                lease_expires_at = NULL,
                                updated_at = ?
                            WHERE id = ? AND state = 'LEASED' AND lease_sequence = ?
                              AND lease_expires_at <= ?
                            """,
                    timestamp(recoveredAt),
                    expiredLease.id(),
                    expiredLease.attemptCount(),
                    timestamp(recoveredAt));
        }
        if (recovered != 1) {
            throw new IllegalStateException(
                    "Expired lease could not be recovered for job " + expiredLease.id());
        }
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

    private Instant databaseNow() {
        Timestamp timestamp = jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return Objects.requireNonNull(timestamp, "database current timestamp").toInstant();
    }

    private static long durationMillis(Duration duration) {
        long milliseconds = duration.toMillis();
        if (milliseconds <= 0) {
            throw new IllegalArgumentException(
                    "leaseDuration must be representable as at least one millisecond");
        }
        return milliseconds;
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
