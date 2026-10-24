package org.upgradeguard.sentry;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.maven.MavenCoordinate;
import org.upgradeguard.sentry.maven.MavenInvoker;
import org.upgradeguard.sentry.maven.OsvScanner;
import org.upgradeguard.sentry.maven.UpgradeEngine;
import org.upgradeguard.sentry.maven.VerifyProfile;
import org.upgradeguard.sentry.verdict.Decision;

public final class SentryCli {
    private SentryCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Map<String, String> options = new HashMap<>();
        boolean json = false;
        boolean offline = false;
        String command = args.length > 0 ? args[0] : "";
        for (int index = 1; index < args.length; index++) {
            String name = args[index];
            if (name.equals("--json")) {
                json = true;
            } else if (name.equals("--offline-scan")) {
                offline = true;
            } else if (name.startsWith("--") && index + 1 < args.length) {
                options.put(name.substring(2), args[++index]);
            } else {
                System.err.println("unknown argument " + name);
                return 3;
            }
        }
        if (!command.equals("verify")) {
            System.err.println("usage: sentry verify --project DIR --package GROUP:ARTIFACT --from VERSION --to VERSION [--out DIR] [--run-id ID] [--json] [--offline-scan]");
            return 3;
        }
        try {
            for (String required : new String[] {"project", "package", "from", "to"}) {
                if (!options.containsKey(required)) {
                    throw new IllegalArgumentException("missing --" + required);
                }
            }
            String runId = options.getOrDefault("run-id", "run-" + Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14));
            UpgradeEngine engine = new UpgradeEngine(
                    new MavenInvoker(options.getOrDefault("maven", System.getenv().getOrDefault("SENTRY_MAVEN", "mvn")),
                            options.containsKey("java-home") ? Path.of(options.get("java-home")) : null,
                            options.containsKey("maven-repo") ? Path.of(options.get("maven-repo")) : null),
                    new OsvScanner(OsvScanner.httpTransport()));
            UpgradeEngine.Outcome outcome = engine.rehearse(new UpgradeEngine.Request(
                    Path.of(options.get("project")), MavenCoordinate.parse(options.get("package")), options.get("from"), options.get("to"), runId,
                    "REHEARSE_UPGRADE", Path.of(options.getOrDefault("out", "evidence")), VerifyProfile.standard(), !offline));
            if (json) {
                System.out.println(Json.text(outcome.evidence()));
            } else {
                System.out.println(outcome.verdict().decision() + ": upgrading " + options.get("package") + " from " + options.get("from") + " to " + options.get("to"));
                outcome.verdict().findings().forEach(finding -> System.out.println("  [" + finding.code() + "] " + finding.message()));
                System.out.println("evidence: " + outcome.evidenceDir());
            }
            return outcome.verdict().decision() == Decision.ACCEPT ? 0 : outcome.verdict().decision() == Decision.REJECT ? 1 : 2;
        } catch (Exception failure) {
            if (json) {
                ObjectNode failed = Json.object();
                failed.put("schema_version", "1.0.0");
                failed.putNull("run_id");
                failed.putNull("operation");
                failed.put("job_state", "FAILED");
                failed.putNull("verdict");
                failed.putNull("evidence_path");
                failed.put("error", String.valueOf(failure.getMessage()));
                failed.put("started_at", Instant.now().toString());
                failed.put("finished_at", Instant.now().toString());
                System.out.println(Json.text(failed));
            } else {
                System.err.println("error: " + failure.getMessage());
            }
            return 3;
        }
    }
}
