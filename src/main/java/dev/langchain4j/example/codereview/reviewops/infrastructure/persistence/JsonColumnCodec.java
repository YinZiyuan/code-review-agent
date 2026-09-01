package dev.langchain4j.example.codereview.reviewops.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.example.codereview.reviewops.domain.CitationEvidence;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JsonColumnCodec {

    private static final TypeReference<Map<String, String>> TOOL_STATES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<CitationEvidence>> CITATIONS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public JsonColumnCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    String encodeToolStates(Map<String, String> toolStates) {
        return encode(Objects.requireNonNull(toolStates, "toolStates"));
    }

    Map<String, String> decodeToolStates(String persistedJson) {
        return decode(persistedJson, TOOL_STATES_TYPE, "tool states");
    }

    String encodeCitations(List<CitationEvidence> citations) {
        return encode(Objects.requireNonNull(citations, "citations"));
    }

    List<CitationEvidence> decodeCitations(String persistedJson) {
        return decode(persistedJson, CITATIONS_TYPE, "citations");
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not encode JSON column", exception);
        }
    }

    private <T> T decode(String persistedJson, TypeReference<T> type, String valueName) {
        try {
            T decoded = objectMapper.readValue(
                    Objects.requireNonNull(persistedJson, "persistedJson"), type);
            if (decoded == null) {
                throw new IllegalStateException(
                        "Persisted " + valueName + " JSON must not be literal null");
            }
            return decoded;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Malformed persisted JSON column", exception);
        }
    }
}
