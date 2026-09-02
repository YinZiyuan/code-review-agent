package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.config.CodeReviewProperties;
import dev.langchain4j.example.codereview.eval.ModelRuntimeMetadata;
import dev.langchain4j.example.codereview.eval.ModelRuntimeMetadataResolver;
import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Builds the persisted run identity from an explicit allow-list of non-secret runtime settings. */
public final class ReviewConfigurationSnapshotFactory {

    private static final String MODEL_PREFIX = "langchain4j.open-ai.chat-model.";

    private final Environment environment;

    public ReviewConfigurationSnapshotFactory(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public ReviewConfigurationSnapshot create(
            CodeReviewProperties properties,
            ReviewIdentityProperties identity,
            ServerProperties.Worker worker) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(worker, "worker");
        ModelRuntimeMetadata model = new ModelRuntimeMetadataResolver(environment).resolve();
        if ("unknown".equals(model.reviewerModel())) {
            throw new IllegalStateException("Reviewer model name must be configured");
        }

        Map<String, String> settings = new TreeMap<>();
        put(settings, "pipeline.version", identity.pipelineVersion());
        put(settings, "prompt.version", identity.promptVersion());
        put(settings, "policy.version", identity.policyVersion());
        put(settings, "policy.max-inline-comments", identity.maxInlineComments());
        put(settings, "work-budget.identity", identity.workBudgetIdentity());
        put(settings, "retry.max-review-attempts", identity.maxReviewAttempts());
        put(settings, "retry.initial-backoff", worker.initialBackoff());
        put(settings, "retry.max-backoff", worker.maxBackoff());
        put(settings, "retry.jitter-ratio", decimal(worker.jitterRatio()));

        put(settings, "model.provider", model.provider());
        put(settings, "model.endpoint", safeEndpoint(environment.getProperty(MODEL_PREFIX + "base-url")));
        put(settings, "model.name", model.reviewerModel());
        put(settings, "model.temperature", decimal(property("temperature", "0")));
        put(settings, "model.max-tokens", integer(property("max-tokens", "4096")));
        put(settings, "model.timeout", duration(property("timeout", "90s")));

        CodeReviewProperties.Rag rag = Objects.requireNonNull(properties.rag(), "rag");
        put(settings, "rag.top-k", rag.topK());
        put(settings, "rag.min-score", decimal(rag.minScore()));
        put(settings, "rag.rerank-enabled", rag.rerankEnabled());
        put(settings, "rag.bm25-top-k", rag.bm25TopK());
        put(settings, "rag.rerank-top-k", rag.rerankTopK());
        put(settings, "rag.rrf-k", rag.rrfK());

        CodeReviewProperties.Orchestration orchestration =
                Objects.requireNonNull(properties.orchestration(), "orchestration");
        put(settings, "orchestration.reviewer-timeout", orchestration.reviewerTimeout());
        put(settings, "orchestration.parallelism", orchestration.parallelism());

        return new ReviewConfigurationSnapshot(
                identity.pipelineVersion(),
                "cfg-sha256-" + hash(settings),
                model.reviewerModel(),
                identity.policyVersion(),
                identity.maxReviewAttempts());
    }

    private String property(String suffix, String fallback) {
        String value = environment.getProperty(MODEL_PREFIX + suffix);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String safeEndpoint(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        try {
            URI uri = URI.create(value.trim()).normalize();
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "unknown";
            }
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            String path = uri.getPath() == null ? "" : uri.getPath();
            return uri.getScheme().toLowerCase() + "://"
                    + uri.getHost().toLowerCase() + port + path;
        } catch (IllegalArgumentException invalidUri) {
            return "unknown";
        }
    }

    private static String decimal(String value) {
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String integer(String value) {
        return Integer.toString(Integer.parseInt(value));
    }

    private static String duration(String value) {
        return DurationStyle.detectAndParse(value).toString();
    }

    private static String hash(Map<String, String> settings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            settings.forEach((key, value) -> {
                update(digest, key);
                update(digest, value);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void put(Map<String, String> settings, String key, Object value) {
        settings.put(key, Objects.requireNonNull(value, key).toString());
    }
}
