# Development

How the code is laid out, how to build and test it, and how it is kept up to date. Releases and
reproducible builds: [releasing](releasing.md). What the tests cover and found: [testing](testing.md). Why each part is the way it is: [developer notes](dev-notes.md).
Paths in `code` are from the repository root.

## Layout

| Path | What |
|---|---|
| `app/` | Compose UI: folders, devices (QR pairing), settings, upstream web GUI as fallback |
| `core/` | `specialUse` foreground service, run conditions and sync windows, REST/event client, widget, notifications |
| `go/bridge/` | Go package `stbridge`; runs Syncthing in-process (start, stop, identity), and holds the backup import and export, the input limits and the memory limit |
| `go/syncthing/` | Upstream Syncthing, git submodule pinned to a release tag |
| `go/patches/` | Our fixes to upstream Syncthing, applied to the submodule by `go/patch.sh` at build time |

The Go core is built into `go/build/stbridge.aar` with gomobile. Kotlin talks to it only through Syncthing's REST API on `127.0.0.1:<random port>`, over TLS that trusts only the core's own certificate, authenticated with an API key. The web GUI is protected by a random 64-character user and password that only the app knows, because other apps can reach loopback; the core refuses any change to them. The API key, user and password are new at every core start; the old ones and the web GUI sessions they opened stop working. The app keeps them only in memory; the core's `config.xml`, in the app's private storage, holds the key, the user and the password's bcrypt hash until the next start replaces them (an export leaves them out).

## Build

Requirements: Linux (`go/build.sh` uses GNU `stat` and `flock`), JDK 21, Go (the toolchain auto-downloads the version pinned in `go/bridge/go.mod`), the Android SDK with platform 37 (`ANDROID_HOME`), and the NDK version pinned in `gradle.properties` (`mysyncthing.ndkVersion`; `sdkmanager "ndk;<version>"`).

```sh
git submodule update --init
./gradlew :app:assembleDebug   # runs go/build.sh first; :core alone cannot be bundled as an AAR
```

Screen tests run with the unit tests, on the JVM (Robolectric): the app's real screens with made-up folders and devices (`app/src/test/.../ui/Fixtures.kt`), checking the forms, the Discard prompt, that a shown password hides the screen from captures, that TalkBack can name every control, and that every text keeps 4.5:1 contrast with a normal and a high-contrast palette (`ContrastTest`). `ScreenshotTest` compares each screen in light, dark, the largest font, Italian and German (the longest text), and the text dialogs in landscape, with the images in `app/src/test/screenshots/`; CI runs it with `:app:verifyRoborazziDebug` and uploads the diff images when one differs. After a deliberate change to a screen, record them again with `./gradlew :app:recordRoborazziDebug` and look at the new images before committing them.

