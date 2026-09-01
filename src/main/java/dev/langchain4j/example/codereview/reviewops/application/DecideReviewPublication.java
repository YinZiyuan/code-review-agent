package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.domain.FindingPublicationPolicy;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationPolicySnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class DecideReviewPublication {

    public static final String PUBLISH_REVIEW_JOB_TYPE = "PUBLISH_REVIEW";

    private final ReviewRunRepository reviewRuns;
    private final ReviewRunMutationStore mutations;
    private final FindingPublicationPolicy policy;
    private final PublicationPolicySnapshot policySnapshot;

    public DecideReviewPublication(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            FindingPublicationPolicy policy,
            PublicationPolicySnapshot policySnapshot) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.policySnapshot = Objects.requireNonNull(policySnapshot, "policySnapshot");
    }

    public DecisionOutcome decide(ReviewRunId id) {
        Objects.requireNonNull(id, "id");
        ReviewRunRepository.StoredReviewRun stored = reviewRuns.find(id).orElse(null);
        if (stored == null) {
            return DecisionOutcome.NOT_FOUND;
        }
        ReviewRun run = stored.reviewRun();
        if (run.state() != ReviewRunState.COMPLETED) {
            return DecisionOutcome.ALREADY_PROCESSED;
        }
        if (!run.findings().isEmpty() && run.findings().stream()
                .allMatch(finding -> finding.publicationDecision().isPresent())) {
            return DecisionOutcome.ALREADY_PROCESSED;
        }
        if (!run.configuration().policyVersion().equals(policySnapshot.version())) {
            throw new IllegalArgumentException(
                    "publication policy snapshot must match the review configuration");
        }

        run.acceptPublicationDecisions(policy.decide(run.findings(), policySnapshot));
        Instant decidedAt = run.attempts().get(run.attempts().size() - 1)
                .endedAt()
                .orElseThrow(() -> new IllegalStateException(
                        "completed review must have persisted completion time"));
        DurableJobRequest publication = new DurableJobRequest(
                PUBLISH_REVIEW_JOB_TYPE,
                run.id().value(),
                run.configuration().maxReviewAttempts(),
                decidedAt,
                "publish-review:" + run.id().value());
        mutations.saveAndEnqueue(
                run, stored.version(), List.of(publication), List.of());
        return DecisionOutcome.DECIDED;
    }

    public enum DecisionOutcome {
        DECIDED,
        ALREADY_PROCESSED,
        NOT_FOUND
    }
}
