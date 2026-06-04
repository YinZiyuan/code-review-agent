package dev.langchain4j.example.codereview.model;

/** Tool run state: ran, expected skip, or analyzer failure. */
public enum ToolRunState {
    RAN,
    SKIPPED_EXPECTED,
    FAILED
}
