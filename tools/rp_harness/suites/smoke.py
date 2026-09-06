"""The per-change smoke suite (H2 relay-min): single profile, relay-driven.

Flow: stack-up-bot -> world-account -> reset-state -> pick two online bots
-> world-chat whisper carrying a per-run sentinel -> bot_reply assert
within a generous bound (three-way outcome, see auto_reply.py) ->
llm-memory-state sanity. Emits JUnit-ish JSON (run_suite.py adds the A8
world-log post-pass). Any relay reconnect/backoff event FAILS the suite
(plan H2: reconnects fail smoke, are tolerated in the battery).
"""
from __future__ import annotations

import time

try:  # package import (python -m / unit battery)
    from .. import assertions, auto_reply
    from ..protocol import Transcript, make_sentinel, wall_ts
except ImportError:  # script import (python tools/rp_harness/...)
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from rp_harness import assertions, auto_reply
    from rp_harness.protocol import Transcript, make_sentinel, wall_ts

DEFAULT_PROFILE = "mobile-balanced-b100-v1"
DEFAULT_REPLY_WINDOW_S = 120.0  # the generous bound: generation + delivery
STACK_TIMEOUT_S = 900.0
OP_TIMEOUT_S = 120.0

RPTEST_USER = "rptest"
RPTEST_PASS = "rptestpass"


def _step(steps: list[dict], name: str):
    """Time one suite step; `steps[-1]` is the step record."""
    started = time.monotonic()
    steps.append({"name": name, "status": "failed", "started": started})

    def finish(status: str, reason: str = "", details: dict | None = None) -> None:
        record = steps[-1]
        record["status"] = status
        record["durationMs"] = round((time.monotonic() - started) * 1000.0, 3)
        if reason:
            record["reason"] = reason
        if details:
            record["details"] = details

    return finish


