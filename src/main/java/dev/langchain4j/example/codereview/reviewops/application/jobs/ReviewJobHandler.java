package dev.langchain4j.example.codereview.reviewops.application.jobs;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public interface ReviewJobHandler {

    String jobType();

    JobOutcome handle(LeasedJob job);

    default JobOutcome handle(LeasedJob job, OperationFence fence) {
        Objects.requireNonNull(fence, "fence").requireCurrent();
        return handle(job);
    }

    enum JobStatus {
        SUCCEEDED,
        TRANSIENT_FAILURE,
        RATE_LIMITED,
        TERMINAL_FAILURE
    }

    record JobOutcome(JobStatus status, String safeCode, Optional<Instant> retryAt) {

        public JobOutcome {
            Objects.requireNonNull(status, "status");
            safeCode = requireNonBlank(safeCode, "safeCode");
            retryAt = retryAt == null ? Optional.empty() : retryAt;
        }

        public static JobOutcome succeeded() {
            return new JobOutcome(JobStatus.SUCCEEDED, "succeeded", Optional.empty());
        }

        public static JobOutcome terminalFailure(String safeCode) {
            return new JobOutcome(JobStatus.TERMINAL_FAILURE, safeCode, Optional.empty());
        }

        public static JobOutcome transientFailure(String safeCode) {
            return new JobOutcome(JobStatus.TRANSIENT_FAILURE, safeCode, Optional.empty());
        }

        public static JobOutcome rateLimited(String safeCode, Instant retryAt) {
            return new JobOutcome(JobStatus.RATE_LIMITED, safeCode, Optional.ofNullable(retryAt));
        }

        private static String requireNonBlank(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
