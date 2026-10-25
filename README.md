<p align="center"><img src="app/src/main/resources/META-INF/resources/logo.png" width="120" alt="Sentry logo"></p>

# Sentry

Try a dependency upgrade on a Java (Maven) project in a separate copy, and see exactly what breaks, before you touch the real project.

Sentry builds your project twice, once as it is and once with one dependency changed, then compares the tests, the resolved dependencies, the library's public methods and known vulnerabilities. It answers **Accept**, **Reject** or **Insufficient Evidence**, and every reason points to a saved file. It also remembers when a project last worked, so you can see what changed since.

A language model never decides the answer. It can only explain measured facts, and only if you switch that on.

## What is in the box

| Folder | What it is |
|---|---|
| `engine/` | the Maven engine and command line: edits one dependency in a copy, builds before and after, compares |
| `app/` | the web app (Spring Boot and Vaadin): projects, jobs, results, settings, a REST API |
| `worker/` | the small program the app may run on a remote machine |
| `demo/` | a tiny project and library for a three-upgrade demo |
| `contracts/v1/` | the JSON format the results are saved in |

## Needs

Java 21 or newer. The `mvnw` wrapper downloads Maven. You need internet for Maven Central and the vulnerability database. The first build also downloads the packages for the web screens.

## Try the demo

```
demo/run.sh
```

It tries three upgrades of a small library: one safe, one that changes behaviour (tests fail) and one that removes a method (compile fails). The results land in `demo/evidence/`.

Your own project:

```
bin/sentry verify --project <folder> --package org.example:lib --from 1.0.0 --to 1.1.0 --out evidence --run-id try-1
```

## Run the web app

```
./mvnw -B -pl app -am -Pproduction -DskipTests package
java -jar app/target/sentry-app-0.1.0.jar \
  --sentry.allowed-roots=/path/to/your/projects \
  --sentry.engine-jar=$PWD/engine/target/sentry-engine-0.1.0-cli.jar \
  --sentry.maven-executable=$PWD/mvnw
```

Open http://127.0.0.1:8080. The front page is at `/welcome/index.html`, and **Start** there opens the app. All settings are listed in `.env.example`.

## Safety rules

- With no access tokens, the app answers only on your own machine. With tokens, every call needs one and each token is limited to named projects.
- Folders must be inside `sentry.allowed-roots`. Paths, links and archive entries that try to escape are refused.
- Running project code next to the app is switched off whenever tokens are set, unless you allow it on purpose.
- Tools are started with argument lists, never shell strings, and with a cleaned environment.
- Output from a remote worker is checked (format, hashes, no links) before it is shown.

## Tests

```
./mvnw test              # fast tests
./mvnw -Pslow test       # real Maven builds, needs internet
```

## How well does it work

We wrote the expected answer for every case before running it: 10 small controlled cases, 3 real projects with a known breaking update, and 3 healthy upgrades.

| | cases | matched | baseline unusable | wrong |
|---|---|---|---|---|
| controlled | 10 | 10 | 0 | 0 |
| real projects, known breaking update | 3 | 2 | 1 | 0 |
| real project, healthy upgrade (expected Accept) | 3 | 3 | 0 | 0 |

For the unusable one, the project did not build even before the change, so there was nothing to compare. No upgrade was wrongly accepted and no healthy upgrade was wrongly rejected. The three healthy upgrades are all on one project (HAP-Java: slf4j-api 1.7.10 to 1.7.36, assertj-core 3.19.0 to 3.21.0, junit 4.12 to 4.13.2), and their tests ran on both sides. That is a small set: it says nothing about large projects, multi-module builds or libraries whose API changes without breaking the compile.

## Honest limits

- Maven projects only in this repository's engine.
- The Azure or SSH worker has not run on a real machine yet, and the explanation feature was only tried with a stand-in model.
- A passing test suite does not prove an upgrade is safe in production, licence-compliant or free of malware.

## Team

Gurman Singh, Madhur Tuteja, Paryag.
