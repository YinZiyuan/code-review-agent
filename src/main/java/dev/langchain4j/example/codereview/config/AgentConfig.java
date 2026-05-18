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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
