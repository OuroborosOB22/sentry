package org.upgradeguard.sentry.verdict;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.upgradeguard.sentry.maven.MavenCoordinate;
import org.upgradeguard.sentry.maven.SideObservation;
import org.upgradeguard.sentry.maven.TestOutcome;
import org.upgradeguard.sentry.maven.TestResult;

public final class VerdictRules {
    private VerdictRules() {
    }

    public record Input(MavenCoordinate target, String fromVersion, String toVersion, SideObservation before, SideObservation after,
                        ScanFindings vulnerabilities, boolean requireScanner) {
    }

    public static Verdict decide(Input input) {
        SideObservation before = input.before();
        SideObservation after = input.after();
        List<Finding> findings = new ArrayList<>();

        if (!before.install().passed() || before.build().outcome().equals("FAIL") || before.infrastructureProblem()) {
            findings.add(Finding.blocking("baseline-build-failed",
                    "The project could not be resolved and built with the current version, so there is nothing to compare against.",
                    "before/install.log", "before/verify.log"));
            return new Verdict(Decision.INSUFFICIENT_EVIDENCE, findings);
        }
        Optional<String> baseline = before.resolvedVersion(input.target());
        if (baseline.isEmpty() || !baseline.get().equals(input.fromVersion())) {
            findings.add(Finding.blocking("baseline-version-mismatch",
                    "The current build resolved " + input.target().text() + " " + baseline.orElse("(not present)") + ", not the "
                            + input.fromVersion() + " you said is in use, so the comparison would be about something else.",
                    "before/dependencies.txt"));
            return new Verdict(Decision.INSUFFICIENT_EVIDENCE, findings);
        }
        if (!after.install().passed()) {
            if (after.infrastructureProblem()) {
                findings.add(Finding.blocking("install-timed-out", "Resolving the proposed dependencies did not finish in time.", "after/install.log"));
                return new Verdict(Decision.INSUFFICIENT_EVIDENCE, findings);
            }
            findings.add(Finding.blocking("upgrade-build-failed",
                    "Maven could not resolve the project with " + input.target().text() + " " + input.toVersion() + ".",
                    "after/install.log"));
            return new Verdict(Decision.REJECT, findings);
        }
        Optional<String> resolvedTarget = after.resolvedVersion(input.target());
        if (resolvedTarget.isEmpty() || !resolvedTarget.get().equals(input.toVersion())) {
            findings.add(Finding.blocking("target-version-not-resolved",
                    "The upgraded build resolved " + input.target().text() + " " + resolvedTarget.orElse("(not present)") + ", not the "
                            + input.toVersion() + " you asked to try, so the experiment did not test the proposed change.",
                    "after/dependencies.txt"));
            return new Verdict(Decision.INSUFFICIENT_EVIDENCE, findings);
        }

        List<Finding> notes = new ArrayList<>();
        notes.addAll(collateralChanges(input));
        scanFindings(input, findings, notes);

        if (after.compileFailure()) {
            findings.add(Finding.blocking("upgrade-compile-failed",
                    "The project compiled with the current version but no longer compiles with " + input.toVersion() + ".",
                    "after/verify.log"));
        } else if (after.build().outcome().equals("FAIL") && !after.infrastructureProblem()) {
            findings.add(Finding.blocking("upgrade-build-failed", "The build fails after the change.", "after/verify.log"));
        }

        boolean comparable = before.testsCollected() && after.testsCollected();
        List<String> regressions = comparable ? regressions(before.results(), after.results()) : List.of();
        if (!regressions.isEmpty()) {
            findings.add(Finding.blocking("test-regression",
                    regressions.size() + " test(s) passed before the change and fail, error, are skipped or are missing after it: "
                            + String.join(", ", regressions.subList(0, Math.min(3, regressions.size()))),
                    "before/reports", "after/reports", "after/verify.log"));
        }
        if (comparable) {
            List<String> newFailing = newFailing(before.results(), after.results());
            if (!newFailing.isEmpty()) {
                notes.add(Finding.warning("new-failing-tests", newFailing.size() + " test(s) exist only after the change and are failing.", "after/reports"));
            }
        }

        boolean blocking = !findings.isEmpty();
        Finding problem = testProblem(before, after, blocking);
        if (problem != null) {
            if (blocking) {
                notes.add(problem);
            } else {
                findings.add(problem);
                return new Verdict(Decision.INSUFFICIENT_EVIDENCE, withNotes(findings, notes));
            }
        }
        if (input.requireScanner() && !input.vulnerabilities().available()) {
            Finding scanner = Finding.warning("required-scanner-unavailable",
                    "The vulnerability scan is required but could not run, so the security position of this change is unknown.");
            if (blocking) {
                notes.add(scanner);
            } else {
                findings.add(scanner);
                return new Verdict(Decision.INSUFFICIENT_EVIDENCE, withNotes(findings, notes));
            }
        }
        if (blocking) {
            return new Verdict(Decision.REJECT, withNotes(findings, notes));
        }
        findings.add(Finding.info("no-blocking-evidence",
                "Both builds resolved, the tests ran on both sides and no new vulnerability, compile failure or test regression was found.",
                "evidence.json"));
        return new Verdict(Decision.ACCEPT, withNotes(findings, notes));
    }

