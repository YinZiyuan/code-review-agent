package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.reporting.MarkdownReporter;
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

    private final CodeReviewAgent agent;
    private final MarkdownReporter reporter;

    public ReviewCommand(CodeReviewAgent agent, MarkdownReporter reporter) {
        this.agent = agent;
        this.reporter = reporter;
    }

    @Override
    public Integer call() {
        System.out.println("Repository : " + repoPath);
        System.out.println("Diff ref   : " + ref);

        String request = "Review code changes in repo: " + repoPath +
                "\nCompare against ref: " + ref +
                "\nCall getGitDiff first, then checkRules, then produce the ReviewResult.";
        ReviewResult result = agent.review(request);
        System.out.println("\n" + reporter.render(result));
        return 0;
    }
}
