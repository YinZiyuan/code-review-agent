package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingFeedbackTest {
    private static final Instant T0 = Instant.parse("2026-08-30T00:00:00Z");
    private static final DeveloperVisibleFindingReference REF =
            new DeveloperVisibleFindingReference(ReviewRunId.newId(),
                    new FindingFingerprint("a".repeat(64)));
    private static final GitHubActor ACTOR = new GitHubActor(42, "octocat");

    @Test
    void reactionCanBeRevisedWithdrawnAndRestored() {
        FindingFeedback feedback = FindingFeedback.record(REF, ACTOR,
                new ObservedReaction(100, FeedbackState.HELPFUL, T0));
        feedback.reconcile(Optional.of(
                new ObservedReaction(101, FeedbackState.FALSE_POSITIVE, T0.plusSeconds(1))),
                T0.plusSeconds(1));
        feedback.reconcile(Optional.empty(), T0.plusSeconds(2));
        feedback.reconcile(Optional.of(
                new ObservedReaction(102, FeedbackState.HELPFUL, T0.plusSeconds(3))),
                T0.plusSeconds(3));

        assertThat(feedback.state()).isEqualTo(FeedbackState.HELPFUL);
        assertThat(feedback.audit()).extracting(FeedbackAuditEntry::current)
                .containsExactly(FeedbackState.HELPFUL, FeedbackState.FALSE_POSITIVE,
                        FeedbackState.WITHDRAWN, FeedbackState.HELPFUL);
    }

    @Test
    void repeatedObservationIsIdempotent() {
        ObservedReaction reaction = new ObservedReaction(100, FeedbackState.HELPFUL, T0);
        FindingFeedback feedback = FindingFeedback.record(REF, ACTOR, reaction);
        feedback.reconcile(Optional.of(reaction), T0.plusSeconds(1));
        assertThat(feedback.audit()).hasSize(1);
    }

    @Test
    void withdrawingAnAlreadyWithdrawnAssessmentIsIdempotent() {
        FindingFeedback feedback = FindingFeedback.record(REF, ACTOR,
                new ObservedReaction(100, FeedbackState.HELPFUL, T0));
        feedback.reconcile(Optional.empty(), T0.plusSeconds(1));
        feedback.reconcile(Optional.empty(), T0.plusSeconds(2));
        assertThat(feedback.audit()).hasSize(2);
    }

    @Test
    void conflictingClassificationForCurrentReactionIdentityIsRejectedAtomically() {
        FindingFeedback feedback = FindingFeedback.record(REF, ACTOR,
                new ObservedReaction(100, FeedbackState.HELPFUL, T0));

        assertThatThrownBy(() -> feedback.reconcile(Optional.of(
                new ObservedReaction(100, FeedbackState.FALSE_POSITIVE, T0.plusSeconds(1))),
                T0.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(feedback.state()).isEqualTo(FeedbackState.HELPFUL);
        assertThat(feedback.reactionId()).contains(100L);
        assertThat(feedback.audit()).containsExactly(
                new FeedbackAuditEntry(null, FeedbackState.HELPFUL, T0, 100L));
    }

    @Test
    void presentReactionTransitionsUseReactionCreationTimeWhileWithdrawalUsesObservationTime() {
        Instant revisedAt = T0.plusSeconds(2);
        Instant withdrawalObservedAt = T0.plusSeconds(20);
        Instant restoredAt = T0.plusSeconds(22);
        FindingFeedback feedback = FindingFeedback.record(REF, ACTOR,
                new ObservedReaction(100, FeedbackState.HELPFUL, T0));

        feedback.reconcile(Optional.of(
                new ObservedReaction(101, FeedbackState.FALSE_POSITIVE, revisedAt)),
                T0.plusSeconds(10));
        feedback.reconcile(Optional.empty(), withdrawalObservedAt);
        feedback.reconcile(Optional.of(
                new ObservedReaction(102, FeedbackState.HELPFUL, restoredAt)),
                T0.plusSeconds(30));

        assertThat(feedback.audit()).extracting(FeedbackAuditEntry::changedAt)
                .containsExactly(T0, revisedAt, withdrawalObservedAt, restoredAt);
    }
}
