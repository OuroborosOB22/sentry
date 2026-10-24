package org.upgradeguard.sentry.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private Json() {
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException problem) {
            throw new IllegalArgumentException("not valid JSON: " + problem.getOriginalMessage(), problem);
        }
    }

    public static JsonNode read(Path file) throws IOException {
        return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static String text(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException problem) {
            throw new IllegalStateException(problem);
        }
    }

    public static void write(Path file, JsonNode node) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, text(node) + "\n", StandardCharsets.UTF_8);
    }
}
