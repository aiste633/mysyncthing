# Security policy

## Reporting a vulnerability

Please report security problems privately through GitHub:
**[Report a vulnerability](https://github.com/aiste633/mysyncthing/security/advisories/new)**
(needs a free GitHub account). Do not open a public issue for them.

The report stays private between you and the maintainer until a fix is out. Helpful to include:

- the app version (Settings › About) and Android version;
- what an attacker can do, and what they need first (another app on the phone, the same network, a paired device);
- steps or a proof of concept.

This is a small project maintained in spare time: replies are best effort. Once a fix is released, the
advisory is published and credits you, unless you prefer not to be named.

## Scope

In scope: this app, its Go bridge (`go/bridge/`) and our patches to Syncthing (`go/patches/`).

A problem in Syncthing itself, which this app runs unchanged apart from those patches, belongs to
the Syncthing project: see its [security policy](https://github.com/syncthing/syncthing/security/policy).
If you are not sure where it belongs, report it here and it will be passed on.

## Supported versions

Only the latest release is supported. Security fixes go into a new release, never into older ones,
so please update before reporting.

## Checking a release

- Builds are reproducible: building the release tag gives a byte-identical APK (see [Reproducible builds](docs/releasing.md#reproducible-builds)).
- Each release's build and its SBOM carry a signed build provenance attestation. The APK you download is that build plus our signature,
  so it is checked in two steps, as in [Verify a release](README.md#verify-a-release): `gh attestation verify` on the tag built by you, then
  `apksigcopier compare` against the download. The SBOM is checked as it is: `gh attestation verify mysyncthing-<version>-sbom.cdx.json --repo aiste633/mysyncthing`.
- Everything the APK contains is listed in the [software bill of materials](docs/sbom.md).

How the app handles your data: [privacy](docs/privacy.md).
