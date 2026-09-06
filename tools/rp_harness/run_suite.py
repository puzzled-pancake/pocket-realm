"""RP harness CLI: run a suite, write JUnit-ish JSON, run the A8 pass.

Usage:
    python tools/rp_harness/run_suite.py --suite smoke \
        --out build/rp-smoke.json [--device emulator-5554] [--adb adb] \
        [--profile mobile-balanced-b100-v1] [--reply-window-s 120] \
        [--world-log build/world.log] [--pull-world-log] [--require-a8]

The A8 log invariants (a post-pass over a world.log): per conversational
turn (one req id) exactly ONE `BotLLM: dispatch ... req=N` line, at least
one `begin ... req=N` (ZERO begins on a busy-class turn - both busy
paths return before the begin line; a cap-class turn DOES carry a begin),
exactly one `end ... req=N`. With zero `BotLLM:`
lines in the log the check NO-OP PASSES unless --require-a8 is given
(the world must then have produced at least one generation line).

Exit status: 0 = suite green (and A8, when attached, green); 1 = failures
or A8 violations; 2 = harness error (no device / relay dead).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

try:  # package import (python -m rp_harness.run_suite / unit battery)
    from .protocol import wall_ts
    from .session import RelayError, RelaySession
    from .suites import smoke
except ImportError:  # script import (python tools/rp_harness/run_suite.py)
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from rp_harness.protocol import wall_ts
    from rp_harness.session import RelayError, RelaySession
    from rp_harness.suites import smoke

# A8 log shapes (docs/plans/rp-depth-fix-plan-v2.3.md §A8): tolerant of the
# optional "gen " prefix so both the planned and the shipped spellings scan.
RE_BOTLLM = re.compile(r"BotLLM:")
RE_DISPATCH = re.compile(r"BotLLM:\s*dispatch\b.*?\breq=(\d+)")
RE_BEGIN = re.compile(r"BotLLM:\s*(?:gen\s+)?begin\b.*?\breq=(\d+)")
RE_END = re.compile(r"BotLLM:\s*(?:gen\s+)?end\b.*?\breq=(\d+)")
# the end line carries the class. A BUSY-class turn is the pinned
# dispatch+end-only shape (both busy paths - the governor and the
# interactive budget - return BEFORE the begin line). A CAP-class turn
# DOES carry a begin: the begin line precedes GenerateHttp, whose first
# check is the concurrency cap (round-2 R2#2/R7#1 - the cap denial
# happens after the generation was logged as started).
RE_END_CLASS = re.compile(r"BotLLM:\s*(?:gen\s+)?end\b.*?\breq=(\d+)\b.*?\bclass=(\w+)")
NO_BEGIN_CLASSES = frozenset({"busy"})


def check_a8_lines(lines, require: bool = False) -> dict:
    """Scan world.log lines for the A8 per-turn invariants."""
    counts: dict[str, dict[str, int]] = {}
    end_classes: dict[str, str] = {}
    botllm_lines = 0

    def bump(req: str, phase: str) -> None:
        counts.setdefault(req, {"dispatch": 0, "begin": 0, "end": 0})[phase] += 1

    for line in lines:
        if not RE_BOTLLM.search(line):
            continue
        botllm_lines += 1
        cls = RE_END_CLASS.search(line)
        if cls:
            end_classes[cls.group(1)] = cls.group(2)
        for phase, pattern in (("dispatch", RE_DISPATCH), ("begin", RE_BEGIN),
                               ("end", RE_END)):
            match = pattern.search(line)
            if match:
                bump(match.group(1), phase)

    violations: list[str] = []
    if botllm_lines == 0:
        # no-op pass unless the caller explicitly requires A8 evidence
        if require:
            violations.append("no BotLLM: lines found (--require-a8 given)")
        return {"ok": not violations, "noOp": not require, "botllmLines": 0,
                "requests": 0, "violations": violations}

    for req in sorted(counts, key=int):
        row = counts[req]
        if row["dispatch"] != 1:
            violations.append(
                f"req={req}: {row['dispatch']} dispatch lines (expected exactly 1)")
        end_cls = end_classes.get(req, "")
        if end_cls in NO_BEGIN_CLASSES:
            # busy: the governor denied the turn before any generation
            # started - dispatch + end only, never a begin
            if row["begin"] != 0:
                violations.append(
                    f"req={req}: begin line on a {end_cls}-class turn "
                    "(expected dispatch+end only)")
        elif row["begin"] < 1:
            violations.append(f"req={req}: no begin line (expected >=1)")
        if row["end"] != 1:
            violations.append(f"req={req}: {row['end']} end lines (expected exactly 1)")
        if row["dispatch"] == 0:
            violations.append(f"req={req}: begin/end without a dispatch line")
    return {"ok": not violations, "noOp": False, "botllmLines": botllm_lines,
            "requests": len(counts), "violations": violations}


def check_a8_log(path: str | Path, require: bool = False) -> dict:
    """check_a8_lines over a world.log file (missing file = no lines)."""
    try:
        text = Path(path).read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {"ok": not require, "noOp": not require, "botllmLines": 0,
                "requests": 0,
                "violations": [] if not require
                else [f"world log not readable: {path}"]}
    return check_a8_lines(text.splitlines(), require=require)


def run_smoke(session, args) -> dict:
    """Run the smoke suite and attach the A8 pass when a log is wired."""
    result = smoke.run(session, profile=args.profile,
                       reply_window_s=args.reply_window_s)
    if args.world_log:
        if args.pull_world_log:
            session.pull_world_log(args.world_log)
        result["a8"] = check_a8_log(args.world_log, require=args.require_a8)
        if not result["a8"]["ok"]:
            result["tests"].append({
                "name": "a8-invariants", "status": "failed",
                "durationMs": 0.0,
                "reason": "; ".join(result["a8"]["violations"])})
            result["summary"]["tests"] += 1
            result["summary"]["failures"] += 1
    return result


def write_report(report: dict, path: str | Path) -> Path:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                      encoding="utf-8")
    return target


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--suite", default="smoke", choices=["smoke"],
                        help="suite to run (relay-min ships smoke only)")
    parser.add_argument("--out", default="build/rp-smoke.json",
                        help="output JSON path")
    parser.add_argument("--adb", default="adb", help="adb binary")
    parser.add_argument("--device", default=None, help="adb device serial")
    parser.add_argument("--profile", default=smoke.DEFAULT_PROFILE,
                        help="bot profile for stack-up-bot")
    parser.add_argument("--reply-window-s", type=float,
                        default=smoke.DEFAULT_REPLY_WINDOW_S, dest="reply_window_s",
                        help="generous bound for the bot_reply assert")
    parser.add_argument("--world-log", default=None, dest="world_log",
                        help="local world.log path for the A8 post-pass")
    parser.add_argument("--pull-world-log", action="store_true",
                        dest="pull_world_log",
                        help="pull the device world.log to --world-log first")
    parser.add_argument("--require-a8", action="store_true", dest="require_a8",
                        help="fail when the world log holds no BotLLM lines")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    session = RelaySession(adb=args.adb, device=args.device)
    try:
        session.connect()
        report = run_smoke(session, args)
    except (RelayError, OSError) as error:
        report = {"suite": args.suite, "ts": wall_ts(), "durationMs": 0.0,
                  "summary": {"tests": 1, "passed": 0, "skipped": 0,
                              "failures": 1},
                  "tests": [{"name": "connect", "status": "failed",
                             "durationMs": 0.0, "reason": str(error)}]}
        write_report(report, args.out)
        print(f"rp_harness: HARNESS ERROR: {error}", file=sys.stderr)
        return 2
    write_report(report, args.out)
    summary = report["summary"]
    print(f"rp_harness {args.suite}: {summary['passed']}/{summary['tests']} passed, "
          f"{summary['skipped']} skipped, {summary['failures']} failed "
          f"-> {args.out}")
    return 0 if summary["failures"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
