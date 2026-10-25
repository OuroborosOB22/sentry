package org.upgradeguard.sentry.app.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.upgradeguard.sentry.app.config.SentryProperties;

public class SshRemote implements Remote {
    private static final String JOBS = "sentry-jobs";

    private final SentryProperties.Azure azure;

    public SshRemote(SentryProperties.Azure azure) {
        this.azure = azure;
    }

    List<String> options(boolean copy) {
        List<String> options = new ArrayList<>(List.of("-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=15",
                "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=3", copy ? "-P" : "-p", String.valueOf(azure.port())));
        if (azure.identityFile() != null) {
            options.addAll(List.of("-i", azure.identityFile().toString(), "-o", "IdentitiesOnly=yes"));
        }
        if (azure.knownHostsFile() != null) {
            options.addAll(List.of("-o", "UserKnownHostsFile=" + azure.knownHostsFile()));
        }
        return options;
    }

    String target() {
        return azure.user() + "@" + azure.host();
    }

    @Override
    public Answer run(List<String> workerArguments) throws IOException {
        for (String argument : workerArguments) {
            if (!argument.matches("[A-Za-z0-9._:+-]+")) {
                throw new IOException("refusing to send an unsafe argument to the worker");
            }
        }
        List<String> command = new ArrayList<>(List.of(azure.sshCommand()));
        command.addAll(options(false));
        command.add(target());
        command.add(azure.workerCommand() + " " + String.join(" ", workerArguments));
        return execute(command, 120);
    }

    @Override
    public void upload(Path local, String remoteRelativePath) throws IOException {
        List<String> command = new ArrayList<>(List.of(azure.scpCommand()));
        command.addAll(options(true));
        command.add(local.toString());
        command.add(target() + ":" + JOBS + "/" + remoteRelativePath);
        Answer answer = execute(command, 600);
        if (answer.exitCode() != 0) {
            throw new IOException("upload failed: " + answer.output().trim());
        }
    }

    @Override
    public void download(String remoteRelativePath, Path local) throws IOException {
        List<String> command = new ArrayList<>(List.of(azure.scpCommand()));
        command.addAll(options(true));
        command.add(target() + ":" + JOBS + "/" + remoteRelativePath);
        command.add(local.toString());
        Answer answer = execute(command, 600);
        if (answer.exitCode() != 0) {
            throw new IOException("download failed: " + answer.output().trim());
        }
    }

    private static Answer execute(List<String> command, int seconds) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("the connection timed out");
            }
            return new Answer(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        }
    }
}
