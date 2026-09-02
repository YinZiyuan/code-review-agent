package dev.langchain4j.example.codereview.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

public record ReviewWorkBudget(
        String version,
        InputLimits input,
        PromptLimits prompt,
        ProcessLimits process,
        StageDeadlines stages,
        WorkspaceLimits workspace,
        ExecutionLimits execution) {

    public ReviewWorkBudget(
            String version,
            InputLimits input,
            PromptLimits prompt,
            ProcessLimits process,
            StageDeadlines stages,
            WorkspaceLimits workspace) {
        this(version, input, prompt, process, stages, workspace,
                new ExecutionLimits(Duration.ofSeconds(60), 4, 16));
    }

    public ReviewWorkBudget {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        input = Objects.requireNonNull(input, "input");
        prompt = Objects.requireNonNull(prompt, "prompt");
        process = Objects.requireNonNull(process, "process");
        stages = Objects.requireNonNull(stages, "stages");
        workspace = Objects.requireNonNull(workspace, "workspace");
        execution = Objects.requireNonNull(execution, "execution");
    }

    public String configurationHash() {
        String canonical = String.join("\n",
                version,
                input.canonical(),
                prompt.canonical(),
                process.canonical(),
                stages.canonical(),
                workspace.canonical(),
                execution.canonical());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Stable typed seam consumed by persisted review-configuration identity. */
    public String identity() {
        return version + ":" + configurationHash();
    }

    public int maxPromptTokens() {
        return prompt.modelContextTokens()
                - prompt.completionReserveTokens()
                - prompt.inputFramingReserveTokens();
    }

    public record InputLimits(
            long maxDiffBytes,
            int maxChangedFiles,
            int maxJavaSourceFiles,
            long maxJavaSourceBytes,
            int maxJavaSourceLineBytes,
            long maxArchiveBytes,
            long maxExpandedBytes,
            int maxArchiveEntries,
            int maxSnippets,
            int maxFindings) {

        public InputLimits(
                long maxDiffBytes,
                int maxChangedFiles,
                int maxJavaSourceFiles,
                long maxJavaSourceBytes,
                long maxArchiveBytes,
                long maxExpandedBytes,
                int maxArchiveEntries,
                int maxSnippets,
                int maxFindings) {
            this(maxDiffBytes, maxChangedFiles, maxJavaSourceFiles, maxJavaSourceBytes,
                    64 * 1024, maxArchiveBytes, maxExpandedBytes, maxArchiveEntries,
                    maxSnippets, maxFindings);
        }

        public InputLimits {
            positive(maxDiffBytes, "maxDiffBytes");
            positive(maxChangedFiles, "maxChangedFiles");
            positive(maxJavaSourceFiles, "maxJavaSourceFiles");
            positive(maxJavaSourceBytes, "maxJavaSourceBytes");
            positive(maxJavaSourceLineBytes, "maxJavaSourceLineBytes");
            positive(maxArchiveBytes, "maxArchiveBytes");
            positive(maxExpandedBytes, "maxExpandedBytes");
            positive(maxArchiveEntries, "maxArchiveEntries");
            positive(maxSnippets, "maxSnippets");
            positive(maxFindings, "maxFindings");
            if (maxDiffBytes > Integer.MAX_VALUE || maxArchiveBytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "download byte limits must not exceed Integer.MAX_VALUE");
            }
        }

        private String canonical() {
            return maxDiffBytes + ":" + maxChangedFiles + ":" + maxJavaSourceFiles + ":"
                    + maxJavaSourceBytes + ":" + maxJavaSourceLineBytes + ":"
                    + maxArchiveBytes + ":" + maxExpandedBytes + ":"
                    + maxArchiveEntries + ":" + maxSnippets + ":" + maxFindings;
        }
    }

    public record PromptLimits(
            String modelId,
            String tokenizerId,
            String tokenizerVersion,
            int maxDiffTokens,
            int modelContextTokens,
            int completionReserveTokens,
            int inputFramingReserveTokens) {

        public PromptLimits(
                int maxDiffTokens,
                int modelContextTokens,
                int completionReserveTokens,
                int inputFramingReserveTokens) {
            this("moonshot-v1-8k", "cl100k_base", "jtokkit-1.1.0",
                    maxDiffTokens, modelContextTokens, completionReserveTokens,
                    inputFramingReserveTokens);
        }

        public PromptLimits {
            requireText(modelId, "modelId");
            requireText(tokenizerId, "tokenizerId");
            requireText(tokenizerVersion, "tokenizerVersion");
            positive(maxDiffTokens, "maxDiffTokens");
            positive(modelContextTokens, "modelContextTokens");
            positive(completionReserveTokens, "completionReserveTokens");
            positive(inputFramingReserveTokens, "inputFramingReserveTokens");
            if ((long) completionReserveTokens + inputFramingReserveTokens
                    >= modelContextTokens) {
                throw new IllegalArgumentException(
                        "completionReserveTokens and inputFramingReserveTokens "
                                + "must fit below modelContextTokens");
            }
            if (maxDiffTokens > modelContextTokens
                    - completionReserveTokens - inputFramingReserveTokens) {
                throw new IllegalArgumentException(
                        "maxDiffTokens must fit inside the reserved model context");
            }
        }

        private String canonical() {
            return modelId + ":" + tokenizerId + ":" + tokenizerVersion + ":"
                    + maxDiffTokens + ":" + modelContextTokens + ":"
                    + completionReserveTokens + ":" + inputFramingReserveTokens;
        }
    }

    public record ProcessLimits(
            int maxOutputBytes,
            int maxCompilerArgumentBytes,
            int compilerMaxHeapMb,
            int analyzerMaxHeapMb) {

        public ProcessLimits(int maxOutputBytes, int compilerMaxHeapMb, int analyzerMaxHeapMb) {
            this(maxOutputBytes, 512 * 1024, compilerMaxHeapMb, analyzerMaxHeapMb);
        }

        public ProcessLimits {
            positive(maxOutputBytes, "maxOutputBytes");
            positive(maxCompilerArgumentBytes, "maxCompilerArgumentBytes");
            positive(compilerMaxHeapMb, "compilerMaxHeapMb");
            positive(analyzerMaxHeapMb, "analyzerMaxHeapMb");
        }

        private String canonical() {
            return maxOutputBytes + ":" + maxCompilerArgumentBytes + ":"
                    + compilerMaxHeapMb + ":" + analyzerMaxHeapMb;
        }
    }

    public record StageDeadlines(
            Duration diffAnalysis,
            Duration toolAnalysis,
            Duration reviewModel,
            Duration summarization,
            Duration compiler,
            Duration spotbugs) {

        public StageDeadlines {
            requirePositive(diffAnalysis, "diffAnalysis");
            requirePositive(toolAnalysis, "toolAnalysis");
            requirePositive(reviewModel, "reviewModel");
            requirePositive(summarization, "summarization");
            requirePositive(compiler, "compiler");
            requirePositive(spotbugs, "spotbugs");
        }

        private String canonical() {
            return diffAnalysis + ":" + toolAnalysis + ":" + reviewModel + ":"
                    + summarization + ":" + compiler + ":" + spotbugs;
        }
    }

    public record WorkspaceLimits(
            Duration staleAge,
            int maxChildrenInspected,
            int maxDeletionsPerRun,
            int maxEntriesDeletedPerRun,
            Duration cleanupDeadline) {

        public WorkspaceLimits(Duration staleAge) {
            this(staleAge, 1_024, 64, 10_000, Duration.ofSeconds(5));
        }

        public WorkspaceLimits {
            requirePositive(staleAge, "staleAge");
            positive(maxChildrenInspected, "maxChildrenInspected");
            positive(maxDeletionsPerRun, "maxDeletionsPerRun");
            positive(maxEntriesDeletedPerRun, "maxEntriesDeletedPerRun");
            requirePositive(cleanupDeadline, "cleanupDeadline");
        }

        private String canonical() {
            return staleAge + ":" + maxChildrenInspected + ":" + maxDeletionsPerRun + ":"
                    + maxEntriesDeletedPerRun + ":" + cleanupDeadline;
        }
    }

    public record ExecutionLimits(
            Duration reviewerTimeout, int stageWorkers, int stageQueueCapacity) {
        public ExecutionLimits {
            requirePositive(reviewerTimeout, "reviewerTimeout");
            positive(stageWorkers, "stageWorkers");
            positive(stageQueueCapacity, "stageQueueCapacity");
        }

        private String canonical() {
            return reviewerTimeout + ":" + stageWorkers + ":" + stageQueueCapacity;
        }
    }

    private static void positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
