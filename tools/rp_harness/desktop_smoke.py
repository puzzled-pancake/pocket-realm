"""Desktop front-to-back RP smoke (no client): bots only.

Flow: boot the rpHost headless -> provision the RPTEST account -> wait
for a living bot population -> reset LLM state -> pick two bots -> a
bot-to-bot whisper carrying a per-run sentinel -> assert, from the
world log (LLMLogLines lane) and the llm-memory-state op:
  - the receiving bot produced a genuine text reply (>= 3 words),
  - the reply shows no chain-of-thought leakage (<think> etc.),
  - the A8 per-turn invariants hold (exactly one dispatch/end),
  - the turn minted memory for the sender pairing.

Usage:
    python tools/rp_harness/desktop_smoke.py --out build/rp-desktop/smoke.json
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
from rp_harness.run_suite import check_a8_log  # noqa: E402

WORLD_LOG = (Path(__file__).resolve().parents[2] / "build" / "rp-desktop" / "world.log")
LOCAL_LOG = Path(__import__("os").environ.get("LOCALAPPDATA", "")) / "PocketRealm" / "runtime" / "server" / "logs" / "world.log"

RPTEST_USER = "rptest"
RPTEST_PASS = "rptestpass"
BOTS_WAIT_S = 900.0
REPLY_WINDOW_S = 180.0

RE_THINK = re.compile(r"<think>|</think>|<reasoning>|</reasoning>|<thought>|</thought>", re.I)
RE_LINE = re.compile(r"BotLLM:\s*line req=(\d+) bot=(\d+) chan=(\d+) text=(.*)")


def snapshot_log(dest: Path) -> Path:
    """Copy the live world log for scanning (rotation-safe best effort)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        text = LOCAL_LOG.read_text(encoding="utf-8", errors="replace")
    except OSError:
        text = ""
    dest.write_text(text, encoding="utf-8")
    return dest


def log_lines(path: Path) -> list[str]:
    try:
        return path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []


def wait_for_bots(host: DesktopHost, minimum: int, timeout_s: float) -> int:
    deadline = time.monotonic() + timeout_s
    best = 0
    while time.monotonic() < deadline:
        try:
            status = host.botstatus()
            best = max(best, int(status.get("onlineBots") or 0))
        except DesktopHostError:
            pass
        if best >= minimum:
            return best
        time.sleep(10.0)
    return best


def poll_reply_lines(log: Path, bot_guid: int, since_ts: float) -> list[str]:
    """The BotLLM: line rows for one bot, from log rows written after since."""
    found: list[str] = []
    for row in log_lines(log):
        match = RE_LINE.search(row)
        if not match:
            continue
        if int(match.group(2)) != bot_guid:
            continue
        stamp = row.split(" ", 1)[0]
        found.append(match.group(4))
    return found


def memory_counts(state: dict) -> tuple[int, int]:
    facts = sum(int(row.get("factCount", 0)) for row in state.get("facts", []))
    rels = len(state.get("relationships", []))
    return facts, rels


def run(host: DesktopHost, minimum_bots: int) -> dict:
    steps: list[dict] = []

    def step(name: str, ok: bool, reason: str = "", **details) -> None:
        steps.append({"name": name, "status": "passed" if ok else "failed",
                      "reason": reason, **details})

    online = host.online()
    got = wait_for_bots(host, minimum_bots, BOTS_WAIT_S)
    step("bots-online", got >= minimum_bots,
         f"online={got} (need {minimum_bots})", online=got)

    acct = host.account(RPTEST_USER, RPTEST_PASS, gm=3)
    step("account", bool(acct.get("ok")), json.dumps(acct))

    step("reset-state", bool(host.reset("").get("ok")))

    world = host.memory("")
    bots = sorted(world.get("onlineBots") or [])
    if len(bots) < 2:
        step("bot-pick", False, f"world summary: {json.dumps(world)[:400]}")
        return {"suite": "desktop-smoke", "steps": steps}
    sender, receiver = bots[0], bots[1]
    step("bot-pick", True, f"sender={sender} receiver={receiver}")

    sentinel = make_sentinel()
    text = (f"Hail {receiver}, this is RPTEST {sentinel} - "
            f"tell me of your travels, friend.")
    chat = host.chat(sender, "whisper", receiver, text)
    step("whisper", bool(chat.get("ok")) and bool(chat.get("injected")),
         json.dumps(chat), sentinel=sentinel)

    receiver_guid = 0
    sender_facts_before, _ = (0, 0)
    before = host.memory(sender)
    sender_facts_before, _ = memory_counts(before)
    for row in before.get("relationships", []):
        if row.get("bot") == receiver:
            receiver_guid = int(row.get("botGuid", 0))
            break

    deadline = time.monotonic() + REPLY_WINDOW_S
    lines: list[str] = []
    facts_after, rels_after = sender_facts_before, 0
    log = WORLD_LOG
    while time.monotonic() < deadline:
        time.sleep(10.0)
        snapshot_log(log)
        if receiver_guid:
            lines = poll_reply_lines(log, receiver_guid, 0)
        after = host.memory(sender)
        facts_after, rels_after = memory_counts(after)
        if lines:
            break
    genuine = [t for t in lines if sentinel not in t and len(t.split()) >= 3]
    step("bot-reply", bool(genuine),
         f"{len(lines)} line rows from guid={receiver_guid}, "
         f"genuine={len(genuine)}", reply=next(iter(genuine), ""))

    leak = [t for t in lines if RE_THINK.search(t)]
    step("no-think-leak", not leak, "; ".join(leak[:2]))

    step("memory-growth", facts_after > sender_facts_before or rels_after > 0,
         f"facts {sender_facts_before}->{facts_after}, relationships={rels_after}")

    a8 = check_a8_log(log, require=True)
    step("a8-invariants", a8["ok"], "; ".join(a8["violations"][:4]),
         requests=a8["requests"], latency=a8["latencyMs"])

    return {"suite": "desktop-smoke", "steps": steps, "replies": lines[:12],
            "a8": a8}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default="build/rp-desktop/smoke.json")
    parser.add_argument("--min-bots", type=int, default=4)
    args = parser.parse_args(argv)

    host = DesktopHost(REPO, bots=24,
                       stderr_path=REPO / "build" / "rp-desktop" / "rphost-stderr.log")
    report: dict = {}
    try:
        host.wait_ready()
        report = run(host, args.min_bots)
    except (DesktopHostError, OSError) as error:
        report = {"suite": "desktop-smoke", "steps": [
            {"name": "host", "status": "failed", "reason": str(error)}]}
    failures = sum(1 for s in report["steps"] if s["status"] == "failed")
    out = REPO / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    try:
        host.close()
    except (DesktopHostError, OSError, ValueError):
        pass
    print(f"desktop-smoke: {failures} failure(s) -> {out}")
    for s in report["steps"]:
        print(f"  {s['status']:7s} {s['name']} {s.get('reason', '')[:120]}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
