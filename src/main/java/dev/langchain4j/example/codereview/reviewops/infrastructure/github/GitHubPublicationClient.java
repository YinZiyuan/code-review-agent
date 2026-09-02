package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.github.CheckRunArtifact;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubInstallationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.GitHubPublicationGateway;
import dev.langchain4j.example.codereview.reviewops.application.github.InlineCommentArtifact;
import dev.langchain4j.example.codereview.reviewops.domain.AuthoritativeRevision;
import dev.langchain4j.example.codereview.reviewops.domain.PublicationReference;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException.Classification.AUTHORIZATION;
import static dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException.Classification.DETERMINISTIC_INPUT;
import static dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException.Classification.RATE_LIMITED;
import static dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException.Classification.TRANSIENT;

public final class GitHubPublicationClient implements GitHubPublicationGateway {

    public static final int MAX_LIST_RESPONSE_BYTES = 1_048_576;
    static final int MAX_MUTATION_RESPONSE_BYTES = 65_536;
    static final int MAX_REQUEST_BYTES = 131_072;
    private static final int MAX_RECONCILIATION_PAGES = 100;
    private static final int PAGE_SIZE = 100;
    private static final String GITHUB_API_VERSION = "2022-11-28";
    private static final String GITHUB_JSON_MEDIA_TYPE = "application/vnd.github+json";
    private static final String COMMENT_ARTIFACT_TYPE = "github_review_comment";

    private final RestClient restClient;
    private final GitHubInstallationGateway installations;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String checkName;
    private final CheckRunFormatter checkFormatter;
    private final InlineCommentFormatter inlineFormatter;

