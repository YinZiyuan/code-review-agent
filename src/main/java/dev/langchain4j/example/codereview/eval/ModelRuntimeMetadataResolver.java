package dev.langchain4j.example.codereview.eval;

import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.Locale;

public class ModelRuntimeMetadataResolver {

    private static final String BASE_URL = "langchain4j.open-ai.chat-model.base-url";
    private static final String MODEL_NAME = "langchain4j.open-ai.chat-model.model-name";

    private final Environment environment;

    public ModelRuntimeMetadataResolver(Environment environment) {
        this.environment = environment;
    }

    public ModelRuntimeMetadata resolve() {
        String host = host(environment.getProperty(BASE_URL));
        String model = valueOrUnknown(environment.getProperty(MODEL_NAME));
        return new ModelRuntimeMetadata(provider(host), host, model, model, model);
    }

    private String host(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "unknown";
        }
        try {
            return valueOrUnknown(URI.create(baseUrl).getHost());
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }

    private String provider(String host) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if ("api.openai.com".equals(normalizedHost)) {
            return "openai";
        }
        if ("moonshot.cn".equals(normalizedHost) || normalizedHost.endsWith(".moonshot.cn")) {
            return "moonshot";
        }
        return "unknown".equals(host) ? "unknown" : "openai-compatible";
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
