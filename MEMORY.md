# Project memory

Resume notes for any session. Dates are ISO 8601, times 24 h.

## Decisions (confirmed 2026-10-24)
- App ID `eu.stedb.mysyncthing`. Licence AGPL-3.0. Distribution: F-Droid only (no Play constraints, no proprietary deps, reproducible builds).
- minSdk 35, target/compileSdk 36. No wider reach wanted.
- Kotlin + Jetpack Compose. Modules `:app`, `:core`, plus `go/`.
- Core: in-process via `gomobile bind` of `go/bridge` (Go package `stbridge`, Java package `eu.stedb.mysyncthing.stbridge`). Not the exec'd-binary approach.
- Kotlin controls the core only through the REST API on a random loopback port. Credentials (API key, GUI user/password) are generated once and kept in DataStore.
- FGS type `specialUse` (dataSync has a 6 h/day cap on API 35+ and cannot start at boot).
- Storage: `MANAGE_EXTERNAL_STORAGE`. The folder picker (SAF tree URI) is converted to a POSIX path.
- Run conditions pause all devices via REST and remember which ones the user had paused, so a resume does not undo the user's choice.
- Web GUI fallback in a WebView, using Basic auth on the first request and then the session cookie.

## State
- 2026-10-24: scaffold written. The Go bridge builds and its test passes on the host (start/stop/restart in one process, REST auth checked). It cross-compiles for android/arm64.
- Android/Kotlin code has NOT been compiled yet: the first session had no access to dl.google.com. GitHub Actions (`.github/workflows/build.yml`) is the first real build.
- Versions of Google-hosted artifacts in `gradle/libs.versions.toml` (AGP 8.13.0, Compose BOM 2025.08.00, androidx) were picked blind. Bump them to current stable on the first build that can reach Google Maven.

## Next
1. Get CI green (fix compile errors, bump versions, maybe move to AGP 9 with built-in Kotlin).
2. Test on a device: start at boot, run conditions, QR pairing, folder accept.
3. Notifications for pending devices/folders; folder detail screen (ignores, versioning); per-folder sharing edit.
4. F-Droid metadata (fastlane/), reproducible-build check, release signing.

## Pins
- Syncthing v2.1.7 (submodule `go/syncthing`, commit d6d0181f7). Keep `go/syncthing.version` in sync when bumping.
- Build via `:app:assembleDebug`; `:core:bundle*Aar` fails by design (local Go AAR dependency).
- Android link needs `-ldflags=-checklinkname=0` (wlynxg/anet uses go:linkname into net). Upstream requires Go 1.26.2.
