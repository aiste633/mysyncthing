# MySyncthing

A Syncthing client for Android 15 and 16 (API 35/36), written in Kotlin and Jetpack Compose. It is a new app, not a fork of syncthing-android. It will be published on F-Droid; there is no release date yet.

## Home-screen widget

See your sync status at a glance, without opening the app. The widget is a single row like a search bar, and you can stretch it sideways. It shows whether everything is up to date, syncing (with progress), paused or needs your attention, and which devices are connected. Its button pauses and resumes syncing, and a tap opens the app. Colours and icons follow your wallpaper and dark mode.

<p align="center"><img src="docs/images/widget.png" width="400" alt="The widget in four states: up to date, syncing at 71 %, no network, and paused"></p>

## Features

### Syncing

- Folders and devices as clear cards with colour and icon status. Pair devices by QR code or device ID.
- Accept shared folders with a suggested location, choose the folder type, sharing and file versioning; restore older versions of a file.
- Run conditions: metered Wi-Fi, mobile data and roaming each on or off; only while charging or only on battery; pause in battery saver; only at set times of the week (for example 22:00–07:00 on weekdays).
- Connect through a SOCKS5 or HTTP proxy, such as Tor with Orbot, optionally with nothing going around it.
- Easy switch from Syncthing-Fork: import its backup, encrypted ones too, and take over its device ID, folders and devices. Export this app's setup to a zip, optionally AES-256 encrypted, and import it on another phone.
- Starts at boot and keeps syncing in the background; the notification says what is happening.
- Notifies you of new devices and shared folders (with Review and Ignore) and of folders that stop syncing.
- Syncthing's own web GUI is built in for the few things the app does not cover, signed in automatically and private to the app.

### Made for Android 15 and 16

- Only Android 15 and 16, so nothing is held back by older versions. It runs as a foreground service of the kind Android allows to run all day, without the 6-hour daily limit of data-sync services.
- Native Material 3 screens in your wallpaper's colours (Material You), with dark mode and the home-screen widget.
- Works with TalkBack: every button is named and every switch and checkbox reads as on or off.
- In English and Italian, in the phone's language or one chosen for the app alone (Android's per-app language). Dates, times and numbers follow the phone's region and its 12/24 h setting. The text is in standard Android string files, ready for a translation platform such as Weblate: see [Translations](#translations).

### Light on battery and flash

- When idle, the app writes about 0.02–0.14 MB an hour to flash; it keeps no log files. Writes come from syncing itself.
- The run conditions and sync times let it sync only when it suits you: on Wi-Fi, while charging, at night.

### Privacy

- No tracking, advertising, analytics or crash-reporting libraries. There is no account and no server of ours: the app's developer receives nothing.
- Syncthing's anonymous usage reporting is asked once and is off unless you say yes. What it sends is described by the Syncthing project: [Security principles](https://docs.syncthing.net/users/security.html) and [data.syncthing.net](https://data.syncthing.net).
- Cloud backup is off, so the device keys never go to a cloud. Android's phone-to-phone transfer to a new phone does carry them, so the new phone keeps the old one's identity: afterwards stop Syncthing on the old phone (the app reminds you once).
- Release builds keep Syncthing's routine log lines, which name devices and folders, out of Android's system log.

Details: [privacy](docs/privacy.md).

### Hardened and tested

What each test covers, with its numbers: [testing and hardening](docs/testing.md).

- Every input field checked against the Syncthing core's own rules, with property tests of 20,000 random inputs each; [input validation](docs/input-validation.md).
- Go fuzzing of everything that reaches the core or reads a file from outside; it found and fixed a bug in Syncthing itself.
- A security audit and a gap analysis against common practice: the built-in web view is locked to the core's own address, the web GUI login is random and locked, a shown password hides the screen from screenshots, StrictMode checks debug builds. [Security policy](SECURITY.md).
- Stress tests (50,000 files, full storage, random UI events), a 10-hour memory soak with no leak, flash-write tracing, and interruption tests (rotation, Android reclaiming the app, permissions taken away).
- CI checks every push: unit tests, lint, a leak check that keeps personal details out of the repository and its history, a guard on the app's permissions, an audit of the workflows, and a twin build that must be byte-identical. CodeQL analyses the app and the core. GitHub Actions are pinned by commit hash, and releases carry a signed build provenance attestation.

### Open and verifiable

- The official Syncthing core (v2), unchanged apart from a few small fixes, each documented in [`go/patches/`](go/patches/). AGPL-3.0, and the Syncthing core MPL-2.0.
- Reproducible builds: every build of a release gives a byte-identical APK, so anyone, F-Droid included, can check that a release APK was built from this source.
- A [software bill of materials](docs/sbom.md) lists everything the APK contains, with versions and licences; CI keeps it current.

## Screenshots

