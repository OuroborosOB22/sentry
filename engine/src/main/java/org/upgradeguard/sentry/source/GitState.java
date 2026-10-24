package org.upgradeguard.sentry.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public record GitState(String commit, boolean dirty) {
    public static GitState of(Path folder) {
        try {
            String top = run(folder, List.of("git", "rev-parse", "--show-toplevel"));
            if (top == null || !Path.of(top.trim()).toRealPath().equals(folder.toRealPath())) {
                return new GitState(null, false);
            }
            String commit = run(folder, List.of("git", "rev-parse", "HEAD"));
            String status = run(folder, List.of("git", "status", "--porcelain"));
            if (commit == null) {
                return new GitState(null, false);
            }
            return new GitState(commit.trim(), status != null && !status.isBlank());
        } catch (IOException problem) {
            return new GitState(null, false);
        }
    }

    private static String run(Path folder, List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? output : null;
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
