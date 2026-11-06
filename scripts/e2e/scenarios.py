#!/usr/bin/env python3
"""Sync scenarios: what conflicts and folder types do between the app and throwaway Syncthing peers.

The debug app on an emulator, paired with run.py's test peer (Syncthing built from our patched core,
run here on loopback only with its own temporary home: no discovery, relays or NAT, so it never meets
a real device or a Syncthing install on this machine). A second such peer joins for the three-device case.
Everything is driven from the host: files on the phone through adb, the phone core through its REST
API (upgrade.PhoneCore), the app's screens through uiautomator (by visible English text), the peers
through theirs. Each scenario sets its devices apart, changes files on more than one side, brings them
together, waits until all agree, and then checks the files on every side and what the app shows.

  fast (also in CI's e2e, about 5 min):
    edit_edit        both edit a file: one copy, both edits kept
    edit_delete      edited on one side, deleted on the other, both orders and both ways: the newer
                     change wins; a deletion that wins keeps the edit as a copy
    both_new         the same new name on both sides: one copy
    rename_edit      renamed here, edited there: both names stay, no copy
    odd_names        subfolders, spaces, accents, CJK, emoji, no extension, a dotfile, two dots, upper case
    copy_deleted     a copy deleted here, another there: gone everywhere, the count follows; the
                     phone's Trash can keeps its own and the count leaves it out
    copy_conflict    both edit the same copy: the core keeps no copy of a copy
    login_rotates    a new web GUI login at every start, and the old one refused
  slow (PC only):
    max_conflicts    12 conflicts in a row on one file, the phone's watcher off: the core keeps 10
                     (upstream's default maxConflicts) and the count follows its deletions
    seeded_copies    12, then 120 copies arriving from the peer: the folder card's count
    ignored_copy     copies an ignore pattern hides still count, and stop counting when deleted
    three_way        three devices edit one file: two copies, named by the winners' short IDs
    send_only        a send-only folder: the peer's change, the app's Override… puts the phone's back
    receive_only     a receive-only folder: a local edit and a new file, the app's Revert… undoes both
    long_name        a 240-byte name in conflict: the copy's name is too long (observed, not judged)

Runs only on an emulator: it wipes the app's data. Results: <out>/summary.md, logs and logcat.

  scripts/e2e/scenarios.py --peer go/build/e2e-peer \\
      --apk app/build/outputs/apk/debug/app-debug.apk \\
      --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \\
      [--only fast | --only edit_edit,three_way] --out build/scenarios
"""
import argparse
import hashlib
import os
import random
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import run  # noqa: E402
from run import ADB, APP, FOLDER, FORWARD_PORT, PHONE_DIR, Peer, adb, eventually, fail, log, sh  # noqa: E402
from upgrade import PHONE_API_PORT, PhoneCore, private  # noqa: E402

PEER_B_GUI, PEER_B_LISTEN = 8387, 22002
CORE_DIRS = {".stfolder", ".stversions"}
MARK = ".sync-conflict-"
# upstream's conflictName: name.sync-conflict-YYYYMMDD-HHMMSS-<short ID>.ext (Conflicts.kt parseConflictCopy)
COPY = re.compile(r"^(.*)\.sync-conflict-(\d{8})-(\d{6})(?:-([A-Z2-7]{7}))?(.*)$")
# A short device ID no device has: base32, 7 letters (seeded_copies).
FAKE_SHORT = "TESTAAA"
SO_ID, SO_DIR = "e2e-so", "/sdcard/Syncthing/E2E-SO"
RO_ID, RO_DIR = "e2e-ro", "/sdcard/Syncthing/E2E-RO"

FAST = ["edit_edit", "edit_delete", "both_new", "rename_edit", "odd_names", "copy_deleted", "copy_conflict", "login_rotates"]
SLOW = ["max_conflicts", "seeded_copies", "ignored_copy", "three_way", "send_only", "receive_only", "long_name"]
STRESS = ["many_files", "big_file", "conflicts_200", "churn_both", "pause_storm", "network_flaps", "count_after_kill",
          "count_after_reboot"]
GROUPS = {"fast": FAST, "slow": SLOW, "stress": STRESS, "all": FAST + SLOW + STRESS}


# ---- files on each side ------------------------------------------------------------------------

def q(path: str) -> str:
    return shlex.quote(path)


def phone_write(rel: str, text: str, root: str = PHONE_DIR) -> None:
    """Through a file pushed by adb: any name and content, no shell quoting of the content."""
    path = f"{root}/{rel}"
    sh(f"mkdir -p {q(path.rsplit('/', 1)[0])}")
    with tempfile.NamedTemporaryFile("w", delete=False, encoding="utf-8") as f:
        f.write(text)
    try:
        adb("push", f.name, path)
    finally:
        Path(f.name).unlink()


def phone_read(rel: str, root: str = PHONE_DIR) -> str | None:
    # `adb shell`, not exec-out: exec-out always exits 0, so a missing file would read as cat's error text.
    r = subprocess.run([ADB, "shell", f"cat {q(f'{root}/{rel}')}"], capture_output=True, timeout=60)
    return r.stdout.decode("utf-8", errors="replace") if r.returncode == 0 else None


def phone_rm(rel: str, root: str = PHONE_DIR) -> None:
    sh(f"rm -f {q(f'{root}/{rel}')}")


def phone_mv(a: str, b: str) -> None:
    sh(f"mv {q(f'{PHONE_DIR}/{a}')} {q(f'{PHONE_DIR}/{b}')}")


def phone_list(root: str = PHONE_DIR) -> set[str]:
    # One string: adb joins its arguments with spaces for the device's shell.
    out = subprocess.run([ADB, "exec-out", f"cd {q(root)} && find . -type f"], capture_output=True, timeout=120)
    names = {line[2:] for line in out.stdout.decode("utf-8", errors="replace").splitlines() if line.startswith("./")}
    return {n for n in names if n.split("/", 1)[0] not in CORE_DIRS and not core_temp(n)}


