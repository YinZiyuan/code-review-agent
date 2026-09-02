package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Reconciles the developer-visible, non-blocking presentation of a terminal review failure. */
public final class PresentReviewFailure {

    private final ReviewRunRepository reviewRuns;
    private final ReviewRunMutationStore mutations;
    private final GitHubPublicationGateway github;
    @SuppressWarnings("unused")
    private final Clock clock;

    public PresentReviewFailure(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            GitHubPublicationGateway github,
            Clock clock) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.github = Objects.requireNonNull(github, "github");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PresentationOutcome present(ReviewRunId id) {
        Objects.requireNonNull(id, "id");
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
            return PresentationOutcome.STALE;
        }

        ReviewFailure failure = run.finalFailure().orElseThrow();
        CheckRunArtifact check = Objects.requireNonNull(
                github.upsertCheck(new GitHubPublicationGateway.CheckRunRequest(
                        run.id(),
                        run.revision(),
                        GitHubPublicationGateway.CheckPresentation.neutralSystemFailure(
                                safeSummary(failure)),
                        List.of(),
                        run.checkRunExternalId())),
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
            mutations.saveProgress(run, stored.version());
        }
        return PresentationOutcome.PRESENTED;
    }

    private static String safeSummary(ReviewFailure failure) {
        String summary = "Review failed safely (" + failure.code()
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
