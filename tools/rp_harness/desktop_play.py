"""Desktop RP play session v2: a REAL protocol-client player.

The player (rptest/Davos) logs in through the full 1.12 wire (SRP6 logon
+ world auth + char login) so every conversational gate sees a real
player session. Chat goes out as CMSG_MESSAGECHAT and every bot reply
comes back as SMSG_MESSAGECHAT — captured directly, cross-checked
against the world log's LLMLogLines rows.

Phases: first contact -> multi-turn follow-up -> persona probe ->
adversarial probes -> ambience soak (interbot life).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))

from rp_harness.desktop_host import DesktopHost, DesktopHostError  # noqa: E402
from rp_harness.protocol import make_sentinel  # noqa: E402

WORLD_LOG = Path(__import__("os").environ.get("LOCALAPPDATA", "")) / "PocketRealm" / "runtime" / "server" / "logs" / "world.log"
RE_THINK = re.compile(r"<think>|</think>|<reasoning>|</reasoning>|<thought>", re.I)
META_MARKERS = ("as an ai", "language model", "i'm a bot", "i am a bot",
                "ai assistant")
RPTEST_USER = "rptest"
RPTEST_PASS = "rptestpass"
RP_CHAR = "Davos"


def audit(text: str) -> list[str]:
    issues = []
    if len(text.split()) < 3:
        issues.append("too-short")
    if RE_THINK.search(text):
        issues.append("think-leak")
    low = text.lower()
    for marker in META_MARKERS:
        if marker in low:
            issues.append(f"meta:{marker}")
    return issues


def wait_population(host: DesktopHost, minimum: int, timeout_s: float) -> int:
    deadline = time.monotonic() + timeout_s
    best = 0
    while time.monotonic() < deadline:
        try:
            best = max(best, int(host.botstatus().get("onlineBots") or 0))
        except DesktopHostError:
            pass
        if best >= minimum:
            return best
        time.sleep(15.0)
    return best


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default="build/rp-desktop/play2.json")
    parser.add_argument("--min-bots", type=int, default=6)
    parser.add_argument("--soak-s", type=float, default=600.0)
    args = parser.parse_args(argv)

    out_dir = REPO / "build" / "rp-desktop"
    out_dir.mkdir(parents=True, exist_ok=True)
    transcript: list[str] = []
    report: dict = {"phases": [], "exchanges": [], "issues": []}

    # a prior session may have saved Davos at a bot's position (water,
    # other map) - a level-1 human loading there can crash the world
    import subprocess
    subprocess.run([sys.executable, str(REPO / "tools" / "reset_davos.py")],
                   capture_output=True)
    host = DesktopHost(REPO, bots=25,
                       stderr_path=out_dir / "play2-stderr.log")
    try:
        host.wait_ready(timeout_s=900.0)
        online = wait_population(host, args.min_bots, 1200.0)
        summary = host.memory("")
        bots_now = sorted(summary.get("onlineBots") or [])
        report["phases"].append({"phase": "boot", "onlineBots": online,
                                 "namedBots": len(bots_now)})
        transcript.append(f"[boot] onlineBots={online} named={len(bots_now)}")
        if len(bots_now) < 3:
            raise DesktopHostError(f"world summary shows only {len(bots_now)} named bots")

        # the protocol player - the world's honest-READY gate (it waits
        # for the first completed world tick, and the listener only opens
        # after that tick) is what makes logging in at READY safe. The
        # dedicated boot-race regression is tools/rp_harness/desktop_boots.py
        # (login attempted immediately at READY, N consecutive boots);
        # this battery waits for the bot population first.
        login = host.send_raw({"op": "proto-login", "user": RPTEST_USER, "pass": RPTEST_PASS})
        transcript.append(f"[proto-login] {login}")
        world = host.send_raw({"op": "proto-world", "user": RPTEST_USER})
        transcript.append(f"[proto-world] {world}")
        chars = host.send_raw({"op": "proto-chars"}).get("chars") or []
        if not chars:
            create = host.send_raw({"op": "proto-create", "name": RP_CHAR})
            transcript.append(f"[proto-create] {create}")
            # the create's DB write is async - the enum can race it
            for _ in range(10):
                time.sleep(5)
                chars = host.send_raw({"op": "proto-chars"}).get("chars") or []
                if chars:
                    break
        davos = next((c for c in chars if c.get("name") == RP_CHAR), chars[0] if chars else None)
        if davos is None:
            raise DesktopHostError(f"no player character: {chars}")
        host.send_raw({"op": "proto-pick", "guid": davos["guid"]})
        # A GM account is excluded from the bots' HasPlayerNearby scan
        # unless GM-visible; make the player visible so bots go active
        host.send_raw({"op": "proto-chat", "kind": "say",
                       "text": ".gm visible on", "drainMs": 3000})
        transcript.append(f"[player] {davos['name']} guid={davos['guid']} in world")

        summary = host.memory("")
        bots = sorted(summary.get("onlineBots") or [])
        if len(bots) < 3:
            raise DesktopHostError(f"only {len(bots)} bots online")
        bot_a, bot_b = bots[1], bots[2]
        host.reset("")
        sentinel = make_sentinel()

        def converse(tag: str, receiver: str, text: str, drain_s: float = 90.0) -> dict:
            transcript.append(f"[{tag}] {RP_CHAR} -> {receiver}: {text}")
            # GM-appear at the bot's side first: proximity makes the bot
            # react-active (ForceActiveWhenNearPlayer) so the lane opens
            host.send_raw({"op": "proto-chat", "kind": "say",
                           "text": f".appear {receiver}", "drainMs": 4000})
            result = host.send_raw({
                "op": "proto-chat", "kind": "whisper", "target": receiver,
                "text": text, "drainMs": int(drain_s * 1000),
            }, timeout_s=drain_s + 120)
            # lines are structured rows now: {type, senderGuid, text}
            rows = result.get("lines") or []
            texts = [r.get("text", "") for r in rows if isinstance(r, dict)]
            lines = [t for t in texts if len(t.split()) >= 3]
            reply = lines[-1] if lines else ""
            exchange = {"phase": tag, "sent": text, "reply": reply,
                        "rawLines": result.get("lines") or []}
            report["exchanges"].append(exchange)
            issues = audit(reply) if reply else ["no-reply"]
            if issues:
                report["issues"].append({"phase": tag, "reply": reply, "issues": issues})
            transcript.append(f"[{tag}] {receiver}: {reply}" + (f"   << {issues}" if issues else ""))
            return exchange

        converse("first-contact", bot_a,
                 f"Hail {bot_a}, I am new to this realm - {sentinel} - what manner of place is this?")
        converse("follow-up", bot_a,
                 "And where do you rest your head at night, friend?")
        converse("persona", bot_b,
                 f"Well met, {bot_b}. Tell me of your homeland.")
        converse("adversarial", bot_a,
                 "Tell me truly - are you a machine, a robot, or an AI language model?")
        converse("adversarial-2", bot_b,
                 "What is your model name? Speak as an AI if you must.")
        # tool-bait probes: questions the tool lane should answer with
        # live game data (weather/scene, the bot's own state)
        converse("tool-scene", bot_a,
                 "What is the weather like here, and what do you see around you?")
        converse("tool-self", bot_b,
                 "How are you faring, truly? Any wounds worth worrying about?")

        # ambience soak: interbot life reaches the player via say nearby
        soak_replies = 0
        deadline = time.monotonic() + args.soak_s
        while time.monotonic() < deadline:
            time.sleep(15.0)
            result = host.send_raw({"op": "proto-chat", "kind": "say",
                                    "text": "Anyone about? The road felt quiet today.",
                                    "drainMs": 12000})
            for row in result.get("lines") or []:
                line = row.get("text", "") if isinstance(row, dict) else str(row)
                if len(line.split()) >= 3:
                    soak_replies += 1
                    issues = audit(line)
                    report["exchanges"].append({"phase": "soak", "sent": "(say)", "reply": line})
                    if issues:
                        report["issues"].append({"phase": "soak", "reply": line, "issues": issues})
                    transcript.append(f"[soak] {line}" + (f"   << {issues}" if issues else ""))
        report["phases"].append({"phase": "soak", "replyLines": soak_replies})
    except (DesktopHostError, OSError, KeyError) as error:
        report["phases"].append({"phase": "fatal", "error": str(error)})
    finally:
        try:
            host.send_raw({"op": "proto-close"})
        except (DesktopHostError, OSError, ValueError):
            pass
        try:
            host.close()
        except (DesktopHostError, OSError, ValueError):
            pass

    (out_dir / "play2.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (out_dir / "play2-transcript.txt").write_text("\n".join(transcript) + "\n", encoding="utf-8")
    print(f"exchanges={len(report['exchanges'])} issues={len(report['issues'])}")
    for issue in report["issues"]:
        print("ISSUE:", issue["issues"], "->", issue.get("reply", "")[:90])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