def core_temp(rel: str) -> bool:
    """The core's temporary file of a pull (`.syncthing.<name>.tmp`): never scanned, kept for a failed pull
    until KeepTemporariesH (long_name's failed conflict move leaves one)."""
    base = rel.rsplit("/", 1)[-1]
    return base.startswith(".syncthing.") and base.endswith(".tmp")


def peer_write(peer: Peer, rel: str, text: str, folder: Path | None = None) -> None:
    p = (folder or peer.folder) / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def peer_read(peer: Peer, rel: str) -> str | None:
    p = peer.folder / rel
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else None


def peer_list(peer: Peer, folder: Path | None = None) -> set[str]:
    root = folder or peer.folder
    return {str(r) for p in root.rglob("*") if p.is_file() and (r := p.relative_to(root)).parts[0] not in CORE_DIRS
            and not core_temp(str(r))}


def copies(names: set[str], of: str | None = None) -> set[str]:
    """The conflict copies among [names]; with [of], only those of that file."""
    found = set()
    for n in names:
        base = n.rsplit("/", 1)[-1]
        if MARK not in base:
            continue
        if of is None:
            found.add(n)
            continue
        m = COPY.match(base)
        d = n.rsplit("/", 1)[0] + "/" if "/" in n else ""
        if m and d + m.group(1) + m.group(5) == of:
            found.add(n)
    return found


# ---- the phone core and the app's screens ------------------------------------------------------

class Phone(PhoneCore):
    """The phone core's REST API; connects again after the app restarted (new port and login)."""

    def call(self, method: str, path: str, body=None):
        try:
            return super().call(method, path, body)
        except (OSError, ValueError):
            self.connect()
            return super().call(method, path, body)

    def scan(self, folder: str = FOLDER) -> None:
        self.call("POST", f"rest/db/scan?folder={folder}")

    def patch_folder(self, folder: str, values: dict) -> None:
        self.call("PATCH", f"rest/config/folders/{folder}", values)


def ui_nodes() -> list[dict]:
    """The screen's nodes as uiautomator sees them (text, content-desc, bounds); retried while the screen moves."""
    for _ in range(5):
        out = sh("uiautomator dump /sdcard/scenarios-ui.xml", check=False)
        if "dumped to" in out:
            xml = adb("exec-out", "cat", "/sdcard/scenarios-ui.xml")
            return [n.attrib for n in ET.fromstring(xml).iter("node")]
        time.sleep(1)
    raise RuntimeError(f"uiautomator dump: {out}")


def ui_has(pattern: str) -> list[str]:
    """Texts on screen matching the regex [pattern]."""
    return [t for n in ui_nodes() for t in (n.get("text", ""), n.get("content-desc", "")) if t and re.search(pattern, t)]


def ui_tap(label: str, timeout: float = 30) -> None:
    """Taps the node whose whole text or description is [label] (the last one: a dialog's button over the screen's)."""
    end = time.monotonic() + timeout
    while True:
        hits = [n for n in ui_nodes() if label in (n.get("text"), n.get("content-desc"))]
        if hits:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", hits[-1]["bounds"]))
            sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            time.sleep(1)
            return
        if time.monotonic() > end:
            raise RuntimeError(f"no {label!r} on screen")
        time.sleep(1)


def folders_tab() -> None:
    run.open_app()
    ui_tap("Folders")


def shown_conflicts() -> int:
    """The conflict count on the folder cards, summed ("12 conflicts" in a card's details; 0 when none)."""
    folders_tab()
    return sum(int(re.sub(r"\D", "", m.group(1))) for t in ui_has(r"conflict")
               for m in [re.search(r"([\d.,\u00a0\u202f]+)[\u00a0 ]conflicts?\b", t)] if m)


def expect_shown(n: int, timeout: float = 90) -> None:
    eventually(f"the app shows {n} conflicts", timeout, lambda: shown_conflicts() == n)


# ---- devices apart and together ----------------------------------------------------------------

class World:
    def __init__(self, a, out: Path) -> None:
        self.a, self.out = a, out
        self.home = Path(tempfile.mkdtemp(prefix="mysyncthing-scenarios-"))
        (self.home / "a").mkdir()
        self.peer = Peer(a.peer.resolve(), self.home / "a", out.resolve())
        self.b: Peer | None = None
        self.core = Phone()
        self.phone = ""

    def peers(self) -> list[Peer]:
        return [self.peer] + ([self.b] if self.b else [])

    def apart(self) -> None:
        """Every link paused from the peers' side; the phone's own settings stay as they are."""
        for p in self.peers():
            for other in [self.phone] + [o.id for o in self.peers() if o is not p]:
                p.call("POST", f"rest/system/pause?device={other}")
        eventually("devices apart", 60, lambda: not any(p.connected(self.phone) for p in self.peers()))

    def together(self, folders: tuple[str, ...] = (FOLDER,), settle: bool = True) -> None:
        for p in self.peers():
            for other in [self.phone] + [o.id for o in self.peers() if o is not p]:
                p.call("POST", f"rest/system/resume?device={other}")
        eventually("devices together", 120, lambda: all(p.connected(self.phone) for p in self.peers()))
        if settle:
            self.settle(folders)

    def scan_all(self, folders: tuple[str, ...] = (FOLDER,)) -> None:
        for f in folders:
            self.core.scan(f)
            for p in self.peers():
                p.call("POST", f"rest/db/scan?folder={f}")

    def settle(self, folders: tuple[str, ...] = (FOLDER,), timeout: float = 240) -> None:
        """Until every side has the same files, nothing to pull and all idle, twice in a row 5 s apart.
        Scans each round: the core deletes copies past maxConflicts without scanning them."""
        streak = [0]

        def agreed() -> bool:
            self.scan_all(folders)
            time.sleep(5)
            ok = all(self.agree(f) for f in folders)
            streak[0] = streak[0] + 1 if ok else 0
            return streak[0] >= 2

        eventually(f"all sides agree on {', '.join(folders)}", timeout, agreed)

    def agree(self, folder: str) -> bool:
        if folder != FOLDER:  # send-only and receive-only folders differ on purpose: their scenarios judge them
            return all(p.call("GET", f"rest/db/status?folder={folder}")["state"] == "idle" for p in self.peers()) \
                and self.core.call("GET", f"rest/db/status?folder={folder}")["state"] == "idle"
        st = self.core.call("GET", f"rest/db/status?folder={folder}")
        if st["state"] != "idle" or st["needTotalItems"]:
            return False
        names = phone_list()
        for p in self.peers():
            pst = p.call("GET", f"rest/db/status?folder={folder}")
            if pst["state"] != "idle" or pst["needTotalItems"] or peer_list(p) != names:
                return False
            if p.call("GET", f"rest/db/completion?folder={folder}&device={self.phone}")["completion"] != 100:
                return False
        return True

    def base(self, files: dict[str, str]) -> None:
        """Files written on the peer and synced everywhere: the common start of a conflict."""
        for rel, text in files.items():
            peer_write(self.peer, rel, text)
        self.settle()
        for rel, text in files.items():
            if phone_read(rel) != text:
                raise RuntimeError(f"{rel} not on the phone after the start")


