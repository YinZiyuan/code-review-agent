package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.InlineCommentArtifact;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishReviewOutcomeTest {

    private static final Instant NOW = Instant.parse("2026-09-01T11:00:00Z");
    private static final String REVIEW_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String NEW_SHA = "abcdef0123456789abcdef0123456789abcdef01";

    @Test
    void staleAuthoritativeHeadIsDurablySupersededBeforeAnyGitHubMutation() {
        ReviewRun run = completedRunWithDecision();
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(NEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();
        PublishReviewOutcome publisher = new PublishReviewOutcome(
                new FixedReviewRunRepository(run, 4),
                mutations,
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC));

        PublishReviewOutcome.PublicationOutcome outcome = publisher.publish(run.id());

        assertThat(outcome).isEqualTo(PublishReviewOutcome.PublicationOutcome.SUPERSEDED);
        assertThat(gateway.authoritativeCalls).isOne();
        assertThat(gateway.requestedRevision).isEqualTo(run.revision());
        assertThat(gateway.checkMutations).isZero();
        assertThat(gateway.commentMutations).isZero();
        assertThat(run.state()).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThat(run.finishedAt()).contains(NOW);
        assertThat(mutations.progressSaveCount).isOne();
        assertThat(mutations.progressExpectedVersion).isEqualTo(4);
        assertThat(mutations.progressState).isEqualTo(ReviewRunState.SUPERSEDED);
        assertThat(mutations.atomicSaveCount).isZero();
    }

    @Test
    void matchingHeadAuthorizesCompletedRunBeforeArtifactWork() {
        ReviewRun run = completedRunWithDecision();
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();
        PublishReviewOutcome publisher = publisher(run, 11, mutations, gateway);

        PublishReviewOutcome.PublicationOutcome outcome = publisher.publish(run.id());

        assertThat(outcome).isEqualTo(PublishReviewOutcome.PublicationOutcome.AUTHORIZED);
        assertThat(gateway.authoritativeCalls).isOne();
        assertThat(gateway.checkMutations).isZero();
        assertThat(gateway.commentMutations).isZero();
        assertThat(run.state()).isEqualTo(ReviewRunState.PUBLISHING);
        assertThat(mutations.progressSaveCount).isOne();
        assertThat(mutations.progressExpectedVersion).isEqualTo(11);
        assertThat(mutations.progressState).isEqualTo(ReviewRunState.PUBLISHING);
        assertThat(mutations.atomicSaveCount).isZero();
    }

    @Test
    void publishingRetryRechecksHeadAndResumesPersistedProgressWithoutReauthorizing() {
        ReviewRun run = completedRunWithDecision();
        run.authorizePublication(new AuthoritativeRevision(REVIEW_SHA), NOW.minusSeconds(5));
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();

        PublishReviewOutcome.PublicationOutcome outcome =
                publisher(run, 12, mutations, gateway).publish(run.id());

        assertThat(outcome).isEqualTo(PublishReviewOutcome.PublicationOutcome.AUTHORIZED);
        assertThat(gateway.authoritativeCalls).isOne();
        assertThat(gateway.checkMutations).isZero();
        assertThat(gateway.commentMutations).isZero();
        assertThat(run.state()).isEqualTo(ReviewRunState.PUBLISHING);
        assertThat(mutations.progressSaveCount).isZero();
        assertThat(mutations.atomicSaveCount).isZero();
    }

    @Test
    void terminalRunsReturnTheirDurableOutcomeWithoutCallingGitHub() {
        ReviewRun superseded = completedRunWithDecision();
        superseded.authorizePublication(new AuthoritativeRevision(NEW_SHA), NOW.minusSeconds(3));
        assertTerminalOutcome(
                superseded, PublishReviewOutcome.PublicationOutcome.SUPERSEDED);

        ReviewRun published = completedRunWithDecision();
        published.authorizePublication(new AuthoritativeRevision(REVIEW_SHA), NOW.minusSeconds(3));
        published.confirmPublication("check-123", Map.of(), NOW.minusSeconds(2));
        assertTerminalOutcome(
                published, PublishReviewOutcome.PublicationOutcome.PUBLISHED);

        ReviewRun failed = completedRunWithDecision();
        failed.authorizePublication(new AuthoritativeRevision(REVIEW_SHA), NOW.minusSeconds(3));
        failed.recordPublicationFailure(
                new ReviewFailure(
                        "github_authorization",
                        FailureClass.TERMINAL,
                        "GitHub publication is not authorized"),
                NOW.minusSeconds(2));
        assertTerminalOutcome(failed, PublishReviewOutcome.PublicationOutcome.FAILED);
    }

    @Test
    void authoritativeLookupPreservesTaskFiveFailureClassificationForTheWorker() {
        for (GitHubFailureException.Classification classification
                : GitHubFailureException.Classification.values()) {
            ReviewRun run = completedRunWithDecision();
            RecordingMutationStore mutations = new RecordingMutationStore();
            GitHubFailureException failure = new GitHubFailureException(
                    classification,
                    "safe GitHub publication failure",
                    classification == GitHubFailureException.Classification.RATE_LIMITED
                            ? NOW.plusSeconds(60) : null);
            GitHubPublicationGateway gateway = new FailingGateway(failure);

            assertThatThrownBy(() -> publisher(run, 13, mutations, gateway).publish(run.id()))
                    .isSameAs(failure);

            assertThat(run.state()).isEqualTo(ReviewRunState.COMPLETED);
            assertThat(mutations.progressSaveCount).isZero();
            assertThat(mutations.atomicSaveCount).isZero();
        }
    }

    private static PublishReviewOutcome publisher(
            ReviewRun run,
            long version,
            RecordingMutationStore mutations,
            GitHubPublicationGateway gateway) {
        return new PublishReviewOutcome(
                new FixedReviewRunRepository(run, version),
                mutations,
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void assertTerminalOutcome(
            ReviewRun run, PublishReviewOutcome.PublicationOutcome expected) {
        RecordingGateway gateway = new RecordingGateway(new AuthoritativeRevision(REVIEW_SHA));
        RecordingMutationStore mutations = new RecordingMutationStore();

        assertThat(publisher(run, 20, mutations, gateway).publish(run.id())).isEqualTo(expected);
        assertThat(gateway.authoritativeCalls).isZero();
        assertThat(gateway.checkMutations).isZero();
        assertThat(gateway.commentMutations).isZero();
        assertThat(mutations.progressSaveCount).isZero();
        assertThat(mutations.atomicSaveCount).isZero();
    }

    private static ReviewRun completedRunWithDecision() {
        ReviewFinding finding = new ReviewFinding(
                new FindingFingerprint("a".repeat(64)),
                new CodeLocation("src/Foo.java", 10, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        "Issue",
                        "Description",
                        "Suggestion"),
                new FindingEvidence("Evidence", List.of(), "regex"));
        ReviewRun run = ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(10, 20, 30, REVIEW_SHA),
                new ReviewConfigurationSnapshot(
                        "pipeline-v1", "configuration-v1", "model-v1", "policy-v1", 3),
                NOW.minusSeconds(60));
        run.startAttempt(NOW.minusSeconds(30));
        run.completeReview(
                List.of(finding),
                new ExecutionMeasurements(100, 10, 2, Map.of()),
                NOW.minusSeconds(10));
        run.drainEvents();
        run.acceptPublicationDecisions(Map.of(
                finding.fingerprint(),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "policy-v1")));
        return run;
    }

    private record FixedReviewRunRepository(ReviewRun run, long version)
            implements ReviewRunRepository {

        @Override
        public Optional<StoredReviewRun> find(ReviewRunId id) {
            return run.id().equals(id) ? Optional.of(new StoredReviewRun(run, version)) : Optional.empty();
        }

        @Override
        public void insert(ReviewRun reviewRun) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long update(ReviewRun reviewRun, long expectedVersion) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingMutationStore implements ReviewRunMutationStore {
        private int progressSaveCount;
        private long progressExpectedVersion = -1;
        private ReviewRunState progressState;
        private int atomicSaveCount;

        @Override
        public long saveProgress(ReviewRun run, long expectedVersion) {
            progressSaveCount++;
            progressExpectedVersion = expectedVersion;
            progressState = run.state();
            return expectedVersion + 1;
        }

        @Override
        public long saveAndEnqueue(
                ReviewRun run,
                long expectedVersion,
                List<DurableJobRequest> jobs,
                List<OutboxEvent> events) {
            atomicSaveCount++;
            return expectedVersion + 1;
        }
    }

    private static final class RecordingGateway implements GitHubPublicationGateway {
        private final AuthoritativeRevision authoritative;
        private int authoritativeCalls;
        private PullRequestRevision requestedRevision;
        private int checkMutations;
        private int commentMutations;

        private RecordingGateway(AuthoritativeRevision authoritative) {
            this.authoritative = authoritative;
        }

        @Override
        public AuthoritativeRevision authoritativeRevision(PullRequestRevision revision) {
            authoritativeCalls++;
            requestedRevision = revision;
            return authoritative;
        }

        @Override
        public CheckRunArtifact upsertCheck(CheckRunRequest request) {
            checkMutations++;
            throw new AssertionError("stale runs must not mutate a Check Run");
        }

        @Override
        public InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request) {
            commentMutations++;
            throw new AssertionError("stale runs must not mutate inline comments");
        }
    }

    private static final class FailingGateway implements GitHubPublicationGateway {
        private final GitHubFailureException failure;

        private FailingGateway(GitHubFailureException failure) {
            this.failure = failure;
        }

        @Override
        public AuthoritativeRevision authoritativeRevision(PullRequestRevision revision) {
            throw failure;
        }

        @Override
        public CheckRunArtifact upsertCheck(CheckRunRequest request) {
            throw new AssertionError("lookup failure must prevent Check mutation");
        }

        @Override
        public InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request) {
            throw new AssertionError("lookup failure must prevent comment mutation");
        }
    }
}
