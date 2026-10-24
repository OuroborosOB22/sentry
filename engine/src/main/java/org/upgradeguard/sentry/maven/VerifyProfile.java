package org.upgradeguard.sentry.maven;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record VerifyProfile(String id, List<String> goals, Map<String, String> properties, Duration installTimeout, Duration verifyTimeout) {
    public static VerifyProfile standard() {
        return new VerifyProfile("maven-verify", List.of("verify"), Map.of(), Duration.ofMinutes(15), Duration.ofMinutes(30));
    }
}
