#!/usr/bin/env bash
# Builds go/build/stbridge.aar (Syncthing core + bridge) with gomobile.
# Needs: Go (toolchain auto-download ok), and ANDROID_HOME or ANDROID_NDK_HOME.
# The NDK must be the one pinned in gradle.properties (mysyncthing.ndkVersion).
# -checklinkname=0: github.com/wlynxg/anet (Android interface listing) links to net internals.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
st="$here/syncthing"
out="$here/build"
export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"

[ -f "$st/go.mod" ] || { echo "go/syncthing submodule missing: git submodule update --init" >&2; exit 1; }

# Reproducible builds: a different NDK means different cgo code in the .so files.
ndk_version="$(sed -n 's/^mysyncthing\.ndkVersion=//p' "$here/../gradle.properties")"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:?set ANDROID_HOME or ANDROID_NDK_HOME}/ndk/$ndk_version}"
found="$(sed -n 's/^Pkg\.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties" 2>/dev/null || true)"
[ "$found" = "$ndk_version" ] || { echo "need NDK $ndk_version, found '${found:-none}' in $ANDROID_NDK_HOME" >&2; exit 1; }

# Shallow submodule checkouts have no tags, so the version is pinned in a file.
version="$(cat "$here/syncthing.version")"
# Reproducible builds (F-Droid): fixed timestamp from the pinned commit.
export SOURCE_DATE_EPOCH="$(git -C "$st" log -1 --format=%ct)"

# Our fixes to the core, until upstream has them.
"$here/patch.sh"

# Web GUI assets are generated, not committed upstream.
(cd "$st" && go generate ./lib/api/auto)

# gomobile writes the absolute paths of go/bridge and go/syncthing into the binary's build
# info, and -trimpath leaves them in. Building through a fixed symlink makes them the same
# on every machine. The lock serialises builds of different checkouts on one machine.
fixed=/tmp/mysyncthing-go
exec 9>"$fixed.lock"
flock 9
ln -sfn "$here" "$fixed"
cd "$fixed/bridge"
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
  -ldflags "-s -w -buildid= -checklinkname=0 -X github.com/syncthing/syncthing/lib/build.Version=$version -X github.com/syncthing/syncthing/lib/build.Stamp=$SOURCE_DATE_EPOCH -X github.com/syncthing/syncthing/lib/build.User=mysyncthing -X github.com/syncthing/syncthing/lib/build.Host=mysyncthing -X github.com/syncthing/syncthing/lib/build.Tags=noupgrade" \
  -o "$out/stbridge.aar" \
  .
echo "built $out/stbridge.aar ($version)"
