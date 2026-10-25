package org.upgradeguard.sentry.app.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "sentry")
public record SentryProperties(
        @DefaultValue("data") Path dataDir,
        @DefaultValue List<Path> allowedRoots,
        @DefaultValue("mvn") String mavenExecutable,
        Path engineJar,
        Path pythonToolDir,
        @DefaultValue("python3") String pythonExecutable,
        @DefaultValue("2") int maxConcurrentJobs,
        @DefaultValue("PT1H") Duration jobTimeout,
        @DefaultValue("PT15S") Duration leaseDuration,
        @DefaultValue("LOCAL") String defaultExecution,
        @DefaultValue("false") boolean allowLocalInHosted,
        @DefaultValue Auth auth,
        @DefaultValue Azure azure,
        @DefaultValue Explanation explanation,
        @DefaultValue Risk risk) {

    public record Auth(@DefaultValue List<Token> tokens) {
    }

    public record Token(String token, @DefaultValue List<String> projects) {
    }

    public record Azure(@DefaultValue("false") boolean enabled, String host, @DefaultValue("22") int port, String user, Path identityFile,
                        Path knownHostsFile, @DefaultValue("ssh") String sshCommand, @DefaultValue("scp") String scpCommand,
                        @DefaultValue("sentry-worker") String workerCommand, @DefaultValue("2") int maxConcurrent) {
    }

    public record Explanation(@DefaultValue("DISABLED") String provider, String baseUrl, String model, String apiKeyEnvironmentVariable,
                              @DefaultValue("PT30S") Duration timeout, @DefaultValue("800") int maxOutputTokens, @DefaultValue("false") boolean allowSourceText,
                              @DefaultValue("") String reasoningEffort) {
    }

    public record Risk(@DefaultValue("false") boolean enabled, String baseUrl, @DefaultValue("PT10S") Duration timeout) {
    }
}
