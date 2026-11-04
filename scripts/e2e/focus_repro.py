#!/usr/bin/env python3
"""Repeats the CI monkey's ANR of 2026-11-03 (API 35) on an emulator: the rename dialog open with
the keyboard up, two rotations within a second, and after the relaunch no window had input focus
("Application does not have a focused window"), with the app's main thread idle.

Fresh install, pairing with the e2e peer (the folder to rename), then E2eTest#dialogQuickRotations
with --loops rounds per gap. Writes to --out: the phase's output, the whole logcat, each focus
loss's dump (focus-loss-N.txt: main thread stack, window and input dumps) and /data/anr. Runs only
on an emulator (adb root, settings restored, app left uninstalled).
"""
import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path

import run
from run import ADB, APP, adb, log, sh


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--peer", type=Path, required=True)
    ap.add_argument("--apk", type=Path, required=True)
    ap.add_argument("--test-apk", type=Path, required=True)
    ap.add_argument("--loops", default="25", help="rounds per gap")
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
    try:
        dev.keep_awake()
        dev.apply({})
        peer.start()
        if run.phase("pairAndSync", peer, a.out):
            run.phase("dialogQuickRotations", peer, a.out, loops=a.loops, gaps=a.gaps)
    finally:
        peer.stop()
        dev.restore()
        (a.out / "logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=300))
        adb("root", check=False)
        adb("wait-for-device", check=False)
        for src, dst in ((f"/sdcard/Android/data/{APP}/files/.", a.out / "losses"), ("/data/anr/.", a.out / "anr")):
            dst.mkdir(exist_ok=True)
            subprocess.run([ADB, "pull", src, str(dst)], capture_output=True, text=True)
        adb("unroot", check=False)
        adb("wait-for-device", check=False)
        sh(f"pm uninstall {APP}", check=False)
        sh(f"pm uninstall {APP}.test", check=False)
        shutil.rmtree(home, ignore_errors=True)
        for args in (("reverse", "--remove", f"tcp:{run.GUI_PORT}"), ("forward", "--remove", f"tcp:{run.FORWARD_PORT}")):
            adb(*args, check=False)

    logcat = (a.out / "logcat.txt").read_text(errors="replace")
    ours, others = run.logcat_anrs(logcat)
    losses = sorted((a.out / "losses").glob("focus-loss-*.txt"))
    log(f"focus losses: {len(losses)}; app ANRs: {ours}; other ANRs: {others}")
    if run.failures:
        log(f"{len(run.failures)} failed:\n  " + "\n  ".join(run.failures))
    log(f"results in {a.out}")


if __name__ == "__main__":
    main()
