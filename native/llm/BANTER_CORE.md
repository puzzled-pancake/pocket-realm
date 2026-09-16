# LLM Banter Core — the 1000-hour voice layer

The deterministic voice under the playerbot LLM companion. Lives in
`native/patches/playerbots/llm_banter_core.h` (pure, header-only, C++11,
no Player/DB/globals), consumed by `PlayerbotLlmPersona.cpp` and proven on
the host by the battery below.

## Why it exists

Players run 1000+ hours; a veteran must still be surprised. A plain
rotation of two variants per cell off a single global atomic would let the
same bot repeat the same line on consecutive triggers, share one counter
across every bot, and never differentiate bot #47 from bot #12. The core
instead provides:

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
  (open/win/lose), superstitions, beast-naming, five mood pools, and the
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

## Coverage

- Persona mood pools carry 12 lines each (busy included); the wildcard
  bank carries 16.
- Moods: GUID-stable weather (hourly bucket + event nudges) with 8 moods,
  volatility-scaled ambient weights, one seasoning line in the system
  prompt's instruction span (no new segment, no format bump).
- Counters + first-meeting/anniversary facts: counter escalation on the
  Nth telling (retire at 5), anniversaries from oldest-fact created_at
  (30/100/365, journal-visible), tier beats (vouch/bickering,
  journal-visible).
- Rumor mill with mutation: deterministic DistortGossipHop per hop
  (cap 3, originator verbatim) with POI-biased sampling (place-named
  rows travel farther).
- Open ends: the wildcard events roller pools (DARE/BET/SUPERSTITION/
  NAMING) exist with no game-side caller yet; cadence stays at murmur
  20-40s, party 6min@50%, global 45min — the silence doctrine holds
  (no fact row, no line).
