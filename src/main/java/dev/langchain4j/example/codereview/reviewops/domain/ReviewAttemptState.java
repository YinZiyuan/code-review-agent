package dev.langchain4j.example.codereview.reviewops.domain;

public enum ReviewAttemptState {
    STARTED, SUCCEEDED, TRANSIENT_FAILURE, TERMINAL_FAILURE
}
