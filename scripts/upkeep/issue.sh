#!/usr/bin/env bash
# Keeps one GitHub issue (label "upkeep") in step with the checks of .github/workflows/upkeep.yml:
# opens it when they find something (newer releases of our pins, F-Droid out of step), updates
# it when that changes, closes it when nothing is left. A check that broke opens a second issue
# (label "upkeep-check") and leaves the first as it is; the next run with every check working
# closes it.
#
# Usage:  scripts/upkeep/issue.sh REPORT
#         REPORT is the checks' Markdown, one section each; empty is nothing to do.
# Env:    GH_TOKEN (issues: write), GH_REPO, RUN_URL, ASSIGNEE;
#         BROKEN: the names of the checks that failed, space-separated;
#         DRY_RUN=1 only prints what it would do (pull requests).
set -euo pipefail

report="$1"
label=upkeep

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

broken="$(open_issue upkeep-check)"
if [ -n "${BROKEN:-}" ]; then
  if [ -z "$broken" ]; then
    gh label create upkeep-check --color FBCA04 --force \
      --description "The monthly upkeep check does not work"
    gh issue create --title "The monthly upkeep check is broken" --label upkeep-check \
      --assignee "$ASSIGNEE" --body "These checks failed:$BROKEN ([run]($RUN_URL)). Until they \
work again, what they look for is not reported. This issue closes itself after the first run in \
which every check works."
  fi
  echo "Checks failed:$BROKEN; the upkeep issue is left as it is." >&2
  exit 1
fi
[ -z "$broken" ] || gh issue close "$broken" --reason completed --comment "Every check works again: $RUN_URL"

number="$(open_issue "$label")"
if [ ! -s "$report" ]; then
  [ -z "$number" ] || gh issue close "$number" --reason completed --comment "Nothing left to do: $RUN_URL"
  echo "Nothing to do."
  exit 0
fi

fingerprint="$(sha256sum < "$report" | cut -c1-16)"
body="The [upkeep check]($RUN_URL) found work to do.
This issue is updated when that changes and closes itself when nothing is left.

$(cat "$report")

**How to bump** (cadence and steps: README, Keeping up to date)

- One pull request per bump or small batch, as usual. Read the release notes first: a new major
  version of an action needs \`using: node24\` or later in its \`action.yml\`.
- Gradle, AGP or a library: regenerate \`gradle/verification-metadata.xml\` (README).
- Anything the APK contains or is built with: run \`scripts/sbom.sh\`.
- NDK, JDK or a new build step: the F-Droid recipe changes too, and fdroiddata needs it before the next tag.

<!-- upkeep $fingerprint -->"

if [ -z "$number" ]; then
  gh label create "$label" --color 0E8A16 --force --description "Newer releases or F-Droid out of step"
  gh issue create --title "Upkeep needed" --label "$label" --assignee "$ASSIGNEE" --body "$body"
elif ! gh issue view "$number" --json body --jq .body | grep -qF -- "<!-- upkeep $fingerprint -->"; then
  gh issue edit "$number" --body "$body"
  gh issue comment "$number" --body "The findings changed; the description shows them now: $RUN_URL"
else
  echo "Issue #$number already shows these findings."
fi
[ "${DRY_RUN:-}" != 1 ] || printf '\nIssue text:\n\n%s\n' "$body"
