package dev.langchain4j.example.codereview.reviewops.application.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.DoubleSupplier;

@FunctionalInterface
public interface BackoffPolicy {

    Instant nextAttemptAt(Instant failedAt, int deliveryAttempt);

    static BackoffPolicy exponential(
            Duration initialDelay,
            Duration maximumDelay,
            double jitterRatio,
            DoubleSupplier random) {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maximumDelay, "maximumDelay");
        Objects.requireNonNull(random, "random");
        if (initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maximumDelay must not be less than initialDelay");
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0.0 || jitterRatio > 1.0) {
            throw new IllegalArgumentException("jitterRatio must be between zero and one");
        }
        long initialNanos = initialDelay.toNanos();
        long maximumNanos = maximumDelay.toNanos();
        return (failedAt, deliveryAttempt) -> {
            Objects.requireNonNull(failedAt, "failedAt");
            if (deliveryAttempt <= 0) {
                throw new IllegalArgumentException("deliveryAttempt must be positive");
            }
            long delayNanos = initialNanos;
            for (int attempt = 1; attempt < deliveryAttempt && delayNanos < maximumNanos; attempt++) {
                delayNanos = delayNanos > maximumNanos / 2
                        ? maximumNanos
                        : Math.min(maximumNanos, delayNanos * 2);
            }
            double randomUnit = random.getAsDouble();
            if (!Double.isFinite(randomUnit) || randomUnit < 0.0 || randomUnit > 1.0) {
                throw new IllegalStateException("backoff random value must be between zero and one");
            }
            long availableJitter = maximumNanos - delayNanos;
            long requestedJitter = (long) Math.floor(delayNanos * jitterRatio * randomUnit);
            long jitterNanos = Math.min(availableJitter, requestedJitter);
            return failedAt.plusNanos(delayNanos + jitterNanos);
        };
    }
}
