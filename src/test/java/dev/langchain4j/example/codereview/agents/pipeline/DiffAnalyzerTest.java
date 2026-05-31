package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.tools.CodeSearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiffAnalyzerTest {

    @TempDir
    Path tmp;

    private final DiffParser parser = new DiffParser();
    private final CodeSearchTool search = new CodeSearchTool();

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

        DiffAnalyzer analyzer = new DiffAnalyzer(parser, search);
        ReviewContext ctx = analyzer.analyze(diff, tmp.resolve("source-before"));

        assertThat(ctx.contextByFile()).containsKey("com/example/Foo.java");
        assertThat(ctx.contextByFile().get("com/example/Foo.java"))
                .anyMatch(s -> s.file().equals("com/example/Helper.java")
                        && s.text().contains("doThing"));
    }

    @Test
    void missing_source_root_yields_empty_context_no_throw() {
        DiffAnalyzer analyzer = new DiffAnalyzer(parser, search);
        ReviewContext ctx = analyzer.analyze(
                "diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n+y\n",
                tmp.resolve("nonexistent"));
        assertThat(ctx.contextByFile()).isEmpty();
    }
}
