package dev.langchain4j.example.codereview.agents.pipeline;

public final class ReviewStageTimeoutException extends RuntimeException {

    ReviewStageTimeoutException(String stage) {
        super("review stage timed out: " + stage);
    }
}
