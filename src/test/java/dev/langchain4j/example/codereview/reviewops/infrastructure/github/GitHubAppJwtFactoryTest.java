package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GitHubAppJwtFactoryTest {

    private static final long APP_ID = 12_345L;
    private static final Instant NOW = Instant.parse("2026-09-01T08:15:30Z");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static KeyPair keyPair;
    private static String privateKeyPem;

    @BeforeAll
    static void generateEphemeralRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    @Test
    void createsAnIndependentlyVerifiableShortLivedRs256AppJwt() throws Exception {
        GitHubAppJwtFactory factory = new GitHubAppJwtFactory(
                APP_ID, privateKeyPem, OBJECT_MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));

        String jwt = factory.create();

        String[] segments = jwt.split("\\.", -1);
        assertThat(segments).hasSize(3);

        JsonNode header = decodeJson(segments[0]);
        JsonNode claims = decodeJson(segments[1]);
        assertThat(header.path("alg").textValue()).isEqualTo("RS256");
        assertThat(header.path("typ").textValue()).isEqualTo("JWT");
        assertThat(claims.path("iss").isIntegralNumber()).isTrue();
        assertThat(claims.path("iss").longValue()).isEqualTo(APP_ID);
        assertThat(claims.path("iat").longValue()).isEqualTo(NOW.minusSeconds(60).getEpochSecond());
        assertThat(claims.path("exp").longValue())
                .isGreaterThan(claims.path("iat").longValue())
                .isLessThanOrEqualTo(claims.path("iat").longValue() + 600);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(segments[2]))).isTrue();
    }

    @Test
    void acceptsPkcs8PemWithWindowsLineEndings() {
        String windowsPem = privateKeyPem.replace("\n", "\r\n");
        GitHubAppJwtFactory factory = new GitHubAppJwtFactory(
                APP_ID, windowsPem, OBJECT_MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(factory.create()).hasSizeGreaterThan(100);
    }

    @Test
    void rejectsPkcs1LabelsWithoutEchoingPrivateKeyMaterial() {
        String marker = "private-material-must-not-leak";
        String pkcs1Pem = "-----BEGIN RSA PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(marker.getBytes(StandardCharsets.UTF_8))
                + "\n-----END RSA PRIVATE KEY-----";

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GitHubAppJwtFactory(
                        APP_ID, pkcs1Pem, OBJECT_MAPPER, Clock.fixed(NOW, ZoneOffset.UTC)))
                .withMessage("GitHub App private key must be PKCS#8 PEM")
                .satisfies(exception -> assertThat(exception.toString()).doesNotContain(marker));
    }

    @Test
    void malformedPkcs8ErrorsAndFactoryTextDoNotExposePrivateKeyMaterial() {
        String marker = "invalid-private-key-material";
        String malformed = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(marker.getBytes(StandardCharsets.UTF_8))
                + "\n-----END PRIVATE KEY-----";

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GitHubAppJwtFactory(
                        APP_ID, malformed, OBJECT_MAPPER, Clock.fixed(NOW, ZoneOffset.UTC)))
                .withMessage("GitHub App private key is invalid")
                .satisfies(exception -> assertThat(exception.toString()).doesNotContain(marker));

        GitHubAppJwtFactory factory = new GitHubAppJwtFactory(
                APP_ID, privateKeyPem, OBJECT_MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(factory.toString()).doesNotContain(privateKeyPem);
    }

    private static JsonNode decodeJson(String segment) throws Exception {
        return OBJECT_MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
    }
}
