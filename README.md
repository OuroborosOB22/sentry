<p align="center"><img src="app/src/main/resources/META-INF/resources/logo.png" width="120" alt="Sentry logo"></p>

# Sentry

Remembers when a Java or Python project last worked, shows what changed since, and tries one dependency change in a separate copy before it touches the real project.

It answers three questions:

1. What changed since this project last worked?
2. If I change this dependency, what breaks, and why?
3. If something broke, how do I get back to the last good state?

A language model never decides a verdict. Verdicts come from build results, test results and policy rules. A model can only explain measured facts, and only if you switch that on.

## Parts

| Part | What it is |
|---|---|
| `engine/` | Maven engine and command line: edits one dependency in a copy, builds before and after, compares tests, scans for known vulnerabilities, diffs the library API, writes evidence in contract v1 |
| `app/` | Spring Boot hub: database, job queue, REST API, MCP endpoint, Vaadin dashboard, execution providers (this machine or an SSH worker VM) |
| `worker/` | the one program the hub may run on a remote machine |
| `contracts/v1/` | copy of the shared JSON contract |
| `demo/` | a small project and a local library repository for a three-upgrade demo |

Python projects run through the UpgradeGuard tool (a separate project) when `sentry.python-tool-dir` points to it. A risk estimate comes from BreakCast (another separate project) when `sentry.risk.enabled` is true. Both are optional and Sentry works without them.

## Needs

Java 21 or newer, Maven (the `mvnw` wrapper downloads it), network for Maven Central and the vulnerability database. The first build downloads Node packages for the dashboard.

## Test

```
./mvnw test                # fast tests, no network needed for most
./mvnw -Pslow test         # real Maven builds of small projects, needs network
```

## Run the demo (command line)

```
demo/run.sh
```

It builds a local fixture library and tries three upgrades of it: one safe, one that changes behaviour (tests fail), one that removes a method (compile fails). Evidence lands in `demo/evidence/`.

One check of your own project:

```
bin/sentry verify --project <folder> --package org.example:lib --from 1.0.0 --to 1.1.0 --out evidence --run-id try-1
```

## Run the dashboard

```
./mvnw -B -pl app -am -Pproduction -DskipTests package
java -jar app/target/sentry-app-0.1.0.jar \
  --sentry.allowed-roots=/path/to/your/projects \
  --sentry.engine-jar=$PWD/engine/target/sentry-engine-0.1.0-cli.jar \
  --sentry.maven-executable=$PWD/mvnw
```

Open http://127.0.0.1:8080. With no access tokens set, the hub answers only on this machine. See `.env.example` for every setting.

## Security rules the hub follows

- Without access tokens it only answers on this machine. With tokens, every call needs one and each token is limited to named projects.
- Local execution (which runs project code next to the hub) is switched off whenever tokens are set, unless you allow it on purpose.
- Folders must sit inside `sentry.allowed-roots`. Paths, links and archive entries that escape are refused.
- State changes need JSON and a same-site origin. Cross-site requests are refused.
- Tools run with argument arrays, never shell strings, and with a filtered environment.
- Remote output is verified (schema, hashes, no links) before it is shown.
- Diagrams are drawn in strict mode from measured data only.

More in `docs/IMPLEMENTATION_STATUS.md`. The VM worker guide is `docs/AZURE_WORKER.md`.

## Team

Gurman Singh, Madhur Tuteja, Paryag.
