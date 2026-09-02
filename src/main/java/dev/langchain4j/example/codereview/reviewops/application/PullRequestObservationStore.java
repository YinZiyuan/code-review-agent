package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public interface PullRequestObservationStore {

    ObservationResult admit(ObservationRequest request);

    record ObservationRequest(
            String deliveryId,
            String eventName,
            String payloadSha256,
            Instant receivedAt,
            ReviewRun reviewRun,
            DurableJobRequest executionJob,
            DurableJobRequest supersessionJob) {
        public ObservationRequest {
            if (payloadSha256 == null || !payloadSha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException(
                        "payloadSha256 must be 64 hexadecimal characters");
            }
            payloadSha256 = payloadSha256.toLowerCase(Locale.ROOT);
        }
    }

    enum ObservationStatus {
        ADMITTED,
        DUPLICATE_DELIVERY,
        EXISTING_REVISION
    }

    record ObservationResult(ObservationStatus status, ReviewRunId reviewRunId) {
        public ObservationResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reviewRunId, "reviewRunId");
        }
    }
}
