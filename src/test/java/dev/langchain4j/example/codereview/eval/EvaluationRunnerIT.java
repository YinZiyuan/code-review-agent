package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvaluationRunnerIT {

    @TempDir Path workDir;

    @Test
    void runsTwoFixtureSamplesAndProducesReport() throws Exception {
        Path samples = workDir.resolve("samples");
        Path reports = workDir.resolve("reports");
        Files.createDirectories(samples);
        copyFixture("sample-pass", samples);
        copyFixture("sample-fail", samples);

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        CodeReviewAgent agent = (request, sourceRoot) -> new ReviewResult(
                "1 finding",
                List.of(new ReviewFinding(
                        "F-001", "User.java", 11, new int[]{11, 11},
                        Severity.CRITICAL, Category.SECURITY,
                        "Hardcoded credential",
                        "Found hardcoded password",
                        "Move to environment variable",
                        "pwd = \"hardcoded\"",
                        List.of(),
                        "llm_reviewer")),
                List.of()
        );
        Matcher matcher = new Matcher((expected, finding) ->
                new LlmJudge.JudgeVerdict(
                        expected.file().equals(finding.file()) && expected.category() == finding.category(),
                        0.9,
                        "same"), 5);
        EvaluationRunner runner = new EvaluationRunner(agent, matcher, mapper);

        EvalReport report = runner.run(samples, reports, "v0-test", Map.of("pipeline", "test"));

        assertThat(report.perSample()).hasSize(2);
        SampleMetrics passMetrics = report.perSample().stream()
                .filter(s -> s.sampleId().equals("sample-pass"))
                .findFirst()
                .orElseThrow();
        assertThat(passMetrics.truePositives()).isEqualTo(1);

        SampleMetrics failMetrics = report.perSample().stream()
                .filter(s -> s.sampleId().equals("sample-fail"))
                .findFirst()
                .orElseThrow();
        assertThat(failMetrics.falseNegatives()).isEqualTo(1);
        assertThat(Files.exists(reports.resolve("v0-test.json"))).isTrue();
        assertThat(report.allowedInputs()).contains("diff.patch", "source-before/");
    }

    @Test
    void repeatedRunsAggregatesAcrossRuns() throws Exception {
        Path samples = workDir.resolve("samples-rep");
        Path reports = workDir.resolve("reports-rep");
        Files.createDirectories(samples);
        copyFixture("sample-pass", samples);

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        CodeReviewAgent agent = (request, sourceRoot) -> new ReviewResult(
                "1 finding",
                List.of(new ReviewFinding(
                        "F-001", "User.java", 11, new int[]{11, 11},
                        Severity.CRITICAL, Category.SECURITY,
                        "Hardcoded credential", "Found hardcoded password",
                        "Move to environment variable", "pwd = \"hardcoded\"",
                        List.of(), "llm_reviewer")),
                List.of());
        Matcher matcher = new Matcher((expected, finding) ->
                new LlmJudge.JudgeVerdict(
                        expected.file().equals(finding.file()) && expected.category() == finding.category(),
                        0.9, "same"), 5);
        EvaluationRunner runner = new EvaluationRunner(agent, matcher, mapper);

        EvalReport report = runner.run(samples, reports, "v-rep", Map.of("pipeline", "test"), null, 3);

        assertThat(report.perRunMetrics()).hasSize(3);
        assertThat(report.perSample()).hasSize(3);
        assertThat(report.perSample()).allSatisfy(s ->
                assertThat(s.sampleId()).startsWith("sample-pass#run"));
        assertThat(report.metricsStdDev().get("recall")).isCloseTo(0.0, within(0.0001));
        assertThat(report.metrics().get("recall")).isCloseTo(1.0, within(0.0001));
    }

    @Test
    void singleRunKeepsLegacyBehaviour() throws Exception {
        Path samples = workDir.resolve("samples-one");
        Path reports = workDir.resolve("reports-one");
        Files.createDirectories(samples);
        copyFixture("sample-pass", samples);

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        CodeReviewAgent agent = (request, sourceRoot) -> new ReviewResult("none", List.of(), List.of());
        Matcher matcher = new Matcher((expected, finding) ->
                new LlmJudge.JudgeVerdict(false, 0.0, "no"), 5);
        EvaluationRunner runner = new EvaluationRunner(agent, matcher, mapper);

        EvalReport report = runner.run(samples, reports, "v-one", Map.of("pipeline", "test"));

        assertThat(report.perRunMetrics()).hasSize(1);
        assertThat(report.perSample()).hasSize(1);
        assertThat(report.perSample().get(0).sampleId()).isEqualTo("sample-pass");
        assertThat(report.metricsStdDev().values()).allSatisfy(v -> assertThat(v).isEqualTo(0.0));
    }

    private void copyFixture(String name, Path samplesRoot) throws Exception {
        Path target = samplesRoot.resolve(name);
        Files.createDirectories(target.resolve("source-before"));
        copy("eval-fixtures/" + name + "/diff.patch", target.resolve("diff.patch"));
        copy("eval-fixtures/" + name + "/annotation.json", target.resolve("annotation.json"));
    }

    private void copy(String classpath, Path dst) throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) throw new IllegalStateException("fixture missing: " + classpath);
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
