package dev.langchain4j.example.codereview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties(prefix = "code-review.work-budget")
public record ReviewWorkBudgetProperties(
        String version,
        Input input,
        Prompt prompt,
        Process process,
        Stages stages,
        Workspace workspace,
        Execution execution) {

    @ConstructorBinding
    public ReviewWorkBudgetProperties {
    }

    public ReviewWorkBudgetProperties(
            String version, Input input, Prompt prompt, Process process,
            Stages stages, Workspace workspace) {
        this(version, input, prompt, process, stages, workspace, null);
    }

    public ReviewWorkBudget toBudget() {
        return toBudget("gpt-5.6-sol");
    }

    ReviewWorkBudget toBudget(String defaultModelId) {
        Input resolvedInput = input == null ? new Input(null, null, null, null, null, null,
                null, null, null, null) : input;
        Prompt resolvedPrompt = prompt == null
                ? new Prompt(null, null, null, null, null, null, null) : prompt;
        Process resolvedProcess = process == null ? new Process(null, null, null, null) : process;
        Stages resolvedStages = stages == null
                ? new Stages(null, null, null, null, null, null) : stages;
        Workspace resolvedWorkspace = workspace == null
                ? new Workspace(null, null, null, null, null) : workspace;
        Execution resolvedExecution = execution == null
                ? new Execution(null, null, null) : execution;
        return new ReviewWorkBudget(
                defaultValue(version, "review-work-v1"),
                resolvedInput.toLimits(),
                resolvedPrompt.toLimits(defaultModelId),
                resolvedProcess.toLimits(),
                resolvedStages.toDeadlines(),
                resolvedWorkspace.toLimits(),
                resolvedExecution.toLimits());
    }

    public record Input(
            Long maxDiffBytes,
            Integer maxChangedFiles,
            Integer maxJavaSourceFiles,
            Long maxJavaSourceBytes,
            Integer maxJavaSourceLineBytes,
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
                    defaultValue(maxJavaSourceLineBytes, 64 * 1024),
                    defaultValue(maxArchiveBytes, 100L * 1024 * 1024),
                    defaultValue(maxExpandedBytes, 500L * 1024 * 1024),
                    defaultValue(maxArchiveEntries, 50_000),
                    defaultValue(maxSnippets, 200),
                    defaultValue(maxFindings, 200));
        }
    }

    public record Prompt(
            String modelId,
            String tokenizerId,
            String tokenizerVersion,
            Integer maxDiffTokens,
            Integer modelContextTokens,
            Integer completionReserveTokens,
            Integer inputFramingReserveTokens) {

        ReviewWorkBudget.PromptLimits toLimits(String defaultModelId) {
            return new ReviewWorkBudget.PromptLimits(
                    defaultValue(modelId, defaultModelId),
                    defaultValue(tokenizerId, "cl100k_base"),
                    defaultValue(tokenizerVersion, "jtokkit-1.1.0"),
                    defaultValue(maxDiffTokens, 4_096),
                    defaultValue(modelContextTokens, 8_192),
                    defaultValue(completionReserveTokens, 2_048),
                    defaultValue(inputFramingReserveTokens, 16));
        }
    }

    public record Process(
            Integer maxOutputBytes,
            Integer maxCompilerArgumentBytes,
            Integer compilerMaxHeapMb,
            Integer analyzerMaxHeapMb) {
        ReviewWorkBudget.ProcessLimits toLimits() {
            return new ReviewWorkBudget.ProcessLimits(
                    defaultValue(maxOutputBytes, 64 * 1024),
                    defaultValue(maxCompilerArgumentBytes, 512 * 1024),
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

    public record Workspace(
            Duration staleAge,
            Integer maxChildrenInspected,
            Integer maxDeletionsPerRun,
            Integer maxEntriesDeletedPerRun,
            Duration cleanupDeadline) {
        ReviewWorkBudget.WorkspaceLimits toLimits() {
            return new ReviewWorkBudget.WorkspaceLimits(
                    defaultValue(staleAge, Duration.ofHours(24)),
                    defaultValue(maxChildrenInspected, 1_024),
                    defaultValue(maxDeletionsPerRun, 64),
                    defaultValue(maxEntriesDeletedPerRun, 10_000),
                    defaultValue(cleanupDeadline, Duration.ofSeconds(5)));
        }
    }

    public record Execution(
            Duration reviewerTimeout, Integer stageWorkers, Integer stageQueueCapacity) {
        ReviewWorkBudget.ExecutionLimits toLimits() {
            return new ReviewWorkBudget.ExecutionLimits(
                    defaultValue(reviewerTimeout, Duration.ofSeconds(60)),
                    defaultValue(stageWorkers, 4),
                    defaultValue(stageQueueCapacity, 16));
        }
    }

    private static <T> T defaultValue(T value, T fallback) {
        return value == null ? fallback : value;
    }
}
