# Tier 2 design note: player-facing whisper replies on a direct path

Status: DESIGN — not implemented. Gate: needs the AppendTurn trade
adjudicated (below) and Tier 1 delivery data from a live battery.

## The problem

A generated whisper reply travels (all intentional design):

1. async worker (MiniMax M3, ~2-6 s)
2. lines built with typing pacing (35 ms/char, reaction beat, per-line
   floor) — the typist illusion
3. `ChatReplyAction` → `SendDelayedPacket(bot session, fut, reqId, botGuid)`
4. packets queued into the **bot's own session** (fake CMSG rows)
5. drained only on the **bot's session update tick**
6. `HandleMessagechatOpcode` re-entry → server routes the whisper to the player

The bot's update cadence is activity-dependent: an active bot drains in
~seconds; a bot demoted to **inactive** (player walked away, player
`.appear`ed elsewhere) drains on a ~10 s internal delay — or effectively
never for a player who has moved on. Authored replies
(`bot->Whisper(...)`) bypass all of this and always arrive instantly.

Tier 1 (shipped) adds the `BotLLM: deliver req=... bot=... packets=...
paceMs=...` hand-off line so the harness can now SEE which side of step
5 a reply was lost on. Confirm that split from a live battery before
building Tier 2.

## The proposal

For `LLM_SRC_CHAT_REPLY` turns with a real interlocutor and
**whisper-class** replies only: after pacing (step 2, unchanged — the
pacing is the experience), route each line through server-side
`Whisper(...)`-equivalent delivery instead of the bot-session queue
(steps 3-5). Say/party/channel replies keep the existing paced
session path (their delay reads as the bot speaking in place, which is
correct for audible channels).

## The trades that need adjudication

1. **Exactly-one AppendTurn.** The session-queue path re-enters
   `HandleMessagechatOpcode`, which is also how the bot "hears" its own
   reply. The rolling-history write already happens separately in
   `GenerateResponsePackets`; a direct path must add NO second history
   record and REMOVE none. Verify by counting history rows for a
   whisper turn before/after.
2. **Anti-flood checks.** The session path runs `CheckChatMessage` and
   friends on the re-entry. A direct path must still respect the mute
   state and the wire limit (255 bytes) — replicate the check, not skip
   it.
3. **The typist illusion on the wire.** The player currently receives
   paced SMSG rows. The direct path must deliver each line at its paced
   delay (per-line sleep in the delivery worker), not dump the paragraph
   at once.
4. **Bot presence.** A direct `Whisper` from a bot whose session is gone
   (logged out mid-generation) must drop silently — the
   `BotLLM: deliver ... dropped=session-gone` line covers the
   observability (logged for every delayed-packet caller, the reqId-0
   journal family included).

## Root cause found before Tier 2 was needed (2026-09-12)

The Tier 1 `deliver` line exposed that the session-lifetime guard's
liveness oracle (`sWorld.FindSession(accountId)`) matched NOTHING: bot
sessions are never registered in the world session map, so 100% of
delayed deliveries — every generated reply — were silently dropped
(`packets=0 dropped=session-gone` on 3/3 live turns). The oracle is now
`FindPlayer(botGuid)` + session pointer identity (the pattern
`ReceiveDelayedPacket` always used), re-checked after the pacing sleep
and immediately before each `QueuePacket`. The "bot update cadence
stall" from the original gap analysis was real but secondary; the
registry check was the wall. Tier 2's remaining scope is only the
inactive-bot cadence stall — measure it from a live battery before
building anything.

## Pass bar

10/10 whisper turns in the battery arrive at the client within pacing
budget regardless of what the player does after sending (walk away,
`.appear` elsewhere). A8 re-scan must stay clean (no second dispatch
lines — this changes delivery only, never generation).
