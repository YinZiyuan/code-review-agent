package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRunRepositoryContractTest {

    @Test
    void storedReviewRunRejectsNegativeVersion() {
        assertThatThrownBy(() -> new ReviewRunRepository.StoredReviewRun(requestedRun(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrencyExceptionExposesReviewRunIdentityAndExpectedVersion() {
        ReviewRunId id = ReviewRunId.newId();

        ReviewRunConcurrencyException exception = new ReviewRunConcurrencyException(id, 7);

        assertThat(exception.reviewRunId()).isEqualTo(id);
        assertThat(exception.expectedVersion()).isEqualTo(7);
    }

    @Test
    void duplicateExceptionExposesReviewRunIdentity() {
        ReviewRunId id = ReviewRunId.newId();

        DuplicateReviewRunException exception = new DuplicateReviewRunException(id);

        assertThat(exception.reviewRunId()).isEqualTo(id);
    }

    private static ReviewRun requestedRun() {
        return ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(17, 29, 41, "head-sha"),
                new ReviewConfigurationSnapshot(
                        "pipeline-v2", "configuration-v5", "model-v3", "policy-v4", 3),
                Instant.parse("2026-08-31T00:00:00Z"));
    }
}
