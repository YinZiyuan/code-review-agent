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
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunChildIdentityMismatchException;
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
import java.util.Set;
import java.util.UUID;

public final class JdbcReviewRunRepository implements ReviewRunRepository {

    private static final int MAX_SNAPSHOT_READ_ATTEMPTS = 3;
    private static final String BUSINESS_IDENTITY_CONSTRAINT =
            "uq_review_runs_business_identity";

    private static final String SELECT_ROOT = """
            SELECT id, installation_id, repository_id, pull_request_number, head_sha,
                   pipeline_version, configuration_version, model_name, policy_version,
                   max_review_attempts, requested_at, state, check_run_external_id,
                   failure_code, failure_class, failure_safe_message, finished_at, version
            FROM review_runs
            WHERE id = ?
            """;

    private static final String SELECT_ROOT_VERSION = """
            SELECT version
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
        return Objects.requireNonNull(
                transactions.execute(status -> findConsistentSnapshot(id)),
                "transaction result");
    }

    private Optional<StoredReviewRun> findConsistentSnapshot(ReviewRunId id) {
        for (int attempt = 1; attempt <= MAX_SNAPSHOT_READ_ATTEMPTS; attempt++) {
            List<PersistedRoot> roots = jdbcTemplate.query(
                    SELECT_ROOT, (resultSet, rowNumber) -> mapRoot(resultSet), id.value());
            if (roots.isEmpty()) {
                return Optional.empty();
            }
            PersistedRoot root = roots.get(0);
            List<ReviewAttempt> attempts = loadAttempts(id);
            List<ReviewFinding> findings = loadFindings(id);
            if (rootVersionIsStill(id, root.version())) {
                ReviewRun run = ReviewRun.reconstitute(
                        root.id(), root.revision(), root.configuration(), root.requestedAt(), root.state(),
                        attempts, findings, root.finalFailure(), root.finishedAt(), root.checkRunExternalId());
                return Optional.of(new StoredReviewRun(run, root.version()));
            }
        }
        throw new IllegalStateException(
                "Could not read a consistent review run snapshot for " + id
                        + " after " + MAX_SNAPSHOT_READ_ATTEMPTS + " attempts");
    }

    private boolean rootVersionIsStill(ReviewRunId id, long expectedVersion) {
        List<Long> versions = jdbcTemplate.query(
                SELECT_ROOT_VERSION,
                (resultSet, rowNumber) -> resultSet.getLong("version"),
                id.value());
        return versions.size() == 1 && versions.get(0) == expectedVersion;
    }

    @Override
    public void insert(ReviewRun reviewRun) {
        Objects.requireNonNull(reviewRun, "reviewRun");
        transactions.executeWithoutResult(status -> {
            insertRootTranslatingBusinessDuplicate(reviewRun);
            insertOwnedChildren(reviewRun);
        });
    }

    private void insertRootTranslatingBusinessDuplicate(ReviewRun reviewRun) {
        try {
            insertRoot(reviewRun);
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
            requirePersistedChildIdentitiesAreSubmitted(reviewRun);
            upsertOwnedChildren(reviewRun);
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

    private void upsertOwnedChildren(ReviewRun run) {
        run.attempts().forEach(attempt -> upsertAttempt(run.id(), attempt));
        run.findings().forEach(finding -> upsertFinding(run.id(), finding));
    }

    private void requirePersistedChildIdentitiesAreSubmitted(ReviewRun run) {
        requirePersistedChildIdentitiesAreSubmitted(
                run.id(),
                "attempt",
                jdbcTemplate.queryForList("""
                                SELECT attempt_number
                                FROM review_attempts
                                WHERE review_run_id = ?
                                ORDER BY attempt_number
                                """, Integer.class, run.id().value()).stream()
                        .map(String::valueOf)
                        .toList(),
                run.attempts().stream()
                        .map(attempt -> Integer.toString(attempt.attemptNumber()))
                        .collect(java.util.stream.Collectors.toSet()));
        requirePersistedChildIdentitiesAreSubmitted(
                run.id(),
                "finding",
                jdbcTemplate.queryForList("""
                                SELECT fingerprint
                                FROM review_findings
                                WHERE review_run_id = ?
                                ORDER BY fingerprint
                                """, String.class, run.id().value()),
                run.findings().stream()
                        .map(finding -> finding.fingerprint().value())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private static void requirePersistedChildIdentitiesAreSubmitted(
            ReviewRunId id, String childType, List<String> persistedIdentities,
            Set<String> submittedIdentities) {
        List<String> omittedIdentities = persistedIdentities.stream()
                .filter(identity -> !submittedIdentities.contains(identity))
                .toList();
        if (!omittedIdentities.isEmpty()) {
            throw new ReviewRunChildIdentityMismatchException(id, childType, omittedIdentities);
        }
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

    private void upsertAttempt(ReviewRunId id, ReviewAttempt attempt) {
        ExecutionMeasurements measurements = attempt.measurements().orElse(null);
        ReviewFailure failure = attempt.failure().orElse(null);
        int affected = jdbcTemplate.update("""
                        INSERT INTO review_attempts (
                            review_run_id, attempt_number, state, started_at, ended_at,
                            latency_ms, input_tokens, output_tokens, tool_states,
                            failure_code, failure_class, failure_safe_message)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                        ON CONFLICT (review_run_id, attempt_number) DO UPDATE
                        SET state = EXCLUDED.state,
                            ended_at = EXCLUDED.ended_at,
                            latency_ms = EXCLUDED.latency_ms,
                            input_tokens = EXCLUDED.input_tokens,
                            output_tokens = EXCLUDED.output_tokens,
                            tool_states = EXCLUDED.tool_states,
                            failure_code = EXCLUDED.failure_code,
                            failure_class = EXCLUDED.failure_class,
                            failure_safe_message = EXCLUDED.failure_safe_message
                        WHERE review_attempts.started_at = EXCLUDED.started_at
                          AND (
                              (review_attempts.state = 'STARTED'
                                  AND EXCLUDED.state IN (
                                      'STARTED', 'SUCCEEDED', 'TRANSIENT_FAILURE',
                                      'TERMINAL_FAILURE', 'CANCELLED'))
                              OR (
                                  review_attempts.state = EXCLUDED.state
                                  AND review_attempts.ended_at IS NOT DISTINCT FROM EXCLUDED.ended_at
                                  AND review_attempts.latency_ms IS NOT DISTINCT FROM EXCLUDED.latency_ms
                                  AND review_attempts.input_tokens IS NOT DISTINCT FROM EXCLUDED.input_tokens
                                  AND review_attempts.output_tokens IS NOT DISTINCT FROM EXCLUDED.output_tokens
                                  AND review_attempts.tool_states IS NOT DISTINCT FROM EXCLUDED.tool_states
                                  AND review_attempts.failure_code IS NOT DISTINCT FROM EXCLUDED.failure_code
                                  AND review_attempts.failure_class IS NOT DISTINCT FROM EXCLUDED.failure_class
                                  AND review_attempts.failure_safe_message
                                      IS NOT DISTINCT FROM EXCLUDED.failure_safe_message
                              )
                          )
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
        requireIdentityAwareWrite(affected, "attempt", id, Integer.toString(attempt.attemptNumber()));
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

