package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.ReviewFinding;

import java.util.List;

public record SampleTrace(
        String sampleId,
        List<ReviewFinding> findings,
        List<MatchResult> matches,
        List<ReviewFinding> unmatchedFindings
) { }
