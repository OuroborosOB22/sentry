package org.upgradeguard.sentry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.maven.EvidenceBuilder;
import org.upgradeguard.sentry.maven.MavenCoordinate;
import org.upgradeguard.sentry.maven.MavenInvoker;
import org.upgradeguard.sentry.maven.OsvScanner;
import org.upgradeguard.sentry.maven.PomEditor;
import org.upgradeguard.sentry.maven.UpgradeEngine;
import org.upgradeguard.sentry.maven.VerifyProfile;
import org.upgradeguard.sentry.snapshot.ComparisonEngine;
import org.upgradeguard.sentry.snapshot.SnapshotEngine;
import org.upgradeguard.sentry.verdict.Decision;

public final class SentryCli {
    private static final String USAGE = String.join("\n",
            "usage:",
            "  sentry verify   --project DIR --package GROUP:ARTIFACT --from VERSION --to VERSION [--out DIR] [--run-id ID]",
            "  sentry snapshot --project DIR --store DIR [--no-verify]",
            "  sentry compare  --store DIR --project-id ID --old SNAPSHOT --new SNAPSHOT",
            "  sentry recover  --project DIR --package GROUP:ARTIFACT --store DIR --project-id ID --snapshot SNAPSHOT [--out DIR] [--run-id ID]",
            "options for all: [--property name=value] [--maven PATH] [--java-home PATH] [--maven-repo PATH] [--json] [--offline-scan]");
    private static final Set<String> FLAGS = Set.of("json", "offline-scan", "no-verify");

    private SentryCli() {
    }

    private record Args(String command, Map<String, String> options, Map<String, String> properties, Set<String> flags) {
        String require(String name) {
            if (!options.containsKey(name)) {
                throw new IllegalArgumentException("missing --" + name);
            }
            return options.get(name);
        }
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    public static int run(String[] argv) {
        Args args;
        try {
            args = parse(argv);
        } catch (IllegalArgumentException problem) {
            System.err.println(problem.getMessage());
            System.err.println(USAGE);
            return 3;
        }
        try {
            return switch (args.command()) {
                case "verify" -> verify(args, "REHEARSE_UPGRADE");
                case "recover" -> recover(args);
                case "snapshot" -> snapshot(args);
                case "compare" -> compare(args);
                default -> {
                    System.err.println(USAGE);
                    yield 3;
                }
            };
        } catch (Exception failure) {
            if (args.flags().contains("json")) {
                System.out.println(Json.text(failedResult(String.valueOf(failure.getMessage()))));
            } else {
                System.err.println("error: " + failure.getMessage());
            }
            return 3;
        }
    }

