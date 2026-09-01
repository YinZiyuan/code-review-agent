package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class PipelineCodeReviewer implements CodeReviewAgent {

    private final DiffAnalyzer diffAnalyzer;
    private final ToolFindingsProducer toolFindingsProducer;
    private final LlmReviewer llmReviewer;
    private final Summarizer summarizer;

    public PipelineCodeReviewer(DiffAnalyzer diffAnalyzer,
                                ToolFindingsProducer toolFindingsProducer,
                                LlmReviewer llmReviewer,
                                Summarizer summarizer) {
        this.diffAnalyzer = diffAnalyzer;
        this.toolFindingsProducer = toolFindingsProducer;
        this.llmReviewer = llmReviewer;
        this.summarizer = summarizer;
    }

    @Override
    public ReviewResult review(String request, Path sourceRoot) {
        return reviewWithTelemetry(request, sourceRoot).result();
    }

    @Override
    public ReviewExecution reviewWithTelemetry(String request, Path sourceRoot) {
        String diff = extractDiff(request);
        ReviewContext ctx = diffAnalyzer.analyze(diff, sourceRoot);
        ToolFindings tools = toolFindingsProducer.produce(ctx);
        LlmReviewer.Draft draft = llmReviewer.review(ctx, tools);
        try {
            ReviewResult result = summarizer.summarize(
                    draft.result(), tools, draft.citationCandidates());
            return new ReviewExecution(result, draft.inputTokens(), draft.outputTokens());
        } catch (RuntimeException failure) {
            throw new ReviewExecutionException(
                    failure, draft.inputTokens(), draft.outputTokens());
        }
    }

    private String extractDiff(String request) {
        int marker = request == null ? -1 : request.indexOf("diff --git");
        return marker < 0 ? request : request.substring(marker);
    }
}
