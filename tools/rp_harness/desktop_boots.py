#!/usr/bin/env python3
"""Boot-race regression: N consecutive boots, player login attempted
IMMEDIATELY at READY.

The product bug this pins: world_runtime.cpp used to report READY before
the world loop's first tick completed, and a player login processed in
that window raced the boot's deferred world-thread legs and fail-fasted
the whole process (0xC0000409, three reproductions in the desktop
battery). The native honest-READY + honest-LISTENER gates (READY waits
for m_first_tick_done; the listener itself opens only after the first
tick survived) are the fix; this suite is the behavioral regression:
every boot flips READY and immediately drives the full protocol login
(SRP6 -> world auth -> char enum -> player login -> live say echo, the
server's own routing of the speaker's line back proving the session is
on the wire) with no settling waits. Two shapes are exercised: the bot
profile boot (default) and the plain bots-disabled boot (the integrated
app shape - map 0 is created by the player's own login, the original
crash shape). Generated-REPLY delivery evidence lives in
desktop_play.py's battery, where bots answer.

Pass bar: N/N boots reach a live session with the host process alive,
and every stop is clean (rc 0, no fail-fast).
"""
from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools" / "rp_harness"))

from desktop_host import DesktopHost, DesktopHostError  # noqa: E402

RPTEST_USER = "RPTEST"
RPTEST_PASS = "rptest"
RP_CHAR = "Davos"

FAILFAST_EXIT = 0xC0000409


def boot_once(out_dir: Path, iteration: int, bots: int) -> dict:
    result: dict = {"boot": iteration, "bots": bots}
    subprocess.run([sys.executable, str(REPO / "tools" / "reset_davos.py")],
                   capture_output=True)
    host = DesktopHost(REPO, bots=bots,
                       stderr_path=out_dir / f"boots-{iteration}-stderr.log")
    started = time.monotonic()
    try:
        ready = host.wait_ready(timeout_s=900.0)
        result["readyAfterS"] = round(time.monotonic() - started, 1)

        # no settling waits: the login races nothing because READY is now
        # honest - drive the full protocol login the moment it flips
        login = host.send_raw({"op": "proto-login", "user": RPTEST_USER, "pass": RPTEST_PASS},
                              timeout_s=60.0)
        result["loginOk"] = bool(login.get("ok"))
        world = host.send_raw({"op": "proto-world", "user": RPTEST_USER}, timeout_s=60.0)
        result["worldAuth"] = world.get("authResult")
        chars = host.send_raw({"op": "proto-chars"}, timeout_s=60.0).get("chars") or []
        davos = next((c for c in chars if c.get("name") == RP_CHAR), None)
        result["charFound"] = davos is not None
        if not davos:
            return result
        pick = host.send_raw({"op": "proto-pick", "guid": davos["guid"]}, timeout_s=120.0)
        result["picked"] = bool(pick.get("ok", True))

        # liveness: the say must round-trip - the server routes the
        # speaker's own line back (self-echo), which includeSelf keeps.
        # This suite proves the BOOT RACE fix (login-at-READY) and a live
        # session; reply-delivery evidence lives in desktop_play.py's
        # battery, where bots answer.
        echo = host.send_raw({"op": "proto-chat", "kind": "say",
                              "text": "The realm wakes slowly this morning.",
                              "drainMs": 8000, "includeSelf": True},
                             timeout_s=60.0)
        result["echoLines"] = len(echo.get("lines") or [])
        result["inWorld"] = bool(result["loginOk"] and result["echoLines"] >= 1)
    except (DesktopHostError, OSError, ValueError) as error:
        result["error"] = str(error)[:400]
        result["inWorld"] = False
    finally:
        alive_before_stop = host.alive()
        host.close(timeout_s=180.0)
        result["aliveAtStop"] = alive_before_stop
        result["exitCode"] = host.proc.poll()
        # a fail-fast (0xC0000409 = 3221226505 on Windows) surfaces here
        result["crashed"] = (not alive_before_stop and
                             result["exitCode"] not in (0, None))
    return result


def main(argv: list[str] | None = None) -> int:
    import argparse
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--boots", type=int, default=5)
    parser.add_argument("--plain-boots", type=int, default=2,
                        help="additional bots-DISABLED boots (the plain app "
                             "boot shape: map 0 is created by the player's "
                             "own login - the original crash shape)")
    parser.add_argument("--out", default="build/rp-desktop/boots.json")
    args = parser.parse_args(argv)

    out_dir = REPO / "build" / "rp-desktop"
    out_dir.mkdir(parents=True, exist_ok=True)
    boots = [boot_once(out_dir, i, 25) for i in range(1, args.boots + 1)]
    boots += [boot_once(out_dir, args.boots + i, 0)
              for i in range(1, args.plain_boots + 1)]

    crashes = sum(1 for b in boots if b.get("crashed"))
    in_world = sum(1 for b in boots if b.get("inWorld"))
    target = args.boots + args.plain_boots
    report = {"suite": "desktop-boots", "boots": boots,
              "crashes": crashes, "inWorld": in_world, "target": target}
    out = REPO / args.out
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    ok = crashes == 0 and in_world == target
    print(f"desktop-boots: {in_world}/{target} in-world, "
          f"{crashes} crash(es) -> {out}")
    for b in boots:
        print(f"  boot {b['boot']}: bots={b.get('bots')} ready={b.get('readyAfterS')}s "
              f"auth={b.get('worldAuth')} echo={b.get('echoLines')} "
              f"exit={b.get('exitCode')}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
