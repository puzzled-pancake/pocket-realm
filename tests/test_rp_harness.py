"""The H2 relay-min harness battery.

Pins the relay op table (WorldConsoleRelay.kt carries the three new op
names wired to the IWorldControl binder), the WorldNative external
declarations, and the world_runtime.cpp source contracts (the injection
path, validation/escaping, the reset/llm-memory SQL) the host cannot
drive - following test_llm_player_surface.py's pattern. The Python side
(harness importability, assertion behavior on synthetic transcripts, the
smoke suite's JUnit-ish schema, the A8 log invariants) is pinned by
direct execution against fakes; no device is required.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / "tools"
RELAY_KT = (ROOT / "android" / "app" / "src" / "androidTest" / "java" /
            "com" / "pocketrealm" / "database" / "WorldConsoleRelay.kt")
NATIVE_KT = (ROOT / "android" / "app" / "src" / "main" / "java" /
             "com" / "pocketrealm" / "server" / "WorldNative.kt")
SERVICE_KT = (ROOT / "android" / "app" / "src" / "main" / "java" /
              "com" / "pocketrealm" / "server" / "WorldRuntimeService.kt")
AIDL = (ROOT / "android" / "app" / "src" / "main" / "aidl" /
        "com" / "pocketrealm" / "server" / "IWorldControl.aidl")
WORLD_CPP = ROOT / "native" / "realm-runtime" / "src" / "world_runtime.cpp"

sys.path.insert(0, str(TOOLS))

from rp_harness import assertions, auto_reply, protocol, run_suite  # noqa: E402
from rp_harness import session as session_mod  # noqa: E402
from rp_harness.suites import smoke as smoke_suite  # noqa: E402


# ---- relay op table -----------------------------------------------------


def test_relay_carries_the_three_new_ops():
    relay = RELAY_KT.read_text(encoding="utf-8")
    assert "worldApi().worldChat(arg1, arg2, arg3, arg4)" in relay, \
        "world-chat must forward char/channel/target/text through the binder"
    assert '"reset-state" -> passthrough(op) { worldApi().resetState(arg1) }' in relay
    assert '"llm-memory-state" -> passthrough(op) { worldApi().llmMemoryState(arg1) }' \
        in relay
    # arg3/arg4 are part of the command payload now (target + text)
    assert 'command.optString("arg3")' in relay
    assert 'command.optString("arg4")' in relay
    # the op table doc names them (host drivers discover ops from this doc)
    for op in ("world-chat", "reset-state", "llm-memory-state"):
        assert op in relay.split("Ops (arg1/arg2")[1].split("*/")[0]


def test_native_and_binder_declare_the_ops():
    native = NATIVE_KT.read_text(encoding="utf-8")
    assert "external fun worldChatNative(" in native
    assert "external fun resetStateNative(player: String): String" in native
    assert "external fun llmMemoryStateNative(player: String): String" in native
    aidl = AIDL.read_text(encoding="utf-8")
    assert "String worldChat(String characterName, String channel, String target, String text);" in aidl
    assert "String resetState(String player);" in aidl
    assert "String llmMemoryState(String player);" in aidl
    service = SERVICE_KT.read_text(encoding="utf-8")
    assert "WorldNative.worldChatNative(" in service
    assert "WorldNative.resetStateNative(player)" in service
    assert "WorldNative.llmMemoryStateNative(player)" in service


# ---- world_runtime.cpp source contracts ----------------------------------


def cpp_body(source: str, signature: str) -> str:
    assert signature in source, f"{signature} missing from world_runtime.cpp"
    return source.split(signature)[1].split("\n    }")[0]


def test_world_chat_injects_through_the_real_opcode_handler():
    source = WORLD_CPP.read_text(encoding="utf-8")
    body = cpp_body(source, "std::string world_chat(")
    # the injection path pin: a synthetic CMSG_MESSAGECHAT packet handed to
    # the same function the opcode table dispatches for client packets
    drain = cpp_body(source, "void drain_chat_injection()")
    assert "session->HandleMessagechatOpcode(packet);" in drain, \
        "the injected line must ride the real chat dispatch path"
    assert "WorldPacket packet(CMSG_MESSAGECHAT" in drain
    assert "uint32(LANG_UNIVERSAL)" in drain
    # whisper payload: the to-name precedes the text, exactly as a client sends
    assert drain.index("packet << uint32(LANG_UNIVERSAL)") < \
        drain.index("packet << request.target")
    assert "sObjectAccessor.FindPlayerByName(request.sender.c_str())" in drain
    assert '"sender-not-online"' in drain
    # world-thread marshaling: the drain hangs off the world tick
    tick = cpp_body(source, "void record_tick(uint32_t duration)")
    assert "drain_chat_injection();" in tick
    assert tick.index("drain_chat_injection();") < tick.index("m_ticks.fetch_add")
    # validation: unknown channel rejected, names/text validated. The JSON
    # literal reasons carry C++ backslash-escaped quotes; the timeout reason
    # is a plain std::string constructor.
    for reason in ("unknown-channel", "invalid-character-name", "invalid-target",
                   "invalid-text", "busy", "world-not-ready"):
        assert f'\\"{reason}\\"' in body, f"world_chat must reject with {reason}"
    assert '"world-tick-timeout"' in body


def test_world_chat_validates_text_shape():
    source = WORLD_CPP.read_text(encoding="utf-8")
    body = cpp_body(source, "std::string world_chat(")
    assert "c >= 0x20 && c < 0x7f" in body, "printable-ASCII-only chat text"
    assert "value.size() <= 255" in body, "the 255-byte client wire limit"
    assert "text[0] == '.'" in body, \
        "a leading . is a GM command and would never reach the chat path"


def test_reset_state_clears_both_memory_tables_for_a_named_player_or_all():
    source = WORLD_CPP.read_text(encoding="utf-8")
    body = cpp_body(source, "std::string reset_state(")
    assert "DELETE FROM bot_player_facts" in body
    assert "DELETE FROM bot_player_relationship" in body
    # column names verified against PlayerbotLlmMemory.cpp (player = GUID low)
    assert "WHERE player='%u'" in body
    assert '\\"world-not-ready\\"' in body, "reset requires a READY world"
    assert '\\"player-missing\\"' in body
    # online-delete safety is a documented decision, not an accident
    assert "facts re-mint" in body
    # names are escaped before the characters lookup
    assert "CharacterDatabase.escape_string(escaped);" in body


def test_llm_memory_state_returns_relationships_and_prefix_fact_counts():
    source = WORLD_CPP.read_text(encoding="utf-8")
    body = cpp_body(source, "std::string llm_memory_state(")
    assert "SUBSTR(f.fact_text, 1, 16)" in body, \
        "per-(bot, prefix) fact buckets at the 16-byte prefix"
    assert "bot_player_relationship r" in body and \
        "LEFT JOIN characters c ON c.guid = r.bot" in body, \
        "relationship rows join the bot name in for normalized matching"
    assert '\\"player-missing\\"' in body
    # empty player = the world summary the smoke uses as its bot picker
    # (the JSON key is assembled in an escaped ostringstream literal)
    assert '\\"onlineBots\\"' in body and "player->GetPlayerbotAI()" in body
    # the players-map read is the lock-guarded online_players pattern
    assert "HashMapHolder<Player>::ReadGuard guard" in body


def test_injection_slot_lifecycle_resets_with_the_world():
    source = WORLD_CPP.read_text(encoding="utf-8")
    # start() and cleanup() both retire a stale pending injection
    assert source.count("        reset_chat_injection();") == 2
    reset = cpp_body(source, "void reset_chat_injection()")
    assert '"world-lifecycle-reset"' in reset
    # single pending slot - the relay is serial
    assert "if (m_chat_slot.pending) return" in cpp_body(source, "void drain_chat_injection()") or \
        "if (m_chat_slot.pending)" in source


def test_jni_entry_points_declared_for_all_three_ops():
    source = WORLD_CPP.read_text(encoding="utf-8")
    for symbol in ("Java_com_pocketrealm_server_WorldNative_worldChatNative",
                   "Java_com_pocketrealm_server_WorldNative_resetStateNative",
                   "Java_com_pocketrealm_server_WorldNative_llmMemoryStateNative"):
        assert symbol in source


# ---- protocol / assertions behavior --------------------------------------


def test_event_carries_both_clocks_and_transcript_round_trips(tmp_path):
    before = protocol.mono_ms()
    transcript = protocol.Transcript()
    transcript.record("op_send", op="ping")
    record = transcript.events[0]
    assert record["mono_ms"] >= before
    assert "T" in record["ts"] and record["ts"].endswith(("Z", "+00:00"))
    path = transcript.write_jsonl(tmp_path / "t.jsonl")
    loaded = protocol.Transcript.load_jsonl(path)
    assert loaded.events == transcript.events
    assert loaded.since(record["mono_ms"], kind="op_send")


def test_bot_reply_name_match_and_word_guard():
    events = [
        protocol.event("bot_reply", speaker="Beazil", text="Aye, the road was long."),
        protocol.event("bot_reply", speaker="Culf", text="Aye, the road was long."),
        protocol.event("bot_reply", speaker="beazil", text="Hi."),
    ]
    assert assertions.bot_reply(events, "Beazil").ok
    # normalization is case/format insensitive
    assert assertions.bot_reply(events, "BEAZIL").ok
    # wrong bot does not satisfy
    wrong = assertions.bot_reply(events[:1], "Culf")
    assert not wrong.ok
    # >=3-word guard: a two-word line is not a conversational reply
    short = assertions.bot_reply([events[2]], "Beazil")
    assert not short.ok and "word guard" in short.reason
    # the since filter excludes stale lines
    stale = assertions.bot_reply(events, "Beazil", since_ms=events[0]["mono_ms"] + 1)
    assert not stale.ok


def test_no_echo_leak_and_tier_up():
    sentinel = "rptestdeadbeef"
    clean = [protocol.event("bot_reply", speaker="Beazil",
                            text="The mines were quiet today, friend.")]
    leaked = clean + [protocol.event("bot_reply", speaker="Culf",
                                     text=f"rptest {sentinel} says hello")]
    assert assertions.no_echo_leak(clean, sentinel).ok
    verdict = assertions.no_echo_leak(leaked, sentinel)
    assert not verdict.ok and "echo leak" in verdict.reason
    sys_events = [protocol.event("sys_line", text="Beazil seems warmer toward you.")]
    assert assertions.tier_up(sys_events, "beazil").ok
    assert not assertions.tier_up(
        [protocol.event("sys_line", text="Beazil seems colder toward you.")],
        "Beazil").ok


def test_fact_persisted_and_reset_verdicts():
    state = {"ok": True, "factCount": 3, "facts": [
        {"bot": "Beazil", "botGuid": 12, "prefix": "likes to fish", "category": "preference", "count": 2},
        {"bot": "Culf", "botGuid": 13, "prefix": "met at the well", "category": "shared-event", "count": 1},
    ], "relationships": [
        {"bot": "Beazil", "botGuid": 12, "tier": "ally", "points": 31}]}
    assert assertions.fact_persisted(state, "Abee").ok
    assert assertions.fact_persisted(state, "Abee", bot="culf").ok
    assert not assertions.fact_persisted(state, "Abee", bot="nobody").ok
    assert assertions.fact_persisted(state, "Abee", prefix="likes to").ok
    assert not assertions.fact_persisted(state, "Abee", prefix="never seen").ok
    assert assertions.relationship(state, "Abee", bot="Beazil").ok
    assert not assertions.relationship({"relationships": []}, "Abee").ok
    assert assertions.reset({"ok": True, "factsCleared": 2,
                             "relationshipsCleared": 1}).ok
    assert not assertions.reset({"ok": False, "reason": "world-not-ready"}).ok
    assert assertions.reset_cleared({"factCount": 0, "relationshipCount": 0}).ok
    assert not assertions.reset_cleared(state).ok


# ---- auto_reply stub ------------------------------------------------------


def test_auto_reply_stub_contract():
    assert not auto_reply.attached(), "relay-min wires no reply transport"
    lines = [
        "2026-09-05 Beazil tells you: Aye, I remember the well.",
        "2026-09-05 Culf says: The road east is long.",
        "2026-09-05 Beazil seems warmer toward you.",
        "2026-09-05 unrelated noise",
    ]
    records = auto_reply.from_log_lines(lines)
    kinds = [r["kind"] for r in records]
    assert kinds == ["bot_reply", "chat_line", "sys_line"]
    assert records[0]["speaker"] == "Beazil" and records[0]["channel"] == "whisper"
    before = {"relationships": [], "facts": []}
    after = {"relationships": [{"botGuid": 5, "bot": "Beazil", "tier": "ally",
                                "points": 12}],
             "facts": [{"botGuid": 5, "bot": "Beazil", "prefix": "likes to fish",
                        "category": "preference"}]}
    delta = auto_reply.memory_delta(before, after, "Abee")
    assert {r["detail"] for r in delta} == {"relationship", "fact"}
    # memory evidence never masquerades as reply text (the >=3-word guard)
    assert assertions.bot_reply(delta, "Beazil").ok is False


# ---- session round trip over a scripted adb -------------------------------


class ScriptedAdb:
    """A fake adb binary: answers the console-in/out relay mechanics."""

    def __init__(self, response: dict, fail_first_push: int = 0,
                 never_answer: bool = False) -> None:
        self.response = response
        self.fail_first_push = fail_first_push
        self.never_answer = never_answer
        self.pushed: list[str] = []

    def __call__(self, argv: list[str], timeout: float) -> str:
        if "stat" in argv:
            return "10150:10150\n"
        if "push" in argv:
            if self.fail_first_push > 0:
                self.fail_first_push -= 1
                raise session_mod.RelayError("device offline")
            self.pushed.append(Path(argv[-2]).read_text(encoding="utf-8"))
            return ""
        if "cat" in argv and not self.never_answer:
            if self.pushed and self.pushed[-1]:
                return json.dumps(self.response)
            return ""
        return ""


def test_relay_session_round_trip_records_events(tmp_path, monkeypatch):
    sleeps: list[float] = []
    monkeypatch.setattr(session_mod.time, "sleep", lambda s: sleeps.append(s))
    fake = ScriptedAdb({"ok": True, "op": "ping", "alive": True},
                       fail_first_push=1)
    relay_session = session_mod.RelaySession(
        adb="adb", device="emulator-5554", runner=fake,
        workdir=tmp_path, poll_interval_s=0.0, attempts=3)
    response = relay_session.send("ping")
    assert response["ok"] is True
    payload = json.loads(fake.pushed[-1])
    assert payload == {"op": "ping", "arg1": "", "arg2": "", "arg3": "", "arg4": ""}
    kinds = [e["kind"] for e in relay_session.transcript.events]
    # the adb hiccup is a first-class reconnect event with backoff, not a
    # silent retry
    assert "reconnect" in kinds and "op_send" in kinds and "op_result" in kinds
    reconnect = relay_session.transcript.by_kind("reconnect")[0]
    assert reconnect["attempt"] == 1 and reconnect["backoff_s"] > 0
    assert sleeps, "backoff actually sleeps between reconnect attempts"


def test_relay_session_records_op_timeout(tmp_path, monkeypatch):
    monkeypatch.setattr(session_mod.time, "sleep", lambda s: None)
    fake = ScriptedAdb({"ok": True}, never_answer=True)
    relay_session = session_mod.RelaySession(
        adb="adb", runner=fake, workdir=tmp_path, poll_interval_s=0.0,
        attempts=1)
    # the out file never appears -> the op times out and raises
    try:
        relay_session.send("world-status", timeout_s=0.05)
        raised = False
    except session_mod.RelayError:
        raised = True
    assert raised
    assert relay_session.transcript.by_kind("op_timeout")


def test_relay_session_normalizes_a_hung_adb_timeout(tmp_path, monkeypatch):
    # round-8 R7: a HUNG adb makes the runner raise subprocess.
    # TimeoutExpired - neither RelayError nor OSError - and it used to
    # escape send() uncaught, bypassing the suite's documented exit-2
    # harness-error contract. send() now normalizes it into the same
    # retry/backoff/reconnect path as every other relay failure.
    monkeypatch.setattr(session_mod.time, "sleep", lambda s: None)

    def hung_runner(argv: list[str], timeout: float) -> str:
        raise subprocess.TimeoutExpired(cmd=argv, timeout=timeout)

    relay_session = session_mod.RelaySession(
        adb="adb", runner=hung_runner, workdir=tmp_path,
        poll_interval_s=0.0, attempts=2)
    try:
        relay_session.send("ping", timeout_s=0.05)
        raised = False
    except session_mod.RelayError as error:
        raised = True
        assert "timed out" in str(error)
    assert raised, "the normalized hang reaches the RelayError contract"
    reconnects = relay_session.transcript.by_kind("reconnect")
    assert len(reconnects) == 2, "each attempt records a reconnect event"
    assert reconnects[0]["attempt"] == 1 and reconnects[0]["backoff_s"] > 0


# ---- smoke suite schema over a fake session --------------------------------


class FakeSession:
    """The smoke suite's relay surface, scripted per op."""

    def __init__(self, tmp_path: Path, *, whisper_lands: bool = True,
                 chat_ok: bool = True, adb: str = "adb",
                 device: str | None = None) -> None:
        self.transcript = protocol.Transcript()
        self.workdir = tmp_path
        self.whisper_lands = whisper_lands
        self.chat_ok = chat_ok
        self.calls: list[dict] = []
        self._sender_snapshots = 0

    def connect(self):
        return self.health()

    def health(self):
        return {"ok": True, "alive": True, "uptimeMs": 42}

    def send(self, op, arg1="", arg2="", arg3="", arg4="", timeout_s=0):
        self.calls.append({"op": op, "arg1": arg1, "arg2": arg2,
                           "arg3": arg3, "arg4": arg4})
        if op == "ping":
            return {"ok": True, "alive": True}
        if op == "stack-up-bot":
            return {"ok": True, "profileId": arg1}
        if op == "world-account":
            return {"ok": True, "accountExists": True}
        if op == "reset-state":
            return {"ok": True, "scope": "all" if not arg1 else "player",
                    "factsCleared": 2, "relationshipsCleared": 1}
        if op == "llm-memory-state" and not arg1:
            return {"ok": True, "scope": "world", "botsOnline": 2,
                    "onlineBots": ["Abee", "Beazil"],
                    "totalFacts": 0, "totalRelationships": 0}
        if op == "llm-memory-state":
            self._sender_snapshots += 1
            rows = (self._sender_snapshots >= 2 and self.whisper_lands)
            return {"ok": True, "scope": "player", "player": arg1,
                    "playerGuid": 77, "relationshipCount": 1 if rows else 0,
                    "relationships": ([{"bot": "Beazil", "botGuid": 5,
                                        "tier": "stranger", "points": 1}]
                                      if rows else []),
                    "factCount": 1 if rows else 0,
                    "facts": ([{"bot": "Beazil", "botGuid": 5,
                                "prefix": "met at the well",
                                "category": "shared-event", "count": 1}]
                              if rows else [])}
        if op == "world-chat":
            if not self.chat_ok:
                return {"ok": False, "reason": "sender-not-online"}
            return {"ok": True, "injected": True, "channel": arg2,
                    "char": arg1, "target": arg3, "textBytes": len(arg4)}
        raise AssertionError(f"unexpected op {op}")


