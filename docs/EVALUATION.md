# Evaluation set

This file was written and committed before any case in it was run. The expected outcomes below are fixed. Results go in `EVALUATION_RESULTS.md`, with every mismatch reported as it happened.

Outcomes are ACCEPT, REJECT or INSUFFICIENT_EVIDENCE. Passing tests never mean an upgrade is safe in production, only that nothing we measured broke.

## Controlled cases

The library `demo-lib` is built locally in four versions (`demo/build-fixture-repo.sh`), so nothing depends on the network except Maven's own plugins. These cases exist as automated tests: `./mvnw -Pslow test` (class `MavenEngineSlowTest` and `SnapshotSlowTest`) and as a command line demo, `demo/run.sh`.

| Id | Change | What is special | Expected |
|---|---|---|---|
| C1 | demo-lib 1.0.0 to 1.1.0 | one method added | ACCEPT |
| C2 | demo-lib 1.0.0 to 1.2.0 | a greeting text changes and a test notices | REJECT (test regression) |
| C3 | demo-lib 1.0.0 to 2.0.0 | a method the client calls is removed | REJECT (compile failure), and the API comparison lists the removed method |
| C4 | demo-lib 1.0.0 to a version that does not exist | the target cannot be resolved | REJECT (build failure) |
| C5 | project whose baseline does not build | broken baseline | INSUFFICIENT_EVIDENCE |
| C6 | claimed starting version differs from the project's real one | wrong premise | INSUFFICIENT_EVIDENCE |
| C7 | project with no tests | nothing observed at run time | INSUFFICIENT_EVIDENCE, never ACCEPT |
| C8 | version managed by a parent POM | unsupported layout | INSUFFICIENT_EVIDENCE, reported as unsupported and not guessed |
| C9 | a library version with a new advisory in the scanner | everything else fine | REJECT |
| C10 | advisory service unreachable | scanner unavailable | not counted as clean; disclosed as unavailable |

## External projects

These are real open source projects, not written by us. Each case comes from the BUMP benchmark of breaking Maven updates (MIT licence). BUMP records that the update broke the build with a compilation failure. The baseline is the commit just before the breaking commit. All three are built with JDK 11, which BUMP recorded for them.

| Id | Project | Change | BUMP says | Expected |
|---|---|---|---|---|
| E1 | hap-java/HAP-Java | org.bouncycastle:bcprov-jdk15on 1.51 to 1.67 | compilation failure | REJECT |
| E2 | sabomichal/jooq-meta-postgres-flyway | org.jooq:jooq-meta 3.16.6 to 3.17.0 | compilation failure | REJECT |
| E3 | BadSkater0729/WorldwideChat | com.github.cryptomorin:XSeries 8.5.0.1 to 8.6.0 | compilation failure | REJECT |

Rule fixed in advance: if the baseline itself does not build here (a missing repository, a private dependency, a plugin that needs a newer JDK), Sentry must report INSUFFICIENT_EVIDENCE. That is the correct behaviour for an unusable baseline, so it is recorded as "baseline unusable" and not as a wrong answer. It is also not counted as a success for the case. Only REJECT matches the expectation.

Run them with `demo/evaluate-external.sh` (needs git, network, and the JDK paths in `demo/external-cases.tsv`).

## What this set cannot show

- Three external projects say little about accuracy in general. They show that the tool reads real builds, not that it is right most of the time.
- All three external cases are known breaking updates, so they test that breakage is caught. They say nothing about false alarms. The only passing external-style case is the small `words-app` demo (commons-lang3 3.12.0 to 3.14.0, expected ACCEPT), which is written by us.
- Controlled cases are built to have a clear answer. Real projects are messier.
