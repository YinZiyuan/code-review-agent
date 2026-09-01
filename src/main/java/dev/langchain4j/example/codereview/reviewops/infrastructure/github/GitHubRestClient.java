package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubInstallationGateway;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException.Classification.AUTHORIZATION;
import static dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException.Classification.DETERMINISTIC_INPUT;
import static dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException.Classification.RATE_LIMITED;
import static dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException.Classification.TRANSIENT;

public final class GitHubRestClient implements GitHubInstallationGateway {

    static final String GITHUB_API_VERSION = "2022-11-28";
    static final String GITHUB_JSON_MEDIA_TYPE = "application/vnd.github+json";
    private static final String GITHUB_DIFF_MEDIA_TYPE = "application/vnd.github.diff";
    private static final long MAX_PULL_REQUEST_METADATA_BYTES = 65_536;
    private static final long MAX_INSTALLATION_TOKEN_RESPONSE_BYTES = 65_536;
    private static final Set<String> ALLOWED_ARCHIVE_REDIRECT_HOSTS = Set.of("codeload.github.com");
    private static final int MAX_ARCHIVE_REDIRECTS = 3;

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
                throw deterministic("GitHub pull request head does not match requested revision");
            }
        } catch (GitHubFailureException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw deterministic("GitHub pull request metadata was invalid");
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
            throw deterministic("GitHub pull request diff was not valid UTF-8");
        }
    }

    public byte[] repositoryArchive(PullRequestRevision revision, long maxBytes) {
        Objects.requireNonNull(revision, "revision");
        validateFullCommitSha(revision.headSha());
        requireDownloadLimit(maxBytes);
        InstallationToken installationToken = token(revision.installationId());
        ArchiveHttpResult result;
        try {
            result = restClient.get()
                    .uri("/repositories/{repositoryId}/zipball/{headSha}",
                            revision.repositoryId(), revision.headSha())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + installationToken.value())
                    .header(HttpHeaders.ACCEPT, GITHUB_JSON_MEDIA_TYPE)
                    .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                    .exchange((request, response) -> archiveHttpResult(response, maxBytes));
        } catch (GitHubFailureException exception) {
            throw exception;
        } catch (RestClientException | NullPointerException exception) {
            throw transientFailure("GitHub archive request failed");
        }

        if (result.body() != null) {
            return result.body();
        }
        URI location = result.redirect();
        int redirectsFollowed = 1;
        while (true) {
            try {
                result = restClient.get()
                        .uri(location)
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                        .exchange((request, response) -> archiveHttpResult(response, maxBytes));
            } catch (GitHubFailureException exception) {
                throw exception;
            } catch (RestClientException | NullPointerException exception) {
                throw transientFailure("GitHub archive request failed");
            }
            if (result.body() != null) {
                return result.body();
            }
            if (redirectsFollowed >= MAX_ARCHIVE_REDIRECTS) {
                throw transientFailure("GitHub archive redirect limit exceeded");
            }
            redirectsFollowed++;
            location = result.redirect();
        }
    }

    private ArchiveHttpResult archiveHttpResult(
            org.springframework.http.client.ClientHttpResponse response, long maxBytes)
            throws IOException {
        int status = response.getStatusCode().value();
        if (response.getStatusCode().is2xxSuccessful()) {
            if (response.getHeaders().getContentLength() > maxBytes) {
                throw deterministic("GitHub archive download size limit exceeded");
            }
            return ArchiveHttpResult.body(readBounded(
                    response.getBody(), maxBytes,
                    () -> deterministic("GitHub archive download size limit exceeded")));
        }
        if (status == HttpStatus.FOUND.value()) {
            return ArchiveHttpResult.redirect(validatedArchiveRedirect(response.getHeaders()));
        }
        throw failureForStatus(
                status,
                response.getHeaders(),
                FailureTarget.REVISION,
                "GitHub archive request failed");
    }

    private static URI validatedArchiveRedirect(HttpHeaders headers) {
        String rawLocation = headers.getFirst(HttpHeaders.LOCATION);
        if (rawLocation == null || rawLocation.isBlank()) {
            throw deterministic("GitHub archive redirect was invalid");
        }
        final URI location;
        try {
            location = URI.create(rawLocation);
        } catch (IllegalArgumentException exception) {
            throw deterministic("GitHub archive redirect was invalid");
        }
        String host = location.getHost();
        boolean trusted = location.isAbsolute()
                && "https".equalsIgnoreCase(location.getScheme())
                && host != null
                && ALLOWED_ARCHIVE_REDIRECT_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT))
                && location.getUserInfo() == null
                && location.getFragment() == null
                && (location.getPort() == -1 || location.getPort() == 443)
                && location.getRawPath() != null
                && !location.getRawPath().isBlank()
                && location.getRawPath().startsWith("/");
        if (!trusted) {
            throw deterministic("GitHub archive redirect was invalid");
        }
        return location;
    }

    private InstallationToken exchangeInstallationToken(long installationId, Instant now) {
        try {
            byte[] response = restClient.post()
                    .uri("/app/installations/{installationId}/access_tokens", installationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtFactory.create())
                    .header(HttpHeaders.ACCEPT, GITHUB_JSON_MEDIA_TYPE)
                    .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                    .exchange((request, tokenResponse) -> {
                        if (!tokenResponse.getStatusCode().is2xxSuccessful()) {
                            throw failureForStatus(
                                    tokenResponse.getStatusCode().value(),
                                    tokenResponse.getHeaders(),
                                    FailureTarget.INSTALLATION_TOKEN,
                                    "GitHub installation token request failed");
                        }
                        if (tokenResponse.getHeaders().getContentLength()
                                > MAX_INSTALLATION_TOKEN_RESPONSE_BYTES) {
                            throw transientFailure(
                                    "GitHub installation token response size limit exceeded");
                        }
                        return readBounded(
                                tokenResponse.getBody(),
                                MAX_INSTALLATION_TOKEN_RESPONSE_BYTES,
                                () -> transientFailure(
                                        "GitHub installation token response size limit exceeded"));
                    });
            JsonNode json = objectMapper.readTree(response);
            String token = requiredText(json, "token");
            Instant expiresAt = Instant.parse(requiredText(json, "expires_at"));
            if (!now.isBefore(expiresAt.minus(refreshSkew))) {
                throw new IllegalArgumentException("token expires too soon");
            }
            return new InstallationToken(token, expiresAt);
        } catch (GitHubFailureException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw transientFailure("GitHub installation token request failed");
        } catch (IOException | DateTimeParseException | IllegalArgumentException
                 | NullPointerException exception) {
            throw transientFailure("GitHub installation token response was invalid");
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
                            throw failureForStatus(
                                    response.getStatusCode().value(),
                                    response.getHeaders(),
                                    FailureTarget.REVISION,
                                    requestFailureMessage);
                        }
                        long contentLength = response.getHeaders().getContentLength();
                        if (contentLength > maxBytes) {
                            throw deterministic(sizeLimitMessage);
                        }
                        return readBounded(
                                response.getBody(), maxBytes,
                                () -> deterministic(sizeLimitMessage));
                    });
            return Objects.requireNonNull(body, "GitHub response body");
        } catch (GitHubFailureException exception) {
            throw exception;
        } catch (RestClientException | NullPointerException exception) {
            throw transientFailure(requestFailureMessage);
        }
    }

    private static byte[] readBounded(
            InputStream input,
            long maxBytes,
            Supplier<GitHubFailureException> sizeLimitFailure)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBytes, 8_192));
        byte[] buffer = new byte[8_192];
        long total = 0;
        int read;
        while ((read = input.read(
                buffer, 0, (int) Math.min(buffer.length, maxBytes - total + 1))) != -1) {
            total += read;
            if (total > maxBytes) {
                throw sizeLimitFailure.get();
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
            throw deterministic("headSha must be a full hexadecimal commit SHA");
        }
        for (int index = 0; index < headSha.length(); index++) {
            char digit = headSha.charAt(index);
            boolean hexadecimal = digit >= '0' && digit <= '9'
                    || digit >= 'a' && digit <= 'f'
                    || digit >= 'A' && digit <= 'F';
            if (!hexadecimal) {
                throw deterministic("headSha must be a full hexadecimal commit SHA");
            }
        }
    }

    private GitHubFailureException failureForStatus(
            int status,
            HttpHeaders headers,
            FailureTarget target,
            String transientMessage) {
        if (status == 429 || status == 403 && hasRateLimitSignal(headers)) {
            return new GitHubFailureException(
                    RATE_LIMITED,
                    "GitHub API rate limit exceeded",
                    retryInstant(headers).orElse(null));
        }
        if (status >= 500 || status == 408) {
            return transientFailure(transientMessage);
        }
        if (status == 401 || status == 403
                || target == FailureTarget.INSTALLATION_TOKEN && status >= 400 && status < 500) {
            return new GitHubFailureException(AUTHORIZATION, "GitHub authorization failed");
        }
        if (status >= 400 && status < 500) {
            return deterministic("GitHub requested revision is unavailable");
        }
        return transientFailure(transientMessage);
    }

    private static boolean hasRateLimitSignal(HttpHeaders headers) {
        return headers.containsKey(HttpHeaders.RETRY_AFTER)
                || headers.containsKey("X-RateLimit-Reset")
                || "0".equals(headers.getFirst("X-RateLimit-Remaining"));
    }

    private Optional<Instant> retryInstant(HttpHeaders headers) {
        List<Instant> candidates = new ArrayList<>(2);
        parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)).ifPresent(candidates::add);
        parseEpochSeconds(headers.getFirst("X-RateLimit-Reset")).ifPresent(candidates::add);
        return candidates.stream()
                .filter(candidate -> !candidate.isBefore(clock.instant()))
                .max(Comparator.naturalOrder());
    }

    private Optional<Instant> parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < 0) {
                return Optional.empty();
            }
            return Optional.of(clock.instant().plusSeconds(seconds));
        } catch (NumberFormatException | java.time.DateTimeException exception) {
            try {
                return Optional.of(ZonedDateTime.parse(
                        value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        }
    }

    private static Optional<Instant> parseEpochSeconds(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            long epochSeconds = Long.parseLong(value.trim());
            return Optional.of(Instant.ofEpochSecond(epochSeconds));
        } catch (NumberFormatException | java.time.DateTimeException exception) {
            return Optional.empty();
        }
    }

    private static GitHubFailureException transientFailure(String safeMessage) {
        return new GitHubFailureException(TRANSIENT, safeMessage);
    }

    private static GitHubFailureException deterministic(String safeMessage) {
        return new GitHubFailureException(DETERMINISTIC_INPUT, safeMessage);
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

    private enum FailureTarget {
        INSTALLATION_TOKEN,
        REVISION
    }

    private record ArchiveHttpResult(byte[] body, URI redirect) {
        private static ArchiveHttpResult body(byte[] body) {
            return new ArchiveHttpResult(Objects.requireNonNull(body, "body"), null);
        }

        private static ArchiveHttpResult redirect(URI redirect) {
            return new ArchiveHttpResult(null, Objects.requireNonNull(redirect, "redirect"));
        }
    }
}
