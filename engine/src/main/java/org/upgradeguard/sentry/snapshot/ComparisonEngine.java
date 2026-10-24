package org.upgradeguard.sentry.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.contract.SchemaValidator;

public final class ComparisonEngine {
    private static final SchemaValidator VALIDATOR = new SchemaValidator();

    private ComparisonEngine() {
    }

    public static ObjectNode compare(Path oldFolder, Path newFolder) throws IOException {
        JsonNode older = Json.read(oldFolder.resolve("snapshot.json"));
        JsonNode newer = Json.read(newFolder.resolve("snapshot.json"));
        Map<String, String> oldFiles = files(Json.read(oldFolder.resolve("manifest.json")));
        Map<String, String> newFiles = files(Json.read(newFolder.resolve("manifest.json")));

        ObjectNode comparison = Json.object();
        comparison.put("schema_version", "1.0.0");
        comparison.put("operation", "COMPARE");
        comparison.put("project_id", older.path("project_id").asText());
        comparison.put("old_snapshot_id", older.path("snapshot_id").asText());
        comparison.put("new_snapshot_id", newer.path("snapshot_id").asText());
        ObjectNode changes = comparison.putObject("changes");
        ObjectNode code = changes.putObject("code");
        sorted(newFiles.keySet(), oldFiles.keySet()).forEach(code.putArray("added")::add);
        sorted(oldFiles.keySet(), newFiles.keySet()).forEach(code.putArray("removed")::add);
        ArrayNode changed = code.putArray("changed");
        new TreeSet<>(oldFiles.keySet()).stream().filter(newFiles::containsKey).filter(name -> !oldFiles.get(name).equals(newFiles.get(name))).forEach(changed::add);

        ArrayNode runtime = changes.putArray("runtime");
        List<String> hostChanges = new ArrayList<>();
        for (String field : List.of("os", "architecture")) {
            if (!older.path("environment").path(field).asText().equals(newer.path("environment").path(field).asText())) {
                ObjectNode entry = runtime.addObject();
                entry.put("field", field);
                entry.put("from", older.path("environment").path(field).asText());
                entry.put("to", newer.path("environment").path(field).asText());
                hostChanges.add(field);
            }
        }
        TreeSet<String> runtimeNames = new TreeSet<>();
        older.path("environment").path("runtimes").fieldNames().forEachRemaining(runtimeNames::add);
        newer.path("environment").path("runtimes").fieldNames().forEachRemaining(runtimeNames::add);
        for (String name : runtimeNames) {
            String left = older.path("environment").path("runtimes").path(name).asText(null);
            String right = newer.path("environment").path("runtimes").path(name).asText(null);
            if (left == null ? right != null : !left.equals(right)) {
                ObjectNode entry = runtime.addObject();
                entry.put("field", "runtime " + name);
                if (left == null) {
                    entry.putNull("from");
                } else {
                    entry.put("from", left);
                }
                if (right == null) {
                    entry.putNull("to");
                } else {
                    entry.put("to", right);
                }
            }
        }
        dependencies(older, newer, changes.putObject("dependencies"));
        tests(older, newer, changes.putObject("tests"));
        ArrayNode warnings = comparison.putArray("warnings");
        if (!hostChanges.isEmpty()) {
            warnings.add("The two snapshots ran on different hosts, so differences may come from the host and not from the project.");
        }
        if (!older.path("profile_id").asText().equals(newer.path("profile_id").asText())) {
            warnings.add("The two snapshots used different verification profiles, so the test results are not directly comparable.");
        }
        if (!older.path("eligibility").asText().equals(SnapshotEngine.PASSING)) {
            warnings.add("The older snapshot was not a fully passing run, so its test results are only a starting point.");
        }
        if (!older.path("tests").path("ran").asBoolean() || !newer.path("tests").path("ran").asBoolean()) {
            warnings.add("At least one snapshot has no test results.");
        }
        warnings.add("A changed file or dependency is a lead, not proof of what caused a difference.");
        comparison.put("created_at", Instant.now().toString());
        VALIDATOR.require("comparison", comparison);
        return comparison;
    }

    private static Map<String, String> files(JsonNode node) {
        Map<String, String> files = new TreeMap<>();
        node.fields().forEachRemaining(entry -> files.put(entry.getKey(), entry.getValue().asText()));
        return files;
    }

    private static List<String> sorted(Iterable<String> keep, Iterable<String> drop) {
        TreeSet<String> result = new TreeSet<>();
        keep.forEach(result::add);
        drop.forEach(result::remove);
        return new ArrayList<>(result);
    }

    private static void dependencies(JsonNode older, JsonNode newer, ObjectNode out) {
        Map<String, String> before = versions(older.path("resolved_dependencies"));
        Map<String, String> after = versions(newer.path("resolved_dependencies"));
        sorted(after.keySet(), before.keySet()).forEach(out.putArray("added")::add);
        sorted(before.keySet(), after.keySet()).forEach(out.putArray("removed")::add);
        ArrayNode changed = out.putArray("changed");
        before.keySet().stream().filter(after::containsKey).filter(name -> !before.get(name).equals(after.get(name))).forEach(name -> {
            ObjectNode entry = changed.addObject();
            entry.put("name", name);
            entry.put("from", before.get(name));
            entry.put("to", after.get(name));
        });
    }

    private static Map<String, String> versions(JsonNode list) {
        Map<String, String> found = new TreeMap<>();
        list.forEach(item -> found.put(item.path("name").asText(), item.path("version").asText()));
        return found;
    }

    private static void tests(JsonNode older, JsonNode newer, ObjectNode out) {
        Map<String, String> before = outcomes(older);
        Map<String, String> after = outcomes(newer);
        ArrayNode newlyFailing = out.putArray("newly_failing");
        ArrayNode newlySkipped = out.putArray("newly_skipped");
        ArrayNode missing = out.putArray("missing");
        ArrayNode fixed = out.putArray("fixed");
        before.forEach((name, outcome) -> {
            String now = after.get(name);
            if (outcome.equals("PASS") && (("FAIL".equals(now)) || "ERROR".equals(now))) {
                newlyFailing.add(name);
            } else if (outcome.equals("PASS") && "SKIP".equals(now)) {
                newlySkipped.add(name);
            } else if (outcome.equals("PASS") && now == null) {
                missing.add(name);
            } else if ((outcome.equals("FAIL") || outcome.equals("ERROR")) && "PASS".equals(now)) {
                fixed.add(name);
            }
        });
        sorted(after.keySet(), before.keySet()).forEach(out.putArray("added")::add);
    }

    private static Map<String, String> outcomes(JsonNode snapshot) {
        Map<String, String> found = new TreeMap<>();
        snapshot.path("tests").path("results").forEach(item -> found.put(item.path("id").asText(), item.path("outcome").asText()));
        return found;
    }
}
