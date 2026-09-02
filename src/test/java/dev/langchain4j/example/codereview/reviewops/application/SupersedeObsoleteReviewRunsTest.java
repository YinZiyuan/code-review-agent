package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.InlineCommentArtifact;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class SupersedeObsoleteReviewRunsTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-09-01T12:00:00Z");
    private static final Instant SUPERSEDED_AT = REQUESTED_AT.plusSeconds(30);

    @Test
    void supersedesEachCandidateThroughItsOwnStoreOperation() {
        ReviewRun current = requested("new-head", REQUESTED_AT);
        ReviewRun first = requested("old-head-one", REQUESTED_AT.minusSeconds(2));
        ReviewRun second = requested("old-head-two", REQUESTED_AT.minusSeconds(1));
        RecordingObsoleteStore obsolete = new RecordingObsoleteStore(List.of(first, second));
        SupersedeObsoleteReviewRuns useCase = new SupersedeObsoleteReviewRuns(
                repositoryContaining(current),
                obsolete,
                githubHead("new-head"),
                Clock.fixed(SUPERSEDED_AT, ZoneOffset.UTC));

        SupersedeObsoleteReviewRuns.SupersessionOutcome outcome = useCase.execute(current.id());

        assertThat(outcome.status())
                .isEqualTo(SupersedeObsoleteReviewRuns.SupersessionStatus.COMPLETED);
        assertThat(outcome.supersededCount()).isEqualTo(2);
        assertThat(obsolete.scope).isEqualTo(new ObsoleteReviewRunStore.SupersessionScope(
                current.id(), current.revision()));
        assertThat(obsolete.updated).containsExactly(first.id(), second.id());
        assertThat(first.state()).isEqualTo(dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState.SUPERSEDED);
        assertThat(second.state()).isEqualTo(dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState.SUPERSEDED);
    }

    @Test
    void missingSupersessionSourceIsTerminalWithoutScanningCandidates() {
        RecordingObsoleteStore obsolete = new RecordingObsoleteStore(List.of(
                requested("old-head", REQUESTED_AT.minusSeconds(1))));
        SupersedeObsoleteReviewRuns useCase = new SupersedeObsoleteReviewRuns(
                repositoryContaining(null),
                obsolete,
                githubHead("new-head"),
                Clock.fixed(SUPERSEDED_AT, ZoneOffset.UTC));

        SupersedeObsoleteReviewRuns.SupersessionOutcome outcome =
                useCase.execute(ReviewRunId.newId());

        assertThat(outcome.status())
                .isEqualTo(SupersedeObsoleteReviewRuns.SupersessionStatus.NOT_FOUND);
        assertThat(obsolete.scope).isNull();
        assertThat(obsolete.updated).isEmpty();
    }

    @Test
    void delayedOldWebhookSupersedesItsOwnRunWithoutSupersedingTheAuthoritativeHead() {
        ReviewRun delayedOld = requested("old-head", REQUESTED_AT.plusSeconds(10));
        ReviewRun authoritative = requested("real-head", REQUESTED_AT);
        RecordingObsoleteStore obsolete = new RecordingObsoleteStore(
                List.of(delayedOld, authoritative));
        SupersedeObsoleteReviewRuns useCase = new SupersedeObsoleteReviewRuns(
                repositoryContaining(delayedOld),
                obsolete,
                githubHead("real-head"),
                Clock.fixed(SUPERSEDED_AT, ZoneOffset.UTC));

        SupersedeObsoleteReviewRuns.SupersessionOutcome outcome =
                useCase.execute(delayedOld.id());

        assertThat(outcome.status())
                .isEqualTo(SupersedeObsoleteReviewRuns.SupersessionStatus.STALE_SOURCE);
        assertThat(outcome.supersededCount()).isEqualTo(1);
        assertThat(delayedOld.state()).isEqualTo(
                dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState.SUPERSEDED);
        assertThat(authoritative.state()).isEqualTo(
                dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState.REQUESTED);
        assertThat(obsolete.updated).containsExactly(delayedOld.id());
        assertThat(obsolete.scope).isNull();
    }

    private static ReviewRun requested(String headSha, Instant requestedAt) {
        return ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(10, 20, 30, headSha),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", 3),
                requestedAt);
    }

    private static ReviewRunRepository repositoryContaining(ReviewRun run) {
        return new ReviewRunRepository() {
            @Override
            public Optional<StoredReviewRun> find(ReviewRunId id) {
                return run == null || !run.id().equals(id)
                        ? Optional.empty()
                        : Optional.of(new StoredReviewRun(run, 0));
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

    private static GitHubPublicationGateway githubHead(String headSha) {
        return new GitHubPublicationGateway() {
            @Override
            public AuthoritativeRevision authoritativeRevision(PullRequestRevision revision) {
                return new AuthoritativeRevision(headSha);
            }

            @Override
            public CheckRunArtifact upsertCheck(CheckRunRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InlineCommentRetraction retractInlineComment(
                    InlineCommentRetractionRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static final class RecordingObsoleteStore implements ObsoleteReviewRunStore {
        private final Map<ReviewRunId, ReviewRun> candidates = new LinkedHashMap<>();
        private final List<ReviewRunId> updated = new ArrayList<>();
        private SupersessionScope scope;

        private RecordingObsoleteStore(List<ReviewRun> candidates) {
            candidates.forEach(run -> this.candidates.put(run.id(), run));
        }

        @Override
        public List<ReviewRunId> findActiveObsoleteRunIds(SupersessionScope scope) {
            this.scope = scope;
            return List.copyOf(candidates.keySet());
        }

        @Override
        public UpdateResult updateInOwnTransaction(
                ReviewRunId obsoleteRunId,
                Function<ReviewRun, Boolean> mutation) {
            ReviewRun run = candidates.get(obsoleteRunId);
            if (run == null) {
                return UpdateResult.NOT_FOUND;
            }
            if (!mutation.apply(run)) {
                return UpdateResult.UNCHANGED;
            }
            updated.add(obsoleteRunId);
            return UpdateResult.UPDATED;
        }
    }
}
