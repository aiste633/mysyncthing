#!/usr/bin/env python3
"""The emulator end-to-end tests on the PC, for one commit (GitHub CI's e2e job until 11/2026).

From a clean checkout: builds the debug app, its instrumented test APK and the test peer, then on an
Android 15 and then an Android 16 emulator (one at a time, each started fresh) runs run.py and
scenarios.py --only fast, each from a fresh install. Writes <out>/summary.md: the commit, the
emulator and its images, and every step's result and time; all logs stay next to it. Paste the
summary into the pull request. With --release a passing summary also goes to
docs/e2e/<versionName>.md: commit it before tagging, as a tag without it fails CI
(scripts/e2e/check-results.sh in reproducible.yml).

Needs ANDROID_HOME with the SDK and NDK, and Go and JDK 21 on PATH (on the PC: source
toolchain/env.sh). Its emulators use port 5580 and every adb call goes to them (ANDROID_SERIAL),
so it never touches another emulator or a phone. It refuses to start while any syncthing process
runs. Offline by default: the emulator gets no DNS and the phone core leaves Syncthing's public
servers once paired (E2E_OFFLINE=1, --offline); --online runs as CI did. About 1.5 h for three APIs (55 min for two on 07/11/2026).

  scripts/e2e/pc.py [--release] [--online] [--apis 35,36,37] [--out build/e2e-pc]
"""
import argparse
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
PORT = 5580
SERIAL = f"emulator-{PORT}"
ANDROID = {35: "Android 15", 36: "Android 16", 37: "Android 17"}
PEER = "go/build/e2e-peer"
APKS = ["--peer", PEER, "--apk", "app/build/outputs/apk/debug/app-debug.apk",
        "--test-apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
MIN = 60


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def out_of(*cmd: str, cwd: Path = ROOT) -> str:
    return subprocess.run(cmd, cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def git(*args: str) -> str:
    return out_of("git", *args)


def dirty() -> str:
    """Changed or untracked files. The submodule's own changes are go/build.sh's patches, applied
    again by every build; a submodule moved to another commit still counts."""
    return git("status", "--porcelain", "--ignore-submodules=dirty")


def pkg_revision(path: Path) -> str:
    try:
        text = (path / "source.properties").read_text()
    except OSError:
        return "not installed"
    m = re.search(r"^Pkg\.Revision\s*=\s*(.+)$", text, re.M)
    return m.group(1).strip() if m else "unknown"


def adb() -> str:
    tools = Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"
    return str(tools) if tools.exists() else "adb"


def serials() -> list[str]:
    lines = out_of(adb(), "devices").splitlines()[1:]
    return [line.split()[0] for line in lines if line.strip()]


def preflight() -> None:
    if not os.environ.get("ANDROID_HOME"):
        sys.exit("set ANDROID_HOME (on the PC: source toolchain/env.sh)")
    if changed := dirty():
        sys.exit(f"the checkout has changes; results must belong to one commit:\n{changed}")
    if r := subprocess.run(["pgrep", "-a", "-x", "syncthing"], capture_output=True, text=True).stdout.strip():
        sys.exit(f"a Syncthing runs on this machine; stop it first (this script never touches it):\n{r}")
    if SERIAL in serials():
        sys.exit(f"{SERIAL} already runs; stop it first (adb -s {SERIAL} emu kill)")
    if r := subprocess.run(["pgrep", "-af", "--", r"-avd e2e-[0-9]+( |$)"], capture_output=True, text=True).stdout.strip():
        sys.exit(f"an e2e-NN emulator already runs; stop it first:\n{r}")


def shown(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


@dataclass
class Result:
    """One row of the summary."""
    name: str
    result: str  # "ok" or "FAIL: ..."
    minutes: float

    @property
    def ok(self) -> bool:
        return self.result == "ok"


def step(name: str, cmd: list[str], logfile: Path, env: dict[str, str], timeout_min: int, cwd: Path = ROOT) -> Result:
    """Runs [cmd] with its output in [logfile], in its own process group so a timeout stops all of it."""
    log(f"{name} (log: {shown(logfile)})")
    began = time.monotonic()
    with logfile.open("w") as f:
        p = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=f, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code = p.wait(timeout=timeout_min * MIN)
            result = "ok" if code == 0 else f"FAIL: exit status {code}"
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, signal.SIGTERM)
            try:
                p.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(p.pid, signal.SIGKILL)
                p.wait()
            result = f"FAIL: over {timeout_min} min, stopped"
        except BaseException:
            os.killpg(p.pid, signal.SIGTERM)
            raise
    r = Result(name, result, (time.monotonic() - began) / MIN)
    log(f"{name}: {r.result} ({r.minutes:.1f} min)")
    return r


def stop_emulator(env: dict[str, str], api: int) -> None:
    subprocess.run([adb(), "emu", "kill"], env=env, capture_output=True, timeout=60)
    for _ in range(60):
        if SERIAL not in serials():
            break
        time.sleep(1)
    else:
        log(f"{SERIAL} still listed a minute after 'emu kill'; stopping its process")
    # By its AVD, which emulator.sh made afresh for this run (preflight: no e2e-NN emulator ran before).
    # Not by port: once the launcher execs qemu, the port may be spelled another way in its arguments.
    subprocess.run(["pkill", "-f", "--", rf"-avd e2e-{api}( |$)"], capture_output=True)


def run_api(api: int, out: Path, offline: bool) -> list[Result]:
    d = out / f"api{api}"
    d.mkdir(parents=True)
    env = {**os.environ, "ANDROID_SERIAL": SERIAL, "E2E_PORT": str(PORT), "E2E_EMULATOR_LOG": str(d / "emulator.log")}
    env.pop("E2E_OFFLINE", None)
    if offline:
        env["E2E_OFFLINE"] = "1"
    off = ["--offline"] if offline else []
    steps: list[Result] = []
    try:
        steps.append(step(f"API {api}: emulator", ["scripts/e2e/emulator.sh", str(api)], d / "emulator-start.log", env, 20))
        if steps[-1].ok:
            # The two are independent (each installs afresh), so the second runs also after a failure.
            steps.append(step(f"API {api}: run.py", ["scripts/e2e/run.py", *APKS, *off, "--out", str(d / "run")],
                              d / "run.log", env, 90))
            steps.append(step(f"API {api}: scenarios.py --only fast",
                              ["scripts/e2e/scenarios.py", "--only", "fast", *APKS, *off, "--out", str(d / "scenarios")],
                              d / "scenarios.log", env, 30))
    finally:
        stop_emulator(env, api)
    return steps


def private(text: str) -> str:
    """No home path or machine name in a summary that may be committed (scripts/check-leaks.sh)."""
    text = text.replace(str(ROOT), "<repo>").replace(str(Path.home()), "~")
    if node := platform.node():
        text = re.sub(rf"\b{re.escape(node)}\b", "<host>", text)
    return text


def embedded(summary: Path, title: str) -> list[str]:
    """A script's own summary.md under a heading of ours, its own first heading left out."""
    if not summary.exists():
        return [f"#### {title}", "", "No summary: the script ended before writing one (see its log).", ""]
    lines = summary.read_text(errors="replace").splitlines()
    if lines and lines[0].startswith("# "):
        lines = lines[1:]
    return [f"#### {title}", *lines, ""]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apis", default="35,36,37", help="API levels, in order (a release needs 35, 36 and 37)")
    ap.add_argument("--online", action="store_true", help="keep the emulator's DNS and the phone core's public servers, as CI did")
    ap.add_argument("--release", action="store_true", help="a passing summary also goes to docs/e2e/<versionName>.md")
    ap.add_argument("--out", type=Path, default=Path("build/e2e-pc"))
    a = ap.parse_args()
    apis = [int(x) for x in a.apis.split(",")]
    if unknown := [x for x in apis if x not in ANDROID]:
        sys.exit(f"unknown API levels {unknown}; known: {sorted(ANDROID)}")
    os.chdir(ROOT)
    preflight()
    out = (ROOT / a.out).resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    commit = git("rev-parse", "HEAD")
    subject = git("log", "-1", "--format=%s")
    version = re.search(r'^\s*versionName = "(.+)"$', (ROOT / "app/build.gradle.kts").read_text(), re.M).group(1)
    began = datetime.now(timezone.utc)
    log(f"commit {commit[:12]} ({subject}), version {version}, APIs {apis}, {'online' if a.online else 'offline'}")

    env = dict(os.environ)
    build = [step("build: app and test APKs", ["./gradlew", "--no-daemon", ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
                  out / "build-app.log", env, 60)]
    if build[0].ok:
        st_version = (ROOT / "go/syncthing.version").read_text().strip()
        build.append(step("build: test peer", ["go", "build", "-trimpath", "-tags", "noupgrade", "-ldflags",
                                               f"-X github.com/syncthing/syncthing/lib/build.Version={st_version}",
                                               "-o", str(ROOT / PEER), "./cmd/syncthing"],
                          out / "build-peer.log", env, 20, cwd=ROOT / "go/syncthing"))
    if changed := dirty():
        (out / "build-check.log").write_text(f"the build changed files of the checkout:\n{changed}\n")
        build.append(Result("build: checkout unchanged", "FAIL: the build changed files (see build-check.log)", 0.0))
    built = all(s.ok for s in build)

    runs: dict[int, list[Result]] = {}
    if built:
        for api in apis:
            runs[api] = run_api(api, out, not a.online)
    ended = datetime.now(timezone.utc)

    def api_ok(api: int) -> bool:
        return api in runs and all(s.ok for s in runs[api])

    passed = built and all(api_ok(api) for api in apis)
    lines = [
        f"# PC end-to-end results: {version}",
        "",
        f"- Commit: {commit}",
        f"- Result: {'PASS' if passed else 'FAIL'}",
        f"- Subject: {subject}",
        f"- When: {began:%Y-%m-%d %H:%M} to {ended:%H:%M} UTC ({(ended - began).total_seconds() / MIN:.0f} min)",
        "- Network: " + ("online, as CI ran" if a.online else
                         "offline (emulator without DNS; phone core off Syncthing's public servers once paired)"),
        f"- Host: {platform.system()} {platform.machine()}; Android Emulator {pkg_revision(Path(os.environ['ANDROID_HOME']) / 'emulator')}",
        "- Script: scripts/e2e/pc.py: run.py, then scenarios.py --only fast, each from a fresh install",
        "",
        "## Build",
        "",
        "| step | result | min |",
        "|---|---|---|",
        *[f"| {s.name.removeprefix('build: ')} | {s.result} | {s.minutes:.1f} |" for s in build],
        "",
    ]
    for api in apis:
        pkg_api = f"{api}.0" if api >= 37 else str(api)  # as emulator.sh: android-37.0 from API 37 on
        image = pkg_revision(Path(os.environ["ANDROID_HOME"]) / f"system-images/android-{pkg_api}/default/x86_64")
        lines += [f"## {ANDROID[api]} (API {api}): {'PASS' if api_ok(api) else 'FAIL'}", "",
                  f"Image: system-images;android-{pkg_api};default;x86_64, revision {image}; Pixel 7.", ""]
        if api not in runs:
            lines += ["Not run: the build failed.", ""]
            continue
        lines += ["| step | result | min |", "|---|---|---|",
                  *[f"| {s.name.removeprefix(f'API {api}: ')} | {s.result} | {s.minutes:.1f} |" for s in runs[api]], ""]
        lines += embedded(out / f"api{api}/run/summary.md", "run.py")
        lines += embedded(out / f"api{api}/scenarios/summary.md", "scenarios.py --only fast")
    text = private("\n".join(lines).rstrip() + "\n")
    (out / "summary.md").write_text(text)
    print(text)
    log(f"summary: {shown(out / 'summary.md')}")

    if a.release:
        if not passed:
            log("not written to docs/e2e/: a release needs a passing run")
        elif set(apis) != set(ANDROID):
            log(f"not written to docs/e2e/: a release needs APIs {sorted(ANDROID)}")
        else:
            dst = ROOT / "docs/e2e" / f"{version}.md"
            dst.parent.mkdir(exist_ok=True)
            dst.write_text(text)
            log(f"wrote {dst.relative_to(ROOT)}; commit it, then tag: "
                f"git add {dst.relative_to(ROOT)} && git commit -m 'PC end-to-end results for {version}'")
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
