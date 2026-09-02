package dev.langchain4j.example.codereview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.analyzer.BoundedProcessRunner;
import dev.langchain4j.example.codereview.analyzer.SourceCompiler;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import dev.langchain4j.example.codereview.eval.EvaluationRunner;
import dev.langchain4j.example.codereview.eval.LlmJudge;
import dev.langchain4j.example.codereview.eval.LlmJudgeImpl;
import dev.langchain4j.example.codereview.eval.Matcher;
import dev.langchain4j.example.codereview.eval.ModelRuntimeMetadata;
import dev.langchain4j.example.codereview.eval.ModelRuntimeMetadataResolver;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.example.codereview.workspace.ReviewWorkspaceFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class AgentConfig {

    @Bean
    public JsonRepair jsonRepair(ChatModel chatModel, ObjectMapper mapper) {
        return new JsonRepair(chatModel, mapper);
    }

    @Bean
    public LlmJudge llmJudge(ChatModel chatModel, ObjectMapper mapper) {
        return new LlmJudgeImpl(chatModel, mapper);
    }

    @Bean
    public Matcher matcher(LlmJudge judge) {
        return new Matcher(judge, 5);
    }

    @Bean
    public ModelRuntimeMetadata modelRuntimeMetadata(Environment environment) {
        return new ModelRuntimeMetadataResolver(environment).resolve();
    }

    @Bean
    public EvaluationRunner evaluationRunner(CodeReviewAgent agent, Matcher matcher, ObjectMapper mapper,
                                             ModelRuntimeMetadata modelRuntime) {
        return new EvaluationRunner(agent, matcher, mapper, modelRuntime);
    }

    @Bean
    public SourceCompiler sourceCompiler(
            BoundedProcessRunner processRunner, ReviewWorkBudget budget) {
        return new SourceCompiler(processRunner::run, budget);
    }

    @Bean
    public SpotBugsAnalyzer.Runner spotBugsRunner(
            BoundedProcessRunner processRunner, ReviewWorkBudget budget) {
        return (classesDir, output) -> {
            BoundedProcessRunner.Result result = processRunner.run(new BoundedProcessRunner.Request(
                    BoundedProcessRunner.ProcessKind.SPOTBUGS,
                    java.util.List.of(
                            "spotbugs", "-maxHeap",
                            Integer.toString(budget.process().analyzerMaxHeapMb()),
                            "-textui", "-quiet", "-xml:withMessages", classesDir.toString()),
                    classesDir.getParent(),
                    budget.stages().spotbugs(),
                    budget.process().maxOutputBytes()));
            return switch (result.outcome()) {
                case TIMED_OUT -> SpotBugsAnalyzer.RunOutcome.TIMED_OUT;
                case CANCELLED -> SpotBugsAnalyzer.RunOutcome.CANCELLED;
                case OUTPUT_LIMIT_EXCEEDED -> SpotBugsAnalyzer.RunOutcome.OUTPUT_LIMIT_EXCEEDED;
                case START_FAILED -> SpotBugsAnalyzer.RunOutcome.UNAVAILABLE;
                case COMPLETED -> {
                    byte[] xml = result.output();
                    if (result.exitCode().orElse(-1) != 0) {
                        yield SpotBugsAnalyzer.RunOutcome.FAILED;
                    }
                    if (result.outputTruncated() || xml.length > budget.process().maxOutputBytes()) {
                        yield SpotBugsAnalyzer.RunOutcome.OUTPUT_LIMIT_EXCEEDED;
                    }
                    java.nio.file.Files.write(output, xml,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                    yield SpotBugsAnalyzer.RunOutcome.COMPLETED;
                }
            };
        };
    }

    @Bean
    public SpotBugsAnalyzer spotBugsAnalyzer(
            SpotBugsAnalyzer.Runner runner,
            SourceCompiler compiler,
            ReviewWorkspaceFactory workspaceFactory,
            ReviewWorkBudget budget) {
        return new SpotBugsAnalyzer(runner, compiler, workspaceFactory, budget);
    }
}
