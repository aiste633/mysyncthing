#!/usr/bin/env bash
# Keeps one GitHub issue (label "vulnerability") in step with the vulnerability scans of
# .github/workflows/vulnerabilities.yml: opens it when a scan finds something, updates it and
# comments when the findings change, closes it when nothing is found any more.
# A scan that broke opens a second issue (label "vulnerability-scan") instead and leaves the
# first one as it is; the next run with every scan working closes it.
#
# Usage:  scripts/vulns/issue.sh REPORT_DIR
#         REPORT_DIR holds one Markdown report per scan that found something; empty is all clear.
# Env:    GH_TOKEN (issues: write), GH_REPO, RUN_URL, ASSIGNEE;
#         BROKEN: the names of the scans that failed, space-separated;
#         DRY_RUN=1 only prints what it would do (pull requests).
set -euo pipefail

dir="$1"
label=vulnerability
title="Published vulnerabilities affect the app"
max=20000   # characters per report; a GitHub issue holds 65536

gh() {
  if [ "${DRY_RUN:-}" = 1 ] && [ "$1 ${2:-}" != "issue list" ] && [ "$1 ${2:-}" != "issue view" ]; then
    printf 'Would run: gh'; printf ' %q' "$@"; echo
  else
    command gh "$@"
  fi
}

open_issue() {
  gh issue list --label "$1" --state open --json number --jq '.[0].number // empty'
}

broken="$(open_issue vulnerability-scan)"
if [ -n "${BROKEN:-}" ]; then
  if [ -z "$broken" ]; then
    gh label create vulnerability-scan --color FBCA04 --force \
      --description "The daily vulnerability scan does not work"
    gh issue create --title "The daily vulnerability scan is broken" --label vulnerability-scan \
      --assignee "$ASSIGNEE" --body "These scans failed:$BROKEN ([run]($RUN_URL)). Until they work \
again, vulnerabilities published for them are not reported. This issue closes itself after the \
first run in which every scan works."
  fi
  echo "Scans failed:$BROKEN; the vulnerability issue is left as it is." >&2
  exit 1
fi
[ -z "$broken" ] || gh issue close "$broken" --reason completed --comment "Every scan works again: $RUN_URL"

number="$(open_issue "$label")"
shopt -s nullglob
reports=("$dir"/*.md)

if [ "${#reports[@]}" -eq 0 ]; then
  if [ -n "$number" ]; then
    gh issue close "$number" --reason completed \
      --comment "Nothing published affects the app any more: $RUN_URL"
  fi
  echo "Nothing found."
  exit 0
fi

findings="$(for r in "${reports[@]}"; do
  if [ "$(wc -c < "$r")" -le "$max" ]; then
    cat "$r"
  else
    cut="$(head -c "$max" "$r")"
    echo "$cut"
    # Close a code block the cut left open.
    [ $(($(grep -c '^```' <<< "$cut") % 2)) = 0 ] || echo '```'
    printf '\n(cut here: the whole report is in the run)\n'
  fi
  echo
done)"
fingerprint="$(printf '%s' "$findings" | sha256sum | cut -c1-16)"
body="The [daily vulnerability scan]($RUN_URL) found published vulnerabilities that affect the app.
This issue is updated when the findings change and closes itself when nothing is left.

$findings

**What to do**

- Go: bump the module, or the Go toolchain, in \`go/bridge/go.mod\`; then run \`scripts/sbom.sh\`.
- Libraries: bump the dependency and run \`scripts/sbom.sh\`. If an advisory cannot affect the app, or only upstream Syncthing can fix it, accept it with a reason in \`scripts/vulns/accepted.toml\`.
- GitHub Actions: pin the fixed release by its commit hash (see the README).

<!-- findings $fingerprint -->"

if [ -z "$number" ]; then
  gh label create "$label" --color B60205 --force \
    --description "A published vulnerability affects the app"
  gh issue create --title "$title" --label "$label" --assignee "$ASSIGNEE" --body "$body"
elif ! gh issue view "$number" --json body --jq .body | grep -qF -- "<!-- findings $fingerprint -->"; then
  gh issue edit "$number" --body "$body"
  gh issue comment "$number" --body "The findings changed; the description shows them now: $RUN_URL"
else
  echo "Issue #$number already shows these findings."
fi
[ "${DRY_RUN:-}" != 1 ] || printf '\nIssue text:\n\n%s\n' "$body"
