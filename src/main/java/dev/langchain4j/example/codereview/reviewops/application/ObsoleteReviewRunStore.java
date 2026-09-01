package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public interface ObsoleteReviewRunStore {

    List<ReviewRunId> findActiveObsoleteRunIds(SupersessionScope scope);

    UpdateResult updateInOwnTransaction(
            ReviewRunId obsoleteRunId,
            Function<ReviewRun, Boolean> mutation);

    enum UpdateResult {
        UPDATED,
        UNCHANGED,
        NOT_FOUND
    }

    record SupersessionScope(
            ReviewRunId currentRunId,
            PullRequestRevision currentRevision,
            Instant currentRequestedAt) {

        public SupersessionScope {
            Objects.requireNonNull(currentRunId, "currentRunId");
            Objects.requireNonNull(currentRevision, "currentRevision");
            Objects.requireNonNull(currentRequestedAt, "currentRequestedAt");
        }
    }
}
