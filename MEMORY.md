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


## Emulator test results (2026-10-24/25, API 35 and 36 AOSP x86_64 images)
- Local build: JDK 21 required (Gradle 8.14 cannot run on JDK 25). Debug build, `go test`, unit tests and lint pass; debug APK ~130 MB.
- Works on both API levels: setup screen, All files access, core start, `specialUse` FGS, web GUI auto-login, SAF folder picker to POSIX path, pending device/folder cards, receiving a multi-GB share, two-way create/edit/delete, remote delete, Trash Can versioning, conflict copies, start at boot, app update (`MY_PACKAGE_REPLACED`; ID, keys, folders kept), swipe-away, forced deep doze without battery exemption, kill -9 mid-transfer (START_STICKY restart, transfer resumes), all four run conditions incl. rapid flapping, user-paused device kept paused.
- Network: Wi-Fi to mobile and airplane on/off reconnect in ~20 s; with 300-600 ms emulated latency reconnect took ~5 min (check on a real phone). Emulator NAT blocks LAN discovery.
- Polish found: web GUI shows usage-reporting prompt; WebView top bar gap; empty folder list has no hint; accept-folder dialog has no default path and no folder type; no native way to add sharing to an existing folder or set versioning; device name is "localhost"; notification says "Syncing" when paused by user, offline, or no device connected; FGS notification not shown after notification permission is granted until its next update.
- Test tips: REST port changes on every core restart (read it from logcat "GUI and API listening"); `dumpsys battery reset` leaves the emulator unplugged; `adb emu network speed` did not throttle transfers.
- 2026-10-25 polish (branch `polish-emulator-findings`): shared `SyncStatus` in core (one event follower in the service; app reads it), notification texts from `summarize()` (unit-tested), device named after the phone, web GUI follows dark mode via values-night theme. Verified on both emulators. Still open for the GUI step: accept dialog default path and folder type, sharing edit, folder settings and versioning.

## GUI decisions (2026-10-25)
- Material You (wallpaper) colours; more contrast: tinted screen background, cards with outline, status as colour + icon (green check / yellow arrows, pause, bell / red error).
- Folder card tap opens a folder settings screen (name, type, sharing, versioning, ignores, remove); device card opens device settings.
- Accepted folders default to `/storage/emulated/0/Syncthing/<label>`; accept screen offers folder type, sharing and versioning.
- Request notification (own channel, Review/Ignore actions): mockup shown, waiting for the user's OK.

## Widget (branch `home-widget`, stacked on `native-gui`, 2026-10-25)
- `core/StatusWidget`: RemoteViews bar, full width x 1 like the search bar (targetCellWidth 5, minWidth 290 dp), 52 dp high centred in the cell, 2 dp corners (user wants almost square). The launcher drag outline is the whole cell row and cannot be smaller than one row, no polling: the service renders on summary changes, at most every 2 s. Colour + icon per `SyncState` (green check, amber arrows/pause/bell, red error, grey stop), progress bar while syncing, pause/resume button toggles `RunConditions.pausedByUser` (enforced like a run condition; also a Settings switch). Tap opens the app (Devices tab when only a device request waits).
- Request notifications (`core/RequestNotifications`, channel "Requests"): one per pending device/folder offer, Review deep-links to the accept screen or add-device dialog, Ignore is permanent (remoteIgnoredDevices / device ignoredFolders, like upstream). No explicit group: cancelling a group summary drops its children.
- Problem (red) includes "No network" and "Not connected"; a single offline device is grey in the app.
- README features the widget near the top with docs/images/widget.png. F-Droid description should mention it once fastlane metadata exists.
- Emulator tips: place widgets by long-press home, Widgets, then a raw `input motionevent` DOWN/MOVE/UP drag (`input draganddrop` does not work); `ui tap <text>` matches substrings anywhere, tap tabs by coordinates.

## Working rules
- Usage reporting: ask once natively at first start with upstream's wording and condition (urAccepted > -1, urSeen < urVersionMax), never pre-answer it; Settings has a switch. User's wish, out of respect for upstream.
- Never commit anything about the user's network or machine (hostnames, IPs, usernames, device IDs, home paths), including this file, comments and commit messages. Use placeholders and check the staged diff before each commit. Local test notes live in Claude's own memory folder, not in the repo.
- Commit as `Claude <noreply@anthropic.com>` (repo-local git config); the machine default identity contains the username and hostname.
- Ask before pushing or opening a PR; one short-lived branch and PR per step, deleted after merge.
- Propose GUI designs and get the user's answer before building them.
