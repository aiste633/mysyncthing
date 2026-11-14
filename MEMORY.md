# Project memory

Resume notes for any session: read this file first. Dates are ISO 8601, times 24 h.

How to keep it: **State** and **Next** are rewritten, not appended to, in every pull request that changes what the app does and at every release. Decisions, Pins and Working rules change only when the thing itself changes. Everything longer goes into [docs/dev-notes.md](docs/dev-notes.md), one section per subject, edited in place. History is in git.

## State (2026-11-14)
- Released: v0.4.0 (2026-11-14, versionCode 7) for Android 15, 16 and 17. Earlier: 0.1.0 (2026-10-29), 0.1.2 (2026-10-30), 0.1.3 (2026-10-31), 0.2.0 (2026-11-05), 0.3.0 (2026-11-12). 0.1.1 was never tagged. Releases are on GitHub; each is signed by the user on their own machine.
- Built from: Syncthing v2.1.7 (three small patches in `go/patches/`), Go toolchain 1.26.11, AGP 9.5.0, Kotlin 2.4.21, Gradle 9.8.2, NDK r30. Details under Pins.
- What works: everything in the README feature list. Native screens for folders, devices, settings, defaults, versions, recent changes, conflicts, run conditions, sync windows (global and per device), proxy, import and export; the web GUI stays as a fallback.
- Languages: English, Italian, Spanish, German, French.
- Tested for 0.4.0: unit and screen tests in CI; the emulator end-to-end run, monkey included, on API 35, 36 and 37 (`docs/e2e/0.4.0.md`); the upgrade test from 0.3.0; a manual walk; network tests (discovery, relay, slow and cut links) on 2026-11-13. It runs on two real phones (Android 16 and 17). See `docs/testing.md`.
- CI on GitHub runs the light jobs (build, tests, lint, staticcheck on the Go bridge, SBOM, leak check, strings, workflow audit, reproducible twin build, CodeQL, govulncheck, daily vulnerability scan, monthly upkeep), all on the pinned runner image `ubuntu-24.04`. Emulator tests run on a PC with `scripts/e2e/pc.py`; a release commits its results.
- F-Droid: not submitted yet. F-Droid asks for three months of active development, reached on 2027-01-24. The recipe is in `fdroid/`.
- Known upstream bugs, tracked here: #80 (three devices, one edit lost in a same-second conflict), #15 and #23 (trash can versioning), #95 (a data race in the folder watcher; because of it CI runs the bridge tests without the race detector).
- On `main`, not released yet: the app's own mark (one line with an arrow up and an arrow down, white on #0891D1) as launcher icon, themed icon, widget and top bar mark, store icon, and as the small icon of the service notification; the widget preview in dark mode. See "App mark" in docs/dev-notes.md.
- A whole-project review on 2026-11-14 found no serious defect. Its hardening batch is on `main` for the next release (see "Hardening batch" in the dev notes): REST calls never use the system proxy, the signing script checks the build provenance before it signs, masked fields always get a password keyboard, cancelled work no longer reads as failed, the core names the app as its wrapper, Android offers to keep the app's data at uninstall, a private build directory under `/tmp`, Gradle repository content filters, key material and MAC addresses in the leak check.
- On main since 0.4.0, for the next release: the "This phone" card on the Devices tab keeps its height when the first poll answers (a dashes line holds the place), so the list below no longer jumps.

