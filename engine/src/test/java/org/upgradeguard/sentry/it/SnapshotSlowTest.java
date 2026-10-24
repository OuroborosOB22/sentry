package org.upgradeguard.sentry.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.upgradeguard.sentry.SentryCli;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.contract.SchemaValidator;
import org.upgradeguard.sentry.maven.MavenInvoker;
import org.upgradeguard.sentry.maven.VerifyProfile;
import org.upgradeguard.sentry.snapshot.ComparisonEngine;
import org.upgradeguard.sentry.snapshot.SnapshotEngine;
import org.upgradeguard.sentry.source.SourceManifest;

@Tag("slow")
class SnapshotSlowTest {
    @TempDir
    static Path shared;
    static FixtureWorld world;

    @BeforeAll
    static void buildTheLibrary() throws Exception {
        world = new FixtureWorld(shared);
    }

    private static Path maven() {
        return Path.of("..", "mvnw").toAbsolutePath().normalize();
    }

    private SnapshotEngine engine() {
        return new SnapshotEngine(new MavenInvoker(maven().toString(), null, null));
    }

    private VerifyProfile profile() {
        return new VerifyProfile("maven-verify", List.of("verify"), world.properties(), Duration.ofMinutes(10), Duration.ofMinutes(10));
    }

    private Path client(Path folder, String version, String tests) throws Exception {
        return world.project(folder, FixtureWorld.pom("<version>" + version + "</version>", ""), tests);
    }

    @Test
    void aGreenRunIsRecordedOnceAndNeverStoresSecrets(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("client"), "1.0.0", FixtureWorld.PASSING_TESTS);
        Files.writeString(project.resolve(".env"), "TOKEN=abc123");
        Files.writeString(project.resolve("server.pem"), "-----BEGIN PRIVATE KEY-----");
        SnapshotEngine.Result first = engine().capture(project, temp.resolve("store"), profile(), true);
        assertEquals("PASSING", first.snapshot().path("eligibility").asText());
        assertFalse(first.alreadyRecorded());
        new SchemaValidator().require("snapshot", first.snapshot());
        assertEquals(2, first.snapshot().path("tests").path("results").size());
        try (ZipFile zip = new ZipFile(first.folder().resolve("source.zip").toFile())) {
            assertTrue(zip.getEntry("pom.xml") != null);
            assertEquals(null, zip.getEntry(".env"));
            assertEquals(null, zip.getEntry("server.pem"));
        }
        assertFalse(Files.readString(first.folder().resolve("manifest.json")).contains("abc123"));
        assertEquals(SourceManifest.of(project).manifestSha256(), first.snapshot().path("source").path("manifest_sha256").asText());
        SnapshotEngine.Result again = engine().capture(project, temp.resolve("store"), profile(), true);
        assertTrue(again.alreadyRecorded());
        assertEquals(first.snapshot().path("snapshot_id").asText(), again.snapshot().path("snapshot_id").asText());
    }

    @Test
    void aBrokenChangeIsStartingOnlyAndTheComparisonPointsAtTheLead(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("client"), "1.0.0", FixtureWorld.PASSING_TESTS);
        SnapshotEngine.Result good = engine().capture(project, temp.resolve("store"), profile(), true);
        Files.writeString(project.resolve("src/test/java/client/HelloTest.java"), FixtureWorld.PASSING_TESTS.replace("startsWith(\"Hello\")", "startsWith(\"Nope\")"));
        SnapshotEngine.Result broken = engine().capture(project, temp.resolve("store"), profile(), true);
        assertEquals("STARTING_ONLY", broken.snapshot().path("eligibility").asText());
        JsonNode comparison = ComparisonEngine.compare(good.folder(), broken.folder());
        assertEquals("client.HelloTest::addsAGreetingWord", comparison.path("changes").path("tests").path("newly_failing").get(0).asText());
        assertEquals("src/test/java/client/HelloTest.java", comparison.path("changes").path("code").path("changed").get(0).asText());
        assertTrue(comparison.path("warnings").toString().contains("lead, not proof"));
    }

    @Test
    void unverifiedAndTestlessSnapshotsAreLabelledHonestly(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("client"), "1.0.0", FixtureWorld.PASSING_TESTS);
        SnapshotEngine.Result starting = engine().capture(project, temp.resolve("store"), profile(), false);
        assertEquals("STARTING_ONLY", starting.snapshot().path("eligibility").asText());
        assertFalse(starting.snapshot().path("tests").path("verified").asBoolean());
        Path testless = client(temp.resolve("testless"), "1.0.0", null);
        SnapshotEngine.Result none = engine().capture(testless, temp.resolve("store2"), profile(), true);
        assertEquals("INCONCLUSIVE", none.snapshot().path("eligibility").asText());
    }

    @Test
    void recoveryTriesTheOlderVersionInSeparateCopiesAndLeavesTheProjectAlone(@TempDir Path temp) throws Exception {
        Path project = client(temp.resolve("client"), "1.0.0", FixtureWorld.PASSING_TESTS);
        SnapshotEngine.Result good = engine().capture(project, temp.resolve("store"), profile(), true);
        Files.writeString(project.resolve("pom.xml"), Files.readString(project.resolve("pom.xml")).replace("<version>1.0.0</version>\n    </dependency>\n    <dependency>\n      <groupId>org.junit",
                "<version>1.2.0</version>\n    </dependency>\n    <dependency>\n      <groupId>org.junit"));
        String before = SourceManifest.of(project).manifestSha256();
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = SentryCli.run(new String[] {"recover", "--project", project.toString(), "--package", "org.upgradeguard.fixture:demo-lib", "--store", temp.resolve("store").toString(),
                    "--project-id", "client", "--snapshot", good.snapshot().path("snapshot_id").asText(), "--out", temp.resolve("evidence").toString(),
                    "--property", "fixture.repo=" + world.repository(), "--maven", maven().toString(), "--offline-scan", "--json", "--run-id", "recovery-run"});
        } finally {
            System.setOut(original);
        }
        assertEquals(0, code, captured.toString(StandardCharsets.UTF_8));
        JsonNode evidence = Json.parse(captured.toString(StandardCharsets.UTF_8));
        new SchemaValidator().require("evidence", evidence);
        assertEquals("REHEARSE_RECOVERY", evidence.path("request").path("operation").asText());
        assertEquals("1.2.0", evidence.path("request").path("change").path("from_version").asText());
        assertEquals("1.0.0", evidence.path("request").path("change").path("to_version").asText());
        assertEquals(before, SourceManifest.of(project).manifestSha256());
    }

    @Test
    void theCliReportsAFailureAsOneJsonDocument(@TempDir Path temp) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        int code;
        try {
            code = SentryCli.run(new String[] {"verify", "--project", temp.resolve("missing").toString(), "--package", "a:b", "--from", "1", "--to", "2", "--json"});
        } finally {
            System.setOut(original);
        }
        assertEquals(3, code);
        JsonNode result = Json.parse(captured.toString(StandardCharsets.UTF_8));
        new SchemaValidator().require("worker_result", result);
        assertEquals("FAILED", result.path("job_state").asText());
        List<String> problems = new ArrayList<>();
        assertTrue(problems.isEmpty());
    }
}