def run(session, *, profile: str = DEFAULT_PROFILE,
        reply_window_s: float = DEFAULT_REPLY_WINDOW_S,
        poll_interval_s: float = 5.0,
        rptest_user: str = RPTEST_USER,
        rptest_pass: str = RPTEST_PASS) -> dict:
    """Drive the smoke suite over a RelaySession (or a test fake).

    Returns the JUnit-ish report dict; `summary.failures == 0` is green.
    """
    suite_started = time.monotonic()
    transcript = getattr(session, "transcript", None) or Transcript()
    steps: list[dict] = []
    reply_events: list[dict] = []
    sentinel = make_sentinel()

    # -- relay health --------------------------------------------------
    finish = _step(steps, "health")
    try:
        session.health()
        finish("passed")
    except Exception as error:  # noqa: BLE001 - a suite records, never raises
        finish("failed", f"relay health check failed: {error}")
        return _report(steps, suite_started, transcript, session)

    # -- boot the stack on the bot profile ------------------------------
    finish = _step(steps, "stack-up-bot")
    response = session.send("stack-up-bot", arg1=profile,
                            timeout_s=STACK_TIMEOUT_S)
    if response.get("ok"):
        finish("passed", details={"profile": profile})
    else:
        finish("failed", f"stack-up-bot not ok: {response}")
        return _report(steps, suite_started, transcript, session)

    # -- the RPTEST account (per-run provisioning, gmlevel 0) -----------
    finish = _step(steps, "world-account")
    response = session.send("world-account", arg1=rptest_user,
                            arg2=rptest_pass, timeout_s=OP_TIMEOUT_S)
    if response.get("ok"):
        finish("passed", "account ready (created or exists)",
               {"accountExists": response.get("accountExists")})
    else:
        finish("failed", f"world-account not ok: {response}")

    # -- test isolation: clear the LLM assertion memory ------------------
    finish = _step(steps, "reset-state")
    response = session.send("reset-state", arg1="", timeout_s=OP_TIMEOUT_S)
    verdict = assertions.reset(response)
    if verdict.ok:
        finish("passed", verdict.reason, verdict.details)
    else:
        finish("failed", verdict.reason)

    # -- pick two online characters (sender + receiving bot) -------------
    finish = _step(steps, "bot-pick")
    summary_state = session.send("llm-memory-state", arg1="",
                                 timeout_s=OP_TIMEOUT_S)
    bots = list(summary_state.get("onlineBots") or [])
    if summary_state.get("ok") and len(bots) >= 2:
        sender, receiver = bots[0], bots[1]
        finish("passed", f"sender={sender} receiver={receiver}",
               {"botsOnline": summary_state.get("botsOnline"), "bots": bots[:8]})
    else:
        finish("failed",
               f"need two online characters, world summary: {summary_state}")
        return _report(steps, suite_started, transcript, session)

    # -- the whisper (the real chat dispatch path, sentinel-bearing) -----
    finish = _step(steps, "world-chat")
    text = (f"Hail {receiver}, this is RPTEST {sentinel} - "
            f"tell me of your travels, friend.")
    injected_at = time.monotonic() * 1000.0
    response = session.send("world-chat", arg1=sender, arg2="whisper",
                            arg3=receiver, arg4=text, timeout_s=OP_TIMEOUT_S)
    if response.get("ok") and response.get("injected"):
        finish("passed", "whisper injected via the real chat dispatch path",
               {"sender": sender, "receiver": receiver,
                "sentinel": sentinel, "response": response})
    else:
        finish("failed", f"world-chat not injected: {response}")
        return _report(steps, suite_started, transcript, session)

    # -- the reply assert, generous window, three-way outcome ------------
    finish = _step(steps, "bot-reply")
    before_state = session.send("llm-memory-state", arg1=sender,
                                timeout_s=OP_TIMEOUT_S)
    evidence: list[dict] = []
    interval = max(0.05, min(poll_interval_s, reply_window_s / 4.0 or 0.05))
    waited = 0.0
    while waited < reply_window_s:
        time.sleep(interval)
        waited += interval
        snapshot = session.send("llm-memory-state", arg1=sender,
                                timeout_s=OP_TIMEOUT_S)
        evidence = auto_reply.memory_delta(before_state, snapshot, sender)
        for record in evidence:
            transcript.append(record)
        if evidence:
            break
    reply_events = auto_reply.poll(session, player=sender, bot=receiver,
                                   since_ms=injected_at)
    verdict = assertions.bot_reply(reply_events + evidence, receiver,
                                   since_ms=injected_at)
    if verdict.ok:
        finish("passed", verdict.reason)
    elif any(e.get("kind") in ("bot_reply", "chat_line") for e in reply_events):
        finish("failed", verdict.reason)  # a text line existed but failed the guard
    elif evidence:
        finish("skipped",
               "conversational turn ran (memory delta) but no reply text is "
               "observable without the auto_reply transport (relay-min stub; "
               "see tools/rp_harness/auto_reply.py)")
    else:
        finish("failed",
               f"no observable turn from {receiver} within "
               f"{reply_window_s:.0f}s of the injected whisper "
               f"(and no reply transport attached)")

    # -- injection hygiene: the sentinel never echoes --------------------
    finish = _step(steps, "no-echo-leak")
    verdict = assertions.no_echo_leak(reply_events + evidence, sentinel)
    finish("passed" if verdict.ok else "failed", verdict.reason)

    # -- llm-memory-state sanity -----------------------------------------
    finish = _step(steps, "llm-memory-state")
    final_state = session.send("llm-memory-state", arg1=sender,
                               timeout_s=OP_TIMEOUT_S)
    if final_state.get("ok") and "relationshipCount" in final_state \
            and "factCount" in final_state:
        finish("passed",
               f"relationshipCount={final_state.get('relationshipCount')} "
               f"factCount={final_state.get('factCount')}")
    else:
        finish("failed", f"unexpected llm-memory-state shape: {final_state}")

    return _report(steps, suite_started, transcript, session, sentinel=sentinel)


def _report(steps: list[dict], suite_started: float, transcript: Transcript,
            session, sentinel: str | None = None) -> dict:
    for record in steps:
        record.pop("started", None)
    # H2 (round-3 R7#4): reconnect/backoff events are first-class
    # transcript events and FAIL smoke (the battery suite tolerates
    # them; run_suite still raises on exhausted retries). Checked here
    # so every exit path - early return or clean finish - sees it.
    reconnects = [e for e in transcript.events if e.get("kind") == "reconnect"]
    if reconnects:
        steps.append({
            "name": "relay-stability",
            "status": "failed",
            "reason": f"{len(reconnects)} relay reconnect/backoff event(s) - "
                      "plan H2: reconnects fail smoke (tolerated in battery)",
            "details": {"events": reconnects[:5]},
        })
    passed = sum(1 for s in steps if s["status"] == "passed")
    skipped = sum(1 for s in steps if s["status"] == "skipped")
    failed = sum(1 for s in steps if s["status"] == "failed")
    report = {
        "suite": "smoke",
        "ts": wall_ts(),
        "durationMs": round((time.monotonic() - suite_started) * 1000.0, 3),
        "summary": {"tests": len(steps), "passed": passed,
                    "skipped": skipped, "failures": failed},
        "tests": steps,
    }
    if sentinel:
        report["sentinel"] = sentinel
    try:
        workdir = getattr(session, "workdir", None)
        if workdir is not None:
            path = transcript.write_jsonl(workdir / "smoke-transcript.jsonl")
            report["transcriptPath"] = str(path)
    except OSError:
        pass  # a transcript write failure never masks the suite verdict
    return report
