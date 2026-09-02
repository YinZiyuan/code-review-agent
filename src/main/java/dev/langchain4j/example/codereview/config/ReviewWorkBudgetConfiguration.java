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

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class ReviewWorkBudgetConfiguration {

    @Bean
    ReviewWorkBudget reviewWorkBudget(ReviewWorkBudgetProperties properties) {
        return properties.toBudget();
    }

    @Bean
    PromptTokenizer promptTokenizer() {
        return new JTokkitPromptTokenizer();
    }

    @Bean
    ReviewPromptAssembler reviewPromptAssembler(
            PromptTokenizer tokenizer, ReviewWorkBudget budget) {
        return new ReviewPromptAssembler(tokenizer, budget);
    }

    @Bean(destroyMethod = "close")
    PipelineStageExecutor pipelineStageExecutor(MeterRegistry metrics) {
        return new PipelineStageExecutor(metrics);
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
