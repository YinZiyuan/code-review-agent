package dev.langchain4j.example.codereview.analyzer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedProcessRunnerTest {

    @TempDir
    Path workingDirectory;

    @Test
    void drainsFloodingOutputWithoutCapturingPastTheByteBudget() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        BoundedProcessRunner runner = new BoundedProcessRunner(metrics);

        BoundedProcessRunner.Result result = runner.run(new BoundedProcessRunner.Request(
                BoundedProcessRunner.ProcessKind.JAVAC,
                List.of("sh", "-c", "yes x | head -c 100000"),
                workingDirectory,
                Duration.ofSeconds(5),
                257));

        assertThat(result.outcome()).isEqualTo(BoundedProcessRunner.Outcome.COMPLETED);
        assertThat(result.exitCode()).hasValue(0);
        assertThat(result.output()).hasSize(257);
        assertThat(result.outputTruncated()).isTrue();
        assertThat(metrics.get("code.review.pipeline.process.duration")
                .tag("process", "javac").tag("outcome", "completed")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void timeoutTerminatesTheProcessAndItsDescendant() throws Exception {
        BoundedProcessRunner runner = new BoundedProcessRunner(new SimpleMeterRegistry());

        BoundedProcessRunner.Result result = runner.run(new BoundedProcessRunner.Request(
                BoundedProcessRunner.ProcessKind.SPOTBUGS,
                List.of("sh", "-c", "sleep 30 & child=$!; echo $child; wait $child"),
                workingDirectory,
                Duration.ofMillis(150),
                128));

        assertThat(result.outcome()).isEqualTo(BoundedProcessRunner.Outcome.TIMED_OUT);
        long childPid = Long.parseLong(new String(result.output(), StandardCharsets.UTF_8).trim());
        awaitNotAlive(childPid);
        assertThat(ProcessHandle.of(childPid)).isEmpty();
    }

    @Test
    void interruptionCancelsTheChildAndRestoresCallerInterruptStatus() throws Exception {
        BoundedProcessRunner runner = new BoundedProcessRunner(new SimpleMeterRegistry());
        AtomicReference<BoundedProcessRunner.Result> result = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread caller = new Thread(() -> {
            result.set(runner.run(new BoundedProcessRunner.Request(
                    BoundedProcessRunner.ProcessKind.JAVAC,
                    List.of("sh", "-c", "sleep 30"),
                    workingDirectory,
                    Duration.ofSeconds(20),
                    128)));
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        caller.start();
        Thread.sleep(150);
        caller.interrupt();
        caller.join(2_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(result.get().outcome()).isEqualTo(BoundedProcessRunner.Outcome.CANCELLED);
        assertThat(interrupted.get()).isTrue();
    }

    private static void awaitNotAlive(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