    private void upsertFinding(ReviewRunId id, ReviewFinding finding) {
        PublicationDecision decision = finding.publicationDecision().orElse(null);
        PublicationReference reference = finding.publicationReference().orElse(null);
        int affected = jdbcTemplate.update("""
                        INSERT INTO review_findings (
                            review_run_id, fingerprint, file_path, post_change_line, changed_line,
                            severity, category, title, description, suggestion,
                            evidence, citations, source, publication_tier,
                            publication_policy_version, artifact_type, artifact_external_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                        ON CONFLICT (review_run_id, fingerprint) DO UPDATE
                        SET publication_tier = EXCLUDED.publication_tier,
                            publication_policy_version = EXCLUDED.publication_policy_version,
                            artifact_type = EXCLUDED.artifact_type,
                            artifact_external_id = EXCLUDED.artifact_external_id
                        WHERE review_findings.file_path = EXCLUDED.file_path
                          AND review_findings.post_change_line = EXCLUDED.post_change_line
                          AND review_findings.changed_line = EXCLUDED.changed_line
                          AND review_findings.severity = EXCLUDED.severity
                          AND review_findings.category = EXCLUDED.category
                          AND review_findings.title = EXCLUDED.title
                          AND review_findings.description = EXCLUDED.description
                          AND review_findings.suggestion = EXCLUDED.suggestion
                          AND review_findings.evidence = EXCLUDED.evidence
                          AND review_findings.citations = EXCLUDED.citations
                          AND review_findings.source = EXCLUDED.source
                          AND (
                              (review_findings.publication_tier IS NULL
                                  AND review_findings.publication_policy_version IS NULL)
                              OR (
                                  review_findings.publication_tier
                                      IS NOT DISTINCT FROM EXCLUDED.publication_tier
                                  AND review_findings.publication_policy_version
                                      IS NOT DISTINCT FROM EXCLUDED.publication_policy_version
                              )
                          )
                          AND (
                              (review_findings.artifact_type IS NULL
                                  AND review_findings.artifact_external_id IS NULL)
                              OR (
                                  EXCLUDED.artifact_type IS NULL
                                  AND EXCLUDED.artifact_external_id IS NULL
                              )
                              OR (
                                  review_findings.artifact_type
                                      IS NOT DISTINCT FROM EXCLUDED.artifact_type
                                  AND review_findings.artifact_external_id
                                      IS NOT DISTINCT FROM EXCLUDED.artifact_external_id
                              )
                          )
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
        requireIdentityAwareWrite(affected, "finding", id, finding.fingerprint().value());
    }

    private static void requireIdentityAwareWrite(int affected, String childType,
                                                  ReviewRunId id, String childIdentity) {
        if (affected != 1) {
            throw new IllegalStateException(
                    "Persisted " + childType + " identity or lifecycle conflicts with aggregate "
                            + id.value() + ": " + childIdentity);
        }
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
        Integer inputTokens = nullableInteger(resultSet, "input_tokens");
        Integer outputTokens = nullableInteger(resultSet, "output_tokens");
        String toolStates = resultSet.getString("tool_states");
        requireAllNullOrAllPresent(
                "attempt measurements", latencyMs, inputTokens, outputTokens, toolStates);
        ExecutionMeasurements measurements = latencyMs == null ? null : new ExecutionMeasurements(
                latencyMs,
                inputTokens,
                outputTokens,
                jsonCodec.decodeToolStates(toolStates));
        return ReviewAttempt.reconstitute(
                resultSet.getInt("attempt_number"),
                instant(resultSet, "started_at"),
                ReviewAttemptState.valueOf(resultSet.getString("state")),
                nullableInstant(resultSet, "ended_at"),
                measurements,
                failure(resultSet, "attempt failure"));
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
        String publicationPolicyVersion = resultSet.getString("publication_policy_version");
        requireAllNullOrAllPresent(
                "publication decision", publicationTier, publicationPolicyVersion);
        PublicationDecision decision = publicationTier == null ? null : new PublicationDecision(
                PublicationTier.valueOf(publicationTier),
                publicationPolicyVersion);
        String artifactType = resultSet.getString("artifact_type");
        String artifactExternalId = resultSet.getString("artifact_external_id");
        requireAllNullOrAllPresent(
                "publication reference", artifactType, artifactExternalId);
        PublicationReference reference = artifactType == null ? null : new PublicationReference(
                artifactType, artifactExternalId);
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
                failure(resultSet, "root failure"),
                nullableInstant(resultSet, "finished_at"),
                resultSet.getLong("version"));
    }

    private static ReviewFailure failure(ResultSet resultSet, String groupName) throws SQLException {
        String failureCode = resultSet.getString("failure_code");
        String failureClass = resultSet.getString("failure_class");
        String failureSafeMessage = resultSet.getString("failure_safe_message");
        requireAllNullOrAllPresent(
                groupName, failureCode, failureClass, failureSafeMessage);
        return failureCode == null ? null : new ReviewFailure(
                failureCode,
                FailureClass.valueOf(failureClass),
                failureSafeMessage);
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void requireAllNullOrAllPresent(String groupName, Object... values) {
        int nullValues = 0;
        for (Object value : values) {
            if (value == null) {
                nullValues++;
            }
        }
        if (nullValues != 0 && nullValues != values.length) {
            throw new IllegalStateException(
                    "Persisted " + groupName + " must be entirely absent or entirely present");
        }
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
