#!/usr/bin/env python3
"""Deep monkey test: Android's random UI events on the debug app, one run per setup and seed.

Each run starts from the same state, so a seed can be replayed: a fresh install paired with
the e2e peer (run.py's Peer and E2eTest#pairAndSync), its folder synced, a second folder
offered and left pending. The peer keeps running and keeps changing files during the run,
and drops the phone now and then, so the screens see live progress and reconnects.

The app is pinned (`am task lock`, three-button navigation so no gesture unpins it), so the
monkey's taps cannot open the notification shade or quick settings and flip Wi-Fi or flight
mode; BACK is then safe to send. A host thread pins it again if the pin is lost (and logs it).

Setups:
  plain     English, light, normal font; a little rotation
  de-big    German, dark, font 2.0 (the largest); a little rotation; the peer away for 35 min
            from minute 3, so the Devices tab shows the grey wait and then the red state
  recreate  "Don't keep activities" on, much rotation

A run ends at the first crash or ANR (the monkey stops there, so the seed and event count
reproduce it). Collected per run: the monkey's log of every event, the whole logcat, the
system's crash records (dropbox), and a summary: crashes, ANRs, native crashes, Go panics,
StrictMode reports grouped by kind and first app frame, pin losses.

Runs only on an emulator: it wipes the app's data and changes system settings, which it
restores at the end (the app is left uninstalled).

  scripts/e2e/monkey.py --peer go/build/e2e-peer \\
      --apk app/build/outputs/apk/debug/app-debug.apk \\
      --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \\
      --setups plain,de-big,recreate --seeds 1101,1102 --events 20000 --out build/monkey
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run  # noqa: E402  (scripts/e2e/run.py: adb, Peer, prepare, phase)
from run import ADB, APP, MONKEY_MIX, Device, adb, awake, log, pin, pinned, sh  # noqa: E402

SETUPS = {
    "plain": {"rotation": "2"},
    "de-big": {"rotation": "2", "locale": "de-DE", "font": "2.0", "night": "yes", "away_min": 35},
    "recreate": {"rotation": "10", "dont_keep": True},
}
DROPBOX_TAGS = ["data_app_crash", "data_app_anr", "data_app_native_crash", "data_app_wtf", "SYSTEM_TOMBSTONE"]


class Churn(threading.Thread):
    """During the run: the peer changes a file every 20 s and drops the phone for 30 s every 5 min.
    With [away_min], the peer stays away that long from minute 3 on, so the Devices tab goes from
    grey "Waiting for devices" to red after 30 min. The screen is woken and the app pinned again
    whenever either is lost (both logged)."""

    def __init__(self, peer: "run.Peer", phone: str, out: Path, away_min: int = 0) -> None:
        super().__init__(daemon=True)
        self.peer, self.phone, self.out = peer, phone, out
        self.away = range(9, 9 + away_min * 3) if away_min else range(0)  # in 20 s ticks
        self.stop = threading.Event()
        self.pin_losses: list[str] = []
        self.sleeps: list[str] = []

    def run(self) -> None:
        tick = 0
        while not self.stop.wait(20):
            tick += 1
            try:
                name = f"churn-{tick % 5}.bin"
                # Every fifth change is 2 MB, long enough to show transfer progress.
                (self.peer.folder / name).write_bytes(bytes([tick % 256]) * (2_000_000 if tick % 5 == 0 else 2_000))
                self.peer.scan()
                if tick in self.away or tick % 15 == 0:
                    self.peer.call("PATCH", f"rest/config/devices/{self.phone}", {"paused": True})
                elif tick % 15 == 2:
                    self.peer.call("PATCH", f"rest/config/devices/{self.phone}", {"paused": False})
                if not awake():
                    self.sleeps.append(time.strftime("%H:%M:%S"))
                    run.Device.wake()
                if not pinned():
                    self.pin_losses.append(time.strftime("%H:%M:%S"))
                    pin()
            except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as e:
                log(f"churn: {e}")

    def finish(self) -> None:
        self.stop.set()
        self.join(timeout=60)
        try:
            self.peer.call("PATCH", f"rest/config/devices/{self.phone}", {"paused": False})
        except (OSError, ValueError):
            pass


def offer_folder(peer: "run.Peer", phone: str) -> None:
    """A second folder offered to the phone and left pending: the offer card and its dialogs."""
    d = peer.dir / "offer"
    d.mkdir(exist_ok=True)
    (d / "offered.txt").write_text("offered, not yet accepted\n")
    peer.call("POST", "rest/config/folders", {
        "id": "offer", "label": "Offered", "path": str(d), "fsWatcherEnabled": False,
        "devices": [{"deviceID": peer.id}, {"deviceID": phone}],
    })


def device_time() -> str:
    return sh("date '+%Y-%m-%d %H:%M:%S'")


def dropbox_since(start: str) -> list[str]:
    """The system's crash and ANR records for the app, made at or after [start] (device time)."""
    text = adb("shell", "dumpsys", "dropbox", "--print", *DROPBOX_TAGS, check=False, timeout=300)
    entries = [e.strip() for e in re.split(r"^=+$", text, flags=re.M) if e.strip()]
    return [e for e in entries if e[:19] >= start and APP in e]


