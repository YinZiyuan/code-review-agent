package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.model.Severity;

public record Violation(
        Severity severity,
        String file,
        int line,
        String rule,
        String message
) { }
