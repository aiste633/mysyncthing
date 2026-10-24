# MySyncthing

A Syncthing client for Android 15 and 16 (API 35/36), written in Kotlin and Jetpack Compose. It is a new app, not a fork of syncthing-android. It is distributed through F-Droid.

## Layout

| Path | What |
|---|---|
| `app/` | Compose UI: folders, devices (QR pairing), settings, upstream web GUI as fallback |
| `core/` | `specialUse` foreground service, run conditions, REST/event client |
| `go/bridge/` | Go package `stbridge`; runs Syncthing in-process, exposes start/stop/identity |
| `go/syncthing/` | Upstream Syncthing, git submodule pinned to a release tag |

The Go core is built into `go/build/stbridge.aar` with gomobile. Kotlin talks to it only through Syncthing's REST API on `127.0.0.1:<random port>`, authenticated with a per-install API key. The web GUI is protected by a generated user/password, because other apps can reach loopback.

## Build

Requirements: JDK 21, Go (the toolchain auto-downloads the version upstream needs), Android SDK 36, NDK (`ANDROID_NDK_HOME`).

```sh
git submodule update --init
./gradlew :app:assembleDebug   # runs go/build.sh first; :core alone cannot be bundled as an AAR
```

`cd go/bridge && go test .` starts and stops the real core on the host. Run `go generate ./lib/api/auto` in `go/syncthing` once first.

## Updating Syncthing

```sh
cd go/syncthing && git fetch --tags && git checkout vX.Y.Z
echo vX.Y.Z > ../syncthing.version
cd ../bridge && go mod tidy && go test .
```

## Licence

AGPL-3.0 (see `LICENSE`). The bundled Syncthing core is MPL-2.0.
