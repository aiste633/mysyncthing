#!/usr/bin/env bash
# Signs the unsigned APK that CI attached to a draft GitHub release, uploads the signed
# APK and removes the unsigned one, so the release offers only the APK to install.
# The key never leaves this machine. F-Droid builds the same tag, checks that its APK
# matches this one byte for byte apart from the signature, and then ships this one.
# Anyone can do the same check: README, "Verify a release".
#
# Usage:  KEYSTORE=/mnt/mskey/mysyncthing-release.p12 scripts/sign-release.sh v0.1.0
# Needs:  gh (logged in, or GH_TOKEN set); apksigcopier (Debian: apt install apksigcopier);
#         apksigner from Android build-tools: on PATH, APKSIGNER=..., or the newest in
#         $ANDROID_HOME/build-tools. KEY_ALIAS defaults to mysyncthing. apksigner asks for
#         the keystore password.
# Once the F-Droid recipe names the key (AllowedAPKSigningKeys), any other key is refused.
#
# New key (once; keep the file and its password backed up, it can never change). Keep it on
# encrypted, offline storage, never in the repository or a synced folder:
#   keytool -genkeypair -storetype PKCS12 -keystore /mnt/mskey/mysyncthing-release.p12 \
#     -alias mysyncthing -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=MySyncthing"
set -euo pipefail

tag="${1:?usage: KEYSTORE=... $0 vX.Y.Z}"
version="${tag#v}"
: "${KEYSTORE:?set KEYSTORE to the release keystore}"
apksigner="${APKSIGNER:-$(command -v apksigner || ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)}"
[ -x "$apksigner" ] || { echo "apksigner not found: put it on PATH, or set APKSIGNER or ANDROID_HOME" >&2; exit 1; }
command -v apksigcopier >/dev/null || { echo "apksigcopier not found: apt install apksigcopier" >&2; exit 1; }
unsigned="mysyncthing-$version-unsigned.apk"
signed="mysyncthing-$version.apk"
recipe="$(dirname "$0")/../fdroid/eu.stedb.mysyncthing.yml"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

gh release download "$tag" --pattern "$unsigned" --dir "$work"
# Only the signature block may be added, or the signed APK no longer matches anyone's build:
# by default apksigner adds JAR signature files (v1; Android 6 and older need them, our
# minSdk is 35) and moves files to its own alignment (the build already aligns them).
"$apksigner" sign --ks "$KEYSTORE" --ks-key-alias "${KEY_ALIAS:-mysyncthing}" \
  --v1-signing-enabled false --alignment-preserved \
  --in "$work/$unsigned" --out "$work/$signed"
# F-Droid's own check: our signature, copied onto the unsigned build, must verify.
apksigcopier compare --verify-cmd "$apksigner verify" "$work/$signed" --unsigned "$work/$unsigned" \
  || { echo "The signed APK is not the build plus a signature: nothing uploaded." >&2; exit 1; }
digest="$("$apksigner" verify --print-certs "$work/$signed" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[ -n "$digest" ] || { echo "apksigner printed no signer: nothing uploaded" >&2; exit 1; }
echo "Signer certificate SHA-256: $digest"
# The recipe's key, one value or a list; empty while the line is still a comment.
allowed="$(awk '/^AllowedAPKSigningKeys:/ { on = 1; sub(/^[^:]*: */, ""); if ($0 != "") print; next }
  on && /^ *- / { sub(/^ *- */, ""); print; next } { on = 0 }' "$recipe" | tr 'A-F' 'a-f')"
if [ -n "$allowed" ] && ! grep -qx "$digest" <<<"$allowed"; then
  echo "This key is not the one in AllowedAPKSigningKeys ($recipe): nothing uploaded." >&2
  exit 1
fi
gh release upload "$tag" "$work/$signed"
gh release delete-asset "$tag" "$unsigned" --yes

echo "Uploaded $signed and removed $unsigned."
[ -n "$allowed" ] || echo "AllowedAPKSigningKeys in the F-Droid recipe is the SHA-256 above."
echo "Publish when ready: gh release edit $tag --draft=false"
