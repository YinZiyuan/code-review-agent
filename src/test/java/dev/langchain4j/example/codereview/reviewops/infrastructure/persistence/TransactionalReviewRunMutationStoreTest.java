package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.DecideReviewPublication;
import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.application.ReviewRunMutationStore;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxStore;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprintFactory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionalReviewRunMutationStoreTest extends PostgresIntegrationSupport {

    private static final Instant T0 = Instant.parse("2026-09-01T02:00:00Z");

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
                jdbcTemplate, transactions, Clock.fixed(T0, ZoneOffset.UTC));
        outbox = new JdbcOutboxStore(jdbcTemplate);
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, durable_jobs, review_runs CASCADE");
    }

    @Test
    void progressOnlySaveAdvancesTheAggregateWithoutCreatingFollowUpIntent() {
        ReviewRun run = requestedRun();
        reviewRuns.insert(run);
        run.startAttempt(T0.plusSeconds(1));

        long nextVersion = store(jobs, outbox).saveProgress(run, 0);

        assertThat(nextVersion).isEqualTo(1);
        assertPersistedState(run.id(), ReviewRunState.RUNNING, 1);
        assertThat(count("durable_jobs")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    @Test
    void completedAggregateJobAndOutboxFactCommitAsOneMutation() {
        RunningRun running = persistedRunningRun();
        running.run().completeReview(
                List.of(finding()), measurements(), T0.plusSeconds(2));
        DurableJobRequest job = publicationDecisionJob(running.run());
        OutboxEvent event = event(running.run(), "00000000-0000-0000-0000-000000000611", 2);

        long nextVersion = store(jobs, outbox).saveAndEnqueue(
                running.run(), running.version(), List.of(job), List.of(event));

        assertThat(nextVersion).isEqualTo(2);
        assertPersistedState(running.run().id(), ReviewRunState.COMPLETED, 2);
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT job_type, payload_reference, idempotency_key
                        FROM durable_jobs
                        """))
                .containsEntry("job_type", ExecuteReviewRun.DECIDE_PUBLICATION_JOB_TYPE)
                .containsEntry("payload_reference", running.run().id().value())
                .containsEntry("idempotency_key",
                        "decide-publication:" + running.run().id().value());
        assertThat(outbox.loadUnpublished(10)).containsExactly(event);
    }

    @Test
    void jobFailureRollsBackTheEarlierAggregateUpdate() {
        RunningRun running = persistedRunningRun();
        running.run().completeReview(List.of(finding()), measurements(), T0.plusSeconds(2));
        DurableJobQueue failingJobs = new FailOnEnqueueQueue(jobs);

        assertThatThrownBy(() -> store(failingJobs, outbox).saveAndEnqueue(
                running.run(), running.version(),
                List.of(publicationDecisionJob(running.run())),
                List.of(event(running.run(), "00000000-0000-0000-0000-000000000621", 2))))
                .isInstanceOf(MutationTestFailure.class)
                .hasMessage("job insert failed");

        assertPriorRunningStateRemains(running.run().id(), running.version());
    }

    @Test
    void firstOutboxFailureRollsBackAggregateAndInsertedJob() {
        RunningRun running = persistedRunningRun();
        running.run().completeReview(List.of(finding()), measurements(), T0.plusSeconds(2));
        OutboxStore failingOutbox = new FailOnAppendOutbox(outbox, 1);

        assertThatThrownBy(() -> store(jobs, failingOutbox).saveAndEnqueue(
                running.run(), running.version(),
                List.of(publicationDecisionJob(running.run())),
                List.of(event(running.run(), "00000000-0000-0000-0000-000000000631", 2))))
                .isInstanceOf(MutationTestFailure.class)
                .hasMessage("outbox append 1 failed");

        assertPriorRunningStateRemains(running.run().id(), running.version());
    }

    @Test
    void laterOutboxFailureRollsBackEarlierOutboxAggregateAndJobWrites() {
        RunningRun running = persistedRunningRun();
        running.run().completeReview(List.of(finding()), measurements(), T0.plusSeconds(2));
        OutboxStore failingOutbox = new FailOnAppendOutbox(outbox, 2);
        List<OutboxEvent> events = List.of(
                event(running.run(), "00000000-0000-0000-0000-000000000641", 2),
                event(running.run(), "00000000-0000-0000-0000-000000000642", 3));

        assertThatThrownBy(() -> store(jobs, failingOutbox).saveAndEnqueue(
                running.run(), running.version(),
                List.of(publicationDecisionJob(running.run())), events))
                .isInstanceOf(MutationTestFailure.class)
                .hasMessage("outbox append 2 failed");

        assertPriorRunningStateRemains(running.run().id(), running.version());
    }

    @Test
    void publicationDecisionIntentIsRejectedUntilTheAggregateIsCompleted() {
        RunningRun running = persistedRunningRun();

        assertThatThrownBy(() -> store(jobs, outbox).saveAndEnqueue(
                running.run(), running.version(),
                List.of(publicationDecisionJob(running.run())), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("COMPLETED");

        assertPriorRunningStateRemains(running.run().id(), running.version());
    }

    @Test
    void publicationIntentIsRejectedUntilPersistedFindingsHaveLegalDecisions() {
        RunningRun running = persistedRunningRun();
        running.run().completeReview(List.of(finding()), measurements(), T0.plusSeconds(2));

        assertThatThrownBy(() -> store(jobs, outbox).saveAndEnqueue(
                running.run(), running.version(),
                List.of(publishReviewJob(running.run())), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publication decisions");

        assertPriorRunningStateRemains(running.run().id(), running.version());
    }

    @Test
    void publicationDecisionsAndIdempotentPublicationIntentCommitTogether() {
        RunningRun running = persistedRunningRun();
        ReviewFinding finding = finding();
        running.run().completeReview(List.of(finding), measurements(), T0.plusSeconds(2));
        running.run().acceptPublicationDecisions(Map.of(
                finding.fingerprint(),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "policy-v1")));

        long nextVersion = store(jobs, outbox).saveAndEnqueue(
                running.run(), running.version(),
                List.of(publishReviewJob(running.run())), List.of());

        assertThat(nextVersion).isEqualTo(2);
        ReviewRun persisted = reviewRuns.find(running.run().id()).orElseThrow().reviewRun();
        assertThat(persisted.state()).isEqualTo(ReviewRunState.COMPLETED);
        assertThat(persisted.findings()).singleElement().satisfies(savedFinding ->
                assertThat(savedFinding.publicationDecision()).contains(
                        new PublicationDecision(PublicationTier.CHECK_SUMMARY, "policy-v1")));
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT job_type, payload_reference, idempotency_key
                        FROM durable_jobs
                        """))
                .containsEntry("job_type", DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE)
                .containsEntry("payload_reference", running.run().id().value())
                .containsEntry("idempotency_key",
                        "publish-review:" + running.run().id().value());
        assertThat(count("outbox_events")).isZero();
    }

    private TransactionalReviewRunMutationStore store(
            DurableJobQueue durableJobs, OutboxStore outboxStore) {
        return new TransactionalReviewRunMutationStore(
                reviewRuns, durableJobs, outboxStore, transactions);
    }

    private RunningRun persistedRunningRun() {
        ReviewRun run = requestedRun();
        reviewRuns.insert(run);
        run.startAttempt(T0.plusSeconds(1));
        return new RunningRun(run, reviewRuns.update(run, 0));
    }

    private void assertPriorRunningStateRemains(ReviewRunId id, long expectedVersion) {
        assertPersistedState(id, ReviewRunState.RUNNING, expectedVersion);
        ReviewRun persisted = reviewRuns.find(id).orElseThrow().reviewRun();
        assertThat(persisted.attempts()).singleElement()
                .satisfies(attempt -> assertThat(attempt.state().name()).isEqualTo("STARTED"));
        assertThat(persisted.findings()).isEmpty();
        assertThat(count("durable_jobs")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    private void assertPersistedState(ReviewRunId id, ReviewRunState state, long version) {
        ReviewRunRepository.StoredReviewRun stored = reviewRuns.find(id).orElseThrow();
        assertThat(stored.reviewRun().state()).isEqualTo(state);
        assertThat(stored.version()).isEqualTo(version);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static ReviewRun requestedRun() {
        return ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(101, 202, 303,
                        "0123456789abcdef0123456789abcdef01234567"),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", 3),
                T0);
    }

    private static ReviewFinding finding() {
        CodeLocation location = new CodeLocation("src/Foo.java", 12, true);
        FindingContent content = new FindingContent(
                FindingSeverity.WARNING, FindingCategory.STABILITY,
                "Unsafe query", "description", "suggestion");
        FindingEvidence evidence = new FindingEvidence(
                "query uses untrusted input", List.of(), "llm_reviewer");
        return new ReviewFinding(
                new FindingFingerprintFactory().create(location, content, evidence),
                location, content, evidence);
    }

    private static ExecutionMeasurements measurements() {
        return new ExecutionMeasurements(20, 0, 0, java.util.Map.of("spotbugs", "RAN"));
    }

    private static DurableJobRequest publicationDecisionJob(ReviewRun run) {
        return new DurableJobRequest(
                ExecuteReviewRun.DECIDE_PUBLICATION_JOB_TYPE,
                run.id().value(),
                run.configuration().maxReviewAttempts(),
                T0.plusSeconds(2),
                "decide-publication:" + run.id().value());
    }

    private static DurableJobRequest publishReviewJob(ReviewRun run) {
        return new DurableJobRequest(
                DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE,
                run.id().value(),
                run.configuration().maxReviewAttempts(),
                T0.plusSeconds(2),
                "publish-review:" + run.id().value());
    }

    private static OutboxEvent event(ReviewRun run, String eventId, long seconds) {
        return new OutboxEvent(
                UUID.fromString(eventId),
                "ReviewRun",
                run.id().value(),
                "ReviewRunCompleted",
                "{\"reviewRunId\":\"" + run.id().value() + "\"}",
                T0.plusSeconds(seconds));
    }

    private record RunningRun(ReviewRun run, long version) {
    }

    private static final class FailOnEnqueueQueue implements DurableJobQueue {
        private final DurableJobQueue delegate;

        private FailOnEnqueueQueue(DurableJobQueue delegate) {
            this.delegate = delegate;
        }

        @Override
        public UUID enqueue(DurableJobRequest request) {
            throw new MutationTestFailure("job insert failed");
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
        public void recordFailure(UUID jobId, String owner, int expectedAttempt,
                                  FailureClass failureClass, Instant nextAttemptAt, Instant now) {
            delegate.recordFailure(jobId, owner, expectedAttempt, failureClass, nextAttemptAt, now);
        }

        @Override
        public int recoverExpiredLeases(Instant now) {
            return delegate.recoverExpiredLeases(now);
        }
    }

    private static final class FailOnAppendOutbox implements OutboxStore {
        private final OutboxStore delegate;
        private final int failingAppend;
        private int appends;

        private FailOnAppendOutbox(OutboxStore delegate, int failingAppend) {
            this.delegate = delegate;
            this.failingAppend = failingAppend;
        }

        @Override
        public void append(OutboxEvent event) {
            appends++;
            if (appends == failingAppend) {
                throw new MutationTestFailure("outbox append " + appends + " failed");
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

    private static final class MutationTestFailure extends RuntimeException {
        private MutationTestFailure(String message) {
            super(message);
        }
    }
}
