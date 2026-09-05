# RP Depth Plan v5 — Local vs Cloud: max player engagement

Status: **FINAL** (round-robin converged 2026-09-03: Wave A proposals → Wave B critique → Wave C verdict CONVERGED). No code changed by this document.
Method: 6 proposal lenses → 6 critics (13 seams code-verified) → 6 verdict reviewers (consistency audit, constraints final-pass CLEAN, second seam verification, roadmap finalization, engagement chair, convergence judge). See §8 for the record.

## 0. Principles (locked)

- No app-side safety/filter prompt text. Zero tokens on it.
- 8 gen/min global device cap stays. Cloud surfaces have their own conf-capped quotas (§5.3) and never compete with device generations.
- Trained default prompt byte-identical when all defaults on. New prompt text rides **seasoning blocks or bridge-note furniture only** — never a new top-level segment, and never a rewrite of a trained fill. Code-verified: the `[State]` fill is hard-locked to trained flavors (PlayerbotLlmMemory.cpp:766 "no invented flavors"), and `ComposeUserTurn`'s `extra` leg renders inside the `[BRIDGE AI]` note and emits nothing when empty — bridge furniture is genuinely outside the byte-identity lock (frozen by the prompt-format golden gates).
- Banklib wording lock: frozen prompt strings change only via `emit_prompt_constants.py` + retrain + golden pins, same commit. Authored corpus (`llm_banter_core.h` pools, recall-cargo banks) is editable-native, not banklib-frozen.
- Silence doctrine (no fact row → no line). Fail-open for pack corruption, fail-closed for unusable endpoints (dead endpoint = silence, never a stuck queue).
- Everything player-visible is player-editable where feasible, and every feature has a kill-switch conf key with a proven zeroed state (§5.4).
- Every feature names its tier — LOCAL / CLOUD / BOTH — plus its LOCAL degradation and its **localizable-when** threshold (ctx + output tokens) so a LiteRT/NPU-class local model can re-tier CLOUD features by table edit.
- **Applies to everything:** any `native/patches` edit requires realm-runtime lockfile refresh + lane rebuild (`build_o09_realm_runtime.py`), per the lockfile pin test.

## 1. Foundations

- **F1. Player-action event sources.** Code-verified: `QueueForPartyBots` (PlayerbotLlmMemory.cpp:1360) is group-gated (`if (!group) return;`) — solo players get zero events today; only level-up/rare-loot/duel mint. Add: **player death (with wipe classification — when the master dies, check group deaths to tell solo death from wipe; same hook, one extra read)**, **player→bot trade completion** (TradeHandler.cpp is hook-free; `HandleAcceptTradeOpcode` moves items and deletes both TradeData synchronously, so polling can miss it — the viable route is a small patch capturing item/money slots *pre-completion*; `Player::GetTradeData()` is public), **zone entry** (PLAYER_EXPLORED_ZONES bits, readable), and **loot-envy** (rare loot won by another bot). Include a proximity fan-out for nearby tier≥2 bots so partyless solo players are covered.
- **F2. Bot↔bot dyad ledger.** New small table (bot, bot, subject, affinity −3..+5) minted from shared party history; follows the `world_gossip` migration precedent. Deferred until its consumers ship (slice 6).
- **F3. Whisper-command grammar — minimal.** The three keyword reads live at SayAction.cpp:780-830; a small parse→dispatch table there serves `/notice` and (later) OOC steering. **Guard: the table must not reorder the E1/E2/authored-greeting early returns guarding it.**
- **F4. ApiTier() dedup + long-form delivery lane.** (a) Factor the duplicated `llmApiProviderSafe && ctx >= 65536` condition (Memory.cpp:273 writer, :753 reader) into one helper so every cloud surface gates identically. (b) The long-form lane is **greenfield, not a retune** (Wave C correction): the queue's `EnqueueValidated` clamps every line to `kMurmurMaxBytes` = 120 bytes and `FatigueAdmits` caps any one fact key at 5 tellings — a staged saga sharing one key dies at line 5 and every line truncates. Needed: a new enqueue+vet path with a per-lane byte budget and a fatigue exemption for staged long-form; plus promoting composer `maxTokens` (hard constant 280 today; murmur lane 48) to a per-lane conf. The queue mechanics that DO carry over: `notBefore` stagger, `kQueueCap` 12, drain ≤2/tick, in-order composer stagger. `LongFormLicensed` covers bridge replies only.
- **F5. Weather + game-hour shim.** `Weather`/`GetWeatherState()` reachable; use a read-only lookup — `FindOrCreateWeather` creates on miss, avoid the side effect.
- **F6. Generic mint-once marker.** `HasFactPrefix` (Memory.cpp:1162) is prefix-scoped; generalize to a once-only mint marker serving anniversaries, first-visits, elite kills.
- **F7. Ambient arbitration — greenfield (Wave C correction).** Wave B named an "A18 crowd arbiter" as owner; no cross-lane arbiter exists. What exists: the chatter `Tick` scheduler + queue under `State().mutex`, per-bot initiative slots, crowd emotes (`TryClaimAmbientSlot` + a global 12s window), and the event-reaction deque — four disjoint mechanisms. F7 federates them under one authored-lines-per-hour counter (§5.2). Schedule it with slice 1 (W1 is F7-paced).

