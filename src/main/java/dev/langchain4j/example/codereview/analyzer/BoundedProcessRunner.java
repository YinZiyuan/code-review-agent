package dev.langchain4j.example.codereview.analyzer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

public final class BoundedProcessRunner {

    public enum ProcessKind {
        JAVAC("javac"),
        SPOTBUGS("spotbugs");

        private final String metricValue;

        ProcessKind(String metricValue) {
            this.metricValue = metricValue;
        }
    }

    public enum Outcome {
        COMPLETED("completed"),
        TIMED_OUT("timed_out"),
        CANCELLED("cancelled"),
        START_FAILED("start_failed");

        private final String metricValue;

        Outcome(String metricValue) {
            this.metricValue = metricValue;
        }
    }

    public record Request(
            ProcessKind kind,
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputBytes) {

        public Request {
            kind = Objects.requireNonNull(kind, "kind");
            command = command == null ? List.of() : List.copyOf(command);
            workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
            timeout = Objects.requireNonNull(timeout, "timeout");
            if (command.isEmpty()) {
                throw new IllegalArgumentException("command must not be empty");
            }
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxOutputBytes <= 0) {
                throw new IllegalArgumentException("maxOutputBytes must be positive");
            }
        }
    }

    public record Result(
            Outcome outcome,
            OptionalInt exitCode,
            byte[] output,
            boolean outputTruncated) {

        public Result {
            outcome = Objects.requireNonNull(outcome, "outcome");
            exitCode = exitCode == null ? OptionalInt.empty() : exitCode;
            output = output == null ? new byte[0] : output.clone();
        }

        @Override
        public byte[] output() {
            return output.clone();
        }
    }

    private final MeterRegistry metrics;

    public BoundedProcessRunner(MeterRegistry metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public Result run(Request request) {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        Outcome outcome = Outcome.START_FAILED;
        Process process = null;
        OutputCollector collector = null;
        Thread drain = null;
        boolean interrupted = false;
        try {
            process = new ProcessBuilder(request.command())
                    .directory(request.workingDirectory().toFile())
                    .redirectErrorStream(true)
                    .start();
            collector = new OutputCollector(process.getInputStream(), request.maxOutputBytes());
            drain = new Thread(collector, "review-process-output-" + request.kind().metricValue);
            drain.setDaemon(true);
            drain.start();

            if (!process.waitFor(request.timeout().toNanos(), TimeUnit.NANOSECONDS)) {
                outcome = Outcome.TIMED_OUT;
                terminateTree(process);
            } else {
                outcome = Outcome.COMPLETED;
            }
        } catch (InterruptedException cancelled) {
            interrupted = true;
            outcome = Outcome.CANCELLED;
            terminateTree(process);
        } catch (IOException startFailure) {
            outcome = Outcome.START_FAILED;
        } finally {
            if (process != null && process.isAlive()) {
                terminateTree(process);
            }
            awaitDrain(drain, collector);
            Timer.builder("code.review.pipeline.process.duration")
                    .tag("process", request.kind().metricValue)
                    .tag("outcome", outcome.metricValue)
                    .register(metrics)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        OptionalInt exitCode = process != null && !process.isAlive()
                ? OptionalInt.of(process.exitValue())
                : OptionalInt.empty();
        return new Result(
                outcome,
                exitCode,
                collector == null ? new byte[0] : collector.bytes(),
                collector != null && collector.truncated());
    }

    private static void terminateTree(Process process) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants = new ArrayList<>();
        process.toHandle().descendants().forEach(descendants::add);
        for (int index = descendants.size() - 1; index >= 0; index--) {
            descendants.get(index).destroyForcibly();
        }
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitDrain(Thread drain, OutputCollector collector) {
        if (drain == null) {
            return;
        }
        try {
            drain.join(2_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (drain.isAlive() && collector != null) {
            collector.close();
            try {
                drain.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream input;
        private final int maxBytes;
        private final ByteArrayOutputStream captured;
        private volatile boolean truncated;

        private OutputCollector(InputStream input, int maxBytes) {
            this.input = input;
            this.maxBytes = maxBytes;
            this.captured = new ByteArrayOutputStream(Math.min(maxBytes, 8_192));
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8_192];
            try (input) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = maxBytes - captured.size();
                    if (remaining > 0) {
                        captured.write(buffer, 0, Math.min(remaining, read));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException ignored) {
                // Process output is untrusted diagnostics and is intentionally never logged.
            }
        }

        private byte[] bytes() {
            return captured.toByteArray();
        }

        private boolean truncated() {
            return truncated;
        }

        private void close() {
            try {
                input.close();
            } catch (IOException ignored) {
                // Closing a bounded diagnostic stream is best-effort.
            }
        }
    }
}
