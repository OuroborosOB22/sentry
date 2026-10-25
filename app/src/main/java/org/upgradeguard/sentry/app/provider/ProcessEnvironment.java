package org.upgradeguard.sentry.app.provider;

import java.util.List;
import java.util.Map;

final class ProcessEnvironment {
    private static final List<String> PASSED = List.of("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "USER", "JAVA_HOME", "SSL_CERT_FILE", "SSL_CERT_DIR", "SENTRY_MAVEN");

    private ProcessEnvironment() {
    }

    static void restrict(Map<String, String> environment) {
        environment.keySet().removeIf(name -> !PASSED.contains(name));
    }
}
