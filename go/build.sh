#!/usr/bin/env bash
# Builds go/build/stbridge.aar (Syncthing core + bridge) with gomobile.
# Needs: Go (toolchain auto-download ok), ANDROID_HOME, ANDROID_NDK_HOME.
# -checklinkname=0: github.com/wlynxg/anet (Android interface listing) links to net internals.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
st="$here/syncthing"
out="$here/build"
export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"

[ -f "$st/go.mod" ] || { echo "go/syncthing submodule missing: git submodule update --init" >&2; exit 1; }

# Shallow submodule checkouts have no tags, so the version is pinned in a file.
version="$(cat "$here/syncthing.version")"
# Reproducible builds (F-Droid): fixed timestamp from the pinned commit.
export SOURCE_DATE_EPOCH="$(git -C "$st" log -1 --format=%ct)"

# Web GUI assets are generated, not committed upstream.
(cd "$st" && go generate ./lib/api/auto)

cd "$here/bridge"
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
gopath="$(go env GOPATH)"
export PATH="$gopath/bin:$PATH"
gomobile init

mkdir -p "$out"
gomobile bind \
  -target android/arm64,android/amd64 \
  -androidapi 35 \
  -javapkg eu.stedb.mysyncthing \
  -tags noupgrade \
  -trimpath \
  -ldflags "-w -buildid= -checklinkname=0 -X github.com/syncthing/syncthing/lib/build.Version=$version -X github.com/syncthing/syncthing/lib/build.Stamp=$SOURCE_DATE_EPOCH -X github.com/syncthing/syncthing/lib/build.User=mysyncthing -X github.com/syncthing/syncthing/lib/build.Host=mysyncthing -X github.com/syncthing/syncthing/lib/build.Tags=noupgrade" \
  -o "$out/stbridge.aar" \
  .
echo "built $out/stbridge.aar ($version)"
