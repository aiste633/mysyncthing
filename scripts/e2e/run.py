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
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
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
MONKEY = ["-s", "4242", "--throttle", "150", "--pct-syskeys", "0", "--pct-appswitch", "0", "-v", "2500"]

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

    def connected(self, device: str) -> bool:
        return bool(self.call("GET", "rest/system/connections")["connections"].get(device, {}).get("connected"))

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
    # Read when a network connects, so Wi-Fi reconnects after it.
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


def open_app() -> None:
    sh(f"am start -W -n {APP}/.MainActivity")


def process_death(peer: Peer, phone: str) -> None:
    """Killed in the background, the service comes back by itself (START_STICKY) and syncs again."""
    log("process death")
    open_app()
    if not eventually("phone connected before the kill", 90, lambda: peer.connected(phone)):
        return
    sh("input keyevent KEYCODE_HOME")
    pid = app_pid()
    sh(f"run-as {APP} kill -9 {pid}")
    if not eventually("service restarted after the kill", 120, lambda: app_pid() not in ("", pid)):
        return
    if not eventually("phone reconnected after the kill", 120, lambda: peer.connected(phone)):
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


def monkey(out: Path) -> None:
    log("monkey")
    r = subprocess.run([ADB, "shell", "monkey", "-p", APP, *MONKEY], capture_output=True, text=True, timeout=1200)
    (out / "monkey.txt").write_text(r.stdout + r.stderr)
    if "// CRASH" in r.stdout or "// NOT RESPONDING" in r.stdout or "Monkey finished" not in r.stdout:
        fail("monkey: crash, ANR or aborted (see monkey.txt)")


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
            phase("textDialogsKeepTypedText", peer, a.out)
            process_death(peer, phone)
            storage_revoked(peer, phone)
            monkey(a.out)
    finally:
        peer.stop()
        (a.out / "logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=300))
        shutil.rmtree(home, ignore_errors=True)
        for args in (("reverse", "--remove", f"tcp:{GUI_PORT}"), ("forward", "--remove", f"tcp:{FORWARD_PORT}")):
            adb(*args, check=False)

    logcat = (a.out / "logcat.txt").read_text(errors="replace")
    if f"Process: {APP}, PID" in logcat:
        fail("the app crashed (see logcat.txt)")
    log(f"StrictMode reports in logcat: {logcat.count('StrictMode policy violation')}")
    if failures:
        log(f"{len(failures)} failed:\n  " + "\n  ".join(failures))
        sys.exit(1)
    log("all passed")


if __name__ == "__main__":
    main()