def short(device_id: str) -> str:
    return device_id.replace("-", "")[:7]


def same_on_all(w: World, rel: str) -> str | None:
    texts = {phone_read(rel)} | {peer_read(p, rel) for p in w.peers()}
    if len(texts) != 1:
        fail(f"{rel} differs between the sides: {sorted(map(str, texts))}")
        return None
    return texts.pop()


def check_conflict(w: World, rel: str, edits: set[str], n_copies: int, at_least: bool = False) -> set[str]:
    """[rel] the same everywhere, [n_copies] copies of it ([at_least] that many), and file plus copies hold exactly [edits]."""
    names = phone_list()
    cs = copies(names, of=rel)
    if len(cs) != n_copies and not (at_least and len(cs) > n_copies):
        fail(f"{rel}: {len(cs)} copies, expected {'at least ' if at_least else ''}{n_copies}: {sorted(cs)}")
    kept = {same_on_all(w, rel)} | {same_on_all(w, c) for c in cs}
    if kept != edits:
        fail(f"{rel}: file and copies hold {sorted(map(str, kept))}, expected {sorted(edits)}")
    return cs


# ---- the scenarios -----------------------------------------------------------------------------

def edit_edit(w: World) -> None:
    w.base({"edit-edit.txt": "start\n"})
    w.apart()
    peer_write(w.peer, "edit-edit.txt", "edited on the peer\n")
    phone_write("edit-edit.txt", "edited on the phone\n")
    w.scan_all()
    w.together()
    check_conflict(w, "edit-edit.txt", {"edited on the peer\n", "edited on the phone\n"}, 1)
    expect_shown(len(copies(phone_list())))


def edit_delete(w: World) -> None:
    """The newer change wins, a deletion too: the core's WinsConflict goes by modification time, and a
    deletion carries the time of the scan that found it. So the order is set, more than a second apart
    (a deletion's time has whole seconds), and each side deletes once before and once after the other's
    edit. A deletion that wins keeps the losing edit as a conflict copy."""
    w.base({f"ed-{n}.txt": "start\n" for n in "abcd"})
    w.apart()
    (w.peer.folder / "ed-a.txt").unlink()  # deleted first, edited later: the edit wins
    phone_rm("ed-c.txt")
    w.scan_all()
    time.sleep(2)
    phone_write("ed-a.txt", "edited on the phone\n")
    phone_write("ed-b.txt", "edited on the phone\n")
    peer_write(w.peer, "ed-c.txt", "edited on the peer\n")
    peer_write(w.peer, "ed-d.txt", "edited on the peer\n")
    w.scan_all()
    time.sleep(2)
    (w.peer.folder / "ed-b.txt").unlink()  # edited first, deleted later: the deletion wins, the edit is a copy
    phone_rm("ed-d.txt")
    w.scan_all()
    w.together()
    check_conflict(w, "ed-a.txt", {"edited on the phone\n"}, 0)
    check_conflict(w, "ed-c.txt", {"edited on the peer\n"}, 0)
    for rel, edit in (("ed-b.txt", "edited on the phone\n"), ("ed-d.txt", "edited on the peer\n")):
        if same_on_all(w, rel) is not None:
            fail(f"{rel}: still there, though its deletion is the newer change")
        cs = copies(phone_list(), of=rel)
        if len(cs) != 1 or {same_on_all(w, c) for c in cs} != {edit}:
            fail(f"{rel}: copies {sorted(cs)}, expected one holding {edit!r}")
    expect_shown(len(copies(phone_list())))


def both_new(w: World) -> None:
    w.apart()
    peer_write(w.peer, "both-new.txt", "new on the peer\n")
    phone_write("both-new.txt", "new on the phone\n")
    w.scan_all()
    w.together()
    check_conflict(w, "both-new.txt", {"new on the peer\n", "new on the phone\n"}, 1)
    expect_shown(len(copies(phone_list())))


def rename_edit(w: World) -> None:
    w.base({"rename-me.txt": "start\n"})
    w.apart()
    phone_mv("rename-me.txt", "renamed.txt")
    peer_write(w.peer, "rename-me.txt", "edited on the peer\n")
    w.scan_all()
    w.together()
    check_conflict(w, "rename-me.txt", {"edited on the peer\n"}, 0)
    check_conflict(w, "renamed.txt", {"start\n"}, 0)


