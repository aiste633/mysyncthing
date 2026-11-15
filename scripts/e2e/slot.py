"""Which of the PC's emulator test jobs this is, so that two can run at once without meeting.

E2E_SLOT=0 (the default), 1 or 2. Each slot has its own emulator console port, its own AVD names
and its own host ports for the test peers and adb's tunnels; slot 0 keeps the numbers used before
slots existed. The phone side of every tunnel is the same in every slot, as each slot has its own
emulator. Run each job from its own checkout: pc.py needs a clean one and writes into its build/.
Three slots at most: adb finds emulators by themselves only on console ports 5554 to 5584.

  E2E_SLOT=1 scripts/e2e/pc.py
"""
import os
import sys

_raw = os.environ.get("E2E_SLOT", "0")
if _raw not in ("0", "1", "2"):
    sys.exit(f"E2E_SLOT={_raw!r}: must be 0, 1 or 2")
SLOT = int(_raw)

EMULATOR_PORT = 5580 + 2 * SLOT  # console port; adb's port is the next one
SERIAL = f"emulator-{EMULATOR_PORT}"
AVD_PREFIX = "e2e-" if SLOT == 0 else f"e2e-s{SLOT}-"


def avd(api: int) -> str:
    """The AVD that emulator.sh makes afresh for [api] in this slot."""
    return f"{AVD_PREFIX}{api}"


def _host(port: int) -> int:
    return port + 10 * SLOT


# Host ports, all on 127.0.0.1.
PEER_GUI = _host(8385)       # the peer's REST API; the phone reaches it as its own 8385 (adb reverse)
PEER_LISTEN = _host(22001)   # the peer's sync port
FORWARD = _host(22100)       # forwarded to the phone's sync port (adb forward); the peer dials it
PHONE_API = _host(8386)      # forwarded to the phone core's REST API (adb forward)
PEER_B_GUI = _host(8387)     # scenarios.py's second peer
PEER_B_LISTEN = _host(22002)
HOST_PORTS = (EMULATOR_PORT, EMULATOR_PORT + 1, PEER_GUI, PEER_LISTEN, FORWARD, PHONE_API, PEER_B_GUI, PEER_B_LISTEN)
