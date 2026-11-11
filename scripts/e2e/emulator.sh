#!/usr/bin/env bash
# Starts a headless emulator for scripts/e2e/run.py: Pixel 7, Android <api> (35 or 36),
# the plain AOSP image (no Google apps), and waits until it has booted. scripts/e2e/pc.py runs it
# for each API; GitHub CI ran it until 11/2026. Needs KVM; on a PC with Android Studio an existing
# AVD started with -no-snapshot does the same.
# E2E_PORT=<even port>: the emulator's console port, so it is emulator-<port> to adb. Set
# ANDROID_SERIAL to that, as pc.py does, so adb never talks to another emulator or a phone.
set -euo pipefail

api="${1:?usage: emulator.sh <api level>}"
: "${ANDROID_HOME:?set ANDROID_HOME}"
image="system-images;android-$api;default;x86_64"
avd="e2e-$api"
bin="$ANDROID_HOME/cmdline-tools/latest/bin"

[ -e /dev/kvm ] || { echo "no /dev/kvm: the x86_64 emulator needs KVM" >&2; exit 1; }
[ -w /dev/kvm ] || { echo "/dev/kvm is not writable for $(id -un): add it to the kvm group" >&2; exit 1; }
port=()
if [ -n "${E2E_PORT:-}" ]; then
  [ "${ANDROID_SERIAL:-}" = "emulator-$E2E_PORT" ] || { echo "E2E_PORT=$E2E_PORT needs ANDROID_SERIAL=emulator-$E2E_PORT" >&2; exit 1; }
  port=(-port "$E2E_PORT")
fi

# Only what is missing: on a PC the emulator and platform tools are shared with other AVDs.
# E2E_SDK_UPDATE=1 installs the current emulator instead of the one already there (CI used it).
missing=()
[ -x "$ANDROID_HOME/emulator/emulator" ] && [ "${E2E_SDK_UPDATE:-0}" != 1 ] || missing+=(emulator)
[ -x "$ANDROID_HOME/platform-tools/adb" ] || missing+=(platform-tools)
[ -d "$ANDROID_HOME/system-images/android-$api/default/x86_64" ] || missing+=("$image")
if [ ${#missing[@]} -gt 0 ]; then
  yes | "$bin/sdkmanager" --licenses >/dev/null || true
  "$bin/sdkmanager" --install "${missing[@]}" >/dev/null
fi
# One AVD directory for both tools: avdmanager and the emulator otherwise look in different
# places when ANDROID_USER_HOME is set, as on GitHub's runners.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$bin/avdmanager" create avd --force -n "$avd" -k "$image" -d pixel_7 >/dev/null
# With -no-window the launcher starts qemu-system-x86_64-headless. Unlike the windowed
# qemu-system-x86_64 it does not link PulseAudio, which GitHub's runner image lacks, so check the
# binary that runs. Its own libraries are bundled under emulator/ and found by its launcher, not by ldd.
emu_dir="$ANDROID_HOME/emulator"
missing_libs=$(ldd "$emu_dir/qemu/linux-x86_64/qemu-system-x86_64-headless" | awk '/not found/ {print $1}' | while read -r lib; do
  [ -n "$(find "$emu_dir" -name "$lib" -print -quit)" ] || echo "$lib"
done)
[ -z "$missing_libs" ] || { echo "the emulator needs system libraries that are missing: $missing_libs" >&2; exit 1; }
# From the package, not `emulator -version`: that runs the windowed binary, which needs PulseAudio.
echo "Android emulator $(sed -n 's/^Pkg.Revision=//p' "$emu_dir/source.properties")"

# E2E_OFFLINE=1 (PC runs): the emulator gets a DNS server where nothing answers, so no name resolves
# for the whole run, pairing and monkey taps included. Syncthing's public servers (global discovery,
# relays, STUN, usage and crash reports) are all reached by name; the tests only use adb's tunnels.
# run.py --offline turns the same options off in the core; this also holds when a monkey switches them on.
dns=()
if [ "${E2E_OFFLINE:-0}" = 1 ]; then
  if ss -Hlun 'sport = :53' | awk '{print $4}' | grep -qE '^(127\.0\.0\.1|0\.0\.0\.0|\*|\[::\]):53$'; then
    echo "E2E_OFFLINE: something listens on port 53 of 127.0.0.1, so DNS would still work" >&2; exit 1
  fi
  dns=(-dns-server 127.0.0.1)
fi

log="${E2E_EMULATOR_LOG:-$(mktemp)}"
# The log's last line says how the emulator ended (status 128 + n: killed by signal n; bash also
# names the signal). Twice on CI it vanished mid-run with nothing in its log (issue #78).
(
  "$ANDROID_HOME/emulator/emulator" -avd "$avd" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -memory 4096 "${port[@]}" "${dns[@]}"
  status=$?
  echo "emulator ended at $(date -u +%H:%M:%S) UTC with status $status"
) >"$log" 2>&1 &
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
if [ "${E2E_OFFLINE:-0}" = 1 ]; then
  # A resolved name prints "PING <name> (<address>)"; an unresolved one "unknown host".
  if "$adb" shell ping -c 1 -W 2 discovery.syncthing.net 2>&1 | grep -q '^PING '; then
    echo "E2E_OFFLINE: the emulator still resolves names" >&2; exit 1
  fi
  echo "emulator offline: no DNS"
fi
echo "Android $api emulator up"
