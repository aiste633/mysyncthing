#!/usr/bin/env bash
# Runs govulncheck with the given arguments and prints what it finds as Markdown, for
# scripts/vulns/report.sh. govulncheck reports only vulnerabilities in code the app can reach.
# Exit: 0 nothing found, 1 found, anything else an error.
set -euo pipefail
trap 'exit 2' ERR   # exit 1 means found

rc=0
out="$(govulncheck "$@")" || rc=$?
case "$rc" in
  0) echo "$out" >&2 ;;
  3) printf '### Go code (govulncheck)\n\n```text\n%s\n```\n' "$out"
     exit 1 ;;
  *) echo "$out" >&2
     echo "govulncheck failed (exit $rc)" >&2
     exit 2 ;;   # not 1, which means found
esac
