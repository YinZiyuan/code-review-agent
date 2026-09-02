package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.model.ReviewResult;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

@Component
public class PipelineCodeReviewer implements CodeReviewAgent {

    private final DiffAnalyzer diffAnalyzer;
    private final ToolFindingsProducer toolFindingsProducer;
    private final LlmReviewer llmReviewer;
    private final Summarizer summarizer;
    private final ReviewWorkBudget budget;
    private final PipelineStageExecutor stages;

    public PipelineCodeReviewer(DiffAnalyzer diffAnalyzer,
                                ToolFindingsProducer toolFindingsProducer,
                                LlmReviewer llmReviewer,
                                Summarizer summarizer,
                                ReviewWorkBudget budget,
                                PipelineStageExecutor stages) {
        this.diffAnalyzer = diffAnalyzer;
        this.toolFindingsProducer = toolFindingsProducer;
        this.llmReviewer = llmReviewer;
        this.summarizer = summarizer;
        this.budget = budget;
        this.stages = stages;
    }

    @Override
    public ReviewResult review(String request, Path sourceRoot) {
        return reviewWithTelemetry(request, sourceRoot).result();
    }

    @Override
    public ReviewExecution reviewWithTelemetry(String request, Path sourceRoot) {
        long reviewStarted = System.nanoTime();
        String diff = extractDiff(request);
        ReviewContext ctx = stages.run(
                PipelineStageExecutor.Stage.DIFF_ANALYSIS,
                remaining(PipelineStageExecutor.Stage.DIFF_ANALYSIS,
                        budget.stages().diffAnalysis(), reviewStarted),
                () -> diffAnalyzer.analyze(diff, sourceRoot));
        ToolFindings tools = stages.run(
                PipelineStageExecutor.Stage.TOOL_ANALYSIS,
                remaining(PipelineStageExecutor.Stage.TOOL_ANALYSIS,
                        budget.stages().toolAnalysis(), reviewStarted),
                () -> toolFindingsProducer.produce(ctx));
        LlmReviewer.Draft draft = stages.run(
                PipelineStageExecutor.Stage.REVIEW_MODEL,
                remaining(PipelineStageExecutor.Stage.REVIEW_MODEL,
                        budget.stages().reviewModel(), reviewStarted),
                () -> llmReviewer.review(ctx, tools));
        try {
            ReviewResult result = stages.run(
                    PipelineStageExecutor.Stage.SUMMARIZATION,
                    remaining(PipelineStageExecutor.Stage.SUMMARIZATION,
                            budget.stages().summarization(), reviewStarted),
                    () -> summarizer.summarize(
                            draft.result(), tools, draft.citationCandidates()));
            return new ReviewExecution(result, draft.inputTokens(), draft.outputTokens());
        } catch (RuntimeException failure) {
            throw new ReviewExecutionException(
                    failure, draft.inputTokens(), draft.outputTokens());
        }
    }

    private Duration remaining(
            PipelineStageExecutor.Stage stage, Duration stageLimit, long reviewStarted) {
        long elapsed = Math.max(0, System.nanoTime() - reviewStarted);
        long overallRemaining = budget.execution().reviewerTimeout().toNanos() - elapsed;
        if (overallRemaining <= 0) {
            throw new ReviewStageTimeoutException(stage.metricValue());
        }
        return Duration.ofNanos(Math.min(stageLimit.toNanos(), overallRemaining));
    }

    private String extractDiff(String request) {
        int marker = request == null ? -1 : request.indexOf("diff --git");
        return marker < 0 ? request : request.substring(marker);
    }
}
