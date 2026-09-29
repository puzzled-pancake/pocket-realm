# Pocket Realm — Feature-Parity Gap Register (Vanilla → TBC → WotLK)

**Status:** converged register (v1.0, 2026-09-29) — companion to `docs/TBC-WOTLK-MULTI-FLAVOR-PORT-PLAN.md`.
**Method:** 9-round multi-agent red-team loop (5→3→2 hunter lenses per round; patch-notes completeness, player-expectation, and app-feature-walk challenge modes; independent claim verification). Convergence: round 9 added **zero new gaps**. ~290 raw findings → **~240 unique gaps** after dedupe; 12 high-severity claims independently confirmed against primary sources (cmangos trees, wotlk-db trackers, checksum corpora).
**Reading guide:** severity **P0** = blocks the flavor shipping, **P1** = major experience loss, **P2** = expected feature, **P3** = polish. Owner stages refer to the port plan (S0–S10 / RA-TBC / RB-WotLK). "new:Sx" = needs a new work item in that stage.

---

## 0. Headline corrections to the port plan (act on these first)

| # | Correction | Evidence |
|---|---|---|
| C1 | **realmlist schema: cmangos TBC/WotLK use `realmbuilds` varchar(64), NOT `gamebuild`** (that's TrinityCore). Plan §3/§6/S2.4/S8.4 rows are wrong. Seed `realmbuilds='8606'/'12340'` per flavor. | mangos-tbc/mangos-wotlk `sql/base/realmd.sql` |
| C2 | **MPQ sets (plan §3/§6 wrong):** TBC root = `common/expansion/patch/patch-2` (+ Data\enUS\ locale/speech set) — *not* vanilla-11+expansion. WotLK root = `common, common-2, expansion, lichking, patch, patch-2, patch-3` (no patch-4) + locale set; tolerated aux: `base-enUS`, `backup-enUS`. | checksum corpora, real client load logs |
| C3 | **`SMSG_AUTH_CHALLENGE` = 0x1EC in all three branches** — plan's "branch-defined" row wrong; SRP6 frames unchanged. | Opcodes.h / AuthSocket.cpp all branches |
| C4 | **Playerbots upstream EXISTS for all three expansions** — cmangos/playerbots master is multi-expansion (MANGOSBOT_ZERO/ONE/TWO, per-expansion conf dists, CI matrix). The S3 kill-switch premise is void; the fork pin 7e2cd2fb (2026-09-06) is post-multi-expansion → **one bots pin serves all flavors**. S3 becomes: port LLM patches + per-flavor conf/SQL selection + disable in-tree legacy `src/game/PlayerBot` module. | playerbots CMakeLists/CI/conf dists |
| C5 | **ACID is inlined as [ACID] commits in tbc-db/wotlk-db now** — the plan's `acid_*.sql` selection rule is stale; pull [ACID] commits from DB-repo history instead. | tbc-db/wotlk-db trees |
| C6 | **WotLK exe is ~12.6 MB ≈ 2.6× vanilla (~4.8 MB), not "~10×"** — fits the 32 MiB scanner cap. Pristine MD5s known: 12340 `45892bde…`, 8606 `57c5c030…` → S5.1 shrinks to SHA-256 compute + cross-check. | anzz1/wow-client-checksums |
| C7 | **WotLK 3.3.5a is NOT large-address-aware** (2 GB VA) — resolves plan's "verify"; input to the S6.4 go/no-go and client memory budgeting. | community/warmane OOM reports |
| C8 | **TBC WTF is already 3-level** (per-realm/per-char SavedVariables); only the char-level cache-file set (`config-cache.wtf`, `bindings-cache.wtf`, `macros-cache.txt`, `layout-local.txt`, `chat-cache.txt`) is WotLK-era. | plan §6 row fix |
| C9 | **WotLK VoiceChat is a 46-line inert stub** (swallows the opcode, no conf keys; classic has none) — ship as-is + docs note. H1-17's "VoiceChat conf keys" overclaim retracted. | mangos-wotlk `src/game/VoiceChat/` |

## 1. Critical path (P0-class — the flavor ships or dies on these)

| ID | Gap | Parity plan | Stage |
|---|---|---|---|
| H2-1 | TBC/WotLK clients read realmlist from **`Data\<locale>\realmlist.wtf`**, not the root file the app writes → flavor clients never connect | Extend `ClientRealmEndpointProjection` to write root + `Data/<observedLocale>/` copies, strip patchlist/realmlistbn/portal lines (H2R2-4), runtime test "connects to local realm only" | S7/S8 |
| H4-1 | **AndroidPort addon uses insecure Lua** (UseAction/bag use/frame moves) — TBC 2.0+ `InCombatLockdown` blocks all of it in combat | Port action buttons to `SecureActionButtonTemplate` + attributes; guard/defer bag-use & movers; bindings stay primary path | S7 |
| H4-2 | **WotLK vehicle/override bars** unmodeled in the 8×4 action model and controller profile | Per-flavor slot resolution honoring `GetBonusBarOffset()`/override UI; vehicle chord map or honest copy; VEHICLE binding category capture | S7 |
| H4-3 | **Per-flavor addon catalogs don't exist** (catalog caps at TBC entries; zero WotLK): need pfQuest-Reforged (30300), Zidras/DBM-WotLK, Omen3, AI-VoiceOver 3.3.5 — pinned repos/commits/checksums like vanilla | Research+pin per-flavor catalogs; per-flavor clientTarget validation | S7 |
| G1-4/G1-5 | **ICC Gunship Battle 20% scripted, The Lich King 5%** — mandatory-path WotLK progression blockers for 1+4 bots | Port both from TrinityCore (GPL-2.0 compatible) into ScriptDevAI + bot strategies (defile spread, Val'kyr focus, cannon/boarding); or recorded cut with teleport bypass | S2/S3 |
| G2-1/G2-2 | **Warden: Anticheat module constructs WardenModuleMgr unconditionally when Anticheat.Enable=1 and throws (mangosd terminates at first login) if warden_modules absent**; `Warden.Enable` key is parsed-but-dead. Shipping conf defaults Enable=false (J3 nuance) — but Android packaging must also rule on the ~80 Blizzard .bin/.key/.cr files (licensing) | Packaging verdict in sources.json (exclude by default) + lazy null-module patch + first-login-under-Wine smoke per flavor + one-conf-key-away hazard noted | S2 |
| F3-3/H2-5 | **Single-qualified-lane landmine:** `qualified().single()` consumers crash the import/planner/prep lanes the moment a second flavor qualifies; storage planner has no cumulative cross-flavor cap. *ID note: "H2-5" here is this row's own shorthand (a WTF/storage-scope sibling of F3-3), not a register §2.x row — no standalone H2-5 gap exists; the treatment is carried under the F3-3 ID.* | Thread `ClientFlavor` through import/planner/prep now; cross-flavor workspace accounting with deny VAL; S9 multi-flavor end state | S5/S9 |
| F1-1/F1-4/F1-5 | **Facade (POCKET_EMBEDDED), o09 overlay anchors, and SQLite backend replacements are classic-verbatim** — wotlk `Master.cpp` uses signal-wait (no stopEvent), overlay anchor text and `DatabaseSqlite` content drift per branch | Re-derive facade commit (16 files) + per-flavor overlay registries + reconciled DatabaseSqlite per pinned commit; per-branch anchor verification | S2 |
| G3-1..G3-10 | **Vanilla-regression quarantine hazards:** strict schema checks (UserAccountStore schema==2, manifests, journal codec, snapshot manifest length==5, ledger seal sha, generation-pointer pruning, addon registry keys, conf golden bytes, lore asset name) will quarantine/strand EXISTING vanilla users when per-flavor fields land as schema bumps | Optional-field shims defaulting "vanilla" everywhere; golden byte-identical tests for vanilla conf/ledger/catalogs; fixture tests with current-release records; scope generation pruning per flavor | ALL stages |
| H4R2-2 | **No character-creation flow exists** (fresh account = zero characters) on any flavor | Server-side starter-character relay (mirrors `account create` path) or guided controller walk-through with per-flavor grid maps; DK gating decision (J2-1: seed `HEROIC_CHARACTERS_PER_REALM`/`MIN_LEVEL_FOR_HEROIC` conf) | S7/S8 |
| P1-2/P1-3/P1-4 | **WotLK memory envelope unmeasured** (TC full-grid preload ~5 GB; bots guidance 8 GB+; client 1.4–1.6 GB + Box64 overhead on a 12 GB shared device) | Mandatory RP6 measurement milestone before S6.4 go/no-go: mangosd RSS vs bot count, client RSS at Dalaran, boot wall-clock; per-flavor admission floors + memory ceilings; forbid grid preload | S2/S6/R4 |

## 2. Register by area

### 2.1 Server gameplay systems (R1: H1, R2: H1R2, R4: F1, R5: G1/G2, R6: J2)

| ID | Gap → parity plan | Stage | Sev |
|---|---|---|---|
| H1-2/J2-1/H5R2 | DK: creation gate (level-55 char, one per realm — config `HEROIC_CHARACTERS_PER_REALM`/`MIN_LEVEL_FOR_HEROIC`), bot DK AI (runes/runeforge), creation matrix, era tests | S2/S3/S8 | P1 |
| H1-3/H3-5 | Bot talent 61-70/71-80 specs, glyph loadouts (`PremadeSpecGlyp` key), pet talents, dual-spec (3.1: lvl 40/1000g — test trainer+swap; bots free) | S3 | P1 |
| H1-4/H5-4 | Achievements: upstream partial criteria; carry achievement_* tables through SQLite lane; bot-earn policy; audit vs TC 3.3.5 matrix | S2/S4 | P1 |
| H1-5/H5-3 | LFG (TBC tool only — no RDF backport) vs RDF (WotLK): seed `lfg_dungeon_rewards`, design offline queue-with-bot-fill loop or documented disable | S3/S4 | P1 |
| H1-6/H5R2-4 | Wintergrasp: implemented as Battlefield but thin (vehicles/siege); smoke the cycle, feature-flag default-off, honest UI copy; bot WG/Halaa strategies or auto-win timers | S2/S3 | P2 |
| H1-7 | BG set: EotS (TBC), SotA/IC (WotLK) templates from DB, bot soak per map, disable broken ones via conf | S3/S4 | P2 |
| H1-8/H5-7 | Arena teams/ratings per flavor; bot skirmish mode (teammates/opponents, rating decay sim, season vendors) — TBC's signature PvP is dead without it | S3/S4 | P1 |
| H1-9/J1-4 | Phasing: content pass on phased chains, bot-master phase sync (DK chain!), phase persistence across Save&Exit | S3/S4 | P2 |
| H1-10/H5-9/P1 | Flying lifecycle: 225/300 ranks, CWF (lvl 77/1000g/Tome), Dalaran no-fly, 310% (Onyxian Drake); bot flight smoke on 530/571 | S3/S4/S6 | P1 |
| H1-11 | Barber+calendar: calendar_* tables in wotlk SQLite lane; gtBarberShopCost DBC in extractor floor | S4/S6 | P2 |
| H1-12 | JC/inscription: trainers/recipes/gem cuts/glyphs floors per flavor | S4 | P2 |
| H1-13/C2 | XP/caps: per-flavor bot max level + XP-rate conf; automated cap test; rested-XP knobs | S3 | P2 |
| H1-14 | Race/class creation matrix per flavor (BE/draenei combos; +DK wotlk), adversarial both directions | S7 | P2 |
| H1-15/J3-11 | Disable in-tree legacy PlayerBot (~29-32 files) in tbc/wotlk builds; fail-loud if both backends on | S2/S3 | P2 |
| H1-16 | mod-playerbots (AC 3.3.5, GPL) recorded as fallback with trigger conditions | S3 | P3 |
| H1-17/G2-9 | Per-flavor conf-key matrix from each core's .conf.dist (incl. `Channel.RestrictedLfg=0` offline); fail-loud unknown keys | S2/S3 | P3 |
| H1R2-1 | DB pin freshness gates: expected revision strings (`required_s2492…` TBC / 14104 WotLK) in per-flavor ledgers; zero-mismatch boot assert | S4 | P1 |
| H1R2-2 | World events/seasonal activation matrix per flavor; seasonal boss spawn acceptance tests | S4/S6 | P2 |
| H1R2-3 | TBC heroic keys/attunements seeded + bot-carried (The Master's Key 24490 confirmed present); Shattered Halls heroic entry test | S4 | P2 |
| H1R2-4 | Transports: TransportAnimation/Rotation DBCs + type-42 GO data per flavor; ride Menethil→Valiance Keep test | S3/S4 | P2 |
| H1R2-5/F2-7 | Raid/dungeon content floors (Naxx25, Sunwell, ZA loot, ICC) + upstream issue triage list blocking solo playthroughs; digest-pin Full_DB blobs | S4 | P2 |
| H1R2-6 | Guild bank tables in per-flavor characters base + migration smoke (deposit/withdraw/perms) | S4 | P3 |
| H1R2-7/J1-2 | AhBot/bot economy per flavor: ahbot SQL from playerbots per-flavor trees, price profiles, **player gold walls** (5000g/CWF/dual-spec) vs quest-only income — tune or opt-in AhBot tier; document g/hour | S3/S4 | P2 |
| H1R2-8 | Patch-port regression matrix: downrank 2.4 formula, ratings/resilience, flying dismount, battle-rez rules — runtime smoke per branch | S3 | P2 |
| G1-7 | Vehicle encounters (Flame Leviathan, Malygos P3, Oculus): bot vehicle-driver AI or documented cut/nerf decision | S3/S4 | P1 |
| G1-9 | TBC heroic CC-trash: bot crowd-control assist (sheep/trap/fear) or zerg-degrade documented | S3 | P2 |
| G1-8 | Encounter DBC manifest: Vehicle/VehicleSeat (WotLK) + npc_spellclick_spells rows in extractor/seed floors | S2/S6 | P1 |
| G2-3 | Anticheat offline posture: write Enable=0 (kick/ban heuristic would ban the sole user); conf-generator key | S2 | P2 |
| G2-4 | Client OS/platform report under Wine vs Warden kick path — qualify before enabling anticheat | S7 | P2 |
| G2-5 | Client self-check on corrupt patch MPQs (hard boot fail) → hash-qualify full MPQ set at import (not just exe); map boot error to re-import | S5 | P2 |
| G2-6 | Repair.exe/Launcher.exe exclusion from managed copy (Repair reverts byte-patched exe to retail) | S5 | P2 |
| G2-8/G2-10 | Ticket/GM offline copy; guild charter registrar NPCs seeded; manual charter flow (bots guild: J1-1) | S4/S7 | P3 |
| J2-2 | Char enum `ORDER BY guid` (creation order, not recency) → per-flavor fork commit ORDER BY logout_time | S2 fork | P2 |
| J2-3 | Tutorial flags per-ACCOUNT in cmangos vs per-char client expectation → per-flavor fork fix | S2 fork | P3 |
| J2-4 | WotLK titles: `knownTitles` column + handlers in fork; sparse dropdown until achievements | S4/S2 | P2 |
| J2-5 | Currency tab (slots 118-150): keep currency-flagged items through era-lint/pipeline; emblem smoke test | S3 | P3 |
| J2-6 | /who lists all bots → product decision (tag+skip or config) | S3 | P3 |
| J2-7 | Packet-conformance error surfaces (2.4.3 ERROR #0 dialogs, 3.3.5 #132 crashes on struct mismatch) → per-flavor conformance soak + supervisor treats ERROR dialogs as session failure | S10 | P2 |
| F1-10 | Relay expansion op (`account set addon`) + ABI version-bump discipline | S2/S8 | P1 |
| F2-4 | **wotlk-db reads DB spell data** (`spell_template` 50,889 rows) + `quest_poi/quest_poi_points` (56,432) — per-flavor table manifests + translator support (see P2-4) | S4 | P1 |
| F2-6 | TBC taxi network unverified upstream (wotlk-db got the taxi rework; tbc-db tracker disabled) → fly every Outland FP test; port fixes if clipping | S4 | P2 |
| F2-2 | Battle for the Undercity questline absent from wotlk-db (issue #240) → accepted-cut decision or community-fix aug SQL | S4 | P1 |
| F2-1 | TBC quest coverage ~6.6k/9k rows; no Isle of Quel'Danas SSO progression (2 quests scripted) → per-zone quest floors vs Wowhead; static fully-unlocked-Isle baseline decision | S4 | P1 |
| F2-3 | Training dummies missing from wotlk-db (issues #400/#493/#494) → seed-aug SQL | S4 | P2 |
| F2-5 | Glyph items (~370 present) — count floor vs Wowhead per class | S4 | P3 |
| F2-8 | Locale tables partial (zh/de only) — verified non-gap; exclude from floors | S4 | P3 |
| F2-9 | Adopt measured floors: vendors 6,444 (tbc)/34,028 (wotlk); creature_loot 174k/220k; events 153/300; keys/glyphs present | S4 | P3 |

### 2.2 Client data & runtime (R1: H2, R2: H2R2, R3: P3, R8: W1)

| ID | Gap → parity plan | Stage | Sev |
|---|---|---|---|
| H2-2/H2-9 | Locale-subfolder MPQ verification + **exact-set semantics** (unknown patch-*.mpq = VAL-04 fail naming the file); derive sets from the qualified client | S5 | P1 |
| H2R2-3 | Pristine MD5 cross-checks (known) + negative vectors for common modified exes (LAA/d3d9ex/repack) | S5 | P1 |
| H2R2-4/H5R2-8 | Neutralize `set patchlist`/`realmlistbn`/`portal` at import; deny-list Launcher/BackgroundDownloader/Repair execution; offline cold-start QA | S5/S7 | P2 |
| H2-3/P1-4 | WotLK client memory ceiling (no LAA) → LAA-flagged variant qualification (hash-pinned, reusing vanilla-tweaks machinery) or RAM admission gate; graphics preset default | S7 | P1 |
| H2-4 | d3d9ex × gxTextureCacheSize DXVK trap (≥2048 halves FPS) → WotLK SAFE_CONFIG pin ≤2047; qualify per DXVK version × driver | S7 | P1 |
| H2-6 | Intro/Expansion movies (DivX/DirectShow): pre-seed movie/expandMovie off per flavor + stuck-in-movie VAL + topology skip step | S7 | P2 |
| H2-7/H4-7/J2 | Runtime artifacts (Cache/WDB growth, Errors/*.txt+dmp, Screenshots): storage accounting, Save&Exit tolerance, harvest crash texts into diagnostics with redaction | S7 | P2 |
| H2-8 | Non-enUS clients: record observedLocale; decide restrict-enUS vs accept-any-with-localized-DBC copy; negative fixture | S5 | P2 |
| H2-10 | mmtile nav-generator version in manifest + consume-time check; adversarial tile-swap test | S6 | P2 |
| H2-11/F1-8 | Per-flavor required-DBC manifest (name+min rows+size band), incl. Vehicle/VehicleSeat + gtBarberShopCost/talent/lfg | S6 | P2 |
| H2-12 | DXVK shader-cache preserved across prefix regeneration; first-session stutter copy; warm-up in soak | S7 | P3 |
| H2-13 | Per-flavor render QA checklist (fog ring, water, shadows, weather, zone-border culling) in capture_rp6 scenario | S7 | P3 |
| H2-14 | Per-flavor root DLL set (fmod.dll suspicious on TBC/WotLK — static FMOD Ex); WotLK sound smoke | S5/S7 | P3 |
| P3-1 | Qualification invariant: qualified flavor must have non-null MPQ set, exe hash, budgets — fail at startup not first import | S5/S6 | P1 |
| P3-2/P1-5 | Per-flavor storage: maxTotalBytes ~25 GiB (TBC) / ~35 GiB (WotLK) verified against planner math; extracted ~9-10 GB / ~16-17 GB | S5/S6 | P1 |
| P3-3 | maxFiles/maxEntries vs 150-200k-file extracted trees (SAF inventory materializes all) → count real tree, raise caps or stream | S5 | P2 |
| P3-4 | Encrypted-entry rejection in the ZIP/7z/RAR lanes (currently Inno-lane only) before staging | S5 | P2 |
| P3-5 | Multi-part archive detection (.part1.rar/.7z.001) with actionable copy | S5 | P2 |
| P3-6/P1-6 | WotLK mmaps runtime on RP6 (est. 4-10 h throttled; desktop 2-12 h band) → benchmark + pre-decided desktop-assisted fallback before S6 exit | S6 | P1 |
| P3-7 | MoveMapGen child-RSS guard + RAM-derated threads; OOM-distinct journal failure | S6 | P2 |
| P3-8 | Prep peak-space accounting (staging+publish sha256 walk) in planner | S6 | P2 |
| P3-9/P3-10 | Per-flavor VAL rejection copy (ISO/NSIS/downloader/macOS .app) | S5 | P3 |
| W1-4 | Per-flavor camera cvars (cameraYaw/Pitch/Smooth/Tracking/Pivot, mouseInvertPitch) + touch-tuned defaults; several persist to config-cache.wtf → catalog reads both files | S7 | P2 |
| W1-7/W1-10 | Donor-machine cvars: coresDetected/processAffinityMask pin wrong SoC → per-flavor SAFE_CONFIG full-core mask, hwDetect=0, scrub machine-specific keys at import | S7 | P2 |
| W1-2 | Per-flavor UI-scale clamp verification (sub-slider uiScale in 3.3.5) | S7 | P3 |
| W1-8 | Name TBC/WotLK low-spec cvar sets (M2UsePixelShaders/shadowLOD/shadowLevel) explicitly in plan | S7 | P3 |

### 2.3 Bots & LLM (R1: H3, R2: H3R2, R4: F1-9, R6: J1/J2, R7: C2)

| ID | Gap → parity plan | Stage | Sev |
|---|---|---|---|
| H3-1/H1-1 | Pin cmangos/playerbots (multi-expansion, one pin serves all) + build matrix per branch with playerbots ON | S3 | P1 |
| H3-2/J2-1 | DK bots: upstream strategies + factory creation + slot policy + runeforge test | S3 | P1 |
| H3-3 | Expansion-zone bot travel/questing: transport/FP graph, soak assertion bots level 61-70/71-80 inside 530/571 | S3 | P1 |
| H3-4 | Upstream drift matrix since 7e2cd2fb; cherry-pick travel/quest fixes; BG/arena keep-off keys proven inert per flavor | S3 | P1 |
| H3R2-1 | Per-flavor conf-key matrix (tbc/wotlk dists diverge: no Jump*/GearProgressionSystem/LevelCheck/UseKnockback/PremadeSpecProb; wotlk adds PremadeSpecGlyp); re-baseline keep-off audit per flavor | S3 | P2 |
| H3R2-2/H5-2 | Dungeon/raid strategy inventory per flavor (stops at Kara/Mechanar/Naxx); port/author flagship-first; heroic comps; generic-fallback limits documented | S3 | P2 |
| H3R2-3 | Bot gear pipeline: badge/emblem acquisition + periodic re-equip via weightscales; randomGearMaxDiff ceiling | S3 | P2 |
| H3R2-4 | Riding automation: BudgetValues hardcodes basic 40/epic 60, flying zeroed → flavor thresholds + CWF purchase at 77 | S3 | P2 |
| H3R2-5 | Bot professions creation-only → accept+document or bounded leveling tick; caps 300/375/450 | S3 | P3 |
| H3R2-6 | LLM memory gate: per-flavor corpus token budget vs llama ctx 12288/6144 KV on 12 GB shared — measure RSS with 320 bots; cap lore additions | S3 | P2 |
| H3R2-7 | Speech-trigger qualification per flavor (whisper/say/party lanes on 8606/12340) | S3 | P2 |
| H3R2-8 | Commander surface flavor-blind: per-flavor max-level clamps, knob ranges, .bot cheat-sheet | S3 | P2 |
| H3R2-9/J1-1 | Bot guild keys per flavor (CreateRandomGuilds/GuildBank/BuyGuildBankTab) — keep-off defaults audited; guild formation for the PLAYER (9 signatures) needs sanctioned bot-signature lane or honest out-of-scope copy | S3 | P2 |
| H3-7 | Lore corpora v204/v303: provenance/licensing per card, target counts (Outland/Northrend arcs), attribution manifest | S3 | P2 |
| H3-8/F3-2 | Era-lint externalized from compile-time arrays to per-flavor data files staged by ServerRuntimeFiles + corpus-driven lint tests; zone-aware lint decision (wotlk bot in Elwynn) recorded | S3 | P2 |
| H3-9/H3-10 | Persona pools (BE/draenei/DK names) + banter topics per flavor | S3 | P3 |
| H3-11/F3-9 | ChatMsg enum static_asserts per branch + per-channel speech-quota weights per flavor (starvation soak) | S3 | P3 |
| H3-12/J1-2 | AhBot keep-excluded decision recorded per flavor (build flag + staging check) | S3 | P3 |
| J1-5 | Quest-tag friction: exclude player's active quest targets from bot objectives or force shared credit; unsynced high-level bots one-shotting vanilla zones | S3 | P3 |
| J1-9 | Bot social continuity: pin player-befriended bots against reroll churn | S3 | P3 |
| F1-9 | Lore asset packaging per flavor (flavor.json llm.lore_cards, ServerRuntimeFiles staging, registry keys) | S3 | P2 |

### 2.4 App UX, addons & controls (R1: H4, R2: H4R2, R6: J1)

| ID | Gap → parity plan | Stage | Sev |
|---|---|---|---|
| H4-4 | Interact primitive per flavor: WotLK binds native INTERACTTARGET; TBC keeps whisper surrogate; re-verify self-whisper rate limits | S7 | P1 |
| H4-5 | WTF scope beyond SavedVariables: per-realm/char config-cache/bindings-cache/macros-cache/layout-local/chat-cache (WotLK) — writes, snapshots, corrupt-recovery per level (TBC: SavedVariables 3-level already) | S7 | P1 |
| H4-6 | Per-character keybindings (WotLK) — codec scope resolution + repair journal tests on 3-level trees | S7 | P1 |
| H4-7/H4R2-10 | Crash artifacts per flavor (Errors/Crash.txt+dmp) + per-flavor log inventory + runtime-builds.json in bundles | S7/S10 | P1 |
| H4-8 | Cache/WDB path map per flavor (root vs Cache/WDB locale subfolders); wipe/validation/bundle routing; first-run cache-build warning | S7 | P1 |
| H4R2-1 | Auto-login GLUE stage: realm preselection via realmName + char-select topology per build + Enter World injection | S7 | P1 |
| H4R2-3 | realmName hard-coded "MaNGOS" → per-flavor; realmlist realmflags 0x40 recommended; assert first login lands on char select | S7/S8 | P1 |
| H4R2-4 | Settings editor catalogs per flavor (109-cvar vanilla catalog → per-flavor data; float-echo format per build) | S7 | P2 |
| H4R2-5 | SAFE_CONFIG per-flavor seed (autoLootDefault, sticky targeting, click-to-move for controller contract) | S7 | P2 |
| H4R2-6/H4R2-7 | Import/tutorial copy + storage budget display per flavor (sizes, installer-era guidance: WotLK torrent downloaders → PC-extract-first lane) | S5/S9 | P2 |
| H4R2-8 | Per-flavor first-run state + flavor-switch card | S9 | P2 |
| H4R2-9 | IME: WotLK native IME under Wine — verify, enable Wine IME per flavor or extend Talk.lua surrogate; support matrix on Controls screen | S7 | P2 |
| H4R2-11/H4-11 | Binding catalog capture per flavor + owned-chord collision audit vs 3.3.5 defaults | S7 | P2 |
| H4-12 | DK rune HUD widget (or documented absence) + hero-class first-run copy | S7 | P2 |
| H4-13 | VoiceOver per-flavor policy (folder names/TOC deps/asset names; TBC coverage honesty note) | S7 | P2 |
| H4-14 | Out-of-date addon client behavior copy per flavor | S7 | P3 |
| H4-10 | Validator caps re-derived vs real WotLK packs (MAX_ENTRIES/512MiB); per-flavor Policy sets | S7 | P2 |
| H4-9 | TOS/EULA scroll-gated Accept qualification per flavor + fallback choreography | S7 | P2 |
| J1-3 | Self-service recovery lane (stuck teleport, FP unlock, hearth reset) as bounded in-app actions | new UX | P2 |
| J1-6 | Expansion transition handoffs (Dark Portal quest+portal, boat/orb arrival) per-flavor checklist | S4 | P2 |
| J1-7 | Attunement/key dungeons with bots (Black Morass timed event) in the strategy matrix | S3 | P2 |
| J1-8 | Bank panel in Bags container or D-pad navigation of stock bank frame (WotLK 7-slot row) | S7 | P3 |
| C3-walk | First-run replay story per flavor; Vulkan catalog/import/quarantine per flavor lanes; LLM model registry per flavor; LAN mode per flavor (see §2.6) | S7/S9 | P1-P3 |

### 2.5 Performance, persistence, import depth (R3: P1/P2/P3, R4: F3)

| ID | Gap → parity plan | Stage | Sev |
|---|---|---|---|
| P1-1 | World DB volume 3× (tbc 65 MB / wotlk 60 MB gz) → per-flavor buffer-pool floor + measured apply budget | S4 | P1 |
| P1-7 | Boot wall-clock per flavor (nav/bot init 15 min-2 h desktop reports) → per-flavor supervisor boot timeout + staged grid load | S2 | P1 |
| P1-8 | Per-flavor quotas: prefix/cache/log growth measured; 12340 exe 12.6 MB fits cap | S6 | P2 |
| P1-9 | 6h cap + Save&Exit at 80 (bigger blobs, bot autosaves) → per-flavor save budget, staggered autosave | S9 | P1 |
| P1-10 | Client FPS/frame-time + thermal sampling in soak harness (RP6 datapoint for 12340 under Box64 — none exists) | S9/R4 | P1 |
| P2-1/P2-3 | checkpoint_dbs.py + DatabaseEngine hard-code classic* → flavor-routed datadir iteration, grants/creates/probes, revision Triples (formats s-rev/numeric) | S4 | P1 |
| P2-2 | Snapshot/restore flavor validation (manifest gains flavor/db-set/table inventory; cross-flavor reject tests) | S4/S9 | P1 |
| P2-4 | SQLite translator: trigger/proc handling or explicit unsupported-construct fail-loud; per-flavor baselines (wotlk Full_DB 30 MB) | S4 | P1 |
| P2-5/P2-6 | Seed-augment per flavor; win sqlite seam lockfiles per flavor | S4/S1 | P2 |
| P2-7/P2-8 | Windows lane per-flavor identity/auth/extractor constants + ParityTest clients | S8/S10 | P1 |
| P2-9/P2-10 | Bundle budgets at wotlk scale (head+tail truncation); redaction key sweep per conf surface | S9/S10 | P2 |
| P2-11 | Update coordinator writes per-flavor requires-revalidate (ledger sha + extractor version); boot gate fails closed → re-prep | S9/S10 | P1 |
| P2-12/F3-6 | Save&Exit + supervisor recovery per flavor: flavor in journal (optional field!), mid-switch kill resumes journaled flavor, slow-boot vs hang heartbeat | S9/S10 | P1 |
| F3-1 | Revision-gate check tuples routed through per-flavor manifest (DB names + rev formats) | S4/S9 | P1 |
| F3-4 | Duplicate same-build copy policy (2nd wotlk copy → deny or per-copy namespaces) | S5/S9 | P1 |
| F3-10 | Backup/restore × session drain interlock | S9 | P2 |
| P2-a (R9) | processAffinityMask/coresDetected — see W1-7 | S7 | P2 |

### 2.6 Integration & coexistence (R4: F3, R6: J3, R7: C3)

| ID | Gap → parity plan | Stage | Sev |
|---|---|---|---|
| H5R2-1 | Cross-flavor character pinning (no migration path exists; account.expansion gates) — pin per flavor; optional one-way copy tool later | S9 | P1 |
| H5R2-2 | Honest raid story: 10/25 content with ≤5 companions — scaling config or full bot raid group decision; bot raid acceptance in build | S3 | P1 |
| F3-5 | Addon rollback × 3-level WTF (per-char SavedVariables sweep on retirement; binding-repair scoping) | S7/S9 | P2 |
| F3-7 | Bot map-policy gate (botRandomMaps ⊆ flavor maps; Dalaran no-fly; vanilla-zone-bots decision) | S3 | P2 |
| F3-8 | Leak-sweep endgame: per-flavor constants migrate to owning tables (flavor.json/ClientFlavors) in S9; owning-tables-only asserted at S10 DoD | S9/S10 | P2 |
| J1-1 | Guild formation (see H3R2-9) | S3 | P2 |
| C3 | App-feature per-flavor stories confirmed missing: Vulkan driver catalog/quarantine per lane; LLM model registry/NPU/endpoint per flavor; LAN join lane per flavor; first-run replay; battery-gated chatter (P2/P3) | S7/S9 | P1-P3 |
| W2-1 | Device-time-as-realm-time DST policy + event-schedule test | S9 | P3 |
| W2-2 | Client-trust/cheating acceptance recorded (single-player: own world) | docs | P3 |
| W2-3 | Cross-device backup restore: state support + fresh-device restore test | S10 | P3 |
| W2-4 | N≥2 humans on LAN: population math + one 2-client soak before LAN exit | S10 | P3 |

### 2.7 Vanilla-regression guards (R5: G3 — apply at every stage)

UserAccountStore: no schema bump — `optString` flavor defaulting "vanilla", quarantine only on invalid (G3-1, **S0-class**: current design would delete the user's account record) · manifests: flavor optional, readers accept both schemas (G3-2) · ledger seal: vanilla asset byte-identical across renames, golden sha (G3-3) · journal codec: optional flavor field (G3-4) · generation pointers seeded from legacy active.json, pruning scoped per flavor (G3-5) · addon registry/catalog ids stable, vanilla validator policies pinned byte-for-byte incl. VOICEOVER_DATA (G3-6) · conf golden byte-tests + registry projections defaulting to current constants (G3-7) · lore asset name kept for vanilla (G3-8) · snapshot manifests: never add keys to schema 2, or relax to ≥ (G3-9) · catalogs/policy/WTF layout verbatim for vanilla (G3-10).

## 3. Independent verification record (round 6, J3)

Confirmed from primary sources: Warden throw mechanics (with runtime-dir nuance) · realmlist Data\<locale>\ · wotlk-db#240 BfU missing · Gunship 20% / LK 5% · scripts in-core (no separate SD2) · TBC/WotLK MPQ root sets · realmbuilds (no gamebuild) · playerbots multi-expansion (pin caveat: verify 7e2cd2fb locally — it's on the puzzled-pancake fork) · 12340 not LAA · in-tree PlayerBot in both branches · TC 5 GB grid-preload comment. UNCERTAIN (settle at S4): tbc-db exact quest row count (import Full_DB, `SELECT COUNT(*) FROM quest_template`).

## 4. Decision points for the product owner

1. **Wintergrasp**: port-or-cut (TrinityCore port vs Dalaran-centric with WG disabled) — S2/S3.
2. **RDF with bots**: design the offline dungeon-finder loop, or disable RDF and document manual grouping — S3.
3. **Raid story**: 10/25 with ≤5 companions + scaling, or full bot raid group — S3.
4. **DK unlock**: blizzlike gate (first character to 55) vs relaxed for solo — S8.
5. **Non-enUS clients**: restrict or accept-with-copy — S5.
6. **Isle of Quel'Danas**: static unlocked baseline vs SSO progression investment — S4.
7. **Battle for the Undercity / missing chains**: accepted cuts vs community-fix aug SQL — S4.
8. **Vehicle encounters** (FL/Malygos/Oculus/Argent jousting): bot vehicle AI investment vs documented cuts — S3.
9. **Guild formation**: sanctioned bot-signature lane vs out-of-scope copy — S3.
10. **Player gold economy**: tuning vs opt-in AhBot tier — S3.
11. **Warden modules**: exclude (patch required) vs vendor with licensing verdict — S2.
12. **WotLK prep**: on-device vs desktop-assisted — measured go/no-go, S6.

## 5. Convergence record

| Round | Lens | New gaps | Running total |
|---|---|---|---|
| R1 | server/client/bots/UX/research hunters | ~65 | 65 |
| R2 | register re-issue + conflict adjudication | +39 | 104 |
| R3 | perf/persistence/import-depth/docs audit | +42 | 146 |
| R4 | native lanes/content completeness/integration | +31 | 177 |
| R5 | boss scripts/warden+voice/vanilla regression | +28 | 205 |
| R6 | journey sim/session layer/register verification | +16 | 221 |
| R7 | patch-notes/player-expectation/app-feature-walk | ~35 (mostly P2/P3 tail) | 256 |
| R8 | config corners + self-consistency | +6 (2×P2, 4×low) | 262 |
| R9 | final confirmation | **0** | **262 — converged** |

Cross-round corrections: playerbots upstream (R2 adjudication, H5 retraction) · MPQ sets (R2/R9) · realmbuilds vs gamebuild (R3) · 0x1EC all flavors (R3) · ACID inline (R3) · exe size ~2.6× not ~10× (R8) · VoiceChat stub (R8) · Warden merged entry with default-off nuance (R8).
