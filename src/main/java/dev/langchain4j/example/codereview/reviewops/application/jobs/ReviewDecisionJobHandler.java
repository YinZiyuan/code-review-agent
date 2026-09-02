package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.DecideReviewPublication;
import dev.langchain4j.example.codereview.reviewops.application.ExecuteReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.util.Objects;

public final class ReviewDecisionJobHandler implements ReviewJobHandler {

    private final DecideReviewPublication decidePublication;

    public ReviewDecisionJobHandler(DecideReviewPublication decidePublication) {
        this.decidePublication = Objects.requireNonNull(decidePublication, "decidePublication");
    }

    @Override
    public String jobType() {
        return ExecuteReviewRun.DECIDE_PUBLICATION_JOB_TYPE;
    }

    @Override
    public JobOutcome handle(LeasedJob job) {
        DecideReviewPublication.DecisionOutcome outcome = decidePublication.decide(
                new ReviewRunId(Objects.requireNonNull(job, "job").payloadReference()));
        return switch (outcome) {
            case DECIDED, ALREADY_PROCESSED -> JobOutcome.succeeded();
            case NOT_FOUND -> JobOutcome.terminalFailure("review_run_not_found");
        };
    }

}
