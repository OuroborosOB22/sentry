package org.upgradeguard.sentry.verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.upgradeguard.sentry.maven.MavenCoordinate;
import org.upgradeguard.sentry.maven.ResolvedDependency;
import org.upgradeguard.sentry.maven.SideObservation;
import org.upgradeguard.sentry.maven.StageResult;
import org.upgradeguard.sentry.maven.TestOutcome;
import org.upgradeguard.sentry.maven.TestResult;

class VerdictRulesTest {
    private static final MavenCoordinate LIB = MavenCoordinate.parse("org.demo:lib");

    private static StageResult pass() {
        return new StageResult("PASS", 0, 1, "install.log");
    }

    private static SideObservation side(String version, List<TestResult> results) {
        return new SideObservation("side", pass(), pass(), results.isEmpty() ? StageResult.notRun() : pass(), results,
                List.of(new ResolvedDependency("org.demo", "lib", "jar", version, "compile")), false, false, "21", "3.9");
    }

    private static List<TestResult> passing(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new TestResult(id, TestOutcome.PASS)).toList();
    }

    private static ScanFindings scan(boolean available, List<String> before, List<String> after) {
        return new ScanFindings("osv", "v1", available, "x", before, after);
    }

    private static VerdictRules.Input input(SideObservation before, SideObservation after, ScanFindings scan) {
        return new VerdictRules.Input(LIB, "1.0.0", "2.0.0", before, after, scan, false);
    }

    private static SideObservation copy(SideObservation base, StageResult install, StageResult build, StageResult tests, boolean compile, boolean infra,
                                        List<TestResult> results) {
        return new SideObservation(base.side(), install, build, tests, results, base.dependencies(), compile, infra, "21", "3.9");
    }

    @Test
    void aCleanUpgradeIsAccepted() {
        Verdict verdict = VerdictRules.decide(input(side("1.0.0", passing("t1", "t2")), side("2.0.0", passing("t1", "t2")), scan(true, List.of(), List.of())));
        assertEquals(Decision.ACCEPT, verdict.decision());
        assertTrue(verdict.codes().contains("no-blocking-evidence"));
    }

    @Test
    void aTestThatStopsPassingIsARegression() {
        SideObservation after = side("2.0.0", List.of(new TestResult("t1", TestOutcome.FAIL), new TestResult("t2", TestOutcome.PASS)));
        Verdict verdict = VerdictRules.decide(input(side("1.0.0", passing("t1", "t2")), after, scan(true, List.of(), List.of())));
        assertEquals(Decision.REJECT, verdict.decision());
        assertEquals(List.of("test-regression"), verdict.codes());
    }

    @Test
    void aPassingTestThatBecomesSkippedOrMissingStillBlocks() {
        Verdict skipped = VerdictRules.decide(input(side("1.0.0", passing("t1", "t2")),
                side("2.0.0", List.of(new TestResult("t1", TestOutcome.SKIP), new TestResult("t2", TestOutcome.PASS))), scan(true, List.of(), List.of())));
        assertEquals(Decision.REJECT, skipped.decision());
        Verdict missing = VerdictRules.decide(input(side("1.0.0", passing("t1", "t2")), side("2.0.0", passing("t2")), scan(true, List.of(), List.of())));
        assertEquals(Decision.REJECT, missing.decision());
    }

    @Test
    void aTestThatWasAlreadyFailingIsNotBlamedOnTheUpgrade() {
        List<TestResult> broken = List.of(new TestResult("t1", TestOutcome.FAIL), new TestResult("t2", TestOutcome.PASS));
        assertEquals(Decision.ACCEPT, VerdictRules.decide(input(side("1.0.0", broken), side("2.0.0", broken), scan(true, List.of(), List.of()))).decision());
    }

    @Test
    void newTestsThatFailAreOnlyANote() {
        SideObservation after = side("2.0.0", List.of(new TestResult("t1", TestOutcome.PASS), new TestResult("new", TestOutcome.FAIL)));
        Verdict verdict = VerdictRules.decide(input(side("1.0.0", passing("t1")), after, scan(true, List.of(), List.of())));
        assertEquals(Decision.ACCEPT, verdict.decision());
        assertTrue(verdict.codes().contains("new-failing-tests"));
    }

    @Test
    void aBrokenBaselineIsInsufficientEvidence() {
        SideObservation base = side("1.0.0", passing("t1"));
        SideObservation broken = copy(base, new StageResult("FAIL", 1, 1, "install.log"), StageResult.notRun(), StageResult.notRun(), false, false, List.of());
        Verdict verdict = VerdictRules.decide(input(broken, side("2.0.0", passing("t1")), scan(true, List.of(), List.of())));
        assertEquals(Decision.INSUFFICIENT_EVIDENCE, verdict.decision());
        assertEquals(List.of("baseline-build-failed"), verdict.codes());
    }

    @Test
    void aBaselineThatResolvedAnotherVersionIsInsufficientEvidence() {
        Verdict verdict = VerdictRules.decide(input(side("0.9.0", passing("t1")), side("2.0.0", passing("t1")), scan(true, List.of(), List.of())));
        assertEquals(List.of("baseline-version-mismatch"), verdict.codes());
        assertEquals(Decision.INSUFFICIENT_EVIDENCE, verdict.decision());
    }

    @Test
    void aTargetThatDidNotWinResolutionIsInsufficientEvidence() {
        Verdict verdict = VerdictRules.decide(input(side("1.0.0", passing("t1")), side("1.0.0", passing("t1")), scan(true, List.of(), List.of())));
        assertEquals(List.of("target-version-not-resolved"), verdict.codes());
        assertEquals(Decision.INSUFFICIENT_EVIDENCE, verdict.decision());
    }

    @Test
    void anUpgradeThatCannotBeResolvedIsRejected() {
        SideObservation base = side("2.0.0", passing("t1"));
        SideObservation broken = copy(base, new StageResult("FAIL", 1, 1, "install.log"), StageResult.notRun(), StageResult.notRun(), false, false, List.of());
        Verdict verdict = VerdictRules.decide(input(side("1.0.0", passing("t1")), broken, scan(true, List.of(), List.of())));
        assertEquals(Decision.REJECT, verdict.decision());
        assertEquals(List.of("upgrade-build-failed"), verdict.codes());
    }

    @Test
    void aResolutionTimeoutIsInsufficientEvidenceNotARejection() {
        SideObservation base = side("2.0.0", passing("t1"));
        SideObservation slow = copy(base, new StageResult("TIMED_OUT", null, 900, "install.log"), StageResult.notRun(), StageResult.notRun(), false, true, List.of());
        assertEquals(Decision.INSUFFICIENT_EVIDENCE, VerdictRules.decide(input(side("1.0.0", passing("t1")), slow, scan(true, List.of(), List.of()))).decision());
    }

    @Test
    void aCompileFailureAfterTheChangeIsRejected() {
        SideObservation base = side("2.0.0", passing("t1"));
        SideObservation broken = copy(base, pass(), new StageResult("FAIL", 1, 1, "verify.log"), StageResult.notRun(), true, false, List.of());
        Verdict verdict = VerdictRules.decide(input(side("1.0.0", passing("t1")), broken, scan(true, List.of(), List.of())));
        assertEquals(Decision.REJECT, verdict.decision());
        assertTrue(verdict.codes().contains("upgrade-compile-failed"));
        assertFalse(verdict.codes().contains("test-regression"));
    }

    @Test
    void aNewAdvisoryRejectsAndAResolvedOneIsANote() {
        Verdict worse = VerdictRules.decide(input(side("1.0.0", passing("t1")), side("2.0.0", passing("t1")), scan(true, List.of("org.demo:lib A"), List.of("org.demo:lib A", "org.demo:lib B"))));
        assertEquals(Decision.REJECT, worse.decision());
        assertTrue(worse.codes().contains("new-vulnerability"));
        Verdict better = VerdictRules.decide(input(side("1.0.0", passing("t1")), side("2.0.0", passing("t1")), scan(true, List.of("org.demo:lib A"), List.of())));
        assertEquals(Decision.ACCEPT, better.decision());
        assertTrue(better.codes().contains("vulnerabilities-resolved"));
    }

    @Test
    void anUnavailableScannerIsDisclosedNotClaimedAsPassed() {
        Verdict verdict = VerdictRules.decide(input(side("1.0.0", passing("t1")), side("2.0.0", passing("t1")), scan(false, List.of(), List.of())));
        assertEquals(Decision.ACCEPT, verdict.decision());
        assertTrue(verdict.codes().contains("vulnerability-scan-unavailable"));
    }

    @Test
    void aRequiredScannerThatIsDownPreventsAccept() {
        VerdictRules.Input strict = new VerdictRules.Input(LIB, "1.0.0", "2.0.0", side("1.0.0", passing("t1")), side("2.0.0", passing("t1")), scan(false, List.of(), List.of()), true);
        Verdict verdict = VerdictRules.decide(strict);
        assertEquals(Decision.INSUFFICIENT_EVIDENCE, verdict.decision());
        assertTrue(verdict.codes().contains("required-scanner-unavailable"));
    }

    @Test
    void noTestsAtAllIsInsufficientEvidenceNeverAccept() {
        Verdict verdict = VerdictRules.decide(input(side("1.0.0", List.of()), side("2.0.0", List.of()), scan(true, List.of(), List.of())));
        assertEquals(Decision.INSUFFICIENT_EVIDENCE, verdict.decision());
        assertTrue(verdict.codes().contains("tests-did-not-run"));
    }

    @Test
    void blockingEvidenceStillRejectsWhenTheAfterTestsTimedOut() {
        SideObservation base = side("2.0.0", passing("t1"));
        SideObservation slow = copy(base, pass(), StageResult.notRun(), new StageResult("TIMED_OUT", null, 900, "verify.log"), false, true, List.of());
        Verdict verdict = VerdictRules.decide(input(side("1.0.0", passing("t1")), slow, scan(true, List.of(), List.of("org.demo:lib NEW"))));
        assertEquals(Decision.REJECT, verdict.decision());
        assertTrue(verdict.codes().contains("tests-timed-out"));
    }

    @Test
    void collateralVersionChangesAreReportedNotHidden() {
        SideObservation before = new SideObservation("b", pass(), pass(), pass(), passing("t1"),
                List.of(new ResolvedDependency("org.demo", "lib", "jar", "1.0.0", "compile"), new ResolvedDependency("org.other", "dep", "jar", "1.0", "compile")), false, false, "21", "3.9");
        SideObservation after = new SideObservation("a", pass(), pass(), pass(), passing("t1"),
                List.of(new ResolvedDependency("org.demo", "lib", "jar", "2.0.0", "compile"), new ResolvedDependency("org.other", "dep", "jar", "1.5", "compile")), false, false, "21", "3.9");
        Verdict verdict = VerdictRules.decide(input(before, after, scan(true, List.of(), List.of())));
        assertTrue(verdict.codes().contains("transitive-changes"));
    }
}
