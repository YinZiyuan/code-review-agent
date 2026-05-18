package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.Severity;

import java.util.List;

public record ExpectedIssue(
        String id,
        String file,
        int line,
        int[] lineRange,
        Category category,
        String subcategory,
        Severity severity,
        String description,
        boolean mustDetect,
        List<String> alternativeDescriptions
) { }
