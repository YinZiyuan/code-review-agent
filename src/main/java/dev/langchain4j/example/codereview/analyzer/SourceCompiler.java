package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import io.micrometer.core.instrument.Metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Runs javac out-of-process; source text and diagnostics are always treated as untrusted. */
public final class SourceCompiler {

    @FunctionalInterface
    public interface CompilerProcess {
        BoundedProcessRunner.Result run(BoundedProcessRunner.Request request);
    }

    private final CompilerProcess process;
    private final ReviewWorkBudget budget;

    public SourceCompiler() {
        this(new BoundedProcessRunner(Metrics.globalRegistry)::run,
                new ReviewWorkBudgetProperties(null, null, null, null, null, null).toBudget());
    }

    public SourceCompiler(CompilerProcess process, ReviewWorkBudget budget) {
        this.process = Objects.requireNonNull(process, "process");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public CompilationResult compile(Path sourceDirectory, Path classesDirectory) {
        Objects.requireNonNull(sourceDirectory, "sourceDirectory");
        Objects.requireNonNull(classesDirectory, "classesDirectory");
        Path argumentFile = null;
        try {
            List<Path> sources = boundedSources(sourceDirectory);
            if (sources == null) {
                return result(CompilationResult.Status.LIMIT_EXCEEDED, "source limit exceeded");
            }
            if (sources.isEmpty()) {
                return result(CompilationResult.Status.NO_SOURCES, "no Java sources");
            }
            if (!Files.isDirectory(classesDirectory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(classesDirectory)) {
                return result(CompilationResult.Status.FAILED, "compiler unavailable");
            }

            argumentFile = Files.createTempFile(
                    classesDirectory.getParent(), "javac-", ".args");
            Files.writeString(argumentFile, sourceArguments(sources),
                    StandardOpenOption.TRUNCATE_EXISTING);
            List<String> command = List.of(
                    javacExecutable(),
                    "-J-Xmx" + budget.process().compilerMaxHeapMb() + "m",
                    "-nowarn",
                    "-proc:none",
                    "-d",
                    classesDirectory.toAbsolutePath().toString(),
                    "@" + argumentFile.toAbsolutePath());
            BoundedProcessRunner.Result processResult = process.run(new BoundedProcessRunner.Request(
                    BoundedProcessRunner.ProcessKind.JAVAC,
                    command,
                    classesDirectory.getParent(),
                    budget.stages().compiler(),
                    budget.process().maxOutputBytes()));
            return map(processResult);
        } catch (IOException | RuntimeException exception) {
            return result(CompilationResult.Status.FAILED, "compiler unavailable");
        } finally {
            if (argumentFile != null) {
                try {
                    Files.deleteIfExists(argumentFile);
                } catch (IOException ignored) {
                    // The enclosing marker-bearing workspace owns any failed deletion.
                }
            }
        }
    }

    private List<Path> boundedSources(Path sourceDirectory) throws IOException {
        if (!Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(sourceDirectory)) {
            return List.of();
        }
        List<Path> sources = new java.util.ArrayList<>();
        long bytes = 0;
        try (Stream<Path> walk = Files.walk(sourceDirectory)) {
            Iterator<Path> javaFiles = walk
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .iterator();
            while (javaFiles.hasNext()) {
                Path source = javaFiles.next();
                if (sources.size() >= budget.input().maxJavaSourceFiles()) {
                    return null;
                }
                long size = Files.size(source);
                if (size > budget.input().maxJavaSourceBytes() - bytes) {
                    return null;
                }
                sources.add(source);
                bytes += size;
            }
        }
        sources.sort(Comparator.comparing(path -> sourceDirectory.relativize(path).toString()));
        return sources;
    }

    private static String sourceArguments(List<Path> sources) {
        return sources.stream().map(path -> path.toAbsolutePath().toString())
                .map(SourceCompiler::quoteArgument)
                .reduce("", (left, right) -> left + right + System.lineSeparator());
    }

    private static String quoteArgument(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String javacExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "javac.exe" : "javac";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static CompilationResult map(BoundedProcessRunner.Result processResult) {
        return switch (processResult.outcome()) {
            case TIMED_OUT -> result(CompilationResult.Status.TIMED_OUT, "compiler timed out");
            case CANCELLED -> result(CompilationResult.Status.CANCELLED, "compiler cancelled");
            case START_FAILED -> result(CompilationResult.Status.FAILED, "compiler unavailable");
            case COMPLETED -> processResult.exitCode().orElse(-1) == 0
                    ? result(CompilationResult.Status.COMPILED, "compiled")
                    : result(CompilationResult.Status.FAILED, "compiler failed");
        };
    }

    private static CompilationResult result(
            CompilationResult.Status status, String safeReason) {
        return new CompilationResult(status, safeReason);
    }
}
