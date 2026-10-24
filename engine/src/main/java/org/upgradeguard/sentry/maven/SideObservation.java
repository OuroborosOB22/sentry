package org.upgradeguard.sentry.maven;

import java.util.List;
import java.util.Optional;

public record SideObservation(
        String side,
        StageResult install,
        StageResult build,
        StageResult tests,
        List<TestResult> results,
        List<ResolvedDependency> dependencies,
        boolean compileFailure,
        boolean infrastructureProblem,
        String javaVersion,
        String mavenVersion) {

    public Optional<String> resolvedVersion(MavenCoordinate coordinate) {
        return dependencies.stream().filter(item -> item.key().equals(coordinate.text())).map(ResolvedDependency::version).findFirst();
    }

    public boolean testsCollected() {
        return !results.isEmpty();
    }
}
