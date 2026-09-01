package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewJobWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String OWNER = "worker-task-7";
    private static final Duration LEASE_DURATION = Duration.ofMinutes(3);
    private static final int BATCH_SIZE = 4;

    @Test
    void recoversExpiredLeasesBeforeLeasingOneBoundedBatch() {
        FakeQueue queue = new FakeQueue();
        queue.recovered = 2;
        ReviewJobWorker worker = worker(queue, List.of(), zeroJitterBackoff(), new FakeHeartbeat(),
                new SimpleMeterRegistry());

        ReviewJobWorker.WorkerCycleResult result = worker.runOnce();

        assertThat(queue.operations).containsExactly("recover", "lease");
        assertThat(queue.leaseOwner).isEqualTo(OWNER);
        assertThat(queue.leaseNow).isEqualTo(NOW);
        assertThat(queue.leaseDuration).isEqualTo(LEASE_DURATION);
        assertThat(queue.leaseLimit).isEqualTo(BATCH_SIZE);
        assertThat(result.recovered()).isEqualTo(2);
        assertThat(result.leased()).isZero();
    }

    @Test
    void successfulJobKeepsHeartbeatActiveAndCompletesWithOriginalFence() {
        FakeQueue queue = new FakeQueue();
        LeasedJob job = leased("REVIEW_EXECUTION", 9, 2);
        queue.leased = List.of(job);
        FakeHeartbeat heartbeat = new FakeHeartbeat();
        ReviewJobHandler handler = handler("REVIEW_EXECUTION", leased -> {
            assertThat(heartbeat.active).isTrue();
            return ReviewJobHandler.JobOutcome.succeeded();
        });
        ReviewJobWorker worker = worker(queue, List.of(handler), zeroJitterBackoff(), heartbeat,
                new SimpleMeterRegistry());

        ReviewJobWorker.WorkerCycleResult result = worker.runOnce();

        assertThat(heartbeat.active).isFalse();
        assertThat(heartbeat.startedJob).isSameAs(job);
        assertThat(heartbeat.startedOwner).isEqualTo(OWNER);
        assertThat(queue.settlements).containsExactly(new Settlement(
                job.id(), OWNER, 9, null, null, NOW));
        assertThat(result.succeeded()).isEqualTo(1);
    }

    @Test
    void transientFailureUsesChargedDeliveryAttemptForCappedExponentialBackoff() {
        FakeQueue queue = new FakeQueue();
        LeasedJob job = leased("REVIEW_EXECUTION", 11, 3);
        queue.leased = List.of(job);
        ReviewJobHandler handler = handler("REVIEW_EXECUTION",
                leased -> ReviewJobHandler.JobOutcome.transientFailure("github_transient"));
        BackoffPolicy backoff = BackoffPolicy.exponential(
                Duration.ofSeconds(10), Duration.ofSeconds(25), 0.20, () -> 0.0);
        ReviewJobWorker worker = worker(queue, List.of(handler), backoff, new FakeHeartbeat(),
                new SimpleMeterRegistry());

        ReviewJobWorker.WorkerCycleResult result = worker.runOnce();

        assertThat(queue.settlements).containsExactly(new Settlement(
                job.id(), OWNER, 11, FailureClass.TRANSIENT, NOW.plusSeconds(25), NOW));
        assertThat(result.retried()).isEqualTo(1);
    }

    @Test
    void exponentialBackoffIsBoundedAndSupportsDeterministicJitter() {
        BackoffPolicy zeroJitter = BackoffPolicy.exponential(
                Duration.ofSeconds(10), Duration.ofSeconds(25), 0.20, () -> 0.0);
        BackoffPolicy halfJitter = BackoffPolicy.exponential(
                Duration.ofSeconds(10), Duration.ofSeconds(25), 0.20, () -> 0.5);

        assertThat(zeroJitter.nextAttemptAt(NOW, 1)).isEqualTo(NOW.plusSeconds(10));
        assertThat(zeroJitter.nextAttemptAt(NOW, 2)).isEqualTo(NOW.plusSeconds(20));
        assertThat(zeroJitter.nextAttemptAt(NOW, 3)).isEqualTo(NOW.plusSeconds(25));
        assertThat(zeroJitter.nextAttemptAt(NOW, 63)).isEqualTo(NOW.plusSeconds(25));
        assertThat(halfJitter.nextAttemptAt(NOW, 1)).isEqualTo(NOW.plusSeconds(11));
        assertThat(halfJitter.nextAttemptAt(NOW, 3)).isEqualTo(NOW.plusSeconds(25));
    }

    @Test
    void exponentialBackoffRejectsInvalidBoundsAttemptsAndRandomValues() {
        assertThatThrownBy(() -> BackoffPolicy.exponential(
                Duration.ZERO, Duration.ofSeconds(10), 0.20, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackoffPolicy.exponential(
                Duration.ofSeconds(20), Duration.ofSeconds(10), 0.20, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackoffPolicy.exponential(
                Duration.ofSeconds(10), Duration.ofSeconds(20), 1.01, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        BackoffPolicy invalidRandom = BackoffPolicy.exponential(
                Duration.ofSeconds(10), Duration.ofSeconds(20), 0.20, () -> 1.01);
        assertThatThrownBy(() -> invalidRandom.nextAttemptAt(NOW, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invalidRandom.nextAttemptAt(NOW, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rateLimitedFailureHonorsTaskFiveRetryAtExactly() {
        FakeQueue queue = new FakeQueue();
        LeasedJob job = leased("REVIEW_EXECUTION", 3, 2);
        queue.leased = List.of(job);
        Instant retryAt = NOW.plusSeconds(137);
        ReviewJobHandler handler = handler("REVIEW_EXECUTION", leased -> {
            throw new GitHubFailureException(
                    GitHubFailureException.Classification.RATE_LIMITED,
                    "GitHub rate limit reached",
                    retryAt);
        });
        ReviewJobWorker worker = worker(queue, List.of(handler), zeroJitterBackoff(),
                new FakeHeartbeat(), new SimpleMeterRegistry());

        ReviewJobWorker.WorkerCycleResult result = worker.runOnce();

        assertThat(queue.settlements).containsExactly(new Settlement(
                job.id(), OWNER, 3, FailureClass.TRANSIENT, retryAt, NOW));
        assertThat(result.rateLimited()).isEqualTo(1);
    }

    @Test
    void rateLimitedFailureWithoutUsableGuidanceFallsBackToBackoff() {
        FakeQueue queue = new FakeQueue();
        LeasedJob job = leased("REVIEW_EXECUTION", 3, 2);
        queue.leased = List.of(job);
        ReviewJobHandler handler = handler("REVIEW_EXECUTION", leased -> {
            throw new GitHubFailureException(
                    GitHubFailureException.Classification.RATE_LIMITED,
                    "GitHub rate limit reached");
        });
        ReviewJobWorker worker = worker(queue, List.of(handler), zeroJitterBackoff(),
                new FakeHeartbeat(), new SimpleMeterRegistry());

        worker.runOnce();

        assertThat(queue.settlements).containsExactly(new Settlement(
                job.id(), OWNER, 3, FailureClass.TRANSIENT, NOW.plusSeconds(20), NOW));
    }

    @Test
    void terminalAndMalformedFailuresBecomeDeadWithoutStoppingTheBatch() {
        FakeQueue queue = new FakeQueue();
        LeasedJob authorization = leased("AUTH", 5, 1);
        LeasedJob malformed = leased("MALFORMED", 6, 1);
        LeasedJob success = leased("SUCCESS", 7, 1);
        queue.leased = List.of(authorization, malformed, success);
        List<ReviewJobHandler> handlers = List.of(
                handler("AUTH", job -> {
                    throw new GitHubFailureException(
                            GitHubFailureException.Classification.AUTHORIZATION,
                            "GitHub authorization failed");
                }),
                handler("MALFORMED", job -> {
                    throw new IllegalArgumentException("unsafe malformed payload detail");
                }),
                handler("SUCCESS", job -> ReviewJobHandler.JobOutcome.succeeded()));
        ReviewJobWorker worker = worker(queue, handlers, zeroJitterBackoff(), new FakeHeartbeat(),
                new SimpleMeterRegistry());

        ReviewJobWorker.WorkerCycleResult result = worker.runOnce();

        assertThat(queue.settlements).containsExactly(
                new Settlement(authorization.id(), OWNER, 5, FailureClass.TERMINAL, NOW, NOW),
                new Settlement(malformed.id(), OWNER, 6, FailureClass.TERMINAL, NOW, NOW),
                new Settlement(success.id(), OWNER, 7, null, null, NOW));
        assertThat(result.dead()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(1);
    }

    @Test
    void unexpectedFailureIsRetriedAndDoesNotStopTheBatch() {
        FakeQueue queue = new FakeQueue();
        LeasedJob failed = leased("FAILS", 1, 1);
        LeasedJob success = leased("SUCCEEDS", 2, 1);
        queue.leased = List.of(failed, success);
        ReviewJobWorker worker = worker(queue, List.of(
                        handler("FAILS", job -> {
                            throw new IllegalStateException("unsafe infrastructure detail");
                        }),
                        handler("SUCCEEDS", job -> ReviewJobHandler.JobOutcome.succeeded())),
                zeroJitterBackoff(), new FakeHeartbeat(), new SimpleMeterRegistry());

        ReviewJobWorker.WorkerCycleResult result = worker.runOnce();

        assertThat(queue.settlements).containsExactly(
                new Settlement(failed.id(), OWNER, 1, FailureClass.TRANSIENT,
                        NOW.plusSeconds(10), NOW),
                new Settlement(success.id(), OWNER, 2, null, null, NOW));
        assertThat(result.retried()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
    }

    @Test
    void settlementFailureLeavesThatLeaseRecoverableAndDoesNotStopTheBatch() {
        FakeQueue queue = new FakeQueue();
        LeasedJob unsettled = leased("FIRST", 1, 1);
        LeasedJob success = leased("SECOND", 2, 1);
        queue.leased = List.of(unsettled, success);
        queue.settlementFailureJobId = unsettled.id();
        ReviewJobWorker worker = worker(queue, List.of(
                        handler("FIRST", job -> ReviewJobHandler.JobOutcome.succeeded()),
                        handler("SECOND", job -> ReviewJobHandler.JobOutcome.succeeded())),
                zeroJitterBackoff(), new FakeHeartbeat(), new SimpleMeterRegistry());

        ReviewJobWorker.WorkerCycleResult result = worker.runOnce();

        assertThat(queue.settlements).containsExactly(new Settlement(
                success.id(), OWNER, 2, null, null, NOW));
        assertThat(result.settlementFailed()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
    }

    @Test
    void heartbeatSessionFailureLeavesThatLeaseRecoverableAndDoesNotStopTheBatch() {
        FakeQueue queue = new FakeQueue();
        LeasedJob unsettled = leased("FIRST", 1, 1);
        LeasedJob success = leased("SECOND", 2, 1);
        queue.leased = List.of(unsettled, success);
        FakeHeartbeat heartbeat = new FakeHeartbeat();
        heartbeat.closeFailureJobId = unsettled.id();
        ReviewJobWorker worker = worker(queue, List.of(
                        handler("FIRST", job -> ReviewJobHandler.JobOutcome.succeeded()),
                        handler("SECOND", job -> ReviewJobHandler.JobOutcome.succeeded())),
                zeroJitterBackoff(), heartbeat, new SimpleMeterRegistry());

        ReviewJobWorker.WorkerCycleResult result = worker.runOnce();

        assertThat(queue.settlements).containsExactly(new Settlement(
                success.id(), OWNER, 2, null, null, NOW));
        assertThat(result.ownershipLost()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
    }

    @Test
    void unknownJobTypeIsSettledAsTerminalWithBoundedMetricTags() {
        FakeQueue queue = new FakeQueue();
        LeasedJob unknown = leased("attacker-controlled-unknown-type", 1, 1);
        queue.leased = List.of(unknown);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ReviewJobWorker worker = worker(queue, List.of(), zeroJitterBackoff(), new FakeHeartbeat(), metrics);

        worker.runOnce();

        assertThat(queue.settlements).containsExactly(new Settlement(
                unknown.id(), OWNER, 1, FailureClass.TERMINAL, NOW, NOW));
        assertThat(metrics.get("code.review.jobs")
                .tags("job.type", "UNKNOWN", "outcome", "dead")
                .counter().count()).isEqualTo(1.0);
        assertThat(metrics.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsOnly("job.type", "outcome"));
    }

    @Test
    void lostHeartbeatOwnershipPreventsCompletionFromUsingAStaleLease() {
        FakeQueue queue = new FakeQueue();
        queue.leased = List.of(leased("REVIEW_EXECUTION", 8, 1));
        FakeHeartbeat heartbeat = new FakeHeartbeat();
        heartbeat.ownershipValid = false;
        ReviewJobWorker worker = worker(queue, List.of(handler(
                        "REVIEW_EXECUTION", job -> ReviewJobHandler.JobOutcome.succeeded())),
                zeroJitterBackoff(), heartbeat, new SimpleMeterRegistry());

        ReviewJobWorker.WorkerCycleResult result = worker.runOnce();

        assertThat(queue.settlements).isEmpty();
        assertThat(result.ownershipLost()).isEqualTo(1);
    }

    @Test
    void scheduledHeartbeatRenewsTheExactLeaseAndCancelsAfterHandling() {
        FakeQueue queue = new FakeQueue();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> scheduled = mock(ScheduledFuture.class);
        java.util.concurrent.atomic.AtomicReference<Runnable> heartbeatTask =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(
                any(Runnable.class),
                eq(Duration.ofSeconds(30).toNanos()),
                eq(Duration.ofSeconds(30).toNanos()),
                eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    heartbeatTask.set(invocation.getArgument(0));
                    return scheduled;
                });
        ScheduledLeaseHeartbeat heartbeat = new ScheduledLeaseHeartbeat(
                queue, CLOCK, LEASE_DURATION, Duration.ofSeconds(30), scheduler);
        LeasedJob job = leased("REVIEW_EXECUTION", 13, 2);

        ReviewJobWorker.LeaseHeartbeat.Session session = heartbeat.start(job, OWNER);
        heartbeatTask.get().run();

        assertThat(queue.renewals).containsExactly(new Renewal(
                job.id(), OWNER, 13, NOW, LEASE_DURATION));
        assertThat(session.ownershipValid()).isTrue();

        session.close();
        verify(scheduled).cancel(false);
        heartbeat.close();
        verify(scheduler).shutdownNow();
    }

    @Test
    void scheduledHeartbeatReportsLostOwnershipWithoutSettlingTheLease() {
        FakeQueue queue = new FakeQueue();
        queue.renewFailure = new IllegalStateException("stale lease");
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> scheduled = mock(ScheduledFuture.class);
        java.util.concurrent.atomic.AtomicReference<Runnable> heartbeatTask =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(scheduler.scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    heartbeatTask.set(invocation.getArgument(0));
                    return scheduled;
                });
        ScheduledLeaseHeartbeat heartbeat = new ScheduledLeaseHeartbeat(
                queue, CLOCK, LEASE_DURATION, Duration.ofSeconds(30), scheduler);

        ReviewJobWorker.LeaseHeartbeat.Session session = heartbeat.start(
                leased("REVIEW_EXECUTION", 14, 1), OWNER);
        heartbeatTask.get().run();

        assertThat(session.ownershipValid()).isFalse();
        session.close();
        heartbeat.close();
    }

    @Test
    void shutdownDuringAHandlerLeavesTheLeaseForSafeExpiryRecovery() throws Exception {
        FakeQueue queue = new FakeQueue();
        queue.leased = List.of(leased("REVIEW_EXECUTION", 12, 1));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReviewJobWorker worker = worker(queue, List.of(handler("REVIEW_EXECUTION", job -> {
                    entered.countDown();
                    await(release);
                    return ReviewJobHandler.JobOutcome.succeeded();
                })), zeroJitterBackoff(), new FakeHeartbeat(), new SimpleMeterRegistry());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var cycle = executor.submit(worker::runOnce);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            worker.shutdown();
            release.countDown();

            ReviewJobWorker.WorkerCycleResult result = cycle.get(5, TimeUnit.SECONDS);
            assertThat(queue.settlements).isEmpty();
            assertThat(result.abandonedOnShutdown()).isEqualTo(1);
            assertThat(worker.runOnce().leased()).isZero();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void workerSettingsRejectBusySpinAndUnsafeHeartbeatConfiguration() {
        assertThatThrownBy(() -> new ReviewJobWorker.WorkerSettings(
                OWNER, Duration.ZERO, Duration.ofSeconds(30), BATCH_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewJobWorker.WorkerSettings(
                OWNER, LEASE_DURATION, LEASE_DURATION, BATCH_SIZE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewJobWorker.WorkerSettings(
                OWNER, LEASE_DURATION, Duration.ofSeconds(30), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ReviewJobWorker worker(
            FakeQueue queue,
            List<ReviewJobHandler> handlers,
            BackoffPolicy backoff,
            FakeHeartbeat heartbeat,
            SimpleMeterRegistry metrics) {
        return new ReviewJobWorker(
                queue,
                new ReviewJobDispatcher(handlers),
                backoff,
                heartbeat,
                CLOCK,
                metrics,
                new ReviewJobWorker.WorkerSettings(
                        OWNER, LEASE_DURATION, Duration.ofSeconds(30), BATCH_SIZE));
    }

    private static BackoffPolicy zeroJitterBackoff() {
        return BackoffPolicy.exponential(
                Duration.ofSeconds(10), Duration.ofMinutes(1), 0.20, () -> 0.0);
    }

    private static LeasedJob leased(String type, int fence, int deliveryAttempt) {
        return new LeasedJob(
                UUID.nameUUIDFromBytes((type + fence).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                type,
                UUID.nameUUIDFromBytes(("payload" + type + fence)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                fence,
                deliveryAttempt,
                5,
                NOW.plus(LEASE_DURATION));
    }

    private static ReviewJobHandler handler(
            String jobType,
            Function<LeasedJob, ReviewJobHandler.JobOutcome> behavior) {
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }

    private static final class FakeHeartbeat implements ReviewJobWorker.LeaseHeartbeat {
        private final AtomicBoolean active = new AtomicBoolean();
        private LeasedJob startedJob;
        private String startedOwner;
        private boolean ownershipValid = true;
        private UUID closeFailureJobId;

        @Override
        public Session start(LeasedJob job, String owner) {
            startedJob = job;
            startedOwner = owner;
            active.set(true);
            return new Session() {
                @Override
                public boolean ownershipValid() {
                    return ownershipValid;
                }

                @Override
                public void close() {
                    active.set(false);
                    if (job.id().equals(closeFailureJobId)) {
                        throw new RuntimeException("unsafe heartbeat shutdown detail");
                    }
                }
            };
        }
    }

    private static final class FakeQueue implements DurableJobQueue {
        private final List<String> operations = new ArrayList<>();
        private final List<Settlement> settlements = new ArrayList<>();
        private final List<Renewal> renewals = new ArrayList<>();
        private List<LeasedJob> leased = List.of();
        private RuntimeException renewFailure;
        private UUID settlementFailureJobId;
        private int recovered;
        private String leaseOwner;
        private Instant leaseNow;
        private Duration leaseDuration;
        private int leaseLimit;

        @Override
        public UUID enqueue(DurableJobRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<LeasedJob> leaseDue(String owner, Instant now, Duration duration, int limit) {
            operations.add("lease");
            leaseOwner = owner;
            leaseNow = now;
            leaseDuration = duration;
            leaseLimit = limit;
            return leased;
        }

        @Override
        public void markSucceeded(UUID jobId, String owner, int expectedAttempt, Instant now) {
            failSettlementIfConfigured(jobId);
            settlements.add(new Settlement(jobId, owner, expectedAttempt, null, null, now));
        }

        @Override
        public void recordFailure(
                UUID jobId,
                String owner,
                int expectedAttempt,
                FailureClass failureClass,
                Instant nextAttemptAt,
                Instant now) {
            failSettlementIfConfigured(jobId);
            settlements.add(new Settlement(
                    jobId, owner, expectedAttempt, failureClass, nextAttemptAt, now));
        }

        @Override
        public void renewLease(
                UUID jobId,
                String owner,
                int expectedAttempt,
                Instant now,
                Duration leaseDuration) {
            if (renewFailure != null) {
                throw renewFailure;
            }
            renewals.add(new Renewal(jobId, owner, expectedAttempt, now, leaseDuration));
        }

        @Override
        public int recoverExpiredLeases(Instant now) {
            operations.add("recover");
            return recovered;
        }

        private void failSettlementIfConfigured(UUID jobId) {
            if (jobId.equals(settlementFailureJobId)) {
                throw new RuntimeException("unsafe settlement failure detail");
            }
        }
    }

    private record Settlement(
            UUID jobId,
            String owner,
            int fence,
            FailureClass failureClass,
            Instant nextAttemptAt,
            Instant settledAt) {
    }

    private record Renewal(
            UUID jobId,
            String owner,
            int fence,
            Instant renewedAt,
            Duration leaseDuration) {
    }
}
