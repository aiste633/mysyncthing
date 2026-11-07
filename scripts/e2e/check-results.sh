#!/usr/bin/env bash
# Checks that a release carries passing PC end-to-end results: docs/e2e/<version>.md as committed
# at <rev> (default HEAD), written by scripts/e2e/pc.py --release. It must say PASS for Android 15
# and 16, and name a commit in <rev>'s history that differs from <rev> only in files the APK and the
# tests never read: docs/, fastlane/ and top-level *.md (so the results file itself, a changelog or
# a README fix may follow the run). CI runs it on every v* tag (reproducible.yml); run it before tagging.
#
# Usage: scripts/e2e/check-results.sh <version> [<rev>]
set -euo pipefail

version="${1:?usage: check-results.sh <version> [<rev>]}"
rev="${2:-HEAD}"
cd "$(git rev-parse --show-toplevel)"
file="docs/e2e/$version.md"
fail() { echo "$file: $1" >&2; exit 1; }

text="$(git show "$rev:$file" 2>/dev/null)" ||
  fail "missing at $rev. Run scripts/e2e/pc.py --release on the release commit and commit the file it writes."
tested="$(sed -n 's/^- Commit: \([0-9a-f]\{40\}\)$/\1/p' <<<"$text")"
[ "$(wc -w <<<"$tested")" -eq 1 ] || fail "needs exactly one '- Commit: <40 hex>' line"
grep -qx -- '- Result: PASS' <<<"$text" || fail "the run did not pass"
for api in 35 36; do
  grep -qE "^## Android [0-9]+ \(API $api\): PASS\$" <<<"$text" || fail "no passing run on API $api"
done
git merge-base --is-ancestor "$tested" "$rev" 2>/dev/null ||
  fail "the tested commit ${tested:0:12} is not in the history of $rev"
changed="$(git diff --name-only "$tested" "$rev" -- . ':(exclude)docs/' ':(exclude)fastlane/' ':(exclude,glob)*.md')"
[ -z "$changed" ] || fail "changed since the tested commit ${tested:0:12}, so run pc.py again:
$changed"
echo "$file: passed on API 35 and 36 at ${tested:0:12}, and nothing the tests read changed since."
