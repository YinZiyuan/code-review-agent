package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.tools.CodeSearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffAnalyzerTest {

    @TempDir
    Path tmp;

    private final DiffParser parser = new DiffParser();
    private final CodeSearchTool search = new CodeSearchTool();
    private final ReviewWorkBudget defaults = new ReviewWorkBudgetProperties(
            null, null, null, null, null, null).toBudget();

    @Test
    void grep_finds_identifier_in_source_root() throws Exception {
        Path src = tmp.resolve("source-before/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Helper.java"),
                "package com.example;\npublic class Helper {\n  void doThing() {}\n}\n");

        String diff = """
                diff --git a/com/example/Foo.java b/com/example/Foo.java
                --- a/com/example/Foo.java
                +++ b/com/example/Foo.java
                @@ -1,1 +1,3 @@
                 package com.example;
                +class Foo { void run() { new Helper().doThing(); } }
                """;

        DiffAnalyzer analyzer = new DiffAnalyzer(parser, search, defaults);
        ReviewContext ctx = analyzer.analyze(diff, tmp.resolve("source-before"));

        assertThat(ctx.contextByFile()).containsKey("com/example/Foo.java");
        assertThat(ctx.contextByFile().get("com/example/Foo.java"))
                .anyMatch(s -> s.file().equals("com/example/Helper.java")
                        && s.text().contains("doThing"));
    }

    @Test
    void missing_source_root_yields_empty_context_no_throw() {
        DiffAnalyzer analyzer = new DiffAnalyzer(parser, search, defaults);
        ReviewContext ctx = analyzer.analyze(
                "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n+y\n",
                tmp.resolve("nonexistent"));
        assertThat(ctx.contextByFile()).isEmpty();
    }

    @Test
    void manyFileDiffAndCrossFileSearchRespectGlobalDeterministicLimits() throws Exception {
        Path src = tmp.resolve("many-files");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Shared.java"), """
                class Shared {
                  void alpha() {}
                  void beta() {}
                  void gamma() {}
                }
                """);
        List<String> fileDiffs = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            String method = List.of("alpha", "beta", "gamma", "delta").get(index);
            fileDiffs.add("""
                    diff --git a/F%s.java b/F%s.java
                    --- a/F%s.java
                    +++ b/F%s.java
                    @@ -0,0 +1 @@
                    +class F%s { void run() { %s(); } }
                    """.formatted(index, index, index, index, index, method));
        }
        ReviewWorkBudget budget = new ReviewWorkBudget(
                defaults.version(),
                new ReviewWorkBudget.InputLimits(
                        defaults.input().maxDiffBytes(), 2,
                        defaults.input().maxJavaSourceFiles(),
                        defaults.input().maxJavaSourceBytes(),
                        defaults.input().maxArchiveBytes(),
                        defaults.input().maxExpandedBytes(),
                        defaults.input().maxArchiveEntries(),
                        1,
                        defaults.input().maxFindings()),
                defaults.prompt(), defaults.process(), defaults.stages(), defaults.workspace());

        ReviewContext context = new DiffAnalyzer(parser, search, budget)
                .analyze(String.join("", fileDiffs), src);

        assertThat(context.fileDiffs()).extracting(DiffParser.FileDiff::path)
                .containsExactly("F0.java", "F1.java");
        assertThat(context.contextByFile()).hasSize(1).containsOnlyKeys("F0.java");
        assertThat(context.contextByFile().get("F0.java")).hasSize(1);
    }
}
