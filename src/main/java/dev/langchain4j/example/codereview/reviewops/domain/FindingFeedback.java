package dev.langchain4j.example.codereview.reviewops.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class FindingFeedback {
    private final FindingFeedbackId id;
    private final DeveloperVisibleFindingReference findingReference;
    private final GitHubActor actor;
    private final List<FeedbackAuditEntry> audit = new ArrayList<>();
    private FeedbackState state;
    private Long reactionId;

    private FindingFeedback(DeveloperVisibleFindingReference reference,
                            GitHubActor actor, ObservedReaction reaction) {
        this.findingReference = Objects.requireNonNull(reference, "reference");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.id = new FindingFeedbackId(reference.reviewRunId(),
                reference.findingFingerprint(), actor.id());
        apply(reaction.classification(), reaction.reactionId(), reaction.createdAt());
    }

    public static FindingFeedback record(DeveloperVisibleFindingReference reference,
                                         GitHubActor actor, ObservedReaction reaction) {
        return new FindingFeedback(reference, actor, Objects.requireNonNull(reaction, "reaction"));
    }

    public void reconcile(Optional<ObservedReaction> observation, Instant observedAt) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(observedAt, "observedAt");
        if (observation.isEmpty()) {
            if (state != FeedbackState.WITHDRAWN) {
                apply(FeedbackState.WITHDRAWN, null, observedAt);
            }
            return;
        }
        ObservedReaction reaction = observation.get();
        if (Objects.equals(reactionId, reaction.reactionId())) {
            if (state != reaction.classification()) {
                throw new IllegalArgumentException(
                        "current reaction identity cannot change classification");
            }
            return;
        }
        apply(reaction.classification(), reaction.reactionId(), reaction.createdAt());
    }

    private void apply(FeedbackState next, Long nextReactionId, Instant changedAt) {
        audit.add(new FeedbackAuditEntry(state, next, changedAt, nextReactionId));
        state = next;
        reactionId = nextReactionId;
    }

    public FindingFeedbackId id() {
        return id;
    }

    public DeveloperVisibleFindingReference findingReference() {
        return findingReference;
    }

    public GitHubActor actor() {
        return actor;
    }

    public FeedbackState state() {
        return state;
    }

    public Optional<Long> reactionId() {
        return Optional.ofNullable(reactionId);
    }

    public List<FeedbackAuditEntry> audit() {
        return List.copyOf(audit);
    }
}