ODD = ["sub/deep/nested.txt", "space name.txt", "Grüße ñ ç.txt", "日本語.txt", "emoji 😀.md", "noext",
       ".dotfile", "two.dots.tar.gz", "UPPER.TXT", "sub/other dir/x.y"]


def odd_names(w: World) -> None:
    w.base({n: "start\n" for n in ODD})
    w.apart()
    for n in ODD:
        peer_write(w.peer, n, f"peer {n}\n")
        phone_write(n, f"phone {n}\n")
    w.scan_all()
    w.together()
    for n in ODD:
        check_conflict(w, n, {f"peer {n}\n", f"phone {n}\n"}, 1)
    expect_shown(len(copies(phone_list())))


def copy_deleted(w: World) -> None:
    cs = sorted(copies(phone_list()))
    if len(cs) < 2:
        raise RuntimeError(f"needs 2 copies from the scenarios before, has {len(cs)}")
    here, there = cs[0], cs[1]
    phone_rm(here)
    w.core.scan()
    (w.peer.folder / there).unlink()
    w.settle()
    left = copies(phone_list())
    if here in left or there in left or copies(peer_list(w.peer)) != left:
        fail(f"copies after deleting {here} here and {there} there: phone {sorted(left)}, peer {sorted(copies(peer_list(w.peer)))}")
    # Trash can versioning (pairAndSync) keeps the one deleted on the peer under .stversions (a deletion
    # made on the phone itself is not versioned): on disk in the folder, but not counted.
    kept = sh(f"find {PHONE_DIR}/.stversions -type f", check=False)
    if COPY.match(there.rsplit("/", 1)[-1]).group(1) not in kept:
        fail(f"{there} not in the phone's Trash can after its deletion on the peer: {kept!r}")
    expect_shown(len(left))


def copy_conflict(w: World) -> None:
    cs = sorted(copies(phone_list()))
    if not cs:
        raise RuntimeError("needs a copy from the scenarios before")
    c = cs[0]
    w.apart()
    peer_write(w.peer, c, "copy edited on the peer\n")
    phone_write(c, "copy edited on the phone\n")
    w.scan_all()
    w.together()
    names = phone_list()
    if nested := [n for n in names if n.count(MARK) > 1]:
        fail(f"a copy of a copy: {nested}")
    same_on_all(w, c)
    expect_shown(len(copies(names)))


def login_rotates(w: World) -> None:
    """The phone core's web GUI login (API key, user, password) is new at every start; the old key is refused."""
    def login() -> tuple[str, str, str]:
        gui = ET.fromstring(private("files/syncthing/config.xml")).find("gui")
        return gui.findtext("apikey") or "", gui.findtext("user") or "", gui.findtext("password") or ""

    w.core.connect()
    before = login()
    sh(f"am force-stop {APP}")
    run.open_app()
    eventually("a new login after the restart", 90, lambda: login()[0] not in ("", before[0]))
    after = login()
    for what, old, new in zip(("API key", "user", "password hash"), before, after, strict=True):
        if old == new:
            fail(f"web GUI {what} unchanged after a restart")
    w.core.connect()
    req = urllib.request.Request(f"http://127.0.0.1:{PHONE_API_PORT}/rest/system/ping", headers={"X-API-Key": before[0]})
    try:
        urllib.request.urlopen(req, timeout=10)
        fail("the old API key still works after a restart")
    except urllib.error.HTTPError as e:
        if e.code not in (401, 403):
            fail(f"old API key: HTTP {e.code}, expected 401 or 403")
    eventually("peer connected after the restart", 120, lambda: w.peer.connected(w.phone))


def max_conflicts(w: World) -> None:
    """With the phone's watcher off, deletions of pruned copies reach the app only through its own checks."""
    w.core.patch_folder(FOLDER, {"fsWatcherEnabled": False})
    try:
        w.base({"many.txt": "start\n"})
        others = len(copies(phone_list())) - len(copies(phone_list(), of="many.txt"))
        for i in range(12):
            w.apart()
            peer_write(w.peer, "many.txt", f"peer {i}\n")
            phone_write("many.txt", f"phone {i}\n")
            w.scan_all()
            w.together()
            time.sleep(1)  # copy names have whole seconds
        n = len(copies(phone_list(), of="many.txt"))
        if n != 10:
            fail(f"many.txt: {n} copies after 12 conflicts, expected 10 (maxConflicts default)")
        if copies(peer_list(w.peer), of="many.txt") != copies(phone_list(), of="many.txt"):
            fail("many.txt: the copies differ between phone and peer")
        expect_shown(others + n, timeout=180)
    finally:
        w.core.patch_folder(FOLDER, {"fsWatcherEnabled": True})


def seeded_copies(w: World) -> None:
    before = len(copies(phone_list()))
    names = [f"seeded/s{i:03d}.sync-conflict-20261106-120000-{FAKE_SHORT}.txt" for i in range(120)]
    for n in names[:12]:
        peer_write(w.peer, n, "seeded copy\n")
    w.settle()
    expect_shown(before + 12)
    for n in names[12:]:
        peer_write(w.peer, n, "seeded copy\n")
    w.settle()
    expect_shown(before + 120)
    shutil.rmtree(w.peer.folder / "seeded")
    w.settle()
    expect_shown(before)


def ignored_copy(w: World) -> None:
    """An ignore pattern on the phone hides the copies from the core; the app's walk still counts them."""
    cs = sorted(copies(phone_list()))
    if not cs:
        raise RuntimeError("needs a copy from the scenarios before")
    w.core.call("POST", f"rest/db/ignores?folder={FOLDER}", {"ignore": ["*.sync-conflict-*"]})
    try:
        w.core.scan()
        time.sleep(10)
        expect_shown(len(cs))
        phone_rm(cs[0])
        w.core.scan()
        expect_shown(len(cs) - 1, timeout=180)
    finally:
        w.core.call("POST", f"rest/db/ignores?folder={FOLDER}", {"ignore": []})
    w.settle()


