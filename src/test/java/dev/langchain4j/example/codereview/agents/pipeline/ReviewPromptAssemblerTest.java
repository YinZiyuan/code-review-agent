package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import dev.langchain4j.example.codereview.analyzer.Violation;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.model.Severity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPromptAssemblerTest {

    @Test
    void oversizedMultiHunkInputIsDeterministicAndNeverConsumesCompletionReserve() {
        String firstHunk = """
                diff --git a/src/A.java b/src/A.java
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1 +1,2 @@
                 class A {}
                +class AddedA {}
                """;
        String secondHunk = """
                diff --git a/src/B.java b/src/B.java
                --- a/src/B.java
                +++ b/src/B.java
                @@ -1 +1,300 @@
                +%s
                +TAIL_SENTINEL_MUST_BE_OMITTED
                """.formatted("longIdentifier ".repeat(4_000));
        String diff = firstHunk + secondHunk;
        ReviewWorkBudget defaults = new ReviewWorkBudgetProperties(
                null, null, null, null, null, null).toBudget();
        ReviewWorkBudget budget = new ReviewWorkBudget(
                defaults.version(), defaults.input(),
                new ReviewWorkBudget.PromptLimits(420, 900, 200, 16),
                defaults.process(), defaults.stages(), defaults.workspace());
        PromptTokenizer tokenizer = new JTokkitPromptTokenizer();
        ReviewPromptAssembler assembler = new ReviewPromptAssembler(tokenizer, budget);
        ReviewContext context = new ReviewContext(
                diff, new DiffParser().parse(diff), Map.of(), Path.of("/source"));

        ReviewPromptAssembler.AssembledPrompt first = assembler.assemble(
                "system instruction", context, new ToolFindings(List.of(), List.of()), List.of());
        ReviewPromptAssembler.AssembledPrompt second = assembler.assemble(
                "system instruction", context, new ToolFindings(List.of(), List.of()), List.of());

        assertThat(first).isEqualTo(second);
        assertThat(first.tokenCount()).isEqualTo(tokenizer.count(first.text()));
        assertThat(first.tokenCount()).isLessThanOrEqualTo(budget.maxPromptTokens());
        assertThat(first.tokenCount()
                + budget.prompt().inputFramingReserveTokens()
                + budget.prompt().completionReserveTokens())
                .isLessThanOrEqualTo(budget.prompt().modelContextTokens());
        assertThat(first.text()).contains(firstHunk.strip());
        assertThat(first.text()).contains("[diff truncated by review work budget]");
        assertThat(first.text()).doesNotContain("TAIL_SENTINEL_MUST_BE_OMITTED");
        assertThat(first.truncated()).isTrue();
    }

    @Test
    void tokenizerTruncationPreservesUnicodeAndHonorsExactTokenLimit() {
        PromptTokenizer tokenizer = new JTokkitPromptTokenizer();
        String text = "你好 review ✅ ".repeat(100);

        String truncated = tokenizer.truncate(text, 17);

        assertThat(tokenizer.count(truncated)).isLessThanOrEqualTo(17);
        assertThat(truncated).doesNotContain("�");
        assertThat(text).startsWith(truncated);
    }

    @Test
    void allUnorderedPromptInputsAreCanonicalizedBeforeTruncation() {
        ReviewWorkBudget budget = new ReviewWorkBudgetProperties(
                null, null, null, null, null, null).toBudget();
        ReviewPromptAssembler assembler = new ReviewPromptAssembler(
                new JTokkitPromptTokenizer(), budget);
        Map<String, List<CodeSnippet>> forward = new LinkedHashMap<>();
        forward.put("z/Z.java", List.of(new CodeSnippet("z/Z.java", 9, "z")));
        forward.put("a/A.java", List.of(new CodeSnippet("a/A.java", 2, "b"),
                new CodeSnippet("a/A.java", 1, "a")));
        Map<String, List<CodeSnippet>> reverse = new LinkedHashMap<>();
        reverse.put("a/A.java", List.of(new CodeSnippet("a/A.java", 1, "a"),
                new CodeSnippet("a/A.java", 2, "b")));
        reverse.put("z/Z.java", List.of(new CodeSnippet("z/Z.java", 9, "z")));
        List<Violation> findings = List.of(
                new Violation(Severity.WARNING, "z/Z.java", 9, "z-rule", "z"),
                new Violation(Severity.CRITICAL, "a/A.java", 1, "a-rule", "a"));
        List<Citation> citations = List.of(
                new Citation("z", "z-source", "z-section"),
                new Citation("a", "a-source", "a-section"));

        String first = assembler.assemble("system",
                new ReviewContext("diff", List.of(), forward, Path.of("source")),
                new ToolFindings(findings, List.of()), citations).text();
        String second = assembler.assemble("system",
                new ReviewContext("diff", List.of(), reverse, Path.of("source")),
                new ToolFindings(List.of(findings.get(1), findings.get(0)), List.of()),
                List.of(citations.get(1), citations.get(0))).text();

        assertThat(first).isEqualTo(second);
        assertThat(first.indexOf("a/A.java")).isLessThan(first.indexOf("z/Z.java"));
        assertThat(first.indexOf("id=a ")).isLessThan(first.indexOf("id=z "));
    }

    @Test
    void assembledPromptIsByteIdenticalAcrossFreshJvmProcesses() throws Exception {
        String javaExecutable = Path.of(
                System.getProperty("java.home"), "bin", "java").toString();
        String classPath = System.getProperty("java.class.path");
        HashSet<String> hashes = new HashSet<>();
        for (int process = 0; process < 5; process++) {
            Process child = new ProcessBuilder(
                    javaExecutable, "-cp", classPath, PromptDeterminismProbe.class.getName(),
                    Integer.toString(process))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(child.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertThat(child.waitFor()).isZero();
            hashes.add(output.trim());
        }
        assertThat(hashes).hasSize(1);
    }
}
