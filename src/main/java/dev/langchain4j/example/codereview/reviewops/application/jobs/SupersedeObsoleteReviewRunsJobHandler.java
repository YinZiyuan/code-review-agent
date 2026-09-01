package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.SupersedeObsoleteReviewRuns;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.util.Objects;
import java.util.function.Function;

public final class SupersedeObsoleteReviewRunsJobHandler implements ReviewJobHandler {

    private final Function<ReviewRunId, SupersedeObsoleteReviewRuns.SupersessionOutcome> supersede;

    public SupersedeObsoleteReviewRunsJobHandler(
            SupersedeObsoleteReviewRuns supersedeObsoleteReviewRuns) {
        this(Objects.requireNonNull(
                supersedeObsoleteReviewRuns, "supersedeObsoleteReviewRuns")::execute);
    }

    SupersedeObsoleteReviewRunsJobHandler(
            Function<ReviewRunId, SupersedeObsoleteReviewRuns.SupersessionOutcome> supersede) {
        this.supersede = Objects.requireNonNull(supersede, "supersede");
    }

    @Override
    public String jobType() {
        return SupersedeObsoleteReviewRuns.JOB_TYPE;
    }

    @Override
    public JobOutcome handle(LeasedJob job) {
        Objects.requireNonNull(job, "job");
        SupersedeObsoleteReviewRuns.SupersessionOutcome outcome = Objects.requireNonNull(
                supersede.apply(new ReviewRunId(job.payloadReference())),
                "supersession outcome");
        return switch (outcome.status()) {
            case COMPLETED -> JobOutcome.succeeded();
            case NOT_FOUND -> JobOutcome.terminalFailure("supersession_source_not_found");
        };
    }
}
