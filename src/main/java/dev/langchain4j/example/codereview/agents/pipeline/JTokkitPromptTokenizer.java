package dev.langchain4j.example.codereview.agents.pipeline;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingResult;
import com.knuddels.jtokkit.api.EncodingType;

public final class JTokkitPromptTokenizer implements PromptTokenizer {

    private final Encoding encoding;

    public JTokkitPromptTokenizer() {
        this("cl100k_base", "jtokkit-1.1.0");
    }

    public JTokkitPromptTokenizer(String tokenizerId, String tokenizerVersion) {
        if (!"cl100k_base".equals(tokenizerId) || !"jtokkit-1.1.0".equals(tokenizerVersion)) {
            throw new IllegalArgumentException("unsupported prompt tokenizer contract");
        }
        this.encoding = Encodings.newDefaultEncodingRegistry()
                .getEncoding(EncodingType.CL100K_BASE);
    }

    @Override
    public int count(String text) {
        return encoding.countTokensOrdinary(text == null ? "" : text);
    }

    @Override
    public String truncate(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        EncodingResult encoded = encoding.encodeOrdinary(text, maxTokens);
        if (!encoded.isTruncated()) {
            return text;
        }
        int end = Math.max(0, encoded.getLastProcessedCharacterIndex());
        return text.substring(0, end);
    }
}
