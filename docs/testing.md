# Testing and hardening

A sync app can do more damage than most apps: when Syncthing records a file as deleted, every
paired device deletes its copy too. So every change to MySyncthing goes through automated
checks on each pull request, and every feature is also checked by hand on emulators and phones before
it is merged. This page lists what is tested, how, and what the tests found. The numbers come
from the test reports of October 2026, with Syncthing v2.1.7.

All tests use dummy data: generated files, made-up configurations and test devices. No test
uses anyone's real folders, devices or backups.

## At a glance

| Area | How | Result |
|---|---|---|
| Every pull request | On GitHub: build, Go and Kotlin unit tests, screen tests, lint, text, leak, manifest, workflow and SBOM checks, a twin build that must be byte-identical. On the PC: the emulator end-to-end tests, with their results in the pull request | Required to merge |
| Screen tests | 27 screens in light, dark, the largest font, Italian and German, compared with reference images; forms; TalkBack names; text contrast with normal and high-contrast palettes | 135 reference images, 4 contrast checks per screen, on every pull request |
| End-to-end | The app on emulators against a real Syncthing peer: pairing, sync both ways, restore, run conditions, device pause, the Devices tab, own sync windows, typed text, idle dialog, process death, All files access taken away, monkey | Android 15 and 16 on the PC (`scripts/e2e/pc.py`), before every merge; each release commits its results in [`docs/e2e/`](e2e/) |
| App updates | The last release, set up and syncing, updated in place to the new build in the middle of a download | Android 15 and 16, before each release: keys, config, database, settings and permissions unchanged; sync back without opening the app |
| Input validation | Every input field checked against the core's own rules; property tests with 20,000 random inputs each | 14 bugs found and fixed |
| Fuzzing | 9 Go fuzz targets on what reaches the core or comes from a file | 1 core bug found and fixed; long runs clean |
| Security reviews | An audit of the app and a gap analysis against common practice | 7 findings fixed; hardening batch below |
| Interruptions | Rotation, Android reclaiming the app, switching away mid-task, permissions taken away | 4 bugs fixed, 1 of them a data-loss risk |
| Stress | 50,000 files, event bursts, full storage, 15,000 random UI events | No crash or data loss; full-storage bugs fixed |
| Deep monkey | About 6 h of random UI events on Android 15 and 16: German at the largest font in dark mode, "Don't keep activities" with rotation, a live peer changing files | 2 ANRs found and fixed; no app crash of its own |
| Sync scenarios | Conflicts of every kind, three devices, send- and receive-only, copy limits, 5,000 files, a 1 GB file, 200 conflicts, flaps, the count after a kill and a reboot | All pass on Android 15 and 16 (0.3.0); 1 upstream data-loss bug found (#80) |
| Memory soak | 10 h 21 min of syncing and GUI use, sampled every minute | No leak |
| Flash writes | Every write of the app traced while idle and while syncing | 0.02–0.14 MB/h when idle |
| Reproducible builds | Three independent builds of the release APK | Byte-identical |

## On every pull request

The checks below run on GitHub for every pull request, and again on `main` after the merge.
The emulator end-to-end tests are not among them: they run on the PC (see [End-to-end tests](#end-to-end-tests)),
which leaves GitHub with checks of a few minutes and no emulator to die halfway.
A new commit on a pull request cancels the run of the commit before it. The `main` branch is
protected: a change reaches it only through a pull request whose checks have passed.

| Job | What it checks |
|---|---|
| `android` | Builds the Go core and the app; runs the tests of the patched core package (`lib/api`), `go vet` and the Go bridge tests (which also run every fuzz target's seed inputs), the Kotlin unit tests, the screen tests (below) and Android lint of both modules. Lint and Kotlin compiler warnings fail the build; lint's settings and the few checks it skips, each with its reason, are in [`lint.xml`](../lint.xml). Then `scripts/manifest-surface.py` compares the release app's permissions and the components other apps can reach, from the merged manifest of all libraries, with the committed list ([manifest-surface.txt](manifest-surface.txt)): a library that adds a permission shows up in review, not in a release |
| `strings` | `scripts/check-strings.py`: text for people only in string resources, so it can be translated, and every string used with the number of arguments it expects (a missing one crashes at run time) |
| `privacy` | `scripts/check-leaks.sh`: no personal detail (addresses, host names, device IDs, home paths, e-mail addresses) in any file, commit message or commit identity in the whole history. It works by allowlist: anything that is not a known public host or a placeholder fails, so a new kind of leak is caught without updating a list |
| `sbom` | Regenerates the [software bill of materials](sbom.md) and fails if it differs from the committed one, or if a dependency has a licence it has not seen before |
| `workflows` | zizmor audits the GitHub workflows themselves: script injection, credentials left in the checkout, permissions, unpinned actions |
| `reproducible` | Builds the release APK twice with different paths, time zones, locales and umasks, and fails if the bytes differ (a diffoscope report shows where). Checks that the Go modules inside the APK match the SBOM. On tags and manual runs, F-Droid's own build image builds it a third time and must match too (up to 90 min, so not on every pull request). A tag also needs the PC end-to-end results of its commit, committed as `docs/e2e/<version>.md` (`scripts/e2e/check-results.sh`) |

CodeQL analyses the Kotlin app, the Go bridge and the whole Go core with our patches, from a
real build, plus the workflows and scripts: on every push to `main` and weekly.

Every day a scan checks for newly published vulnerabilities: govulncheck on the Go bridge and
the core (it reports only vulnerabilities in code the app can reach), the OSV database for
every other library in the [SBOM](sbom.md), and GitHub's advisories for the pinned actions.
Anything found opens an issue, which closes itself once it is fixed. Advisories that cannot
affect the app are listed with their reason in
[`scripts/vulns/accepted.toml`](../scripts/vulns/accepted.toml): today the upstream web GUI's
AngularJS 1.3.20 and moment 2.19.4, which only Syncthing can replace.

Every month, and on every tag, an upkeep check lists newer releases of everything the build
pins (Syncthing, Go, Gradle, the NDK, the libraries, the actions, the scan tools) and checks
that F-Droid keeps up: its recipe must match ours, and it must publish each release within 10
days. Weekly it warns before GitHub turns off these scheduled checks after 60 quiet days. See
[Keeping up to date](../README.md#keeping-up-to-date).

The workflows use a read-only token and leave no credentials in the checkout, and every
GitHub Action is pinned by its full commit hash, so a moved tag cannot change what CI runs. A
release's APK and SBOM carry a signed build provenance attestation, which anyone can check
with `gh attestation verify` (see the [security policy](../SECURITY.md)).

Kotlin unit tests cover, among others: the form checks, the run conditions and sync windows (also: a device added while they block
syncing is paused too, and only the user's own pauses stay afterwards; a property test pauses and
resumes devices at random, also while everything is blocked, and checks that the screens always
show the user's own choice and that it holds when the block ends), the overall status while no
device is connected (a property test: grey "Waiting for devices" until 30 min after the last
device that is not paused was seen, or after the start, the end of a block or a device resumed
when that is later, so a device resumed after days gets its 30 min too (a second property test
against a plain model), then red; the status is summarized again
then by the wall clock, and a device that connects ends the wait; the alarm for it never wakes the
phone), "Sync windows" in amber
exactly when the sync windows are what pauses the devices (a property test against the run
conditions), devices' own sync windows (the week as 10,080 minutes: the mask checked against
the windows minute by minute, the next opening, stretches across midnight and the week's end;
a property test pauses, resumes and holds devices at random against their own windows and
checks that only the user's own pauses stay; the grouped windows on the screen give back
exactly the mask, also a property test), "last seen" in the phone's time zone, the
notification and widget texts, the REST client's paths, version restores, the settings
migration and writes on a full disk, the Syncthing-Fork import preview, and the translations
(every translated string must take the same placeholders as the English one). Go tests start
and stop the real Syncthing core in-process and check the GUI lock, restarts in place, the
proxy, the backup export in all three formats (round trip, wrong passwords, known-answer tests of the AES zip
format, a sealed v1 golden file that must keep opening, every flipped bit and cut of a sealed file refused, Argon2id
parameters from a file bounded before any key derivation; devices' own sync windows only under device IDs as the core writes them, at most 256,
and dropped from the preview for devices the backup does not pair with) and the Fork import. Backups are treated as untrusted input: a hostile backup (external
versioning, a versions folder that would be cleaned out, a folder in the app's own files,
overlapping folders, bidi characters, relays and other servers, open GUI settings) is imported
and the core started on it, and none of it is left; a backup replaced after its preview is
refused, and a zip whose directory alone would fill memory too.

## Screen tests

The app's real screens run on the JVM (Robolectric, Android 16) with made-up folders and
devices: three folders (up to date, syncing, stopped by an error), three devices (on the LAN,
through a relay, offline and paused by the user) and a folder offer. No real device ID, name or address is used.

- Screenshots: 27 screens, from the first-run checklist to the export form (Strong, Compatible and None), each in light,
  dark, the largest font (200 %), Italian and German (the longest text): 135 images in `app/src/test/screenshots/`.
  The device list and a device's screen are also shown while a run condition pauses every device,
  and the device list after 30 min with no device connected; the device list and screens while
  devices' own sync windows hold them or never let them sync, and the sync windows editor for a
  device (overlapping, under 1 h, never, default) and for all (under 1 h). CI
  compares every screen with its image and attaches the difference when one changes.
- TalkBack: on every screen, every control that can be tapped or switched must have a name.
- Contrast: on every screen, every text stands out from what is behind it by at least 4.5:1
  (WCAG AA), measured on the drawn pixels, with the phone's usual palette and with a high-contrast
  one (Android's Colour contrast setting turns card colours dark in light mode), in light and
  dark: every screen of the screenshot list × 4 palettes. Disabled controls are left out; they are faint by design.
- Forms: the device ID check character, a device already added, the suggested path for an
  offered folder, proxy and export password errors, "Discard changes?" only after a change,
  and a shown password marking the window secure.
- Device pause: each card's toggle shows the user's own pause and rests while a run condition
  pauses everything, with the reason on the card; the device's screen still sets the choice then.
- Devices tab: "Pause syncing" is a switch of the stored setting and "Sync windows" opens its
  screen, both showing their value with the default marked, and neither is left in Settings;
  every card that is not connected says when the device was last seen (or never), after the
  reason it is paused; the device's screen no longer does.
- Own sync windows: the device's editor keeps Save off while its windows never overlap the
  global ones, and saves windows under 1 h with a warning that names the stretches; the global
  windows there are read only. The global editor names the devices its new windows would leave
  with no time and saves only on "Save anyway". Cards of devices held by their own windows have
  a resting pause button; "Never syncs" is red on the card and the device's screen.
- Largest font: the widget's second line shows only while it fits under the first (it was cut at
  200 % on the emulator), and the widget is drawn again when the font size changes that, with no
  new status (WidgetRedrawTest), and at that size the tabs' status gets its own row under the title,
  up to two lines (StatusBarFitTest: every status in every language at 360 and 412 dp; on a
  Pixel 7's width all read in full but the pause outside the sync windows with its next time,
  and none runs into the screen below).

Both checks were tried on purpose against a broken screen, and both failed as they should.
Dialogs with a text field are left out: on Robolectric they never settle. The end-to-end
tests cover them on the emulators.

## End-to-end tests

The debug app runs on an emulator (plain Android 15 and 16 images, Pixel 7) against a real
Syncthing peer built from the same patched core. The peer runs on the build machine's loopback
only, with discovery, relays and NAT off, so it never meets a real device; it reaches the
emulator through `adb`. `scripts/e2e/run.py` runs the steps in order, and refuses to run on a
real phone.

They run on the PC, not on GitHub, since 11/2026. In November 2026 GitHub temporarily suspended the
maintainer's account, so CI was slimmed down to light checks to prevent further suspensions. The
emulator runs were the heaviest part of it, duplicated the PC's own runs, and twice died halfway
(issue #78). `scripts/e2e/pc.py` does a whole run for one
commit: from a clean checkout it builds the app, the test APK and the peer, then on a fresh Android 15
and then a fresh Android 16 emulator runs run.py and the fast [sync scenarios](#sync-scenarios), and
writes `build/e2e-pc/summary.md` with the commit, the emulator images and each step's result and time.
That summary goes into the pull request. For a release, `pc.py --release` also writes it to
`docs/e2e/<version>.md`, which is committed before the tag; CI refuses a tag whose file is missing,
did not pass on both versions, or names a commit that differs from the tag in anything but `docs/`,
`fastlane/` and top-level `*.md`. Unlike the GitHub runs these results are not publicly reproduced:
they are the record of a run on the maintainer's PC, and anyone can repeat it with the same commands.

| Step | What must hold |
|---|---|
| Pairing | Fresh install: the checklist, the usage question, the peer added by typing its ID, the peer's folder offer accepted with Trash can versioning |
| Sync both ways | 21 files from the peer arrive; a file written on the phone reaches the peer, which then sees the phone at 100 %. The peer added on the fresh install has 1 connection, and the peer (3 by default) opens no extra ones |
| Restore a version | The peer changes a file; the old one is restored from the phone's Trash can, and the peer gets it back too |
| Conflicts | The peer and the phone change the same file while apart: one edit stays, the other becomes a conflict copy that reaches the phone. The summary and the folder's card count 1; a stopped core counts none, and a restart finds the copy again by walking the folder; deleted on the phone, the count goes and the peer deletes its copy too |
| Run conditions | Wi-Fi off (mobile data, which is off for syncing by default): the peer loses the phone; Wi-Fi on: it comes back. Every step starts on Wi-Fi, with the emulator's internet check off so a runner without Google's check server still uses Wi-Fi |
| Device pause | Paused from its card: the peer loses the phone. With all syncing paused, the card's toggle is disabled; resumed on the device's screen, it stays paused until syncing is allowed, then the peer connects. Paused on its screen while blocked, it stays paused after the block (the peer stays away for 20 s), and resumes from the screen |
| Devices tab | "Pause syncing" on the tab: stored, the peer loses the phone, the status says paused and the cards' toggles rest; the card says when the core last saw the peer; switched off, the peer comes back. The peer pausing the phone on its side: the status is the grey "Waiting for devices", red due 30 min after the peer was last seen. "Sync windows" opens its screen and changes nothing |
| Own sync windows | The peer's own windows closed now: the peer loses the phone, the core holds it, the card names the next opening and its pause button rests, the device's screen says why; turned off in its editor (Save), the peer comes back and the hold is released. Own windows that miss the global ones: the peer stays away and the card says "Never syncs". The windows are set around the phone's clock, so no alarm is waited for |
| Typed text | Text typed in a rename dialog and in list dialogs (Settings, device defaults) survives the screen being recreated. Checked at once, with no wait: dialogs on values read from Syncthing are there on the recreated screen's first frame, and Settings keeps its scroll position. Afterwards only one copy of the screen stays in memory |
| Folder browser | Add folder, Browse, Download (which Android's own picker refuses), New folder `mysyncthing-test-browse`, Use this folder: the form takes the folder's name and the core gets the folder at that path; removed again after |
| Idle dialog | An open rename dialog draws fewer than 30 frames in 5 s (a re-measure loop seen on Robolectric would draw about 300) |
| Recent changes | A file written on the phone reaches the peer; the folder card's history icon opens that folder's recent changes, which list the file as changed on this phone |
| Stop and start | The user's stop: the main thread answers within 1 s throughout (the core's shutdown runs in the background), the stop ends within 20 s (it does not wait for the 60 s event long-poll), the peer loses the phone; started again, it comes back. A start right after a stop starts the core once the stop is done. A stop with the web GUI open shows "Syncthing is stopped" with Start there (it was blank), and Start brings the core and the peer back |
| Process death | The app's process killed in the background: the service restarts by itself, reconnects and syncs a new file |
| All files access taken away | For 45 s: the core does not start, the peer sees no deletions and its files are unchanged; with access back, the phone reconnects |
| Launcher starts | Opened first by an intent other than the launcher's, then three times from the launcher: one copy of the main screen stays, not four |
| Remove goes back to the tab | A folder and a device removed on their screens: the Folders and Devices lists show, not a blank screen or the wrong tab |
| Monkey | 30,000 random events with a fixed seed (about 13 min), BACK and rotation included, the app pinned so no tap leaves it (pinned again within 2 s where Android 16's launcher lets a tap through): no crash, no freeze, no blank screen |

The run also fails on any crash or ANR of the app in the log (crashes and ANRs of other processes are listed), and on a blank screen: the app logs an error when its back stack empties, which no crash check would see. It counts StrictMode reports. On the PC
emulators (2026-10-28) every step passed on both versions, with 0 StrictMode reports.

On a PC the runs keep the emulator off Syncthing's public servers: `E2E_OFFLINE=1 scripts/e2e/emulator.sh <api>`
gives the emulator a DNS server where nothing answers (the script checks that no name resolves), and `--offline`
(run.py, scenarios.py, monkey.py) turns off the phone core's global discovery, relays and NAT traversal after pairing.
The DNS block is the one that holds: the monkey can switch those options back on in Settings. `pc.py` runs this
way by default; `pc.py --online` keeps the defaults, as CI did.

### Sync scenarios

`scripts/e2e/scenarios.py` runs after run.py, from a fresh install paired with the same kind of
peer. It drives everything from the build machine: files on the phone through `adb`, the phone's
core through its REST API, the app's screens through uiautomator. Each scenario sets the devices
apart, changes files on more than one side, brings them together, waits until every side holds the
same files, and then checks the files on every side and the count on the folder card.

| Scenario | What must hold |
|---|---|
| Edit and edit | Both edit a file: one conflict copy, and file plus copy hold both edits |
| Edit and delete | Edited on one side, deleted on the other, each side deleting once before and once after the other's edit: the newer change wins, as Syncthing decides by time. An edit that wins leaves no copy; a deletion that wins keeps the edit as one conflict copy |
| Both new | The same new name on both sides: one copy |
| Rename and edit | Renamed on the phone, then (2 s later) the old name edited on the peer: both names stay, no copy. A rename deletes the old name, and the newer change wins |
| Odd names | Subfolders, spaces, accents, CJK, emoji, no extension, a dotfile, two dots, upper case: one copy each, named after its file |
| Copy deleted | A copy deleted on the phone and another on the peer: gone everywhere, and the count follows. The Trash can keeps the one deleted on the peer, and the count leaves it out |
| Copy of a copy | Both edit the same copy: Syncthing keeps no copy of a copy |
| Web GUI login | A new API key, user and password at every start; the old key is refused |

These eight (`--only fast`, about 5 min) run after run.py in every `pc.py` run. By hand, the PC also runs:

| Scenario | What must hold |
|---|---|
| Copy limit | 12 conflicts in a row on one file with the phone's watcher off: 10 copies stay (Syncthing's default `maxConflicts`), and the count follows the deletions |
| Copies from elsewhere | 12, then 120 copies arriving from the peer: the card counts them |
| Ignored copies | Copies hidden by an ignore pattern still count, and stop counting once deleted |
| Three devices | A second peer joins, and all three edit one file: two or more copies holding the two losing edits, named by the short IDs of the three devices. The two losers come back seconds apart: within the same second, Syncthing (v2.1.7) names their copies alike and deletes one, losing that edit (issue #80, upstream). Should it still happen, the summary lists it as a known upstream bug |
| Send-only | The peer's change waits; Override… on the folder's screen puts the phone's version back on the peer |
| Receive-only | A local edit and a new file; Revert… on the folder's screen undoes both |
| Long name | A 240-byte name in conflict: the copy's name is too long for Android. Observed only: the folder's problem and both sides' files are recorded |
| Stress (`--only stress`) | 5,000 small files; a 1 GB file paused halfway and resumed, identical at the end; 200 conflicts at once; 10 min of edits from both sides, after which the count equals the copies on disk; 50 rapid pause/resume rounds of the device and the folder, plus 10 through the folder's button; airplane mode on and off every 30 s during a transfer; the count after the app's process is killed and after a reboot. The app's memory and the core's goroutines are recorded after each step |

### App updates

People update by installing the new APK over the old one, by hand or through F-Droid. Android
keeps the app's data and kills the running app. `scripts/e2e/upgrade.py` installs the debug
build of the last release, lets that release's own end-to-end test pair it with the peer and
sync, and adds more kinds of settings through the core's REST API: a second device, an
untrusted one, a receive-only folder with staggered versioning, a folder shared encrypted,
ignore patterns and changed options. Then it installs the new build over it while a 48 MB
download is running. Both builds are signed with the same key.

When the new build has a newer Syncthing core, the core migrates the old config and database
here, so a breaking change upstream fails the test too. It runs on the PC emulators before
each release, from the last release and from the oldest one still in use (a step of releasing
in the [README](../README.md#reproducible-builds-and-releases)), not in CI.

| Step | What must hold |
|---|---|
| Sync comes back | Without the app being opened: the process restarts, the service runs and the peer reconnects |
| Nothing lost | The keys (so the device ID) are byte-identical. Every value of the core's config is unchanged, except the format version and the GUI's port and login, both new at every start; a newer core may only add values, which are logged. The ignore patterns read back the same. The database files are the same files, so there is no full rescan and no "moved from another phone" note. Every app setting is unchanged, except the login stored by 0.2.0 and older, which must be gone. All files access, notifications and the battery exemption are still granted |
| The interrupted download | Finishes after the update, identical to the peer's file, with no conflict copies |
| In the new build | The setup checklist is done, the peer and the folder are there, and new files sync both ways |
| A user's Stop | Holds through an update |
| Start at boot off | An update still starts sync again when it was running before |
| Going back | Android refuses the old build as a downgrade |

On the PC emulators (2026-10-30), from 0.1.0: every step passed on Android 15 and 16, with the
update landing in the middle of the download. By hand with release builds (0.1.0 to 0.1.2, signed
with a test key), sync was back about 2 s after the update without the app being opened, and the
device ID, folder, device, run conditions and sync-time window were all kept.

## Input validation and property tests

Every value a person or another program can put into the app was checked against the source
of the Syncthing core: what it must be, what the app checks, and what the core checks. The
full table, one row per field, is in [input validation](input-validation.md). The audit
found 14 bugs, all fixed, for example:

- A paired device offering a folder named `..` had the whole internal storage suggested as
  the folder's location: one quick tap on Save would have shared everything with that device.
  Suggested names now go through a port of the core's own path cleaner.
- A damaged or foreign key file was silently replaced with a new identity, so the phone
  became an unknown device to all its peers. The core now refuses to start and says why.
- Ignore patterns the core cannot parse were "saved", and then the folder stopped. The app
  now runs the core's own parser before saving.
- A speed limit of 3,000,000 MB/s overflowed and was stored as 1 KiB/s; `NaN` and `1e3` were
  accepted. A number typed with Persian or Arabic digits was stored as typed and silently
  read as the default by the core. Such digits are now refused with a clear message.

Each form check has property tests: 20,000 generated inputs each, with a fixed seed so a
failure can be repeated. Every bug fixed in Kotlin has a test that fails when the old code is
put back. Later inputs got the same treatment as they were added: the proxy, the sync windows,
the backup passwords, the Fork import.

## Fuzzing

Go's fuzzer feeds random input to everything that reaches the core or reads a file from
outside. The seed inputs run with every push; the long runs below were done when each target
was written (without network access, since a fuzzed configuration can switch on discovery).

| Target | Input | Invariant | Long run |
|---|---|---|---|
| `FuzzPrepare` | `config.xml`, key files, GUI login | Refused, or the GUI is locked to that login and an existing key is never replaced | 60 min, 692,536 inputs |
| `FuzzGUIGuard` | GUI settings changes | Refused, or the login and lock are unchanged | 20 min, 383,435 inputs |
| `FuzzRESTConfig` | Config API requests to a running core | The app's login still works, a wrong one doesn't, the API needs credentials | 45 min, 607,758 inputs |
| `FuzzCheckIgnores` | Ignore patterns | What the check accepts loads without error | 45 min, 1,344,638 inputs |
| `FuzzProxyFrom` | Proxy settings | Refused, or the proxy URL reads back the same | 3 min |
| `FuzzReadOwnSettings` | This app's backup settings, devices' own sync windows too | Refused, or they read back the same | 3 min; with own windows 90 s, 985,058 inputs |
| `FuzzReadForkBackup` | Backup zips (Syncthing-Fork's and this app's) | Refused, or read and passing the import policy, never a crash; own sync windows only for devices the backup pairs with | 10 min, 499,258 inputs |
| `FuzzImportPolicy` | `config.xml` of an imported backup | Whatever the core reads, the import policy leaves nothing that runs a command, cleans out or reaches files outside shared storage, or reports elsewhere; a second run changes nothing | 15 min, 2,534,263 inputs (found a slice difference, fixed) |
| `FuzzReadJavaPrefs` | Syncthing-Fork's saved preferences (Java serialization, read by a strict allowlist parser, never deserialized) | Refused or read, never a crash | 3 min |

`FuzzRESTConfig` found a bug in Syncthing itself: six GUI setting changes in a row made the
GUI and API unreachable for 15 seconds, because the core's supervisor counted each listener
restart as a failure and backed off. It is fixed by a small core patch with a regression
test (`go/patches/0002`).

## Security reviews

**Audit (2026-10-25).** The app was reviewed for security and privacy; all 7 findings were
fixed:

- Android's phone-to-phone transfer is limited to the configuration, keys and app settings;
  the database is left out, and cloud backup is off (see [privacy](privacy.md)).
- The web GUI login, 64 random characters, is new at every core start and locked: the core refuses any
  change to it, and the previous login, API key and web GUI sessions stop working (`TestLoginNewEveryStart`).
- A screen opened from a notification only opens for a request that is really pending.
- The built-in web view sends no usage metrics and no Safe Browsing checks.
- Request notifications show no addresses, and lock-screen notifications no names.
- CI got a read-only token; the leak check was added.

**Gap analysis (2026-10-27).** The project was compared with common security practice for
open-source Android apps, which gave a list of 19 items. Done so far:

- `main` is protected by a ruleset; CodeQL, zizmor and the manifest guard run in CI (above);
  releases carry a build provenance attestation.
- Web GUI containment: the built-in web view loads only the core's own address. Anything
  else, including content and file URLs, is refused. A full walk through the web GUI on an
  emulator gave 0 refusals of its own content.
- Quiet release logs: release builds pass only the core's warnings and errors to logcat. Its
  routine lines name devices, addresses and folders, and logcat can end up in bug reports.
- While a password is shown in clear text (backup export and import, proxy, encrypted
  sharing), the window is marked secure: screenshots and screen recordings of it are black.
- StrictMode in debug builds reports disk and network use on the main thread. A normal
  session, web GUI included, gives 0 violations, after 3 main-thread disk reads it found
  were moved off.
- [Security policy](../SECURITY.md) with private reporting through GitHub, and a
  [privacy page](privacy.md).

## Emulators and phones

Every feature is checked by hand before it is merged, on Android 15 and Android 16 emulators
with a local test peer; the larger ones also on two real phones (Android 16 and Android 15). Screens are
checked in light and dark mode, at the largest font size, and with TalkBack: every button is
named, and every switch and checkbox reads as on or off. The Italian, Spanish, German and French
translations were walked screen by screen the same way.

Emulator results include: start at boot, an app update that keeps the device ID and folders,
the app swiped away, deep Doze without a battery exemption, the app killed in the middle of a
transfer (Android restarts it and the transfer resumes), all run conditions
switched on and off rapidly, and a device the user paused staying paused.

On a real phone:

- One hour unplugged, screen off, battery optimisation on: the app was not killed, and files
  arrived in 13–30 s.
- 3.25 GB over mobile data in about 11 minutes (about 5 MB/s), with no errors; edits and
  deletes went back the other way.
- Switching from mobile data to Wi-Fi reconnected in 18 s. From Wi-Fi to mobile data it took
  up to about 7.5 minutes, because the other device keeps the dead connection until its
  5-minute timeout (Syncthing's own behaviour).

## Interruption tests (2026-10-26)

What happens when Android or the user interrupts the app in the middle of something:

| Scenario | Result |
|---|---|
| Rotation while editing | Fixed: typed text in rename dialogs was lost; an untouched form asked "Discard changes?" |
| "Don't keep activities" (Android destroys every screen that is left) | Same two bugs, fixed; everything else came back as it was |
| App process killed in the background with an editor open | Text, prompts and the back stack restored |
| Leaving a screen in the middle of a task | Fixed: leaving Restore versions mid-way restored the files but skipped the step that makes them sync |
| Screen lock while editing | Pass |
| Notification permission taken away while running | Pass: no crash, the service comes back |
| All files access taken away while running | **Fixed: data-loss risk** (below) |

The serious one: when All files access was switched off, Android killed the app as expected,
then restarted its service, which started the core without the permission. The core then saw
only the files the app had written itself, and recorded every other file as deleted: on one
test folder, all 41 files. A peer connecting at that moment would have deleted its copies. In
the test the peers were kept away, so nothing spread. Now the service and the boot receiver
check the permission first: without it the core does not start, and a notification says that
syncing stopped and why.

Found on the way, in Syncthing itself: restoring a Trash can version of a file in a subfolder
fails. That is left to the Syncthing project.

## Stress tests (2026-10-27)

| Test | Result |
|---|---|
| A folder with 50,000 small files (100 folders of 500) | Synced in about 5 minutes (about 165 files/s). Memory 161 MB before, 237 MB at the peak, 224 MB after. A rescan with nothing changed: 2.4 s |
| Cold start with that folder | Screen drawn in 565 ms, core answering after 1.7 s, folder scanned after 3.8 s |
| 5,000 files changed on the peer and 2,000 new files on the phone at the same moment | Both sides up to date with 52,000 files after 67 s |
| The peer restarted 10 times, the device paused and resumed 30 times, 50 folder changes in 2 s, 30 rapid taps on a switch | No crash; one notification, no spam; the switch shows what is stored |
| Storage almost full (under Syncthing's 1 % minimum) | Files wait, the folder says why, syncing goes on by itself once space is freed |
| Storage completely full | Found 2 bugs: a settings change crashed the app, and after a failed start nothing restarted the core. Both fixed: the change is refused with a message, and "Try again" works. No data was lost: the configuration, database and settings are written to a temporary file and renamed |
| Random UI events (Android's monkey), 6 seeds | 15,000 events, no crash or freeze |

The fixes were checked again on the emulator before they were merged; that check found three
more points (a deliberate Stop undone by a rotation, a raw core message, a slow read at boot),
which were also fixed first.

## Deep monkey (2026-11-03)

`scripts/e2e/monkey.py` on the PC emulators, Android 16 and 15. Each run starts from a fresh install
paired with the e2e peer, with a second folder offered. The peer changes a file every 20 s and drops
the phone every 5 min. The app is pinned, so BACK can be sent and no tap reaches quick settings.
Three setups at 120,000 events each (about 50 min), plus the end-to-end run with its 30,000 events:

| Setup | What it adds |
|---|---|
| plain | English, light, normal font |
| de-big | German, dark, the largest font (2.0), the peer away for 35 min |
| recreate | "Don't keep activities", much rotation, the peer starting on "Never syncs" |

| Found | Fixed |
|---|---|
| Opened by an intent other than the launcher's, every later launcher start added a copy of the main screen. The monkey stacked 121 copies and then froze the app (ANR) | A copy on top steps aside; the end-to-end run checks that one stays |
| The Keep free dialog read the disk on the main thread (StrictMode, about 50 ms) | Read in the background |
| On Android 16 the launcher's Overview button ignores screen pinning, so a tap could leave the app | The tests pin the app again within 2 s |
| The user's Stop waited on the main thread for the core to shut down; on a slow emulator that froze the app for over 5 s (ANR, CI on Android 16). A Start tapped during a stop was lost | The core stops in the background while the service keeps the process alive; starts and stops run one after the other. The end-to-end run checks both |
| (Found in the check of that fix) Stop could do nothing for up to 60 s on an idle phone: it waited for the app's open request for core events, which could not be cancelled. A backup import could time out on the same wait | Requests that only read are cancelled at once (writes still finish); Stop starts in under 0.1 s. Tested in unit tests and on both emulators |

Left as they are (Android's own, no app code in the stack): on Android 15 a screenshot's "Capture more"
with a rotation during it crashes the app's process in the framework's scroll capture; on Android 16
the same crashes System UI. The monkey runs list both and do not fail on them. Also left (seen in the
walk after the runs): the QR scanner closes when the app is opened again from its icon, as the scanner
library starts it that way; coming back through Recents keeps it open.

Still open ([#68](https://github.com/aiste633/mysyncthing/issues/68)): on CI's Android 15 emulator, now and then a rotation with a
text dialog, the keyboard and the cursor handle up leaves the new dialog window without input focus, so the
monkey's keys time out (an ANR, while the app itself is idle). It did not happen in 100 tries on the PC
emulator; run.py saves the window and input state when it happens. Now that the end-to-end tests run on
the PC only, it shows only if it happens there.

## 0.3.0 test (2026-11-06)

Everything new since 0.2.0, on the PC emulators (Android 16, then 15), with the emulator offline.

| Run | Android 16 | Android 15 |
|---|---|---|
| Sync scenarios, fast and slow (15) | All pass (three devices after its fix) | All pass, 12 min |
| Stress (8) | All pass, about 22 min; the 1 GB file paused at 42 % | All pass, about 22 min; paused at 41 % |
| End-to-end run plus the fast scenarios | 21 min 45 s, plus about 4 min | 21 min 54 s, plus 4 min 7 s |
| Deep monkey, 2 seeds × 3 setups × 120,000 events, conflicts made by the peer and 12 or 120 copies seeded | 6 of 6, 0 crashes, 0 ANRs | 6 of 6, 0 ANRs; 2 crashes of the known framework race in scroll capture (above), no app frame |
| Walk by hand (German, font 2.0, dark, widget) | Conflict count and badge, Recent changes, widget dot, folder browser, export Strong/Compatible/none and import, Remove folder, Show ID in dark, web GUI after Stop and Start: all as designed | The same |

Found: two conflict copies made in the same second on two devices get the same name, and Syncthing then deletes
one of them, losing that edit (upstream, [#80](https://github.com/aiste633/mysyncthing/issues/80)). At font 2.0 the
"99+" badge covers half of the card's history icon (fixed separately). A name of about 240 bytes in conflict cannot
get a copy on Android: each side keeps its own edit and the folder shows the error (upstream behaviour, no data
lost). StrictMode reports came from Android's own frames and the WebView, none from the app. MainActivity instances stayed
flat over each hour (at most 7), so no leak; memory rose from 164 to 254 MB after the big transfers and went back
once the process was killed.

## Memory soak (2026-10-27)

10 h 21 min on an emulator, one app process the whole time, sampled every minute (614
samples): the app's memory and threads, open files, and the core's Go heap and goroutines.

- Every 5 minutes, 10 files changed on the peer. Every hour, a burst of 500 files and the
  peer paused and resumed; every 2 hours, the burst deleted and a 20 MB file added.
- Every 10 minutes a scripted tour of the app (61 tours): the widget's pause and resume,
  folders, devices, settings, recent changes, rotation, leaving and coming back, the screen
  off and on.

| | Start | After 3.5 h | End |
|---|---|---|---|
| Memory when idle (PSS) | 172 MB | 209 MB | 211 MB |
| Threads when idle | 47 | 59 | 60 |
| Go heap in use | 5–10 MB | 6–12 MB | 6–12 MB |
| Goroutines | 124–125 | 123–124 | 121–130 |

**No leak.** Memory rose during the first 3.5 hours, in steps at the two biggest bursts, and
then stayed flat for 7 hours through three more of them. The rise is the high-water mark of
the native allocators and the Go runtime keeping the operating-system threads it made for the
biggest burst, which it does by design. The Java heap, open files and goroutines went back to
the same level after every tour and every burst.

## Flash writes (2026-10-26)

Flash memory wears with writes, so every write of the app was traced (`strace` on all its
threads, and the kernel's count of bytes sent to storage) for 30 minutes per scenario:

| Scenario | Written per hour |
|---|---|
| Idle, screen on, app open | 0.02 MB |
| Idle, screen off | 0.14 MB |
| Idle, peer connected, screen off (also a 2 h 48 min run) | 0.07–0.10 MB |
| A small file changed on the peer every 5 minutes | 3.9 MB |

About 2.4 MB a day when idle. Most of that is Android's own profile saver, not the app. The
app writes no log files (the core logs to memory), and its settings, widget and notifications
write nothing on a timer. The only real volume is Syncthing's database when files change:
about 250 KB per small changed file, less in proportion for big files. A day with 1,000 small
changes writes about 250 MB, far below what phone flash is rated for. These numbers are from a
debug build on an emulator; a release build should write less.

## Reproducible builds

The same commit gave a byte-identical unsigned release APK, with the same SHA-256, from a local
build, both legs of the CI twin build, and F-Droid's build server image. CI checks the twin build on
every pull request, and F-Droid's build on every tag. See [README](../README.md#reproducible-builds-and-releases).

## Not covered yet

- The roaming run condition: emulators cannot simulate roaming.
- Battery and CPU use were not measured; the soak ran on Android 16 only.
- Flash writes of a release build.
- The end-to-end tests use one peer on loopback: no relays, discovery, NAT or slow networks.
- The end-to-end results are a record of the maintainer's PC run, not a public CI run: each
  release's file in `docs/e2e/` names the commit, and anyone can repeat the run with `scripts/e2e/pc.py`.
