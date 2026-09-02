package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.CheckPresentation;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.CheckRunRequest;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.PublicationFinding;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckRunFormatterTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final ReviewRunId RUN_ID = new ReviewRunId(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    private final CheckRunFormatter formatter = new CheckRunFormatter();

    @Test
    void formatsFindingsInAStableSeverityLocationAndFingerprintOrder() {
        PublicationFinding laterWarning = finding(
                "c".repeat(64), FindingSeverity.WARNING, "src/Zed.java", 20,
                "Later", "Later description", "Later evidence", "Later suggestion");
        PublicationFinding critical = finding(
                "b".repeat(64), FindingSeverity.CRITICAL, "src/Beta.java", 9,
                "Critical", "Critical description", "Critical evidence", "Critical suggestion");
        PublicationFinding earlierWarning = finding(
                "a".repeat(64), FindingSeverity.WARNING, "src/Alpha.java", 10,
                "Earlier", "Earlier description", "Earlier evidence", "Earlier suggestion");

        CheckRunFormatter.FormattedCheckRun formatted = formatter.format(request(
                CheckPresentation.success("Review completed with 3 findings."),
                List.of(laterWarning, critical, earlierWarning)));

        assertThat(formatted.status()).isEqualTo("completed");
        assertThat(formatted.conclusion()).isEqualTo("success");
        assertThat(formatted.title()).isEqualTo("Code review completed");
        assertThat(formatted.summary()).isEqualTo("Review completed with 3 findings.");
        assertThat(formatted.text())
                .containsSubsequence("Critical", "Earlier", "Later")
                .contains("src/Alpha.java:10")
                .contains("src/Zed.java:20");
    }

    @Test
    void boundsSummaryAndTextAfterEscapingAndRedactsSecretShapedValues() {
        String token = "ghs_abcdefghijklmnopqrstuvwxyz0123456789";
        PublicationFinding oversized = finding(
                "d".repeat(64), FindingSeverity.WARNING, "src/[Unsafe](target).java", 11,
                "[link](https://attacker.invalid) " + token
                        + " -----BEGIN PRIVATE KEY-----\nprivate-material\n-----END PRIVATE KEY-----",
                "*".repeat(CheckRunFormatter.MAX_TEXT_CHARACTERS * 2),
                "Bearer " + token,
                "Suggestion");

        CheckRunFormatter.FormattedCheckRun formatted = formatter.format(request(
                CheckPresentation.success("summary " + "_".repeat(4088)),
                List.of(oversized)));

        assertThat(formatted.summary()).hasSizeLessThanOrEqualTo(
                CheckRunFormatter.MAX_SUMMARY_CHARACTERS);
        assertThat(formatted.text()).hasSizeLessThanOrEqualTo(
                CheckRunFormatter.MAX_TEXT_CHARACTERS);
        assertThat(formatted.text())
                .contains("\\[link\\]\\(https://attacker.invalid\\)")
                .contains("src/\\[Unsafe\\]\\(target\\).java:11")
                .doesNotContain(token)
                .doesNotContain("private-material")
                .doesNotContain("BEGIN PRIVATE KEY");
    }

    @Test
    void formatsNoFindingsAsSuccessfulAndSystemFailureAsNeutralWithoutFindingText() {
        CheckRunFormatter.FormattedCheckRun noFindings = formatter.format(request(
                CheckPresentation.success("Review completed with no findings."), List.of()));
        CheckRunFormatter.FormattedCheckRun failure = formatter.format(request(
                CheckPresentation.neutralSystemFailure(
                        "Review publication could not finish safely."), List.of()));

        assertThat(noFindings.title()).isEqualTo("Code review completed");
        assertThat(noFindings.conclusion()).isEqualTo("success");
        assertThat(noFindings.text()).isEqualTo("No findings were selected for publication.");
        assertThat(failure.title()).isEqualTo("Code review system failure");
        assertThat(failure.status()).isEqualTo("completed");
        assertThat(failure.conclusion()).isEqualTo("neutral");
        assertThat(failure.summary()).isEqualTo("Review publication could not finish safely.");
        assertThat(failure.text()).isEqualTo(
                "The review system could not publish a trustworthy result. No code comments were posted.");
    }

    private static CheckRunRequest request(
            CheckPresentation presentation, List<PublicationFinding> findings) {
        return new CheckRunRequest(
                RUN_ID,
                new PullRequestRevision(41, 73, 12, SHA),
                presentation,
                findings,
                Optional.empty());
    }

    private static PublicationFinding finding(
            String fingerprint,
            FindingSeverity severity,
            String file,
            int line,
            String title,
            String description,
            String evidence,
            String suggestion) {
        return new PublicationFinding(
                new FindingFingerprint(fingerprint),
                new CodeLocation(file, line, true),
                new FindingContent(
                        severity, FindingCategory.STABILITY, title, description, suggestion),
                new FindingEvidence(evidence, List.of(), "regex"),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "policy-v1"),
                Optional.empty());
    }
}