End-to-end tests run the debug app on an emulator against a real Syncthing peer built from the same patched core. `scripts/e2e/run.py` runs these steps in order: pairing and sync both ways, restoring a Trash can version, a conflict copy, run conditions (Wi-Fi off), pausing a device from its card and its screen, the Devices tab's "Pause syncing" and the grey-then-red wait for devices, a device's own sync windows (closed now, and never overlapping the global ones), typed text kept when the screen is recreated and an open dialog left idle (it must stop drawing), the length limits, rotation with a dialog open, recent changes, the folder browser, removing a folder and a device, the user's stop and start, the app's process killed, All files access taken away (the peer must see no deletions), on Android 17 the local network permission taken away, starts from the launcher, and a monkey run with a fixed seed; it also fails on any crash, ANR or blank screen in the log. Then come the fast sync scenarios (`scripts/e2e/scenarios.py --only fast`: conflicts of several kinds and the web GUI login). What each step must show is in [testing](testing.md#end-to-end-tests). The peer listens on loopback only, with discovery, relays and NAT off, and reaches the emulator through `adb reverse` and `adb forward`. They run on a PC with KVM, not in GitHub CI. One command does a whole run for the checked-out commit (about 1.5 h for the three Android versions):

```sh
scripts/e2e/pc.py             # --release also writes docs/e2e/<version>.md; --online keeps the public servers
```

It needs a clean checkout, `ANDROID_HOME` with the SDK and NDK, Go and JDK 21. It builds the APKs and the peer, starts a fresh Android 15, then 16, then 17 emulator on port 5580 (every adb call goes to it, never to another emulator or a phone), runs run.py and the fast scenarios on each, and writes `build/e2e-pc/summary.md` with every step's result and time, for the pull request; the logs, logcat and the peer's logs are next to it. It refuses to start while a Syncthing runs on the machine, or with less than 10 GiB of memory free. The emulator gets no DNS and the phone's core leaves Syncthing's public servers once paired, unless `--online` is given. A second run can go at the same time from another checkout with `E2E_SLOT=1` (its own emulator port, AVD names and peer ports; `scripts/e2e/slot.py`). To run one script by hand on an emulator that is already up (the scripts refuse a real phone, as they wipe the app's data):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
(cd go/syncthing && go build -tags noupgrade -o ../build/e2e-peer ./cmd/syncthing)
scripts/e2e/run.py --peer go/build/e2e-peer --apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Results (`summary.md`), logcat and the peer's log land in `build/e2e/`.

`scripts/e2e/upgrade.py` tests an update in place: it takes the debug APKs of the last release and of this build (`--old-apk`, `--old-test-apk`, `--apk`, `--test-apk`, built on the same machine so they share the debug key) and checks that everything set up in the old one survives and syncs on ([testing](testing.md#app-updates)).

`cd go/bridge && go test .` starts and stops the real core on the host. Run `go/patch.sh` and then `go generate ./lib/api/auto` in `go/syncthing` once first.

`go/build.sh` applies `go/patches/*.patch` to the submodule (`go/patch.sh`, idempotent), so the submodule shows as modified after a build. Each patch is a fix we also report upstream; drop it once a Syncthing release has it. To change one, commit the change in the submodule and export it with `git format-patch -1 --no-signature --zero-commit -o ../patches/`, then check out the pinned commit again.

## Security checks

- `docs/manifest-surface.txt` lists every permission the release app has and every part of it other apps can reach, from the merged release manifest with all libraries. The `android` CI job fails when it changes, so a library that adds a permission or an exported component is seen in review. After an intended change: `./gradlew :app:processReleaseManifest && scripts/manifest-surface.py`, and commit the result.
- CodeQL (`.github/workflows/codeql.yml`) analyses the Kotlin app and the Go bridge with the patched core from a real build, and the workflows and the Python scripts, on every push to `main`, weekly, by hand, and on pull requests that change that workflow. Findings are under Security › Code scanning. It needs the repository's CodeQL setting on "Advanced".
- Published vulnerabilities (`.github/workflows/vulnerabilities.yml`, daily at 04:41 UTC and by hand): govulncheck on the Go code (only what the app can reach), osv-scanner on every other library in `docs/sbom.cdx.json` (`scripts/vulns/osv.sh`), and zizmor's known-vulnerable-actions audit on the pinned actions (`scripts/vulns/actions.sh`). Findings open one issue (label `vulnerability`), assigned to the repository owner; it is updated when they change and closes itself when nothing is left. A scan that breaks leaves that issue alone and opens a second one (label `vulnerability-scan`), which closes itself once every scan works again: GitHub mails a failed scheduled run only to whoever last changed the schedule. An advisory that cannot affect the app, or that only upstream Syncthing can fix, goes into `scripts/vulns/accepted.toml` with its reason; a new advisory or another version of that package is reported again. Pull requests that change the scans or the SBOM run them as a check. osv-scanner's version and checksum are in `scripts/vulns/osv-scanner.txt`. Locally: `scripts/vulns/osv.sh --offline` (downloads the OSV database, about 250 MB).
- Upkeep (`.github/workflows/upkeep.yml`): see [Keeping up to date](#keeping-up-to-date).
- Leak check (`privacy` job in `build.yml`, `scripts/check-leaks.sh`): no host names, addresses, device IDs, home paths, e-mail or MAC addresses and no key material in the files, the commits or the history.
- `go vet` and staticcheck on the Go bridge (`android` job in `build.yml`). The bridge tests run without the race detector until upstream fixes a data race in the folder watcher (issue #95).
- Every job runs on a pinned runner image (`ubuntu-24.04`), so a new image never arrives unannounced.
- zizmor lints the workflows (`workflows` job in `build.yml`): script injection, credentials kept in the checkout, permissions, and that each pinned action commit belongs to its action. Its version and wheel checksum are in `scripts/zizmor-requirements.txt`. Checkouts never keep the job's token (`persist-credentials: false`).
- govulncheck (`.github/workflows/govulncheck.yml`) looks up the Go vulnerability database for anything the bridge, the patched core, their modules or the Go standard library contain that the app's code can actually reach, for android/arm64 with cgo as the app is built. It runs on every pull request and push to `main`, and daily as part of the vulnerability scan above, since new vulnerabilities appear without changes here; a hit fails it. The fix is usually a newer Go (`toolchain` line in `go/bridge/go.mod`) or module; then run `scripts/sbom.sh`.

## Updating Syncthing

```sh
cd go/syncthing && git checkout . && git clean -fd lib && git fetch --tags && git checkout vX.Y.Z
echo vX.Y.Z > ../syncthing.version
../patch.sh   # a patch that no longer applies: drop it if the release has the fix, else rebase it
cd ../bridge && go mod tidy && go test .
```

Then commit the submodule's new commit with `go/syncthing.version`, and run `scripts/sbom.sh`: it stops at every web GUI file that is new or changed, which then goes into `scripts/sbom/webgui.json`. A new core migrates the old config and database, so run the upgrade test (`scripts/e2e/upgrade.py`) before releasing it.

## Keeping up to date

Security fixes come first: the daily vulnerability scan opens an issue within a day (see [Security checks](#security-checks)). Everything else follows this cadence:

- Syncthing: a new release within two weeks, with Go's latest patch release of our line. Each bump rebases or drops the patches in `go/patches/`.
- Go: move to a newer line (1.N+1) before ours stops getting fixes, which is when Go 1.N+2 ships (February and August).
- Gradle, AGP, Kotlin, libraries, NDK, actions and the scan tools: in batches, at least once a quarter.

`.github/workflows/upkeep.yml` keeps the list. On the 10th of each month, and on every tag, it opens one issue (label `upkeep`) with:

- every pin with a newer stable release (`scripts/upkeep/versions.py`): Syncthing, Go, the Go modules the bridge picks itself, Gradle, the NDK, each version in `gradle/libs.versions.toml`, the pinned actions, the tools CI installs with `go install` (staticcheck, govulncheck), the runner image, zizmor and osv-scanner. Not compileSdk/targetSdk, which are raised by hand with Android's behaviour changes, nor the Go modules that come with Syncthing.
- F-Droid out of step (`scripts/upkeep/fdroid.sh`), once the app is there: fdroiddata's recipe differs from ours apart from versions (F-Droid would build a different APK, which cannot carry our signature, so that version would never be published), or F-Droid has not published our latest release within 10 days.

The issue is updated when its list changes and closes itself when nothing is left; a check that breaks opens a second issue (label `upkeep-check`). Each Monday it also checks that main has had a commit in the last 45 days (`scripts/upkeep/quiet.sh`): GitHub turns off scheduled workflows after 60 days without activity, the vulnerability scan included, and nothing would report it. Otherwise it opens an issue (label `upkeep-quiet`) until the next commit. Locally: `scripts/upkeep/versions.py`, and `scripts/upkeep/fdroid.sh` (needs `gh`, `jq` and mikefarah's `yq`).

A bump is an ordinary pull request: regenerate `gradle/verification-metadata.xml` for anything Gradle resolves, run `scripts/sbom.sh`, and for a new major version of an action check that its `action.yml` says `using: node24` or later.

## Software bill of materials

Everything the release APK contains, with versions and licences, is in the software bill of materials: [`docs/sbom.md`](sbom.md), and [`docs/sbom.cdx.json`](sbom.cdx.json) in CycloneDX 1.6 with checksums and the dependency graph. It covers the Android and Kotlin libraries, the Go modules linked into the core, C code they bundle, the third-party files in Syncthing's web GUI, and the pinned build tools.

`scripts/sbom.sh` generates both files from the build itself: Gradle's resolved release classpath (licences from the POMs, checksums from `gradle/verification-metadata.xml`), `go list` with the core's build settings (licences read from each module's licence files), and `scripts/sbom/webgui.json` for the web GUI, whose files are pinned by checksum. It fails on anything it cannot place: a licence it has not seen before (it accepts only free software licences, so that the app stays free software), C code in a new Go package, a web GUI file in no entry or changed. CI keeps it current: the `sbom` job in `build.yml` regenerates it and fails when the committed files differ, and the release build checks the Go modules against the build info in the APK's `libgojni.so`. After a dependency, Syncthing or toolchain change, run `scripts/sbom.sh` and commit the result.

## Translations

All text for people is in string resources; English is the source language; Italian, Spanish, German and French are translated. Files, conventions for translators, and how code handles text: [`docs/translations.md`](translations.md).
