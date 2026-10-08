# Pocket Realm — TBC & WotLK Multi-Flavor Port Plan

**Status:** approved-for-execution plan (v1.0, 2026-09-29)
**Scope:** add Burning Crusade 2.4.3 (build 8606) and Wrath of the Lich King 3.3.5a (build 12340) as first-class flavors alongside Vanilla 1.12.1 (build 5875), with zero regression to the vanilla lane.
**Method:** staged execution; every stage ends at a defined **End State** and passes a **multi-agent red-team round-robin** gate before the next stage starts.

---

## 0. Mission

Pocket Realm today is one flavor: `offline-vanilla-1.12`, pinned end-to-end to CMaNGOS mangos-classic + classic-db + classic playerbots + the 1.12.1.5875 client. The mission is to make *flavor* a first-class dimension of the product so that the same app hosts, imports, prepares, launches, supervises, and plays **any supported flavor** (vanilla / TBC / WotLK) with per-flavor server cores, databases, extractors, client identities, catalogs, and UX — while keeping every existing safety property (fail-closed gates, provenance chains, supervision, Save & Exit ordering).

The platform half of the app (Wine/Box64 launch chain, X server, DXVK/Turnip/VirGL/Gladio, supervisor, MariaDB/SQLite engine, import staging, LLM runtime, diagnostics, LAN mode) is client-agnostic and is expected to carry over with parameterization only. The game stack is duplicated per flavor.

## 1. Scope & non-goals

**In scope**
- Server core, DB content, bots, extractors, import/identity, client runtime UX, accounts, addons, LLM corpora — per flavor.
- Flavor selection and coexistence in the app UI and durable state.
- Build/provenance/packaging/test infrastructure parameterized by flavor.

**Non-goals (explicitly out)**
- Clients other than 1.12.1.5875 / 2.4.3.8606 / 3.3.5a.12340 (no 2.0.x, 2.1.x, 3.0.x–3.3.x interim builds as first-class; the flavor schema may list them later).
- Any Blizzard asset bundling (unchanged policy: user imports their own client).
- Cataclysm+ (different architecture, BLP/WDLC changes, not addressed).
- Making the Gladio/OpenGL lane qualified for TBC/WotLK (DXVK/d3d9 is the supported route for the new flavors; Gladio remains vanilla-validated only).
- Windows desktop lane feature parity is limited to what exists today (the desktop lane must not *break*; per-flavor desktop auth fixtures are in scope for the test lane only).

## 2. Invariants (apply to every stage, checked at every gate)

1. **Vanilla stays shippable.** Every stage must leave the vanilla lane green: full existing test suite + vanilla device smoke. No stage may regress vanilla behavior or storage.
2. **Fail-closed.** Every new gate (client identity, data manifests, flavor routing) must reject unknown/mismatched input with an actionable `VAL-*` code, never silently coerce.
3. **Provenance chain intact.** Every built artifact continues to be ledgered in lockfiles with source commits + digests; `tools/check_sources.py` and `tools/check_repo.py` stay authoritative and are extended, never bypassed.
4. **No cross-flavor data flow.** A flavor's client must never connect to another flavor's realm; a flavor's prepared data must never be consumed by another flavor's server. Enforced by manifest gates *and* verified adversarially at each gate (the "leak sweep", §4.6).
5. **On-device parity of safety behavior.** Supervisor start/stop order, Save & Exit ordering, crash quarantine, storage preflight: identical semantics for every flavor.
6. **Decisions are recorded.** Every design choice made during a stage is appended to the Decision Log (§8.3) in the same commit.

## 3. Target architecture (what "done" looks like)

- `schemas/flavor.json` becomes a **multi-flavor manifest**: a list of flavor descriptors, each with `id` (`vanilla`, `tbc`, `wotlk`), `game.expansion`, `client_version`, `supported_client_builds` (hash-qualified builds), server core pin, db pin, bots pin, extractor pin, DB-name set, addon interface numbers, bot conf values, corpus identifiers.
- Client identity contract: `ClientFlavor` table mapping client IDs
  - `wow-1.12.1-5875` → flavor `vanilla` (existing),
  - `wow-2.4.3-8606` → flavor `tbc`,
  - `wow-3.3.5a-12340` → flavor `wotlk`.
  Client ID replaces every bare `WOW_5875_ID` comparison; per-flavor constants live in one place.
- Server stack per flavor: submodules `native/cmangos` (classic), `native/cmangos-tbc`, `native/cmangos-wotlk` (forks of `cmangos/mangos-tbc`, `cmangos/mangos-wotlk` carrying the Pocket Realm commits), each with a matching db submodule and bots tree; `schemas/sources.json` and all lockfiles become per-flavor sets (`realm-runtime-lockfile-{vanilla,tbc,wotlk}[-abi].json`, `o11-extractor-lockfile-*` likewise).
- Databases per flavor: `vanilla*`/`classic*` (keep `classic*` for vanilla to avoid churn), `tbcrealmd/tbcmangos/tbccharacters/tbclogs`, `wotlkrealmd/wotlkmangos/wotlkcharacters/wotlklogs`; migration ledgers `database-migrations-{flavor}.json`; the DatabaseEngine routes by flavor.
- Prepared-data generations carry `clientBuild` + `cmangosFamily` and are consumed only by the matching server build.
- App: flavor is chosen at import time (a client import defines the flavor of that managed copy) and displayed everywhere; runtime sessions are per-flavor; add-on validator accepts the flavor's interface numbers; bot policy and LLM corpora are per flavor.

**Compatibility constants (targets, verified in Stage 5/S8):**

| | Vanilla | TBC | WotLK |
|---|---|---|---|
| Client ID | `wow-1.12.1-5875` | `wow-2.4.3-8606` | `wow-3.3.5a-12340` |
| PE version resource | 1.12.1.5875 | 2.4.3.8606 | 3.3.5a.12340 |
| realmd acceptance | `realmbuilds` = `5875 6005 6141` | `realmbuilds` = `8606` | `realmbuilds` = `12340` |

