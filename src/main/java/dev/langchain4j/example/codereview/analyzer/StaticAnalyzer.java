package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;

import java.util.List;

public interface StaticAnalyzer {
    String name();
    List<Violation> analyze(List<DiffParser.FileDiff> files);
}