def _smoke(tmp_path, **session_kwargs):
    session = FakeSession(tmp_path, **session_kwargs)
    report = smoke_suite.run(session, reply_window_s=0.2, poll_interval_s=0.05)
    return session, report


def test_smoke_suite_green_schema_and_three_way_outcome(tmp_path):
    session, report = _smoke(tmp_path)
    assert set(report) >= {"suite", "ts", "durationMs", "summary", "tests",
                           "sentinel", "transcriptPath"}
    assert report["suite"] == "smoke"
    assert report["summary"] == {"tests": 9, "passed": 8, "skipped": 1,
                                 "failures": 0}
    by_name = {t["name"]: t for t in report["tests"]}
    # the whisper actually rode the relay with the sentinel text
    chat = next(c for c in session.calls if c["op"] == "world-chat")
    assert chat["arg2"] == "whisper" and chat["arg1"] == "Abee" \
        and chat["arg3"] == "Beazil" and report["sentinel"] in chat["arg4"]
    # relay-min: turn evidence observed but no reply TEXT -> skipped, not pass
    assert by_name["bot-reply"]["status"] == "skipped"
    assert "auto_reply" in by_name["bot-reply"]["reason"]
    assert by_name["no-echo-leak"]["status"] == "passed"
    assert by_name["llm-memory-state"]["status"] == "passed"
    # the transcript is JSONL with both clocks per event
    lines = Path(report["transcriptPath"]).read_text(
        encoding="utf-8").strip().splitlines()
    record = json.loads(lines[0])
    assert "mono_ms" in record and "ts" in record


