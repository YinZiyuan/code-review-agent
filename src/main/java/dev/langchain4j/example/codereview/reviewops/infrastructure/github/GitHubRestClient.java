package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubInstallationGateway;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class GitHubRestClient implements GitHubInstallationGateway {

    static final String GITHUB_API_VERSION = "2022-11-28";
    static final String GITHUB_JSON_MEDIA_TYPE = "application/vnd.github+json";
    private static final String GITHUB_DIFF_MEDIA_TYPE = "application/vnd.github.diff";
    private static final long MAX_PULL_REQUEST_METADATA_BYTES = 65_536;

    private final RestClient restClient;
    private final GitHubAppJwtFactory jwtFactory;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration refreshSkew;
    private final int maxCacheEntries;
    private final ConcurrentHashMap<Long, InstallationToken> tokenCache = new ConcurrentHashMap<>();
    private final Object tokenCacheLock = new Object();

    public GitHubRestClient(
            RestClient restClient,
            GitHubAppJwtFactory jwtFactory,
            ObjectMapper objectMapper,
            Clock clock,
            Duration refreshSkew,
            int maxCacheEntries
    ) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.jwtFactory = Objects.requireNonNull(jwtFactory, "jwtFactory");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshSkew = Objects.requireNonNull(refreshSkew, "refreshSkew");
        if (refreshSkew.isNegative()) {
            throw new IllegalArgumentException("refreshSkew must not be negative");
        }
        if (maxCacheEntries <= 0) {
            throw new IllegalArgumentException("maxCacheEntries must be positive");
        }
        this.maxCacheEntries = maxCacheEntries;
    }

    @Override
    public InstallationToken token(long installationId) {
        if (installationId <= 0) {
            throw new IllegalArgumentException("installationId must be positive");
        }
        synchronized (tokenCacheLock) {
            Instant now = clock.instant();
            InstallationToken cached = tokenCache.get(installationId);
            if (cached != null && now.isBefore(cached.expiresAt().minus(refreshSkew))) {
                return cached;
            }

            InstallationToken fresh = exchangeInstallationToken(installationId, now);
            if (!tokenCache.containsKey(installationId) && tokenCache.size() >= maxCacheEntries) {
                evictEarliestExpiry();
            }
            tokenCache.put(installationId, fresh);
            return fresh;
        }
    }

    public void requireExactPullRequestHead(PullRequestRevision revision) {
        Objects.requireNonNull(revision, "revision");
        validateFullCommitSha(revision.headSha());
        byte[] response = getBounded(
                revision,
                "/repositories/{repositoryId}/pulls/{pullRequestNumber}",
                GITHUB_JSON_MEDIA_TYPE,
                MAX_PULL_REQUEST_METADATA_BYTES,
                "GitHub pull request metadata size limit exceeded",
                "GitHub pull request metadata request failed",
                revision.repositoryId(),
                revision.pullRequestNumber());
        try {
            JsonNode json = objectMapper.readTree(response);
            String authoritativeHead = requiredText(json.path("head"), "sha");
            if (!revision.headSha().equals(authoritativeHead)) {
                throw new SafeGitHubException(
                        "GitHub pull request head does not match requested revision");
            }
        } catch (SafeGitHubException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new SafeGitHubException("GitHub pull request metadata was invalid");
        }
    }

    public String pullRequestDiff(PullRequestRevision revision, long maxBytes) {
        Objects.requireNonNull(revision, "revision");
        validateFullCommitSha(revision.headSha());
        byte[] response = getBounded(
                revision,
                "/repositories/{repositoryId}/pulls/{pullRequestNumber}",
                GITHUB_DIFF_MEDIA_TYPE,
                maxBytes,
                "GitHub pull request diff size limit exceeded",
                "GitHub pull request diff request failed",
                revision.repositoryId(),
                revision.pullRequestNumber());
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(response))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new SafeGitHubException("GitHub pull request diff was not valid UTF-8");
        }
    }

    public byte[] repositoryArchive(PullRequestRevision revision, long maxBytes) {
        Objects.requireNonNull(revision, "revision");
        validateFullCommitSha(revision.headSha());
        return getBounded(
                revision,
                "/repositories/{repositoryId}/zipball/{headSha}",
                GITHUB_JSON_MEDIA_TYPE,
                maxBytes,
                "GitHub archive download size limit exceeded",
                "GitHub archive request failed",
                revision.repositoryId(),
                revision.headSha());
    }

    private InstallationToken exchangeInstallationToken(long installationId, Instant now) {
        try {
            String response = restClient.post()
                    .uri("/app/installations/{installationId}/access_tokens", installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtFactory.create())
                    .header(HttpHeaders.ACCEPT, GITHUB_JSON_MEDIA_TYPE)
                    .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                    .retrieve()
                    .body(String.class);
            JsonNode json = objectMapper.readTree(response);
            String token = requiredText(json, "token");
            Instant expiresAt = Instant.parse(requiredText(json, "expires_at"));
            if (!now.isBefore(expiresAt.minus(refreshSkew))) {
                throw new IllegalArgumentException("token expires too soon");
            }
            return new InstallationToken(token, expiresAt);
        } catch (RestClientException | JsonProcessingException | DateTimeParseException
                 | IllegalArgumentException | NullPointerException exception) {
            throw new IllegalStateException("GitHub installation token request failed");
        }
    }

    private byte[] getBounded(
            PullRequestRevision revision,
            String uriTemplate,
            String accept,
            long maxBytes,
            String sizeLimitMessage,
            String requestFailureMessage,
            Object... uriVariables
    ) {
        requireDownloadLimit(maxBytes);
        InstallationToken installationToken = token(revision.installationId());
        try {
            byte[] body = restClient.get()
                    .uri(uriTemplate, uriVariables)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken.value())
                    .header(HttpHeaders.ACCEPT, accept)
                    .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new SafeGitHubException(requestFailureMessage);
                        }
                        long contentLength = response.getHeaders().getContentLength();
                        if (contentLength > maxBytes) {
                            throw new SafeGitHubException(sizeLimitMessage);
                        }
                        return readBounded(response.getBody(), maxBytes, sizeLimitMessage);
                    });
            return Objects.requireNonNull(body, "GitHub response body");
        } catch (SafeGitHubException exception) {
            throw exception;
        } catch (RestClientException | NullPointerException exception) {
            throw new SafeGitHubException(requestFailureMessage);
        }
    }

    private static byte[] readBounded(InputStream input, long maxBytes, String sizeLimitMessage)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBytes, 8_192));
        byte[] buffer = new byte[8_192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new SafeGitHubException(sizeLimitMessage);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void requireDownloadLimit(long maxBytes) {
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "download limit must be between 1 and Integer.MAX_VALUE bytes");
        }
    }

    static void validateFullCommitSha(String headSha) {
        if (headSha == null || headSha.length() != 40) {
            throw new IllegalArgumentException("headSha must be a full hexadecimal commit SHA");
        }
        for (int index = 0; index < headSha.length(); index++) {
            char digit = headSha.charAt(index);
            boolean hexadecimal = digit >= '0' && digit <= '9'
                    || digit >= 'a' && digit <= 'f'
                    || digit >= 'A' && digit <= 'F';
            if (!hexadecimal) {
                throw new IllegalArgumentException("headSha must be a full hexadecimal commit SHA");
            }
        }
    }

    private static String requiredText(JsonNode json, String fieldName) {
        if (json == null) {
            throw new IllegalArgumentException("missing JSON response");
        }
        JsonNode value = json.path(fieldName);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("missing field");
        }
        return value.textValue();
    }

    private void evictEarliestExpiry() {
        tokenCache.entrySet().stream()
                .min(Comparator.<Map.Entry<Long, InstallationToken>, Instant>comparing(
                                entry -> entry.getValue().expiresAt())
                        .thenComparingLong(Map.Entry::getKey))
                .ifPresent(entry -> tokenCache.remove(entry.getKey(), entry.getValue()));
    }

    @Override
    public String toString() {
        return "GitHubRestClient[cachedInstallations=" + tokenCache.size() + "]";
    }

    private static final class SafeGitHubException extends IllegalStateException {
        private SafeGitHubException(String message) {
            super(message);
        }
    }
}