def three_way(w: World) -> None:
    (w.home / "b").mkdir()
    b = Peer(w.a.peer.resolve(), w.home / "b", w.out.resolve(), gui_port=PEER_B_GUI, listen_port=PEER_B_LISTEN, name="peer-b", seeded=False)
    b.start()
    w.b = b
    a = w.peer
    a.call("POST", "rest/config/devices", {"deviceID": b.id, "name": "peer-b", "addresses": [f"tcp://127.0.0.1:{PEER_B_LISTEN}"]})
    b.call("POST", "rest/config/devices", {"deviceID": a.id, "name": "peer", "addresses": [f"tcp://127.0.0.1:{run.PEER_LISTEN}"]})
    b.call("POST", "rest/config/devices", {"deviceID": w.phone, "name": "e2e-phone", "addresses": [f"tcp://127.0.0.1:{FORWARD_PORT}"]})
    w.core.call("POST", "rest/config/devices", {"deviceID": b.id, "name": "peer-b", "addresses": ["dynamic"]})
    devices = [{"deviceID": d} for d in (a.id, b.id, w.phone)]
    b.call("POST", "rest/config/folders", {"id": FOLDER, "label": run.LABEL, "path": str(b.folder), "fsWatcherEnabled": False,
                                           "rescanIntervalS": 3600, "devices": devices})
    for side in (a, w.core):
        f = side.call("GET", f"rest/config/folders/{FOLDER}")
        f["devices"] = f["devices"] + [{"deviceID": b.id}]
        side.call("PUT", f"rest/config/folders/{FOLDER}", f)
    eventually("peer-b connected to the phone and the peer", 120, lambda: b.connected(w.phone) and b.connected(a.id))
    w.base({"three.txt": "start\n"})
    w.apart()
    edits = {"edited on the phone\n", "edited on the peer\n", "edited on peer-b\n"}
    phone_write("three.txt", "edited on the phone\n")
    time.sleep(1.1)
    peer_write(a, "three.txt", "edited on the peer\n")
    time.sleep(1.1)
    peer_write(b, "three.txt", "edited on peer-b\n")
    w.scan_all()
    w.together()
    # Each device that holds a losing edit makes its own copy; one that took a version that then loses
    # again makes another: two or more copies, holding the two losing edits between them.
    cs = check_conflict(w, "three.txt", edits, 2, at_least=True)
    log(f"three_way: {len(cs)} copies: {sorted(cs)}")
    ids = {short(d) for d in (w.phone, a.id, b.id)}
    for c in cs:
        m = COPY.match(c)
        if not m or m.group(4) not in ids:
            fail(f"copy {c}: short ID not one of the three devices")
    expect_shown(len(copies(phone_list())))


def leave_three_way(w: World) -> None:
    """Peer B leaves: the phone and the first peer forget it, so the later scenarios have two sides."""
    if not w.b:
        return
    b, w.b = w.b, None
    for side in (w.peer, w.core):
        f = side.call("GET", f"rest/config/folders/{FOLDER}")
        f["devices"] = [d for d in f["devices"] if d["deviceID"] != b.id]
        side.call("PUT", f"rest/config/folders/{FOLDER}", f)
        side.call("DELETE", f"rest/config/devices/{b.id}")
    b.stop()


def shared_folder(w: World, fid: str, phone_dir: str, kind: str) -> Path:
    """A folder shared by the peer and taken on the phone through its core, of [kind]."""
    d = w.peer.dir / fid
    d.mkdir(exist_ok=True)
    w.peer.call("POST", "rest/config/folders", {"id": fid, "label": fid, "path": str(d), "fsWatcherEnabled": False,
                                                 "rescanIntervalS": 3600, "devices": [{"deviceID": w.phone}]})
    sh(f"mkdir -p {q(phone_dir)}")
    w.core.call("POST", "rest/config/folders", {"id": fid, "label": fid, "path": phone_dir, "type": kind,
                                                "devices": [{"deviceID": w.peer.id}]})
    return d


def tap_folder_button(label: str, button: str, confirm: str) -> None:
    folders_tab()
    ui_tap(label)
    ui_tap(button, timeout=60)
    ui_tap(confirm)
    sh("input keyevent KEYCODE_BACK")


def send_only(w: World) -> None:
    d = shared_folder(w, SO_ID, SO_DIR, "sendonly")
    phone_write("so.txt", "from the phone\n", root=SO_DIR)
    w.core.scan(SO_ID)
    eventually("so.txt on the peer", 120, lambda: (d / "so.txt").is_file() and (d / "so.txt").read_text() == "from the phone\n")
    peer_write(w.peer, "so.txt", "changed on the peer\n", folder=d)
    w.peer.call("POST", f"rest/db/scan?folder={SO_ID}")
    eventually("the phone sees the peer's change", 120,
               lambda: w.core.call("GET", f"rest/db/status?folder={SO_ID}")["needTotalItems"] == 1)
    if phone_read("so.txt", root=SO_DIR) != "from the phone\n":
        fail("send-only folder took the peer's change")
    tap_folder_button(SO_ID, "Override…", "Override")
    eventually("the phone's so.txt back on the peer", 120, lambda: (d / "so.txt").read_text() == "from the phone\n")