## 2. LOCAL track

### The featured set

- **W1. Event reactions** *(merge of L1.1 + L1.8 + wipe aftermath)*. Corpus cells (cheer, condolence, grim, shaken — 12 lines × 4 archetypes) fired on player death, wipe, player level-up, bot death — all claiming the existing ambient slot under F7's ≤3/hr ambient cap (the cap wins over the 15-min slot). The wipe is the screenshot moment: survivors' shaken lines, a minted gossip row ("we lost Kor in the Deadmines") the rumor mill retells, and **the first post-wipe line is a guaranteed beat outside the paced budget — wipes are rare, and rare must land**. Zero generations. First fires hour 1-10.
- **W2. Errand mini-quests** (L2.1). Tier≥2 bots mint an errand fact with an authored ask ("bring me a Bronze Tube from Stormwind"); completion via the F1 trade hook or arrival; pays +1 sentiment and a settlement beat. **Re-mint cadence: one fresh errand per bot per 1-2 hours** (Wave C: without it, hours 5-10 go quiet after the firsts run out). Player dial: errand frequency (part of §5.4).
- **W3. Earned place memory** *(merge of L1.4 + L2.6 + L1.3)*. First-zone-visit facts (explore bits), elite-kill facts + gossip rows, a visited-places ledger (per-bot zone table; POI picks gain a visited bias so place-talk is earned, not sampled from the static 40-title list), once-only anniversary fact mint (F6). Fires in hour 1.
- **W4. Grudge act-refusal** (L2.4). World-thread tool validator (follow/party_invite, PlayerbotLlmTools.cpp:388/:401 — `GetUnresolvedGrudge` reachable) refuses with an authored one-liner while a grudge stands; gratitude clears it. Volatility-dial-gated.
- **W5. Bot curiosity questions** (L2.2). Third initiative class: GUID-stable authored question table, tier≥2, once per pairing per question, ≤1/30min. **On the 0.8B fallback the answer fact is minted deterministically from the player's raw reply** (q08 fills licensed shapes only 4/6 — a vanished answer is a broken promise); model-mediated log_fact only on the 2B primary.
- **W6. Dyad callbacks** (L2.3, on F2). Party topics read dyad facts ("Kor still owes Bren from Deadmines"), preferring aged untold ones, escalating via existing counter lines. Zero new generations.
- **W7. World-truth lane** *(merge of L1.2 + L1.7 + L2.5; Wave-C reshape: split by default)*.
  - **W7a — weather/hour ambient bias, default-ON:** rain doubles superstition/homesick slots, night doubles nightweary; reweights sampling of existing persona pools — zero tokens, zero prompt bytes touched, works from slice 2.
  - **W7b — scene state + homeland/faction stance, default-OFF furniture:** in-combat/stealthed/dungeon/night and warm-at-home/uneasy-in-enemy-zones lines via the **bridge-note `extra` leg** with a world-truth dial — NOT the `[State]` fill (banklib-trained, "no invented flavors" law) and NOT the pack overlay (process-cached-global, can't carry per-bot lines). Bake-off promotion metric: battery stage-8 scene-state adherence + the §5.1 cache-hit assert.
- **W8. Player `/notice` — scene-read for the player.** A whisper command rendering the live-scene struct W7b builds — location, combat/stealth, wounded members, hour/weather, the current live rumor (newest-8 gossip preference — no credence column exists) — as 2-3 second-person lines to the PLAYER plus one authored in-character nudge. The player's own half of immersion. Zero generations. Rides F3-minimal.

### Hygiene lane (ship silently; not counted as player-facing depth)

- **H1** Un-alias smitten/grudge/grief mood pools (L1.5) — `MoodPoolOf` (llm_banter_core.h:554) aliases the three new moods onto homesick/blooddrunk/nightweary, contradicting the prompt seasoning; three dedicated pools, 36 lines, zero tokens. (Always-on corpus correction; no key.)
- **H2** Per-preset voice exemplar (L2.10) — 2-3 example lines as a fill in the voice-lock block body. **Hard guard: body override applies to seasoning ids only; the native trained-id skip (PlayerbotLlmPrompt.h:563) stays.** Needs preset schema 3 (slice 7).
- **H3** Keyword-triggered lore (legacy S.3) — canonical POI/figure title in any turn injects its card once per session, ≤80 tokens, first drop in the §5.1 order. Default-off until the bake-off.

### Dropped by engagement review (all five kills confirmed by the Wave C engagement chair)

- S.4 `/remember` + `/retcon` — players want to be remembered, not to be memory librarians; ordinary-chat `log_fact`, the persona card, and W5's deterministic answer-mint already cover the want inside 20 hours.
- S.5 swipe — chat-sidebar power tool; also a dialogue-collision hazard (bare "again" is ordinary speech).
- C1.3 budget ledger as a player-facing feature — stays as the internal quota meter (§5.3).
- L1.6 rumor distortion families — definitionally a >20-hour effect; verbatim retelling reads as legend-hardening, not absence.
- L2.11 gossip tone tag — invisible plumbing for a frame suffix nobody sees.
- (L2.9 assembler and L2.8 turn-taking are folds, not kills: L2.9 is C1's local code path; L2.8 is C3's infrastructure — max **2 generated turns** per scene (BatchInFlight is single-flight and drops jobs when the interactive lane is busy), remaining turns authored dyad lines, power-rung-routed, ≤20 scene-min/hr, GovernorAdmit per turn.)

## 3. CLOUD track

Design rule: conf keys in `confBlockExternal` only (verified real, LlmRuntimePolicy.kt:209), fire only when the interactive-lane governor is idle, quota-capped per §5.3, fail-closed to silence.

- **C1. Campfire Saga** (absorbs L2.9; **the cloud trial flagship** — the only cloud feature whose output persists in the world). One composer call (`RunComposerBatchInner` — code-verified single PostChatHttp, speaker-validated parse, staggered enqueue) turns a bot's fact rows + legend counters + anniversary buckets (~2k tokens, bounded) into a 300-600 token saga; **headline-only write-back** as a gossip row (write path Memory.cpp:1276-1286: scrub → neuter → 250-byte truncate — a headline fits, a paragraph does not) so NPCs retell it for weeks. The player's money bought a permanent artifact. Delivery via the F4b lane. **Local degradation (verified):** `LongFormLicensed` never gates at 0.8B's 210 tokens; the 2B at 230 earns a licensed ~255-token telling; deterministic frame-stitch assembler covers the rest.
- **C2. Ripening memory** *(recap + reflection)* — "the realm remembers between sessions." **Recap, BOTH-tier:** world-start digest of DB facts + anniversary buckets with a created_at "since last world start" filter (Wave C correction: legend/fatigue counters are process-lifetime and invisible at world start — the digest carries DB truth only), silence below 3 new rows. Deterministic digest first (zero calls, works offline); cloud prose second as the **free first touch of the trial** (4-6 short chat lines within the murmur law — no F4 dependency). **Reflection, CLOUD:** on logout, one call synthesizes 3-5 dense memories from **top-12 rows/bot by recency×tier** (all-rows is a 20k-token logout bill), rows ~30 tokens, write-backs skipped while any bot runs a local profile; synthesized rows carry a distinct source tag, journal-visible and removable; scrub is transport hygiene only.
- **C3. Roundtable.** Player addresses the party → one composer call yields a 3-6 turn speaker-tagged discussion (~90-100 tokens/turn; rides F4b — round-table turns are long-form lines too). 1 call replaces 3-4 cloud calls. Player-initiated, so it is exempt from the §5.2 session rare-lane cap and governed only by its §5.3 quota. **Local: one speaker per idle window (BatchInFlight is single-flight — honest labeling), or an authored 2-turn dyad exchange at zero gens.**
- **C4. Rare narrator beats** *(milestone narration + drama director, BOTH)*. One rarity budget. The beat PICK is a GUID-stable seeded roll over a frozen enum weighted by dyad affinity and cooldown (zero calls); the cloud call only upgrades the wording (milestone narration: tier ceremonies, anniversaries, boss kills; zone/time/weather inputs). Player-editable drama dial; instruction flavor on bridge furniture; non-enum output dropped whole.
- **C5. Player-portrait dossier** (BOTH). Weekly-capped; cloud call synthesizes all bots' facts about the player into one shared dossier row; **local half: deterministic weekly dossier row (deduped/merged fact rows minted by ledger machinery)** — cloud upgrades wording only. Synthesized rows (both halves) carry a distinct source tag, journal-visible and removable. Hour-100 content by design.
- **C6. Tavern serial + inn regulars** (CLOUD; stretch, hour-100). Director wrapper on the composer job: player idles ≥5 min in an inn with ≥3 bots; scene kinds (reunion, rivalry, debt collection) pick the instruction flavor; GUID-stable per-inn casts with per-inn dyad history. **Local degradation: silence (the set-piece simply doesn't fire); authored dyad exchanges remain available as tavern ambience.** Blocked on F2. Risk noted by the engagement chair: if C6 slips, hour-60-100 novelty depends entirely on new mints (new zones, elites, errands) — the long-tail thins at level cap.

## 4. SHARED track

- **S.1 OOC director whisper `((...))`** — post-v1, on the F3-minimal table: parses as direction, rides the bridge `extra` leg, expires on scene change or ~10 min, zero generations; passes the same ScrubControlTokens path as other furniture. Justified by W8's command-use telemetry.
- **S.2 Player persona card** — player-editable "who I am", staged as a file like the prompt pack, opt-in seasoning block; empty renders nothing; ≤120 tokens (§5.1). **Inline onboarding prompt at Trusted tier ("Tell Kor who you are") — never a settings expedition.**
- **S.3 Codex — the long-form reading surface.** Persistent re-readable archive of sagas, recaps, set-pieces, rumor evolution chains; app screen and/or `story` whisper read. F4b delivery is the notification; the codex is the destination — a 6" chat frame scrolls mid-combat and a drip-fed saga loses its middle. Unreadable long-form is wasted cloud spend; replay is where long-form compounds. Local: assembler beats still archived. (Pure read surface — no kill-switch key beyond UI visibility.)

## 5. Cross-cutting laws

### 5.1 Seasoning budget
Hard cap **300 tokens / ~8 lines across ALL opt-in seasoning blocks, defaults ~0**: persona ≤120, lore card ≤80, exemplar ≤60, stance+dossier+mood ≤40; enforced at prompt build with drop order lore → exemplar → persona. Context math (Wave C correction): the local server runs a **single 12288-token slot** (no `--parallel` flag exists anywhere in the repo; "2 concurrent generations" is app-side admission via `LLMMaxSimultaniousGenerations`, queued on one slot), so the unmanaged ~550-token worst case is ~4.5% of context — it fits, but it is paid as prefill on every local generation and breaks the shared cache prefix. Volatile content sits at the **prompt tail before history, ordered stable→volatile** (persona, exemplar, dossier, stance, lore, OOC note); the battery asserts cache-hit rate.

### 5.2 Pacing caps (F7 owns the ceiling)
**Global ≤8 authored lines/hr; ambient ≤3 of those (weather/hour/scene share one slot); any single category ≤2/hr; bot questions ≤1/30min; rare-lane beats ≤1/session; recap only when ≥3 new rows.** Exemptions: the first post-wipe line (guaranteed, once per wipe) and player-initiated calls (C3 roundtable is governed by its §5.3 quota only). These are engine limits with a rationale (wallpaper prevention), exposed to the player as one global chatter-frequency multiplier dial.

### 5.3 Cloud quota table (the player's API bill)
| Surface | Default cap |
|---|---|
| Sagas | ≤3/day per roster (not per bot) |
| Recap (prose) | 1/world-start, ≤6/day |
| Narrator beats | ≤10/day |
| Roundtable | ≤30/day |
| Tavern set-pieces | ≤4/hr, ≤12/day |
| Reflection | 1/logout, ≤3/day |
| Dossier | 1/week |

The ledger shows estimated $/day, not just counts. No cloud keys in the local conf block (policy test).

### 5.4 Rollback key map
| Feature | Key (AiPlayerbot.*) | Zeroed state |
|---|---|---|
| W1 reactions + debt beat | LLmEventReactionsEnabled = 0 | no event minting, no queue |
| W2 errands | LLMErrandsEnabled = 0 (+ frequency dial) | no errand asks |
| W3 place memory | LLMPlaceMemoryEnabled = 0 | no new mints (existing facts remain; silence doctrine governs lines) |
| W4 grudge refusal | LLMGrudgeRefusalEnabled = 0 (+ volatility dial) | tools always allowed |
| W5 questions | LLMCuriosityEnabled = 0 | no asks |
| W6 dyads | LLMDyadLedgerEnabled = 0 | no dyad topics |
| W7a bias | LLMWorldTruthAmbient = 0 | unbiased table |
| W7b furniture | LLMWorldTruthFurniture = 0 | byte-diff proves trained default restored, both directions |
| W8 /notice | LLMSceneReadEnabled = 0 | command answers nothing |
| S.1 OOC | LLMOocNotesEnabled = 0 | `((` is dialogue |
| C1-C6 | per-surface quota keys (§5.3) = 0 | surface dead, fail-closed silence |
| F7 pacing | LLMAuthoredLinesPerHour (default 8) + player multiplier | — |

H2 exemplar and H3 lore default off (absent override / key 0); H1 is an always-on corpus correction with no key.

### 5.5 Schema/transport
S.2 + H2 need preset **EXPORT_SCHEMA 3** with a legacy-strip path (the SCHEMA_1 pattern, BotPresetStore.kt:378-390); `llmPackDeltas` is boolean-only, so block-body overrides need a new bodies map + native parser extension (seasoning ids only). F2's table follows the `world_gossip` migration precedent.

### 5.6 Telemetry + locale
Per-feature local counters (command use, errand completion rate, codex opens, recap→session-length, swipe-free dissatisfaction proxies) — local-only, opt-in exportable. Locale-suffixed corpus file resolution (community translation, zero engine work).

## 6. Final roadmap (Wave C ranking, dependency-ordered thin slices)

- **Slice 1 — two hooks and a debt:** F1-thin (death hook incl. wipe classification + trade pre-completion capture patch) + F7 → **W1 event reactions** (death/level-up/wipe, guaranteed first post-wipe line) + **debt-settlement beat** (the shipped-but-unwired `TierBeatCargo` kind 1, fired by the trade hook when a debt fact stands — owned by W2's settlement machinery, shipped early because it is nearly free) + **W4 grudge refusal**. Highest hour-1-10 density per engineering hour; zero cloud cost; zero trained-default risk.
- **Slice 2 — place and time:** F5 + F6 → **W3 earned place memory** + **W7a weather/hour bias (default-ON)** + **W5 curiosity questions** + H1 mood pools.
- **Slice 3 — the realm remembers:** F4a (ApiTier dedup) + quota meter → **C2 recap**: deterministic digest first (free, offline), cloud prose second as the trial's free first touch.
- **Slice 4 — the player's half:** F3-minimal → **W8 `/notice`** + **W7b scene-state furniture** (bake-off decides default-on).
- **Slice 5 — the flagship:** F4b delivery lane (new enqueue+vet path + composer maxTokens conf) → **C1 saga** (trial flagship) + **S.3 codex**.
- **Slice 6 — social:** F1-full (zone entry, loot-envy) + F2 → **W6 dyad callbacks** + **C3 roundtable** (with the 2-turn scene contract) + **C4 narrator/drama beats** + **C5 dossier**.
- **Slice 7 — identity:** **S.2 persona card** + preset schema 3 + H2 exemplar + H3 keyword lore.
- **Slice 8 — steering:** **S.1 OOC whisper** on the shipped F3 table.
- **Slice 9 — the serial:** **C6 tavern + inn regulars**.
- **Stretch:** hook rumors, secrets chain.

**Onboarding defaults** (fresh local-tier player, zero settings): W1 death/level-up/wipe reactions, debt beats, W4 refusal (mid-rung dial), W3 firsts/anniversaries/legends, W7a weather/hour, W5 questions (≤1/30min), W8 `/notice`, deterministic recap digest (≥3 rows), H1 pools — all under §5.2 caps; seasoning blocks default empty per §5.1. **Cloud trial:** the first world-start prose recap is the free first touch; the first saga is the flagship moment (one capped call, priced in the ledger, persistent opt-out; nothing else cloud fires uninvited). **Persona card** prompt fires once at Trusted tier.

## 7. Measurement (per feature, before merge)

- Zero-token features: corpus pins + repetition-coverage sim; pacing-cap tests against F7.
- Prompt-side: prompt-dump byte-diff (exactly the intended lines; trained default untouched, both directions); battery token meter; cache-hit-rate assert (§5.1).
- Generative: battery stage 8 (RP-depth) extended — steered-vs-unsteered, scene-state adherence, curiosity answer-capture rate on both local models; battery stage 9 (tavern) extended to wipe-seeded and directed rounds; governor accounting tests (scene contracts, question cadence).
- Cloud: parser drop tests (over-budget/marker/unknown-speaker lines dropped whole), scrub/neuter fixpoint on write-backs (transport hygiene only — no content filtering), quota policy tests, codex delivered-vs-opened ratio.

## 8. Review record

- **Wave A (6 lenses):** 46 proposals; cross-lens convergence on event sources (3 lenses), dyad state (3), the composer path as cloud carrier, persona/steering as the player-agency gap.
- **Wave B (6 critics):** 13 seams code-verified (2 corrections: trade hook greenfield, whisper site SayAction.cpp); 1 constraint violation (L1.7 trained-fill rewrite → bridge-leg carrier); 2 BLOCKERs (seasoning stack → 300-token cap; scene budget → 2-turn rung-routed contract); 7 kills, 5 merges, 4 retags; 3 new proposals (/notice, wipe aftermath, codex); 6 structural amendments.
- **Wave C (6 verdicts):** consistency audit — 6 editorial must-fixes (id collisions, quota/cap conflicts, C6 label) all folded; constraints final pass — CLEAN, W7 carrier verified against the golden gates; seam re-verification — 3 Wave-B claims corrected (no cross-lane arbiter → F7 greenfield; queue cannot host sagas → F4b is a new enqueue+vet path; ctx is a single 12288 slot, no --parallel); roadmap finalized (W8 hoisted to slice 4, digest-first recap); engagement chair — SHIP with one reshape (W7 split default-ON/OFF, errand re-mint cadence, guaranteed wipe beat; all 5 kills confirmed; trial flagship = saga); convergence judge — **CONVERGED**, idea space exhausted (Wave B's delta over Wave A was almost entirely bounding work — kills, merges, caps — the signature of a closed space).

## 9. Implementation status (2026-09-04, post-slice build)

IMPLEMENTED (all compile-gated against the real NDK toolchain + host-pinned):
- Slice 1: F1-thin (player-death/wipe hook at SetDeathState, trade hook pre-moveItems), F7 arbiter (global ≤N/hr + category caps, ambient-slot + chatter-drain wiring), W1 condolence/shaken cells, EVENT_DEBT_SETTLED kind-1 beat, W4 grudge act-refusal, H1 dedicated mood pools.
- Slice 2: W7a weather/hour bias (default ON, LLMWorldTruthAmbient), W3 first-visit facts (explore-bit hook), elite-kill facts + town rows, anniversary mints, W5 curiosity bank (16) + deterministic answer capture at the bridge.
- Slice 3: F4a ExternalApiTierActive() dedup, C1.3 CloudQuotaAdmits, C2 recap (deterministic digest + quota-capped cloud prose, SplitNarratorBlock safety law).
- Slice 4: W8 `/notice` scene read + POOL_SCENE_NUDGE, W7b scene/homeland furniture (bridge extra leg, default OFF), HomeZoneOfRace/IsEnemyCapitalZone pure helpers.
- Slice 5: F4b long-form lane (200-byte line law, one-shot F7 charge, fatigue bypass), C1 campfire saga (posture/tier/quota gated, headline gossip write-back), S.3 `story` codex whisper read.
- Slice 6: F2 in-process dyad ledger (elite co-kills + shared wipes mint), W6 dyad party topics, C4 authored drama set pieces (3 kinds × 6, affinity-shaped), C3 roundtable row (master's party line → composer, quota-capped), C5 weekly dossier (deterministic row + cloud wording upgrade with fallback).
- Slice 7 (lean): S.2 persona card as the `player-persona` seasoning pack block (empty default renders nothing — no schema change needed), H3 keyword lore (once per session per pairing, question path keeps first claim).

DEFERRED (documented trade-offs, not silent cuts):
- S.1 OOC whisper: post-v1 per the plan's own arbitration (W8 command-use telemetry is the evidence gate).
- H2 per-preset voice exemplars + preset schema 3: pending the bake-off; the persona block pattern shows the no-schema path.
- C4 cloud wording upgrade: the authored deterministic beat is the v1; the upgrade is a follow-up call.
- C6 tavern serial + inn regulars: stretch (hour-100), blocked on the dyad ledger's restart persistence which is documented as process-lifetime.
- Codex app screen: the `story` whisper read is the v1 surface.
- Loot-envy event source: cut (not in the engagement top-10).
- Hook rumors + secrets chain: stretch, cut from v1.

New conf keys (all fail-open, documented in the driver's conf-dist block):
LLMEventReactionsEnabled, LLMGrudgeRefusalEnabled, LLMAuthoredLinesPerHour, LLMWorldTruthAmbient, LLMRecapEnabled, LLMRecapProse, LLMRecapProsePerDay, LLMSceneReadEnabled, LLMWorldTruthFurniture, LLMSagaEnabled, LLMSagaPerDay, LLMRoundtablePerDay, LLMDossierEnabled, LLMDossierPerDay.

### Wave-1 review amendments (folded)
- The condolence over a body is exempt from ALL pacing (the plan exempted only the first post-wipe line): deaths are bounded by the 6-hour mint-once window and the single speaker, so the wider exemption is safe and recorded here.
- Key folds: W3/W5 minting and the wipe aftermath ride LLMEventReactionsEnabled; W5 curiosity additionally rides LLMCuriosityEnabled; C4 drama rides LLMDramaEnabled. All three keys exist with default 1 (plan §5.4's map updated by this note).
- The mood seasoning line (Phase-3, prior batch) renders always-on by design of that converged batch; this plan adds no new always-on prompt text (W7a is sampling-only; W7b default OFF; persona block default empty).
- The 300-token seasoning budget is ENFORCED natively (whole trailing lines drop past ~1200 bytes in LoadPackSeasoning).
- F7 semantics hardened after review: budget stamps land only on CONFIRMED deliveries (peek-then-stamp); murmur batch dispatch is skipped entirely when the ambient budget has no room; saga blocks precheck queue/ring before charging.
- The recap prose path falls back to the deterministic digest on ANY failure (a dead endpoint never costs the digest).
- Cloud workers re-check ExternalApiTierActive() inside the thread (a tier flip mid-queue never routes a cloud call to a local endpoint).
- The weekly dossier gate is per player, stamps only when a mintable fact exists, and its cloud half requires a 7-day-old pairing (nothing cloud fires uninvited at a first login).
