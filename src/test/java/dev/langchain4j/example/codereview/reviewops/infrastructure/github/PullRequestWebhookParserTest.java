package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.application.github.VerifiedPullRequestEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser.ParseStatus.IGNORED;
import static dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser.ParseStatus.INVALID;
import static dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser.ParseStatus.PARSED;
import static dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser.ParseStatus.PAYLOAD_TOO_LARGE;
import static org.assertj.core.api.Assertions.assertThat;

class PullRequestWebhookParserTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-09-01T04:05:06Z");
    private static final String DELIVERY_ID = "delivery-123";
    private static final int MAX_WEBHOOK_BYTES = 4_096;

    private final PullRequestWebhookParser parser = parserWithLimit(MAX_WEBHOOK_BYTES);

    @ParameterizedTest
    @ValueSource(strings = {"opened", "reopened", "synchronize"})
    void parsesInterestedPullRequestActionsIntoExactVerifiedFacts(String action) {
        byte[] payload = interestedPayload(action);

        PullRequestWebhookParser.ParseResult result = parser.parse(DELIVERY_ID, "pull_request", payload);

        assertThat(result.status()).isEqualTo(PARSED);
        assertThat(result.reasonCode()).isNull();
        assertThat(result.event()).isEqualTo(new VerifiedPullRequestEvent(
                DELIVERY_ID,
                action,
                41L,
                73L,
                "octo/repo",
                12,
                "0123456789abcdef0123456789abcdef01234567",
                "https://github.com/octo/repo.git",
                OBSERVED_AT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"closed", "edited", "labeled"})
    void ignoresUninterestedPullRequestActionsWithoutRequiringRevisionFacts(String action) {
        byte[] payload = ("{\"action\":\"" + action + "\"}").getBytes(StandardCharsets.UTF_8);

        PullRequestWebhookParser.ParseResult result = parser.parse(DELIVERY_ID, "pull_request", payload);

        assertThat(result.status()).isEqualTo(IGNORED);
        assertThat(result.event()).isNull();
        assertThat(result.reasonCode()).isNull();
    }

    @Test
    void ignoresOtherGitHubEventTypesWithoutParsingTheirPayload() {
        byte[] malformedPayload = "not-json-payload-marker".getBytes(StandardCharsets.UTF_8);

        PullRequestWebhookParser.ParseResult result = parser.parse(DELIVERY_ID, "issues", malformedPayload);

        assertThat(result.status()).isEqualTo(IGNORED);
        assertThat(result.event()).isNull();
        assertThat(result.reasonCode()).isNull();
        assertThat(result.toString()).doesNotContain("not-json-payload-marker");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "null",
            "[]",
            "{\"action\":\"opened\"",
            "{\"action\":\"opened\",\"installation\":{\"id\":41}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":0},\"repository\":{\"id\":73,\"full_name\":\"octo/repo\",\"clone_url\":\"https://github.com/octo/repo.git\"},\"number\":12,\"pull_request\":{\"head\":{\"sha\":\"abc\"}}}",
            "{\"action\":\"opened\",\"installation\":{\"id\":41},\"repository\":{\"id\":73,\"full_name\":\"\",\"clone_url\":\"https://github.com/octo/repo.git\"},\"number\":12,\"pull_request\":{\"head\":{\"sha\":\"abc\"}}}"
    })
    void returnsAFixedSafeReasonForMalformedInterestedPayloads(String malformedPayload) {
        PullRequestWebhookParser.ParseResult result = parser.parse(
                DELIVERY_ID, "pull_request", malformedPayload.getBytes(StandardCharsets.UTF_8));

        assertThat(result.status()).isEqualTo(INVALID);
        assertThat(result.event()).isNull();
        assertThat(result.reasonCode()).isEqualTo("INVALID_PULL_REQUEST_PAYLOAD");
    }

    @Test
    void returnsTheFixedSafeReasonForANullPayload() {
        PullRequestWebhookParser.ParseResult result = parser.parse(DELIVERY_ID, "pull_request", null);

        assertThat(result.status()).isEqualTo(INVALID);
        assertThat(result.event()).isNull();
        assertThat(result.reasonCode()).isEqualTo("INVALID_PULL_REQUEST_PAYLOAD");
    }

    @Test
    void rejectsTrailingContentAfterAnOtherwiseValidJsonDocument() {
        String validPayload = new String(interestedPayload("opened"), StandardCharsets.UTF_8);
        byte[] payloadWithTrailingDocument = (validPayload + "{}").getBytes(StandardCharsets.UTF_8);

        PullRequestWebhookParser.ParseResult result = parser.parse(
                DELIVERY_ID, "pull_request", payloadWithTrailingDocument);

        assertThat(result.status()).isEqualTo(INVALID);
        assertThat(result.event()).isNull();
        assertThat(result.reasonCode()).isEqualTo("INVALID_PULL_REQUEST_PAYLOAD");
    }

    @Test
    void rejectsAnOversizedBodyBeforeAttemptingJsonParsing() {
        byte[] oversizedMalformedPayload = "x".repeat(33).getBytes(StandardCharsets.UTF_8);
        PullRequestWebhookParser boundedParser = parserWithLimit(32);

        PullRequestWebhookParser.ParseResult result = boundedParser.parse(
                DELIVERY_ID, "pull_request", oversizedMalformedPayload);

        assertThat(result.status()).isEqualTo(PAYLOAD_TOO_LARGE);
        assertThat(result.event()).isNull();
        assertThat(result.reasonCode()).isEqualTo("WEBHOOK_PAYLOAD_TOO_LARGE");
        assertThat(result.toString()).doesNotContain("x".repeat(33));
    }

    @Test
    void acceptsAValidPayloadAtTheConfiguredByteLimit() {
        byte[] compactPayload = interestedPayload("opened");
        byte[] payloadAtLimit = (new String(compactPayload, StandardCharsets.UTF_8)
                + " ".repeat(8)).getBytes(StandardCharsets.UTF_8);
        PullRequestWebhookParser exactLimitParser = parserWithLimit(payloadAtLimit.length);

        assertThat(exactLimitParser.parse(DELIVERY_ID, "pull_request", payloadAtLimit).status())
                .isEqualTo(PARSED);
    }

    @Test
    void invalidResultsAndParsedEventsNeverRetainTheRawPayload() {
        byte[] payload = interestedPayload("opened");

        PullRequestWebhookParser.ParseResult parsed = parser.parse(DELIVERY_ID, "pull_request", payload);
        PullRequestWebhookParser.ParseResult invalid = parser.parse(
                DELIVERY_ID, "pull_request", "payload-marker".getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.toString()).doesNotContain("raw-body-must-not-survive");
        assertThat(invalid.toString()).doesNotContain("payload-marker");
        assertThat(VerifiedPullRequestEvent.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly(
                        "deliveryId", "action", "installationId", "repositoryId", "repositoryFullName",
                        "pullRequestNumber", "headSha", "cloneUrl", "observedAt");
    }

    private static PullRequestWebhookParser parserWithLimit(int maxWebhookBytes) {
        Clock clock = Clock.fixed(OBSERVED_AT, ZoneOffset.UTC);
        return new PullRequestWebhookParser(new ObjectMapper(), maxWebhookBytes, clock);
    }

    private static byte[] interestedPayload(String action) {
        String json = """
                {"action":"%s","installation":{"id":41},"repository":{"id":73,"full_name":"octo/repo","clone_url":"https://github.com/octo/repo.git"},"number":12,"pull_request":{"head":{"sha":"0123456789abcdef0123456789abcdef01234567"}},"sender":{"login":"raw-body-must-not-survive"}}
                """.formatted(action).strip();
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
