package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobQueue;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SettleReviewJobFailureTest {

    private static final Instant NOW = Instant.parse("2026-09-01T06:00:00Z");

    @Test
    void exhaustedPublicationAtomicallyFailsActiveRunAndRequestsNeutralPresentation() {
        ReviewRun run = publishingRun();
        RecordingRepository repository = new RecordingRepository(run, 12);
        SettleReviewJobFailure settlement = new SettleReviewJobFailure(repository);
        LeasedJob job = finalLease(DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE, run.id());

        var result = settlement.settleFinalFailure(
                job, FailureClass.TRANSIENT, "github_transient", NOW);

        assertThat(result.disposition()).isEqualTo(DurableJobQueue.FailureDisposition.DEAD);
        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.finalFailure()).get().satisfies(failure -> {
            assertThat(failure.code()).isEqualTo("github_transient");
            assertThat(failure.classification()).isEqualTo(FailureClass.TERMINAL);
            assertThat(failure.safeMessage()).isEqualTo("review job attempts exhausted");
        });
        assertThat(repository.updatedVersion).isEqualTo(12L);
        assertThat(result.followUpJobs()).singleElement().satisfies(followUp -> {
            assertThat(followUp.jobType()).isEqualTo(ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE);
            assertThat(followUp.payloadReference()).isEqualTo(run.id().value());
            assertThat(followUp.idempotencyKey()).isEqualTo(
                    "present-review-failure:" + run.id().value());
        });
    }

    @Test
    void exhaustedDecisionWithoutDurableDecisionFailsCompletedRun() {
        ReviewRun run = completedRun();
        RecordingRepository repository = new RecordingRepository(run, 4);
        SettleReviewJobFailure settlement = new SettleReviewJobFailure(repository);

        var result = settlement.settleFinalFailure(
                finalLease(ExecuteReviewRun.DECIDE_PUBLICATION_JOB_TYPE, run.id()),
                FailureClass.TRANSIENT,
                "job_handler_failed",
                NOW);

        assertThat(result.disposition()).isEqualTo(DurableJobQueue.FailureDisposition.DEAD);
        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(result.followUpJobs()).hasSize(1);
    }

    @Test
    void crashAfterDurablePublicationEffectMarksDeliverySucceeded() {
        ReviewRun run = publishingRun();
        run.recordPublicationProgress("check-1", Map.of());
        run.confirmPublication("check-1", Map.of(), NOW.minusSeconds(1));
        RecordingRepository repository = new RecordingRepository(run, 9);
        SettleReviewJobFailure settlement = new SettleReviewJobFailure(repository);

        var result = settlement.settleFinalFailure(
                finalLease(DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE, run.id()),
                FailureClass.TRANSIENT,
                "job_handler_failed",
                NOW);

        assertThat(result.disposition()).isEqualTo(DurableJobQueue.FailureDisposition.SUCCEEDED);
        assertThat(result.followUpJobs()).isEmpty();
        assertThat(repository.updatedVersion).isNull();
    }

    @Test
    void exhaustedSupersessionFailsItsStillActiveOwningRun() {
        ReviewRun run = requestedRun();
        RecordingRepository repository = new RecordingRepository(run, 3);
        SettleReviewJobFailure settlement = new SettleReviewJobFailure(repository);

        var result = settlement.settleFinalFailure(
                finalLease(SupersedeObsoleteReviewRuns.JOB_TYPE, run.id()),
                FailureClass.TERMINAL,
                "github_authorization",
                NOW);

        assertThat(result.disposition()).isEqualTo(DurableJobQueue.FailureDisposition.DEAD);
        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(result.followUpJobs()).hasSize(1);
    }

    private static LeasedJob finalLease(String type, ReviewRunId runId) {
        return new LeasedJob(UUID.randomUUID(), type, runId.value(), 7, 3, 3, NOW.plusSeconds(30));
    }

    private static ReviewRun requestedRun() {
        return ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(
                        10, 20, 30, "0123456789abcdef0123456789abcdef01234567"),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", 3),
                NOW.minusSeconds(30));
    }

    private static ReviewRun completedRun() {
        ReviewRun run = requestedRun();
        run.startAttempt(NOW.minusSeconds(20));
        run.completeReview(List.of(), new ExecutionMeasurements(1, 0, 0, Map.of()),
                NOW.minusSeconds(19));
        run.drainEvents();
        return run;
    }

    private static ReviewRun publishingRun() {
        ReviewRun run = completedRun();
        run.acceptPublicationDecisions(Map.of());
        run.authorizePublication(new AuthoritativeRevision(run.revision().headSha()),
                NOW.minusSeconds(10));
        return run;
    }

    private static final class RecordingRepository implements ReviewRunRepository {
        private final ReviewRun run;
        private final long version;
        private Long updatedVersion;

        private RecordingRepository(ReviewRun run, long version) {
            this.run = run;
            this.version = version;
        }

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
            updatedVersion = expectedVersion;
            return expectedVersion + 1;
        }
    }
}
