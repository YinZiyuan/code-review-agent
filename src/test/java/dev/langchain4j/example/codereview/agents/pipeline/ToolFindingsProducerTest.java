package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.RegexAnalyzer;
import dev.langchain4j.example.codereview.analyzer.SourceCompiler;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import dev.langchain4j.example.codereview.infra.DiffParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFindingsProducerTest {

    @Test
    void regex_violation_is_returned_with_ok_status() {
        String diff = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,1 +1,2 @@
                 class Foo {
                +  String password = "hunter2";
                """;
        DiffParser parser = new DiffParser();
        ReviewContext ctx = new ReviewContext(diff, parser.parse(diff), Map.of(), Path.of("/nonexistent"));

        SpotBugsAnalyzer spotbugs = new SpotBugsAnalyzer(
                (classesDir, output) -> false, new SourceCompiler());
        ToolFindingsProducer producer = new ToolFindingsProducer(new RegexAnalyzer(), spotbugs);

        ToolFindings out = producer.produce(ctx);

        assertThat(out.violations()).anyMatch(v -> v.rule().equals("hardcoded-credential"));
        assertThat(out.statuses()).anyMatch(s -> s.tool().equals("regex") && s.status().equals("ok"));
        assertThat(out.statuses()).anyMatch(s -> s.tool().equals("spotbugs") && s.status().equals("skipped"));
    }
}