def receive_only(w: World) -> None:
    d = shared_folder(w, RO_ID, RO_DIR, "receiveonly")
    peer_write(w.peer, "ro.txt", "from the peer\n", folder=d)
    w.peer.call("POST", f"rest/db/scan?folder={RO_ID}")
    eventually("ro.txt on the phone", 120, lambda: phone_read("ro.txt", root=RO_DIR) == "from the peer\n")
    phone_write("ro.txt", "changed on the phone\n", root=RO_DIR)
    phone_write("extra.txt", "new on the phone\n", root=RO_DIR)
    w.core.scan(RO_ID)
    eventually("the phone counts 2 local changes", 120,
               lambda: w.core.call("GET", f"rest/db/status?folder={RO_ID}")["receiveOnlyTotalItems"] == 2)
    if (d / "ro.txt").read_text() != "from the peer\n" or (d / "extra.txt").exists():
        fail("receive-only folder sent its local changes")
    tap_folder_button(RO_ID, "Revert…", "Revert")
    eventually("ro.txt reverted on the phone", 120, lambda: phone_read("ro.txt", root=RO_DIR) == "from the peer\n")
    eventually("extra.txt gone from the phone", 120, lambda: phone_read("extra.txt", root=RO_DIR) is None)


def long_name(w: World) -> None:
    """240 bytes plus ".sync-conflict-…" passes the 255-byte limit of a name: the core cannot rename the
    loser. Observed only: what the folder shows, and that both sides keep a file."""
    name = "L" * 236 + ".txt"
    w.base({name: "start\n"})
    w.apart()
    peer_write(w.peer, name, "long, edited on the peer\n")
    phone_write(name, "long, edited on the phone\n")
    w.scan_all()
    w.together(settle=False)
    time.sleep(60)
    st = w.core.call("GET", f"rest/db/status?folder={FOLDER}")
    errors = w.core.call("GET", f"rest/folder/errors?folder={FOLDER}")
    log(f"long_name: phone state {st['state']}, pull errors {errors.get('errors')}, need {st['needTotalItems']}; "
        f"phone has {phone_read(name)!r}, peer has {peer_read(w.peer, name)!r}")
    (w.out / "long_name.txt").write_text(f"status {st}\nerrors {errors}\n")
    folders_tab()
    with (w.out / "long_name.png").open("wb") as f:  # the folder card as it shows the problem
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, timeout=60, check=False)
    # Leave the folder clean for any later run: the long file goes on both sides.
    (w.peer.folder / name).unlink(missing_ok=True)
    phone_rm(name)
    w.settle()


# ---- stress (PC only) --------------------------------------------------------------------------

def app_memory() -> str:
    """The app's total PSS and the core's goroutines, for the summary: a leak shows as growth."""
    pss = next((line.split()[2] for line in sh(f"dumpsys meminfo {APP}", check=False).splitlines()
                if line.strip().startswith("TOTAL PSS:")), "?")
    return f"PSS {pss} kB"


def goroutines(w: World) -> int | None:
    try:
        return w.core.call("GET", "rest/system/status").get("goroutines")
    except (OSError, ValueError):
        return None


def data_free_mb() -> int:
    """Free space where the phone's folders are."""
    return int(sh("df -k /sdcard | tail -n 1").split()[3]) // 1024


def phone_sha(rel: str) -> str:
    return sh(f"sha256sum {q(f'{PHONE_DIR}/{rel}')}").split()[0]


def peer_sha(peer: Peer, rel: str) -> str:
    h = hashlib.sha256()
    with (peer.folder / rel).open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def big_peer_file(peer: Peer, rel: str, mb: int) -> None:
    """[mb] MB of random bytes: nothing the block hashing can share with another file."""
    p = peer.folder / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open("wb") as f:
        for _ in range(mb):
            f.write(os.urandom(1 << 20))


def remove_everywhere(w: World, rel: str) -> None:
    """Deleted on the peer and synced; the phone's Trash can copy goes too, so the disk stays free."""
    target = w.peer.folder / rel
    if target.is_dir():
        shutil.rmtree(target)
    else:
        target.unlink(missing_ok=True)
    w.settle(timeout=1200)
    sh(f"rm -rf {q(f'{PHONE_DIR}/.stversions/{rel}')}", check=False)


def many_files(w: World) -> None:
    """C1: 5,000 small files (1-10 kB, about 27 MB in 50 directories) from the peer."""
    rnd = random.Random(5000)
    for i in range(5000):
        peer_write(w.peer, f"many/d{i % 50:02d}/f{i:04d}.txt", "x" * rnd.randint(1000, 10_000))
    began = time.monotonic()
    w.settle(timeout=1800)
    log(f"many_files: 5,000 files on the phone after {time.monotonic() - began:.0f} s")
    folders_tab()  # the app answers while the folder holds them (an ANR is judged at the end)
    remove_everywhere(w, "many")


def big_file(w: World) -> None:
    """C2: one large file, paused halfway by the phone and resumed: complete and identical."""
    mb = min(1024, data_free_mb() // 3)
    if mb < 200:
        raise RuntimeError(f"only {data_free_mb()} MB free on the emulator")
    big_peer_file(w.peer, "big.bin", mb)
    send_limit(w, 20_000)  # about 50 s for 1 GB: long enough to catch it halfway
    w.peer.call("POST", f"rest/db/scan?folder={FOLDER}")
    eventually("big.bin a third through", 900, lambda: 30 <= (transfer_percent(w, "big.bin") or 0) <= 70)
    folders_tab()
    with (w.out / "big_file-progress.png").open("wb") as f:  # the card's progress and rate, for the report
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, timeout=60, check=False)
    w.core.call("POST", f"rest/system/pause?device={w.peer.id}")
    eventually("the peer gone while paused on the phone", 30, lambda: not w.peer.connected(w.phone))
    time.sleep(20)
    if sh(f"test -f {PHONE_DIR}/big.bin && echo yes", check=False) == "yes":
        fail("big.bin finished while the peer was paused on the phone")
    w.core.call("POST", f"rest/system/resume?device={w.peer.id}")
    send_limit(w, 0)
    began = time.monotonic()
    w.settle(timeout=1800)
    log(f"big_file: {mb} MB, the rest after resuming took {time.monotonic() - began:.0f} s")
    if phone_sha("big.bin") != peer_sha(w.peer, "big.bin"):
        fail("big.bin differs between phone and peer")
    remove_everywhere(w, "big.bin")


