package org.upgradeguard.sentry.verdict;

import java.util.List;

public record Finding(String code, String severity, String subject, String message, List<String> evidencePaths) {
    public static final String BLOCKING = "BLOCKING";
    public static final String WARNING = "WARNING";
    public static final String INFO = "INFO";

    public static Finding blocking(String code, String message, String... paths) {
        return new Finding(code, BLOCKING, message, message, List.of(paths));
    }

    public static Finding warning(String code, String message, String... paths) {
        return new Finding(code, WARNING, message, message, List.of(paths));
    }

    public static Finding info(String code, String message, String... paths) {
        return new Finding(code, INFO, message, message, List.of(paths));
    }
}
