package org.upgradeguard.sentry.explain;

import java.util.regex.Pattern;

public final class PlainText {
    private static final Pattern LINKISH = Pattern.compile("\\S*(://|www\\.|mailto:|javascript:|data:)\\S*", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9 .,:;_/+()%'-]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private PlainText() {
    }

    public static String clean(String text, int limit) {
        String cleaned = SPACES.matcher(UNSAFE.matcher(LINKISH.matcher(text == null ? "" : text).replaceAll(" ")).replaceAll(" ")).replaceAll(" ").trim();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }
}