## Next
1. TLS on the loopback API with the core's own certificate pinned (OkHttp and the WebView), its own pull request.
2. README reworked around features, screenshots in dark mode, a Caveats section.
3. Each waits for the user's go: `BootReceiver` not exported; a soft memory limit for the Go core as an advanced setting; CameraX instead of the old barcode scanner library.
4. F-Droid inclusion request on or after 2027-01-24.
5. Later: action major bumps and Go 1.27 (issue #92), a newer runner image once it is out of preview (the upkeep check lists it), OpenSSF Scorecard, crash capture, issue forms. The race detector in CI once upstream has fixed #95.

## Decisions (confirmed 2026-10-24)
- App ID `eu.stedb.mysyncthing`. Licence AGPL-3.0-or-later (was -only until 2026-10-31, user decision). Free software only: every shipped component must have an FSF-free licence (`knownLicenses` in scripts/sbom/main.go is the gate). Distribution: the app will be published on F-Droid (no Play constraints, no proprietary deps, reproducible builds). Releases on GitHub: v0.1.0 (2026-10-29), v0.1.2 (2026-10-30; 0.1.1 never tagged, versionCode 2 unused), v0.1.3 (2026-10-31, versionCode 4: backup import hardening), v0.2.0 (2026-11-05, versionCode 5: per-device pause and sync windows, explicit defaults, widget language, monkey fixes), v0.3.0 (2026-11-12, versionCode 6: folder browser, conflict copies, strong export encryption, new web GUI login per start, input length limits, default-location block, freeze fix), v0.4.0 prepared 2026-11-13 (versionCode 7: Android 17, targetSdk 37, local network access required there, memory-limit notice); the recipe is ready (2026-10-30) and the user submits it to fdroiddata. Don't describe the app as on F-Droid until F-Droid has accepted it.
- minSdk 35, targetSdk and compileSdk 37 (Android 15, 16 and 17). No wider reach wanted.
- Kotlin + Jetpack Compose. Modules `:app`, `:core`, plus `go/`.
- Core: in-process via `gomobile bind` of `go/bridge` (Go package `stbridge`, Java package `eu.stedb.mysyncthing.stbridge`). Not the exec'd-binary approach.
- Kotlin controls the core only through the REST API on a random loopback port. Credentials (API key, GUI user/password) are new at every core start (`Credentials.random()`) and kept only in memory (`CoreState.Running`); restarts in place keep them. The DataStore keys of versions up to 0.2.0 are removed at start (`Settings.forgetStoredLogin`).
- FGS type `specialUse` (dataSync has a 6 h/day cap on API 35+ and cannot start at boot).
- Storage: `MANAGE_EXTERNAL_STORAGE`. Folders are chosen in the app's own browser (`ui/FolderBrowser.kt`, storages from `StorageManager`, read with All files access) or by quick picks; Android's SAF picker was dropped 2026-11-05 (it refuses Download and storage roots). Internal storage is `internalStorage` (set in MainActivity from Environment; not always /storage/emulated/0: work profile, Private Space, cloned apps); the bridge gets it via `SetInternalStorage` before an import. `Android/data` and `Android/obb` are refused (app and import).
- Run conditions pause all devices via REST and remember which ones the user had paused, so a resume does not undo the user's choice.
- Web GUI fallback in a WebView, using Basic auth on the first request and then the session cookie.

## Pins
- NDK `mysyncthing.ndkVersion` in `gradle.properties` (r30, 30.0.16248370). `go/build.sh` refuses any other NDK. Also update `ndk:` in `fdroid/eu.stedb.mysyncthing.yml`.
- compileSdk 37 (Compose 1.12 and Navigation 2.10 require it); targetSdk 37 since 2026-11-12. Kotlin 2.4 needs R8 9.1.29+, so keep AGP and Kotlin in step (developer.android.com/build/kotlin-support).
- Release APK is arm64-v8a only (`abiFilters` in release); `vcsInfo` off. Signing happens on the user's PC only, never in CI.
- F-Droid recipe builds Go from source: `srclibs: go`, checked out in prebuild at the `toolchain` line of `go/bridge/go.mod`, built in `build` with `make.bash`, bootstrapped by forky's golang-go (Go 1.26 needs >= 1.24.6 to build). Prebuild seds `go/build.sh` to `GOTOOLCHAIN=local` with that Go first on PATH, so no prebuilt toolchain is downloaded. CI's `fdroid` job writes fdroiddata's `srclibs/go.yml` and must still match the release APK byte for byte. `fastlane/.../en-US/images/icon.png` (512 px) is rendered from the adaptive icon (viewport 12..96 of 108): F-Droid cannot render vector-only launcher icons.
- `sign-release.sh` signs with `--v1-signing-enabled false --alignment-preserved`: apksigner's defaults add v1 JAR files and re-align, so the signed APK stops being the build plus a signature and F-Droid's apksigcopier check fails (v0.1.0 was first signed that way). Before signing, the script checks the draft's APK against its build provenance (`gh attestation verify`: built by `reproducible.yml` on GitHub's runners from the tag, at the commit the local checkout has for that tag), so a replaced draft file is never signed. The script runs that apksigcopier check itself, refuses a key other than the recipe's `AllowedAPKSigningKeys`, and removes the unsigned APK from the release. After the upload it keeps the latest signed APK and its `.sha256` in `release/` (gitignored, only one version): before 2026-10-31 the signed APK existed only in its temp dir and on the release page. Release notes come from the fastlane changelog (`draft-release` job).
- gomobile embeds absolute module paths in the .so build info; `go/build.sh` builds via the symlink `/tmp/mysyncthing-go/go` so they match everywhere. Do not remove. `/tmp/mysyncthing-go/` is a mode-700 directory of the building user (the lock is in it too) and the script refuses anything else there, since `/tmp` is shared; up to 0.4.0 the symlink was `/tmp/mysyncthing-go` itself, so those binaries carry another path.
- Any dependency/plugin bump: regenerate `gradle/verification-metadata.xml` (command in README).
- Syncthing v2.1.7 (submodule `go/syncthing`, commit d6d0181f7). Keep `go/syncthing.version` in sync when bumping.
- Build via `:app:assembleDebug`; `:core:bundle*Aar` fails by design (local Go AAR dependency).
- GitHub Actions: pinned by full commit hash with a `# vX.Y.Z` comment (user's choice 2026-10-27: a moved tag could change what CI runs). All on Node 24 (Node 20 is deprecated: runners warn and force it): checkout v5.1.0, setup-java v5.7.0, setup-go v6.5.0, setup-gradle v5.0.2, upload-artifact v7.0.2, download-artifact v8.0.2 (fails on a digest mismatch). Bump steps in README; check `using:` in the new commit's action.yml.
- Android link needs `-ldflags=-checklinkname=0` (wlynxg/anet uses go:linkname into net). Upstream requires Go 1.26.2.

## Working rules
- Usage reporting: ask once natively at first start with upstream's wording and condition (urAccepted > -1, urSeen < urVersionMax), never pre-answer it; Settings has a switch. User's wish, out of respect for upstream.
- This file is public on purpose, so any session can resume the work. Nothing about the user's network or machine (hostnames, IPs, usernames, device IDs, home paths, personal e-mail) may go into it, or into code, comments, commit messages or PR text. Use placeholders. Local test notes live in Claude's own memory folder, not in the repo.
- `scripts/check-leaks.sh` enforces this in CI (job `privacy` in `build.yml`) over all files and history; run it before each push. It is allowlist-only (user's choice, no secret denylist): every IP, IPv6, URL host, host:port, LAN name (.lan, .local, ...), UNC path, user@host, home path, device ID, e-mail and commit identity must be a public host on its list or a placeholder (loopback, RFC 5737 ranges, example.com, `${...}`, `<...>`). Add a new public host to `public_hosts` there; never add a private one.
- Commit as `Claude <noreply@anthropic.com>` (repo-local git config); the machine default identity contains the username and hostname.
- Coalesce changes: few pull requests and few CI runs (user's rule, 2026-11-14). One short-lived branch per pull request; GitHub deletes it on merge. Merge commits only.
- Finish every check locally first (unit tests, lint, emulator and phone checks) and push or open a PR only when all of them pass; GitHub CI comes last (user's rule, 2026-10-25).
- Propose GUI designs and get the user's answer before building them.

## Notes by subject
The long notes are in [docs/dev-notes.md](docs/dev-notes.md):
- Emulator test results (2026-10-24/25, API 35 and 36 AOSP x86_64 images)
- GUI decisions (2026-10-25)
- Widget (merged in PR #4, 2026-10-25)
- Edge to edge and first phone test (2026-10-25)
- Security audit (2026-10-25)
- Web-GUI features made native (2026-10-25)
- Nord 4 UX fixes (2026-10-26)
- Stress-test fixes (2026-10-27)
- Import from Syncthing-Fork (2026-10-26)
- Input validation audit (2026-10-26)
- Fork features: run conditions, proxy, export, exact times (2026-10-27)
- SBOM (2026-10-27)
- Internationalization (2026-10-27)
- App hardening (2026-10-28)
- Security policy and privacy page (2026-10-28)
- Docs: features and testing (2026-10-28)
- Screen tests (2026-10-28)
- End-to-end tests (2026-10-28)
- Wi-Fi multicast lock (2026-10-28)
- Lint and compiler warnings (2026-10-29)
- Go toolchain and govulncheck (2026-10-29)
- Vulnerability alerts (2026-10-29)
- Run-condition pause (2026-10-29)
- Per-device pause (2026-11-02)
- Devices tab and the 30 min status rule (2026-11-02)
- Per-device sync windows (2026-11-02)
- Upkeep check (2026-10-30)
- Screens keep core values across recreation (2026-10-30)
- Santa hat easter egg (2026-10-30)
- Free-software audit (2026-10-31)
- Deep monkey test (2026-11-02)
- CI triggers (2026-11-05)
- Phone defaults: one connection, recent changes per folder (2026-11-05)
- Back action pops only its own screen (2026-11-05)
- Conflict copies (2026-11-05)
- Default folder location blocked, New folder pre-filled (2026-11-08, for the release after 0.3.0)
- Length limits (2026-11-11, into 0.3.0)
- Android 17, targetSdk 37 (2026-11-12)
- Network tests (2026-11-13)
- This phone card keeps its height (2026-11-14, for the release after 0.4.0)
- Hardening batch (2026-11-14)
