package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.model.ToolRunState;
import dev.langchain4j.example.codereview.model.ToolStatus;
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
    void reportPersistsFindingsAndMatchDecisionsForEverySample() throws Exception {
        Path samples = workDir.resolve("samples-trace");
        Path reports = workDir.resolve("reports-trace");
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
                        "Hardcoded credential", "Found hardcoded password",
                        "Move to environment variable", "pwd = hardcoded",
                        List.of(), "llm_reviewer")),
                List.of());
        Matcher matcher = new Matcher((expected, finding) ->
                new LlmJudge.JudgeVerdict(
                        expected.file().equals(finding.file()) && expected.category() == finding.category(),
                        0.9, "same issue"), 5);

        new EvaluationRunner(agent, matcher, mapper)
                .run(samples, reports, "v-trace", Map.of("pipeline", "test"));

        JsonNode root = mapper.readTree(reports.resolve("v-trace.json").toFile());
        JsonNode traces = root.path("traces");
        assertThat(traces.isArray()).isTrue();
        assertThat(traces).hasSize(2);

        JsonNode pass = findTrace(traces, "sample-pass");
        assertThat(pass.path("findings")).hasSize(1);
        assertThat(pass.path("findings").get(0).path("title").asText())
                .isEqualTo("Hardcoded credential");
        assertThat(pass.path("matches")).hasSize(1);
        assertThat(pass.path("matches").get(0).path("matched").asBoolean()).isTrue();
        assertThat(pass.path("matches").get(0).path("judge_reason").asText()).isEqualTo("same issue");
        assertThat(pass.path("unmatched_findings")).isEmpty();

        JsonNode fail = findTrace(traces, "sample-fail");
        assertThat(fail.path("matches")).hasSize(1);
        assertThat(fail.path("matches").get(0).path("matched").asBoolean()).isFalse();
        assertThat(fail.path("matches").get(0).path("expected").path("id").asText()).isEqualTo("I-001");
        assertThat(fail.path("unmatched_findings")).hasSize(1);
        assertThat(fail.path("unmatched_findings").get(0).path("id").asText()).isEqualTo("F-001");
    }

    @Test
    void reportPersistsSafeRuntimeMetadataAndRedactsSensitiveConfig() throws Exception {
        Path samples = workDir.resolve("samples-metadata");
        Path reports = workDir.resolve("reports-metadata");
        Files.createDirectories(samples);
        copyFixture("sample-pass", samples);

        String secret = "test-secret-that-must-not-reach-the-report";
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        CodeReviewAgent agent = (request, sourceRoot) -> ReviewResult.empty("none");
        Matcher matcher = new Matcher((expected, finding) ->
                new LlmJudge.JudgeVerdict(false, 0.0, "no"), 5);
        ModelRuntimeMetadata metadata = new ModelRuntimeMetadata(
                "openai-compatible", "sub2api.apemind.ai",
                "gpt-5.6-luna", "gpt-5.6-luna", "gpt-5.6-luna");

        new EvaluationRunner(agent, matcher, mapper, metadata)
                .run(samples, reports, "v-metadata", Map.of(
                        "pipeline", "test",
                        "environment", Map.of("HARMLESS_NAME", secret),
                        "headers", Map.of("cookie", secret),
                        "unrecognized_config", secret,
                        "api_key", secret,
                        "authorization", "Bearer " + secret));

        JsonNode root = mapper.readTree(reports.resolve("v-metadata.json").toFile());
        assertThat(root.path("model_runtime").path("provider").asText())
                .isEqualTo("openai-compatible");
        assertThat(root.path("model_runtime").path("base_url_host").asText())
                .isEqualTo("sub2api.apemind.ai");
        assertThat(root.path("model_runtime").path("judge_model").asText())
                .isEqualTo("gpt-5.6-luna");
        assertThat(root.path("trace_policy").path("audience").asText())
                .isEqualTo("evaluator-only");
        assertThat(root.path("trace_policy").path("excluded_fields"))
                .containsExactlyInAnyOrder(
                        mapper.getNodeFactory().textNode("api_keys"),
                        mapper.getNodeFactory().textNode("authorization_headers"),
                        mapper.getNodeFactory().textNode("environment_variables"),
                        mapper.getNodeFactory().textNode("agent_prompts"));
        assertThat(root.path("trace_policy").path("persisted_fields"))
                .containsExactlyInAnyOrder(
                        mapper.getNodeFactory().textNode("traces.findings"),
                        mapper.getNodeFactory().textNode("traces.matches"),
                        mapper.getNodeFactory().textNode("traces.unmatched_findings"),
                        mapper.getNodeFactory().textNode("model_runtime"));
        assertThat(root.path("config").path("pipeline").asText()).isEqualTo("test");
        assertThat(root.path("config").path("judge_model").asText()).isEqualTo("gpt-5.6-luna");
        assertThat(root.path("config").has("api_key")).isFalse();
        assertThat(root.path("config").has("authorization")).isFalse();
        assertThat(root.path("config").has("environment")).isFalse();
        assertThat(root.path("config").has("headers")).isFalse();
        assertThat(root.path("config").has("unrecognized_config")).isFalse();
        assertThat(root.toString())
                .doesNotContain(secret)
                .doesNotContain("Bearer " + secret);
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

    @Test
    void toolCountsExcludeExpectedSkips() throws Exception {
        Path samples = workDir.resolve("samples-tool");
        Path reports = workDir.resolve("reports-tool");
        Files.createDirectories(samples);
        copyFixture("sample-pass", samples);

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        CodeReviewAgent agent = (request, sourceRoot) -> new ReviewResult(
                "n", List.of(),
                List.of(new ToolStatus("regex", ToolRunState.RAN, null),
                        new ToolStatus("spotbugs", ToolRunState.SKIPPED_EXPECTED, "not buildable"),
                        new ToolStatus("other", ToolRunState.FAILED, "boom")));
        Matcher matcher = new Matcher((e, f) -> new LlmJudge.JudgeVerdict(false, 0.0, "no"), 5);
        EvaluationRunner runner = new EvaluationRunner(agent, matcher, mapper);

        EvalReport report = runner.run(samples, reports, "v-tool", Map.of("pipeline", "test"));
        SampleMetrics m = report.perSample().get(0);
        assertThat(m.toolCallsTotal()).isEqualTo(2);
        assertThat(m.toolCallsFailed()).isEqualTo(1);
    }

    private void copyFixture(String name, Path samplesRoot) throws Exception {
        Path target = samplesRoot.resolve(name);
        Files.createDirectories(target.resolve("source-before"));
        copy("eval-fixtures/" + name + "/diff.patch", target.resolve("diff.patch"));
        copy("eval-fixtures/" + name + "/annotation.json", target.resolve("annotation.json"));
    }

    private JsonNode findTrace(JsonNode traces, String sampleId) {
        for (JsonNode trace : traces) {
            if (sampleId.equals(trace.path("sample_id").asText())) {
                return trace;
            }
        }
        throw new AssertionError("Missing trace for " + sampleId);
    }

    private void copy(String classpath, Path dst) throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(classpath)) {
            if (in == null) throw new IllegalStateException("fixture missing: " + classpath);
            Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
