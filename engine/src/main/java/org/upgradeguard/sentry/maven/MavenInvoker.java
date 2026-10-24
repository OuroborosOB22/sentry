package org.upgradeguard.sentry.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

public final class MavenInvoker {
    private static final List<String> PASSED_ENVIRONMENT = List.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "USER", "SSL_CERT_FILE", "SSL_CERT_DIR");

    public record Result(int exitCode, boolean timedOut, double seconds) {
    }

    private final String mavenExecutable;
    private final Path javaHome;
    private final Path localRepository;

    public MavenInvoker(String mavenExecutable, Path javaHome, Path localRepository) {
        this.mavenExecutable = mavenExecutable;
        this.javaHome = javaHome;
        this.localRepository = localRepository;
    }

    public static MavenInvoker fromEnvironment() {
        String executable = System.getenv().getOrDefault("SENTRY_MAVEN", "mvn");
        String home = System.getenv("SENTRY_JAVA_HOME");
        String repository = System.getenv("SENTRY_MAVEN_REPO");
        return new MavenInvoker(executable, home == null ? null : Path.of(home), repository == null ? null : Path.of(repository));
    }

    public Path localRepository() {
        return localRepository != null ? localRepository : Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    public List<String> command(List<String> arguments, Map<String, String> properties) {
        List<String> command = new ArrayList<>();
        command.add(mavenExecutable);
        command.add("-B");
        command.add("--no-transfer-progress");
        if (localRepository != null) {
            command.add("-Dmaven.repo.local=" + localRepository);
        }
        new TreeMap<>(properties).forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.addAll(arguments);
        return command;
    }

    public Result run(Path projectDir, List<String> arguments, Map<String, String> properties, Path logFile, Duration timeout) throws IOException {
        Files.createDirectories(logFile.toAbsolutePath().getParent());
        ProcessBuilder builder = new ProcessBuilder(command(arguments, properties));
        builder.directory(projectDir.toFile());
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(name -> !PASSED_ENVIRONMENT.contains(name));
        if (javaHome != null) {
            environment.put("JAVA_HOME", javaHome.toString());
        } else if (System.getenv("JAVA_HOME") != null) {
            environment.put("JAVA_HOME", System.getenv("JAVA_HOME"));
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        long started = System.nanoTime();
        Process process = builder.start();
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            return new Result(finished ? process.exitValue() : -1, !finished, Math.round(seconds * 100.0) / 100.0);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for Maven", interrupted);
        }
    }
}
