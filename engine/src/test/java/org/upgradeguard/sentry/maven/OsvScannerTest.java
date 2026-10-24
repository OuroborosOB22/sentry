package org.upgradeguard.sentry.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.upgradeguard.sentry.contract.Json;

class OsvScannerTest {
    private static ResolvedDependency dependency(String group, String artifact, String version) {
        return new ResolvedDependency(group, artifact, "jar", version, "compile");
    }

    @Test
    void asksForEveryDependencyAndReportsAdvisoriesPerArtifact() throws IOException {
        List<String> bodies = new ArrayList<>();
        OsvScanner scanner = new OsvScanner((url, body) -> {
            bodies.add(body);
            return "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-1\"},{\"id\":\"CVE-2\"}]},{}]}";
        });
        List<String> found = scanner.scan(List.of(dependency("org.apache.logging.log4j", "log4j-core", "2.14.1"), dependency("a", "b", "1")));
        assertEquals(List.of("org.apache.logging.log4j:log4j-core CVE-2", "org.apache.logging.log4j:log4j-core GHSA-1"), found);
        assertEquals("Maven", Json.parse(bodies.get(0)).path("queries").get(0).path("package").path("ecosystem").asText());
        assertEquals("org.apache.logging.log4j:log4j-core", Json.parse(bodies.get(0)).path("queries").get(0).path("package").path("name").asText());
    }

    @Test
    void anEmptyDependencyListNeedsNoRequest() throws IOException {
        assertTrue(new OsvScanner((url, body) -> {
            throw new AssertionError("should not be called");
        }).scan(List.of()).isEmpty());
    }

    @Test
    void anUnexpectedAnswerIsAnErrorNotAnEmptyResult() {
        OsvScanner scanner = new OsvScanner((url, body) -> "{\"results\":[]}");
        assertThrows(IOException.class, () -> scanner.scan(List.of(dependency("a", "b", "1"))));
    }

    @Test
    void aNetworkFailureSurfacesAsAnIoException() {
        OsvScanner scanner = new OsvScanner((url, body) -> {
            throw new IOException("offline");
        });
        assertThrows(IOException.class, () -> scanner.scan(List.of(dependency("a", "b", "1"))));
    }
}