<p align="center">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1-folders.png" width="240" alt="Folders: each folder is a card with its path, size, devices and status">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2-folder.png" width="240" alt="A folder's own screen with its status, sharing and settings">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3-devices.png" width="240" alt="Devices: this device with its ID, and the other devices with their connection and status">
</p>
<p align="center">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4-restore.png" width="240" alt="Restoring an older version of a file">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5-error.png" width="240" alt="A folder that stopped syncing is shown in red, with a Folder error chip at the top">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6-offer.png" width="240" alt="A notification for a folder shared from another device, with Review and Ignore">
</p>

## Layout

| Path | What |
|---|---|
| `app/` | Compose UI: folders, devices (QR pairing), settings, upstream web GUI as fallback |
| `core/` | `specialUse` foreground service, run conditions, REST/event client |
| `go/bridge/` | Go package `stbridge`; runs Syncthing in-process, exposes start/stop/identity |
| `go/syncthing/` | Upstream Syncthing, git submodule pinned to a release tag |
| `go/patches/` | Our fixes to upstream Syncthing, applied to the submodule by `go/patch.sh` at build time |

The Go core is built into `go/build/stbridge.aar` with gomobile. Kotlin talks to it only through Syncthing's REST API on `127.0.0.1:<random port>`, authenticated with a per-install API key. The web GUI is protected by a random 64-character user and password that only the app knows, because other apps can reach loopback; the core refuses any change to them.

## Build

Requirements: JDK 21, Go (the toolchain auto-downloads the version pinned in `go/bridge/go.mod`), Android SDK 36, and the NDK version pinned in `gradle.properties` (`mysyncthing.ndkVersion`; `sdkmanager "ndk;<version>"`).

```sh
git submodule update --init
./gradlew :app:assembleDebug   # runs go/build.sh first; :core alone cannot be bundled as an AAR
```

Screen tests run with the unit tests, on the JVM (Robolectric): the app's real screens with made-up folders and devices (`app/src/test/.../ui/Fixtures.kt`), checking the forms, the Discard prompt, that a shown password hides the screen from captures, and that TalkBack can name every control. `ScreenshotTest` compares each screen in light, dark, the largest font and Italian with the images in `app/src/test/screenshots/`; CI runs it with `:app:verifyRoborazziDebug` and uploads the diff images when one differs. After a deliberate change to a screen, record them again with `./gradlew :app:recordRoborazziDebug` and look at the new images before committing them.

End-to-end tests run the debug app on an emulator against a real Syncthing peer built from the same patched core: pairing, accepting a shared folder, sync both ways, restoring a Trash can version, run conditions (Wi-Fi off), typed text kept when the screen is recreated, the app's process killed, All files access taken away (the peer must see no deletions), and a monkey run with a fixed seed. CI runs them on Android 15 and 16 (`e2e` job). The peer listens on loopback only, with discovery and relays off, and reaches the emulator through `adb reverse` and `adb forward`. Locally, with an emulator running (the script refuses a real phone, as it wipes the app's data):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
(cd go/syncthing && go build -tags noupgrade -o ../build/e2e-peer ./cmd/syncthing)
scripts/e2e/run.py --peer go/build/e2e-peer --apk app/build/outputs/apk/debug/app-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Results, logcat and the peer's log land in `build/e2e/`.

`cd go/bridge && go test .` starts and stops the real core on the host. Run `go/patch.sh` and then `go generate ./lib/api/auto` in `go/syncthing` once first.

`go/build.sh` applies `go/patches/*.patch` to the submodule (`go/patch.sh`, idempotent), so the submodule shows as modified after a build. Each patch is a fix we also report upstream; drop it once a Syncthing release has it. To change one, commit the change in the submodule and export it with `git format-patch -1 --no-signature --zero-commit -o ../patches/`, then check out the pinned commit again.

## Security checks

- `docs/manifest-surface.txt` lists every permission the release app has and every part of it other apps can reach, from the merged release manifest with all libraries. The `android` CI job fails when it changes, so a library that adds a permission or an exported component is seen in review. After an intended change: `./gradlew :app:processReleaseManifest && scripts/manifest-surface.py`, and commit the result.
- CodeQL (`.github/workflows/codeql.yml`) analyses the Kotlin app, the Go bridge with the patched core, the workflows and the scripts, from a real build, on every push to `main`, weekly and by hand. Findings are under Security › Code scanning. It needs the repository's CodeQL setting on "Advanced".
- zizmor lints the workflows (`workflows` job in `build.yml`): script injection, credentials kept in the checkout, permissions, and that each pinned action commit belongs to its action. Its version and wheel checksum are in `scripts/zizmor-requirements.txt`. Checkouts never keep the job's token (`persist-credentials: false`).
- govulncheck (`.github/workflows/govulncheck.yml`) looks up the Go vulnerability database for anything the bridge, the patched core, their modules or the Go standard library contain that the app's code can actually reach, for android/arm64 with cgo as the app is built. It runs on every push and pull request and weekly, since new vulnerabilities appear without changes here; a hit fails it. The fix is usually a newer Go (`toolchain` line in `go/bridge/go.mod`) or module; then run `scripts/sbom.sh`.

