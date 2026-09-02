# MariaDB Replacement Research Plan

Standing runbook for the **database-backend study**: determine whether Pocket
Realm should replace MariaDB with a more performant database, what gains are
theoretically available, how hard the swap would be, and the
advantages/disadvantages of each option — via an **iterative six-agent
round-robin**: six agents approach the question from different angles, and
whenever a round produces any new load-bearing fact or unresolved
disagreement, **all six relaunch** with the updated Findings Digest, until a
full round returns zero new facts and unanimous verdicts (two consecutive
clean rounds to declare convergence).

Everything here is written so a fresh session (or a new reviewer) can run
the loop without any other context. This document mirrors the conventions of
the proven LLM runbook (`native/llm/REVIEW_AND_RESEARCH_PLAN.md`).

> **OWNER DECISION (2026-09-02), post-study:** the study's "gated second
> phase" concluded with the dual-provider window as the PERMANENT
> endgame — MariaDB/MySQL stays the stable default provider, SQLite
> ships only as a separate experimental APK (`-PsqliteProvider`), and no
> cutover or supply-chain deletion will be executed (P8 of the
> implementation plan is descoped). This plan is retained as the study
> record.

**Study ground rules:** the study is **read-only** — no production files are
modified; agents may run read-only commands (git, wc, python one-liners) but
must not build, stage, or edit. All findings must cite `file:line` evidence.

---

## Part 0 — Status snapshot (update every round)

| Round | Agents | New findings | Disagreements | Clean? |
|-------|--------|--------------|---------------|--------|
| 0 | 3 × Explore (orientation) | seed digest below (Part 3) | — | n/a |
| 1 | 6 × researcher | 20 (F18–F37) + corrections to F6/F7/F10/F12/F15 | 2 raised, both resolved by main-agent verification (D1 embedded-MariaDB, D2 sources.json SQLite) | No (new facts + Q2 verdict split A vs B/C/D/E/F) |
| 2 | 6 × researcher | 10 (F38–F47) + F18 corrected in place (async partially live) | 1 systematic (F18 overstatement — raised independently by 4 agents; resolved by verification) | No (new load-bearing facts; Q2 verdicts now UNANIMOUS: control-first package, SQLite gated 2nd phase) |
| 3 | 6 × researcher | 8 (F48–F55) + F39 edit-site corrected (world_runtime.cpp, not lifecycle.cpp) | 0 (none; all corrections resolved by verification) | No (new load-bearing facts; Q2 unanimity CONFIRMED by all six; OQ11 = NONE) |
| 4 | 6 × researcher | 0 new facts; 4 load-bearing corrections (F9 probe cadence, F28 line refs, F31 APK metric, F34 layer size) + F50 addendum (null-guard needed today) | 0 | No (corrections entered the digest → not clean by rule; verdicts UNANIMOUS, all six affirm) |
| 5 | 6 × researcher | 0 (citation cosmetics only) | 0 | **CLEAN #1** (all six CONFIRM; zero new load-bearing facts; queue empty) |
| 6 | 6 × researcher | 0 | 0 | **CLEAN #2 — CONVERGENCE DECLARED** (all six: findings NONE, verdicts CONFIRM; several lanes returned "ideas: none by design") |

**Final status: CONVERGED at Round 6** (two consecutive clean rounds; 55 digest facts — 54 verified, 1 open [F17, on-device measurement]; zero unresolved disagreements; Q1–Q5 unanimous since Round 2 and re-confirmed in Rounds 3–6). Final report: `docs/plans/mariadb-replacement-research-report.md`.

**Convergence rule.** A round is *clean* when: (a) all six agents return,
(b) zero new load-bearing facts enter the digest, (c) every verdict in the
Part 2 study questions is unanimous (identical answer; confidence may
differ), and (d) the disagreement queue is empty. Convergence is declared
after **two consecutive clean rounds**. **Hard cap: 6 rounds** — if the cap
is hit with live disagreements, stop and write the final report with an
explicit *Unresolved / escalated to maintainer* section.

**Round log template:**

```
## Round N — <date>
Agents returned: 6/6
New digest facts: <count> (F## ranges) — or NONE
Disagreements raised: <count> — list D## — or NONE
Verdicts: Q1..Q5 unanimous? <yes/no, where split>
Digest status: <verified/disputed/open counts>
Notes: <anything unusual — e.g. stale line refs, submodule drift>
```

### Round logs

## Round 1 — 2026-08-21
Agents returned: 6/6
New digest facts: 20 (F18–F37) + 5 corrections (F6 refined, F7 refined, F10 corrected, F12 refined, F15 corrected)
Disagreements raised: 2 — D1 (embedded-MariaDB viability: Agent B "upstream still ships libmysqld" vs Agent F "removed/dropped post-10.11 + GPL-2.0-only") → **resolved by main-agent verification: disqualified on licensing alone** (THIRD_PARTY_NOTICES.md states MariaDB is "never linked into the app or Pocket Realm's GPL-3.0 code"; GPL-2.0-only cannot be linked into GPL-3.0); upstream status at best ambiguous, web balance says dropped around the 10.x→11.x transition (F33). D2 (sources.json SQLite record: B cited sources.json:28 vs F "no entry") → **resolved: line 28 exists but is a `native_build_host.dependencies_built` build-host note, NOT a component provenance entry; no url/sha pin, zip gitignored, no fetcher** (F32).
Verdicts: Q1..Q5 unanimous? **No.** Q1 substantially aligned (bottleneck = boot + world-thread inline-write stalls + process churn; not throughput/memory/socket) but phrased differently. **Q2 split**: Agent A recommends control baseline **plus two engine-agnostic S-class fixes** (enable async transactions in the embedded facade; trx_commit=2) as the answer; B/C/D/E recommend control-now with in-tree SQLite as the conditional second candidate; F recommends the SQLite swap on provisioning grounds. Convergent sub-consensus across all six: control tuning is the immediate first step; SQLite (after hardening) is the only viable replacement; all other candidates disqualified or worse.
Digest status: 36 facts — 35 verified, 1 open (F17; now with the F19 quantum explanation and probe-print location)
Notes: submodule line refs drifted slightly and were corrected during verification: AllowAsyncTransactions in RandomPlayerbotMgr at :1291 (not :1166); realmd Main.cpp:307; Player.cpp REPLACE INTO at :16182; PlayerbotLlmMemory.cpp line refs cited against the `native/patches/` copy which is byte-identical to the submodule file (cmp verified); CharacterHandler.cpp actually at `src/game/Entities/`. Round-0 F10's "~93 MB / 393 files" was wrong (manifest sql_size sum = 127.7 MB / 412; classicmangos 125.3 MB / 402). **Round classified NOT CLEAN → all six relaunch with updated digest.**

## Round 2 — 2026-08-21
Agents returned: 6/6
New digest facts: 10 (F38–F47)
Disagreements raised: 1 systematic — **F18's consequence was overstated**, raised independently by Agents A, B, D, E: async transactions are NOT fully absent at runtime. (a) the embedded realmd facade enables LoginDatabase async (`realmd_runtime.cpp:171`); (b) `RandomPlayerbotMgr::AddRandomBots()` enables CharacterDatabase async at runtime, reachable by default in the shipped bot profile (BotProfiles.kt:90 `RandomBotAutologin=1`, :93 `RandomBotAutoCreate=1`, :149 `AsyncBotLogin=0`; sticky atomic flag, never reset); (c) async *queries* were never gated — the machinery runs in production today, with a world-thread deadlock already fixed by the RESULT_QUEUE_ANDROID build overlay. Inline COMMIT-fsync stalls therefore apply to World/Login(:world)/Logs always, and CharacterDatabase only pre-churn/saturated-pool. Resolved by main-agent verification (all three sub-claims confirmed in source). Agent F also moved off "swap-dominates-provisioning" to the consensus.
Verdicts: Q1–Q5 unanimous? **Q2 YES — all six now hold the same position**: control baseline first, packaged with the S-class async-enable (in-repo `lifecycle.cpp`, mirroring the realmd precedent) and optional extra levers (query-connection counts, poll interval, pool trim); `trx_commit=2` explicitly split out as a separate durability-contract decision (F25/F45), not bundled; in-tree SQLite retained as the *gated second phase* (gates: F26/F30 hardening, F43 tooling rebuild, F42 o09-lane coverage, F44 export bridge, F32 pinning, F45 durability parity); all other candidates rejected (F33 licensing, KV/OLAP shape, PostgreSQL process model). Q1 unanimous in substance (bottleneck = first-boot CLI churn + engine-agnostic write stalls/serialization; magnitudes unmeasured, F17). Q3/Q4/Q5 aligned with Q2 structure.
Digest status: 47 facts — 46 verified, 1 open (F17)
Notes: OQ7 answered (async-enable is S-class; ONE hidden blocker found: post-halt null-deref in Database.cpp:340-344 — guard required via overlay); OQ8 answered (drain lands in the 60 s saveWorld + 30 s world-stop budgets, not the DB stop; wave modeled 2-13 s); OQ9 answered (o09 `-DDO_*` flags are NO-OPS — CMake reads `SQLITE`/`POSTGRESQL`; silent-MySQL trap; legacy-facade-only SQLite coverage; Kotlin revision/ledger machinery is MariaDB-bound). **Round classified NOT CLEAN (new load-bearing facts) → all six relaunch for Round 3 stability test.**

## Round 3 — 2026-08-21
Agents returned: 6/6
New digest facts: 8 (F48–F55)
Disagreements raised: 0 — but two corrections to prior facts, both resolved by main-agent verification: (a) **F39's edit site was wrong** (Agent D): `lifecycle.cpp` builds the legacy o4 TEST facade (`libpocketrealm.so`, consumed only by tools/run_realm_test.py; RealmSupervisor.kt self-documents as legacy) — the production `:world` runs `WorldRuntimeService` → `libpocket_world_runtime.so` → `world_runtime.cpp`, whose embedded sequence (:564-688) likewise never enables async. Phase-1 executed as previously specified would have been a production no-op. S-class unchanged; corrected edit site is world_runtime.cpp (both edited for parity). The null-guard surface is 12+ `m_threadBody->Delay` sites (Database.cpp:344/:413/:567 + DatabaseImpl.h ×9), not 1. (b) **F28's census undercounted** (Agent C): runtime dialect surface is **11 statements**, not 8 — raw `TRUNCATE` ×2 (ObjectMgr.cpp:6055-6056, bypassing the `_TRUNCATE_` macro) and `DELETE … ORDER BY … LIMIT` (libanticheat.cpp:179; requires `SQLITE_ENABLE_UPDATE_DELETE_LIMIT`, which the vendored SQLite build does NOT define). Also (Agent A, corroborated by B): the shipped bot-load baseline is **320 default / 600 built-in ceiling / 700 legacy** (BotProfiles.kt:1030-1031; MAX_SUPPORTED_TARGET=10_000, BotPopulationPolicy.kt:40) — not the upstream 50/200 the Part 1b model assumed; at 600/700 the modeled save wave EXCEEDS the 30 s saveall-ack ceiling (reachable not-clean flags today, engine-agnostic).
Verdicts: Q1–Q5 unanimous? **YES — all six CONFIRM the Round-2 position** (control-first with async-enable at the corrected site + null-guard; trx_commit=2 split out; SQLite gated second phase; all others rejected). OQ11 answered **NONE** by all six independently: no APK-size ceiling, no emulator-boot release gate, no licensing deadline anywhere in docs/; the only quantified runtime perf constraint is the **250 ms world-p99 admission contract** (F49), which *strengthens* control-first.
Digest status: 55 facts — 54 verified, 1 open (F17)
Notes: Agent E flagged (and the main agent confirms via git status) that a parallel LLM-session has uncommitted dirt in supervisor/schemas files — no digest fact drifts. Agents B/F re-verified all line refs survived the intervening commit aa8bb79 (+346 lines in build_o09_realm_runtime.py). New: CI never exercises the packaging gates (F53); arm64 lockfile index-guard missing (F54); seeder backslash-escape gap ~48.7k sites/3 files (F52). **Round classified NOT CLEAN (new load-bearing facts) → Round 4 relaunch.**

