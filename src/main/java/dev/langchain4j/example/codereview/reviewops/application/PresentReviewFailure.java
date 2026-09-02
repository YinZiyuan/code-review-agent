package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.jobs.OperationFence;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationReference;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reconciles the developer-visible, non-blocking presentation of a terminal review failure. */
public final class PresentReviewFailure {

    private final ReviewRunRepository reviewRuns;
    private final ReviewRunMutationStore mutations;
    private final GitHubPublicationGateway github;
    @SuppressWarnings("unused")
    private final Clock clock;
    private final ReviewOperationsTelemetry telemetry;

    public PresentReviewFailure(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            GitHubPublicationGateway github,
            Clock clock) {
        this(reviewRuns, mutations, github, clock, ReviewOperationsTelemetry.NOOP);
    }

    public PresentReviewFailure(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            GitHubPublicationGateway github,
            Clock clock,
            ReviewOperationsTelemetry telemetry) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.github = Objects.requireNonNull(github, "github");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    public PresentationOutcome present(ReviewRunId id) {
        return present(id, OperationFence.unfenced());
    }

    public PresentationOutcome present(ReviewRunId id, OperationFence fence) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fence, "fence");
        ReviewRunRepository.StoredReviewRun stored = reviewRuns.find(id).orElse(null);
        if (stored == null) {
            return PresentationOutcome.NOT_FOUND;
        }
        ReviewRun run = stored.reviewRun();
        if (run.state() != ReviewRunState.FAILED) {
            return PresentationOutcome.ALREADY_PROCESSED;
        }

        AuthoritativeRevision authoritative = Objects.requireNonNull(
                github.authoritativeRevision(run.revision()), "authoritative revision");
        if (!authoritative.matches(run.revision())) {
            telemetry.preventedStale(
                    ReviewOperationsTelemetry.StaleStage.FAILURE_PRESENTATION);
            return PresentationOutcome.STALE;
        }

        ReviewFailure failure = run.finalFailure().orElseThrow();
        long version = stored.version();
        boolean commentsMayRemain = !run.commentReferences().isEmpty();
        if (commentsMayRemain) {
            try {
                Set<FindingFingerprint> retracted = retractConfirmedComments(run, fence);
                run.recordFailurePresentationCommentRetractions(retracted);
                fence.requireCurrent();
                version = mutations.saveProgress(run, version);
                commentsMayRemain = false;
            } catch (GitHubFailureException cleanupFailure) {
                if (isRetryable(cleanupFailure)) {
                    throw cleanupFailure;
                }
            }
        }
        CheckRunArtifact check = Objects.requireNonNull(
                github.upsertCheck(new GitHubPublicationGateway.CheckRunRequest(
                        run.id(),
                        run.revision(),
                        GitHubPublicationGateway.CheckPresentation.neutralSystemFailure(
                                safeSummary(failure, commentsMayRemain),
                                commentsMayRemain),
                        List.of(),
                        run.checkRunExternalId()), fence),
                "neutral Check");
        String previous = run.checkRunExternalId().orElse(null);
        if (!check.githubArtifactId().equals(previous)) {
            if (previous != null
                    && check.reconciliation()
                    != CheckRunArtifact.Reconciliation.REPLACED_MISSING) {
                throw new IllegalStateException(
                        "GitHub failure Check reconciliation returned a conflicting artifact");
            }
            run.recordFailurePresentationCheck(check.githubArtifactId());
            fence.requireCurrent();
            mutations.saveProgress(run, version);
        }
        telemetry.publication(ReviewOperationsTelemetry.PublicationOutcome.NEUTRAL_FAILURE);
        return PresentationOutcome.PRESENTED;
    }

    private Set<FindingFingerprint> retractConfirmedComments(
            ReviewRun run, OperationFence fence) {
        Set<FindingFingerprint> confirmed = new java.util.LinkedHashSet<>();
        run.commentReferences().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        java.util.Comparator.comparing(FindingFingerprint::value)))
                .forEach(entry -> {
                    GitHubPublicationGateway.InlineCommentRetraction retraction =
                            Objects.requireNonNull(github.retractInlineComment(
                                    new GitHubPublicationGateway.InlineCommentRetractionRequest(
                                            run.id(), run.revision(), entry.getKey(), entry.getValue()),
                                    fence),
                                    "confirmed comment retraction");
                    PublicationReference reference = entry.getValue();
                    if (!entry.getKey().equals(retraction.fingerprint())
                            || !reference.externalId().equals(retraction.githubArtifactId())) {
                        throw new GitHubFailureException(
                                GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                                "GitHub comment retraction returned a conflicting artifact");
                    }
                    confirmed.add(entry.getKey());
                });
        return Set.copyOf(confirmed);
    }

    private static boolean isRetryable(GitHubFailureException failure) {
        return failure.classification() == GitHubFailureException.Classification.TRANSIENT
                || failure.classification() == GitHubFailureException.Classification.RATE_LIMITED;
    }

    private static String safeSummary(ReviewFailure failure, boolean commentsMayRemain) {
        String summary = commentsMayRemain
                ? "Review failed safely (" + failure.code()
                        + "). This Check is neutral; previously published comments may remain."
                : "Review failed safely (" + failure.code()
                        + "). This Check is neutral and no code comments were published.";
        int limit = GitHubPublicationGateway.CheckPresentation.MAX_SAFE_SUMMARY_CHARACTERS;
        return summary.length() <= limit ? summary : summary.substring(0, limit);
    }

    public enum PresentationOutcome {
        PRESENTED,
        STALE,
        ALREADY_PROCESSED,
        NOT_FOUND
    }
}
