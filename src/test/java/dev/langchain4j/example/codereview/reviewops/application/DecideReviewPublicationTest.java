package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingPublicationPolicy;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationPolicySnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecideReviewPublicationTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final PublicationPolicySnapshot POLICY =
            new PublicationPolicySnapshot("policy-v1", 1);

    @Test
    void decidesEveryPersistedFindingOnceAndAtomicallyEnqueuesPublication() {
        ReviewFinding inline = finding("a", 10);
        ReviewFinding summary = finding("b", 20);
        ReviewRun run = completedRun(List.of(inline, summary));
        Map<FindingFingerprint, PublicationDecision> decisions = Map.of(
                inline.fingerprint(), new PublicationDecision(
                        PublicationTier.INLINE_COMMENT, POLICY.version()),
                summary.fingerprint(), new PublicationDecision(
                        PublicationTier.CHECK_SUMMARY, POLICY.version()));
        FindingPublicationPolicy policy = mock(FindingPublicationPolicy.class);
        when(policy.decide(run.findings(), POLICY)).thenReturn(decisions);
        RecordingMutationStore mutations = new RecordingMutationStore();
        DecideReviewPublication decider = new DecideReviewPublication(
                new FixedReviewRunRepository(run, 7),
                mutations,
                policy,
                POLICY);

        DecideReviewPublication.DecisionOutcome outcome = decider.decide(run.id());

        assertThat(outcome).isEqualTo(DecideReviewPublication.DecisionOutcome.DECIDED);
        verify(policy, times(1)).decide(run.findings(), POLICY);
        assertThat(run.state()).isEqualTo(ReviewRunState.COMPLETED);
        assertThat(run.findings())
                .allSatisfy(finding -> assertThat(finding.publicationDecision()).isPresent());
        assertThat(run.findings())
                .extracting(finding -> finding.publicationDecision().orElseThrow().tier())
                .containsExactly(PublicationTier.INLINE_COMMENT, PublicationTier.CHECK_SUMMARY);
        assertThat(mutations.progressSaveCount).isZero();
        assertThat(mutations.atomicSaveCount).isOne();
        assertThat(mutations.atomicExpectedVersion).isEqualTo(7);
        assertThat(mutations.atomicState).isEqualTo(ReviewRunState.COMPLETED);
        assertThat(mutations.events).isEmpty();
        assertThat(mutations.jobs).singleElement().satisfies(job -> {
            assertThat(job.jobType()).isEqualTo("PUBLISH_REVIEW");
            assertThat(job.payloadReference()).isEqualTo(run.id().value());
            assertThat(job.maxAttempts()).isEqualTo(3);
            assertThat(job.nextAttemptAt()).isEqualTo(NOW.minusSeconds(10));
            assertThat(job.idempotencyKey()).isEqualTo("publish-review:" + run.id().value());
        });
    }

    @Test
    void retryAfterAtomicCommitDoesNotReevaluatePersistedDecisions() {
        ReviewFinding finding = finding("retry", 30);
        ReviewRun run = completedRun(List.of(finding));
        run.acceptPublicationDecisions(Map.of(
                finding.fingerprint(),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, POLICY.version())));
        FindingPublicationPolicy policy = mock(FindingPublicationPolicy.class);
        RecordingMutationStore mutations = new RecordingMutationStore();
        DecideReviewPublication decider = new DecideReviewPublication(
                new FixedReviewRunRepository(run, 8),
                mutations,
                policy,
                POLICY);

        DecideReviewPublication.DecisionOutcome outcome = decider.decide(run.id());

        assertThat(outcome).isEqualTo(DecideReviewPublication.DecisionOutcome.ALREADY_PROCESSED);
        verify(policy, never()).decide(run.findings(), POLICY);
        assertThat(mutations.progressSaveCount).isZero();
        assertThat(mutations.atomicSaveCount).isZero();
    }

    @Test
    void emptyFindingRetryReusesTheExactImmutablePublicationIntent() {
        ReviewRun run = completedRun(List.of());
        FindingPublicationPolicy policy = mock(FindingPublicationPolicy.class);
        when(policy.decide(run.findings(), POLICY)).thenReturn(Map.of());
        RecordingMutationStore mutations = new RecordingMutationStore();

        DecideReviewPublication.DecisionOutcome first = new DecideReviewPublication(
                new FixedReviewRunRepository(run, 9),
                mutations,
                policy,
                POLICY).decide(run.id());
        DecideReviewPublication.DecisionOutcome retry = new DecideReviewPublication(
                new FixedReviewRunRepository(run, 10),
                mutations,
                policy,
                POLICY).decide(run.id());

        assertThat(first).isEqualTo(DecideReviewPublication.DecisionOutcome.DECIDED);
        assertThat(retry).isEqualTo(DecideReviewPublication.DecisionOutcome.DECIDED);
        verify(policy, times(2)).decide(run.findings(), POLICY);
        assertThat(mutations.jobHistory).hasSize(2);
        assertThat(mutations.jobHistory.get(0)).isEqualTo(mutations.jobHistory.get(1));
        assertThat(mutations.jobHistory.get(0).nextAttemptAt()).isEqualTo(NOW.minusSeconds(10));
    }

    private static ReviewRun completedRun(List<ReviewFinding> findings) {
        ReviewRun run = ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(
                        10, 20, 30, "0123456789abcdef0123456789abcdef01234567"),
                new ReviewConfigurationSnapshot(
                        "pipeline-v1", "configuration-v1", "model-v1", "policy-v1", 3),
                NOW.minusSeconds(60));
        run.startAttempt(NOW.minusSeconds(30));
        run.completeReview(
                findings,
                new ExecutionMeasurements(100, 10, 2, Map.of()),
                NOW.minusSeconds(10));
        run.drainEvents();
        return run;
    }

    private static ReviewFinding finding(String suffix, int line) {
        return new ReviewFinding(
                new FindingFingerprint(String.valueOf(line).repeat(64).substring(0, 64)),
                new CodeLocation("src/" + suffix + ".java", line, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        "Issue " + suffix,
                        "Description " + suffix,
                        "Suggestion " + suffix),
                new FindingEvidence("Evidence " + suffix, List.of(), "regex"));
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
        private int atomicSaveCount;
        private long atomicExpectedVersion = -1;
        private ReviewRunState atomicState;
        private List<DurableJobRequest> jobs = List.of();
        private List<OutboxEvent> events = List.of();
        private final List<DurableJobRequest> jobHistory = new ArrayList<>();

        @Override
        public long saveProgress(ReviewRun run, long expectedVersion) {
            progressSaveCount++;
            return expectedVersion + 1;
        }

        @Override
        public long saveAndEnqueue(
                ReviewRun run,
                long expectedVersion,
                List<DurableJobRequest> jobs,
                List<OutboxEvent> events) {
            atomicSaveCount++;
            atomicExpectedVersion = expectedVersion;
            atomicState = run.state();
            this.jobs = List.copyOf(jobs);
            this.events = List.copyOf(events);
            this.jobHistory.addAll(jobs);
            return expectedVersion + 1;
        }
    }
}
