package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.jobs.ExpiredJobLeaseRecovery;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class RecoverExpiredReviewExecution implements ExpiredJobLeaseRecovery {

    private final ReviewRunRepository reviewRuns;

    public RecoverExpiredReviewExecution(ReviewRunRepository reviewRuns) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
    }

    @Override
    public RecoveryAction recover(LeasedJob expiredLease, Instant recoveredAt) {
        Objects.requireNonNull(expiredLease, "expiredLease");
        Objects.requireNonNull(recoveredAt, "recoveredAt");
        if (!ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE.equals(expiredLease.jobType())) {
            return RecoveryAction.UNHANDLED;
        }

        Optional<ReviewRunRepository.StoredReviewRun> loaded = reviewRuns.find(
                new ReviewRunId(expiredLease.payloadReference()));
        if (loaded.isEmpty()) {
            return RecoveryAction.UNHANDLED;
        }

        ReviewRunRepository.StoredReviewRun stored = loaded.orElseThrow();
        ReviewRun run = stored.reviewRun();
        if (run.state() == ReviewRunState.RUNNING) {
            run.recoverInterruptedAttempt(new ReviewFailure(
                    "worker_interrupted",
                    FailureClass.TRANSIENT,
                    "interrupted execution details are not retained"), recoveredAt);
            reviewRuns.update(run, stored.version());
        }
        return switch (run.state()) {
            case REQUESTED -> RecoveryAction.RETRY_WITHOUT_CHARGE;
            case COMPLETED, PUBLISHING, PUBLISHED, FAILED, SUPERSEDED -> RecoveryAction.SUCCEEDED;
            case RUNNING -> throw new IllegalStateException("running attempt was not recovered");
        };
    }
}
