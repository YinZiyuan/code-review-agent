package dev.langchain4j.example.codereview.agents.pipeline;

import dev.langchain4j.example.codereview.analyzer.RegexAnalyzer;
import dev.langchain4j.example.codereview.analyzer.SourceCompiler;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import dev.langchain4j.example.codereview.analyzer.SpotBugsResult;
import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.ToolRunState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFindingsProducerTest {

    @Test
    void regex_violation_is_returned_with_ran_status_and_spotbugs_expected_skip() {
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
        assertThat(out.statuses()).anyMatch(s -> s.tool().equals("regex") && s.state() == ToolRunState.RAN);
        assertThat(out.statuses()).anyMatch(s -> s.tool().equals("spotbugs")
                && s.state() == ToolRunState.SKIPPED_EXPECTED);
    }

    @Test
    void spotbugs_can_run_without_findings() {
        String diff = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,1 +1,2 @@
                 class Foo {
                +  int count = 1;
                """;
        DiffParser parser = new DiffParser();
        ReviewContext ctx = new ReviewContext(diff, parser.parse(diff), Map.of(), Path.of("/nonexistent"));

        SpotBugsAnalyzer spotbugs = new SpotBugsAnalyzer(
                (classesDir, output) -> false, new SourceCompiler()) {
            @Override
            public SpotBugsResult analyzeWithSource(List<DiffParser.FileDiff> files, Path sourceDir) {
                return new SpotBugsResult(true, List.of());
            }
        };
        ToolFindingsProducer producer = new ToolFindingsProducer(new RegexAnalyzer(), spotbugs);

        ToolFindings out = producer.produce(ctx);

        assertThat(out.statuses()).anyMatch(s -> s.tool().equals("spotbugs") && s.state() == ToolRunState.RAN);
    }

    @Test
    void spotbugs_failure_is_reported_without_throwing() {
        String diff = """
                diff --git a/Foo.java b/Foo.java
                --- a/Foo.java
                +++ b/Foo.java
                @@ -1,1 +1,2 @@
                 class Foo {
                +  int count = 1;
                """;
        DiffParser parser = new DiffParser();
        ReviewContext ctx = new ReviewContext(diff, parser.parse(diff), Map.of(), Path.of("/nonexistent"));

        SpotBugsAnalyzer spotbugs = new SpotBugsAnalyzer(
                (classesDir, output) -> false, new SourceCompiler()) {
            @Override
            public SpotBugsResult analyzeWithSource(List<DiffParser.FileDiff> files, Path sourceDir) {
                throw new RuntimeException("boom");
            }
        };
        ToolFindingsProducer producer = new ToolFindingsProducer(new RegexAnalyzer(), spotbugs);

        ToolFindings out = producer.produce(ctx);

        assertThat(out.statuses()).anySatisfy(s -> {
            assertThat(s.tool()).isEqualTo("spotbugs");
            assertThat(s.state()).isEqualTo(ToolRunState.FAILED);
            assertThat(s.reason()).contains("boom");
        });
    }
}
