package dev.langchain4j.example.codereview.reviewops.domain;

public record FindingFingerprint(String value) {
    public FindingFingerprint {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint must be lowercase SHA-256 hex");
        }
    }
}
