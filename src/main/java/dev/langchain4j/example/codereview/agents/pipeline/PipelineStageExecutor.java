package dev.langchain4j.example.codereview.agents.pipeline;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class PipelineStageExecutor implements AutoCloseable {

    public enum Stage {
        DIFF_ANALYSIS("diff_analysis"),
        TOOL_ANALYSIS("tool_analysis"),
        REVIEW_MODEL("review_model"),
        SUMMARIZATION("summarization");

        private final String metricValue;

        Stage(String metricValue) {
            this.metricValue = metricValue;
        }
    }

    private final MeterRegistry metrics;
    private final ExecutorService executor;

    public PipelineStageExecutor(MeterRegistry metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(
                    runnable, "review-stage-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(4, threads);
    }

    public <T> T run(Stage stage, Duration timeout, Supplier<T> work) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(work, "work");
        long started = System.nanoTime();
        String outcome = "failure";
        Future<T> future = executor.submit(work::get);
        try {
            T result = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            outcome = "success";
            return result;
        } catch (TimeoutException timeoutFailure) {
            outcome = "timeout";
            future.cancel(true);
            throw new ReviewStageTimeoutException(stage.metricValue);
        } catch (InterruptedException interrupted) {
            outcome = "cancelled";
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("review stage was cancelled");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error) {
                throw new IllegalStateException("review stage failed");
            }
            throw new IllegalStateException("review stage failed");
        } finally {
            Timer.builder("code.review.pipeline.stage.duration")
                    .tag("stage", stage.metricValue)
                    .tag("outcome", outcome)
                    .register(metrics)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
