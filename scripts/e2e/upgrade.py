#!/usr/bin/env python3
"""Upgrade test: the last release, set up and syncing, updated in place to this build.

A user updates by installing the new APK over the old one (by hand or through
F-Droid): Android keeps the app's data and kills the running app. This installs
the old debug build, lets its own E2eTest#pairAndSync pair it with the test peer
(run.py's) and sync, adds more kinds of settings through the phone core's REST
API (which every version has), then installs this build over it while a transfer
runs. Without opening the app it checks that sync comes back by itself, and that
the identity, the whole core config, ignore patterns and database, the app's
settings and the permissions are all as they were; then E2eTest#afterUpgrade checks the
rest from inside the app. On Android 17 an update from a build for Android 16 must stop
syncing and post the notice until the local network is allowed. It also checks that a user's Stop holds through an update, that
an update with Start at boot off starts again what ran, and that Android refuses
the old build back.

Before a release, run it from the last release and from the oldest one still in
use. A core update then runs upstream's config and database migrations on real
old data. Both builds must carry the same signing key: debug builds made on one
machine do (its ~/.android/debug.keystore). Runs only on an emulator: it wipes
the app's data.

  scripts/e2e/upgrade.py --peer go/build/e2e-peer \\
      --old-apk old/app-debug.apk --old-test-apk old/app-debug-androidTest.apk \\
      --apk app/build/outputs/apk/debug/app-debug.apk \\
      --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \\
      --out build/upgrade
"""
import argparse
import hashlib
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ next to run.py
import run  # noqa: E402
from run import (  # noqa: E402
    ADB, APP, FOLDER, PHONE_API_PORT, PHONE_DIR, Peer, PhoneCore, adb, app_pid, eventually, fail, log, phase, phone_id, private, sh,
)

BIG = "big.bin"
BIG_MB = 48
SLOW_KBPS = 1000  # the peer's send limit while the update lands: BIG takes about 50 s
FROM_PEER_AFTER = "from-peer-after-update.txt"  # E2eTest's
RO_DIR, ENC_DIR = "/sdcard/Syncthing/UpgradeRO", "/sdcard/Syncthing/UpgradeEnc"
# Values an update may change, each with its reason; anything else changed or gone fails.
MAY_CHANGE = {
    "/version": "the config's format version: a newer core migrates it",
    "/gui/address": "a loopback port picked at every start (go/bridge)",
    "/gui/apiKey": "a new login at every start (go/bridge Start)",
    "/gui/user": "a new login at every start (go/bridge Start)",
    "/gui/password": "a new login at every start (go/bridge Start)",
}
# The stored login of 0.2.0 and older: the app removes it at start and keeps the login in memory only.
GONE_SETTINGS = ("api_key", "gui_user", "gui_password")
SECRET = re.compile(r"api_?key|password|user", re.IGNORECASE)


def varint(b: bytes, i: int) -> tuple[int, int]:
    n = shift = 0
    while True:
        n |= (b[i] & 0x7F) << shift
        i += 1
        if b[i - 1] < 0x80:
            return n, i
        shift += 7


def fields(b: bytes):
    """(field number, value) of a protobuf message; a length-delimited value as bytes."""
    i = 0
    while i < len(b):
        tag, i = varint(b, i)
        kind = tag & 7
        if kind == 0:
            v, i = varint(b, i)
        elif kind == 2:
            n, i = varint(b, i)
            v, i = b[i:i + n], i + n
        elif kind in (1, 5):
            n = 8 if kind == 1 else 4
            v, i = b[i:i + n], i + n
        else:
            raise ValueError(f"protobuf wire type {kind}")
        yield tag >> 3, v


def preferences(pb: bytes) -> dict[str, bytes]:
    """DataStore's PreferenceMap: key -> its Value message, compared as bytes."""
    out = {}
    for num, entry in fields(pb):
        if num == 1:
            kv = dict(fields(entry))
            out[kv[1].decode()] = kv.get(2, b"")
    return out


def new_device_id(peer: Peer) -> str:
    """A real device ID that no device has: generated with the peer's binary and thrown away."""
    with tempfile.TemporaryDirectory() as home:
        subprocess.run([peer.binary, "generate", "--home", home, "--no-port-probing"], env=peer.env, check=True, capture_output=True)
        return subprocess.run([peer.binary, "device-id", "--home", home], env=peer.env, check=True, capture_output=True, text=True).stdout.strip()


