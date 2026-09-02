package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

public final class GitHubWebhookVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final int SHA_256_HEX_LENGTH = 64;

    private final byte[] secret;

    public GitHubWebhookVerifier(byte[] secret) {
        if (secret == null || secret.length == 0
                || new String(secret, StandardCharsets.UTF_8).isBlank()) {
            throw new IllegalArgumentException("webhook secret must not be blank");
        }
        this.secret = secret.clone();
    }

    public boolean verify(byte[] payload, String signature) {
        if (payload == null || signature == null
                || !signature.startsWith(SIGNATURE_PREFIX)
                || signature.length() != SIGNATURE_PREFIX.length() + SHA_256_HEX_LENGTH) {
            return false;
        }

        byte[] suppliedDigest = decodeHex(signature, SIGNATURE_PREFIX.length());
        if (suppliedDigest == null) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return MessageDigest.isEqual(mac.doFinal(payload), suppliedDigest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static byte[] decodeHex(String value, int offset) {
        byte[] decoded = new byte[SHA_256_HEX_LENGTH / 2];
        for (int index = 0; index < decoded.length; index++) {
            int high = decodeAsciiHexDigit(value.charAt(offset + index * 2));
            int low = decodeAsciiHexDigit(value.charAt(offset + index * 2 + 1));
            if (high < 0 || low < 0) {
                return null;
            }
            decoded[index] = (byte) ((high << 4) | low);
        }
        return decoded;
    }

    private static int decodeAsciiHexDigit(char digit) {
        if (digit >= '0' && digit <= '9') {
            return digit - '0';
        }
        if (digit >= 'a' && digit <= 'f') {
            return digit - 'a' + 10;
        }
        if (digit >= 'A' && digit <= 'F') {
            return digit - 'A' + 10;
        }
        return -1;
    }
}
