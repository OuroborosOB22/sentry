# Evaluation results

Run on 25 October 2026 on one Mac (Apple silicon), JDK 25 for the tool and the controlled cases, JDK 11 (Corretto) for the three external projects. The expected outcomes were committed first, in `EVALUATION.md` (commit `1ff76e0`), before any case was run.

## Controlled cases

`./mvnw -Pslow test -pl engine` ran 16 real-Maven tests: 16 passed, 0 failed (11 in `MavenEngineSlowTest`, 5 in `SnapshotSlowTest`). `demo/run.sh` gave the three command line cases.

| Id | Expected | Result |
|---|---|---|
| C1 demo-lib 1.0.0 to 1.1.0 | ACCEPT | ACCEPT (demo script and test) |
| C2 1.0.0 to 1.2.0 | REJECT, test regression | REJECT (demo script and test) |
| C3 1.0.0 to 2.0.0 | REJECT, compile failure, API diff lists the removed method | REJECT (demo script and test) |
| C4 version that does not exist | REJECT, build failure | as expected (test) |
| C5 baseline that does not build | INSUFFICIENT_EVIDENCE | as expected (test) |
| C6 wrong claimed starting version | INSUFFICIENT_EVIDENCE | as expected (test) |
| C7 project without tests | never ACCEPT | as expected (test) |
| C8 version managed by a parent | unsupported, not guessed | as expected (test) |
| C9 new advisory | REJECT | as expected (test) |
| C10 advisory service unreachable | disclosed, not counted as clean | as expected (test) |

C9 and C10 use a scanner answer that the test supplies. Only one test, the Log4Shell lookup, talks to the real vulnerability service.

## External projects

| Id | Project | Expected | First run | After the fix |
|---|---|---|---|---|
| E1 | hap-java/HAP-Java, bcprov-jdk15on 1.51 to 1.67 | REJECT | REJECT | not rerun, not needed |
| E2 | sabomichal/jooq-meta-postgres-flyway, jooq-meta 3.16.6 to 3.17.0 | REJECT | no result: the run crashed | REJECT |
| E3 | BadSkater0729/WorldwideChat, XSeries 8.5.0.1 to 8.6.0 | REJECT | no result: the run crashed | INSUFFICIENT_EVIDENCE (baseline does not build) |

What the findings were:

- E1: two blocking findings. The project compiled with 1.51 and no longer compiles with 1.67, which is the compilation failure BUMP recorded. The change also brings one advisory that was not present before (and removes 13).
- E2: the project compiled before and no longer compiles with 3.17.0, again as BUMP recorded. The tests of the current version did not run, so there was no test baseline, and the tool says so.
- E3: `baseline-build-failed`. The project could not be resolved and built with the current version here, so there was nothing to compare against. By the rule fixed in advance this counts as "baseline unusable". It is not a match for the expected REJECT and it is not a wrong answer.

## What this evaluation found about the tool

The first run of E2 and E3 did not produce a verdict at all. The API comparison library (japicmp) stopped with "Could not load a class" when a library's own parent classes were not in the jar, and that error ended the whole run. Fixed afterwards: missing classes are now ignored for the comparison, and if a comparison still fails it is reported as "API comparison unavailable" and the verdict is decided without it. New tests: `ApiDifferTest`. E2 and E3 were rerun after the fix. E1 was not affected.

## Summary

| | Cases | Matched | Baseline unusable | Wrong |
|---|---|---|---|---|
| Controlled | 10 | 10 | 0 | 0 |
| External | 3 | 2 | 1 | 0 |

No wrongly accepted upgrade in any case. All three external projects are known breaking updates, so this says nothing about false alarms on healthy upgrades. See `EVALUATION.md` for what the set cannot show.
