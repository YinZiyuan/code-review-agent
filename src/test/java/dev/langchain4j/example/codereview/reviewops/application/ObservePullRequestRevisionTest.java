package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationRequest;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationResult;
import dev.langchain4j.example.codereview.reviewops.application.github.VerifiedPullRequestEvent;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationStatus.ADMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservePullRequestRevisionTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-09-01T04:05:06Z");
    private static final Instant ADMITTED_AT = Instant.parse("2026-09-01T04:06:07Z");
    private static final String PAYLOAD_SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final ReviewConfigurationSnapshot CONFIGURATION =
            new ReviewConfigurationSnapshot(
                    "pipeline-v3", "configuration-v7", "kimi-k2", "policy-v5", 3);

    @Test
    void translatesVerifiedFactsAndPayloadDigestIntoANewReviewExecutionIntent() {
        CapturingObservationStore store = new CapturingObservationStore();
        ObservePullRequestRevision useCase = new ObservePullRequestRevision(
                store, Clock.fixed(ADMITTED_AT, ZoneOffset.UTC), CONFIGURATION);

        ObservationResult result = useCase.observe(verifiedEvent(), PAYLOAD_SHA256);

        ObservationRequest request = store.captured;
        assertThat(request.deliveryId()).isEqualTo("delivery-123");
        assertThat(request.eventName()).isEqualTo("pull_request");
        assertThat(request.payloadSha256()).isEqualTo(PAYLOAD_SHA256);
        assertThat(request.receivedAt()).isEqualTo(OBSERVED_AT);
        assertThat(request.reviewRun().revision().installationId()).isEqualTo(41L);
        assertThat(request.reviewRun().revision().repositoryId()).isEqualTo(73L);
        assertThat(request.reviewRun().revision().pullRequestNumber()).isEqualTo(12);
        assertThat(request.reviewRun().revision().headSha())
                .isEqualTo("0123456789abcdef0123456789abcdef01234567");
        assertThat(request.reviewRun().configuration()).isEqualTo(CONFIGURATION);
        assertThat(request.reviewRun().requestedAt()).isEqualTo(ADMITTED_AT);
        assertThat(request.executionJob().jobType()).isEqualTo("REVIEW_EXECUTION");
        assertThat(request.executionJob().payloadReference())
                .isEqualTo(request.reviewRun().id().value());
        assertThat(request.executionJob().maxAttempts()).isEqualTo(3);
        assertThat(request.executionJob().nextAttemptAt()).isEqualTo(ADMITTED_AT);
        assertThat(request.executionJob().idempotencyKey())
                .isEqualTo("review-execution:" + request.reviewRun().id().value());
        assertThat(result.status()).isEqualTo(ADMITTED);
        assertThat(result.reviewRunId()).isEqualTo(request.reviewRun().id());
    }

    @Test
    void rejectsANonSha256PayloadDigestBeforeCallingPersistence() {
        CapturingObservationStore store = new CapturingObservationStore();
        ObservePullRequestRevision useCase = new ObservePullRequestRevision(
                store, Clock.fixed(ADMITTED_AT, ZoneOffset.UTC), CONFIGURATION);

        assertThatThrownBy(() -> useCase.observe(verifiedEvent(), "not-a-sha256-digest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payloadSha256 must be 64 hexadecimal characters");

        assertThat(store.captured).isNull();
    }

    private static VerifiedPullRequestEvent verifiedEvent() {
        return new VerifiedPullRequestEvent(
                "delivery-123",
                "opened",
                41L,
                73L,
                "octo/repo",
                12,
                "0123456789abcdef0123456789abcdef01234567",
                "https://github.com/octo/repo.git",
                OBSERVED_AT);
    }

    private static final class CapturingObservationStore implements PullRequestObservationStore {

        private ObservationRequest captured;

        @Override
        public ObservationResult admit(ObservationRequest request) {
            captured = request;
            return new ObservationResult(ADMITTED, request.reviewRun().id());
        }
    }
}
