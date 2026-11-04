#!/usr/bin/env python3
"""End-to-end tests: the debug app on an emulator against a real Syncthing peer.

The peer is Syncthing built from our patched core (go/syncthing), run here on
loopback only: no discovery, relays or NAT, so it never meets a real device.
The emulator reaches its REST API through `adb reverse`; the peer dials the
phone through `adb forward`. The phases in E2eTest run one at a time, with the
checks only the host can make in between: what the peer has on disk, the app's
process killed, All files access taken away, and a monkey run.

Runs only on an emulator: it wipes the app's data and its test folder.

  scripts/e2e/run.py --peer go/build/e2e-peer \\
      --apk app/build/outputs/apk/debug/app-debug.apk \\
      --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \\
      --out build/e2e
"""
import argparse
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

APP = "eu.stedb.mysyncthing"
RUNNER = f"{APP}.test/androidx.test.runner.AndroidJUnitRunner"
GUI_PORT = 8385         # E2eTest's Peer.PORT
PEER_LISTEN = 22001     # the peer's own sync port, on the host
PHONE_PORT = 22000      # the phone's sync port
FORWARD_PORT = 22100    # host port forwarded to it
FOLDER, LABEL = "e2e", "E2E"
PHONE_DIR = "/sdcard/Syncthing/E2E"
SEEDS = 20
V1, V2 = "version 1\n", "second version\n"  # E2eTest's V1 and V2
# Monkey event mix. Not set: touch, motion, trackball, majornav and anyevent keep their defaults.
# syskeys: BACK (and HOME, volume, CALL), harmless while pinned. appswitch: starts MainActivity
# again. permission 0: runtime permissions stay as installed (-g). Rotation per run.
# --ignore-crashes: a crash of another process does not end the run (a monkey screenshot key once
# crashed System UI's scroll capture over a German, font 2.0 screen, API 36); the app's own crashes
# still fail it (monkey_problems, and the logcat checks). --ignore-native-crashes likewise: the
# monitor ends the run at a new tombstone of ANY process (Monkey.java: mAbort = ... || !mIgnoreNativeCrashes;
# most likely what ended one CI run on API 36 after 2.5 min with no crash line); the app's own native crashes fail it from logcat.
# --ignore-timeouts likewise for ANRs: without it any process's ANR ends the run (one CI run on API 35
# at event 10,325); the app's own ANRs fail it (monkey_problems, logcat_anrs).
# The monkey writes its "// CRASH:" and "// NOT RESPONDING:" lines to stderr: read both streams.
MONKEY_MIX = ["--pct-syskeys", "5", "--pct-appswitch", "2", "--pct-permission", "0", "--pct-flip", "0",
              "--ignore-crashes", "--ignore-timeouts", "--monitor-native-crashes", "--ignore-native-crashes",
              "-v", "-v", "-v"]
# The monkey counts its throttle pauses as events too: 30,000 take about 13 min on the PC emulators.
MONKEY = ["-s", "4242", "--throttle", "100", *MONKEY_MIX, "--pct-rotation", "2", "30000"]
NAVBAR = "com.android.internal.systemui.navbar."

failures: list[str] = []
_tools = Path(os.environ.get("ANDROID_HOME", "/nonexistent")) / "platform-tools/adb"
ADB = str(_tools) if _tools.exists() else "adb"


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def fail(msg: str) -> None:
    log(f"FAIL: {msg}")
    failures.append(msg)


def adb(*args: str, check: bool = True, timeout: int = 120) -> str:
    r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stdout}{r.stderr}")
    return r.stdout


def sh(cmd: str, check: bool = True) -> str:
    return adb("shell", cmd, check=check).strip()


def eventually(what: str, timeout: float, done) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            if done():
                return True
        except (OSError, RuntimeError, ValueError):
            pass
        time.sleep(1)
    fail(f"timed out after {timeout:.0f} s: {what}")
    return False


