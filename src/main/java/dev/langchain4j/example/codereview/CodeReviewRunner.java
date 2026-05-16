package dev.langchain4j.example.codereview;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;

public class CodeReviewRunner {

    public static void main(String[] args) {
        // Default: review this project itself against HEAD~1
        String repoPath = args.length > 0 ? args[0] : System.getProperty("user.dir");
        String ref      = args.length > 1 ? args[1] : "HEAD~1";

        System.out.println("=".repeat(60));
        System.out.println("  Code Review Agent");
        System.out.println("=".repeat(60));
        System.out.println("Repository : " + repoPath);
        System.out.println("Diff ref   : " + ref);
        System.out.println("=".repeat(60));

        // 1. Chat model (Kimi)
        String apiKey = System.getenv("MOONSHOT_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: environment variable MOONSHOT_API_KEY is not set.");
            System.err.println("Run: export MOONSHOT_API_KEY=sk-xxxx");
            System.exit(1);
        }
        ChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl("https://api.moonshot.cn/v1")
                .apiKey(apiKey)
                .modelName("moonshot-v1-8k")
                .logRequests(false)
                .logResponses(false)
                .build();

        // 2. Knowledge base (RAG)
        ContentRetriever retriever = KnowledgeBaseLoader.load();

        // 3. Build agent
        CodeReviewAgent agent = AiServices.builder(CodeReviewAgent.class)
                .chatModel(chatModel)
                .tools(new GitDiffTool(), new RuleCheckerTool())
                .contentRetriever(retriever)
                .build();

        // 4. Run review
        String result = agent.review(
                "Please review the code changes in the git repository at: " + repoPath +
                "\nCompare against ref: " + ref +
                "\nWorkflow: first call getGitDiff, then call checkRules with the diff content, then write your full review."
        );

        System.out.println("\n" + result);
    }
}
