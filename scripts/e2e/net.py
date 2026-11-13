#!/usr/bin/env python3
"""Network tests on the PC: global discovery, a relay, NAT, a slow link and silent cuts, with no real
peer and nothing reached outside this machine. Not part of pc.py: run after big releases or about
every three months (user, 12/11/2026).

The test peer, a discovery server (stdiscosrv) and a relay (strelaysrv), all built from the pinned
core, run in a network namespace at 198.51.100.2 (scripts/e2e/netns.sh, run as root before and after
this script, which runs as the repository's owner). The emulator reaches them through its own NAT;
the peer can reach nothing outside its namespace, so every connection is opened by the phone, as
from a phone on mobile data. The phone core uses only these servers: no public discovery, relay,
STUN or DNS (the emulator runs without DNS, as pc.py's).

Per API: a fresh emulator, the app paired with the peer over loopback by E2eTest's pairAndSync (as
run.py does), then the peer moves into the namespace with its address set to dynamic on both sides:
  discovery  the peer listens on TCP and QUIC only; the phone finds it through the discovery server
             and dials it; 20 MB from the peer and 5 MB from the phone arrive identical
  slow       150±30 ms each way, 1 % loss, 2 Mbit/s: 5 MB arrives identical
  cut        a 2-minute silent cut (100 % loss) in the middle of a 10 MB transfer: it ends identical
  relay      the peer listens on the relay only; after an app restart the phone connects through
             it (type relay-client); 20 MB and 5 MB as above
  relay_cut  an 8-minute silent cut over the relay: the connection drops, comes back through discovery
             and the relay within 5 minutes of the end of the cut, and the transfer ends identical
Results: <out>/summary.md (as pc.py's), logs next to it.

  (root)  scripts/e2e/netns.sh up
  (owner) scripts/e2e/net.py [--apis 35,36,37] [--out build/e2e-net]
  (root)  scripts/e2e/netns.sh down
"""
import argparse
import json
import os
import shutil
import signal
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import pc  # noqa: E402
import run  # noqa: E402
import scenarios  # noqa: E402
from pc import ANDROID, PORT, ROOT, SERIAL, Result, git, log, step  # noqa: E402

NS_IP = "198.51.100.2"
DISCO_PORT, RELAY_PORT, PEER_PORT, PEER_GUI = 8443, 22067, 22001, 8385
RUN = Path("/run/mst-net")
T = ROOT / "build/nettest"
BIN = T / "bin"
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
TEST_APK = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
MIN = 60


# ---- the root helper (netns.sh) ----------------------------------------------------------------

def helper(request: str, timeout: int = 120) -> str:
    """One request to netns.sh's helper; its answer, or an exception with its reason. Never waits forever.
    The id pairs the answer with the request: an answer left for a run that was stopped is skipped."""
    rid = str(time.time_ns() % 10**18)
    try:
        fd = os.open(RUN / "ctl", os.O_WRONLY | os.O_NONBLOCK)  # ENXIO: the helper is not running
        try:
            os.write(fd, f"{rid} {request}\n".encode())
        finally:
            os.close(fd)
    except OSError as e:
        raise RuntimeError("netns.sh's helper does not run; as root: scripts/e2e/netns.sh down, then up") from e

    def late(*_):
        raise TimeoutError(f"netns.sh: no answer to '{request}' within {timeout} s")

    old = signal.signal(signal.SIGALRM, late)
    signal.alarm(timeout)
    try:
        while True:
            with (RUN / "reply").open() as f:
                got, _, answer = f.read().strip().partition(" ")
            if got == rid:
                break
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, old)
    if not answer.startswith("ok"):
        raise RuntimeError(f"netns.sh: {request}: {answer}")
    return answer


def disco_lookup(device: str) -> list[str]:
    """The addresses the test discovery server has for [device]. Lookups need no client certificate; the
    server's own certificate is the one new_identity made, so it is not checked here."""
    ctx = ssl.create_default_context()
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
    try:
        with urllib.request.urlopen(f"https://{NS_IP}:{DISCO_PORT}/?device={device}", context=ctx, timeout=10) as r:
            return json.loads(r.read()).get("addresses", [])
    except urllib.error.HTTPError:
        return []


