package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.CheckRunRequest;
import dev.langchain4j.example.codereview.reviewops.application.github.InlineCommentArtifact;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.OperationFence;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationReference;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishReviewOutcomeTest {

    private static final Instant NOW = Instant.parse("2026-09-01T11:00:00Z");
    private static final String REVIEW_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String NEW_SHA = "abcdef0123456789abcdef0123456789abcdef01";

    @Test
    void staleAuthoritativeHeadIsDurablySupersededBeforeAnyGitHubMutation() {
        ReviewRun run = completedRunWithDecision();
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(NEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();
        PublishReviewOutcome publisher = new PublishReviewOutcome(
                new FixedReviewRunRepository(run, 4),
                mutations,
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC));

        PublishReviewOutcome.PublicationOutcome outcome = publisher.publish(run.id());

        assertThat(outcome).isEqualTo(PublishReviewOutcome.PublicationOutcome.SUPERSEDED);
        assertThat(gateway.authoritativeCalls).isOne();
        assertThat(gateway.requestedRevision).isEqualTo(run.revision());
        assertThat(gateway.checkMutations).isZero();
        assertThat(gateway.commentMutations).isZero();
        assertThat(run.state()).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThat(run.finishedAt()).contains(NOW);
        assertThat(mutations.progressSaveCount).isOne();
        assertThat(mutations.progressExpectedVersion).isEqualTo(4);
        assertThat(mutations.progressState).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThat(mutations.atomicSaveCount).isZero();
    }

    @Test
    void lostLeaseBeforeAuthorizationPreventsAggregateAndGitHubMutation() {
        ReviewRun run = completedRunWithDecision();
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();
        OperationFence lost = () -> {
            throw new OperationFence.Lost();
        };

        assertThatThrownBy(() -> publisher(run, 4, mutations, gateway).publish(run.id(), lost))
                .isInstanceOf(OperationFence.Lost.class);

        assertThat(run.state()).isEqualTo(ReviewRunState.COMPLETED);
        assertThat(gateway.authoritativeCalls).isOne();
        assertThat(gateway.checkMutations).isZero();
        assertThat(gateway.commentMutations).isZero();
        assertThat(mutations.progressSaveCount).isZero();
    }

    @Test
    void matchingHeadPublishesAndPersistsAuthorizationCheckAndCompletionInOrder() {
        ReviewRun run = completedRunWithDecision();
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();
        PublishReviewOutcome publisher = publisher(run, 11, mutations, gateway);

        PublishReviewOutcome.PublicationOutcome outcome = publisher.publish(run.id());

        assertThat(outcome).isEqualTo(PublishReviewOutcome.PublicationOutcome.PUBLISHED);
        assertThat(gateway.authoritativeCalls).isOne();
        assertThat(gateway.checkMutations).isOne();
        assertThat(gateway.commentMutations).isZero();
        assertThat(run.state()).isEqualTo(ReviewRunState.PUBLISHED);
        assertThat(run.checkRunExternalId()).contains("check-123");
        assertThat(mutations.progressSnapshots).containsExactly(
                new ProgressSnapshot(11, ReviewRunState.PUBLISHING, null, 0),
                new ProgressSnapshot(12, ReviewRunState.PUBLISHING, "check-123", 0),
                new ProgressSnapshot(13, ReviewRunState.PUBLISHED, "check-123", 0));
        assertThat(mutations.atomicSaveCount).isZero();
    }

    @Test
    void publishingRetryRechecksHeadAndFinishesWithoutReauthorizing() {
        ReviewRun run = completedRunWithDecision();
        run.authorizePublication(new AuthoritativeRevision(REVIEW_SHA), NOW.minusSeconds(5));
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();

        PublishReviewOutcome.PublicationOutcome outcome =
                publisher(run, 12, mutations, gateway).publish(run.id());

        assertThat(outcome).isEqualTo(PublishReviewOutcome.PublicationOutcome.PUBLISHED);
        assertThat(gateway.authoritativeCalls).isOne();
        assertThat(gateway.checkMutations).isOne();
        assertThat(gateway.commentMutations).isZero();
        assertThat(run.state()).isEqualTo(ReviewRunState.PUBLISHED);
        assertThat(mutations.progressSnapshots).containsExactly(
                new ProgressSnapshot(12, ReviewRunState.PUBLISHING, "check-123", 0),
                new ProgressSnapshot(13, ReviewRunState.PUBLISHED, "check-123", 0));
        assertThat(mutations.atomicSaveCount).isZero();
    }

    @Test
    void deletedCheckReplacementIsPersistedBeforeAnyInlineCommentMutation() {
        ReviewRun run = completedRunWithInlineDecisions();
        run.authorizePublication(new AuthoritativeRevision(REVIEW_SHA), NOW.minusSeconds(5));
        run.recordPublicationProgress("check-deleted", Map.of());
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        gateway.replaceMissingCheckWith("check-replacement");
        gateway.observeRun(run);
        RecordingMutationStore mutations = new RecordingMutationStore();

        PublishReviewOutcome.PublicationOutcome outcome =
                publisher(run, 70, mutations, gateway).publish(run.id());

        assertThat(outcome).isEqualTo(PublishReviewOutcome.PublicationOutcome.PUBLISHED);
        assertThat(run.checkRunExternalId()).contains("check-replacement");
        assertThat(gateway.checkRequests.get(0).existingGitHubArtifactId())
                .contains("check-deleted");
        assertThat(gateway.checkIdObservedAtFirstComment).isEqualTo("check-replacement");
        assertThat(mutations.progressSnapshots).startsWith(
                new ProgressSnapshot(70, ReviewRunState.PUBLISHING, "check-replacement", 0));
    }

    @Test
    void deletedPersistedCommentIsReconciledAndReplacementPersistedBeforeContinuing() {
        ReviewRun run = completedRunWithInlineDecisions();
        FindingFingerprint first = new FindingFingerprint("a".repeat(64));
        PublicationReference deleted = new PublicationReference(
                "github_review_comment", "comment-deleted");
        run.authorizePublication(new AuthoritativeRevision(REVIEW_SHA), NOW.minusSeconds(5));
        run.recordPublicationProgress("check-123", Map.of(first, deleted));
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        gateway.replaceMissingCommentWith(first, "comment-replacement");
        RecordingMutationStore mutations = new RecordingMutationStore();

        PublishReviewOutcome.PublicationOutcome outcome =
                publisher(run, 80, mutations, gateway).publish(run.id());

        assertThat(outcome).isEqualTo(PublishReviewOutcome.PublicationOutcome.PUBLISHED);
        assertThat(gateway.commentRequests.get(0).finding().existingReference())
                .contains(deleted);
        assertThat(run.commentReferences().get(first).externalId())
                .isEqualTo("comment-replacement");
        assertThat(mutations.progressSnapshots).startsWith(
                new ProgressSnapshot(80, ReviewRunState.PUBLISHING, "check-123", 1));
    }

    @Test
    void partialCommentRetrySkipsConfirmedFindingAndPersistsEveryNewArtifactBeforeContinuing() {
        ReviewRun run = completedRunWithInlineDecisions();
        FindingFingerprint second = new FindingFingerprint("b".repeat(64));
        GitHubFailureException transientFailure = new GitHubFailureException(
                GitHubFailureException.Classification.TRANSIENT,
                "safe transient comment failure");
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        gateway.failCommentOnce(second, transientFailure);
        RecordingMutationStore mutations = new RecordingMutationStore();

        assertThatThrownBy(() -> publisher(run, 20, mutations, gateway).publish(run.id()))
                .isSameAs(transientFailure);

        assertThat(run.state()).isEqualTo(ReviewRunState.PUBLISHING);
        assertThat(run.checkRunExternalId()).contains("check-123");
        assertThat(run.commentReferences()).containsOnlyKeys(
                new FindingFingerprint("a".repeat(64)));
        assertThat(gateway.commentFingerprints()).containsExactly(
                "a".repeat(64), "b".repeat(64));
        assertThat(mutations.progressSnapshots).containsExactly(
                new ProgressSnapshot(20, ReviewRunState.PUBLISHING, null, 0),
                new ProgressSnapshot(21, ReviewRunState.PUBLISHING, "check-123", 0),
                new ProgressSnapshot(22, ReviewRunState.PUBLISHING, "check-123", 1));

        PublishReviewOutcome.PublicationOutcome retryOutcome =
                publisher(run, 23, mutations, gateway).publish(run.id());

        assertThat(retryOutcome).isEqualTo(PublishReviewOutcome.PublicationOutcome.PUBLISHED);
        assertThat(gateway.commentFingerprints()).containsExactly(
                "a".repeat(64), "b".repeat(64), "a".repeat(64), "b".repeat(64));
        assertThat(run.commentReferences()).containsOnlyKeys(
                new FindingFingerprint("a".repeat(64)),
                new FindingFingerprint("b".repeat(64)));
        assertThat(mutations.progressSnapshots).endsWith(
                new ProgressSnapshot(23, ReviewRunState.PUBLISHING, "check-123", 2),
                new ProgressSnapshot(24, ReviewRunState.PUBLISHED, "check-123", 2));
    }

    @Test
    void aFailedProgressSaveStopsBeforeTheNextExternalMutation() {
        ReviewRun run = completedRunWithInlineDecisions();
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();
        mutations.failOnSaveNumber(2);

        assertThatThrownBy(() -> publisher(run, 30, mutations, gateway).publish(run.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected progress save failure");

        assertThat(gateway.checkMutations).isOne();
        assertThat(gateway.commentMutations).isZero();
    }

    @Test
    void aFailedCommentProgressSaveStopsBeforeTheNextCommentMutation() {
        ReviewRun run = completedRunWithInlineDecisions();
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();
        mutations.failOnSaveNumber(3);

        assertThatThrownBy(() -> publisher(run, 35, mutations, gateway).publish(run.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected progress save failure");

        assertThat(gateway.checkMutations).isOne();
        assertThat(gateway.commentFingerprints()).containsExactly("a".repeat(64));
    }

    @Test
    void terminalCommentFailureIsPersistedBeforeNeutralCheckAndPostsNoLaterComments() {
        ReviewRun run = completedRunWithInlineDecisions();
        FindingFingerprint first = new FindingFingerprint("a".repeat(64));
        GitHubFailureException terminalFailure = new GitHubFailureException(
                GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                "GitHub inline comment location was invalid");
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        gateway.failCommentOnce(first, terminalFailure);
        gateway.observeRun(run);
        RecordingMutationStore mutations = new RecordingMutationStore();

        assertThatThrownBy(() -> publisher(run, 40, mutations, gateway).publish(run.id()))
                .isSameAs(terminalFailure);

        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.finalFailure()).hasValueSatisfying(failure -> {
            assertThat(failure.code()).isEqualTo("github_deterministic_input");
            assertThat(failure.safeMessage())
                    .isEqualTo("GitHub inline comment location was invalid");
        });
        assertThat(gateway.commentFingerprints()).containsExactly("a".repeat(64));
        assertThat(gateway.checkRequests).hasSize(2);
        assertThat(gateway.checkRequests.get(1).presentation().outcome())
                .isEqualTo(GitHubPublicationGateway.CheckOutcome.NEUTRAL_SYSTEM_FAILURE);
        assertThat(gateway.checkRequests.get(1).findings()).isEmpty();
        assertThat(gateway.stateAtNeutralCheck).isEqualTo(ReviewRunState.FAILED);
        assertThat(mutations.progressSnapshots).containsSubsequence(
                new ProgressSnapshot(42, ReviewRunState.FAILED, "check-123", 0));
    }

    @Test
    void terminalFailureAfterOneCommentRetractsItBeforePersistingFailure() {
        ReviewRun run = completedRunWithInlineDecisions();
        FindingFingerprint second = new FindingFingerprint("b".repeat(64));
        GitHubFailureException terminalFailure = new GitHubFailureException(
                GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                "GitHub inline comment location was invalid");
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        gateway.failCommentAlways(second, terminalFailure);
        gateway.observeRun(run);
        RecordingMutationStore mutations = new RecordingMutationStore();

        assertThatThrownBy(() -> publisher(run, 80, mutations, gateway).publish(run.id()))
                .isSameAs(terminalFailure);

        assertThat(gateway.commentFingerprints()).containsExactly(
                "a".repeat(64), "b".repeat(64));
        assertThat(gateway.retractedFingerprints()).containsExactly("a".repeat(64));
        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.commentReferences()).isEmpty();
        assertThat(run.findings().get(0).publicationReference()).isEmpty();
        CheckRunRequest neutral = gateway.checkRequests.get(1);
        assertThat(neutral.presentation().codeCommentsMayRemain()).isFalse();
        assertThat(mutations.progressSnapshots).endsWith(
                new ProgressSnapshot(83, ReviewRunState.FAILED, "check-123", 0));
    }

    @Test
    void retryableRetractionKeepsDurableReferencesAndRetryReconcilesTheFirstComment() {
        ReviewRun run = completedRunWithInlineDecisions();
        FindingFingerprint first = new FindingFingerprint("a".repeat(64));
        FindingFingerprint second = new FindingFingerprint("b".repeat(64));
        GitHubFailureException terminalFailure = new GitHubFailureException(
                GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                "GitHub inline comment location was invalid");
        GitHubFailureException transientCleanup = new GitHubFailureException(
                GitHubFailureException.Classification.TRANSIENT,
                "GitHub inline comment retraction failed");
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        gateway.failCommentAlways(second, terminalFailure);
        gateway.failRetractionOnce(first, transientCleanup);
        RecordingMutationStore mutations = new RecordingMutationStore();

        assertThatThrownBy(() -> publisher(run, 90, mutations, gateway).publish(run.id()))
                .isSameAs(transientCleanup);

        assertThat(run.state()).isEqualTo(ReviewRunState.PUBLISHING);
        assertThat(run.commentReferences()).containsOnlyKeys(first);
        assertThat(gateway.checkRequests).hasSize(1);

        assertThatThrownBy(() -> publisher(run, 93, mutations, gateway).publish(run.id()))
                .isSameAs(terminalFailure);

        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.commentReferences()).isEmpty();
        assertThat(gateway.commentFingerprints()).containsExactly(
                "a".repeat(64), "b".repeat(64), "a".repeat(64), "b".repeat(64));
        assertThat(gateway.retractedFingerprints()).containsExactly(
                "a".repeat(64), "a".repeat(64));
    }

    @Test
    void terminalRetractionFailureRetainsReferencesAndMakesNeutralWarningTruthful() {
        ReviewRun run = completedRunWithInlineDecisions();
        FindingFingerprint first = new FindingFingerprint("a".repeat(64));
        FindingFingerprint second = new FindingFingerprint("b".repeat(64));
        GitHubFailureException terminalFailure = new GitHubFailureException(
                GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                "GitHub inline comment location was invalid");
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        gateway.failCommentAlways(second, terminalFailure);
        gateway.failRetractionOnce(first, new GitHubFailureException(
                GitHubFailureException.Classification.AUTHORIZATION,
                "GitHub authorization failed"));
        RecordingMutationStore mutations = new RecordingMutationStore();

        assertThatThrownBy(() -> publisher(run, 100, mutations, gateway).publish(run.id()))
                .isSameAs(terminalFailure);

        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.commentReferences()).containsOnlyKeys(first);
        CheckRunRequest neutral = gateway.checkRequests.get(1);
        assertThat(neutral.presentation().codeCommentsMayRemain()).isTrue();
        assertThat(neutral.presentation().safeSummary()).contains("comments may remain");
    }

    @Test
    void neutralCheckCreatedAfterInitialTerminalCheckFailureIsPersistedOnFailedRun() {
        ReviewRun run = completedRunWithDecision();
        GitHubFailureException terminalFailure = new GitHubFailureException(
                GitHubFailureException.Classification.AUTHORIZATION,
                "GitHub authorization failed");
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        gateway.failCheckOnce(terminalFailure);
        gateway.observeRun(run);
        RecordingMutationStore mutations = new RecordingMutationStore();

        assertThatThrownBy(() -> publisher(run, 50, mutations, gateway).publish(run.id()))
                .isSameAs(terminalFailure);

        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.checkRunExternalId()).contains("check-123");
        assertThat(gateway.checkRequests).hasSize(2);
        assertThat(gateway.stateAtNeutralCheck).isEqualTo(ReviewRunState.FAILED);
        assertThat(mutations.progressSnapshots).endsWith(
                new ProgressSnapshot(51, ReviewRunState.FAILED, null, 0),
                new ProgressSnapshot(52, ReviewRunState.FAILED, "check-123", 0));
    }

    @Test
    void bestEffortNeutralCheckPersistenceDoesNotMaskTheOriginalTerminalFailure() {
        ReviewRun run = completedRunWithDecision();
        GitHubFailureException terminalFailure = new GitHubFailureException(
                GitHubFailureException.Classification.AUTHORIZATION,
                "GitHub authorization failed");
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        gateway.failCheckOnce(terminalFailure);
        RecordingMutationStore mutations = new RecordingMutationStore();
        mutations.failOnSaveNumber(3);

        assertThatThrownBy(() -> publisher(run, 60, mutations, gateway).publish(run.id()))
                .isSameAs(terminalFailure);

        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(gateway.checkRequests).hasSize(2);
        assertThat(mutations.progressSnapshots).containsExactly(
                new ProgressSnapshot(60, ReviewRunState.PUBLISHING, null, 0),
                new ProgressSnapshot(61, ReviewRunState.FAILED, null, 0));
    }

    @Test
    void terminalRunsReturnTheirDurableOutcomeWithoutCallingGitHub() {
        ReviewRun superseded = completedRunWithDecision();
        superseded.authorizePublication(new AuthoritativeRevision(NEW_SHA), NOW.minusSeconds(3));
        assertTerminalOutcome(
                superseded, PublishReviewOutcome.PublicationOutcome.SUPERSEDED);

        ReviewRun published = completedRunWithDecision();
        published.authorizePublication(new AuthoritativeRevision(REVIEW_SHA), NOW.minusSeconds(3));
        published.confirmPublication("check-123", Map.of(), NOW.minusSeconds(2));
        assertTerminalOutcome(
                published, PublishReviewOutcome.PublicationOutcome.PUBLISHED);

        ReviewRun failed = completedRunWithDecision();
        failed.authorizePublication(new AuthoritativeRevision(REVIEW_SHA), NOW.minusSeconds(3));
        failed.recordPublicationFailure(
                new ReviewFailure(
                        "github_authorization",
                        FailureClass.TERMINAL,
                        "GitHub publication is not authorized"),
                NOW.minusSeconds(2));
        assertTerminalOutcome(failed, PublishReviewOutcome.PublicationOutcome.FAILED);
    }

    @Test
    void retryableAuthoritativeLookupFailuresPropagateWithoutSettlingTheRun() {
        for (GitHubFailureException.Classification classification : List.of(
                GitHubFailureException.Classification.TRANSIENT,
                GitHubFailureException.Classification.RATE_LIMITED)) {
            ReviewRun run = completedRunWithDecision();
            RecordingMutationStore mutations = new RecordingMutationStore();
            GitHubFailureException failure = new GitHubFailureException(
                    classification,
                    "safe GitHub publication failure",
                    classification == GitHubFailureException.Classification.RATE_LIMITED
                            ? NOW.plusSeconds(60) : null);
            GitHubPublicationGateway gateway = new FailingGateway(failure);

            assertThatThrownBy(() -> publisher(run, 13, mutations, gateway).publish(run.id()))
                    .isSameAs(failure);

            assertThat(run.state()).isEqualTo(ReviewRunState.COMPLETED);
            assertThat(mutations.progressSaveCount).isZero();
            assertThat(mutations.atomicSaveCount).isZero();
        }
    }

    @Test
    void terminalAuthoritativeLookupFailuresSettleCompletedRunBeforePropagatingToWorker() {
        for (GitHubFailureException.Classification classification : List.of(
                GitHubFailureException.Classification.AUTHORIZATION,
                GitHubFailureException.Classification.DETERMINISTIC_INPUT)) {
            ReviewRun run = completedRunWithDecision();
            RecordingMutationStore mutations = new RecordingMutationStore();
            GitHubFailureException failure = new GitHubFailureException(
                    classification, "safe GitHub publication failure");

            assertThatThrownBy(() -> publisher(
                    run, 13, mutations, new FailingGateway(failure)).publish(run.id()))
                    .isSameAs(failure);

            assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
            assertThat(run.finalFailure()).contains(new ReviewFailure(
                    classification == GitHubFailureException.Classification.AUTHORIZATION
                            ? "github_authorization" : "github_deterministic_input",
                    FailureClass.TERMINAL,
                    "safe GitHub publication failure"));
            assertThat(run.finishedAt()).contains(NOW);
            assertThat(run.checkRunExternalId()).isEmpty();
            assertThat(mutations.progressSaveCount).isOne();
            assertThat(mutations.progressExpectedVersion).isEqualTo(13);
            assertThat(mutations.progressState).isEqualTo(ReviewRunState.FAILED);
            assertThat(mutations.atomicSaveCount).isZero();
        }
    }

    @Test
    void terminalAuthoritativeLookupFailureSettlesPublishingRunAndRetainsProgress() {
        ReviewRun run = completedRunWithDecision();
        run.authorizePublication(new AuthoritativeRevision(REVIEW_SHA), NOW.minusSeconds(5));
        run.recordPublicationProgress("check-existing", Map.of());
        RecordingMutationStore mutations = new RecordingMutationStore();
        GitHubFailureException failure = new GitHubFailureException(
                GitHubFailureException.Classification.AUTHORIZATION,
                "safe GitHub publication failure");

        assertThatThrownBy(() -> publisher(
                run, 17, mutations, new FailingGateway(failure)).publish(run.id()))
                .isSameAs(failure);

        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.finalFailure()).contains(new ReviewFailure(
                "github_authorization",
                FailureClass.TERMINAL,
                "safe GitHub publication failure"));
        assertThat(run.finishedAt()).contains(NOW);
        assertThat(run.checkRunExternalId()).contains("check-existing");
        assertThat(mutations.progressSaveCount).isOne();
        assertThat(mutations.progressExpectedVersion).isEqualTo(17);
        assertThat(mutations.progressState).isEqualTo(ReviewRunState.FAILED);
        assertThat(mutations.atomicSaveCount).isZero();
    }

    private static PublishReviewOutcome publisher(
            ReviewRun run,
            long version,
            RecordingMutationStore mutations,
            GitHubPublicationGateway gateway) {
        return new PublishReviewOutcome(
                new FixedReviewRunRepository(run, version),
                mutations,
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void assertTerminalOutcome(
            ReviewRun run, PublishReviewOutcome.PublicationOutcome expected) {
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();

        assertThat(publisher(run, 20, mutations, gateway).publish(run.id())).isEqualTo(expected);
        assertThat(gateway.authoritativeCalls).isZero();
        assertThat(gateway.checkMutations).isZero();
        assertThat(gateway.commentMutations).isZero();
        assertThat(mutations.progressSaveCount).isZero();
        assertThat(mutations.atomicSaveCount).isZero();
    }

    private static ReviewRun completedRunWithDecision() {
        ReviewFinding finding = new ReviewFinding(
                new FindingFingerprint("a".repeat(64)),
                new CodeLocation("src/Foo.java", 10, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        "Issue",
                        "Description",
                        "Suggestion"),
                new FindingEvidence("Evidence", List.of(), "regex"));
        ReviewRun run = ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(10, 20, 30, REVIEW_SHA),
                new ReviewConfigurationSnapshot(
                        "pipeline-v1", "configuration-v1", "model-v1", "policy-v1", 3),
                NOW.minusSeconds(60));
        run.startAttempt(NOW.minusSeconds(30));
        run.completeReview(
                List.of(finding),
                new ExecutionMeasurements(100, 10, 2, Map.of()),
                NOW.minusSeconds(10));
        run.drainEvents();
        run.acceptPublicationDecisions(Map.of(
                finding.fingerprint(),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "policy-v1")));
        return run;
    }

    private static ReviewRun completedRunWithInlineDecisions() {
        ReviewFinding first = finding("a".repeat(64), "src/A.java", 10);
        ReviewFinding second = finding("b".repeat(64), "src/B.java", 20);
        ReviewRun run = ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(10, 20, 30, REVIEW_SHA),
                new ReviewConfigurationSnapshot(
                        "pipeline-v1", "configuration-v1", "model-v1", "policy-v1", 3),
                NOW.minusSeconds(60));
        run.startAttempt(NOW.minusSeconds(30));
        run.completeReview(
                List.of(first, second),
                new ExecutionMeasurements(100, 10, 2, Map.of()),
                NOW.minusSeconds(10));
        run.drainEvents();
        run.acceptPublicationDecisions(Map.of(
                first.fingerprint(),
                new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v1"),
                second.fingerprint(),
                new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v1")));
        return run;
    }

    private static ReviewFinding finding(String fingerprint, String file, int line) {
        return new ReviewFinding(
                new FindingFingerprint(fingerprint),
                new CodeLocation(file, line, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        "Issue " + file,
                        "Description",
                        "Suggestion"),
                new FindingEvidence("Evidence", List.of(), "regex"));
    }

    private record FixedReviewRunRepository(ReviewRun run, long version)
            implements ReviewRunRepository {

        @Override
        public Optional<StoredReviewRun> find(ReviewRunId id) {
            return run.id().equals(id) ? Optional.of(new StoredReviewRun(run, version)) : Optional.empty();
        }

        @Override
        public void insert(ReviewRun reviewRun) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long update(ReviewRun reviewRun, long expectedVersion) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingMutationStore implements ReviewRunMutationStore {
        private int progressSaveCount;
        private long progressExpectedVersion = -1;
        private ReviewRunState progressState;
        private int atomicSaveCount;
        private int failOnSaveNumber = -1;
        private final java.util.ArrayList<ProgressSnapshot> progressSnapshots =
                new java.util.ArrayList<>();

        @Override
        public long saveProgress(ReviewRun run, long expectedVersion) {
            progressSaveCount++;
            if (progressSaveCount == failOnSaveNumber) {
                throw new IllegalStateException("injected progress save failure");
            }
            progressExpectedVersion = expectedVersion;
            progressState = run.state();
            progressSnapshots.add(new ProgressSnapshot(
                    expectedVersion,
                    run.state(),
                    run.checkRunExternalId().orElse(null),
                    run.commentReferences().size()));
            return expectedVersion + 1;
        }

        private void failOnSaveNumber(int saveNumber) {
            failOnSaveNumber = saveNumber;
        }

        @Override
        public long saveAndEnqueue(
                ReviewRun run,
                long expectedVersion,
                List<DurableJobRequest> jobs,
                List<OutboxEvent> events) {
            atomicSaveCount++;
            return expectedVersion + 1;
        }
    }

    private static final class RecordingGateway implements GitHubPublicationGateway {
        private final AuthoritativeRevision authoritative;
        private int authoritativeCalls;
        private PullRequestRevision requestedRevision;
        private int checkMutations;
        private int commentMutations;
        private final java.util.ArrayList<CheckRunRequest> checkRequests =
                new java.util.ArrayList<>();
        private final java.util.ArrayList<InlineCommentRequest> commentRequests =
                new java.util.ArrayList<>();
        private final java.util.ArrayList<InlineCommentRetractionRequest> retractionRequests =
                new java.util.ArrayList<>();
        private FindingFingerprint failCommentFingerprint;
        private GitHubFailureException commentFailure;
        private boolean commentFailureRepeats;
        private FindingFingerprint failRetractionFingerprint;
        private GitHubFailureException retractionFailure;
        private GitHubFailureException checkFailure;
        private ReviewRun observedRun;
        private ReviewRunState stateAtNeutralCheck;
        private String replacementCheckId;
        private FindingFingerprint replacementCommentFingerprint;
        private String replacementCommentId;
        private String checkIdObservedAtFirstComment;

        private RecordingGateway(AuthoritativeRevision authoritative) {
            this.authoritative = authoritative;
        }

        @Override
        public AuthoritativeRevision authoritativeRevision(PullRequestRevision revision) {
            authoritativeCalls++;
            requestedRevision = revision;
            return authoritative;
        }

        @Override
        public CheckRunArtifact upsertCheck(CheckRunRequest request) {
            checkMutations++;
            checkRequests.add(request);
            if (request.presentation().outcome()
                    == GitHubPublicationGateway.CheckOutcome.NEUTRAL_SYSTEM_FAILURE
                    && observedRun != null) {
                stateAtNeutralCheck = observedRun.state();
            }
            if (checkFailure != null) {
                GitHubFailureException failure = checkFailure;
                checkFailure = null;
                throw failure;
            }
            if (replacementCheckId != null) {
                return new CheckRunArtifact(
                        replacementCheckId,
                        CheckRunArtifact.Reconciliation.REPLACED_MISSING);
            }
            return new CheckRunArtifact(
                    request.existingGitHubArtifactId().orElse("check-123"));
        }

        @Override
        public InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request) {
            commentMutations++;
            commentRequests.add(request);
            if (checkIdObservedAtFirstComment == null && observedRun != null) {
                checkIdObservedAtFirstComment = observedRun.checkRunExternalId().orElse(null);
            }
            if (commentFailure != null
                    && request.finding().fingerprint().equals(failCommentFingerprint)) {
                GitHubFailureException failure = commentFailure;
                if (!commentFailureRepeats) {
                    commentFailure = null;
                }
                throw failure;
            }
            if (request.finding().fingerprint().equals(replacementCommentFingerprint)) {
                return new InlineCommentArtifact(
                        request.finding().fingerprint(),
                        replacementCommentId,
                        InlineCommentArtifact.Reconciliation.REPLACED_MISSING);
            }
            return new InlineCommentArtifact(
                    request.finding().fingerprint(),
                    "comment-" + request.finding().fingerprint().value().substring(0, 8));
        }

        @Override
        public InlineCommentRetraction retractInlineComment(
                InlineCommentRetractionRequest request) {
            retractionRequests.add(request);
            if (retractionFailure != null
                    && request.fingerprint().equals(failRetractionFingerprint)) {
                GitHubFailureException failure = retractionFailure;
                retractionFailure = null;
                throw failure;
            }
            return new InlineCommentRetraction(
                    request.fingerprint(), request.reference().externalId());
        }

        private void failCommentOnce(
                FindingFingerprint fingerprint, GitHubFailureException failure) {
            failCommentFingerprint = fingerprint;
            commentFailure = failure;
            commentFailureRepeats = false;
        }

        private void failCommentAlways(
                FindingFingerprint fingerprint, GitHubFailureException failure) {
            failCommentFingerprint = fingerprint;
            commentFailure = failure;
            commentFailureRepeats = true;
        }

        private void failRetractionOnce(
                FindingFingerprint fingerprint, GitHubFailureException failure) {
            failRetractionFingerprint = fingerprint;
            retractionFailure = failure;
        }

        private void failCheckOnce(GitHubFailureException failure) {
            checkFailure = failure;
        }

        private void observeRun(ReviewRun run) {
            observedRun = run;
        }

        private void replaceMissingCheckWith(String replacementCheckId) {
            this.replacementCheckId = replacementCheckId;
        }

        private void replaceMissingCommentWith(
                FindingFingerprint fingerprint, String replacementCommentId) {
            this.replacementCommentFingerprint = fingerprint;
            this.replacementCommentId = replacementCommentId;
        }

        private List<String> commentFingerprints() {
            return commentRequests.stream()
                    .map(request -> request.finding().fingerprint().value())
                    .toList();
        }

        private List<String> retractedFingerprints() {
            return retractionRequests.stream()
                    .map(request -> request.fingerprint().value())
                    .toList();
        }
    }

    private record ProgressSnapshot(
            long expectedVersion,
            ReviewRunState state,
            String checkId,
            int commentCount) {
    }

    private static final class FailingGateway implements GitHubPublicationGateway {
        private final GitHubFailureException failure;

        private FailingGateway(GitHubFailureException failure) {
            this.failure = failure;
        }

        @Override
        public AuthoritativeRevision authoritativeRevision(PullRequestRevision revision) {
            throw failure;
        }

        @Override
        public CheckRunArtifact upsertCheck(CheckRunRequest request) {
            throw new AssertionError("lookup failure must prevent Check mutation");
        }

        @Override
        public InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request) {
            throw new AssertionError("lookup failure must prevent comment mutation");
        }

        @Override
        public InlineCommentRetraction retractInlineComment(
                InlineCommentRetractionRequest request) {
            throw new AssertionError("lookup failure must prevent comment retraction");
        }
    }
}