def test_smoke_suite_fails_when_no_turn_observable(tmp_path):
    _, report = _smoke(tmp_path, whisper_lands=False)
    by_name = {t["name"]: t for t in report["tests"]}
    assert by_name["bot-reply"]["status"] == "failed"
    assert report["summary"]["failures"] >= 1


def test_smoke_suite_fails_fast_when_injection_fails(tmp_path):
    _, report = _smoke(tmp_path, chat_ok=False)
    by_name = {t["name"]: t for t in report["tests"]}
    assert by_name["world-chat"]["status"] == "failed"
    assert "not injected" in by_name["world-chat"]["reason"]
    # the reply assert never ran against a dead injection
    assert "bot-reply" not in by_name
    assert report["summary"]["failures"] >= 1


def test_smoke_suite_fails_on_relay_reconnect_events(tmp_path):
    # H2 (round-3 R7#4): reconnect/backoff events are first-class
    # transcript events and FAIL smoke - a recovered adb hiccup is not
    # a green per-change gate (the battery suite tolerates them; only
    # exhausted retries raise from run_suite)
    _, clean = _smoke(tmp_path)
    assert "relay-stability" not in {t["name"] for t in clean["tests"]}
    session = FakeSession(tmp_path)
    session.transcript.record("reconnect", op="ping", attempt=1,
                              backoff_s=2.0, error="adb dropped")
    report = smoke_suite.run(session, reply_window_s=0.2, poll_interval_s=0.05)
    by_name = {t["name"]: t for t in report["tests"]}
    assert by_name["relay-stability"]["status"] == "failed"
    assert "reconnect/backoff event(s)" in by_name["relay-stability"]["reason"]
    assert report["summary"]["failures"] >= 1