def seed(core: PhoneCore, second: str, untrusted: str) -> None:
    """
    More than the old build's own test sets up, so a core update's config migration meets
    more kinds of settings: another device, an untrusted one, a receive-only folder with
    staggered versioning, a folder shared encrypted, ignore patterns and changed options.
    The addresses are on loopback and nothing listens there: the devices never connect.
    """
    core.call("POST", "rest/config/devices", {
        "deviceID": second, "name": "upgrade-second", "addresses": ["tcp://127.0.0.1:22998"],
        "compression": "always", "maxSendKbps": 700, "maxRecvKbps": 300, "numConnections": 2,
    })
    core.call("POST", "rest/config/devices", {
        "deviceID": untrusted, "name": "upgrade-untrusted", "addresses": ["tcp://127.0.0.1:22999"], "untrusted": True,
    })
    for folder, path in (("upgrade-ro", RO_DIR), ("upgrade-enc", ENC_DIR)):
        sh(f"mkdir -p {path}")
        sh(f"echo {folder} > {path}/note.txt")
    core.call("POST", "rest/config/folders", {
        "id": "upgrade-ro", "label": "Upgrade receive-only", "path": RO_DIR, "type": "receiveonly",
        "devices": [{"deviceID": second}], "rescanIntervalS": 7200, "fsWatcherEnabled": False, "order": "smallestFirst",
        "versioning": {"type": "staggered", "params": {"maxAge": "864000", "cleanInterval": "7200"}},
    })
    core.call("POST", "rest/config/folders", {
        "id": "upgrade-enc", "label": "Upgrade encrypted", "path": ENC_DIR, "type": "sendonly",
        "devices": [{"deviceID": untrusted, "encryptionPassword": "upgrade-test-only"}],
    })
    core.call("PATCH", f"rest/config/folders/{FOLDER}", {"maxConflicts": 5, "order": "oldestFirst"})
    core.call("POST", f"rest/db/ignores?folder={FOLDER}", {"ignore": ["// upgrade test", "!keep.part", "*.part", "(?d).DS_Store", "/private/**"]})
    core.call("PATCH", "rest/config/options", {
        "maxFolderConcurrency": 2, "keepTemporariesH": 48, "progressUpdateIntervalS": 7, "connectionLimitMax": 20,
        "minHomeDiskFree": {"value": 2, "unit": "%"},
    })


def by_id(v):
    """Lists of devices and folders as maps by their ID, so their order does not count."""
    if isinstance(v, list) and v and all(isinstance(x, dict) and ("deviceID" in x or "id" in x) for x in v):
        return {x.get("deviceID") or x["id"]: by_id(x) for x in v}
    if isinstance(v, dict):
        return {k: by_id(x) for k, x in v.items()}
    return v


def changed(before, after, path=""):
    """(path, before, after) for every value of [before] that [after] changed or dropped."""
    if path in MAY_CHANGE:
        return
    if isinstance(before, dict) and isinstance(after, dict):
        for k, v in before.items():
            if k in after:
                yield from changed(v, after[k], f"{path}/{k}")
            else:
                yield f"{path}/{k}", v, "(gone)"
    elif before != after:
        yield path, before, after


def added(before, after, path=""):
    """Paths only in [after]: what a newer core adds, for the log."""
    if isinstance(before, dict) and isinstance(after, dict):
        for k, v in after.items():
            yield from (added(before[k], v, f"{path}/{k}") if k in before else [f"{path}/{k}"])


def database() -> dict[str, str]:
    """The core's database files and their inodes: a database made anew has new ones."""
    out = {}
    for line in sh(f"run-as {APP} ls -i files/syncthing/index-v2").split("\n"):
        parts = line.split()
        if len(parts) == 2 and parts[1].endswith(".db"):
            out[parts[1]] = parts[0]
    return out


def state(core: PhoneCore) -> dict:
    core.connect()
    config = by_id(core.call("GET", "rest/config"))
    return {
        "identity": {f: hashlib.sha256(private(f"files/syncthing/{f}")).hexdigest() for f in ("cert.pem", "key.pem")},
        "config": config,
        "ignores": {f: core.call("GET", f"rest/db/ignores?folder={f}") for f in config["folders"]},
        "settings": preferences(private("files/datastore/settings.preferences_pb")),
        "database": database(),
    }


def compare(before: dict, after: dict, names: dict[str, str]) -> None:
    # No device IDs and no secrets in the log: it goes into reports.
    def label(path: str) -> str:
        for device, name in names.items():
            path = path.replace(device, name)
        return path

    def value(path: str, v) -> str:
        return "(hidden)" if SECRET.search(path) else repr(v)

    if not before["database"]:
        fail("no database files found before the update")
    for what in ("identity", "database"):
        if after[what] != before[what]:
            fail(f"{what} changed in the update")
    for name in GONE_SETTINGS:
        if name in after["settings"]:
            fail(f"settings {name} still stored after the update")
    before = {**before, "settings": {k: v for k, v in before["settings"].items() if k not in GONE_SETTINGS}}
    for what in ("config", "ignores", "settings"):
        # A newer core or app may add settings; none of the old ones may go or change.
        for path, old, new in changed(before[what], after[what]):
            fail(f"{what} {label(path)} changed in the update: {value(path, old)} -> {value(path, new)}")
        for path in added(before[what], after[what]):
            log(f"{what} {label(path)} new in the update")


