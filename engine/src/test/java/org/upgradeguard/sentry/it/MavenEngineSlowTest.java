package org.upgradeguard.sentry.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.upgradeguard.sentry.contract.SchemaValidator;
import org.upgradeguard.sentry.maven.MavenCoordinate;
import org.upgradeguard.sentry.maven.MavenInvoker;
import org.upgradeguard.sentry.maven.OsvScanner;
import org.upgradeguard.sentry.maven.UpgradeEngine;
import org.upgradeguard.sentry.maven.VerifyProfile;
import org.upgradeguard.sentry.source.SourceManifest;
import org.upgradeguard.sentry.verdict.Decision;

@Tag("slow")
class MavenEngineSlowTest {
    private static final MavenCoordinate LIB = MavenCoordinate.parse(FixtureWorld.GROUP + ":" + FixtureWorld.ARTIFACT);
    private static final String VERSION_1 = "<version>1.0.0</version>";

    @TempDir
    static Path shared;
    static FixtureWorld world;

    @BeforeAll
    static void buildTheLibrary() throws IOException {
        world = new FixtureWorld(shared);
    }

    private UpgradeEngine engine(OsvScanner.Transport transport) {
        String maven = Path.of("..", "mvnw").toAbsolutePath().normalize().toString();
        return new UpgradeEngine(new MavenInvoker(maven, null, null), new OsvScanner(transport));
    }

