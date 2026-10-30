#!/usr/bin/env bash
# Warns before GitHub turns off the scheduled workflows. In a public repository GitHub disables
# them after 60 days without activity, and then nothing reports it: not the daily vulnerability
# scan, not the upkeep check. Once main has had no commit for 45 days, this opens an issue
# (label "upkeep-quiet"); the first run after a new commit closes it.
#
# Usage:  scripts/upkeep/quiet.sh
# Env:    GH_TOKEN (issues: write), GH_REPO, ASSIGNEE, RUN_URL;
#         DRY_RUN=1 only prints what it would do (pull requests); NOW: epoch seconds, for tests.
set -euo pipefail

warn_days=45
label=upkeep-quiet

gh() {
  case "${DRY_RUN:-} $1 ${2:-}" in
    "1 issue list" | "1 api "*) command gh "$@" ;;
    1\ *) printf 'Would run: gh'; printf ' %q' "$@"; echo ;;
    *) command gh "$@" ;;
  esac
}

last="$(gh api "repos/$GH_REPO/commits/HEAD" --jq .commit.committer.date)"
days=$(( (${NOW:-$(date -u +%s)} - $(date -u -d "$last" +%s)) / 86400 ))
number="$(gh issue list --label "$label" --state open --json number --jq '.[0].number // empty')"

if [ "$days" -lt "$warn_days" ]; then
  [ -z "$number" ] || gh issue close "$number" --reason completed --comment "main has a new commit: $RUN_URL"
  echo "Last commit on main $days days ago."
  exit 0
fi

echo "Last commit on main $days days ago: $((60 - days)) of 60 days left before the scheduled workflows stop." >&2
if [ -z "$number" ]; then
  gh label create "$label" --color FBCA04 --force \
    --description "GitHub turns off the scheduled checks after 60 quiet days"
  gh issue create --title "Scheduled checks stop soon: no commit for $days days" --label "$label" \
    --assignee "$ASSIGNEE" --body "main has had no commit for $days days ([run]($RUN_URL)). After \
60 days without activity GitHub turns off the scheduled workflows: the daily vulnerability scan \
and the upkeep check. Nothing reports that once it happens.

Merge any pull request before then; a bump from the open \`upkeep\` issue, if there is one, is a \
good candidate. If they were turned off already: Actions › the workflow › Enable workflow, for \
\`vulnerabilities\` and \`upkeep\`. This issue closes itself after the next commit on main."
fi
