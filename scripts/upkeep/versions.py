#!/usr/bin/env python3
"""Lists every pinned version that has a newer stable release, for the monthly upkeep check
(.github/workflows/upkeep.yml). Security fixes are not its job: the daily vulnerability scan
reports those within a day. This keeps the rest from going stale.

Checked: Syncthing, the Go toolchain (newest patch of our line, and a newer line), the Go
modules the bridge chooses itself, Gradle, the NDK, every versioned entry of
gradle/libs.versions.toml, the pinned GitHub Actions, zizmor and osv-scanner.
Not checked: compileSdk/targetSdk (raised by hand, with Android's behaviour changes), JDK 21
(LTS), Go modules that come with Syncthing (bumped with it).

Usage:  scripts/upkeep/versions.py [REPO_ROOT]
Prints a Markdown table and exits 0 (all current), 1 (updates found) or 2 (a lookup failed).
"""

import json
import traceback
import re
import subprocess
import sys
import time
import tomllib
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo.maven.apache.org/maven2"
PLUGINS = "https://plugins.gradle.org/m2"
STABLE = re.compile(r"^\d+(\.\d+)*$")
# Versions in libs.versions.toml that follow another entry instead of their own releases.
FOLLOWS = {"robolectricAndroidAll": "robolectric"}
# Go modules in go/bridge/go.mod the bridge picks itself; the others come with Syncthing.
GO_MODULES = ["golang.org/x/mobile"]


def fetch(url, missing_ok=False):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                return r.read().decode()
        except urllib.error.HTTPError as e:
            if e.code == 404 and missing_ok:
                return None
            if e.code < 500 and e.code != 429:
                raise
        except urllib.error.URLError:
            pass
        time.sleep(2 ** (attempt + 1))
    raise RuntimeError(f"no answer from {url}")


def key(version):
    return tuple(int(p) for p in version.split("."))


def newest(versions):
    return max((v for v in versions if STABLE.match(v)), key=key)


def tags(repo, prefix="v"):
    out = subprocess.run(["git", "ls-remote", "--tags", "--refs", f"https://github.com/{repo}.git"],
                         check=True, capture_output=True, text=True).stdout
    return [line.rsplit("/", 1)[1][len(prefix):] for line in out.splitlines()
            if line.rsplit("/", 1)[1].startswith(prefix)]


def maven(group, artifact):
    google = group.startswith(("androidx.", "com.android", "com.google."))
    for repo in ([GOOGLE] if google else []) + [CENTRAL, PLUGINS]:
        xml = fetch(f"{repo}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml", missing_ok=True)
        if xml:
            return newest(v.text for v in ET.fromstring(xml).iter("version"))
    raise RuntimeError(f"{group}:{artifact} is in no Maven repository")


def go_proxy(module, path):
    escaped = re.sub(r"[A-Z]", lambda m: "!" + m.group().lower(), module)
    return json.loads(fetch(f"https://proxy.golang.org/{escaped}/{path}"))


