package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRunTest {
    private static final Instant T0 = Instant.parse("2026-08-30T00:00:00Z");
    private static final ExecutionMeasurements METRICS =
            new ExecutionMeasurements(10, 1, 1, Map.of());

    @Test
    void transientFailureReturnsToRequestedUntilAttemptsExhausted() {
        ReviewRun run = requested(2);
        run.startAttempt(T0);
        run.recordTransientAttemptFailure(transientFailure(), METRICS, T0.plusSeconds(1));
        assertThat(run.state()).isEqualTo(ReviewRunState.REQUESTED);

        run.startAttempt(T0.plusSeconds(2));
        run.recordTransientAttemptFailure(transientFailure(), METRICS, T0.plusSeconds(3));
        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.attempts()).hasSize(2);
    }

    @Test
    void completingReviewFreezesFindingsAndRecordsOneEvent() {
        ReviewRun run = requested(3);
        run.startAttempt(T0);
        ReviewFinding finding = ReviewFindingTest.finding("regex", List.of());
        run.completeReview(List.of(finding), METRICS, T0.plusSeconds(1));

        assertThat(run.state()).isEqualTo(ReviewRunState.COMPLETED);
        assertThat(run.findings()).containsExactly(finding);
        assertThat(run.drainEvents()).containsExactly(
                new ReviewRunCompleted(run.id(), T0.plusSeconds(1)));
        assertThat(run.drainEvents()).isEmpty();
        assertThatThrownBy(() -> run.completeReview(List.of(), METRICS, T0.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void staleAuthoritativeRevisionSupersedesCompletedRun() {
        ReviewRun run = completed();
        run.authorizePublication(new AuthoritativeRevision("new-sha"), T0.plusSeconds(2));
        assertThat(run.state()).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThatThrownBy(() -> run.confirmPublication("check-1", Map.of(), T0.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void currentRevisionCanPublishAfterEveryFindingHasDecision() {
        ReviewRun run = completed();
        Map<FindingFingerprint, PublicationDecision> decisions = Map.of(
                run.findings().get(0).fingerprint(),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "publish-v1"));
        run.acceptPublicationDecisions(decisions);
        run.authorizePublication(new AuthoritativeRevision("sha"), T0.plusSeconds(2));
        run.confirmPublication("check-1", Map.of(), T0.plusSeconds(3));

        assertThat(run.state()).isEqualTo(ReviewRunState.PUBLISHED);
        assertThat(run.checkRunExternalId()).contains("check-1");
    }

    @Test
    void decisionsMustCoverExactlyTheCompletedFindings() {
        ReviewRun run = completed();
        assertThatThrownBy(() -> run.acceptPublicationDecisions(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void terminalAttemptFailureEndsReviewWithFinalFailure() {
        ReviewRun run = requested(3);
        ReviewFailure failure = new ReviewFailure("bad_diff", FailureClass.TERMINAL, "invalid patch");
        run.startAttempt(T0);

        run.recordTerminalAttemptFailure(failure, METRICS, T0.plusSeconds(1));

        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.finalFailure()).contains(failure);
        assertThat(run.finishedAt()).contains(T0.plusSeconds(1));
    }

    @Test
    void terminalPublicationFailureEndsAuthorizedPublication() {
        ReviewRun run = completed();
        run.acceptPublicationDecisions(Map.of(
                run.findings().get(0).fingerprint(),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "publish-v1")));
        run.authorizePublication(new AuthoritativeRevision("sha"), T0.plusSeconds(2));
        ReviewFailure failure = new ReviewFailure("github", FailureClass.TERMINAL, "GitHub rejected check");

        run.recordPublicationFailure(failure, T0.plusSeconds(3));

        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.finalFailure()).contains(failure);
        assertThat(run.finishedAt()).contains(T0.plusSeconds(3));
    }

    @Test
    void publicationCommentReferencesMustBelongToInlineFindings() {
        ReviewRun run = completed();
        FindingFingerprint fingerprint = run.findings().get(0).fingerprint();
        run.acceptPublicationDecisions(Map.of(
                fingerprint, new PublicationDecision(PublicationTier.CHECK_SUMMARY, "publish-v1")));
        run.authorizePublication(new AuthoritativeRevision("sha"), T0.plusSeconds(2));

        assertThatThrownBy(() -> run.confirmPublication("check-1", Map.of(
                fingerprint, new PublicationReference("REVIEW_COMMENT", "comment-1")), T0.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void confirmedInlineCommentsAreRecordedAgainstTheirFindings() {
        ReviewRun run = completedWithTwoFindings();
        FindingFingerprint firstFingerprint = run.findings().get(0).fingerprint();
        FindingFingerprint secondFingerprint = run.findings().get(1).fingerprint();
        PublicationReference firstReference = new PublicationReference("REVIEW_COMMENT", "comment-1");
        PublicationReference secondReference = new PublicationReference("REVIEW_COMMENT", "comment-2");
        run.acceptPublicationDecisions(Map.of(
                firstFingerprint, new PublicationDecision(PublicationTier.INLINE_COMMENT, "publish-v1"),
                secondFingerprint, new PublicationDecision(PublicationTier.INLINE_COMMENT, "publish-v1")));
        run.authorizePublication(new AuthoritativeRevision("sha"), T0.plusSeconds(2));

        run.confirmPublication("check-1", Map.of(
                firstFingerprint, firstReference,
                secondFingerprint, secondReference), T0.plusSeconds(3));

        assertThat(run.findings().get(0).publicationReference()).contains(firstReference);
        assertThat(run.findings().get(1).publicationReference()).contains(secondReference);
        assertThat(run.commentReferences()).containsExactlyInAnyOrderEntriesOf(Map.of(
                firstFingerprint, firstReference,
                secondFingerprint, secondReference));
        assertThatThrownBy(() -> run.commentReferences().put(firstFingerprint, firstReference))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyInlineCommentCoverageLeavesPublicationUnchanged() {
        ReviewRun run = completed();
        FindingFingerprint fingerprint = run.findings().get(0).fingerprint();
        run.acceptPublicationDecisions(Map.of(
                fingerprint, new PublicationDecision(PublicationTier.INLINE_COMMENT, "publish-v1")));
        run.authorizePublication(new AuthoritativeRevision("sha"), T0.plusSeconds(2));

        assertThatThrownBy(() -> run.confirmPublication("check-1", Map.of(), T0.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);

        assertUnconfirmedPublication(run);
        assertThat(run.findings().get(0).publicationReference()).isEmpty();
    }

    @Test
    void partialInlineCommentCoverageLeavesEveryFindingUnchanged() {
        ReviewRun run = completedWithTwoFindings();
        ReviewFinding first = run.findings().get(0);
        ReviewFinding second = run.findings().get(1);
        run.acceptPublicationDecisions(Map.of(
                first.fingerprint(), new PublicationDecision(PublicationTier.INLINE_COMMENT, "publish-v1"),
                second.fingerprint(), new PublicationDecision(PublicationTier.INLINE_COMMENT, "publish-v1")));
        run.authorizePublication(new AuthoritativeRevision("sha"), T0.plusSeconds(2));

        assertThatThrownBy(() -> run.confirmPublication("check-1", Map.of(
                first.fingerprint(), new PublicationReference("REVIEW_COMMENT", "comment-1")),
                T0.plusSeconds(3)))
                .isInstanceOf(IllegalArgumentException.class);

        assertUnconfirmedPublication(run);
        assertThat(first.publicationReference()).isEmpty();
        assertThat(second.publicationReference()).isEmpty();
    }

    @Test
    void supersedeRejectsMatchingRevisionAndTerminalState() {
        ReviewRun run = requested(3);

        assertThatThrownBy(() -> run.supersede(new AuthoritativeRevision("sha"), T0.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        run.supersede(new AuthoritativeRevision("new-sha"), T0.plusSeconds(1));
        assertThat(run.state()).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThatThrownBy(() -> run.supersede(new AuthoritativeRevision("another-sha"), T0.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void supersedingRunningReviewCancelsAttemptWithoutFabricatedExecutionEvidence() {
        ReviewRun run = requested(3);
        ReviewAttempt attempt = run.startAttempt(T0);

        run.supersede(new AuthoritativeRevision("new-sha"), T0.plusSeconds(1));

        assertThat(run.state()).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThat(run.finishedAt()).contains(T0.plusSeconds(1));
        assertThat(run.findings()).isEmpty();
        assertThat(attempt.state()).isEqualTo(ReviewAttemptState.CANCELLED);
        assertThat(attempt.endedAt()).contains(T0.plusSeconds(1));
        assertThat(attempt.measurements()).isEmpty();
        assertThat(attempt.failure()).isEmpty();
        assertThatThrownBy(() -> run.startAttempt(T0.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.completeReview(
                List.of(ReviewFindingTest.finding("regex", List.of())), METRICS, T0.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.authorizePublication(
                new AuthoritativeRevision("sha"), T0.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidRunningSupersessionTimeLeavesRunAndAttemptUnchanged() {
        ReviewRun run = requested(3);
        ReviewAttempt attempt = run.startAttempt(T0);

        assertThatThrownBy(() -> run.supersede(
                new AuthoritativeRevision("new-sha"), T0.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(run.state()).isEqualTo(ReviewRunState.RUNNING);
        assertThat(run.finishedAt()).isEmpty();
        assertThat(attempt.state()).isEqualTo(ReviewAttemptState.STARTED);
        assertThat(attempt.endedAt()).isEmpty();
        assertThat(attempt.measurements()).isEmpty();
        assertThat(attempt.failure()).isEmpty();
    }

    @Test
    void childCommandsAreNotPublicOutsideTheDomainBoundary() throws NoSuchMethodException {
        assertThat(Modifier.isPublic(ReviewAttempt.class.getDeclaredMethod(
                "succeed", ExecutionMeasurements.class, Instant.class).getModifiers())).isFalse();
        assertThat(Modifier.isPublic(ReviewAttempt.class.getDeclaredMethod(
                "failTransient", ReviewFailure.class, ExecutionMeasurements.class, Instant.class)
                .getModifiers())).isFalse();
        assertThat(Modifier.isPublic(ReviewAttempt.class.getDeclaredMethod(
                "cancel", Instant.class).getModifiers())).isFalse();
        assertThat(Modifier.isPublic(ReviewFinding.class.getDeclaredMethod(
                "acceptPublicationDecision", PublicationDecision.class).getModifiers())).isFalse();
        assertThat(Modifier.isPublic(ReviewFinding.class.getDeclaredMethod(
                "recordPublicationReference", PublicationReference.class).getModifiers())).isFalse();
    }

    @Test
    void nullCompletedFindingsLeaveRunAndAttemptUnchanged() {
        ReviewRun run = requested(3);
        ReviewAttempt attempt = run.startAttempt(T0);

        assertThatThrownBy(() -> run.completeReview(null, METRICS, T0.plusSeconds(1)))
                .isInstanceOf(NullPointerException.class);

        assertThat(run.state()).isEqualTo(ReviewRunState.RUNNING);
        assertThat(attempt.state()).isEqualTo(ReviewAttemptState.STARTED);
        assertThat(run.findings()).isEmpty();
        assertThat(run.drainEvents()).isEmpty();
    }

    @Test
    void duplicateCompletedFindingsLeaveRunAndAttemptUnchanged() {
        ReviewRun run = requested(3);
        ReviewAttempt attempt = run.startAttempt(T0);
        ReviewFinding finding = ReviewFindingTest.finding("regex", List.of());

        assertThatThrownBy(() -> run.completeReview(List.of(finding, finding), METRICS, T0.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(run.state()).isEqualTo(ReviewRunState.RUNNING);
        assertThat(attempt.state()).isEqualTo(ReviewAttemptState.STARTED);
        assertThat(run.findings()).isEmpty();
        assertThat(run.drainEvents()).isEmpty();
    }

    @Test
    void chronologyRejectedCompletionLeavesRunAndAttemptUnchanged() {
        ReviewRun run = requested(3);
        ReviewAttempt attempt = run.startAttempt(T0);

        assertThatThrownBy(() -> run.completeReview(List.of(ReviewFindingTest.finding("regex", List.of())),
                METRICS, T0.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(run.state()).isEqualTo(ReviewRunState.RUNNING);
        assertThat(attempt.state()).isEqualTo(ReviewAttemptState.STARTED);
        assertThat(attempt.endedAt()).isEmpty();
        assertThat(attempt.measurements()).isEmpty();
        assertThat(attempt.failure()).isEmpty();
        assertThat(run.findings()).isEmpty();
        assertThat(run.drainEvents()).isEmpty();
    }

    @Test
    void nullPublicationDecisionLeavesAllFindingsUnchanged() {
        ReviewRun run = completedWithTwoFindings();
        ReviewFinding first = run.findings().get(0);
        ReviewFinding second = run.findings().get(1);
        Map<FindingFingerprint, PublicationDecision> decisions = new HashMap<>();
        decisions.put(first.fingerprint(), new PublicationDecision(PublicationTier.CHECK_SUMMARY, "publish-v1"));
        decisions.put(second.fingerprint(), null);

        assertThatThrownBy(() -> run.acceptPublicationDecisions(decisions))
                .isInstanceOf(NullPointerException.class);

        assertThat(run.state()).isEqualTo(ReviewRunState.COMPLETED);
        assertThat(first.publicationDecision()).isEmpty();
        assertThat(second.publicationDecision()).isEmpty();
    }

    @Test
    void mismatchedDecisionPolicyLeavesAllFindingsUnchanged() {
        ReviewRun run = completedWithTwoFindings();
        ReviewFinding first = run.findings().get(0);
        ReviewFinding second = run.findings().get(1);
        Map<FindingFingerprint, PublicationDecision> decisions = Map.of(
                first.fingerprint(), new PublicationDecision(PublicationTier.CHECK_SUMMARY, "publish-v1"),
                second.fingerprint(), new PublicationDecision(PublicationTier.RETAIN_ONLY, "other-policy"));

        assertThatThrownBy(() -> run.acceptPublicationDecisions(decisions))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(run.state()).isEqualTo(ReviewRunState.COMPLETED);
        assertThat(first.publicationDecision()).isEmpty();
        assertThat(second.publicationDecision()).isEmpty();
    }

    @Test
    void missingPublicationFailureTimeLeavesRunUnchanged() {
        ReviewRun run = publishing();
        ReviewFailure failure = new ReviewFailure("github", FailureClass.TERMINAL, "GitHub rejected check");

        assertThatThrownBy(() -> run.recordPublicationFailure(failure, null))
                .isInstanceOf(NullPointerException.class);

        assertThat(run.state()).isEqualTo(ReviewRunState.PUBLISHING);
        assertThat(run.finalFailure()).isEmpty();
        assertThat(run.finishedAt()).isEmpty();
    }

    private static ReviewRun requested(int maxAttempts) {
        return ReviewRun.request(ReviewRunId.newId(),
                new PullRequestRevision(1, 2, 3, "sha"),
                new ReviewConfigurationSnapshot("pipeline", "model", "publish-v1", maxAttempts), T0);
    }

    private static ReviewRun completed() {
        ReviewRun run = requested(3);
        run.startAttempt(T0);
        run.completeReview(List.of(ReviewFindingTest.finding("regex", List.of())),
                METRICS, T0.plusSeconds(1));
        run.drainEvents();
        return run;
    }

    private static ReviewRun completedWithTwoFindings() {
        ReviewRun run = requested(3);
        ReviewFinding first = ReviewFindingTest.finding("regex", List.of());
        CodeLocation secondLocation = new CodeLocation("src/Bar.java", 20, true);
        FindingContent secondContent = new FindingContent(
                FindingSeverity.WARNING, FindingCategory.STABILITY,
                "Unchecked value", "description", "suggestion");
        FindingEvidence secondEvidence = new FindingEvidence("value was not checked", List.of(), "regex");
        ReviewFinding second = new ReviewFinding(
                new FindingFingerprintFactory().create(secondLocation, secondContent, secondEvidence),
                secondLocation, secondContent, secondEvidence);
        run.startAttempt(T0);
        run.completeReview(List.of(first, second), METRICS, T0.plusSeconds(1));
        run.drainEvents();
        return run;
    }

    private static ReviewRun publishing() {
        ReviewRun run = completed();
        run.acceptPublicationDecisions(Map.of(
                run.findings().get(0).fingerprint(),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "publish-v1")));
        run.authorizePublication(new AuthoritativeRevision("sha"), T0.plusSeconds(2));
        return run;
    }

    private static ReviewFailure transientFailure() {
        return new ReviewFailure("timeout", FailureClass.TRANSIENT, "timed out");
    }

    private static void assertUnconfirmedPublication(ReviewRun run) {
        assertThat(run.state()).isEqualTo(ReviewRunState.PUBLISHING);
        assertThat(run.commentReferences()).isEmpty();
        assertThat(run.checkRunExternalId()).isEmpty();
        assertThat(run.finishedAt()).isEmpty();
    }
}
