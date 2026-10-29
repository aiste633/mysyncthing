#!/usr/bin/env bash
# CI wrapper for one vulnerability scan (.github/workflows/vulnerabilities.yml): runs it and
# sets the step output found=true|false. Findings stay in $RUNNER_TEMP/vulns/NAME.md, for the
# issue, and go to the step summary. A scan that fails fails the step and sets no output, so a
# broken scan never opens or closes the issue.
#
# Usage:  scripts/vulns/report.sh NAME COMMAND [ARGS...]
#         COMMAND prints Markdown and exits 0 (nothing found), 1 (found) or else (error).
set -euo pipefail

name="$1"
shift
out="$RUNNER_TEMP/vulns"
mkdir -p "$out"
rc=0
"$@" > "$out/$name.md" || rc=$?
case "$rc" in
  0) rm "$out/$name.md"
     echo found=false >> "$GITHUB_OUTPUT" ;;
  1) tee -a "$GITHUB_STEP_SUMMARY" < "$out/$name.md"
     echo found=true >> "$GITHUB_OUTPUT" ;;
  *) echo "The $name scan failed (exit $rc)." >&2
     exit "$rc" ;;
esac