# ---- A8 invariants + the CLI -----------------------------------------------


def test_a8_scan_balanced_duplicate_and_missing():
    balanced = [
        "BotLLM: dispatch bot=5 src=0 lane=chat req=7",
        "BotLLM: gen begin req=7 bot=5 lane=chat",
        "BotLLM: gen end req=7 bot=5 class=ok durMs=812",
        "BotLLM: dispatch bot=6 src=0 lane=chat req=8",
        "BotLLM: begin req=8 bot=6",
        "BotLLM: end req=8 bot=6 class=ok durMs=99",
    ]
    result = run_suite.check_a8_lines(balanced)
    assert result["ok"] and result["requests"] == 2 and not result["noOp"]
    duplicate = run_suite.check_a8_lines(
        balanced + ["BotLLM: dispatch bot=6 src=0 lane=chat req=8"])
    assert not duplicate["ok"]
    assert any("2 dispatch lines" in v for v in duplicate["violations"])
    missing_end = run_suite.check_a8_lines(balanced[:2])
    assert not missing_end["ok"]
    assert any("end lines" in v for v in missing_end["violations"])
    orphan = run_suite.check_a8_lines(["BotLLM: gen begin req=9 bot=1",
                                       "BotLLM: gen end req=9 bot=1"])
    assert any("without a dispatch line" in v for v in orphan["violations"])


