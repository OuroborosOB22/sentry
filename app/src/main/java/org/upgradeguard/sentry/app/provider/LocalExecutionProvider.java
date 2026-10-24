package org.upgradeguard.sentry.app.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.upgradeguard.sentry.app.config.SentryProperties;

public class LocalExecutionProvider implements ExecutionProvider {
    private final SentryProperties properties;
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, Path> folders = new ConcurrentHashMap<>();

    public LocalExecutionProvider(SentryProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "LOCAL";
    }

    @Override
    public ProviderCheck validate() {
        List<String> details = new ArrayList<>();
        boolean java = properties.engineJar() != null && Files.isRegularFile(properties.engineJar());
        details.add("Maven engine jar: " + (java ? properties.engineJar() : "not configured (sentry.engine-jar), Maven projects cannot run"));
        boolean python = properties.pythonToolDir() != null && Files.isDirectory(properties.pythonToolDir());
        details.add("Python tool: " + (python ? properties.pythonToolDir() : "not configured (sentry.python-tool-dir), Python projects cannot run"));
        return new ProviderCheck(java || python, "runs on this machine, each job in its own process", details);
    }

    List<String> command(JobSpec spec, Path out) {
        List<String> command = new ArrayList<>();
        if (spec.ecosystem().equals("MAVEN")) {
            command.addAll(List.of("java", "-jar", properties.engineJar().toString()));
            if (spec.operation().equals("SNAPSHOT")) {
                command.addAll(List.of("snapshot", "--project", spec.projectPath().toString(), "--store", out.resolve("snapshots").toString(), "--json"));
            } else if (spec.operation().equals("REHEARSE_RECOVERY")) {
                command.addAll(List.of("recover", "--project", spec.projectPath().toString(), "--package", spec.packageName(), "--store", spec.snapshotStore().toString(),
                        "--project-id", spec.projectSlug(), "--snapshot", spec.snapshotId(), "--out", out.toString(), "--run-id", spec.jobId(), "--json"));
            } else {
                command.addAll(List.of("verify", "--project", spec.projectPath().toString(), "--package", spec.packageName(), "--from", spec.fromVersion(),
                        "--to", spec.toVersion(), "--out", out.toString(), "--run-id", spec.jobId(), "--json"));
            }
            command.addAll(List.of("--maven", properties.mavenExecutable()));
        } else {
            command.addAll(List.of(properties.pythonExecutable(), "-m", "upgradeguard"));
            if (spec.operation().equals("SNAPSHOT")) {
                command.addAll(List.of("snapshot-run", "--repo", spec.projectPath().toString(), "--store", out.resolve("snapshots").toString(), "--json", "--quiet"));
            } else {
                command.addAll(List.of("run", "--repo", spec.projectPath().toString(), "--package", spec.packageName(), "--from", spec.fromVersion(), "--to", spec.toVersion(),
                        "--evidence", out.toString(), "--work", spec.workDir().resolve("python-work").toString(), "--json", "--quiet"));
            }
        }
        return command;
    }

    @Override
    public String submit(JobSpec spec) throws IOException {
        Files.createDirectories(spec.workDir());
        Path out = Files.createDirectories(spec.workDir().resolve("out"));
        ProcessBuilder builder = new ProcessBuilder(command(spec, out));
        if (!spec.ecosystem().equals("MAVEN")) {
            builder.directory(properties.pythonToolDir().toFile());
        } else {
            builder.directory(spec.workDir().toFile());
        }
        ProcessEnvironment.restrict(builder.environment());
        builder.redirectOutput(spec.workDir().resolve("stdout.json").toFile());
        builder.redirectError(spec.workDir().resolve("stderr.log").toFile());
        Process process = builder.start();
        processes.put(spec.jobId(), process);
        folders.put(spec.jobId(), spec.workDir());
        return spec.jobId();
    }

    @Override
    public ProviderStatus status(String handle) throws IOException {
        Process process = processes.get(handle);
        if (process == null) {
            throw new UnknownHandleException(handle);
        }
        if (process.isAlive()) {
            return new ProviderStatus(ProviderStatus.RUNNING, "running");
        }
        int code = process.exitValue();
        if (code >= 0 && code <= 2) {
            return new ProviderStatus(ProviderStatus.SUCCEEDED, "finished with exit code " + code);
        }
        return new ProviderStatus(ProviderStatus.FAILED, failureText(folders.get(handle), code));
    }

    private static String failureText(Path folder, int code) {
        try {
            String stdout = Files.readString(folder.resolve("stdout.json"));
            int at = stdout.indexOf("\"error\"");
            if (at >= 0) {
                return stdout.substring(at, Math.min(stdout.length(), at + 400)).replaceAll("\\s+", " ");
            }
            String stderr = Files.readString(folder.resolve("stderr.log")).trim();
            return stderr.isEmpty() ? "the process stopped with exit code " + code : stderr.substring(Math.max(0, stderr.length() - 400));
        } catch (IOException unreadable) {
            return "the process stopped with exit code " + code;
        }
    }

    @Override
    public void cancel(String handle) throws IOException {
        Process process = processes.get(handle);
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @Override
    public Path collect(String handle, Path destination) throws IOException {
        Path folder = folders.get(handle);
        if (folder == null) {
            throw new UnknownHandleException(handle);
        }
        Path out = folder.resolve("out");
        Path evidence = findEvidenceFolder(out);
        copyTree(evidence == null ? out : evidence, destination);
        return destination;
    }

    private static Path findEvidenceFolder(Path out) throws IOException {
        if (!Files.isDirectory(out)) {
            return null;
        }
        try (Stream<Path> children = Files.list(out)) {
            for (Path child : children.sorted().toList()) {
                if (Files.isRegularFile(child.resolve("evidence.json"))) {
                    return child;
                }
            }
        }
        return null;
    }

    static void copyTree(Path source, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isSymbolicLink(path)) {
                    throw new EvidenceVerifier.UntrustedOutput("the output contains a link at " + source.relativize(path));
                }
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    @Override
    public void cleanup(String handle) throws IOException {
        Process process = processes.remove(handle);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        Path folder = folders.remove(handle);
        if (folder != null && Files.isDirectory(folder)) {
            try (Stream<Path> walk = Files.walk(folder)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