    public GitHubPublicationClient(
            RestClient restClient,
            GitHubInstallationGateway installations,
            ObjectMapper objectMapper,
            Clock clock,
            String checkName,
            CheckRunFormatter checkFormatter,
            InlineCommentFormatter inlineFormatter) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.installations = Objects.requireNonNull(installations, "installations");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (checkName == null || checkName.isBlank()) {
            throw new IllegalArgumentException("checkName must not be blank");
        }
        if (checkName.length() > 100) {
            throw new IllegalArgumentException("checkName exceeds 100 characters");
        }
        this.checkName = checkName;
        this.checkFormatter = Objects.requireNonNull(checkFormatter, "checkFormatter");
        this.inlineFormatter = Objects.requireNonNull(inlineFormatter, "inlineFormatter");
    }

    @Override
    public AuthoritativeRevision authoritativeRevision(PullRequestRevision revision) {
        Objects.requireNonNull(revision, "revision");
        byte[] response = exchange(
                revision,
                HttpMethod.GET,
                request -> request.uri(
                        "/repositories/{repositoryId}/pulls/{pullRequestNumber}",
                        revision.repositoryId(), revision.pullRequestNumber()),
                null,
                MAX_MUTATION_RESPONSE_BYTES,
                FailureTarget.REVISION,
                "GitHub pull request metadata request failed");
        try {
            JsonNode json = objectMapper.readTree(response);
            String headSha = requiredText(json.path("head"), "sha");
            GitHubRestClient.validateFullCommitSha(headSha);
            return new AuthoritativeRevision(headSha);
        } catch (GitHubFailureException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException | NullPointerException failure) {
            throw transientFailure("GitHub pull request metadata response was invalid");
        }
    }

    @Override
    public CheckRunArtifact upsertCheck(CheckRunRequest request) {
        Objects.requireNonNull(request, "request");
        CheckRunFormatter.FormattedCheckRun formatted = checkFormatter.format(request);
        Optional<String> persistedId = request.existingGitHubArtifactId()
                .map(GitHubPublicationClient::requireArtifactId);
        if (persistedId.isPresent()) {
            try {
                return updateCheck(request, formatted, persistedId.orElseThrow());
            } catch (MissingArtifactException missing) {
                // A confirmed artifact may have been deleted manually. Reconcile before recreation.
            }
        }

        Optional<String> reconciled = findCheckByExternalId(request);
        if (reconciled.isPresent()) {
            return updateCheck(request, formatted, reconciled.orElseThrow());
        }
        return createCheck(request, formatted);
    }

    @Override
    public InlineCommentArtifact reconcileInlineComment(InlineCommentRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<PublicationReference> existingReference = request.finding().existingReference();
        if (existingReference.isPresent()) {
            PublicationReference reference = existingReference.orElseThrow();
            if (!COMMENT_ARTIFACT_TYPE.equals(reference.artifactType())) {
                throw deterministic("Persisted GitHub comment reference was invalid");
            }
            return new InlineCommentArtifact(
                    request.finding().fingerprint(),
                    requireArtifactId(reference.externalId()));
        }

        String body = inlineFormatter.format(request.finding());
        String marker = InlineCommentFormatter.marker(
                request.finding().fingerprint().value());
        Optional<String> reconciled = findCommentByMarker(request, marker);
        if (reconciled.isPresent()) {
            return new InlineCommentArtifact(
                    request.finding().fingerprint(), reconciled.orElseThrow());
        }
        return createComment(request, body);
    }

    private Optional<String> findCheckByExternalId(CheckRunRequest request) {
        String expectedExternalId = request.reconciliationExternalId().value().toString();
        for (int page = 1; page <= MAX_RECONCILIATION_PAGES; page++) {
            int currentPage = page;
            byte[] response = exchange(
                    request.revision(),
                    HttpMethod.GET,
                    spec -> spec.uri(builder -> builder
                            .path("/repositories/{repositoryId}/commits/{headSha}/check-runs")
                            .queryParam("check_name", checkName)
                            .queryParam("filter", "all")
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", currentPage)
                            .build(request.revision().repositoryId(),
                                    request.revision().headSha())),
                    null,
                    MAX_LIST_RESPONSE_BYTES,
                    FailureTarget.CHECK_LIST,
                    "GitHub Check reconciliation request failed");
            JsonNode checks = readJson(response, "GitHub Check reconciliation response was invalid")
                    .path("check_runs");
            if (!checks.isArray()) {
                throw transientFailure("GitHub Check reconciliation response was invalid");
            }
            for (JsonNode check : checks) {
                if (expectedExternalId.equals(optionalText(check, "external_id"))) {
                    return Optional.of(requiredArtifactId(check));
                }
            }
            if (checks.size() < PAGE_SIZE) {
                return Optional.empty();
            }
        }
        throw transientFailure("GitHub Check reconciliation page limit exceeded");
    }

    private Optional<String> findCommentByMarker(
            InlineCommentRequest request, String marker) {
        for (int page = 1; page <= MAX_RECONCILIATION_PAGES; page++) {
            int currentPage = page;
            byte[] response = exchange(
                    request.revision(),
                    HttpMethod.GET,
                    spec -> spec.uri(builder -> builder
                            .path("/repositories/{repositoryId}/pulls/{pullRequestNumber}/comments")
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("page", currentPage)
                            .build(request.revision().repositoryId(),
                                    request.revision().pullRequestNumber())),
                    null,
                    MAX_LIST_RESPONSE_BYTES,
                    FailureTarget.COMMENT_LIST,
                    "GitHub comment reconciliation request failed");
            JsonNode comments = readJson(
                    response, "GitHub comment reconciliation response was invalid");
            if (!comments.isArray()) {
                throw transientFailure("GitHub comment reconciliation response was invalid");
            }
            for (JsonNode comment : comments) {
                JsonNode body = comment.path("body");
                if (body.isTextual() && body.textValue().contains(marker)) {
                    return Optional.of(requiredArtifactId(comment));
                }
            }
            if (comments.size() < PAGE_SIZE) {
                return Optional.empty();
            }
        }
        throw transientFailure("GitHub comment reconciliation page limit exceeded");
    }

    private CheckRunArtifact createCheck(
            CheckRunRequest request, CheckRunFormatter.FormattedCheckRun formatted) {
        Map<String, Object> payload = checkPayload(request, formatted);
        payload.put("head_sha", request.revision().headSha());
        byte[] response = exchange(
                request.revision(),
                HttpMethod.POST,
                spec -> spec.uri(
                        "/repositories/{repositoryId}/check-runs",
                        request.revision().repositoryId()),
                serializedPayload(payload),
                MAX_MUTATION_RESPONSE_BYTES,
                FailureTarget.CHECK_CREATE,
                "GitHub Check creation failed");
        return new CheckRunArtifact(requiredArtifactId(readJson(
                response, "GitHub Check creation response was invalid")));
    }

    private CheckRunArtifact updateCheck(
            CheckRunRequest request,
            CheckRunFormatter.FormattedCheckRun formatted,
            String checkId) {
        byte[] response = exchange(
                request.revision(),
                HttpMethod.PATCH,
                spec -> spec.uri(
                        "/repositories/{repositoryId}/check-runs/{checkRunId}",
                        request.revision().repositoryId(), checkId),
                serializedPayload(checkPayload(request, formatted)),
                MAX_MUTATION_RESPONSE_BYTES,
                FailureTarget.CHECK_UPDATE,
                "GitHub Check update failed");
        String confirmedId = requiredArtifactId(readJson(
                response, "GitHub Check update response was invalid"));
        if (!checkId.equals(confirmedId)) {
            throw transientFailure("GitHub Check update response was invalid");
        }
        return new CheckRunArtifact(confirmedId);
    }

    private InlineCommentArtifact createComment(
            InlineCommentRequest request, String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("commit_id", request.revision().headSha());
        payload.put("path", request.finding().location().file());
        payload.put("line", request.finding().location().line());
        payload.put("side", "RIGHT");
        byte[] response = exchange(
                request.revision(),
                HttpMethod.POST,
                spec -> spec.uri(
                        "/repositories/{repositoryId}/pulls/{pullRequestNumber}/comments",
                        request.revision().repositoryId(),
                        request.revision().pullRequestNumber()),
                serializedPayload(payload),
                MAX_MUTATION_RESPONSE_BYTES,
                FailureTarget.COMMENT_CREATE,
                "GitHub inline comment creation failed");
        return new InlineCommentArtifact(
                request.finding().fingerprint(),
                requiredArtifactId(readJson(
                        response, "GitHub inline comment creation response was invalid")));
    }

    private Map<String, Object> checkPayload(
            CheckRunRequest request, CheckRunFormatter.FormattedCheckRun formatted) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("title", formatted.title());
        output.put("summary", formatted.summary());
        output.put("text", formatted.text());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", checkName);
        payload.put("external_id", request.reconciliationExternalId().value().toString());
        payload.put("status", formatted.status());
        payload.put("conclusion", formatted.conclusion());
        payload.put("output", output);
        return payload;
    }

    private byte[] serializedPayload(Map<String, Object> payload) {
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(payload);
            if (serialized.length > MAX_REQUEST_BYTES) {
                throw deterministic("GitHub publication request size limit exceeded");
            }
            return serialized;
        } catch (GitHubFailureException failure) {
            throw failure;
        } catch (IOException failure) {
            throw deterministic("GitHub publication request was invalid");
        }
    }

    private byte[] exchange(
            PullRequestRevision revision,
            HttpMethod method,
            RequestConfigurer requestConfigurer,
            byte[] payload,
            int maxResponseBytes,
            FailureTarget failureTarget,
            String transientMessage) {
        GitHubInstallationGateway.InstallationToken token =
                installations.token(revision.installationId());
        try {
            RestClient.RequestBodySpec request = authenticated(
                    requestConfigurer.configure(restClient.method(method)), token);
            if (payload != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(payload);
            }
            return request.exchange((httpRequest, response) -> {
                int status = response.getStatusCode().value();
                if (!response.getStatusCode().is2xxSuccessful()) {
                    if (failureTarget == FailureTarget.CHECK_UPDATE
                            && status == HttpStatus.NOT_FOUND.value()) {
                        throw new MissingArtifactException();
                    }
                    throw failureForStatus(
                            status,
                            response.getHeaders(),
                            failureTarget,
                            transientMessage);
                }
                if (response.getHeaders().getContentLength() > maxResponseBytes) {
                    throw transientFailure(
                            "GitHub publication response size limit exceeded");
                }
                return readBounded(
                        response.getBody(),
                        maxResponseBytes,
                        () -> transientFailure(
                                "GitHub publication response size limit exceeded"));
            });
        } catch (MissingArtifactException | GitHubFailureException failure) {
            throw failure;
        } catch (RestClientException | NullPointerException failure) {
            throw transientFailure(transientMessage);
        }
    }

    private static RestClient.RequestBodySpec authenticated(
            RestClient.RequestBodySpec request,
            GitHubInstallationGateway.InstallationToken token) {
        return request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                .header(HttpHeaders.ACCEPT, GITHUB_JSON_MEDIA_TYPE)
                .header("X-GitHub-Api-Version", GITHUB_API_VERSION);
    }

    private GitHubFailureException failureForStatus(
            int status,
            HttpHeaders headers,
            FailureTarget target,
            String transientMessage) {
        if (isRateLimited(status, headers)) {
            return new GitHubFailureException(
                    RATE_LIMITED,
                    "GitHub API rate limit exceeded",
                    retryInstant(headers).orElseGet(() -> clock.instant().plusSeconds(60)));
        }
        if (status >= 500 || status == HttpStatus.REQUEST_TIMEOUT.value()) {
            return transientFailure(transientMessage);
        }
        if (status == HttpStatus.UNAUTHORIZED.value()
                || status == HttpStatus.FORBIDDEN.value()) {
            return new GitHubFailureException(
                    AUTHORIZATION, "GitHub authorization failed");
        }
        if (target == FailureTarget.COMMENT_CREATE
                && status == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
            return deterministic("GitHub inline comment location was invalid");
        }
        if (status >= 400 && status < 500) {
            return deterministic(switch (target) {
                case REVISION -> "GitHub pull request is unavailable";
                case COMMENT_CREATE, COMMENT_LIST -> "GitHub pull request comment is unavailable";
                case CHECK_CREATE, CHECK_UPDATE, CHECK_LIST -> "GitHub Check is unavailable";
            });
        }
        return transientFailure(transientMessage);
    }

    private boolean isRateLimited(int status, HttpHeaders headers) {
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return true;
        }
        if (status != HttpStatus.FORBIDDEN.value()) {
            return false;
        }
        boolean retryAfter = parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER))
                .filter(retryAt -> !retryAt.isBefore(clock.instant()))
                .isPresent();
        boolean primaryLimit = "0".equals(headers.getFirst("X-RateLimit-Remaining"))
                && parseEpochSeconds(headers.getFirst("X-RateLimit-Reset"))
                .filter(retryAt -> !retryAt.isBefore(clock.instant()))
                .isPresent();
        return retryAfter || primaryLimit;
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
            return seconds < 0
                    ? Optional.empty() : Optional.of(clock.instant().plusSeconds(seconds));
        } catch (NumberFormatException | java.time.DateTimeException ignored) {
            try {
                return Optional.of(ZonedDateTime.parse(
                        value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            } catch (DateTimeParseException invalidDate) {
                return Optional.empty();
            }
        }
    }

    private static Optional<Instant> parseEpochSeconds(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochSecond(Long.parseLong(value.trim())));
        } catch (NumberFormatException | java.time.DateTimeException failure) {
            return Optional.empty();
        }
    }

    private static byte[] readBounded(
            InputStream input,
            long maxBytes,
            Supplier<GitHubFailureException> sizeFailure) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maxBytes, 8_192));
        byte[] buffer = new byte[8_192];
        long total = 0;
        int read;
        while ((read = input.read(
                buffer, 0, (int) Math.min(buffer.length, maxBytes - total + 1))) != -1) {
            total += read;
            if (total > maxBytes) {
                throw sizeFailure.get();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private JsonNode readJson(byte[] response, String safeMessage) {
        try {
            return Objects.requireNonNull(objectMapper.readTree(response), "JSON response");
        } catch (IOException | NullPointerException failure) {
            throw transientFailure(safeMessage);
        }
    }

    private static String requiredText(JsonNode parent, String name) {
        JsonNode value = parent == null ? null : parent.path(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("missing field");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode parent, String name) {
        JsonNode value = parent.path(name);
        return value.isTextual() ? value.textValue() : "";
    }

    private static String requiredArtifactId(JsonNode response) {
        JsonNode id = response.path("id");
        if ((!id.isIntegralNumber() && !id.isTextual()) || id.asText().isBlank()) {
            throw transientFailure("GitHub publication response was invalid");
        }
        return requireArtifactId(id.asText());
    }

    private static String requireArtifactId(String artifactId) {
        if (artifactId == null || !artifactId.matches("[1-9][0-9]*")) {
            throw deterministic("GitHub artifact identifier was invalid");
        }
        return artifactId;
    }

    private static GitHubFailureException transientFailure(String safeMessage) {
        return new GitHubFailureException(TRANSIENT, safeMessage);
    }

    private static GitHubFailureException deterministic(String safeMessage) {
        return new GitHubFailureException(DETERMINISTIC_INPUT, safeMessage);
    }

    private enum FailureTarget {
        REVISION,
        CHECK_LIST,
        CHECK_CREATE,
        CHECK_UPDATE,
        COMMENT_LIST,
        COMMENT_CREATE
    }

    @FunctionalInterface
    private interface RequestConfigurer {
        RestClient.RequestBodySpec configure(RestClient.RequestBodyUriSpec request);
    }

    private static final class MissingArtifactException extends RuntimeException {
    }
}
