package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.CheckOutcome;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.CheckRunRequest;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.PublicationFinding;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class CheckRunFormatter {

    public static final int MAX_SUMMARY_CHARACTERS = 4_096;
    public static final int MAX_TEXT_CHARACTERS = 60_000;

    public FormattedCheckRun format(CheckRunRequest request) {
        Objects.requireNonNull(request, "request");
        boolean systemFailure = request.presentation().outcome()
                == CheckOutcome.NEUTRAL_SYSTEM_FAILURE;
        String title = systemFailure
                ? "Code review system failure" : "Code review completed";
        String summary = InlineCommentFormatter.truncate(
                InlineCommentFormatter.safeMarkdown(request.presentation().safeSummary()),
                MAX_SUMMARY_CHARACTERS);
        String text = systemFailure
                ? "The review system could not publish a trustworthy result. No code comments were posted."
                : findingsText(request.findings());
        return new FormattedCheckRun(
                title,
                summary,
                InlineCommentFormatter.truncate(text, MAX_TEXT_CHARACTERS),
                request.presentation().status().name().toLowerCase(java.util.Locale.ROOT),
                request.presentation().conclusion().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String findingsText(List<PublicationFinding> findings) {
        if (findings.isEmpty()) {
            return "No findings were selected for publication.";
        }
        List<PublicationFinding> ordered = findings.stream()
                .sorted(Comparator
                        .comparingInt((PublicationFinding finding) ->
                                severityRank(finding.content().severity()))
                        .thenComparing(finding -> finding.location().file())
                        .thenComparingInt(finding -> finding.location().line())
                        .thenComparing(finding -> finding.fingerprint().value()))
                .toList();
        StringBuilder text = new StringBuilder("### Review findings\n");
        for (PublicationFinding finding : ordered) {
            text.append("\n- **")
                    .append(finding.content().severity().name())
                    .append(" · ")
                    .append(finding.content().category().name())
                    .append(" · ")
                    .append(InlineCommentFormatter.safeMarkdown(finding.content().title()))
                    .append("** — `")
                    .append(InlineCommentFormatter.safeMarkdown(finding.location().file()))
                    .append(':')
                    .append(finding.location().line())
                    .append("`\n  ")
                    .append(InlineCommentFormatter.safeMarkdown(
                            finding.content().description()))
                    .append("\n  Evidence: ")
                    .append(InlineCommentFormatter.safeMarkdown(
                            finding.evidence().evidence()))
                    .append("\n  Suggestion: ")
                    .append(InlineCommentFormatter.safeMarkdown(
                            finding.content().suggestion()))
                    .append('\n');
        }
        return text.toString().stripTrailing();
    }

    private static int severityRank(FindingSeverity severity) {
        return switch (severity) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case SUGGESTION -> 2;
        };
    }

    public record FormattedCheckRun(
            String title,
            String summary,
            String text,
            String status,
            String conclusion) {

        public FormattedCheckRun {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(conclusion, "conclusion");
        }
    }
}
