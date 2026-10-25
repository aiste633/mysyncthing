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
- 2026-10-24 17:55: CI green (run 6): AAR, debug APK, unit tests, lint. The APK has never been run on a device.
- The first session had no access to dl.google.com, so Android builds are verified only in GitHub Actions.
- Versions of Google-hosted artifacts in `gradle/libs.versions.toml` (AGP 8.13.0, Compose BOM 2025.08.00, androidx) were picked blind. Bump them to current stable on the first build that can reach Google Maven.
- 2026-10-24 23:20: reproducible builds (branch `claude/project-thread-yav31b`). Two local Go AAR builds from different checkout paths are byte-identical. CI twin build, F-Droid image build and the release workflow are written; see README "Reproducible builds and releases".

## Next
1. Bump Google-hosted versions to current stable (AGP 9 / compileSdk 37 would allow OkHttp 5).
2. Test on a device: start at boot, run conditions, QR pairing, folder accept.
3. Notifications for pending devices/folders; folder detail screen (ignores, versioning); per-folder sharing edit.
4. First release: create the release key (`scripts/sign-release.sh` header), tag, sign, fill `AllowedAPKSigningKeys` in `fdroid/eu.stedb.mysyncthing.yml`, then submit to fdroiddata (user does this once the app is usable).

## Pins
- NDK `mysyncthing.ndkVersion` in `gradle.properties` (r30, 30.0.16248370). `go/build.sh` refuses any other NDK. Also update `ndk:` in `fdroid/eu.stedb.mysyncthing.yml`.
- Release APK is arm64-v8a only (`abiFilters` in release); `vcsInfo` off. Signing happens on the user's PC only, never in CI.
- gomobile embeds absolute module paths in the .so build info; `go/build.sh` builds via the symlink `/tmp/mysyncthing-go` so they match everywhere. Do not remove.
- Any dependency/plugin bump: regenerate `gradle/verification-metadata.xml` (command in README).
- Syncthing v2.1.7 (submodule `go/syncthing`, commit d6d0181f7). Keep `go/syncthing.version` in sync when bumping.
- Build via `:app:assembleDebug`; `:core:bundle*Aar` fails by design (local Go AAR dependency).
- Android link needs `-ldflags=-checklinkname=0` (wlynxg/anet uses go:linkname into net). Upstream requires Go 1.26.2.
