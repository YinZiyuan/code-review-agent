package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;
import java.util.Optional;

public final class ReviewAttempt {
    private final int attemptNumber;
    private final Instant startedAt;
    private ReviewAttemptState state;
    private Instant endedAt;
    private ExecutionMeasurements measurements;
    private ReviewFailure failure;

    private ReviewAttempt(int attemptNumber, Instant startedAt) {
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        this.attemptNumber = attemptNumber;
        this.startedAt = java.util.Objects.requireNonNull(startedAt, "startedAt");
        this.state = ReviewAttemptState.STARTED;
    }

    static ReviewAttempt start(int attemptNumber, Instant startedAt) {
        return new ReviewAttempt(attemptNumber, startedAt);
    }

    void succeed(ExecutionMeasurements measurements, Instant endedAt) {
        finish(ReviewAttemptState.SUCCEEDED, measurements, null, endedAt);
    }

    void failTransient(ReviewFailure failure, ExecutionMeasurements measurements, Instant endedAt) {
        requireFailureClass(failure, FailureClass.TRANSIENT);
        finish(ReviewAttemptState.TRANSIENT_FAILURE, measurements, failure, endedAt);
    }

    void failTerminal(ReviewFailure failure, ExecutionMeasurements measurements, Instant endedAt) {
        requireFailureClass(failure, FailureClass.TERMINAL);
        finish(ReviewAttemptState.TERMINAL_FAILURE, measurements, failure, endedAt);
    }

    private void finish(ReviewAttemptState next, ExecutionMeasurements measurements,
                        ReviewFailure failure, Instant endedAt) {
        if (state != ReviewAttemptState.STARTED) throw new IllegalStateException("attempt is terminal");
        this.measurements = java.util.Objects.requireNonNull(measurements, "measurements");
        this.endedAt = java.util.Objects.requireNonNull(endedAt, "endedAt");
        if (endedAt.isBefore(startedAt)) throw new IllegalArgumentException("endedAt precedes startedAt");
        this.failure = failure;
        this.state = next;
    }

    private static void requireFailureClass(ReviewFailure failure, FailureClass expected) {
        if (failure == null || failure.classification() != expected) {
            throw new IllegalArgumentException("failure classification must be " + expected);
        }
    }

    public int attemptNumber() { return attemptNumber; }
    public Instant startedAt() { return startedAt; }
    public ReviewAttemptState state() { return state; }
    public Optional<Instant> endedAt() { return Optional.ofNullable(endedAt); }
    public Optional<ExecutionMeasurements> measurements() { return Optional.ofNullable(measurements); }
    public Optional<ReviewFailure> failure() { return Optional.ofNullable(failure); }
}
