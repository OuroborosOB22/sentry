package org.upgradeguard.sentry.maven;

public record ResolvedDependency(String groupId, String artifactId, String type, String version, String scope) {
    public String key() {
        return groupId + ":" + artifactId;
    }
}