def test_a8_scan_busy_and_cap_turn_shapes():
    # the pinned denial shapes (round-2 R2#2/R7#1): a BUSY turn (the
    # governor or the interactive budget - both return BEFORE the begin
    # line) logs dispatch + end with NO begin; a CAP turn (the
    # concurrency check inside GenerateHttp, which runs AFTER the begin
    # line) logs dispatch + begin + end. The scan must accept both real
    # shapes and still flag a begin line on a busy turn.
    denied = run_suite.check_a8_lines([
        "BotLLM: dispatch bot=5 src=0 lane=chat req=11",
        "BotLLM: gen end req=11 bot=5 class=busy durMs=3",
        "BotLLM: dispatch bot=6 src=0 lane=chat req=12",
        "BotLLM: gen begin req=12 bot=6 lane=chat",
        "BotLLM: gen end req=12 bot=6 class=cap durMs=0",
    ])
    assert denied["ok"] and denied["requests"] == 2, denied["violations"]
    corrupted = run_suite.check_a8_lines([
        "BotLLM: dispatch bot=5 src=0 lane=chat req=11",
        "BotLLM: gen begin req=11 bot=5 lane=chat",
        "BotLLM: gen end req=11 bot=5 class=busy durMs=3",
    ])
    assert not corrupted["ok"]
    assert any("begin line on a busy-class turn" in v
               for v in corrupted["violations"])


