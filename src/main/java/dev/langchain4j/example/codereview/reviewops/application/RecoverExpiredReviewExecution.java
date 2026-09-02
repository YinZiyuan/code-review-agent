package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.jobs.ExpiredJobLeaseRecovery;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
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
import java.util.List;

public final class RecoverExpiredReviewExecution implements ExpiredJobLeaseRecovery {

    private final ReviewRunRepository reviewRuns;

    public RecoverExpiredReviewExecution(ReviewRunRepository reviewRuns) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
    }

    @Override
    public RecoveryAction recover(LeasedJob expiredLease, Instant recoveredAt) {
        return recoverWithIntents(expiredLease, recoveredAt).action();
    }

    @Override
    public RecoverySettlement recoverWithIntents(
            LeasedJob expiredLease, Instant recoveredAt) {
        Objects.requireNonNull(expiredLease, "expiredLease");
        Objects.requireNonNull(recoveredAt, "recoveredAt");
        if (!knownReviewJob(expiredLease.jobType())) {
            return settlement(RecoveryAction.UNHANDLED);
        }

        Optional<ReviewRunRepository.StoredReviewRun> loaded = reviewRuns.find(
                new ReviewRunId(expiredLease.payloadReference()));
        if (loaded.isEmpty()) {
            return settlement(RecoveryAction.UNHANDLED);
        }

        ReviewRunRepository.StoredReviewRun stored = loaded.orElseThrow();
        ReviewRun run = stored.reviewRun();
        if (ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE.equals(expiredLease.jobType())
                && run.state() == ReviewRunState.RUNNING) {
            run.recoverInterruptedAttempt(new ReviewFailure(
                    "worker_interrupted",
                    FailureClass.TRANSIENT,
                    "interrupted execution details are not retained"), recoveredAt);
            reviewRuns.update(run, stored.version());
        }
        if (run.state() == ReviewRunState.FAILED) {
            List<DurableJobRequest> followUps =
                    ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE.equals(expiredLease.jobType())
                            || run.checkRunExternalId().isPresent()
                            ? List.of()
                            : List.of(failurePresentation(run, recoveredAt));
            RecoveryAction action =
                    ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE.equals(expiredLease.jobType())
                            && run.checkRunExternalId().isEmpty()
                            ? RecoveryAction.RETRY_WITHOUT_CHARGE
                            : RecoveryAction.SUCCEEDED;
            return new RecoverySettlement(action, followUps);
        }
        if (run.state() == ReviewRunState.PUBLISHED
                || run.state() == ReviewRunState.SUPERSEDED) {
            return settlement(RecoveryAction.SUCCEEDED);
        }
        if (ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE.equals(expiredLease.jobType())) {
            return settlement(RecoveryAction.SUCCEEDED);
        }
        if (run.state() == ReviewRunState.RUNNING
                && ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE.equals(
                        expiredLease.jobType())) {
            throw new IllegalStateException("running attempt was not recovered");
        }
        return settlement(RecoveryAction.RETRY_WITHOUT_CHARGE);
    }

    private static boolean knownReviewJob(String jobType) {
        return ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE.equals(jobType)
                || ExecuteReviewRun.DECIDE_PUBLICATION_JOB_TYPE.equals(jobType)
                || DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE.equals(jobType)
                || SupersedeObsoleteReviewRuns.JOB_TYPE.equals(jobType)
                || ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE.equals(jobType);
    }

    private static DurableJobRequest failurePresentation(ReviewRun run, Instant recoveredAt) {
        return new DurableJobRequest(
                ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE,
                run.id().value(),
                run.configuration().maxReviewAttempts(),
                recoveredAt,
                "present-review-failure:" + run.id().value());
    }

    private static RecoverySettlement settlement(RecoveryAction action) {
        return new RecoverySettlement(action, List.of());
    }
}
