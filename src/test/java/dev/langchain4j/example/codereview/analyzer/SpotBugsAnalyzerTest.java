package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.workspace.ReviewWorkspace;
import dev.langchain4j.example.codereview.workspace.ReviewWorkspaceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpotBugsAnalyzerTest {

    @TempDir
    Path tmp;

    @Test
    void parsesFixtureXmlAndKeepsOnlyChangedLines() throws Exception {
        Path xml = Path.of("src/test/resources/fixtures/spotbugs/sample-output.xml");
        SpotBugsAnalyzer analyzer = new SpotBugsAnalyzer((classesDir, output) -> {
            Files.copy(xml, output, StandardCopyOption.REPLACE_EXISTING);
            return SpotBugsAnalyzer.RunOutcome.COMPLETED;
        }, new SourceCompiler(), new ReviewWorkspaceFactory(tmp));

        DiffParser.FileDiff changed = new DiffParser.FileDiff("UserService.java", List.of(
                new DiffParser.AddedLine(5, "return user.getProfile().getDisplayName().trim();")
        ));
        Path sourceDir = Files.createDirectory(tmp.resolve("src"));
        Files.writeString(sourceDir.resolve("UserService.java"), "public class UserService {}");

        SpotBugsResult result = analyzer.analyzeWithSource(List.of(changed), sourceDir);

        assertThat(result.ran()).isTrue();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0).file()).isEqualTo("UserService.java");
        assertThat(result.violations().get(0).line()).isEqualTo(5);
        assertThat(result.violations().get(0).rule()).isEqualTo("NP_NULL_ON_SOME_PATH");
        assertThat(result.violations().get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(Files.list(tmp)
                .noneMatch(path -> path.getFileName().toString().startsWith(ReviewWorkspace.PREFIX)))
                .isTrue();
    }

    @Test
    void skipsWhenSourcesDoNotCompile() throws Exception {
        Path sourceDir = Files.createDirectory(tmp.resolve("bad"));
        Files.writeString(sourceDir.resolve("X.java"),
                "public class X { com.example.Missing m; }");

        SpotBugsAnalyzer analyzer = new SpotBugsAnalyzer(
                (classesDir, output) -> {
                    throw new AssertionError("should not run");
                },
                new SourceCompiler(),
                new ReviewWorkspaceFactory(tmp));

        SpotBugsResult result = analyzer.analyzeWithSource(List.of(), sourceDir);
        assertThat(result.ran()).isFalse();
        assertThat(result.violations()).isEmpty();
        assertThat(result.safeReason()).isEqualTo("compiler failed");
        assertThat(Files.list(tmp)
                .noneMatch(path -> path.getFileName().toString().startsWith(ReviewWorkspace.PREFIX)))
                .isTrue();
    }

    @Test
    void skipsWhenAnalyzeIsCalledWithoutSource() {
        SpotBugsAnalyzer analyzer = new SpotBugsAnalyzer(
                (classesDir, output) -> {
                    throw new AssertionError("should not run");
                },
                new SourceCompiler(),
                new ReviewWorkspaceFactory(tmp));

        assertThat(analyzer.analyze(List.of())).isEmpty();
    }

    @Test
    void timeoutIsASafeSkippedOutcomeAndArtifactsAreCleaned() throws Exception {
        Path sourceDir = Files.createDirectory(tmp.resolve("slow"));
        Files.writeString(sourceDir.resolve("Slow.java"), "class Slow {}");
        SpotBugsAnalyzer analyzer = new SpotBugsAnalyzer(
                (classesDir, output) -> SpotBugsAnalyzer.RunOutcome.TIMED_OUT,
                new SourceCompiler(),
                new ReviewWorkspaceFactory(tmp));

        SpotBugsResult result = analyzer.analyzeWithSource(List.of(), sourceDir);

        assertThat(result.ran()).isFalse();
        assertThat(result.safeReason()).isEqualTo("analyzer timed out");
        assertThat(Files.list(tmp)
                .noneMatch(path -> path.getFileName().toString().startsWith(ReviewWorkspace.PREFIX)))
                .isTrue();
    }

    @Test
    void analyzerFailureIsSafeAndCleansClassesAndReportArtifacts() throws Exception {
        Path sourceDir = Files.createDirectory(tmp.resolve("analyzer-failure"));
        Files.writeString(sourceDir.resolve("Failure.java"), "class Failure {}");
        SpotBugsAnalyzer analyzer = new SpotBugsAnalyzer(
                (classesDir, output) -> { throw new IOException("repository-secret"); },
                new SourceCompiler(),
                new ReviewWorkspaceFactory(tmp));

        SpotBugsResult result = analyzer.analyzeWithSource(List.of(), sourceDir);

        assertThat(result.ran()).isFalse();
        assertThat(result.safeReason()).isEqualTo("analyzer unavailable");
        assertThat(Files.list(tmp)
                .noneMatch(path -> path.getFileName().toString().startsWith(ReviewWorkspace.PREFIX)))
                .isTrue();
    }
}