def test_a8_scan_reports_p50_p95_from_durms():
    # plan A8's "p50/p95 from durMs" (round-4 R2): nearest-rank
    # percentiles over every end line carrying a durMs; round-5 R7: the
    # ok-class subset rides beside the aggregate (fast busy/cap denials
    # deflate it); the empty scan reports no latency block at all
    lines = []
    for i, dur in enumerate((400, 100, 300, 200, 500, 600, 700), start=1):
        lines.append(f"BotLLM: dispatch bot=5 src=0 lane=chat req={i}")
        lines.append(f"BotLLM: gen begin req={i} bot=5 lane=chat")
        lines.append(f"BotLLM: gen end req={i} bot=5 class=ok durMs={dur}")
    report = run_suite.check_a8_lines(lines)
    assert report["ok"], report["violations"]
    # sorted 100..700, n=7: nearest-rank p50 = ceil(3.5)=4th = 400;
    # p95 = ceil(6.65)=7th = 700; all-ok turns carry the same ok subset
    assert report["latencyMs"] == {"p50": 400, "p95": 700, "n": 7,
                                   "okP50": 400, "okP95": 700, "okN": 7}
    # a fast busy denial deflates the aggregate but not the ok subset
    lines.append("BotLLM: dispatch bot=5 src=0 lane=chat req=8")
    lines.append("BotLLM: gen end req=8 bot=5 class=busy durMs=1")
    mixed = run_suite.check_a8_lines(lines)
    assert mixed["ok"], mixed["violations"]
    assert mixed["latencyMs"]["n"] == 8
    # sorted [1,100..700]: nearest-rank p50 = ceil(4.0)=4th = 300 - the
    # denial deflates the aggregate (was 400) but not the ok subset
    assert mixed["latencyMs"]["p50"] == 300
    assert mixed["latencyMs"]["okN"] == 7
    assert mixed["latencyMs"]["okP50"] == 400
    assert run_suite.check_a8_lines([])["latencyMs"] == {}


