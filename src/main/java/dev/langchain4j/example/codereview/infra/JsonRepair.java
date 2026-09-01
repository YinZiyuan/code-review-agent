package dev.langchain4j.example.codereview.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

public class JsonRepair {

    public record ParseResult<T>(T value, int inputTokens, int outputTokens) {
        public ParseResult {
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("model token usage must be non-negative");
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(JsonRepair.class);
    private static final Pattern BASE64_PAYLOAD = Pattern.compile("\\(base64: \"([A-Za-z0-9+/=]+)\"\\)");

    private final ChatModel model;
    private final ObjectMapper mapper;
    private final ObjectMapper snakeCaseMapper;

    public JsonRepair(ChatModel model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
        this.snakeCaseMapper = mapper.copy()
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    public <T> T parseOrRepair(String raw, Class<T> type) {
        return parseOrRepairResponse(raw, type).value();
    }

    public <T> ParseResult<T> parseOrRepairWithUsage(String raw, Class<T> type) {
        ParsedResponse<T> parsed = parseOrRepairResponse(raw, type);
        if (parsed.repairResponse() == null) {
            return new ParseResult<>(parsed.value(), 0, 0);
        }
        return new ParseResult<>(
                parsed.value(),
                requiredInputTokens(parsed.repairResponse()),
                requiredOutputTokens(parsed.repairResponse()));
    }

    private <T> ParsedResponse<T> parseOrRepairResponse(String raw, Class<T> type) {
        String normalized = normalize(raw);
        try {
            return new ParsedResponse<>(readValue(extractJson(normalized), type), null);
        } catch (JsonProcessingException first) {
            log.warn("JSON parse failed ({}); attempting repair", first.getOriginalMessage());
            ChatResponse repairResponse = askForRepair(normalized);
            try {
                return new ParsedResponse<>(
                        readValue(extractJson(repairResponse.aiMessage().text()), type),
                        repairResponse);
            } catch (JsonProcessingException second) {
                throw new RepairFailedException(
                        "Repair did not produce valid JSON: " + second.getOriginalMessage(),
                        second,
                        requiredInputTokens(repairResponse),
                        requiredOutputTokens(repairResponse));
            }
        }
    }

    public <T> T repairThenParse(String raw, Class<T> type) {
        ChatResponse repairResponse = askForRepair(normalize(raw));
        try {
            return readValue(extractJson(repairResponse.aiMessage().text()), type);
        } catch (JsonProcessingException e) {
            throw new RepairFailedException(
                    "Repair did not produce valid JSON: " + e.getOriginalMessage(),
                    e,
                    requiredInputTokens(repairResponse),
                    requiredOutputTokens(repairResponse));
        }
    }

    private ChatResponse askForRepair(String raw) {
        String prompt = """
                The following text was supposed to be a single JSON object but failed to parse.
                Return ONLY the corrected JSON - no prose, no markdown fences.
                Do NOT add, remove, or change semantic content; only fix syntax (quotes, commas, escapes).

                Broken JSON:
                """ + raw;
        return model.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build());
    }

    private static int requiredInputTokens(ChatResponse response) {
        if (response.tokenUsage() == null || response.tokenUsage().inputTokenCount() == null) {
            throw new IllegalStateException("repair response did not include input token usage");
        }
        return response.tokenUsage().inputTokenCount();
    }

    private static int requiredOutputTokens(ChatResponse response) {
        if (response.tokenUsage() == null || response.tokenUsage().outputTokenCount() == null) {
            throw new IllegalStateException("repair response did not include output token usage");
        }
        return response.tokenUsage().outputTokenCount();
    }

    private <T> T readValue(String json, Class<T> type) throws JsonProcessingException {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException first) {
            try {
                return snakeCaseMapper.readValue(json, type);
            } catch (JsonProcessingException ignored) {
                throw first;
            }
        }
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
        private final int inputTokens;
        private final int outputTokens;

        public RepairFailedException(
                String message, Throwable cause, int inputTokens, int outputTokens) {
            super(message, cause);
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("model token usage must be non-negative");
            }
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        public int inputTokens() {
            return inputTokens;
        }

        public int outputTokens() {
            return outputTokens;
        }
    }

    private record ParsedResponse<T>(T value, ChatResponse repairResponse) {
    }
}
