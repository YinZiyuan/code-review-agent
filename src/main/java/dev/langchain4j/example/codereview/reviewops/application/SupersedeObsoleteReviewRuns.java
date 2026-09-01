package dev.langchain4j.example.codereview.reviewops.application;

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
    private final Clock clock;

    public SupersedeObsoleteReviewRuns(
            ReviewRunRepository reviewRuns,
            ObsoleteReviewRunStore obsoleteRuns,
            Clock clock) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
        this.obsoleteRuns = Objects.requireNonNull(obsoleteRuns, "obsoleteRuns");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SupersessionOutcome execute(ReviewRunId currentRunId) {
        Objects.requireNonNull(currentRunId, "currentRunId");
        ReviewRun current = reviewRuns.find(currentRunId)
                .map(ReviewRunRepository.StoredReviewRun::reviewRun)
                .orElse(null);
        if (current == null) {
            return SupersessionOutcome.notFound();
        }

        ObsoleteReviewRunStore.SupersessionScope scope =
                new ObsoleteReviewRunStore.SupersessionScope(
                        current.id(), current.revision(), current.requestedAt());
        List<ReviewRunId> candidates = List.copyOf(Objects.requireNonNull(
                obsoleteRuns.findActiveObsoleteRunIds(scope), "obsolete review run ids"));
        Instant supersededAt = clock.instant();
        int superseded = 0;
        for (ReviewRunId candidate : candidates) {
            Objects.requireNonNull(candidate, "obsolete review run id");
            if (obsoleteRuns.updateInOwnTransaction(candidate, obsolete -> {
                if (!stillActiveAndObsolete(obsolete, scope)) {
                    return false;
                }
                obsolete.supersede(
                        new AuthoritativeRevision(scope.currentRevision().headSha()),
                        supersededAt);
                return true;
            }) == ObsoleteReviewRunStore.UpdateResult.UPDATED) {
                superseded++;
            }
        }
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
        boolean active = candidate.state() == ReviewRunState.REQUESTED
                || candidate.state() == ReviewRunState.RUNNING
                || candidate.state() == ReviewRunState.COMPLETED
                || candidate.state() == ReviewRunState.PUBLISHING;
        return samePullRequest
                && active
                && !candidate.id().equals(scope.currentRunId())
                && !candidateRevision.headSha().equals(current.headSha())
                && candidate.requestedAt().isBefore(scope.currentRequestedAt());
    }

    public enum SupersessionStatus {
        COMPLETED,
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

        private static SupersessionOutcome notFound() {
            return new SupersessionOutcome(SupersessionStatus.NOT_FOUND, 0);
        }
    }
}