def send_limit(w: World, kbps: int) -> None:
    """The peer's send rate, in kB/s (0: no limit). Loopback counts as LAN, which the core leaves
    unlimited unless limitBandwidthInLan is on (as upgrade.py does)."""
    w.peer.call("PATCH", "rest/config/options", {"maxSendKbps": kbps, "limitBandwidthInLan": kbps > 0})


def transfer_percent(w: World, rel: str) -> float | None:
    """How far the phone's pull of [rel] is, in percent: from the core's latest DownloadProgress event (every
    5 s while pulling; the folder's inSyncBytes counts whole files only). None when no pull of it is reported."""
    events = w.core.call("GET", "rest/events?events=DownloadProgress&limit=1&timeout=6") or []
    for e in events:
        p = (e.get("data") or {}).get(FOLDER, {}).get(rel)
        if p and p.get("bytesTotal"):
            return 100.0 * p["bytesDone"] / p["bytesTotal"]
    return None


def conflicts_200(w: World) -> None:
    """C3: 200 files in conflict at once."""
    before = len(copies(phone_list()))
    names = [f"c200/f{i:03d}.txt" for i in range(200)]
    w.base({n: "start\n" for n in names})
    w.apart()
    for n in names:
        peer_write(w.peer, n, f"peer {n}\n")
    sh(f"cd {PHONE_DIR}/c200 && for i in $(seq -w 0 199); do echo \"phone c200/f$i.txt\" > f$i.txt; done")
    w.scan_all()
    w.together()
    n = len(copies(phone_list(), None)) - before
    if n != 200:
        fail(f"c200: {n} new copies, expected 200")
    expect_shown(before + n, timeout=180)
    folders_tab()
    with (w.out / "conflicts_200.png").open("wb") as f:  # the card with its count and the badge's "99+"
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, timeout=60, check=False)
    remove_everywhere(w, "c200")
    expect_shown(before)


def churn_both(w: World) -> None:
    """C4: 10 min connected, the peer rewrites 100 files every 10 s, the phone 20 of them every 30 s.
    The edits that cross make conflicts; at the end the app's count equals the copies on disk."""
    w.base({f"churn/f{i:03d}.txt": "start\n" for i in range(100)})
    end = time.monotonic() + 600
    tick = 0
    while time.monotonic() < end:
        tick += 1
        for i in range(100):
            peer_write(w.peer, f"churn/f{i:03d}.txt", f"peer {tick}\n")
        w.peer.call("POST", f"rest/db/scan?folder={FOLDER}")
        if tick % 3 == 0:
            sh(f"cd {PHONE_DIR}/churn && for i in $(seq -w 0 19); do echo \"phone {tick}\" > f0$i.txt; done")
            w.core.scan()
        time.sleep(10)
    w.settle(timeout=600)
    on_disk = len(copies(phone_list()))
    log(f"churn_both: {len(copies(phone_list(), None))} copies after 10 min; {app_memory()}")
    expect_shown(on_disk, timeout=180)
    remove_everywhere(w, "churn")


def pause_storm(w: World) -> None:
    """C5: the peer device and the folder paused and resumed 50 times each in about 2 min through the
    core, the app sent to the back and brought forward between, then the folder's own button 10 times."""
    for i in range(50):
        w.core.call("POST", f"rest/system/{'pause' if i % 2 == 0 else 'resume'}?device={w.peer.id}")
        w.core.patch_folder(FOLDER, {"paused": i % 2 == 0})
        if i % 10 == 9:
            run.go_home()
            run.open_app()
        time.sleep(1)
    w.core.call("POST", f"rest/system/resume?device={w.peer.id}")
    w.core.patch_folder(FOLDER, {"paused": False})
    folders_tab()
    ui_tap(run.LABEL)
    for i in range(10):
        ui_tap("Pause" if i % 2 == 0 else "Resume")
    sh("input keyevent KEYCODE_BACK")
    eventually("folder not paused at the end", 30, lambda: not w.core.call("GET", f"rest/config/folders/{FOLDER}")["paused"])
    eventually("peer connected after the storm", 120, lambda: w.peer.connected(w.phone))
    peer_write(w.peer, "after-storm.txt", "after the storm\n")
    w.settle()


def network_flaps(w: World) -> None:
    """C6: airplane mode on and off every 20 s while a 300 MB file arrives; the app's run conditions
    stop and start syncing each time (the test link itself runs over adb, not the radio)."""
    big_peer_file(w.peer, "flaps.bin", 300)
    send_limit(w, 2_000)  # about 150 s: the flaps happen during the transfer
    w.peer.call("POST", f"rest/db/scan?folder={FOLDER}")
    try:
        for i in range(8):
            sh(f"cmd connectivity airplane-mode {'enable' if i % 2 == 0 else 'disable'}")
            time.sleep(20)
    finally:
        sh("cmd connectivity airplane-mode disable", check=False)
        sh("svc wifi enable", check=False)
        send_limit(w, 0)
    w.settle(timeout=1800)
    if phone_sha("flaps.bin") != peer_sha(w.peer, "flaps.bin"):
        fail("flaps.bin differs between phone and peer")
    remove_everywhere(w, "flaps.bin")


def ensure_copy(w: World) -> int:
    """At least one copy in the folder (a fresh conflict when none is left); the count."""
    if not copies(phone_list()):
        both_new(w)
    return len(copies(phone_list()))


