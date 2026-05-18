package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.config.CodeReviewProperties;
import dev.langchain4j.example.codereview.eval.EvalReport;
import dev.langchain4j.example.codereview.eval.EvaluationRunner;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Component
@Command(name = "eval", description = "Run evaluation suite against PR samples")
public class EvalCommand implements Callable<Integer> {

    @Option(names = "--version", required = true, description = "Version label, e.g. v0-baseline")
    private String version;

    @Option(names = "--samples", description = "Override samples directory")
    private Path samplesOverride;

    @Option(names = "--report-dir", description = "Override reports output directory")
    private Path reportDirOverride;

    private final EvaluationRunner runner;
    private final CodeReviewProperties props;

    public EvalCommand(EvaluationRunner runner, CodeReviewProperties props) {
        this.runner = runner;
        this.props = props;
    }

    @Override
    public Integer call() throws Exception {
        Path samples = samplesOverride != null ? samplesOverride : props.eval().samplesDir();
        Path reports = reportDirOverride != null ? reportDirOverride : props.eval().reportDir();
        Map<String, Object> config = Map.of(
                "judge_model", props.eval().judgeModel(),
                "runs_per_sample", props.eval().runsPerSample(),
                "pipeline", "w1-single-agent"
        );

        EvalReport report = runner.run(samples, reports, version, config);
        System.out.printf("recall=%.2f precision=%.2f fp_rate=%.2f%n",
                report.metrics().get("recall"),
                report.metrics().get("precision"),
                report.metrics().get("fp_rate"));
        return 0;
    }
}
