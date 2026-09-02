package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Removes credential-shaped material before review content crosses the publication boundary. */
final class PublicationSecretRedactor {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [^-\\r\\n]*PRIVATE KEY-----.*?-----END [^-\\r\\n]*PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/%=-]+");
    private static final Pattern CREDENTIAL_URI = Pattern.compile(
            "(?i)\\b((?:jdbc:)?[a-z][a-z0-9+.-]*://[^\\s:/@]+:)([^\\s/@]+)(@)");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?(?:"
                    + "aws[_-]?(?:secret[_-]?access[_-]?key|session[_-]?token)"
                    + "|secret[_-]?access[_-]?key"
                    + "|webhook[_-]?(?:secret|token)"
                    + "|database[_-]?(?:url|password)"
                    + "|db[_-]?(?:url|password)"
                    + "|github[_-]?(?:token|pat)"
                    + "|model[_-]?(?:token|api[_-]?key)"
                    + "|openai[_-]?api[_-]?key"
                    + "|anthropic[_-]?api[_-]?key"
                    + "|client[_-]?secret"
                    + "|access[_-]?(?:token|key(?:[_-]?id)?)"
                    + "|api[_-]?key"
                    + "|password|passwd|pwd|secret|token"
                    + ")[\\\"']?\\s*[:=]\\s*)"
                    + "(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\""
                    + "|'(?:\\\\.|[^'\\\\])*'"
                    + "|[^\\s,;&}\\]]+)");
    private static final Pattern GITHUB_TOKEN = Pattern.compile(
            "(?i)\\b(?:github_pat_[A-Za-z0-9_]+|gh[psuor]_[A-Za-z0-9]+)");
    private static final Pattern MODEL_TOKEN = Pattern.compile(
            "(?i)\\bsk-[A-Za-z0-9_-]{10,}");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile(
            "\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b");
    private static final Pattern SLACK_TOKEN = Pattern.compile(
            "(?i)\\bxox[baprs]-[A-Za-z0-9-]{10,}");
    private static final Pattern STRIPE_TOKEN = Pattern.compile(
            "(?i)\\b(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{10,}");
    private static final Pattern OTHER_PROVIDER_TOKEN = Pattern.compile(
            "(?i)\\b(?:npm_[A-Za-z0-9]{20,}|glpat-[A-Za-z0-9_-]{10,}"
                    + "|hf_[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{20,})");

    private PublicationSecretRedactor() {
    }

    static String redact(String value) {
        String redacted = Objects.requireNonNull(value, "value");
        redacted = PRIVATE_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = AUTHORIZATION.matcher(redacted)
                .replaceAll(match -> match.group(1) + " " + REDACTED);
        redacted = CREDENTIAL_URI.matcher(redacted)
                .replaceAll(match -> match.group(1) + REDACTED + match.group(3));
        redacted = redactAssignments(redacted);
        redacted = GITHUB_TOKEN.matcher(redacted).replaceAll(REDACTED);
        redacted = MODEL_TOKEN.matcher(redacted).replaceAll(REDACTED);
        redacted = AWS_ACCESS_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = SLACK_TOKEN.matcher(redacted).replaceAll(REDACTED);
        redacted = STRIPE_TOKEN.matcher(redacted).replaceAll(REDACTED);
        return OTHER_PROVIDER_TOKEN.matcher(redacted).replaceAll(REDACTED);
    }

    private static String redactAssignments(String value) {
        Matcher matcher = CREDENTIAL_ASSIGNMENT.matcher(value);
        StringBuffer result = new StringBuffer(value.length());
        while (matcher.find()) {
            String wholeMatch = matcher.group();
            String prefix = matcher.group(1);
            String assignedValue = wholeMatch.substring(prefix.length());
            String replacement = prefix + quotedRedaction(assignedValue);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String quotedRedaction(String assignedValue) {
        if (assignedValue.length() >= 2) {
            char quote = assignedValue.charAt(0);
            if ((quote == '\'' || quote == '\"')
                    && assignedValue.charAt(assignedValue.length() - 1) == quote) {
                return quote + REDACTED + quote;
            }
        }
        return REDACTED;
    }
}
