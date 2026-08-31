package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import dev.langchain4j.example.codereview.reviewops.domain.CitationEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.DuplicateReviewRunException;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationReference;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewAttempt;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewAttemptState;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunConcurrencyException;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcReviewRunRepository implements ReviewRunRepository {

    private static final String BUSINESS_IDENTITY_CONSTRAINT =
            "review_runs_installation_id_repository_id_pull_request_numb_key";

    private static final String SELECT_ROOT = """
            SELECT id, installation_id, repository_id, pull_request_number, head_sha,
                   pipeline_version, configuration_version, model_name, policy_version,
                   max_review_attempts, requested_at, state, check_run_external_id,
                   failure_code, failure_class, failure_safe_message, finished_at, version
            FROM review_runs
            WHERE id = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactions;
    private final JsonColumnCodec jsonCodec;

    public JdbcReviewRunRepository(JdbcTemplate jdbcTemplate, TransactionOperations transactions,
                                   JsonColumnCodec jsonCodec) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
    }

    @Override
    public Optional<StoredReviewRun> find(ReviewRunId id) {
        Objects.requireNonNull(id, "id");
        List<PersistedRoot> roots = jdbcTemplate.query(
                SELECT_ROOT, (resultSet, rowNumber) -> mapRoot(resultSet), id.value());
        if (roots.isEmpty()) {
            return Optional.empty();
        }
        PersistedRoot root = roots.get(0);
        List<ReviewAttempt> attempts = loadAttempts(id);
        List<ReviewFinding> findings = loadFindings(id);
        ReviewRun run = ReviewRun.reconstitute(
                root.id(), root.revision(), root.configuration(), root.requestedAt(), root.state(),
                attempts, findings, root.finalFailure(), root.finishedAt(), root.checkRunExternalId());
        return Optional.of(new StoredReviewRun(run, root.version()));
    }

    @Override
    public void insert(ReviewRun reviewRun) {
        Objects.requireNonNull(reviewRun, "reviewRun");
        try {
            transactions.executeWithoutResult(status -> {
                insertRoot(reviewRun);
                insertOwnedChildren(reviewRun);
            });
        } catch (DataIntegrityViolationException exception) {
            if (BUSINESS_IDENTITY_CONSTRAINT.equals(postgresConstraintName(exception))) {
                throw new DuplicateReviewRunException(reviewRun.id());
            }
            throw exception;
        }
    }

    @Override
    public long update(ReviewRun reviewRun, long expectedVersion) {
        Objects.requireNonNull(reviewRun, "reviewRun");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        Long nextVersion = transactions.execute(status -> {
            int updated = updateRoot(reviewRun, expectedVersion);
            if (updated == 0) {
                throw new ReviewRunConcurrencyException(reviewRun.id(), expectedVersion);
            }
            deleteOwnedChildren(reviewRun.id());
            insertOwnedChildren(reviewRun);
            return expectedVersion + 1;
        });
        return Objects.requireNonNull(nextVersion, "transaction result");
    }

    private void insertRoot(ReviewRun run) {
        ReviewFailure failure = run.finalFailure().orElse(null);
        jdbcTemplate.update("""
                        INSERT INTO review_runs (
                            id, installation_id, repository_id, pull_request_number, head_sha,
                            pipeline_version, configuration_version, model_name, policy_version,
                            max_review_attempts, requested_at, state, check_run_external_id,
                            failure_code, failure_class, failure_safe_message, finished_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                run.id().value(),
                run.revision().installationId(),
                run.revision().repositoryId(),
                run.revision().pullRequestNumber(),
                run.revision().headSha(),
                run.configuration().pipelineVersion(),
                run.configuration().configurationVersion(),
                run.configuration().modelName(),
                run.configuration().policyVersion(),
                run.configuration().maxReviewAttempts(),
                timestamp(run.requestedAt()),
                run.state().name(),
                run.checkRunExternalId().orElse(null),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.classification().name(),
                failure == null ? null : failure.safeMessage(),
                run.finishedAt().map(JdbcReviewRunRepository::timestamp).orElse(null));
    }

    private int updateRoot(ReviewRun run, long expectedVersion) {
        ReviewFailure failure = run.finalFailure().orElse(null);
        return jdbcTemplate.update("""
                        UPDATE review_runs
                        SET state = ?, check_run_external_id = ?, failure_code = ?, failure_class = ?,
                            failure_safe_message = ?, finished_at = ?, version = version + 1
                        WHERE id = ? AND version = ?
                        """,
                run.state().name(),
                run.checkRunExternalId().orElse(null),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.classification().name(),
                failure == null ? null : failure.safeMessage(),
                run.finishedAt().map(JdbcReviewRunRepository::timestamp).orElse(null),
                run.id().value(),
                expectedVersion);
    }

    private void insertOwnedChildren(ReviewRun run) {
        run.attempts().forEach(attempt -> insertAttempt(run.id(), attempt));
        run.findings().forEach(finding -> insertFinding(run.id(), finding));
    }

    private void deleteOwnedChildren(ReviewRunId id) {
        jdbcTemplate.update("DELETE FROM review_findings WHERE review_run_id = ?", id.value());
        jdbcTemplate.update("DELETE FROM review_attempts WHERE review_run_id = ?", id.value());
    }

    private void insertAttempt(ReviewRunId id, ReviewAttempt attempt) {
        ExecutionMeasurements measurements = attempt.measurements().orElse(null);
        ReviewFailure failure = attempt.failure().orElse(null);
        jdbcTemplate.update("""
                        INSERT INTO review_attempts (
                            review_run_id, attempt_number, state, started_at, ended_at,
                            latency_ms, input_tokens, output_tokens, tool_states,
                            failure_code, failure_class, failure_safe_message)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                        """,
                id.value(),
                attempt.attemptNumber(),
                attempt.state().name(),
                timestamp(attempt.startedAt()),
                attempt.endedAt().map(JdbcReviewRunRepository::timestamp).orElse(null),
                measurements == null ? null : measurements.latencyMs(),
                measurements == null ? null : measurements.inputTokens(),
                measurements == null ? null : measurements.outputTokens(),
                measurements == null ? null : jsonCodec.encodeToolStates(measurements.toolStates()),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.classification().name(),
                failure == null ? null : failure.safeMessage());
    }

    private void insertFinding(ReviewRunId id, ReviewFinding finding) {
        PublicationDecision decision = finding.publicationDecision().orElse(null);
        PublicationReference reference = finding.publicationReference().orElse(null);
        jdbcTemplate.update("""
                        INSERT INTO review_findings (
                            review_run_id, fingerprint, file_path, post_change_line, changed_line,
                            severity, category, title, description, suggestion,
                            evidence, citations, source, publication_tier,
                            publication_policy_version, artifact_type, artifact_external_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                        """,
                id.value(),
                finding.fingerprint().value(),
                finding.location().file(),
                finding.location().line(),
                finding.location().changedLine(),
                finding.content().severity().name(),
                finding.content().category().name(),
                finding.content().title(),
                finding.content().description(),
                finding.content().suggestion(),
                finding.evidence().evidence(),
                jsonCodec.encodeCitations(finding.evidence().citations()),
                finding.evidence().source(),
                decision == null ? null : decision.tier().name(),
                decision == null ? null : decision.policyVersion(),
                reference == null ? null : reference.artifactType(),
                reference == null ? null : reference.externalId());
    }

    private List<ReviewAttempt> loadAttempts(ReviewRunId id) {
        return jdbcTemplate.query("""
                        SELECT attempt_number, state, started_at, ended_at,
                               latency_ms, input_tokens, output_tokens, tool_states,
                               failure_code, failure_class, failure_safe_message
                        FROM review_attempts
                        WHERE review_run_id = ?
                        ORDER BY attempt_number
                        """,
                (resultSet, rowNumber) -> mapAttempt(resultSet), id.value());
    }

    private ReviewAttempt mapAttempt(ResultSet resultSet) throws SQLException {
        Long latencyMs = nullableLong(resultSet, "latency_ms");
        ExecutionMeasurements measurements = latencyMs == null ? null : new ExecutionMeasurements(
                latencyMs,
                resultSet.getInt("input_tokens"),
                resultSet.getInt("output_tokens"),
                jsonCodec.decodeToolStates(resultSet.getString("tool_states")));
        return ReviewAttempt.reconstitute(
                resultSet.getInt("attempt_number"),
                instant(resultSet, "started_at"),
                ReviewAttemptState.valueOf(resultSet.getString("state")),
                nullableInstant(resultSet, "ended_at"),
                measurements,
                failure(resultSet));
    }

    private List<ReviewFinding> loadFindings(ReviewRunId id) {
        return jdbcTemplate.query("""
                        SELECT fingerprint, file_path, post_change_line, changed_line,
                               severity, category, title, description, suggestion,
                               evidence, citations, source, publication_tier,
                               publication_policy_version, artifact_type, artifact_external_id
                        FROM review_findings
                        WHERE review_run_id = ?
                        ORDER BY fingerprint
                        """,
                (resultSet, rowNumber) -> mapFinding(resultSet), id.value());
    }

    private ReviewFinding mapFinding(ResultSet resultSet) throws SQLException {
        String publicationTier = resultSet.getString("publication_tier");
        PublicationDecision decision = publicationTier == null ? null : new PublicationDecision(
                PublicationTier.valueOf(publicationTier),
                resultSet.getString("publication_policy_version"));
        String artifactType = resultSet.getString("artifact_type");
        PublicationReference reference = artifactType == null ? null : new PublicationReference(
                artifactType, resultSet.getString("artifact_external_id"));
        List<CitationEvidence> citations = jsonCodec.decodeCitations(resultSet.getString("citations"));
        return ReviewFinding.reconstitute(
                new FindingFingerprint(resultSet.getString("fingerprint")),
                new CodeLocation(
                        resultSet.getString("file_path"),
                        resultSet.getInt("post_change_line"),
                        resultSet.getBoolean("changed_line")),
                new FindingContent(
                        FindingSeverity.valueOf(resultSet.getString("severity")),
                        FindingCategory.valueOf(resultSet.getString("category")),
                        resultSet.getString("title"),
                        resultSet.getString("description"),
                        resultSet.getString("suggestion")),
                new FindingEvidence(
                        resultSet.getString("evidence"), citations, resultSet.getString("source")),
                decision,
                reference);
    }

    private static PersistedRoot mapRoot(ResultSet resultSet) throws SQLException {
        return new PersistedRoot(
                new ReviewRunId(resultSet.getObject("id", UUID.class)),
                new PullRequestRevision(
                        resultSet.getLong("installation_id"),
                        resultSet.getLong("repository_id"),
                        resultSet.getInt("pull_request_number"),
                        resultSet.getString("head_sha")),
                new ReviewConfigurationSnapshot(
                        resultSet.getString("pipeline_version"),
                        resultSet.getString("configuration_version"),
                        resultSet.getString("model_name"),
                        resultSet.getString("policy_version"),
                        resultSet.getInt("max_review_attempts")),
                instant(resultSet, "requested_at"),
                ReviewRunState.valueOf(resultSet.getString("state")),
                resultSet.getString("check_run_external_id"),
                failure(resultSet),
                nullableInstant(resultSet, "finished_at"),
                resultSet.getLong("version"));
    }

    private static ReviewFailure failure(ResultSet resultSet) throws SQLException {
        String failureCode = resultSet.getString("failure_code");
        return failureCode == null ? null : new ReviewFailure(
                failureCode,
                FailureClass.valueOf(resultSet.getString("failure_class")),
                resultSet.getString("failure_safe_message"));
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static String postgresConstraintName(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (!"org.postgresql.util.PSQLException".equals(current.getClass().getName())) {
                continue;
            }
            try {
                Object serverError = current.getClass().getMethod("getServerErrorMessage").invoke(current);
                if (serverError == null) {
                    return null;
                }
                return (String) serverError.getClass().getMethod("getConstraint").invoke(serverError);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return null;
    }

    private record PersistedRoot(
            ReviewRunId id,
            PullRequestRevision revision,
            ReviewConfigurationSnapshot configuration,
            Instant requestedAt,
            ReviewRunState state,
            String checkRunExternalId,
            ReviewFailure finalFailure,
            Instant finishedAt,
            long version) {
    }
}
