package dev.langchain4j.example.codereview.agents.pipeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineStageExecutorTest {

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
}
