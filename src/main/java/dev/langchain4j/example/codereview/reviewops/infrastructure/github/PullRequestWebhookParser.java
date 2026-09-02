package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.langchain4j.example.codereview.reviewops.application.github.VerifiedPullRequestEvent;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

public final class PullRequestWebhookParser {

    public static final String INVALID_PAYLOAD_REASON = "INVALID_PULL_REQUEST_PAYLOAD";
    public static final String PAYLOAD_TOO_LARGE_REASON = "WEBHOOK_PAYLOAD_TOO_LARGE";

    private static final String PULL_REQUEST_EVENT = "pull_request";
    private static final Set<String> INTERESTED_ACTIONS = Set.of("opened", "reopened", "synchronize");

    private final ObjectReader objectReader;
    private final int maxWebhookBytes;
    private final Clock clock;

    public PullRequestWebhookParser(ObjectMapper objectMapper, int maxWebhookBytes) {
        this(objectMapper, maxWebhookBytes, Clock.systemUTC());
    }

    public PullRequestWebhookParser(
            ObjectMapper objectMapper,
            int maxWebhookBytes,
            Clock clock
    ) {
        if (maxWebhookBytes <= 0) {
            throw new IllegalArgumentException("maxWebhookBytes must be positive");
        }
        this.objectReader = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.maxWebhookBytes = maxWebhookBytes;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public ParseResult parse(String deliveryId, String eventName, byte[] payload) {
        if (payload == null) {
            return ParseResult.invalid();
        }
        if (payload.length > maxWebhookBytes) {
            return ParseResult.payloadTooLarge();
        }
        if (!PULL_REQUEST_EVENT.equals(eventName)) {
            return ParseResult.ignored();
        }

        try {
            JsonNode root = objectReader.readTree(payload);
            String action = requiredText(root, "action");
            if (!INTERESTED_ACTIONS.contains(action)) {
                return ParseResult.ignored();
            }

            VerifiedPullRequestEvent event = new VerifiedPullRequestEvent(
                    deliveryId,
                    action,
                    requiredPositiveLong(root.path("installation"), "id"),
                    requiredPositiveLong(root.path("repository"), "id"),
                    requiredText(root.path("repository"), "full_name"),
                    requiredPositiveInt(root, "number"),
                    requiredText(root.path("pull_request").path("head"), "sha"),
                    requiredText(root.path("repository"), "clone_url"),
                    clock.instant());
            return ParseResult.parsed(event);
        } catch (IOException | IllegalArgumentException exception) {
            return ParseResult.invalid();
        }
    }

    private static String requiredText(JsonNode parent, String fieldName) {
        JsonNode value = parent.path(fieldName);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("missing required text");
        }
        return value.textValue();
    }

    private static long requiredPositiveLong(JsonNode parent, String fieldName) {
        JsonNode value = parent.path(fieldName);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("invalid positive integer");
        }
        return value.longValue();
    }

    private static int requiredPositiveInt(JsonNode parent, String fieldName) {
        JsonNode value = parent.path(fieldName);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException("invalid positive integer");
        }
        return value.intValue();
    }

    public enum ParseStatus {
        PARSED,
        IGNORED,
        INVALID,
        PAYLOAD_TOO_LARGE
    }

    public record ParseResult(
            ParseStatus status,
            VerifiedPullRequestEvent event,
            String reasonCode
    ) {
        private static ParseResult parsed(VerifiedPullRequestEvent event) {
            return new ParseResult(ParseStatus.PARSED, event, null);
        }

        private static ParseResult ignored() {
            return new ParseResult(ParseStatus.IGNORED, null, null);
        }

        private static ParseResult invalid() {
            return new ParseResult(ParseStatus.INVALID, null, INVALID_PAYLOAD_REASON);
        }

        private static ParseResult payloadTooLarge() {
            return new ParseResult(ParseStatus.PAYLOAD_TOO_LARGE, null, PAYLOAD_TOO_LARGE_REASON);
        }
    }
}
