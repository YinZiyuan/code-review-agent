package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.jobs.OperationFence;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class SupersedeObsoleteReviewRuns {

    public static final String JOB_TYPE = "SUPERSEDE_OBSOLETE_RUNS";

    private final ReviewRunRepository reviewRuns;
    private final ObsoleteReviewRunStore obsoleteRuns;
    private final GitHubPublicationGateway github;
    private final Clock clock;
    private final ReviewOperationsTelemetry telemetry;

    public SupersedeObsoleteReviewRuns(
            ReviewRunRepository reviewRuns,
            ObsoleteReviewRunStore obsoleteRuns,
            GitHubPublicationGateway github,
            Clock clock) {
        this(reviewRuns, obsoleteRuns, github, clock, ReviewOperationsTelemetry.NOOP);
    }

    public SupersedeObsoleteReviewRuns(
            ReviewRunRepository reviewRuns,
            ObsoleteReviewRunStore obsoleteRuns,
            GitHubPublicationGateway github,
            Clock clock,
            ReviewOperationsTelemetry telemetry) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
        this.obsoleteRuns = Objects.requireNonNull(obsoleteRuns, "obsoleteRuns");
        this.github = Objects.requireNonNull(github, "github");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public SupersessionOutcome execute(ReviewRunId currentRunId) {
        return execute(currentRunId, OperationFence.unfenced());
    }

    public SupersessionOutcome execute(ReviewRunId currentRunId, OperationFence fence) {
        Objects.requireNonNull(currentRunId, "currentRunId");
        Objects.requireNonNull(fence, "fence");
        ReviewRun current = reviewRuns.find(currentRunId)
                .map(ReviewRunRepository.StoredReviewRun::reviewRun).orElse(null);
        if (current == null) {
            return SupersessionOutcome.notFound();
        }

        AuthoritativeRevision authoritative = github.authoritativeRevision(
                current.revision(), fence);
        Instant supersededAt = clock.instant();
        if (!authoritative.headSha().equals(current.revision().headSha())) {
            ObsoleteReviewRunStore.UpdateResult result = obsoleteRuns.updateInOwnTransaction(
                    current.id(), source -> {
                        if (!isActive(source)
                                || authoritative.headSha().equals(source.revision().headSha())) {
                            return false;
                        }
                        fence.requireCurrent();
                        source.supersede(authoritative, supersededAt);
                        return true;
                    });
            int superseded = result == ObsoleteReviewRunStore.UpdateResult.UPDATED ? 1 : 0;
            if (superseded == 1) {
                telemetry.lifecycle(
                        ReviewOperationsTelemetry.LifecycleOutcome.SUPERSEDED, 1);
                telemetry.preventedStale(
                        ReviewOperationsTelemetry.StaleStage.SUPERSESSION_SOURCE);
            }
            return SupersessionOutcome.staleSource(superseded);
        }

        ObsoleteReviewRunStore.SupersessionScope scope =
                new ObsoleteReviewRunStore.SupersessionScope(
                        current.id(), current.revision());
        List<ReviewRunId> candidates = List.copyOf(Objects.requireNonNull(
                obsoleteRuns.findActiveObsoleteRunIds(scope), "obsolete review run ids"));
        int superseded = 0;
        for (ReviewRunId candidate : candidates) {
            Objects.requireNonNull(candidate, "obsolete review run id");
            if (obsoleteRuns.updateInOwnTransaction(candidate, obsolete -> {
                if (!stillActiveAndObsolete(obsolete, scope)) {
                    return false;
                }
                fence.requireCurrent();
                obsolete.supersede(
                        new AuthoritativeRevision(scope.currentRevision().headSha()),
                        supersededAt);
                return true;
            }) == ObsoleteReviewRunStore.UpdateResult.UPDATED) {
                superseded++;
            }
        }
        telemetry.lifecycle(
                ReviewOperationsTelemetry.LifecycleOutcome.SUPERSEDED, superseded);
        return SupersessionOutcome.completed(superseded);
    }

    private static boolean stillActiveAndObsolete(
            ReviewRun candidate,
            ObsoleteReviewRunStore.SupersessionScope scope) {
        PullRequestRevision candidateRevision = candidate.revision();
        PullRequestRevision current = scope.currentRevision();
        boolean samePullRequest = candidateRevision.installationId() == current.installationId()
                && candidateRevision.repositoryId() == current.repositoryId()
                && candidateRevision.pullRequestNumber() == current.pullRequestNumber();
        boolean active = isActive(candidate);
        return samePullRequest
                && active
                && !candidate.id().equals(scope.currentRunId())
                && !candidateRevision.headSha().equals(current.headSha());
    }

    private static boolean isActive(ReviewRun candidate) {
        return candidate.state() == ReviewRunState.REQUESTED
                || candidate.state() == ReviewRunState.RUNNING
                || candidate.state() == ReviewRunState.COMPLETED
                || candidate.state() == ReviewRunState.PUBLISHING;
    }

    public enum SupersessionStatus {
        COMPLETED,
        STALE_SOURCE,
        NOT_FOUND
    }

    public record SupersessionOutcome(SupersessionStatus status, int supersededCount) {

        public SupersessionOutcome {
            Objects.requireNonNull(status, "status");
            if (supersededCount < 0) {
                throw new IllegalArgumentException("supersededCount must be non-negative");
            }
            if (status == SupersessionStatus.NOT_FOUND && supersededCount != 0) {
                throw new IllegalArgumentException("missing source cannot supersede review runs");
            }
        }

        private static SupersessionOutcome completed(int supersededCount) {
            return new SupersessionOutcome(SupersessionStatus.COMPLETED, supersededCount);
        }

        private static SupersessionOutcome staleSource(int supersededCount) {
            return new SupersessionOutcome(SupersessionStatus.STALE_SOURCE, supersededCount);
        }

        private static SupersessionOutcome notFound() {
            return new SupersessionOutcome(SupersessionStatus.NOT_FOUND, 0);
        }
    }
}
