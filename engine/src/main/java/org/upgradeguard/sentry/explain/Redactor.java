package org.upgradeguard.sentry.explain;

import java.util.List;
import java.util.regex.Pattern;

public final class Redactor {
    static final int MAX_LINE = 2000;
    private static final Pattern KEY_BLOCK = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(-----END [A-Z ]*PRIVATE KEY-----|$)", Pattern.DOTALL);
    private static final List<Pattern> LINE_SECRETS = List.of(
            Pattern.compile("(?i)\\b[A-Za-z0-9_.-]{0,48}(password|passwd|pwd|secret|token|api[_-]?key|credential|private[_-]?key|auth|jwt)[A-Za-z0-9_.-]{0,48}\\s{0,8}[:=(]\\s{0,8}(\"[^\"]{0,300}\"|'[^']{0,300}'|[^\\s;)]{1,300})"),
            Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]{0,20}://[^\\s/:@]{1,100}:[^\\s/@]{1,200}@"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,400}\\.[A-Za-z0-9_-]{5,400}\\.[A-Za-z0-9_-]{5,400}"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,200}"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,200}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,200}\\b"),
            Pattern.compile("[\"'][A-Za-z0-9+/=_-]{32,300}[\"']"));

    private Redactor() {
    }

    public static String redact(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : KEY_BLOCK.matcher(text).replaceAll("[redacted]").split("\n", -1)) {
            String cleaned = line;
            if (cleaned.length() > MAX_LINE) {
                cleaned = "[very long line removed]";
            } else {
                for (Pattern pattern : LINE_SECRETS) {
                    cleaned = pattern.matcher(cleaned).replaceAll("[redacted]");
                }
            }
            out.append(cleaned).append('\n');
        }
        return out.length() == 0 ? "" : out.substring(0, out.length() - 1);
    }
}
