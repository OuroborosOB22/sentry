package org.upgradeguard.sentry.maven;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DependencyListParser {
    private DependencyListParser() {
    }

    public static List<ResolvedDependency> parse(String text) {
        List<ResolvedDependency> found = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.endsWith(":") || line.equals("none")) {
                continue;
            }
            int note = line.indexOf(" -- ");
            if (note >= 0) {
                line = line.substring(0, note);
            }
            String[] parts = line.split(":");
            if (parts.length == 5) {
                found.add(new ResolvedDependency(parts[0], parts[1], parts[2], parts[3], parts[4]));
            } else if (parts.length == 6) {
                found.add(new ResolvedDependency(parts[0], parts[1], parts[2], parts[4], parts[5]));
            }
        }
        found.sort(Comparator.comparing(ResolvedDependency::key).thenComparing(ResolvedDependency::version));
        return found;
    }
}
