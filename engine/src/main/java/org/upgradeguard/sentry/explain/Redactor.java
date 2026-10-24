package org.upgradeguard.sentry.explain;

import java.util.List;
import java.util.regex.Pattern;

public final class Redactor {
    private static final List<Pattern> SECRETS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(-----END [A-Z ]*PRIVATE KEY-----|$)", Pattern.DOTALL),
            Pattern.compile("(?i)[A-Za-z0-9_.-]*(password|passwd|pwd|secret|token|api_?key|credential|private_?key|auth|jwt)[A-Za-z0-9_.-]*\\s*[:=(]\\s*(\"[^\"\\n]*\"|'[^'\\n]*'|[^\\s,;)]+)"),
            Pattern.compile("(?i)[a-z][a-z0-9+.-]*://[^\\s/:@]+:[^\\s/@]+@"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
            Pattern.compile("[\"'][A-Za-z0-9+/=_-]{32,}[\"']"));

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
