package org.upgradeguard.sentry.verdict;

import java.util.List;

public record Verdict(Decision decision, List<Finding> findings) {
    public List<String> codes() {
        return findings.stream().map(Finding::code).toList();
    }
}