*(Correction C1, register §0: cmangos TBC/WotLK realmd uses the same `realmlist.realmbuilds` varchar(64) column as classic — the numeric `gamebuild` column named here previously is TrinityCore's schema. Per-flavor accepted sets are data in `schemas/flavor.json` (`realmd.accepted_builds`), mirrored on `ClientFlavor`.)*
| account.expansion | 0 | 1 | 2 |
| Addon `## Interface` | 11200 | 20400 | 30300 |
| Bot max level | 60 | 70 | 80 |
| Bot random maps | 0,1 | 0,1,530 | 0,1,530,571 |
| MPQ set | base 11 MPQs | root: `common, expansion, patch, patch-2` + `Data\enUS\` locale/speech set | root: `common, common-2, expansion, lichking, patch, patch-2, patch-3` (**no patch-4**) + locale set; tolerated aux `base-enUS`, `backup-enUS` |

*(Correction C2, register §0: the corrected TBC/WotLK root+locale sets — not "vanilla-11 + expansion". Verified against checksum corpora and real client load logs. Exact per-file sets are recorded at S5 qualification with exact-set semantics; an unknown `patch-*.mpq` is a `VAL-04` naming the file.)*
| Graphics route | DXVK (Gladio validated) | DXVK only | DXVK only |

## 4. Operating protocol — multi-agent red-team round-robin ("till perfection")

Every stage ends with a review gauntlet run by an independent **panel** of reviewer agents. The panel is adversarial: its job is to *kill* the stage exit, not to approve it. No stage advances on a schedule — only on convergence.

### 4.1 Panel roles (one agent per role per round)

| Role | Charter — what this agent is hunting for |
|---|---|
| **R1 Architect / Consistency** | Flavor leakage: bare build constants, wrong-flavor pins in lockfiles, single-flavor assumptions in new code, contract drift between `flavor.json` and code. Checks the stage against §3 target architecture. |
| **R2 Adversarial Breaker** | Writes and runs negative tests: wrong-flavor client vs. wrong-flavor server, corrupted/mismatched manifests, doctored MPQ sets, wrong DB names, stale generations. Goal: make the system do something wrong **without** a clean rejection. |
| **R3 Security & Provenance** | Hash gates fail closed; provenance digests match; no unsigned/foreign code path; no new network surface; secrets/redaction rules hold; lockfile asserts can't be trivially bypassed; no PII in logs. |
| **R4 Performance & Resources** | Device budgets: storage planner estimates vs. reality, migration apply time, world boot time, memory of mangosd-tbc/wotlk + MariaDB + client on RP6-class hardware, mmap/tile counts, thermals during soak, admission-controller behavior. |
| **R5 Content & Gameplay Fidelity** | DB content correctness for the flavor (quests/spawns/loot sanity), era lint correctness (no anachronisms *and* no false bans), bot behavior sanity, DBC/extractor output validity, addon compatibility claims. |
| **R6 UX, Docs & Tests** | User-facing copy per flavor (no "1.12" hard-coded text), error messages actionable (`VAL-*`), wiki/README updates truthful, new tests meaningful and in CI, fixtures present for the flavor. |

Roles R1–R6 are **read-only reviewers with test-run rights**: they may run builds/tests/scripts and propose tests, but they do not edit production code. Findings are fixed by the implementation agent, then re-reviewed.

### 4.2 Round algorithm (per stage)

```
round n (n = 1..):
  1. Dispatch all six reviewer agents in parallel with:
       - stage charter + End State checklist,
       - the full diff set since stage start,
       - evidence pack (build logs, test output, device captures),
       - round order rotation (round 1: R1→R6; round 2: R6→R1; alternate).
  2. Each reviewer returns findings: {id, severity, evidence, repro, proposed test}.
  3. Deduplicate; classify S0–S3 (below).
  4. Implementation agent fixes all S0/S1; resolves or writes recorded waivers for S2;
     triages S3 (fix or batch).
  5. Re-run stage's full verification suite + all tests proposed by reviewers.
  6. Convergence check (4.4). If met → stage exits. Else next round.
```

### 4.3 Severity model

| Sev | Meaning | Gate rule |
|---|---|---|
| **S0** | Breaks an invariant (§2): vanilla regression, cross-flavor data flow, fail-open gate, provenance bypass, data loss | Must fix. Blocks exit. |
| **S1** | Major correctness: wrong-flavor behavior reachable by a user, crash, mis-qualification, misleading gate | Must fix. Blocks exit. |
| **S2** | Minor correctness / UX harm / budget miss / doc untruth | Fix or recorded waiver with rationale. Blocks exit if open. |
| **S3** | Nit, style, wording, test polish | Fix or batch; does not block. |

### 4.4 Convergence gate — the operational definition of "perfection"

A stage exits only when **all** of the following hold:

1. **Two consecutive full rounds** with **zero new S0/S1** findings and no open S2.
2. Every reviewer confirms their charter checklist ran to completion (no "not examined").
3. Every finding from every round has: a fix (verified by re-running its repro), or a recorded waiver in the Decision Log with reviewer sign-off.
4. All reviewer-proposed tests are merged and green in CI.
5. The **leak sweep** (§4.6) is clean for the stage's surfaces.
6. Hard cap: **6 rounds**. If not converged by round 6, the stage does **not** exit with waivers — it is *redesigned* (architecture changed) and the round counter resets. Perfection is the only exit; the cap forces redesign over patching, not acceptance.

### 4.5 Evidence pack (per stage, archived with the exit)

- Build logs (host + Android ABIs) with lockfile diff.
- Full test-suite output (repo tests + androidTest + reviewer tests).
- Device evidence: `tools/capture_rp6.py` captures of import → prep → launch → play → Save & Exit for the flavor; soak log (≥ 2 h world uptime with default bot population; boot-time and memory samples).
- Updated `schemas/*.json` provenance (commits + digests).
- Decision Log entries for every non-obvious choice + all waivers.

### 4.6 Leak sweep (mechanical check run every round)

Scripted greps + runtime assertions:
- `grep` for banned bare constants outside their owning table: `5875`, `6005`, `6141`, `1.12`, `11200`, `8606`, `20400`, `12340`, `30300`, `classicrealmd`, `classicmangos`, `classiccharacters`, `classiclogs`, `expansion.mpq`, `lichking`.
- Runtime assertions (androidTest): TBC client import rejected while `tbc` flavor unqualified; TBC prepared data rejected by vanilla server manifest gate and vice versa; TBC client pointed at vanilla realm fails login with clean error and does not create an account row; migration ledger for flavor X never applies to flavor Y's database.
- The sweep itself is versioned (`tools/leak_sweep.py`) and grows each stage; it runs in CI forever after.

## 5. Stage plan

### Stage gate table

| Stage | Name | Flavor scope | Depends on | Character |
|---|---|---|---|---|
| S0 | Flavor foundation | all (platform) | — | one-time |
| S1 | Provenance & build parameterization | all (platform) | S0 | one-time |
| S2 | Server core bring-up | per flavor | S1 | runbook |
| S3 | Bots & LLM patches | per flavor | S2 | runbook |
| S4 | Database content & migrations | per flavor | S2 | runbook |
| S5 | Client identity & import | per flavor | S1 | runbook |
| S6 | Data preparation (o11) | per flavor | S2, S5 | runbook |
| S7 | Client runtime & UX | per flavor | S5 | runbook |
| S8 | Accounts, auth & networking | per flavor | S2, S4 | runbook |
| S9 | Coexistence & flavor selection | all | RA+RB through S8 | one-time |
| S10 | Program qualification & release | all | S9 | one-time |
| **RA** | **Runbook instantiation: TBC** | tbc | S0–S1 | executes S2–S8 for `tbc` |
| **RB** | **Runbook instantiation: WotLK** | wotlk | S0–S1, lessons from RA | executes S2–S8 for `wotlk` |

S2–S8 are defined **once** as a repeatable runbook; RA and RB execute them. Within a run, S2→S3→S4→S6→S8 are the critical path; S5 and S7 can proceed in parallel with S2–S4 once S1 lands.

---

### S0 — Flavor foundation (platform)

**Goal:** make "flavor" exist end-to-end as a type, with the vanilla lane re-expressed in terms of it and *zero behavior change*.

**Work items**
1. Extend `schemas/flavor.json` to a multi-flavor manifest (list of flavor descriptors; §3 fields). Add a validator tool `tools/check_flavor.py` consumed by `tools/check_repo.py` (the gate CI runs; see D12).
2. Android: replace the `WOW_5875_ID` singleton (`android/app/src/main/java/com/pocketrealm/client/ClientRuntime.kt:256`) with a `ClientFlavor` contract object (id, flavor, build, version string, interface numbers, DB-name set, manifest gates). Keep `WOW_5875_ID` as a derived constant so all existing comparisons keep compiling while they are migrated one module at a time.
3. Introduce per-flavor artifact *naming convention* (lockfiles, ledgers, DB names, data dirs, generation pointers) and a resolver: given flavor → artifact names. DB naming decision: keep `classic*` for vanilla; `tbc*` / `wotlk*` sets for the new flavors (Decision Log entry).
4. Threading audit: enumerate every module that takes a client id / DB name / family string (ManagedClientStore, ClientGenerationStore, DataPreparationStore, PreparedDataStore, WineRuntimeStore, ServerRuntimeFiles, AndroidRuntimeBackend, IntegratedClientDisplay, InGameSettingsEditor, AddonArchiveValidator, BotProfiles) and convert each to take a `ClientFlavor` parameter. Mechanical, module by module, tests after each.
5. `schemas/flavor.json` gains `supported_client_builds` entries per flavor once S5 qualifies hashes; until then each new flavor is `"qualified": false` and import rejects it — but the schema, plumbing, and UI affordances exist now.

**End State (all must hold)**
- [ ] `flavor.json` validates as multi-flavor; `tools/check_flavor.py` gates the build; only `vanilla` is qualified.
- [ ] No production Kotlin/Python file outside the `ClientFlavor`/flavor-registry definitions contains a bare build constant (leak sweep green).
- [ ] All existing tests green; new unit tests cover the resolver and every migrated module boundary.
- [ ] Vanilla device smoke identical to pre-change baseline (capture diff reviewed by R4).
- [ ] Red-team gauntlet converged (§4.4).

---

### S1 — Provenance & build system parameterization (platform)

**Goal:** every build lane can build *any* flavor's server stack from pinned sources, with per-flavor lockfiles, and the pre-existing provenance split is repaired.

**Work items**
1. **Repair first:** reconcile the o11 extractor provenance split — `schemas/o11-extractor-lockfile{,-arm64-v8a}.json` and `DataPreparationStore.kt:412` record commit `c096bada…` while `tools/build_o09_realm_runtime.py:37` pins `ce83805d…`. Rebuild extractors from `ce83805d`, update lockfiles + app constant, add a CI check that they can never diverge again.
2. `schemas/sources.json`: schema extension for per-flavor entries (`cmangos-core-tbc`, `cmangos-db-tbc`, `playerbots-tbc`, … same for wotlk); `tools/check_sources.py` learns per-flavor submodule prefixes (`native/cmangos-tbc`, `native/classic-db-tbc`, `native/playerbots-tbc`, …).
3. `scripts/build_native.py`: add `--flavor {vanilla,tbc,wotlk}`; stage list, submodule source dir, cmake cache dir, and output staging all resolve through the flavor. Playerbots mirror step (`ensure_playerbots()`) parameterized to the flavor's bots tree.
4. `tools/build_o09_realm_runtime.py` (realm runtime lane): flavor parameter; `CMANGOS_COMMIT` assert per flavor; overlay table split into (a) expansion-neutral overlays (SQLite backend injection) and (b) **per-flavor overlay definitions** that must be re-derived per branch (see S2).
5. `tools/build_o11_extractors.py`: flavor parameter, per-flavor staging dirs (`native/.build-o11-$abi-$flavor`), per-flavor lockfiles.
6. `tools/stage_database_migrations.py` / `tools/seed_realm_db.py`: flavor parameter; per-flavor ledger names and expected revisions.
7. `schemas/database-migrations.json` → per-flavor ledgers; `schemas/sqlite-seed-baseline.json`, `seed-augment` provenance likewise. Gradle packaging gate learns the set.
8. Migration strategy: old single-file names remain as aliases for vanilla during a deprecation window; CI fails once all consumers migrated.

**End State**
- [ ] `python scripts/build_native.py --flavor vanilla` reproduces byte-identical staging vs. today (lockfile diff empty) — regression proof.
- [ ] `--flavor tbc` and `--flavor wotlk` resolve all pins and fail **loudly with actionable messages** where a source is not yet registered (expected at this point: trees not forked yet) — fail-closed verified by R2.
- [ ] Provenance split repaired; divergence CI check in place.
- [ ] All lockfile/ledger renames complete; no consumer reads the unqualified names.
- [ ] Gauntlet converged.

---

### S2 — Server core bring-up *(runbook: executes once per flavor)*

**Goal:** the flavor's CMaNGOS core (realmd + mangosd) builds as Android native libs with the Pocket Realm lifecycle/SQLite/interaction overlays, and boots on host + device against its own databases.

**Work items (per flavor)**
1. **Fork & pin.** Fork `cmangos/mangos-tbc` (or `cmangos/mangos-wotlk`) → `puzzled-pancake/mangos-tbc` (`-wotlk`), branch `pocket-realm`. Register in `.gitmodules`, `sources.json`, database-migrations source_commits.
2. **Port the five vanilla fork commits**, re-derived per branch (source text differs):
   - PlayerBots FetchContent SOURCE_DIR pin;
   - Bionic `<sys/timeb.h>` skip (and any other Bionic/NDK fixes the branch needs — budget for new ones: the tbc/wotlk branches have newer Boost/`std::` usage);
   - `POCKET_EMBEDDED` embeddable lifecycle facade (O04) — adapt to that branch's `Master.cpp`/realmd main;
   - internal-reference scrub commit equivalent;
   - depth-fix/HEAD pinning equivalent.
3. **Re-derive the o09 overlay set** for the branch: `db-sqlite-connection-hardening` (DatabaseSqlite.* replacement — mostly portable, re-anchor), ahbot gates, `runtime-dialect-*` overlays in `ObjectMgr.cpp`/`libanticheat.cpp`, `PocketRealmInteraction.cpp` (re-anchor the hook point; re-test the "Interact Target" semantics per client).
4. **realmd build acceptance (correction C1):** TBC/WotLK upstream realmd uses the *same* `realmlist.realmbuilds` varchar(64) column as classic (register §0 C1 — the numeric `realmlist.gamebuild` named here previously is TrinityCore's schema; no `gamebuild` field may exist anywhere). Seed `realmbuilds='8606'` (tbc) / `'12340'` (wotlk); the sets are data in `schemas/flavor.json` (`realmd.accepted_builds`), mirrored on `ClientFlavor`. Drive `realmd.sql` base handling, the seed row, `realmd_runtime.cpp` diagnostics, and `ServerRuntimeFiles.kt` conf generation from that one source, per flavor.
5. **Build lanes:** Android NDK build via S1's `--flavor`; Windows/MSVC lane (`tools/build_win_realm_runtime.py`) per flavor; per-flavor `realm-runtime-lockfile*` written and asserted.
6. **Host smoke** (desktop lane): mangosd+realmd boot against seeded DB (S4-lite: base sql only), `account create`, world ping, bot module loads (stub ok).
7. **Device smoke:** RP6 boot of the flavor's realm with minimal world content; supervisor ordering; Save & Exit; crash-tracer still wired.

**End State**
- [ ] `git submodule status` shows all per-flavor pins; `check_sources.py` green for all flavors.
- [ ] Android + Windows lane builds green for the flavor; lockfiles written with matching commits.
- [ ] On-device: world reaches "ready" with the flavor's DBs; 30-minute stable session; clean Save & Exit; crash-quarantine behavior verified by R2 (kill -9, corrupted DB reject).
- [ ] realmd accepts exactly the flavor's build (adversarial: vanilla client → TBC realm must fail login cleanly; verified both directions).
- [ ] Overlay provenance digests recorded; gauntlet converged.

---

### S3 — Bots & LLM patches *(runbook)*

**Goal:** a bots tree for the flavor's branch carries the Pocket Realm LLM patch set, and bot population policy is flavor-correct.

**Work items (per flavor)**
1. **Source resolution (correction C4 — resolved; no kill-switch).** Upstream `cmangos/playerbots` master is multi-expansion (`MANGOSBOT_ZERO/ONE/TWO`, per-expansion conf dists, CI matrix), and the Pocket Realm fork pin `7e2cd2fb` is post-multi-expansion — **one bots pin serves all flavors**. The former kill-switch premise ("no TBC/WotLK upstream; ship without bots" product call) is void. S3 is: port the LLM patch set to the flavor's bots API, select conf/SQL per flavor, and disable the in-tree legacy `PlayerBot` module; the fork/branch topology (`puzzled-pancake/playerbots` single `pocket-realm` branch vs per-branch branches) is recorded as H3-1's D-new decision (bots-llm.md:13). **Do not proceed past S3 exit on an unpinned tree** (unchanged — the pin now exists; drift/build failure on newer branches is the residual risk, §8.1 risk 1).
2. Mirror into `native/playerbots-tbc|wotlk`, submodule + `sources.json` + mirror step in `build_native.py`.
3. Port the ~25 LLM patch files (`native/patches/playerbots/`) to the flavor's bots API: LlmBridge/Chatter/Memory/Persona/Tools/Truth, banter core, Llama runtime hook. Keep a single source of truth where possible: factor genuinely flavor-independent logic into shared headers under `native/patches/playerbots-common/` (Decision Log entry), leaving thin per-flavor adapters.
4. **Era lint re-curation:** rebuild the anachronism ban list per flavor (vanilla: current list; TBC: ban WotLK+ concepts — death knights, Northrend, lich king, etc., allow Outland/flying; WotLK: allow through WotLK concepts, ban Cataclysm+). Keep the rule that a term legitimate in *earlier* content is never banned. Add corpus-driven tests per flavor.
5. Flavor-correct bot policy values in `BotProfiles.kt`/`BotPopulationPolicy.kt`: max level (60/70/80), random maps (+530/+571), characters-per-bot-account per the flavor's factory, POI rumor list per flavor (zones of that expansion), chat line cap per the flavor's client limit.
6. aiplayerbot conf: `ServerRuntimeFiles.kt` emits per-flavor conf; **fail-loud config check** — unknown keys for the flavor's bots build must abort staging, not silently drop (R2 verifies). **Correction C9 constraint:** the conf-matrix generator (`tools/generate_conf_matrix.py`) and every consumer must not emit or expect `VoiceChat*` keys — mangos-wotlk's VoiceChat is a 46-line inert stub (swallows the opcode, no conf keys; classic has none), recorded as data under the flavor's `conf_matrix.excluded_keys` (the F2-8 self-documenting-exclusion pattern) with the honest "VoiceChat UI present but inert" docs note (Decision Log entry).
7. LLM SQL tables: `native/llm/sql/*`, `sql/migrations/*` applied per flavor (via S4 ledger); gossip pool seeded per flavor; prompt pack identity string per flavor ("World of Warcraft: The Burning Crusade" / "…: Wrath of the Lich King"); lore corpus per flavor (`lore_cards_v112.jsonl` → `v204`/`v303` variants or shared corpus + era lint — decision in-stage).

**End State**
- [ ] Bots tree pinned with license recorded; build green with playerbots ON for the flavor on both lanes.
- [ ] Bots spawn, path, level to cap, and chat via the LLM lane on-device; 1-hour bot soak without tick-time regression beyond the admission controller's budget (R4 evidence).
- [ ] Era lint: per-flavor unit tests prove no anachronisms and no false bans (R5 curates a probe list).
- [ ] Conf staging fails loudly on unknown keys; gauntlet converged.

---

### S4 — Database content & migrations *(runbook)*

**Goal:** the flavor's world DB seeds and migrates through the full provenance-verified pipeline, on MariaDB and on the SQLite lane.

**Work items (per flavor)**
1. Submodule the upstream DB (`cmangos/tbc-db` / `cmangos/wotlk-db`) → `native/classic-db-tbc|wotlk`; register pins.
2. Extend `tools/stage_database_migrations.py` selection rules per flavor: base realmd/characters/logs from the flavor's core `sql/base/`; `Full_DB` archive; DB updates + instances; core `sql/updates` above the DB's installed revision; `sql/base/dbc/*`; scriptdev2; **ACID as `[ACID]`-tagged commits pulled from the flavor DB repo's history** (correction C5 — there is no `acid_*.sql` file to select for tbc/wotlk; classic keeps its in-tree `ACID/acid_classic.sql`), digest-recorded in the ledger's `source_commits`; bots SQL; Pocket Realm LLM SQLs. Per-flavor expected revisions from the flavor's `revision_sql.h`.
3. `tools/seed_realm_db.py` + `sqlite_seed_translator.py`: flavor parameter; per-flavor DB names (S0 resolver); row-count sanity floors per flavor (new numbers measured, not guessed).
4. New seed-augment capture for the flavor (bot equip/rnditem caches) bound to the flavor's `manifest_sha256` tripwire.
5. `schemas/sqlite-seed-baseline.json` per flavor; `DatabaseEngine.kt` routes ledger by flavor (marker files per flavor DB set); fail-loud `DB-REVISION` behavior preserved per flavor.
6. Character/realmd schema divergence handling (e.g. realmlist `gamebuild`, per-expansion columns in characters) — the ledger carries the flavor's own base SQL, so this falls out; the app's diagnostics that `SELECT` specific columns (`world_runtime.cpp` account info) are checked against the flavor's schema.

**End State**
- [ ] `database-migrations-{flavor}.json` built with full digest chain; `check_repo` + packaging gate green.
- [ ] MariaDB lane: full migration apply on-device within a measured budget (record number; R4 gate vs. storage/time); snapshots + verified backups round-trip for the flavor.
- [ ] SQLite lane: seed green with per-flavor baseline; fail-loud on stale manifest verified.
- [ ] World boots from the seeded DB; content sanity pass (R5): starting zones populated, trainers/class spells present to the flavor's level cap, no error-spam in mangosd log (define numeric threshold).
- [ ] Gauntlet converged.

---

### S5 — Client identity & import *(runbook)*

**Goal:** a genuine flavor client imports, verifies, and publishes as a managed copy with the flavor's identity — and every wrong client is rejected with an actionable code.

**Work items (per flavor)**
1. Qualify the genuine client: hash the `WoW.exe` (record pristine SHA-256, **cross-checked against the known pristine MD5s** — correction C6: 12340 `45892bde…`, 8606 `57c5c030…`, anzz1/wow-client-checksums; so S5.1 is SHA-256 compute + MD5 cross-check, not open-ended verification), version-resource tuple (`(2,4,3,8606)` / `(3,3,5,12340)`), PE32 machine check (unchanged), file size; register in `ClientFlavor` + `flavor.json.supported_client_builds` (entries carry exe size + SHA-256 + MD5 provenance). Size reality (C6): 12340 `WoW.exe` ≈ 12.6 MB ≈ 2.6× vanilla (~4.8 MB) — **not** "~10×" — and fits `SafClientScanner.MAX_EXE_BYTES = 32 MiB`; the cap needs no raise.
2. Per-flavor **MPQ allowlists re-derived with exact-set semantics** (correction C2): TBC root = `common, expansion, patch, patch-2` plus the `Data\enUS\` locale/speech set — *not* vanilla-11 + expansion; WotLK root = `common, common-2, expansion, lichking, patch, patch-2, patch-3` (**no `patch-4`**) plus the locale set, with tolerated auxiliaries `base-enUS`/`backup-enUS`. Derive the exact per-file sets from the qualified client; the set is data in `ClientFlavor` (`requiredMpqPaths` + `toleratedAuxiliaryMpqs`), not code. An MPQ outside the required+tolerated set — e.g. an unknown `patch-4.mpq` — is a `VAL-04` naming the file (§2.2 H2-2/H2-9's treatments, client-data-runtime.md:23-52). MPQ magic check reused as-is.
3. `ClientPeIdentity.kt`: replace `(1,12,1)` match with per-flavor tuples (accept any qualified tuple; reject others with `VAL-02` naming the supported builds).
4. Deduplicate the scanner constants (`EXPECTED_VERSION`, `REQUIRED_MPQS` in `SafClientScanner.kt:170–174` and `ArchiveClientScanner.kt:128–132`) into the `ClientFlavor` table.
5. `ImportExtractionPolicy.kt` root allowlists per flavor: extend `ROOT_FILES`/`ROOT_DIRECTORIES` with the flavor's root layout (TBC/WotLK: `BackgroundDownloader.exe`, `Launcher.exe`, `WowError.exe`, `dbghelp.dll`, `ijl15.dll`, `Patch.dll`?, `unicows.dll`, `divxdecoder.dll` — derive from the qualified client; WotLK adds `Data\enUS\` locale subfolder semantics — the scanner must accept clients whose MPQs live in `Data\` or `Data\<locale>\` per flavor).
6. Locale handling: keep infer-not-gate for now (VAL-05), but record the observed locale set per flavor; `ManagedClientImporter` publish identity stops hard-coding `"enUS-flat-classic"` (flavor + observed locale).
7. Storage planner: per-flavor `extractedEstimate` (vanilla 4 GiB; measure TBC/WotLK in S6 and re-derive), `maxTotalBytes` per flavor (WotLK ≥ 35 GiB total workspace — confirm against RP6-class storage reality), per-file cap review vs. `lichking.mpq` (~1.2 GB, fits 8 GiB).
8. Debug/host fixtures: `SyntheticClientArchives.kt` per-flavor synthetic clients; `tools/stage_o07_client.py` and `tools/install_client_windows.ps1` per-flavor constants; `ArchiveFormatSniffer` VAL-11/VAL-12 copy per flavor.
9. All `WOW_5875_ID` gates (`ManagedClientStore.kt:59,93–112`, `ClientGenerationStore.kt`, `WineRuntimeStore` prefix path `wow5875` → per-flavor prefix segment, `AndroidRuntimeBackend.CLIENT_BUILD_ID`, `IntegratedClientDisplay`, `InGameSettingsEditor`) routed through `ClientFlavor`.

**End State**
- [ ] Genuine flavor client (enUS) imports end-to-end on device: folder lane + archive lane (zip/7z/rar/Inno where applicable), resumable staging verified.
- [ ] Negative matrix green (R2 authored): vanilla client into tbc-qualified app accepted (multi-flavor app) but *flavor routing* correct; wrong-build clients (2.0.x, 3.0.x, 3.2.2) rejected; doctored MPQ set rejected; oversized/malformed MPQ rejected; each with its VAL code.
- [ ] Storage preflight correct per flavor (low-headroom warning math reviewed by R4).
- [ ] Manifest identity: generation carries flavor + build + locale; all consumers verify via resolver.
- [ ] Gauntlet converged.

---

### S6 — Data preparation (o11) *(runbook)*

**Goal:** on-device extraction of DBC/maps/vmaps/mmaps from the flavor's client, gated and consumed by the matching server build.

**Work items (per flavor)**
1. Build the flavor's extractors (`ad`, `vmap_extractor`, `vmap_assembler`, `MoveMapGen`) from the flavor's cmangos fork via S1's lane; port `o11-cmangos-safe-mpq-listfile.patch` (re-anchor); per-flavor lockfiles; packaging into APK jniLibs per flavor (named per flavor: `libpocket_ad_tbc.so` … or selected at runtime — decision in-stage; naming per flavor is simpler and fail-closed).
2. `DataPreparationStore`: per-flavor invocation, validation floors re-measured (MIN_DBC/MIN_MAP_TILES per flavor — WotLK has far more tiles), manifest carries flavor + `clientBuild` + family; provenance records the flavor's extractor commit.
3. `PreparedDataStore` consumer gate: flavor + build + family must match the server session's flavor; mismatch = hard refuse (R2 verifies cross-flavor refusal both ways).
4. **Size/time qualification on RP6:** measure extraction wall-clock, peak storage, and mmaps size; decide per flavor whether on-device-only prep is acceptable or the desktop-assisted lane (`tools/win_prepare_data.py` / `tools/prepare_o11_bot_data.py`) must be qualified as an official alternative for WotLK. **The go/no-go criteria explicitly include the client-side 2 GB VA ceiling (correction C7: WotLK 3.3.5a is NOT large-address-aware)** — Dalaran client RSS/VA pressure against that ceiling is part of the measured input (feeds DP12 and the graphics-preset default, W1-8). **This is a named go/no-go decision point** (Decision Log + user sign-off if desktop-assisted becomes required for WotLK).
5. Bot-data host mirror per flavor (maps list per flavor).

**End State**
- [ ] Extractors built + locked per flavor; APK packaging gate green.
- [ ] On-device prep completes for the flavor's client with measured time/storage recorded; manifests gate correctly in both directions (cross-flavor refusal tested).
- [ ] mangosd launches with `DataDir` = flavor's generation; world boots with no DBC/map errors (numeric error threshold defined and met).
- [ ] Go/no-go decision recorded for WotLK prep strategy; gauntlet converged.

---

### S7 — Client runtime & UX *(runbook)*

**Goal:** the flavor's client launches, renders, auto-logins, and is manageable under the same supervision and safety behavior as vanilla.

**Work items (per flavor)**
1. **Graphics route policy:** DXVK/d3d9 only for the flavor (`ClientRuntimeContract.armClientArguments()`); `opengl`/`virgl` lanes disabled per flavor with clear UI copy (Gladio validated for vanilla only). DXVK log attestation wording verified against the flavor's `WoW.exe` (exe name unchanged, so `isArmDxvkLogAttested` should hold — verify on device).
2. **`Config.wtf` policy per flavor:** `ManagedConfigPolicy` enforced-keys set re-derived (TBC/WotLK cvar names differ; `gxApi "D3D9"`, `d3d9ex`, `gxResolution`; WotLK's sound cvar family differs from vanilla's — the vanilla catalog's "WotLK-era inert" comments become live keys). Per-flavor SAFE_CONFIG sets; float-echo format verified per client (`ConfigWtfCodec` — the 5875 float format pin needs per-build verification).
3. **Settings & bindings catalogs:** build `WowTbcSettingsCatalog` / `WowWotlkSettingsCatalog` and binding catalogs: capture from the qualified client (WTF write-out + `Bindings.xml`/`binds` read), integrity-hash like vanilla's (109 settings/211 bindings for vanilla → measure per flavor; WotLK is much larger). `InGameSettingsFiles.kt` WTF layout per flavor (correction C8: **TBC is already 3-level** — per-realm/per-character `SavedVariables` — so the rework covers TBC's SavedVariables too, with H4-5's scope split accordingly (§2.4 H4-5, ux-addons-controls.md:137-161); genuinely WotLK-only is the char-level cache-file set `config-cache.wtf`, `bindings-cache.wtf`, `macros-cache.txt`, `layout-local.txt`, `chat-cache.txt`, and W1-4's `config-cache.wtf` read path (client-data-runtime.md:493-506) is scoped to those WotLK-era files; the single-account-file assumption is vanilla-only).
4. **vanilla-tweaks per flavor:** feature-gated off for tbc/wotlk at S7 start (honest UI copy: "not available for this flavor"). **The former "LAA is built into later clients? verify" is answered (correction C7): WotLK 3.3.5a is NOT large-address-aware (2 GB VA).** An LAA variant for 12340 would be a byte-patched exe requiring hash-pinned qualification (§2.2 H2-3's option branch, client-data-runtime.md:100-113), else the RAM admission gate (`minClientFreeMemoryMiB` preflight, H2-3 item 3). Optional later work item (recorded, not blocking): widescreen FoV equivalents exist in community patchers — out of scope unless a maintained, license-compatible patcher is found.
5. **Auto-login:** per-flavor login-window topology (`Build5875LoginTopology` → `Build8606LoginTopology`, `Build12340LoginTopology`): capture window classes/geometry for the flavor's login screen (x86 and ARM lanes differ — both qualified), tap choreography re-calibrated (TBC/WotLK login screens differ from vanilla's), IME char map verified per client.
6. **Addons per flavor:** `AddonArchiveValidator` acceptedInterfaces per flavor ({11200} / {20400} / {30300}; VoiceOver data policy per flavor — AI_VoiceOver has per-expansion artifacts, extend `VoiceOverReleaseResolver`); AndroidPort addon: port to the flavor's UI API or exclude per flavor with honest copy (decision in-stage); catalog `clientTarget` metadata promoted to first-class per-flavor catalogs.
7. **UI copy sweep:** every user-visible "1.12"/"Vanilla" string (`FirstRunTutorial`, `HomeScreen` chip, `SettingsScreen` tweaks copy, `AddonsScreen`, `ControlsScreen`, `DiagnosticsScreen` flavor line) becomes flavor-aware.
8. **Wine/prefix:** per-flavor prefix path segment (`wow5875` → `wow8606`/`wow12340`); registry/config writes re-verified; `WineRuntimeStore` authorization checks routed through `ClientFlavor`.

**End State**
- [ ] Flavor client reaches the character screen on RP6 via DXVK route (x86 lane smoke also green); 2-hour play soak with graphics/audio stable; DXVK attestation log proof archived.
- [ ] Auto-login lands on the character screen unattended on both lanes, 5/5 attempts.
- [ ] In-game settings editor round-trips the flavor's cvars/bindings; WTF layout correct per flavor (R2 corrupts files → clean recovery, no silent loss).
- [ ] Addon lifecycle for the flavor: install, project, rollback; wrong-interface addons rejected with per-flavor copy.
- [ ] Disabled features (tweaks, GL lane) are visibly and honestly surfaced; leak sweep green; gauntlet converged.

---

### S8 — Accounts, auth & networking *(runbook)*

**Goal:** accounts are flavor-correct (expansion flag), auth handled by the flavor's realmd, desktop test fixtures updated, LAN mode safe per flavor.

**Work items (per flavor)**
1. **Expansion flag:** add a relay op (`account set addon <n>`) invocation path in `native/realm-runtime/src/world_runtime.cpp` alongside `account create`; app sets expansion = 1 (tbc) / 2 (wotlk) at account creation; vanilla stays 0. `UserAccountStore` schema gains a flavor field (migration for existing data — vanilla stays vanilla).
2. **Auth fixtures:** desktop lane pins (`AuthGate.kt`, `RpWowClient.kt` SRP6 frames hard-coding build 5875) → per-flavor frame layouts; the auth-challenge opcode is **one shared 0x1EC constant** in all three branches (correction C3 — no per-expansion opcode parameterization; the plan's earlier per-expansion opcode pins were wrong), so this item parameterizes only the per-flavor *frame descriptors* (build-bytes width, proof-frame length — verified against each branch's `AuthSocket.cpp`) and the harness's frame pins; `tools/rp_harness/probe_world_auth.py` asserts the shared 0x1EC constant and fails loud on any other.
3. **realmd protocol acceptance:** verify the flavor's realmd rejects other builds (both directions vs. vanilla) with clean client-side errors; warden module differences noted (classic's wardenwin scans are classic-specific — the flavor's branch ships its own).
4. **Networking/LAN:** LAN host projection per flavor (no port/protocol change expected; verify realm list advertises the flavor's `realmbuilds` accepted-build set (correction C1) so *remote* clients of the right flavor can connect and wrong ones cannot — a new, deliberate behavior to test explicitly).
5. Credentials/first-login flow: `SinglePlayerCredentialStore` unchanged; auto-login credentials injection verified in S7; `FIRST_LOGIN_UVAR_DEFAULTS` per flavor (WotLK cvar set).

**End State**
- [ ] Account row shows correct `expansion` per flavor (DB-verified); gmlevel flow intact.
- [ ] Auth matrix green: {vanilla, tbc, wotlk} client × {vanilla, tbc, wotlk} realm — only matching pairs authenticate; every mismatch fails cleanly (wire-level fixtures per flavor in the desktop harness).
- [ ] LAN mode: matching flavor connects; mismatched flavor refused at realmlist/auth; documented.
- [ ] Gauntlet converged.

---

### S9 — Coexistence & flavor selection (platform)

**Goal:** one app hosts all three flavors simultaneously, safely, with clear UX.

**Work items**
1. Managed-client registry: multiple managed copies (one per flavor at minimum), each pinned to its flavor; Home screen flavor card per imported client; import flow offers "add another flavor".
2. Per-flavor durable state namespaces: data dirs, generation pointers, DB sets, prefix paths, addon generations, backups/snapshots, logs, diagnostics bundles. Storage accounting per flavor (Settings shows per-flavor usage).
3. Session policy: one realm at a time (device budget) — flavor switching = full Save & Exit of the previous session then boot of the next; supervisor journal entries carry flavor.
4. Backup/restore and app-update coordinator per flavor (ledger sets, lockfile checks across all flavors at update time).
5. Diagnostics: per-flavor flavor line (from `flavor.json`), per-flavor logs, support bundle includes flavor map.
6. First-run tutorial: multi-flavor copy; per-flavor "what you need" text.

**End State**
- [ ] All three flavors installed side-by-side; alternating sessions across flavors 10× with zero cross-flavor state bleed (R2's marquee adversarial scenario: interleave sessions, kill mid-switch, verify journals/backups/DBs stay per-flavor consistent).
- [ ] Storage planner totals correct across flavors; low-storage path tested (deny second import with actionable message).
- [ ] Docs/wiki rewritten for multi-flavor; gauntlet converged.

---

### S10 — Program qualification & release

**Goal:** the multi-flavor app is release-qualified as a whole.

**Work items**
1. **Device matrix:** RP6 (primary), one additional Adreno device, one emulator AVD lane, Windows desktop lane (regression only). Per flavor × per device: import → prep → play 2 h → Save & Exit → backup/restore.
2. **Soak & stability:** 8-hour weekend soak per flavor (bots at default population), memory/thermal sampling via `tools/battery_sampler.py`; admission controller behavior under the flavor's larger worlds (WotLK especially).
3. **Upgrade paths:** app update over an existing install with all three flavors present (ledger/lockfile verification across flavors); database migration re-apply idempotence.
4. **Docs & wiki:** all pages de-vanilla-fied where wrong, per-flavor pages added (Choosing-a-Vulkan-Driver caveat for tbc/wotlk, Game-Files-and-Import per-flavor sizes, Add-ons per flavor).
5. **Release engineering:** `schemas/flavor.json` shipped qualified for all three; release notes; versioned as the multi-flavor milestone (e.g. 0.110.0).

**End State (program Definition of Done)**
- [ ] Full matrix green; soaks archived; no open S0–S2 program-wide.
- [ ] Vanilla regression suite green (invariant 1 held end-to-end).
- [ ] Leak sweep (grown through all stages) green in CI.
- [ ] All Decision Log entries and waivers reviewed and closed or accepted.
- [ ] Final red-team round across the whole program (not just one stage) converged.

---

## 6. Per-flavor parameter master table (the runbook's input data)

| Parameter | vanilla | tbc | wotlk | Owner stage |
|---|---|---|---|---|
| Client ID / build | `wow-1.12.1-5875` / 5875 | `wow-2.4.3-8606` / 8606 | `wow-3.3.5a-12340` / 12340 | S5 |
| Pristine exe SHA-256 | `b4756d38…` (existing) | measure; MD5 cross-check `57c5c030…` (C6) | measure; MD5 cross-check `45892bde…` (C6); size ≈ 12.6 MB ≈ 2.6× vanilla — fits the 32 MiB scanner cap | S5 |
| MPQ set | 11 base MPQs | root `common/expansion/patch/patch-2` + `Data\enUS\` locale/speech set (C2) | root `common, common-2, expansion, lichking, patch, patch-2, patch-3` — no `patch-4` — + locale set; tolerated aux `base-enUS`/`backup-enUS` (C2) | S5 |
| Server core | mangos-classic fork @ ce83805d | mangos-tbc fork @ TBD | mangos-wotlk fork @ TBD | S2 |
| DB content | classic-db @ be1a5206 | tbc-db @ TBD | wotlk-db @ TBD | S4 |
| Bots tree | cmangos/playerbots fork @ 7e2cd2fb (multi-expansion master; one pin serves all flavors — correction C4) | same pin @ 7e2cd2fb | same pin @ 7e2cd2fb | S3 |
| DB names | `classic*` (kept) | `tbc*` | `wotlk*` | S0/S4 |
| realmlist schema | `realmbuilds` `5875 6005 6141` | `realmbuilds` `8606` | `realmbuilds` `12340` | S2 |

*(Correction C1: `realmbuilds` varchar(64) in all three branches — register §0.)*
| account.expansion | 0 | 1 | 2 | S8 |
| Addon interfaces | 11200 | 20400 | 30300 | S7 |
| Bot max level / maps | 60 / 0,1 | 70 / 0,1,530 | 80 / 0,1,530,571 | S3 |
| Extractor floors | MIN_DBC=100, tiles=100 | re-measure | re-measure | S6 |
| extractedEstimate | 4 GiB | measure | measure (likely ≥ 8–12 GiB) | S5/S6 |
| maxTotalBytes | 20 GiB | ~25 GiB | ~35 GiB (confirm) | S5 |
| Settings/bindings catalog | 109 / 211 (existing) | capture | capture (larger) | S7 |
| WTF layout | account-level | 3-level (per-realm/per-char SavedVariables — correction C8: already 3-level upstream, not "account-level (verify)") | 3-level + WotLK-era char-level cache set (`config-cache.wtf`, `bindings-cache.wtf`, `macros-cache.txt`, `layout-local.txt`, `chat-cache.txt`) | S7 |
| Graphics lanes | DXVK + Gladio validated | DXVK only | DXVK only | S7 |
| vanilla-tweaks | on | off (research item) | off (research item) | S7 |
| Auth opcode/frames | `0x1EC` all flavors (frames verified per branch) | = | = | S8 |

*(Correction C3, register §0: `SMSG_AUTH_CHALLENGE` = 0x1EC in all three branches — the "branch-defined" row this replaces was wrong. SRP6 frame *layouts* still need per-branch verification (P2-7 keeps that distinction); the per-flavor `authFrame` descriptors carry what S8 verifies.)*
| LLM corpus / era lint | v112 (existing) | per-flavor | per-flavor | S3 |

## 7. Pinned-artifact inventory (what the gauntlet watches)

Every item below is a known vanilla pin (audit, 2026-09-29) that must end up flavor-routed. The leak sweep checks the finished state; reviewers check the path.

**Server:** `.gitmodules` (cmangos/classic-db/playerbots) · `schemas/sources.json` · `schemas/database-migrations.json` source_commits · all `schemas/realm-runtime-lockfile*.json` · `tools/build_o09_realm_runtime.py:37` · `SharedDefines.h:1968` · `RealmList.cpp:35–78` · `AuthSocket.cpp:295,899` · `wardenwin.cpp` · `native/cmangos/sql/base/realmd.sql:177–200` · `revision_sql.h:3–6` · `Master.cpp:363/392/425` · `realmd_runtime.cpp:135` · `native/patches/cmangos/*` · `native/patches/o11-cmangos-safe-mpq-listfile.patch`.

**Bots/LLM:** `native/patches/playerbots/*` (~25 files) · `PlayerbotLlmChatter.cpp:230,736` · `PlayerbotLlmTruthCore.h:836` · `llm_banter_core.h:47` · `BotProfiles.kt:144–146` · `BotPopulationPolicy.kt:13–33` · `ServerRuntimeFiles.kt:115–129,166,453` · `LlmPromptPack.kt:235` · `lore_cards_v112.jsonl` · `native/llm/sql/*` · `sql/migrations/*`.

**Import/identity:** `ClientPeIdentity.kt:38` · `SafClientScanner.kt:170–174` · `ArchiveClientScanner.kt:87–205` · `ManagedClientImporter.kt:44,222,294,585,728` · `ManagedClientStore.kt:59,93–112` · `ClientGenerationStore.kt:93,134,184` · `ClientRuntime.kt:256` · `ArchiveFormatSniffer.kt:54–92` · `ImportExtractionPolicy.kt:107–115` · `ImportModel.kt:54–90` · `ImportStoragePlanner.kt:10–54` · `DataPreparationStore.kt:267,295,407–439` · `PreparedDataStore.kt:38–99` · `tools/stage_o07_client.py` · `tools/install_client_windows.ps1` · `SyntheticClientArchives.kt:45`.

**Runtime/UX:** `WineRuntimeStore.kt:485,655,919,1581` · `ClientTweaks.kt:99–443` · `SinglePlayerAutoLogin.kt:33–279` · `WowVanillaBindingCatalog.kt` · `WowVanillaSettingssCatalog.kt` · `ConfigWtfCodec.kt:120` · `SavedVariablesCodec.kt:4` · `InGameSettingsFiles.kt:12–16` · `ManagedConfigPolicy.kt:51` · `InputProfile.kt:19,94,399` · `AddonArchiveValidator.kt:18,32,157` · `AddonRepository.kt:484,878` · `AddonRuntimeProjector.kt:246` · `VoiceOverReleaseResolver.kt:5,23` · `AndroidPortPackage.kt` · UI copy in `FirstRunTutorial/HomeScreen/SettingsScreen/AddonsScreen/ControlsScreen/DiagnosticsScreen` · `schemas/vanilla-tweaks-lockfile*` · `schemas/flavor.json`.

**Desktop/test lane:** `desktop/.../AuthGate.kt:265,293–297,357` · `RpWowClient.kt:46,131,168` · `tools/rp_harness/probe_*.py` · `tests/test_sqlite_seeding.py` · `tests/test_differential_lane.py:178` · `tests/test_pocket_realm_nearby_interact.py` · `tests/test_win_lockfile.py:146` · `tests/test_llm_truth.py:127` · `AddonArchiveValidatorTest.kt:101–215` · `ClientBuild5875LoginTest.kt`.

## 8. Risks, kill-switches, decision log

### 8.1 Ranked risks

| # | Risk | Stage | Mitigation / kill-switch |
|---|---|---|---|
| 1 | **Bots-tree drift/build failure on newer branches** *(demoted from the former "no maintainable bots tree" kill-switch by correction C4: upstream `cmangos/playerbots` master is multi-expansion and the 7e2cd2fb pin serves all flavors)* | S3 | Fallback = the recorded H1-16 decision entry (mod-playerbots evaluation with trigger conditions, server-systems.md:414-427); ship-flavor-without-bots only via that recorded path, never silently. |
| 2 | **WotLK map/vmap/mmap on-device time+size** | S6 | Measured go/no-go; fallback = qualified desktop-assisted prep lane (already exists in tool form). |
| 3 | **Fork commit port effort per branch** (Bionic/NDK breaks on newer branches) | S2 | Budget buffer; host-build first to shake out compile issues before device. |
| 4 | **Storage caps on real devices** (multi-flavor installs) | S5/S9 | Per-flavor budgets; explicit user messaging; desktop-assisted prep reduces on-device headroom needs. |
| 5 | **Login/topology re-qualification fragility** (per client, per lane) | S7 | Evidence-based capture tooling; topology tests as androidTest; accept manual login as fallback UI. |
| 6 | **DXVK/Turnip behavior differences on newer D3D9 usage** | S7 | DXVK-only policy; driver quarantine machinery already exists; qualify on real hardware early. |
| 7 | **WotLK cvar/settings surface much larger** | S7 | Capture-then-hash approach like vanilla; scope in-game editor to a curated subset if needed (record). |
| 8 | **Ledger/lockfile combinatorial drift** across 3 flavors | S1 | Single resolver + CI divergence checks; no hand-edited lockfiles. |

### 8.2 External inputs required (user-provided)

1. Genuine **TBC 2.4.3 enUS** client (build 8606) for qualification.
2. Genuine **WotLK 3.3.5a enUS** client (build 12340) for qualification.
3. RP6-class test device availability windows for S6/S7/S10 measurements and soaks.
4. Product calls at named decision points: WotLK desktop-assisted prep (S6), AndroidPort addon per-flavor scope (S7). *(The former "bots-less fallback (S3)" product call is removed — correction C4 resolved the bots-tree premise; the residual drift fallback is the recorded H1-16 decision entry, not a product call.)*

### 8.3 Decision Log

Appended in-repo (`docs/DECISIONS-PORT.md`) as stages execute. Entries so far (from this plan):

| D# | Decision | Rationale |
|---|---|---|
| D1 | Keep `classic*` DB names for vanilla; add `tbc*`/`wotlk*` | avoids churn/risk in the proven working lane |
| D2 | TBC runbook (RA) executes before WotLK (RB) | smallest step on every axis; lessons transfer |
| D3 | DXVK-only graphics route for tbc/wotlk at launch | Gladio validated only against vanilla GL usage |
| D4 | Per-flavor extractor .so names (not runtime dispatch) | fail-closed and simpler to gate/provenance |
| D5 | "Perfection" = §4.4 convergence gate; 6-round cap forces redesign | bounded, auditable, no time-based exits |

## 9. Execution schedule (shape, not dates)

- **Wave 1 (parallel):** S0 → S1 (critical); S5 identity groundwork can start once S0's `ClientFlavor` exists.
- **Wave 2:** RA (TBC) — S2 → S3/S4 (parallel) → S6 → S8; S5/S7 in parallel lanes.
- **Wave 3:** RB (WotLK) — same runbook; re-use RA's re-derived overlay techniques and measured budgets.
- **Wave 4:** S9 coexistence → S10 qualification.
- Each stage gate is a hard boundary: no stage starts before the previous gate's convergence is recorded in the Decision Log.

*Every stage's red-team gauntlet (§4) is run with fresh reviewer agents per round — reviewers never review their own fixes, and the implementation agent never marks its own findings resolved.*
