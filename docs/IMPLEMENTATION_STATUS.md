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
- The hub container image builds, starts as a non-root user with a read-only filesystem and all capabilities dropped, answers 401 without a token and 200 with one.

## Fake-tested

- Azure VM provider: the real `worker/sentry-worker` script runs behind a fake `ssh` and `scp`. It proves the protocol, argument checks, link refusal and output verification. It does not prove a real machine works.
- Explanation provider: an OpenAI-compatible adapter tested against a local mock server. No real model was called and no key exists in this repository.
- Risk model: tested against a mock server and once against the BreakCast fixture model, which is not a research result.

## Pending

- First live check on a real Azure VM (see `AZURE_WORKER.md`). Needs a VM and credentials.
- Public hosted deployment. The container files exist; no public site was created.
- A real trained risk model (BreakCast has no verified passing upgrades yet).
- Client-usage matching of removed APIs (ASM) and encoder fine-tuning: stretch items, not started.
- Automated tests for the dashboard screens themselves. They were checked by hand in a browser; the API behind them has tests.

## Checks

| Command | Result |
|---|---|
| `./mvnw test` | see the report in the final message |
| `./mvnw -Pslow test` | see the report in the final message |
| `demo/run.sh` | pass, test failure, compile failure verdicts |

## Choices made without asking

- Dashboard styling: Vaadin core with the Lumo theme (free). Charts are not used (Vaadin Charts is paid). Diagrams use Mermaid in strict mode.
- Dashboard pages allow script evaluation and a base tag because the dashboard framework needs both; the API and MCP paths keep the strict policy.
- Settings changes and connection tests need an administrator token when tokens are set.
- Passing runs are the only runs counted as "last worked"; a snapshot taken when tests did not pass is labelled starting-only.
