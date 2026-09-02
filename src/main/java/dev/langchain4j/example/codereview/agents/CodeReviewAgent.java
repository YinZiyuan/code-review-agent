package dev.langchain4j.example.codereview.agents;

import dev.langchain4j.example.codereview.model.ReviewResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public interface CodeReviewAgent {

    default ReviewResult review(String request) {
        return review(request, Paths.get("").toAbsolutePath());
    }

    ReviewResult review(String request, Path sourceRoot);

    default ReviewExecution reviewWithTelemetry(String request, Path sourceRoot) {
        throw new UnsupportedOperationException("reviewer does not expose model token usage");
    }

    record ReviewExecution(ReviewResult result, int inputTokens, int outputTokens) {
        public ReviewExecution {
            Objects.requireNonNull(result, "result");
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("model token usage must be non-negative");
            }
        }
    }

    final class ReviewExecutionException extends RuntimeException {
        private final int inputTokens;
        private final int outputTokens;

        public ReviewExecutionException(Throwable cause, int inputTokens, int outputTokens) {
            super("review execution failed after receiving model usage", cause);
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("model token usage must be non-negative");
            }
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        public int inputTokens() {
            return inputTokens;
        }

        public int outputTokens() {
            return outputTokens;
        }
    }
}
