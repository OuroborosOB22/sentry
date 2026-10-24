package org.upgradeguard.sentry.verdict;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public record ScanFindings(String scanner, String version, boolean available, String coverage, List<String> before, List<String> after) {
    public Set<String> introduced() {
        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        return added;
    }

    public Set<String> resolved() {
        Set<String> gone = new TreeSet<>(before);
        gone.removeAll(after);
        return gone;
    }
}
