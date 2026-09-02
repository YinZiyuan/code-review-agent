package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.jobs.ExpiredJobLeaseRecovery;
import dev.langchain4j.example.codereview.reviewops.application.jobs.LeasedJob;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
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

class RecoverExpiredReviewJobTest {

    private static final Instant NOW = Instant.parse("2026-09-01T07:00:00Z");

    @Test
    void exhaustedExecutionLeaseThatFailsRunAlsoRequestsNeutralPresentation() {
        ReviewRun run = requestedRun(1);
        run.startAttempt(NOW.minusSeconds(30));
        RecordingRepository repository = new RecordingRepository(run, 5);
        RecordingTelemetry telemetry = new RecordingTelemetry();
        RecoverExpiredReviewExecution recovery =
                new RecoverExpiredReviewExecution(repository, telemetry);

        ExpiredJobLeaseRecovery.RecoverySettlement settlement = recovery.recoverWithIntents(
                finalLease(ReviewRunAdmissionStore.REVIEW_EXECUTION_JOB_TYPE, run.id()), NOW);

        assertThat(settlement.action()).isEqualTo(ExpiredJobLeaseRecovery.RecoveryAction.SUCCEEDED);
        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(repository.updatedVersion).isEqualTo(5L);
        assertThat(settlement.followUpJobs()).singleElement().satisfies(job -> {
            assertThat(job.jobType()).isEqualTo(ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE);
            assertThat(job.payloadReference()).isEqualTo(run.id().value());
        });
        assertThat(telemetry.failed).isOne();
    }

    @Test
    void everyUnfinishedIdempotentJobKindRecoversWithoutChargingAPhantomAttempt() {
        List<JobCase> cases = List.of(
                new JobCase(ExecuteReviewRun.DECIDE_PUBLICATION_JOB_TYPE, completedRun()),
                new JobCase(DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE, publishingRun()),
                new JobCase(SupersedeObsoleteReviewRuns.JOB_TYPE, requestedRun(3)),
                new JobCase(ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE, failedRun()));

        for (JobCase jobCase : cases) {
            RecoverExpiredReviewExecution recovery = new RecoverExpiredReviewExecution(
                    new RecordingRepository(jobCase.run(), 1));

            ExpiredJobLeaseRecovery.RecoverySettlement settlement = recovery.recoverWithIntents(
                    finalLease(jobCase.jobType(), jobCase.run().id()), NOW);

            assertThat(settlement.action())
                    .as(jobCase.jobType())
                    .isEqualTo(ExpiredJobLeaseRecovery.RecoveryAction.RETRY_WITHOUT_CHARGE);
        }
    }

    @Test
    void failedRunWithPersistedSuccessCheckStillSchedulesOrRetriesNeutralPresentation() {
        ReviewRun run = publishingRun();
        run.recordPublicationProgress("success-check", Map.of());
        run.recordJobSystemFailure(
                new dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure(
                        "github_transient",
                        dev.langchain4j.example.codereview.reviewops.domain.FailureClass.TERMINAL,
                        "review job attempts exhausted"),
                NOW.minusSeconds(2));
        RecoverExpiredReviewExecution recovery = new RecoverExpiredReviewExecution(
                new RecordingRepository(run, 3));

        ExpiredJobLeaseRecovery.RecoverySettlement publication =
                recovery.recoverWithIntents(finalLease(
                        DecideReviewPublication.PUBLISH_REVIEW_JOB_TYPE, run.id()), NOW);
        ExpiredJobLeaseRecovery.RecoverySettlement presentation =
                recovery.recoverWithIntents(finalLease(
                        ExecuteReviewRun.PRESENT_REVIEW_FAILURE_JOB_TYPE, run.id()),
                        NOW.plusSeconds(10));

        assertThat(publication.action())
                .isEqualTo(ExpiredJobLeaseRecovery.RecoveryAction.SUCCEEDED);
        assertThat(publication.followUpJobs()).singleElement().satisfies(intent ->
                assertThat(intent.nextAttemptAt()).isEqualTo(run.finishedAt().orElseThrow()));
        assertThat(presentation.action())
                .isEqualTo(ExpiredJobLeaseRecovery.RecoveryAction.RETRY_WITHOUT_CHARGE);
        assertThat(presentation.followUpJobs()).isEmpty();
    }

    private static LeasedJob finalLease(String type, ReviewRunId runId) {
        return new LeasedJob(UUID.randomUUID(), type, runId.value(), 9, 3, 3,
                NOW.minusSeconds(1));
    }

    private static ReviewRun requestedRun(int maxAttempts) {
        return ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(
                        10, 20, 30, "0123456789abcdef0123456789abcdef01234567"),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", maxAttempts),
                NOW.minusSeconds(60));
    }

    private static ReviewRun completedRun() {
        ReviewRun run = requestedRun(3);
        run.startAttempt(NOW.minusSeconds(20));
        run.completeReview(List.of(), new ExecutionMeasurements(1, 0, 0, Map.of()),
                NOW.minusSeconds(19));
        run.drainEvents();
        return run;
    }

    private static ReviewRun publishingRun() {
        ReviewRun run = completedRun();
        run.acceptPublicationDecisions(Map.of());
        run.authorizePublication(
                new dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision(
                        run.revision().headSha()),
                NOW.minusSeconds(10));
        return run;
    }

    private static ReviewRun failedRun() {
        ReviewRun run = requestedRun(3);
        run.recordJobSystemFailure(new dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure(
                "job_handler_failed",
                dev.langchain4j.example.codereview.reviewops.domain.FailureClass.TERMINAL,
                "review job failed terminally"), NOW.minusSeconds(1));
        return run;
    }

    private record JobCase(String jobType, ReviewRun run) {
    }

    private static final class RecordingTelemetry implements ReviewOperationsTelemetry {
        private int failed;

        @Override
        public void lifecycle(LifecycleOutcome outcome, int count) {
            assertThat(outcome).isEqualTo(LifecycleOutcome.FAILED);
            failed += count;
        }
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
