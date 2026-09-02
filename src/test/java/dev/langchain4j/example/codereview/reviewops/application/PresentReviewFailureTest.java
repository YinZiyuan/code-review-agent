package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationReference;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
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

        @Override
        public long saveProgress(ReviewRun run, long expectedVersion) {
            savedExpectedVersion = expectedVersion;
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
            return new CheckRunArtifact("check-41");
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
            return new InlineCommentRetraction(
                    new FindingFingerprint("a".repeat(64)),
                    new PublicationReference("github_review_comment", "1").externalId());
        }
    }
}
