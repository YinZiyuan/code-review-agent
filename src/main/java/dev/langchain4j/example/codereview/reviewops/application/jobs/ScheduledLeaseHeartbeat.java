package dev.langchain4j.example.codereview.reviewops.application.jobs;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScheduledLeaseHeartbeat implements ReviewJobWorker.LeaseHeartbeat, AutoCloseable {

    private final DurableJobQueue queue;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ScheduledLeaseHeartbeat(
            DurableJobQueue queue,
            Clock clock,
            Duration leaseDuration,
            Duration interval,
            ScheduledExecutorService scheduler) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = requirePositive(leaseDuration, "leaseDuration");
        this.interval = requirePositive(interval, "interval");
        if (interval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("interval must be less than leaseDuration");
        }
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public Session start(LeasedJob job, String owner) {
        Objects.requireNonNull(job, "job");
        owner = requireNonBlank(owner, "owner");
        if (closed.get()) {
            throw new IllegalStateException("lease heartbeat is shut down");
        }
        HeartbeatState state = new HeartbeatState(job, owner);
        if (!state.renewNow()) {
            throw new IllegalStateException("lease ownership could not be confirmed");
        }
        long intervalNanos = interval.toNanos();
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                state::renewNow, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
        return new HeartbeatSession(state, future);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            scheduler.shutdownNow();
        }
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private final class HeartbeatState {
        private final LeasedJob job;
        private final String owner;
        private final AtomicBoolean ownershipValid = new AtomicBoolean(true);
        private final AtomicBoolean stopped = new AtomicBoolean();

        private HeartbeatState(LeasedJob job, String owner) {
            this.job = job;
            this.owner = owner;
        }

        private boolean renewNow() {
            if (!ownershipValid.get() || stopped.get() || closed.get()) {
                return false;
            }
            try {
                queue.renewLease(
                        job.id(), owner, job.attemptCount(), clock.instant(), leaseDuration);
                return true;
            } catch (RuntimeException lostOwnership) {
                ownershipValid.set(false);
                return false;
            }
        }

        private void stop() {
            stopped.set(true);
        }
    }

    private static final class HeartbeatSession implements Session {
        private final HeartbeatState state;
        private final ScheduledFuture<?> future;
        private final AtomicBoolean closed = new AtomicBoolean();

        private HeartbeatSession(
                HeartbeatState state,
                ScheduledFuture<?> future) {
            this.state = Objects.requireNonNull(state, "state");
            this.future = Objects.requireNonNull(future, "future");
        }

        @Override
        public boolean renewNow() {
            return state.renewNow();
        }

        @Override
        public boolean ownershipValid() {
            return state.ownershipValid.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                state.stop();
                future.cancel(false);
            }
        }
    }
}
