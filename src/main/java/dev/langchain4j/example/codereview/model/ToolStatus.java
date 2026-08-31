package dev.langchain4j.example.codereview.model;

public record ToolStatus(String tool, ToolRunState state, String reason) { }
