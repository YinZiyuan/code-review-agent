package dev.langchain4j.example.codereview.reviewops.infrastructure.jobs;

import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.infrastructure.persistence.PostgresIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresDurableJobQueueTest extends PostgresIntegrationSupport {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T02:00:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-08-31T02:05:00Z");
    private static final Instant LEASED_AT = Instant.parse("2026-08-31T02:10:00Z");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);

    private JdbcTemplate jdbcTemplate;
    private PostgresDurableJobQueue queue;

    @BeforeEach
    void setUpQueue() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        queue = queueUsing(dataSource, Clock.fixed(CREATED_AT, ZoneOffset.UTC));
        jdbcTemplate.execute("TRUNCATE TABLE durable_jobs");
    }

    @Test
    void duplicateIdempotencyKeyReturnsTheOriginalJobWithoutReplacingItsIntent() {
        DurableJobRequest original = request(
                "REVIEW_EXECUTION",
                "delivery-duplicate",
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                3,
                DUE_AT);
        DurableJobRequest duplicate = request(
                "SUPERSEDE_REVIEW",
                "delivery-duplicate",
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                7,
                DUE_AT.plusSeconds(60));

        UUID originalId = queue.enqueue(original);
        UUID duplicateId = queue.enqueue(duplicate);

        assertThat(duplicateId).isEqualTo(originalId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM durable_jobs", Integer.class)).isEqualTo(1);
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT job_type, payload_reference, max_attempts, next_attempt_at FROM durable_jobs WHERE id = ?",
                originalId);
        assertThat(stored)
                .containsEntry("job_type", "REVIEW_EXECUTION")
                .containsEntry("payload_reference", original.payloadReference())
                .containsEntry("max_attempts", 3);
        assertThat(((Timestamp) stored.get("next_attempt_at")).toInstant()).isEqualTo(DUE_AT);
    }

    @Test
    void doesNotLeaseJobsScheduledAfterThePollingTime() {
        UUID futureJob = queue.enqueue(request(
                "REVIEW_EXECUTION",
                "future-job",
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                3,
                LEASED_AT.plusSeconds(1)));

        assertThat(queue.leaseDue("worker-a", LEASED_AT, LEASE_DURATION, 10)).isEmpty();
        assertThat(jobRow(futureJob))
                .containsEntry("state", "READY")
                .containsEntry("attempt_count", 0);
    }

    @Test
    void leasingIncrementsAttemptCountAndReturnsTheExecutionIntent() {
        UUID payloadReference = UUID.fromString("00000000-0000-0000-0000-000000000004");
        UUID jobId = queue.enqueue(request(
                "REVIEW_EXECUTION", "due-job", payloadReference, 3, DUE_AT));

        List<LeasedJob> leased = queue.leaseDue("worker-a", LEASED_AT, LEASE_DURATION, 1);

        assertThat(leased).containsExactly(new LeasedJob(
                jobId, "REVIEW_EXECUTION", payloadReference, 1, 3, LEASED_AT.plus(LEASE_DURATION)));
        assertThat(jobRow(jobId))
                .containsEntry("state", "LEASED")
                .containsEntry("attempt_count", 1)
                .containsEntry("lease_owner", "worker-a");
        assertThat(((Timestamp) jobRow(jobId).get("lease_expires_at")).toInstant())
                .isEqualTo(LEASED_AT.plus(LEASE_DURATION));
    }

    @Test
    void twoWorkersUsingIndependentConnectionsLeaseDisjointJobs() throws Exception {
        for (int index = 0; index < 6; index++) {
            queue.enqueue(request(
                    "REVIEW_EXECUTION",
                    "concurrent-" + index,
                    UUID.fromString("00000000-0000-0000-0000-%012d".formatted(index + 10)),
                    3,
                    DUE_AT));
        }

        try (Connection firstConnection = dataSource.getConnection();
             Connection secondConnection = dataSource.getConnection()) {
            PostgresDurableJobQueue firstWorker = queueUsing(
                    new SingleConnectionDataSource(firstConnection, true),
                    Clock.fixed(CREATED_AT, ZoneOffset.UTC));
            PostgresDurableJobQueue secondWorker = queueUsing(
                    new SingleConnectionDataSource(secondConnection, true),
                    Clock.fixed(CREATED_AT, ZoneOffset.UTC));
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<List<LeasedJob>> firstLease = executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return firstWorker.leaseDue("worker-a", LEASED_AT, LEASE_DURATION, 4);
                });
                Future<List<LeasedJob>> secondLease = executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return secondWorker.leaseDue("worker-b", LEASED_AT, LEASE_DURATION, 4);
                });
                start.countDown();

                Set<UUID> firstIds = ids(firstLease.get(10, TimeUnit.SECONDS));
                Set<UUID> secondIds = ids(secondLease.get(10, TimeUnit.SECONDS));

                assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
                Set<UUID> allIds = new HashSet<>(firstIds);
                allIds.addAll(secondIds);
                assertThat(allIds).hasSize(6);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void leasingSkipsARowLockedByAnotherConnectionWithoutWaitingForItsTransaction() throws Exception {
        queue.enqueue(request(
                "REVIEW_EXECUTION",
                "locked-first",
                UUID.fromString("00000000-0000-0000-0000-000000000016"),
                3,
                DUE_AT));
        queue.enqueue(request(
                "REVIEW_EXECUTION",
                "available-second",
                UUID.fromString("00000000-0000-0000-0000-000000000017"),
                3,
                DUE_AT));
        UUID firstDueId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM durable_jobs
                ORDER BY next_attempt_at, created_at, id
                LIMIT 1
                """, UUID.class);

        try (Connection lockingConnection = dataSource.getConnection();
             Connection workerConnection = dataSource.getConnection()) {
            lockingConnection.setAutoCommit(false);
            try (PreparedStatement lock = lockingConnection.prepareStatement(
                    "SELECT id FROM durable_jobs WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, firstDueId);
                assertThat(lock.executeQuery().next()).isTrue();
            }
            PostgresDurableJobQueue independentWorker = queueUsing(
                    new SingleConnectionDataSource(workerConnection, true),
                    Clock.fixed(CREATED_AT, ZoneOffset.UTC));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<List<LeasedJob>> lease = executor.submit(() -> independentWorker.leaseDue(
                        "worker-b", LEASED_AT, LEASE_DURATION, 1));

                assertThat(lease.get(2, TimeUnit.SECONDS))
                        .singleElement()
                        .extracting(LeasedJob::id)
                        .isNotEqualTo(firstDueId);
            } finally {
                lockingConnection.rollback();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void completionAndFailureRejectAWorkerThatDoesNotOwnTheLease() {
        UUID jobId = queue.enqueue(request(
                "REVIEW_EXECUTION",
                "owner-check",
                UUID.fromString("00000000-0000-0000-0000-000000000020"),
                3,
                DUE_AT));
        queue.leaseDue("worker-a", LEASED_AT, LEASE_DURATION, 1);

        assertThatThrownBy(() -> queue.markSucceeded(jobId, "worker-b", LEASED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(jobId.toString());
        assertThatThrownBy(() -> queue.recordFailure(
                jobId,
                "worker-b",
                FailureClass.TRANSIENT,
                LEASED_AT.plusSeconds(30),
                LEASED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(jobId.toString());
        assertThat(jobRow(jobId))
                .containsEntry("state", "LEASED")
                .containsEntry("lease_owner", "worker-a");

        queue.markSucceeded(jobId, "worker-a", LEASED_AT.plusSeconds(2));

        assertThat(jobRow(jobId))
                .containsEntry("state", "SUCCEEDED")
                .containsEntry("lease_owner", null)
                .containsEntry("lease_expires_at", null);
    }

    @Test
    void transientFailureRetriesBeforeTheAttemptBoundAndDiesAtTheBound() {
        UUID jobId = queue.enqueue(request(
                "REVIEW_EXECUTION",
                "bounded-retry",
                UUID.fromString("00000000-0000-0000-0000-000000000021"),
                2,
                DUE_AT));
        queue.leaseDue("worker-a", LEASED_AT, LEASE_DURATION, 1);
        Instant retryAt = LEASED_AT.plusSeconds(30);

        queue.recordFailure(jobId, "worker-a", FailureClass.TRANSIENT, retryAt, LEASED_AT.plusSeconds(1));

        assertThat(jobRow(jobId))
                .containsEntry("state", "READY")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_failure_class", "TRANSIENT")
                .containsEntry("lease_owner", null)
                .containsEntry("lease_expires_at", null);
        assertThat(((Timestamp) jobRow(jobId).get("next_attempt_at")).toInstant()).isEqualTo(retryAt);

        queue.leaseDue("worker-b", retryAt, LEASE_DURATION, 1);
        queue.recordFailure(jobId, "worker-b", FailureClass.TRANSIENT,
                retryAt.plusSeconds(30), retryAt.plusSeconds(1));

        assertThat(jobRow(jobId))
                .containsEntry("state", "DEAD")
                .containsEntry("attempt_count", 2)
                .containsEntry("last_failure_class", "TRANSIENT")
                .containsEntry("lease_owner", null)
                .containsEntry("lease_expires_at", null);
    }

    @Test
    void terminalFailureDiesImmediatelyWithoutConsumingTheRemainingAttemptBudget() {
        UUID jobId = queue.enqueue(request(
                "REVIEW_EXECUTION",
                "terminal-failure",
                UUID.fromString("00000000-0000-0000-0000-000000000022"),
                5,
                DUE_AT));
        queue.leaseDue("worker-a", LEASED_AT, LEASE_DURATION, 1);

        queue.recordFailure(jobId, "worker-a", FailureClass.TERMINAL,
                LEASED_AT.plusSeconds(30), LEASED_AT.plusSeconds(1));

        assertThat(jobRow(jobId))
                .containsEntry("state", "DEAD")
                .containsEntry("attempt_count", 1)
                .containsEntry("last_failure_class", "TERMINAL")
                .containsEntry("lease_owner", null)
                .containsEntry("lease_expires_at", null);
    }

    @Test
    void recoversLeasesThatExpireExactlyAtTheRecoveryTimeAndClearsLeaseFacts() {
        UUID jobId = queue.enqueue(request(
                "REVIEW_EXECUTION",
                "expired-lease",
                UUID.fromString("00000000-0000-0000-0000-000000000023"),
                3,
                DUE_AT));
        queue.leaseDue("worker-a", LEASED_AT, LEASE_DURATION, 1);

        int recovered = queue.recoverExpiredLeases(LEASED_AT.plus(LEASE_DURATION));

        assertThat(recovered).isEqualTo(1);
        assertThat(jobRow(jobId))
                .containsEntry("state", "READY")
                .containsEntry("attempt_count", 1)
                .containsEntry("lease_owner", null)
                .containsEntry("lease_expires_at", null);
        assertThat(queue.recoverExpiredLeases(LEASED_AT.plus(LEASE_DURATION))).isZero();
    }

    @Test
    void doesNotRecoverALeaseWhoseExpiryIsAfterTheRecoveryTime() {
        UUID expiredJobId = queue.enqueue(request(
                "REVIEW_EXECUTION",
                "expired-boundary",
                UUID.fromString("00000000-0000-0000-0000-000000000025"),
                3,
                DUE_AT));
        UUID unexpiredJobId = queue.enqueue(request(
                "REVIEW_EXECUTION",
                "unexpired-boundary",
                UUID.fromString("00000000-0000-0000-0000-000000000026"),
                3,
                DUE_AT));
        queue.leaseDue("worker-a", LEASED_AT, LEASE_DURATION, 1);
        queue.leaseDue("worker-b", LEASED_AT, LEASE_DURATION.plusMinutes(1), 1);

        int recovered = queue.recoverExpiredLeases(LEASED_AT.plus(LEASE_DURATION));

        assertThat(recovered).isEqualTo(1);
        assertThat(List.of(jobRow(expiredJobId).get("state"), jobRow(unexpiredJobId).get("state")))
                .containsExactlyInAnyOrder("READY", "LEASED");
    }

    @Test
    void durableJobRequestRejectsNonInternalOrInvalidIntentFields() {
        UUID payloadReference = UUID.fromString("00000000-0000-0000-0000-000000000024");

        assertThatThrownBy(() -> request(" ", "key", payloadReference, 3, DUE_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("TYPE", " ", payloadReference, 3, DUE_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("TYPE", "key", null, 3, DUE_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> request("TYPE", "key", payloadReference, 0, DUE_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leasingRejectsBlankOwnersAndNonPositiveBounds() {
        assertThatThrownBy(() -> queue.leaseDue(" ", LEASED_AT, LEASE_DURATION, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.leaseDue("worker-a", LEASED_AT, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queue.leaseDue("worker-a", LEASED_AT, LEASE_DURATION, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DurableJobRequest request(String jobType, String idempotencyKey, UUID payloadReference,
                                      int maxAttempts, Instant nextAttemptAt) {
        return new DurableJobRequest(jobType, payloadReference, maxAttempts, nextAttemptAt, idempotencyKey);
    }

    private PostgresDurableJobQueue queueUsing(javax.sql.DataSource queueDataSource, Clock clock) {
        JdbcTemplate template = new JdbcTemplate(queueDataSource);
        return new PostgresDurableJobQueue(
                template,
                new TransactionTemplate(new DataSourceTransactionManager(queueDataSource)),
                clock);
    }

    private Map<String, Object> jobRow(UUID jobId) {
        return jdbcTemplate.queryForMap("""
                SELECT state, attempt_count, lease_owner, lease_expires_at,
                       last_failure_class, next_attempt_at, updated_at
                FROM durable_jobs
                WHERE id = ?
                """, jobId);
    }

    private Set<UUID> ids(List<LeasedJob> jobs) {
        Set<UUID> ids = new HashSet<>();
        jobs.forEach(job -> ids.add(job.id()));
        return ids;
    }
}
