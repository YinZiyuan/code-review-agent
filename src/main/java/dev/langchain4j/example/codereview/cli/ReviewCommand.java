package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.CodeReviewAgent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Component
@Command(name = "review", description = "Review a git diff")
public class ReviewCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to git repository", defaultValue = ".")
    private String repoPath;

    @Parameters(index = "1", description = "Git ref to diff against", defaultValue = "HEAD~1")
    private String ref;

    private final ObjectProvider<CodeReviewAgent> agentProvider;

    public ReviewCommand(ObjectProvider<CodeReviewAgent> agentProvider) {
        this.agentProvider = agentProvider;
    }

    @Override
    public Integer call() {
        CodeReviewAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            System.err.println("CodeReviewAgent bean not wired yet (lands in T11).");
            return 2;
        }
        System.out.println("Repository : " + repoPath);
        System.out.println("Diff ref   : " + ref);

        String request = "Review code changes in repo: " + repoPath +
                "\nCompare against ref: " + ref +
                "\nCall getGitDiff first, then checkRules, then produce the review.";
        // CodeReviewAgent still returns String until T15 swaps it to ReviewResult.
        String result = agent.review(request);
        System.out.println("\n" + result);
        return 0;
    }
}
