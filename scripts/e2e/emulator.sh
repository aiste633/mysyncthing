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

[ -e /dev/kvm ] || { echo "no /dev/kvm: the x86_64 emulator needs KVM" >&2; exit 1; }
# GitHub's Linux runners have KVM, but only root may open it.
if [ -e /dev/kvm ] && [ ! -w /dev/kvm ]; then
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm.rules >/dev/null
  sudo udevadm control --reload-rules
  sudo udevadm trigger --name-match=kvm
fi

# Only what is missing: on a PC the emulator and platform tools are shared with other AVDs.
# E2E_SDK_UPDATE=1 (CI) installs the current emulator instead of whatever the runner image has.
missing=()
[ -x "$ANDROID_HOME/emulator/emulator" ] && [ "${E2E_SDK_UPDATE:-0}" != 1 ] || missing+=(emulator)
[ -x "$ANDROID_HOME/platform-tools/adb" ] || missing+=(platform-tools)
[ -d "$ANDROID_HOME/system-images/android-$api/default/x86_64" ] || missing+=("$image")
if [ ${#missing[@]} -gt 0 ]; then
  yes | "$bin/sdkmanager" --licenses >/dev/null || true
  "$bin/sdkmanager" --install "${missing[@]}" >/dev/null
fi
echo no | "$bin/avdmanager" create avd --force -n "$avd" -k "$image" -d pixel_7 >/dev/null
# The emulator links system libraries a minimal Linux may lack (GitHub's runner image has no libpulse).
qemu="$ANDROID_HOME/emulator/qemu/linux-x86_64/qemu-system-x86_64"
if ldd "$qemu" | grep -q 'not found'; then
  echo "the emulator needs system libraries that are missing:" >&2; ldd "$qemu" | grep 'not found' >&2; exit 1
fi
"$ANDROID_HOME/emulator/emulator" -version | head -n 1

log="${E2E_EMULATOR_LOG:-$(mktemp)}"
"$ANDROID_HOME/emulator/emulator" -avd "$avd" -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -memory 4096 >"$log" 2>&1 &
emu=$!

# Waits for the boot, but gives up with the emulator's own output if it exits or takes over 10 minutes.
adb="$ANDROID_HOME/platform-tools/adb"
booted() { [ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
for _ in $(seq 1 300); do
  booted && break
  if ! kill -0 "$emu" 2>/dev/null; then
    echo "emulator exited before booting:" >&2; tail -n 50 "$log" >&2; exit 1
  fi
  sleep 2
done
booted || { echo "emulator did not boot in 10 minutes:" >&2; tail -n 50 "$log" >&2; exit 1; }
# Screen on and unlocked, so the tests' activity comes to the front.
"$adb" shell input keyevent KEYCODE_WAKEUP
"$adb" shell wm dismiss-keyguard
echo "Android $api emulator up"
