package dev.langchain4j.example.codereview.config;

import java.util.Map;
import java.util.Objects;

/** Fail-closed mapping between the effective reviewer model and token accounting contract. */
final class ReviewModelContextContract {

    private static final Map<String, Contract> SUPPORTED = Map.of(
            "moonshot-v1-8k", new Contract("cl100k_base", "jtokkit-1.1.0", 8_192));

    private ReviewModelContextContract() {
    }

    static void verify(String effectiveModel, ReviewWorkBudget.PromptLimits prompt) {
        Contract expected = SUPPORTED.get(effectiveModel);
        if (expected == null
                || !Objects.equals(effectiveModel, prompt.modelId())
                || !Objects.equals(expected.tokenizerId(), prompt.tokenizerId())
                || !Objects.equals(expected.tokenizerVersion(), prompt.tokenizerVersion())
                || expected.contextTokens() != prompt.modelContextTokens()) {
            throw new IllegalStateException(
                    "effective reviewer model has no matching validated prompt contract");
        }
    }

    private record Contract(String tokenizerId, String tokenizerVersion, int contextTokens) {
    }
}
