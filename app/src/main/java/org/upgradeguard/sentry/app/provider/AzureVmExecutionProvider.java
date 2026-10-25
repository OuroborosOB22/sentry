package org.upgradeguard.sentry.app.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.source.SourceArchive;
import org.upgradeguard.sentry.source.SourceManifest;

public class AzureVmExecutionProvider implements ExecutionProvider {
    private final Remote remote;
    private final boolean enabled;

    public AzureVmExecutionProvider(Remote remote, boolean enabled) {
        this.remote = remote;
        this.enabled = enabled;
    }

    @Override
    public String id() {
        return "AZURE_VM";
    }

    @Override
    public ProviderCheck validate() {
        if (!enabled) {
            return new ProviderCheck(false, "not configured", List.of("set sentry.azure.enabled=true and the host, user and key to turn this on"));
        }
        try {
            Remote.Answer answer = remote.run(List.of("capabilities"));
            if (answer.exitCode() != 0) {
                return new ProviderCheck(false, "the worker did not answer", List.of(answer.output().trim()));
            }
            JsonNode capabilities = Json.parse(answer.output().trim());
            return new ProviderCheck(true, "connected", List.of("worker version " + capabilities.path("worker_version").asText(), "java " + capabilities.path("java").asText(),
                    "A connection test shows the worker answers. It does not prove a full job works."));
        } catch (IOException | IllegalArgumentException problem) {
            return new ProviderCheck(false, "cannot reach the worker", List.of(String.valueOf(problem.getMessage())));
        }
    }

    @Override
    public String submit(JobSpec spec) throws IOException {
        if (!enabled) {
            throw new IOException("the Azure VM provider is not enabled");
        }
        if (!spec.ecosystem().equals("MAVEN") || !spec.operation().equals("REHEARSE_UPGRADE")) {
            throw new IOException("the remote worker supports Maven upgrade rehearsals only");
        }
        Files.createDirectories(spec.workDir());
        Path archive = spec.workDir().resolve("source.zip");
        SourceArchive.write(spec.projectPath(), SourceManifest.of(spec.projectPath()), archive);
        String id = spec.jobId();
        expect(remote.run(List.of("capabilities")), "capabilities");
        remote.run(List.of("cleanup", id));
        expect(remote.run(List.of("init", id)), "init");
        remote.upload(archive, id + "/source.zip");
        expect(remote.run(List.of("prepare", id)), "prepare");
        expect(remote.run(List.of("start", id, "verify", spec.packageName(), spec.fromVersion(), spec.toVersion())), "start");
        return id;
    }

    private static JsonNode expect(Remote.Answer answer, String step) throws IOException {
        if (answer.exitCode() != 0) {
            throw new IOException("the worker refused " + step + ": " + answer.output().trim());
        }
        return Json.parse(answer.output().trim());
    }

    @Override
    public ProviderStatus status(String handle) throws IOException {
        JsonNode state = expect(remote.run(List.of("status", handle)), "status");
        return switch (state.path("state").asText()) {
            case "RUNNING" -> new ProviderStatus(ProviderStatus.RUNNING, "running on the VM");
            case "SUCCEEDED" -> new ProviderStatus(ProviderStatus.SUCCEEDED, "finished with exit code " + state.path("exit").asText());
            default -> new ProviderStatus(ProviderStatus.FAILED, "the remote job stopped with exit code " + state.path("exit").asText());
        };
    }

    @Override
    public void cancel(String handle) throws IOException {
        expect(remote.run(List.of("cancel", handle)), "cancel");
    }

    @Override
    public Path collect(String handle, Path destination) throws IOException {
        expect(remote.run(List.of("package", handle)), "package");
        Path download = Files.createTempFile("sentry-out", ".zip");
        Path unpacked = Files.createTempDirectory("sentry-out-");
        try {
            remote.download(handle + "/out.zip", download);
            SourceArchive.extract(download, unpacked);
            Path evidence = unpacked.resolve(handle);
            if (!Files.isDirectory(evidence)) {
                throw new EvidenceVerifier.UntrustedOutput("the remote output has no folder for job " + handle);
            }
            LocalExecutionProvider.copyTree(evidence, destination);
            return destination;
        } finally {
            Files.deleteIfExists(download);
            try (var walk = Files.walk(unpacked)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Override
    public void cleanup(String handle) throws IOException {
        remote.run(List.of("cleanup", handle));
    }
}
