#!/usr/bin/env python3
"""Tool-lane funnel verification probe (Phase 3 Fix A).

Question-only measurement of the tool lane on an external model
(MiniMax M3 zero-shots the trained <<tool>> contract). For each tool-bait
whisper - a question whose correct answer requires live game state - the
probe reads the world log's `BotLLM: toolfunnel` rows to classify where
the call was lost:

    emitted -> known -> licensed -> queued   (the executor's funnel)

Loss at `emitted` (no funnel row at all) is a PROMPT problem -> the
few-shot exemplar block (Fix B). Loss at `unlicensed`/`stale-license` is
a licensing-coverage gap (Fix C). A `queued` row with a state-correct
reply is the end-to-end pass bar.

Usage: python tools/rp_harness/desktop_toolprobe.py [--probes 20]
Writes build/rp-desktop/toolprobe.json.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools" / "rp_harness"))

from desktop_host import DesktopHost, DesktopHostError  # noqa: E402

WORLD_LOG = (Path(__file__).resolve().parents[2] / "build" / "rp-desktop" /
             "world.log")
if not WORLD_LOG.parent.exists():
    WORLD_LOG = (Path(__import__("os").environ["LOCALAPPDATA"]) /
                 "PocketRealm" / "runtime" / "server" / "logs" / "world.log")

RE_FUNNEL = re.compile(
    r"BotLLM: toolfunnel bot=(\d+) speaker=\d+ stamp=\d+ tool=(\S+) stage=(\S+)")

# state-only questions: the correct answer requires a licensed read tool
# (scene/self/memory), never prose invention
PROBES = [
    ("scene-time", "What hour of the day is it right now, and how do you know?"),
    ("scene-weather", "What is the weather like where you stand?"),
    ("scene-place", "Look around - what do you see near you?"),
    ("self-health", "How are you faring, truly? Any wounds worth worrying about?"),
    ("self-state", "How does your body feel at this moment?"),
    ("memory-search", "Do you remember our first meeting? Tell it to me."),
    ("scene-place-2", "What is around you at this moment? Name what you see."),
    ("scene-time-2", "Is it morning or evening for you just now?"),
    ("self-health-2", "Are you hurt at all? Speak plainly."),
    ("memory-search-2", "What do you recall about me, friend?"),
]


def log_count() -> int:
    try:
        return len(WORLD_LOG.read_text(encoding="utf-8", errors="replace").splitlines())
    except OSError:
        return 0


def funnel_rows_since(start: int) -> list[tuple[str, str, str]]:
    rows = []
    try:
        lines = WORLD_LOG.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return rows
    for row in lines[start:]:
        match = RE_FUNNEL.search(row)
        if match:
            rows.append((match.group(1), match.group(2), match.group(3)))
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--probes", type=int, default=20)
    parser.add_argument("--min-bots", type=int, default=6)
    parser.add_argument("--out", default="build/rp-desktop/toolprobe.json")
    args = parser.parse_args(argv)

    out_dir = REPO / "build" / "rp-desktop"
    out_dir.mkdir(parents=True, exist_ok=True)
    host = DesktopHost(REPO, bots=25,
                       stderr_path=out_dir / "toolprobe-stderr.log")
    results: list[dict] = []
    try:
        host.wait_ready(timeout_s=900.0)
        deadline = time.monotonic() + 1200.0
        bots: list[str] = []
        while time.monotonic() < deadline:
            summary = host.memory("")
            bots = sorted(summary.get("onlineBots") or [])
            if len(bots) >= args.min_bots:
                break
            time.sleep(10.0)
        if len(bots) < 2:
            raise DesktopHostError(f"only {len(bots)} bots online")
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

        i = 0
        while len(results) < args.probes:
            tag, question = PROBES[i % len(PROBES)]
            i += 1
            bot = bots[len(results) % len(bots)]
            host.send_raw({"op": "proto-chat", "kind": "say",
                           "text": f".appear {bot}", "drainMs": 4000})
            mark = log_count()
            try:
                result = host.send_raw({
                    "op": "proto-chat", "kind": "whisper", "target": bot,
                    "text": question, "drainMs": 90_000,
                }, timeout_s=240)
            except DesktopHostError as failure:
                # a hung generation/transport must end THIS probe, not the
                # suite: record the miss and move on
                results.append({
                    "probe": tag, "bot": bot, "question": question,
                    "reply": f"<op-timeout: {str(failure)[:120]}>",
                    "funnelStages": [], "queued": False, "emitted": False,
                })
                print(f"[{len(results):2d}/{args.probes}] {tag} -> OP TIMEOUT")
                continue
            rows = funnel_rows_since(mark)
            stages = [stage for (_, _, stage) in rows]
            texts = [r.get("text", "") for r in (result.get("lines") or [])
                     if isinstance(r, dict)]
            reply = texts[-1] if texts else ""
            # a tool-execution leaf: queued rows exist
            results.append({
                "probe": tag, "bot": bot, "question": question,
                "reply": reply[:300],
                "funnelStages": stages,
                "queued": "queued" in stages,
                "emitted": "emitted" in stages,
            })
            print(f"[{len(results):2d}/{args.probes}] {tag} bot={bot} "
                  f"stages={stages} reply={reply[:60]!r}")
            # incremental save: a mid-suite crash must not consume the
            # evidence gathered so far (round-2 review demand)
            (REPO / args.out).parent.mkdir(parents=True, exist_ok=True)
            (REPO / args.out).write_text(json.dumps({
                "suite": "desktop-toolprobe", "probes": results,
            }, indent=2) + "\n", encoding="utf-8")

        queued = sum(1 for r in results if r["queued"])
        emitted = sum(1 for r in results if r["emitted"])
        loss_stage = "none"
        if emitted < len(results):
            loss_stage = "emitted (prompt-side: model rarely emits markers)"
        elif queued < emitted:
            loss_stage = "executor (unlicensed / stale-license / unknown)"
        report = {
            "suite": "desktop-toolprobe",
            "probes": results,
            "emitted": emitted,
            "queued": queued,
            "total": len(results),
            "lossStage": loss_stage,
        }
        out = REPO / args.out
        out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"toolprobe: emitted={emitted}/{len(results)} queued={queued} "
              f"loss={loss_stage} -> {out}")
        return 0
    except (DesktopHostError, OSError, ValueError, KeyError) as error:
        print(f"toolprobe failed: {error}", file=sys.stderr)
        return 1
    finally:
        try:
            host.send_raw({"op": "proto-close"})
        except (DesktopHostError, OSError, ValueError):
            pass
        host.close()


if __name__ == "__main__":
    raise SystemExit(main())
