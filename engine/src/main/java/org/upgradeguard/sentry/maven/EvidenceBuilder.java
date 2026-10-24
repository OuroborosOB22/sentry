package org.upgradeguard.sentry.maven;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.source.GitState;
import org.upgradeguard.sentry.source.SourceManifest;
import org.upgradeguard.sentry.verdict.Finding;
import org.upgradeguard.sentry.verdict.ScanFindings;
import org.upgradeguard.sentry.verdict.Verdict;
import org.upgradeguard.sentry.verdict.VerdictRules;

public final class EvidenceBuilder {
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*");

    private EvidenceBuilder() {
    }

    public record Context(String runId, String operation, String projectName, SourceManifest manifest, GitState git, MavenCoordinate target,
                          String fromVersion, String toVersion, String profileId, boolean network, String executionProvider) {
    }

    public static ObjectNode build(Context context, SideObservation before, SideObservation after, ScanFindings scan, Verdict verdict,
                                   Path evidenceDir, boolean apiAvailable) throws IOException {
        List<ObjectNode> artifacts = artifactManifest(evidenceDir);
        Map<String, String> idByPath = new HashMap<>();
        artifacts.forEach(item -> idByPath.put(item.get("path").asText(), item.get("id").asText()));

        ObjectNode evidence = Json.object();
        evidence.put("schema_version", "1.0.0");
        evidence.put("run_id", slug(context.runId()));
        evidence.set("request", request(context, after));
        evidence.set("before", side(before, "before", scan, idByPath, context.network()));
        evidence.set("after", side(after, "after", scan, idByPath, context.network()));
        evidence.set("diff", diff(before, after));
        evidence.set("verdict", verdict(verdict, idByPath));
        evidence.set("coverage", coverage(scan, apiAvailable));
        ArrayNode manifest = evidence.putArray("artifact_manifest");
        artifacts.forEach(manifest::add);
        return evidence;
    }

    static ObjectNode request(Context context, SideObservation reference) {
        ObjectNode request = Json.object();
        request.put("schema_version", "1.0.0");
        request.put("run_id", slug(context.runId()));
        request.put("project_id", slug(context.projectName()));
        request.put("operation", context.operation());
        ObjectNode source = request.putObject("source");
        if (context.git().commit() == null) {
            source.putNull("commit");
        } else {
            source.put("commit", context.git().commit());
        }
        source.put("dirty", context.git().dirty());
        source.put("manifest_sha256", context.manifest().manifestSha256());
        source.put("snapshot_id", context.manifest().snapshotId());
        ArrayNode exclusions = source.putArray("exclusions");
        context.manifest().exclusionLabels().forEach(exclusions::add);
        request.put("profile_id", slug(context.profileId()));
        request.set("environment_spec", environment(reference, context.network()));
        request.put("policy_id", "default-permissive");
        request.put("execution_provider", context.executionProvider());
        ObjectNode change = request.putObject("change");
        change.put("ecosystem", "MAVEN");
        change.put("package", context.target().text());
        change.put("from_version", context.fromVersion());
        change.put("to_version", context.toVersion());
        return request;
    }

