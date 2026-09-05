"""RP harness (H2 relay-min): the per-change smoke-test rail.

stdlib-only Python package driving the WorldConsoleRelay instrumentation
(tools/world_console.py mechanics: adb root + push/pull of
filesDir/console-in.json / console-out.json) and asserting on the RP/LLM
surfaces through the fixed-purpose relay ops (world-chat, reset-state,
llm-memory-state).

Layout:
    protocol.py    event records + JSONL transcript (mono_ms + ts per event)
    session.py     adb link, console-in/out round trip, reconnect events
    assertions.py  bot_reply / no_echo_leak / tier_up / fact_persisted / reset
    auto_reply.py  the bot-reply detector (stub interface in relay-min)
    suites/smoke.py  the single-profile smoke suite
    run_suite.py   CLI + JUnit-ish JSON + the A8 log-invariant post-pass

See README.md for ops and usage.
"""

__version__ = "0.1.0"