## Round 4 — 2026-08-21
Agents returned: 6/6
New digest facts: 0 — but 4 load-bearing CORRECTIONS (all main-agent-verified): (a) **F9**: the "1 Hz probe" is upstream-only; the shipped overlay probes at ~10 s spacing, single-outstanding, with a 15 s staleness bound on the login gate (RandomPlayerbotMgr.cpp:798,:849,:1114-1175; overlay PB_MGR_DB_SCHEDULE_ANDROID, build_o09:455-540) — a DB stall closes the login gate within ≤~15 s, not ~1-2 s. (b) **F28**: PlayerbotLlmMemory.cpp grew mid-study (parallel LLM session; now 695 lines) — current refs: INSERT IGNORE :218, ODKU :403, UNIX_TIMESTAMP :441, DATE_ADD :543, NOW() :548/:562; Player.cpp REPLACE INTO :16182 (:16176 is the stmt-id decl). (c) **F31**: `useLegacyPackaging=true` stores jniLibs **compressed** — measured on the current APK: arm64 MariaDB closure 40,553,072 B staged → **12.26 MiB in-APK**; the −38.67/−57.24 MiB figure is INSTALLED footprint; download-size benefit ≈ 12.3 MiB arm64 / ~18 MiB x86_64; current arm64 full APK 275.61 MiB. (d) **F34**: Kotlin database layer = **11 files / 2,271 lines** (not 12/3,035); DatabaseEngine.kt 1,298 = 57.2% of the layer. Plus F50 addendum: the null-guard is load-bearing TODAY — production world_runtime.cpp has a restart path (StopServerEmbedded ×4 + ResetForReinit else-branch, :682-688), `m_allowAsyncTransactions` is sticky (playerbots set it in session 1), so session 2 re-enters with CharacterDatabase async-on against freshly-halted thread state.
Disagreements raised: 0
Verdicts: Q1–Q5 unanimous? **YES — all six AFFIRM the Round-2/3 position with the corrections folded in.** Agent B/C/E returned findings NONE outright.
Digest status: 55 facts — 54 verified, 1 open (F17)
Notes: convergence trajectory: R1 = 20 facts; R2 = 10; R3 = 8; R4 = 0 new facts (4 corrections). The corrections are real and report-changing (Q1 phrasing, Q3 magnitudes) → by the strict rule the round is NOT clean. **Round 5 relaunch with corrections baked in; Round 5/6 = the two clean-round candidates (cap = 6).**

## Round 5 — 2026-08-21
Agents returned: 6/6
New digest facts: 0
Disagreements raised: 0
Verdicts: Q1–Q5 unanimous? **YES — all six CONFIRM** (identical position; A/B/D/E affirm with full spot-verification; scored table re-affirmed 34/22/rejected).
Digest status: 55 facts — 54 verified, 1 open (F17). Citation cosmetics reported (NOT load-bearing; folded in as editorial fixes): DatabaseImpl.h holder->Execute at :184/:193 (macros at :182/:191); world_runtime.cpp restart block :685-689; DatabaseEngine.kt share 57.1%; THIRD_PARTY_NOTICES.md:32; F31 download clause per-ABI labels (arm64 −12.26 MiB exact; x86_64 ≈−19.2 MiB gross / ≈−18 net — zlib-9 estimate, no x86_64 APK exists to measure); Player.cpp REPLACE INTO currently :16176; enum cols :18/:27; @var file count is idiom-dependent (82-101; mechanical either way); UPDATE..JOIN 19 stmts; splitter recount 20,431/232 string-aware vs 17,282/296 simple-regex (same magnitude/structure); build_o09 flag cite now :1876 (file grew via parallel session).
Notes: the parallel LLM session's uncommitted edits keep shifting line numbers in build_o09_realm_runtime.py and PlayerbotLlmMemory.cpp — citations against those files are point-in-time. **Round classified CLEAN #1 → Round 6 = clean #2 candidate; convergence on two consecutive clean rounds.**

## Round 6 — 2026-08-21 (FINAL)
Agents returned: 6/6
New digest facts: 0
Disagreements raised: 0
Verdicts: Q1–Q5 unanimous? **YES — all six: findings NONE, verdicts CONFIRM** (identical to Rounds 2–5). Multiple lanes explicitly returned "ideas: none — the ledger already covers this lane" and "clean round #2 confirmed."
Digest status: 55 facts — 54 verified, 1 open (F17, on-device measurement by design)
Notes: Spot-verifications across lanes were byte-exact (F31 APK sums, F50 12-site enumeration, F23/F25/F30 test+code paths, F48/F49 profiles+gate, F33 notices+upstream license re-checked against github.com/MariaDB/server). Two cosmetic notes recorded for the report: quote the F31 download figure as the measured sum (12,858,890 B DEFLATE on the 288,993,581 B arm64 APK) and label the full-APK total as build-dependent (272.0–275.65 MiB across recent builds). **CONVERGENCE DECLARED — write the Part 5 report.**

---

## Part 1 — What is being studied (architecture map)

Pocket Realm runs a CMaNGOS WoW 1.12.1 realm (world + login + playerbots +
in-process llama.cpp LLM companion) and a **MariaDB server, both on one
Android device** (Retroid Pocket 6 target: Adreno 740, 12 GB RAM; LLM worker
pinned to cores 3–5; 77 °C thermal ceiling — DB work competes for the
remaining cores).

### 1a. How MariaDB is provisioned and run today

- **No MariaDB source in-tree.** Two ABI-specific, hash-pinned lanes:
  - x86_64 (emulator lane): MariaDB **11.5.2** source-built in Docker via
    the Termux glibc harness — `tools/build_mariadb_android.py`, staged by
    `tools/stage_mariadb_runtime.py` into `native/.build-x86_64/mariadb-staging/`,
    lockfile `schemas/mariadb-runtime-lockfile.json` (Termux package index
    also sha-pinned; refresh needs `--refresh`).
  - arm64-v8a (device lane): Termux bionic **mariadb 12.3.2** `.deb` closure
    conversion — `tools/stage_mariadb_android_arm.py`, lockfile
    `schemas/mariadb-runtime-lockfile-arm64-v8a.json`.
  - Two different MariaDB majors by ABI is intentional and current state.
- **Process model:** a separate forked `mariadbd` process (renamed
  `libpocket_mariadbd.so`, jniLibs trick, `useLegacyPackaging` + strip
  disabled in `android/app/build.gradle.kts` ~875–885). Spawned by
  `DatabaseEngine.startDaemon()` (`android/.../database/DatabaseEngine.kt:235-305`)
  through JNI in **`libwine_spike.so`**
  (`native/wine-spike/src/jni_shim.cpp:483-576`, `glibc_program_run.c`):
  - x86_64: `fork()` + `execve` of **PRoot as a seccomp syscall adapter**
    (lines 173–372) wrapping the glibc binary — a ptrace-based shim with
    real runtime cost.
  - arm64: direct `fork()` + `execve` with `LD_LIBRARY_PATH` (lines 374–534).
- **Networking:** `skip-networking`; **Unix domain socket only**
  (`<dbRoot>/run/mariadb.sock`, readiness = socket-file poll, 45 s deadline).
- **Config:** `my.cnf` generated per run by `DatabaseConfigPolicy.render()`
  (`android/.../database/DatabaseConfigPolicy.kt:19-41`):
  `innodb-buffer-pool-size=128M`, `innodb-log-file-size=32M`,
  `innodb-flush-log-at-trx-commit=1`, `sync-binlog=0`,
  `performance-schema=OFF`, `max-connections=24`, utf8mb4. InnoDB is the
  engine in use; zero storage-engine plugins ship.
- **How the game core connects:** CMaNGOS runtime libs
  (`libpocket_world_runtime.so`, `libpocket_realmd_runtime.so`) statically
  link **mariadb-connector-c** (pinned commit, built by
  `tools/build_o09_realm_runtime.py:1547-1557, 1832-1851` with
  `-DDO_MYSQL=ON -DDO_SQLITE=OFF`) and connect via the Unix socket
  (`host "."` → `native/cmangos/src/shared/Database/DatabaseMysql.cpp:121-137`).
  Kotlin never links a MySQL client — it shells the packaged `mariadb` CLI.
- **Schemas (5):** `classicrealmd`, `classiccharacters`, `classiclogs`,
  `classicmangos`, `pocketrealm_meta` (app migration ledger), created in
  `DatabaseEngine.initialize()` (lines 169–187); users `pocket_admin` /
  `pocket_core` (least-privilege, negative-tested).
- **Migrations:** 412-entry append-only manifest
  (`schemas/database-migrations.json`, built by
  `tools/stage_database_migrations.py` from pinned submodules cmangos
  082afd6 / classic_db be1a520 / playerbots 3b77c5f; `native/llm/sql/*`
  appended last as 0411/0412). Applied on device by
  `applyPinnedMigrations` (`DatabaseEngine.kt:405-494`) with hash
  verification + per-file ledger rows + snapshot rollback.
- **Datadir ownership is sealed:** provider identity (bootstrap sha,
  manifest sha, provider closure sha) is bound into durable state markers;
  drift ⇒ `PROVIDER_MISMATCH`, engine refuses to start
  (`DatabaseDurableState.kt:96-114`). **Any engine swap invalidates
  existing datadirs by design** — the migration story must handle this.

### 1b. How the game core uses the DB (hot paths, ranked)

1. **Per-player full-save transaction every ~15 min + on every logout**
   (`Player::SaveToDB`, `native/cmangos/src/game/Entities/Player.cpp:15528`,
   timer :1529, default `World.cpp:497`): DELETE+re-INSERT of `characters`
   row and full per-guid child tables, × (1 player + 50–200 bots).
2. **Bot login/logout churn** — ~20 SELECTs per login
   (`CharacterHandler.cpp:176-240`, batched via
   `PlayerbotLoginMgr.cpp:170-185`); logout adds full SaveToDB +
   `PlayerbotDbStore` INSERT-per-value (`PlayerbotMgr.cpp:211-230, 307-310`;
   `PlayerbotDbStore.cpp:45-89`). Pocket pacing batches 5 activations
   (`PlayerbotAIConfig.cpp:214-218`; Min/MaxRandomBots = 50/200).
3. **LLM memory synchronous `PQuery` on the world thread** per chat turn +
   30 s prewarm per bot (`PlayerbotLlmMemory.cpp:165,243,302,352,446`;
   `PlayerbotAI.cpp:280-302, 570`) — direct tick-stall risk (rate-gated,
   default OFF).
4. **Startup load** — first boot applies ~93 MB of migrations (classicmangos
   93.4 MB / 393 files; playerbot-world 31.9 MB; characters 2.33 MB), then
   ~80 `ObjectMgr::Load*` table scans; every boot re-reads the ~75 MB world
   DB (read-only at runtime).
5. **`ai_playerbot_random_bots` event churn** — DELETE+INSERT per event
   change across all bots (`RandomPlayerbotMgr.cpp` ~3290–3360).
