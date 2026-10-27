# Input validation

Every value a person or another program can put into the app, what it must
be, and who checks it. "Core" is Syncthing v2.1.7 as built into the app;
"bridge" is `go/bridge`. Checks that follow a core rule name the core file
they follow. Tests: `app/src/test/.../ValidationTest.kt` (property tests, 20,000
inputs each, fixed seed), `core/src/test/.../RestClientPathTest.kt`, and the
Go fuzz targets and tests in `go/bridge`.

## Form fields

| Field (screen) | Expected | App check | Core check |
|---|---|---|---|
| Device ID (Add device) | 56 base32 characters in 8 groups, 4 Luhn check characters; dashes, spaces, case, `=` and 0/1/8 for O/I/B tolerated | `deviceId()`: the core's parser ported, check characters included; the pre-2014 52-character form refused (4 dropped characters would make one); saved in canonical form; "Already added" | `lib/protocol/deviceid.go`: refuses a wrong check character |
| Device name (Add device, Device, This phone) | Text; required when renaming | Required, trimmed | None |
| Group (Device, Folder) | Text, local to this phone | Trimmed | None |
| Addresses (Device, Defaults for new devices) | `dynamic`, or `tcp`/`quic` URL to a host with an optional port 1-65535 (22000 when left out), or a relay URL | `isDeviceAddress()`; a bare host:port is taken as TCP | None at save; a bad address fails at dial time |
| Download/upload limit (Device, Network, Defaults) | 0 (none) to 2 TB/s, in kB/s or MB/s; stored as KiB/s (a Go int, 64-bit on phones; `Long` in the app, so any value the web GUI sets still loads) | `amountError()` with `decimal()`: plain decimal in 0-9 digits, either separator; no sign, exponent, NaN or Infinity; other scripts' digits refused with "Use the digits 0-9" | None (negative means none) |
| Compression, Connections, Download order, Folder type, Full rescan | One of the listed choices | Choice dialog | Unknown values silently become the default; rescan clamped to 0 to 1 year |
| Listen addresses (Settings) | `default`, or canonical `tcp`/`quic` URL with an optional port (22000 when left out, 0 = any), relay URL, `dynamic+https` URL | `isListenAddress()` | `lib/connections/service.go` skips a non-canonical URL (only logged); `lib/connections/util.go` `fixupPort` adds 22000 |
| Discovery servers (Settings) | `default`, or `https` URL with a host, optional port, `?id=` (a device ID) or `?insecure` | `isDiscoveryServer()` | `lib/discover/global.go`: https, or http only with insecure and noannounce |
| Folder name (Add folder, Folder) | Text; required when renaming | Required, trimmed | None |
| Folder ID (Add folder) | Non-empty, unique, usable in a REST path: no `/`, not `.` or `..`, no control characters | `folderIdError()`; an offer keeps the other device's ID and is only checked for duplicates | Non-empty, unique (`lib/config/config.go`) |
| Folder path (Add folder) | Absolute, in shared storage or on an SD card (`/storage/...`, `/sdcard` accepted and spelled out), no `.`/`..`/empty parts, no control characters, not the same as, inside or around another folder | `folderPathError()`, saved through `folderPath()` | Non-empty only; it creates whatever path it is given |
| Suggested path for an offered folder | One directory in the default location, named after the offer | `safeName()`: the core's `SanitizePath` ported, plus never `.` or `..`, at most 255 bytes; falls back to the ID, then "Folder" | Auto-accept uses `SanitizePath` and skips existing paths |
| Versioning: days to keep (Trash can, Staggered) | Whole number, 0 = forever | `wholeNumberError()`: 0-9 digits only, others refused with "Use the digits 0-9" (Staggered stored in seconds) | `strconv.Atoi`; an unreadable value silently becomes the default |
| Versioning: versions to keep (Simple) | Whole number, 1 or more | `wholeNumberError(min = 1)`, as upstream's web GUI | `strconv.Atoi`; 0 keeps no versions |
| Keep free on this phone (Folder, Defaults) | Decimal 0-100 for %, 0 or more for MB/GB | `amountError()`, as for speed limits | None |
| Encryption password (Share encrypted) | Non-empty; kept exactly as typed, spaces included, so it matches the other devices | Required | None |
| Proxy host (Proxy) | IP address (IPv4 or IPv6, no zone) or DNS name: RFC 1123 labels, at most 253 characters, last label not all digits | `proxyHostError()`, mirroring the bridge | `proxyFrom` in `go/bridge/proxy.go` refuses the same; the core's dialer would fail at every dial |
| Proxy port (Proxy) | Whole number 1-65535 | `proxyPortError()`: 0-9 digits only, others refused with "Use the digits 0-9" | `proxyFrom` |
| Proxy user name and password (Proxy) | Optional; a password needs a user name; each at most 255 UTF-8 bytes (SOCKS5, RFC 1929), no control characters | `proxyUserError()`, `proxyCredentialError()` | `proxyFrom` |
| Export password (Export a backup) | Empty (no encryption, with a warning) or at least 12 characters (WinZip AES has a fixed 1000 PBKDF2 rounds), at most 1024 UTF-8 bytes, valid Unicode, no control characters | `exportPasswordError()`, counted in code points; lone surrogates refused | `ExportPasswordError` in `go/bridge/export.go`, checked again before writing |
| Export password again | The same text | `exportRepeatError()` | n/a |
| Ignore patterns (Folder, Defaults) | One pattern per line, valid for the core's parser; `#include` files must exist | `CheckIgnores` in the bridge runs the core's own parser before saving; the core's answer is checked too | `lib/model` saves any patterns and answers 200, then the folder stops with an error |
| Search (Restore versions, Failed items) | Any text | None needed: plain substring match | Not sent |
| Switches (run conditions, watcher, introducer, ...) | On or off | n/a | n/a |

