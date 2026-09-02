package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import dev.langchain4j.example.codereview.model.ReviewResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PipelineCodeReviewerTest {

    private final ReviewWorkBudget defaults = new ReviewWorkBudgetProperties(
            null, null, null, null, null, null).toBudget();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final PipelineStageExecutor stages = new PipelineStageExecutor(metrics);

    @AfterEach
    void closeStages() {
        stages.close();
    }

    @Test
    void exposesTheBoundedModelResponsesActualTokenUsage() {
        DiffAnalyzer analyzer = mock(DiffAnalyzer.class);
        ToolFindingsProducer toolFindingsProducer = mock(ToolFindingsProducer.class);
        LlmReviewer llmReviewer = mock(LlmReviewer.class);
        Summarizer summarizer = mock(Summarizer.class);
        ReviewContext context = new ReviewContext(
                "diff", List.of(), Map.of(), Path.of("/tmp/exact-sha"));
        ToolFindings tools = new ToolFindings(List.of(), List.of());
        ReviewResult draft = ReviewResult.empty("draft");
        ReviewResult summarized = ReviewResult.empty("summarized");
        when(analyzer.analyze("diff", context.sourceRoot())).thenReturn(context);
        when(toolFindingsProducer.produce(context)).thenReturn(tools);
        when(llmReviewer.review(context, tools))
                .thenReturn(new LlmReviewer.Draft(draft, List.of(), 125, 16));
        when(summarizer.summarize(draft, tools, List.of())).thenReturn(summarized);

        var execution = new PipelineCodeReviewer(
                analyzer, toolFindingsProducer, llmReviewer, summarizer, defaults, stages)
                .reviewWithTelemetry("diff", context.sourceRoot());

        assertThat(execution.result()).isSameAs(summarized);
        assertThat(execution.inputTokens()).isEqualTo(125);
        assertThat(execution.outputTokens()).isEqualTo(16);
    }

    @Test
    void measuredDraftSummarizerFailureCarriesActualTokenUsage() {
        DiffAnalyzer analyzer = mock(DiffAnalyzer.class);
        ToolFindingsProducer toolFindingsProducer = mock(ToolFindingsProducer.class);
        LlmReviewer llmReviewer = mock(LlmReviewer.class);
        Summarizer summarizer = mock(Summarizer.class);
        ReviewContext context = new ReviewContext(
                "diff", List.of(), Map.of(), Path.of("/tmp/exact-sha"));
        ToolFindings tools = new ToolFindings(List.of(), List.of());
        ReviewResult draft = ReviewResult.empty("draft");
        IllegalArgumentException summarizerFailure =
                new IllegalArgumentException("invalid summarized output");
        when(analyzer.analyze("diff", context.sourceRoot())).thenReturn(context);
        when(toolFindingsProducer.produce(context)).thenReturn(tools);
        when(llmReviewer.review(context, tools))
                .thenReturn(new LlmReviewer.Draft(draft, List.of(), 125, 16));
        when(summarizer.summarize(draft, tools, List.of())).thenThrow(summarizerFailure);
        PipelineCodeReviewer reviewer = new PipelineCodeReviewer(
                analyzer, toolFindingsProducer, llmReviewer, summarizer, defaults, stages);

        var failure = catchThrowableOfType(
                dev.langchain4j.example.codereview.agents.CodeReviewAgent.ReviewExecutionException.class,
                () -> reviewer.reviewWithTelemetry("diff", context.sourceRoot()));

        assertThat(failure.getCause()).isSameAs(summarizerFailure);
        assertThat(failure.inputTokens()).isEqualTo(125);
        assertThat(failure.outputTokens()).isEqualTo(16);
    }

    @Test
    void diffStageDeadlineStopsThePipelineWithASafeTimeout() {
        DiffAnalyzer analyzer = mock(DiffAnalyzer.class);
        ToolFindingsProducer tools = mock(ToolFindingsProducer.class);
        LlmReviewer llm = mock(LlmReviewer.class);
        Summarizer summarizer = mock(Summarizer.class);
        when(analyzer.analyze("diff", Path.of("/tmp/exact-sha"))).thenAnswer(ignored -> {
            Thread.sleep(5_000);
            return null;
        });
        ReviewWorkBudget.StageDeadlines base = defaults.stages();
        ReviewWorkBudget budget = new ReviewWorkBudget(
                defaults.version(), defaults.input(), defaults.prompt(), defaults.process(),
                new ReviewWorkBudget.StageDeadlines(
                        Duration.ofMillis(30), base.toolAnalysis(), base.reviewModel(),
                        base.summarization(), base.compiler(), base.spotbugs()),
                defaults.workspace());
        PipelineCodeReviewer reviewer = new PipelineCodeReviewer(
                analyzer, tools, llm, summarizer, budget, stages);

        assertThat(catchThrowableOfType(
                ReviewStageTimeoutException.class,
                () -> reviewer.reviewWithTelemetry("diff", Path.of("/tmp/exact-sha"))))
                .hasMessage("review stage timed out: diff_analysis");
        verifyNoInteractions(tools, llm, summarizer);
        assertThat(metrics.get("code.review.pipeline.stage.duration")
                .tag("stage", "diff_analysis")
                .tag("outcome", "timeout")
                .timer().count()).isEqualTo(1);
    }
}
