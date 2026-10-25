package org.upgradeguard.sentry.maven;

import java.util.regex.Pattern;

public record MavenCoordinate(String groupId, String artifactId) {
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_.-]+");

    public MavenCoordinate {
        if (!PART.matcher(groupId).matches() || !PART.matcher(artifactId).matches()) {
            throw new IllegalArgumentException("a Maven package looks like groupId:artifactId");
        }
    }

    public static MavenCoordinate parse(String text) {
        String[] parts = text.split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("a Maven package looks like groupId:artifactId");
        }
        return new MavenCoordinate(parts[0], parts[1]);
    }

    public String text() {
        return groupId + ":" + artifactId;
    }
}
