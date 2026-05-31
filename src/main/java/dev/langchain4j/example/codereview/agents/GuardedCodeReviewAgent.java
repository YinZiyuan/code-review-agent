package dev.langchain4j.example.codereview.agents;

import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.rag.CitationKeywordInjector;
import dev.langchain4j.example.codereview.rag.CitationTracker;
import dev.langchain4j.example.codereview.rag.RetrievalRecorder;
import dev.langchain4j.service.output.OutputParsingException;

import java.util.List;

public class GuardedCodeReviewAgent implements CodeReviewAgent {

    private final CodeReviewAgent inner;
    private final JsonRepair jsonRepair;
    private final RetrievalRecorder recorder;
    private final CitationTracker tracker;
    private final CitationKeywordInjector injector;

    public GuardedCodeReviewAgent(CodeReviewAgent inner,
                                  JsonRepair jsonRepair,
                                  RetrievalRecorder recorder,
                                  CitationTracker tracker,
                                  CitationKeywordInjector injector) {
        this.inner = inner;
        this.jsonRepair = jsonRepair;
        this.recorder = recorder;
        this.tracker = tracker;
        this.injector = injector;
    }

    @Override
    public ReviewResult review(String request) {
        try {
            ReviewResult result;
            try {
                result = inner.review(request);
            } catch (OutputParsingException e) {
                result = jsonRepair.parseOrRepair(e.getMessage(), ReviewResult.class);
            }

            List<Citation> candidates = tracker.toCitations(recorder.snapshot());
            List<ReviewFinding> updated = injector.inject(result.findings(), candidates);
            return new ReviewResult(result.summary(), updated, result.toolStatus());
        } finally {
            recorder.clear();
        }
    }
}
