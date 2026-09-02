package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.ToolStatus;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.PreparedReviewSource;
import dev.langchain4j.example.codereview.reviewops.application.github.ReviewSourceProvider;
import dev.langchain4j.example.codereview.reviewops.application.jobs.DurableJobRequest;
import dev.langchain4j.example.codereview.reviewops.application.outbox.OutboxEvent;
import dev.langchain4j.example.codereview.reviewops.domain.DomainEvent;
import dev.langchain4j.example.codereview.reviewops.domain.ExecutionMeasurements;
import dev.langchain4j.example.codereview.reviewops.domain.FailureClass;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFailure;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRun;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunCompleted;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunRepository;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunState;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

public final class ExecuteReviewRun {

    public static final String DECIDE_PUBLICATION_JOB_TYPE = "DECIDE_PUBLICATION";
    public static final String PRESENT_REVIEW_FAILURE_JOB_TYPE = "PRESENT_REVIEW_FAILURE";
    private static final String REVIEW_PROMPT_PREFIX = "Review the following diff:\n\n";

    private final ReviewRunRepository reviewRuns;
    private final ReviewRunMutationStore mutations;
    private final ReviewSourceProvider sources;
    private final CodeReviewAgent reviewer;
    private final ReviewFindingMapper findingMapper;
    private final DiffParser diffParser;
    private final Clock clock;