def checks(root):
    """Yields (what, ours, newest, where to bump) for every pin."""
    syncthing = (root / "go/syncthing.version").read_text().strip().removeprefix("v")
    yield "Syncthing", syncthing, newest(tags("syncthing/syncthing")), \
        "README, Updating Syncthing"

    gomod = (root / "go/bridge/go.mod").read_text()
    ours = re.search(r"^toolchain go(\S+)", gomod, re.M).group(1)
    # The toolchain module GOTOOLCHAIN downloads from: v0.0.1-go1.26.10.linux-amd64 is Go 1.26.10.
    releases = re.findall(r"^v0\.0\.1-go(\d+(?:\.\d+)*)\.linux-amd64$",
                          fetch("https://proxy.golang.org/golang.org/toolchain/@v/list"), re.M)
    line = ours.rsplit(".", 1)[0]
    yield f"Go {line}", ours, newest(v for v in releases if v.startswith(line + ".")), \
        "`toolchain` in `go/bridge/go.mod`"
    top = newest(releases)
    if key(top)[:2] > key(ours)[:2]:
        lines = sorted({v.rsplit(".", 1)[0] for v in releases if len(key(v)) == 3}, key=key)
        supported = "still gets fixes" if line in lines[-2:] else "no longer gets fixes"
        yield f"Go, newer line (Go {line} {supported})", ours, top, \
            "`toolchain` in `go/bridge/go.mod`, once Syncthing builds with it"

    for module in GO_MODULES:
        version = re.search(rf"^(?:require)?\s*{re.escape(module)} (\S+)", gomod, re.M).group(1)
        # Pseudo-versions (no release tags) only compare by commit time.
        latest = go_proxy(module, "@latest")
        if latest["Version"] != version and latest["Time"] > go_proxy(module, f"@v/{version}.info")["Time"]:
            yield module, version, latest["Version"], f"`go get {module}@latest` in `go/bridge`"

    wrapper = (root / "gradle/wrapper/gradle-wrapper.properties").read_text()
    yield "Gradle", re.search(r"gradle-(\S+)-bin\.zip", wrapper).group(1), \
        json.loads(fetch("https://services.gradle.org/versions/current"))["version"], \
        "`gradle-wrapper.properties` (`./gradlew wrapper`, with the checksum)"

    ndk = re.search(r"^mysyncthing\.ndkVersion=(\S+)", (root / "gradle.properties").read_text(), re.M).group(1)
    repo = ET.fromstring(fetch("https://dl.google.com/android/repository/repository2-3.xml"))
    stable = [p.get("path").split(";", 1)[1] for p in repo.iter("remotePackage")
              if p.get("path", "").startswith("ndk;")
              and p.find("channelRef") is not None and p.find("channelRef").get("ref") == "channel-0"]
    yield "Android NDK", ndk, newest(stable), \
        "`gradle.properties` and `ndk:` in the F-Droid recipe"

    catalog = tomllib.loads((root / "gradle/libs.versions.toml").read_text())
    coordinates = {}
    for lib in catalog["libraries"].values():
        if "version" in lib:
            group, artifact = lib["module"].split(":")
            coordinates.setdefault(lib["version"]["ref"], (group, artifact))
    for plugin in catalog["plugins"].values():
        coordinates.setdefault(plugin["version"]["ref"], (plugin["id"], plugin["id"] + ".gradle.plugin"))
    for name, version in catalog["versions"].items():
        if name in FOLLOWS:
            continue
        group, artifact = coordinates[name]
        yield f"{name} ({group}:{artifact})", version, maven(group, artifact), \
            "`gradle/libs.versions.toml`"

    pinned = {}
    for workflow in sorted((root / ".github/workflows").glob("*.yml")):
        for m in re.finditer(r"uses: ([\w.-]+/[\w.-]+)[\w./-]*@[0-9a-f]{40} # v(\S+)", workflow.read_text()):
            pinned.setdefault(m.group(1), m.group(2))
    for action, version in sorted(pinned.items()):
        yield action, version, newest(tags(action)), "commit hash in `.github/workflows/` (README)"

    zizmor = re.search(r"^zizmor==(\S+)", (root / "scripts/zizmor-requirements.txt").read_text(), re.M).group(1)
    yield "zizmor", zizmor, json.loads(fetch("https://pypi.org/pypi/zizmor/json"))["info"]["version"], \
        "`scripts/zizmor-requirements.txt`"
    osv = re.search(r"^version=(\S+)", (root / "scripts/vulns/osv-scanner.txt").read_text(), re.M).group(1)
    yield "osv-scanner", osv, newest(tags("google/osv-scanner")), "`scripts/vulns/osv-scanner.txt`"


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    rows = []
    try:
        for what, ours, latest, where in checks(root):
            # A Go pseudo-version comes only when it is outdated.
            if not STABLE.match(ours) or key(latest) > key(ours):
                rows.append((what, ours, latest, where))
    except Exception as e:
        traceback.print_exc()
        print(f"The update check failed: {e!r}", file=sys.stderr)
        return 2
    if not rows:
        return 0
    print("### Newer releases\n\n| What | Ours | Newest | Where |\n|---|---|---|---|")
    for what, ours, latest, where in rows:
        print(f"| {what} | {ours} | {latest} | {where} |")
    print()
    return 1


if __name__ == "__main__":
    sys.exit(main())