def service_running() -> bool:
    return ".SyncthingService" in sh(f"dumpsys activity services {APP}", check=False)


def version_code() -> int:
    for line in sh(f"dumpsys package {APP}").split("\n"):
        if "versionCode=" in line:
            return int(line.split("versionCode=")[1].split()[0])
    raise RuntimeError("no versionCode in dumpsys package")


def install(apk: Path) -> subprocess.CompletedProcess:
    """`adb install -r` as a user's update: no -g, the old install's permissions must carry over."""
    return subprocess.run([ADB, "install", "-r", str(apk)], capture_output=True, text=True, timeout=300)


def permissions() -> dict[str, bool]:
    pkg = sh(f"dumpsys package {APP}")
    return {
        "All files access": "MANAGE_EXTERNAL_STORAGE: allow" in sh(f"appops get {APP} MANAGE_EXTERNAL_STORAGE"),
        "notifications": "android.permission.POST_NOTIFICATIONS: granted=true" in pkg,
        "battery optimisation off": APP in sh("dumpsys deviceidle whitelist"),
        "local network": local_network_granted(pkg),
    }


def local_network_granted(pkg: str | None = None) -> bool:
    """Android 15 and 16 grant it with INTERNET; Android 17 asks for it."""
    if run.api_level() < 37:
        return True
    return f"{run.LOCAL_NETWORK}: granted=true" in (pkg or sh(f"dumpsys package {APP}"))


def local_network_after_update(peer: Peer, phone: str, updated: float) -> None:
    """
    An update from a build that targets Android 16 arrives on Android 17 without the local network
    permission: the service, started again by the update, must not start the core and must post the
    notice. Then the user grants it and opens the app, as the notice's tap leads them to.
    """
    if local_network_granted():
        log("local network granted after the update (no notice needed)")
        return
    log("local network not granted after the update, as for a user")
    if eventually("notice after the update: syncing stopped without the local network", 120,
                  lambda: run.problem_shown(run.NO_LOCAL_NETWORK_ID)):
        time.sleep(15)
        if peer.connected(phone, since=updated):
            fail("the core ran after the update without the local network permission")
    sh(f"pm grant {APP} {run.LOCAL_NETWORK}")
    run.open_app()


def phone_ls() -> list[str]:
    return sh(f"ls -a {PHONE_DIR}", check=False).split()


def comes_back(peer: Peer, phone: str, old_pid: str, what: str) -> bool:
    """Sync back by itself, without the app being opened."""
    return (eventually(f"app process restarted after {what}", 120, lambda: app_pid() not in ("", old_pid))
            and eventually(f"sync service running after {what}", 60, service_running)
            and eventually(f"peer reconnected after {what}", 120, lambda: peer.connected(phone)))


def update_mid_transfer(peer: Peer, phone: str, apk: Path) -> None:
    """The update lands while the phone downloads a file; the download finishes afterwards, intact."""
    # Loopback counts as LAN, which the core leaves unlimited unless told otherwise.
    peer.call("PATCH", "rest/config/options", {"maxSendKbps": SLOW_KBPS, "limitBandwidthInLan": True})
    data = peer.folder / BIG
    with data.open("wb") as f:
        for _ in range(BIG_MB):
            f.write(secrets.token_bytes(1 << 20))
    digest = hashlib.sha256(data.read_bytes()).hexdigest()
    peer.scan()
    tmp = f".syncthing.{BIG}.tmp"
    eventually(f"{BIG} downloading on the phone", 120, lambda: tmp in phone_ls() and int(sh(f"stat -c %s {PHONE_DIR}/{tmp}")) > 0)

    pid = app_pid()
    log(f"update over the running app (pid {pid}), {BIG} partly downloaded")
    updated = time.time()
    r = install(apk)
    if "Success" not in r.stdout:
        raise RuntimeError(f"update refused: {r.stdout}{r.stderr}")
    local_network_after_update(peer, phone, updated)
    comes_back(peer, phone, pid, "the update")
    peer.call("PATCH", "rest/config/options", {"maxSendKbps": 0, "limitBandwidthInLan": False})
    if eventually(f"{BIG} complete on the phone", 300, lambda: BIG in phone_ls() and tmp not in phone_ls()):
        got = sh(f"sha256sum {PHONE_DIR}/{BIG}").split()[0]
        if got != digest:
            fail(f"{BIG} on the phone differs from the peer's after the update")
    conflicts = [n for n in phone_ls() if ".sync-conflict-" in n]
    if conflicts:
        fail(f"conflict copies after the update: {conflicts}")


