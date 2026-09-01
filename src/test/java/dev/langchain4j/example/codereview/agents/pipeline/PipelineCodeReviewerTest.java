package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.model.ReviewResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineCodeReviewerTest {

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
                analyzer, toolFindingsProducer, llmReviewer, summarizer)
                .reviewWithTelemetry("diff", context.sourceRoot());

        assertThat(execution.result()).isSameAs(summarized);
        assertThat(execution.inputTokens()).isEqualTo(125);
        assertThat(execution.outputTokens()).isEqualTo(16);
    }
}
