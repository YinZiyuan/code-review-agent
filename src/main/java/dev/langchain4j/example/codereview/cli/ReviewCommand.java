package dev.langchain4j.example.codereview.cli;

import dev.langchain4j.example.codereview.agents.CodeReviewAgent;
import dev.langchain4j.example.codereview.model.ReviewResult;
import dev.langchain4j.example.codereview.reporting.MarkdownReporter;
import dev.langchain4j.example.codereview.tools.GitDiffTool;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
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
    private final GitDiffTool gitDiffTool;

    public ReviewCommand(CodeReviewAgent agent, MarkdownReporter reporter, GitDiffTool gitDiffTool) {
        this.agent = agent;
        this.reporter = reporter;
        this.gitDiffTool = gitDiffTool;
    }

    @Override
    public Integer call() {
        String effectiveRepo = repoPath == null || repoPath.isBlank() ? "." : repoPath;
        String effectiveRef = ref == null || ref.isBlank() ? "HEAD~1" : ref;
        System.out.println("Repository : " + effectiveRepo);
        System.out.println("Diff ref   : " + effectiveRef);

        String diff = gitDiffTool.getGitDiff(effectiveRepo, effectiveRef);
        String request = "Review the following diff. The full diff is below; do not call git tools.\n\n"
                + diff;
        ReviewResult result = agent.review(request, Path.of(effectiveRepo).toAbsolutePath());
        System.out.println("\n" + reporter.render(result));
        return 0;
    }
}
