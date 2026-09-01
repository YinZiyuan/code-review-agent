package dev.langchain4j.example.codereview.eval;

import dev.langchain4j.example.codereview.model.Category;
import dev.langchain4j.example.codereview.model.ReviewFinding;
import dev.langchain4j.example.codereview.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MatcherTest {

    private ExpectedIssue expected(int line, int[] range) {
        return new ExpectedIssue(
                "I-1", "Foo.java", line, range,
                Category.SECURITY, "sql_injection", Severity.CRITICAL,
                "SQL injection vulnerability", true,
                List.of("Unparameterized query"));
    }

    private ReviewFinding agent(String file, int line, String desc) {
        return new ReviewFinding(
                "F-1", file, line, null, Severity.CRITICAL, Category.SECURITY,
                "title", desc, "fix", "ev", List.of(), "llm_reviewer");
    }

    @Test
    void layer1MissesWhenNoCandidateInRange() {
        AtomicInteger judgeCalls = new AtomicInteger();
        Matcher matcher = new Matcher((expected, agent) -> {
            judgeCalls.incrementAndGet();
            return new LlmJudge.JudgeVerdict(true, 1.0, "unused");
        }, 5);

        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45})),
                List.of(agent("Foo.java", 100, "something else"))
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).matched()).isFalse();
        assertThat(judgeCalls).hasValue(0);
    }

    @Test
    void layer1FiltersByFileNotJustLine() {
        AtomicInteger judgeCalls = new AtomicInteger();
        Matcher matcher = new Matcher((expected, agent) -> {
            judgeCalls.incrementAndGet();
            return new LlmJudge.JudgeVerdict(true, 1.0, "unused");
        }, 5);

        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45})),
                List.of(agent("OtherFile.java", 42, "SQL injection"))
        );

        assertThat(results.get(0).matched()).isFalse();
        assertThat(judgeCalls).hasValue(0);
    }

    @Test
    void layer2MatchesViaJudge() {
        Matcher matcher = new Matcher((expected, agent) ->
                new LlmJudge.JudgeVerdict(true, 0.9, "same problem"), 5);

        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45})),
                List.of(agent("Foo.java", 43, "Found a SQL injection vulnerability here"))
        );

        assertThat(results.get(0).matched()).isTrue();
        assertThat(results.get(0).confidence()).isEqualTo(0.9);
    }

    @Test
    void layer2RejectsWhenJudgeSaysNo() {
        Matcher matcher = new Matcher((expected, agent) ->
                new LlmJudge.JudgeVerdict(false, 0.1, "different concern"), 5);
        ReviewFinding candidate = agent("Foo.java", 43, "missing javadoc");

        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45})),
                List.of(candidate)
        );

        assertThat(results.get(0).matched()).isFalse();
        assertThat(results.get(0).agentFinding()).isSameAs(candidate);
        assertThat(results.get(0).confidence()).isEqualTo(0.1);
        assertThat(results.get(0).judgeReason()).isEqualTo("different concern");
    }

    @Test
    void oneFindingCannotMatchMultipleExpectedIssues() {
        Matcher matcher = new Matcher((expected, agent) ->
                new LlmJudge.JudgeVerdict(true, 0.9, "same problem"), 5);
        ReviewFinding finding = agent("Foo.java", 43, "SQL injection");

        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45}), expected(43, new int[]{40, 45})),
                List.of(finding)
        );

        assertThat(results).filteredOn(MatchResult::matched).hasSize(1);
        assertThat(results).filteredOn(result -> !result.matched()).hasSize(1);
    }

    @Test
    void rangeToleranceDefault5() {
        Matcher matcher = new Matcher((expected, agent) ->
                new LlmJudge.JudgeVerdict(true, 1.0, "yes"), 5);

        List<MatchResult> results = matcher.match(
                List.of(expected(42, new int[]{40, 45})),
                List.of(agent("Foo.java", 48, "sql injection"))
        );

        assertThat(results.get(0).matched()).isTrue();
    }
}
