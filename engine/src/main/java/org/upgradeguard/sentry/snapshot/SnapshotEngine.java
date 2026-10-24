package org.upgradeguard.sentry.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.contract.SchemaValidator;
import org.upgradeguard.sentry.maven.EvidenceBuilder;
import org.upgradeguard.sentry.maven.MavenInvoker;
import org.upgradeguard.sentry.maven.SideObservation;
import org.upgradeguard.sentry.maven.SideRunner;
import org.upgradeguard.sentry.maven.TestOutcome;
import org.upgradeguard.sentry.maven.VerifyProfile;
import org.upgradeguard.sentry.source.GitState;
import org.upgradeguard.sentry.source.SourceArchive;
import org.upgradeguard.sentry.source.SourceManifest;
import org.upgradeguard.sentry.verdict.VerdictRules;

public final class SnapshotEngine {
    public static final String PASSING = "PASSING";
    public static final String STARTING_ONLY = "STARTING_ONLY";
    public static final String INCONCLUSIVE = "INCONCLUSIVE";

    private final MavenInvoker invoker;
    private final SchemaValidator validator = new SchemaValidator();

    public SnapshotEngine(MavenInvoker invoker) {
        this.invoker = invoker;
    }

    public record Result(ObjectNode snapshot, Path folder, boolean alreadyRecorded) {
    }

    public Result capture(Path projectDir, Path storeRoot, VerifyProfile profile, boolean verify) throws IOException {
        Path project = projectDir.toRealPath();
        SourceManifest manifest = SourceManifest.of(project);
        GitState git = GitState.of(project);
        String projectId = EvidenceBuilder.slug(project.getFileName().toString());
        Path staging = Files.createTempDirectory("sentry-snapshot-");
        try {
            Path copy = copy(project, staging.resolve("copy"));
            SideObservation observed = verify
                    ? new SideRunner(invoker).observe("snapshot", copy, staging.resolve("logs"), profile)
                    : new SideRunner(invoker).observeResolutionOnly("snapshot", copy, staging.resolve("logs"), profile);
            String eligibility = eligibility(observed, verify);
            String id = identity(manifest.manifestSha256(), profile.id(), observed, eligibility);
            Path folder = storeRoot.resolve(projectId).resolve(id);
            if (Files.isDirectory(folder)) {
                return new Result((ObjectNode) Json.read(folder.resolve("snapshot.json")), folder, true);
            }
            Path pending = Files.createDirectories(storeRoot.resolve(projectId)).resolve(".pending-" + id);
            deleteTree(pending);
            Files.createDirectories(pending);
            copyTree(staging.resolve("logs"), pending.resolve("logs"));
            SourceArchive.write(project, manifest, pending.resolve("source.zip"));
            ObjectNode files = Json.object();
            manifest.fileHashes().forEach(files::put);
            Json.write(pending.resolve("manifest.json"), files);
            ObjectNode snapshot = record(id, projectId, manifest, git, observed, profile, eligibility, verify, pending);
            validator.require("snapshot", snapshot);
            Json.write(pending.resolve("snapshot.json"), snapshot);
            Files.move(pending, folder);
            return new Result(snapshot, folder, false);
        } finally {
            deleteTree(staging);
        }
    }

    static String eligibility(SideObservation observed, boolean verify) {
        if (!verify) {
            return STARTING_ONLY;
        }
        if (!observed.install().passed() || observed.compileFailure() || observed.infrastructureProblem() || !observed.testsCollected()
                || observed.tests().outcome().equals("TIMED_OUT")) {
            return INCONCLUSIVE;
        }
        boolean allGreen = observed.build().passed() && observed.tests().passed()
                && observed.results().stream().noneMatch(item -> item.outcome() == TestOutcome.FAIL || item.outcome() == TestOutcome.ERROR);
        return allGreen ? PASSING : STARTING_ONLY;
    }

    static String identity(String manifestSha, String profile, SideObservation observed, String eligibility) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((manifestSha + "|" + profile + "|" + eligibility + "|").getBytes(StandardCharsets.UTF_8));
            VerdictRules.worstByIdentity(observed.results()).forEach((id, outcome) -> digest.update((id + "=" + outcome + ";").getBytes(StandardCharsets.UTF_8)));
            return "snap-" + HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ObjectNode record(String id, String projectId, SourceManifest manifest, GitState git, SideObservation observed, VerifyProfile profile,
                              String eligibility, boolean verified, Path pending) throws IOException {
        ObjectNode snapshot = Json.object();
        snapshot.put("schema_version", "1.0.0");
        snapshot.put("snapshot_id", id);
        snapshot.put("project_id", projectId);
        ObjectNode source = snapshot.putObject("source");
        if (git.commit() == null) {
            source.putNull("commit");
        } else {
            source.put("commit", git.commit());
        }
        source.put("dirty", git.dirty());
        source.put("manifest_sha256", manifest.manifestSha256());
        source.put("snapshot_id", manifest.snapshotId());
        manifest.exclusionLabels().forEach(source.putArray("exclusions")::add);
        ObjectNode environment = snapshot.putObject("environment");
        environment.put("os", System.getProperty("os.name", "unknown").toLowerCase(java.util.Locale.ROOT).replace(' ', '-'));
        environment.put("architecture", System.getProperty("os.arch", "unknown"));
        ObjectNode runtimes = environment.putObject("runtimes");
        runtimes.put("java", observed.javaVersion());
        runtimes.put("maven", observed.mavenVersion());
        environment.putNull("image_digest");
        environment.put("network_mode", "CONTROLLED");
        environment.putArray("env_var_names");
        ArrayNode dependencies = snapshot.putArray("resolved_dependencies");
        observed.dependencies().forEach(item -> {
            ObjectNode entry = dependencies.addObject();
            entry.put("name", item.key());
            entry.put("version", item.version());
        });
        ObjectNode tests = snapshot.putObject("tests");
        tests.put("verified", verified);
        tests.put("ran", observed.testsCollected());
        ArrayNode results = tests.putArray("results");
        Map<String, TestOutcome> worst = new TreeMap<>(VerdictRules.worstByIdentity(observed.results()));
        worst.forEach((name, outcome) -> {
            ObjectNode entry = results.addObject();
            entry.put("id", name.length() > 500 ? name.substring(0, 500) : name);
            entry.put("outcome", outcome.name());
        });
        snapshot.put("profile_id", EvidenceBuilder.slug(profile.id()));
        snapshot.put("eligibility", eligibility);
        ArrayNode artifacts = snapshot.putArray("artifacts");
        for (ObjectNode item : EvidenceBuilder.artifactManifestOf(pending, "snapshot.json")) {
            artifacts.add(item);
        }
        snapshot.put("created_at", Instant.now().toString());
        return snapshot;
    }

    private static Path copy(Path source, Path destination) throws IOException {
        SourceManifest manifest = SourceManifest.of(source);
        for (String relative : manifest.fileHashes().keySet()) {
            Path target = destination.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(source.resolve(relative), target);
        }
        Files.createDirectories(destination);
        return destination;
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path) && !Files.isSymbolicLink(path)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static JsonNode read(Path folder) throws IOException {
        return Json.read(folder.resolve("snapshot.json"));
    }
}
