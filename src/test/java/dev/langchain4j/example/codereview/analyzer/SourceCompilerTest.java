package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SourceCompilerTest {

    @TempDir
    Path tmp;

    @Test
    void compilesInABoundedChildJvmWithHeapDeadlineAndOutputLimits() throws Exception {
        Path source = Files.createDirectory(tmp.resolve("source"));
        Path classes = Files.createDirectory(tmp.resolve("classes"));
        Files.writeString(source.resolve("Foo.java"),
                "public class Foo { public int x() { return 1; } }");
        AtomicReference<BoundedProcessRunner.Request> request = new AtomicReference<>();
        BoundedProcessRunner real = new BoundedProcessRunner(new SimpleMeterRegistry());
        SourceCompiler compiler = new SourceCompiler(candidate -> {
            request.set(candidate);
            return real.run(candidate);
        }, defaults());

        CompilationResult result = compiler.compile(source, classes);

        assertThat(result.status()).isEqualTo(CompilationResult.Status.COMPILED);
        assertThat(classes.resolve("Foo.class")).exists();
        assertThat(request.get().kind()).isEqualTo(BoundedProcessRunner.ProcessKind.JAVAC);
        assertThat(request.get().command()).contains("-J-Xmx256m", "-proc:none");
        assertThat(request.get().timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(request.get().maxOutputBytes()).isEqualTo(64 * 1024);
    }

    @Test
    void syntaxDiagnosticsContainingSecretsAreNeverLogged(CapturedOutput output) throws Exception {
        String secret = "ghp_must_not_escape_compiler_diagnostic_123";
        Path source = Files.createDirectory(tmp.resolve("bad-source"));
        Path classes = Files.createDirectory(tmp.resolve("bad-classes"));
        Files.writeString(source.resolve("SecretSyntax.java"),
                "public class SecretSyntax { String token = \"" + secret + "\" BROKEN }");

        CompilationResult result = realCompiler(defaults()).compile(source, classes);

        assertThat(result.status()).isEqualTo(CompilationResult.Status.FAILED);
        assertThat(result.safeReason()).isEqualTo("compiler failed");
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test
    void rejectsManyFilesAndOversizedSourcesBeforeStartingAProcess() throws Exception {
        ReviewWorkBudget constrained = budgetWithSourceLimits(defaults(), 1, 16);
        AtomicBoolean started = new AtomicBoolean();
        SourceCompiler compiler = new SourceCompiler(request -> {
            started.set(true);
            throw new AssertionError("process must not start");
        }, constrained);
        Path classes = Files.createDirectory(tmp.resolve("limited-classes"));
        Path many = Files.createDirectory(tmp.resolve("many"));
        Files.writeString(many.resolve("A.java"), "class A {}");
        Files.writeString(many.resolve("B.java"), "class B {}");

        assertThat(compiler.compile(many, classes).status())
                .isEqualTo(CompilationResult.Status.LIMIT_EXCEEDED);
        assertThat(started).isFalse();

        Path large = Files.createDirectory(tmp.resolve("large"));
        Files.writeString(large.resolve("Large.java"), "class Large { long padding123; }");
        assertThat(compiler.compile(large, classes).status())
                .isEqualTo(CompilationResult.Status.LIMIT_EXCEEDED);
        assertThat(started).isFalse();
    }

    @Test
    void timeoutAndOomEquivalentBecomeSafeNonBlockingResults() throws Exception {
        Path source = Files.createDirectory(tmp.resolve("timeout-source"));
        Files.writeString(source.resolve("Slow.java"), "class Slow {}");
        Path classes = Files.createDirectory(tmp.resolve("timeout-classes"));

        SourceCompiler timeout = new SourceCompiler(request -> new BoundedProcessRunner.Result(
                BoundedProcessRunner.Outcome.TIMED_OUT, OptionalInt.empty(),
                "untrusted timeout diagnostics".getBytes(), false), defaults());
        assertThat(timeout.compile(source, classes))
                .extracting(CompilationResult::status, CompilationResult::safeReason)
                .containsExactly(CompilationResult.Status.TIMED_OUT, "compiler timed out");

        SourceCompiler oom = new SourceCompiler(request -> new BoundedProcessRunner.Result(
                BoundedProcessRunner.Outcome.COMPLETED, OptionalInt.of(137),
                "OutOfMemoryError secret-value".getBytes(), false), defaults());
        assertThat(oom.compile(source, classes))
                .extracting(CompilationResult::status, CompilationResult::safeReason)
                .containsExactly(CompilationResult.Status.FAILED, "compiler failed");
    }

    @Test
    void emptyDirectoryDoesNotStartAProcess() throws Exception {
        SourceCompiler compiler = new SourceCompiler(
                request -> { throw new AssertionError("process must not start"); }, defaults());
        Path source = Files.createDirectory(tmp.resolve("empty"));
        Path classes = Files.createDirectory(tmp.resolve("empty-classes"));

        assertThat(compiler.compile(source, classes).status())
                .isEqualTo(CompilationResult.Status.NO_SOURCES);
    }

    private static SourceCompiler realCompiler(ReviewWorkBudget budget) {
        return new SourceCompiler(
                new BoundedProcessRunner(new SimpleMeterRegistry())::run, budget);
    }

    private static ReviewWorkBudget defaults() {
        return new ReviewWorkBudgetProperties(null, null, null, null, null, null).toBudget();
    }

    private static ReviewWorkBudget budgetWithSourceLimits(
            ReviewWorkBudget base, int files, long bytes) {
        ReviewWorkBudget.InputLimits input = base.input();
        return new ReviewWorkBudget(base.version(), new ReviewWorkBudget.InputLimits(
                input.maxDiffBytes(), input.maxChangedFiles(), files, bytes,
                input.maxArchiveBytes(), input.maxExpandedBytes(), input.maxArchiveEntries(),
                input.maxSnippets(), input.maxFindings()), base.prompt(), base.process(),
                base.stages(), base.workspace());
    }
}
