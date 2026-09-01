package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.model.ToolRunState;
import dev.langchain4j.example.codereview.model.ToolStatus;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.PreparedReviewSource;
import dev.langchain4j.example.codereview.reviewops.application.github.ReviewSourceProvider;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecuteReviewRunTest {

    private static final Instant T0 = Instant.parse("2026-09-01T01:00:00Z");
    private static final Path SOURCE_ROOT = Path.of("/tmp/exact-sha-source");
    private static final String DIFF = """
            diff --git a/src/Foo.java b/src/Foo.java
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -11,0 +12,1 @@
            +String query = input;
            """;

    @Test
    void completesThroughExistingAgentAndAtomicallyRequestsPublication() {
        ReviewRun run = requestedRun(3);
        FakeReviewRunRepository repository = new FakeReviewRunRepository(run, 7);
        RecordingMutationStore mutations = new RecordingMutationStore();
        FakePreparedSource source = new FakePreparedSource(DIFF, SOURCE_ROOT);
        MutableClock clock = new MutableClock(T0);
        List<ToolStatus> mutableStatuses = new ArrayList<>(
                List.of(new ToolStatus("spotbugs", ToolRunState.RAN, null)));
        RecordingAgent agent = new RecordingAgent((request, sourceRoot) -> {
            assertThat(mutations.progressStates).containsExactly(ReviewRunState.RUNNING);
            assertThat(source.closed).isFalse();
            clock.advance(Duration.ofMillis(250));
            return new ReviewResult("summary", List.of(pipelineFinding(12)), mutableStatuses);
        });
        ExecuteReviewRun executor = executor(
                repository, mutations, revision -> source, agent, clock);

        ExecuteReviewRun.ExecutionOutcome outcome = executor.execute(run.id());

        assertThat(outcome.status()).isEqualTo(ExecuteReviewRun.ExecutionStatus.COMPLETED);
        assertThat(agent.request).isEqualTo("Review the following diff:\n\n" + DIFF);
        assertThat(agent.sourceRoot).isEqualTo(SOURCE_ROOT);
        assertThat(source.closed).isTrue();
        assertThat(mutations.progressStates).containsExactly(ReviewRunState.RUNNING);
        assertThat(mutations.progressExpectedVersions).containsExactly(7L);
        assertThat(mutations.atomicState).isEqualTo(ReviewRunState.COMPLETED);
        assertThat(mutations.atomicExpectedVersion).isEqualTo(8L);
        assertThat(mutations.jobs).singleElement().satisfies(job -> {
            assertThat(job.jobType()).isEqualTo("DECIDE_PUBLICATION");
            assertThat(job.payloadReference()).isEqualTo(run.id().value());
            assertThat(job.maxAttempts()).isEqualTo(3);
            assertThat(job.nextAttemptAt()).isEqualTo(T0.plusMillis(250));
            assertThat(job.idempotencyKey()).isEqualTo("decide-publication:" + run.id().value());
        });
        assertThat(mutations.events).singleElement().satisfies(event -> {
            assertThat(event.aggregateType()).isEqualTo("ReviewRun");
            assertThat(event.aggregateId()).isEqualTo(run.id().value());
            assertThat(event.eventType()).isEqualTo("ReviewRunCompleted");
            assertThat(event.payload()).isEqualTo(
                    "{\"reviewRunId\":\"" + run.id().value() + "\"}");
            assertThat(event.occurredAt()).isEqualTo(T0.plusMillis(250));
        });
        assertThat(run.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.location().line()).isEqualTo(12);
            assertThat(finding.location().changedLine()).isTrue();
        });
        ExecutionMeasurements measurements = run.attempts().get(0).measurements().orElseThrow();
        assertThat(measurements).isEqualTo(
                new ExecutionMeasurements(250, 0, 0, java.util.Map.of("spotbugs", "RAN")));
        mutableStatuses.clear();
        assertThat(measurements.toolStates()).containsExactlyEntriesOf(
                java.util.Map.of("spotbugs", "RAN"));
        assertThatThrownBy(() -> measurements.toolStates().put("regex", "FAILED"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void modelTimeoutIsPersistedAsSafeTransientFailureAndSourceIsClosed() {
        ReviewRun run = requestedRun(3);
        RecordingMutationStore mutations = new RecordingMutationStore();
        FakePreparedSource source = new FakePreparedSource(DIFF, SOURCE_ROOT);
        MutableClock clock = new MutableClock(T0);
        RecordingAgent agent = new RecordingAgent((request, sourceRoot) -> {
            clock.advance(Duration.ofSeconds(2));
            throw new RuntimeException(new HttpTimeoutException("sensitive upstream detail"));
        });

        ExecuteReviewRun.ExecutionOutcome outcome = executor(
                new FakeReviewRunRepository(run, 4), mutations, revision -> source, agent, clock)
                .execute(run.id());

        assertThat(outcome.status()).isEqualTo(ExecuteReviewRun.ExecutionStatus.RETRYABLE_FAILURE);
        assertThat(outcome.failure()).contains(
                new ReviewFailure("model_timeout", FailureClass.TRANSIENT, "review model timed out"));
        assertThat(outcome.retryAt()).isEmpty();
        assertThat(run.state()).isEqualTo(ReviewRunState.REQUESTED);
        assertThat(run.attempts().get(0).measurements()).contains(
                new ExecutionMeasurements(2000, 0, 0, java.util.Map.of()));
        assertThat(source.closed).isTrue();
        assertThat(mutations.progressStates)
                .containsExactly(ReviewRunState.RUNNING, ReviewRunState.REQUESTED);
        assertThat(mutations.jobs).isEmpty();
        assertThat(mutations.events).isEmpty();
    }

    @Test
    void invalidPipelineOutputIsTerminalWithoutPublicationIntentAndClosesSource() {
        ReviewRun run = requestedRun(3);
        RecordingMutationStore mutations = new RecordingMutationStore();
        FakePreparedSource source = new FakePreparedSource(DIFF, SOURCE_ROOT);
        MutableClock clock = new MutableClock(T0);
        RecordingAgent agent = new RecordingAgent((request, sourceRoot) -> {
            clock.advance(Duration.ofMillis(10));
            return new ReviewResult("invalid", List.of(pipelineFinding(null)), List.of());
        });

        ExecuteReviewRun.ExecutionOutcome outcome = executor(
                new FakeReviewRunRepository(run, 2), mutations, revision -> source, agent, clock)
                .execute(run.id());

        assertThat(outcome.status()).isEqualTo(ExecuteReviewRun.ExecutionStatus.TERMINAL_FAILURE);
        assertThat(outcome.failure()).contains(new ReviewFailure(
                "invalid_review_output", FailureClass.TERMINAL,
                "review pipeline returned invalid output"));
        assertThat(run.state()).isEqualTo(ReviewRunState.FAILED);
        assertThat(run.findings()).isEmpty();
        assertThat(source.closed).isTrue();
        assertThat(mutations.progressStates)
                .containsExactly(ReviewRunState.RUNNING, ReviewRunState.FAILED);
        assertThat(mutations.jobs).isEmpty();
        assertThat(mutations.events).isEmpty();
    }

    @Test
    void exhaustedFormatRepairIsARequiredTerminalOutputFailure() {
        ReviewRun run = requestedRun(3);
        RecordingMutationStore mutations = new RecordingMutationStore();
        FakePreparedSource source = new FakePreparedSource(DIFF, SOURCE_ROOT);
        RecordingAgent agent = new RecordingAgent((request, sourceRoot) -> {
            throw new JsonRepair.RepairFailedException(
                    "unsafe malformed model output", new IllegalArgumentException("invalid JSON"));
        });

        ExecuteReviewRun.ExecutionOutcome outcome = executor(
                new FakeReviewRunRepository(run, 2), mutations, revision -> source, agent,
                new MutableClock(T0)).execute(run.id());

        assertThat(outcome.status()).isEqualTo(ExecuteReviewRun.ExecutionStatus.TERMINAL_FAILURE);
        assertThat(outcome.failure()).contains(new ReviewFailure(
                "invalid_review_output", FailureClass.TERMINAL,
                "review pipeline returned invalid output"));
        assertThat(source.closed).isTrue();
        assertThat(mutations.jobs).isEmpty();
    }

    @Test
    void reclaimedRunningAttemptIsClosedBeforeStartingTheNextAttempt() {
        ReviewRun run = requestedRun(3);
        run.startAttempt(T0.minusSeconds(30));
        RecordingMutationStore mutations = new RecordingMutationStore();
        FakePreparedSource source = new FakePreparedSource(DIFF, SOURCE_ROOT);
        MutableClock clock = new MutableClock(T0);
        RecordingAgent agent = new RecordingAgent((request, sourceRoot) ->
                new ReviewResult("summary", List.of(), List.of()));

        ExecuteReviewRun.ExecutionOutcome outcome = executor(
                new FakeReviewRunRepository(run, 11), mutations, revision -> source, agent, clock)
                .execute(run.id());

        assertThat(outcome.status()).isEqualTo(ExecuteReviewRun.ExecutionStatus.COMPLETED);
        assertThat(run.attempts()).hasSize(2);
        assertThat(run.attempts().get(0).failure()).contains(new ReviewFailure(
                "worker_interrupted", FailureClass.TRANSIENT, "review worker was interrupted"));
        assertThat(mutations.progressStates).containsExactly(
                ReviewRunState.REQUESTED, ReviewRunState.RUNNING);
        assertThat(mutations.progressExpectedVersions).containsExactly(11L, 12L);
        assertThat(mutations.atomicExpectedVersion).isEqualTo(13L);
        assertThat(source.closed).isTrue();
    }

    @Test
    void durableCompletionMakesExecutionReentryANoOp() {
        ReviewRun run = requestedRun(3);
        run.startAttempt(T0.minusSeconds(2));
        run.completeReview(List.of(), new ExecutionMeasurements(1, 0, 0, java.util.Map.of()),
                T0.minusSeconds(1));
        run.drainEvents();
        RecordingMutationStore mutations = new RecordingMutationStore();
        CountingSourceProvider sources = new CountingSourceProvider();
        RecordingAgent agent = new RecordingAgent((request, sourceRoot) ->
                new ReviewResult("must not run", List.of(), List.of()));

        ExecuteReviewRun.ExecutionOutcome outcome = executor(
                new FakeReviewRunRepository(run, 9), mutations, sources, agent,
                new MutableClock(T0)).execute(run.id());

        assertThat(outcome.status()).isEqualTo(ExecuteReviewRun.ExecutionStatus.ALREADY_PROCESSED);
        assertThat(sources.prepareCalls).isZero();
        assertThat(agent.calls).isZero();
        assertThat(mutations.progressStates).isEmpty();
        assertThat(mutations.jobs).isEmpty();
    }

    @Test
    void sourceFailuresPreserveSafeTaskFiveClassificationForTheWorker() {
        assertGitHubFailureMapping(
                GitHubFailureException.Classification.TRANSIENT,
                null,
                ExecuteReviewRun.ExecutionStatus.RETRYABLE_FAILURE,
                FailureClass.TRANSIENT,
                "github_transient");
        assertGitHubFailureMapping(
                GitHubFailureException.Classification.RATE_LIMITED,
                T0.plusSeconds(60),
                ExecuteReviewRun.ExecutionStatus.RETRYABLE_FAILURE,
                FailureClass.TRANSIENT,
                "github_rate_limited");
        assertGitHubFailureMapping(
                GitHubFailureException.Classification.AUTHORIZATION,
                null,
                ExecuteReviewRun.ExecutionStatus.TERMINAL_FAILURE,
                FailureClass.TERMINAL,
                "github_authorization");
        assertGitHubFailureMapping(
                GitHubFailureException.Classification.DETERMINISTIC_INPUT,
                null,
                ExecuteReviewRun.ExecutionStatus.TERMINAL_FAILURE,
                FailureClass.TERMINAL,
                "github_deterministic_input");
    }

    @Test
    void modelIsNotCalledUnlessStartedAttemptWasDurablySaved() {
        ReviewRun run = requestedRun(3);
        RecordingMutationStore mutations = new RecordingMutationStore();
        mutations.failNextProgressSave = true;
        CountingSourceProvider sources = new CountingSourceProvider();
        RecordingAgent agent = new RecordingAgent((request, sourceRoot) ->
                new ReviewResult("must not run", List.of(), List.of()));

        assertThatThrownBy(() -> executor(
                new FakeReviewRunRepository(run, 1), mutations, sources, agent,
                new MutableClock(T0)).execute(run.id()))
                .isInstanceOf(TestPersistenceFailure.class);

        assertThat(sources.prepareCalls).isZero();
        assertThat(agent.calls).isZero();
        assertThat(mutations.atomicState).isNull();
    }

    private void assertGitHubFailureMapping(
            GitHubFailureException.Classification githubClassification,
            Instant retryAt,
            ExecuteReviewRun.ExecutionStatus expectedStatus,
            FailureClass expectedFailureClass,
            String expectedCode) {
        ReviewRun run = requestedRun(3);
        RecordingMutationStore mutations = new RecordingMutationStore();
        ReviewSourceProvider sources = revision -> {
            throw new GitHubFailureException(
                    githubClassification, "safe GitHub source failure", retryAt);
        };

        ExecuteReviewRun.ExecutionOutcome outcome = executor(
                new FakeReviewRunRepository(run, 0), mutations, sources,
                new RecordingAgent((request, sourceRoot) -> null), new MutableClock(T0))
                .execute(run.id());

        assertThat(outcome.status()).isEqualTo(expectedStatus);
        assertThat(outcome.failure()).contains(
                new ReviewFailure(expectedCode, expectedFailureClass, "safe GitHub source failure"));
        assertThat(outcome.retryAt()).isEqualTo(Optional.ofNullable(retryAt));
        assertThat(run.state()).isEqualTo(
                expectedFailureClass == FailureClass.TRANSIENT
                        ? ReviewRunState.REQUESTED : ReviewRunState.FAILED);
    }

    private static ExecuteReviewRun executor(
            ReviewRunRepository repository,
            ReviewRunMutationStore mutations,
            ReviewSourceProvider sources,
            CodeReviewAgent agent,
            Clock clock) {
        return new ExecuteReviewRun(
                repository,
                mutations,
                sources,
                agent,
                new ReviewFindingMapper(),
                new DiffParser(),
                clock);
    }

    private static ReviewRun requestedRun(int maxAttempts) {
        return ReviewRun.request(
                ReviewRunId.newId(),
                new PullRequestRevision(10, 20, 30,
                        "0123456789abcdef0123456789abcdef01234567"),
                new ReviewConfigurationSnapshot(
                        "pipeline-v3", "configuration-v1", "model-v1", "policy-v1", maxAttempts),
                T0.minusSeconds(60));
    }

    private static dev.langchain4j.example.codereview.model.ReviewFinding pipelineFinding(
            Integer line) {
        return new dev.langchain4j.example.codereview.model.ReviewFinding(
                "F-001",
                "src/Foo.java",
                line,
                null,
                Severity.WARNING,
                Category.STABILITY,
                "Unsafe query",
                "description",
                "suggestion",
                "query uses untrusted input",
                List.of(),
                "llm_reviewer");
    }

    private static final class FakeReviewRunRepository implements ReviewRunRepository {
        private final StoredReviewRun stored;

        private FakeReviewRunRepository(ReviewRun run, long version) {
            this.stored = new StoredReviewRun(run, version);
        }

        @Override
        public Optional<StoredReviewRun> find(ReviewRunId id) {
            return stored.reviewRun().id().equals(id) ? Optional.of(stored) : Optional.empty();
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
        private final List<ReviewRunState> progressStates = new ArrayList<>();
        private final List<Long> progressExpectedVersions = new ArrayList<>();
        private ReviewRunState atomicState;
        private long atomicExpectedVersion = -1;
        private List<DurableJobRequest> jobs = List.of();
        private List<OutboxEvent> events = List.of();
        private boolean failNextProgressSave;

        @Override
        public long saveProgress(ReviewRun run, long expectedVersion) {
            progressStates.add(run.state());
            progressExpectedVersions.add(expectedVersion);
            if (failNextProgressSave) {
                failNextProgressSave = false;
                throw new TestPersistenceFailure();
            }
            return expectedVersion + 1;
        }

        @Override
        public long saveAndEnqueue(
                ReviewRun run,
                long expectedVersion,
                List<DurableJobRequest> jobs,
                List<OutboxEvent> events) {
            this.atomicState = run.state();
            this.atomicExpectedVersion = expectedVersion;
            this.jobs = List.copyOf(jobs);
            this.events = List.copyOf(events);
            return expectedVersion + 1;
        }
    }

    private static final class FakePreparedSource implements PreparedReviewSource {
        private final String diff;
        private final Path root;
        private boolean closed;

        private FakePreparedSource(String diff, Path root) {
            this.diff = diff;
            this.root = root;
        }

        @Override
        public String diffPatch() {
            return diff;
        }

        @Override
        public Path sourceRoot() {
            return root;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class CountingSourceProvider implements ReviewSourceProvider {
        private int prepareCalls;

        @Override
        public PreparedReviewSource prepare(PullRequestRevision revision) {
            prepareCalls++;
            return new FakePreparedSource(DIFF, SOURCE_ROOT);
        }
    }

    private static final class RecordingAgent implements CodeReviewAgent {
        private final AgentBehavior behavior;
        private int calls;
        private String request;
        private Path sourceRoot;

        private RecordingAgent(AgentBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public ReviewResult review(String request, Path sourceRoot) {
            calls++;
            this.request = request;
            this.sourceRoot = sourceRoot;
            return behavior.review(request, sourceRoot);
        }
    }

    @FunctionalInterface
    private interface AgentBehavior {
        ReviewResult review(String request, Path sourceRoot);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class TestPersistenceFailure extends RuntimeException {
    }
}
