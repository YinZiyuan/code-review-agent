package dev.langchain4j.example.codereview.reviewops.application.jobs;

import dev.langchain4j.example.codereview.reviewops.application.SupersedeObsoleteReviewRuns;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class SupersedeObsoleteReviewRunsJobHandler implements ReviewJobHandler {

    private final BiFunction<ReviewRunId, OperationFence,
            SupersedeObsoleteReviewRuns.SupersessionOutcome> supersede;

    public SupersedeObsoleteReviewRunsJobHandler(
            SupersedeObsoleteReviewRuns supersedeObsoleteReviewRuns) {
        Objects.requireNonNull(supersedeObsoleteReviewRuns, "supersedeObsoleteReviewRuns");
        this.supersede = supersedeObsoleteReviewRuns::execute;
    }

    SupersedeObsoleteReviewRunsJobHandler(
            Function<ReviewRunId, SupersedeObsoleteReviewRuns.SupersessionOutcome> supersede) {
        Objects.requireNonNull(supersede, "supersede");
        this.supersede = (id, fence) -> supersede.apply(id);
    }

    @Override
    public String jobType() {
        return SupersedeObsoleteReviewRuns.JOB_TYPE;
    }

    @Override
    public JobOutcome handle(LeasedJob job) {
        return handle(job, OperationFence.unfenced());
    }

    @Override
    public JobOutcome handle(LeasedJob job, OperationFence fence) {
        Objects.requireNonNull(job, "job");
        SupersedeObsoleteReviewRuns.SupersessionOutcome outcome = Objects.requireNonNull(
                supersede.apply(new ReviewRunId(job.payloadReference()),
                        Objects.requireNonNull(fence, "fence")),
                "supersession outcome");
        return switch (outcome.status()) {
            case COMPLETED, STALE_SOURCE -> JobOutcome.succeeded();
            case NOT_FOUND -> JobOutcome.terminalFailure("supersession_source_not_found");
        };
    }
}
