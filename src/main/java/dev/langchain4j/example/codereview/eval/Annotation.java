package dev.langchain4j.example.codereview.eval;

import java.util.List;

public record Annotation(
        List<ExpectedIssue> expectedIssues,
        List<SuppressedPattern> shouldNotReport,
        String notes
) { }
