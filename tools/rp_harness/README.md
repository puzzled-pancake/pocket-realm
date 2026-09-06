# RP harness (H2 relay-min)

The per-change smoke-test rail: a stdlib-only Python package that drives
the real product stack on a benchmark emulator through the long-lived
`WorldConsoleRelay` instrumentation (the `tools/world_console.py`
mechanics - adb root, push of `filesDir/console-in.json`, poll of
`filesDir/console-out.json`), asserts on the RP/LLM surfaces, and emits
JUnit-ish JSON plus a JSONL transcript.

## New relay ops

Three fixed-purpose ops were added to `WorldConsoleRelay.kt` ->
`IWorldControl.aidl` -> `WorldRuntimeService.kt` -> `WorldNative.kt` ->
`native/realm-runtime/src/world_runtime.cpp` (the `character_persistence`
pattern: fixed verbs, native-side validation, JSON answers; no SQL and no
raw chat text crosses Binder).

### `world-chat` `{char, channel, target, text}`

* payload: `arg1` = sending character name, `arg2` = channel
  (`say|party|whisper|yell`), `arg3` = receiving bot name (whisper only,
  empty otherwise), `arg4` = the chat text (printable ASCII, 1..255
  bytes, no leading `.` - that would be parsed as a GM command).
* injection path: the native op queues the line; the WORLD THREAD drains
  it on the next tick (the `BotTargetFence` marshaling law - session chat
  handlers run there), locates the character's `Player` via
  `sObjectAccessor.FindPlayerByName`, builds a synthetic
  `CMSG_MESSAGECHAT` packet in the exact client wire layout (`uint32
  type, uint32 lang`, then the per-type payload) and hands it to
  `WorldSession::HandleMessagechatOpcode` - the same function the opcode
  table dispatches for real client packets. Every downstream gate
  (`CheckChatMessage`, `CanSpeak` flood control, `ParseCommands`,
  `RandomPlayerbotMgr` radius/team filters on say/yell, the per-bot
  `PlayerbotAI::HandleCommand` hook on whisper, and inside it the LLM
  say/party/whisper trigger gates under test) therefore runs identically
  for an injected line and a client line. Language is `LANG_UNIVERSAL`
  (every faction understands).
  A session-grafted synthetic login was deliberately NOT taken: without a
  socket the session reads as a bot (`GetRemoteAddress()` ==
  `"disconnected/bot"`), so `isRealPlayer()` stays false and the LLM
  conversational gates would never fire - the injection would not be
  "the identical gating under test".
* response: `{"ok":true,"injected":true,"channel":..,"char":..,..
  "target":..,"textBytes":N}` or `{"ok":false,"reason":..}` with
  `world-not-ready | unknown-channel | invalid-character-name |
  invalid-target | invalid-text | busy | sender-not-online |
  world-tick-timeout | world-lifecycle-reset`.

### `reset-state` `[player]`

* payload: `arg1` = player name, empty = every player. World must be
  READY.
* clears the LLM assertion state for test isolation: `DELETE` from
  `bot_player_facts` and `bot_player_relationship` (columns verified
  against `native/patches/playerbots/PlayerbotLlmMemory.cpp`). Deleting
  while bots are online is acceptable for tests - facts re-mint on the
  next conversation and relationships regrow from zero; the in-process
  rolling history / chatter pools are process-local and die with the
  world process.
* response: `{"ok":true,"scope":"all|player","player":..,`
  `"factsCleared":N,"relationshipsCleared":M}` (counts are pre-delete
  snapshots; PExecute reports no affected rows).

### `llm-memory-state` `{player}`

* payload: `arg1` = player name; empty player = world summary.
* named: per-bot relationship rows (bot, botGuid, tier, points - bot
  names joined from `characters` for normalized matching) plus
  per-(bot, prefix) fact counts (prefix = first 16 bytes of
  `fact_text`, category kept, `GROUP BY`).
* world summary: `botsOnline`, `onlineBots` (sorted names - the smoke's
  bot picker; the same lock-guarded players-map read as
  `online_players()`), `totalFacts`, `totalRelationships`.
* response: `{"ok":true,"scope":"player|world",...}` or
  `{"ok":false,"reason":"world-not-ready|invalid-player-name|player-missing"}`.

## Usage

```sh
# attach to (or boot first via tools/world_console.py mechanics) a device
python tools/rp_harness/run_suite.py --suite smoke --out build/rp-smoke.json

# with the A8 world-log post-pass (pull + scan)
python tools/rp_harness/run_suite.py --suite smoke --out build/rp-smoke.json \
    --world-log build/world.log --pull-world-log

# strict: the log must contain BotLLM generations
python tools/rp_harness/run_suite.py --suite smoke --out build/rp-smoke.json \
    --world-log build/world.log --pull-world-log --require-a8
```

Exit codes: 0 green, 1 failures/A8 violations, 2 harness error (no
device / relay dead).

## Transcript

`build/rp-harness/smoke-transcript.jsonl` - one JSON event per line.
Every event carries BOTH clocks: `mono_ms` (ms-resolution
`time.monotonic()`-based) for latency math and `ts` (ISO-8601 UTC) for
correlating against device logs across reconnects. Event kinds include
`op_send`, `op_result`, `op_timeout`, `reconnect` (adb hiccup: attempt
count + backoff - a recorded event, never a silent retry; per plan H2 a
reconnect FAILS the smoke suite and is tolerated in the battery), `health`,
`forward`, `bot_reply`, `chat_line`, `sys_line`, `reply_evidence`.

## Assertions

`assertions.py` - pure functions over transcript events / parsed op JSON:
`bot_reply` (normalized name match + >=3-word guard), `no_echo_leak`
(the per-run sentinel must never surface in a bot line), `tier_up` (the
"<bot> seems warmer toward you." sys line), `fact_persisted` /
`relationship` (via `llm-memory-state`), `reset` / `reset_cleared`.

## auto_reply stub (relay-min)

`auto_reply.py` documents the reply-detector interface the realmd-auth
slice will implement against a real protocol client (session-key
injection + the forwarded world endpoint `adb forward tcp:3724
tcp:8085`, which `session.py` already owns). In relay-min:

* `attached()` is `False` - no reply TEXT transport exists; a bot's
  whisper back to a socket-less sender is not externally observable.
* `memory_delta(before, after, player)` - new `llm-memory-state` rows
  prove the injected whisper ran the bot's conversational machinery
  (turn evidence, no text).
* `from_log_lines(lines)` - best-effort parsing of observed world.log
  text into reply records.

The smoke's `bot-reply` step uses the plan's three-way outcome table:
observed reply text = **pass**; memory evidence only = **skipped** (text
unobservable without the transport); neither = **fail** (the chat path
is dead - exactly the regression this rail exists to catch).

## A8 invariants

`run_suite.check_a8_lines` scans a world.log for, per req id: exactly
one `BotLLM: dispatch ... req=N`, at least one `begin ... req=N`
EXCEPT busy-class turns - both busy paths (the per-bot governor and the
interactive budget) return BEFORE the begin line, so a busy turn is
dispatch+end only (`NO_BEGIN_CLASSES == {busy}`) - and exactly one
`end ... req=N`. A cap turn DOES carry a begin: the begin line precedes
GenerateHttp, whose first check is the concurrency cap. Zero `BotLLM:`
lines no-op PASSES unless `--require-a8` is given (then it fails). The
result also carries nearest-rank `latencyMs` percentiles (`p50`/`p95`/`n`)
computed from the end lines' `durMs` fields.
