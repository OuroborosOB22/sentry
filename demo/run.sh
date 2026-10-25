#!/bin/sh
# three upgrades of the same library: one safe, one that changes behaviour, one that removes a method
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"
"$here/demo/build-fixture-repo.sh"
for target in 1.1.0 1.2.0 2.0.0; do
  echo "== demo-lib 1.0.0 to $target"
  "$here/bin/sentry" verify --project "$here/demo/client" --package org.upgradeguard.fixture:demo-lib --from 1.0.0 --to "$target" \
    --property "fixture.repo=$here/demo/fixture-repo" --out "$here/demo/evidence" --run-id "demo-$target" || true
done
