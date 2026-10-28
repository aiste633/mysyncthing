#!/usr/bin/env bash
# Starts a headless emulator for scripts/e2e/run.py: Pixel 7, Android <api> (35 or 36),
# the plain AOSP image (no Google apps), and waits until it has booted.
# CI (e2e job in build.yml) runs it on a Linux runner with KVM; on a PC with Android Studio
# an existing AVD started with -no-snapshot does the same.
set -euo pipefail

api="${1:?usage: emulator.sh <api level>}"
: "${ANDROID_HOME:?set ANDROID_HOME}"
image="system-images;android-$api;default;x86_64"
avd="e2e-$api"
bin="$ANDROID_HOME/cmdline-tools/latest/bin"

# GitHub's Linux runners have KVM, but only root may open it.
if [ -e /dev/kvm ] && [ ! -w /dev/kvm ]; then
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm.rules >/dev/null
  sudo udevadm control --reload-rules
  sudo udevadm trigger --name-match=kvm
fi

# Only what is missing: on a PC the emulator and platform tools are shared with other AVDs.
missing=()
[ -x "$ANDROID_HOME/emulator/emulator" ] || missing+=(emulator)
[ -x "$ANDROID_HOME/platform-tools/adb" ] || missing+=(platform-tools)
[ -d "$ANDROID_HOME/system-images/android-$api/default/x86_64" ] || missing+=("$image")
if [ ${#missing[@]} -gt 0 ]; then
  yes | "$bin/sdkmanager" --licenses >/dev/null || true
  "$bin/sdkmanager" --install "${missing[@]}" >/dev/null
fi
echo no | "$bin/avdmanager" create avd --force -n "$avd" -k "$image" -d pixel_7 >/dev/null

"$ANDROID_HOME/emulator/emulator" -avd "$avd" -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -memory 4096 >"${E2E_EMULATOR_LOG:-/dev/null}" 2>&1 &

adb="$ANDROID_HOME/platform-tools/adb"
"$adb" wait-for-device
for _ in $(seq 1 180); do
  [ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  sleep 2
done
[ "$("$adb" shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || { echo "emulator did not boot" >&2; exit 1; }
# Screen on and unlocked, so the tests' activity comes to the front.
"$adb" shell input keyevent KEYCODE_WAKEUP
"$adb" shell wm dismiss-keyguard
echo "Android $api emulator up"
