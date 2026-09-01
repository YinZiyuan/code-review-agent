package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
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

        AuthoritativeRevision authoritative;
        try {
            authoritative = Objects.requireNonNull(
                    github.authoritativeRevision(run.revision()),
                    "authoritative revision");
        } catch (GitHubFailureException failure) {
            settleTerminalHeadLookupFailure(run, stored.version(), failure);
            throw failure;
        }
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

    private void settleTerminalHeadLookupFailure(
            ReviewRun run,
            long expectedVersion,
            GitHubFailureException failure) {
        ReviewFailure terminalFailure = switch (failure.classification()) {
            case TRANSIENT, RATE_LIMITED -> null;
            case AUTHORIZATION -> new ReviewFailure(
                    "github_authorization", FailureClass.TERMINAL, failure.getMessage());
            case DETERMINISTIC_INPUT -> new ReviewFailure(
                    "github_deterministic_input", FailureClass.TERMINAL, failure.getMessage());
        };
        if (terminalFailure == null) {
            return;
        }
        if (run.state() == ReviewRunState.COMPLETED) {
            run.recordPublicationAuthorizationFailure(terminalFailure, clock.instant());
        } else {
            run.recordPublicationFailure(terminalFailure, clock.instant());
        }
        mutations.saveProgress(run, expectedVersion);
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