class Peer:
    def __init__(self, binary: Path, home: Path, out: Path):
        self.binary, self.dir, self.out = binary, home, out
        self.key = secrets.token_hex(16)
        self.folder = home / "folder"
        self.proc: subprocess.Popen | None = None
        # Its own HOME, so nothing lands in the user's home directory.
        self.env = {**os.environ, "HOME": str(home), "STNODEFAULTFOLDER": "1", "STNOUPGRADE": "1"}

    def call(self, method: str, path: str, body=None):
        req = urllib.request.Request(
            f"http://127.0.0.1:{GUI_PORT}/{path}", method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"X-API-Key": self.key, "Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=10) as r:
            text = r.read().decode()
        return json.loads(text) if text.strip() else None

    def start(self) -> None:
        subprocess.run([self.binary, "generate", "--home", self.dir, "--no-port-probing"], env=self.env, check=True, capture_output=True)
        self.id = subprocess.run([self.binary, "device-id", "--home", self.dir], env=self.env, check=True, capture_output=True, text=True).stdout.strip()
        self.proc = subprocess.Popen(
            [self.binary, "serve", "--home", self.dir, "--no-browser", "--no-restart",
             f"--gui-address=http://127.0.0.1:{GUI_PORT}", f"--gui-apikey={self.key}", f"--log-file={self.out / 'peer.log'}"],
            env=self.env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        if not eventually("peer REST API up", 60, lambda: self.call("GET", "rest/system/ping")):
            raise RuntimeError("peer did not start")
        self.call("PATCH", "rest/config/options", {
            "listenAddresses": [f"tcp://127.0.0.1:{PEER_LISTEN}"],
            "globalAnnounceEnabled": False, "localAnnounceEnabled": False,
            "relaysEnabled": False, "natEnabled": False, "urAccepted": -1, "crashReportingEnabled": False,
        })
        self.folder.mkdir()
        for i in range(SEEDS):
            (self.folder / f"seed-{i:02d}.txt").write_text(f"seed file {i}\n" * (i + 1))
        (self.folder / "versioned.txt").write_text(V1)
        self.call("POST", "rest/config/folders", {"id": FOLDER, "label": LABEL, "path": str(self.folder), "fsWatcherEnabled": False, "rescanIntervalS": 3600})
        eventually("peer scanned its folder", 60, lambda: self.call("GET", f"rest/db/status?folder={FOLDER}")["globalFiles"] == SEEDS + 1)
        log(f"peer up with {SEEDS + 1} files")

    def scan(self) -> None:
        self.call("POST", f"rest/db/scan?folder={FOLDER}")

    def connected(self, device: str, since: float = 0) -> bool:
        """Connected now; with [since] (epoch seconds), by a connection made at or after it, not one still
        open from before (the peer notices a killed app's connection closing only after a moment)."""
        c = self.call("GET", "rest/system/connections")["connections"].get(device, {})
        return bool(c.get("connected")) and (not since or datetime.fromisoformat(c["startedAt"]).timestamp() >= int(since))

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            try:
                self.call("POST", "rest/system/shutdown")
                self.proc.wait(timeout=20)
            except (OSError, subprocess.TimeoutExpired):
                self.proc.kill()


def require_emulator() -> None:
    if sh("getprop ro.kernel.qemu", check=False) != "1" and sh("getprop ro.boot.qemu", check=False) != "1":
        sys.exit("not an emulator: refusing to wipe app data on a real phone")


def prepare(apk: Path, test_apk: Path) -> None:
    # The phases need the screen on and unlocked (an earlier monkey.py run lets it sleep).
    sh("svc power stayon true")
    Device.wake()
    for pkg in (APP, f"{APP}.test"):
        adb("uninstall", pkg, check=False)
    sh(f"rm -rf {PHONE_DIR}", check=False)
    adb("install", "-r", "-g", str(apk))
    adb("install", "-r", "-g", str(test_apk))
    sh(f"appops set --uid {APP} MANAGE_EXTERNAL_STORAGE allow")
    sh(f"dumpsys deviceidle whitelist +{APP}")
    for s in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {s} 0")
    # Wi-Fi passes Android's internet check without reaching Google's server, which the emulator on a
    # CI runner sometimes cannot: mobile data then stays the default network (E2eTest.onWifi).
    # Read when a network connects, so Wi-Fi reconnects after it (E2eTest.onWifi joins it again).
    sh("settings put global captive_portal_mode 0")
    sh("svc wifi disable")
    sh("svc wifi enable")
    adb("reverse", f"tcp:{GUI_PORT}", f"tcp:{GUI_PORT}")
    adb("forward", f"tcp:{FORWARD_PORT}", f"tcp:{PHONE_PORT}")


def phase(name: str, peer: Peer, out: Path, **extra: str) -> bool:
    log(f"phase {name}")
    r = subprocess.run(
        [ADB, "shell", "am", "instrument", "-w", "-r",
         "-e", "class", f"{APP}.e2e.E2eTest#{name}",
         "-e", "peerId", peer.id, "-e", "peerKey", peer.key, "-e", "phoneAddress", f"tcp://127.0.0.1:{FORWARD_PORT}",
         *[a for k, v in extra.items() for a in ("-e", k, v)],
         RUNNER],
        capture_output=True, text=True, timeout=900,
    )
    (out / f"phase-{name}.txt").write_text(r.stdout + r.stderr)
    if "OK (1 test)" not in r.stdout:
        # The assertion's message in the job log too, as the artifact is not always at hand.
        why = next((line.split("stack=", 1)[1] for line in r.stdout.splitlines() if "stack=" in line), "no test result")
        fail(f"phase {name}: {why} (see phase-{name}.txt)")
        return False
    return True


def phone_id(peer: Peer) -> str:
    ids = [d["deviceID"] for d in peer.call("GET", "rest/config/devices") if d["deviceID"] != peer.id]
    if len(ids) != 1:
        raise RuntimeError(f"peer knows {len(ids)} phones")
    return ids[0]


def phone_file(name: str) -> str | None:
    r = subprocess.run([ADB, "shell", "cat", f"{PHONE_DIR}/{name}"], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def app_pid() -> str:
    return sh(f"pidof {APP}", check=False)


LAUNCHER_START = f"am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n {APP}/.MainActivity"


def go_home() -> None:
    """HOME, and back only once a window other than the app's has focus: `input` returns before the
    system handles the key, and a start sent meanwhile is undone by it (CI, API 35: the launcher
    start's `am start -W` then waited for the app to be drawn, which never came)."""
    sh("input keyevent KEYCODE_HOME")
    eventually("another window focused after HOME", 10, lambda: (f := focus_line()).startswith("mCurrentFocus=Window{") and APP not in f)


def open_app() -> None:
    """As the launcher does (new task, reset if needed)."""
    sh(LAUNCHER_START)


def main_activities() -> int:
    return sum(1 for line in sh("dumpsys activity activities").splitlines() if "Hist" in line and f"{APP}/.MainActivity" in line)


def launcher_starts() -> None:
    """Opened first by an intent that is not the launcher's, then three times from the launcher: one
    MainActivity stays, not four (each copy kept its own screens; the monkey stacked 121, then an ANR)."""
    log("launcher starts")
    sh(f"am start -W -f 0x10008000 -n {APP}/.MainActivity")  # new task, cleared: a root that is not the launcher's
    for _ in range(3):
        go_home()
        sh(LAUNCHER_START)
    time.sleep(3)
    if (n := main_activities()) != 1:
        fail(f"launcher starts: {n} MainActivity copies in the task, expected 1")


def process_death(peer: Peer, phone: str) -> None:
    """Killed in the background, the service comes back by itself (START_STICKY) and syncs again."""
    log("process death")
    # The phase before ended its process: only a connection from this start shows the service runs (sticky).
    opened = time.time()
    open_app()
    if not eventually("phone connected before the kill", 90, lambda: peer.connected(phone, since=opened)):
        return
    go_home()
    pid = app_pid()
    killed = time.time()
    sh(f"run-as {APP} kill -9 {pid}")
    if not eventually("service restarted after the kill", 120, lambda: app_pid() not in ("", pid)):
        return
    if not eventually("phone reconnected after the kill", 120, lambda: peer.connected(phone, since=killed)):
        return
    (peer.folder / "after-kill.txt").write_text("written on the peer after the kill\n")
    peer.scan()
    eventually("after-kill.txt on the phone", 120, lambda: phone_file("after-kill.txt") is not None)


def storage_revoked(peer: Peer, phone: str) -> None:
    """Without All files access the core must not start: a core that cannot see the files would delete them everywhere."""
    log("All files access revoked")
    before = peer.call("GET", f"rest/db/status?folder={FOLDER}")
    files = sorted(p.name for p in peer.folder.iterdir())
    sh(f"appops set --uid {APP} MANAGE_EXTERNAL_STORAGE deny")
    time.sleep(45)
    after = peer.call("GET", f"rest/db/status?folder={FOLDER}")
    if (after["globalDeleted"], after["globalFiles"]) != (before["globalDeleted"], before["globalFiles"]):
        fail(f"peer's folder changed without All files access: deleted {before['globalDeleted']} -> {after['globalDeleted']}, "
             f"files {before['globalFiles']} -> {after['globalFiles']}")
    if sorted(p.name for p in peer.folder.iterdir()) != files:
        fail("files on the peer's disk changed without All files access")
    if peer.connected(phone):
        fail("the phone's core runs without All files access")
    sh(f"appops set --uid {APP} MANAGE_EXTERNAL_STORAGE allow")
    open_app()
    eventually("phone reconnected with All files access back", 120, lambda: peer.connected(phone))


class Device:
    """System settings the monkey runs change: read first, put back by restore()."""

    def __init__(self) -> None:
        self.saved = {
            "font_scale": sh("settings get system font_scale"),
            "accelerometer_rotation": sh("settings get system accelerometer_rotation"),
            "user_rotation": sh("settings get system user_rotation"),
            "screen_off_timeout": sh("settings get system screen_off_timeout"),
            "always_finish_activities": sh("settings get global always_finish_activities"),
        }
        self.night = sh("cmd uimode night").rsplit(":", 1)[-1].strip()  # "Night mode: no"
        # navigation_mode 0 is three-button already (the AOSP images): no overlay is touched then.
        self.three_button = sh("settings get secure navigation_mode") == "0"
        self.nav = next((m.group(1) for m in re.finditer(r"\[x\] " + re.escape(NAVBAR) + r"(\w+)", sh("cmd overlay list"))), None)

    def keep_awake(self) -> None:
        sh("svc power stayon true")
        sh("settings put system screen_off_timeout 1800000")
        sh("locksettings set-disabled true", check=False)
        self.wake()
        if not self.three_button:
            sh(f"cmd overlay enable-exclusive --category {NAVBAR}threebutton")

    @staticmethod
    def wake() -> None:
        sh("input keyevent KEYCODE_WAKEUP")
        sh("wm dismiss-keyguard", check=False)

    def apply(self, setup: dict) -> None:
        sh(f"settings put system font_scale {setup.get('font', '1.0')}")
        sh(f"cmd uimode night {setup.get('night', 'no')}")
        sh(f"settings put global always_finish_activities {1 if setup.get('dont_keep') else 0}")
        sh("settings put system accelerometer_rotation 0")
        sh("settings put system user_rotation 0")
        if "locale" in setup:
            sh(f"cmd locale set-app-locales {APP} --locales {setup['locale']}")

    def restore(self) -> None:
        sh("am task lock stop", check=False)
        sh(f"cmd uimode night {self.night}", check=False)
        for key, value in self.saved.items():
            ns = "global" if key == "always_finish_activities" else "system"
            if value in ("null", ""):
                sh(f"settings delete {ns} {key}", check=False)
            else:
                sh(f"settings put {ns} {key} {value}", check=False)
        if not self.three_button:
            if self.nav:
                sh(f"cmd overlay enable-exclusive --category {NAVBAR}{self.nav}", check=False)
            else:
                sh(f"cmd overlay disable {NAVBAR}threebutton", check=False)
        sh("svc power stayon false", check=False)


def task_id() -> str | None:
    m = re.search(r"Task\{\w+ #(\d+)[^\n]*A=\d+:" + re.escape(APP) + r"\b", sh("dumpsys activity activities"))
    return m.group(1) if m else None


def awake() -> bool:
    return "mWakefulness=Awake" in sh("dumpsys power | grep -m1 mWakefulness=", check=False)


def pinned() -> bool:
    return "=PINNED" in sh("dumpsys activity activities | grep -m1 mLockTaskModeState"
                          " || dumpsys activity | grep -m1 mLockTaskModeState", check=False)


def pin() -> bool:
    open_app()
    tid = task_id()
    if tid is None:
        return False
    sh(f"am task lock {tid}", check=False)
    return eventually("app pinned", 15, pinned)


# Crashes in the app's process that are Android's own, left as they are by the user's decision: a
# monkey run reports them, but they do not fail it. Matched on the crash's stack in the monkey log,
# and only when no frame of the app's own code is in it.
KNOWN_PLATFORM_CRASHES = {
    # Android 15: a screenshot's "Capture more" still running when the screen goes away (rotation);
    # the framework then calls the scroll capture callback it already cleared. User 2026-11-03: leave it.
    "android.view.ScrollCaptureConnection": "Android 15 scroll capture race",
}


def known_platform_crash(trace: str) -> str | None:
    """Why [trace] (a crash's stack) is a known platform crash, or None: also None when any frame of
    the app's own code is in it."""
    if f"at {APP}." in trace:
        return None
    return next((why for sig, why in KNOWN_PLATFORM_CRASHES.items() if sig in trace), None)


def logcat_app_crashes(logcat: str) -> tuple[list[str], list[str]]:
    """The app's Java crashes in a logcat (AndroidRuntime's FATAL EXCEPTION blocks): its own, by their
    first line, and the known platform ones, by why they are known."""
    blocks: list[list[str]] = []
    for line in logcat.splitlines():
        if "AndroidRuntime" not in line:
            continue
        if "FATAL EXCEPTION" in line:
            blocks.append([])
        elif blocks:
            blocks[-1].append(line)
    ours: list[str] = []
    known: list[str] = []
    for b in blocks:
        trace = "\n".join(b)
        if f"Process: {APP}, PID" not in trace:
            continue
        if why := known_platform_crash(trace):
            known.append(why)
        else:
            ours.append(b[1].split(": ", 1)[-1] if len(b) > 1 else "?")
    return ours, known


def native_crashes(logcat: str) -> tuple[bool, list[str]]:
    """Native crashes in a logcat (the tombstone's ">>> process <<<" line): whether the app's process
    had one (a Go panic in the core ends that way too), and the other processes that had one."""
    names = re.findall(r">>> (\S+) <<<", logcat)
    return APP in [n.split(":")[0] for n in names], sorted({n for n in names if n.split(":")[0] != APP})


def logcat_anrs(logcat: str) -> tuple[list[str], list[str]]:
    """ANRs in a logcat (ActivityManager's "ANR in" with the "Reason:" line after it; WindowManager
    and the monkey repeat it in their own words): the app's, and other processes', each as "process: reason"."""
    ours: list[str] = []
    others: list[str] = []
    lines = logcat.splitlines()
    for i, line in enumerate(lines):
        if not (m := re.search(r"ActivityManager: ANR in (\S+)", line)):
            continue
        reason = next((l.split("Reason: ", 1)[1].strip() for l in lines[i + 1:i + 6] if "Reason: " in l), "?")
        (ours if m.group(1).split(":")[0] == APP else others).append(f"{m.group(1)}: {reason}")
    return ours, others


def monkey_problems(text: str) -> tuple[list[str], list[str]]:
    """Crashes and ANRs in a monkey log: the app's own, and the rest: other processes' and the app
    process's known platform crashes (KNOWN_PLATFORM_CRASHES), which are Android's, not ours."""
    ours: list[str] = []
    others: list[str] = []
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if not (m := re.match(r"// (CRASH|NOT RESPONDING): (\S+)", line)):
            continue
        what = f"{m.group(1)} {m.group(2)}"
        if m.group(2).split(":")[0] != APP:
            others.append(what)
            continue
        block = []
        for later in lines[i + 1:]:
            if not later.startswith("//") or later.startswith(("// CRASH:", "// NOT RESPONDING:")):
                break
            block.append(later)
        known = known_platform_crash("\n".join(block))
        if known and m.group(1) == "CRASH":
            others.append(f"{what} (known: {known})")
        else:
            ours.append(what)
    # Any process's new tombstone; whose it was is read from logcat (native_crashes).
    others += ["new native crash (whose: see the logcat check)"] * text.count("** New native crash detected.")
    return ours, others


def focus_line() -> str:
    """The window manager's focused window (API 35 prints it under "displays", not "windows")."""
    for cmd in ("dumpsys window displays", "dumpsys window"):
        if line := next((l.strip() for l in sh(cmd, check=False).splitlines() if "mCurrentFocus=" in l), None):
            return line
    return "no mCurrentFocus line"


def input_focus(inputs: str | None = None) -> str:
    """The input dispatcher's focused window ("FocusedWindows: <none>" if none). It can differ from
    mCurrentFocus: in CI's API 35 freeze the window manager named the app's dialog while input never got
    it ("pending focus request"), so every key waited 5 s and then was dropped."""
    lines = (inputs if inputs is not None else sh("dumpsys input", check=False)).splitlines()
    for i, l in enumerate(lines):
        if "FocusedWindows:" in l:
            return l.strip() if "<none>" in l or i + 1 == len(lines) else lines[i + 1].strip()
    return "no FocusedWindows line"


def pull_anr_traces(dst: Path) -> None:
    """/data/anr (the system's ANR dumps and SIGQUIT stacks) into [dst]; needs adb root (emulator images)."""
    adb("root", check=False)
    adb("wait-for-device", check=False, timeout=60)
    dst.mkdir(parents=True, exist_ok=True)
    subprocess.run([ADB, "pull", "/data/anr/.", str(dst)], capture_output=True, text=True, timeout=120)
    adb("unroot", check=False)
    adb("wait-for-device", check=False, timeout=60)


class PinGuard(threading.Thread):
    """Every 2 s: wakes the screen and pins the app again if either was lost, and records when.
    On API 36 the launcher draws the three-button bar, and its Overview button ignores screen
    pinning: a monkey tap there sends the app's task to the back and later events to other apps
    (seed 4242, PC 2026-11-03). That is the platform's, not the app's; the guard keeps the run in
    the app."""

    def __init__(self, out: Path | None = None) -> None:
        super().__init__(daemon=True)
        self.stop = threading.Event()
        self.pin_losses: list[str] = []
        self.sleeps: list[str] = []
        # With [out]: the window manager's state whenever no window has had focus for about 4 s
        # (an input ANR comes at 5 s): "Application does not have a focused window" on API 35.
        self.out = out
        self.unfocused = 0
        self.focus_losses: list[str] = []

    def run(self) -> None:
        while not self.stop.wait(2):
            try:
                if not awake():
                    self.sleeps.append(time.strftime("%H:%M:%S"))
                    Device.wake()
                if not pinned():
                    self.pin_losses.append(time.strftime("%H:%M:%S"))
                    pin()
                if self.out is not None:
                    self.watch_focus()
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as e:
                log(f"pin guard: {e}")

    def watch_focus(self) -> None:
        """No focused window for the window manager or for input (see input_focus)."""
        line = focus_line()
        inputs = sh("dumpsys input", check=False)
        lost = line.endswith("=null") or "<none>" in input_focus(inputs)
        self.unfocused = self.unfocused + 1 if lost else 0
        if self.unfocused != 2 or len(self.focus_losses) >= 3:
            return
        self.focus_losses.append(time.strftime("%H:%M:%S"))
        pid = app_pid()
        sh(f"run-as {APP} kill -3 {pid}", check=False)  # the main thread's stack, into /data/anr
        # "activity top" holds the app's view hierarchy per window: which dialog or popup it was.
        (self.out / f"focus-loss-{len(self.focus_losses)}.txt").write_text(
            f"{time.strftime('%H:%M:%S')} pid {pid}\n{line}\n\n" + inputs + "\n\n"
            + "\n\n".join(sh(c, check=False) for c in ("dumpsys window displays", "dumpsys window windows", "dumpsys activity top")))

    def finish(self) -> None:
        self.stop.set()
        self.join(timeout=60)


def monkey(out: Path) -> None:
    """Random events with a fixed seed. The app is pinned (am task lock, three-button navigation), so
    BACK can be sent and no tap reaches the notification shade or quick settings."""
    log("monkey")
    dev = Device()
    try:
        dev.keep_awake()
        dev.apply({})
        if not pin():
            fail("monkey: the app could not be pinned")
            return
        guard = PinGuard(out)
        guard.start()
        try:
            r = subprocess.run([ADB, "shell", "monkey", "-p", APP, *MONKEY], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, timeout=3600)
        finally:
            guard.finish()
        (out / "monkey.txt").write_text(r.stdout)
        log(f"monkey: pinned again {len(guard.pin_losses)} times {guard.pin_losses}, woken {len(guard.sleeps)} times")
        if guard.focus_losses:
            log(f"monkey: no focused window for 4 s at {guard.focus_losses} (see focus-loss-*.txt)")
        ours, others = monkey_problems(r.stdout)
        if others:
            log(f"monkey: other processes, not the app: {others}")
        if ours or "Monkey finished" not in r.stdout:
            # The end of its log in the job log too: CI's artifact is not always at hand.
            tail = [line for line in r.stdout.splitlines() if not line.startswith(":Sending")][-40:]
            log(f"monkey exited {r.returncode}; the end of its log:\n  " + "\n  ".join(tail))
            fail(f"monkey: {ours or 'aborted'} (see monkey.txt)")
        if not pinned() or not awake():
            fail("monkey: at the end the app was no longer pinned or the screen was off, so events may have gone elsewhere")
    finally:
        dev.restore()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--peer", type=Path, required=True)
    ap.add_argument("--apk", type=Path, required=True)
    ap.add_argument("--test-apk", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=Path("build/e2e"))
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)

    require_emulator()
    prepare(a.apk, a.test_apk)
    adb("logcat", "-c")
    home = Path(tempfile.mkdtemp(prefix="mysyncthing-e2e-"))
    peer = Peer(a.peer.resolve(), home, a.out.resolve())
    try:
        peer.start()
        if phase("pairAndSync", peer, a.out):
            phone = phone_id(peer)
            (peer.folder / "versioned.txt").write_text(V2)
            peer.scan()
            if phase("restoreVersion", peer, a.out):
                eventually("old versioned.txt on the peer's disk", 60, lambda: (peer.folder / "versioned.txt").read_text() == V1)
            phase("runConditions", peer, a.out)
            phase("devicePause", peer, a.out)
            phase("devicesTab", peer, a.out)
            phase("deviceWindows", peer, a.out)
            phase("textDialogsKeepTypedText", peer, a.out)
            phase("stopAndStart", peer, a.out)
            process_death(peer, phone)
            storage_revoked(peer, phone)
            launcher_starts()
            monkey(a.out)
    finally:
        peer.stop()
        (a.out / "logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=300))
        shutil.rmtree(home, ignore_errors=True)
        for args in (("reverse", "--remove", f"tcp:{GUI_PORT}"), ("forward", "--remove", f"tcp:{FORWARD_PORT}")):
            adb(*args, check=False)

    logcat = (a.out / "logcat.txt").read_text(errors="replace")
    crashes, known = logcat_app_crashes(logcat)
    if crashes:
        fail(f"the app crashed: {crashes} (see logcat.txt)")
    if known:
        log(f"known platform crashes in the app's process (not failing): {known}")
    ours, others = logcat_anrs(logcat)
    if ours:
        fail(f"the app did not respond (ANR): {ours} (see logcat.txt, anr/)")
        pull_anr_traces(a.out / "anr")
    if others:
        log(f"ANRs of other processes (not failing): {others}")
    ours, others = native_crashes(logcat)
    if ours:
        fail("the app's process crashed natively (see logcat.txt: '>>> eu.stedb.mysyncthing <<<')")
    if others:
        log(f"native crashes of other processes (not failing): {others}")
    log(f"StrictMode reports in logcat: {logcat.count('StrictMode policy violation')}")
    if failures:
        log(f"{len(failures)} failed:\n  " + "\n  ".join(failures))
        sys.exit(1)
    log("all passed")


if __name__ == "__main__":
    main()
