package org.upgradeguard.sentry.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {
    private static final Path FIXTURES = Path.of("..", "contracts", "v1", "fixtures");
    private static final List<String> NAMES = List.of(
            "request", "evidence", "prediction_request", "prediction_response", "snapshot", "comparison", "worker_result");
    private final SchemaValidator validator = new SchemaValidator();

    private static String schemaFor(Path fixture) {
        String file = fixture.getFileName().toString();
        return NAMES.stream().sorted(Comparator.comparingInt(String::length).reversed()).filter(file::startsWith).findFirst().orElseThrow();
    }

    private static List<Path> fixtures(String kind) throws IOException {
        try (Stream<Path> files = Files.list(FIXTURES.resolve(kind))) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void everyValidFixtureIsAcceptedByTheJavaValidator() throws IOException {
        List<Path> valid = fixtures("valid");
        assertTrue(valid.size() >= 8, "expected the shared fixtures to be present");
        for (Path fixture : valid) {
            assertEquals(List.of(), validator.problems(schemaFor(fixture), Json.read(fixture)), fixture.toString());
        }
    }

    @Test
    void everyInvalidFixtureIsRejectedByTheJavaValidator() throws IOException {
        List<Path> invalid = fixtures("invalid");
        assertTrue(invalid.size() >= 8, "expected the shared fixtures to be present");
        for (Path fixture : invalid) {
            assertFalse(validator.problems(schemaFor(fixture), Json.read(fixture)).isEmpty(), fixture.toString());
        }
    }

    @Test
    void requireThrowsWithTheReasonForAnInvalidDocument() throws IOException {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                () -> validator.require("request", Json.read(FIXTURES.resolve("invalid/request_bad_version.json"))));
        assertTrue(problem.getMessage().contains("not valid contract v1"));
    }

    @Test
    void jsonHelperRejectsGarbageWithAClearMessage() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{not json"));
    }
}
