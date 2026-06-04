package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.config.CodeReviewProperties;
import dev.langchain4j.example.codereview.eval.EvalReport;
import dev.langchain4j.example.codereview.eval.EvaluationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Component
@Command(name = "eval",
        description = "Run evaluation suite against PR samples",
        sortOptions = false)
public class EvalCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(EvalCommand.class);

    @Option(names = "--version", required = true, description = "Version label, e.g. v0-baseline")
    private String version;

    @Option(names = "--samples-dir", description = "Override samples directory")
    private Path samplesOverride;

    @Option(names = "--report-dir", description = "Override reports output directory")
    private Path reportDirOverride;

    @Option(names = "--pipeline", description = "Pipeline label recorded in report config", defaultValue = "w1-single-agent")
    private String pipeline;

    @Option(names = "--samples",
            description = "Comma-separated sample IDs to include, e.g. reverse-001,reverse-002. Default: all samples.")
    private String samplesCsv;

    @Option(names = "--suite",
            description = "smoke | dev | release. smoke: first 2 samples; dev/release: all samples. Default: dev.",
            defaultValue = "dev")
    private String suite;

    @Option(names = "--runs",
            description = "Repeat each sample N times for variance. Default: 1.",
            defaultValue = "1")
    private int runs;

    private final EvaluationRunner runner;
    private final CodeReviewProperties props;

    public EvalCommand(EvaluationRunner runner, CodeReviewProperties props) {
        this.runner = runner;
        this.props = props;
    }

    @Override
    public Integer call() throws Exception {
        if (System.getProperty("debug") != null) {
            log.warn("System property 'debug' detected (value={}); clearing to avoid Spring debug-mode log spam.",
                    System.getProperty("debug"));
            System.clearProperty("debug");
        }

        Path samples = samplesOverride != null ? samplesOverride : props.eval().samplesDir();
        Path reports = reportDirOverride != null ? reportDirOverride : props.eval().reportDir();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("judge_model", props.eval().judgeModel());
        config.put("runs_per_sample", runs);
        config.put("pipeline", pipeline);
        config.put("suite", suite);

        Set<String> filter = parseFilter(samplesCsv, suite, samples);
        EvalReport report = runner.run(samples, reports, version, config, filter, runs);
        System.out.printf("recall=%.2f precision=%.2f fp_rate=%.2f%n",
                report.metrics().get("recall"),
                report.metrics().get("precision"),
                report.metrics().get("fp_rate"));
        return 0;
    }

    private Set<String> parseFilter(String csv, String suite, Path samplesDir) {
        if (csv != null && !csv.isBlank()) {
            return Arrays.stream(csv.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        }
        if ("smoke".equalsIgnoreCase(suite)) {
            try (var stream = Files.list(samplesDir)) {
                return stream.filter(Files::isDirectory)
                        .sorted()
                        .limit(2)
                        .map(path -> path.getFileName().toString())
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            } catch (IOException e) {
                log.warn("Could not list smoke suite samples from {}: {}", samplesDir, e.toString());
                return Set.of();
            }
        }
        return Set.of();
    }
}
