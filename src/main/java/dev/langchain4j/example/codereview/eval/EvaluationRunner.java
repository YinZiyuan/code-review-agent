package dev.langchain4j.example.codereview.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
        return run(samplesDir, reportsDir, version, config, null);
    }

    public EvalReport run(Path samplesDir, Path reportsDir, String version,
                          Map<String, Object> config, Set<String> sampleIdFilter) throws IOException {
        List<Path> sampleDirs = listSampleDirs(samplesDir);
        if (sampleIdFilter != null && !sampleIdFilter.isEmpty()) {
            sampleDirs = sampleDirs.stream()
                    .filter(path -> sampleIdFilter.contains(path.getFileName().toString()))
                    .toList();
        }
        List<SampleMetrics> perSample = new ArrayList<>();

        for (Path dir : sampleDirs) {
            Sample sample = Sample.load(dir, mapper);
            log.info("Evaluating sample {}", sample.id());
            perSample.add(evaluateOne(sample));
        }

        Map<String, Double> aggregate = Map.of(
                "recall", Metrics.recall(perSample),
                "precision", Metrics.precision(perSample),
                "fp_rate", Metrics.fpRate(perSample),
                "severity_accuracy", Metrics.severityAccuracy(perSample),
                "avg_latency_ms", Metrics.avgLatencyMs(perSample),
                "avg_input_tokens", Metrics.avgInputTokens(perSample),
                "avg_output_tokens", Metrics.avgOutputTokens(perSample),
                "tool_success_rate", Metrics.toolSuccessRate(perSample)
        );

        EvalReport report = new EvalReport(
                version,
                currentCommit(),
                currentTag(),
                Instant.now().toString(),
                config,
                List.of("diff.patch", "source-before/"),
                aggregate,
                perSample
        );

        Files.createDirectories(reportsDir);
        ObjectMapper reportMapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(reportsDir.resolve(version + ".json"),
                reportMapper.writeValueAsString(report),
                StandardCharsets.UTF_8);
        return report;
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

        return new SampleMetrics(sample.id(), tp, fp, fn, severityMatches, severityComparisons,
                latency, 0L, 0L,
                result.toolStatus() == null ? 0 : result.toolStatus().size(),
                result.toolStatus() == null ? 0 : (int) result.toolStatus().stream()
                        .filter(status -> !"ok".equalsIgnoreCase(status.status()))
                        .count(),
                result.toolStatus() == null ? List.of() : result.toolStatus());
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
