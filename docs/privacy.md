# Privacy

Last updated: 28 October 2026. Changes to this page are in the repository's history.

MySyncthing runs Syncthing on your phone. Syncthing copies folders directly between devices you
pair yourself. There is no MySyncthing account and no MySyncthing server: the app's developer
receives no data from the app, and has no access to your files, settings or devices.

## In short

- No analytics, advertising, tracking or crash-reporting libraries.
- Your files go only to devices you paired, end-to-end encrypted (TLS 1.3).
- To find and reach your devices over the internet, Syncthing uses public servers run by the
  Syncthing project. They see this phone's IP address and Syncthing device ID, never your files.
  Each one can be turned off.
- Anonymous usage reporting to the Syncthing project is off unless you agree to it.
- Nothing goes to Google: no cloud backup, and the built-in web view sends no metrics or Safe
  Browsing checks.
- Android's phone-to-phone transfer to a new phone carries the device keys, so the new phone
  keeps this phone's identity (see below).

## What the app keeps on the phone

All of this stays in the app's private storage, until you clear the app's data or uninstall it:

| What | Why |
|---|---|
| Device ID and its key pair (`cert.pem`, `key.pem`) | Identifies this phone to your other devices |
| Syncthing's configuration: paired devices (names, IDs, addresses), folders (names, paths) | To sync what you set up |
| Syncthing's database of file names, sizes, times and hashes | To know what changed |
| App settings (run conditions, schedules, proxy) | To behave as you set it up |
| Syncthing's log, in memory only | Shown in the web GUI's Logs view; gone when the app stops |

The synced files themselves are in the folders you choose on shared storage.

Android's logcat receives only Syncthing's warnings and errors, and the app's own errors. The
core's routine log lines, which name devices, addresses and folders, are left out of release
builds, because logcat can end up in bug reports.

Cloud backup is off. Moving to a new phone with Android's device-to-device transfer (by cable or
directly between phones) carries the device ID, its keys, the configuration and the app settings,
so the new phone keeps the old one's identity; the database is left out and rebuilt. The
transfer stays between the two phones. Afterwards stop Syncthing on the old phone (the app
reminds you once): two phones with the same keys look like one device to your other devices.

A backup you export (Settings › System › Export a backup) contains the configuration and the
device keys. It is written where you choose, and is AES-256 encrypted if you set a password.
Keep it safe: anyone with the file can act as this phone towards your devices.

## What leaves the phone

### To your own devices

File contents, file names and metadata go to the devices that share each folder, as you set up.
Connections are mutually authenticated and encrypted end to end. A device you mark as untrusted
for a folder stores only encrypted data, with a password it never sees.

### To public Syncthing servers

These servers are run by the [Syncthing project](https://syncthing.net), not by this app's
developer. Each is a Syncthing default that you can turn off in the app's Settings:

| Service | What it receives | Setting |
|---|---|---|
| Global discovery (`discovery-lookup`, `discovery-announce-v4` and `-v6.syncthing.net`) | This phone's device ID, its IP address and the ports it listens on; the device IDs it looks up | Settings › Finding devices › Internet discovery |
| Relays (from the pool at `relays.syncthing.net`) | When no direct connection works: the IP addresses and device IDs of both ends, and the encrypted traffic, which the relay cannot read | Settings › Finding devices › Relays |
| STUN (`stun.syncthing.net`, or public STUN servers run by others if it cannot be found) | This phone's IP address, to find out how it can be reached from outside | Off with Router port opening (Settings › Finding devices) |

The Syncthing project's own documentation describes these services:
[Security principles](https://docs.syncthing.net/users/security.html).

### On the local network

Local discovery announces this phone's device ID and ports to devices on the same network.
Router port opening (UPnP, NAT-PMP) asks the router to forward a port. Both can be turned off:
Settings › Finding devices › Local network, and Router port opening.

### Anonymous usage reporting (only with your consent)

At first start the app asks whether to send Syncthing's anonymous usage reports to the
Syncthing project. It is off unless you say yes, and Settings › Advanced › Anonymous usage
reporting turns it off at any time; *Preview usage report* shows the exact report. What the
reports contain and how they are used is described by the Syncthing project itself:
[Security principles](https://docs.syncthing.net/users/security.html) and
[data.syncthing.net](https://data.syncthing.net).

Syncthing's update check is off: updates come through the app store only.

### Through a proxy

With a proxy set (Settings › Network › Proxy), Syncthing's connections go through it, for
example Tor with Orbot. The proxy's operator sees the connections the proxy carries.

## Permissions

| Permission | Why |
|---|---|
| Camera | Scanning a device ID's QR code. Images are not stored or sent. |
| All files access | Syncing folders anywhere on shared storage |
| Notifications | The sync service's notification, and notices of new devices, shared folders and errors |
| Internet, network state | Connecting to your devices, and the run conditions (Wi-Fi, mobile data, roaming) |
| Foreground service, start at boot, ignore battery optimisation | Syncing in the background, as you set it up |
| Wi-Fi state, Wi-Fi multicast, wake lock | Declared; not used by this version |

## The web GUI and links

Syncthing's web GUI runs on the phone, locked with a random password that only the app knows.
The built-in view loads nothing from the internet. Links to websites open in your browser, under
that browser's own privacy terms.

## Your rights

The developer holds no personal data about you from the app, so there is nothing to look up,
correct or delete on our side: everything above is on your phone and on the devices you paired,
under your control. Clearing the app's data or uninstalling it deletes what the app keeps.

For data received by the Syncthing project's servers, contact the Syncthing project. For the
usage report, the legal basis is your consent, which you can withdraw in Settings at any time.
Under the GDPR you may also complain to the data protection authority where you live.

## Contact

Questions about privacy: [ask privately through GitHub](https://github.com/aiste633/mysyncthing/security/advisories/new)
(needs a free GitHub account). See also the [security policy](../SECURITY.md).
