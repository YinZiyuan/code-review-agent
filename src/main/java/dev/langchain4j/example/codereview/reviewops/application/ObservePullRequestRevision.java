package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationRequest;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationResult;
import dev.langchain4j.example.codereview.reviewops.application.github.VerifiedPullRequestEvent;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class ObservePullRequestRevision {

    private static final String PULL_REQUEST_EVENT = "pull_request";
    private static final String SUPERSESSION_JOB_TYPE = "SUPERSEDE_OBSOLETE_RUNS";
    private static final int FINAL_ATTEMPT_RECOVERY_DISPATCHES = 1;

    private final PullRequestObservationStore observations;
    private final Clock clock;
    private final ReviewConfigurationSnapshot configuration;

    public ObservePullRequestRevision(
            PullRequestObservationStore observations,
            Clock clock,
            ReviewConfigurationSnapshot configuration) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public ObservationResult observe(VerifiedPullRequestEvent event, String payloadSha256) {
        Objects.requireNonNull(event, "event");
        Instant admittedAt = clock.instant();
        ReviewRunId reviewRunId = ReviewRunId.newId();
        ReviewRun reviewRun = ReviewRun.request(
                reviewRunId,
                new PullRequestRevision(
                        event.installationId(),
                        event.repositoryId(),
                        event.pullRequestNumber(),
                        event.headSha()),
                configuration,
                admittedAt);
        DurableJobRequest executionJob = new DurableJobRequest(
                ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE,
                reviewRunId.value(),
                executionDispatchAllowance(configuration.maxReviewAttempts()),
                admittedAt,
                "review-execution:" + reviewRunId.value());
        DurableJobRequest supersessionJob = new DurableJobRequest(
                SUPERSESSION_JOB_TYPE,
                reviewRunId.value(),
                configuration.maxReviewAttempts(),
                admittedAt,
                "supersede-obsolete-runs:" + reviewRunId.value());
        return observations.admit(new ObservationRequest(
                event.deliveryId(),
                PULL_REQUEST_EVENT,
                payloadSha256,
                event.observedAt(),
                reviewRun,
                executionJob,
                supersessionJob));
    }

    private static int executionDispatchAllowance(int reviewAttemptAllowance) {
        return Math.addExact(reviewAttemptAllowance, FINAL_ATTEMPT_RECOVERY_DISPATCHES);
    }
}
