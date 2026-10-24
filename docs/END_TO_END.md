# One journey through all three projects

Observed on one machine. Every output below was seen, not written by hand. The risk model is the BreakCast fixture model, which is not a research result.

## Start

BreakCast (separate project, folder `mlProject`):

```
./.venv/bin/python -m breakcast.cli make-fixture-model
./.venv/bin/python -m breakcast.cli serve --model models/fixture --port 8090
```

Sentry (this project), pointing at UpgradeGuard (folder `sweProject`) and BreakCast:

```
./mvnw -B -pl app -am -Pproduction -DskipTests package
java -jar app/target/sentry-app-0.1.0.jar \
  --sentry.allowed-roots=<folder with the projects to check> \
  --sentry.engine-jar=$PWD/engine/target/sentry-engine-0.1.0-cli.jar \
  --sentry.python-tool-dir=<path to sweProject> \
  --sentry.python-executable=<path to sweProject>/.venv/bin/python \
  --sentry.risk.enabled=true --sentry.risk.base-url=http://127.0.0.1:8090
```

## Steps and what happened

1. Register the UpgradeGuard demo repository as a Python project: `POST /api/projects` answered `ecosystem: PYTHON`.
2. Start a rehearsal: `POST /api/projects/swe-demo/jobs` with `requests` 2.28.2 to 2.32.5. The hub ran `python -m upgradeguard run --json` in its own process, checked the evidence against contract v1 and finished in about 18 seconds with `ACCEPT` (no blocking evidence, 10 known vulnerabilities resolved).
3. Read the report over MCP: `tools/call` with `job_report` returned the same verdict and findings.
4. Register a small Maven project in the dashboard and try commons-lang3 3.12.0 to 3.14.0. Result `ACCEPT`, with a dependency table, a change diagram and the standing note that passing tests are not proof of production safety.
5. Ask for a risk estimate: `POST /api/projects/words-app/risk` returned status `OK`, score 0.34 for the minor change and 0.88 for a made-up major jump, model `fixture-1`, with an advisory line and the model's own "fixture model" scope text.
6. Stop BreakCast and ask again: the hub answered `RISK_UNREACHABLE` with a plain reason. Verdicts and everything else kept working.

## What this proves and does not prove

- The three projects talk only through contract v1 JSON and one HTTP call, and each runs without the others.
- It does not prove the risk score is useful. No model trained on real data exists yet.
