package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import dev.langchain4j.example.codereview.infra.DiffParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
}
