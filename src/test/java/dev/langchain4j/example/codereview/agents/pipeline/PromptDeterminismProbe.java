package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PromptDeterminismProbe {

    private PromptDeterminismProbe() {
    }

    public static void main(String[] args) throws Exception {
        int rotation = Integer.parseInt(args[0]);
        Map<String, List<CodeSnippet>> context = new LinkedHashMap<>();
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            indexes.add((index + rotation) % 12);
        }
        for (int index : indexes) {
            String file = "source/F" + index + ".java";
            context.put(file, List.of(new CodeSnippet(file, index + 1, "line " + index)));
        }
        ReviewWorkBudget budget = new ReviewWorkBudgetProperties(
                null, null, null, null, null, null).toBudget();
        PromptTokenizer tokenizer = new PromptTokenizer() {
            @Override
            public int count(String text) {
                return text == null ? 0 : text.length();
            }

            @Override
            public String truncate(String text, int maxTokens) {
                return text.substring(0, Math.min(text.length(), Math.max(0, maxTokens)));
            }
        };
        String prompt = new ReviewPromptAssembler(tokenizer, budget).assemble(
                "system",
                new ReviewContext("diff", List.of(), context, Path.of("source")),
                new ToolFindings(List.of(), List.of()),
                List.of()).text();
        System.out.print(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(prompt.getBytes(StandardCharsets.UTF_8))));
    }
}
