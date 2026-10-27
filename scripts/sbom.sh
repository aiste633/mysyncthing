#!/usr/bin/env bash
# Writes the SBOM of the release APK: docs/sbom.cdx.json (CycloneDX) and docs/sbom.md.
# Run it after changing any dependency, the Syncthing version or the pinned toolchain,
# and commit the result. CI runs it with --check and fails when the files are stale.
#
# Usage:  scripts/sbom.sh [--check]
# Needs:  what the app build needs (JDK, ANDROID_HOME, the go/syncthing submodule), Go.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

[ -f go/syncthing/go.mod ] || { echo "go/syncthing submodule missing: git submodule update --init" >&2; exit 1; }
# As go/build.sh: the patched core with its generated web GUI assets.
go/patch.sh > /dev/null
(cd go/syncthing && go generate ./lib/api/auto)

# The configuration cache is off because the task reads the resolved dependency graph.
./gradlew --quiet --no-configuration-cache -I scripts/sbom/runtime-deps.init.gradle.kts \
  :app:sbomRuntimeDeps > "$work/gradle.txt"

mkdir "$work/out"
(cd scripts/sbom && go run . -repo "$root" -gradle "$work/gradle.txt" -out "$work/out")

if [ "${1:-}" = --check ]; then
  if ! diff -u docs/sbom.md "$work/out/sbom.md" || ! diff -u docs/sbom.cdx.json "$work/out/sbom.cdx.json" > /dev/null; then
    echo "docs/sbom.* is out of date: run scripts/sbom.sh and commit the result." >&2
    exit 1
  fi
  echo "SBOM up to date."
else
  cp "$work/out/sbom.cdx.json" "$work/out/sbom.md" docs/
  echo "Wrote docs/sbom.cdx.json and docs/sbom.md."
fi
