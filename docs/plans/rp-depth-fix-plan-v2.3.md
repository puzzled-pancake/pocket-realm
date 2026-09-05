# RP Depth Fix Plan v2.3 — Cloud Conversation Unlock, Scheduler Fast-Lane, Full-Bot-Count Fixes

Revision 2.3 — amended after three review rounds. Round 1: 27 findings (⟦R⟧). Round 2: 13 findings (⟦R2⟧). Round 3: a 100-reviewer panel audit (~200 findings; 8 invalidations, ~40 major mechanism corrections; amendments marked ⟦R3⟧). This revision supersedes v2.2 entirely; every mechanism changed in R3 is written out in full rather than referenced.

Companion evidence: `tmp/rp_session_transcript_2026-09-05.log` (on-disk, gitignored — §14 commits a sanitized copy under `docs/evidence/`). Prior art: `docs/plans/rp-depth-local-vs-cloud-plan.md` (v5). All file:line citations in this document were re-verified against the working mirror (`native/cmangos/src/modules/PlayerBots/…`) and the overlay tree (`native/patches/playerbots/…`) at revision time; anchor-managed files are cited as `symbol @ file:region` because §0.b edits ride payloads, not lines.

---

## 0. Standing constraints (inviolable, checked per change)

1. **No app-side content-policy/safety boilerplate in any LLM prompt body — zero tokens.** ⟦R3⟧ Carve-out, recorded: diegetic in-character refusal lines (E0's pools, E3's wiring) are *authored content delivered engine-side* — they are never prompt text and never app-side. This line must not be cited to reject refusal pools.
2. **Device lane (on-device llama): 8 gen/min tier cap stays untouched** (per-tier governor staging: TUNED_E2B/BASE_E2B 8/8 per 60 s, TUNED_Q08 8/20 — `LlmModelRegistry.kt:104,129,162`; enforced natively by `GovernorAdmit` @ `PlayerbotLLMInterface.cpp:517`). The device silence-doctrine pin — the literal comment `"silence default: no event, no line, no floor"` at `PlayerbotLlmChatter.cpp:1301`, asserted by `tests/test_llm_chatter.py:149` — must stay green after every change. ⟦R3⟧ Acknowledged: that pin is a *string* pin with no lane awareness; it cannot catch a device-lane leak by itself. The behavioral guard is the T1 negative-pin matrix (§10 T1) — those pins are mandatory companions of A5/A6/A3, not follow-ups. **A device-silence-pin violation in any T gate is a program hard stop (§0.d).**
3. **Trained default prompt byte-frozen.** ⟦R3⟧ Restated precisely: `PlayerbotLlmPrompt.h` and `PlayerbotLlmRecallCore.h` are *emitter-spliced only* — hand edits are forbidden; the only legal change is `tools/llm_lab/emit_prompt_constants.py` regenerating between its hand-added marker pairs, in the same commit as a banklib change and a re-pinned golden. No new prompt/body text lands in these files by any other route. All new bodies reuse `BuildChatRequestBody` (`PlayerbotLlmPrompt.h:617-672`, fully parameterized — verified) and the seasoning paths.
4. **Authored corpus wording lock.** ⟦R3⟧ Corrected scope: `emit_prompt_constants.py` manages *only* the banklib-verbatim block in `PlayerbotLlmPrompt.h` and the frame arrays in `PlayerbotLlmRecallCore.h`. Authored pool lines (E0/E1) are hand-written C++ tables in `llm_banter_core.h` / `PlayerbotLlmPersona.cpp` / `PlayerbotLlmChatterCore.h` under `pocketllm::LineIsValid` + word laws + host-sim + T1 pool pins — the emitter is not in their path and must not be invoked for them. The emitter depends on `G:\NPU LLM` at import; T0 vendors its constant surfaces as fixtures so the gate is hermetic (§10 T0).
5. **Silence doctrine preserved on the device lane; cloud lane speech is quota-capped** (two-tier budget law, §2 A7).
6. **Fail-open pack corruption / fail-closed dead endpoints** — A4/A5 implement the dead-endpoint law (authored fallback instead of silence, cloud lane only). ⟦R3⟧ A4's fallback and A5's floor are *mutually exclusive per failure*: the fallback owns addressed/interlocutor turns; the floor owns only non-interlocutor dead air. Pinned in T1.
7. **Every new behavior has a kill-switch conf key or an explicit preset-field/keyless rationale.** Full key list in §0.a — including the rows v2.2 omitted (C1, C4, C7, E3, B5, G1, A8/A9). Kill-switch convention: opt-out, default on, `0` disables (cf. `LLMEnabled`/`LLMEventReactionsEnabled` default 1 @ `PlayerbotAIConfig.cpp:659/:764`).
8. **Native edits follow the per-file change regime (§0.b)** and pass the compile gate, host suite, and realm-runtime lockfile pin test.
9. **No bot-count reductions anywhere** — including *at runtime*: T4 pins that the admission controller never sheds below target to pass its own p99 bound (§10 T4).
10. **No time estimates; merge gate = compile gate + host suite + lockfile pin + WS-T.** ⟦R3⟧ The smoke suite and terminal gates require the harness; the harness is therefore Phase 0 (§11), not a parallel lane.
11. **Schema changes ride the migration lane — never edit a shipped migration file or the seed DDL of an already-shipped entry.** ⟦R3⟧ Sharpened to the repo's real law: migration SQL is sha-pinned twice (manifest `sql_sha256` and the on-device ledger); the ledger check `DB-REVISION: ledger drift` @ `DatabaseEngine.kt:554-558` fail-closes the world on any byte change to an applied entry. Schema change = new tail entry only (§4 C2).
12. **Player-editable where feasible.** ⟦R3⟧ One-line rationale recorded: conf-internal keys are engine-health knobs with safe defaults; the LLM screen exposes the Cloud conversation toggle (and its spend disclosure), the advanced surface exposes the B2 debug-log toggle — the v2.2 "only LLMCloudChatter in UI" sentence was wrong and is withdrawn.
13. ⟦R3⟧ **No cloud-lane behavior may key on `LLMCloudChatter` alone.** Every widened site consumes the conjunction `CloudLaneOpen() = sPlayerbotAIConfig.llmCloudChatter && ExternalApiTierActive()` (helper in `PlayerbotLlmGates.h`, §10 T1). The key defaults to 1; the tier is conf-static false on every device emission (verified: device blocks never emit `LLMProviderSafe`; every device tier ctx < 65536). T1 pins the mirror case: key on + tier inactive ⇒ device behavior byte-identical.

### §0.a Kill-switch / knob surface (complete list, ⟦R3⟧ re-audited)

Unit and scope stated per key; `0` semantics stated. Quota values previously hardcoded as `CloudQuotaAdmits` literals are now conf keys, following the repo's own precedent (`LLMDossierPerDay` @ `PlayerbotAIConfig.cpp:785`, `LLMSagaPerDay` :781).

