package dev.langchain4j.example.codereview.server;

import java.util.UUID;
import java.util.regex.Pattern;

/** Bounded, non-secret identifiers allowed in production review-operation logs. */
public record ReviewCorrelation(
        String deliveryId,
        UUID reviewRunId,
        Long repositoryId,
        Integer pullRequestNumber,
        String headSha,
        UUID jobId,
        String pipelineVersion,
        String configurationVersion) {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}");

    public ReviewCorrelation {
        deliveryId = safe(deliveryId, SAFE_IDENTIFIER);
        repositoryId = repositoryId != null && repositoryId > 0 ? repositoryId : null;
        pullRequestNumber = pullRequestNumber != null && pullRequestNumber > 0
                ? pullRequestNumber : null;
        headSha = safe(headSha, SHA);
        pipelineVersion = safe(pipelineVersion, SAFE_IDENTIFIER);
        configurationVersion = safe(configurationVersion, SAFE_IDENTIFIER);
    }

    public static ReviewCorrelation webhook(
            String deliveryId,
            String pipelineVersion,
            String configurationVersion) {
        return new ReviewCorrelation(
                deliveryId, null, null, null, null, null,
                pipelineVersion, configurationVersion);
    }

    private static String safe(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches() ? value : null;
    }
}
