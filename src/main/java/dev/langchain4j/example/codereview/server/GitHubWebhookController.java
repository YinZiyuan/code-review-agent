package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.application.ObservePullRequestRevision;
import dev.langchain4j.example.codereview.reviewops.application.PullRequestObservationStore.ObservationResult;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.GitHubWebhookVerifier;
import dev.langchain4j.example.codereview.reviewops.infrastructure.github.PullRequestWebhookParser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@RestController
@ConditionalOnProperty(name = "code-review.runtime", havingValue = "server")
@Conditional(WebhookSecretConfiguredCondition.class)
public final class GitHubWebhookController {

    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String DELIVERY_HEADER = "X-GitHub-Delivery";
    private static final String EVENT_HEADER = "X-GitHub-Event";
    private static final String PULL_REQUEST_EVENT = "pull_request";
    private static final String WEBHOOK_EVENTS_METRIC = "code_review_webhook_events_total";

    private final GitHubWebhookVerifier verifier;
    private final PullRequestWebhookParser parser;
    private final ObservePullRequestRevision observations;
    private final MeterRegistry meterRegistry;
    private final int maxWebhookBytes;

    public GitHubWebhookController(
            GitHubWebhookVerifier verifier,
            PullRequestWebhookParser parser,
            ObservePullRequestRevision observations,
            MeterRegistry meterRegistry,
            ServerProperties serverProperties) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.maxWebhookBytes = Objects.requireNonNull(serverProperties, "serverProperties")
                .github().maxWebhookBytes();
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Void> receive(
            @RequestHeader HttpHeaders headers,
            @RequestBody byte[] payload) {
        String eventName = headers.getFirst(EVENT_HEADER);
        record(metricEvent(eventName), "received");

        String signature;
        String deliveryId;
        try {
            signature = requiredSingleHeader(headers, SIGNATURE_HEADER);
            deliveryId = requiredSingleHeader(headers, DELIVERY_HEADER);
            eventName = requiredSingleHeader(headers, EVENT_HEADER);
        } catch (WebhookRequestException exception) {
            record(metricEvent(eventName), "invalid");
            throw exception;
        }

        if (payload == null || payload.length > maxWebhookBytes) {
            record(metricEvent(eventName), "invalid");
            throw WebhookRequestException.payloadTooLarge();
        }
        if (!verifier.verify(payload, signature)) {
            record(metricEvent(eventName), "signature_failure");
            throw WebhookRequestException.invalidSignature();
        }

        PullRequestWebhookParser.ParseResult parsed = parser.parse(deliveryId, eventName, payload);
        return switch (parsed.status()) {
            case IGNORED -> {
                record(metricEvent(eventName), "ignored");
                yield ResponseEntity.accepted().build();
            }
            case INVALID -> {
                record(metricEvent(eventName), "invalid");
                throw WebhookRequestException.invalidPayload();
            }
            case PAYLOAD_TOO_LARGE -> {
                record(metricEvent(eventName), "invalid");
                throw WebhookRequestException.payloadTooLarge();
            }
            case PARSED -> admitted(parsed, payload, eventName);
        };
    }

    private ResponseEntity<Void> admitted(
            PullRequestWebhookParser.ParseResult parsed,
            byte[] payload,
            String eventName) {
        ObservationResult result = observations.observe(parsed.event(), sha256(payload));
        record(metricEvent(eventName), switch (result.status()) {
            case ADMITTED -> "admitted";
            case DUPLICATE_DELIVERY -> "duplicate";
            case EXISTING_REVISION -> "existing_revision";
        });
        return ResponseEntity.accepted().build();
    }

    private static String requiredSingleHeader(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        if (values == null || values.size() != 1 || values.get(0) == null || values.get(0).isBlank()) {
            throw WebhookRequestException.malformedRequest();
        }
        return values.get(0);
    }

    private void record(String event, String outcome) {
        Counter.builder(WEBHOOK_EVENTS_METRIC)
                .tags("event", event, "outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    private static String metricEvent(String eventName) {
        return PULL_REQUEST_EVENT.equals(eventName) ? PULL_REQUEST_EVENT : "other";
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
