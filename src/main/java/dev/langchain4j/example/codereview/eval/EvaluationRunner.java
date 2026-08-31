package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.model.ToolRunState;
import dev.langchain4j.example.codereview.model.ToolStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final CodeReviewAgent agent;
    private final Matcher matcher;
    private final ObjectMapper mapper;

    public EvaluationRunner(CodeReviewAgent agent, Matcher matcher, ObjectMapper mapper) {
        this.agent = agent;
        this.matcher = matcher;
        this.mapper = mapper;
    }

    public EvalReport run(Path samplesDir, Path reportsDir, String version, Map<String, Object> config) throws IOException {
        return run(samplesDir, reportsDir, version, config, null, 1);
    }

    public EvalReport run(Path samplesDir, Path reportsDir, String version,
                          Map<String, Object> config, Set<String> sampleIdFilter) throws IOException {
        return run(samplesDir, reportsDir, version, config, sampleIdFilter, 1);
    }

    public EvalReport run(Path samplesDir, Path reportsDir, String version,
                          Map<String, Object> config, Set<String> sampleIdFilter, int runs) throws IOException {
        int runCount = Math.max(1, runs);
        List<Path> sampleDirs = listSampleDirs(samplesDir);
        if (sampleIdFilter != null && !sampleIdFilter.isEmpty()) {
            sampleDirs = sampleDirs.stream()
                    .filter(path -> sampleIdFilter.contains(path.getFileName().toString()))
                    .toList();
        }
        List<SampleMetrics> flattened = new ArrayList<>();
        List<Map<String, Double>> perRunMetrics = new ArrayList<>();

        for (int r = 0; r < runCount; r++) {
            List<SampleMetrics> thisRun = new ArrayList<>();
            for (Path dir : sampleDirs) {
                Sample sample = Sample.load(dir, mapper);
                log.info("Evaluating sample {} (run {}/{})", sample.id(), r + 1, runCount);
                SampleMetrics m = evaluateOne(sample);
                thisRun.add(m);
                flattened.add(runCount > 1 ? withRunSuffix(m, r + 1) : m);
            }
            perRunMetrics.add(aggregate(thisRun));
        }

        Map<String, Double> meanMetrics = meanAcrossRuns(perRunMetrics);
        Map<String, Double> stdDevMetrics = stdDevAcrossRuns(perRunMetrics, meanMetrics);

        EvalReport report = new EvalReport(
                version,
                currentCommit(),
                currentTag(),
                Instant.now().toString(),
                config,
                List.of("diff.patch", "source-before/"),
                meanMetrics,
                flattened,
                perRunMetrics,
                stdDevMetrics
        );

        Files.createDirectories(reportsDir);
        ObjectMapper reportMapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(reportsDir.resolve(version + ".json"),
                reportMapper.writeValueAsString(report),
                StandardCharsets.UTF_8);
        return report;
    }

    private static Map<String, Double> aggregate(List<SampleMetrics> perSample) {
        Map<String, Double> agg = new LinkedHashMap<>();
        agg.put("recall", Metrics.recall(perSample));
        agg.put("precision", Metrics.precision(perSample));
        agg.put("fp_rate", Metrics.fpRate(perSample));
        agg.put("severity_accuracy", Metrics.severityAccuracy(perSample));
        agg.put("avg_latency_ms", Metrics.avgLatencyMs(perSample));
        agg.put("avg_input_tokens", Metrics.avgInputTokens(perSample));
        agg.put("avg_output_tokens", Metrics.avgOutputTokens(perSample));
        agg.put("tool_success_rate", Metrics.toolSuccessRate(perSample));
        return agg;
    }

    private static Map<String, Double> meanAcrossRuns(List<Map<String, Double>> runs) {
        Map<String, Double> mean = new LinkedHashMap<>();
        if (runs.isEmpty()) {
            return mean;
        }
        for (String key : runs.get(0).keySet()) {
            double sum = runs.stream().mapToDouble(m -> m.getOrDefault(key, 0.0)).sum();
            mean.put(key, sum / runs.size());
        }
        return mean;
    }

    private static Map<String, Double> stdDevAcrossRuns(List<Map<String, Double>> runs, Map<String, Double> mean) {
        Map<String, Double> sd = new LinkedHashMap<>();
        for (String key : mean.keySet()) {
            double mu = mean.get(key);
            double var = runs.stream()
                    .mapToDouble(m -> {
                        double d = m.getOrDefault(key, 0.0) - mu;
                        return d * d;
                    })
                    .average().orElse(0.0);
            sd.put(key, Math.sqrt(var));
        }
        return sd;
    }

    private static SampleMetrics withRunSuffix(SampleMetrics m, int run) {
        return new SampleMetrics(m.sampleId() + "#run" + run, m.truePositives(), m.falsePositives(),
                m.falseNegatives(), m.severityMatches(), m.severityComparisons(), m.latencyMs(),
                m.inputTokens(), m.outputTokens(), m.toolCallsTotal(), m.toolCallsFailed(), m.toolStatuses());
    }

    private SampleMetrics evaluateOne(Sample sample) {
        long start = System.currentTimeMillis();
        ReviewResult result;
        try {
            String request = "Review the following diff. The full diff is below; do not call git tools.\n\n"
                    + sample.diffPatch();
            result = callAgentWithRetry(request, sample.sourceBeforeDir(), sample.id());
        } catch (Exception e) {
            log.warn("Sample {} review failed: {}", sample.id(), e.toString());
            result = ReviewResult.empty("review error: " + e.getMessage());
        }
        long latency = System.currentTimeMillis() - start;

        List<ReviewFinding> findings = result.findings() == null ? List.of() : result.findings();
        List<MatchResult> matches = matcher.match(sample.annotation().expectedIssues(), findings);

        int tp = (int) matches.stream().filter(MatchResult::matched).count();
        int fn = matches.size() - tp;
        int fp = Math.max(0, findings.size() - tp);
        int severityMatches = 0;
        int severityComparisons = 0;
        for (MatchResult match : matches) {
            if (match.matched()) {
                severityComparisons++;
                if (match.expected().severity() == match.agentFinding().severity()) {
                    severityMatches++;
                }
            }
        }

        List<ToolStatus> statuses = result.toolStatus() == null ? List.of() : result.toolStatus();
        int toolTotal = (int) statuses.stream()
                .filter(s -> s.state() != ToolRunState.SKIPPED_EXPECTED)
                .count();
        int toolFailed = (int) statuses.stream()
                .filter(s -> s.state() == ToolRunState.FAILED)
                .count();

        return new SampleMetrics(sample.id(), tp, fp, fn, severityMatches, severityComparisons,
                latency, 0L, 0L, toolTotal, toolFailed, statuses);
    }

    private ReviewResult callAgentWithRetry(String request, Path sourceRoot, String sampleId) {
        try {
            return agent.review(request, sourceRoot);
        } catch (RuntimeException e) {
            if (!isRetryable(e)) {
                throw e;
            }
            log.warn("Sample {} review timed out; retrying once: {}", sampleId, e.toString());
            return agent.review(request, sourceRoot);
        }
    }

    private static boolean isRetryable(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String name = cur.getClass().getName();
            if (name.equals("java.net.http.HttpTimeoutException")
                    || name.equals("java.net.SocketTimeoutException")
                    || name.equals("org.springframework.web.client.ResourceAccessException")) {
                return true;
            }
        }
        return false;
    }

    private List<Path> listSampleDirs(Path samplesDir) throws IOException {
        if (!Files.isDirectory(samplesDir)) {
            throw new IOException("Samples directory not found: " + samplesDir);
        }
        try (var stream = Files.list(samplesDir)) {
            return stream.filter(Files::isDirectory).sorted().toList();
        }
    }

    private String currentCommit() {
        return runGit("rev-parse", "HEAD");
    }

    private String currentTag() {
        String tag = runGit("describe", "--tags", "--exact-match");
        return tag == null || tag.contains("fatal") ? null : tag;
    }

    private String runGit(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.waitFor();
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