    private static List<Finding> withNotes(List<Finding> findings, List<Finding> notes) {
        List<Finding> all = new ArrayList<>(findings);
        all.addAll(notes);
        return all;
    }

    private static Finding testProblem(SideObservation before, SideObservation after, boolean blocking) {
        if (before.tests().outcome().equals("TIMED_OUT") || !before.testsCollected()) {
            return Finding.warning(before.tests().outcome().equals("TIMED_OUT") ? "tests-timed-out" : "tests-did-not-run",
                    "The tests of the current version did not produce results, so there is no baseline to compare tests against.", "before/verify.log");
        }
        if (after.tests().outcome().equals("TIMED_OUT") || (!after.testsCollected() && !after.compileFailure())) {
            return Finding.warning(after.tests().outcome().equals("TIMED_OUT") ? "tests-timed-out" : "tests-did-not-run",
                    "The tests did not produce results after the change, so a regression cannot be proved either way.", "after/verify.log");
        }
        return null;
    }

    private static List<Finding> collateralChanges(VerdictRules.Input input) {
        Map<String, String> before = new HashMap<>();
        input.before().dependencies().forEach(item -> before.put(item.key(), item.version()));
        List<String> changes = new ArrayList<>();
        input.after().dependencies().forEach(item -> {
            String old = before.get(item.key());
            if (!item.key().equals(input.target().text()) && !item.version().equals(old)) {
                changes.add(item.key() + " " + (old == null ? "(new)" : old) + " -> " + item.version());
            }
        });
        if (changes.isEmpty()) {
            return List.of();
        }
        return List.of(Finding.info("transitive-changes", changes.size() + " other dependency version(s) changed with it: "
                + String.join(", ", changes.subList(0, Math.min(5, changes.size()))), "before/dependencies.txt", "after/dependencies.txt"));
    }

    private static void scanFindings(Input input, List<Finding> findings, List<Finding> notes) {
        ScanFindings scan = input.vulnerabilities();
        if (!scan.available()) {
            notes.add(Finding.warning("vulnerability-scan-unavailable", "The vulnerability scan could not run, so new advisories were not checked.", "scan.json"));
            return;
        }
        Set<String> introduced = scan.introduced();
        if (!introduced.isEmpty()) {
            findings.add(Finding.blocking("new-vulnerability", "The change brings in " + introduced.size()
                    + " known advisory(ies) that were not present before: " + String.join(", ", new ArrayList<>(introduced).subList(0, Math.min(3, introduced.size()))), "scan.json"));
        }
        Set<String> resolved = scan.resolved();
        if (!resolved.isEmpty()) {
            notes.add(Finding.info("vulnerabilities-resolved", "The change removes " + resolved.size() + " known advisory(ies).", "scan.json"));
        }
    }

    public static List<String> regressions(List<TestResult> before, List<TestResult> after) {
        Map<String, TestOutcome> current = new HashMap<>();
        after.forEach(item -> current.put(item.id(), item.outcome()));
        Set<String> found = new TreeSet<>();
        for (TestResult old : before) {
            if (old.outcome() == TestOutcome.PASS && current.getOrDefault(old.id(), TestOutcome.SKIP) != TestOutcome.PASS) {
                found.add(old.id());
            }
        }
        return new ArrayList<>(found);
    }

    public static List<String> newFailing(List<TestResult> before, List<TestResult> after) {
        Set<String> known = new TreeSet<>();
        before.forEach(item -> known.add(item.id()));
        Set<String> found = new TreeSet<>();
        for (TestResult item : after) {
            if (!known.contains(item.id()) && (item.outcome() == TestOutcome.FAIL || item.outcome() == TestOutcome.ERROR)) {
                found.add(item.id());
            }
        }
        return new ArrayList<>(found);
    }
}
