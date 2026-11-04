#!/usr/bin/env python3
"""Repeats the CI monkey's ANR of 2026-11-03 (API 35) on an emulator: the rename dialog open with
the keyboard up, two rotations within a second, and after the relaunch no window had input focus
("Application does not have a focused window") while the app's main thread was idle.

Fresh install and pairing with the e2e peer (the folder to rename), then driven from here, with no
instrumentation in the app's process: an instrumented version kept every relaunched activity (184,
then out of memory) where the app alone keeps none. Per gap and round: the field tapped so the
keyboard is up, rotated 90 degrees and back [gap] ms later, a key sent as the monkey's was, and the
window manager must name a focused window of the app within 4 s (an input ANR comes after 5 s).
On a loss: window and input dumps and the main thread's stack (SIGQUIT via run-as, into /data/anr).

Writes to --out: summary lines, each loss's dumps, the whole logcat and /data/anr. Runs only on an
emulator; settings restored, the app left uninstalled.
"""
import argparse
import re
import shutil
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import run
from run import APP, adb, log, sh

NAME = "Name"  # R.string.name


def nodes() -> list[ET.Element]:
    sh("uiautomator dump /data/local/tmp/ui.xml", check=False)
    return list(ET.fromstring(sh("cat /data/local/tmp/ui.xml")).iter("node"))


def center(node: ET.Element) -> tuple[int, int]:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_label(label: str, timeout: float = 20) -> None:
    """Taps the node showing [label] (its text or description), as a person reads the screen."""
    end = time.time() + timeout
    while time.time() < end:
        hit = next((n for n in nodes() if label in (n.get("text"), n.get("content-desc"))), None)
        if hit is not None:
            sh("input tap %d %d" % center(hit))
            return
        time.sleep(1)
    raise RuntimeError(f"nothing shows {label!r}")


def keyboard_up() -> bool:
    return "mInputShown=true" in sh("dumpsys input_method", check=False)


def focus_field() -> bool:
    """Taps the text field (the only editable node) until the keyboard is up."""
    for _ in range(3):
        field = next((n for n in nodes() if n.get("class") == "android.widget.EditText"), None)
        if field is None:
            return False
        sh("input tap %d %d" % center(field))
        end = time.time() + 3
        while time.time() < end:
            if keyboard_up():
                return True
            time.sleep(0.25)
    return False


def focused(timeout: float) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        if APP in run.focus_line():
            return True
        time.sleep(0.25)
    return False


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--peer", type=Path, required=True)
    ap.add_argument("--apk", type=Path, required=True)
    ap.add_argument("--test-apk", type=Path, required=True)
    ap.add_argument("--loops", type=int, default=50, help="rounds per gap")
    ap.add_argument("--gaps", default="300,500,700,1000", help="ms between the two rotations")
    ap.add_argument("--out", type=Path, default=Path("build/focus-repro"))
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)

    run.require_emulator()
    run.prepare(a.apk, a.test_apk)
    adb("logcat", "-c")
    dev = run.Device()
    home = Path(tempfile.mkdtemp(prefix="mysyncthing-e2e-"))
    peer = run.Peer(a.peer.resolve(), home, a.out.resolve())
    summary: list[str] = []
    losses = 0
    try:
        dev.keep_awake()
        dev.apply({})
        peer.start()
        if not run.phase("pairAndSync", peer, a.out):
            return
        run.open_app()
        tap_label(run.LABEL)
        tap_label(NAME)
        stuck = False
        for gap in (int(g) for g in a.gaps.split(",")):
            no_keyboard = 0
            for i in range(1, a.loops + 1):
                if not keyboard_up() and not focus_field():
                    no_keyboard += 1
                sh("settings put system user_rotation 1")
                time.sleep(gap / 1000)
                sh("settings put system user_rotation 0")
                sh("input keyevent KEYCODE_DPAD_DOWN", check=False)  # an input waiting for focus, as the monkey's key was
                if focused(4):
                    continue
                losses += 1
                pid = run.app_pid()
                sh(f"run-as {APP} kill -3 {pid}", check=False)
                (a.out / f"focus-loss-{losses}.txt").write_text(
                    f"gap {gap} ms, round {i}, pid {pid}\n{run.focus_line()}\n\n"
                    + "\n\n".join(sh(c, check=False) for c in ("dumpsys window displays", "dumpsys window windows", "dumpsys input")))
                summary.append(f"gap {gap} ms: focus lost in round {i}")
                if not focused(15):
                    summary.append(f"gap {gap} ms: still no focus 15 s later; stopped")
                    stuck = True
                    break
            summary.append(f"gap {gap} ms: {a.loops if not stuck else i} rounds, keyboard not up before {no_keyboard} of them")
            if stuck:
                break
    finally:
        peer.stop()
        dev.restore()
        (a.out / "logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=300))
        run.pull_anr_traces(a.out / "anr")
        for pkg in (APP, f"{APP}.test"):
            adb("uninstall", pkg, check=False)
        shutil.rmtree(home, ignore_errors=True)
        for args in (("reverse", "--remove", f"tcp:{run.GUI_PORT}"), ("forward", "--remove", f"tcp:{run.FORWARD_PORT}")):
            adb(*args, check=False)

    logcat = (a.out / "logcat.txt").read_text(errors="replace")
    ours, others = run.logcat_anrs(logcat)
    relaunches = logcat.count("finishDrawing of relaunch")
    summary.append(f"focus losses: {losses}; app ANRs: {ours}; other ANRs: {others}; relaunches drawn: {relaunches}")
    summary.append(f"keyboard shown (ImeTracker onShown): {logcat.count('onShown')}")
    (a.out / "summary.txt").write_text("\n".join(summary) + "\n")
    for line in summary:
        log(line)
    if run.failures:
        log(f"{len(run.failures)} failed:\n  " + "\n  ".join(run.failures))


if __name__ == "__main__":
    main()
