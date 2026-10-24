package org.upgradeguard.sentry.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.upgradeguard.sentry.app.config.SentryProperties;
import org.upgradeguard.sentry.app.provider.AzureVmExecutionProvider;
import org.upgradeguard.sentry.app.provider.EvidenceVerifier;
import org.upgradeguard.sentry.app.provider.ExecutionProvider;
import org.upgradeguard.sentry.app.provider.SshRemote;

class AzureProviderProtocolTest {
    @TempDir
    Path temp;
    Path remoteHome;
    Path project;
    SentryProperties.Azure azure;

    private Path script(String name, String body) throws IOException {
        Path file = temp.resolve("bin").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    @BeforeEach
    void setUp() throws Exception {
        remoteHome = Files.createDirectories(temp.resolve("remote-home"));
        Files.createDirectories(remoteHome.resolve("sentry-jobs"));
        Path worker = Path.of("..", "worker", "sentry-worker").toAbsolutePath().normalize();
        Files.createDirectories(temp.resolve("fakebin"));
        Files.createSymbolicLink(temp.resolve("fakebin/sentry-worker"), worker);
        Path engine = script("fake-engine", """
                run_id=""; out=""
                while [ $# -gt 0 ]; do
                  case "$1" in --run-id) run_id="$2";; --out) out="$2";; esac
                  shift
                done
                mkdir -p "$out/$run_id/after"
                printf 'log for %s' "$run_id" > "$out/$run_id/after/pytest.log"
                printf '{"decision": "REJECT"}' > "$out/$run_id/verdict.json"
                cp "$FIXTURE_EVIDENCE" "$out/$run_id/evidence.json"
                if [ -n "${FAKE_ENGINE_SLEEP:-}" ]; then sleep "$FAKE_ENGINE_SLEEP"; fi
                echo '{}'
                exit 1
                """);
        script("fake-ssh", """
                while [ $# -gt 0 ]; do
                  case "$1" in -o|-p|-i) shift 2;; -*) shift;; *) break;; esac
                done
                shift
                HOME="%s" PATH="%s:$PATH" SENTRY_ENGINE_COMMAND="%s" FIXTURE_EVIDENCE="%s" FAKE_ENGINE_SLEEP="${FAKE_ENGINE_SLEEP:-}" sh -c "$*"
                """.formatted(remoteHome, temp.resolve("fakebin"), engine, temp.resolve("evidence-template.json")));
        script("fake-scp", """
                while [ $# -gt 0 ]; do
                  case "$1" in -o|-P|-i) shift 2;; -*) shift;; *) break;; esac
                done
                source="$1"; target="$2"
                strip() { echo "$1" | sed 's#^[^:]*:##'; }
                case "$source" in
                  *@*:*) cp "%s/$(strip "$source")" "$target" ;;
                  *) cp "$source" "%s/$(strip "$target")" ;;
                esac
                """.formatted(remoteHome, remoteHome));
        Path template = EvidenceFolders.create(temp.resolve("template"), "job-protocol-test", "REJECT");
        Files.copy(template.resolve("evidence.json"), temp.resolve("evidence-template.json"));
        project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        azure = new SentryProperties.Azure(true, "vm.example", 22, "azureuser", null, null, temp.resolve("bin/fake-ssh").toString(),
                temp.resolve("bin/fake-scp").toString(), "sentry-worker", 2);
    }

    private ExecutionProvider provider() {
        return new AzureVmExecutionProvider(new SshRemote(azure), true);
    }

    private ExecutionProvider.JobSpec spec(String id) {
        return new ExecutionProvider.JobSpec(id, "REHEARSE_UPGRADE", "MAVEN", "project", project, "org.demo:lib", "1.0.0", "2.0.0", null, null, temp.resolve("work-" + id));
    }

    private ExecutionProvider.ProviderStatus waitFor(ExecutionProvider provider, String handle) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            ExecutionProvider.ProviderStatus status = provider.status(handle);
            if (status.finished()) {
                return status;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the remote job never finished");
    }

    @Test
    void theHandshakeReportsTheWorkerAndStaysHonestAboutWhatItProves() {
        ExecutionProvider.ProviderCheck check = provider().validate();
        assertTrue(check.usable());
        assertTrue(check.details().stream().anyMatch(line -> line.contains("does not prove a full job works")));
    }

    @Test
    void aJobTravelsToTheHostRunsAndComesBackAsVerifiedEvidence() throws Exception {
        ExecutionProvider provider = provider();
        String handle = provider.submit(spec("job-protocol-test"));
        assertEquals("job-protocol-test", handle);
        assertTrue(Files.isRegularFile(remoteHome.resolve("sentry-jobs/job-protocol-test/source/pom.xml")));
        ExecutionProvider.ProviderStatus status = waitFor(provider, handle);
        assertEquals("SUCCEEDED", status.state());
        Path collected = provider.collect(handle, temp.resolve("collected"));
        JsonNode evidence = EvidenceVerifier.verify(collected);
        assertEquals("REJECT", evidence.path("verdict").path("decision").asText());
        provider.cleanup(handle);
        assertFalse(Files.exists(remoteHome.resolve("sentry-jobs/job-protocol-test")));
    }

    @Test
    void cancelStopsTheRemoteJob() throws Exception {
        ExecutionProvider provider = new AzureVmExecutionProvider(new SshRemote(new SentryProperties.Azure(true, "vm", 22, "u", null, null,
                temp.resolve("bin/fake-ssh").toString(), temp.resolve("bin/fake-scp").toString(), "sentry-worker", 2)), true);
        Files.writeString(temp.resolve("bin/fake-ssh"), Files.readString(temp.resolve("bin/fake-ssh")).replace("FAKE_ENGINE_SLEEP=\"${FAKE_ENGINE_SLEEP:-}\"", "FAKE_ENGINE_SLEEP=30"));
        String handle = provider.submit(spec("job-cancel-test"));
        assertEquals("RUNNING", provider.status(handle).state());
        provider.cancel(handle);
        ExecutionProvider.ProviderStatus status = waitFor(provider, handle);
        assertEquals("FAILED", status.state());
        assertTrue(status.message().contains("130"));
        provider.cleanup(handle);
    }

    @Test
    void anUnreachableHostIsReportedNotHidden() throws Exception {
        script("down-ssh", "echo 'ssh: connect to host vm port 22: Operation timed out' >&2\nexit 255");
        SentryProperties.Azure down = new SentryProperties.Azure(true, "vm", 22, "u", null, null, temp.resolve("bin/down-ssh").toString(), temp.resolve("bin/fake-scp").toString(), "sentry-worker", 2);
        ExecutionProvider provider = new AzureVmExecutionProvider(new SshRemote(down), true);
        ExecutionProvider.ProviderCheck check = provider.validate();
        assertFalse(check.usable());
        assertTrue(check.details().get(0).contains("timed out"));
        assertThrows(IOException.class, () -> provider.submit(spec("job-down-test")));
        assertThrows(IOException.class, () -> provider.status("job-down-test"));
    }

    @Test
    void unsafeArgumentsNeverReachTheWorker() {
        SshRemote remote = new SshRemote(azure);
        for (String bad : List.of("a; rm -rf /", "$(id)", "a b", "`id`", "a|b", "../x\n")) {
            assertThrows(IOException.class, () -> remote.run(List.of("status", bad)), bad);
        }
    }

    @Test
    void theWorkerItselfRefusesBadIdsAndUnknownCommands() throws Exception {
        SshRemote remote = new SshRemote(azure);
        assertTrue(remote.run(List.of("status", "..")).output().contains("bad job id") || remote.run(List.of("status", "..")).exitCode() != 0);
        assertTrue(remote.run(List.of("bogus")).output().contains("unknown command"));
        assertTrue(remote.run(List.of("start", "job-x", "verify", "-rf", "1", "2")).output().contains("bad"));
    }

    @Test
    void aDisabledProviderRefusesEverything() {
        ExecutionProvider off = new AzureVmExecutionProvider(new SshRemote(azure), false);
        assertFalse(off.validate().usable());
        assertThrows(IOException.class, () -> off.submit(spec("job-off-test")));
    }

    @Test
    void onlyMavenUpgradeRehearsalsAreSentToTheRemoteWorker() {
        ExecutionProvider.JobSpec python = new ExecutionProvider.JobSpec("job-py-test", "REHEARSE_UPGRADE", "PYTHON", "project", project, "requests", "1", "2", null, null, temp.resolve("w"));
        assertThrows(IOException.class, () -> provider().submit(python));
    }
}