    public ExecuteReviewRun(
            ReviewRunRepository reviewRuns,
            ReviewRunMutationStore mutations,
            ReviewSourceProvider sources,
            CodeReviewAgent reviewer,
            ReviewFindingMapper findingMapper,
            DiffParser diffParser,
            Clock clock) {
        this.reviewRuns = Objects.requireNonNull(reviewRuns, "reviewRuns");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer");
        this.findingMapper = Objects.requireNonNull(findingMapper, "findingMapper");
        this.diffParser = Objects.requireNonNull(diffParser, "diffParser");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ExecutionOutcome execute(ReviewRunId id) {
        Objects.requireNonNull(id, "id");
        Optional<ReviewRunRepository.StoredReviewRun> loaded = reviewRuns.find(id);
        if (loaded.isEmpty()) {
            return ExecutionOutcome.notFound();
        }

        ReviewRun run = loaded.orElseThrow().reviewRun();
        long version = loaded.orElseThrow().version();
        if (run.state() == ReviewRunState.RUNNING) {
            Instant recoveredAt = clock.instant();
            run.recoverInterruptedAttempt(new ReviewFailure(
                    "worker_interrupted", FailureClass.TRANSIENT,
                    "interrupted execution details are not retained"), recoveredAt);
            if (run.state() == ReviewRunState.FAILED) {
                enqueueFailurePresentation(run, version, recoveredAt);
                return ExecutionOutcome.terminal(run.finalFailure().orElseThrow());
            }
            version = mutations.saveProgress(run, version);
        }

        ExecutionOutcome settled = outcomeForSettledState(run);
        if (settled != null) {
            return settled;
        }

        Instant startedAt = clock.instant();
        run.startAttempt(startedAt);
        version = mutations.saveProgress(run, version);

        PipelineOutput output;
        try {
            output = invokePipeline(run);
        } catch (RuntimeException exception) {
            TokenCounts tokenCounts = tokenCounts(exception);
            return persistExecutionFailure(
                    run, version, startedAt, classify(exception), tokenCounts);
        }

        Instant completedAt = clock.instant();
        ExecutionMeasurements measurements = measurements(
                startedAt, completedAt, output.inputTokens(), output.outputTokens(), output.toolStates());
        try {
            run.completeReview(output.findings(), measurements, completedAt);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return persistExecutionFailure(
                    run,
                    version,
                    startedAt,
                    Failure.invalidOutput(),
                    new TokenCounts(output.inputTokens(), output.outputTokens()));
        }

        List<OutboxEvent> events = run.drainEvents().stream()
                .map(this::toOutboxEvent)
                .toList();
        DurableJobRequest publicationDecision = new DurableJobRequest(
                DECIDE_PUBLICATION_JOB_TYPE,
                run.id().value(),
                run.configuration().maxReviewAttempts(),
                completedAt,
                "decide-publication:" + run.id().value());
        mutations.saveAndEnqueue(
                run, version, List.of(publicationDecision), events);
        return ExecutionOutcome.completed();
    }

    private PipelineOutput invokePipeline(ReviewRun run) {
        PreparedReviewSource prepared = null;
        RuntimeException pipelineFailure = null;
        CodeReviewAgent.ReviewExecution execution = null;
        try {
            prepared = Objects.requireNonNull(
                    sources.prepare(run.revision()), "prepared review source");
            String diffPatch = prepared.diffPatch();
            if (diffPatch == null || diffPatch.isBlank()) {
                throw new IllegalArgumentException("review diff is required");
            }
            FileDiffSet fileDiffs = FileDiffSet.from(diffParser.parse(diffPatch));
            execution = Objects.requireNonNull(
                    reviewer.reviewWithTelemetry(
                            REVIEW_PROMPT_PREFIX + diffPatch, prepared.sourceRoot()),
                    "review execution");
            ReviewResult result = execution.result();
            List<dev.langchain4j.example.codereview.model.ReviewFinding> pipelineFindings =
                    Objects.requireNonNull(result.findings(), "review findings");
            List<ReviewFinding> findings = pipelineFindings.stream()
                    .map(finding -> findingMapper.map(finding, fileDiffs))
                    .toList();
            requireUniqueFingerprints(findings);
            return new PipelineOutput(
                    findings, toolStates(result.toolStatus()),
                    execution.inputTokens(), execution.outputTokens());
        } catch (RuntimeException exception) {
            RuntimeException reported = exception;
            if (execution != null
                    && findCause(exception, CodeReviewAgent.ReviewExecutionException.class) == null) {
                reported = new CodeReviewAgent.ReviewExecutionException(
                        exception, execution.inputTokens(), execution.outputTokens());
            }
            pipelineFailure = reported;
            throw reported;
        } finally {
            if (prepared != null) {
                try {
                    prepared.close();
                } catch (RuntimeException closeFailure) {
                    if (pipelineFailure == null) {
                        if (execution != null) {
                            throw new CodeReviewAgent.ReviewExecutionException(
                                    closeFailure,
                                    execution.inputTokens(),
                                    execution.outputTokens());
                        }
                        throw closeFailure;
                    }
                    pipelineFailure.addSuppressed(closeFailure);
                }
            }
        }
    }

    private ExecutionOutcome persistExecutionFailure(
            ReviewRun run,
            long version,
            Instant startedAt,
            Failure classified,
            TokenCounts tokenCounts) {
        Instant failedAt = clock.instant();
        ExecutionMeasurements measurements = measurements(
                startedAt,
                failedAt,
                tokenCounts.inputTokens(),
                tokenCounts.outputTokens(),
                Map.of());
        if (classified.failure().classification() == FailureClass.TRANSIENT) {
            run.recordTransientAttemptFailure(classified.failure(), measurements, failedAt);
        } else {
            run.recordTerminalAttemptFailure(classified.failure(), measurements, failedAt);
        }
        if (run.state() == ReviewRunState.FAILED) {
            enqueueFailurePresentation(run, version, failedAt);
        } else {
            mutations.saveProgress(run, version);
        }
        if (run.state() == ReviewRunState.FAILED) {
            return ExecutionOutcome.terminal(run.finalFailure().orElseThrow());
        }
        return ExecutionOutcome.retryable(classified.failure(), classified.retryAt());
    }

    private void enqueueFailurePresentation(ReviewRun run, long expectedVersion, Instant failedAt) {
        DurableJobRequest failurePresentation = new DurableJobRequest(
                PRESENT_REVIEW_FAILURE_JOB_TYPE,
                run.id().value(),
                run.configuration().maxReviewAttempts(),
                failedAt,
                "present-review-failure:" + run.id().value());
        mutations.saveAndEnqueue(
                run, expectedVersion, List.of(failurePresentation), List.of());
    }

    private static ExecutionOutcome outcomeForSettledState(ReviewRun run) {
        return switch (run.state()) {
            case REQUESTED -> null;
            case COMPLETED, PUBLISHING, PUBLISHED -> ExecutionOutcome.alreadyProcessed();
            case SUPERSEDED -> ExecutionOutcome.superseded();
            case FAILED -> ExecutionOutcome.terminal(run.finalFailure().orElseThrow());
            case RUNNING -> throw new IllegalStateException("running attempt was not recovered");
        };
    }

    private static void requireUniqueFingerprints(List<ReviewFinding> findings) {
        Set<FindingFingerprint> unique = new LinkedHashSet<>();
        for (ReviewFinding finding : findings) {
            if (!unique.add(finding.fingerprint())) {
                throw new IllegalArgumentException("review output contains duplicate findings");
            }
        }
    }

    private static Map<String, String> toolStates(List<ToolStatus> statuses) {
        Objects.requireNonNull(statuses, "toolStatus");
        Map<String, String> toolStates = new LinkedHashMap<>();
        for (ToolStatus status : statuses) {
            Objects.requireNonNull(status, "tool status");
            if (status.tool() == null || status.tool().isBlank() || status.state() == null) {
                throw new IllegalArgumentException("tool status is invalid");
            }
            if (toolStates.putIfAbsent(status.tool(), status.state().name()) != null) {
                throw new IllegalArgumentException("duplicate tool status");
            }
        }
        return Map.copyOf(toolStates);
    }

    private static ExecutionMeasurements measurements(
            Instant startedAt,
            Instant endedAt,
            int inputTokens,
            int outputTokens,
            Map<String, String> toolStates) {
        long latencyMs = Math.max(0, Duration.between(startedAt, endedAt).toMillis());
        return new ExecutionMeasurements(latencyMs, inputTokens, outputTokens, toolStates);
    }

    private OutboxEvent toOutboxEvent(DomainEvent event) {
        if (event instanceof ReviewRunCompleted completed) {
            return new OutboxEvent(
                    UUID.randomUUID(),
                    "ReviewRun",
                    completed.reviewRunId().value(),
                    "ReviewRunCompleted",
                    "{\"reviewRunId\":\"" + completed.reviewRunId().value() + "\"}",
                    completed.occurredAt());
        }
        throw new IllegalArgumentException("unsupported review run event");
    }

    private static Failure classify(RuntimeException exception) {
        GitHubFailureException githubFailure = findCause(exception, GitHubFailureException.class);
        if (githubFailure != null) {
            return switch (githubFailure.classification()) {
                case TRANSIENT -> new Failure(
                        new ReviewFailure("github_transient", FailureClass.TRANSIENT,
                                githubFailure.getMessage()), null);
                case RATE_LIMITED -> new Failure(
                        new ReviewFailure("github_rate_limited", FailureClass.TRANSIENT,
                                githubFailure.getMessage()), githubFailure.retryAt().orElse(null));
                case AUTHORIZATION -> new Failure(
                        new ReviewFailure("github_authorization", FailureClass.TERMINAL,
                                githubFailure.getMessage()), null);
                case DETERMINISTIC_INPUT -> new Failure(
                        new ReviewFailure("github_deterministic_input", FailureClass.TERMINAL,
                                githubFailure.getMessage()), null);
            };
        }
        if (hasTimeoutCause(exception)) {
            return new Failure(new ReviewFailure(
                    "model_timeout", FailureClass.TRANSIENT, "review model timed out"), null);
        }
        if (findCause(exception, JsonRepair.RepairFailedException.class) != null
                || findCause(exception, IllegalArgumentException.class) != null
                || findCause(exception, NullPointerException.class) != null) {
            return Failure.invalidOutput();
        }
        return new Failure(new ReviewFailure(
                "review_execution_failed", FailureClass.TRANSIENT,
                "review pipeline failed transiently"), null);
    }

    private static boolean hasTimeoutCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static TokenCounts tokenCounts(Throwable failure) {
        CodeReviewAgent.ReviewExecutionException measured = findCause(
                failure, CodeReviewAgent.ReviewExecutionException.class);
        return measured == null
                ? new TokenCounts(0, 0)
                : new TokenCounts(measured.inputTokens(), measured.outputTokens());
    }

    private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }

