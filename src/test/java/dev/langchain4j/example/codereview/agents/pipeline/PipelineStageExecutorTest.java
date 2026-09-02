package dev.langchain4j.example.codereview.agents.pipeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import dev.langchain4j.example.codereview.tools.CodeSearchTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineStageExecutorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void deadlineCancelsTheStageAndRecordsOnlyLowCardinalityOutcome() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        try (PipelineStageExecutor executor = new PipelineStageExecutor(metrics)) {
            assertThatThrownBy(() -> executor.run(
                    PipelineStageExecutor.Stage.REVIEW_MODEL,
                    Duration.ofMillis(50),
                    () -> {
                        started.countDown();
                        try {
                            Thread.sleep(10_000);
                        } catch (InterruptedException expected) {
                            interrupted.set(true);
                            Thread.currentThread().interrupt();
                        }
                        return "late";
                    }))
                    .isInstanceOf(ReviewStageTimeoutException.class)
                    .hasMessage("review stage timed out: review_model");

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).isTrue();
            assertThat(metrics.get("code.review.pipeline.stage.duration")
                    .tag("stage", "review_model")
                    .tag("outcome", "timeout")
                    .timer().count()).isEqualTo(1);
        }
    }

    @Test
    void successfulStageReturnsValueAndRecordsDuration() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        try (PipelineStageExecutor executor = new PipelineStageExecutor(metrics)) {
            assertThat(executor.run(
                    PipelineStageExecutor.Stage.DIFF_ANALYSIS,
                    Duration.ofSeconds(1),
                    () -> "done")).isEqualTo("done");
        }

        assertThat(metrics.get("code.review.pipeline.stage.duration")
                .tag("stage", "diff_analysis")
                .tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void boundedCapacityRejectsExcessStagesWithoutGrowingAnUnboundedQueue() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (PipelineStageExecutor executor = new PipelineStageExecutor(metrics, 1, 1)) {
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> executor.run(
                    PipelineStageExecutor.Stage.TOOL_ANALYSIS,
                    Duration.ofSeconds(2),
                    () -> await(running, release)));
            assertThat(running.await(1, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> queued = CompletableFuture.runAsync(() -> executor.run(
                    PipelineStageExecutor.Stage.TOOL_ANALYSIS,
                    Duration.ofSeconds(2),
                    () -> "queued"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.queuedTaskCount() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(executor.queuedTaskCount()).isEqualTo(1);

            assertThatThrownBy(() -> executor.run(
                    PipelineStageExecutor.Stage.REVIEW_MODEL,
                    Duration.ofSeconds(1),
                    () -> "excess"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("review stage capacity exhausted");
            assertThat(metrics.get("code.review.pipeline.stage.duration")
                    .tag("stage", "review_model")
                    .tag("outcome", "capacity")
                    .timer().count()).isEqualTo(1);

            release.countDown();
            first.get(1, TimeUnit.SECONDS);
            queued.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void timedOutCorpusScanObservesInterruptAndReleasesTheOnlyWorker() throws Exception {
        Path source = temporaryDirectory.resolve("Large.java");
        Files.writeString(source, "x".repeat(4 * 1024 * 1024));
        ReviewWorkBudget base = new ReviewWorkBudgetProperties(
                null, null, null, null, null, null).toBudget();
        ReviewWorkBudget.InputLimits input = base.input();
        ReviewWorkBudget budget = new ReviewWorkBudget(
                base.version(),
                new ReviewWorkBudget.InputLimits(
                        input.maxDiffBytes(), input.maxChangedFiles(),
                        input.maxJavaSourceFiles(), input.maxJavaSourceBytes(), 5 * 1024 * 1024,
                        input.maxArchiveBytes(), input.maxExpandedBytes(),
                        input.maxArchiveEntries(), input.maxSnippets(), input.maxFindings()),
                base.prompt(), base.process(), base.stages(), base.workspace(), base.execution());
        CodeSearchTool search = new CodeSearchTool();
        try (PipelineStageExecutor executor = new PipelineStageExecutor(
                new SimpleMeterRegistry(), 1, 1)) {
            assertThatThrownBy(() -> executor.run(
                    PipelineStageExecutor.Stage.DIFF_ANALYSIS,
                    Duration.ofMillis(5),
                    () -> search.search(temporaryDirectory, List.of("absent"), budget)))
                    .isInstanceOf(ReviewStageTimeoutException.class);

            assertThat(executor.run(
                    PipelineStageExecutor.Stage.DIFF_ANALYSIS,
                    Duration.ofSeconds(1),
                    () -> "worker-released")).isEqualTo("worker-released");
        }
    }

    private static String await(CountDownLatch running, CountDownLatch release) {
        running.countDown();
        try {
            release.await();
            return "released";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "cancelled";
        }
    }
}
