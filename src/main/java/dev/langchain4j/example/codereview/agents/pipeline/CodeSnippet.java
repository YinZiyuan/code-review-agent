package dev.langchain4j.example.codereview.agents.pipeline;

public record CodeSnippet(String file, int line, String text) {
}