    private static OsvScanner.Transport noAdvisories() {
        return (url, body) -> {
            int queries = Math.max(1, com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body).path("queries").size());
            return "{\"results\":[" + "{},".repeat(queries - 1) + "{}]}";
        };
    }

    private UpgradeEngine.Outcome rehearse(Path project, String from, String to, Path out, OsvScanner.Transport transport, String tests) throws IOException {
        VerifyProfile profile = new VerifyProfile("maven-verify", List.of("verify"), world.properties(), Duration.ofMinutes(10), Duration.ofMinutes(10));
        return engine(transport).rehearse(new UpgradeEngine.Request(project, LIB, from, to, "run-" + tests, "REHEARSE_UPGRADE", out, profile, true));
    }

    private Path client(Path folder, String pom, String tests) throws IOException {
        return world.project(folder, pom, tests);
    }

    @Test
    void aCompatibleUpgradeIsAcceptedWithLinkedEvidenceAndLeavesTheProjectUntouched(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("p"), FixtureWorld.pom(VERSION_1, ""), FixtureWorld.PASSING_TESTS);
        String before = SourceManifest.of(project).manifestSha256();
        UpgradeEngine.Outcome outcome = rehearse(project, "1.0.0", "1.1.0", temp.resolve("e"), noAdvisories(), "compatible");
        assertEquals(Decision.ACCEPT, outcome.verdict().decision(), outcome.verdict().findings().toString());
        assertEquals(before, SourceManifest.of(project).manifestSha256());
        new SchemaValidator().require("evidence", outcome.evidence());
        assertEquals(2, outcome.evidence().path("after").path("tests").path("results").size());
        assertEquals("1.1.0", versionOf(outcome.evidence().path("after"), LIB.text()));
        assertEquals("1.0.0", versionOf(outcome.evidence().path("before"), LIB.text()));
        assertTrue(Files.readString(project.resolve("pom.xml")).contains("1.0.0"));
        assertTrue(outcome.evidence().path("artifact_manifest").size() > 8);
    }

    @Test
    void aBehaviourChangeThatBreaksATestIsARegression(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("p"), FixtureWorld.pom(VERSION_1, ""), FixtureWorld.PASSING_TESTS);
        UpgradeEngine.Outcome outcome = rehearse(project, "1.0.0", "1.2.0", temp.resolve("e"), noAdvisories(), "behaviour");
        assertEquals(Decision.REJECT, outcome.verdict().decision());
        assertTrue(outcome.verdict().codes().contains("test-regression"), outcome.verdict().codes().toString());
        assertEquals("client.HelloTest::addsAGreetingWord", outcome.evidence().path("diff").path("tests").path("regressions").get(0).asText());
        new SchemaValidator().require("evidence", outcome.evidence());
    }

    @Test
    void aRemovedMethodIsACompileFailureAndTheApiDiffSeesIt(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("p"), FixtureWorld.pom(VERSION_1, ""), FixtureWorld.PASSING_TESTS);
        UpgradeEngine.Outcome outcome = rehearse(project, "1.0.0", "2.0.0", temp.resolve("e"), noAdvisories(), "removed");
        assertEquals(Decision.REJECT, outcome.verdict().decision());
        assertTrue(outcome.verdict().codes().contains("upgrade-compile-failed"), outcome.verdict().codes().toString());
        JsonNode api = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(Files.readString(outcome.evidenceDir().resolve("api-diff.json")));
        assertTrue(api.path("available").asBoolean());
        assertTrue(api.toString().contains("demo.Greeter.greet"), api.toString());
        assertTrue(outcome.evidence().path("coverage").path("checked").toString().contains("library API differences"));
    }

    @Test
    void aVersionThatDoesNotExistIsRejectedAsABuildFailure(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("p"), FixtureWorld.pom(VERSION_1, ""), FixtureWorld.PASSING_TESTS);
        UpgradeEngine.Outcome outcome = rehearse(project, "1.0.0", "9.9.9", temp.resolve("e"), noAdvisories(), "missing");
        assertEquals(Decision.REJECT, outcome.verdict().decision());
        assertEquals(List.of("upgrade-build-failed"), outcome.verdict().codes());
    }

    @Test
    void aBaselineThatCannotBeBuiltIsInsufficientEvidence(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("p"), FixtureWorld.pom("<version>7.7.7</version>", ""), FixtureWorld.PASSING_TESTS);
        UpgradeEngine.Outcome outcome = rehearse(project, "7.7.7", "1.1.0", temp.resolve("e"), noAdvisories(), "nobaseline");
        assertEquals(Decision.INSUFFICIENT_EVIDENCE, outcome.verdict().decision());
        assertEquals(List.of("baseline-build-failed"), outcome.verdict().codes());
    }

    @Test
    void aClaimedStartingVersionThatIsNotWhatTheProjectUsesIsInsufficientEvidence(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("p"), FixtureWorld.pom(VERSION_1, ""), FixtureWorld.PASSING_TESTS);
        UpgradeEngine.Outcome outcome = rehearse(project, "1.1.0", "1.2.0", temp.resolve("e"), noAdvisories(), "wrongfrom");
        assertEquals(Decision.INSUFFICIENT_EVIDENCE, outcome.verdict().decision());
        assertEquals(List.of("baseline-version-mismatch"), outcome.verdict().codes());
    }

    @Test
    void aProjectWithoutTestsIsNeverAccepted(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("p"), FixtureWorld.pom(VERSION_1, ""), null);
        UpgradeEngine.Outcome outcome = rehearse(project, "1.0.0", "1.1.0", temp.resolve("e"), noAdvisories(), "notests");
        assertEquals(Decision.INSUFFICIENT_EVIDENCE, outcome.verdict().decision());
        assertTrue(outcome.verdict().codes().contains("tests-did-not-run"));
    }

    @Test
    void aVersionManagedByAParentIsReportedAsUnsupportedNotGuessed(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("p"), FixtureWorld.pom("", ""), FixtureWorld.PASSING_TESTS);
        UpgradeEngine.Outcome outcome = rehearse(project, "1.0.0", "1.1.0", temp.resolve("e"), noAdvisories(), "managed");
        assertEquals(Decision.INSUFFICIENT_EVIDENCE, outcome.verdict().decision());
        assertEquals(List.of("unsupported-project"), outcome.verdict().codes());
        new SchemaValidator().require("evidence", outcome.evidence());
    }

    @Test
    void aNewAdvisoryFromTheScannerRejectsEvenWhenEverythingElseIsFine(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("p"), FixtureWorld.pom(VERSION_1, ""), FixtureWorld.PASSING_TESTS);
        OsvScanner.Transport scanner = (url, body) -> {
            JsonNode queries = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(body).path("queries");
            StringBuilder answer = new StringBuilder("{\"results\":[");
            for (int index = 0; index < queries.size(); index++) {
                boolean newVersion = queries.get(index).path("version").asText().equals("1.1.0");
                answer.append(index > 0 ? "," : "").append(newVersion && queries.get(index).path("package").path("name").asText().equals(LIB.text())
                        ? "{\"vulns\":[{\"id\":\"GHSA-test-0001\"}]}" : "{}");
            }
            return answer.append("]}").toString();
        };
        UpgradeEngine.Outcome outcome = rehearse(project, "1.0.0", "1.1.0", temp.resolve("e"), scanner, "advisory");
        assertEquals(Decision.REJECT, outcome.verdict().decision());
        assertEquals(List.of("new-vulnerability"), outcome.verdict().codes().stream().filter(code -> !code.equals("transitive-changes")).toList());
    }

    @Test
    void anUnreachableAdvisoryServiceIsDisclosedAndNeverCountedAsClean(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("p"), FixtureWorld.pom(VERSION_1, ""), FixtureWorld.PASSING_TESTS);
        OsvScanner.Transport down = (url, body) -> {
            throw new IOException("network down");
        };
        UpgradeEngine.Outcome outcome = rehearse(project, "1.0.0", "1.1.0", temp.resolve("e"), down, "scannerdown");
        assertEquals(Decision.ACCEPT, outcome.verdict().decision());
        assertTrue(outcome.verdict().codes().contains("vulnerability-scan-unavailable"));
        assertTrue(outcome.evidence().path("coverage").path("unavailable").toString().contains("OSV"));
        assertFalse(outcome.evidence().path("before").path("scanner_results").get(0).path("available").asBoolean());
    }

    @Test
    void theRealAdvisoryServiceKnowsAboutLog4Shell() throws Exception {
        OsvScanner scanner = new OsvScanner(OsvScanner.httpTransport());
        List<String> found = scanner.scan(List.of(new org.upgradeguard.sentry.maven.ResolvedDependency("org.apache.logging.log4j", "log4j-core", "jar", "2.14.1", "compile")));
        assertTrue(found.stream().anyMatch(item -> item.contains("GHSA-jfh8-c2jp-5v3q")), found.toString());
        assertNotEquals(0, found.size());
    }

    private static String versionOf(JsonNode side, String name) {
        for (JsonNode item : side.path("resolved_dependencies")) {
            if (item.path("name").asText().equals(name)) {
                return item.path("version").asText();
            }
        }
        return null;
    }
}
