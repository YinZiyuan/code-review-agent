package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRunReconstitutionTest {
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-31T00:00:00Z");
    private static final Instant COMPLETED_AT = REQUESTED_AT.plusSeconds(5);
    private static final ExecutionMeasurements MEASUREMENTS =
            new ExecutionMeasurements(5, 2, 1, Map.of("regex", "RAN"));

    @Test
    void reconstitutesCompletedReviewWithoutRecordingEvents() {
        ReviewRun original = ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(17, 29, 41, "head-sha"),
                new ReviewConfigurationSnapshot(
                        "pipeline-v2", "configuration-v5", "model-v3", "policy-v4", 3),
                REQUESTED_AT);
        original.startAttempt(REQUESTED_AT);
        original.completeReview(List.of(ReviewFindingTest.finding("regex", List.of())),
                MEASUREMENTS, COMPLETED_AT);
        original.acceptPublicationDecisions(Map.of(
                original.findings().get(0).fingerprint(),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "policy-v4")));
        original.drainEvents();

        ReviewAttempt persistedAttempt = original.attempts().get(0);
        ReviewFinding persistedFinding = original.findings().get(0);
        ReviewRun reconstituted = ReviewRun.reconstitute(
                original.id(), original.revision(), original.configuration(), original.requestedAt(),
                original.state(),
                List.of(ReviewAttempt.reconstitute(
                        persistedAttempt.attemptNumber(), persistedAttempt.startedAt(), persistedAttempt.state(),
                        persistedAttempt.endedAt().orElse(null), persistedAttempt.measurements().orElse(null),
                        persistedAttempt.failure().orElse(null))),
                List.of(ReviewFinding.reconstitute(
                        persistedFinding.fingerprint(), persistedFinding.location(), persistedFinding.content(),
                        persistedFinding.evidence(), persistedFinding.publicationDecision().orElse(null),
                        persistedFinding.publicationReference().orElse(null))),
                original.finalFailure().orElse(null), original.finishedAt().orElse(null),
                original.checkRunExternalId().orElse(null));

        assertThat(reconstituted.id()).isEqualTo(original.id());
        assertThat(reconstituted.revision()).isEqualTo(original.revision());
        assertThat(reconstituted.configuration()).isEqualTo(original.configuration());
        assertThat(reconstituted.requestedAt()).isEqualTo(original.requestedAt());
        assertThat(reconstituted.state()).isEqualTo(ReviewRunState.COMPLETED);
        assertThat(reconstituted.attempts()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(original.attempts());
        assertThat(reconstituted.findings()).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(original.findings());
        assertThat(reconstituted.commentReferences()).isEmpty();
        assertThat(reconstituted.drainEvents()).isEmpty();
    }

    @Test
    void rejectsRunningReviewWithoutStartedLastAttempt() {
        ReviewAttempt successfulAttempt = ReviewAttempt.reconstitute(
                1, REQUESTED_AT, ReviewAttemptState.SUCCEEDED, COMPLETED_AT, MEASUREMENTS, null);

        assertThatThrownBy(() -> ReviewRun.reconstitute(
                ReviewRunId.newId(), new PullRequestRevision(17, 29, 41, "head-sha"), configuration(),
                REQUESTED_AT, ReviewRunState.RUNNING, List.of(successfulAttempt), List.of(),
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRunningReviewWhenEarlierAttemptIsStillStarted() {
        ReviewAttempt firstAttempt = ReviewAttempt.reconstitute(
                1, REQUESTED_AT, ReviewAttemptState.STARTED, null, null, null);
        ReviewAttempt secondAttempt = ReviewAttempt.reconstitute(
                2, REQUESTED_AT.plusSeconds(1), ReviewAttemptState.STARTED, null, null, null);

        assertThatThrownBy(() -> ReviewRun.reconstitute(
                ReviewRunId.newId(), new PullRequestRevision(17, 29, 41, "head-sha"), configuration(),
                REQUESTED_AT, ReviewRunState.RUNNING, List.of(firstAttempt, secondAttempt), List.of(),
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPublishedReviewWithoutFinishedAt() {
        ReviewAttempt successfulAttempt = ReviewAttempt.reconstitute(
                1, REQUESTED_AT, ReviewAttemptState.SUCCEEDED, COMPLETED_AT, MEASUREMENTS, null);

        assertThatThrownBy(() -> ReviewRun.reconstitute(
                ReviewRunId.newId(), new PullRequestRevision(17, 29, 41, "head-sha"), configuration(),
                REQUESTED_AT, ReviewRunState.PUBLISHED, List.of(successfulAttempt), List.of(),
                null, null, "check-123")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reconstitutesPublishingReviewWithPartialExternalProgress() {
        ReviewAttempt successfulAttempt = ReviewAttempt.reconstitute(
                1, REQUESTED_AT, ReviewAttemptState.SUCCEEDED, COMPLETED_AT, MEASUREMENTS, null);
        ReviewFinding first = ReviewFindingTest.finding("regex", List.of());
        ReviewFinding second = ReviewFindingTest.finding("spotbugs", List.of());
        PublicationDecision inline = new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v4");
        PublicationReference confirmed = new PublicationReference("REVIEW_COMMENT", "comment-1");

        ReviewRun restored = ReviewRun.reconstitute(
                ReviewRunId.newId(), new PullRequestRevision(17, 29, 41, "head-sha"), configuration(),
                REQUESTED_AT, ReviewRunState.PUBLISHING, List.of(successfulAttempt), List.of(
                        ReviewFinding.reconstitute(
                                first.fingerprint(), first.location(), first.content(), first.evidence(),
                                inline, confirmed),
                        ReviewFinding.reconstitute(
                                new FindingFingerprint(
                                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                                second.location(), second.content(), second.evidence(),
                                inline, null)),
                null, null, "check-123");

        assertThat(restored.state()).isEqualTo(ReviewRunState.PUBLISHING);
        assertThat(restored.checkRunExternalId()).contains("check-123");
        assertThat(restored.findings().get(0).publicationReference()).contains(confirmed);
        assertThat(restored.findings().get(1).publicationReference()).isEmpty();
        assertThat(restored.drainEvents()).isEmpty();
    }

    @Test
    void reconstitutesFailedPublicationWithoutDiscardingConfirmedArtifacts() {
        ReviewAttempt successfulAttempt = ReviewAttempt.reconstitute(
                1, REQUESTED_AT, ReviewAttemptState.SUCCEEDED, COMPLETED_AT, MEASUREMENTS, null);
        ReviewFinding finding = ReviewFindingTest.finding("regex", List.of());
        PublicationReference confirmed = new PublicationReference("REVIEW_COMMENT", "comment-1");

        ReviewRun restored = ReviewRun.reconstitute(
                ReviewRunId.newId(), new PullRequestRevision(17, 29, 41, "head-sha"), configuration(),
                REQUESTED_AT, ReviewRunState.FAILED, List.of(successfulAttempt), List.of(
                        ReviewFinding.reconstitute(
                                finding.fingerprint(), finding.location(), finding.content(), finding.evidence(),
                                new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v4"), confirmed)),
                new ReviewFailure("github-failure", FailureClass.TERMINAL, "publication failed"),
                COMPLETED_AT.plusSeconds(1), "check-123");

        assertThat(restored.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(restored.checkRunExternalId()).contains("check-123");
        assertThat(restored.findings().get(0).publicationReference()).contains(confirmed);
    }

    @Test
    void reconstitutesFailedExecutionAfterNeutralCheckWasConfirmed() {
        ReviewFailure failure = new ReviewFailure(
                "invalid_review_output", FailureClass.TERMINAL, "review output was invalid");
        ReviewAttempt failedAttempt = ReviewAttempt.reconstitute(
                1, REQUESTED_AT, ReviewAttemptState.TERMINAL_FAILURE, COMPLETED_AT,
                MEASUREMENTS, failure);

        ReviewRun restored = ReviewRun.reconstitute(
                ReviewRunId.newId(), new PullRequestRevision(17, 29, 41, "head-sha"),
                configuration(), REQUESTED_AT, ReviewRunState.FAILED,
                List.of(failedAttempt), List.of(), failure, COMPLETED_AT, "check-123");

        assertThat(restored.checkRunExternalId()).contains("check-123");
        assertThat(restored.commentReferences()).isEmpty();
    }

    @Test
    void reconstitutesFailedDecisionAfterNeutralCheckWasConfirmed() {
        ReviewAttempt successfulAttempt = ReviewAttempt.reconstitute(
                1, REQUESTED_AT, ReviewAttemptState.SUCCEEDED, COMPLETED_AT, MEASUREMENTS, null);
        ReviewFinding undecided = ReviewFindingTest.finding("regex", List.of());
        ReviewFailure failure = new ReviewFailure(
                "decision_failed", FailureClass.TERMINAL, "publication decision failed");

        ReviewRun restored = ReviewRun.reconstitute(
                ReviewRunId.newId(), new PullRequestRevision(17, 29, 41, "head-sha"),
                configuration(), REQUESTED_AT, ReviewRunState.FAILED,
                List.of(successfulAttempt), List.of(undecided), failure,
                COMPLETED_AT.plusSeconds(1), "check-123");

        assertThat(restored.checkRunExternalId()).contains("check-123");
        assertThat(restored.findings().get(0).publicationDecision()).isEmpty();
    }

    @Test
    void reconstitutesSupersededPublicationWithoutDiscardingConfirmedArtifacts() {
        ReviewAttempt successfulAttempt = ReviewAttempt.reconstitute(
                1, REQUESTED_AT, ReviewAttemptState.SUCCEEDED, COMPLETED_AT, MEASUREMENTS, null);
        ReviewFinding finding = ReviewFindingTest.finding("regex", List.of());
        PublicationReference confirmed = new PublicationReference("REVIEW_COMMENT", "comment-1");

        ReviewRun restored = ReviewRun.reconstitute(
                ReviewRunId.newId(), new PullRequestRevision(17, 29, 41, "head-sha"), configuration(),
                REQUESTED_AT, ReviewRunState.SUPERSEDED, List.of(successfulAttempt), List.of(
                        ReviewFinding.reconstitute(
                                finding.fingerprint(), finding.location(), finding.content(), finding.evidence(),
                                new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v4"), confirmed)),
                null, COMPLETED_AT.plusSeconds(1), "check-123");

        assertThat(restored.state()).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThat(restored.checkRunExternalId()).contains("check-123");
        assertThat(restored.findings().get(0).publicationReference()).contains(confirmed);
    }

    @Test
    void rejectsConfirmedPublicationArtifactsWithoutTheirCheckRunIdentity() {
        ReviewAttempt successfulAttempt = ReviewAttempt.reconstitute(
                1, REQUESTED_AT, ReviewAttemptState.SUCCEEDED, COMPLETED_AT, MEASUREMENTS, null);
        ReviewFinding finding = ReviewFindingTest.finding("regex", List.of());

        assertThatThrownBy(() -> ReviewRun.reconstitute(
                ReviewRunId.newId(), new PullRequestRevision(17, 29, 41, "head-sha"), configuration(),
                REQUESTED_AT, ReviewRunState.PUBLISHING, List.of(successfulAttempt), List.of(
                        ReviewFinding.reconstitute(
                                finding.fingerprint(), finding.location(), finding.content(), finding.evidence(),
                                new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v4"),
                                new PublicationReference("REVIEW_COMMENT", "comment-1"))),
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publication references require a check run external id");
    }

    private static ReviewConfigurationSnapshot configuration() {
        return new ReviewConfigurationSnapshot(
                "pipeline-v2", "configuration-v5", "model-v3", "policy-v4", 3);
    }
}
