package org.upgradeguard.sentry.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SchemaValidator {
    private static final String BASE = "https://schemas.upgradeguard.invalid/v1/";
    private static final List<String> NAMES = List.of(
            "request", "evidence", "prediction_request", "prediction_response", "snapshot", "comparison", "worker_result");

    private final Map<String, String> sources = new HashMap<>();
    private final SchemaRegistry registry;

    public SchemaValidator() {
        for (String name : NAMES) {
            sources.put(BASE + name + ".schema.json", load(name));
        }
        registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(sources));
    }

    private static String load(String name) {
        String path = "/contracts/v1/" + name + ".schema.json";
        try (InputStream stream = SchemaValidator.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("missing contract schema " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException problem) {
            throw new IllegalStateException("could not read " + path, problem);
        }
    }

    public List<String> problems(String schemaName, JsonNode document) {
        Schema schema = registry.getSchema(com.networknt.schema.SchemaLocation.of(BASE + schemaName + ".schema.json"));
        List<String> found = new ArrayList<>();
        for (Error error : schema.validate(document)) {
            found.add(error.getInstanceLocation() + ": " + error.getMessage());
        }
        return found;
    }

    public void require(String schemaName, JsonNode document) {
        List<String> found = problems(schemaName, document);
        if (!found.isEmpty()) {
            throw new IllegalArgumentException(schemaName + " is not valid contract v1: " + String.join("; ", found.subList(0, Math.min(5, found.size()))));
        }
    }
}
