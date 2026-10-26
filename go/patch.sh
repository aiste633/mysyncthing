#!/usr/bin/env bash
# Applies go/patches/*.patch to the go/syncthing submodule: fixes we need before upstream
# has them. Idempotent: a patch that is already applied is skipped. Fails if one applies
# neither way, e.g. after a Syncthing update: then drop or rebase it.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
for p in "$here"/patches/*.patch; do
  [ -e "$p" ] || continue
  if git -C "$here/syncthing" apply --reverse --check "$p" 2>/dev/null; then
    continue
  fi
  git -C "$here/syncthing" apply "$p"
  echo "applied $(basename "$p")"
done
