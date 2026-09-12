"""Interactive admission probe: watch the login gate live."""
from __future__ import annotations

import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))

from rp_harness.desktop_host import DesktopHost  # noqa: E402

host = DesktopHost(REPO, bots=25,
                   stderr_path=REPO / "build" / "rp-desktop" / "probe-stderr.log")
try:
    print("waiting READY...", flush=True)
    print("ready:", host.wait_ready(timeout_s=900.0), flush=True)
    for i in range(20):
        status = host.botstatus()
        print(f"[{i:02d}] {status}", flush=True)
        if int(status.get("onlineBots", 0)) >= 10:
            print("BOTS ARE LOGGING IN", flush=True)
            break
        time.sleep(15.0)
finally:
    host.close()
