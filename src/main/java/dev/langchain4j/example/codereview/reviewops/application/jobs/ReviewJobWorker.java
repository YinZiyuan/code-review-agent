package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReviewJobWorker {

    private static final String JOB_METRIC = "code.review.jobs";

    private final DurableJobQueue queue;
    private final ReviewJobDispatcher dispatcher;
    private final BackoffPolicy backoff;
    private final LeaseHeartbeat heartbeat;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final WorkerSettings settings;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public ReviewJobWorker(
            DurableJobQueue queue,
            ReviewJobDispatcher dispatcher,
            BackoffPolicy backoff,
            LeaseHeartbeat heartbeat,
            Clock clock,
            MeterRegistry metrics,
            WorkerSettings settings) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.backoff = Objects.requireNonNull(backoff, "backoff");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public WorkerCycleResult runOnce() {
        if (shutdown.get()) {
            return WorkerCycleResult.empty();
        }
        Instant recoveryTime = clock.instant();
        int recovered = queue.recoverExpiredLeases(
                recoveryTime, settings.recoveryBatchSize());
        if (shutdown.get()) {
            return new WorkerCycleResult(recovered, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        Instant leaseTime = clock.instant();
        List<LeasedJob> leased = List.copyOf(queue.leaseDue(
                settings.owner(), leaseTime, settings.leaseDuration(), settings.batchSize()));
        CycleCounts counts = new CycleCounts(recovered, leased.size());
        List<ActiveLease> activeLeases = startHeartbeats(leased, counts);
        for (int index = 0; index < activeLeases.size(); index++) {
            if (shutdown.get()) {
                counts.abandonedOnShutdown += activeLeases.size() - index;
                closeRemaining(activeLeases, index);
                break;
            }
            process(activeLeases.get(index), counts);
        }
        return counts.result();
    }

    public void shutdown() {
        shutdown.set(true);
    }

    private List<ActiveLease> startHeartbeats(List<LeasedJob> leased, CycleCounts counts) {
        List<ActiveLease> active = new ArrayList<>(leased.size());
        for (int index = 0; index < leased.size(); index++) {
            if (shutdown.get()) {
                counts.abandonedOnShutdown += leased.size() - index;
                break;
            }
            LeasedJob job = leased.get(index);
            try {
                LeaseHeartbeat.Session session = Objects.requireNonNull(
                        heartbeat.start(job, settings.owner()), "heartbeat session");
                active.add(new ActiveLease(job, session));
            } catch (RuntimeException failure) {
                counts.ownershipLost++;
                recordMetric(job, "ownership_lost");
            }
        }
        return List.copyOf(active);
    }

    private void process(ActiveLease activeLease, CycleCounts counts) {
        LeasedJob job = activeLease.job();
        LeaseHeartbeat.Session session = activeLease.session();
        if (!renewOwnership(session)) {
            closeIgnoringFailure(session);
            counts.ownershipLost++;
            recordMetric(job, "ownership_lost");
            return;
        }

        ReviewJobHandler.JobOutcome outcome;
        try {
            outcome = dispatcher.dispatch(job);
        } catch (RuntimeException failure) {
            outcome = classify(failure);
        }
        boolean ownershipValid;
        try {
            ownershipValid = session.ownershipValid();
        } catch (RuntimeException heartbeatFailure) {
            ownershipValid = false;
        }
        try {
            session.close();
        } catch (RuntimeException heartbeatFailure) {
            ownershipValid = false;
        }

        if (shutdown.get()) {
            counts.abandonedOnShutdown++;
            recordMetric(job, "shutdown");
            return;
        }
        if (!ownershipValid) {
            counts.ownershipLost++;
            recordMetric(job, "ownership_lost");
            return;
        }

        Instant settledAt = clock.instant();
        try {
            switch (outcome.status()) {
                case SUCCEEDED -> {
                    queue.markSucceeded(
                            job.id(), settings.owner(), job.attemptCount(), settledAt);
                    counts.succeeded++;
                    recordMetric(job, "succeeded");
                }
                case TRANSIENT_FAILURE -> {
                    Instant retryAt = backoff.nextAttemptAt(settledAt, job.deliveryAttempt());
                    queue.recordFailure(
                            job.id(), settings.owner(), job.attemptCount(), FailureClass.TRANSIENT,
                            retryAt, settledAt);
                    counts.retried++;
                    recordMetric(job, "retried");
                }
                case RATE_LIMITED -> {
                    Instant retryAt = usableRetryAt(outcome.retryAt(), settledAt)
                            .orElseGet(() -> backoff.nextAttemptAt(
                                    settledAt, job.deliveryAttempt()));
                    queue.recordFailure(
                            job.id(), settings.owner(), job.attemptCount(), FailureClass.TRANSIENT,
                            retryAt, settledAt);
                    counts.rateLimited++;
                    recordMetric(job, "rate_limited");
                }
                case TERMINAL_FAILURE -> {
                    queue.recordFailure(
                            job.id(), settings.owner(), job.attemptCount(), FailureClass.TERMINAL,
                            settledAt, settledAt);
                    counts.dead++;
                    recordMetric(job, "dead");
                }
            }
        } catch (IllegalStateException staleLease) {
            counts.ownershipLost++;
            recordMetric(job, "ownership_lost");
        } catch (RuntimeException settlementFailure) {
            counts.settlementFailed++;
            recordMetric(job, "settlement_failed");
        }
    }

    private static boolean renewOwnership(LeaseHeartbeat.Session session) {
        try {
            return session.renewNow();
        } catch (RuntimeException heartbeatFailure) {
            return false;
        }
    }

    private static void closeRemaining(List<ActiveLease> activeLeases, int first) {
        for (int index = first; index < activeLeases.size(); index++) {
            closeIgnoringFailure(activeLeases.get(index).session());
        }
    }

    private static void closeIgnoringFailure(LeaseHeartbeat.Session session) {
        try {
            session.close();
        } catch (RuntimeException ignored) {
            // The lease remains recoverable; shutdown and lost-ownership paths must continue.
        }
    }

    private ReviewJobHandler.JobOutcome classify(RuntimeException failure) {
        GitHubFailureException github = findCause(failure, GitHubFailureException.class);
        if (github != null) {
            return switch (github.classification()) {
                case TRANSIENT -> ReviewJobHandler.JobOutcome.transientFailure("github_transient");
                case RATE_LIMITED -> ReviewJobHandler.JobOutcome.rateLimited(
                        "github_rate_limited", github.retryAt().orElse(null));
                case AUTHORIZATION -> ReviewJobHandler.JobOutcome.terminalFailure(
                        "github_authorization");
                case DETERMINISTIC_INPUT -> ReviewJobHandler.JobOutcome.terminalFailure(
                        "github_deterministic_input");
            };
        }
        if (findCause(failure, IllegalArgumentException.class) != null
                || findCause(failure, NullPointerException.class) != null) {
            return ReviewJobHandler.JobOutcome.terminalFailure("malformed_job");
        }
        return ReviewJobHandler.JobOutcome.transientFailure("job_handler_failed");
    }

    private void recordMetric(LeasedJob job, String outcome) {
        String jobType = dispatcher.handles(job.jobType()) ? job.jobType() : "UNKNOWN";
        metrics.counter(
                JOB_METRIC,
                "job.type", jobType,
                "outcome", outcome).increment();
    }

    private static Optional<Instant> usableRetryAt(Optional<Instant> retryAt, Instant now) {
        return retryAt.filter(candidate -> candidate.isAfter(now));
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }

    public record WorkerSettings(
            String owner,
            Duration leaseDuration,
            Duration heartbeatInterval,
            int batchSize,
            int recoveryBatchSize) {

        public WorkerSettings(
                String owner,
                Duration leaseDuration,
                Duration heartbeatInterval,
                int batchSize) {
            this(owner, leaseDuration, heartbeatInterval, batchSize, batchSize);
        }

        public WorkerSettings {
            owner = requireNonBlank(owner, "owner");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
            if (leaseDuration.isZero() || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("leaseDuration must be positive");
            }
            if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
                throw new IllegalArgumentException("heartbeatInterval must be positive");
            }
            if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
                throw new IllegalArgumentException("heartbeatInterval must be less than leaseDuration");
            }
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize must be positive");
            }
            if (recoveryBatchSize <= 0) {
                throw new IllegalArgumentException("recoveryBatchSize must be positive");
            }
        }

        private static String requireNonBlank(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }

    public record WorkerCycleResult(
            int recovered,
            int leased,
            int succeeded,
            int retried,
            int rateLimited,
            int dead,
            int ownershipLost,
            int settlementFailed,
            int abandonedOnShutdown) {

        private static WorkerCycleResult empty() {
            return new WorkerCycleResult(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    @FunctionalInterface
    public interface LeaseHeartbeat {
        Session start(LeasedJob job, String owner);

        interface Session extends AutoCloseable {
            boolean renewNow();

            boolean ownershipValid();

            @Override
            void close();
        }
    }

    private record ActiveLease(LeasedJob job, LeaseHeartbeat.Session session) {

        private ActiveLease {
            Objects.requireNonNull(job, "job");
            Objects.requireNonNull(session, "session");
        }
    }

    private static final class CycleCounts {
        private final int recovered;
        private final int leased;
        private int succeeded;
        private int retried;
        private int rateLimited;
        private int dead;
        private int ownershipLost;
        private int settlementFailed;
        private int abandonedOnShutdown;

        private CycleCounts(int recovered, int leased) {
            this.recovered = recovered;
            this.leased = leased;
        }

        private WorkerCycleResult result() {
            return new WorkerCycleResult(
                    recovered,
                    leased,
                    succeeded,
                    retried,
                    rateLimited,
                    dead,
                    ownershipLost,
                    settlementFailed,
                    abandonedOnShutdown);
        }
    }
}
