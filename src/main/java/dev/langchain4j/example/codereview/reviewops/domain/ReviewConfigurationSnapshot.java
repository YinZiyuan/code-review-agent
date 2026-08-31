package dev.langchain4j.example.codereview.reviewops.domain;

public record ReviewConfigurationSnapshot(
        String pipelineVersion,
        String configurationVersion,
        String modelName,
        String policyVersion,
        int maxReviewAttempts) {
    public ReviewConfigurationSnapshot {
        requireText(pipelineVersion, "pipelineVersion");
        requireText(configurationVersion, "configurationVersion");
        requireText(modelName, "modelName");
        requireText(policyVersion, "policyVersion");
        if (maxReviewAttempts < 1) {
            throw new IllegalArgumentException("maxReviewAttempts must be at least 1");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
