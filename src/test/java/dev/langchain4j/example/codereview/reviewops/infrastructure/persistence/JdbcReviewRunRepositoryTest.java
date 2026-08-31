package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
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
import dev.langchain4j.example.codereview.reviewops.domain.ReviewAttemptState;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunChildIdentityMismatchException;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunConcurrencyException;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReviewRunRepositoryTest extends PostgresIntegrationSupport {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-31T01:00:00Z");
    private static final Instant FIRST_ATTEMPT_ENDED_AT = REQUESTED_AT.plusSeconds(2);
    private static final Instant SECOND_ATTEMPT_STARTED_AT = REQUESTED_AT.plusSeconds(3);
    private static final Instant COMPLETED_AT = REQUESTED_AT.plusSeconds(8);
    private static final Instant PUBLISHED_AT = REQUESTED_AT.plusSeconds(10);
    private static final FindingFingerprint INLINE_FINGERPRINT =
            new FindingFingerprint("a".repeat(64));
    private static final FindingFingerprint SUMMARY_FINGERPRINT =
            new FindingFingerprint("b".repeat(64));

    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;
    private JdbcReviewRunRepository repository;

    @BeforeEach
    void setUpRepository() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        repository = new JdbcReviewRunRepository(
                jdbcTemplate, transactionTemplate, new JsonColumnCodec(new ObjectMapper()));
        jdbcTemplate.execute("TRUNCATE TABLE review_runs CASCADE");
    }

    @Test
    void roundTripsARequestedReviewAtVersionZeroWithoutReplayingEvents() {
        ReviewRun requested = requestedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000001")));

        repository.insert(requested);

        assertStoredRun(repository.find(requested.id()).orElseThrow(), requested, 0);
        assertThat(repository.find(ReviewRunId.newId())).isEmpty();
    }

    @Test
    void updatesAppendNewChildrenAndProgressTheirPublicationAtSuccessiveVersions() {
        ReviewRun requested = requestedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000002")));
        repository.insert(requested);
        ReviewRun running = repository.find(requested.id()).orElseThrow().reviewRun();
        running.startAttempt(SECOND_ATTEMPT_STARTED_AT);

        assertThat(repository.update(running, 0)).isEqualTo(1);

        ReviewRun completed = repository.find(requested.id()).orElseThrow().reviewRun();
        completed.completeReview(findings(), successfulMeasurements(), COMPLETED_AT);
        completed.acceptPublicationDecisions(publicationDecisions());

        assertThat(repository.update(completed, 1)).isEqualTo(2);

        assertStoredRun(repository.find(completed.id()).orElseThrow(), completed, 2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_attempts WHERE review_run_id = ?",
                Integer.class, completed.id().value())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                        SELECT state FROM review_attempts
                        WHERE review_run_id = ? ORDER BY attempt_number
                        """, String.class, completed.id().value()))
                .containsExactly("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_findings WHERE review_run_id = ?",
                Integer.class, completed.id().value())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                        SELECT publication_tier FROM review_findings
                        WHERE review_run_id = ? ORDER BY fingerprint
                        """, String.class, completed.id().value()))
                .containsExactly("INLINE_COMMENT", "CHECK_SUMMARY");
    }

    @Test
    void rejectsUpdateThatOmitsAPersistedTransientAttemptAndRollsBackTheRootVersion() {
        ReviewRun original = requestedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000020")));
        original.startAttempt(REQUESTED_AT);
        original.recordTransientAttemptFailure(
                new ReviewFailure("MODEL_TIMEOUT", FailureClass.TRANSIENT, "model timed out"),
                new ExecutionMeasurements(2_000, 120, 0, Map.of("regex", "RAN")),
                FIRST_ATTEMPT_ENDED_AT);
        repository.insert(original);
        ReviewRun submittedWithoutAttempt = requestedRun(original.id());

        assertThatThrownBy(() -> repository.update(submittedWithoutAttempt, 0))
                .isInstanceOfSatisfying(ReviewRunChildIdentityMismatchException.class, exception -> {
                    assertThat(exception.reviewRunId()).isEqualTo(original.id());
                    assertThat(exception.childType()).isEqualTo("attempt");
                    assertThat(exception.omittedIdentities()).containsExactly("1");
                });

        assertStoredRun(repository.find(original.id()).orElseThrow(), original, 0);
        assertThat(jdbcTemplate.queryForList("""
                        SELECT attempt_number FROM review_attempts
                        WHERE review_run_id = ? ORDER BY attempt_number
                        """, Integer.class, original.id().value()))
                .containsExactly(1);
    }

    @Test
    void rejectsUpdateThatReplacesAPersistedFindingFingerprintAndRollsBackTheRootVersion() {
        ReviewRun original = completedRunWithoutDecisions(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000021")),
                List.of(finding(INLINE_FINGERPRINT)));
        repository.insert(original);
        FindingFingerprint replacementFingerprint = new FindingFingerprint("c".repeat(64));
        ReviewRun submittedWithReplacement = ReviewRun.reconstitute(
                original.id(), original.revision(), original.configuration(), original.requestedAt(),
                original.state(), original.attempts(), List.of(finding(replacementFingerprint)),
                original.finalFailure().orElse(null), original.finishedAt().orElse(null),
                original.checkRunExternalId().orElse(null));

        assertThatThrownBy(() -> repository.update(submittedWithReplacement, 0))
                .isInstanceOfSatisfying(ReviewRunChildIdentityMismatchException.class, exception -> {
                    assertThat(exception.reviewRunId()).isEqualTo(original.id());
                    assertThat(exception.childType()).isEqualTo("finding");
                    assertThat(exception.omittedIdentities())
                            .containsExactly(INLINE_FINGERPRINT.value());
                });

        assertStoredRun(repository.find(original.id()).orElseThrow(), original, 0);
        assertThat(jdbcTemplate.queryForList("""
                        SELECT fingerprint FROM review_findings
                        WHERE review_run_id = ? ORDER BY fingerprint
                        """, String.class, original.id().value()))
                .containsExactly(INLINE_FINGERPRINT.value());
    }

    @Test
    void roundTripsPublicationReferencesAndRootCompletionTimestamp() {
        ReviewRun published = completedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000003")));
        published.authorizePublication(new AuthoritativeRevision(published.revision().headSha()), COMPLETED_AT);
        published.confirmPublication(
                "check-run-314",
                Map.of(INLINE_FINGERPRINT, new PublicationReference("REVIEW_COMMENT", "comment-2718")),
                PUBLISHED_AT);

        repository.insert(published);

        assertStoredRun(repository.find(published.id()).orElseThrow(), published, 0);
    }

    @Test
    void publicationUpdatesPreserveExistingFindingFeedbackAndItsAuditHistory() {
        ReviewRun completed = completedRunWithoutDecisions(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000017")));
        repository.insert(completed);
        String auditEntries = """
                [
                  {"recordedAt":"2026-08-31T01:00:09Z","state":"HELPFUL"},
                  {"recordedAt":"2026-08-31T01:00:10Z","state":"FALSE_POSITIVE"}
                ]
                """;
        jdbcTemplate.update("""
                        INSERT INTO finding_feedback (
                            review_run_id, finding_fingerprint, actor_id, actor_login, state,
                            github_reaction_id, audit_entries, first_recorded_at, last_changed_at)
                        VALUES (?, ?, 404, 'reviewer', 'FALSE_POSITIVE', 505,
                                CAST(? AS jsonb), ?, ?)
                        """,
                completed.id().value(), INLINE_FINGERPRINT.value(), auditEntries,
                java.sql.Timestamp.from(REQUESTED_AT.plusSeconds(9)),
                java.sql.Timestamp.from(REQUESTED_AT.plusSeconds(10)));

        ReviewRun publishing = repository.find(completed.id()).orElseThrow().reviewRun();
        publishing.acceptPublicationDecisions(publicationDecisions());
        publishing.authorizePublication(
                new AuthoritativeRevision(publishing.revision().headSha()), COMPLETED_AT.plusSeconds(1));

        assertThat(repository.update(publishing, 0)).isEqualTo(1);

        ReviewRun published = repository.find(completed.id()).orElseThrow().reviewRun();
        published.confirmPublication(
                "check-run-314",
                Map.of(INLINE_FINGERPRINT, new PublicationReference("REVIEW_COMMENT", "comment-2718")),
                PUBLISHED_AT);
        assertThat(repository.update(published, 1)).isEqualTo(2);

        assertStoredRun(repository.find(completed.id()).orElseThrow(), published, 2);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT count(*)
                        FROM finding_feedback
                        WHERE review_run_id = ? AND finding_fingerprint = ? AND actor_id = 404
                        """, Integer.class, completed.id().value(), INLINE_FINGERPRINT.value()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                        SELECT audit_entries = CAST(? AS jsonb)
                        FROM finding_feedback
                        WHERE review_run_id = ? AND finding_fingerprint = ? AND actor_id = 404
                        """, Boolean.class, auditEntries,
                completed.id().value(), INLINE_FINGERPRINT.value())).isTrue();
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT actor_login, state, github_reaction_id,
                               first_recorded_at, last_changed_at, withdrawn_at
                        FROM finding_feedback
                        WHERE review_run_id = ? AND finding_fingerprint = ? AND actor_id = 404
                        """, completed.id().value(), INLINE_FINGERPRINT.value()))
                .containsEntry("actor_login", "reviewer")
                .containsEntry("state", "FALSE_POSITIVE")
                .containsEntry("github_reaction_id", 505L)
                .containsEntry("first_recorded_at", java.sql.Timestamp.from(REQUESTED_AT.plusSeconds(9)))
                .containsEntry("last_changed_at", java.sql.Timestamp.from(REQUESTED_AT.plusSeconds(10)))
                .containsEntry("withdrawn_at", null);
    }

    @Test
    void roundTripsFinalFailureAndItsTimestamp() {
        ReviewRun failed = completedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000004")));
        failed.authorizePublication(new AuthoritativeRevision(failed.revision().headSha()), COMPLETED_AT);
        failed.recordPublicationFailure(
                new ReviewFailure("GITHUB_UNAVAILABLE", FailureClass.TERMINAL, "publication unavailable"),
                PUBLISHED_AT);

        repository.insert(failed);

        assertStoredRun(repository.find(failed.id()).orElseThrow(), failed, 0);
    }

    @Test
    void roundTripsACancelledAttemptWhenARunningReviewIsSuperseded() {
        ReviewRun superseded = requestedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000005")));
        superseded.startAttempt(REQUESTED_AT.plusSeconds(1));
        superseded.supersede(new AuthoritativeRevision("new-head-sha"), FIRST_ATTEMPT_ENDED_AT);

        repository.insert(superseded);

        assertStoredRun(repository.find(superseded.id()).orElseThrow(), superseded, 0);
        assertThat(repository.find(superseded.id()).orElseThrow().reviewRun().attempts().get(0).state())
                .isEqualTo(ReviewAttemptState.CANCELLED);
    }

    @Test
    void translatesOnlyTheSixColumnBusinessIdentityConflictToDuplicateReviewRun() {
        ReviewRun original = requestedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000006")));
        ReviewRun duplicateIdentity = requestedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000007")));
        repository.insert(original);

        assertThatThrownBy(() -> repository.insert(duplicateIdentity))
                .isInstanceOfSatisfying(DuplicateReviewRunException.class,
                        exception -> assertThat(exception.reviewRunId()).isEqualTo(duplicateIdentity.id()));
        assertThat(repository.find(original.id())).isPresent();
        assertThat(repository.find(duplicateIdentity.id())).isEmpty();
    }

    @Test
    void doesNotTranslateAnUnrelatedChildConstraintThatSharesTheBusinessConstraintName() {
        jdbcTemplate.execute("""
                ALTER TABLE review_attempts
                ADD CONSTRAINT uq_review_runs_business_identity CHECK (attempt_number <> 1)
                """);
        ReviewRun running = runningRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000018")));
        try {
            assertThatThrownBy(() -> repository.insert(running))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isNotInstanceOf(DuplicateReviewRunException.class);
            assertThat(repository.find(running.id())).isEmpty();
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE review_attempts
                    DROP CONSTRAINT uq_review_runs_business_identity
                    """);
        }
    }

    @Test
    void doesNotTranslateTheUnrelatedReviewRunTechnicalPrimaryKeyConstraint() {
        ReviewRunId sharedTechnicalId = new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000019"));
        ReviewRun original = requestedRun(sharedTechnicalId);
        repository.insert(original);
        ReviewRun technicalConflict = ReviewRun.request(
                sharedTechnicalId,
                new PullRequestRevision(101, 202, 303, "different-head-sha"),
                original.configuration(),
                REQUESTED_AT);

        assertThatThrownBy(() -> repository.insert(technicalConflict))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateReviewRunException.class);
        assertStoredRun(repository.find(sharedTechnicalId).orElseThrow(), original, 0);
    }

    @Test
    void rejectsTheSecondUpdateFromTwoReadersOfVersionZero() {
        ReviewRun original = requestedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000008")));
        repository.insert(original);
        ReviewRunRepository.StoredReviewRun firstRead = repository.find(original.id()).orElseThrow();
        ReviewRunRepository.StoredReviewRun secondRead = repository.find(original.id()).orElseThrow();
        firstRead.reviewRun().startAttempt(REQUESTED_AT.plusSeconds(1));
        secondRead.reviewRun().startAttempt(REQUESTED_AT.plusSeconds(2));

        assertThat(repository.update(firstRead.reviewRun(), firstRead.version())).isEqualTo(1);

        assertThatThrownBy(() -> repository.update(secondRead.reviewRun(), secondRead.version()))
                .isInstanceOfSatisfying(ReviewRunConcurrencyException.class, exception -> {
                    assertThat(exception.reviewRunId()).isEqualTo(original.id());
                    assertThat(exception.expectedVersion()).isZero();
                });
        assertThat(repository.find(original.id()).orElseThrow().version()).isEqualTo(1);
        assertThat(repository.find(original.id()).orElseThrow().reviewRun().attempts().get(0).startedAt())
                .isEqualTo(REQUESTED_AT.plusSeconds(1));
    }

    @Test
    void competingRequiredTransactionsReceiveOptimisticConcurrencyInsteadOfALockFailure() throws Exception {
        ReviewRun original = requestedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000016")));
        repository.insert(original);

        CountDownLatch bothTransactionsReadVersionZero = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome<Long>> first = executor.submit(() -> capture(() ->
                    findThenCompeteToUpdate(
                            original.id(), REQUESTED_AT.plusSeconds(1), bothTransactionsReadVersionZero)));
            Future<Outcome<Long>> second = executor.submit(() -> capture(() ->
                    findThenCompeteToUpdate(
                            original.id(), REQUESTED_AT.plusSeconds(2), bothTransactionsReadVersionZero)));

            List<Outcome<Long>> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(outcome -> outcome.failure() == null)
                    .singleElement()
                    .extracting(Outcome::value)
                    .isEqualTo(1L);
            assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null)
                    .singleElement()
                    .satisfies(outcome -> {
                        assertThat(outcome.failure())
                                .isInstanceOf(ReviewRunConcurrencyException.class)
                                .isNotInstanceOf(DataAccessException.class);
                        ReviewRunConcurrencyException concurrencyFailure =
                                (ReviewRunConcurrencyException) outcome.failure();
                        assertThat(concurrencyFailure.reviewRunId()).isEqualTo(original.id());
                        assertThat(concurrencyFailure.expectedVersion()).isZero();
                    });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void findNeverReturnsAMixedVersionWhenAnUpdateRunsAfterTheRootSelect() throws Exception {
        ReviewRun running = runningRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000010")));
        repository.insert(running);
        ReviewRun writerCopy = repository.find(running.id()).orElseThrow().reviewRun();
        writerCopy.completeReview(findings(), successfulMeasurements(), COMPLETED_AT);
        writerCopy.acceptPublicationDecisions(publicationDecisions());

        CountDownLatch rootSelected = new CountDownLatch(1);
        CountDownLatch continueChildReads = new CountDownLatch(1);
        JdbcReviewRunRepository pausingReader = new JdbcReviewRunRepository(
                new RootPausingJdbcTemplate(dataSource, rootSelected, continueChildReads),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                new JsonColumnCodec(new ObjectMapper()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome<ReviewRunRepository.StoredReviewRun>> reader = executor.submit(
                    () -> capture(() -> pausingReader.find(running.id()).orElseThrow()));
            assertThat(rootSelected.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Outcome<Long>> writer = executor.submit(() -> capture(() ->
                    transactionTemplate.execute(status -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout = '500ms'");
                        return repository.update(writerCopy, 0);
                    })));
            Outcome<Long> writerOutcome = writer.get(5, TimeUnit.SECONDS);
            continueChildReads.countDown();
            Outcome<ReviewRunRepository.StoredReviewRun> readerOutcome = reader.get(5, TimeUnit.SECONDS);

            assertThat(writerOutcome.failure()).isNull();
            assertThat(writerOutcome.value()).isEqualTo(1L);
            assertThat(readerOutcome.failure()).isNull();
            assertStoredRun(readerOutcome.value(), writerCopy, 1);
        } finally {
            continueChildReads.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void joinsAnExistingRequiredTransaction() {
        ReviewRun requested = requestedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000009")));

        transactionTemplate.executeWithoutResult(status -> {
            repository.insert(requested);
            status.setRollbackOnly();
        });

        assertThat(repository.find(requested.id())).isEmpty();
    }

    @Test
    void jsonCodecPreservesTheOriginalJacksonCauseForMalformedPersistedJson() {
        JsonColumnCodec codec = new JsonColumnCodec(new ObjectMapper());

        assertThatThrownBy(() -> codec.decodeToolStates("not-json"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void jsonCodecRejectsLiteralNullAndNonObjectToolStates() {
        JsonColumnCodec codec = new JsonColumnCodec(new ObjectMapper());

        assertThatThrownBy(() -> codec.decodeToolStates("null"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool states");
        assertThatThrownBy(() -> codec.decodeToolStates("[]"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void jsonCodecRejectsLiteralNullAndNonArrayCitations() {
        JsonColumnCodec codec = new JsonColumnCodec(new ObjectMapper());

        assertThatThrownBy(() -> codec.decodeCitations("null"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("citations");
        assertThatThrownBy(() -> codec.decodeCitations("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void legacyPartialRootFailureCannotBeSilentlyCollapsed() {
        ReviewRun requested = requestedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000011")));
        repository.insert(requested);

        assertLegacyCorruptionFails(
                requested.id(),
                "review_runs",
                "ck_review_runs_failure_group",
                "UPDATE review_runs SET failure_class = 'TERMINAL' WHERE id = ?",
                "root failure");
    }

    @Test
    void legacyPartialAttemptMeasurementsCannotBecomeZeroOrNullFacts() {
        ReviewRun running = runningRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000012")));
        repository.insert(running);

        assertLegacyCorruptionFails(
                running.id(),
                "review_attempts",
                "ck_review_attempts_measurements_group",
                """
                        UPDATE review_attempts SET latency_ms = 17
                        WHERE review_run_id = ? AND attempt_number = 2
                        """,
                "attempt measurements");
    }

    @Test
    void legacyPartialAttemptFailureCannotBeSilentlyCollapsed() {
        ReviewRun running = runningRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000013")));
        repository.insert(running);

        assertLegacyCorruptionFails(
                running.id(),
                "review_attempts",
                "ck_review_attempts_failure_group",
                """
                        UPDATE review_attempts SET failure_class = 'TRANSIENT'
                        WHERE review_run_id = ? AND attempt_number = 2
                        """,
                "attempt failure");
    }

    @Test
    void legacyPartialPublicationDecisionCannotBeSilentlyCollapsed() {
        ReviewRun completed = completedRunWithoutDecisions(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000014")));
        repository.insert(completed);

        assertLegacyCorruptionFails(
                completed.id(),
                "review_findings",
                "ck_review_findings_publication_decision_group",
                """
                        UPDATE review_findings SET publication_policy_version = 'policy-v5'
                        WHERE review_run_id = ?
                        """,
                "publication decision");
    }

    @Test
    void legacyPartialPublicationReferenceCannotBeSilentlyCollapsed() {
        ReviewRun completed = completedRun(new ReviewRunId(
                UUID.fromString("00000000-0000-0000-0000-000000000015")));
        repository.insert(completed);

        assertLegacyCorruptionFails(
                completed.id(),
                "review_findings",
                "ck_review_findings_publication_reference_group",
                """
                        UPDATE review_findings SET artifact_external_id = 'comment-1'
                        WHERE review_run_id = ?
                        """,
                "publication reference");
    }

    private static ReviewRun requestedRun(ReviewRunId id) {
        return ReviewRun.request(id,
                new PullRequestRevision(101, 202, 303, "reviewed-head-sha"),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v7", "kimi-k2", "policy-v5", 3),
                REQUESTED_AT);
    }

    private static ReviewRun runningRun(ReviewRunId id) {
        ReviewRun run = requestedRun(id);
        run.startAttempt(REQUESTED_AT);
        run.recordTransientAttemptFailure(
                new ReviewFailure("MODEL_TIMEOUT", FailureClass.TRANSIENT, "model timed out"),
                new ExecutionMeasurements(2_000, 120, 0, Map.of("regex", "RAN", "spotbugs", "SKIPPED")),
                FIRST_ATTEMPT_ENDED_AT);
        run.startAttempt(SECOND_ATTEMPT_STARTED_AT);
        return run;
    }

    private static ReviewRun completedRun(ReviewRunId id) {
        ReviewRun run = completedRunWithoutDecisions(id);
        run.acceptPublicationDecisions(publicationDecisions());
        return run;
    }

    private static ReviewRun completedRunWithoutDecisions(ReviewRunId id) {
        return completedRunWithoutDecisions(id, findings());
    }

    private static ReviewRun completedRunWithoutDecisions(ReviewRunId id,
                                                           List<ReviewFinding> completedFindings) {
        ReviewRun run = runningRun(id);
        run.completeReview(completedFindings, successfulMeasurements(), COMPLETED_AT);
        run.drainEvents();
        return run;
    }

    private static ExecutionMeasurements successfulMeasurements() {
        return new ExecutionMeasurements(
                5_000, 1_200, 340, Map.of("regex", "RAN", "spotbugs", "RAN", "rag", "RAN"));
    }

    private static List<ReviewFinding> findings() {
        return List.of(
                finding(INLINE_FINGERPRINT),
                new ReviewFinding(
                        SUMMARY_FINGERPRINT,
                        new CodeLocation("src/test/java/example/ServiceTest.java", 19, false),
                        new FindingContent(
                                FindingSeverity.SUGGESTION, FindingCategory.TEST,
                                "Missing regression", "The failure path is uncovered", "Add a regression test"),
                        new FindingEvidence("no assertion covers the failure", List.of(), "llm")));
    }

    private static ReviewFinding finding(FindingFingerprint fingerprint) {
        return new ReviewFinding(
                fingerprint,
                new CodeLocation("src/main/java/example/Service.java", 41, true),
                new FindingContent(
                        FindingSeverity.CRITICAL, FindingCategory.SECURITY,
                        "SQL injection", "User input reaches SQL", "Bind the value"),
                new FindingEvidence(
                        "request parameter flows into the query",
                        List.of(new CitationEvidence("owasp-sqli", "OWASP", "Prevention")),
                        "hybrid-rag"));
    }

    private static Map<FindingFingerprint, PublicationDecision> publicationDecisions() {
        return Map.of(
                INLINE_FINGERPRINT, new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v5"),
                SUMMARY_FINGERPRINT, new PublicationDecision(PublicationTier.CHECK_SUMMARY, "policy-v5"));
    }

    private static void assertStoredRun(ReviewRunRepository.StoredReviewRun stored,
                                        ReviewRun expected, long expectedVersion) {
        ReviewRun actual = stored.reviewRun();
        assertThat(stored.version()).isEqualTo(expectedVersion);
        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.revision()).isEqualTo(expected.revision());
        assertThat(actual.configuration()).isEqualTo(expected.configuration());
        assertThat(actual.requestedAt()).isEqualTo(expected.requestedAt());
        assertThat(actual.state()).isEqualTo(expected.state());
        assertThat(actual.attempts()).usingRecursiveComparison().isEqualTo(expected.attempts());
        assertThat(actual.findings()).usingRecursiveComparison().isEqualTo(expected.findings());
        assertThat(actual.commentReferences()).isEqualTo(expected.commentReferences());
        assertThat(actual.checkRunExternalId()).isEqualTo(expected.checkRunExternalId());
        assertThat(actual.finalFailure()).isEqualTo(expected.finalFailure());
        assertThat(actual.finishedAt()).isEqualTo(expected.finishedAt());
        assertThat(actual.drainEvents()).isEmpty();
    }

    private static <T> Outcome<T> capture(Callable<T> operation) {
        try {
            return new Outcome<>(operation.call(), null);
        } catch (Throwable failure) {
            return new Outcome<>(null, failure);
        }
    }

    private record Outcome<T>(T value, Throwable failure) {
    }

    private long findThenCompeteToUpdate(ReviewRunId id, Instant attemptStartedAt,
                                         CountDownLatch bothTransactionsReadVersionZero) {
        Long nextVersion = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL deadlock_timeout = '100ms'");
            jdbcTemplate.execute("SET LOCAL lock_timeout = '3s'");
            ReviewRunRepository.StoredReviewRun stored = repository.find(id).orElseThrow();
            assertThat(stored.version()).isZero();
            bothTransactionsReadVersionZero.countDown();
            try {
                if (!bothTransactionsReadVersionZero.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for both transaction reads");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for competing update", exception);
            }
            stored.reviewRun().startAttempt(attemptStartedAt);
            return repository.update(stored.reviewRun(), stored.version());
        });
        return java.util.Objects.requireNonNull(nextVersion, "transaction result");
    }

    private void assertLegacyCorruptionFails(ReviewRunId id, String tableName,
                                             String constraintName, String corruptSql,
                                             String expectedGroupName) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("ALTER TABLE " + tableName
                    + " DROP CONSTRAINT IF EXISTS " + constraintName);
            jdbcTemplate.update(corruptSql, id.value());

            assertThatThrownBy(() -> repository.find(id))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(expectedGroupName);

            status.setRollbackOnly();
        });
    }

    private static final class RootPausingJdbcTemplate extends JdbcTemplate {

        private final CountDownLatch rootSelected;
        private final CountDownLatch continueChildReads;

        private RootPausingJdbcTemplate(DataSource dataSource, CountDownLatch rootSelected,
                                        CountDownLatch continueChildReads) {
            super(dataSource);
            this.rootSelected = rootSelected;
            this.continueChildReads = continueChildReads;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            List<T> rows = super.query(sql, rowMapper, args);
            if (sql.contains("FROM review_runs")) {
                rootSelected.countDown();
                try {
                    if (!continueChildReads.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to continue child reads");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while pausing child reads", exception);
                }
            }
            return rows;
        }
    }
}
