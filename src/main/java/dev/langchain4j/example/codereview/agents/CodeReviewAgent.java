package dev.langchain4j.example.codereview.agents;

import dev.langchain4j.example.codereview.model.ReviewResult;

import java.nio.file.Path;
import java.nio.file.Paths;

public interface CodeReviewAgent {

    default ReviewResult review(String request) {
        return review(request, Paths.get("").toAbsolutePath());
    }

    ReviewResult review(String request, Path sourceRoot);
}
