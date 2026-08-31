package dev.langchain4j.example.codereview.reviewops.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class FindingFingerprintFactory {
    public FindingFingerprint create(CodeLocation location, FindingContent content, FindingEvidence evidence) {
        String canonical = location.file() + "\n"
                + content.category().name() + "\n"
                + normalizeText(content.title()) + "\n"
                + normalizeText(evidence.evidence());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return new FindingFingerprint(HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalizeText(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
