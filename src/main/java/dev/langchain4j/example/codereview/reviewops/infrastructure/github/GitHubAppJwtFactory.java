package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class GitHubAppJwtFactory {

    private static final String BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----";
    private static final String END_PRIVATE_KEY = "-----END PRIVATE KEY-----";
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final long appId;
    private final PrivateKey privateKey;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GitHubAppJwtFactory(long appId, String privateKeyPem, ObjectMapper objectMapper, Clock clock) {
        if (appId <= 0) {
            throw new IllegalArgumentException("GitHub App id must be positive");
        }
        this.appId = appId;
        this.privateKey = parsePkcs8PrivateKey(privateKeyPem);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String create() {
        Instant now = clock.instant();
        long issuedAt = now.minusSeconds(60).getEpochSecond();
        long expiresAt = now.plusSeconds(540).getEpochSecond();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iat", issuedAt);
        claims.put("exp", expiresAt);
        claims.put("iss", appId);

        String signingInput = encodeJson(header) + "." + encodeJson(claims);
        return signingInput + "." + sign(signingInput);
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return BASE64_URL.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode GitHub App JWT", exception);
        }
    }

    private String sign(String signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return BASE64_URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not sign GitHub App JWT", exception);
        }
    }

    private static PrivateKey parsePkcs8PrivateKey(String privateKeyPem) {
        if (privateKeyPem == null) {
            throw new IllegalArgumentException("GitHub App private key must be PKCS#8 PEM");
        }
        String normalized = privateKeyPem.replace("\r\n", "\n").trim();
        if (!normalized.startsWith(BEGIN_PRIVATE_KEY + "\n")
                || !normalized.endsWith("\n" + END_PRIVATE_KEY)
                || normalized.indexOf(BEGIN_PRIVATE_KEY, BEGIN_PRIVATE_KEY.length()) >= 0
                || normalized.substring(0, normalized.length() - END_PRIVATE_KEY.length())
                .contains(END_PRIVATE_KEY)) {
            throw new IllegalArgumentException("GitHub App private key must be PKCS#8 PEM");
        }

        String encoded = normalized.substring(
                BEGIN_PRIVATE_KEY.length(), normalized.length() - END_PRIVATE_KEY.length())
                .replaceAll("\\s", "");
        byte[] keyBytes = null;
        try {
            keyBytes = Base64.getDecoder().decode(encoded);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("GitHub App private key is invalid");
        } finally {
            if (keyBytes != null) {
                Arrays.fill(keyBytes, (byte) 0);
            }
        }
    }

    @Override
    public String toString() {
        return "GitHubAppJwtFactory[appId=" + appId + "]";
    }
}
