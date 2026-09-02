package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.config.CodeReviewProperties;
import dev.langchain4j.example.codereview.eval.EvalReport;
import dev.langchain4j.example.codereview.eval.EvaluationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalCommandTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void setDebug() {
        System.setProperty("debug", "release");
    }

    @AfterEach
    void clear() {
        System.clearProperty("debug");
    }

    @Test
    void clears_debug_system_property_and_passes_filter() throws Exception {
        EvaluationRunner runner = mock(EvaluationRunner.class);
        ArgumentCaptor<Set<String>> filterCap = ArgumentCaptor.forClass(Set.class);
        when(runner.run(any(), any(), anyString(), any(), filterCap.capture(), eq(1)))
                .thenReturn(emptyReport());

        CodeReviewProperties props = new CodeReviewProperties(
                new CodeReviewProperties.Rag(tmp, 3, 0.4, false, 8, 4, 60),
                new CodeReviewProperties.Eval("m", 1, tmp, tmp));
        EvalCommand cmd = new EvalCommand(runner, props);
        setField(cmd, "version", "v-test");
        setField(cmd, "pipeline", "p");
        setField(cmd, "samplesCsv", "reverse-001,reverse-002");
        setField(cmd, "runs", 1);

        cmd.call();

        assertThat(System.getProperty("debug")).isNull();
        assertThat(filterCap.getValue()).containsExactlyInAnyOrder("reverse-001", "reverse-002");
        verify(runner).run(eq(tmp), eq(tmp), eq("v-test"), any(), any(), eq(1));
    }

    @Test
    void passes_runs_option_to_runner_and_report_config() throws Exception {
        EvaluationRunner runner = mock(EvaluationRunner.class);
        when(runner.run(any(), any(), anyString(), any(), any(), eq(3)))
                .thenReturn(emptyReport());

        CodeReviewProperties props = new CodeReviewProperties(
                new CodeReviewProperties.Rag(tmp, 3, 0.4, false, 8, 4, 60),
                new CodeReviewProperties.Eval("m", 1, tmp, tmp));
        EvalCommand cmd = new EvalCommand(runner, props);
        setField(cmd, "version", "v-runs");
        setField(cmd, "pipeline", "p");
        setField(cmd, "runs", 3);

        cmd.call();

        verify(runner).run(eq(tmp), eq(tmp), eq("v-runs"),
                argThat(c -> c.get("runs_per_sample").equals(3)),
                any(), eq(3));
    }

    private EvalReport emptyReport() {
        return new EvalReport("v-test", "abc", null, "t",
                Map.of(), List.of(), Map.of("recall", 0.0, "precision", 0.0, "fp_rate", 0.0),
                List.of(), List.of(), Map.of());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
