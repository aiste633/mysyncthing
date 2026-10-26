#!/usr/bin/env bash
# Applies go/patches/*.patch to the go/syncthing submodule: fixes we need before upstream
# has them. Idempotent: patches already applied are skipped. Patches may change the same
# files, so the check is on the whole tree: the submodule must be its pinned commit plus
# the first k patches, for some k; the rest are then applied. Fails on any other state,
# e.g. after a Syncthing update (then drop or rebase the patches) or with local edits.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
sub="$here/syncthing"
patches=("$here"/patches/*.patch)
[ -e "${patches[0]}" ] || exit 0

idx="$(mktemp)"
trap 'rm -f "$idx"' EXIT
g() { GIT_INDEX_FILE="$idx" git -C "$sub" "$@"; }

# The tree in the working directory, untracked files included.
g read-tree HEAD
g add -A
actual="$(g write-tree)"

# The pinned commit plus the first k patches, for k = 0, 1, ...
g read-tree HEAD
applied=-1
for k in $(seq 0 ${#patches[@]}); do
  if [ "$k" -gt 0 ]; then
    g apply --cached "${patches[k-1]}" || break
  fi
  if [ "$(g write-tree)" = "$actual" ]; then
    applied=$k
  fi
done
if [ "$applied" -lt 0 ]; then
  echo "go/syncthing is neither its pinned commit nor that plus the first patches" >&2
  exit 1
fi

for p in "${patches[@]:applied}"; do
  git -C "$sub" apply "$p"
  echo "applied $(basename "$p")"
done
