package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.ObservePullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.application.SupersedeObsoleteReviewRuns;
import dev.langchain4j.example.codereview.reviewops.application.github.VerifiedPullRequestEvent;
import dev.langchain4j.example.codereview.reviewops.application.jobs.BackoffPolicy;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobDispatcher;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobWorker;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ScheduledLeaseHeartbeat;
import dev.langchain4j.example.codereview.reviewops.application.jobs.SupersedeObsoleteReviewRunsJobHandler;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewAttemptState;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import dev.langchain4j.example.codereview.reviewops.infrastructure.jobs.PostgresDurableJobQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupersedeObsoleteReviewRunsPostgresIntegrationTest extends PostgresIntegrationSupport {

    private static final Instant T0 = Instant.parse("2026-09-01T13:00:00Z");
    private static final ReviewConfigurationSnapshot CONFIGURATION =
            new ReviewConfigurationSnapshot(
                    "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", 3);

    private JdbcTemplate jdbcTemplate;
    private JdbcReviewRunRepository reviewRuns;
    private PostgresDurableJobQueue jobs;
    private JdbcPullRequestObservationStore observations;
    private TransactionTemplate transactions;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUpAdapters() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        reviewRuns = new JdbcReviewRunRepository(
                jdbcTemplate, transactions, new JsonColumnCodec(new ObjectMapper()));
        jobs = new PostgresDurableJobQueue(
                jdbcTemplate, transactions, Clock.fixed(T0, ZoneOffset.UTC));
        TransactionalReviewRunAdmissionStore admissions = new TransactionalReviewRunAdmissionStore(
                reviewRuns, jobs, new JdbcOutboxStore(jdbcTemplate), transactions);
        observations = new JdbcPullRequestObservationStore(
                jdbcTemplate, admissions, jobs, transactions);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        jdbcTemplate.execute("TRUNCATE TABLE outbox_events, durable_jobs, github_deliveries, review_runs CASCADE");
    }

    @AfterEach
    void stopScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void admittedNewRevisionSupersedesOlderRunningRunAndCompletesItsJob() {
        ReviewRunId oldId = observe("delivery-old", "old-head", T0);
        ReviewRunRepository.StoredReviewRun oldStored = reviewRuns.find(oldId).orElseThrow();
        ReviewRun old = oldStored.reviewRun();
        old.startAttempt(T0.plusSeconds(1));
        reviewRuns.update(old, oldStored.version());
        ReviewRunId currentId = observe("delivery-new", "new-head", T0.plusSeconds(2));
        jdbcTemplate.update(
                "UPDATE durable_jobs SET next_attempt_at = ? WHERE job_type = 'REVIEW_EXECUTION'",
                Timestamp.from(T0.plus(Duration.ofDays(1))));

        PostgresObsoleteReviewRunStore obsolete = new PostgresObsoleteReviewRunStore(
                jdbcTemplate, reviewRuns, transactions);
        SupersedeObsoleteReviewRuns useCase = new SupersedeObsoleteReviewRuns(
                reviewRuns, obsolete, Clock.fixed(T0.plusSeconds(3), ZoneOffset.UTC));
        ScheduledLeaseHeartbeat heartbeat = new ScheduledLeaseHeartbeat(
                jobs,
                Clock.fixed(T0.plusSeconds(3), ZoneOffset.UTC),
                Duration.ofMinutes(3),
                Duration.ofSeconds(30),
                scheduler);
        ReviewJobWorker worker = new ReviewJobWorker(
                jobs,
                new ReviewJobDispatcher(List.of(
                        new SupersedeObsoleteReviewRunsJobHandler(useCase))),
                BackoffPolicy.exponential(
                        Duration.ofSeconds(10), Duration.ofMinutes(1), 0.0, () -> 0.0),
                heartbeat,
                Clock.fixed(T0.plusSeconds(3), ZoneOffset.UTC),
                new SimpleMeterRegistry(),
                new ReviewJobWorker.WorkerSettings(
                        "supersession-worker",
                        Duration.ofMinutes(3),
                        Duration.ofSeconds(30),
                        10));

        ReviewJobWorker.WorkerCycleResult result = worker.runOnce();

        ReviewRun superseded = reviewRuns.find(oldId).orElseThrow().reviewRun();
        assertThat(superseded.state()).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThat(superseded.attempts()).singleElement().satisfies(attempt ->
                assertThat(attempt.state()).isEqualTo(ReviewAttemptState.CANCELLED));
        assertThat(reviewRuns.find(currentId).orElseThrow().reviewRun().state())
                .isEqualTo(ReviewRunState.REQUESTED);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT state FROM durable_jobs WHERE job_type = 'SUPERSEDE_OBSOLETE_RUNS' ORDER BY next_attempt_at",
                        String.class))
                .containsExactly("SUCCEEDED", "SUCCEEDED");
        assertThat(result.succeeded()).isEqualTo(2);
        heartbeat.close();
    }

    @Test
    void eachObsoleteRunCommitsInItsOwnTransaction() {
        ReviewRun first = requested("old-head-one", T0);
        ReviewRun second = requested("old-head-two", T0.plusSeconds(1));
        ReviewRun current = requested("new-head", T0.plusSeconds(2));
        reviewRuns.insert(first);
        reviewRuns.insert(second);
        reviewRuns.insert(current);
        ReviewRunRepository failOnSecondUpdate = new ReviewRunRepository() {
            @Override
            public Optional<StoredReviewRun> find(ReviewRunId id) {
                return reviewRuns.find(id);
            }

            @Override
            public void insert(ReviewRun reviewRun) {
                reviewRuns.insert(reviewRun);
            }

            @Override
            public long update(ReviewRun reviewRun, long expectedVersion) {
                if (reviewRun.id().equals(second.id())) {
                    throw new IllegalStateException("injected second-root failure");
                }
                return reviewRuns.update(reviewRun, expectedVersion);
            }
        };
        PostgresObsoleteReviewRunStore obsolete = new PostgresObsoleteReviewRunStore(
                jdbcTemplate, failOnSecondUpdate, transactions);
        SupersedeObsoleteReviewRuns useCase = new SupersedeObsoleteReviewRuns(
                failOnSecondUpdate,
                obsolete,
                Clock.fixed(T0.plusSeconds(3), ZoneOffset.UTC));

        assertThatThrownBy(() -> useCase.execute(current.id()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reviewRuns.find(first.id()).orElseThrow().reviewRun().state())
                .isEqualTo(ReviewRunState.SUPERSEDED);
        assertThat(reviewRuns.find(second.id()).orElseThrow().reviewRun().state())
                .isEqualTo(ReviewRunState.REQUESTED);
        assertThat(reviewRuns.find(current.id()).orElseThrow().reviewRun().state())
                .isEqualTo(ReviewRunState.REQUESTED);
    }

    private ReviewRunId observe(String deliveryId, String headSha, Instant observedAt) {
        return new ObservePullRequestRevision(
                observations, Clock.fixed(observedAt, ZoneOffset.UTC), CONFIGURATION)
                .observe(new VerifiedPullRequestEvent(
                                deliveryId,
                                "synchronize",
                                10,
                                20,
                                "octo/repo",
                                30,
                                headSha,
                                "https://github.com/octo/repo.git",
                                observedAt),
                        Integer.toHexString(deliveryId.hashCode()).repeat(64).substring(0, 64))
                .reviewRunId();
    }

    private static ReviewRun requested(String headSha, Instant requestedAt) {
        return ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(10, 20, 30, headSha),
                CONFIGURATION,
                requestedAt);
    }
}