## Other inputs

| Input | Check |
|---|---|
| QR code scan | Goes into the Device ID field and its check |
| Folder picker | The SAF tree URI is turned into a path, then checked as a typed path |
| Notification and widget intents | `MainActivity` only opens a review for an offer that is pending (security audit 2026-10-25) |
| Folder and device IDs in REST paths | Sent as one escaped path segment (`RestClient.request`), so `?`, `#`, `%` and `\` arrive intact |
| Bridge `Start` credentials | 1 to 72 characters of `A-Z a-z 0-9 - _`, as `Settings` makes them; anything else is refused before a file is written |
| `config.xml`, `cert.pem`, `key.pem` at start | An unreadable identity stops the start instead of being replaced by a new device ID; the GUI login and lock are applied and saved, or the start fails |
| Web GUI config changes | `guiGuard` refuses any change to the GUI login or access settings |
| Run conditions and proxy in an imported backup | `mysyncthing.json` (this app's export) is read strictly: version 1, no unknown or trailing data, a known power source, the proxy checked as above; Fork's proxy address fields go through the same check, a bad one is listed as not imported |
| Stored proxy setting | An unreadable stored value becomes "SOCKS5, nothing else" with no host, which the bridge refuses: the core then does not start rather than connect directly |

## Fuzzing

`go test -run '^$' -fuzz '^<target>$' -fuzztime <time>` in `go/bridge`. The seed
inputs run with every `go test`.

| Target | Input | Invariant |
|---|---|---|
| `FuzzPrepare` | config.xml, cert.pem, key.pem, GUI user and password | Either refused, or the GUI is locked to that login in memory and on disk, and an existing key is never replaced |
| `FuzzGUIGuard` | JSON decoded onto the GUI settings | Either refused, or the login and lock are unchanged |
| `FuzzRESTConfig` | PUT/PATCH bodies to the config API of a running core | The app's login still starts a session, a wrong password doesn't, REST needs credentials |
| `FuzzCheckIgnores` | Ignore pattern text | Whatever `CheckIgnores` accepts loads without error once written |
| `FuzzProxyFrom` | Proxy scheme, host, port, user, password | Either refused, or the proxy URL it builds parses back to the same URL |
| `FuzzReadOwnSettings` | `mysyncthing.json` | Either refused, or it re-encodes and reads back to the same settings |

Run without network access: a fuzzed config can turn on usage reporting or discovery.
