"""Bot-reply detection for the RP harness - STUB INTERFACE (relay-min).

The realmd-auth slice (suites/realmd_auth.py) attaches a protocol client:
it provisions an RPTEST account, injects the session key into the account
row, and opens a direct world session over the forwarded endpoint
(session.py owns `adb forward tcp:3724 tcp:8085`). That client sees bot
whispers as they arrive and emits `bot_reply` transcript events with real
text. IT IS OUT OF SCOPE for relay-min.

Stable interface consumed by the suites (implementations may evolve):

    attached() -> bool
        Whether ANY reply transport is wired this run. False in relay-min.

    poll(session, *, player=None, bot=None, since_ms=None) -> list[dict]
        Drive whatever observation channels exist and return transcript-
        shaped records (protocol.event-compatible dicts, `kind` in
        {"bot_reply", "chat_line", "sys_line", "reply_evidence"}).

Relay-min's best-effort channels (both implemented here):

* `from_log_lines(lines)` - parse observed world.log text for chat lines
  (the staged LogFileLevel gates what exists; a verbose world log carries
  delivery traces). Never fabricated: unmatched lines yield nothing.
* `memory_delta(before, after, player)` - a pure comparison of two
  llm-memory-state snapshots; new relationship/fact rows for the player
  prove the injected whisper ran the bot's conversational machinery
  (evidence of the TURN, not of a reply text). Records carry
  kind="reply_evidence" so bot_reply's >=3-word text guard correctly
  refuses to call them replies.

The three-way outcome the suites derive (the plan's outcome table):
reply text = pass; memory evidence only = skipped (text unobservable
without the transport); neither = fail (the chat path is dead).
"""
from __future__ import annotations

from .assertions import normalize
from .protocol import event


def attached() -> bool:
    """No protocol client is wired in relay-min."""
    return False


def from_log_lines(lines, source: str = "world.log") -> list[dict]:
    """Best-effort: turn observed log text into chat/sys records.

    Understood shapes (all optional-whitespace tolerant, case-insensitive
    markers; only lines that actually match produce records):

      ... <Speaker> tells you: <text>          (a whisper to the observer)
      ... <Speaker> says: <text>               (ambient say)
      ... <Bot> seems warmer toward you.       (the tier-up sys line)
    """
    records: list[dict] = []
    for raw in lines:
        line = raw.rstrip("\n")
        lowered = line.lower()
        if " tells you: " in lowered:
            head, _, text = line.partition(" tells you: ")
            speaker = head.strip().split()[-1] if head.strip() else ""
            if speaker and text.strip():
                records.append(event("bot_reply", speaker=speaker,
                                     text=text.strip(), channel="whisper",
                                     source=source))
        elif " says: " in lowered:
            head, _, text = line.partition(" says: ")
            speaker = head.strip().split()[-1] if head.strip() else ""
            if speaker and text.strip():
                records.append(event("chat_line", speaker=speaker,
                                     text=text.strip(), channel="say",
                                     source=source))
        elif "seems warmer toward you" in lowered or \
                "seems colder toward you" in lowered:
            records.append(event("sys_line", text=line.strip(), source=source))
    return records


def memory_delta(before: dict | None, after: dict, player: str) -> list[dict]:
    """New relationship/fact rows between two llm-memory-state snapshots.

    Returns kind="reply_evidence" records (NOT bot_reply: they prove the
    turn ran, but carry no reply text for the >=3-word guard).
    """
    before_rels = {(r.get("botGuid"), r.get("tier"), r.get("points"))
                   for r in (before or {}).get("relationships", [])}
    before_facts = {(f.get("botGuid"), f.get("prefix"), f.get("category"))
                    for f in (before or {}).get("facts", [])}
    records: list[dict] = []
    for row in after.get("relationships", []):
        key = (row.get("botGuid"), row.get("tier"), row.get("points"))
        if key not in before_rels:
            records.append(event("reply_evidence", player=player,
                                 channel="memory", detail="relationship",
                                 bot=row.get("bot", ""),
                                 tier=row.get("tier"), points=row.get("points")))
    for row in after.get("facts", []):
        key = (row.get("botGuid"), row.get("prefix"), row.get("category"))
        if key not in before_facts:
            records.append(event("reply_evidence", player=player,
                                 channel="memory", detail="fact",
                                 bot=row.get("bot", ""),
                                 prefix=row.get("prefix", ""),
                                 category=row.get("category", "")))
    return records


def poll(session, *, player: str = "", bot: str | None = None,
         since_ms: float | None = None) -> list[dict]:
    """Drive the relay-min observation channels.

    Currently: the player's llm-memory-state snapshot (rows appearing
    after a whisper are reply evidence). Log tails are read by the caller
    (run_suite's --world-log) via from_log_lines.
    """
    if not player:
        return []
    response = session.send("llm-memory-state", arg1=player)
    records = [event("reply_evidence", player=player, channel="memory",
                     detail="state", bot=row.get("bot", ""))
               for row in response.get("facts", []) or []]
    records += [event("reply_evidence", player=player, channel="memory",
                      detail="relationship", bot=row.get("bot", ""))
                for row in response.get("relationships", []) or []]
    if bot is not None:
        records = [r for r in records
                   if normalize(r.get("bot", "")) == normalize(bot)]
    if since_ms is not None:
        records = [r for r in records if r.get("mono_ms", 0.0) >= since_ms]
    return records
