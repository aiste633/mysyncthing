#!/usr/bin/env bash
# Signs the unsigned APK that CI attached to a draft GitHub release and uploads the result.
# The key never leaves this machine. F-Droid builds the same tag, checks that its APK
# matches this one byte for byte apart from the signature, and then ships this one.
#
# Usage:  KEYSTORE=~/keys/mysyncthing.jks scripts/sign-release.sh v0.1.0
# Needs:  gh (logged in), apksigner from Android build-tools (on PATH, or APKSIGNER=...).
#         KEY_ALIAS defaults to mysyncthing. apksigner asks for the keystore password.
#
# New key (once; keep the file and its password backed up, it can never change):
#   keytool -genkeypair -keystore ~/keys/mysyncthing.jks -alias mysyncthing \
#     -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=MySyncthing"
set -euo pipefail

tag="${1:?usage: KEYSTORE=... $0 vX.Y.Z}"
version="${tag#v}"
: "${KEYSTORE:?set KEYSTORE to the release keystore}"
apksigner="${APKSIGNER:-apksigner}"
unsigned="mysyncthing-$version-unsigned.apk"
signed="mysyncthing-$version.apk"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

gh release download "$tag" --pattern "$unsigned" --dir "$work"
"$apksigner" sign --ks "$KEYSTORE" --ks-key-alias "${KEY_ALIAS:-mysyncthing}" \
  --in "$work/$unsigned" --out "$work/$signed"
"$apksigner" verify --print-certs "$work/$signed" | grep 'SHA-256'
gh release upload "$tag" "$work/$signed"

echo "Uploaded $signed. AllowedAPKSigningKeys in the F-Droid recipe is the SHA-256 above."
echo "Publish when ready: gh release edit $tag --draft=false"