def strictmode(logcat: str) -> Counter:
    """StrictMode reports by kind and the first frame in the app's own code."""
    lines = logcat.splitlines()
    found: Counter = Counter()
    for i, line in enumerate(lines):
        if "StrictMode policy violation" not in line:
            continue
        kind = m.group(1) if (m := re.search(r"android\.os\.strictmode\.(\w+)", line)) else "?"
        frame = "(no app frame)"
        for later in lines[i + 1:i + 60]:
            if "StrictMode policy violation" in later:
                break
            if m := re.search(r"\bat (eu\.stedb\.mysyncthing\.\S+)", later):
                frame = m.group(1)
                break
        found[f"{kind} at {frame}"] += 1
    return found


def one_run(name: str, setup: dict, seed: int, a, dev: Device) -> dict:
    out = a.out / f"{name}-{seed}"
    out.mkdir(parents=True, exist_ok=True)
    result = {"setup": name, "seed": seed, "events": a.events}
    run.failures.clear()
    run.prepare(a.apk, a.test_apk)
    dev.keep_awake()
    dev.apply({})  # pairing in the plain setup; the run's own setup once paired
    home = Path(tempfile.mkdtemp(prefix="mysyncthing-monkey-"))
    peer = run.Peer(a.peer.resolve(), home, out.resolve())
    adb("logcat", "-c", check=False)
    logcat_file = (out / "logcat.txt").open("w")
    logcat = subprocess.Popen([ADB, "logcat", "-v", "threadtime"], stdout=logcat_file, stderr=subprocess.STDOUT)
    churn = None
    try:
        peer.start()
        if not run.phase("pairAndSync", peer, out):
            result["result"] = f"setup failed: {'; '.join(run.failures)}"
            return result
        phone = run.phone_id(peer)
        offer_folder(peer, phone)
        dev.apply(setup)
        if not pin():
            result["result"] = "setup failed: could not pin the app"
            return result
        churn = Churn(peer, phone, out, setup.get("away_min", 0))
        churn.start()
        start = device_time()
        began = time.monotonic()
        cmd = [ADB, "shell", "monkey", "-p", APP, "-s", str(seed), "--throttle", str(a.throttle),
               "--pct-rotation", setup["rotation"], *MONKEY_MIX, str(a.events)]
        log(f"{name} seed {seed}: {' '.join(cmd[2:])}")
        with (out / "monkey.txt").open("w") as f:
            try:
                r = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, timeout=a.events * (a.throttle + 400) // 1000 + 600)
                timed_out = False
            except subprocess.TimeoutExpired:
                timed_out = True
        result["minutes"] = round((time.monotonic() - began) / 60, 1)
        monkey = (out / "monkey.txt").read_text(errors="replace")
        done = re.findall(r"Events injected: (\d+)", monkey)
        result["injected"] = int(done[-1]) if done else None
        result["crash"] = "// CRASH" in monkey
        result["anr"] = "// NOT RESPONDING" in monkey
        result["monkey_finished"] = "// Monkey finished" in monkey and not timed_out
        result["dropbox"] = dropbox_since(start)
        result["pin_losses"] = churn.pin_losses
        result["sleeps"] = churn.sleeps
    finally:
        if churn:
            churn.finish()
        peer.stop()
        sh("am task lock stop", check=False)
        time.sleep(3)
        logcat.terminate()
        logcat_file.close()
        shutil.rmtree(home, ignore_errors=True)
        for args in (("reverse", "--remove", f"tcp:{run.GUI_PORT}"), ("forward", "--remove", f"tcp:{run.FORWARD_PORT}")):
            adb(*args, check=False)

    text = (out / "logcat.txt").read_text(errors="replace")
    result["java_crash"] = f"Process: {APP}, PID" in text
    result["native_crash"] = f">>> {APP} <<<" in text
    result["go_panic"] = sorted({l.strip() for l in text.splitlines() if re.search(r"\b(panic: |fatal error: )", l)})[:20]
    result["strictmode"] = dict(strictmode(text).most_common())
    (out / "dropbox.txt").write_text("\n\n".join(result["dropbox"]))
    bad = [k for k in ("crash", "anr", "java_crash", "native_crash") if result[k]]
    if result["go_panic"]:
        bad.append("go_panic")
    if result["dropbox"]:
        bad.append("dropbox")
    if not result["monkey_finished"] and not bad:
        bad.append("monkey did not finish")
    result["result"] = "FAIL: " + ", ".join(bad) if bad else "ok"
    (out / "summary.json").write_text(json.dumps(result, indent=2))
    log(f"{name} seed {seed}: {result['result']} ({result['injected']} events, {result['minutes']} min, "
        f"{sum(result['strictmode'].values())} StrictMode, {len(result['pin_losses'])} pin losses)")
    return result


