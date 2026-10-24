# Implementation status

Real means it ran for real here. Fake-tested means it ran against a stand-in that behaves like the real thing. Pending means it has not been run.

## Real

- Maven engine: pass, test regression, compile failure, missing target version, broken baseline, unsupported project and a known-vulnerable library (log4shell) all run against real Maven builds and the real vulnerability database (slow tests).
- Snapshots, comparison and recovery rehearsal on real Maven projects.
- API difference between two library versions (japicmp) inside the engine evidence.
- Hub: registered projects, rehearsal job, snapshot job, results page, before and after tabs, diagram, Markdown export, settings page, shown in a real browser (narrow and wide window).
- A rehearsal started from the dashboard on a real Maven project (commons-lang3 3.12.0 to 3.14.0) finished with ACCEPT.
- The hub ran the UpgradeGuard tool on a real Python project (requests 2.28.2 to 2.32.5) and showed ACCEPT with the evidence checked against contract v1.
- The MCP endpoint listed its tools and returned the report of that real job.
- The hub called a running BreakCast service (fixture model) through the risk endpoint, and reported `RISK_UNREACHABLE` cleanly when the service was stopped.
- The new dashboard theme (local Inter and JetBrains Mono fonts, mint on dark, light blue with thick outlines in light mode, saved choice) was checked in a real browser in both modes on the projects list, a real run result and the explanation dialog.
- A rehearsal started through the REST API on the real `words-app` project (commons-lang3 3.12.0 to 3.14.0) finished with ACCEPT and showed in the dashboard.
- The explanation dialog (choose report only or up to three project files, see the exact redacted text and where it would go, confirm) was opened against a real result with no model configured.
- Evaluation: `EVALUATION.md` was committed before the cases were run; `EVALUATION_RESULTS.md` records the outcome. Controlled cases 10 of 10, external projects 2 of 3 matched and 1 baseline unusable. Running it found and fixed a real defect (the API comparison crashed a whole run when a library's parent classes were in another jar).
- The hub container image builds, starts as a non-root user with a read-only filesystem and all capabilities dropped, answers 401 without a token and 200 with one.

## Fake-tested

- Azure VM provider: the real `worker/sentry-worker` script runs behind a fake `ssh` and `scp`. It proves the protocol, argument checks, link refusal and output verification. It does not prove a real machine works.
- Explanation provider: an OpenAI-compatible adapter tested against a local mock server. No real model was called and no key exists in this repository.
- Risk model: tested against a mock server and once against the BreakCast fixture model, which is not a research result.

## Pending
- A run on a real Azure VM. The worker script and the provider are tested against a fake `ssh` and `scp`; no real machine was used in this release either.
- A real model call. The explanation provider is tested against a local mock server only, so a real endpoint is unverified.

- First live check on a real Azure VM (see `AZURE_WORKER.md`). Needs a VM and credentials.
- Public hosted deployment. The container files exist; no public site was created.
- A real trained risk model (BreakCast has no verified passing upgrades yet).
- Client-usage matching of removed APIs (ASM) and encoder fine-tuning: stretch items, not started.
- Automated tests for the dashboard screens themselves. They were checked by hand in a browser; the API behind them has tests.

## Checks

| Command | Result |
|---|---|
| `./mvnw test` | engine 85 passed, app 77 passed, 0 failed, 0 skipped |
| `./mvnw -Pslow test` | engine 16 real-Maven tests passed (needs network) |
| `demo/evaluate-external.sh` | E1 REJECT, E2 REJECT, E3 INSUFFICIENT_EVIDENCE (baseline does not build), see `EVALUATION_RESULTS.md` |
| `demo/run.sh` | pass, test failure, compile failure verdicts |

## Choices made without asking

- Dashboard styling: Vaadin core with the Lumo theme (free). Charts are not used (Vaadin Charts is paid). Diagrams use Mermaid in strict mode.
- Dashboard pages allow script evaluation and a base tag because the dashboard framework needs both; the API and MCP paths keep the strict policy.
- Settings changes and connection tests need an administrator token when tokens are set.
- Passing runs are the only runs counted as "last worked"; a snapshot taken when tests did not pass is labelled starting-only.

## Changes in this release

- Model claims must cite evidence ids that the service provided (`finding:`, `dep:`, `test:`, `file:`). Claims that cite nothing provided are dropped, and a summary that cites nothing provided is not shown.
- Model text is reduced to plain text (links, markup and angle brackets removed) before it is shown or exported.
- Project files can be shared with the model only after a preview and a confirm. Files must import the changed library, at most three, secret-looking text is removed from the whole file before it is cut to 12 KB, and the preview shows the exact text that would be sent.
- BreakCast integration is off by default and its card stays hidden unless enabled. It is not part of this project's results.
- Java is about 10,100 lines, CSS and JavaScript about 230 lines.
