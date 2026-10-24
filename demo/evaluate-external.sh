#!/bin/sh
# runs the external cases in demo/external-cases.tsv and prints one line per case: id, expected, actual
set -u
here="$(cd "$(dirname "$0")/.." && pwd)"
work="${EVAL_WORK:-$(mktemp -d)}"
out="${EVAL_OUT:-$here/demo/evaluation-out}"
mkdir -p "$out"
tail -n +2 "$here/demo/external-cases.tsv" | while IFS="$(printf '\t')" read -r id repo commit package from to java expected; do
  folder="$work/$id"
  rm -rf "$folder"
  git clone -q "$repo" "$folder" && git -C "$folder" checkout -q "$commit^" || { echo "$id	$expected	CLONE_FAILED"; continue; }
  "$here/bin/sentry" verify --project "$folder" --package "$package" --from "$from" --to "$to" --java-home "$java" --out "$out" --run-id "eval-$id" --json > "$out/eval-$id.stdout.json" 2> "$out/eval-$id.stderr.log"
  actual="$(grep -o '"decision" *: *"[A-Z_]*"' "$out/eval-$id/evidence.json" 2>/dev/null | head -1 | sed 's/.*: *"\(.*\)"/\1/')"
  echo "$id	$expected	${actual:-NO_RESULT}"
done
