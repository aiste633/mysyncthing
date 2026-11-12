# End-to-end results of each release

One file per release, `<version>.md`, written by `scripts/e2e/pc.py --release` on the PC: the
emulator end-to-end tests (`scripts/e2e/run.py` and `scripts/e2e/scenarios.py --only fast`) on
Android 15, 16 and 17 (0.3.0: 15 and 16), the commit they ran on, the emulator images, and every step's result and time.

A release tag needs its file: CI (`reproducible.yml`) runs `scripts/e2e/check-results.sh <version>`,
which fails if the file is missing, did not pass on every version, or names a commit that differs
from the tag outside `docs/`, `fastlane/` and top-level `*.md`. See
[testing](../testing.md#end-to-end-tests) for what the tests check.
