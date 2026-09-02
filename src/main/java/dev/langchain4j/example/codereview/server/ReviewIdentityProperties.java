package dev.langchain4j.example.codereview.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "code-review.server.identity")
public record ReviewIdentityProperties(
        String pipelineVersion,
        String promptVersion,
        String policyVersion,
        String modelDeploymentIdentity,
        String workBudgetIdentity,
        Integer maxReviewAttempts,
        Integer maxInlineComments) {

    public ReviewIdentityProperties {
        pipelineVersion = defaultText(pipelineVersion, "pipeline-v3");
        promptVersion = defaultText(promptVersion, "review-prompt-v1");
        policyVersion = defaultText(policyVersion, "policy-v1");
        modelDeploymentIdentity = defaultText(modelDeploymentIdentity, "moonshot-public-api-v1");
        workBudgetIdentity = defaultText(workBudgetIdentity, "legacy-work-budget-v1");
        requireSafeIdentity(modelDeploymentIdentity, "modelDeploymentIdentity");
        if (maxReviewAttempts == null) {
            maxReviewAttempts = 3;
        }
        if (maxInlineComments == null) {
            maxInlineComments = 5;
        }
        if (maxReviewAttempts < 1) {
            throw new IllegalArgumentException("maxReviewAttempts must be at least 1");
        }
        if (maxInlineComments < 1) {
            throw new IllegalArgumentException("maxInlineComments must be at least 1");
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null ? fallback : requireText(value);
    }

    private static String requireText(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("identity values must not be blank");
        }
        return value;
    }

    private static void requireSafeIdentity(String value, String name) {
        if (!Pattern.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", value)) {
            throw new IllegalArgumentException(name + " must be a non-secret deployment identifier");
        }
    }
}
