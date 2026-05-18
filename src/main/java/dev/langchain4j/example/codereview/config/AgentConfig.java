package dev.langchain4j.example.codereview.config;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.tools.GitDiffTool;
import dev.langchain4j.example.codereview.tools.RuleCheckerTool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
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
}