## Reproducible builds and releases

Every build of a commit gives a byte-identical unsigned release APK (arm64 only; debug builds also carry x86_64). This is what lets F-Droid ship the APK signed with our key. What keeps it that way:

- Toolchains are pinned in one place each: NDK in `gradle.properties`, Go in `go/bridge/go.mod` (`toolchain` line; the CI release build checks the APK was built with it), Gradle in the wrapper (with checksum), JDK 21 via `jvmToolchain`.
- `go/build.sh` strips paths, build IDs and timestamps from the Go library, and builds through the fixed symlink `/tmp/mysyncthing-go`, because gomobile records module paths.
- `gradle/verification-metadata.xml` holds SHA-256 checksums of every Gradle dependency. After changing a dependency or plugin version, refresh it:

  ```sh
  ./gradlew --write-verification-metadata sha256 --refresh-dependencies --no-configuration-cache -I scripts/sbom/runtime-deps.init.gradle.kts \
    :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest :app:verifyRoborazziDebug \
    :app:lintDebug :core:lintDebug :app:sbomRuntimeDeps
  scripts/sbom.sh
  ```

  `--refresh-dependencies` makes Gradle fetch every POM again: with them cached it records only the `.module` files it reads, and a clean machine, which also reads the POMs, fails verification. The `sbomRuntimeDeps` part adds the POMs the SBOM reads licences from; `scripts/sbom.sh` then updates the SBOM (see [Licence](#licence)).

- GitHub Actions are pinned by full commit hash, with the version in a comment, so a moved tag cannot change what CI runs. To bump one: `git ls-remote --tags https://github.com/<owner>/<action> 'v*'`, take the commit of the new release (the `^{}` line for an annotated tag), and check that its `action.yml` says `using: node24` or later.
- CI (`.github/workflows/reproducible.yml`) builds the release APK twice, with different paths, time zones, locales and umasks, and fails if the bytes differ (a diffoscope report is attached). On pull requests, tags and manual runs, F-Droid's build image builds it a third time from the recipe in `fdroid/` and must match too.

Releasing:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`. CI creates a draft release with `mysyncthing-0.1.0-unsigned.apk` and its SBOM, `mysyncthing-0.1.0-sbom.cdx.json`, and signed build provenance (SLSA) for both: which workflow run, commit and runner made them. Anyone can check a file with `gh attestation verify <file> --repo aiste633/mysyncthing`.
3. On your machine: `KEYSTORE=~/keys/mysyncthing.jks scripts/sign-release.sh v0.1.0`, then publish the draft.

F-Droid: `fdroid/eu.stedb.mysyncthing.yml` is the draft recipe for fdroiddata. It has not been submitted, and when it will be is not decided yet.

## Updating Syncthing

```sh
cd go/syncthing && git checkout . && git clean -fd lib && git fetch --tags && git checkout vX.Y.Z
echo vX.Y.Z > ../syncthing.version
../patch.sh   # a patch that no longer applies: drop it if the release has the fix, else rebase it
cd ../bridge && go mod tidy && go test .
```

## Privacy and security

- [Privacy](docs/privacy.md): what the app keeps on the phone, what goes to Syncthing's public servers and how to turn each off, permissions, your rights under the GDPR.
- [Security policy](SECURITY.md): report a vulnerability privately through GitHub, never in a public issue.
- [Testing and hardening](docs/testing.md): what each test covers and what it found, with its numbers.

## Translations

All text for people is in string resources; English is the source language and Italian the first translation. Files, conventions for translators, and how code handles text: [`docs/translations.md`](docs/translations.md).

## Licence

AGPL-3.0 (see `LICENSE`). The bundled Syncthing core is MPL-2.0.

Everything the release APK contains, with versions and licences, is in the software bill of materials: [`docs/sbom.md`](docs/sbom.md), and [`docs/sbom.cdx.json`](docs/sbom.cdx.json) in CycloneDX 1.6 with checksums and the dependency graph. It covers the Android and Kotlin libraries, the Go modules linked into the core, C code they bundle, the third-party files in Syncthing's web GUI, and the pinned build tools.

`scripts/sbom.sh` generates both files from the build itself: Gradle's resolved release classpath (licences from the POMs, checksums from `gradle/verification-metadata.xml`), `go list` with the core's build settings (licences read from each module's licence files), and `scripts/sbom/webgui.json` for the web GUI, whose files are pinned by checksum. It fails on anything it cannot place: a licence it has not seen before, C code in a new Go package, a web GUI file in no entry or changed. CI keeps it current: the `sbom` job in `build.yml` regenerates it and fails when the committed files differ, and the release build checks the Go modules against the build info in the APK's `libgojni.so`. After a dependency, Syncthing or toolchain change, run `scripts/sbom.sh` and commit the result.
