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

    private static ReviewConfigurationSnapshot configuration() {
        return new ReviewConfigurationSnapshot(
                "pipeline-v2", "configuration-v5", "model-v3", "policy-v4", 3);
    }
}
