package org.upgradeguard.sentry.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenInvokerTest {
    private static Path script(Path folder, String body) throws Exception {
        Path file = folder.resolve("fake-mvn");
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    @Test
    void buildsArgumentListsNeverShellStrings() {
        MavenInvoker invoker = new MavenInvoker("mvn", null, Path.of("/cache/repo"));
        List<String> command = invoker.command(List.of("verify"), Map.of("b", "2", "a", "1; rm -rf /"));
        assertEquals(List.of("mvn", "-B", "--no-transfer-progress", "-Dmaven.repo.local=/cache/repo", "-Da=1; rm -rf /", "-Db=2", "verify"), command);
    }

    @Test
    void doesNotPassHostSecretsToMaven(@TempDir Path folder) throws Exception {
        Path executable = script(folder, "env > \"$PWD/seen.txt\"");
        MavenInvoker.Result result = new MavenInvoker(executable.toString(), null, null)
                .run(folder, List.of("verify"), Map.of(), folder.resolve("run.log"), Duration.ofSeconds(20));
        assertEquals(0, result.exitCode());
        String seen = Files.readString(folder.resolve("seen.txt"));
        assertTrue(seen.contains("PATH="));
        for (String name : System.getenv().keySet()) {
            if (name.contains("TOKEN") || name.contains("SECRET") || name.contains("KEY") || name.equals("SSH_AUTH_SOCK")) {
                assertFalse(seen.contains(name + "="), name);
            }
        }
    }

    @Test
    void stopsAProcessThatRunsTooLongAndSaysSo(@TempDir Path folder) throws Exception {
        Path executable = script(folder, "sleep 30");
        long started = System.nanoTime();
        MavenInvoker.Result result = new MavenInvoker(executable.toString(), null, null)
                .run(folder, List.of("verify"), Map.of(), folder.resolve("run.log"), Duration.ofSeconds(1));
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        assertTrue((System.nanoTime() - started) / 1_000_000_000.0 < 15);
    }

    @Test
    void recordsTheExitCodeAndTheOutput(@TempDir Path folder) throws Exception {
        Path executable = script(folder, "echo hello from maven\nexit 3");
        MavenInvoker.Result result = new MavenInvoker(executable.toString(), null, null)
                .run(folder, List.of("verify"), Map.of(), folder.resolve("deep/run.log"), Duration.ofSeconds(20));
        assertEquals(3, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue(Files.readString(folder.resolve("deep/run.log")).contains("hello from maven"));
    }
}
