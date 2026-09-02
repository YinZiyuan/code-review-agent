package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway.PublicationFinding;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationTier;

import java.util.Objects;

public final class InlineCommentFormatter {

    public static final int MAX_BODY_CHARACTERS = 16_000;
    private static final String MARKER_PREFIX = "<!-- code-review-agent:fingerprint=";
    private static final String MARKDOWN_SPECIAL = "\\`*_{}[]()#+-!|";

    public String format(PublicationFinding finding) {
        Objects.requireNonNull(finding, "finding");
        if (!finding.location().changedLine()
                || finding.decision().tier() != PublicationTier.INLINE_COMMENT) {
            throw new IllegalArgumentException(
                    "finding is not eligible for inline publication");
        }

        String marker = marker(finding.fingerprint().value());
        String content = "**%s · %s · %s**\n\n%s\n\n**Evidence:** %s\n\n**Suggestion:** %s"
                .formatted(
                        finding.content().severity().name(),
                        finding.content().category().name(),
                        safeMarkdown(finding.content().title()),
                        safeMarkdown(finding.content().description()),
                        safeMarkdown(finding.evidence().evidence()),
                        safeMarkdown(finding.content().suggestion()));
        int contentLimit = MAX_BODY_CHARACTERS - marker.length() - 2;
        return truncate(content, contentLimit) + "\n\n" + marker;
    }

    static String marker(String fingerprint) {
        return MARKER_PREFIX + fingerprint + " -->";
    }

    static String safeMarkdown(String value) {
        String redacted = PublicationSecretRedactor.redact(value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        StringBuilder escaped = new StringBuilder(redacted.length());
        for (int index = 0; index < redacted.length(); index++) {
            char character = redacted.charAt(index);
            if (MARKDOWN_SPECIAL.indexOf(character) >= 0) {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    static String truncate(String value, int maxCharacters) {
        if (maxCharacters < 0) {
            throw new IllegalArgumentException("maxCharacters must not be negative");
        }
        if (value.length() <= maxCharacters) {
            return value;
        }
        if (maxCharacters == 0) {
            return "";
        }
        if (maxCharacters == 1) {
            return "…";
        }
        return value.substring(0, maxCharacters - 1) + "…";
    }
}
