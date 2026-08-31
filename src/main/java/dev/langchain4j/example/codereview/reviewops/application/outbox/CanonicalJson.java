package dev.langchain4j.example.codereview.reviewops.application.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CanonicalJson() {
    }

    static String canonicalize(String rawJson) {
        Objects.requireNonNull(rawJson, "payload");
        if (rawJson.isBlank()) {
            throw new IllegalArgumentException("payload must be valid JSON");
        }
        try {
            JsonNode parsed = MAPPER.readTree(rawJson);
            if (parsed == null || parsed.isMissingNode()) {
                throw new IllegalArgumentException("payload must be valid JSON");
            }
            return MAPPER.writeValueAsString(sorted(parsed));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("payload must be valid JSON", exception);
        }
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sortedObject = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            names.forEach(name -> sortedObject.set(name, sorted(node.get(name))));
            return sortedObject;
        }
        if (node.isArray()) {
            ArrayNode orderedArray = MAPPER.createArrayNode();
            node.forEach(element -> orderedArray.add(sorted(element)));
            return orderedArray;
        }
        return node;
    }
}
