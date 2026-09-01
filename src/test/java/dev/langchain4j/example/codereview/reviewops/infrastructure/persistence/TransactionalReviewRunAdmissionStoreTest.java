package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunAdmissionStore;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunJobMismatchException;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobIntentConflictException;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxStore;
import dev.langchain4j.example.codereview.reviewops.domain.DuplicateReviewRunException;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionalReviewRunAdmissionStoreTest extends PostgresIntegrationSupport {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-31T03:00:00Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-31T03:10:00Z");
    private static final UUID ORIGINAL_RUN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID DUPLICATE_RUN_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID FIRST_EVENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID SECOND_EVENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000202");

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactions;
    private JdbcReviewRunRepository reviewRuns;
    private PostgresDurableJobQueue jobs;
    private JdbcOutboxStore outbox;

    @BeforeEach
    void setUpAdapters() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        reviewRuns = new JdbcReviewRunRepository(
                jdbcTemplate, transactions, new JsonColumnCodec(new ObjectMapper()));
        jobs = new PostgresDurableJobQueue(
                jdbcTemplate, transactions, Clock.fixed(REQUESTED_AT, ZoneOffset.UTC));
        outbox = new JdbcOutboxStore(jdbcTemplate);
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, durable_jobs, review_runs CASCADE");
    }

    @Test
    void successfulAdmissionCommitsOneRunOneJobAndOneOutboxFact() {
        ReviewRun run = requestedRun(ORIGINAL_RUN_ID);
        DurableJobRequest job = executionJob(run.id(), "review-run:original");
        OutboxEvent event = requestedEvent(FIRST_EVENT_ID, run.id(), REQUESTED_AT);

        admission(jobs, outbox).admit(run, job, List.of(event));

        assertThat(reviewRuns.find(run.id())).isPresent();
        assertTableCounts(1, 1, 1);
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT job_type, payload_reference, idempotency_key
                        FROM durable_jobs
                        """))
                .containsEntry("job_type", "REVIEW_EXECUTION")
                .containsEntry("payload_reference", run.id().value())
                .containsEntry("idempotency_key", "review-run:original");
        assertThat(outbox.loadUnpublished(10)).containsExactly(event);
    }

    @Test
    void mismatchedExecutionPayloadIsRejectedBeforeAnyAdmissionWrite() {
        ReviewRun run = requestedRun(ORIGINAL_RUN_ID);
        UUID wrongPayloadReference = DUPLICATE_RUN_ID;
        DurableJobRequest mismatchedJob = new DurableJobRequest(
                "REVIEW_EXECUTION", wrongPayloadReference, 3, REQUESTED_AT, "review-run:mismatch");

        assertThatThrownBy(() -> admission(jobs, outbox).admit(
                run,
                mismatchedJob,
                List.of(requestedEvent(FIRST_EVENT_ID, run.id(), REQUESTED_AT))))
                .isInstanceOfSatisfying(ReviewRunJobMismatchException.class, failure -> {
                    assertThat(failure.reviewRunId()).isEqualTo(run.id());
                    assertThat(failure.payloadReference()).isEqualTo(wrongPayloadReference);
                });

        assertTableCounts(0, 0, 0);
    }

    @Test
    void runningReviewIsRejectedBeforeAnyAdmissionWrite() {
        ReviewRun run = requestedRun(ORIGINAL_RUN_ID);
        run.startAttempt(REQUESTED_AT.plusSeconds(1));

        assertThatThrownBy(() -> admission(jobs, outbox).admit(
                run,
                executionJob(run.id(), "review-run:already-started"),
                List.of(requestedEvent(FIRST_EVENT_ID, run.id(), REQUESTED_AT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("new REQUESTED");

        assertTableCounts(0, 0, 0);
    }

    @Test
    void nonExecutionJobIsRejectedBeforeAnyAdmissionWrite() {
        ReviewRun run = requestedRun(ORIGINAL_RUN_ID);
        DurableJobRequest wrongJobType = new DurableJobRequest(
                "REACTION_RECONCILE", run.id().value(), 3, REQUESTED_AT,
                "review-run:wrong-job-type");

        assertThatThrownBy(() -> admission(jobs, outbox).admit(
                run,
                wrongJobType,
                List.of(requestedEvent(FIRST_EVENT_ID, run.id(), REQUESTED_AT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REVIEW_EXECUTION");

        assertTableCounts(0, 0, 0);
    }

    @Test
    void admissionJoinsAnExistingRequiredTransactionAndRollsBackWithItsCaller() {
        ReviewRun run = requestedRun(ORIGINAL_RUN_ID);

        transactions.executeWithoutResult(status -> {
            admission(jobs, outbox).admit(
                    run,
                    executionJob(run.id(), "review-run:outer-rollback"),
                    List.of(requestedEvent(FIRST_EVENT_ID, run.id(), REQUESTED_AT)));
            assertTableCounts(1, 1, 1);
            status.setRollbackOnly();
        });

        assertTableCounts(0, 0, 0);
    }

    @Test
    void jobEnqueueFailureRollsBackTheEarlierReviewRunInsert() {
        ReviewRun run = requestedRun(ORIGINAL_RUN_ID);
        DurableJobQueue failingJobs = new FailingEnqueueQueue(jobs);

        assertThatThrownBy(() -> admission(failingJobs, outbox).admit(
                run,
                executionJob(run.id(), "review-run:job-failure"),
                List.of(requestedEvent(FIRST_EVENT_ID, run.id(), REQUESTED_AT))))
                .isInstanceOf(AdmissionTestFailure.class)
                .hasMessage("job enqueue failed");

        assertTableCounts(0, 0, 0);
    }

    @Test
    void firstOutboxAppendFailureRollsBackTheEarlierRunAndJobWrites() {
        ReviewRun run = requestedRun(ORIGINAL_RUN_ID);
        OutboxStore failingOutbox = new FailOnAppendOutbox(outbox, 1);

        assertThatThrownBy(() -> admission(jobs, failingOutbox).admit(
                run,
                executionJob(run.id(), "review-run:first-outbox-failure"),
                List.of(requestedEvent(FIRST_EVENT_ID, run.id(), REQUESTED_AT))))
                .isInstanceOf(AdmissionTestFailure.class)
                .hasMessage("outbox append 1 failed");

        assertTableCounts(0, 0, 0);
    }

    @Test
    void laterOutboxAppendFailureRollsBackTheRunJobAndEarlierOutboxWrite() {
        ReviewRun run = requestedRun(ORIGINAL_RUN_ID);
        OutboxStore failOnSecondAppend = new FailOnAppendOutbox(outbox, 2);
        List<OutboxEvent> events = List.of(
                requestedEvent(FIRST_EVENT_ID, run.id(), REQUESTED_AT),
                requestedEvent(SECOND_EVENT_ID, run.id(), REQUESTED_AT.plusSeconds(1)));

        assertThatThrownBy(() -> admission(jobs, failOnSecondAppend).admit(
                run,
                executionJob(run.id(), "review-run:later-outbox-failure"),
                events))
                .isInstanceOf(AdmissionTestFailure.class)
                .hasMessage("outbox append 2 failed");

        assertTableCounts(0, 0, 0);
    }

    @Test
    void duplicateBusinessAdmissionHasAStableOutcomeWithoutDuplicatingJobOrOutbox() {
        ReviewRun original = requestedRun(ORIGINAL_RUN_ID);
        admission(jobs, outbox).admit(
                original,
                executionJob(original.id(), "review-run:duplicate"),
                List.of(requestedEvent(FIRST_EVENT_ID, original.id(), REQUESTED_AT)));
        ReviewRun duplicateIdentity = requestedRun(DUPLICATE_RUN_ID);

        assertThatThrownBy(() -> admission(jobs, outbox).admit(
                duplicateIdentity,
                executionJob(duplicateIdentity.id(), "review-run:duplicate"),
                List.of(requestedEvent(SECOND_EVENT_ID, duplicateIdentity.id(), REQUESTED_AT))))
                .isInstanceOfSatisfying(DuplicateReviewRunException.class,
                        failure -> assertThat(failure.reviewRunId()).isEqualTo(duplicateIdentity.id()));

        assertTableCounts(1, 1, 1);
        assertThat(reviewRuns.find(original.id())).isPresent();
        assertThat(reviewRuns.find(duplicateIdentity.id())).isEmpty();
        assertThat(outbox.loadUnpublished(10))
                .extracting(OutboxEvent::aggregateId)
                .containsExactly(original.id().value());
    }

    @Test
    void differentBusinessIdentityReusingAJobKeyRollsBackItsRunAndOutbox() {
        ReviewRun original = requestedRun(ORIGINAL_RUN_ID);
        admission(jobs, outbox).admit(
                original,
                executionJob(original.id(), "review-run:shared-key"),
                List.of(requestedEvent(FIRST_EVENT_ID, original.id(), REQUESTED_AT)));
        ReviewRun distinctIdentity = requestedRun(DUPLICATE_RUN_ID, 304, "other-head-sha");

        assertThatThrownBy(() -> admission(jobs, outbox).admit(
                distinctIdentity,
                executionJob(distinctIdentity.id(), "review-run:shared-key"),
                List.of(requestedEvent(
                        SECOND_EVENT_ID, distinctIdentity.id(), REQUESTED_AT.plusSeconds(1)))))
                .isInstanceOfSatisfying(DurableJobIntentConflictException.class, failure ->
                        assertThat(failure.idempotencyKey()).isEqualTo("review-run:shared-key"));

        assertTableCounts(1, 1, 1);
        assertThat(reviewRuns.find(original.id())).isPresent();
        assertThat(reviewRuns.find(distinctIdentity.id())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload_reference FROM durable_jobs WHERE idempotency_key = ?",
                UUID.class,
                "review-run:shared-key")).isEqualTo(original.id().value());
        assertThat(outbox.loadUnpublished(10))
                .extracting(OutboxEvent::aggregateId)
                .containsExactly(original.id().value());
    }

    @Test
    void unpublishedEventsUseStableOccurrenceAndIdentityOrderAndPublicationDoesNotDeleteFacts() {
        UUID earlierHighId = UUID.fromString("00000000-0000-0000-0000-000000000302");
        UUID earlierLowId = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID laterId = UUID.fromString("00000000-0000-0000-0000-000000000300");
        ReviewRunId aggregateId = new ReviewRunId(ORIGINAL_RUN_ID);
        OutboxEvent later = requestedEvent(laterId, aggregateId, REQUESTED_AT.plusSeconds(1));
        OutboxEvent earlierHigh = requestedEvent(earlierHighId, aggregateId, REQUESTED_AT);
        OutboxEvent earlierLow = requestedEvent(earlierLowId, aggregateId, REQUESTED_AT);
        outbox.append(later);
        outbox.append(earlierHigh);
        outbox.append(earlierLow);

        assertThat(outbox.loadUnpublished(2)).containsExactly(earlierLow, earlierHigh);

        outbox.markPublished(earlierLowId, PUBLISHED_AT);

        assertThat(outbox.loadUnpublished(10)).containsExactly(earlierHigh, later);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_events WHERE event_id = ?",
                java.sql.Timestamp.class,
                earlierLowId).toInstant()).isEqualTo(PUBLISHED_AT);
    }

    @Test
    void concurrentAtLeastOncePollersShareAStableEventIdentityAndPublicationAckIsIdempotent()
            throws Exception {
        ReviewRunId aggregateId = new ReviewRunId(ORIGINAL_RUN_ID);
        OutboxEvent event = requestedEvent(FIRST_EVENT_ID, aggregateId, REQUESTED_AT);
        outbox.append(event);

        try (Connection firstConnection = dataSource.getConnection();
             Connection secondConnection = dataSource.getConnection()) {
            JdbcOutboxStore firstPoller = outboxUsing(firstConnection);
            JdbcOutboxStore secondPoller = outboxUsing(secondConnection);
            CyclicBarrier simultaneousPoll = new CyclicBarrier(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<List<OutboxEvent>> first = executor.submit(() -> {
                    simultaneousPoll.await(5, TimeUnit.SECONDS);
                    return firstPoller.loadUnpublished(1);
                });
                Future<List<OutboxEvent>> second = executor.submit(() -> {
                    simultaneousPoll.await(5, TimeUnit.SECONDS);
                    return secondPoller.loadUnpublished(1);
                });

                assertThat(first.get(5, TimeUnit.SECONDS)).containsExactly(event);
                assertThat(second.get(5, TimeUnit.SECONDS)).containsExactly(event);

                firstPoller.markPublished(event.eventId(), PUBLISHED_AT);
                assertThat(firstPoller.loadUnpublished(1)).isEmpty();
                assertThat(secondPoller.loadUnpublished(1)).isEmpty();

                secondPoller.markPublished(event.eventId(), PUBLISHED_AT.plusSeconds(1));
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM outbox_events WHERE event_id = ?",
                        Integer.class,
                        event.eventId())).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT published_at FROM outbox_events WHERE event_id = ?",
                        java.sql.Timestamp.class,
                        event.eventId()).toInstant()).isEqualTo(PUBLISHED_AT);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void outboxEventCanonicalizesJsonRecursivelyAndRejectsInvalidJsonAtItsBoundary() {
        ReviewRunId aggregateId = new ReviewRunId(ORIGINAL_RUN_ID);
        OutboxEvent canonical = new OutboxEvent(
                FIRST_EVENT_ID,
                "ReviewRun",
                aggregateId.value(),
                "ReviewRunRequested",
                " { \"z\" : 3, \"nested\" : { \"b\" : 2, \"a\" : 1 }, "
                        + "\"array\" : [ { \"d\" : 4, \"c\" : 3 }, 2, 1 ] } ",
                REQUESTED_AT);

        assertThat(canonical.payload()).isEqualTo(
                "{\"array\":[{\"c\":3,\"d\":4},2,1],\"nested\":{\"a\":1,\"b\":2},\"z\":3}");
        outbox.append(canonical);
        assertThat(outbox.loadUnpublished(1)).containsExactly(canonical);

        assertThatThrownBy(() -> new OutboxEvent(
                SECOND_EVENT_ID,
                "ReviewRun",
                aggregateId.value(),
                "ReviewRunRequested",
                "{\"broken\":",
                REQUESTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload must be valid JSON");
    }

    @Test
    void nanosecondEventTimesNormalizeToPostgresMicrosForRoundTripOrderingAndPublication() {
        ReviewRunId aggregateId = new ReviewRunId(ORIGINAL_RUN_ID);
        Instant firstRawOccurrence = Instant.parse("2026-08-31T03:00:00.123456789Z");
        Instant secondRawOccurrence = Instant.parse("2026-08-31T03:00:00.123456999Z");
        Instant expectedOccurrence = Instant.parse("2026-08-31T03:00:00.123456Z");
        UUID highId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        UUID lowId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        OutboxEvent high = requestedEvent(highId, aggregateId, firstRawOccurrence);
        OutboxEvent low = requestedEvent(lowId, aggregateId, secondRawOccurrence);
        outbox.append(high);
        outbox.append(low);

        assertThat(high.occurredAt()).isEqualTo(expectedOccurrence);
        assertThat(low.occurredAt()).isEqualTo(expectedOccurrence);
        assertThat(outbox.loadUnpublished(2)).containsExactly(low, high);

        Instant rawPublishedAt = Instant.parse("2026-08-31T03:10:00.987654321Z");
        Instant expectedPublishedAt = Instant.parse("2026-08-31T03:10:00.987654Z");
        outbox.markPublished(lowId, rawPublishedAt);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_events WHERE event_id = ?",
                java.sql.Timestamp.class,
                lowId).toInstant()).isEqualTo(expectedPublishedAt);
        assertThat(outbox.loadUnpublished(2)).containsExactly(high);
    }

    private ReviewRunAdmissionStore admission(DurableJobQueue jobQueue, OutboxStore outboxStore) {
        return new TransactionalReviewRunAdmissionStore(
                reviewRuns, jobQueue, outboxStore, transactions);
    }

    private static JdbcOutboxStore outboxUsing(Connection connection) {
        return new JdbcOutboxStore(new JdbcTemplate(
                new SingleConnectionDataSource(connection, true)));
    }

    private static ReviewRun requestedRun(UUID id) {
        return requestedRun(id, 303, "admitted-head-sha");
    }

    private static ReviewRun requestedRun(UUID id, int pullRequestNumber, String headSha) {
        return ReviewRun.request(
                new ReviewRunId(id),
                new PullRequestRevision(101, 202, pullRequestNumber, headSha),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v7", "kimi-k2", "policy-v5", 3),
                REQUESTED_AT);
    }

    private static DurableJobRequest executionJob(ReviewRunId runId, String idempotencyKey) {
        return new DurableJobRequest(
                "REVIEW_EXECUTION", runId.value(), 3, REQUESTED_AT, idempotencyKey);
    }

    private static OutboxEvent requestedEvent(UUID eventId, ReviewRunId runId, Instant occurredAt) {
        return new OutboxEvent(
                eventId,
                "ReviewRun",
                runId.value(),
                "ReviewRunRequested",
                "{}",
                occurredAt);
    }

    private void assertTableCounts(int reviewRunCount, int jobCount, int outboxCount) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_runs", Integer.class)).isEqualTo(reviewRunCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM durable_jobs", Integer.class)).isEqualTo(jobCount);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(outboxCount);
    }

    private static final class FailingEnqueueQueue implements DurableJobQueue {

        private final DurableJobQueue delegate;

        private FailingEnqueueQueue(DurableJobQueue delegate) {
            this.delegate = delegate;
        }

        @Override
        public UUID enqueue(DurableJobRequest request) {
            throw new AdmissionTestFailure("job enqueue failed");
        }

        @Override
        public List<LeasedJob> leaseDue(String owner, Instant now, Duration leaseDuration, int limit) {
            return delegate.leaseDue(owner, now, leaseDuration, limit);
        }

        @Override
        public void markSucceeded(UUID jobId, String owner, int expectedAttempt, Instant now) {
            delegate.markSucceeded(jobId, owner, expectedAttempt, now);
        }

        @Override
        public void recordFailure(UUID jobId, String owner, int expectedAttempt, FailureClass failureClass,
                                  Instant nextAttemptAt, Instant now) {
            delegate.recordFailure(
                    jobId, owner, expectedAttempt, failureClass, nextAttemptAt, now);
        }

        @Override
        public int recoverExpiredLeases(Instant now) {
            return delegate.recoverExpiredLeases(now);
        }
    }

    private static final class FailOnAppendOutbox implements OutboxStore {

        private final OutboxStore delegate;
        private final int failingAppend;
        private int appendCount;

        private FailOnAppendOutbox(OutboxStore delegate, int failingAppend) {
            this.delegate = delegate;
            this.failingAppend = failingAppend;
        }

        @Override
        public void append(OutboxEvent event) {
            appendCount++;
            if (appendCount == failingAppend) {
                throw new AdmissionTestFailure("outbox append " + appendCount + " failed");
            }
            delegate.append(event);
        }

        @Override
        public List<OutboxEvent> loadUnpublished(int limit) {
            return delegate.loadUnpublished(limit);
        }

        @Override
        public void markPublished(UUID eventId, Instant publishedAt) {
            delegate.markPublished(eventId, publishedAt);
        }
    }

    private static final class AdmissionTestFailure extends RuntimeException {
        private AdmissionTestFailure(String message) {
            super(message);
        }
    }
}