    public enum ExecutionStatus {
        COMPLETED,
        RETRYABLE_FAILURE,
        TERMINAL_FAILURE,
        ALREADY_PROCESSED,
        SUPERSEDED,
        NOT_FOUND
    }

    public record ExecutionOutcome(
            ExecutionStatus status,
            Optional<ReviewFailure> failure,
            Optional<Instant> retryAt) {

        public ExecutionOutcome {
            Objects.requireNonNull(status, "status");
            failure = failure == null ? Optional.empty() : failure;
            retryAt = retryAt == null ? Optional.empty() : retryAt;
        }

        private static ExecutionOutcome completed() {
            return new ExecutionOutcome(ExecutionStatus.COMPLETED, Optional.empty(), Optional.empty());
        }

        private static ExecutionOutcome retryable(ReviewFailure failure, Instant retryAt) {
            return new ExecutionOutcome(
                    ExecutionStatus.RETRYABLE_FAILURE,
                    Optional.of(failure),
                    Optional.ofNullable(retryAt));
        }

        private static ExecutionOutcome terminal(ReviewFailure failure) {
            return new ExecutionOutcome(
                    ExecutionStatus.TERMINAL_FAILURE, Optional.of(failure), Optional.empty());
        }

        private static ExecutionOutcome alreadyProcessed() {
            return new ExecutionOutcome(
                    ExecutionStatus.ALREADY_PROCESSED, Optional.empty(), Optional.empty());
        }

        private static ExecutionOutcome superseded() {
            return new ExecutionOutcome(
                    ExecutionStatus.SUPERSEDED, Optional.empty(), Optional.empty());
        }

        private static ExecutionOutcome notFound() {
            ReviewFailure failure = new ReviewFailure(
                    "review_run_not_found", FailureClass.TERMINAL, "review run was not found");
            return new ExecutionOutcome(
                    ExecutionStatus.NOT_FOUND, Optional.of(failure), Optional.empty());
        }
    }

    private record PipelineOutput(
            List<ReviewFinding> findings,
            Map<String, String> toolStates,
            int inputTokens,
            int outputTokens) {
        private PipelineOutput {
            findings = List.copyOf(findings);
            toolStates = Map.copyOf(toolStates);
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("model token usage must be non-negative");
            }
        }
    }

    private record Failure(ReviewFailure failure, Instant retryAt) {
        private Failure {
            Objects.requireNonNull(failure, "failure");
        }

        private static Failure invalidOutput() {
            return new Failure(new ReviewFailure(
                    "invalid_review_output", FailureClass.TERMINAL,
                    "review pipeline returned invalid output"), null);
        }
    }

    private record TokenCounts(int inputTokens, int outputTokens) {
        private TokenCounts {
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("model token usage must be non-negative");
            }
        }
    }
}
