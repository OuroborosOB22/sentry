package org.upgradeguard.sentry.maven;

import java.util.regex.Pattern;

public final class Versions {
    private static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");

    private Versions() {
    }

    public static String require(String version) {
        if (version == null || !PATTERN.matcher(version).matches()) {
            throw new IllegalArgumentException("the version is not a plain version number");
        }
        return version;
    }
}
