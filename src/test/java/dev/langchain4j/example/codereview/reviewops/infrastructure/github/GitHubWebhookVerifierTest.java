package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GitHubWebhookVerifierTest {

    private static final byte[] SECRET = "webhook-test-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYLOAD = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsTheHmacSha256OfTheExactRawPayloadBytes() throws Exception {
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier(SECRET);

        assertThat(verifier.verify(PAYLOAD, signatureFor(SECRET, PAYLOAD))).isTrue();
    }

    @Test
    void rejectsASignatureProducedWithAnotherSecret() throws Exception {
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier(SECRET);
        byte[] otherSecret = "another-webhook-secret".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(PAYLOAD, signatureFor(otherSecret, PAYLOAD))).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "sha1=7369676e6174757265",
            "SHA256=0000000000000000000000000000000000000000000000000000000000000000",
            "sha256=",
            "sha256=00",
            "sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeg",
            "sha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef00"
    })
    void rejectsMissingOrMalformedSignatureHeaders(String signature) {
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier(SECRET);

        assertThat(verifier.verify(PAYLOAD, signature)).isFalse();
    }

    @Test
    void acceptsUppercaseHexDigitsAfterTheRequiredLowercasePrefix() throws Exception {
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier(SECRET);
        String signature = signatureFor(SECRET, PAYLOAD).toUpperCase();
        signature = "sha256=" + signature.substring("SHA256=".length());

        assertThat(verifier.verify(PAYLOAD, signature)).isTrue();
    }

    @Test
    void rejectsNonAsciiHexLookalikes() throws Exception {
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier(SECRET);
        String asciiSignature = signatureFor(SECRET, PAYLOAD);
        StringBuilder lookalike = new StringBuilder("sha256=");
        for (char digit : asciiSignature.substring("sha256=".length()).toCharArray()) {
            if (digit >= '0' && digit <= '9') {
                lookalike.append((char) ('\uff10' + digit - '0'));
            } else {
                lookalike.append((char) ('\uff41' + digit - 'a'));
            }
        }

        assertThat(verifier.verify(PAYLOAD, lookalike.toString())).isFalse();
    }

    @Test
    void rejectsTheSameJsonTextWhenItsRawBytesDiffer() throws Exception {
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier(SECRET);
        byte[] payloadWithTrailingNewline = "{\"action\":\"opened\"}\n".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(payloadWithTrailingNewline, signatureFor(SECRET, PAYLOAD))).isFalse();
    }

    @Test
    void clonesTheSecretPassedToTheConstructor() throws Exception {
        byte[] mutableSecret = SECRET.clone();
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier(mutableSecret);
        mutableSecret[0] ^= 0x01;

        assertThat(verifier.verify(PAYLOAD, signatureFor(SECRET, PAYLOAD))).isTrue();
        assertThat(verifier.toString()).doesNotContain(new String(SECRET, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsNullPayloadWithoutThrowingOrFormattingSensitiveData() {
        GitHubWebhookVerifier verifier = new GitHubWebhookVerifier(SECRET);

        assertThat(verifier.verify(null, "sha256=" + "00".repeat(32))).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n"})
    void requiresANonblankSecret(String secret) {
        byte[] secretBytes = secret == null ? null : secret.getBytes(StandardCharsets.UTF_8);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GitHubWebhookVerifier(secretBytes))
                .withMessage("webhook secret must not be blank");
    }

    private static String signatureFor(byte[] secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
