package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.FinalJobFailureSettlement;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Job-kind-aware final-delivery settlement. The caller owns the surrounding database
 * transaction so the returned follow-up intents can be inserted with this aggregate mutation.
 */
public final class SettleReviewJobFailure implements FinalJobFailureSettlement {

    private final ReviewRunRepository reviewRuns;

    public SettleReviewJobFailure(ReviewRunRepository reviewRuns) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
    }

    @Override
    public FinalJobFailureSettlement.FinalFailureSettlement settleFinalFailure(
            LeasedJob job,
            FailureClass deliveryFailureClass,
            String safeCode,
            Instant settledAt) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(deliveryFailureClass, "deliveryFailureClass");
        requireSafeCode(safeCode);
        Objects.requireNonNull(settledAt, "settledAt");

        ReviewRunRepository.StoredReviewRun stored = reviewRuns.find(
                new ReviewRunId(job.payloadReference())).orElse(null);
        if (stored == null || !knownReviewJob(job.jobType())) {
            return dead();
        }
        ReviewRun run = stored.reviewRun();
        if (effectIsAlreadyDurable(job.jobType(), run)) {
            return succeeded();
        }
        if (ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE.equals(job.jobType())) {
            return dead();
        }

        if (run.state() != ReviewRunState.FAILED) {
            run.recordJobSystemFailure(new ReviewFailure(
                    safeCode,
                    FailureClass.TERMINAL,
                    deliveryFailureClass == FailureClass.TRANSIENT
                            ? "review job attempts exhausted"
                            : "review job failed terminally"), settledAt);
            reviewRuns.update(run, stored.version());
        }
        return new FinalJobFailureSettlement.FinalFailureSettlement(
                DurableJobQueue.FailureDisposition.DEAD,
                List.of(failurePresentation(run, settledAt)));
    }

    private static boolean knownReviewJob(String jobType) {
        return ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE.equals(jobType)
                || ExecuteReviewRun.DECIDE_PUBLICATION_JOB_TYPE.equals(jobType)
                || DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE.equals(jobType)
                || SupersedeObsoleteReviewRuns.JOB_TYPE.equals(jobType)
                || ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE.equals(jobType);
    }

    private static boolean effectIsAlreadyDurable(String jobType, ReviewRun run) {
        ReviewRunState state = run.state();
        if (ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE.equals(jobType)) {
            return state == ReviewRunState.COMPLETED
                    || state == ReviewRunState.PUBLISHING
                    || state == ReviewRunState.PUBLISHED
                    || state == ReviewRunState.SUPERSEDED;
        }
        if (ExecuteReviewRun.DECIDE_PUBLICATION_JOB_TYPE.equals(jobType)) {
            return (state == ReviewRunState.COMPLETED
                    && run.findings().stream()
                            .allMatch(finding -> finding.publicationDecision().isPresent()))
                    || state == ReviewRunState.PUBLISHING
                    || state == ReviewRunState.PUBLISHED
                    || state == ReviewRunState.SUPERSEDED;
        }
        if (DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE.equals(jobType)) {
            return state == ReviewRunState.PUBLISHED || state == ReviewRunState.SUPERSEDED;
        }
        if (SupersedeObsoleteReviewRuns.JOB_TYPE.equals(jobType)) {
            return state == ReviewRunState.PUBLISHED
                    || state == ReviewRunState.FAILED
                    || state == ReviewRunState.SUPERSEDED;
        }
        return false;
    }

    private static DurableJobRequest failurePresentation(ReviewRun run, Instant nextAttemptAt) {
        return new DurableJobRequest(
                ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE,
                run.id().value(),
                run.configuration().maxReviewAttempts(),
                nextAttemptAt,
                "present-review-failure:" + run.id().value());
    }

    private static void requireSafeCode(String safeCode) {
        if (safeCode == null || safeCode.isBlank() || safeCode.length() > 128
                || !safeCode.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("safeCode must be a bounded reason code");
        }
    }

    private static FinalJobFailureSettlement.FinalFailureSettlement dead() {
        return new FinalJobFailureSettlement.FinalFailureSettlement(
                DurableJobQueue.FailureDisposition.DEAD, List.of());
    }

    private static FinalJobFailureSettlement.FinalFailureSettlement succeeded() {
        return new FinalJobFailureSettlement.FinalFailureSettlement(
                DurableJobQueue.FailureDisposition.SUCCEEDED, List.of());
    }
}
