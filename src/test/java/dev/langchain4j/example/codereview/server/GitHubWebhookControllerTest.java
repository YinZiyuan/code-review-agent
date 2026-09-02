package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.application.ObservePullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationResult;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationStatus;
import dev.langchain4j.example.codereview.reviewops.application.github.VerifiedPullRequestEvent;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewRunId;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.GitHubWebhookVerifier;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser.ParseStatus.IGNORED;
import static dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser.ParseStatus.INVALID;
import static dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser.ParseStatus.PARSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GitHubWebhookController.class)
@Import({WebhookExceptionHandler.class, GitHubWebhookControllerTest.TestBeans.class})
@TestPropertySource(properties = {
        "code-review.runtime=server",
        "code-review.server.github.webhook-secret=test's-webhook-secret"
})
class GitHubWebhookControllerTest {

    private static final String DELIVERY_ID = "delivery-123";
    private static final String PULL_REQUEST_EVENT = "pull_request";
    private static final String VALID_SIGNATURE = "sha256=" + "a".repeat(64);

    @jakarta.annotation.Resource
    private MockMvc mockMvc;

    @jakarta.annotation.Resource
    private MeterRegistry meterRegistry;

    @MockitoBean
    private GitHubWebhookVerifier verifier;

    @MockitoBean
    private PullRequestWebhookParser parser;

    @MockitoBean
    private ObservePullRequestRevision observePullRequestRevision;

    @Test
    void passesTheExactRawBytesToTheVerifierAndDoesNotParseAnInvalidSignature() throws Exception {
        byte[] rawPayload = "raw\nbytes\u0000stay-unchanged".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(any(byte[].class), anyString())).thenReturn(false);

        mockMvc.perform(webhook(rawPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("INVALID_SIGNATURE"));

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(verifier).verify(payloads.capture(), eq(VALID_SIGNATURE));
        assertThat(payloads.getValue()).containsExactly(rawPayload);
        verify(parser, never()).parse(anyString(), anyString(), any(byte[].class));
        verify(observePullRequestRevision, never()).observe(any(), anyString());
    }

    @Test
    void rejectsMissingRequiredHeadersBeforeVerifyingOrParsing() throws Exception {
        mockMvc.perform(post("/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("MALFORMED_WEBHOOK_REQUEST"));

        verify(verifier, never()).verify(any(byte[].class), anyString());
        verify(parser, never()).parse(anyString(), anyString(), any(byte[].class));
        verify(observePullRequestRevision, never()).observe(any(), anyString());
        assertThat(meterRegistry.find("code_review_webhook_events_total")
                .tags("event", "other", "outcome", "invalid")
                .counter()).isNotNull();
    }

    @Test
    void rejectsAnOversizedPayloadBeforeVerificationOrJsonParsing() throws Exception {
        mockMvc.perform(webhook("x".repeat(33).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().string("WEBHOOK_PAYLOAD_TOO_LARGE"));

        verify(verifier, never()).verify(any(byte[].class), anyString());
        verify(parser, never()).parse(anyString(), anyString(), any(byte[].class));
        verify(observePullRequestRevision, never()).observe(any(), anyString());
    }

    @Test
    void admitsAParsedVerifiedEventUsingTheSha256OfTheOriginalBytes() throws Exception {
        byte[] rawPayload = "abc".getBytes(StandardCharsets.UTF_8);
        VerifiedPullRequestEvent event = verifiedEvent();
        when(verifier.verify(rawPayload, VALID_SIGNATURE)).thenReturn(true);
        when(parser.parse(DELIVERY_ID, PULL_REQUEST_EVENT, rawPayload))
                .thenReturn(new PullRequestWebhookParser.ParseResult(PARSED, event, null));
        when(observePullRequestRevision.observe(event,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
                .thenReturn(new ObservationResult(ObservationStatus.ADMITTED, ReviewRunId.newId()));

        mockMvc.perform(webhook(rawPayload))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(observePullRequestRevision).observe(event,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void acceptsDuplicateAndExistingRevisionAdmissionsWithoutExecutingAReview() throws Exception {
        byte[] rawPayload = "abc".getBytes(StandardCharsets.UTF_8);
        VerifiedPullRequestEvent event = verifiedEvent();
        when(verifier.verify(rawPayload, VALID_SIGNATURE)).thenReturn(true);
        when(parser.parse(DELIVERY_ID, PULL_REQUEST_EVENT, rawPayload))
                .thenReturn(new PullRequestWebhookParser.ParseResult(PARSED, event, null));
        when(observePullRequestRevision.observe(eq(event), anyString()))
                .thenReturn(new ObservationResult(ObservationStatus.DUPLICATE_DELIVERY, ReviewRunId.newId()))
                .thenReturn(new ObservationResult(ObservationStatus.EXISTING_REVISION, ReviewRunId.newId()));

        mockMvc.perform(webhook(rawPayload)).andExpect(status().isAccepted());
        mockMvc.perform(webhook(rawPayload)).andExpect(status().isAccepted());
    }

    @Test
    void acceptsVerifiedIgnoredEventsWithoutAdmission() throws Exception {
        byte[] rawPayload = "not-json-but-ignored".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(rawPayload, VALID_SIGNATURE)).thenReturn(true);
        when(parser.parse(DELIVERY_ID, PULL_REQUEST_EVENT, rawPayload))
                .thenReturn(new PullRequestWebhookParser.ParseResult(IGNORED, null, null));

        mockMvc.perform(webhook(rawPayload))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(observePullRequestRevision, never()).observe(any(), anyString());
    }

    @Test
    void returnsOnlyTheFixedCodeForMalformedInterestedPayloads() throws Exception {
        byte[] rawPayload = "malformed-payload-must-not-leak".getBytes(StandardCharsets.UTF_8);
        when(verifier.verify(rawPayload, VALID_SIGNATURE)).thenReturn(true);
        when(parser.parse(DELIVERY_ID, PULL_REQUEST_EVENT, rawPayload))
                .thenReturn(new PullRequestWebhookParser.ParseResult(
                        INVALID, null, PullRequestWebhookParser.INVALID_PAYLOAD_REASON));

        mockMvc.perform(webhook(rawPayload))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("INVALID_PULL_REQUEST_PAYLOAD"));

        verify(observePullRequestRevision, never()).observe(any(), anyString());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webhook(byte[] payload) {
        return post("/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", VALID_SIGNATURE)
                .header("X-GitHub-Delivery", DELIVERY_ID)
                .header("X-GitHub-Event", PULL_REQUEST_EVENT)
                .content(payload);
    }

    private static VerifiedPullRequestEvent verifiedEvent() {
        return new VerifiedPullRequestEvent(
                DELIVERY_ID,
                "opened",
                41L,
                73L,
                "octo/repo",
                12,
                "0123456789abcdef0123456789abcdef01234567",
                "https://github.com/octo/repo.git",
                Instant.parse("2026-09-01T04:05:06Z"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        ServerProperties serverProperties() {
            return new ServerProperties(new ServerProperties.GitHub(
                    0L, "", "", 32, Duration.ofSeconds(5), Duration.ofSeconds(30)), null);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
