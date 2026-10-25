# Project memory

Resume notes for any session. Dates are ISO 8601, times 24 h.

## Decisions (confirmed 2026-10-24)
- App ID `eu.stedb.mysyncthing`. Licence AGPL-3.0. Distribution: the app will be published on F-Droid (no Play constraints, no proprietary deps, reproducible builds). When is not decided yet: no date, don't describe it as imminent or as already available.
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

## Widget (merged in PR #4, 2026-10-25)
- `core/StatusWidget`: RemoteViews bar, 1 row, placed 4 cells wide, user-resizable horizontally (minResizeWidth 180 dp fits icon, text, button; up to full width), 52 dp high centred in the cell, 2 dp corners (user wants almost square). The launcher drag outline is the whole cell row and cannot be smaller than one row, no polling: the service renders on summary changes, at most every 2 s. Colour + icon per `SyncState` (green check, amber arrows/pause/bell, red error, grey stop), progress bar while syncing, pause/resume button toggles `RunConditions.pausedByUser` (enforced like a run condition; also a Settings switch). Tap opens the app (Devices tab when only a device request waits).
- Request notifications (`core/RequestNotifications`, channel "Requests"): one per pending device/folder offer, Review deep-links to the accept screen or add-device dialog, Ignore is permanent (remoteIgnoredDevices / device ignoredFolders, like upstream). No explicit group: cancelling a group summary drops its children.
- Problem (red) includes "No network" and "Not connected"; a single offline device is grey in the app.
- README features the widget near the top with docs/images/widget.png. F-Droid description should mention it once fastlane metadata exists.
- Emulator tips: place widgets by long-press home, Widgets, then a raw `input motionevent` DOWN/MOVE/UP drag (`input draganddrop` does not work); `ui tap <text>` matches substrings anywhere, tap tabs by coordinates.

