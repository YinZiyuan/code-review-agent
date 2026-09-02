package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import dev.langchain4j.example.codereview.reviewops.application.ObsoleteReviewRunStore;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class PostgresObsoleteReviewRunStore implements ObsoleteReviewRunStore {

    private final JdbcTemplate jdbcTemplate;
    private final ReviewRunRepository reviewRuns;
    private final TransactionOperations transactions;

    public PostgresObsoleteReviewRunStore(
            JdbcTemplate jdbcTemplate,
            ReviewRunRepository reviewRuns,
            TransactionOperations transactions) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public List<ReviewRunId> findActiveObsoleteRunIds(SupersessionScope scope) {
        Objects.requireNonNull(scope, "scope");
        PullRequestRevision current = scope.currentRevision();
        return jdbcTemplate.query("""
                        SELECT id
                        FROM review_runs
                        WHERE installation_id = ?
                          AND repository_id = ?
                          AND pull_request_number = ?
                          AND id <> ?
                          AND head_sha <> ?
                          AND state IN ('REQUESTED', 'RUNNING', 'COMPLETED', 'PUBLISHING')
                        ORDER BY requested_at, id
                        """,
                (resultSet, rowNumber) -> new ReviewRunId(
                        resultSet.getObject("id", java.util.UUID.class)),
                current.installationId(),
                current.repositoryId(),
                current.pullRequestNumber(),
                scope.currentRunId().value(),
                current.headSha());
    }

    @Override
    public UpdateResult updateInOwnTransaction(
            ReviewRunId obsoleteRunId,
            Function<ReviewRun, Boolean> mutation) {
        Objects.requireNonNull(obsoleteRunId, "obsoleteRunId");
        Objects.requireNonNull(mutation, "mutation");
        return Objects.requireNonNull(transactions.execute(status -> {
            ReviewRunRepository.StoredReviewRun stored = reviewRuns.find(obsoleteRunId).orElse(null);
            if (stored == null) {
                return UpdateResult.NOT_FOUND;
            }
            ReviewRun obsolete = stored.reviewRun();
            if (!mutation.apply(obsolete)) {
                return UpdateResult.UNCHANGED;
            }
            reviewRuns.update(obsolete, stored.version());
            return UpdateResult.UPDATED;
        }), "transaction result");
    }
}
