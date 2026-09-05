"""The RP harness assertions.

All assertions are pure functions over transcript events (protocol.py
records) or over parsed relay-op JSON - the host unit battery pins them
with synthetic transcripts, and the suites run the same code on device.

* bot_reply       a bot's reply line exists: normalized name match on the
                  speaker AND a >=3-word text guard (a name alone or an
                  empty/emote-only line is not a conversational reply)
* no_echo_leak    the sentinel token carried by an injected whisper never
                  appears in any observed bot line (injection hygiene:
                  the model must not parrot protocol back)
* tier_up         the system-colored tier-shift line ("seems warmer toward
                  you.") naming the bot exists in the transcript
* fact_persisted  the llm-memory-state op reports fact rows for the player
                  (optionally for one bot and/or one fact prefix)
* reset / reset_cleared  the reset-state op answered ok / a state snapshot
                  shows zero rows
"""
from __future__ import annotations

from dataclasses import dataclass, field

# the A16/E4 tier-shift sys line's stable tail (see TierShiftSysLine in
# PlayerbotLlmRecallCore.h: "<bot> seems warmer toward you." / colder)
TIER_UP_PHRASE = "seems warmer toward you"
TIER_DOWN_PHRASE = "seems colder toward you"

REPLY_EVENT_KINDS = ("bot_reply", "chat_line")
MIN_REPLY_WORDS = 3


@dataclass
class AssertionResult:
    ok: bool
    reason: str = ""
    event: dict | None = None
    details: dict = field(default_factory=dict)


def normalize(name: str) -> str:
    """Case/whitespace/punctuation-insensitive name key (bot names arrive
    in WoW capitalization; transcripts may carry any case)."""
    return "".join(c for c in (name or "").lower() if c.isalnum())


def _reply_records(events) -> list[dict]:
    return [e for e in events
            if e.get("kind") in REPLY_EVENT_KINDS and "text" in e]


def bot_reply(events, bot: str, since_ms: float | None = None) -> AssertionResult:
    """The named bot produced a conversational reply (>=3 words)."""
    target = normalize(bot)
    if not target:
        return AssertionResult(False, "bot_reply: empty bot name")
    for record in _reply_records(events):
        if since_ms is not None and record.get("mono_ms", 0.0) < since_ms:
            continue
        speaker = normalize(record.get("speaker", ""))
        if speaker != target:
            continue
        words = record.get("text", "").split()
        if len(words) >= MIN_REPLY_WORDS:
            return AssertionResult(
                True, f"{bot} replied ({len(words)} words)", record)
        return AssertionResult(
            False,
            f"{bot} line is under the {MIN_REPLY_WORDS}-word guard: "
            f"{record.get('text', '')!r}", record)
    return AssertionResult(
        False,
        f"no reply line from {bot} in "
        f"{len(_reply_records(events))} reply event(s)"
        + (f" since mono_ms={since_ms}" if since_ms is not None else ""))


def no_echo_leak(events, sentinel: str) -> AssertionResult:
    """The injected sentinel never appears in any observed bot line."""
    key = normalize(sentinel)
    if not key:
        return AssertionResult(False, "no_echo_leak: empty sentinel")
    for record in _reply_records(events):
        if key in normalize(record.get("text", "")):
            return AssertionResult(
                False, f"echo leak: sentinel surfaced in bot line {record!r}",
                record)
    return AssertionResult(True, "sentinel never echoed")


def tier_up(events, bot: str, since_ms: float | None = None) -> AssertionResult:
    """The tier-up sys line for the bot exists (normalized name match)."""
    target = normalize(bot)
    for record in events:
        if record.get("kind") != "sys_line":
            continue
        if since_ms is not None and record.get("mono_ms", 0.0) < since_ms:
            continue
        text = record.get("text", "")
        if TIER_UP_PHRASE in text.lower() and target in normalize(text):
            return AssertionResult(True, "tier-up sys line observed", record)
    return AssertionResult(False, f"no tier-up sys line for {bot}")


def fact_persisted(state: dict, player: str, bot: str | None = None,
                   prefix: str | None = None) -> AssertionResult:
    """The llm-memory-state snapshot holds fact rows for the player.

    `state` is the parsed op response; `bot`/`prefix` narrow the match
    (prefix compares normalized, the op buckets at 16 bytes).
    """
    facts = state.get("facts") or []
    for row in facts:
        if bot is not None and normalize(row.get("bot", "")) != normalize(bot):
            continue
        if prefix is not None and \
                normalize(prefix) not in normalize(row.get("prefix", "")):
            continue
        return AssertionResult(
            True, f"fact rows present for {player}",
            details={"factCount": state.get("factCount", len(facts))})
    return AssertionResult(
        False,
        f"no fact rows for {player}"
        + (f" bot={bot}" if bot else "")
        + (f" prefix={prefix!r}" if prefix else "")
        + f" (factCount={state.get('factCount', 0)})",
        details={"factCount": state.get("factCount", 0)})


def relationship(state: dict, player: str, bot: str | None = None) -> AssertionResult:
    """A relationship row (tier/points) exists for the player [and bot]."""
    rows = state.get("relationships") or []
    for row in rows:
        if bot is not None and normalize(row.get("bot", "")) != normalize(bot):
            continue
        return AssertionResult(
            True, f"relationship row present for {player}",
            details={"tier": row.get("tier"), "points": row.get("points")})
    return AssertionResult(
        False, f"no relationship rows for {player}"
               f" (relationshipCount={state.get('relationshipCount', 0)})")


def reset(response: dict) -> AssertionResult:
    """The reset-state op answered ok with its row-count summary."""
    if not response.get("ok"):
        return AssertionResult(
            False, f"reset-state failed: {response.get('reason', response)!r}")
    return AssertionResult(
        True,
        "reset-state ok: factsCleared={factsCleared} "
        "relationshipsCleared={relationshipsCleared}".format(**{
            "factsCleared": response.get("factsCleared", 0),
            "relationshipsCleared": response.get("relationshipsCleared", 0)}),
        details={"scope": response.get("scope", "")})


def reset_cleared(state: dict) -> AssertionResult:
    """A llm-memory-state snapshot shows zero rows (the post-reset world)."""
    facts = state.get("factCount", 0)
    rels = state.get("relationshipCount", 0)
    if facts == 0 and rels == 0:
        return AssertionResult(True, "memory state is empty")
    return AssertionResult(
        False, f"memory state not empty: facts={facts} relationships={rels}")