def count_after_kill(w: World) -> None:
    """B2: the app's process killed in the background; the service comes back and counts the copies again."""
    n = ensure_copy(w)
    run.go_home()
    pid = run.app_pid()
    sh(f"run-as {APP} kill -9 {pid}")
    eventually("service back after the kill", 120, lambda: run.app_pid() not in ("", pid))
    expect_shown(n)


def count_after_reboot(w: World) -> None:
    """B2: the emulator restarted; the app starts at boot and counts the copies again."""
    n = ensure_copy(w)
    adb("reboot", check=False)
    time.sleep(10)
    adb("wait-for-device", timeout=300)
    eventually("booted", 300, lambda: sh("getprop sys.boot_completed", check=False) == "1")
    sh("svc power stayon true")
    run.Device.wake()
    adb("reverse", f"tcp:{run.GUI_PORT}", f"tcp:{run.GUI_PORT}")
    adb("forward", f"tcp:{FORWARD_PORT}", f"tcp:{run.PHONE_PORT}")
    eventually("the app's service started at boot", 180, lambda: run.app_pid() != "")
    eventually("peer connected after the reboot", 180, lambda: w.peer.connected(w.phone))
    expect_shown(n)


SCENARIOS = {f.__name__: f for f in (edit_edit, edit_delete, both_new, rename_edit, odd_names, copy_deleted, copy_conflict,
                                     login_rotates, max_conflicts, seeded_copies, ignored_copy, three_way, send_only,
                                     receive_only, long_name, many_files, big_file, conflicts_200, churn_both, pause_storm,
                                     network_flaps, count_after_kill, count_after_reboot)}


# ---- main --------------------------------------------------------------------------------------

def no_user_syncthing() -> None:
    """A Syncthing of this machine's user must not run: its ports and discovery are the user's."""
    r = subprocess.run(["pgrep", "-a", "-x", "syncthing"], capture_output=True, text=True)
    if r.stdout.strip():
        sys.exit(f"a Syncthing runs on this machine; stop it first (not touched by this script):\n{r.stdout}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--peer", type=Path, required=True)
    ap.add_argument("--apk", type=Path, required=True)
    ap.add_argument("--test-apk", type=Path, required=True)
    ap.add_argument("--only", default="all", help="all, fast, slow, stress, or scenario names, comma-separated")
    ap.add_argument("--out", type=Path, default=Path("build/scenarios"))
    a = ap.parse_args()
    names = [n for part in a.only.split(",") for n in GROUPS.get(part, [part])]
    if unknown := [n for n in names if n not in SCENARIOS]:
        sys.exit(f"unknown scenarios: {unknown}; known: {list(SCENARIOS)}")
    if Path(a.peer).name == "syncthing":
        sys.exit("--peer must be the e2e build (go/build/e2e-peer), not a syncthing binary")
    a.out.mkdir(parents=True, exist_ok=True)
    no_user_syncthing()
    run.require_emulator()
    run.prepare(a.apk, a.test_apk)
    sh("svc power stayon true")
    adb("logcat", "-c")
    w = World(a, a.out)
    results: list[tuple[str, str, float, str]] = []
    try:
        w.peer.start()
        if not run.phase("pairAndSync", w.peer, a.out):
            sys.exit("pairing failed: " + "; ".join(run.failures))
        w.phone = run.phone_id(w.peer)
        run.open_app()
        w.core.connect()
        results.append(("(start)", "", 0.0, f"{app_memory()}, {goroutines(w)} goroutines"))
        for name in names:
            log(f"scenario {name}")
            before = len(run.failures)
            began = time.monotonic()
            try:
                SCENARIOS[name](w)
            except Exception as e:  # noqa: BLE001  one scenario's trouble must not stop the rest
                fail(f"{name}: {type(e).__name__}: {e}")
                (a.out / f"{name}-error.txt").write_text(traceback.format_exc())
                try:  # the next scenario starts with every link up
                    w.together()
                except Exception:  # noqa: BLE001
                    pass
            finally:
                if name == "three_way":
                    leave_three_way(w)
            mine = run.failures[before:]
            results.append((name, "ok" if not mine else "FAIL: " + "; ".join(mine), time.monotonic() - began,
                            f"{app_memory()}, {goroutines(w)} goroutines"))
            log(f"scenario {name}: {results[-1][1]}")
    finally:
        for p in w.peers():
            p.stop()
        (a.out / "logcat.txt").write_text(adb("logcat", "-d", check=False, timeout=300))
        shutil.rmtree(w.home, ignore_errors=True)
        for args in (("reverse", "--remove", f"tcp:{run.GUI_PORT}"), ("forward", "--remove", f"tcp:{FORWARD_PORT}"),
                     ("forward", "--remove", f"tcp:{PHONE_API_PORT}")):
            adb(*args, check=False)
        sh("rm -f /sdcard/scenarios-ui.xml", check=False)

    logcat = (a.out / "logcat.txt").read_text(errors="replace")
    crashes, known = run.logcat_app_crashes(logcat)
    native, _ = run.native_crashes(logcat)
    anrs, _ = run.logcat_anrs(logcat)
    lines = ["# Scenarios", "", "| scenario | result | min | app after it |", "|---|---|---|---|"]
    lines += [f"| {n} | {r} | {s / 60:.1f} | {m} |" for n, r, s, m in results]
    lines += ["", f"App crashes: {crashes or 'none'}; known platform: {known or 'none'}; native: {native}; ANRs: {anrs or 'none'}",
              f"Blank screens: {run.blank_screens(logcat)}"]
    (a.out / "summary.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))
    if crashes or native or anrs:
        fail(f"app crashes {crashes}, native {native}, ANRs {anrs}")
    if run.failures:
        sys.exit(f"{len(run.failures)} failures")
    log("all passed")


if __name__ == "__main__":
    main()
