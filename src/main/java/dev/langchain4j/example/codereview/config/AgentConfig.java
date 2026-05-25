package dev.langchain4j.example.codereview.config;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.eval.EvaluationRunner;
import dev.langchain4j.example.codereview.eval.LlmJudge;
import dev.langchain4j.example.codereview.eval.LlmJudgeImpl;
import dev.langchain4j.example.codereview.eval.Matcher;
import dev.langchain4j.example.codereview.tools.GitDiffTool;
import dev.langchain4j.example.codereview.tools.RuleCheckerTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.analyzer.SourceCompiler;
import dev.langchain4j.example.codereview.analyzer.SpotBugsAnalyzer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@Configuration
public class AgentConfig {

    @Bean
    public CodeReviewAgent codeReviewAgent(
            ChatModel chatModel,
            ContentRetriever retriever,
            GitDiffTool gitDiffTool,
            RuleCheckerTool ruleCheckerTool) {
        return AiServices.builder(CodeReviewAgent.class)
                .chatModel(chatModel)
                .tools(gitDiffTool, ruleCheckerTool)
                .contentRetriever(retriever)
                .build();
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
    public EvaluationRunner evaluationRunner(CodeReviewAgent agent, Matcher matcher, ObjectMapper mapper) {
        return new EvaluationRunner(agent, matcher, mapper);
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
