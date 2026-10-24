package org.upgradeguard.sentry.explain;

import java.util.List;
import java.util.regex.Pattern;

public final class Redactor {
    private static final List<Pattern> SECRETS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(-----END [A-Z ]*PRIVATE KEY-----|$)", Pattern.DOTALL),
            Pattern.compile("(?i)\\b(api[_-]?key|secret|token|passwd|password)\\b\\s*[:=]\\s*\\S+"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"));

    private Redactor() {
    }

    public static String redact(String text) {
        String result = text;
        for (Pattern pattern : SECRETS) {
            result = pattern.matcher(result).replaceAll("[redacted]");
        }
        return result;
    }
}
