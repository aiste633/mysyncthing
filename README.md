# MySyncthing

A Syncthing client for Android 15 and 16 (API 35/36), written in Kotlin and Jetpack Compose. It is a new app, not a fork of syncthing-android. It is distributed through F-Droid.

## Home-screen widget

See your sync status at a glance, without opening the app. The widget is a single row, the size of a search bar. It shows whether everything is up to date, syncing (with progress), paused or needs your attention, and which devices are connected. Its button pauses and resumes syncing, and a tap opens the app. Colours and icons follow your wallpaper and dark mode.

<p align="center"><img src="docs/images/widget.png" width="400" alt="The widget in four states: up to date, syncing at 71 %, no network, and paused"></p>

## Features

- Folders and devices as clear cards with colour and icon status, in Material You colours and dark mode.
- Accept shared folders with a suggested location, choose the folder type, sharing and file versioning.
- Run conditions: Wi-Fi only, unmetered only, while charging, not in battery saver.
- Starts at boot and keeps syncing in the background; the notification says what is happening.
- Syncthing's own web GUI is built in for anything the app does not cover.

## Layout

| Path | What |
|---|---|
| `app/` | Compose UI: folders, devices (QR pairing), settings, upstream web GUI as fallback |
| `core/` | `specialUse` foreground service, run conditions, REST/event client |
| `go/bridge/` | Go package `stbridge`; runs Syncthing in-process, exposes start/stop/identity |
| `go/syncthing/` | Upstream Syncthing, git submodule pinned to a release tag |

The Go core is built into `go/build/stbridge.aar` with gomobile. Kotlin talks to it only through Syncthing's REST API on `127.0.0.1:<random port>`, authenticated with a per-install API key. The web GUI is protected by a generated user/password, because other apps can reach loopback.

## Build

Requirements: JDK 21, Go (the toolchain auto-downloads the version upstream needs), Android SDK 36, and the NDK version pinned in `gradle.properties` (`mysyncthing.ndkVersion`; `sdkmanager "ndk;<version>"`).

```sh
git submodule update --init
./gradlew :app:assembleDebug   # runs go/build.sh first; :core alone cannot be bundled as an AAR
```

`cd go/bridge && go test .` starts and stops the real core on the host. Run `go generate ./lib/api/auto` in `go/syncthing` once first.

## Reproducible builds and releases

Every build of a commit gives a byte-identical unsigned release APK (arm64 only; debug builds also carry x86_64). This is what lets F-Droid ship the APK signed with our key. What keeps it that way:

- Toolchains are pinned in one place each: NDK in `gradle.properties`, Go in `go/syncthing/go.mod`, Gradle in the wrapper (with checksum), JDK 21 via `jvmToolchain`.
- `go/build.sh` strips paths, build IDs and timestamps from the Go library, and builds through the fixed symlink `/tmp/mysyncthing-go`, because gomobile records module paths.
- `gradle/verification-metadata.xml` holds SHA-256 checksums of every Gradle dependency. After changing a dependency or plugin version, refresh it:

  ```sh
  ./gradlew --write-verification-metadata sha256 :app:assembleRelease :app:assembleDebug testDebugUnitTest :app:lintDebug
  ```

- CI (`.github/workflows/reproducible.yml`) builds the release APK twice, with different paths, time zones, locales and umasks, and fails if the bytes differ (a diffoscope report is attached). On pull requests, tags and manual runs, F-Droid's build image builds it a third time from the recipe in `fdroid/` and must match too.

Releasing:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`. CI creates a draft release with `mysyncthing-0.1.0-unsigned.apk`.
3. On your machine: `KEYSTORE=~/keys/mysyncthing.jks scripts/sign-release.sh v0.1.0`, then publish the draft.

F-Droid: `fdroid/eu.stedb.mysyncthing.yml` is the draft recipe for fdroiddata, not yet submitted.

## Updating Syncthing

```sh
cd go/syncthing && git fetch --tags && git checkout vX.Y.Z
echo vX.Y.Z > ../syncthing.version
cd ../bridge && go mod tidy && go test .
```

## Licence

AGPL-3.0 (see `LICENSE`). The bundled Syncthing core is MPL-2.0.
