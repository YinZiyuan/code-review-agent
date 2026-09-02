package dev.langchain4j.example.codereview.reviewops.application.jobs;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@FunctionalInterface
public interface ExpiredJobLeaseRecovery {

    /**
     * Reconciles an expired lease after its delivery budget is exhausted.
     * The queue invokes this callback in the same transaction that settles the job.
     */
    RecoveryAction recover(LeasedJob expiredLease, Instant recoveredAt);

    default RecoverySettlement recoverWithIntents(
            LeasedJob expiredLease, Instant recoveredAt) {
        return new RecoverySettlement(recover(expiredLease, recoveredAt), List.of());
    }

    enum RecoveryAction {
        RETRY_WITHOUT_CHARGE,
        SUCCEEDED,
        UNHANDLED
    }

    record RecoverySettlement(
            RecoveryAction action,
            List<DurableJobRequest> followUpJobs) {

        public RecoverySettlement {
            Objects.requireNonNull(action, "action");
            followUpJobs = List.copyOf(Objects.requireNonNull(followUpJobs, "followUpJobs"));
        }
    }
}