    private static Args parse(String[] argv) {
        String command = argv.length > 0 ? argv[0] : "";
        Map<String, String> options = new HashMap<>();
        Map<String, String> properties = new HashMap<>();
        Set<String> flags = new HashSet<>();
        for (int index = 1; index < argv.length; index++) {
            String name = argv[index];
            if (!name.startsWith("--")) {
                throw new IllegalArgumentException("unknown argument " + name);
            }
            String key = name.substring(2);
            if (FLAGS.contains(key)) {
                flags.add(key);
            } else if (index + 1 >= argv.length) {
                throw new IllegalArgumentException("missing a value after " + name);
            } else if (key.equals("property")) {
                String[] pair = argv[++index].split("=", 2);
                if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_.-]+")) {
                    throw new IllegalArgumentException("a property looks like name=value");
                }
                properties.put(pair[0], pair[1]);
            } else {
                options.put(key, argv[++index]);
            }
        }
        return new Args(command, options, properties, flags);
    }

    private static MavenInvoker invoker(Args args) {
        return new MavenInvoker(args.options().getOrDefault("maven", System.getenv().getOrDefault("SENTRY_MAVEN", "mvn")),
                args.options().containsKey("java-home") ? Path.of(args.options().get("java-home")) : null,
                args.options().containsKey("maven-repo") ? Path.of(args.options().get("maven-repo")) : null);
    }

    private static String runId(Args args) {
        return args.options().getOrDefault("run-id", "run-" + Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14));
    }

    private static int verify(Args args, String operation) throws IOException {
        String from = args.require("from");
        return rehearse(args, operation, from, args.require("to"));
    }

    private static int rehearse(Args args, String operation, String from, String to) throws IOException {
        UpgradeEngine engine = new UpgradeEngine(invoker(args), new OsvScanner(OsvScanner.httpTransport()));
        UpgradeEngine.Outcome outcome = engine.rehearse(new UpgradeEngine.Request(
                Path.of(args.require("project")), MavenCoordinate.parse(args.require("package")), from, to, runId(args), operation,
                Path.of(args.options().getOrDefault("out", "evidence")), VerifyProfile.standard().withProperties(args.properties()),
                !args.flags().contains("offline-scan")));
        if (args.flags().contains("json")) {
            System.out.println(Json.text(outcome.evidence()));
        } else {
            Banner.print(System.out);
            System.out.println(Banner.decision(outcome.verdict().decision().toString()) + (Banner.colour() ? " " : ": ") + operation.toLowerCase().replace('_', ' ')
                    + " of " + args.require("package") + " from " + from + " to " + to);
            System.out.println();
            outcome.verdict().findings().forEach(finding -> System.out.println("  " + Banner.accent("[" + finding.code() + "]") + " " + finding.message()));
            System.out.println();
            System.out.println(Banner.dim("evidence:", Banner.colour()) + " " + outcome.evidenceDir());
        }
        return outcome.verdict().decision() == Decision.ACCEPT ? 0 : outcome.verdict().decision() == Decision.REJECT ? 1 : 2;
    }

    private static int recover(Args args) throws Exception {
        MavenCoordinate target = MavenCoordinate.parse(args.require("package"));
        JsonNode snapshot = Json.read(Path.of(args.require("store")).resolve(EvidenceBuilder.slug(args.require("project-id"))).resolve(safeId(args.require("snapshot"))).resolve("snapshot.json"));
        String wanted = null;
        for (JsonNode item : snapshot.path("resolved_dependencies")) {
            if (item.path("name").asText().equals(target.text())) {
                wanted = item.path("version").asText();
            }
        }
        if (wanted == null) {
            throw new IllegalArgumentException("the snapshot does not contain " + target.text());
        }
        String current = PomEditor.currentVersion(Files.readString(Path.of(args.require("project")).resolve("pom.xml"), StandardCharsets.UTF_8), target);
        return rehearse(args, "REHEARSE_RECOVERY", current, wanted);
    }

    private static int snapshot(Args args) throws IOException {
        SnapshotEngine.Result result = new SnapshotEngine(invoker(args)).capture(
                Path.of(args.require("project")), Path.of(args.require("store")), VerifyProfile.standard().withProperties(args.properties()),
                !args.flags().contains("no-verify"));
        if (args.flags().contains("json")) {
            System.out.println(Json.text(result.snapshot()));
        } else {
            System.out.println(result.snapshot().path("snapshot_id").asText() + " " + result.snapshot().path("eligibility").asText()
                    + (result.alreadyRecorded() ? " (already recorded)" : ""));
        }
        return result.snapshot().path("eligibility").asText().equals(SnapshotEngine.INCONCLUSIVE) ? 2 : 0;
    }

    private static int compare(Args args) throws IOException {
        Path project = Path.of(args.require("store")).resolve(EvidenceBuilder.slug(args.require("project-id")));
        ObjectNode comparison = ComparisonEngine.compare(project.resolve(safeId(args.require("old"))), project.resolve(safeId(args.require("new"))));
        System.out.println(Json.text(comparison));
        return 0;
    }

    private static String safeId(String id) {
        if (!id.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new IllegalArgumentException("not a snapshot id: " + id);
        }
        return id;
    }

    private static ObjectNode failedResult(String message) {
        ObjectNode failed = Json.object();
        failed.put("schema_version", "1.0.0");
        failed.putNull("run_id");
        failed.putNull("operation");
        failed.put("job_state", "FAILED");
        failed.putNull("verdict");
        failed.putNull("evidence_path");
        failed.put("error", message.length() > 1000 ? message.substring(0, 1000) : message);
        failed.put("started_at", Instant.now().toString());
        failed.put("finished_at", Instant.now().toString());
        return failed;
    }
}
