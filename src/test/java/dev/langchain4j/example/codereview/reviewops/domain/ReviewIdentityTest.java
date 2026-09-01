package dev.langchain4j.example.codereview.reviewops.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewIdentityTest {

    @Test
    void reviewRunIdCreatesNonNullUuid() {
        assertThat(ReviewRunId.newId().value()).isNotNull();
    }

    @Test
    void pullRequestRevisionRejectsInvalidIdentity() {
        assertThatThrownBy(() -> new PullRequestRevision(0, 2, 3, "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PullRequestRevision(1, 2, 0, "abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PullRequestRevision(1, 2, 3, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configurationVersionChangesConfigurationSnapshotIdentity() {
        ReviewConfigurationSnapshot snapshot =
                new ReviewConfigurationSnapshot(
                        "w4-tuned", "config-2026-08-31", "moonshot-v1-8k", "publish-v1", 3);
        ReviewConfigurationSnapshot changedConfigurationVersion =
                new ReviewConfigurationSnapshot(
                        "w4-tuned", "config-2026-09-01", "moonshot-v1-8k", "publish-v1", 3);

        assertThat(changedConfigurationVersion).isNotEqualTo(snapshot);
    }

    @Test
    void configurationRequiresAtLeastOneTotalAttempt() {
        ReviewConfigurationSnapshot snapshot =
                new ReviewConfigurationSnapshot(
                        "w4-tuned", "config-2026-08-31", "moonshot-v1-8k", "publish-v1", 3);

        assertThat(snapshot.maxReviewAttempts()).isEqualTo(3);
        assertThatThrownBy(() ->
                new ReviewConfigurationSnapshot(
                        "w4-tuned", "config-2026-08-31", "moonshot-v1-8k", "publish-v1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authoritativeRevisionComparesByHeadSha() {
        PullRequestRevision revision = new PullRequestRevision(1, 2, 3, "abc123");
        assertThat(new AuthoritativeRevision("abc123").matches(revision)).isTrue();
        assertThat(new AuthoritativeRevision("def456").matches(revision)).isFalse();
    }
}