## Edge to edge and first phone test (branch `edge-to-edge`, 2026-10-25)
- Insets: `SafeBars` (system bars + display cutout) for the top bar, tab bar and snackbar; `AppScreen` content uses `safeDrawing` (adds the keyboard); the FAB gets End-side padding because Scaffold only lifts it above bottom insets. Checked with cutout emulation, 3-button and gesture nav, both landscapes, on API 35/36 and a real Android 16 phone (OnePlus, punch-hole, 3-button nav).
- QR scanner is `ui/ScanActivity` (zxing CaptureActivity subclass): follows rotation, hint kept above the nav bar.
- Animations off (user's phone): indeterminate spinners freeze as a dot, so use `BusyIndicator()`.
- Corners: user wants much less rounded; theme `AppShapes` (2-8 dp) and `ButtonShape` (4 dp) on filled/outlined/tonal buttons.
- Widget: OnePlus launcher ignores `previewLayout`; `previewImage` covers it.
- Real-phone results: setup, two-way sync, widget, QR scan with the camera all fine. 1 h unplugged with screen off and battery optimisation on (no OEM battery switches): not killed, same process, files arrived in 13-30 s. 3.25 GB over mobile data from a server in ~11 min (~5 MB/s), no errors; edits and deletes went back. Wi-Fi to mobile took ~7.5 min to reconnect because the peer keeps the dead TCP link until its 5 min receive timeout and rejects worse-priority connections meanwhile (upstream `lib/connections/service.go`); mobile to Wi-Fi 18 s.
- Material 3 determinate bars draw a stop dot and gap (looked like a glitch): use `ProgressBar()`. Segmented labels must allow two lines (phone display size larger than the emulator's).
- OxygenOS 16's per-app battery page ("Allow background activity" / Smart / Restrict) is Android's Unrestricted / Optimized / Restricted: the standard battery-optimisation request covers it.
- Setup is a checklist (All files access required, notifications optional, run in background recommended) that stays until Done (`setup_done` in DataStore; shown again while All files access is missing); Settings has the same rows under Permissions. Approved by the user 2026-10-25. A notification prompt Android no longer shows (refused twice) opens the app's notification settings.
- Real phone rules: another Syncthing app may be installed and stopped there; never touch it, keep it stopped while testing (same default ports). Check the foreground app before any tap.

## Security audit (2026-10-25)
- Web GUI login: 64 random characters for user and password (`Settings.credentials()`, earlier short ones are replaced). `go/bridge` subscribes `guiGuard`, which refuses any config change to the GUI login or access settings (e.g. from the web GUI's settings); `TestGUILoginLocked` covers it. The WebView hides Log Out, which would reload into the login page.
- WebView: Google usage metrics and Safe Browsing off (manifest meta-data); it only loads loopback. Injected CSS (`HIDE_APP_MANAGED` in `WebGuiScreen.kt`) hides Log Out, Shut Down and the GUI login/address/HTTPS/LDAP settings; recheck its selectors when bumping Syncthing.
- Restart from the web GUI (or a database reset) restarts the core in place in `go/bridge` (`watch`): same address, `IsRunning` stays true. `SyncStatus.follow` resets its event cursor after an error, because a restarted core counts events from 1.
- Phone migration (`res/xml/data_extraction_rules.xml`): device transfer carries config.xml, cert/key and the DataStore, never the database (a stale database plus a partial file copy would spread deletions). Cloud backup stays off. The service flags "config but no `index-v2`" as moved, and the app shows a one-time note to stop Syncthing on the old phone.
- Folder review from an intent opens only for a real pending offer (MainActivity is exported). Request notifications: no IP, and a public version for the lock screen.

## Web-GUI features made native (2026-10-25)
- User picked from a list of 40 web-GUI-only features; built in 7 batches, one PR each: A status (34, 36, 19, 7, 1, 39, 2), B folders (3, 5, 6, 8, 9, 12, 14, 15), C devices (16-18, 20-24, 26), D network (27-29, 38), E defaults for new folders and for new devices (separate entries), F encryption (send only: 13, 25), G recent changes (in-memory only), restore versions, about. Left in the web GUI: versions path and cleanup interval, external versioning, database free space, raw advanced settings, logs.
- Units: every number carries its unit; sizes and speeds in SI (kB, MB/s), converted to the core's KiB/s where it stores limits. Times day-first, 24 h (`whenText`).
- Batch A: core notices (`/rest/system/error`, no event) are read whenever the event long-poll returns and 30 s have passed, so no extra wake-ups; shown as a red card on Folders and a high-importance "Problems" notification (on by default, user's choice: rare and blocking), and they make the summary red. Speeds and listener/discovery health are polled every 2 s only while a screen shows them (`MainViewModel.liveStats`). Per-file download progress comes from the DownloadProgress event in the shared follower.
- Batch B: new folders get `ignorePerms: true` (no real permissions on shared storage). Receive-only folders with local changes and send-only folders with remote changes are "needs review" (yellow) with Show changes plus Revert/Override; send-only need no longer counts as syncing. Ignore patterns are a full-screen editor; "Set ignore patterns first" adds the folder paused and saving unpauses it. Groups use upstream's folder `group` field and are listed ungrouped first, then by name. Rescan interval, download order and minimum free space (`{value, unit}`, SI) are folder settings.
- Batch C: Add device is a full screen (route `add-device`, also used for requests) with nearby devices from `/rest/system/discovery` and folders to share. Device screen: Trust (introducer, auto-accept), group, addresses ("dynamic" plus fixed, bare host:port becomes tcp://), speed limits (shown in SI, stored in KiB/s: `limitSi`/`limitKib`), compression, connections. Settings > Ignored requests lists `remoteIgnoredDevices` and each device's `ignoredFolders` with Unignore. At core start the new-folder defaults move from upstream's "~" (the app's private dir) to Syncthing/ in shared storage with ignorePerms, so auto-accepted folders land there; skipped once the user changed them.
- Batch D: Settings has Network (global limits, limit on LAN), Finding devices (local, internet discovery, relays, NAT) and, under Advanced, listen addresses and discovery servers (`ListEditDialog`, "default" token as a checkbox). A banner and System > Restart Syncthing appear when `/rest/config/restart-required` says so; restart goes through `/rest/system/restart`, which the bridge turns into a restart in place on the same address.
- Batch E: Settings > New folders and devices opens `DefaultFolderScreen` and `DefaultDeviceScreen` (`/rest/config/defaults/folder`, `/device`) and the default ignore patterns (`/rest/config/defaults/ignores`, the ignore editor with a null folder). The add-folder form starts from the folder defaults (type, versioning, location). The core applies default ignores only to auto-accepted folders, so `MainViewModel.addFolder` adds the folder paused, writes them, then resumes it. Device defaults need nothing: POST /rest/config/devices fills unspecified fields from them.
- Batch F: encrypted sharing, send side only (no "Receive encrypted" type, user's choice). A device's Trust group has Untrusted (confirm lists folders shared without a password, which the core then unshares; introducer and auto-accept go off and are hidden). Sharing with an untrusted device, on the folder, device or add-folder screen, asks for a password (`PasswordDialog`, `FolderDevice.encryptionPassword`). `setFolderDevices` now sends whole entries: before, toggling one device dropped the others' encryptionPassword and introducedBy.
- `scripts/check-leaks.sh` also scans code: write URL-shaped literals so the scheme's "://" starts a new string literal (`"tcp" + "://..."`) or the host is `${...}`, and use 0.0.0.0 or RFC 5737 addresses in hints.

## Working rules
- Usage reporting: ask once natively at first start with upstream's wording and condition (urAccepted > -1, urSeen < urVersionMax), never pre-answer it; Settings has a switch. User's wish, out of respect for upstream.
- This file is public on purpose, so any session can resume the work. Nothing about the user's network or machine (hostnames, IPs, usernames, device IDs, home paths, personal e-mail) may go into it, or into code, comments, commit messages or PR text. Use placeholders. Local test notes live in Claude's own memory folder, not in the repo.
- `scripts/check-leaks.sh` enforces this in CI (job `privacy` in `build.yml`) over all files and history; run it before each push. It is allowlist-only (user's choice, no secret denylist): every IP, IPv6, URL host, host:port, LAN name (.lan, .local, ...), UNC path, user@host, home path, device ID, e-mail and commit identity must be a public host on its list or a placeholder (loopback, RFC 5737 ranges, example.com, `${...}`, `<...>`). Add a new public host to `public_hosts` there; never add a private one.
- Commit as `Claude <noreply@anthropic.com>` (repo-local git config); the machine default identity contains the username and hostname.
- Ask before pushing or opening a PR; one short-lived branch and PR per step, deleted after merge.
- Finish every check locally first (unit tests, lint, emulator and phone checks) and push or open a PR only when all of them pass; GitHub CI comes last (user's rule, 2026-10-25).
- Propose GUI designs and get the user's answer before building them.
