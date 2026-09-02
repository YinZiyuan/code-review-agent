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
        WorkspaceLimits workspace) {

    public ReviewWorkBudget {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        input = Objects.requireNonNull(input, "input");
        prompt = Objects.requireNonNull(prompt, "prompt");
        process = Objects.requireNonNull(process, "process");
        stages = Objects.requireNonNull(stages, "stages");
        workspace = Objects.requireNonNull(workspace, "workspace");
    }

    public String configurationHash() {
        String canonical = String.join("\n",
                version,
                input.canonical(),
                prompt.canonical(),
                process.canonical(),
                stages.canonical(),
                workspace.canonical());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
            long maxArchiveBytes,
            long maxExpandedBytes,
            int maxArchiveEntries,
            int maxSnippets,
            int maxFindings) {

        public InputLimits {
            positive(maxDiffBytes, "maxDiffBytes");
            positive(maxChangedFiles, "maxChangedFiles");
            positive(maxJavaSourceFiles, "maxJavaSourceFiles");
            positive(maxJavaSourceBytes, "maxJavaSourceBytes");
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
                    + maxJavaSourceBytes + ":" + maxArchiveBytes + ":" + maxExpandedBytes + ":"
                    + maxArchiveEntries + ":" + maxSnippets + ":" + maxFindings;
        }
    }

    public record PromptLimits(
            int maxDiffTokens,
            int modelContextTokens,
            int completionReserveTokens,
            int inputFramingReserveTokens) {

        public PromptLimits {
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
            return maxDiffTokens + ":" + modelContextTokens + ":"
                    + completionReserveTokens + ":" + inputFramingReserveTokens;
        }
    }

    public record ProcessLimits(
            int maxOutputBytes, int compilerMaxHeapMb, int analyzerMaxHeapMb) {
        public ProcessLimits {
            positive(maxOutputBytes, "maxOutputBytes");
            positive(compilerMaxHeapMb, "compilerMaxHeapMb");
            positive(analyzerMaxHeapMb, "analyzerMaxHeapMb");
        }

        private String canonical() {
            return maxOutputBytes + ":" + compilerMaxHeapMb + ":" + analyzerMaxHeapMb;
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

    public record WorkspaceLimits(Duration staleAge) {
        public WorkspaceLimits {
            requirePositive(staleAge, "staleAge");
        }

        private String canonical() {
            return staleAge.toString();
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
}
