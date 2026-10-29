#!/usr/bin/env bash
# Checks every library in the SBOM (docs/sbom.cdx.json) against the published vulnerabilities
# in the OSV database, minus those accepted in scripts/vulns/accepted.toml. Go modules are
# left to govulncheck. Prints a Markdown report of what is new.
#
# Usage:  scripts/vulns/osv.sh [--offline] [SBOM, default docs/sbom.cdx.json]
#         --offline downloads the whole database instead of asking api.osv.dev (about 250 MB).
# Exit:   0 nothing new, 1 new vulnerabilities, anything else an error.
set -euo pipefail
# Exit 1 means found: any command that fails exits 2 instead.
trap 'exit 2' ERR

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
# shellcheck source=/dev/null
. <(grep -E '^(version|sha256)=' "$here/osv-scanner.txt")
cache="${XDG_CACHE_HOME:-$HOME/.cache}/mysyncthing"
bin="$cache/osv-scanner-$version"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if ! echo "$sha256  $bin" | sha256sum --check --status 2> /dev/null; then
  mkdir -p "$cache"
  curl -fsSL --retry 3 -o "$work/osv-scanner" \
    "https://github.com/google/osv-scanner/releases/download/v$version/osv-scanner_linux_amd64"
  echo "$sha256  $work/osv-scanner" | sha256sum --check --quiet
  install -m 755 "$work/osv-scanner" "$bin"
fi

offline=()
if [ "${1:-}" = --offline ]; then
  offline=(--offline-vulnerabilities --download-offline-databases)
  export OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY="$cache/osv-db"
  shift
fi
sbom="${1:-$root/docs/sbom.cdx.json}"

# 0: nothing found, 1: something found (accepted or not); anything else is an error.
rc=0
"$bin" scan source "${offline[@]}" --format json -L "$sbom" \
  > "$work/osv.json" 2> "$work/osv.log" || rc=$?
if [ "$rc" -gt 1 ]; then
  cat "$work/osv.log" >&2
  echo "osv-scanner failed (exit $rc)" >&2
  exit 2
fi
report="$(python3 "$here/osv-report.py" "$work/osv.json" "$here/accepted.toml")"
[ -z "$report" ] || { echo "$report"; exit 1; }
