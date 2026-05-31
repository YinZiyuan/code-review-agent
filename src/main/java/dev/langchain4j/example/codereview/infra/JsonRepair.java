package dev.langchain4j.example.codereview.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

public class JsonRepair {

    private static final Logger log = LoggerFactory.getLogger(JsonRepair.class);
    private static final Pattern BASE64_PAYLOAD = Pattern.compile("\\(base64: \"([A-Za-z0-9+/=]+)\"\\)");

    private final ChatModel model;
    private final ObjectMapper mapper;

    public JsonRepair(ChatModel model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    public <T> T parseOrRepair(String raw, Class<T> type) {
        String normalized = normalize(raw);
        try {
            return mapper.readValue(extractJson(normalized), type);
        } catch (JsonProcessingException first) {
            log.warn("JSON parse failed ({}); attempting repair", first.getOriginalMessage());
            String repaired = askForRepair(normalized);
            try {
                return mapper.readValue(extractJson(repaired), type);
            } catch (JsonProcessingException second) {
                throw new RepairFailedException(
                        "Repair did not produce valid JSON: " + second.getOriginalMessage(),
                        second);
            }
        }
    }

    public <T> T repairThenParse(String raw, Class<T> type) {
        String repaired = askForRepair(normalize(raw));
        try {
            return mapper.readValue(extractJson(repaired), type);
        } catch (JsonProcessingException e) {
            throw new RepairFailedException(
                    "Repair did not produce valid JSON: " + e.getOriginalMessage(),
                    e);
        }
    }

    private String askForRepair(String raw) {
        String prompt = """
                The following text was supposed to be a single JSON object but failed to parse.
                Return ONLY the corrected JSON - no prose, no markdown fences.
                Do NOT add, remove, or change semantic content; only fix syntax (quotes, commas, escapes).

                Broken JSON:
                """ + raw;
        var response = model.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build());
        return response.aiMessage().text();
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        var matcher = BASE64_PAYLOAD.matcher(raw);
        if (matcher.find()) {
            try {
                byte[] decoded = Base64.getDecoder().decode(matcher.group(1));
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                log.warn("Could not decode base64 JSON payload: {}", e.getMessage());
            }
        }
        return raw;
    }

    private String extractJson(String body) {
        if (body == null) {
            return "";
        }
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end < start) {
            return body;
        }
        return body.substring(start, end + 1);
    }

    public static class RepairFailedException extends RuntimeException {
        public RepairFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