| Behavior | Key / surface | Default | Unit / scope | 0 = |
|---|---|---|---|---|
| All WS-A cloud widenings (A1 grant-disjunct, A3, A4-flip, A5, A6, F5-pins) | `AiPlayerbot.LLMCloudChatter` | 1 (new external configs); **0 for the upgrade cohort** via one-time Settings marker + LLM-screen prompt | conf, staged | new legs off; today's external behavior unchanged |
| Unaddressed-party replies (A3) | `AiPlayerbot.LLMPartyReplyEnabled` | 0 until T3 party step green, then 1 | conf | party stays addressed-only |
| Street reaction share | `LLMCloudStreetSayPct` | 25 | % of admitted crowd reactions | emote-only |
| Street / rpgchat / bot2bot daily quotas | `LLMStreetSayPerDay` / `LLMRpgChatPerDay` / `LLMBotToBotPerDay` | 200 / 300 / 300 | per-UTC-day, realm-global, process-local (reset on restart — documented) | surface off |
| Ambient cloud line budget | `AiPlayerbot.LLMCloudLineBudgetPerHour` (⟦R3⟧ **new key**; the device key `LLMAuthoredLinesPerHour` @ `PlayerbotAIConfig.cpp:766`, default 8, is kept unchanged — v2.2's "renamed from llmCloudAuthoredLinesPerHour" was false; that key never existed) | 90 (⟦R3⟧ raised from 30; see A7) | per-hour, realm-global, arbiter-scoped | non-exempt ambient lines off |
| Interactive cloud budget | `AiPlayerbot.LLMCloudInteractivePerPlayerHour` | 240 | per-hour, **per real player** | interactive cloud replies off |
| Dialogue fast-lane (A2) | `AiPlayerbot.LLMDialogueFastLane` | 1 | conf, both lanes | arming off |
| Turn-award cap / weighting (C6) | `LLMTurnAwardDailyCap` + `LLMTurnAwardWeighting` (⟦R3⟧ split — one key cannot express capped-unweighted) | 20 / 1 | per-pairing per-day / flag | cap off, flat +1 / weighting off |
| Deed values (C6) | `LLMDeedPointsFirstVisit/Trade/SharedKill/Quest` | 5/3/2/4 | points per deed | that deed disabled; facts/reactions at the same hooks continue |
| PassiveDelay per profile (B3) | `passiveDelayMs` → `AiPlayerbot.PassiveDelay` (native key exists @ `PlayerbotAIConfig.cpp:113`, native default **4000**) | 3000 (experience presets) / 10000 (others) | ms, per-bot | n/a (preset field; default 10000 keeps legacy emission byte-identical) |
| Party digests (C4) | `AiPlayerbot.LLMPartyDigestPerDay` | 6 | per-day, realm-global | off |
| Greet-repeat guard (C7) | `AiPlayerbot.LLMGreetMemory` | 1 | conf | nonce+marker off (verbatim replay returns) |
| Conversation memory (C8) | `AiPlayerbot.LLMHistoryPersist` | 1 | conf | history stays process-local |
| Debug log staging (B2) | `worldDebugLogs` Settings toggle → conf `LogFileLevel` 1/3 | 0 | app toggle | level 1 |
| realmd reactor keep-alive (G1) | `AiPlayerbot.RealmdTimerMs` | 250 | conf, ms | timer off (documented workaround) |
| TLS verification (G3) | `AiPlayerbot.LLMTLSVerify` | 1 | conf | today's unverified behavior (self-signed LAN endpoints) |
| Keyless by design (rationale rows): C3 deterministic templates, C5 tier ceremony, A8/A9 logs, B6 allocator hygiene, B7 floor value, D1/D4 preset fields, E1/E2 pools | — | — | — | preset = switch / code revert; recorded here so "complete list" is true |

Only these appear in UI: the Cloud conversation toggle (+ spend disclosure + restart note) in the LLM screen, and the B2 debug toggle in the advanced surface. Everything else is conf/preset-internal.

### §0.b Per-file change regime (⟦R3⟧ corrected against the build tooling — the v2.2 description of this was wrong in both directions)

The build driver `tools/build_o09_realm_runtime.py:prepare_cmangos_source()` (=:3648) **wipes and recreates** `native/cmangos/src/modules/PlayerBots/` from the pristine `native/playerbots` submodule every build (:3664-3669), refuses dirty submodules (:3657-3663), then applies overlays (explicit `write_bytes` list, :3757-3777 — exactly 21 files today) and anchor payloads (`replace_anchor`, :3585-3607 — idempotent; raises "source overlay anchor drift" only on UPSTREAM-text mismatch after a submodule re-pin). Therefore there are exactly **four** legal edit lanes:

1. **Overlay files** — edit `native/patches/playerbots/<file>` only (there is no second tree to edit; v2.2's "edit BOTH trees" described a mirror that is regenerated and your edit would be silently erased). Enforced by `patches_content` sha256 in all **three** lane lockfiles (pin test `tests/test_db_async_null_guard.py:263`; every overlay edit requires all three lane rebuilds — there is no `--write-lockfiles` shortcut; T0 adds one, §10). The NEW `PlayerbotLlmGates.h` lands as the 22nd overlay (explicit write_bytes line + lockfile regen; host tests read `PATCHES/<file>`).
2. **Anchor-managed files** — edits EXTEND the existing UPSTREAM/ANDROID payload pairs registered in `prepare_cmangos_source()`. Files: SayAction.cpp/.h, PlayerbotAI.cpp/.h, ChatHandler.cpp, PlayerbotAIConfig.h/.cpp (`PB_CONFIG_`/`PB_LLM_CONFIG_`), PlayerbotLLMInterface.h/.cpp (`PB_LLM_IFACE_`/`PB_IFACE_`), RandomPlayerbotMgr.cpp/.h, RpgSubActions.cpp (`PB_RPG_`), RandomPlayerbotFactory.cpp, PlayerbotLoginMgr.cpp, DebugAction.cpp, aiplayerbot.conf.dist.in, Player.cpp, LootHandler.cpp, Unit.cpp, TradeHandler.cpp, WorldSession.h. ⟦R3⟧ Added: **new anchor pairs required** for C6's quest hook (Player.cpp `RewardQuest` region — no existing payload covers it) and G3's TLS region (PlayerbotLLMInterface.cpp:889-968 sits between existing payloads).
3. **Submodule single-tree files** — AiFactory.cpp, PlayerbotAIBase.cpp, PlayerbotTextMgr.cpp, PlayerbotSecurity.cpp, RpgTriggers.cpp, PlayerbotMgr.cpp (⟦R3⟧ added — D1's login hook at :507), CharacterHandler.cpp (⟦R3⟧ added — bot login callback at :113-173), the cmangos core files Map.cpp, Log.cpp (`src/shared/Log/`), Conditions.cpp, the network files AsyncSocket.hpp/AsyncListener.hpp/AuthSocket.cpp (⟦R3⟧ added — G1/G2 hygiene), and `native/realm-runtime/src/realmd_runtime.cpp` (own tree). ⟦R3⟧ v2.2 called these "working-mirror edits with a host pin" — impossible. The real procedure: edit the submodule file, **commit in that submodule**, bump `PLAYERBOTS_COMMIT`/`CMANGOS_COMMIT` (:37-38), rebuild, add the NEW host source-contract pin reading the pristine tree (pattern: `tests/test_llm_recall.py:415-424` exact-string pins; `test_db_async_null_guard.py:25` pristine-read precedent). Batch these commits — one submodule bump per phase, not per file.
4. **texts.sql** (⟦R3⟧ new lane for E2): `sql/world/ai_playerbot_texts.sql` lives in the pristine playerbots submodule; edits ride lane 3 (submodule commit) AND trip the sqlite-seed pins (escape-count @ `tests/test_sqlite_seeding.py:454`, seed transcript digests) — both re-pins named in E2's change recipe.

### §0.c Security riders (⟦R3⟧ NEW — merge-blocking for WS-A, non-negotiable)

1. **API-key redaction + debug-gate fix.** Today `"Send the request: " + requestStr` — including `Authorization: Bearer <key>` and the full body — lands in `debugLines` (`PlayerbotLLMInterface.cpp:988-989`) and is echoed to any requesting player: `debug llm` is reachable at **SEC_PLAYER** via `.bot debug` (registered SEC_PLAYER @ `Chat.cpp:917`; `HandleBotDebug` passes no security check; `Event(".bot",…)` forces `isMod` @ `DebugAction.cpp:40`). Fix in the same change as A8: redact the Authorization line in debugLines; require real `GetSecurity() >= SEC_MODERATOR` even when source == ".bot". Pin: no key bytes in debug packets, ever.
2. **G3 lands before the first live-key cloud byte** (A0 included). See §8 G3.
3. **Port-parse crash + app-side bound**: widen the endpoint catch to `std::exception` @ `PlayerbotAIConfig.cpp:801-807` (today only `invalid_argument`; a huge port throws `out_of_range` → world fails to boot), and bound the port 1-65535 in `normalizeExternalEndpoint` (`LlmRuntimePolicy.kt:107-120`) so it cannot be typed into the UI. Fail-closed on parse failure (log + dead endpoint → A4 fallback), never silently default 443.
4. **Disclosure + cost copy** rides F3: "Bot chat, including your messages, is sent to your configured external provider" and a token-scale hint (~1-1.5M tokens per active evening — prompt-dominated). Pin the strings in T2.
5. `LLMPromptDumpFile` (`PlayerbotAIConfig.cpp:696`) writes full prompts (facts + verbatim history) to a plaintext file — the app never emits it; T1 pins the app emission absent.

### §0.d Abort/pivot decision table (⟦R3⟧ NEW — the plan's missing risk spine)

| Stage | Observation | Action |
|---|---|---|
| A0.1 probe | Outbound socket / `BotLLM:` line / reply text observed | Proceed (three-way outcome table in A0) |
| A0.1 | Socket blocked in emulator, endpoint reachable from host | Local relay test lane (adb forward); re-probe through it; record "relay-reachable" in provider notes |
| A0.2 journal | Async healthy, zero outbound attempt | **Gate problem, not lane problem** — A1 becomes the critical path; no A2-A7 until the probe goes green |
| A0.3 contract | Auth failure / non-2xx / unreachable | Provider requirements doc (endpoint, auth, ctx ≥ 65536, latency, register); pivot endpoint/provider; no providerSafe hacks as a substitute |
| A0.4 content | HTTP 200 with garbage (refusals, leakage, register drift) in a 50-prompt probe | One bounded remediation: tighten strip matrix + EnqueueValidated vetting, re-probe; still >10% rejected ⇒ treat as A0.3 |
| A0.5 latency | Probe p95 > 3× SLA (>24 s) | A2 mandatory-first; still >3× ⇒ A0.3 pivot |
| Mid-build | Quota economics unreconcilable | Fallback scope: **addressed-whisper-only cloud lane** (drop A3/A6/A1b-widenings; keep A1/A2/A4/A5/A7 with interactive budget only) — roughly halves WS-A |
| Mid-build | A4 threading audit fails | Fallback: interceptors stay on the world thread; cloud generation gets a world-thread timeout + authored fallback; no interceptor demotion, no async delivery |
| T3 | Cloud reply p95 > 2× SLA under single-player load, or spend > 2× projection, or provider availability < 98% | Demote WS-A to experimental: `LLMCloudChatter` default flips 0, UI labels it experimental; WS-B/C/D/E ship independently |
| Any T gate | Device silence pin red, or any device-lane behavioral delta from a cloud item | **HARD STOP.** Merge blocked; if inherent to the design, all WS-A cloud work stops permanently; WS-B/D/E continue |

---

## 1. Background — root causes, inlined (⟦R3⟧: v2.2 deferred these to a nonexistent "v2.0 §1"; they are restated here with transcript evidence and corrected where round 3 invalidated the framing)

- **R1 — party dead-air.** Unaddressed party lines generate nothing: the hard trigger requires `addressedToBot` on SRC_PARTY (`hardTriggerAllowed` @ SayAction.cpp:638-641); the party-recording block (669-685, outside the gate) consumes the pending answer and records the line but never dispatches. Transcript: nine player party lines (:93-104), zero bot replies.
- **R2 — ⟦R3⟧ corrected — gate disagreement, not a dead gate.** `AiFactory.cpp:904` grants "ai chat" whenever `llmEnabled == 2`, and the app emits exactly that in external mode (`LlmRuntimePolicy.kt:393`) and the debug in-process block (`ServerRuntimeFiles.kt:453`). The strategy is live; the RPG lane (chance 100 @ `PlayerbotAIConfig.cpp:875`) and bot2bot routing are **open today**. A1 is a widening + retroactive containment, not a resurrection. The blast radius v2.2 feared (A1b) already exists.
- **R3 — greet latency.** Transcript: named /say greet answered ~4m48s later (:92); scheduler echo drift ~2.5 min (:9-10). Passive bots wake on `PassiveDelay` (app-hardcoded 10000 @ `BotProfiles.kt:129`).
- **R4 — register breaks.** UI-speak refusals verbatim in transcript (:71, :85 — `PlayerbotSecurity.cpp:203/:244`, both `"Invite me to your group first"`; the :85 refusal fired *after* the player invited).
- **R5 — prose defects.** Dossier double-frame verbatim (:113: `Town talk says 'The word on Varleigh: met Varleigh for the first time'` — mint @ `PlayerbotLlmMemory.cpp:2979-2982`), propagated bot-to-bot (:117).
- **R6 — greet verbatim replay across restarts.** `BanterState` seeds are deterministic (`llm_banter_core.h:737-745` — no time entropy), so each boot replays the identical draw sequence per (bot, pool).
- **R7 — realmd instability.** `FATAL: ConnectionAbortedError` (:13) and `world closed` (:105). ⟦R3⟧ Corrected attribution in §8 G1.
- **R8 — log noise hides signal.** Level-3 spam: a prior 30-min soak produced a 79.7 MB world.log (condition-check DEBUG_LOG flood).
- **R9 — ⟦R3⟧ corrected — the world runtime is a bionic/NDK library** (JNI, `native/realm-runtime/src/world_runtime.cpp`); glibc framing retired. B6's allocator facts also corrected (§3).
- **R10 — spawn stacking.** Fresh level-1 bots sit at exact racial start coordinates indefinitely (saved-position login; `RandomizeFirst` early-return at level == start; the periodic teleport requires players online and level ≥ 5).

---

## 2. WS-A — Cloud Conversation Unlock (centerpiece)

Philosophy unchanged: the device lane keeps every restriction; on the cloud lane (`ExternalApiTierActive()` @ `PlayerbotLlmMemory.cpp:2653-2656` — `providerSafe != 0 && ctx >= 65536`, conf-static, verified) the LLM becomes the conversation engine, SillyTavern-style, under the two-tier budget law (A7).

### A0 — Confirmation experiments (before anything else) ⟦R3⟧ restaged

v2.2's staging was wrong twice: `LLMEnabled = 3` selects no network lane (the lane is `LLMBackend = 0` + endpoint), and no shipped path emits 3.

1. **Probe 1 (cloud path)**: stage the **full external block** — `LLMEnabled = 2`, `LLMBackend = 0`, `LLMApiEndpoint` (explicit path, e.g. `https://api.z.ai/api/paas/v4/chat/completions` — the normalizer appends `/v1/chat/completions` to bare origins, which 404s on z.ai), `LLMApiKey`, `LLMProviderSafe = 1`, `LLMContextLength = 131072`, `LLMConnectTimeout = 10` — realm restart, whisper a bot. Instruments: the existing `debug llm` / `debug chatreplydo` console commands (`DebugAction.cpp:58-63,1233-1256` — they echo resolve→connect→TLS→status→body traces) are the primary instruments; the plain whisper is the end-to-end confirmation. ⟦R3⟧ **A8's two quiet-fail log lines land first** (below), otherwise a 200-with-garbage reply is indistinguishable from a dead gate.
2. **Probe 2 (async health)**: whisper `journal` — pure `std::async` memory read (`SayAction.cpp:816-831`), zero network. ≥1 line within 30 s *at staged `PassiveDelay = 4000`* (delivery needs two bot ticks; the bound is cadence-coupled and B3 invalidates probes taken at 10000).
3. **Three-way outcome table** (replaces "contradiction ⇒ stop"): reply text = success; `BotLLM:` error line = network/auth class (record status); pure silence = escalate to `debug llm` before declaring contradiction. Then §0.d's table governs.
4. **Probe 3 (content, 50 prompts)**: novel sentences; >10% refusals/leakage/register drift ⇒ A0.4 path.

**A0.a — Config-key plumbing.** Every new `AiPlayerbot.*` key: member + `GetIntDefault` in the `PB_LLM_CONFIG_*` anchor payload (extending it — a bare mirror edit is erased, §0.b), a `PB_LLM_CONF_ANDROID` conf.dist entry (operator-docs convention — v2.2 omitted all nine), app-side emission **via the appended external/speech block only — never `playerbotConfig()`** (that text is identity-digest-pinned; the `llmSpeech` precedent @ `BotProfiles.kt:49-57`), a T2 gradle emission pin, and a **host driver-payload pin** (extractor pattern `tests/test_llm_player_surface.py:25`). Extend `tests/test_llm_recall.py:415-424` for the new member list. Key list = §0.a. Detekt: the emission-signature changes invalidate 5 baseline IDs (`confBlock`/`confBlockExternal`/`confLines` LongParameterList/LongMethod @ detekt-baseline.xml) — group the cloud keys into one `CloudLaneConf` parameter and regenerate the baseline in the same commit (T2).

### A1 — Widen the live gate + contain the firehose ⟦R3⟧ retitled

`AiFactory.cpp:904` is not dead (R2 above). The change:

```cpp
if (sPlayerbotAIConfig.llmEnabled == 2 ||
    (sPlayerbotAIConfig.llmEnabled > 0 && PlayerbotLlmGates::CloudLaneOpen()))
    nonCombatEngine->addStrategy("ai chat");
```
- Include `playerbot/PlayerbotLlmMemory.h` (module convention — SayAction.cpp:5 precedent; no cycle). Lane 3 regime (submodule commit + `PLAYERBOTS_COMMIT` bump + new pin).
- The SayAction gate (:687) accepts `CloudLaneOpen()` alongside `llmEnabled == 3`; refusal logs once per bot per session (stamp pattern @ `PlayerbotLlmMemory.cpp:1661-1675`).
- `llmEnabled` value legend, recorded once: 0 = off; 1 = native default, strategy not auto-granted; 2 = strategy granted to all (app-emitted on both device-external and debug lanes); 3 = enabled without the strategy (hand-conf only — no shipped path emits it).
- **Semantics decision ⟦R3⟧**: the `== 2` arm stays (it is *today's* external behavior — killing it would change the device-lane-external baseline); the widened gates that matter are keyed on `CloudLaneOpen()` so the toggle actually masters them. The honest negative pin asserts "toggle off ⇒ today's external behavior", not "device behavior".
- **A1b — containment with units**: the RPG lane is open today at chance 100. Gate `RequestNewLines` (`RpgSubActions.cpp:422`, anchor payload PB_RPG_) on `CloudLaneOpen() && CloudQuotaAdmits("rpgchat", sPlayerbotAIConfig.llmRpgChatPerDay)` counting **generations** (one trigger ≈ 5-11 turns — conversations vs generations differ 10×), effective cloud chance scaled 100 → 10 (pacing from cadence, not starvation), silent stop on exhaustion + once-per-day Basic log. Bot-to-bot stays behind the chance key (see A7). Site list corrected: the "ai chat" strategy also gates `isAiChat` @ `PlayerbotAI.cpp:1787` (legacy throttle bypass, noDelay, context accumulation) — add its T1 pins.
- Conf flips require realm restart (staged at start). Documented in the toggle copy. The GM `.(rnd)bot reload` path exists (RandomPlayerbotMgr `HandleConsoleReload`) but leaves strategies stale — noted, not relied on.

### A2 — Conversation fast-lane (bounded) ⟦R3⟧ actuator corrected

v2.2 named two wrong actuators. The real mechanism:

1. **Arming**: `m_dialogueUntil = time(0) + 300` (time_t seconds — codebase convention) set inside `ChatReplyDo` at the async-dispatch site (~SayAction.cpp:1188) and at the *cloud-fallback delivery site post-A4* (v2.2's 909-931 site is relocated by A4 — the pin follows the relocation). World-thread only; plain write (all three ChatReplyDo call sites are world-thread; only the drain site holds `chatRepliesMutex` — v2.2's blanket claim was wrong). Listener bots never arm (non-mention roll loss drops the line before ChatReplyDo). Decide + pin: event turns and bot2bot turns do **not** arm (gate on `gateSpeaker->isRealPlayer()`).
2. **Always-active**: NEW `IN_DIALOGUE` enum value (PlayerbotAI.h:310 region) + an early return in `GetPriorityType()` placed immediately after the real-player/master checks (~6040) and **before** NO_PATH/IN_INACTIVE_MAP/IN_ACTIVE_MAP (below them, cross-map whispers and inactive zones would throttle the interlocutor) + `{0,0}` bracket entry at `GetPriorityBracket` (6167-6173). The Map.cpp:843-856 read is **dropped** — that block is inert (`Player::UpdateAI` discards `minimal` @ Player.cpp:1637-1648). Stamp the AllowActivity 5 s cache hot on arming so fast-lane uptake isn't cache-delayed.
3. **Zone cap**: per-map concurrent count in `PlayerbotLlmMemory` under `StateMutex` (CloudQuotaAdmits pattern). **Scope is per-MAP** (v2.2 said map in A2 and zone in §12/T1 — map is correct; zone wording withdrawn). Semantics: interlocutor admission unconditional (soft cap); non-interlocutor admits require count < 16 after **pruning expired markers** (self-healing: store mapId → {guid → expiresAt}; TTL 300 s; live dialogues re-arm — logout mid-dialogue leaks at most one ghost for ≤ 300 s; no decrement path needed). Fast-lane key gates at the ARMING sites.
4. First-turn latency is **not** covered by A2 (arming is post-drain; delivery already rides the detached `SendDelayedPacket` thread) — B3's 3000 ms is what makes T3's ≤5 s greet bound honest; the §13 echo-deferral rationale is corrected accordingly.

### A3 — Cloud-aware hard trigger + exactly-one responder ⟦R3⟧ rebuilt

Anchor correction: the party block is at SayAction.cpp:669-685 (v2.2 cited 688-698, which points into the gate interior — an anchor-managed file, so a stale offset drifts the payload). The comment in the code documents the current design; the restructure is:

1. **Trigger widening** (`PlayerbotLlmGates::HardTriggerAllowed`): whisper always; party/raid addressed (both lanes) or **unaddressed + realPlayer + `CloudLaneOpen()` + `LLMPartyReplyEnabled`** (cloud only); /say stays name-addressed + real-player on both lanes; trade/general/yell stay non-triggers. The existing recording legs are preserved byte-identically and per-bot: `NotePartyLine` (addressed), `ConsumePendingAnswer` (unaddressed) — and the unaddressed leg is extended to also `NotePartyLine` so the roundtable keeps its fuel. Widened to SRC_RAID for recording parity.
2. **Responder selection — claim-based, never a roll** (v2.2's "per-line roll the party block already computes" does not exist — the block computes no roll; and N independent rolls yield Binomial noise: zero or multiple responders). Mechanism: `PlayerbotLlmMemory::TryClaimPartyResponder(speakerGuid, FNV(msg), groupId)` under `StateMutex`, first-writer-wins (same-map ChatReplyDo calls are sequential within one tick; the mutex covers cross-map groups). Rotation field `lastWinnerGuid` prevents monopolization. The claim is checked at the **top of the party block** (~677) so losers skip context-building, not just dispatch. The addressed bot bypasses the claim. `ConsumePendingAnswer` and the claim are mutually exclusive per line (an armed ask consumes; the deterministic fact is the answer — no double generation).
3. **Bot-authored hole closed**: add `gateSpeaker->isRealPlayer()` to the *addressed* party/raid leg of `HardTriggerAllowed` (today a bot line naming a bot passes the hard gate — real spend per stray mention on the cloud lane). Pin the matrix including this case.
4. **Flood gate**: per-speaker party coalescing — N lines within 2 s = one generation (key like `llmHistoryKey` 0x8000…|SRC_PARTY).
5. T1 pins: exactly ONE generation per player party line across the full N-bot fan-out; recording preserved for all bots; 0-responder and 2-responder failure cases; toggle-off mirror.

### A4 — Authored interceptors demote to cloud failure-fallbacks ⟦R3⟧ five corrections folded in

Split into **plumbing (Phase 3a)** and **behavior flip (Phase 3c)**:

1. **FallbackPlan struct, not pre-drawn text**: the async payload carries pool/category id + channel + award flags (defaulted trailing parameter — the second dispatch site at `RpgSubActions.cpp:567` keeps compiling and stays silent). The line is drawn at failure time on the async thread — the proven `BusyReply(botGuid)` precedent (`PlayerbotLlmPersona.cpp:486-506`). Pre-drawing would advance shared recency rings and mint belief facts for lines never delivered, breaking device-identical behavior.
2. **Threading law**: no `Player*`/`Session*` across the async wait. Awards via a NEW guid-keyed `AddRelationshipPointsByGuid(bot, player, points)` that re-resolves via `sObjectAccessor.FindPlayer` (the `AddBoundedSentimentInput` precedent @ `PlayerbotLlmMemory.cpp:1078-1081`); delivery rides the guid-identity delayed-packet path (`PlayerbotAI.cpp:7931-7944` — NOT the raw-session pointer compare at :7917). T1 includes a source-contract scan: the async region contains no direct `bot->Whisper`.
3. **Failure classes, post-parse**: busy (governor denial → BusyReply persona line, instant pacing — both lanes, unchanged), cap (concurrency `>=` fix from A7 → authoredFallback, never busy), timeout, http_%d, error, empty (including content stripped to nothing — evaluated after `ParseResponse`, the last point emptiness is knowable). The world's log clock is second-resolution; duration is computed in-process and printed (A8).
4. **Single-delivery-closure**: player-line `AppendTurn` stays synchronous pre-dispatch (ordering); the closure owns {deliver, bot-line AppendTurn, guid award} executed exactly once across generated/busy/fallback. The four world-thread interceptor sites (greet 776-780, welcome 801-805, TryFallback 917-929, generated pre-award 968) collapse into it; welcome (authored on both lanes, once per **player** ever — `PlayerHasAnyPairing` @ `PlayerbotLlmMemory.cpp:3047-3055`, not once per pairing) keeps its own award by design and the closure enumerates all sites in the T1 exclusivity pin.
5. **A4/A5 exclusivity**: one owner consumes each failure — the fallback owns interlocutor turns; the floor only fires for non-interlocutor dead air. Pinned.

Device lane: interceptors preempt exactly as today. Cloud lane: generate; busy → persona line; error/timeout/empty → plumbed fallback. First-contact welcome stays authored on both lanes (onboarding beat).

### A5 — Murmur floor as failure-fallback (cloud-scoped) ⟦R3⟧ mechanism specified

1. **Gate**: `CloudLaneOpen()` only (the conjunction, never the bare key). Device pin stays green *and* the new behavioral device pins assert the floor never fires device-side.
2. **Branch restructure**: the existing floor branch (`PlayerbotLlmChatter.cpp:1339-1386`) is the `!composer && !generated` leg — **unreachable whenever a composer URL is configured**, i.e., exactly on the cloud lane. The ladder gains a post-failure floor leg for composer-configured rungs; the dormant manual-override leg stays byte-identical (source pins at `tests/test_llm_chatter.py:146-217` stay green).
3. **Failure latch**: `std::atomic` {lane, class, time} in ChatterState, written ONLY by `RunComposerBatchInner` when `PostChatHttp` returns `"error"` (hard/timeout) — never `{}` (busy), never by the device worker; read-and-clear at the refill tick; expires after N minutes; effective spacing doubles per consecutive failed batch (capped). Chatter never reads `LLMCloudChatter` today — the gate is newly wired.
4. **No ARB exemption** (v2.2's claim contradicted the code — the floor is re-checked at delivery @ Chatter.cpp:1220-1222 and that re-check *is* the sustained cap). Floor entries keep their own enqueue guard set (lastFloorAt — stage a real `floorMinSpacingSec` ≥ 270, today 0 on every rung, RingAdmits, kQueueCap=12, TemplateSpacingAdmits) and ride the normal delivery stamp. "Never for budget drops" is implemented structurally by the batch gate at 1278-1287, which the floor sits inside — pinned.
5. F5's content (never wall-clock, in-flight suppression) becomes two T1 pins under A4/A5 — no separate workstream.

### A6 — Street reactions (cloud lane) ⟦R3⟧ admission pipeline specified

The crowd branch (SayAction.cpp:649-660; `QueueCrowdEmote` @ `PlayerbotLlmMemory.cpp:2123-2166` — 10% roll, 12 s world window, per-bot slot, 7-emote table) stays the entry point. Order matters — pin it: **world/zone window claim → per-bot slot → `LLMCloudStreetSayPct` roll → `CloudQuotaAdmits("street", key)` → dispatch; emote on any rejection.** The pct roll's position after the windows is the difference between a quota lasting a session and draining in two minutes (per-hearing-bot rolls at 40 bots ≈ 10 candidates/line). "Per-zone pacing via TryClaimAmbientSlot" is withdrawn — that helper is per-bot keyed and drags generated lines into the authored AMBIENT budget; instead a per-AreaId window map beside `LastCrowdEmoteAt` + either a new `ARB_STREET` category or quota-first admission exempt from the authored arbiter. Delivery via an authored SAY `EventReaction` (2-5 s `notBefore`; world-thread drain @ `PlayerbotAI.cpp:267-303`), NOT the shared chatter queue (20-45 s display pacing is stale for street). The emote is dropped (not deferred) during generation. Bodies: new `StreetSystemMessage`/`StreetNote` functions in `PlayerbotLlmChatterCore.h` (overlay lane; frozen murmur wording untouched) via `BuildChatRequestBody`; compose-site scrub chain (`NeuterMarkersCopy` + `ScrubControlTokens`) pinned — providerSafe strips schema keys only. Street says do not arm A2. Quota-exhausted ⇒ emote-only (symmetric with pct=0). E0's 48 street fallbacks gate A6; interim behavior is emote-only.

### A7 — Two-tier budgets ⟦R3⟧ the central economics fix

v2.2's numbers were incoherent three ways (units, scope, and the binding cap). The corrected law:

1. **Tier I — Interactive (exempt from the arbiter)**: whisper, addressed /say, the one party responder, A2 continuations. New `LLMCloudInteractivePerPlayerHour = 240` (≈ 1 line/15 s sustained), **per real player** (keyed by player guid; the SentimentRate keyed-map pattern). The addressed interlocutor is always admitted. The governor (16/bot, 48 global, **per 60 s window** — `governorWindowSec = 60` @ `LlmModelRegistry.kt:46`; v2.2 read these as per-hour — a 60× error) remains the burst limiter: true ceiling ≈ 1800-2880 gen/hr at 4-8 s latency, 240/hr floor at all-timeout.
2. **Tier II — Ambient**: murmur, street, rpgchat, bot2bot, dossier, recap, saga under `LLMCloudLineBudgetPerHour = 90` with **proportional category caps** (cloud: AMBIENT = cap/4, REACTION = cap/8, SCENE = cap; device branch exactly today's `min(3,cap)`/`min(2,cap)`/cap of 8 — `AuthoredLineAdmitsLocked` @ `PlayerbotLlmMemory.cpp:1682-1707`). Lane selection by evaluating `ExternalApiTierActive()` inside the locked helper (conf-static; no lane argument threaded through 8 call sites). Without proportional caps the street quota is unreachable (`min(3,30)` = 3/hr = 72/day < 200/day).
3. **Bot-to-bot**: `LLMBotToBotChatChance` emitted **conditionally**: 25 when `llmCloudChatter`, 10 (today) otherwise (`LlmRuntimePolicy.kt:272/:365`) + `LLMBotToBotPerDay = 300` (was 500 — inverted priority vs street; 500 > street 200 made background chat outweigh player-visible life). Add an autonomous-exchange depth cap per pair (≤ 3 consecutive turns, reset on any real-player interaction) — the only self-amplifying lane.
4. **Concurrency off-by-one fix**: `generationCount > maxGenerations` → `>=` @ `PlayerbotLLMInterface.cpp:707` (today admits max+1); T2 staging assertion pins 4.
5. **Quota-restart semantics documented**: `CloudQuotaAdmits` is process-local (verified — static map, UTC-day, `StateMutex`) and resets on realm restart; §0.a states it; the toggle copy says "daily quotas are per-session".
6. Device leak-proof assertion (gradle): every `LlmModelRegistry` tier ctx < 65536; device block never emits `LLMProviderSafe`; external block always emits `= 1`; debug block emits neither.

### A8 — Generation observability ⟦R3⟧ schema rebuilt

Plumb a `uint64 reqId` (atomic counter) through `Generate` → `GenerateHttp` (both anchor-managed; payload extension):

- `BotLLM: dispatch bot=%u src=%d lane=%s req=%llu` — world thread at SayAction.cpp:1188, **unthrottled** (v2.2's global 1/s cap destroys the per-turn assertion at 320-bot login bursts; if a volume guard is kept it is per-bot). Basic level.
- `BotLLM: gen begin req=%llu bot=%u lane=%s` — worker thread after `GovernorAdmit`.
- `BotLLM: gen end req=%llu bot=%u class=ok|busy|cap|timeout|http_%d|error|empty durMs=%lu` — duration from `steady_clock` (log timestamps are second-resolution; v2.2's "overspeed" class never existed; busy/cap/device-lane classes added; the device lane gets symmetric begin/end inside `PlayerbotLlamaRuntime::Generate` or the assertion is scoped cloud-only — pick one, pin it).

No prompt/body content (the debugLines leak is closed by §0.c.1, not by logs). `run_suite.py` invariants: per cloud conversational turn exactly one `dispatch req=N`, ≥1 `begin req=N`, exactly one `end req=N`; p50/p95 from `durMs`; zero `No bots texts` greps target the **outError** variants only (post-B2 the outDetail misses are invisible — §3 B2).

### A9 — Log noise ⟦R3⟧ re-shaped

Preferred: **once-per-name demote in place** at `PlayerbotTextMgr.cpp:95/:135` (truncate the logged name to ~80 chars) — one submodule commit, kills the per-generation spam, keeps the missing-key diagnostic for E1/E2 renames, needs zero anchor churn. The silent variant is rejected as primary (it would hide register-audit regressions and prompt-text leaks). Preserve the outError empty-table branch (:90/:130 — the real pack-staging alarm). Fix the adjacent `proper_list` underflow at :147 in the same commit. The v2.2 parenthetical ("drop logs live in RunDeviceBatchInner") was wrong — nothing logs there; transport errors log in `PlayerbotLLMInterface.cpp`; both chatter workers drop silently (A5's latch is the fix on the cloud lane). Also note: the detail-level miss logs the entire prompt-furniture string — A9 kills a prompt-text log leak B2 alone wouldn't.

---

## 3. WS-B — Scheduler + loop fixes at full bot counts

### B2 — Log plumbing (split ⟦R3⟧)

1. **Now**: world conf `LogFileLevel = 3 → 1` (`ServerRuntimeFiles.kt:145`; realmd already 1 @ :60). Level 2 rejected (it retains the movement/BG detail churn). Advanced toggle `worldDebugLogs` stages 3; copy states the restart requirement. T2 pins both emissions (nothing pins LogFileLevel today).
2. **Deferred until measured**: the buffered appender. v2.2 cited `Log.cpp:729` — outDebug's fflush, dead code the moment (1) lands; the live sites are outString :412 / outBasic :647. fflush is page-cache write, not fsync; no measurement attributes tick-p99 cost to it; and buffering surrenders crash-tail evidence on a platform whose fatal modes (LMK SIGKILL, native crash) run no flush. Revisit only if T4 shows log-attributable stalls; if built: counter+timestamp flush check inside the existing `m_worldLogMtx`, unconditional flush on all error classes and secondary files, no timer thread, keyed on a conf knob (there is one Release lane — "in release" is unimplementable).
3. PlayerbotAIBase.cpp:35/40: the lines are **already outDebug** (v2.2's "demote" was stale); only the inverted "lesser" wording at :35 is fixed, riding a driver anchor payload (`PB_AIBASE_LOG_`) — not worth a submodule bump alone; fold into the next lane-3 batch. AllowActivity logs nothing; Map.cpp:885-886 stays (12 lines/min).
4. "Zero `No bots texts`" greps re-targeted per A8 (outError variants; or a level-3 staged leg).

### B3 — PassiveDelay per profile

`BotProfiles.kt:129` hardcode → `passiveDelayMs` field (bounds `1_000..60_000`, init-block pattern :78), emitted as `AiPlayerbot.PassiveDelay = $passiveDelayMs` from `playerbotConfig()` — **default exactly 10000** so every non-experience preset's emission (and thus adv/usr5 digest inputs) stays byte-identical; experience presets 3000. Covers both T3 profiles (both are experience presets; `experiencePresets` @ BotProfiles.kt:1246-1249). Native fallback is 4000 (:113) — documented, not changed. T2 pins: a legacy preset's `playerbotConfig()` byte-identical pre/post; a stored usr5 record minted pre-B3 still resolves; all seven experience presets emit 3000. Couplings recorded: `MovementAction::MinimalMove` uses `passiveDelay/1000` as teleport spacing (MovementActions.cpp:515 — 3.3× more resume churn; accept + note, or decouple later); PID feedback (`ScaleBotActivity`) may erode active% — T4 captures resolved activity% alongside p99. IN_DIALOGUE bots are structurally immune (passiveDelay arms only on the minimal path @ PlayerbotAI.cpp:2181). The 500/600 experience presets run 3000 unsoaked — one T4 sentence acknowledges it.

### B4 — Profile tuning at unchanged counts (measured-first ⟦R3⟧)

Before values (verified): LOW_POWER_80 2500 ms / 8 iter / 3% active; ALIVE_REALM_320 2000 / 15 / 12%. Proposed: 1250 / 16 / 8% and 1500 / 18 / 15%. Both tuples pass validators; worst-case work-rate ×10.7 (LOW_POWER_80) and ×2.0 (ALIVE_320) — **numbers are interpolations toward the SMART chip, not measurements**. Procedure: add bench twins with the new tuples; run `tools/run_bot_pressure_benchmark.py` on the actual T4 lanes (physical-device comparables: p99 54-143 ms at 320-600 bots for hotter configs); commit values only with the artifact attached; then T4 soaks. T4 adds the **no-shedding pin** (`effectiveTarget == selectedTarget`, reason "selected-profile" — `BotAdmissionController.kt:98-104`), because p99 > 250 silently sheds bots, violating §0.9 at runtime. LOW_POWER_80's ×10.7 contradicts its "light load for weaker devices" identity and ties BUSY_WORLD_240 on two axes — land it only after its own 6 GB soak (split B4a/B4b if needed). Housekeeping rides along: KDoc refresh (:842-847), `BotExperiencePresetTest` flips (:34-37), activity-chip reconciliation (ACTIVE/LIGHT chips @ `BotCustomConfiguration.kt:214-228`), and the id-versioning decision — **retuned presets mint `-v2` ids with `-v1` kept resolvable** (the `mobile-lively-b700-v2` precedent @ :430; `defaultProfile` moves to v2; stored selections keep old behavior until re-picked — this is the §0 "preset = switch" promise made true).

### B5 — Freezer exemptions ⟦R3⟧ predicate + fencing specified

Manifest: `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` on WorldRuntimeService and DatabaseService (permission already declared, Manifest:11; targetSdk is 27 so the attributes are forward-compat documentation — the operative fix is promotion machinery; RealmService/ClientRuntimeService already carry the pattern). Mechanism, extending `ServiceHandle.foregroundStartAction` (`AndroidRuntimeBackend.kt:1141-1224`; the :70-74 cite is the construction site): supervisor-owned promote/demote verbs driven by the existing 1 s `monitorComponents` loop. **Predicate** (the world has no RUNNING state — states are STOPPED/STARTING/READY/SAVING/STOPPING/FAILED @ `ServerRuntimeContract.kt:11-18`): `stateCode == READY && (realPlayers > 0 || (!playerbotsEnabled && onlinePlayers > 0))` — `onlinePlayers` counts bots and `realPlayers` is bot-profile-gated, so the naive readings never fire or never demote; asymmetric hysteresis (promote immediate, demote after 3 empty samples). **Fencing**: promote-then-demote fallback so an in-flight promote intent can't crash a freshly created service (`ForegroundServiceDidNotStartInTimeException`); `stopAccepted` in-gate flag at `WorldRuntimeService.kt:195/:206` (the `AdmissionTransitionGate` serializes every verb); DatabaseService promotes on its own engine-claim and demotes at engine stop/owner-loss (it has no transition gate — supervisor-side demote only). Extract a pure `ForegroundPromotionPolicy` for T2 (no Robolectric in repo; `DurableRuntimeSupervisorTest` FakeBackend pattern). Channel: reuse `RealmService.ensureChannel` (a second channel is noise); notification ids 3/4. Pre-step: `dumpsys activity processes` during a T3 background window to confirm :world/:database are actually freezer-eligible (they are held bound by a persistent FGS supervisor — bound-FGS priority may already exempt them; if so, redirect to mariadbd child-process protection). Wake-lock coverage already exists (supervisor holds one across lifecycle ops). **F1 lands first** (§11).

### B6 — RSS hygiene ⟦R3⟧ facts corrected

`mallopt(M_PURGE, 1)` behind `android_get_device_api_level() >= 28` (M_PURGE is API **28**; M_PURGE_ALL API **34**; mallopt compiles to 26; the v2.2 "corrected" 30/31 was still wrong). Optional `M_PURGE_ALL` on ≥ 34 lanes. Hook: end of the `WorldRunnable::run()` iteration after `sWorld.Update` (world_runnable.cpp:49) — single-tree, no anchor, on the world thread, 60 s cadence. "Right after logout waves" is dropped (no discrete wave event exists). T4's RSS assertion is **demoted to a recorded datum** — it is un-attributable (scudo's default `M_DECAY_TIME=1` already releases free pages; the purge accelerates within the window) and has no stimulus while admission owns the target; if asserted at all: an explicit population cut via a test-only admission override, anon-heap (malloc_info) rather than VmRSS, differential vs a disabled lane. Grid residency stays documented-not-fought.

### B7 — Admission floor monotonicity ⟦R3⟧ rescoped

The user-visible dip is `CROWDED_REALM_400.minFreeMemoryMiB = 1792 < ALIVE_REALM_320's 2048` (BotProfiles.kt:922 vs :882). Fix :922 → 2048 (experience ladder becomes monotonic; matches FULL/MASSIVE and the custom default). `CROWDED_400` (:681, legacy, `userSelectable = false`, member of the **frozen adv4 catalog**) stays at 1792 — touching it invalidates persisted adv4 identities for zero user benefit. T2 pin scoped to `experiencePresets` only (a union-catalog pin is wrong: BENCH_FORCED_1000 legitimately exceeds at 3072).

### B8 — Stage the default prompts file ⟦R3⟧ both v2.2 options were broken

The loader opens a **bare relative path** (CWD-resolved — never the run dir), and the conf default `llm_character_card` (`PlayerbotAIConfig.cpp:900` region) expects `Name::text` lines, not JSON — pointing it at the staged `llm_prompt_pack.json` would produce per-line ERROR spam and DB writes; staging alone under the bare name resolves nothing. Correct hybrid: stage an **empty (0-byte) file named `llm_character_card`** via the existing staging lane, and emit `AiPlayerbot.LLMDefaultPromptsFile = <absolute path>` in `playerbotConfig()` — absolute-path emission is the codebase convention for every staged file (`LLMLoreFile`, `LLMPromptPackFile`). Result: zero DB writes, prompts byte-identical to today's clean fail-open, the `not found or unreadable` outString line (PlayerbotAIConfig.cpp loader — level-independent, survives B2) disappears. T2 pins the staged file + emission; T3's clean-boot assertion greps the exact literal "not found or unreadable" (any level) and accepts `Loaded 0`.

---

## 4. WS-C — Memory that revises + prose quality

### C1 — Fact versioning ⟦R3⟧ reworked (v2.2's mechanism had a fatal coverage flaw)

The `"met <name>"` fact exists for ~one pairing per player (`PlayerHasAnyPairing` gates the welcome); an UPDATE keyed on that prefix never fires for the other 79/319 bots, self-destructs its own trigger after one transition, contradicts the tier prose while tier is still stranger, and breaks the `met`-prefix dedupe + tenure anchor. Replacement, in preference order:

1. **Render-time rewording** (primary): at the facts fetch (`BuildTrainedChatRequest` :789-818 and `GetJournal` :1293-1327), when the pairing's absence ≠ "a first meeting", rewrite/suppress first-meeting-shaped rows at render. No schema write, idempotent, reversible, reaches model-authored first-meeting facts too, keeps `created_at` (tenure) and C2's id-keyed seeding intact.
2. Rewording is **tier-gated** (tier ≥ acquaintance, points ≥ 10) so the ledger never contradicts `Standing:`.
3. Replacement text is **corpus-shaped and prefix-stable**: verb-initial past-tense clause ≤ 8 words (the committed vectors' law — facts are short verb-initial clauses, 2-7 words; "old acquaintance of the road" was a noun phrase and off-corpus). A 3-line rotation avoids verbatim repeats in restart drills.
4. If any persisted form is kept: a single conditional `UPDATE … WHERE fact_text LIKE 'met %'` (idempotent, never DELETE+INSERT), triggered off the absence read, not the prefix it destroys.

T3's "repeat greeting shows upgraded wording" pins the deterministic render path (not the cloud reword — `LLMDossierPerDay` defaults **1/day global**, so the cloud leg is nearly unreachable and would flake).

### C2 — Voiced-fact persistence ⟦R3⟧ migration corrected (this was the bricking risk)

**The seed DDL is never edited.** One new append-only migration — `0413-playerbot-characters-ai_playerbot_llm_memory_v2.sql`, registered as an explicit `selected.append(Input(...))` after the existing tail entries in `tools/stage_database_migrations.py:select_inputs()` (the LLM entries are the documented tail precedent, :136-141):

```sql
ALTER TABLE bot_player_facts ADD COLUMN voiced_at BIGINT UNSIGNED NULL DEFAULT NULL;
ALTER TABLE bot_player_relationship ADD COLUMN last_voiced_tier TINYINT UNSIGNED NULL DEFAULT NULL;
ALTER TABLE bot_player_relationship ADD COLUMN last_greeted_at TIMESTAMP NULL DEFAULT NULL;
ALTER TABLE bot_player_relationship ADD COLUMN last_greet_line VARCHAR(255) NULL DEFAULT NULL;
CREATE TABLE IF NOT EXISTS bot_player_history ( ... );   -- C8
```

- **No backfill** (stated): NULL voiced_at = "never voiced" — existing rows keep exactly today's per-process behavior; backfilling would permanently silence historical debt/goal initiations.
- `voiced_at` has exactly one stamp site: the TickInitiative delivery block (`PlayerbotLlmMemory.cpp:2404-2413`) where the fact id is in hand synchronously; never at enqueue on delayed paths. `InitiatedFactIds` seeds lazily per pair by extending the existing newest-6 PQuery to also select `voiced_at` — no boot-time table scan.
- **The INSERT-dedupe half is dropped** (v2.2's "dedupes on (bot, player, fact prefix)" described no mechanism — no prefix column exists, async INSERTs make probe-dedupe racy, a UNIQUE-over-text needs backfill + dialect splits, and the goal doesn't need it). The H2/T3 row-count check is a behavioral `GROUP BY … HAVING COUNT(*)>1` assertion, or a generated-column UNIQUE ships in the *same* migration if wanted — pick one, pin it.
- **Full re-pin checklist** (v2.2 undercounted): regenerate manifest+assets; `schemas/sqlite-seed-baseline.json` (`--write-baseline`); `schemas/seed-augment/PROVENANCE.json` re-validation; seed transcript assets; `tests/test_sqlite_dialect.py:408-414` count/tail pins (412→413); `DatabaseStartPreparationTest` count defaults. Add the **parity test** the repo lacks: fresh-manifest replay vs truncated+0413 replay ⇒ identical `PRAGMA table_info`.
- **Upgrade disclosure**: MariaDB lane = snapshot + ALTER (seconds; 1.5 GiB free gate). SQLite lane = full re-provision (minutes; 119 MiB corpus replay) — user state IS carried for `bot_*` tables (the carry excludes only `ai_playerbot_*` tables @ `DatabaseEngine.kt:1486` — verified; v2.2's fear of a bot-memory wipe was wrong), but the cost and the `MIGRATIONS_STALE` downgrade behavior go in the runbook: **after 0413, APK downgrade fails closed (`DB-REVISION`); the only paths are stay-on-new or reinstall (data loss). Documented, accepted, pinned with one T2 test + a release-note string.**
- `tier_since` already exists in the seed (`ai_playerbot_llm_memory.sql:29`) and is dead — C5 is a DML-only write, no DDL.

### C3 — Town-talk prose ⟦R3⟧ scoped honestly (it is not "keyless prose")

The deterministic mint half is lane-blind — its rows are read by `GossipAbout` into trained `{G}` cargo frames AND re-persisted as `heard the town talk:` facts that render into device prompts. So: per-category grammar templates (the four LogFact categories; the pick query widens beyond `shared-event`), templates supply the grammatical subject (noun-phrase facts read broken in any frame), the row is minted **de-framed** (a bare town-talk clause; the greeting rider and murmur `{E}` templates own the single frame — fixes both double-frame sites at once), day-one cloud reword keeps the 7-day age gate dropped (gate @ :2985, not the restart-resetting weekly stamp) with a **word-count clamp ≤ 24 + hygiene pass on `lines[0]`** before `ShareGossip` (today's acceptance checks only `lines.size() == 1`), and every new shape enters a **fact-shape lint** (§10 T1: lowercase/verb-initial/2-9 words/no markers — the committed vectors are the reference corpus). T1: day-one + tier ⇒ deterministic template, network-free.

### C4 — Deterministic party digests ⟦R3⟧ mechanism specified

Real gap (party chat leaves zero durable trace today — single-slot `partyLineAt`, 120 s erase-on-take roundtable). Spec: a new bounded per-master rolling deque (10-12 entries, ≤ 160 B) written at the A3-restructured block, `partyLineAt` untouched; **one writer bot** per window via the saga storyteller selection (tier ≥ 3 grouped bot); `MintOnceFact` keyed (master, windowIndex); digest register-native (one salient beat rendered 5-24 words — "the party spent the march arguing over <topic>", never a 10-line concatenation); category `shared-event` with LogFact whitelist/mask/dossier-query coordination pinned so digest rows don't crowd the party-topic newest-6 window; quota `LLMPartyDigestPerDay = 6`; cloud lane optionally rewords via the recap-prose pattern; `voiced_at` unset at mint (stamped only if a surface voices it). Mirrors the recap's deterministic-digest + cloud-prose two-tier.

### C5 — Tier ceremony persistence ⟦R3⟧ amended

The ceremony already exists (`ConsumeTierTransition`/`CeremonyUpCargo`/`TierShiftSysLine` @ `PlayerbotLlmBridge.cpp:169-216, 826-880`) — v2.2 specced a new rider against it (triple-voicing) and F4 duplicated it (deleted). Deltas: persist `last_voiced_tier` (column in 0413); write `tier_since` in the ODKU (DML; MySQL assigns left-to-right so the `tier_since` assignment precedes the `tier` assignment — pinned via the `tools/test_sqlite_odku.c` fixture); the **stranger→acquaintance join deed is specified or cut** (a bare join needs +10 — 2-5× any other deed; default: join awards nothing, first group *turn* does); persisted ceremonies are gated on `tier_since` recency (≤ N hours — honors the bridge's "never fire on stale state" doctrine, which the persistence deliberately amends); the sys line stays per-crossing, the prose rider ≤ 1/session/pairing (`StandingVoicedPairs` pattern) and **per-player coalesced** (a 5-bot simultaneous crossing yields ≤ 1 rider); MySQL/SQLite dialect split pinned.

### C6 — Relationship economics ⟦R3⟧ honest numbers + anti-farm

Ladder (verified): acquaintance ≥ 10, ally ≥ 30, trusted ≥ 60 stored; Bonded = points ≥ 120 on read. With the deed/turn values, "Bonded ≈ 25-30 actions" is only true for a quest-only diet; the honest target is **~45-55 mixed actions** (or drop Bonded to ~80 — pick one, state it). Cap is **per-pairing per-day** (the SentimentRate keyed-map pattern, NOT the global CloudQuotaAdmits). Cap consumption: turns + shared-kill consume the cap; trade/quest/first-visit don't (already scarce). Anti-farm set, stated: quest gated `!pQuest->IsRepeatable()` behind a NEW `CORE_REWARDQUEST` anchor pair at `Player::RewardQuest` (`RemoveTimedQuest(quest_id)` @ Player.cpp:12554 is the unique insertion point; Player.cpp is already anchor-managed + already includes PlayerbotLlmMemory.h); trade reuses the 60 s SentimentRate admission with the deed delta explicitly exempt from the ±2 clamp (or a separate `AddDeedPoints` sharing the gate); shared-kill daily-capped per pairing; first-visit rides the explore bit (naturally once). Keys split per §0.a; 0 = deed disabled with facts/reactions at the same hooks preserved (pinned). Delete or wire the dead `RecordBotLine` (:571).

### C7 — Greet-repeat guard ⟦R3⟧ shrunk (v2.2's "chatter state blob" does not exist)

Two-layer fix, no new serialization subsystem: (1) mix a **boot nonce** into `InitBanterState`'s seed (`llm_banter_core.h:737-745`) — two lines, ends the deterministic verbatim replay across restarts (requires re-pinning the FNV golden `test_llm_banter.py:61` in the same commit); (2) greet-only persistence via `last_greeted_at` + `last_greet_line` on the relationship row (0413 columns) — `GreetingLine`'s selection excludes the last voiced line per pairing. Full ring serialization (v2.2's C7) is cut: wrong subsystem, no storage lane, pool-version hazards, and ~250 B/bot for a 40-line bank. Cross-bot echo (320 bots sharing 10-line tier pools) is E1's job, partially — recorded as accepted residue.

### C8 — ⟦R3⟧ NEW: conversation memory (the plan's biggest silent deferral, now shipped)

`RollingHistory` is a process-local deque (`PlayerbotLlmMemory.cpp:262-296`, caps 32 external / 20 device, ≤ 240 B lines) — every restart is context-free beyond facts; T3's "standing references history" failed by design; the bot "knows about you but cannot remember with you". Fix, riding 0413: `bot_player_history(bot, player_or_channel, seq, speaker, line, ts)`, PK (bot, player_or_channel, seq); insert inside `AppendHistoryTurn` (the single choke point :283-296); lazy-load into the deque on first prompt build per pairing after restart; keep the existing caps (≤ 32 rows/pairing — trivial footprint, zero prompt-budget delta vs today's mid-conversation state). Gated `LLMHistoryPersist` (§0.a). Without this, WS-A's SillyTavern premise undersells at exactly the moment F3's copy and the tier ceremony direct player attention at it.

---

## 5. WS-D — World aliveness without reducing bots

### D1 — Spawn-stack fix ⟦R3⟧ three traps closed

The empty-candidate case today doesn't "stay stacked" — it recurses with the cap bypassed (RandomPlayerbotMgr.cpp:2707-2715). And `RandomTeleport` hard-returns for `GetLevel() < 5` (:2534) — exactly the stacked population (sync band 1-4). Spec: a login-only `force` flag bypasses the level guard for the forced path only; the near-player filter (new code — no radius parameter exists; candidates are creature-spawn points, so placement avoids live mobs via a ring offset around the chosen player, the FleeManager::CalculateDestination pattern) resolves keep-best **before** the :2714 recursion and is not gated on `activeOnly`; no real player online ⇒ plain level-appropriate teleport (status quo), never stay-stacked; the filter is restricted to the login site so D2's periodic path doesn't inherit it; stagger via `ScheduleTeleport` so grid loads don't stack with login waves; T4 asserts `teleportsLast60s`. Hooks: `OnBotLoginInternal` (anchor-managed) or `PlayerbotMgr.cpp:507` (lane 3 — classified ⟦R3⟧) or the core callback (CharacterHandler.cpp — classified). Companion, zero-cost: enable the existing "range"/"map" login criteria on experience presets.

### D2 — Newbie-zone population ⟦R3⟧ corrected mechanics + crash-class fix

"Within 1500yd" is telemetry, not a mechanism — the teleport is **zone-granular** (active zones @ Map.cpp:759-797). Spec: `teleportMinIntervalSeconds` 3600 → 600 (min bound only — max stays 14400; both-bounds-at-600 is a 10-24× teleport-event multiplication with O(candidates×bots) world-thread scans). **Crash-class**: `BotAdvancedSettings` requires `teleportMinMinutes in 30..2880 && % 30 == 0` (`BotProfiles.kt:208-209`) and `fromProfile` runs outside `runCatching` (`Settings.kt:643`) — 600 s throws at settings load; the validation widen (5-min granularity) + adv4 encoding note ships **in the same change**, with a gradle round-trip pin for every catalog profile. The level band problem is stated and solved via D1's guard exemption (or `RandomBotMinLevel = 5`); the max-player-level drain (one level-60 alt empties starter zones) is documented; the "level matching off for Classic" KDoc/pins/chip flip ride the `-v2` preset (B4 discipline). Horde dormancy (~5/9 of candidates never intersect alliance active zones) is recorded as accepted.

### D3 — Street life ⟦R3⟧ re-pointed (v2.2's flag was a no-op)

`RandomBotSayWithoutMaster` gates two masterless paths that say nothing (null tell recipient @ `PlayerbotAI.cpp:3477`; `QueryItemUsageAction` ownerless) and shares zero code with the LLM quiet-gate — flipping it delivers nothing, and repairing it would spray un-arbitrated mechanic-speak. Replaced: stage the **Chatter ambient lane** on experience presets (`LLMEnabled` block + a non-OFF `LLMChatterPowerFile` rung) — the already-quiet-gated, arbiter-bounded, ring-deduped murmur path (cadence ≤ 2-3 lines/min near a player). If a legacy say-capacity is ever wanted, it routes through Chatter's queue, never a bare `bot->Say`. T1 negative pin: `RandomBotSayWithoutMaster` stays 0 on all profiles. The `BotPolicyTest.kt:145` pin is updated accordingly.

### D4 — Village ring ⟦R3⟧ moved inside earshot + honest classification

The nearPlayerTeleport path is a density **cap** filter — no point anchor, no band, no count; the level<5 guard forbids moving level 1-4 bots; and **40-100 yd is outside every audio range** (`ListenRange.Say = 25.0` @ World.cpp:464; murmur 28 yd) — v2.2's ring would be visible-but-mute scenery. Spec: ring at **10-25 yd** (inside say range after a few steps — A6 street reactions and RpgAIChatTrigger arm on ring members); "settler" designation (same-race level 1-4 bots exempt from D1 relocation and from the randomize event — stable villagers) with the offset placed via the existing `RandomTeleport(bot, locs)` overload's GetHeight snapping; profile fields `villageRingCount (0=off, 3-5)` + `villageRingMinYd/MaxYd`; D1 exempts up to `villageRingCount` per spawn point (the two items pull opposite directions on the same bots — resolved explicitly). This is **new native behavior in an anchor-managed file behind new keys** — §0.a's honest label, not "preset = the switch". Persistence is emergent (the 150 yd proximity freeze @ RandomPlayerbotMgr.cpp:2273 holds members; wander leaks slowly; no re-forming machinery).

---

## 6. WS-E — Authored corpus

### E0 — First tranche (96 lines) ⟦R3⟧ dependencies corrected

Street short-reaction fallbacks (48) + persona security refusals (48). **No double-counting**: the existing 48-line `refuseLine[4][12]` pool (`PlayerbotLlmPersona.cpp:291`, CATEGORY_REFUSE) refuses *begs* ("give me your gold" class) — E0's 48 are a NEW pool refusing *invite/trade/group gates*, on never-overlapping triggers. **Dependency fix**: street gates A6; refusals gate E3; **A4 does not depend on E0** (its fallbacks reuse today's pools) — execution-order corrected. Pools land as hand-authored C++ tables (the `kGrudgeRefuse`/`MurmurFloorTable` patterns) under `LineIsValid` + word laws + host-sim + T1 pool pins — never through the emitter (§0.4). State-key layout: the `guid<<8` scheme is full (bits 0-7 used) — new cells use the `guid<<24 | (pool+1)` layout. Author street templates 5-24 words with `{P}` templating so either delivery lane validates. Empty-pool degradation verified safe (`SelectLine` returns `{0}`; `TryFallback` false ⇒ generated path).

### E1 — Pool targets ⟦R3⟧ reconciled

All "Now" counts verified exact (greet 4×10, busy 12, silence 12, idle 18, floor 10, kill 14, condolence/shaken 96). Corrections: the table's targets sum to **~1,100**, not §6's "~2,000" — headline fixed; "archetype seasoning" is **additive** (tier draw + one spoken archetype phrase composed in `GreetingLine` — composite space 40×12=480; a replacement draw would strand ~half the population in SHY, since `ArchetypeFor` is class-only — fix its stale doc or add race to the seed before layering); level-up "Now" is honestly **0** (no cheer pool exists; the beat is generation + emote marker — the +24 authored cheers ride a small delivery decision at the event drain); a floor-template authoring rule (renders ≥ 5 words with a 2-word `{E}`); the `~457` denominator is defined (native authored speech lines; `ai_playerbot_texts.sql`'s 2,925 rows audited separately as E2b). FNV golden re-pin (`test_llm_banter.py:61`) is **in the T1 list** for the kill-quips change — v2.2 omitted it (guaranteed red suite).

### E2 — Register audit ⟦R3⟧ scoped to what's reachable

The drift is real but lives in the legacy surfaces: the C++ authored pools are clean (zero violations sampled); texts.sql's hello/goodbye/hello_follow pools (~90 reachable rows incl. the "Hi, lead the way!" line @ :3831 — the dangling-initiative family, all six rows assert an unproposed action, consumed at master-acquisition @ PlayerbotAI.cpp:2185) plus ~6 inline literals (GuildManagementActions, BroadcastHelper, EmoteAction — the worst offenders, none in v2.2's regime lists). Scope: those rows + literals; the 533 dead rows (taunt/loot/aoe pools with no reader) explicitly excluded; row-content edits only, no key renames (A9's once-per-name keeps the diagnostic); texts.sql rides the lane-4 regime with the escape-count/digest re-pins named. Register pin = banned-token scan over quoted pool literals + word-count ranges + a key-coverage pin (every `BOT_TEXT("k")` literal resolves to ≥ 1 row) — the `test_llm_banter.py` content-contract pattern, not FNV goldens. Do NOT touch the recognition/dedupe Hi tokens (`PlayerbotLlmPersona.cpp:853`, `PlayerbotLlmTruthCore.h:144`).

### E3 — Security-refusal wiring ⟦R3⟧ corrected mechanics

The live site is :244 (PLAYERBOT_SECURITY_INVITE); :203 is dead code. The existing refuse pool is semantically wrong for security denials. Spec: new `POOL_SECURITY_REFUSE` (4 archetypes × 12) in `llm_banter_core.h` + exported `PlayerbotLlmPersona::SecurityRefusalLine(Player*)` (the `GrudgeRefusalLine` :659-675 pattern — no Classify, no message); wired at :244 only; **dedupe re-keyed to (guid, DenyReason)** — today's exact-text dedupe @ :253-256 is defeated by ring rotation (12 distinct whispers before repeatDelay trips); legacy string kept as draw-failure fallback (BusyReply precedent); scope = the invite-family reasons (invite/leader/full-group) — LOW_LEVEL/GEARSCORE/queue denials keep their actionable numbers on day one; kill-switch `llmBanterEnabled=0` ⇒ byte-identical UI-speak (pinned). Cost claim corrected: "zero LLM cost" true; "deterministic" withdrawn (ring-random output) — the T1 pin asserts pool membership, register hygiene (no "|cff"/"gearscore"/UI tokens), ring non-repetition, archetype mapping, and the reason-keyed dedupe. Two-regime change: PlayerbotSecurity.cpp (lane 3) + overlay pool.

---

## 7. WS-F — UX/product fixes

### F1 — Supervisor self-heal ⟦R3⟧ narrowed to the real gap

Half of v2.2's claim already exists: STOPPED observations skip at `DurableRuntimeSupervisor.kt:375`. The real orphan is **owner == null while RUNNING** (binder-death cleared the claim; teardown race in flight). Spec: self-heal only the null-owner case, via **adopt-then-forceStop** (claim with current session + fresh token — legal when owner == null — then forceStop under the adopted owner; `forceStopOwned`'s `requireOwner` makes v2.2's "forceStop with the observed owner" unimplementable); bounded re-observe grace first (the teardown thread may be mid-save); the non-null-mismatch refusal stays pinned (`dirtyRecoveryNeverKillsAnUnverifiedOwner` — the only dangerous branch); DATABASE orphans route through the existing recovery lane (killing :database without `engine.close()` orphans mariadbd); new backend verb + FakeBackend seam; copy: map `UNVERIFIED_ORPHAN`/`TimeoutCancellationException` to human text at the `failStage`/decode boundary, raw detail to logs. **F1 lands before B5** (§11).

### F2 — Account form (four concrete edits, ⟦R3⟧ expanded from v2.2's one line)

(1) Fields always enabled; realm-readiness moves from `creationEnabled` into Create-button gating (HomeScreen.kt:411-412/:806) — the `accountOperationPending` disable is load-bearing and stays; (2) per-keystroke validation **reusing `UserAccountStore.isValidCredential`** (:136 — the rule already exists in four layers; don't write a fifth), house pattern = the BotsScreen name dialog's isError/supportingText; (3) portrait card renders while idle with a "start the realm" status (closes the UI/backend phase mismatch — the backend accepts WORLD_READY/CLIENT_FAILED); (4) replace the `javaClass.simpleName` leak (:329) with `accountProvisionFailureMessage("ACCOUNT_CONTROL_FAILED")` copy. Validation logic as pure functions in `AccountStatusPresentation.kt` for T2. No backend work (duplicate/restart/mariadb failure paths already end-to-end friendly). Keyboard type + "stored uppercase" hint as polish.

### F3 — Lane-neutral copy ⟦R3⟧ surface + accuracy fixes

The three strings are code-supported (name-addressing verified as the both-lane hard trigger; tier pools back "strangers keep it short"; the admission ramp backs "grows from M"). Corrections: the app's one genuinely lane-wrong string — FirstRunTutorial.kt:79-86 "Everything runs on this device, offline" — joins the sweep (SettingsScreen :1018's garbled "Replies land" too); **the hint gets a surface** (LLM-screen runtime card line and/or a one-per-account sysmessage on the welcome path — T3's "hint present" is unassertable without one); chip renders "(grows from M)" only when `initialTarget < selectedTarget`; import copy keeps the two-lane split ("an hour or more for the ~5 GB archive"); the toggle copy carries the §0.c.4 disclosure + restart note. C8 caveat copy ("bots recall facts about you, not full past chats") only if C8 slips.

### F4 / F5 — deleted / demoted ⟦R3⟧

F4 was C5's rider verbatim — deleted. F5's unique content (never wall-clock; one canned line max; in-flight suppression) is two T1 pins under A4/A5. §0.a's row relabels to A5.

---

## 8. WS-G — realmd asio + net hygiene

### G1 — realmd keep-alive + liveness ⟦R3⟧ re-grounded

The "no-work exit" premise is unreachable (the acceptor re-arms unconditionally, `AsyncListener.hpp:38-44`); the plausible failure is a missed reactor wakeup leaving a dead-but-READY listener. The 250 ms self-rearming `steady_timer` on the realmd io_context (`realmd_runtime.cpp:146-168`) still fixes both modes and is trivially cheap — **kept**, plus: an **io-thread liveness count** in the heartbeat (a returning thread count while `!m_stop` ⇒ `fail()` — converts every silent-listener mode into a visible FAILED); kill-switch `RealmdTimerMs` (§0.a; workaround with a removal condition); and the **world-mirror decision made explicit**: the world listener is the same asio construction (`Master.h:83`, `Master.cpp:627-635`, running `m_context.run()` with **no exception guard** — parity demands the try/catch at minimum). Either mirror the pump to Master's context (assign Master.cpp a §0.b lane) or produce idle-accept evidence for port 8085 in T3 — the v2.2 "needs no mirror" claim is withdrawn as unproven. The coupling sentence is rewritten: no app-level fd leak is demonstrable (asio 1.32's accept path is RAII-safe); the *in-same-change* items are the concrete cmangos hygiene fixes from G2, plus an **fd-count assertion** in realmd_auth (the only way the coupling becomes verifiable). Realmd_auth also gains the pre-auth idle-close case (connect, send nothing, expect close ~30 s — the actual AuthSocket death window is one-shot pre-auth @ AuthSocket.cpp:199-214, cancelled at first packet :278; post-auth idle is liveness-only, labeled as such).

### G2 — Root-cause follow-up ⟦R3⟧ diagnosis-first, hygiene now

Sequence: (1) fd-count probe across N logon cycles + idle on the current build (A0-style — flat count ⇒ close the investigation); (2) the **hygiene fixes ship with G1** regardless (they're real hazards): write-completion handlers close on error (`AuthSocket.cpp:298,317,620,636,647,652,776,822,829,881,1144` — a stalled peer pins fd+object unbounded once the 30 s timer is cancelled at :278); `shared_from_this()` in the timeout lambda (:202 captures raw `this`); non-throwing `close(ec)` overloads (`AsyncSocket.hpp:52/:86` — a throw inside a handler fails the whole server); (3) `BOOST_ASIO_DISABLE_EPOLL` stays a diagnostic lane only (one `target_compile_definitions` in `native/realm-runtime/CMakeLists.txt`; select-reactor FD_SETSIZE 1024 caveat); (4) the Boost 1.86 → 1.87+ upgrade is measurement-conditional, with its four-lockfile re-pin list enumerated. The AsyncSocket/AsyncListener/AuthSocket files join §0.b lane 3.

### G3 — TLS + parse ⟦R3⟧ fully specified (was a one-liner that would have bricked the lane)

One change, six parts: (1) staged `cacert.pem` via the app staging lane + `SSL_CTX_load_verify_locations(file, staged)` with `/system/etc/security/cacerts` hashed-dir fallback (viable on API 26+; **no `/etc/ssl/certs` exists for native code on Android** — bare `SSL_VERIFY_PEER` fails every handshake); (2) `SSL_set1_host` before `SSL_connect` (chain-only verification still accepts any valid cert — SNI already provides the name at :938); (3) `SSL_CTX_set_verify(SSL_VERIFY_PEER)` + `SSL_CTX_set_min_proto_version(TLS1_2)` (today only SSLv2/3 disabled @ :919); (4) kill-switch `LLMTLSVerify` (default 1; 0 restores behavior for self-signed LAN endpoints — the LLM screen advertises them); (5) the §0.c.3 port-parse catch + app-side bound; (6) a **new anchor pair** for the TLS region (no existing payload covers :889-968). Rollback order: redaction + port fix immediately; verify-on only after A4's fallback plumbing exists (a verify failure must yield an authored line, not dead air). T3 drill: self-signed/wrong-host endpoint ⇒ fallback + failure log, never a hang. Sequencing: **G3 precedes the first live-key byte, A0 included.** Adjacent, recorded: `http://` endpoints send the Bearer header in cleartext — the normalizer warns; `AF_INET` pinning @ :741 breaks IPv6-only carriers — documented as IPv4-required with a resolution-family log line.

---

## 9. WS-H — Harness v2 ⟦R3⟧ relay-first (the schedule-critical restructure)

H was the largest workstream and secretly the critical path (the per-change smoke gate and T3 step 0 need it, yet v2.2 sequenced it last). Restructure:

### H1 — Protocol fixes (corrected against the server, kept for the realmd leg only)

All eight v2.2 items verify against the server with two corrections and four additions. Corrections: "CHAR_CREATE success == 0x2E" is the **payload byte** inside SMSG_CHAR_CREATE (wire opcode **0x3A** @ Opcodes.h:96; as an opcode assertion it hangs); the multi-char crash is "2+ chars desync, data-dependent crash point" (the reference parser reads petDisplayId's low byte as a count — 11-byte desync from character 2; not a 9-char/8 KB fragmentation effect; the pet block is an unconditional 3×u32 @ Player.cpp:1785-1787). Additions (the world leg v2.2 never listed): mandatory AuthCrypt header cipher (recv 6-byte/decrypted, send 4-byte/encrypted, key = 40-byte K LE — `WorldSocket.cpp:490`, `AuthCrypt.cpp:25-52`); CMSG_AUTH_SESSION build (digest = SHA1(account‖0u32‖clientSeed‖serverSeed‖K), build must be 5875, account row needs `os='Win'`, `platform='x86'`, `token` NULL); the addon-info zlib block (required or kick); SMSG_COMPRESSED_UPDATE_OBJECT framing. Verified server-side: 26-byte proof for build 5875 (sAuthLogonProof_S_BUILD_6005 @ AuthSocket.cpp:145-153), LE realm-list size (:876-879), SRP6 le(A,32)/le(B,32) (BigNumber reverse semantics), ping threshold 27 s (server) ⇒ ≥30 s throttle correct, realmlist row projected to 127.0.0.1 (ClientRealmEndpointProjection + DatabaseEngine.projectRealmEndpoint).

### H2 — tools/rp_harness/ ⟦R3⟧ relay-first

**The relay carries 90% of the assertions; the protocol client is the realmd-auth stretch goal.** Two new WorldConsoleRelay ops (the `character_persistence` fixed-purpose pattern @ world_runtime.cpp:248-335 — contract-clean, no SQL crosses Binder): `world-chat {char, channel, target, text}` (injects via the real ChatHandler dispatch path — the identical gating under test) and `llm-memory-state {player}` (per-bot relationship rows + per-(bot,prefix) fact counts — replaces v2.2's infeasible "SQL helper"; mariadb is unix-socket-only and IDatabaseControl.aidl forbids SQL-passing ops). Force-points becomes a validated console command (`llm relationship <bot> <player> <+points>`) behind an IWorldControl op — never live SQL (the async stomp write races a live bump). Structure: `protocol.py` (stdlib only; ms-resolution `mono_ms` + wall-clock `ts` per event — monotonic alone can't correlate across reconnects), `session.py` (owns `adb forward tcp:3724/tcp:8085` + health check — no in-repo precedent exists; reconnect/backoff events are first-class transcript events and **fail smoke**, tolerate-in-battery), `assertions.py` (bot_reply / no_echo_leak with normalization + ≥ 3-word guard / tier_up via sys line / fact_persisted via the relay op / reset helper), `auto_reply.py`, `suites/smoke.py` (per-change, single profile, relay-driven), `suites/battery.py` (45 cmds, dual profile), `suites/realmd_auth.py` (the only protocol-client dependency; 3× + post-idle + **pre-auth idle-close** + fd-count assertion), `run_suite.py` (JUnit-ish JSON; the A8 invariants). RPTEST: provisioned per-run before world boot via the relay account op (gmlevel 0); the sessionkey-injection trick (write K to the account row; the world socket reads K from DB and never checks the password — WorldSocket.cpp:336/:400) covers direct-world sessions; re-inject after any realmd logon (realmd overwrites sessionkey @ AuthSocket.cpp:1154); skip provisioning under LAN_HOST. No bash-heredoc patching (the mariadb-plan gotcha #12 rule). sqlite (not mariadb) is the o09 lane's engine — DB-side fixes ride the relay regardless.

---

## 10. WS-T — TESTING PHASE

**Phase structure ⟦R3⟧ corrected**: Phase 0 rails exist before the first native change. T0/T2 run continuously per change; T1 pins land with each change; smoke.py is the per-change emulator gate for behavior-bearing native diffs (prompt/pool-only changes stay on host gates); T3/T4/T5 are terminal. **Test isolation**: every T3/T4 run begins with a state-reset step (fresh character via harness CHAR_CREATE + a `reset_realm_state` relay op clearing facts/tiers/quotas — the "userdata survives" lane note applies to the *lane*, not to assertion state; v2.2's inherited-state design made its own welcome/dedupe/cadence asserts unrunnable).

### T0 — Static gates

1. **Compile gate — to be authored** (`tools/compile_gate.py`; v2.2 assumed it existed): parse `build.ninja` edges (the driver already reads it @ :4243), run the rules.ninja clang++ line with `-fsyntax-only` on changed TUs, PCH-stripped, wrapped in an anchor-apply/restore phase (new driver flag — `--configure-only` *restores* anchors). ~5-15 min/change.
2. Emitter `--check` for the two spliced blocks, **plus vendored fixtures** (`tests/fixtures/banklib_constants.json` + `--from-fixture`) so the gate is hermetic off the authoring box; a register/word-law lint over pool literals covers what the emitter structurally cannot see.
3. Lockfile pin after **all three lane rebuilds** (v2.2 said "lane rebuild" singular; three lockfiles stale together). A `--write-lockfiles` warm-dir regen mode is added to the driver.
4. `capture_avd.py`: re-`--checkin` the lane record + `--compare` a live app report (four fields; the fingerprint is recorded but deliberately not compared — v2.2 misdescribed the tool); soak driver omits the baseline script's `adb uninstall` (state reset is the relay op's job, not data destruction).
5. **New cheap gates**: key-parity (every `AiPlayerbot.*` literal in driver payloads appears in the Kotlin emission surface and vice versa — generalizes `test_llm_recall.py:415-424`); the fact-shape lint (C3); a banned-token CI grep (no content-policy boilerplate anywhere in prompt-adjacent text).

### T1 — Host unit tests

- **PlayerbotLlmGates.h** (22nd overlay): `HardTriggerAllowed(uint32 src, bool addressed, bool realPlayer, bool cloudChat)`, `ReplyGateAllowed(src, llmEnabled, hasStrategy, cloudChat, blockedMask)`, `CloudLaneOpen()`, `ContainsNameIgnoreCase(msg, name)` (replaces the boost::icontains call — the lowercase-name law becomes pinnable), `ClassifyGeneration(busy, rawError, rawEmpty, linesEmpty)`, `SelectResponder(candidates, addressedGuid, seed)`, `EvictDialogueVictim(occupants, interlocutor)`, `StreetAdmissionOrder(...)`. Mirror `GateSrc` enum bridged by `static_assert`s in SayAction.cpp. **Consume-not-copy rule stated**: the call sites are replaced by helper calls in the same anchor payload the pins read. Scope fence in the header: pure decision predicates; no I/O, clock, config reads, or state. Harness: `tools/test_llm_gates.cpp` + `tests/test_llm_gates.py`.
- **New files declared as new**: `tests/test_llm_memory.py`, `tests/test_llm_persona.py` (v2.2 cited them as existing; they don't — `ls` verified), `test_plan_v5` references renamed to the real `test_llm_recall.py` `test_plan_v5_*` family.
- The pin matrix (negative pins corrected per §2): toggle-off ⇒ *today's external* behavior (not "device behavior"); key-on + tier-off ⇒ device byte-identical (the mirror case); `LLMPartyReplyEnabled=0` ⇒ addressed-only; BUSY split (duty-cycle denial ⇒ persona line; concurrency cap ⇒ fallback, never busy, never silence); `LLMCloudLineBudgetPerHour = 0` blocks only non-exempt lines (`!globalCap ⇒ return exempt` semantics pinned); interactive-exempt-from-arbiter; street pct/quota + the admission order; floor latch classes; zone cap 16/map with interlocutor-wins + TTL prune; exactly-one-responder incl. 0/2 failure cases; A4 closure exclusivity across all sites + the no-off-thread-deref scan; award caps/deeds incl. farm pins (N trades in 60 s ⇒ 1 deed; repeatable quest ⇒ 0; cap exhaustion); C1 render rewording tier gate; migration DDL + manifest tail + parity; C8 round-trip; E0/E1 pool pins + FNV re-pin + register lint; E3 wiring + reason-keyed dedupe; G3 anchor pins (verify present, port catch widened); A8 format pins (no content); B2 LogFileLevel emissions.
- The **silence pin** stays; the A5 floor's device-leg behavioral pin (the current suite has none — string pins can't see lanes) lands with A5, not later.

### T2 — App-side tests

All v2.2 items plus the omissions: B2 `LogFileLevel = 1/3` emissions (nothing pins it today); B4/B2/D2/D3 assertion flips in `BotExperiencePresetTest` (:34-39, :44) named; B3 PassiveDelay emission + BotPresetStore roundtrip + **byte-identity for legacy presets**; the external-block governor pins (16/48/4/25 conditional — currently unpinned); `LlmConfMergeOrderTest` extended over `playerbotConfig() + confBlockExternal` (every new key proven well-formed); FGS pure `ForegroundPromotionPolicy` (promote/demote/save&exit-race in `FakeBackend.actions`); F1 orphan self-heal (null-owner positive + mismatch-refusal negative); B7 monotonicity sweep scoped to experiencePresets; B8 staged-file + key emission; **detekt baseline regeneration in the same commit as A0.a**; D2's `fromProfile` round-trip for every catalog profile (the crash-class pin). CI: `:app:detekt` added to the android-unit job (detekt is configured, not CI-wired; "Detekt green" is otherwise manual).

### T3 — Emulator functional battery (rewritten pass criteria)

Profiles LOW_POWER_80 @ 6 GB and ALIVE_REALM_320 @ 8 GB AVD; step −1 = state reset; step 0 = realmd_auth green. Per-step fixes (v2.2 ratings: 3 red/unimplementable, 6 flaky):

1. **Welcome**: fresh character per run; assert the authored welcome pattern within `PassiveDelay×2 + 2 s`; "hint" asserts its assigned F3 surface (or the welcome's remember-clause), never app UI (unobservable to a protocol client).
2. **Journal**: trigger is exact-match lowercase `journal` (no trim — verified); first line ≤ 30 s, ≥ 3 lines ≤ 60 s; sequenced after the welcome consumed first contact; bounds assume B3's 3000 ms (stated).
3. **Novel sentence**: SLA = client-observed whisper-send → reply-arrival (ms transcript); "from dispatch" withdrawn (server clock is second-resolution; T3/T4 now share one definition); A8's `durMs` is the coarse cross-check; the novel sentence dodges all authored intercepts and runs as the *second* whisper (the first is always the welcome).
4. Invalid-name: unchanged (green).
5. **Party**: bot-selection recipe specified (nearest ungrouped bot, invite verified accepted — random bots self-group and refuse); invite two bots; exactly ONE reply per unaddressed line ≤ SLA+10 s; the one-responder assert is explicit.
6. **Street**: scripted protocol (6 unaddressed says, ≥ 13 s spacing, ≥ 5 bots in 25 yd); pass = ≥ 1 reaction ≤ 60 s **and** ≤ 1 per 12 s world window (the anti-spam law is the automatable part); "within quota" demoted to T1.
7. **Restart**: asserts what actually persists — session-standing sys line re-arms per process (`MaybeSessionStandingLine`), `standing` returns the stored tier, journal shows no duplicate facts, `gossip` persists, C8's last-session tail recalls; the greeting-upgrade leg (≥ 6 h absence gate) moves to T1.
8. **Drills**: dead endpoint = `127.0.0.1:<closed port>` (instant refusal, not a SYN burn); fallback ≤ SLA+10 s + ≥ 1 failure-class log + **exactly one** delivered line (the A4/A5 exclusivity proof); saturation = ≥ 6 whispers in 2 s to distinct bots ×3, pass = ≥ 1 busy-or-fallback line and zero silent drops (the busy class needs duty-cycle saturation, not the concurrency cap — drill sized accordingly); A5 floor observation bounded ≤ 360 s or excluded.
9. **Background**: record last `Max Diff:` before backgrounding, assert delta ≤ 5000 ms after return (the metric is monotonic-since-start — `World.cpp:1533-1535` resets ~never — v2.2's absolute bound was falsely red); + session alive + post-return whisper ≤ SLA+10 s; an adb driver (`input keyevent HOME`) joins H2; emulator-vs-device documented as approximation (B5's real-hardware leg is a device-test checklist item, not an emulator gate).
10. **Tier-up**: forced via the relay's `llm relationship` command while the world is **stopped**, then boot (a live bump races the async stomp write); ceremony visible + rider per-player; persists across restart.

Single client throughout (verified — one client sees all say/party traffic). Per-profile bounds stated per step.

### T4 — Soak + perf (assertion set rebuilt)

Per profile, soak clock starts at steady state (`botsOnline ≥ 0.95×target` sustained, or `rampCappedAt` recorded with reason — power-save caps accepted, memory-floor/world-p99 caps are failures; the `BotPressureBenchmarkRunner` semantics adopted wholesale):

- **Liveness**: poll world status ≤ 10 s — zero FAILED transitions; post-soak clean stop + clean next boot (no dirty-journal recovery); grep the real wedge marker ("world loop wedged") not "FATAL" (no such log class exists); harness-transcript `FATAL:` greps scoped to world.log only.
- **Log health**: pre-soak conf pin `LogFileLevel = 1`; ≤ 10 MB gate with 50 MB flood backstop; measured on-device (`run-as stat`), not through the adb copy.
- **Tick p99**: `worldTickP99Ms` from world status (2048-tick window @ world_runtime.cpp:156-185); max steady-state sample ≤ 250 ms **and** `botTargetAdapted == false` **and** final count ≥ 0.95× target (the no-shedding pin — p99-at-full-target is jointly implied by the admission contract).
- **Conversational latency**: ≥ 30 paired turns, client-observed, nearest-rank p50 ≤ 4 s / p95 ≤ 8 s; flake policy: one outlier in (8 s, 60 s] allowed if p90 ≤ 8 s; any reply > 60 s fails outright.
- **RSS**: recorded datum only (B6) — population cut via test-only admission override, anon-heap (malloc_info), differential if a disabled lane exists.
- **Promise-kept**: class-explicit — fresh-character first-contact (authored ≤ 5 s), repeat greets (generated, counted under the SLA leg), post-restart-gap arrival greets (authored ≤ 5 s); ≥ 20 class-b and ≥ 5 class-a events per soak so neither leg is vacuous (v2.2's version could pass 100% on authored welcomes).

### T5 — Regression sweep + scorecard

Mechanical: full pytest host suite (the real count is ~370 collected tests / 394 items with 7 CI deselects — v2.2's "163" was the LLM-slice function count), gradle suite + detekt (now CI-wired), prompt-format pins, emitter --check (fixture-backed), lockfile pins ×3 lanes, realmd_auth. **The "20-reviewer judgment set" did not exist and is replaced**: freeze a before-transcript by running the committed battery on the unmodified build; score both transcripts on 3 fixed rubrics (engagement = unprompted follow-ups/10 turns; pacing = latency percentiles + inter-line gaps; continuity = fact/tier/history persistence across restart), human-read gate ≥ 4/5 (the `s8_beats_gates.py` precedent), scorecard committed under `docs/evidence/`. If a before-battery can't be produced, drop the comparative framing and gate on T3/T4 absolutes — no improvised judgment panels.

---

## 11. Execution order & dependencies (⟦R3⟧ rebuilt — v2.2's ordering had three inversions)

- **Phase 0 — Rails (no speech-policy change):** G3 (full, with §0.c) → A8 (logs + reqId + key redaction + debug-gate fix) → A9 → B8 → B2 level-drop → H2 relay-min (world-chat op + smoke.py + reset op) + H1 minimal set. Everything after this is observable, secure, and gated.
- **Phase 1 — Probes + plumbing:** A0 (restaged) + A0.a (keys, one submodule-bump batch with A9's if not already landed) + E0 pools (authoring lane, parallel).
- **Phase 2 — Gates & budgets (one merge train):** A1 (+A1b quota at the PB_RPG_ payload) + A7 (two-tier budgets, arbiter lane evaluation, off-by-one, conditional bot2bot emission) + PlayerbotLlmGates.h (22nd overlay) + T1 negative-pin matrix. A6 does not start before A7.
- **Phase 3 — Dialogue mechanics (strictly sequential, same payload family):** A4-plumbing (inert; device byte-identical pin) → A3 (behind `LLMPartyReplyEnabled` default 0) → A4-flip (cloud lane). Smoke per change.
- **Phase 4 — Cloud producers:** A6 (needs E0-street + A7) + A5 (latch + ladder leg) + E3 (needs E0-refusals; PlayerbotSecurity lane-3 batch).
- **Phase 5 — Parallel lanes with internal edges:** **F1 → B5** (inverted from v2.2); **D1 → D2 → D4** (D3 = chatter-lane staging, anytime); C2's 0413 migration (corrected mechanics) then C1/C3(after E1 templates)/C4/C5/C6(after the CORE_REWARDQUEST anchor pair)/C7/C8 (same migration); B3 → B4 (after A2; bench-before-commit); B6, B7; E1/E2 (texts.sql lane-4 batch); F2/F3; G1(+hygiene)+G2 probe.
- **Phase 6 — Terminal:** T3 → T4 → T5.

Batch rules: one file version per overlay per phase (A2-zone-cap + A7-lane-arg both touch PlayerbotLlmMemory.cpp — co-landed); anchor-payload extensions to the same payload are one merge train; single-tree edits batch into one submodule bump per phase; the T1 pins land in the same commit as their change (the lockfile tripwire is CI-mechanical on every push — pins left for later go red). Thin slice if time-boxed: Phase 0 + E3/C3/B8/F2/F3/A9/A8 (the visible-jank pass) ships value independently of WS-A's core. Rollback: §0.a per key; C = additive nullable + append-only 0413 with the documented downgrade law; E = additive pools; F = copy.

## 12. Risks & mitigations (⟦R3⟧ corrected — 6 of v2.2's 9 rows were wrong as claims)

| Risk | Mitigation (corrected) |
|---|---|
| A1 widening opens lanes | The lanes are **already open** (R2); A1b quotas are retroactive containment — merge the quota in the same change; install-audit pin that no ==2 profile ships un-quota'd |
| A2 always-active mass | Bracket+enum+cache-stamp mechanism (Map.cpp claim dropped); 16/map cap + TTL prune + interlocutor-wins; arming only on real-player turns; rotation pattern pinned |
| A4 double-delivery/double-award | Guid awards + single closure + exclusivity pin; A4/A5 consumed-failure handoff; async-region no-deref scan |
| A5 doctrine/stamp violations | CloudLaneOpen() conjunction + behavioral device pins (the string pin can't catch leaks); latch spec; floor keeps the delivery stamp (no ARB exemption) |
| B5 battery/policy | Predicate composite + promote-then-demote fence + in-gate demotion; **pre-step dumpsys verification** (bound-by-FGS may already exempt — redirect to mariadbd if so); F1 first |
| B2 crash-tail loss | Appender deferred; when built: error-class flush + 256/5 s, documented ≤ 5 s SIGKILL window (no flush path exists on LMK) |
| C migrations | 0413 append-only, seed frozen, full re-pin checklist, parity test, downgrade law documented + pinned |
| G1 reactor | Timer + liveness count + world-mirror decision + kill-switch; fd coupling demoted to probe + hygiene |
| 8 GB soak lane | Fingerprint re-checkin + live compare; snapshots off; **relay state reset between runs**; launch flags pinned per lane (never the 4096 default) |
| ⟦R3⟧ added: API-key disclosure/MITM | §0.c riders merge-blocking; G3 precedes first live byte |
| ⟦R3⟧ added: cloud spend invisibility | Interactive/ambient budgets + per-player cap + disclosure copy + "generations today" line in Diagnostics (AppLog BotLLM count — the ServerStatusJson ABI is pinned at 8 fields; don't touch it) |
| ⟦R3⟧ added: schema downgrade | Fail-closed by design; documented + release-noted; reinstall = data loss stated |
| ⟦R3⟧ added: test contamination | Reset step; fresh characters; quota counters are process-local (documented) |
| ⟦R3⟧ added: H/T sequencing | Phase 0 harness; relay-first; protocol client = realmd-auth stretch only |

## 13. Recorded deferrals (explicit, not silent)

1. **W2 errands** (relationship→quest bridge; design at `docs/plans/rp-depth-local-vs-cloud-plan.md:32` — verified) — deferred; C6's deed values leave hooks.
2. **Echo-latency clamp as a separate mechanism** — corrected rationale: B4 + A2 cover steady-state and turn-2+; **first-turn latency is covered by neither** (arming is post-drain) — B3's 3000 ms is the first-turn lever; the deferral is evidence-gated on T4's first-turn measurements.
3. **/say first-contact welcome** — rejected (recorded): say-range scripted welcomes read as NPC barks; whisper is the intimate channel; street liveliness on /say is A6's.
4. **Conversational-history persistence — no longer deferred** (v2.2 omitted it entirely; it is C8 now). If C8 slips past the release, the F3 caveat copy ships in its place and the slip is recorded here.
5. **LAN quota fairness** — quotas are realm-global; a per-player sub-cap is a one-line follow-up if `allowLanPlayers` realms report starvation; recorded.
6. **Presence-gating as the ambient control plane** — the two-tier budgets are the shipped mechanism; presence-gating (the murmur lane's `RealPlayersInWorld`+proximity primitives extended to street/rpgchat) is recorded as the preferred end-state and the natural A7 successor if quotas prove noisy.

## 14. Evidence appendix (⟦R3⟧ new commitment)

- Commit a sanitized `rp_session_transcript_2026-09-05.log` under `docs/evidence/` with a symptom→line→code-anchor index (the motivating evidence is currently gitignored and unverifiable from a clean checkout).
- Commit the T5 before/after scorecard artifacts under `docs/evidence/` with versioned filenames.
- This document's amendment history: R1 (27), R2 (13), R3 (100-reviewer panel: ~200 findings, 8 invalidations — dead-gate, C2 seed-edit, B6 API facts, D3 no-op, A2 Map.cpp hook, G1 premise + fd folklore, T5 judge set, F4/F5 duplication). Two v2.2 ⟦R2⟧ markers were themselves false (B6's "corrected" API facts; the undeclared ⟦R3⟧ header) — this revision declares all three rounds and carries finding IDs in the commit message, not the body.

---

## 15. Round-robin review gate (⟦R4⟧ added mid-run — the terminal review protocol)

The merge gate's final pass is a **round-robin review with 8 independent
reviewer agents**, run after all implementation phases land and before any
release-claiming commit. The protocol, exactly:

1. **Panel composition (8 scopes, fixed).** Each reviewer agent owns ONE
   scope and reviews the full diff of this plan's run (baseline commit to
   HEAD) against the plan sections named, plus a general bug hunt in its
   scope:
   - R1 Native cloud lane: §2 A1/A3/A5/A7 + PlayerbotLlmGates.h +
     SayAction/AiFactory/RpgTriggers anchors (conjunction law, device-lane
     byte-identity, quota math, threading).
   - R2 Native transport + security: §0.c riders, G3 TLS/redaction/port,
     A8 observability (reqId pairing, no content leakage, class truth),
     A9, and the driver anchor payloads' byte-exactness against pristine.
   - R3 Authored corpus + persona: E0 pools (register/word laws, state-key
     lanes, seeded-path isolation), E3 wiring (kill-switch, dedupe key,
     archetype mapping), A5's floor wording invariants.
   - R4 Schema + memory persistence: C2/C8 migration (append-only law,
     no-backfill, parity, downgrade law, ledger pins), C-workstream
     columns' writers, the sqlite seed re-pin family.
   - R5 App conf/emission surface: CloudLaneConf grouping, the appended-
     block-only law, device-block cloud-key absence, settings write-set,
     detekt baseline legitimacy (no hand-edits), B2/B8 emission pins.
   - R6 App UX/supervisor: F2/F3 copy truthfulness (every string code-
     supported), the Cloud toggle disclosure (§0.c.4), B7, B5/F1 seams,
     Settings/UI pins.
   - R7 Harness + tests: tools/rp_harness correctness (relay ops, A8
     post-pass), the full T1/T2 pin matrix vs the plan's §10 lists — find
     pins that are missing, tautological, or weakened relative to v2.2.
   - R8 Whole-plan conformance: standing constraints §0 (all 13 + §0.a/§0.b/
     §0.c/§0.d), phase ordering (§11), no time estimates, no bot-count
     reductions, emitter law (§0.3/§0.4), and the honesty of the PLAN-LOG
     claims against the actual tree (spot-verify 5 random claims).
2. **A round PASSES only if ALL 8 reviewers return zero findings of
   BLOCKER or MAJOR severity.** MINOR/nit findings are recorded in the
   review log but do not fail the round.
3. **If ANY reviewer fails the round** — reports a BLOCKER/MAJOR, OR a
   reviewer errors out (infra failure, timeout, non-verdict) — **the fixes
   (or the re-run) are applied and the ENTIRE 8-reviewer round runs again
   from scratch.** No partial credit, no carrying a passing reviewer's
   verdict across rounds: every reviewer re-reviews the full current tree,
   because a fix for one finding can invalidate another scope's pass
   (shared files: the driver, PlayerbotLlmMemory, Settings/LlmRuntimePolicy).
   Loop until one full round passes with 8/8 clean verdicts.
4. **Every reviewer must verify, not vibe.** Each BLOCKER/MAJOR finding
   cites file:line evidence reproduced by reading the tree (or a failing
   command it actually ran). A reviewer that cannot run a command it needs
   (no device, no compiler) says so and marks the item UNVERIFIED rather
   than guessing; an UNVERIFIED potential-BLOCKER still fails the round
   and escalates to a scope that can verify it.
5. **Review log.** Each round appends to `docs/evidence/review-rounds.md`:
   round number, per-reviewer verdict (PASS / findings list with
   severities+evidence / ERROR), the fixes applied between rounds, and
   the diffstat re-reviewed. The gate is closed when the log ends with a
   round recording 8/8 PASS. Cap escalation honesty: if a round surfaces
   no NEW findings twice in a row after fixes, but a stale finding cannot
   be resolved without device access, record it as a device-gated residue
   in the checklist — do not loop forever on an unverifiable.