def preflight() -> None:
    if os.geteuid() == 0:
        sys.exit("run as the repository's owner, not root (only netns.sh runs as root)")
    if not (RUN / "ctl").exists():
        sys.exit("no network namespace: run 'scripts/e2e/netns.sh up' as root first")
    try:
        helper("net clean", timeout=20)  # the helper answers, and the link starts clean
    except (RuntimeError, TimeoutError) as e:
        sys.exit(str(e))
    pc.preflight()


def device_id(home: Path) -> str:
    return subprocess.run([BIN / "st-peer", "device-id", "--home", home], check=True, capture_output=True,
                          text=True, env={**os.environ, "HOME": str(home)}).stdout.strip()


def new_identity(home: Path) -> str:
    """A fresh certificate in [home] (cert.pem, key.pem), as the core makes them; its ID."""
    shutil.rmtree(home, ignore_errors=True)
    subprocess.run([BIN / "st-peer", "generate", "--home", home, "--no-port-probing"], check=True,
                   capture_output=True, env={**os.environ, "HOME": str(home)})
    return device_id(home)


# ---- the peer and the phone --------------------------------------------------------------------

def build(out: Path) -> list[Result]:
    env = dict(os.environ)
    steps = [step("build: app and test APKs", ["./gradlew", "--no-daemon", ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
                  out / "build-app.log", env, 60)]
    if steps[-1].ok:
        version = (ROOT / "go/syncthing.version").read_text().strip()
        ldflags = f"-X github.com/syncthing/syncthing/lib/build.Version={version}"
        BIN.mkdir(parents=True, exist_ok=True)
        steps.append(step("build: peer, discovery server, relay",
                          ["sh", "-c", f'go build -trimpath -tags noupgrade -ldflags "{ldflags}" -o "{BIN}/st-peer" ./cmd/syncthing'
                                       f' && go build -trimpath -o "{BIN}/" ./cmd/stdiscosrv ./cmd/strelaysrv'],
                          out / "build-servers.log", env, 20, cwd=ROOT / "go/syncthing"))
    if changed := pc.dirty():
        (out / "build-check.log").write_text(f"the build changed files of the checkout:\n{changed}\n")
        steps.append(Result("build: checkout unchanged", "FAIL: the build changed files (see build-check.log)", 0.0))
    return steps


class World:
    """One API's run: the paired phone, the peer in the namespace, and the servers' IDs."""

    def __init__(self, out: Path, disco: str, relay: str) -> None:
        self.run, self.sc = run, scenarios
        self.out, self.disco, self.relay = out, disco, relay
        self.disco_url = f"https://{NS_IP}:{DISCO_PORT}/?id={disco}"
        self.relay_url = f"relay://{NS_IP}:{RELAY_PORT}/?id={relay}"
        self.direct = [f"tcp://{NS_IP}:{PEER_PORT}", f"quic://{NS_IP}:{PEER_PORT}"]
        self.core = scenarios.Phone()
        self.peer = None
        self.phone = ""
        self.notes: dict[str, str] = {}

    # The peer, first on loopback for pairing (as run.py), then in the namespace.

    def pair(self) -> bool:
        run = self.run
        run.require_emulator()
        run.prepare(APK, TEST_APK)
        run.adb("logcat", "-G", "64M", check=False)
        run.adb("logcat", "-c")
        shutil.rmtree(T / "peer", ignore_errors=True)
        loop = run.Peer(BIN / "st-peer", T / "peer", self.out)
        try:
            loop.start()
            if not run.phase("pairAndSync", loop, self.out):
                return False
            self.phone = run.phone_id(loop)
        finally:
            loop.stop()
        for args in (("reverse", "--remove", f"tcp:{run.GUI_PORT}"), ("forward", "--remove", f"tcp:{run.FORWARD_PORT}")):
            run.adb(*args, check=False)  # no path between phone and peer but the namespace's
        self.peer = NsPeer(loop)
        self.peer_config()
        helper("start peer")
        self.run.eventually("peer's REST API in the namespace", 60, lambda: self.peer.call("GET", "rest/system/ping"))
        # Before the phone asks: a lookup that finds nothing is cached for a minute, then backs off for longer.
        self.announced("tcp://")
        self.phone_config()
        return True

    def announced(self, scheme: str) -> None:
        """Until the discovery server answers a lookup of the peer with a [scheme] address."""
        self.run.eventually(f"peer announced a {scheme} address", 120,
                            lambda: any(a.startswith(scheme) for a in disco_lookup(self.peer.id)))

    def peer_config(self) -> None:
        """Written before it starts in the namespace: its address, servers and the phone's address."""
        conf = T / "peer/config.xml"
        tree = ET.parse(conf)
        root = tree.getroot()
        gui = root.find("gui")
        gui.find("address").text = f"{NS_IP}:{PEER_GUI}"
        (gui.find("apikey") if gui.find("apikey") is not None else ET.SubElement(gui, "apikey")).text = self.peer.key
        o = root.find("options")
        for tag in ("listenAddress", "globalAnnounceServer"):
            for e in o.findall(tag):
                o.remove(e)
        for addr in self.direct:
            ET.SubElement(o, "listenAddress").text = addr
        ET.SubElement(o, "globalAnnounceServer").text = self.disco_url
        for key, value in {"globalAnnounceEnabled": "true", "localAnnounceEnabled": "false", "relaysEnabled": "true",
                           "natEnabled": "false"}.items():
            o.find(key).text = value
        for d in root.findall("device"):
            if d.get("id") == self.phone:
                for a in d.findall("address"):
                    d.remove(a)
                ET.SubElement(d, "address").text = "dynamic"
        tree.write(conf, encoding="utf-8", xml_declaration=False)

    def phone_config(self) -> None:
        """Only the namespace's servers; the peer found by discovery, not at an address."""
        self.run.open_app()
        self.core.connect()
        self.core.call("PATCH", "rest/config/options", {
            "globalAnnounceServers": [self.disco_url], "globalAnnounceEnabled": True, "localAnnounceEnabled": False,
            "relaysEnabled": True, "natEnabled": False, "crashReportingEnabled": False, "stunServers": [],
            "listenAddresses": ["tcp://0.0.0.0:22000", "quic://0.0.0.0:22000"]})
        self.core.call("PATCH", f"rest/config/devices/{self.peer.id}", {"addresses": ["dynamic"]})
        o = self.core.call("GET", "rest/config/options")
        if o["globalAnnounceServers"] != [self.disco_url] or not o["relaysEnabled"] or o["listenAddresses"][0] == "default":
            raise RuntimeError(f"the phone core did not take the test options: {o}")

    def restart_app(self) -> None:
        """A fresh core: its discovery cache forgets the peer's old addresses."""
        self.run.sh(f"am force-stop {self.run.APP}")
        self.run.open_app()
        self.core.connect(timeout=120)

    # The link.

    def link(self) -> dict:
        return self.core.call("GET", "rest/system/connections")["connections"].get(self.peer.id, {})

    def connected(self, since: float = 0) -> dict:
        """The phone's connection to the peer, made at or after [since] (epoch seconds)."""
        c = self.link()
        if not c.get("connected"):
            return {}
        if since and datetime.fromisoformat(c["startedAt"]).timestamp() < int(since):
            return {}
        return c

    def reconnect(self) -> dict:
        since = time.time()
        self.core.call("POST", f"rest/system/pause?device={self.peer.id}")
        self.run.eventually("phone apart from the peer", 60, lambda: not self.link().get("connected"))
        self.core.call("POST", f"rest/system/resume?device={self.peer.id}")
        return self.wait_link(since, 180)

    def wait_link(self, since: float, timeout: float) -> dict:
        found: list[dict] = []
        self.run.eventually("phone connected to the peer", timeout, lambda: found.append(c) or True if (c := self.connected(since)) else False)
        return found[-1] if found else {}

    def found_by_discovery(self, scheme: str) -> None:
        cache = self.core.call("GET", "rest/system/discovery").get(self.peer.id, {})
        if not any(a.startswith(scheme) and NS_IP in a for a in cache.get("addresses", [])):
            raise RuntimeError(f"the phone's discovery cache has no {scheme} address of the peer: {cache}")

    # Files.

    def from_peer(self, rel: str, mb: int) -> None:
        self.sc.big_peer_file(self.peer, rel, mb)
        self.peer.scan()

    def from_phone(self, rel: str, mb: int) -> None:
        self.run.sh(f"dd if=/dev/urandom of={self.run.PHONE_DIR}/{rel} bs=1048576 count={mb} 2>/dev/null")
        self.core.scan()

    def same(self, rel: str) -> bool:
        return self.sc.phone_sha(rel) == self.sc.peer_sha(self.peer, rel)

    def arrives(self, rel: str, timeout: float) -> float:
        """Seconds until [rel] is identical on both sides."""
        began = time.monotonic()
        if not self.run.eventually(f"{rel} identical on phone and peer", timeout, lambda: self.same(rel)):
            raise RuntimeError(f"{rel} not identical after {timeout:.0f} s")
        return time.monotonic() - began

    def percent(self, rel: str) -> float:
        events = self.core.call("GET", "rest/events?events=DownloadProgress&limit=1&timeout=6") or []
        for e in events:
            p = (e.get("data") or {}).get(self.run.FOLDER, {}).get(rel)
            if p and p.get("bytesTotal"):
                return 100.0 * p["bytesDone"] / p["bytesTotal"]
        return 0.0

    def send_limit(self, kbps: int) -> None:
        self.peer.call("PATCH", "rest/config/options", {"maxSendKbps": kbps, "limitBandwidthInLan": kbps > 0})


class NsPeer(run.Peer):
    """The paired peer, now in the namespace: same home, ID and key; its REST API at NS_IP."""

    def __init__(self, loop: run.Peer) -> None:
        super().__init__(loop.binary, loop.dir, loop.out, PEER_GUI, PEER_PORT)
        self.key, self.id = loop.key, loop.id

    def call(self, method: str, path: str, body=None):
        req = urllib.request.Request(f"http://{NS_IP}:{PEER_GUI}/{path}", method=method,
                                     data=None if body is None else json.dumps(body).encode(),
                                     headers={"X-API-Key": self.key, "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            text = r.read().decode()
        return json.loads(text) if text.strip() else None

    def stop(self) -> None:
        helper("stop peer")


# ---- the scenarios -----------------------------------------------------------------------------

def discovery(w: World) -> None:
    c = w.reconnect()
    if c.get("type") not in ("tcp-client", "quic-client"):
        raise RuntimeError(f"expected a direct connection opened by the phone, got {c.get('type')} {c.get('address')}")
    w.found_by_discovery("tcp://")
    w.from_peer("disco-peer.bin", 20)
    w.from_phone("disco-phone.bin", 5)
    a, b = w.arrives("disco-peer.bin", 300), w.arrives("disco-phone.bin", 300)
    w.notes["discovery"] = f"{c['type']}; 20 MB in {a:.0f} s, 5 MB back in {b:.0f} s"


def slow(w: World) -> None:
    helper("net slow")
    try:
        w.from_peer("slow.bin", 5)
        s = w.arrives("slow.bin", 900)
        w.notes["slow"] = f"5 MB in {s:.0f} s ({5 * 8 / s:.2f} Mbit/s), {w.link().get('type')}"
    finally:
        helper("net clean")


def cut_during_transfer(w: World, rel: str, cut_s: int, must_reconnect: bool) -> str:
    before = w.wait_link(0, 60)
    w.send_limit(200)  # 10 MB in about 50 s: the cut lands in the middle
    try:
        w.from_peer(rel, 10)
        w.run.eventually(f"{rel} under way", 180, lambda: w.percent(rel) >= 15)
        at = w.percent(rel)
        helper("net cut")
        try:
            time.sleep(cut_s)
            if must_reconnect:
                w.run.eventually("the connection dropped during the cut", 60, lambda: not w.link().get("connected"))
        finally:
            helper("net clean")
        ended = time.time()
        if must_reconnect:
            back = w.wait_link(ended, 300)
            again = datetime.fromisoformat(back["startedAt"]).timestamp() - ended
        w.send_limit(0)
        s = w.arrives(rel, 600)
    finally:
        w.send_limit(0)
    kept = w.connected().get("startedAt") == before.get("startedAt")
    how = f"reconnected {max(again, 0):.0f} s after the cut, {back.get('type')}" if must_reconnect else \
        ("same connection survived" if kept else f"reconnected, {w.link().get('type')}")
    return f"cut at {at:.0f} %; {how}; complete {s:.0f} s after the cut"


def cut(w: World) -> None:
    w.notes["cut"] = cut_during_transfer(w, "cut.bin", 120, must_reconnect=False)


def relay(w: World) -> None:
    w.peer.call("PATCH", "rest/config/options", {"listenAddresses": [w.relay_url]})
    w.announced("relay://")  # the peer joined the relay and announced it
    since = time.time()
    w.restart_app()
    c = w.wait_link(since, 300)
    if c.get("type") != "relay-client":
        raise RuntimeError(f"expected relay-client, got {c.get('type')} {c.get('address')}")
    w.found_by_discovery("relay://")
    w.from_peer("relay-peer.bin", 20)
    w.from_phone("relay-phone.bin", 5)
    a, b = w.arrives("relay-peer.bin", 300), w.arrives("relay-phone.bin", 300)
    w.notes["relay"] = f"relay-client; 20 MB in {a:.0f} s, 5 MB back in {b:.0f} s"


# A silent cut ends a connection only through the core's own timeout (lib/protocol): nothing received for
# ReceiveTimeout (300 s), checked every ReceiveTimeout/2, so up to 450 s after the last message. The relay
# drops its side after 2 min, but its reset cannot reach the phone during the cut.
RECEIVE_TIMEOUT_WORST_S = 300 + 150


def relay_cut(w: World) -> None:
    w.notes["relay_cut"] = cut_during_transfer(w, "relay-cut.bin", RECEIVE_TIMEOUT_WORST_S + 30, must_reconnect=True)


SCENARIOS = [discovery, slow, cut, relay, relay_cut]


# ---- one API, then main ------------------------------------------------------------------------

def run_api(api: int, out: Path, disco: str, relay_id: str) -> tuple[list[Result], dict[str, str]]:
    d = out / f"api{api}"
    d.mkdir(parents=True)
    env = {**os.environ, "E2E_EMULATOR_LOG": str(d / "emulator.log")}
    steps = [step(f"API {api}: emulator", ["scripts/e2e/emulator.sh", str(api)], d / "emulator-start.log", env, 20)]
    notes: dict[str, str] = {}
    if not steps[-1].ok:
        return steps, notes
    w = World(d, disco, relay_id)
    try:
        began = time.monotonic()
        try:
            ok = w.pair()
            steps.append(Result(f"API {api}: pair, then the peer into the namespace",
                                "ok" if ok else "FAIL: pairAndSync (see phase-pairAndSync.txt)", (time.monotonic() - began) / MIN))
        except Exception as e:  # noqa: BLE001
            steps.append(Result(f"API {api}: pair, then the peer into the namespace", f"FAIL: {type(e).__name__}: {e}",
                                (time.monotonic() - began) / MIN))
            ok = False
        if ok:
            for f in SCENARIOS:
                log(f"API {api}: {f.__name__}")
                before = len(w.run.failures)
                began = time.monotonic()
                try:
                    f(w)
                    result = "FAIL: " + "; ".join(w.run.failures[before:]) if w.run.failures[before:] else "ok"
                except Exception as e:  # noqa: BLE001  one scenario's trouble must not stop the rest
                    result = f"FAIL: {type(e).__name__}: {e}"
                    helper("net clean")
                steps.append(Result(f"API {api}: {f.__name__}", result, (time.monotonic() - began) / MIN))
                log(f"API {api}: {f.__name__}: {result}")
    finally:
        helper("net clean")
        if w.peer:
            helper("stop peer")
            for name in ("peer.log", "peer-stdout.log"):
                if (T / name).exists():
                    shutil.copy(T / name, d / name)
        (d / "logcat.txt").write_text(w.run.adb("logcat", "-d", check=False, timeout=300))
        pc.stop_emulator(env, api)
    crashes, _ = w.run.logcat_app_crashes((d / "logcat.txt").read_text(errors="replace"))
    steps.append(Result(f"API {api}: no app crash in logcat", "ok" if not crashes else f"FAIL: {len(crashes)} crashes", 0.0))
    return steps, {k: v for k, v in w.notes.items()}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apis", default="35,36,37")
    ap.add_argument("--out", type=Path, default=Path("build/e2e-net"))
    a = ap.parse_args()
    apis = [int(x) for x in a.apis.split(",")]
    if unknown := [x for x in apis if x not in ANDROID]:
        sys.exit(f"unknown API levels {unknown}; known: {sorted(ANDROID)}")
    os.chdir(ROOT)
    preflight()
    # Every adb call of this process and its children goes to the test emulator; it runs without DNS.
    os.environ.update({"ANDROID_SERIAL": SERIAL, "E2E_PORT": str(PORT), "E2E_OFFLINE": "1"})
    out = (ROOT / a.out).resolve()
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    commit, subject = git("rev-parse", "HEAD"), git("log", "-1", "--format=%s")
    began = datetime.now(timezone.utc)
    log(f"commit {commit[:12]} ({subject}), APIs {apis}")

    built = build(out)
    runs: dict[int, tuple[list[Result], dict[str, str]]] = {}
    if all(s.ok for s in built):
        disco, relay_id = new_identity(T / "disco"), new_identity(T / "relay")
        helper("start disco")
        helper("start relay")
        try:
            for api in apis:
                runs[api] = run_api(api, out, disco, relay_id)
        finally:
            helper("stop relay")
            helper("stop disco")
            for name in ("disco.log", "relay.log"):
                if (T / name).exists():
                    shutil.copy(T / name, out / name)
    ended = datetime.now(timezone.utc)

    def api_ok(api: int) -> bool:
        return api in runs and all(s.ok for s in runs[api][0])

    passed = all(s.ok for s in built) and all(api_ok(api) for api in apis)
    lines = ["# PC network test results", "", f"- Commit: {commit}", f"- Result: {'PASS' if passed else 'FAIL'}",
             f"- Subject: {subject}",
             f"- When: {began:%Y-%m-%d %H:%M} to {ended:%H:%M} UTC ({(ended - began).total_seconds() / MIN:.0f} min)",
             f"- Network: namespace {NS_IP} (discovery server, relay, peer), reached through the emulator's NAT; "
             "emulator without DNS; no public server", "- Script: scripts/e2e/net.py (root part: scripts/e2e/netns.sh)", "",
             "## Build", "", "| step | result | min |", "|---|---|---|",
             *[f"| {s.name.removeprefix('build: ')} | {s.result} | {s.minutes:.1f} |" for s in built], ""]
    for api in apis:
        lines += [f"## {ANDROID[api]} (API {api}): {'PASS' if api_ok(api) else 'FAIL'}", ""]
        if api not in runs:
            lines += ["Not run: the build failed.", ""]
            continue
        steps, notes = runs[api]
        lines += ["| step | result | min | measured |", "|---|---|---|---|"]
        for s in steps:
            name = s.name.removeprefix(f"API {api}: ")
            lines.append(f"| {name} | {s.result.replace('|', '/')} | {s.minutes:.1f} | {notes.get(name, '')} |")
        lines.append("")
    text = pc.private("\n".join(lines).rstrip() + "\n")
    (out / "summary.md").write_text(text)
    print(text)
    log(f"summary: {pc.shown(out / 'summary.md')}; now run 'scripts/e2e/netns.sh down' as root")
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