def report(results: list[dict], a) -> str:
    api = sh("getprop ro.build.version.sdk")
    rows = [f"# Monkey run, API {api}, {time.strftime('%Y-%m-%d %H:%M')}", "",
            f"{a.events} events per run, throttle {a.throttle} ms.", "",
            "| Setup | Seed | Result | Events | Minutes | StrictMode | Pin losses | Screen off |", "|---|---|---|---|---|---|---|---|"]
    for r in results:
        rows.append(f"| {r['setup']} | {r['seed']} | {r['result']} | {r.get('injected')} | {r.get('minutes')} | "
                    f"{sum(r.get('strictmode', {}).values())} | {len(r.get('pin_losses', []))} | {len(r.get('sleeps', []))} |")
    sm: Counter = Counter()
    for r in results:
        sm.update(r.get("strictmode", {}))
    if sm:
        rows += ["", "## StrictMode, all runs", ""] + [f"- {n} x {k}" for k, n in sm.most_common()]
    panics = sorted({p for r in results for p in r.get("go_panic", [])})
    if panics:
        rows += ["", "## Go panic lines", ""] + [f"- `{p}`" for p in panics]
    return "\n".join(rows) + "\n"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--peer", type=Path, required=True)
    ap.add_argument("--apk", type=Path, required=True)
    ap.add_argument("--test-apk", type=Path, required=True)
    ap.add_argument("--setups", default=",".join(SETUPS))
    ap.add_argument("--seeds", default="1101")
    ap.add_argument("--events", type=int, default=20000)
    ap.add_argument("--throttle", type=int, default=100)
    ap.add_argument("--out", type=Path, default=Path("build/monkey"))
    a = ap.parse_args()
    setups = a.setups.split(",")
    if unknown := [s for s in setups if s not in SETUPS]:
        sys.exit(f"unknown setups: {unknown}; known: {list(SETUPS)}")
    seeds = [int(s) for s in a.seeds.split(",")]
    a.out.mkdir(parents=True, exist_ok=True)

    run.require_emulator()
    dev = Device()
    results = []
    try:
        for name in setups:
            for seed in seeds:
                results.append(one_run(name, SETUPS[name], seed, a, dev))
                (a.out / "summary.md").write_text(report(results, a))
    finally:
        dev.restore()
        adb("uninstall", APP, check=False)
        adb("uninstall", f"{APP}.test", check=False)
    log(f"report: {a.out / 'summary.md'}")
    if any(r["result"] != "ok" for r in results):
        sys.exit(1)


if __name__ == "__main__":
    main()