    static ObjectNode environment(SideObservation observation, boolean network) {
        ObjectNode environment = Json.object();
        environment.put("os", System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT).replace(' ', '-'));
        environment.put("architecture", System.getProperty("os.arch", "unknown"));
        ObjectNode runtimes = environment.putObject("runtimes");
        runtimes.put("java", observation.javaVersion());
        runtimes.put("maven", observation.mavenVersion());
        environment.putNull("image_digest");
        environment.put("network_mode", network ? "CONTROLLED" : "OFFLINE");
        environment.putArray("env_var_names");
        return environment;
    }

    static ObjectNode stage(StageResult stage, String side, Map<String, String> idByPath) {
        ObjectNode node = Json.object();
        node.put("outcome", stage.outcome());
        if (stage.exitCode() == null) {
            node.putNull("exit_code");
        } else {
            node.put("exit_code", stage.exitCode());
        }
        node.put("duration_seconds", stage.seconds());
        if (stage.log() != null && idByPath.containsKey(side + "/" + stage.log())) {
            node.put("log_artifact", idByPath.get(side + "/" + stage.log()));
        }
        return node;
    }

    static ObjectNode side(SideObservation observation, String side, ScanFindings scan, Map<String, String> idByPath, boolean network) {
        ObjectNode node = Json.object();
        node.set("install", stage(observation.install(), side, idByPath));
        node.set("build", stage(observation.build(), side, idByPath));
        ObjectNode tests = node.putObject("tests");
        tests.set("stage", stage(observation.tests(), side, idByPath));
        ArrayNode results = tests.putArray("results");
        VerdictRules.worstByIdentity(observation.results()).forEach((id, outcome) -> {
            ObjectNode entry = results.addObject();
            entry.put("id", id.length() > 500 ? id.substring(0, 500) : id);
            entry.put("outcome", outcome.name());
        });
        ArrayNode dependencies = node.putArray("resolved_dependencies");
        observation.dependencies().forEach(item -> {
            ObjectNode entry = dependencies.addObject();
            entry.put("name", item.key());
            entry.put("version", item.version());
        });
        ArrayNode scanners = node.putArray("scanner_results");
        ObjectNode osv = scanners.addObject();
        osv.put("scanner", "osv");
        osv.put("version", "api-v1");
        osv.put("available", scan.available());
        osv.put("coverage", scan.available() ? "Maven artifacts in the resolved dependency list" : "not run or unreachable");
        osv.put("checked_at", Instant.now().toString());
        osv.put("finding_count", side.equals("before") ? scan.before().size() : scan.after().size());
        ArrayNode artifacts = node.putArray("artifacts");
        idByPath.forEach((path, id) -> {
            if (path.startsWith(side + "/")) {
                artifacts.add(id);
            }
        });
        node.set("environment", environment(observation, network));
        return node;
    }

    static ObjectNode diff(SideObservation before, SideObservation after) {
        ObjectNode diff = Json.object();
        ObjectNode dependencies = diff.putObject("dependencies");
        Map<String, String> old = new HashMap<>();
        before.dependencies().forEach(item -> old.put(item.key(), item.version()));
        Map<String, String> current = new HashMap<>();
        after.dependencies().forEach(item -> current.put(item.key(), item.version()));
        ArrayNode added = dependencies.putArray("added");
        ArrayNode removed = dependencies.putArray("removed");
        ArrayNode changed = dependencies.putArray("changed");
        current.keySet().stream().sorted().filter(key -> !old.containsKey(key)).forEach(added::add);
        old.keySet().stream().sorted().filter(key -> !current.containsKey(key)).forEach(removed::add);
        current.keySet().stream().sorted().filter(key -> old.containsKey(key) && !old.get(key).equals(current.get(key))).forEach(key -> {
            ObjectNode entry = changed.addObject();
            entry.put("name", key);
            entry.put("from", old.get(key));
            entry.put("to", current.get(key));
        });
        ObjectNode tests = diff.putObject("tests");
        boolean comparable = before.testsCollected() && after.testsCollected();
        ArrayNode regressions = tests.putArray("regressions");
        ArrayNode newFailing = tests.putArray("new_failing");
        ArrayNode missing = tests.putArray("missing_or_skipped");
        if (comparable) {
            VerdictRules.regressions(before.results(), after.results()).forEach(regressions::add);
            VerdictRules.newFailing(before.results(), after.results()).forEach(newFailing::add);
            Map<String, TestOutcome> afterWorst = VerdictRules.worstByIdentity(after.results());
            VerdictRules.worstByIdentity(before.results()).forEach((id, outcome) -> {
                if (outcome == TestOutcome.PASS && (afterWorst.get(id) == null || afterWorst.get(id) == TestOutcome.SKIP)) {
                    missing.add(id);
                }
            });
        }
        return diff;
    }

    static ObjectNode verdict(Verdict verdict, Map<String, String> idByPath) {
        ObjectNode node = Json.object();
        node.put("decision", verdict.decision().name());
        ArrayNode findings = node.putArray("findings");
        for (Finding finding : verdict.findings()) {
            ObjectNode entry = findings.addObject();
            entry.put("code", finding.code());
            entry.put("severity", finding.severity());
            entry.put("subject", cut(finding.subject(), 500));
            entry.put("message", cut(finding.message(), 1000));
            ArrayNode ids = entry.putArray("evidence_ids");
            for (String path : finding.evidencePaths()) {
                String id = idByPath.get(path);
                if (id != null) {
                    ids.add(id);
                }
            }
            entry.put("provenance", "sentry verdict rules");
        }
        return node;
    }

    static ObjectNode coverage(ScanFindings scan, boolean apiAvailable) {
        ObjectNode coverage = Json.object();
        ArrayNode checked = coverage.putArray("checked");
        ArrayNode notChecked = coverage.putArray("not_checked");
        ArrayNode unavailable = coverage.putArray("unavailable");
        checked.add("dependency resolution").add("compilation").add("unit tests").add("resolved dependency diff");
        if (apiAvailable) {
            checked.add("library API differences");
        }
        if (scan.available()) {
            checked.add("known vulnerabilities (OSV)");
        } else {
            unavailable.add("known vulnerabilities (OSV)");
        }
        notChecked.add("licence policy for Maven artifacts").add("whether this project calls any changed API").add("integration tests outside the selected profile")
                .add("malware analysis");
        return coverage;
    }

    static List<ObjectNode> artifactManifest(Path evidenceDir) throws IOException {
        List<ObjectNode> items = new ArrayList<>();
        if (!Files.isDirectory(evidenceDir)) {
            return items;
        }
        try (Stream<Path> walk = Files.walk(evidenceDir)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path)).sorted().toList()) {
                String relative = evidenceDir.relativize(file).toString().replace('\\', '/');
                if (relative.equals("evidence.json") || !SAFE_PATH.matcher(relative).matches()) {
                    continue;
                }
                ObjectNode item = Json.object();
                item.put("id", artifactId(relative));
                item.put("path", relative);
                item.put("sha256", SourceManifest.sha256(file));
                item.put("media_type", mediaType(relative));
                items.add(item);
            }
        }
        return items;
    }

    static String mediaType(String name) {
        if (name.endsWith(".json")) {
            return "application/json";
        }
        if (name.endsWith(".xml")) {
            return "application/xml";
        }
        if (name.endsWith(".html")) {
            return "text/html";
        }
        return "text/plain";
    }

    public static String artifactId(String relative) {
        String text = slug(relative.replace('/', '-'), 1, 100);
        if (text.length() <= 64) {
            return text;
        }
        String trimmed = text.substring(0, 55).replaceAll("^[-._]+|[-._]+$", "");
        return trimmed + "-" + shortHash(relative);
    }

    private static String shortHash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String slug(String text) {
        return slug(text, 3, 64);
    }

    static String slug(String text, int minimum, int maximum) {
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("^[-._]+|[-._]+$", "");
        if (cleaned.isEmpty()) {
            cleaned = "item";
        }
        if (cleaned.length() > maximum) {
            cleaned = cleaned.substring(0, maximum).replaceAll("^[-._]+|[-._]+$", "");
        }
        StringBuilder padded = new StringBuilder(cleaned);
        while (padded.length() < minimum) {
            padded.append('x');
        }
        return padded.toString();
    }

    private static String cut(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