6. **1 Hz DB probe** (`SELECT 1`) gating bot logins on round-trip delay
   (`RandomPlayerbotMgr.cpp:1120-1185`; interval 1000 ms,
   `PlayerbotAIConfig.cpp:219`).
7. **AHBot / auction timers** (20 s / 1 min) — bursty async writes.
8. **Housekeeping** — uptime UPDATE, addon data, weekly maintenance
   full-save (`World.cpp:2179-2195`).

**Threading model** (`Database.cpp:120-190`): per database = 1 sync
connection (world-thread, lock-held) + 1 async connection + 1 serialized
`SqlDelayThread` worker; callbacks pumped on the world thread. A large
share of runtime SQL (all playerbot/LLM paths) is raw sprintf-style
strings, not prepared statements.

### 1c. Prior art: the embedded SQLite lane (already exists, compiled out)

- CMaNGOS ships an in-tree SQLite backend: `DO_SQLITE`
  (`native/cmangos/src/shared/Database/DatabaseSqlite.cpp`) — WAL,
  `synchronous=1`, `busy_timeout=2 ms`, one sqlite3 handle per connection.
- `tools/seed_realm_db.py` translates the MySQL dumps into 4 SQLite files
  (staged examples: `native/.o4-stage-arm64/db/*.sqlite`, mangos = 74.9 MB).
- `scripts/build_native.py` builds the SQLite lane (`-DSQLITE=ON`); the
  shipped lane builds `-DDO_SQLITE=OFF`. Documented in
  `docs/patches/native-source-patches.md:113-171`.

### 1d. Hard constraints every agent must respect

- `native/cmangos`, `native/playerbots`, `native/classic-db` are **pinned,
  pristine submodules — never edited.** Core changes only via anchor
  overlays in `tools/build_o09_realm_runtime.py` or whole replacement files
  in `native/patches/`. Effective source = upstream + overlays.
- **Migration manifest is 412 entries, append-only.** Never renumber or
  insert mid-array; new SQL only as tail entries via
  `tools/stage_database_migrations.py:select_inputs()`.
- **Provider-sealed datadirs** (Part 1a) — engine swaps are fail-closed
  events by design; the plan must include an upgrade/migration path or an
  accepted re-initialization story.
- **Dual ABI lanes** (x86_64 emulator / arm64-v8a device); ELF gates on
  both: DT_NEEDED allowlist + all LOAD segments ≥ 0x4000 (16 KB pages),
  enforced in `tools/build_o09_realm_runtime.py` **and** the Gradle closure
  validator.
- **Licensing:** app is GPL-3.0; every vendored component recorded in
  `schemas/sources.json` + `THIRD_PARTY_NOTICES.md`. Candidate DBs must be
  GPL-3.0-compatible with a notices story.
- **Windows Git Bash warning:** nested heredocs mangle Python
  triple-quotes/backslashes and have caused silent anchor misses. Prefer
  the Edit tool or byte-splice scripts; verify content afterward.
- The study itself changes nothing: **read-only.**

---

## Part 2 — The six-agent round-robin protocol

### 2a. Study questions (every agent answers all five, every round)

- **Q1.** Is the database a material bottleneck for the on-device runtime
  today — where exactly (boot, world-thread stalls, save throughput,
  memory), and what is the evidence?
- **Q2.** Which option has the best performance / effort / risk trade-off?
  (Options always include the control baseline: **keep MariaDB, tune it
  harder** — config, engine settings, provisioning changes only.)
- **Q3.** What theoretical gains does the recommended option offer — boot
  time, world-thread DB stall latency, save-transaction throughput, RSS,
  APK/binary size, x86_64 PRoot elimination — with magnitude, reasoning
  chain, and confidence (high/med/low)?
- **Q4.** How hard is it — which layers are touched (CMaNGOS DB layer,
  Kotlin `database/` package, build scripts, lockfiles, Gradle lanes) and
  what effort class (S < days, M < weeks, L > weeks)?
- **Q5.** What are the decisive advantages/disadvantages and risks per
  candidate, and what evidence would flip the recommendation?

### 2b. Roster (six researchers, launched in parallel, fresh agents each round)

Each agent gets a **self-contained prompt**: Part 1 verbatim + its angle
brief below + the current Part 3 Findings Digest + the open-questions list
+ the disagreement queue. Type: read-only general-purpose researchers
(read-only bash allowed). Each must return:

