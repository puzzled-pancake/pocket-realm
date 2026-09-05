# Bank authoring handoff R5 — Phase-6 RP-depth rows (winning pack variants)

Follows R4 §1-7 (opener law; terse floor since relaxed to 4w, see
§2; gates-before-compose,
sessions 12-16 turns, P52 8-20w L3c, 150-word hard cap L3b only for
cue-bearing rows). New in R5: rows for the WINNING pack variants —
initiative openers, mood-seasoned replies, rumor hops, tier-5 bickering,
tavern/campfire registers — within the word laws; tool rows ≤130 words
prose (prose + tool lines ≤ the 230-token E2B cap at ~1.5 tok/word).

## 1. New banks (all cue-bearing shapes use the frozen LONGFORM_CUE)

| bank | rows (target) | content |
|---|---|---|
| P55 initiative openers | ~120 | bot-speaks-first openers from a real fact (debt/goal/event): opener + one-line reply, each fact opens ONCE. Register: plain voice, no tools on the opener turn (the engine's one-note law). Negative controls: stranger arrivals (no greeting memory → silence, not invention). |
| P56 mood-seasoned | ~200 (25 × 8 moods) | same turn answered in all 8 weathers (bored/blooddrunk/homesick/coinheavy/nightweary/smitten/grudge/grief): the weather colors edges, never flips character. Grudge never names itself; grief quiets (shorter band); smitten brightens. Volatility note: weather TURNS are trained, weather DECISIONS are engine-side (no prompt teaches transitions). |
| P57 rumor hops | ~150 | 3-hop chains on one event row: originator verbatim → hop-1 sharpening → hop-2 money rung → hop-3 frozen (cap). Hearsay marked every hop; invented names/outcomes are the failure class. POI-biased subset: place-named rows (≈1/3, from the 137 POI titles). |
| P58 tier-5 bickering | ~120 | bonded pairs trading sharp-fond lines: snap + grin, never mean. Distinct from insults (grudge cargo) by the warmth marker. Tier-5 ONLY shape; lower tiers must not bicker (negative controls at tier 3-4). |
| P59 tavern/campfire | ~200 | 4-voice scenes: gruff/wry + loud/boastful + soft/precise + quiet/dry around ONE happening. No shared 6-word shingles across voices (the S9 gate); every voice event-anchored; zero tools; mood-colored per voice. Campfire subset: nightweary register (quiet, honest, short). |
| P60 anniversaries | ~60 | milestone turns at 30/100/365-day tenure: month familiarity, hundred-day crossings, year weight. Journal-adjacent register (warm, brief, unexplained — the ceremony law, never naming the mechanic). |

**Known coverage gap (tracked):** the beat-cargo frames now ship SIX
flavors per kind (`BEAT_CARGO_VARIANTS`, banklib law `want 6`), but the
CURRENT weights were trained on flavors 0-2 only. P55-P60 above do not
yet give flavors 3-5 a home — cover them by drawing each cargo kind's
examples across all six flavors (flavor index = bot_guid % 6) in these
rows, or schedule a dedicated top-up bank; until then the new flavors
ride the engine unweighted (they render, but are not tuned).

## 2. Word laws (unchanged, restated for the new drafters)

- Word floors per banklib (6w for standard/verbose rows; terse rows
  validate at 4w); tool rows ≤130w prose;
  long-form 80-150w ONLY with the frozen cue (L3b); P52 murmur 8-20w (L3c).
- Opener cap: first word max TWICE per card across ALL banks in the file.
- Tool-row dilution: P55-P60 are nearly all tool-free; top up protocol
  share per the standing rule (place protocol turns INSIDE arcs/scenes,
  not isolated).

## 3. Gates (extend G5, n=3 majority, fixed seeds)

- S8 RP-DEPTH (`tools/llm_lab/s8_beats_gates.py` RP_DEPTH leg): initiative
  fit, keyword-grounded rumor fidelity (hearsay wording; invented
  names/outcomes stay a human-review read on the artifact), grudge
  continuity, mood-4turn voiced+varied — across the three arms
  (plain/seasoned/seasoned+mood) on one tier-3 card per --model run;
  per-VARIANT numbers require pasting each candidate into the seasoning
  arm (the bakeoff docstring says the same). Ship per-tier winners: E2B
  standard/verbose where it wins, Qwen-0.8B terse-only.
- S9 tavern (`tools/llm_lab/s9_tavern_gates.py`): 4-bot rounds, gates
  4/4 voiced, ≥3/4 anchored, 0 tools, 0 shared 6-word shingles.
- Token-cost column per model per phase in every run note (chars/4
  heuristic + `/tokenize` measured where a server is up).