def test_a8_scan_no_op_passes_on_silent_logs():
    silent = run_suite.check_a8_lines(
        ["POCKET_WORLD_LOOP starting stopped=0", "something else"])
    assert silent["ok"] and silent["noOp"] and silent["requests"] == 0
    required = run_suite.check_a8_lines(
        ["POCKET_WORLD_LOOP starting stopped=0"], require=True)
    assert not required["ok"] and required["violations"]
    missing = run_suite.check_a8_log(tmp_path_placeholder := "no-such.log")
    assert missing["ok"] and missing["noOp"]
    # round-7 R7: the unreadable-log return still carries the (empty)
    # latency block - the report shape is the same on every exit path
    assert missing["latencyMs"] == {}


def test_cli_end_to_end_with_a8(tmp_path, monkeypatch):
    log = tmp_path / "world.log"
    log.write_text(
        "BotLLM: dispatch bot=5 src=0 lane=chat req=7\n"
        "BotLLM: gen begin req=7 bot=5 lane=chat\n"
        "BotLLM: gen end req=7 bot=5 class=ok durMs=812\n", encoding="utf-8")
    monkeypatch.setattr(
        run_suite, "RelaySession",
        lambda adb="adb", device=None: FakeSession(tmp_path, adb=adb, device=device))
    out = tmp_path / "smoke.json"
    code = run_suite.main(["--suite", "smoke", "--out", str(out),
                           "--world-log", str(log)])
    assert code == 0
    report = json.loads(out.read_text(encoding="utf-8"))
    assert report["summary"]["failures"] == 0
    assert report["a8"]["ok"] and report["a8"]["requests"] == 1

    bad_log = tmp_path / "bad.log"
    bad_log.write_text(
        "BotLLM: dispatch bot=5 src=0 lane=chat req=7\n"
        "BotLLM: dispatch bot=5 src=0 lane=chat req=7\n"
        "BotLLM: gen begin req=7 bot=5\n", encoding="utf-8")
    code = run_suite.main(["--suite", "smoke", "--out", str(out),
                           "--world-log", str(bad_log)])
    assert code == 1
    report = json.loads(out.read_text(encoding="utf-8"))
    assert any(t["name"] == "a8-invariants" and t["status"] == "failed"
               for t in report["tests"])

    empty_log = tmp_path / "empty.log"
    empty_log.write_text("POCKET_WORLD_LOOP starting stopped=0\n",
                         encoding="utf-8")
    assert run_suite.main(["--suite", "smoke", "--out", str(out),
                           "--world-log", str(empty_log)]) == 0
    assert run_suite.main(["--suite", "smoke", "--out", str(out),
                           "--world-log", str(empty_log),
                           "--require-a8"]) == 1
