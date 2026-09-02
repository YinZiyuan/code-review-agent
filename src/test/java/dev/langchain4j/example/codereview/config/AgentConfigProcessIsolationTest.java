package dev.langchain4j.example.codereview.config;

import dev.langchain4j.example.codereview.analyzer.BoundedProcessRunner;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentConfigProcessIsolationTest {

    @Test
    void spotBugsUsesTheSharedDeadlineHeapAndOutputBudget() throws Exception {
        ReviewWorkBudget budget = new ReviewWorkBudgetProperties(
                null, null, null, null, null, null).toBudget();
        BoundedProcessRunner process = mock(BoundedProcessRunner.class);
        when(process.run(any())).thenReturn(new BoundedProcessRunner.Result(
                BoundedProcessRunner.Outcome.COMPLETED,
                OptionalInt.of(0),
                "<BugCollection/>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                false));
        Path workspace = Files.createTempDirectory("agent-config-process-");
        try {
            Path classes = Files.createDirectory(workspace.resolve("classes"));
            Path output = workspace.resolve("report.xml");
            SpotBugsAnalyzer.Runner runner = new AgentConfig().spotBugsRunner(process, budget);

            assertThat(runner.run(classes, output))
                    .isEqualTo(SpotBugsAnalyzer.RunOutcome.COMPLETED);

            ArgumentCaptor<BoundedProcessRunner.Request> request =
                    ArgumentCaptor.forClass(BoundedProcessRunner.Request.class);
            verify(process).run(request.capture());
            assertThat(request.getValue().kind())
                    .isEqualTo(BoundedProcessRunner.ProcessKind.SPOTBUGS);
            assertThat(request.getValue().command()).containsSubsequence(
                    "spotbugs", "-maxHeap", "256", "-textui");
            assertThat(request.getValue().command()).doesNotContain("-output", output.toString());
            assertThat(request.getValue().timeout()).isEqualTo(budget.stages().spotbugs());
            assertThat(request.getValue().maxOutputBytes())
                    .isEqualTo(budget.process().maxOutputBytes());
            assertThat(output).hasContent("<BugCollection/>");
        } finally {
            Files.deleteIfExists(workspace.resolve("report.xml"));
            Files.deleteIfExists(workspace.resolve("classes"));
            Files.deleteIfExists(workspace);
        }
    }
}
