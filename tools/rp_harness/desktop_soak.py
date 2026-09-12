#!/usr/bin/env python3
"""Living-world composer soak (Phase 4): a long warm session with the
player parked among bots, to exercise the ambient lanes the short
batteries never reach - the LLM chatter composer (bot2bot banter),
rumours, crowd emotes, street reactions.

Design (from the Phase-2 gap analysis): unaddressed says are
non-triggers by law (HardTriggerAllowed refuses says without a bot
name); the street ladder answers them with 1-in-10 crowd emotes on a
12 s world-wide cooldown. The composer needs bots in RPG/wander states
near each other and TIME. So this suite keeps the player appearing
around the online bots in rotation, sprinkles addressed + unaddressed
says, listens for bot says and text emotes, and afterwards censuses the
world log for composer dispatches (`BotLLM: dispatch ... lane=cloud` by
src), spoken bot lines (`BotLLM: line`), and the chatter quota.

Usage: python tools/rp_harness/desktop_soak.py --soak-s 2400
Writes build/rp-desktop/soak.json + soak-transcript.txt.
"""
from __future__ import annotations

import argparse
import collections
import json
import re
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))

from rp_harness.desktop_host import DesktopHost, DesktopHostError  # noqa: E402
from rp_harness.desktop_play import audit, wait_population  # noqa: E402
from rp_harness.protocol import make_sentinel  # noqa: E402

WORLD_LOG = (Path(__import__("os").environ["LOCALAPPDATA"]) /
             "PocketRealm" / "runtime" / "server" / "logs" / "world.log")

RE_DISPATCH = re.compile(r"BotLLM: dispatch bot=(\d+) src=(\d+) lane=(\S+)")
RE_LINE = re.compile(r"BotLLM: line req=\d+ bot=(\d+) chan=(\d+) text=(.*)")
RE_QUOTA = re.compile(r"BotLLM:.*(quota|exhausted|per day)", re.IGNORECASE)

AMBIENT_SAYS = [
    "Anyone about? The road felt quiet today.",
    "A fine day for a walk, if nothing else.",
    "The tavern will be warm tonight, I bet.",
]
ADDRESSED_OPENER = "Well met, {bot} - what news from your part of the realm?"


