#!/usr/bin/env bash
# Regenerates the Syncthing-Fork fixtures with zip4j, the library Fork uses.
#   gen.sh             the committed goldens in this directory (no keys in them)
#   gen.sh dummy DIR   complete dummy backups for emulator tests (synthetic keys,
#                      IDs and paths; not committed)
#   gen.sh unzip ZIP DIR [PASSWORD]
#                      extracts an export of this app with zip4j (interop check)
# Needs Java 21+ and Go; downloads zip4j once and checks its hash.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
zip4j_version=2.11.6
zip4j_sha256=5f1eb1e9d67cfee200a695e4079751b49e73a226b154193e13f7c602efbe72fb
cache="${XDG_CACHE_HOME:-$HOME/.cache}/mysyncthing"
jar="$cache/zip4j-$zip4j_version.jar"
if ! echo "$zip4j_sha256  $jar" | sha256sum -c --status 2>/dev/null; then
  mkdir -p "$cache"
  curl -sSfL -o "$jar" "https://repo1.maven.org/maven2/net/lingala/zip4j/zip4j/$zip4j_version/zip4j-$zip4j_version.jar"
  echo "$zip4j_sha256  $jar" | sha256sum -c --status
fi
# Java decodes arguments in the locale's charset: a non-ASCII password needs UTF-8.
gen() { LC_ALL=C.UTF-8 java -cp "$jar" "$here/GenForkFixtures.java" "$@"; }

if [ "${1:-}" = unzip ]; then
  gen unzip "$2" "$3" "${4:-}"
  exit 0
fi

if [ "${1:-}" = dummy ]; then
  out="$(realpath -m "$2")"
  mkdir -p "$out"
  # Config dirs (config.xml, keys, a stand-in database) from the Go test helper.
  (cd "$here/../.." && DUMMY_FORK_DIR="$out" go test -run '^TestWriteDummyForkBackups$' -count=1 . >/dev/null)
  for d in "$out"/*/; do
    name="$(basename "$d")"
    gen prefs "$d/sharedpreferences.dat" "$(cat "$d/prefs-variant")"
    rm "$d/prefs-variant"
    password="$(cat "$d/password" 2>/dev/null || true)"
    rm -f "$d/password"
    gen zip "$d" "$out/$name.zip" "$password"
  done
  ls -l "$out"/*.zip
  exit 0
fi

for v in full minimal typed; do gen prefs "$here/prefs-$v.dat" "$v"; done
gen golden "$here"
