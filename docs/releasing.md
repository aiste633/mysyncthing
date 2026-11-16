# Reproducible builds and releases

How a release is built, checked, signed and published. Building and testing: [development](development.md).
Checking a published release yourself: [Verify a release](../README.md#verify-a-release).
Paths in `code` are from the repository root.

## Reproducible builds

Every build of a commit gives a byte-identical unsigned release APK (arm64 only; debug builds also carry x86_64). This is what lets F-Droid ship the APK signed with our key. What keeps it that way:

- Toolchains are pinned in one place each: NDK in `gradle.properties`, Go in `go/bridge/go.mod` (`toolchain` line; the CI release build checks the APK was built with it), Gradle in the wrapper (with checksum), JDK 21 via `jvmToolchain`.
- `go/build.sh` strips paths, build IDs and timestamps from the Go library, and builds through a fixed symlink in `/tmp/mysyncthing-build/` (a directory only the building user can write), because gomobile records module paths.
- `gradle/verification-metadata.xml` holds SHA-256 checksums of every Gradle dependency. After changing a dependency or plugin version, refresh it:

  ```sh
  ./gradlew --write-verification-metadata sha256 --refresh-dependencies --no-configuration-cache -I scripts/sbom/runtime-deps.init.gradle.kts \
    :app:assembleRelease :app:assembleDebug :app:assembleDebugAndroidTest testDebugUnitTest :app:verifyRoborazziDebug \
    :app:lintDebug :core:lintDebug :app:sbomRuntimeDeps
  scripts/sbom.sh
  ```

  `--refresh-dependencies` makes Gradle fetch every POM again: with them cached it records only the `.module` files it reads, and a clean machine, which also reads the POMs, fails verification. The `sbomRuntimeDeps` part adds the POMs the SBOM reads licences from; `scripts/sbom.sh` then updates the SBOM (see [Software bill of materials](development.md#software-bill-of-materials)).

- GitHub Actions are pinned by full commit hash, with the version in a comment, so a moved tag cannot change what CI runs. To bump one: `git ls-remote --tags https://github.com/<owner>/<action> 'v*'`, take the commit of the new release (the `^{}` line for an annotated tag), and check that its `action.yml` says `using: node24` or later. `setup-gradle` always runs with `cache-provider: basic`, the MIT-licensed cache: its default since v6 is a proprietary library.
- CI (`.github/workflows/reproducible.yml`) builds the release APK twice, with different paths, time zones, locales and umasks, and fails if the bytes differ (a diffoscope report is attached). On tags and manual runs, F-Droid's build image builds it a third time from the recipe in `fdroid/` and must match too. A pull request that changes the recipe or the build steps runs it by hand on its branch (Actions › reproducible › Run workflow).

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Run `scripts/e2e/upgrade.py` (see [Build](development.md#build)) on Android 15, 16 and 17 emulators, from the last release's tag to this commit, and again from the oldest release still in use; all builds made on the same machine. It is not in CI, so this is where a change that breaks updates is caught, our own or upstream's (a core update migrates the old config and database).
3. Commit the version bump, then run `scripts/e2e/pc.py --release` on that commit (see [Build](development.md#build)). When it passes it writes `docs/e2e/<version>.md`; commit that file. `scripts/e2e/check-results.sh <version>` checks it as CI will: a tag fails if the file is missing, did not pass on Android 15, 16 and 17, or names a commit that differs from the tag outside `docs/`, `fastlane/` and top-level `*.md`.
4. If the F-Droid recipe's build steps changed (NDK, JDK, Go bootstrap, a new step), fdroiddata must have them first: F-Droid builds a new tag from its own copy, with only the version changed. The upkeep check runs on the tag and reports a recipe out of step.
5. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`. CI creates a draft release with `mysyncthing-0.1.0-unsigned.apk` and its SBOM, `mysyncthing-0.1.0-sbom.cdx.json`, and signed build provenance (SLSA) for both: which workflow run, commit and runner made them. Anyone can check a file with `gh attestation verify <file> --repo aiste633/mysyncthing`, the unsigned APK included once it is rebuilt (see [Verify a release](../README.md#verify-a-release)).
6. On your machine, with the key's encrypted storage open: `KEYSTORE=/path/to/mysyncthing-release.p12 scripts/sign-release.sh v0.1.0`. It first checks the draft's APK against its build provenance (built by the release workflow from the tag, at the commit your checkout has for it) and signs nothing else. It signs the APK (signature only: no v1 files, alignment kept), checks with apksigcopier that the result is the build plus a signature, as F-Droid does, refuses a key other than the recipe's `AllowedAPKSigningKeys`, uploads `mysyncthing-0.1.0.apk` and removes the unsigned APK, so the release offers only the APK to install. It keeps a copy of the latest signed APK with its SHA-256 in `release/` (ignored by git; older ones are removed). Then publish the draft. The release notes come from the changelog.

## F-Droid

`fdroid/eu.stedb.mysyncthing.yml` is the recipe for fdroiddata, ready to submit. It names the release key (`AllowedAPKSigningKeys`) and builds Go from source.
