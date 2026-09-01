package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.PreparedReviewSource;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.assertj.core.api.ThrowableAssert;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class GitHubArchiveSourceProviderTest {

    private static final String API_BASE_URL = "https://api.github.test";
    private static final String INSTALLATION_TOKEN = "installation-source-token";
    private static final String HEAD_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String OTHER_SHA = "fedcba9876543210fedcba9876543210fedcba98";
    private static final String DIFF = """
            diff --git a/src/Main.java b/src/Main.java
            index 1111111..2222222 100644
            --- a/src/Main.java
            +++ b/src/Main.java
            @@ -1 +1 @@
            -class Main {}
            +class Main { int answer = 42; }
            """;
    private static final Instant NOW = Instant.parse("2026-09-01T09:00:00Z");
    private static final PullRequestRevision REVISION =
            new PullRequestRevision(41L, 73L, 12, HEAD_SHA);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static String privateKeyPem;

    @TempDir
    Path tempParent;

    @BeforeAll
    static void createPrivateKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var privateKey = generator.generateKeyPair().getPrivate();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    @Test
    void preparesTheValidatedExactRevisionUnderAFreshRootAndCloseDeletesIt() throws Exception {
        Remote remote = remote();
        byte[] archive = zip(Map.of(
                "octo-repo-0123456/src/Main.java", "class Main { int answer = 42; }",
                "octo-repo-0123456/README.md", "review fixture"));
        expectSuccessfulSource(remote.server(), DIFF, archive);
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 65_536, 16_384, 10);

        PreparedReviewSource prepared = provider.prepare(REVISION);
        Path sourceRoot = prepared.sourceRoot();
        Path cleanupRoot = sourceRoot.getParent();

        assertThat(prepared.diffPatch()).isEqualTo(DIFF);
        assertThat(sourceRoot).startsWith(tempParent).isNotEqualTo(tempParent);
        assertThat(Files.readString(sourceRoot.resolve("src/Main.java")))
                .isEqualTo("class Main { int answer = 42; }");
        assertThat(Files.readString(sourceRoot.resolve("README.md"))).isEqualTo("review fixture");
        assertThat(sourceRoot.resolve("octo-repo-0123456")).doesNotExist();
        assertThat(Files.isSymbolicLink(sourceRoot.resolve("src/Main.java"))).isFalse();

        prepared.close();
        prepared.close();

        assertThat(cleanupRoot).doesNotExist();
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @Test
    void refusesARevisionWhoseAuthoritativeHeadDoesNotEqualTheRequestedSha() throws Exception {
        Remote remote = remote();
        expectToken(remote.server());
        expectHead(remote.server(), OTHER_SHA);
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 65_536, 16_384, 10);

        assertDeterministicFailure(
                () -> provider.prepare(REVISION),
                "GitHub pull request head does not match requested revision");
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @Test
    void validatesTheHeadAgainAfterDownloadingSoAConcurrentUpdateCannotMixDiffAndSource() throws Exception {
        Remote remote = remote();
        byte[] archive = zip(Map.of("root/file.txt", "content"));
        expectToken(remote.server());
        expectHead(remote.server(), HEAD_SHA);
        expectDiff(remote.server(), DIFF);
        expectArchive(remote.server(), archive);
        expectHead(remote.server(), OTHER_SHA);
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 65_536, 16_384, 10);

        assertDeterministicFailure(
                () -> provider.prepare(REVISION),
                "GitHub pull request head does not match requested revision");
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @Test
    void rejectsBranchNamesAndAbbreviatedShasBeforeMakingAnyRequest() {
        Remote remote = remote();
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 65_536, 16_384, 10);
        PullRequestRevision branchRevision = new PullRequestRevision(41L, 73L, 12, "main");

        assertDeterministicFailure(
                () -> provider.prepare(branchRevision),
                "headSha must be a full hexadecimal commit SHA");
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "root/../escaped.txt",
            "../escaped.txt",
            "/absolute.txt",
            "C:\\absolute.txt",
            "root\\windows-escape.txt"
    })
    void rejectsTraversalAndAbsoluteArchiveEntriesAndCleansPartialExtraction(String maliciousEntry)
            throws Exception {
        Remote remote = remote();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("root/safe.txt", "created before rejection");
        entries.put(maliciousEntry, "must not escape");
        expectSuccessfulSource(remote.server(), DIFF, zip(entries));
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 65_536, 16_384, 10);

        assertDeterministicFailure(
                () -> provider.prepare(REVISION),
                "GitHub archive contains an unsafe entry");
        assertThat(tempParent).isEmptyDirectory();
        assertThat(tempParent.resolve("escaped.txt")).doesNotExist();
        remote.server().verify();
    }

    @Test
    void rejectsLeadingTraversalEvenWhenItIsTheOnlyArchiveEntry() throws Exception {
        Remote remote = remote();
        expectSuccessfulSource(remote.server(), DIFF, zip(Map.of("../escaped.txt", "must not escape")));
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 65_536, 16_384, 10);

        assertDeterministicFailure(
                () -> provider.prepare(REVISION),
                "GitHub archive contains an unsafe entry");
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @Test
    void rejectsUnixSymlinkEntriesInsteadOfMaterializingTheirTargets() throws Exception {
        Remote remote = remote();
        byte[] archive = zip(Map.of(
                "root/target.txt", "safe",
                "root/link", "target.txt"));
        markAsUnixSymlink(archive, "root/link");
        expectSuccessfulSource(remote.server(), DIFF, archive);
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 65_536, 16_384, 10);

        assertDeterministicFailure(
                () -> provider.prepare(REVISION),
                "GitHub archive contains an unsafe entry");
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @Test
    void enforcesTheExpandedByteLimitAndCleansFilesWrittenBeforeTheLimit() throws Exception {
        Remote remote = remote();
        expectSuccessfulSource(remote.server(), DIFF, zip(Map.of(
                "root/a.txt", "12345",
                "root/b.txt", "67890")));
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 65_536, 9, 10);

        assertDeterministicFailure(
                () -> provider.prepare(REVISION),
                "GitHub archive expanded size limit exceeded");
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @Test
    void enforcesTheArchiveEntryCountLimitAndCleansPartialExtraction() throws Exception {
        Remote remote = remote();
        expectSuccessfulSource(remote.server(), DIFF, zip(Map.of(
                "root/a.txt", "a",
                "root/b.txt", "b")));
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 65_536, 16_384, 1);

        assertDeterministicFailure(
                () -> provider.prepare(REVISION),
                "GitHub archive entry count limit exceeded");
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @Test
    void topLevelDirectoryMarkerDoesNotConsumeTheFileEntryBudget() throws Exception {
        Remote remote = remote();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("root/", null);
        entries.put("root/only.txt", "one file");
        expectSuccessfulSource(remote.server(), DIFF, zip(entries));
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 65_536, 16_384, 1);

        try (PreparedReviewSource prepared = provider.prepare(REVISION)) {
            assertThat(Files.readString(prepared.sourceRoot().resolve("only.txt")))
                    .isEqualTo("one file");
        }
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @Test
    void boundsArchiveDownloadBeforeExtractionAndRedactsTheBearerAndResponseBody(
            CapturedOutput output) throws Exception {
        String responseSecret = "response-secret-must-not-leak";
        Remote remote = remote();
        expectToken(remote.server());
        expectHead(remote.server(), HEAD_SHA);
        expectDiff(remote.server(), DIFF);
        remote.server().expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/zipball/" + HEAD_SHA))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + INSTALLATION_TOKEN))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(responseSecret.repeat(10)));
        GitHubArchiveSourceProvider provider = provider(remote.client(), 16_384, 32, 16_384, 10);

        assertThatThrownBy(() -> provider.prepare(REVISION))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.DETERMINISTIC_INPUT);
                    assertThat(exception).hasMessage("GitHub archive download size limit exceeded");
                    assertThat(exception.toString())
                            .doesNotContain(responseSecret)
                            .doesNotContain(INSTALLATION_TOKEN);
                });
        assertThat(output.getAll())
                .doesNotContain(responseSecret)
                .doesNotContain(INSTALLATION_TOKEN);
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @Test
    void boundsTheDiffDownloadAndDoesNotCreateTemporaryState() throws Exception {
        Remote remote = remote();
        expectToken(remote.server());
        expectHead(remote.server(), HEAD_SHA);
        expectDiff(remote.server(), DIFF);
        GitHubArchiveSourceProvider provider = provider(remote.client(), 32, 65_536, 16_384, 10);

        assertDeterministicFailure(
                () -> provider.prepare(REVISION),
                "GitHub pull request diff size limit exceeded");
        assertThat(tempParent).isEmptyDirectory();
        remote.server().verify();
    }

    @Test
    void followsTheGitHubArchiveRedirectWithoutForwardingInstallationCredentials() throws Exception {
        byte[] archive = zip(Map.of("root/file.txt", "content"));
        String downloadUrl = "https://codeload.github.com/octo/repo/legacy.zip/" + HEAD_SHA;
        Remote remote = remote();
        expectToken(remote.server());
        expectArchiveRedirect(remote.server(), downloadUrl);
        remote.server().expect(once(), requestTo(downloadUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(GitHubArchiveSourceProviderTest::assertNoAuthorization)
                .andRespond(withSuccess(archive, MediaType.APPLICATION_OCTET_STREAM));

        assertThat(remote.client().repositoryArchive(REVISION, 65_536)).isEqualTo(archive);
        remote.server().verify();
    }

    @Test
    void rejectsAnArchiveRedirectWithoutALocation() {
        Remote remote = remote();
        expectToken(remote.server());
        remote.server().expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/zipball/" + HEAD_SHA))
                .andRespond(withStatus(HttpStatus.FOUND));

        assertThatThrownBy(() -> remote.client().repositoryArchive(REVISION, 65_536))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.DETERMINISTIC_INPUT);
                    assertThat(exception).hasMessage("GitHub archive redirect was invalid");
                });
        remote.server().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://attacker.invalid/archive.zip",
            "http://codeload.github.com/octo/repo/archive.zip",
            "https://user@codeload.github.com/octo/repo/archive.zip",
            "https://codeload.github.com:8443/octo/repo/archive.zip"
    })
    void rejectsUntrustedArchiveRedirectLocations(String location) {
        Remote remote = remote();
        expectToken(remote.server());
        expectArchiveRedirect(remote.server(), location);

        assertThatThrownBy(() -> remote.client().repositoryArchive(REVISION, 65_536))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.DETERMINISTIC_INPUT);
                    assertThat(exception).hasMessage("GitHub archive redirect was invalid");
                });
        remote.server().verify();
    }

    @Test
    void boundsArchiveRedirectLoopsAndNeverForwardsCredentials() {
        String loopUrl = "https://codeload.github.com/octo/repo/loop.zip";
        Remote remote = remote();
        expectToken(remote.server());
        expectArchiveRedirect(remote.server(), loopUrl);
        remote.server().expect(times(3), requestTo(loopUrl))
                .andExpect(method(HttpMethod.GET))
                .andExpect(GitHubArchiveSourceProviderTest::assertNoAuthorization)
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, loopUrl));

        assertThatThrownBy(() -> remote.client().repositoryArchive(REVISION, 65_536))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.TRANSIENT);
                    assertThat(exception).hasMessage("GitHub archive redirect limit exceeded");
                });
        remote.server().verify();
    }

    @Test
    void enforcesTheArchiveByteCapOnTheCredentialFreeRedirectedResponse() {
        String responseSecret = "redirected-archive-secret";
        String downloadUrl = "https://codeload.github.com/octo/repo/oversized.zip";
        Remote remote = remote();
        expectToken(remote.server());
        expectArchiveRedirect(remote.server(), downloadUrl);
        remote.server().expect(once(), requestTo(downloadUrl))
                .andExpect(GitHubArchiveSourceProviderTest::assertNoAuthorization)
                .andRespond(withSuccess(
                        responseSecret.repeat(10), MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> remote.client().repositoryArchive(REVISION, 32))
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.DETERMINISTIC_INPUT);
                    assertThat(exception).hasMessage("GitHub archive download size limit exceeded");
                    assertThat(exception.toString()).doesNotContain(responseSecret);
                });
        remote.server().verify();
    }

    private GitHubArchiveSourceProvider provider(
            GitHubRestClient client,
            long maxDiffBytes,
            long maxArchiveBytes,
            long maxExpandedBytes,
            int maxEntries) {
        return new GitHubArchiveSourceProvider(
                client, tempParent, maxDiffBytes, maxArchiveBytes, maxExpandedBytes, maxEntries);
    }

    private static Remote remote() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RestClient.Builder builder = RestClient.builder().baseUrl(API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubAppJwtFactory jwtFactory = new GitHubAppJwtFactory(123L, privateKeyPem, OBJECT_MAPPER, clock);
        GitHubRestClient client = new GitHubRestClient(
                builder.build(), jwtFactory, OBJECT_MAPPER, clock, Duration.ofSeconds(30), 8);
        return new Remote(client, server);
    }

    private static void expectSuccessfulSource(
            MockRestServiceServer server, String diff, byte[] archive) {
        expectToken(server);
        expectHead(server, HEAD_SHA);
        expectDiff(server, diff);
        expectArchive(server, archive);
        expectHead(server, HEAD_SHA);
    }

    private static void expectToken(MockRestServiceServer server) {
        server.expect(once(), requestTo(API_BASE_URL + "/app/installations/41/access_tokens"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, org.hamcrest.Matchers.startsWith("Bearer eyJ")))
                .andRespond(withSuccess("""
                        {"token":"%s","expires_at":"%s"}
                        """.formatted(INSTALLATION_TOKEN, NOW.plusSeconds(600)), MediaType.APPLICATION_JSON));
    }

    private static void expectHead(MockRestServiceServer server, String headSha) {
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + INSTALLATION_TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess("{\"head\":{\"sha\":\"" + headSha + "\"}}",
                        MediaType.APPLICATION_JSON));
    }

    private static void expectDiff(MockRestServiceServer server, String diff) {
        server.expect(once(), requestTo(API_BASE_URL + "/repositories/73/pulls/12"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + INSTALLATION_TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github.diff"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess(diff, MediaType.valueOf("application/vnd.github.diff")));
    }

    private static void expectArchive(MockRestServiceServer server, byte[] archive) {
        server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/zipball/" + HEAD_SHA))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + INSTALLATION_TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess(archive, MediaType.APPLICATION_OCTET_STREAM));
    }

    private static void expectArchiveRedirect(MockRestServiceServer server, String location) {
        server.expect(once(), requestTo(
                        API_BASE_URL + "/repositories/73/zipball/" + HEAD_SHA))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + INSTALLATION_TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, location));
    }

    private static void assertNoAuthorization(org.springframework.http.HttpRequest request) {
        assertThat(request.getHeaders()).doesNotContainKey(HttpHeaders.AUTHORIZATION);
    }

    private static void assertDeterministicFailure(
            ThrowableAssert.ThrowingCallable operation, String safeMessage) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(GitHubFailureException.class, exception -> {
                    assertThat(exception.classification())
                            .isEqualTo(GitHubFailureException.Classification.DETERMINISTIC_INPUT);
                    assertThat(exception.retryAt()).isEmpty();
                    assertThat(exception).hasMessage(safeMessage);
                });
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zip.putNextEntry(zipEntry);
                if (entry.getValue() != null) {
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void markAsUnixSymlink(byte[] archive, String entryName) {
        byte[] expectedName = entryName.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset <= archive.length - 46; offset++) {
            if (littleEndianInt(archive, offset) != 0x02014b50) {
                continue;
            }
            int nameLength = littleEndianShort(archive, offset + 28);
            if (nameLength == expectedName.length
                    && bytesEqual(archive, offset + 46, expectedName)) {
                putLittleEndianShort(archive, offset + 4, (3 << 8) | 20);
                putLittleEndianInt(archive, offset + 38, (0120000 | 0777) << 16);
                return;
            }
        }
        throw new AssertionError("central-directory entry not found");
    }

    private static boolean bytesEqual(byte[] archive, int offset, byte[] expected) {
        if (offset + expected.length > archive.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (archive[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static int littleEndianShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return littleEndianShort(bytes, offset) | littleEndianShort(bytes, offset + 2) << 16;
    }

    private static void putLittleEndianShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void putLittleEndianInt(byte[] bytes, int offset, int value) {
        putLittleEndianShort(bytes, offset, value);
        putLittleEndianShort(bytes, offset + 2, value >>> 16);
    }

    private record Remote(GitHubRestClient client, MockRestServiceServer server) {
    }
}
