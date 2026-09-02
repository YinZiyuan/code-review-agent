package dev.langchain4j.example.codereview.agents.pipeline;

public interface PromptTokenizer {

    int count(String text);

    String truncate(String text, int maxTokens);
}