1. findings with `file:line` evidence (new facts or corrections),
2. verdicts on Q1–Q5 with confidence,
3. explicit disagreements with digest entries (by F## number),
4. 2–3 ideas for the Part 4 ledger, tagged
   `[performance]`/`[risk]`/`[integration]`/`[migration]`.

**Agent A — Performance & bottleneck analyst.** Model where DB time
actually goes on-device: socket round-trip cost vs in-process calls,
serialized `SqlDelayThread`, sync world-thread `PQuery` stalls, fork+exec +
PRoot overhead (x86_64), InnoDB flush behavior on device flash, buffer-pool
memory pressure (128M in a 6–12 GB device running server + LLM + Wine
client). Quantify theoretical ceilings per hot path (Part 1b). Baseline
"tune MariaDB in place" is a first-class option: what do
`innodb-flush-log-at-trx-commit=2`, pool sizing, and Aria/MyISAM for
throwaway tables buy? Estimates must state assumptions.

**Agent B — Backend candidate surveyor.** Survey and rate candidates:
tuned MariaDB (control), in-tree SQLite (WAL) via `DO_SQLITE`, libSQL,
DuckDB, LMDB, RocksDB/rocksdb (as KV — likely a poor fit for relational
schema, must justify), embedded MariaDB (libmysqld — verify current
upstream status), PostgreSQL-via-Termux (same process-model problem as
today). Criteria: Android NDK dual-ABI buildability + 16 KB-page/ELF-gate
compliance, GPL-3.0 compatibility, maturity/maintenance, fit for the
write-heavy characters DB vs read-mostly ~75 MB world DB, in-process vs
separate-process implications. Deliver a scored comparison table.

**Agent C — SQL compatibility & migration analyst.** Inventory the
MariaDB-dialect surface actually used across all 412 manifest sources:
stored procedures/triggers/events, `ON DUPLICATE KEY UPDATE`, user
variables, `LAST_INSERT_ID`, multi-table DELETE/UPDATE, collations,
`ENGINE=` clauses, anything SQLite et al. reject. Audit
`tools/seed_realm_db.py` coverage and gaps against that inventory. Define
the datadir migration story under provider seals (translate-and-rebuild vs
re-initialize-from-manifest vs dual-boot cutover) respecting append-only
discipline. Estimate dialect-translation effort class.

**Agent D — Integration & architecture analyst.** How clean is the CMaNGOS
`Database` abstraction for slotting/reusing backends
(`Database.cpp`, `DatabaseMysql.cpp`, `DatabaseSqlite.cpp`,
`SqlDelayThread`, `SqlPreparedStatement`, `SQLStorage` — including
threading/async semantics per backend)? Map the Kotlin surface that exists
only because the DB is a separate process (`com/pocketrealm/database/` 12
files: engine, service, native launcher, config policy, seals, snapshots,
supervisor prep — what is deleted vs rewritten for an in-process engine?).
Check supervisor start ordering (DB → realmd → world) and Save & Exit drain
semantics for each topology. Flag every place that assumes a socket or a
`mariadb` CLI.

**Agent E — Risk, durability & operations analyst.** Crash recovery/ACID
per option; **in-process blast radius** (a core crash now takes the DB down
— quantify what the supervised-separate-process model buys today and what
snapshots/`DatabaseDurableState` machinery must be re-built in-process);
WAL/journal behavior on device flash (UFS) incl. `synchronous` trade-offs;
backup/restore across ABI lanes (already impossible across the two MariaDB
providers — document); upgrade/downgrade and app-update story; corruption
risk and repair tooling; test coverage implications (which existing tests
cover DB lifecycle: `tests/test_mariadb_lockfile_pins.py`,
`tests/test_playerbot_mobile_pacing.py`, DB-probe gate).

**Agent F — Build, packaging & supply-chain analyst.** What a candidate
costs to provision: NDK builds for both ABIs, ELF-gate compliance, binary
size delta (quantify the MariaDB closure's APK cost today vs SQLite
~1 MB-class or others), lockfile/provider plumbing
(`mariadb-runtime-lockfile*.json`, `DatabaseRuntimeContract.kt`, Gradle
`databaseRuntime`/`realmRuntime` build types, jniLibs staging), build
reproducibility (source-build vs prebuilt Termux packages), licensing /
`THIRD_PARTY_NOTICES.md` work, and ongoing patch burden (Termux index
pins, build-recipe overlays, PRoot shim maintenance on x86_64).

### 2c. Per-round workflow (the relaunch rule)

1. **Launch** all six in parallel with self-contained prompts (2b).
2. **Verify** every claimed finding in source yourself before accepting it
   — agents are high-quality but not infallible; false positives happen.
   Confirm `file:line` still matches (submodules can drift between rounds).
3. **Update** the Part 3 digest: add new facts (next F## number, status
   `verified`), mark corrections, resolve or re-queue disagreements.
   Disputed facts get a targeted re-verification assignment in the next
   round's prompts (name the agent and the exact claim).
4. **Classify the round**: if **any** new load-bearing fact entered the
   digest **or** any disagreement is unresolved → **relaunch all six** with
   the updated digest (batch completion, not mid-round restarts — parallel
   agents must not work from a stale digest). Otherwise the round is clean.
5. **Log the round** in Part 0 using the template; append round ideas to
   Part 4.
6. **Repeat** until two consecutive clean rounds or the 6-round cap.

Rationale for relaunch-all-on-any-discovery: the user's requirement — a
"foolproof" plan where none disagree. A fact one agent discovers can
invalidate another agent's verdict; only a full re-vote on the updated
digest proves agreement is stable.

### 2d. Open questions

**Round 1 (all six answered — recorded for provenance):**

- OQ1: per-query socket round-trip cost — **answered R1**: no measured
  number in-repo; the probe prints the round-trip (RandomPlayerbotMgr.cpp
  ~:4525); the async path adds a 0-10 ms quantum (F19); web-cited
  estimates: unix-socket small-query round trip ~0.05-0.2 ms vs in-process
  ~1-10 µs. Remaining: capture on-device percentiles (Part 4 idea #1).
- OQ2: DatabaseSqlite async/prepared semantics — **answered R1**: nominally
  full contract, async machinery backend-agnostic, but with the F26 defect
  list.
- OQ3: PRoot shim cost — **answered R1** (F36): x86_64-only, ptrace-based,
  web-cited ~44 % aggregate / 10-100× syscall-bound; arm64 no equivalent.
- OQ4: runtime vs migration-only dialect — **answered R1** (F28): runtime
  surface is 8 statements (6 in one default-OFF file); migration-only:
  @var ×82 files, UPDATE..JOIN ×2, enum ×1.
- OQ5: busy_timeout=2 ms safety — **answered R1** (F30): dropped-write +
  write-wedge hazard (not corruption); realmd/world cross-process
  LoginDatabase writes make it concrete.
- OQ6: buffer pool usage — **answered R1** (F21): world DB is MyISAM (page
  cache); pool serves the characters hot set only; reducible to ~64 MB
  class.

**Round 2 (answered — recorded for provenance):**

- OQ7 — **answered** (F39): async-enable is S-class; one hidden blocker
  (post-halt null-deref, Database.cpp:340-344/:188) needs a guard overlay;
  drain ordering safe; deadlock class pre-fixed; no test assumes inline
  writes.
- OQ8 — **answered** (F40): drain lands in saveWorld 60 s + world-stop 30 s
  (not the DB-stop 30 s); wave models 2-13 s; async defers rather than
  multiplies (same single connection serialized either way).
- OQ9 — **answered** (F41/F42): o09 flags are no-ops (real switch
  `-DSQLITE=ON`); libsqlite3.a already in both deps prefixes; SQLite
  coverage is legacy-facade-only; Kotlin revision/ledger machinery is
  MariaDB-bound.

**Round 3 (answered — recorded for provenance):**

- OQ10 — produced the last source-fact batch (F48–F55): bot-load baseline
  320/600/700; 250 ms admission contract; F39 edit-site correction
  (world_runtime.cpp); 12+ null-guard sites; dialect 11 statements;
  seeder escape gap; CI packaging-gate gap; arm64 index guard. Lanes
  otherwise re-verified clean.
- OQ11 — **NONE** (all six agents, independently): no product constraint
  reorders control-first / gated-SQLite-second (F55).

**Round 4 (answered):**

- OQ12 — final sweep produced 0 new facts but 4 load-bearing corrections
  (F9 probe cadence; F28 line refs after the parallel-session file growth;
  F31 download-vs-installed APK metric; F34 layer size) + the F50 addendum
  (null-guard load-bearing today via the restart path). Lanes otherwise
  clean; three of six agents returned findings NONE outright.

**Round 5 (clean-round candidate #1 — confirmation only):**

- OQ13: With all corrections now baked into the digest, confirm: (a) every
  digest fact in your lane is correct as written; (b) your Q1-Q5 verdicts
  are final and identical to the Round-2/3/4 position. Report findings
  ONLY if something is wrong or missing — "NONE" is the expected outcome
  and completes a clean round.

---

## Part 3 — Findings Digest (seeded from Round 0)

Status legend: `verified` (checked in source this session), `disputed`
(disagreement open), `open` (plausible, not yet verified). Agents cite
these by number; corrections reference the F## they invalidate.

**Provisioning & process model**

- **F1** `verified` — MariaDB is a separate forked process over a Unix
  socket; no TCP, no embedded libmysqld. Evidence: Part 1a
  (`DatabaseEngine.kt:235-305`, `DatabaseMysql.cpp:121-137`,
  `DatabaseConfigPolicy.kt:19-41`).
- **F2** `verified` — Two different MariaDB majors by ABI (11.5.2 glibc
  x86_64 source-build; 12.3.2 bionic arm64 Termux `.deb`), each with its
  own bootstrap hash and lockfile. Evidence: `tools/build_mariadb_android.py`,
  `tools/stage_mariadb_android_arm.py`, `schemas/mariadb-runtime-lockfile*.json`.
- **F3** `verified` — x86_64 runs mariadbd under PRoot purely as a seccomp
  shim (ptrace-based). Evidence: `native/wine-spike/src/glibc_program_run.c:173-372`.
- **F4** `verified` — The DB JNI launcher lives in `libwine_spike.so`
  (shared with the Wine experiment). Evidence:
  `native/wine-spike/src/jni_shim.cpp:483-576`.
- **F5** `verified` — Server + client are shipped as jniLibs `.so` files
  with legacy packaging and stripping disabled; a symlink tree into
  `nativeLibraryDir` is rebuilt and hash-reverified every app start.
  Evidence: `android/app/build.gradle.kts` ~875–906;
  `DatabaseEngine.stageProviderData()` (~749–799).

**Usage & hot paths**

- **F6** `verified` (refined R1) — Write-heavy at runtime on `classiccharacters`
  (saves, bot churn, auctions, events); world DB ~75 MB and read-only after
  startup **except when the LLM lane is ON** — `world_gossip` writes go to
  `classicmangos` at runtime (PlayerbotLlmMemory.cpp:461-482, manifest 0412 →
  classicmangos; see F37). Evidence: Part 1b.
- **F7** `verified` (refined R1) — Writes serialize through one
  `SqlDelayThread` per database *when async is enabled*; sync reads run inline
  on the world/map thread; playerbot + LLM runtime SQL largely raw strings.
  **Refinement (F18): in the shipped embedded lane async writes/transactions
  are disabled entirely, so most writes execute inline on the calling
  thread.** Sync pool = 1 connection by default, round-robin, shared by world
  AND map threads under `SqlConnection::Lock`. Evidence: `Database.cpp:120-190`
  + F18 below.
- **F8** `verified` — LLM memory reads/writes are synchronous on the world
  thread per chat turn and per 30 s prewarm per bot (accepted caveat in the
  LLM runbook; default OFF). Evidence: `PlayerbotAI.cpp:280-302, 570`.
- **F9** `verified` (corrected R4) — Bot logins are gated on measured
  CharacterDatabase round-trip delay: the SHIPPED overlay probes at ~10 s
  spacing, single-outstanding, with a 15 s staleness bound
  (PocketScheduleDatabaseProbe / PocketDatabaseReadyForLogin /
  PocketBeginDatabaseProbe, RandomPlayerbotMgr.cpp:798,:849,:1114-1175;
  overlay tools/build_o09_realm_runtime.py:455-540). Logins allowed only if
  probe delay < 10 s AND result ≤ 15 s old. The "1 Hz SELECT 1" cadence is
  upstream-only. A DB stall closes the login gate within ≤ ~15 s. The
  repo's own built-in DB-latency detector and baseline metric; RTT surfaces
  via console diff (RandomPlayerbotMgr.cpp:4525 upstream / :4657
  effective). Evidence: as cited;
  `tests/test_playerbot_mobile_pacing.py`.
- **F10** `verified` (corrected R1) — First boot applies the pinned manifest
  through the `mariadb` CLI with size-scaled timeouts (30 s + 40 ms/KB, cap
  600 s; DatabaseEngine.kt:455-456); every boot re-reads the world DB via ~80
  `Load*` scans. **Correction: the payload is 127.7 MB across 412 entries
  (classicmangos 125.3 MB / 402 files; characters 2.4 MB / 8), not ~93 MB /
  393 files** (sql_size sums from schemas/database-migrations.json); first
  boot costs ~4 CLI process spawns per pending migration ≈ 1,650 spawns
  (DatabaseEngine.kt:436-466 + helpers :840-869; see F20). Evidence:
  `DatabaseEngine.kt:405-494`; `ObjectMgr.cpp`.

**Prior art**

- **F11** `verified` — An in-tree SQLite backend (`DO_SQLITE`) exists and
  is compiled out of the shipped lane; `tools/seed_realm_db.py` translates
  the MySQL dumps; staged SQLite DBs exist under `native/.o4-stage-arm64/db/`.
  Evidence: Part 1c; `tools/build_o09_realm_runtime.py:1832-1851`.
- **F12** `verified` (refined R1) — SQLite lane config today: WAL,
  `synchronous=1` (= NORMAL under WAL — **no fsync per commit, i.e.
  trx_commit=2-equivalent durability**, see F27), `busy_timeout=2 ms` (a
  correctness hazard, see F26/F30), per-connection handles. Evidence:
  `DatabaseSqlite.cpp:47-56`.

**Constraints**

- **F13** `verified` — Datadirs are provider-sealed; any provider/engine
  drift is a fail-closed `PROVIDER_MISMATCH`. Evidence:
  `DatabaseDurableState.kt:96-114`.
- **F14** `verified` — Migration manifest is 412 entries, append-only,
  hash-verified per file on device. Evidence:
  `schemas/database-migrations.json`; `DatabaseEngine.kt:405-494`.
- **F15** `verified` (corrected R1 — scope) — ELF gates run in two places,
  but the **strict** DT_NEEDED allowlist + all-LOAD≥0x4000 gate covers only
  the realm/llama libs (build_o09_realm_runtime.py gate block). The MariaDB
  closure's gates are closure-completeness + an alignment **ceiling** (≤0x4000
  passes — 4 KB glibc ELFs are fine because they are execve'd, never
  dlopen'd; stage_mariadb_runtime.py:329-331); Gradle checks only e_machine
  + provider id (build.gradle.kts ~:666-669). An in-process engine `.so`
  inherits the strict gate (see F35). Evidence:
  `tools/build_o09_realm_runtime.py`; `android/app/build.gradle.kts`.
- **F16** `verified` — Memory budget on target: 12 GB device running
  server + Wine client + llama.cpp concurrently; "6 GB should be enough"
  is unmeasured. Evidence: `README.md`.
- **F17** `open` — No in-repo benchmark of DB latency exists; the 10 s
  login-gate threshold and the DB probe are the only latency telemetry.
  (R1: the probe prints the measured round-trip — RandomPlayerbotMgr.cpp
  ~:4525; and the async path adds a 0-10 ms poll quantum on top of raw RTT,
  F19. Still no recorded numbers.)

**Round 1 additions (all verified in source by the main agent unless noted)**

**The dominant runtime mechanism**

- **F18** `verified` (corrected R2 — scope narrowed by F38) — **The embedded
  world facade never enables async transactions** (disabled by omission, not
  "compiled out"): `AllowAsyncTransactions()` for all four DBs exists only at
  Master.cpp:134-137 inside `#ifndef POCKET_EMBEDDED` (guard :101, close
  :341); the embedded lane compiles with `POCKET_EMBEDDED`
  (native/pocket-runtime/CMakeLists.txt:55) and its facade hooks
  (lifecycle.cpp:95-225) never enable; default `false` (Database.h:238).
  **R2 correction (F38): at runtime, CharacterDatabase self-enables via
  AddRandomBots (bots lane, default-reachable) and the realmd facade enables
  LoginDatabase — so the inline-write consequence applies to
  World/Login(:world)/Logs always and CharacterDatabase only pre-churn /
  saturated-pool.** When disabled: `Execute`→`DirectExecute` inline
  (Database.cpp:340-341), `CommitTransaction`→`CommitTransactionDirect`
  inline (:409-410), prepared executes inline (:563-564). Engine-agnostic.
- **F19** `verified` — The async worker (when used) sleeps 10 ms per poll
  and sleeps *before* processing (SqlDelayThread.cpp:41,:50) — 0-10 ms
  queue-latency floor on every async op regardless of engine. The 1 Hz
  "database delay" probe therefore measures quantum + RTT + callback pump,
  overstating socket RTT.

**Boot / first-boot**

- **F20** `verified` — First boot = 412 pending migrations × ~4 CLI process
  spawns (ledgerStatus + ledgerPending + runClient apply + ledgerFinish;
  DatabaseEngine.kt:436-466, helpers :840-869) ≈ **1,650 fork+exec(+PRoot on
  x86_64) cycles**, feeding 127.7 MB of SQL through CLI stdin; supervisor
  budget 30 min (RuntimeContracts.kt:71). Each spawn pays fork + execve +
  link + socket + auth. Steady boots re-run ledgerStatus per entry (412
  spawns) + ~80 Load* scans.
- **F21** `verified` (OQ6) — Storage-engine split measured from the actual
  sources: world base dump (ClassicDB z2815) = **154 MyISAM + 35 InnoDB**
  tables; characters base = 53 InnoDB + 5 MyISAM; realmd = 10 MyISAM + 1
  InnoDB; LLM tables InnoDB. The ~75 MB world DB rides the **OS page cache,
  not the 128 MB InnoDB pool**; the pool's real job is the small
  characters/logs InnoDB hot set. Pool reducible to ~64 MB class; total
  realistic MariaDB memory saving ≈ 60-130 MB (0.5-1 % of device RAM).

**Kotlin / process surface**

- **F22** `verified` — Kotlin separate-process surface: socket path born in
  3 places (DatabaseEngine.kt:49; ServerRuntimeFiles.kt:46,:108-109);
  graceful stop = CLI `SHUTDOWN;` as pocket_admin + 30 s join + socket-gone
  + process-group drain proof (DatabaseEngine.kt:349-365); health = CLI
  `SELECT 'POCKET_DB_OK', VERSION(), @@skip_networking` (:307-318);
  readiness = socket-file poll, 45 s deadline (:292-301). All
  server/CLI invocations fork+exec via libwine_spike.so JNI (:690-722).
- **F23** `verified` — Dirty-stop is the DEFAULT teardown: `close()` deletes
  the clean marker and hard-cancels the native program whenever RUNNING/
  STARTING (DatabaseEngine.kt:661-666) → `kill(-pgid, SIGTERM→SIGKILL)`
  (glibc_program_run.c:137-148); InnoDB crash recovery runs on every
  uncontrolled end and is acceptance-tested. `recover()` = dirty-recovery
  daemon start + error-log classification ("recover"/"crash"/"InnoDB") +
  health + clean stop (:381-403). Budgets: 30 min start / 30 min recovery /
  30 s stop (RuntimeContracts.kt:70-77).
- **F24** `verified` — Backups are **same-device, same-generation,
  same-provider-closure only**: every snapshot bakes provider + closure sha
  + bootstrap sha + manifest sha + `generationUuid`; restore requires exact
  equality with the current device identity AND the current datadir's
  generation UUID — a fresh random UUID per `initialize()`
  (DatabaseEngine.kt:136, :572). No dump/export tool ships (closure =
  mariadbd + mariadb client only, `plugins: []` — lockfiles). Datadir in
  noBackupFilesDir. No cross-ABI, cross-device, or export path exists.
- **F34** `verified` (numbers corrected R4) — Swap surface: Kotlin
  `database/` layer = **11 files / 2,271 lines**; DatabaseEngine.kt (1,298
  lines = 57.2 % of the layer; ~45 % of it deletable for an in-process
  engine: daemon spawn/CLI/drain/config-render), 3 small files deletable
  (DatabaseNative, DatabaseRunResult, …), seals/snapshot-store
  engine-agnostic and survivable (DatabaseDurableState.kt mostly unchanged,
  re-pointed). Supervisor treats DATABASE as an opaque component: start
  order DATABASE→REALM→WORLD (RuntimeTopology.kt:108-110), STOP_ORDER with
  saveWorld-first (DurableRuntimeSupervisor.kt:449,:601-606) —
  engine-independent. Provider identity is hard-bound to MariaDB anatomy
  (`initialized()` requires mysql/global_priv.*|user.* +
  `mariadb_upgrade_info` version match, DatabaseEngine.kt:1194-1203;
  provider ids DatabaseRuntimeContract.kt:4-7).

**Durability / tuning**

- **F25** `verified` — The control baseline's main commit-latency lever is
  **test-forbidden today**: DatabaseConfigPolicyTest asserts
  `innodb-flush-log-at-trx-commit=1` present and `=2`/`=0`/
  `innodb-doublewrite=0` absent (test names "...WithoutRelaxingDurability").
  Moving to =2 is a deliberate durability-contract change requiring test
  edits + a data-loss-tolerance decision.
- **F27** `verified` — Durability ladder: MariaDB trx_commit=1 (today) =
  zero committed loss on power cut; trx_commit=2 = ≤~1 s rollback window;
  SQLite WAL synchronous=NORMAL (=1, the in-tree lane setting) = rollback to
  last checkpoint on power cut (**a downgrade vs today**); SQLite
  synchronous=FULL (=2) = parity with today at fsync-per-commit cost.
  Process-crash safety holds in all four. (SQLite PRAGMA docs; agent-cited,
  semantics standard.)

**SQLite lane readiness**

- **F26** `verified` — In-tree SQLite backend implements the full
  SqlConnection contract (Query/QueryNamed/Execute/BEGIN/COMMIT/ROLLBACK/
  escape/prepared statements; DatabaseSqlite.cpp:82-374) — async/transaction/
  query-holder machinery is backend-agnostic (SqlDelayThread executes
  generic SqlConnection ops; SqlOperations.cpp:46-99). **Defects as-is**:
  `busy_timeout=2 ms` with `Execute` returning false on SQLITE_BUSY and no
  retry (:56,:134-140); `QueryResultSqlite` double-scans every result set
  and reads columns post-reset (bogus initial values, overwritten by the
  immediate NextRow) and leaks the 8-byte stmt wrapper per query
  (QueryResultSqlite.cpp:26-48; DatabaseSqlite.cpp:234); inverted
  `m_bIsQuery` (readonly==0 marks writes as queries, :246-250 — latent, no
  callers); `QueryNamed` returns a live result on 0 rows where MySQL returns
  null (:95-115 — benign for the 2 existing callers). All fixable in
  `native/patches/` replacement files.
- **F29** `verified` — `tools/seed_realm_db.py` translates only the 5 base
  sources (DB_SOURCES :46-51); it drops `SET @x :=` lines (fatal for the 82
  files using guid-relocation `@var` constants), drops `KEY`/`UNIQUE KEY`/
  `CONSTRAINT…FOREIGN KEY` lines wholesale (:131-,:161-177), and its
  statement splitter breaks on `;` inside string literals (:195). Staged
  output verified: mangos.sqlite (194 tables) / characters.sqlite (59) /
  realmd.sqlite (14) each have **zero explicit secondary indexes**
  (direct sqlite_master query) — every non-PK lookup becomes a table scan.
  Simulation of the existing translator over all 412 manifest files: 5/412
  clean, 3,941 real dialect/syntax failures (plus 9,304 no-such-table
  artifacts from the harness lacking the world base). World seed is also
  revision-behind (z2815 vs required z2830) with no SQLite migration-apply
  path.
- **F30** `verified` (OQ5) — `busy_timeout=2 ms` is a **dropped-write +
  write-wedge risk, not corruption** (WAL single-writer returns SQLITE_BUSY,
  never tears). Chain A: BUSY on a write inside `SqlTransaction::Execute` →
  rollback → return false → SqlDelayThread ignores → save silently dropped.
  Chain B: BUSY/BUSY_SNAPSHOT at COMMIT → `SqlTransaction::Execute` returns
  false **without ROLLBACK** (SqlOperations.cpp:46-68) → open transaction
  wedges the connection ("cannot start a transaction within a transaction")
  → permanent write outage for the session. Cross-process exposure is real:
  `:realm` (realmd) and `:world` are separate processes both writing
  LoginDatabase (AuthSocket.cpp:1154 sessionkey UPDATE on every login;
  Master.cpp:150 realmlist UPDATE). Required fixes if the lane ships:
  busy_timeout ≥ 100-500 ms, `BEGIN IMMEDIATE`, commit-failure rollback.
- **F28** `verified` (refs refreshed R4 — file grew mid-study, parallel LLM
  session; now 695 lines) — **The complete MariaDB-only dialect surface
  executed at runtime by the core is 11 statements** (F51-corrected):
  `REPLACE INTO` ×2 (Item.cpp:253, Player.cpp:16182 — SQLite-native) and,
  in PlayerbotLlmMemory.cpp (current refs): INSERT IGNORE :218; ODKU with
  IF() :403 — MySQL evaluates ODKU assignments left-to-right so the rewrite
  must fold the increment (CASE WHEN points + excluded.points >= 60 …);
  UNIX_TIMESTAMP :441; DATE_ADD(NOW(), INTERVAL 7 DAY) :543; NOW() :548,:562
  — all in one default-OFF feature. Plus raw TRUNCATE ×2 and DELETE..LIMIT
  (F51). Everything else is macro-abstracted (DatabaseEnv.h:28-70 defines
  _NOW_/_UNIXTIME_/_CONCAT3_/_TRUNCATE_ etc. per backend; ~125-139 usage
  sites). Migration SQL census: zero stored
  procedures/triggers/events/views/LAST_INSERT_ID/partitioning/fulltext;
  `@var` scripts in 90 files (mechanical constant-folding resolves them);
  UPDATE..JOIN ×2 files (18 stmts); enum ×1; CONCAT ×113 (SQLite 3.46.1 has
  CONCAT, 3.44+); ALTER..CHANGE/MODIFY ×9 files/30; TRUNCATE ×2 files/7;
  ALTER..ADD INDEX ×4/1 file; CHARACTER SET utf8mb3 ×1.

**Supply chain / packaging**

- **F31** `verified` (metric corrected R4) — Database-lane packaging cost,
  two distinct metrics: **installed footprint** (jniLibs extracted to
  nativeLibraryDir at install): MariaDB closure x86_64 57.24 MiB / arm64
  38.67 MiB (mariadbd 26.6 MB + client 5.25 MB + 9 support libs) + ~8 MiB
  provider assets + 23.48 MiB migrations per ABI; **APK download size**:
  `useLegacyPackaging=true` stores jniLibs DEFLATED — measured on the
  current arm64 APK: MariaDB closure 40,553,072 B staged → **12.26 MiB
  compressed** (x86_64 ≈ 18 MiB by the same ratio); current arm64 full APK
  275.61 MiB. SQLite-class candidate: static +1.2-1.8 MB per .so ⇒ net
  **−38.67/−57.24 MiB installed, ≈ −12.3/−18 MiB download** per ABI; data
  assets neutral (23.48 MiB .sqlz vs ~24.3 MiB compressed .sqlite).
  MariaDB = 80 % of the database-lane closure but 6-10 % of the full-lane
  native closure (~595-604 MiB, Wine/LLM-dominated).
- **F32** `verified` — Supply chain: x86_64 MariaDB is coupled to the Wine
  closure (ld-linux/libc staged from Wine artifacts with byte-match
  requirement, stage_mariadb_runtime.py:339-356); the source-build recipe
  carries live drift overlays (CMake 4 policy, GCC 16 signal type, evex512
  removal — build_mariadb_android.py:46-133); the Termux index silently
  moved twice during a Phase-4 window (hence pins + `--refresh`). SQLite
  amalgamation zip is gitignored with **no component-level provenance** —
  sources.json:28 is a build-host dependency note, not a pin; no fetcher
  script exists. SQLite build stage lives only in the legacy
  scripts/build_native.py lane, not the shipped o09 lane.
- **F33** `verified` — **Embedded MariaDB (libmysqld) is disqualified**:
  MariaDB is GPL-2.0-only and the repo's own notices state it is "never
  linked into the app or Pocket Realm's GPL-3.0 code" (in-process linking
  would be a GPL-2.0-only↔GPL-3.0 conflict — exactly why the separate-
  process isolation exists). Upstream status is at best dubious post-10.11
  (web balance: WITH_EMBEDDED_SERVER absent from newer build docs; the x86_64
  Termux recipe still anchors the flag but the arm64 12.3.2 recipe does not
  set it at all). Resolves Round-1 disagreement D1.
- **F35** `verified` — In-process-engine gate consequence (refines F15): a
  SQLite static-lib candidate inherits the strict realm/llama ELF gate
  (DT_NEEDED allowlist + LOAD ≥ 0x4000) and trivially passes it (final-link
  flag under the pinned NDK). A candidate shipping as its own `.so` must be
  built 16 KB-aligned from the start.
- **F36** `verified` (OQ3) — PRoot is an **x86_64-emulator-lane-only** ptrace
  shim around mariadbd (arm64 = direct execve, glibc_program_run.c:374+);
  published overheads: ~44 % aggregate on a Termux benchmark, 10-100× on
  syscall-bound paths (web-cited; nothing measured in-repo). The JNI runner
  thread also polls in 50 ms slices for the daemon's lifetime. PRoot
  artifacts (~375 KB) are Wine-owned and remain in full-lane APKs regardless
  of DB engine; the Gradle guard at build.gradle.kts:727-728 already refuses
  `pocketLane=database` for non-arm64, so the x86_64-database-lane dead-end
  is enforced, not latent.
- **F37** `verified` — LLM memory: 3-5 sequential sync `PQuery`es per chat
  turn on the world thread; `ShareGossip`/`world_gossip` writes hit
  **WorldDatabase** at runtime (PlayerbotLlmMemory.cpp:461-482; table lives
  in classicmangos per manifest 0412). Default OFF. Refines F6/F8.

**Round 2 additions (all verified in source by the main agent unless noted)**

- **F38** `verified` — **Async transactions are partially live at runtime
  today.** (a) The embedded realmd facade calls
  `LoginDatabase.AllowAsyncTransactions()` (realmd_runtime.cpp:171). (b)
  `RandomPlayerbotMgr::AddRandomBots()` calls
  `CharacterDatabase.AllowAsyncTransactions()` — submodule
  native/playerbots/playerbot/RandomPlayerbotMgr.cpp:1166 (the o09 build
  recreates the in-tree mirror from the pristine pinned submodule,
  build_o09_realm_runtime.py:1593-1609), reachable by default in the shipped
  bot profile (BotProfiles.kt:90 `RandomBotAutologin=1`, :93
  `RandomBotAutoCreate=1`, :149 `AsyncBotLogin=0`); the flag is a sticky
  atomic with no reset (Database.h:233,:282). (c) Async *queries* were never
  gated — the delay thread (created unconditionally, Database.cpp:145),
  result queue, and world-thread callback pump run in production, with a
  world-thread deadlock class already fixed by the RESULT_QUEUE_ANDROID
  overlay (build_o09_realm_runtime.py ~:306-320: callbacks execute outside
  the queue lock). Consequence: bot character saves are async from the first
  pool-growth pass; inline COMMIT-fsync stalls = World/Login(:world)/Logs
  always + CharacterDatabase pre-churn/saturated-pool.
- **F39** `verified` (OQ7) — **Async-enable for the world facade is S-class
  with exactly one hidden blocker.** Edit: 4 `AllowAsyncTransactions()`
  calls in native/pocket-runtime/src/lifecycle.cpp between
  InitWorldEmbedded (:149) and StartNetworkEmbedded (:153) — in-repo file,
  mirroring Master.cpp:132-137 (enable after startup settings, before world
  thread) and the realmd_runtime.cpp:171 precedent. Blocker: post-halt
  null-deref window — `Database::Execute` dereferences `m_threadBody`
  unchecked (Database.cpp:340-344) and HaltDelayThread sets it nullptr
  (:188); the async-off path is safe (DirectExecute null-checks), the async
  path needs a guard via the native/patches overlay. Drain ordering is safe:
  StopEmbedded runs clearOnlineAccounts → KickAll(true, saves) → realmlist
  direct UPDATE → HaltDelayThread ×4 (Master.cpp:655-668); the worker
  destructor sweeps remaining ops (SqlDelayThread.cpp:27-31). No existing
  test asserts inline-write semantics (DatabaseLifecycleTest asserts
  recoverability only); saveall-ack ≠ flushed pre-exists at the
  map-messenger layer (HandleSaveAllCommand acks after queueing). The
  sessionkey write is DirectPExecute (bypasses the queue — no auth race).
- **F40** `verified` (OQ8) — **Stop-drain budgets correctly attributed.**
  saveWorld = 60 s (componentStopMs, RuntimeContracts.kt:75) wrapping a 30 s
  native saveall (CONTROL_TIMEOUT_MS, ServerRuntimeContract.kt:9); world
  stop has its own 30 s native ceiling with worker-detach on exceed; the 30 s
  databaseStopMs (:76) covers only MariaDB SHUTDOWN. A 200-bot logout wave
  (≈200 transactions × ~50-150 statements + 1 fsync/commit) models to 2-13 s
  serialized — ≥4× margin. Async-enable *defers* rather than multiplies the
  total: DirectExecute/CommitTransactionDirect already lock the same single
  async connection (Database.cpp:417-432). Dirty-stop (the default teardown)
  loss window with async = ≤10 ms quantum + backlog — identical to
  standalone CMaNGOS semantics; :world force-stop is killProcess; SIGTERM →
  150 ms → SIGKILL (glibc_program_run.c:131-143).
- **F41** `verified` (OQ9) — **The o09 `-DDO_MYSQL=ON -DDO_SQLITE=OFF`
  flags are no-ops**: cmangos/CMakeLists.txt:385-390 selects the backend
  from the `POSTGRESQL`/`SQLITE` cache variables (MySQL = else-default); the
  correct switch is `-DSQLITE=ON` (as the legacy lane does,
  scripts/build_native.py:396). Trap: flipping the existing flag to
  DO_SQLITE=ON silently builds MySQL. `libsqlite3.a` 3.46.1 is already
  staged in BOTH o09 deps prefixes (native/.deps/prefix-{x86_64,arm64}/lib/,
  5.37/5.57 MB) so `find_package(SQLite3)` resolves with zero new build
  steps — the F32 gap is provenance pinning only. realm-runtime/CMakeLists
  links `${MYSQL_LIBRARY}` unconditionally into both runtime targets (inert
  under SQLITE; connector-c prebuild becomes wasted work).
- **F42** `verified` (OQ9) — **SQLite acceptance coverage is legacy-facade
  only.** tools/run_realm_test.py + pocket_lifecycle_test cover the o4
  facade end-to-end on-device with `-DSQLITE=ON`; the o09 facade has never
  been built SQLITE=ON and has zero DB tests (realm-runtime/tests has only
  test_bot_target_fence.cpp); no Kotlin/Gradle-lane coverage. The Kotlin
  revision/ledger machinery is MariaDB-bound: verifyExpectedRevisions probes
  information_schema.COLUMNS (DatabaseEngine.kt:924-928) and createLedger
  uses ENUM + InnoDB DDL (:820-838) — a swap must reimplement both
  engine-side (incl. the mandatory negative test).
- **F43** `verified` — **Dialect/tooling refinements.** The seed splitter's
  dominant defect is inline comments, not `;`-in-strings: `; -- comment`
  merges statements — 296/412 files, 17,282 sites (independently reproduced
  by the main agent). Seed coverage is structurally incomplete beyond the
  revision label: 5/412 sources ingested; the z2815 snapshot lacks the
  spell_template coefficient columns the pinned core requires (added only by
  the uncovered sql/base/dbc/original_data/Spell.sql —
  stage_database_migrations.py:114-121). Concrete index casualty:
  bot_player_facts loses `KEY bot_player (bot,player)` → full scans on the
  per-prompt LLM queries. @var recount: 90 files (statement-level).
  ALTER..CHANGE/MODIFY ×9 files; TRUNCATE ×2. The ODKU rewrite must fold the
  increment (`CASE WHEN points + excluded.points >= 60 …`) — MySQL evaluates
  assignments left-to-right, SQLite reads pre-update values; a mechanical
  rewrite silently lags tier thresholds. tz footnote: MariaDB NOW() is
  session-tz, SQLite CURRENT_TIMESTAMP is UTC.
- **F44** `verified` — **Export-bridge hazards.** classiccharacters has two
  `data longblob` packed-binary columns (characters.sql:61,:156) — a
  mariadb-CLI TSV export corrupts them unless each blob is HEX()-encoded and
  re-imported as X'…' (plus TSV unescaping). A dual-provider window is
  mandatory for any translate-and-rebuild cutover: after a provider swap the
  old engine cannot boot to export (fail-closed seals + provider files gone
  from the APK).
- **F45** `verified` — **Durability asymmetry.** The SQLite lane has NO
  policy-test analog to DatabaseConfigPolicyTest — a swap would silently
  adopt the WAL synchronous=NORMAL power-cut rollback (the exact contract
  F25 forbids for MariaDB). Refinement of F27: trx_commit=2's loss window
  manifests only on power/host cut — mariadbd is a separate process, so the
  routine dirty-stop (process kill) loses nothing under =2; decide the
  power-cut contract once for both engines (either test-encode FULL/synchronous=FULL
  parity or record an explicit ≤1 s acceptance).
- **F46** `verified` — **Packaging extras.** The MariaDB lane ships a
  second, renamed shared OpenSSL per ABI (libdb_libcrypto + libdb_libssl ≈
  5.8 MiB arm64 / 8.3 MiB x86_64) alongside the realm runtime's statically
  linked .deps OpenSSL — a SQLite lane deletes both. Ongoing maintenance
  quantified: 1,067 lines across 3 dedicated tools, 2 lockfiles pinning 34
  packages on a live index that drifted twice, a tripwire test, 3 recipe
  drift overlays, and the Wine ld-linux/libc byte-match coupling.
- **F47** `verified` — **Additional S-class control levers.** Per-DB query
  connection counts default to 1 (WorldDatabaseConnections etc.,
  Master.cpp:348,:371,:402,:436; pool cap 16, Database.cpp:31; my.cnf
  max-connections=24 leaves headroom) — raising to 2-3 is a deployed-config
  key, un-serializing concurrent sync reads. SqlDelayThread's 10 ms poll is
  a plain constant (SqlDelayThread.cpp:41) — reducible by one-anchor overlay
  or condition-variable wakeup to remove the F19 quantum floor.

**Round 3 additions (all verified in source by the main agent unless noted)**

- **F48** `verified` — **The shipped bot-load baseline is 320 default / 600
  built-in ceiling / 700 legacy — not the upstream 50/200** the Part 1b
  model assumed. Fresh-install default = ALIVE_REALM_320 (BotProfiles.kt:
  839-871, defaultProfile :1030-1031; profiles always override
  Min/MaxRandomBots :97-98); selectable ladder tops at MASSIVE_REALM_600
  (:960-962); legacy LAUNCH_DAY_700 still launchable (:568,:1027-1028);
  custom ceiling MAX_SUPPORTED_TARGET = 10_000 (BotPopulationPolicy.kt:40).
  Login churn is ramp-paced (+50/30 s, 3 logins/2 s, batch 8). Rescales
  F40's wave model: 320 bots → ~3.2-20.8 s vs the 30 s saveall-ack ceiling
  (1.4-9.4× margin); 600/700 modeled upper bounds 39/45.5 s **exceed** the
  30 s ceiling → "world save acknowledgement timed out" + not-clean session
  flag reachable today, engine-agnostic (async defers, doesn't multiply; a
  SQLite single-writer serializes the same wave, plus F30's wedge).
- **F49** `verified` — **Quantified world-tick product contract: DB stalls
  feed a guarded 250 ms budget.** Every experience preset's admission limit
  is maxWorldP99Ms=250 (BotAdmissionLimits; custom default
  BotCustomConfiguration.kt:58, user-bounded 100-300 ms); the admission
  controller pauses bot growth / reduces the effective target on
  worldP99Ms > 250, ≥2 new hard stalls, thermal-severe, storage/memory
  floors (BotAdmissionController.kt:156-166; wiki
  Local-Server-World-and-Bots.md:55-68; hard-stall telemetry in
  world_runtime.cpp:58-66). Gives the F17 measurement phase a ready-made
  pass/fail threshold (hold world p99 ≤ 250 ms at target 320).
- **F50** `verified` — **Async-enable's production edit site is
  world_runtime.cpp, NOT lifecycle.cpp** (corrects F39's edit site):
  lifecycle.cpp builds libpocketrealm.so — the legacy o4 TEST facade
  (consumed only by tools/run_realm_test.py + the self-documented-legacy
  RealmSupervisor lane); production `:world` runs WorldRuntimeService →
  libpocket_world_runtime.so → world_runtime.cpp, whose embedded sequence
  (StartDatabasesEmbedded :564 → InitWorldEmbedded :571 →
  StartNetworkEmbedded :613 → StopEmbedded :680) likewise never calls
  AllowAsyncTransactions. Executing phase-1 at the old site would have been
  a production no-op. S-class unchanged: 4 calls in world_runtime.cpp
  (exact realmd_runtime.cpp:171 precedent; placed only AFTER
  InitWorldEmbedded succeeds, mirroring the upstream "no async during
  startup" invariant, Master.cpp:132) + lifecycle.cpp for test-facade
  parity (or one overlay in Master::StartNetworkEmbedded covering both) +
  an o09-lane assertion that all four DBs report async-enabled. The
  null-guard surface is exactly 12 sites (m_threadBody->Delay:
  Database.cpp:344, :413, :567 + DatabaseImpl.h:57,:66,:75,:84,:95,:104,:113
  + holder->Execute DatabaseImpl.h:182,:191) — the overlay must span both
  files or centralize a guarded enqueue helper. **R4 addendum: the null-
  guard is load-bearing TODAY, independent of the enable** — production
  world_runtime.cpp has a restart path (StopServerEmbedded ×4 +
  ResetForReinit else-branch :682-688) and `m_allowAsyncTransactions` is
  sticky (playerbots set it in session 1, never reset), so a restart cycle
  re-enters with CharacterDatabase async-on against freshly-halted thread
  state; the guard should land first in Phase 1 regardless.
- **F51** `verified` — **Runtime dialect surface is 11 statements, not 8**
  (corrects F28's enumeration): + raw `TRUNCATE` ×2
  (ObjectMgr.cpp:6055-6056 via DirectExecute, bypassing the `_TRUNCATE_`
  macro that the DO_SQLITE branch maps to DELETE FROM) and
  `DELETE … ORDER BY … LIMIT` (libanticheat.cpp:179 — SQLite accepts it
  only with SQLITE_ENABLE_UPDATE_DELETE_LIMIT, which the vendored SQLite
  build does NOT define: native/.deps/src/sqlite/CMakeLists.txt has zero
  hits). Hard syntax failure in the SQLite lane unless the flag is added or
  the statement is rewritten. All three are trivial S-class fixes but
  belong in the guard-test enumeration. (Minor: Player.cpp REPLACE INTO is
  at :16176.)
- **F52** `verified` — **Seeder escape-fidelity gap**: the translator
  rewrites only `\'` and `\"` (seed_realm_db.py:221) — MySQL backslash
  escapes `\n`/`\r`/`\\` in data are left as literal backslash text:
  ~48.7k sites in exactly 3 of 412 files (z2815 dump; ai_playerbot_texts
  21,776+21,776 — the bot LLM help-text corpus; realmd). MariaDB converts
  on INSERT; SQLite would keep literal `\n` → corrupted bot texts/quest
  formatting. One more ordered rewrite pair in the F43 gate. Corpus micro-
  residue (no effort impact): ALTER..ADD INDEX ×4 in 1 file; CHARACTER SET
  utf8mb3 ×1; everything else re-verified zero across all 412.
- **F53** `verified` — **CI never exercises the packaging gates**: ci.yml
  runs repo hygiene + JVM unit tests only (deliberate — Windows runners
  lack the MSYS2/NDK toolchain; no full APK builds; check_sources.py stays
  local). validateDatabaseRuntime, ELF-closure checks, jniLibs staging, and
  provider-pin verification execute only on developer machines; only the
  lockfile tripwire is CI-covered (via pytest). A packaging-gate regression
  cannot fail CI — budget a JVM-test extraction or consciously accept
  local-gate-only (applies to both phases, incl. the F41 fail-loud fix).
- **F54** `verified` — **Arm64 lockfile index-guard missing**: the arm64
  stager hashes whatever Packages.gz it downloads (stage_mariadb_android_
  arm.py:252) and records it into the lockfile without comparing to the
  committed value — no --refresh discipline, unlike the x86_64 lane's
  refuse-on-index-drift guard (stage_mariadb_runtime.py:95-119). Package
  bytes remain in-script pinned (18 hashes), so drift cannot move bytes —
  only the completeness-check basis and the silently rewritten
  package_index.sha256 field. The 2 recorded drift incidents were x86_64
  gpkg-index events.
- **F55** `verified` — **OQ11 resolved: NONE** (all six agents swept
  docs/ independently): no APK-size ceiling (shipped 0.100.2 arm64 full APK
  ≈ 272 MiB, GitHub-Releases-only distribution — no Play constraints), no
  emulator-boot-time release gate, no licensing deadline. The only
  quantified runtime perf constraint is F49's 250 ms gate, which
  *strengthens* control-first. Constraints that bind existing gates
  without reordering: restore is product-contractually required to boot to
  world-ready before committing (Data-Storage-and-Privacy.md:43-45); the
  ledger refuse-on-mismatch is a documented user promise (:29-31).

---

## Part 4 — Ideas Ledger (every researcher contributes, every round)

**Rule:** after the findings report, each researcher MUST append 2–3 ideas
(non-findings — experiments, integration opportunities, measurement ideas),
tagged `[performance]`/`[risk]`/`[integration]`/`[migration]`. Main agent
triages: dedupe, rank by impact × feasibility, promote the best into the
final report's phased plan. Never delete — mark `[done]`/`[promoted]`/
`[rejected: reason]`.

### Seed entries (from Round 0)

- `[performance]` The 1 Hz DB probe already measures round-trip delay —
  promote it to a first-class latency metric in the on-device bench
  (percentiles, not just the 10 s gate).
- `[migration]` `seed_realm_db.py` proves manifest→SQLite is tractable;
  a "rebuild datadir from manifest" cutover would sidestep provider-seal
  migration entirely (fresh initialize on new engine, accept one-time
  character-loss or export/import bridge).
- `[performance]` Hybrid split: keep read-mostly world DB wherever it is
  cheapest to load once; put the write-heavy characters DB on the engine
  with the best commit latency — does any candidate support both roles?
- `[integration]` An in-process engine deletes the fork/exec + PRoot +
  symlink-tree + socket-readiness machinery (~5 fragile integration points
  in Part 1a) — count those as maintenance win, not just performance.
- `[risk]` Two MariaDB majors already make cross-ABI backup/restore
  impossible — a single embedded engine could *unify* the lanes; verify.
- `[performance]` `innodb-flush-log-at-trx-commit=2` + `sync-binlog=0`
  (already) may capture most of the commit-latency win with zero migration
  cost — the control baseline may be stronger than assumed. Must be
  measured against data-loss tolerance.

### Round 1 (deduped by main agent; 12 promoted to the ledger, 2 rejected)

- `[performance]` **On-device telemetry first, zero code cost** (A+B+D
  merged): capture the existing 1 Hz DB-probe output (RandomPlayerbotMgr.cpp
  ~:4525) + the existing per-query `[%u ms] SQL:` DEBUG_FILTER_LOG lines
  (DatabaseMysql.cpp:~189 / DatabaseSqlite.cpp:~142) during a scripted
  200-bot login→play→save→logout session; export p50/p99. Precondition for
  any evidence-based verdict; converts F17 to measured.
- `[performance][risk]` **Two-knob A/B on the control lane** (A+B+E
  merged): (a) enable async transactions in the embedded facade
  (native/pocket-runtime/src/lifecycle.cpp — in-repo file, no submodule
  edit); (b) `innodb-flush-log-at-trx-commit=2` + pool 128M→64M (requires
  the F25 test-contract decision). Individually revertible; measures
  world-tick jitter, shutdown duration, probe distribution. Durability
  caveat: =2 accepts ≤1 s power-cut rollback (F27) — same contract the
  in-tree SQLite lane already uses.
- `[performance]` **First-boot spawn batching** (A+D merged): coalesce the
  ~1,650 per-migration CLI spawns (F20) into batched client sessions
  (keep per-file hash verification + ledger rows by verifying each entry
  before concatenation). Pure Kotlin; est. −40-200 s first boot; no
  manifest/seal impact.
- `[migration]` **Ship the SQLite bootstrap as hash-pinned pre-built
  `.sqlite` assets** (C): seed → gzip → asset tree with per-file sha
  (mirroring the .sqlz pattern) instead of replaying 412 translated
  migrations on-device; MariaDB manifest untouched; validation harness
  compares against the MariaDB-built reference once in CI.
- `[risk]` **Dialect conformance CI gate** (C): run the translator × 412
  manifest files + the 8 runtime statements against SQLite 3.46.1
  in-memory, with the ODKU assignment-order trap (F28) as an explicit
  fixture.
- `[migration]` **`@var` constant-folding preprocessor** (C): standalone
  dump-agnostic stage resolving the 82-file guid-relocation pattern;
  reappears on every classic-db submodule bump.
- `[integration]` **SQLite connection-layer hardening micro-patch** (B+D
  merged, S-class): busy_timeout 2 ms → ~100-500 ms + retry/BEGIN IMMEDIATE
  + commit-failure rollback (F30), fix QueryResultSqlite double-scan +
  wrapper leak, fix inverted isQuery, align QueryNamed 0-row semantics —
  all via `native/patches/` replacement files behind the existing
  DO_SQLITE flag.
- `[risk]` **Boot-time `PRAGMA integrity_check` + `VACUUM INTO` rebuild
  gate** (E) for any SQLite prototype — MariaDB lane has no equivalent
  check today.
- `[migration]` **Character export bridge before any engine decision** (E):
  mariadb-client-driven dump of classiccharacters (+realmd account rows) to
  the exports dir via shipped tools only — converts every future provider
  bump / engine swap from "characters lost" (F24/E7-class events) to
  "characters restorable"; S-sized.
- `[integration]` **Formalize the SQLite supply chain** (F): sources.json
  component entry + fetch-and-pin script (url + sha256) + fold the sqlite
  stage into tools/build_o09_realm_runtime.py's dep set; closes the F32 gap
  regardless of the engine decision.
- `[migration]` **Dual-provider APK cutover window** (F): ship both the
  MariaDB closure and the new engine's seed for one release; translate the
  sealed datadir on first boot; drop MariaDB the next release. Respects
  fail-closed seals + append-only manifest at a temporary +39-57 MiB cost.
  Alternative from C: re-initialize-from-manifest for
  classicmangos/classiclogs + translate-and-rebuild only the user-state
  slice (characters + realmd) — smaller export set; both need the export
  bridge above.
- `[performance]` **Hybrid split check** (seed, refined by R1 facts): keep
  read-mostly world DB wherever cheapest; put write-heavy characters DB on
  the best commit-latency engine — R1 note: F18/F19 make the *engine*
  choice second-order for commit latency vs the async-disabled + quantum
  issues; re-evaluate after the two-knob A/B.
- `[rejected: F33]` Embedded-MariaDB arm64 link spike (B's idea) —
  licensing disqualifies in-process embedded MariaDB regardless of link
  success.
- `[rejected: already implemented]` Add a Gradle check refusing
  pocketLane=database + x86_64 (F's idea) — exists at
  build.gradle.kts:727-728.

### Round 2 (deduped by main agent)

- `[performance]` **Event-driven SqlDelayThread wakeup** (A2/F merged):
  replace the 10 ms sleep-poll with condition-variable signaling (one-anchor
  overlay, same pattern as RESULT_QUEUE_ANDROID) — removes the F19 quantum
  floor for all queued writes; S-class; engine-agnostic; zero durability
  impact.
- `[risk][measurement]` **Measure before adopting trx_commit=2** (A2+D2+E
  merged): log SqlDelayThread queue depth + graceful-stop drain duration
  (HaltDelayThread window) + first-AddRandomBots timestamp into the existing
  performance_status plumbing; one device session decides the F40 model and
  whether the fsync term justifies the F45 contract change.
- `[integration]` **The concrete Phase-1 change set** (A2+D2+F merged):
  lifecycle.cpp async-enable ×4 + Database.cpp null-guard overlay + fix the
  no-op backend flags to `-DSQLITE`/fail-loud backend selection
  (build_o09_realm_runtime.py:1868 — required before ANY SQLite-lane
  experiment can be trusted) + optional stop-budget honesty (distinct
  "save-acknowledged" vs "save-durable" supervisor states).
- `[performance]` **Five-lever control package** (B): async-enable +
  trx_commit=2 (gated on the F45 decision) + pool 128→64M + query
  connections 1→2-3 (config-only) + poll 10→2 ms. Each independently
  revertible.
- `[migration]` **Single-source-of-truth seeder** (C2): replace the 5-source
  seed subset with a fixed translator replaying the SAME pinned 412-source
  manifest chain (comment-aware splitter + @var folding + ALTER..CHANGE
  emulation + index regeneration) — kills the z2815/z2830 gap and the
  407-source divergence by construction.
- `[migration]` **Dual-provider export window with blob-safe bridge** (C2):
  one release shipping both providers + a runClient exporter
  (HEX(blob)/X'…' TSV, TSV-unescape, tz normalization); release N+1 drops
  MariaDB. The only cutover consistent with F13/F24/F44.
- `[dialect]` **ODKU threshold guard test** (C2): pin tier to
  MySQL-equivalent semantics (points at 9/10, 29/30, 59/60) if the LLM lane
  ever runs on SQLite — the C2 assignment-order drift is otherwise
  undetectable until a bot's tier visibly lags.
- `[test]` **Dirty-kill data-survival acceptance** (E): write a sentinel via
  the world lane, killForTest :world, recover :database, assert the
  committed sentinel survives — the matrix today proves recoverability, not
  durability; guards any future async/trx_commit change cheaply.
- `[durability]` **One power-cut contract, both engines** (E): extend the
  DatabaseConfigPolicyTest pattern to the SQLite lane (require
  synchronous=FULL) or record the ≤1 s acceptance as a decision artifact —
  removes the F45 asymmetry before any swap effort.
- `[provenance]` **Pin the amalgamation symmetrically with MariaDB** (F):
  sqlite zip sha256 as a real pin (sources.json entry + tripwire test) —
  makes the shadow lane supply-chain-clean; last asymmetry in the
  comparison.
- `[harness]` **Port pocket_lifecycle_test to the o09 facade, backend-
  parameterized** (F+D2): moves the SQLite candidate from dormant code to a
  continuously verified lane; also the vehicle for the F26 defect-fix
  regression tests.

### Round 3 (deduped by main agent)

- `[measurement]` **Parameterize the on-device bench by preset** (A3+B3):
  world-p99, probe RTT percentiles, and saveall-ack duration at the shipped
  default 320 AND top built-in 600, with the 250 ms admission gate (F49) as
  pass/fail and the controller's effective-target reductions logged as a
  product-level DB-stall symptom counter; log saveall-ack duration vs the
  30 s ceiling (F48 says top presets can time out today).
- `[integration]` **Phase-1 parity guard** (D3): land the 4
  AllowAsyncTransactions calls in BOTH world_runtime.cpp (production) and
  lifecycle.cpp (test facade) + an o09-lane assertion that all four DBs
  report async-enabled after start — prevents facade tests passing while
  production silently regresses (the F50 trap).
- `[risk]` **Size the null-guard overlay for all 12+ m_threadBody sites**
  (D3): Database.cpp:344/:413/:567 + DatabaseImpl.h ×9, or centralize a
  guarded enqueue helper in the replacement header.
- `[migration]` **Row-level fidelity harness** (C3): seed identical sources
  through both mariadb and the rebuilt SQLite seeder, diff every table —
  would have caught the F52 escape gap mechanically and catches future
  splitter misses.
- `[risk]` **Make the DELETE..LIMIT policy explicit** (C3): add
  SQLITE_ENABLE_UPDATE_DELETE_LIMIT to the vendored SQLite build or rewrite
  libanticheat.cpp:179 as a correlated-subquery delete; route the three
  macro-bypass statements (F51) into the DatabaseEnv.h dialect layer with a
  grep-verifiable guard test.
- `[harness]` **CI-cover the packaging gates** (F3): refactor
  validateDatabaseRuntime / mariaFiles e_machine / manifest checks into
  pure-JVM functions with unit tests so the sole CI Gradle run fails on
  gate regressions (closes F53).
- `[provenance]` **Arm64 index-guard symmetry** (F3): make
  stage_mariadb_android_arm.py compare the downloaded Packages.gz sha
  against the committed lockfile value and refuse without --refresh
  (mirrors the x86_64 lane; closes F54).
- `[risk]` **DB-death sentry** (E3): observe() runs only at lifecycle
  transitions — a periodic mid-session watch of DATABASE/WORLD (or a
  daemon-exit callback) converts silent mid-session mariadbd death into
  immediate ERROR+RECOVERY. Engine-neutral, S-class.
- `[risk]` **Corrupted-datadir fixture test** (E3): pin the intended
  fail-closed outcome of recover() on a deliberately corrupted ibdata1 —
  the corruption story is currently implicit.
- `[performance]` **Profile-aware save scheduler** (A3, conditional): if
  F17 shows waves dominate at 320+, staggered 15-min save timers / tiered
  bot-save batching is an engine-agnostic S/M lever upstream of any engine
  decision.
- `[cleanup]` Telemetry label drift: RUNTIME_BUILD_ID "o13-…" vs status
  JSON "o09-…" vs pinned 082afd6 (D3) — harmless; worth cleanup before
  build IDs are used for seal forensics.

### Round 4 (deduped by main agent)

- `[measurement]` **Free continuous DB telemetry** (A4): expose
  `GetDatabaseDelay("CharacterDatabase")` in the world status JSON (the
  channel that already feeds tick p99) — the probe already samples RTT
  every ~10 s; zero new machinery. Use the probe's 15 s lost-result expiry
  as the async-enable regression canary in Phase-1 tests.
- `[integration]` **One `EnqueueAsync`/`SafeDelay` helper** (B4+D4 merged):
  a single null-checking member covering all 12 sites (3 Database.cpp
  Delay + 7 DatabaseImpl.h Delay + 2 holder->Execute) instead of per-site
  guards — smaller diff, uniform behavior; land it FIRST in Phase 1 (it is
  load-bearing today per the F50 addendum, independent of the enable).
- `[performance]` **Pool lever as a device-class tier** (B4): encode the
  128→64M trim in DatabaseConfigPolicy's existing per-ABI branching so the
  `WithoutRelaxingDurability` test pattern extends to pin it without
  touching durability lines.
- `[migration]` **Self-maintaining dialect inventory** (C4): guard script
  that greps effective source for the non-macro dialect classes
  (REPLACE INTO, INSERT IGNORE, ODKU, MySQL date fns, raw TRUNCATE,
  DELETE/UPDATE..LIMIT) and fails CI on any hit outside the enumerated
  file:line list — turns inventory rot (this round's line drift) into a
  build failure.
- `[migration]` **Export-derived seeding alternative for Phase 2** (C4):
  instead of re-translating 412 files, apply the manifest on the MariaDB
  side (already the system of record) and construct the SQLite seed
  through the F44 bridge — dialect fidelity for DATA comes free; the
  seeder shrinks to DDL translation; F29/F43/F52 collapse for the seed
  path. Cost: the export bridge becomes a prerequisite of seeding. Cost
  this before committing to the splitter rebuild.
- `[dialect]` **DELETE..LIMIT by portable rewrite, not build flag** (C4):
  `DELETE … WHERE rowid IN (SELECT rowid … ORDER BY time ASC LIMIT ?)` is
  semantically equivalent and avoids re-vendoring SQLite with
  SQLITE_ENABLE_UPDATE_DELETE_LIMIT.
- `[provenance]` **Arm64 index pin two-liner** (F4): move
  `index_sha256` from recorded to pinned-expected in
  stage_mariadb_android_arm.py + value-compare in check_sources.py —
  closes F54 fail-closed, no workflow change.
- `[test]` **Dirty-kill-during-PAUSED** (E4): once the parallel M5
  companion-mode work lands, extend the crash matrix with device-death
  while PAUSED → recovery → fresh unpause + stop-from-PAUSED clean-stop.
- `[promoted]` D4's "null-guard unconditionally first" → folded into the
  F50 addendum; F4's "quote both size metrics" → folded into F31 and the
  report spec.

---

## Part 5 — Final report spec (the converged deliverable)

When convergence is declared, write
`docs/plans/mariadb-replacement-research-report.md` containing, in order:

1. **Executive verdict** — replace vs tune; the recommended backend (or
   "keep tuned MariaDB"); one-paragraph reasoning; comparison against the
   do-nothing and tune-in-place controls.
2. **Theoretical gains model** — per metric (boot/startup time, world-thread
   DB stall latency, save-transaction throughput, RSS, APK size, x86_64
   PRoot elimination): expected magnitude, reasoning chain, cited digest
   facts, confidence, and the assumptions that must be checked on-device.
3. **Difficulty assessment** — layers touched per candidate (CMaNGOS DB
   layer overlays, Kotlin `database/` package, build scripts, lockfiles,
   Gradle lanes, migration tooling) with effort class S/M/L per phase.
4. **Advantages / disadvantages / risks per candidate** — including the
   tuned-MariaDB control; each disadvantage traced to a digest fact.
5. **Verdict matrix** — all six agents × Q1–Q5: final answer + confidence.
   Must be unanimous on Q2 (the recommendation) or the report carries an
   *Unresolved / escalated* section explaining the split and what evidence
   would resolve it.
6. **Phased implementation plan** — concrete, ordered phases ready to
   execute later (expected shape, refined by the study): on-device
   measurement harness → control-baseline tuning test → prototype lane
   (e.g., reactivate `DO_SQLITE`) → dialect/migration tooling → provider
   rework in Kotlin/Gradle → cutover + rollback. Each phase: files,
   exit criteria, rollback.
7. **On-device measurement plan** (theory must be falsifiable) — benchmark
   harness design for the Retroid Pocket 6 reusing the coexist-test
   methodology (`G:\NPU llm\scripts\coexist-test-e2b.sh` pattern): metrics
   = world-thread DB stall time, save-transaction latency percentiles, bot
   login-burst duration, boot + first-boot migration time, steady-state
   RSS, flash-write volume, thermal coexistence with LLM worker + client.
   Include the scripted scenario (sustained busy-party session) and
   before/after protocol.
8. **Accepted caveats / unresolved questions registry** — everything the
   study could not settle, each with the measurement or experiment that
   would settle it.

---

## Part 6 — How to run a round (main-agent checklist)

1. Copy Part 1 + the agent's 2b brief + Part 3 digest (current) + open
   questions + disagreement queue into six self-contained prompts.
2. Launch all six in one parallel batch (read-only general-purpose agents).
3. On completion: verify every claimed finding in source; update Part 3;
   update the disagreement queue; append round ideas to Part 4.
4. Classify per 2c; relaunch all six or mark clean; update Part 0.
5. On convergence (2 clean rounds) or the 6-round cap: write the Part 5
   report; final Part 0 update.
