package org.upgradeguard.sentry.maven;

public record StageResult(String outcome, Integer exitCode, double seconds, String log) {
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String NOT_RUN = "NOT_RUN";
    public static final String TIMED_OUT = "TIMED_OUT";

    public static StageResult notRun() {
        return new StageResult(NOT_RUN, null, 0, null);
    }

    public boolean passed() {
        return PASS.equals(outcome);
    }
}
