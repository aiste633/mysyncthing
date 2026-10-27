#!/usr/bin/env bash
# Applies go/patches/*.patch to the go/syncthing submodule: fixes we need before upstream
# has them. Idempotent: patches already applied are skipped. Patches may change the same
# files, so the check is on all the files they touch together: these must be as in the
# pinned commit plus the first k patches, for some k; the rest are then applied. Other
# files don't count (F-Droid's scanner deletes some). Fails on any other state, e.g.
# after a Syncthing update (then drop or rebase the patches) or with local edits.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
sub="$here/syncthing"
patches=("$here"/patches/*.patch)
[ -e "${patches[0]}" ] || exit 0

idx="$(mktemp)"
trap 'rm -f "$idx"' EXIT
g() { GIT_INDEX_FILE="$idx" git -C "$sub" "$@"; }

mapfile -t paths < <(for p in "${patches[@]}"; do git -C "$sub" apply --numstat "$p" | cut -f3; done | sort -u)

# The pinned commit with the touched files as they are in the working directory.
g read-tree HEAD
for f in "${paths[@]}"; do
  if [ -e "$sub/$f" ]; then g update-index --add -- "$f"; else g update-index --force-remove -- "$f"; fi
done
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
  echo "go/syncthing: the files the patches change are neither as pinned nor as after the first patches" >&2
  exit 1
fi

for p in "${patches[@]:applied}"; do
  git -C "$sub" apply "$p"
  echo "applied $(basename "$p")"
done
