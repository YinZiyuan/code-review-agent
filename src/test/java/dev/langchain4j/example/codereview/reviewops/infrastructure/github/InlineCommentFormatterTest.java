package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.PublicationFinding;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprint;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationDecision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InlineCommentFormatterTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private final InlineCommentFormatter formatter = new InlineCommentFormatter();

    @Test
    void appendsTheExactInvisibleFingerprintMarkerAndEscapesMarkdown() {
        PublicationFinding finding = inlineFinding(
                "[Unsafe](https://attacker.invalid)",
                "Use `raw` *carefully* <script>alert(1)</script>",
                "Evidence [here](https://attacker.invalid)",
                "Replace _this_ value");

        String body = formatter.format(finding);

        assertThat(body)
                .contains("\\[Unsafe\\]\\(https://attacker.invalid\\)")
                .contains("Use \\`raw\\` \\*carefully\\* &lt;script&gt;alert\\(1\\)&lt;/script&gt;")
                .contains("Evidence \\[here\\]\\(https://attacker.invalid\\)")
                .contains("Replace \\_this\\_ value")
                .endsWith("<!-- code-review-agent:fingerprint=" + FINGERPRINT + " -->");
    }

    @Test
    void boundsTheCommentWithoutTruncatingItsMarkerAndRedactsSecrets() {
        String token = "github_pat_abcdefghijklmnopqrstuvwxyz0123456789";
        PublicationFinding finding = inlineFinding(
                "Oversized " + token
                        + " sk-abcdefghijklmnopqrstuvwxyz0123456789"
                        + " -----BEGIN PRIVATE KEY-----\nprivate-material\n-----END PRIVATE KEY-----",
                "x".repeat(InlineCommentFormatter.MAX_BODY_CHARACTERS * 2),
                "Bearer " + token,
                "sk-abcdefghijklmnopqrstuvwxyz0123456789");

        String body = formatter.format(finding);

        assertThat(body).hasSizeLessThanOrEqualTo(InlineCommentFormatter.MAX_BODY_CHARACTERS);
        assertThat(body)
                .doesNotContain(token)
                .doesNotContain("sk-abcdefghijklmnopqrstuvwxyz0123456789")
                .doesNotContain("private-material")
                .endsWith("<!-- code-review-agent:fingerprint=" + FINGERPRINT + " -->");
    }

    @Test
    void rejectsFindingsThatAreNotEligibleForInlinePublication() {
        PublicationFinding summaryOnly = new PublicationFinding(
                new FindingFingerprint(FINGERPRINT),
                new CodeLocation("src/Foo.java", 12, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        "Issue",
                        "Description",
                        "Suggestion"),
                new FindingEvidence("Evidence", List.of(), "regex"),
                new PublicationDecision(PublicationTier.CHECK_SUMMARY, "policy-v1"),
                Optional.empty());

        assertThatThrownBy(() -> formatter.format(summaryOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("finding is not eligible for inline publication");
    }

    private static PublicationFinding inlineFinding(
            String title, String description, String evidence, String suggestion) {
        return new PublicationFinding(
                new FindingFingerprint(FINGERPRINT),
                new CodeLocation("src/Foo.java", 12, true),
                new FindingContent(
                        FindingSeverity.WARNING,
                        FindingCategory.STABILITY,
                        title,
                        description,
                        suggestion),
                new FindingEvidence(evidence, List.of(), "regex"),
                new PublicationDecision(PublicationTier.INLINE_COMMENT, "policy-v1"),
                Optional.empty());
    }
}
