package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import dev.langchain4j.example.codereview.reviewops.application.jobs.BackoffPolicy;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobDispatcher;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobHandler;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobWorker;
import dev.langchain4j.example.codereview.reviewops.application.jobs.ScheduledLeaseHeartbeat;
import dev.langchain4j.example.codereview.reviewops.infrastructure.jobs.PostgresDurableJobQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewJobWorkerPostgresIntegrationTest extends PostgresIntegrationSupport {

    private static final Instant T0 = Instant.parse("2026-09-01T11:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(3);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private JdbcTemplate jdbcTemplate;
    private PostgresDurableJobQueue firstQueue;
    private PostgresDurableJobQueue secondQueue;
    private MutableClock clock;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUpWorker() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        clock = new MutableClock(T0.plusSeconds(2));
        firstQueue = new PostgresDurableJobQueue(jdbcTemplate, transactions, Clock.fixed(T0, ZoneOffset.UTC));
        secondQueue = new PostgresDurableJobQueue(jdbcTemplate, transactions, Clock.fixed(T0, ZoneOffset.UTC));
        scheduler = Executors.newSingleThreadScheduledExecutor();
        jdbcTemplate.execute("TRUNCATE TABLE durable_jobs CASCADE");
    }

    @AfterEach
    void stopScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void staleWorkerNeverDispatchesAWaitingJobReassignedAfterBatchLeaseExpiry() {
        UUID firstId = firstQueue.enqueue(new DurableJobRequest(
                "FIRST", UUID.randomUUID(), 3, T0, "first-waiting-race"));
        UUID secondId = firstQueue.enqueue(new DurableJobRequest(
                "SECOND", UUID.randomUUID(), 3, T0.plusSeconds(1), "second-waiting-race"));
        AtomicBoolean secondInvokedByStaleWorker = new AtomicBoolean();
        AtomicReference<List<LeasedJob>> secondWorkerLeases = new AtomicReference<>(List.of());
        ReviewJobHandler first = handler("FIRST", job -> {
            assertThat(job.id()).isEqualTo(firstId);
            clock.set(T0.plus(LEASE_DURATION).plusSeconds(3));
            assertThat(secondQueue.recoverExpiredLeases(clock.instant())).isEqualTo(2);
            secondWorkerLeases.set(secondQueue.leaseDue(
                    "worker-b", clock.instant(), LEASE_DURATION, 2));
            return ReviewJobHandler.JobOutcome.succeeded();
        });
        ReviewJobHandler second = handler("SECOND", job -> {
            secondInvokedByStaleWorker.set(true);
            return ReviewJobHandler.JobOutcome.succeeded();
        });
        ScheduledLeaseHeartbeat heartbeat = new ScheduledLeaseHeartbeat(
                firstQueue, clock, LEASE_DURATION, HEARTBEAT_INTERVAL, scheduler);
        ReviewJobWorker worker = new ReviewJobWorker(
                firstQueue,
                new ReviewJobDispatcher(List.of(first, second)),
                BackoffPolicy.exponential(
                        Duration.ofSeconds(10), Duration.ofMinutes(1), 0.0, () -> 0.0),
                heartbeat,
                clock,
                new SimpleMeterRegistry(),
                new ReviewJobWorker.WorkerSettings(
                        "worker-a", LEASE_DURATION, HEARTBEAT_INTERVAL, 2));

        worker.runOnce();

        assertThat(secondWorkerLeases.get())
                .extracting(LeasedJob::id)
                .contains(secondId);
        assertThat(secondInvokedByStaleWorker).isFalse();
        heartbeat.close();
    }

    private static ReviewJobHandler handler(
            String jobType,
            java.util.function.Function<LeasedJob, ReviewJobHandler.JobOutcome> behavior) {
        return new ReviewJobHandler() {
            @Override
            public String jobType() {
                return jobType;
            }

            @Override
            public JobOutcome handle(LeasedJob job) {
                return behavior.apply(job);
            }
        };
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        private void set(Instant now) {
            this.now.set(now);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("test clock uses UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
