package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            return true;
        }, new SourceCompiler());

        DiffParser.FileDiff changed = new DiffParser.FileDiff("UserService.java", List.of(
                new DiffParser.AddedLine(5, "return user.getProfile().getDisplayName().trim();")
        ));
        Path sourceDir = Files.createDirectory(tmp.resolve("src"));
        Files.writeString(sourceDir.resolve("UserService.java"), "public class UserService {}");

        List<Violation> v = analyzer.analyzeWithSource(List.of(changed), sourceDir);

        assertThat(v).hasSize(1);
        assertThat(v.get(0).file()).isEqualTo("UserService.java");
        assertThat(v.get(0).line()).isEqualTo(5);
        assertThat(v.get(0).rule()).isEqualTo("NP_NULL_ON_SOME_PATH");
        assertThat(v.get(0).severity()).isEqualTo(Severity.CRITICAL);
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
                new SourceCompiler());

        List<Violation> v = analyzer.analyzeWithSource(List.of(), sourceDir);
        assertThat(v).isEmpty();
    }

    @Test
    void skipsWhenAnalyzeIsCalledWithoutSource() {
        SpotBugsAnalyzer analyzer = new SpotBugsAnalyzer(
                (classesDir, output) -> {
                    throw new AssertionError("should not run");
                },
                new SourceCompiler());

        assertThat(analyzer.analyze(List.of())).isEmpty();
    }
}
