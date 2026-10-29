#!/usr/bin/env bash
# Checks the actions our workflows pin against GitHub's published advisories, with zizmor's
# known-vulnerable-actions audit (the same zizmor as the "workflows" job in build.yml).
# Prints a Markdown report of what it finds. Needs GH_TOKEN: the audit asks the GitHub API.
#
# Usage:  scripts/vulns/actions.sh [workflow files or directories, default .github/workflows]
# Exit:   0 nothing found, 1 vulnerable actions, anything else an error.
set -euo pipefail
# Exit 1 means found: any command that fails exits 2 instead.
trap 'exit 2' ERR

root="$(cd "$(dirname "$0")/../.." && pwd)"
[ -n "${GH_TOKEN:-}" ] || { echo "GH_TOKEN is not set" >&2; exit 2; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

python3 -m venv "$work/venv"
"$work/venv/bin/pip" install --quiet --no-deps --require-hashes -r "$root/scripts/zizmor-requirements.txt"
# --no-exit-codes: exit 0 with findings, so only a failure of zizmor itself stops here.
"$work/venv/bin/zizmor" --quiet --format json --no-exit-codes "${@:-$root/.github/workflows}" > "$work/zizmor.json"

jq -r --arg root "$root/" '.[] | select(.ident == "known-vulnerable-actions" and (.ignored | not))
  | .locations[0]
  | "- [\(.symbolic.annotation)](https://github.com/advisories/\(.symbolic.annotation)):"
    + " `\(.concrete.feature)` in \(.symbolic.key.Local.verbatim_path // "?" | ltrimstr($root))"
    + " line \(.concrete.location.start_point.row + 1)"' "$work/zizmor.json" > "$work/found.md"

[ -s "$work/found.md" ] || exit 0
echo "### Pinned GitHub Actions ($(wc -l < "$work/found.md") found)"
echo
cat "$work/found.md"
exit 1
