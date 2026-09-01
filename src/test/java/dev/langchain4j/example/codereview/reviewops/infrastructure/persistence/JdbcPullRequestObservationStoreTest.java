package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationRequest;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationResult;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunAdmissionStore;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.infrastructure.jobs.PostgresDurableJobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationStatus.ADMITTED;
import static dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationStatus.DUPLICATE_DELIVERY;
import static dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationStatus.EXISTING_REVISION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcPullRequestObservationStoreTest extends PostgresIntegrationSupport {

    private static final Instant RECEIVED_AT = Instant.parse("2026-09-01T04:05:06Z");
    private static final String PAYLOAD_SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final UUID ORIGINAL_RUN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID REPLAY_RUN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final ReviewConfigurationSnapshot CONFIGURATION =
            new ReviewConfigurationSnapshot(
                    "pipeline-v3", "configuration-v7", "kimi-k2", "policy-v5", 3);

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactions;
    private JdbcReviewRunRepository reviewRuns;
    private PostgresDurableJobQueue jobs;

    @BeforeEach
    void setUpAdapters() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_delivery_handled ON github_deliveries");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_delivery_handled()");
        jdbcTemplate.execute(
                "TRUNCATE TABLE github_deliveries, outbox_events, durable_jobs, review_runs CASCADE");
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        reviewRuns = new JdbcReviewRunRepository(
                jdbcTemplate, transactions, new JsonColumnCodec(new ObjectMapper()));
        jobs = new PostgresDurableJobQueue(
                jdbcTemplate, transactions, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
    }

    @Test
    void admitsOnceAndReturnsTheAuthoritativeRunForDeliveryReplay() {
        PullRequestObservationStore store = store(admission(jobs));

        ObservationResult admitted = store.admit(request("delivery-123", ORIGINAL_RUN_ID));
        ObservationResult replay = store.admit(request("delivery-123", REPLAY_RUN_ID));

        assertThat(admitted).isEqualTo(new ObservationResult(ADMITTED, runId(ORIGINAL_RUN_ID)));
        assertThat(replay)
                .isEqualTo(new ObservationResult(DUPLICATE_DELIVERY, runId(ORIGINAL_RUN_ID)));
        assertCounts(1, 1, 1);
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT event_name, payload_sha256, received_at, handled_at
                        FROM github_deliveries
                        WHERE delivery_id = 'delivery-123'
                        """))
                .containsEntry("event_name", "pull_request")
                .containsEntry("payload_sha256", PAYLOAD_SHA256)
                .containsEntry("received_at", java.sql.Timestamp.from(RECEIVED_AT));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT handled_at IS NOT NULL FROM github_deliveries WHERE delivery_id = ?",
                Boolean.class,
                "delivery-123")).isTrue();
        assertExecutionJob(ORIGINAL_RUN_ID);
    }

    @Test
    void recordsADifferentDeliveryButReusesAnExistingBusinessRevision() {
        PullRequestObservationStore store = store(admission(jobs));
        store.admit(request("delivery-123", ORIGINAL_RUN_ID));

        ObservationResult result = store.admit(request("delivery-456", REPLAY_RUN_ID));

        assertThat(result)
                .isEqualTo(new ObservationResult(EXISTING_REVISION, runId(ORIGINAL_RUN_ID)));
        assertCounts(2, 1, 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM github_deliveries WHERE handled_at IS NOT NULL",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void rejectsAReusedDeliveryIdWithDifferentPayloadFacts() {
        PullRequestObservationStore store = store(admission(jobs));
        store.admit(request("delivery-123", ORIGINAL_RUN_ID));
        ObservationRequest conflictingReplay = request(
                "delivery-123", REPLAY_RUN_ID, "f".repeat(64));

        assertThatThrownBy(() -> store.admit(conflictingReplay))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recorded delivery conflicts with supplied event facts");

        assertCounts(1, 1, 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload_sha256 FROM github_deliveries WHERE delivery_id = ?",
                String.class,
                "delivery-123")).isEqualTo(PAYLOAD_SHA256);
    }

    @Test
    void serializesConcurrentDeliveriesForTheSameBusinessRevision() throws Exception {
        PullRequestObservationStore store = store(admission(jobs));
        CyclicBarrier simultaneousAdmission = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ObservationResult> first = executor.submit(() -> {
                simultaneousAdmission.await(5, TimeUnit.SECONDS);
                return store.admit(request("delivery-concurrent-1", ORIGINAL_RUN_ID));
            });
            Future<ObservationResult> second = executor.submit(() -> {
                simultaneousAdmission.await(5, TimeUnit.SECONDS);
                return store.admit(request("delivery-concurrent-2", REPLAY_RUN_ID));
            });

            List<ObservationResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results).extracting(ObservationResult::status)
                    .containsExactlyInAnyOrder(ADMITTED, EXISTING_REVISION);
            assertThat(results).extracting(ObservationResult::reviewRunId)
                    .containsOnly(results.stream()
                            .filter(result -> result.status() == ADMITTED)
                            .findFirst().orElseThrow().reviewRunId());
            assertCounts(2, 1, 1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failureAfterDeliveryInsertRollsBackTheDelivery() {
        ReviewRunAdmissionStore failingAdmission = (reviewRun, executionJob, events) -> {
            throw new AdmissionTestFailure("run admission failed");
        };

        assertThatThrownBy(() -> store(failingAdmission)
                .admit(request("delivery-123", ORIGINAL_RUN_ID)))
                .isInstanceOf(AdmissionTestFailure.class)
                .hasMessage("run admission failed");

        assertCounts(0, 0, 0);
    }

    @Test
    void failureAfterRunInsertRollsBackTheDeliveryAndRun() {
        DurableJobQueue failingJobs = new FailingEnqueueQueue();

        assertThatThrownBy(() -> store(admission(failingJobs))
                .admit(request("delivery-123", ORIGINAL_RUN_ID)))
                .isInstanceOf(AdmissionTestFailure.class)
                .hasMessage("job enqueue failed");

        assertCounts(0, 0, 0);
    }

    @Test
    void failureAfterJobInsertRollsBackDeliveryRunAndJob() {
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_delivery_handled() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'handled update failed';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_delivery_handled
                BEFORE UPDATE OF handled_at ON github_deliveries
                FOR EACH ROW EXECUTE FUNCTION fail_delivery_handled()
                """);

        assertThatThrownBy(() -> store(admission(jobs))
                .admit(request("delivery-123", ORIGINAL_RUN_ID)))
                .hasMessageContaining("handled update failed");

        assertCounts(0, 0, 0);
    }

    @Test
    void aggregateAdmissionRulesRejectAnAlreadyStartedRunAndRollBackItsDelivery() {
        ObservationRequest request = request("delivery-123", ORIGINAL_RUN_ID);
        request.reviewRun().startAttempt(RECEIVED_AT.plusSeconds(1));

        assertThatThrownBy(() -> store(admission(jobs)).admit(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("new REQUESTED");

        assertCounts(0, 0, 0);
    }

    private PullRequestObservationStore store(ReviewRunAdmissionStore admission) {
        return new JdbcPullRequestObservationStore(jdbcTemplate, admission, transactions);
    }

    private ReviewRunAdmissionStore admission(DurableJobQueue jobQueue) {
        return new TransactionalReviewRunAdmissionStore(
                reviewRuns,
                jobQueue,
                new JdbcOutboxStore(jdbcTemplate),
                transactions);
    }

    private static ObservationRequest request(String deliveryId, UUID proposedRunId) {
        return request(deliveryId, proposedRunId, PAYLOAD_SHA256);
    }

    private static ObservationRequest request(
            String deliveryId, UUID proposedRunId, String payloadSha256) {
        ReviewRun reviewRun = ReviewRun.request(
                runId(proposedRunId),
                new PullRequestRevision(41L, 73L, 12, "observed-head-sha"),
                CONFIGURATION,
                RECEIVED_AT);
        return new ObservationRequest(
                deliveryId,
                "pull_request",
                payloadSha256,
                RECEIVED_AT,
                reviewRun,
                new DurableJobRequest(
                        "REVIEW_EXECUTION",
                        proposedRunId,
                        CONFIGURATION.maxReviewAttempts(),
                        RECEIVED_AT,
                        "review-execution:" + proposedRunId));
    }

    private static ReviewRunId runId(UUID id) {
        return new ReviewRunId(id);
    }

    private void assertExecutionJob(UUID runId) {
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT job_type, payload_reference, max_attempts, idempotency_key
                        FROM durable_jobs
                        """))
                .containsEntry("job_type", "REVIEW_EXECUTION")
                .containsEntry("payload_reference", runId)
                .containsEntry("max_attempts", 3)
                .containsEntry("idempotency_key", "review-execution:" + runId);
    }

    private void assertCounts(int deliveryCount, int reviewRunCount, int durableJobCount) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM github_deliveries", Integer.class)).isEqualTo(deliveryCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_runs", Integer.class)).isEqualTo(reviewRunCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM durable_jobs", Integer.class)).isEqualTo(durableJobCount);
    }

    private static final class FailingEnqueueQueue implements DurableJobQueue {

        @Override
        public UUID enqueue(DurableJobRequest request) {
            throw new AdmissionTestFailure("job enqueue failed");
        }

        @Override
        public List<LeasedJob> leaseDue(
                String owner, Instant now, Duration leaseDuration, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markSucceeded(UUID jobId, String owner, int expectedAttempt, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordFailure(
                UUID jobId,
                String owner,
                int expectedAttempt,
                FailureClass failureClass,
                Instant nextAttemptAt,
                Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int recoverExpiredLeases(Instant now) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class AdmissionTestFailure extends RuntimeException {
        private AdmissionTestFailure(String message) {
            super(message);
        }
    }
}
