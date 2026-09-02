package dev.langchain4j.example.codereview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "code-review.work-budget")
public record ReviewWorkBudgetProperties(
        String version,
        Input input,
        Prompt prompt,
        Process process,
        Stages stages,
        Workspace workspace) {

    public ReviewWorkBudget toBudget() {
        Input resolvedInput = input == null ? new Input(null, null, null, null, null, null,
                null, null, null) : input;
        Prompt resolvedPrompt = prompt == null ? new Prompt(null, null, null, null) : prompt;
        Process resolvedProcess = process == null ? new Process(null, null, null) : process;
        Stages resolvedStages = stages == null
                ? new Stages(null, null, null, null, null, null) : stages;
        Workspace resolvedWorkspace = workspace == null ? new Workspace(null) : workspace;
        return new ReviewWorkBudget(
                defaultValue(version, "review-work-v1"),
                resolvedInput.toLimits(),
                resolvedPrompt.toLimits(),
                resolvedProcess.toLimits(),
                resolvedStages.toDeadlines(),
                resolvedWorkspace.toLimits());
    }

    public record Input(
            Long maxDiffBytes,
            Integer maxChangedFiles,
            Integer maxJavaSourceFiles,
            Long maxJavaSourceBytes,
            Long maxArchiveBytes,
            Long maxExpandedBytes,
            Integer maxArchiveEntries,
            Integer maxSnippets,
            Integer maxFindings) {

        ReviewWorkBudget.InputLimits toLimits() {
            return new ReviewWorkBudget.InputLimits(
                    defaultValue(maxDiffBytes, 5L * 1024 * 1024),
                    defaultValue(maxChangedFiles, 500),
                    defaultValue(maxJavaSourceFiles, 2_000),
                    defaultValue(maxJavaSourceBytes, 32L * 1024 * 1024),
                    defaultValue(maxArchiveBytes, 100L * 1024 * 1024),
                    defaultValue(maxExpandedBytes, 500L * 1024 * 1024),
                    defaultValue(maxArchiveEntries, 50_000),
                    defaultValue(maxSnippets, 200),
                    defaultValue(maxFindings, 200));
        }
    }

    public record Prompt(
            Integer maxDiffTokens,
            Integer modelContextTokens,
            Integer completionReserveTokens,
            Integer inputFramingReserveTokens) {

        ReviewWorkBudget.PromptLimits toLimits() {
            return new ReviewWorkBudget.PromptLimits(
                    defaultValue(maxDiffTokens, 4_096),
                    defaultValue(modelContextTokens, 8_192),
                    defaultValue(completionReserveTokens, 2_048),
                    defaultValue(inputFramingReserveTokens, 16));
        }
    }

    public record Process(
            Integer maxOutputBytes, Integer compilerMaxHeapMb, Integer analyzerMaxHeapMb) {
        ReviewWorkBudget.ProcessLimits toLimits() {
            return new ReviewWorkBudget.ProcessLimits(
                    defaultValue(maxOutputBytes, 64 * 1024),
                    defaultValue(compilerMaxHeapMb, 256),
                    defaultValue(analyzerMaxHeapMb, 256));
        }
    }

    public record Stages(
            Duration diffAnalysis,
            Duration toolAnalysis,
            Duration reviewModel,
            Duration summarization,
            Duration compiler,
            Duration spotbugs) {

        ReviewWorkBudget.StageDeadlines toDeadlines() {
            return new ReviewWorkBudget.StageDeadlines(
                    defaultValue(diffAnalysis, Duration.ofSeconds(10)),
                    defaultValue(toolAnalysis, Duration.ofSeconds(45)),
                    defaultValue(reviewModel, Duration.ofSeconds(60)),
                    defaultValue(summarization, Duration.ofSeconds(5)),
                    defaultValue(compiler, Duration.ofSeconds(20)),
                    defaultValue(spotbugs, Duration.ofSeconds(30)));
        }
    }

    public record Workspace(Duration staleAge) {
        ReviewWorkBudget.WorkspaceLimits toLimits() {
            return new ReviewWorkBudget.WorkspaceLimits(
                    defaultValue(staleAge, Duration.ofHours(24)));
        }
    }

    private static <T> T defaultValue(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