def to_background() -> None:
    """
    `am instrument` force-stops the app when a phase ends, and a force-stopped app gets no
    broadcasts: opened and left as a person would, it is a normal app in the background again.
    """
    run.open_app()
    run.go_home()


def syncing_in_background(peer: Peer, phone: str, what: str) -> bool:
    to_background()
    return eventually(f"{what}: syncing in the background", 90, lambda: service_running() and peer.connected(phone))


def stop_holds(peer: Peer, phone: str, apk: Path, out: Path) -> None:
    """A user's Stop lasts until they start again or the phone restarts, through an update too."""
    if not phase("stopSync", peer, out):
        return
    to_background()
    time.sleep(10)
    if service_running():
        fail("opening the app started sync again after the user's Stop")
        return
    r = install(apk)  # the same build again: Android still sends MY_PACKAGE_REPLACED
    if "Success" not in r.stdout:
        raise RuntimeError(f"reinstall refused: {r.stdout}{r.stderr}")
    time.sleep(45)
    if service_running() or peer.connected(phone):
        fail("an update started sync again after the user's Stop")
    if phase("startSync", peer, out):
        syncing_in_background(peer, phone, "Start")


def restart_without_boot_start(peer: Peer, phone: str, apk: Path, out: Path) -> None:
    """With Start at boot off, an update still starts again what ran before it."""
    if not phase("startOnBootOff", peer, out) or not syncing_in_background(peer, phone, "Start at boot off"):
        return
    pid = app_pid()
    r = install(apk)
    if "Success" not in r.stdout:
        raise RuntimeError(f"reinstall refused: {r.stdout}{r.stderr}")
    comes_back(peer, phone, pid, "an update with Start at boot off")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--peer", type=Path, required=True)
    ap.add_argument("--old-apk", type=Path, required=True)
    ap.add_argument("--old-test-apk", type=Path, required=True)
    ap.add_argument("--apk", type=Path, required=True)
    ap.add_argument("--test-apk", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=Path("build/upgrade"))
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)

    run.require_emulator()
    run.prepare(a.old_apk, a.old_test_apk)
    adb("logcat", "-c")
    home = Path(tempfile.mkdtemp(prefix="mysyncthing-upgrade-"))
    peer = Peer(a.peer.resolve(), home, a.out.resolve())
    try:
        peer.start()
        if not phase("pairAndSync", peer, a.out):  # the old build's own test
            return
        phone = phone_id(peer)
        old_code = version_code()
        if not syncing_in_background(peer, phone, "old build"):
            return
        core = PhoneCore()
        second, untrusted = new_device_id(peer), new_device_id(peer)
        core.connect()
        seed(core, second, untrusted)
        names = {phone: "(phone)", peer.id: "(peer)", second: "(second)", untrusted: "(untrusted)"}
        before, perms = state(core), permissions()

        update_mid_transfer(peer, phone, a.apk)
        new_code = version_code()
        log(f"updated: versionCode {old_code} -> {new_code}")
        compare(before, state(core), names)
        now = permissions()
        for name, had in perms.items():
            if had and not now[name]:
                fail(f"{name} lost in the update")

        (peer.folder / FROM_PEER_AFTER).write_text("written on the peer after the update\n")
        peer.scan()
        r = install(a.test_apk)
        if "Success" not in r.stdout:
            raise RuntimeError(f"test APK: {r.stdout}{r.stderr}")
        phase("afterUpgrade", peer, a.out, phoneId=phone)

        stop_holds(peer, phone, a.apk, a.out)
        restart_without_boot_start(peer, phone, a.apk, a.out)

        if new_code > old_code:
            r = install(a.old_apk)
            if "INSTALL_FAILED_VERSION_DOWNGRADE" not in r.stdout + r.stderr:
                fail(f"the old build was not refused as a downgrade: {r.stdout}{r.stderr}")
    finally:
        peer.stop()
        (a.out / "logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=300))
        shutil.rmtree(home, ignore_errors=True)
        for args in (("reverse", "--remove", f"tcp:{run.GUI_PORT}"), ("forward", "--remove", f"tcp:{run.FORWARD_PORT}"),
                     ("forward", "--remove", f"tcp:{PHONE_API_PORT}")):
            adb(*args, check=False)

    logcat = (a.out / "logcat.txt").read_text(errors="replace")
    if f"Process: {APP}, PID" in logcat:
        fail("the app crashed (see logcat.txt)")
    if run.failures:
        log(f"{len(run.failures)} failed:\n  " + "\n  ".join(run.failures))
        sys.exit(1)
    log("all passed")


if __name__ == "__main__":
    main()
