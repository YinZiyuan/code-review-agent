package dev.langchain4j.example.codereview.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewFinding(
        String id,
        String file,
        Integer line,
        int[] lineRange,
        Severity severity,
        Category category,
        String title,
        String description,
        String suggestion,
        String evidence,
        List<Citation> citations,
        String source
) { }
