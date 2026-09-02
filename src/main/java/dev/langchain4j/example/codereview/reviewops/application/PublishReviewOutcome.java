package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.InlineCommentArtifact;
import dev.langchain4j.example.codereview.reviewops.application.jobs.OperationFence;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationReference;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PublishReviewOutcome {

    private final ReviewRunRepository reviewRuns;
    private final ReviewRunMutationStore mutations;
    private final GitHubPublicationGateway github;
    private final Clock clock;
    private final ReviewOperationsTelemetry telemetry;

    public PublishReviewOutcome(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            GitHubPublicationGateway github,
            Clock clock) {
        this(reviewRuns, mutations, github, clock, ReviewOperationsTelemetry.NOOP);
    }

    public PublishReviewOutcome(
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

    public PublicationOutcome publish(ReviewRunId id) {
        return publish(id, OperationFence.unfenced());
    }

    public PublicationOutcome publish(ReviewRunId id, OperationFence fence) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fence, "fence");
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
            settleTerminalHeadLookupFailure(run, stored.version(), failure, fence);
            throw failure;
        }
        if (!authoritative.matches(run.revision())) {
            fence.requireCurrent();
            if (run.state() == ReviewRunState.COMPLETED) {
                run.authorizePublication(authoritative, clock.instant());
            } else {
                run.supersede(authoritative, clock.instant());
            }
            fence.requireCurrent();
            mutations.saveProgress(run, stored.version());
            telemetry.lifecycle(ReviewOperationsTelemetry.LifecycleOutcome.SUPERSEDED, 1);
            telemetry.preventedStale(ReviewOperationsTelemetry.StaleStage.PUBLICATION_HEAD);
            return PublicationOutcome.SUPERSEDED;
        }

        long version = stored.version();
        if (run.state() == ReviewRunState.COMPLETED) {
            fence.requireCurrent();
            run.authorizePublication(authoritative, clock.instant());
            fence.requireCurrent();
            version = mutations.saveProgress(run, version);
        }
        return reconcilePublication(run, version, fence);
    }

    private PublicationOutcome reconcilePublication(
            ReviewRun run, long initialVersion, OperationFence fence) {
        long version = initialVersion;
        try {
            List<GitHubPublicationGateway.PublicationFinding> selectedFindings = run.findings().stream()
                    .map(PublishReviewOutcome::publicationFinding)
                    .filter(finding -> finding.decision().tier() != PublicationTier.RETAIN_ONLY)
                    .toList();
            List<GitHubPublicationGateway.PublicationFinding> inlineFindings = selectedFindings.stream()
                    .filter(finding -> finding.decision().tier() == PublicationTier.INLINE_COMMENT)
                    .sorted(Comparator
                            .comparing((GitHubPublicationGateway.PublicationFinding finding) ->
                                    finding.location().file())
                            .thenComparingInt(finding -> finding.location().line())
                            .thenComparing(finding -> finding.fingerprint().value()))
                    .toList();
            if (inlineFindings.stream().anyMatch(finding -> !finding.location().changedLine())) {
                throw new GitHubFailureException(
                        GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                        "Inline publication location was invalid");
            }

            GitHubPublicationGateway.CheckRunRequest checkRequest =
                    new GitHubPublicationGateway.CheckRunRequest(
                            run.id(),
                            run.revision(),
                            GitHubPublicationGateway.CheckPresentation.success(
                                    successSummary(selectedFindings.size(), inlineFindings.size())),
                            selectedFindings,
                            run.checkRunExternalId());
            CheckRunArtifact confirmedCheck = Objects.requireNonNull(
                    github.upsertCheck(checkRequest, fence), "confirmed Check");
            String checkId = confirmedCheck.githubArtifactId();
            Optional<String> recordedCheck = run.checkRunExternalId();
            if (recordedCheck.isPresent() && !recordedCheck.orElseThrow().equals(checkId)) {
                if (confirmedCheck.reconciliation()
                        != CheckRunArtifact.Reconciliation.REPLACED_MISSING) {
                    throw new GitHubFailureException(
                            GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                            "GitHub Check reconciliation returned a conflicting artifact");
                }
                run.replaceMissingPublicationCheck(recordedCheck.orElseThrow(), checkId);
                fence.requireCurrent();
                version = mutations.saveProgress(run, version);
            }
            if (recordedCheck.isEmpty()) {
                run.recordPublicationProgress(checkId, Map.of());
                fence.requireCurrent();
                version = mutations.saveProgress(run, version);
            }

            for (GitHubPublicationGateway.PublicationFinding finding : inlineFindings) {
                InlineCommentArtifact confirmedComment = Objects.requireNonNull(
                        github.reconcileInlineComment(
                                new GitHubPublicationGateway.InlineCommentRequest(
                                        run.id(), run.revision(), finding), fence),
                        "confirmed inline comment");
                if (!finding.fingerprint().equals(confirmedComment.fingerprint())) {
                    throw new GitHubFailureException(
                            GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                            "GitHub comment reconciliation returned a conflicting artifact");
                }
                PublicationReference reference = new PublicationReference(
                        "github_review_comment", confirmedComment.githubArtifactId());
                Optional<PublicationReference> recorded = finding.existingReference();
                if (recorded.isPresent()
                        && !recorded.orElseThrow().equals(reference)) {
                    if (confirmedComment.reconciliation()
                            != InlineCommentArtifact.Reconciliation.REPLACED_MISSING) {
                        throw new GitHubFailureException(
                                GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                                "GitHub comment reconciliation returned a conflicting artifact");
                    }
                    run.replaceMissingPublicationComment(
                            finding.fingerprint(), recorded.orElseThrow(), reference);
                    fence.requireCurrent();
                    version = mutations.saveProgress(run, version);
                } else if (recorded.isEmpty()) {
                    run.recordPublicationProgress(
                            checkId, Map.of(finding.fingerprint(), reference));
                    fence.requireCurrent();
                    version = mutations.saveProgress(run, version);
                }
            }

            run.confirmPublication(checkId, run.commentReferences(), clock.instant());
            fence.requireCurrent();
            mutations.saveProgress(run, version);
            telemetry.publication(ReviewOperationsTelemetry.PublicationOutcome.PUBLISHED);
            return PublicationOutcome.PUBLISHED;
        } catch (GitHubFailureException failure) {
            settleTerminalArtifactFailure(run, version, failure, fence);
            throw failure;
        }
    }

    private void settleTerminalArtifactFailure(
            ReviewRun run,
            long expectedVersion,
            GitHubFailureException failure,
            OperationFence fence) {
        ReviewFailure terminalFailure = terminalReviewFailure(failure);
        if (terminalFailure == null) {
            return;
        }
        boolean commentsMayRemain = !run.commentReferences().isEmpty();
        if (commentsMayRemain) {
            try {
                Set<FindingFingerprint> confirmedRetractions =
                        retractConfirmedComments(run, fence);
                run.recordPublicationFailureAfterCommentRetraction(
                        terminalFailure, confirmedRetractions, clock.instant());
                commentsMayRemain = false;
            } catch (GitHubFailureException cleanupFailure) {
                if (terminalReviewFailure(cleanupFailure) == null) {
                    throw cleanupFailure;
                }
                run.recordPublicationFailure(terminalFailure, clock.instant());
            }
        } else {
            run.recordPublicationFailure(terminalFailure, clock.instant());
        }
        fence.requireCurrent();
        long failedVersion = mutations.saveProgress(run, expectedVersion);
        telemetry.lifecycle(ReviewOperationsTelemetry.LifecycleOutcome.FAILED, 1);
        telemetry.publication(ReviewOperationsTelemetry.PublicationOutcome.FAILED);
        bestEffortNeutralCheck(run, failedVersion, failure, commentsMayRemain, fence);
    }

    private Set<FindingFingerprint> retractConfirmedComments(
            ReviewRun run, OperationFence fence) {
        Set<FindingFingerprint> confirmed = new LinkedHashSet<>();
        run.commentReferences().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(FindingFingerprint::value)))
                .forEach(entry -> {
                    GitHubPublicationGateway.InlineCommentRetraction retraction =
                            Objects.requireNonNull(
                                    github.retractInlineComment(
                                            new GitHubPublicationGateway
                                                    .InlineCommentRetractionRequest(
                                                    run.id(),
                                                    run.revision(),
                                                    entry.getKey(),
                                                    entry.getValue()), fence),
                                    "confirmed comment retraction");
                    if (!entry.getKey().equals(retraction.fingerprint())
                            || !entry.getValue().externalId()
                                    .equals(retraction.githubArtifactId())) {
                        throw new GitHubFailureException(
                                GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                                "GitHub comment retraction returned a conflicting artifact");
                    }
                    confirmed.add(entry.getKey());
                });
        return Set.copyOf(confirmed);
    }

    private void bestEffortNeutralCheck(
            ReviewRun run,
            long expectedVersion,
            GitHubFailureException originalFailure,
            boolean commentsMayRemain,
            OperationFence fence) {
        try {
            CheckRunArtifact neutralCheck = Objects.requireNonNull(
                    github.upsertCheck(new GitHubPublicationGateway.CheckRunRequest(
                            run.id(),
                            run.revision(),
                            GitHubPublicationGateway.CheckPresentation.neutralSystemFailure(
                                    neutralFailureSummary(
                                            originalFailure.getMessage(), commentsMayRemain),
                                    commentsMayRemain),
                            List.of(),
                            run.checkRunExternalId()), fence),
                    "neutral Check");
            if (run.checkRunExternalId().isEmpty()) {
                run.recordFailedPublicationCheck(neutralCheck.githubArtifactId());
                fence.requireCurrent();
                mutations.saveProgress(run, expectedVersion);
            }
        } catch (RuntimeException ignored) {
            // The durable terminal failure is authoritative; neutral presentation is best effort.
        }
    }

    private static GitHubPublicationGateway.PublicationFinding publicationFinding(
            ReviewFinding finding) {
        return new GitHubPublicationGateway.PublicationFinding(
                finding.fingerprint(),
                finding.location(),
                finding.content(),
                finding.evidence(),
                finding.publicationDecision().orElseThrow(),
                finding.publicationReference());
    }

    private static String successSummary(int selectedFindings, int inlineFindings) {
        if (selectedFindings == 0) {
            return "Review completed with no findings selected for publication.";
        }
        return "Review completed with %d %s; %d inline %s."
                .formatted(
                        selectedFindings,
                        selectedFindings == 1 ? "finding" : "findings",
                        inlineFindings,
                        inlineFindings == 1 ? "comment" : "comments");
    }

    private static String neutralFailureSummary(
            String safeMessage, boolean commentsMayRemain) {
        String summary = commentsMayRemain
                ? "Review publication failed safely; previously published comments may remain. "
                        + "Reason: " + safeMessage
                : "Review publication failed safely: " + safeMessage;
        int limit = GitHubPublicationGateway.CheckPresentation.MAX_SAFE_SUMMARY_CHARACTERS;
        return summary.length() <= limit ? summary : summary.substring(0, limit);
    }

    private static ReviewFailure terminalReviewFailure(GitHubFailureException failure) {
        return switch (failure.classification()) {
            case TRANSIENT, RATE_LIMITED -> null;
            case AUTHORIZATION -> new ReviewFailure(
                    "github_authorization", FailureClass.TERMINAL, failure.getMessage());
            case DETERMINISTIC_INPUT -> new ReviewFailure(
                    "github_deterministic_input", FailureClass.TERMINAL, failure.getMessage());
        };
    }

    private void settleTerminalHeadLookupFailure(
            ReviewRun run,
            long expectedVersion,
            GitHubFailureException failure,
            OperationFence fence) {
        ReviewFailure terminalFailure = terminalReviewFailure(failure);
        if (terminalFailure == null) {
            return;
        }
        fence.requireCurrent();
        if (run.state() == ReviewRunState.COMPLETED) {
            run.recordPublicationAuthorizationFailure(terminalFailure, clock.instant());
        } else {
            run.recordPublicationFailure(terminalFailure, clock.instant());
        }
        fence.requireCurrent();
        mutations.saveProgress(run, expectedVersion);
        telemetry.lifecycle(ReviewOperationsTelemetry.LifecycleOutcome.FAILED, 1);
        telemetry.publication(ReviewOperationsTelemetry.PublicationOutcome.FAILED);
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
