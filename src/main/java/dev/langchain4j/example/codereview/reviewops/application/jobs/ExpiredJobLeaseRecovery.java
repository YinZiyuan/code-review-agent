package dev.langchain4j.example.codereview.reviewops.application.jobs;

import java.time.Instant;

@FunctionalInterface
public interface ExpiredJobLeaseRecovery {

    /**
     * Reconciles an expired lease after its delivery budget is exhausted.
     * The queue invokes this callback in the same transaction that settles the job.
     */
    RecoveryAction recover(LeasedJob expiredLease, Instant recoveredAt);

    enum RecoveryAction {
        RETRY_WITHOUT_CHARGE,
        SUCCEEDED,
        UNHANDLED
    }
}
