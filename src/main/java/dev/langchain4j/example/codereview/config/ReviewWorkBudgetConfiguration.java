package dev.langchain4j.example.codereview.config;

import dev.langchain4j.example.codereview.agents.pipeline.JTokkitPromptTokenizer;
import dev.langchain4j.example.codereview.agents.pipeline.PromptTokenizer;
import dev.langchain4j.example.codereview.agents.pipeline.PipelineStageExecutor;
import dev.langchain4j.example.codereview.agents.pipeline.ReviewPromptAssembler;
import dev.langchain4j.example.codereview.analyzer.BoundedProcessRunner;
import dev.langchain4j.example.codereview.workspace.ReviewWorkspaceFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class ReviewWorkBudgetConfiguration {

    @Bean
    ReviewWorkBudget reviewWorkBudget(
            ReviewWorkBudgetProperties properties, Environment environment) {
        String effectiveModel = environment.getProperty(
                "langchain4j.open-ai.chat-model.model-name", "gpt-5.6-sol");
        ReviewWorkBudget budget = properties.toBudget(effectiveModel);
        ReviewModelContextContract.verify(effectiveModel, budget.prompt());
        return budget;
    }

    @Bean
    PromptTokenizer promptTokenizer(ReviewWorkBudget budget) {
        return new JTokkitPromptTokenizer(
                budget.prompt().tokenizerId(), budget.prompt().tokenizerVersion());
    }

    @Bean
    ReviewPromptAssembler reviewPromptAssembler(
            PromptTokenizer tokenizer, ReviewWorkBudget budget) {
        return new ReviewPromptAssembler(tokenizer, budget);
    }

    @Bean(destroyMethod = "close")
    PipelineStageExecutor pipelineStageExecutor(
            MeterRegistry metrics, ReviewWorkBudget budget) {
        return new PipelineStageExecutor(
                metrics,
                budget.execution().stageWorkers(),
                budget.execution().stageQueueCapacity());
    }

    @Bean
    BoundedProcessRunner boundedProcessRunner(MeterRegistry metrics) {
        return new BoundedProcessRunner(metrics);
    }

    @Bean
    ReviewWorkspaceFactory reviewWorkspaceFactory() {
        return new ReviewWorkspaceFactory(Path.of(System.getProperty("java.io.tmpdir")));
    }

    @Bean
    ReviewWorkBudgetMetrics reviewWorkBudgetMetrics(
            ReviewWorkBudget budget, MeterRegistry metrics) {
        return new ReviewWorkBudgetMetrics(budget, metrics);
    }
}
