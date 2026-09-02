# LLM Banter Core — the 1000-hour voice layer

The deterministic voice under the playerbot LLM companion. Lives in
`native/patches/playerbots/llm_banter_core.h` (pure, header-only, C++11,
no Player/DB/globals), consumed by `PlayerbotLlmPersona.cpp` and proven on
the host by the battery below. Spawned from a four-agent creative audit
(2026-08-22): repetition audit, surprise design, tool architecture, test
architecture.

## Why it exists

Players run 1000+ hours; a veteran must still be surprised. The audit
found the old persona path rotated TWO variants per cell off a GLOBAL
atomic — the same bot could repeat the same line on consecutive triggers,
every bot shared one counter, and nothing differentiated bot #47 from
bot #12. The core replaces that with:

- **Selection engine** (`SelectLine`): per-(bot, audience, category)
  novelty-weighted draws over a 16-slot recency ring. A line cannot return
  until pool_size-2 other lines were heard; ring exhaustion evicts the
  oldest half so randomness resumes (never a fixed cycle). GUID-seeded
  SplitMix32 — identical inputs give identical bots, and the battery pins
  the fingerprint.
- **Wildcard bank** (1% of draws, 120s cooldown): sanctioned chaos —
  philosophical openers, counting compulsions, smell memories, quiet
  prophecies. Rare enough to surprise at hour 1000, common enough to ever
  be seen.
- **Per-bot traits** (demeanor x quirk x tic, GUID-derived, byte-stable):
  prompt-seasoning strings so the MODEL keeps the voice too; tic prefixes
  on authored lines (1-in-3 seeded, never doubled).
- **Corpora** (all ASCII, <=200 chars, no protocol bytes): greetings by
  relationship tier (stranger/acquaintance/ally/trusted), kill confirms,
  rare-loot reactions, idle provocations, reply-to-silence, dares, bets
  (open/win/lose), superstitions, beast-naming, six mood pools, and the
  wildcard bank.
- **Marker neutering** (`NeuterMarkers`): the injection defense — player
  text echoed into prompts/history can carry forged `<<tool ...>>` calls;
  every player-authored string passes through this. Fixpoint loop because
  removing a pair can join two singles into a NEW pair (`><<>` -> `>>`) —
  a real bug the fuzz leg found and the fix comment forbids regressing.

## The battery (tests/test_llm_banter.py + tools/test_llm_banter_core.cpp)

Host-compiled against the SHIPPED header with `-std=c++11` (the oldest
dialect the game build uses). Three legs:

1. **invariants** — every pool line passes the content contract (ASCII,
   length, no `<<`/`>>`/stray braces; `{P}`/`{B}` placeholders allowed),
   traits are GUID-stable, neutering is correct and idempotent, rendering
   is bounded under hostile names, selection survives zeroed state / tiny
   pools / time extremes, RNG golden pin.
2. **sim 500000** — the 1000-hour compression: 200 bots x 50 players x 6
   categories, Zipf-ish bursts, 2-11s inter-arrivals. Thresholds: no
   immediate repeat anywhere; every 16-pick window >= 10 distinct lines;
   wildcard rate in [0.2%, 3%] (design 1%); no line exceeds 35% of a
   pool's draws; byte-identical across runs.
3. **fuzz 200000** — adversarial mutations of tool-marker text, hostile
   names, corrupted state structs, tiny pools. Never crash, never leak
   protocol bytes, never OOB, never double-tic.

Last run: 10/10 green, 500k sim in <1s. Measured: wildcard_rate 1.007%,
zero immediate repeats in 500k draws, worst_index_share 10.1%.

## Extending (the contract)

New lines: add to the pools in the core header — the battery enforces the
content contract mechanically. New pools: add the enum + array + `Pool()`
case; `kMinPoolLines` (6) is the floor the invariants leg asserts. New
categories in-game: keep passing `nowMs` from the caller for cooldowns;
`0` disables them. Any deliberate change to pools or selection updates the
golden pin in the same commit — the battery fails otherwise, by design.

## Next steps (from the creative audit, in surprise-per-line order)

1. Grow persona improv cells from 2 to 12+ lines/cell (the core already
   selects; the pools are the limit) and route the busy-pool variants
   through it.
2. Moods: engine-side entry/exit rules feeding the mood pools + a
   mood-segment in BuildPromptContext (last stable segment, one
   PROMPT_FORMAT_VERSION bump).
3. Counters + first-meeting/anniversary facts (running jokes that
   escalate; the bot remembers YOUR falls at hour 800).
4. Rumor mill with mutation (the crown jewel: retellings drift
   deterministically; the player hears their own legend come back warped).
5. Wildcard events roller (dares, campfire stories, bets on loot).
