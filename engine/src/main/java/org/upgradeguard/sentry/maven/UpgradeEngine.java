package org.upgradeguard.sentry.maven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.contract.SchemaValidator;
import org.upgradeguard.sentry.source.GitState;
import org.upgradeguard.sentry.source.SourceManifest;
import org.upgradeguard.sentry.verdict.Decision;
import org.upgradeguard.sentry.verdict.Finding;
import org.upgradeguard.sentry.verdict.ScanFindings;
import org.upgradeguard.sentry.verdict.Verdict;
import org.upgradeguard.sentry.verdict.VerdictRules;

public final class UpgradeEngine {
    public record Request(Path projectDir, MavenCoordinate target, String fromVersion, String toVersion, String runId, String operation,
                          Path evidenceRoot, VerifyProfile profile, boolean scanOnline) {
    }

    public record Outcome(Verdict verdict, Path evidenceDir, JsonNode evidence) {
    }

    private final MavenInvoker invoker;
    private final OsvScanner scanner;
    private final SchemaValidator validator = new SchemaValidator();

    public UpgradeEngine(MavenInvoker invoker, OsvScanner scanner) {
        this.invoker = invoker;
        this.scanner = scanner;
    }

    public Outcome rehearse(Request request) throws IOException {
        Versions.require(request.fromVersion());
        Versions.require(request.toVersion());
        Path project = request.projectDir().toRealPath();
        Path pomPath = project.resolve("pom.xml");
        if (!Files.isRegularFile(pomPath)) {
            throw new IOException("there is no pom.xml in " + project);
        }
        String evidenceName = EvidenceBuilder.slug(request.runId());
        Path evidenceDir = request.evidenceRoot().resolve(evidenceName);
        deleteTree(evidenceDir);
        Files.createDirectories(evidenceDir);

        SourceManifest manifest = SourceManifest.of(project);
        GitState git = GitState.of(project);
        EvidenceBuilder.Context context = new EvidenceBuilder.Context(request.runId(), request.operation(), project.getFileName().toString(), manifest, git,
                request.target(), request.fromVersion(), request.toVersion(), request.profile().id(), request.scanOnline(), "LOCAL");

        PomEditor.PinResult pin;
        try {
            pin = PomEditor.pin(Files.readString(pomPath, StandardCharsets.UTF_8), request.target(), request.toVersion());
        } catch (UnsupportedPomException limitation) {
            return limited(context, evidenceDir, limitation.getMessage());
        }

        Path work = Files.createTempDirectory("sentry-work-");
        try {
            Path beforeProject = copyProject(project, work.resolve("before"));
            Path afterProject = copyProject(project, work.resolve("after"));
            Files.writeString(afterProject.resolve("pom.xml"), pin.pomText(), StandardCharsets.UTF_8);
            Files.writeString(evidenceDir.resolve("pom-change.txt"), "changed " + pin.place() + " of " + request.target().text() + " from "
                    + pin.oldVersion() + " to " + request.toVersion() + "\n", StandardCharsets.UTF_8);

            SideRunner runner = new SideRunner(invoker);
            SideObservation before = runner.observe("before", beforeProject, evidenceDir.resolve("before"), request.profile());
            SideObservation after = runner.observe("after", afterProject, evidenceDir.resolve("after"), request.profile());

            ScanFindings scan = scan(request, before, after);
            writeScan(evidenceDir, scan);
            boolean apiAvailable = writeApiDiff(evidenceDir, request);

            Verdict verdict = VerdictRules.decide(new VerdictRules.Input(request.target(), request.fromVersion(), request.toVersion(), before, after, scan, false));
            writeVerdictAndEvidence(evidenceDir, context, before, after, scan, verdict, apiAvailable);
            return new Outcome(verdict, evidenceDir, Json.read(evidenceDir.resolve("evidence.json")));
        } finally {
            deleteTree(work);
        }
    }

    private Outcome limited(EvidenceBuilder.Context context, Path evidenceDir, String reason) throws IOException {
        StageResult none = StageResult.notRun();
        SideObservation empty = new SideObservation("before", none, none, none, List.of(), List.of(), false, false, "unknown", "unknown");
        Verdict verdict = new Verdict(Decision.INSUFFICIENT_EVIDENCE, List.of(Finding.blocking("unsupported-project", "This project is outside what Sentry supports yet: " + reason)));
        ScanFindings scan = new ScanFindings("osv", "api-v1", false, "not run", List.of(), List.of());
        writeVerdictAndEvidence(evidenceDir, context, empty, empty, scan, verdict, false);
        return new Outcome(verdict, evidenceDir, Json.read(evidenceDir.resolve("evidence.json")));
    }

    private ScanFindings scan(Request request, SideObservation before, SideObservation after) {
        if (!request.scanOnline() || !before.install().passed() || !after.install().passed()) {
            return new ScanFindings("osv", "api-v1", false, "not run", List.of(), List.of());
        }
        try {
            return new ScanFindings("osv", "api-v1", true, "Maven artifacts", scanner.scan(before.dependencies()), scanner.scan(after.dependencies()));
        } catch (IOException unreachable) {
            return new ScanFindings("osv", "api-v1", false, "unreachable: " + unreachable.getMessage(), List.of(), List.of());
        }
    }

    private void writeScan(Path evidenceDir, ScanFindings scan) throws IOException {
        ObjectNode node = Json.object();
        node.put("scanner", scan.scanner());
        node.put("available", scan.available());
        node.put("coverage", scan.coverage());
        scan.before().forEach(node.putArray("before")::add);
        scan.after().forEach(node.putArray("after")::add);
        Json.write(evidenceDir.resolve("scan.json"), node);
    }

    private boolean writeApiDiff(Path evidenceDir, Request request) throws IOException {
        Path repository = invoker.localRepository();
        ObjectNode diff = ApiDiffer.compare(ApiDiffer.jarPath(repository, request.target(), request.fromVersion()),
                ApiDiffer.jarPath(repository, request.target(), request.toVersion()), request.fromVersion(), request.toVersion());
        Json.write(evidenceDir.resolve("api-diff.json"), diff);
        return diff.path("available").asBoolean();
    }

    private void writeVerdictAndEvidence(Path evidenceDir, EvidenceBuilder.Context context, SideObservation before, SideObservation after,
                                         ScanFindings scan, Verdict verdict, boolean apiAvailable) throws IOException {
        ObjectNode summary = Json.object();
        summary.put("decision", verdict.decision().name());
        verdict.codes().forEach(summary.putArray("codes")::add);
        Json.write(evidenceDir.resolve("verdict.json"), summary);
        ObjectNode evidence = EvidenceBuilder.build(context, before, after, scan, verdict, evidenceDir, apiAvailable);
        validator.require("evidence", evidence);
        Json.write(evidenceDir.resolve("evidence.json"), evidence);
    }

    private static Path copyProject(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(source) && SourceManifest.isExcluded(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(destination.resolve(source.relativize(directory).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!SourceManifest.isExcluded(file.getFileName().toString()) && attributes.isRegularFile() && !attributes.isSymbolicLink()) {
                    Files.copy(file, destination.resolve(source.relativize(file).toString()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return destination;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException problem) throws IOException {
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
