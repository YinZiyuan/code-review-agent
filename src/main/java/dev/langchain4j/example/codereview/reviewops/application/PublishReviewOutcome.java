package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;

import java.time.Clock;
import java.util.Objects;

public final class PublishReviewOutcome {

    private final ReviewRunRepository reviewRuns;
    private final ReviewRunMutationStore mutations;
    private final GitHubPublicationGateway github;
    private final Clock clock;

    public PublishReviewOutcome(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            GitHubPublicationGateway github,
            Clock clock) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.github = Objects.requireNonNull(github, "github");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PublicationOutcome publish(ReviewRunId id) {
        Objects.requireNonNull(id, "id");
        ReviewRunRepository.StoredReviewRun stored = reviewRuns.find(id).orElse(null);
        if (stored == null) {
            return PublicationOutcome.NOT_FOUND;
        }
        ReviewRun run = stored.reviewRun();
        PublicationOutcome settled = settledOutcome(run.state());
        if (settled != null) {
            return settled;
        }

        AuthoritativeRevision authoritative = Objects.requireNonNull(
                github.authoritativeRevision(run.revision()),
                "authoritative revision");
        if (!authoritative.matches(run.revision())) {
            if (run.state() == ReviewRunState.COMPLETED) {
                run.authorizePublication(authoritative, clock.instant());
            } else {
                run.supersede(authoritative, clock.instant());
            }
            mutations.saveProgress(run, stored.version());
            return PublicationOutcome.SUPERSEDED;
        }

        if (run.state() == ReviewRunState.COMPLETED) {
            run.authorizePublication(authoritative, clock.instant());
            mutations.saveProgress(run, stored.version());
        }
        return PublicationOutcome.AUTHORIZED;
    }

    private static PublicationOutcome settledOutcome(ReviewRunState state) {
        return switch (state) {
            case COMPLETED, PUBLISHING -> null;
            case PUBLISHED -> PublicationOutcome.PUBLISHED;
            case SUPERSEDED -> PublicationOutcome.SUPERSEDED;
            case FAILED -> PublicationOutcome.FAILED;
            case REQUESTED, RUNNING -> PublicationOutcome.NOT_READY;
        };
    }

    public enum PublicationOutcome {
        AUTHORIZED,
        SUPERSEDED,
        PUBLISHED,
        FAILED,
        NOT_READY,
        NOT_FOUND
    }
}
