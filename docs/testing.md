# Testing and hardening

A sync app can do more damage than most apps: when Syncthing records a file as deleted, every
paired device deletes its copy too. So every change to MySyncthing goes through automated
checks on each push, and every feature is also checked by hand on emulators and phones before
it is merged. This page lists what is tested, how, and what the tests found. The numbers come
from the test reports of October 2026, with Syncthing v2.1.7.

All tests use dummy data: generated files, made-up configurations and test devices. No test
uses anyone's real folders, devices or backups.

## At a glance

| Area | How | Result |
|---|---|---|
| Every push | Build, Go and Kotlin unit tests, lint, text, leak, manifest, workflow and SBOM checks, a twin build that must be byte-identical | Required to merge |
| Input validation | Every input field checked against the core's own rules; property tests with 20,000 random inputs each | 14 bugs found and fixed |
| Fuzzing | 8 Go fuzz targets on what reaches the core | 1 core bug found and fixed; long runs clean |
| Security reviews | An audit of the app and a gap analysis against common practice | 7 findings fixed; hardening batch below |
| Interruptions | Rotation, Android reclaiming the app, switching away mid-task, permissions taken away | 4 bugs fixed, 1 of them a data-loss risk |
| Stress | 50,000 files, event bursts, full storage, 15,000 random UI events | No crash or data loss; full-storage bugs fixed |
| Memory soak | 10 h 21 min of syncing and GUI use, sampled every minute | No leak |
| Flash writes | Every write of the app traced while idle and while syncing | 0.02–0.14 MB/h when idle |
| Reproducible builds | Three independent builds of the release APK | Byte-identical |

## On every push

The checks below run on GitHub for every push and pull request. The `main` branch is
protected: a change reaches it only through a pull request whose checks have passed.

| Job | What it checks |
|---|---|
| `android` | Builds the Go core and the app; runs the tests of the patched core package (`lib/api`), `go vet` and the Go bridge tests (which also run every fuzz target's seed inputs), the Kotlin unit tests and Android lint. Then `scripts/manifest-surface.py` compares the release app's permissions and the components other apps can reach, from the merged manifest of all libraries, with the committed list ([manifest-surface.txt](manifest-surface.txt)): a library that adds a permission shows up in review, not in a release |
| `strings` | `scripts/check-strings.py`: text for people only in string resources, so it can be translated, and every string used with the number of arguments it expects (a missing one crashes at run time) |
| `privacy` | `scripts/check-leaks.sh`: no personal detail (addresses, host names, device IDs, home paths, e-mail addresses) in any file, commit message or commit identity in the whole history. It works by allowlist: anything that is not a known public host or a placeholder fails, so a new kind of leak is caught without updating a list |
| `sbom` | Regenerates the [software bill of materials](sbom.md) and fails if it differs from the committed one, or if a dependency has a licence it has not seen before |
| `workflows` | zizmor audits the GitHub workflows themselves: script injection, credentials left in the checkout, permissions, unpinned actions |
| `reproducible` | Builds the release APK twice with different paths, time zones, locales and umasks, and fails if the bytes differ (a diffoscope report shows where). Checks that the Go modules inside the APK match the SBOM. On pull requests, tags and manual runs, F-Droid's own build image builds it a third time and must match too |

CodeQL analyses the Kotlin app, the Go bridge and the whole Go core with our patches, from a
real build, plus the workflows and scripts: on every push to `main` and weekly.

The workflows use a read-only token and leave no credentials in the checkout, and every
GitHub Action is pinned by its full commit hash, so a moved tag cannot change what CI runs. A
release's APK and SBOM carry a signed build provenance attestation, which anyone can check
with `gh attestation verify` (see the [security policy](../SECURITY.md)).

Kotlin unit tests cover, among others: the form checks, the run conditions and sync times, the
notification and widget texts, the REST client's paths, version restores, the settings
migration and writes on a full disk, the Syncthing-Fork import preview, and the translations
(every translated string must take the same placeholders as the English one). Go tests start
and stop the real Syncthing core in-process and check the GUI lock, restarts in place, the
proxy, the backup export (round trip, wrong passwords, known-answer tests of the AES zip
format) and the Fork import.

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
put back. Later inputs got the same treatment as they were added: the proxy, the sync times,
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
| `FuzzReadOwnSettings` | This app's backup settings | Refused, or they read back the same | 3 min |
| `FuzzReadForkBackup` | Syncthing-Fork backup zips | Refused or read, never a crash | 3 min |
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
- The web GUI login, 64 random characters, is locked: the core refuses any change to it.
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
named, and every switch and checkbox reads as on or off. The Italian translation was walked
screen by screen the same way.

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
build, both legs of the CI twin build, and F-Droid's build server image. CI checks this on
every push. See [README](../README.md#reproducible-builds-and-releases).

## Not covered yet

- The roaming run condition: emulators cannot simulate roaming.
- Battery and CPU use were not measured; the soak ran on Android 16 only.
- Flash writes of a release build.
- There are no automated UI tests in CI; the screens are checked by hand for each change.