def census_log() -> dict:
    dispatches = collections.Counter()
    lane_counts = collections.Counter()
    spoken = collections.Counter()
    lines_total = 0
    quota_rows: list[str] = []
    try:
        rows = WORLD_LOG.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        rows = []
    for row in rows:
        match = RE_DISPATCH.search(row)
        if match:
            dispatches[match.group(2)] += 1
            lane_counts[match.group(3)] += 1
        match = RE_LINE.search(row)
        if match:
            spoken[match.group(1)] += 1
            lines_total += 1
        if RE_QUOTA.search(row):
            quota_rows.append(row[:160])
    return {
        "dispatchBySrc": dict(dispatches),
        "dispatchByLane": dict(lane_counts),
        "dispatchTotal": sum(dispatches.values()),
        "spokenLineRows": lines_total,
        "spokenByBot": dict(spoken),
        "quotaRows": quota_rows[:8],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--soak-s", type=float, default=2400.0)
    parser.add_argument("--min-bots", type=int, default=15)
    parser.add_argument("--out", default="build/rp-desktop/soak.json")
    args = parser.parse_args(argv)

    out_dir = REPO / "build" / "rp-desktop"
    out_dir.mkdir(parents=True, exist_ok=True)
    transcript: list[str] = []
    host = DesktopHost(REPO, bots=25,
                       stderr_path=out_dir / "soak-stderr.log")
    report: dict = {"phases": [], "exchanges": []}
    try:
        host.wait_ready(timeout_s=900.0)
        online = wait_population(host, args.min_bots, 1800.0)
        report["phases"].append({"phase": "boot", "onlineBots": online})
        transcript.append(f"[boot] onlineBots={online}")
        host.reset("")

        login = host.send_raw({"op": "proto-login", "user": "RPTEST", "pass": "rptest"})
        host.send_raw({"op": "proto-world", "user": "RPTEST"})
        chars = host.send_raw({"op": "proto-chars"}).get("chars") or []
        davos = next((c for c in chars if c.get("name") == "Davos"), None)
        if davos is None:
            raise DesktopHostError(f"no Davos: {chars}")
        host.send_raw({"op": "proto-pick", "guid": davos["guid"]})
        host.send_raw({"op": "proto-chat", "kind": "say",
                       "text": ".gm visible on", "drainMs": 3000})
        transcript.append(f"[player] Davos guid={davos['guid']} in world")

        summary = host.memory("")
        bots = sorted(summary.get("onlineBots") or [])
        sentinel = make_sentinel()
        soak_since = time.monotonic()
        rotation = 0
        say_i = 0
        bot_says = 0
        emote_events = 0
        next_round = 0.0
        while time.monotonic() - soak_since < args.soak_s:
            time.sleep(20.0)
            if time.monotonic() >= next_round:
                # a new round: appear at the next bot in rotation and greet
                bot = bots[rotation % len(bots)]
                rotation += 1
                host.send_raw({"op": "proto-chat", "kind": "say",
                               "text": f".appear {bot}", "drainMs": 4000})
                result = host.send_raw({
                    "op": "proto-chat", "kind": "whisper", "target": bot,
                    "text": ADDRESSED_OPENER.format(bot=bot) +
                            f" ({sentinel})",
                    "drainMs": 90_000,
                }, timeout_s=240)
                texts = [r.get("text", "") for r in (result.get("lines") or [])
                         if isinstance(r, dict)]
                for text in texts:
                    if len(text.split()) >= 3:
                        bot_says += 1
                        transcript.append(f"[round {rotation}] {bot}: {text}")
                emotes = result.get("emotes") or []
                if emotes:
                    emote_events += len(emotes)
                next_round = time.monotonic() + 120.0
                continue
            # ambient unaddressed say on the quiet cadence
            result = host.send_raw({"op": "proto-chat", "kind": "say",
                                    "text": AMBIENT_SAYS[say_i % len(AMBIENT_SAYS)],
                                    "drainMs": 15_000})
            say_i += 1
            for row in result.get("lines") or []:
                text = row.get("text", "") if isinstance(row, dict) else str(row)
                if len(text.split()) >= 3:
                    bot_says += 1
                    issues = audit(text)
                    report["exchanges"].append(
                        {"phase": "ambient", "reply": text, "issues": issues})
                    transcript.append(f"[ambient] {text}")
            emotes = result.get("emotes") or []
            if emotes:
                emote_events += len(emotes)
                transcript.append(f"[emotes] +{len(emotes)}")

        report["phases"].append({"phase": "soak", "botSayLines": bot_says,
                                 "emoteEvents": emote_events,
                                 "rotationRounds": rotation})
        report["census"] = census_log()
        out = REPO / args.out
        out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        (out_dir / "soak-transcript.txt").write_text(
            "\n".join(transcript) + "\n", encoding="utf-8")
        census = report["census"]
        print(f"desktop-soak: rounds={rotation} botLines={bot_says} "
              f"emotes={emote_events} dispatches={census['dispatchTotal']} "
              f"spokenRows={census['spokenLineRows']} -> {out}")
        return 0
    except (DesktopHostError, OSError, KeyError, ValueError) as error:
        report["phases"].append({"phase": "fatal", "error": str(error)})
        (REPO / args.out).write_text(json.dumps(report, indent=2) + "\n",
                                     encoding="utf-8")
        print(f"desktop-soak failed: {error}", file=sys.stderr)
        return 1
    finally:
        try:
            host.send_raw({"op": "proto-close"})
        except (DesktopHostError, OSError, ValueError):
            pass
        host.close()


if __name__ == "__main__":
    raise SystemExit(main())
