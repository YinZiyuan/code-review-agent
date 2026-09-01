package dev.langchain4j.example.codereview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.analyzer.SourceCompiler;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import dev.langchain4j.example.codereview.eval.EvaluationRunner;
import dev.langchain4j.example.codereview.eval.LlmJudge;
import dev.langchain4j.example.codereview.eval.LlmJudgeImpl;
import dev.langchain4j.example.codereview.eval.Matcher;
import dev.langchain4j.example.codereview.eval.ModelRuntimeMetadata;
import dev.langchain4j.example.codereview.eval.ModelRuntimeMetadataResolver;
import dev.langchain4j.example.codereview.infra.JsonRepair;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

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
    public SourceCompiler sourceCompiler() {
        return new SourceCompiler();
    }

    @Bean
    public SpotBugsAnalyzer.Runner spotBugsRunner() {
        return (classesDir, output) -> {
            try {
                Process process = new ProcessBuilder(
                        "spotbugs", "-textui", "-quiet", "-xml", "-output", output.toString(),
                        classesDir.toString())
                        .redirectErrorStream(true)
                        .start();
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0 && Files.size(output) > 0;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }
}
