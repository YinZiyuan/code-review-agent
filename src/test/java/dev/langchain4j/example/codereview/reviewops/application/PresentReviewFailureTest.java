package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.jobs.OperationFence;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
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
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PresentReviewFailureTest {

    private static final Instant NOW = Instant.parse("2026-09-01T04:00:00Z");
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void terminalExecutionFailureCreatesOneNeutralCheckAtAuthoritativeHeadWithZeroComments() {
        ReviewRun run = failedExecutionRun();
        RecordingMutations mutations = new RecordingMutations();
        RecordingGitHub github = new RecordingGitHub(new AuthoritativeRevision(SHA));
        PresentReviewFailure presenter = new PresentReviewFailure(
                repository(run, 8), mutations, github, Clock.fixed(NOW, ZoneOffset.UTC));

        PresentReviewFailure.PresentationOutcome outcome = presenter.present(run.id());

        assertThat(outcome).isEqualTo(PresentReviewFailure.PresentationOutcome.PRESENTED);
        assertThat(github.checkRequests).singleElement().satisfies(request -> {
            assertThat(request.revision()).isEqualTo(run.revision());
            assertThat(request.presentation().outcome())
                    .isEqualTo(GitHubPublicationGateway.CheckOutcome.NEUTRAL_SYSTEM_FAILURE);
            assertThat(request.presentation().conclusion())
                    .isEqualTo(GitHubPublicationGateway.CheckConclusion.NEUTRAL);
            assertThat(request.presentation().codeCommentsMayRemain()).isFalse();
            assertThat(request.presentation().safeSummary())
                    .contains("Review failed safely")
                    .contains("invalid_review_output")
                    .doesNotContain("repository source");
            assertThat(request.findings()).isEmpty();
            assertThat(request.existingGitHubArtifactId()).isEmpty();
        });
        assertThat(github.commentMutations).isZero();
        assertThat(run.checkRunExternalId()).contains("check-41");
        assertThat(mutations.savedExpectedVersion).isEqualTo(8L);
    }

    @Test
    void staleFailedRunPerformsNoGitHubMutation() {
        ReviewRun run = failedExecutionRun();
        RecordingGitHub github = new RecordingGitHub(new AuthoritativeRevision(
                "abcdef0123456789abcdef0123456789abcdef01"));
        RecordingMutations mutations = new RecordingMutations();
        PresentReviewFailure presenter = new PresentReviewFailure(
                repository(run, 2), mutations, github, Clock.fixed(NOW, ZoneOffset.UTC));

        PresentReviewFailure.PresentationOutcome outcome = presenter.present(run.id());

        assertThat(outcome).isEqualTo(PresentReviewFailure.PresentationOutcome.STALE);
        assertThat(github.checkRequests).isEmpty();
        assertThat(github.commentMutations).isZero();
        assertThat(mutations.savedExpectedVersion).isNull();
    }

    @Test
    void lostLeaseAfterHeadGuardPreventsNeutralCheckMutation() {
        ReviewRun run = failedExecutionRun();
        RecordingGitHub github = new RecordingGitHub(new AuthoritativeRevision(SHA));
        RecordingMutations mutations = new RecordingMutations();
        PresentReviewFailure presenter = new PresentReviewFailure(
                repository(run, 2), mutations, github, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> presenter.present(run.id(), () -> {
            throw new OperationFence.Lost();
        })).isInstanceOf(OperationFence.Lost.class);

        assertThat(github.checkRequests).isEmpty();
        assertThat(github.commentMutations).isZero();
        assertThat(mutations.savedVersions).isEmpty();
    }

    @Test
    void terminalCommentCleanupFailureProducesATruthfulNeutralWarning() {
        ReviewRun run = failedPublicationRunWithComment();
        RecordingMutations mutations = new RecordingMutations();
        RecordingGitHub github = new RecordingGitHub(new AuthoritativeRevision(SHA));
        github.retractionFailure = new GitHubFailureException(
                GitHubFailureException.Classification.AUTHORIZATION,
                "GitHub comment deletion is no longer authorized");
        PresentReviewFailure presenter = new PresentReviewFailure(
                repository(run, 11), mutations, github, Clock.fixed(NOW, ZoneOffset.UTC));

        PresentReviewFailure.PresentationOutcome outcome = presenter.present(run.id());

        assertThat(outcome).isEqualTo(PresentReviewFailure.PresentationOutcome.PRESENTED);
        assertThat(run.commentReferences()).containsOnlyKeys(new FindingFingerprint("a".repeat(64)));
        assertThat(github.retractionRequests).hasSize(1);
        assertThat(github.checkRequests).singleElement().satisfies(request -> {
            assertThat(request.presentation().codeCommentsMayRemain()).isTrue();
            assertThat(request.presentation().safeSummary()).contains("comments may remain");
            assertThat(request.findings()).isEmpty();
        });
    }

    @Test
    void confirmedCommentCleanupClearsDurableReferencesBeforeNeutralPresentation() {
        ReviewRun run = failedPublicationRunWithComment();
        RecordingMutations mutations = new RecordingMutations();
        RecordingGitHub github = new RecordingGitHub(new AuthoritativeRevision(SHA));
        PresentReviewFailure presenter = new PresentReviewFailure(
                repository(run, 15), mutations, github, Clock.fixed(NOW, ZoneOffset.UTC));

        PresentReviewFailure.PresentationOutcome outcome = presenter.present(run.id());

        assertThat(outcome).isEqualTo(PresentReviewFailure.PresentationOutcome.PRESENTED);
        assertThat(run.commentReferences()).isEmpty();
        assertThat(github.retractionRequests).hasSize(1);
        assertThat(github.checkRequests).singleElement().satisfies(request -> {
            assertThat(request.presentation().codeCommentsMayRemain()).isFalse();
            assertThat(request.presentation().safeSummary()).contains("no code comments were published");
        });
        assertThat(mutations.savedVersions).containsExactly(15L);
        assertThat(mutations.savedCommentCounts).containsExactly(0);
    }

    private static ReviewRun failedExecutionRun() {
        ReviewRun run = ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(10, 20, 30, SHA),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", 3),
                NOW.minusSeconds(10));
        run.startAttempt(NOW.minusSeconds(5));
        run.recordTerminalAttemptFailure(
                new ReviewFailure(
                        "invalid_review_output",
                        FailureClass.TERMINAL,
                        "repository source must never be exposed"),
                new ExecutionMeasurements(5, 0, 0, Map.of()),
                NOW);
        return run;
    }

    private static ReviewRun failedPublicationRunWithComment() {
        FindingFingerprint fingerprint = new FindingFingerprint("a".repeat(64));
        ReviewFinding finding = new ReviewFinding(
                fingerprint,
                new CodeLocation("src/Foo.java", 12, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        "Issue",
                        "Description",
                        "Suggestion"),
                new FindingEvidence("Evidence", List.of(), "regex"));
        ReviewRun run = ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(10, 20, 30, SHA),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", 3),
                NOW.minusSeconds(20));
        run.startAttempt(NOW.minusSeconds(15));
        run.completeReview(
                List.of(finding),
                new ExecutionMeasurements(5, 1, 1, Map.of()),
                NOW.minusSeconds(10));
        run.drainEvents();
        run.acceptPublicationDecisions(Map.of(
                fingerprint, new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v1")));
        run.authorizePublication(new AuthoritativeRevision(SHA), NOW.minusSeconds(8));
        run.recordPublicationProgress(
                "check-40",
                Map.of(fingerprint, new PublicationReference("github_review_comment", "501")));
        run.recordJobSystemFailure(new ReviewFailure(
                "github_transient",
                FailureClass.TERMINAL,
                "review job attempts exhausted"), NOW.minusSeconds(2));
        return run;
    }

    private static ReviewRunRepository repository(ReviewRun run, long version) {
        return new ReviewRunRepository() {
            @Override
            public Optional<StoredReviewRun> find(ReviewRunId id) {
                return run.id().equals(id)
                        ? Optional.of(new StoredReviewRun(run, version)) : Optional.empty();
            }

            @Override
            public void insert(ReviewRun reviewRun) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long update(ReviewRun reviewRun, long expectedVersion) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final class RecordingMutations implements ReviewRunMutationStore {
        private Long savedExpectedVersion;
        private final List<Long> savedVersions = new java.util.ArrayList<>();
        private final List<Integer> savedCommentCounts = new java.util.ArrayList<>();

        @Override
        public long saveProgress(ReviewRun run, long expectedVersion) {
            savedExpectedVersion = expectedVersion;
            savedVersions.add(expectedVersion);
            savedCommentCounts.add(run.commentReferences().size());
            return expectedVersion + 1;
        }

        @Override
        public long saveAndEnqueue(
                ReviewRun run,
                long expectedVersion,
                List<DurableJobRequest> jobs,
                List<OutboxEvent> events) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingGitHub implements GitHubPublicationGateway {
        private final AuthoritativeRevision authoritative;
        private final List<CheckRunRequest> checkRequests = new java.util.ArrayList<>();
        private final List<InlineCommentRetractionRequest> retractionRequests =
                new java.util.ArrayList<>();
        private GitHubFailureException retractionFailure;
        private int commentMutations;

        private RecordingGitHub(AuthoritativeRevision authoritative) {
            this.authoritative = authoritative;
        }

        @Override
        public AuthoritativeRevision authoritativeRevision(PullRequestRevision revision) {
            return authoritative;
        }

        @Override
        public CheckRunArtifact upsertCheck(CheckRunRequest request) {
            checkRequests.add(request);
            return new CheckRunArtifact(request.existingGitHubArtifactId().orElse("check-41"));
        }

        @Override
        public dev.langchain4j.example.codereview.reviewops.application.github.InlineCommentArtifact
                reconcileInlineComment(InlineCommentRequest request) {
            commentMutations++;
            throw new AssertionError("failure presentation must not publish comments");
        }

        @Override
        public InlineCommentRetraction retractInlineComment(
                InlineCommentRetractionRequest request) {
            commentMutations++;
            retractionRequests.add(request);
            if (retractionFailure != null) {
                throw retractionFailure;
            }
            return new InlineCommentRetraction(request.fingerprint(), request.reference().externalId());
        }
    }
}
