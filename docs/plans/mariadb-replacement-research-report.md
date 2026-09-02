# MariaDB Replacement Research Report

**Status: CONVERGED** — final deliverable of the six-agent round-robin study
defined in `mariadb-replacement-research-plan.md`. Six rounds were run
(2026-08-21); Rounds 5 and 6 were consecutive clean rounds (all six agents
returned, zero new load-bearing facts, unanimous verdicts on Q1–Q5, empty
disagreement queue), so convergence is declared within the 6-round cap.
Evidence base: 55 digest facts (F1–F55) in the plan's Part 3 — 54 verified
in source by the main agent, 1 open (F17: no on-device DB latency benchmark
exists; by design a measurement, not a source fact). Every claim below cites
its digest facts; the digest carries the `file:line` evidence.

> **OWNER DECISION (2026-09-02), post-study:** MariaDB/MySQL remains the
> stable, default database provider, permanently. SQLite remains
> experimental, shipped as a separate APK built with `-PsqliteProvider`
> (the default build is the MariaDB APK); both providers and both APK
> artifacts are kept permanently. The dual-provider window (Phase-2 G7)
> is the permanent endgame arrangement — the Phase-2 "cutover" and
> supply-chain deletion framing below (P8 of the implementation plan) is
> DESCOPED and will not be executed. This report is retained as the
> study record that produced that arrangement.

---

## 1. Executive verdict

**Do not replace MariaDB today. Keep it, and fix the engine-agnostic defects
that actually cause the DB pain. Re-evaluate the in-tree SQLite lane as a
gated second phase only after on-device measurements say the control
package is insufficient.**

One paragraph of reasoning: the study's central discovery (F18/F38/F50) is
that the dominant database costs on the device are **not properties of
MariaDB at all** — the embedded world facade never enables async
transactions, so World/Login(:world)/Logs writes (and character saves until
the bot manager self-enables) execute inline on the calling thread
*including the per-commit fsync* (F18/F38); the async worker that does run
polls on a 10 ms sleep quantum (F19); and first boot burns ~1,650
fork+exec CLI spawns replaying 127.7 MB of migrations (F20). Every one of
those is identical under SQLite — a swap buys only second-order wins
(socket round-trip, mariadbd RSS, APK mass, x86_64 PRoot) at M-to-L cost
against ten verified gates (F26/F29/F30/F32/F41/F42/F43/F44/F45/F51/F52),
while the swap's own lane currently has a 2 ms busy-timeout dropped-write/
wedge hazard (F30), a silently-downgraded power-cut durability contract
(F45), and zero acceptance coverage on the shipped build facade (F42).
The control package — an S-class, fully revertible set of in-repo changes —
attacks the verified mechanisms at near-zero risk.

Comparison against the controls:
- **Do nothing:** the 250 ms world-p99 product contract (F49) keeps eroding
  under inline write stalls; at 600/700-bot presets the save wave already
  models past the 30 s saveall-ack ceiling (F48) — reachable today,
  engine-agnostic.
- **Tune MariaDB in place (the chosen control, expanded):** the highest-
  leverage items are not my.cnf knobs but the async-enable + null-guard +
  batching changes below; the classic `trx_commit=2` knob is real but is a
  deliberate durability-contract change, test-forbidden today (F25), and is
  split out as an explicit decision rather than bundled (F45).
- **Swap now:** strictly dominated until the Phase-2 gates close and a
  measurement proves the need.

Unanimous (Q2 identical across all six agents from Round 2 through
Round 6; confidence high).

---

## 2. Theoretical gains model

### 2.1 What Phase 1 (control package) buys

| Metric | Expected magnitude | Reasoning chain | Facts | Confidence |
|---|---|---|---|---|
| World-thread DB stall | Inline COMMIT-fsync + statement execution removed from the calling thread for World/Login(:world)/Logs (always) and characters (pre-churn) | Async-enable routes Execute/CommitTransaction/ExecuteStmt through the already-running delay thread; drain proven safe at stop (KickAll before halts; destructor sweep) | F18, F38, F39, F50 | high (mechanism) / low (magnitude — F17) |
| Async queue latency floor | 0–10 ms → ~0–2 ms per queued op | Poll constant 10 ms is one anchor/overlay; condvar wakeup removes it entirely | F19, F47 | high |
| Sync-read contention | Concurrent map/world sync reads un-serialize | `*DatabaseConnections` config keys default 1; raising to 2–3 is a rendered-config key (cap 16; max-connections=24 leaves headroom) | F47 | med |
| First-boot wall time | −40–200 s (modeled) | ~1,650 spawns → ~415 by batching per-database pending SQL into one client session (per-entry hashes/ledger preserved) | F20, F23 | med |
| Steady-boot | 412 ledgerStatus spawns batchable; ~80 Load* scans unchanged | Engine-agnostic scan cost stays | F10, F20 | med-low |
| RSS | −60–130 MB | Pool 128M→64M: world DB is 154/189 MyISAM (page cache, not the pool); pool only serves the characters hot set | F21 | high (RAM), low (latency impact) |
| Commit latency (only if the split-out decision is taken) | fsync-per-commit → OS-buffered; ~10–100× single-thread commit latency | trx_commit=2 / SQLite synchronous=NORMAL are the same contract; loss window is power-cut-only (separate process preserves kernel page cache on process kill) | F25, F27, F45 | high (mechanism) / med (magnitude) |
| APK size / PRoot | 0 / 0 | Phase 1 changes no packaging; PRoot is x86_64-emulator-only and Wine-owned | F31, F36 | high |

Assumptions that must be checked on-device: per-query socket RTT (est.
~0.05–0.2 ms web-cited; the probe already prints it — F9/F17), UFS fsync
latency (1–10 ms class), and the actual world-tick share of DB time.

### 2.2 What Phase 2 (gated SQLite swap) would additionally buy

| Metric | Magnitude | Facts | Confidence |
|---|---|---|---|
| APK download size | **−12.26 MiB arm64 (measured: 12,858,890 B DEFLATE of the 288,993,581 B current APK); ≈−19.2 MiB gross / ≈−18 net x86_64 (zlib-9 estimate — no x86_64 APK exists to measure)** | F31 | high arm64 / med-high x86_64 |
| Installed footprint | −38.67 MiB arm64 / −57.24 MiB x86_64 (jniLibs extracted at install) + ~8 MiB provider assets + duplicate OpenSSL 5.80/8.39 MiB per ABI | F31, F46 | high |
| RSS | mariadbd process (pool + overhead) replaced by in-process page cache | F21, F31 | med-low |
| x86_64 PRoot | DB-path shim eliminated on the emulator lane; artifacts stay in full APKs (Wine needs them); the DB lane is arm64-enforced | F36 | high (scope) / unmeasured (cost) |
| First boot | 412-CLI-spawn replay replaced by seed/asset path — minutes-class potential | F20, F29 | low-med (tooling-gated) |
| Save throughput / world-thread stall | **No fundamental gain** — single-writer serialization and the async machinery are engine-agnostic; WAL adds the F30 wedge until hardened | F7, F19, F30 | high |
| Supply chain | Deletes 1,067 lines / 3 tools, 2 lockfiles (34 pins on a live index that drifted twice), Wine ld-linux byte-match coupling, duplicate OpenSSL | F32, F46, F54 | high |

---

## 3. Difficulty assessment

| Work item | Layers touched | Class |
|---|---|---|
| **P1a Null-guard helper** (12 sites: Database.cpp:344/:413/:567 + DatabaseImpl.h ×7 Delay + ×2 holder->Execute) | native/patches overlay (cmangos shared DB) | **S** — and load-bearing **today** via the production restart path + sticky async flag (F50) |
| **P1b Async-enable ×4** in world_runtime.cpp after InitWorldEmbedded + lifecycle.cpp parity + o09 assertion | realm-runtime (in-repo), pocket-runtime (in-repo) | **S** |
| **P1c Fail-loud backend selection** (-DSQLITE / refuse ambiguous flags) | tools/build_o09_realm_runtime.py | **S** |
| **P1d Levers** (connections 2–3, poll 2 ms/condvar, pool 64M as device-class tier) | deployed config + one anchor overlay + DatabaseConfigPolicy (+ its test) | **S** |
| **P1e First-boot spawn batching** | DatabaseEngine.kt apply loop | **S–M** |
| **P1f trx_commit=2 decision** | DatabaseConfigPolicy + test edits + decision artifact (covers both engines) | **S** + a product decision |
| **P2 SQLite lane** (all ten gates) | CMaNGOS patches (F26/F51), Kotlin database/ 11 files/2,271 lines with revision+ledger reimplementation (F42), seeder rebuild or export-derived seeding (F29/F43/F52), provenance pin (F32), o09 lifecycle port (F42), blob-safe dual-provider export bridge (F44), durability parity (F45), Gradle/provider rework (F34) | **M to L** |

No submodule edits are required for Phase 1's C++ items — the guard rides
`native/patches/` replacement files; the enable sites are in-repo facades.

---

## 4. Advantages / disadvantages / risks per candidate

**Tuned MariaDB (control) — 34/40, first.**
Advantages: proven ACID with acceptance-tested crash recovery on a
battery device (F23); zero seal/migration exposure (F13 untouched);
immediately revertible levers; the entire dialect surface (11 runtime
statements) stays native (F28/F51).
Disadvantages: keeps the packaging mass (F31), the dual-major lanes and
live-index maintenance (F2/F32/F54), Wine coupling (F32), same-device-only
backups with no export tool (F24), and CI blindness to packaging gates
(F53). The 600/700-bot save-wave ceiling persists (F48) unless the
split-out durability decision is taken.

**In-tree SQLite (DO_SQLITE) — 22/40, gated second.**
Advantages: only candidate with a plausible end-state — in-process, public
domain, −12–19 MiB download / −39–57 MiB installed, deletes the MariaDB
supply chain and duplicate OpenSSL, first-ever portable backups possible.
Disadvantages (each traced): 2 ms busy-timeout dropped-write + commit-
failure wedge under exactly the bot-save waves (F30); silent power-cut
durability downgrade unless synchronous=FULL is test-encoded (F45, F27);
seeder covers 5/412 sources with 0 indexes and a comment-blind splitter
(F29/F43) plus ~49k backslash-escape corruptions (F52); the o09 build
flags are no-ops — real switch is `-DSQLITE=ON` (F41, silent-MySQL trap);
zero shipped-facade coverage (F42); Kotlin revision/ledger machinery is
MariaDB-bound (F42); runtime dialect surface is 11 statements incl. the
ODKU assignment-order trap (F28/F51); fail-closed seals force a
dual-provider cutover with blob-safe export (F13/F44).

**PostgreSQL (Termux) — 20/40, rejected.** Same separate-process model,
per-connection backend processes, farthest dialect, all-new provisioning.
**libSQL — 19/40, rejected.** Inherits every SQLite gate plus a new
Rust/provenance burden in a pin-everything repo. **LMDB — 18/40,
RocksDB — 17/40, rejected.** KV stores: lose SQL, the schema, and the 412-
migration surface; rewrite-class. **DuckDB — 16/40, rejected.** Columnar
OLAP against DELETE+re-INSERT OLTP saves.
**Embedded MariaDB (libmysqld) — DISQUALIFIED.** GPL-2.0-only cannot link
into the GPL-3.0 app — the separate process *is* the license boundary
(F33; repo notices state it verbatim; upstream license re-verified).

---

## 5. Verdict matrix (all six agents × Q1–Q5, final)

All verdicts below were **identical across Agents A–F** (unanimous since
Round 2; re-confirmed Rounds 3–6 with zero disagreements).

| Question | Unanimous answer | Confidence |
|---|---|---|
| **Q1** Is the DB a material bottleneck today? | **Yes, in exactly three engine-agnostic places:** first-boot CLI churn (~1,650 spawns / 127.7 MB — F20); world-thread inline write stalls incl. COMMIT fsync (World/Login(:world)/Logs always, characters pre-churn — F18/F38/F50); serialization + 10 ms quantum ceiling (F7/F19/F47). **Not** throughput, memory, or socket-bound (F21). A stall closes the bot-login gate within ≤~15 s (F9) and erodes the guarded 250 ms world-p99 budget (F49); at 600/700 bots the save wave models past the 30 s saveall-ack ceiling today (F48). | high (mechanism) / low (magnitudes — F17 open) |
| **Q2** Best performance/effort/risk trade-off? | **Control baseline first** (Phase 1 package below), **trx_commit=2 split out** as a one-time power-cut-contract decision covering both engines; **in-tree SQLite = gated Phase 2** conditional on measurements; **all other candidates rejected** (embedded MariaDB disqualified on licensing). | high |
| **Q3** Theoretical gains? | Per §2 tables: Phase 1 removes the stall classes at S-cost (mechanism high, magnitudes unmeasured); Phase 2 adds measured APK wins (−12.26 MiB arm64 download / −38.67 MiB installed) and the supply-chain deletion, but no fundamental runtime gain. | high (direction) / med (magnitude) |
| **Q4** How hard? | Phase 1 = S per item (< days); Phase 2 = M→L (ten gates, layers per §3). | high |
| **Q5** Decisive risks & flip evidence? | Per §4. **Flips to Phase 2:** on-device measurement (F17) showing Phase 1 cannot hold world p99 ≤ 250 ms at 320 bots (F49) while gates close at budget; a hard APK-size ceiling (none exists — F55); a durability incident attributable to the separate-process model. **Flips to control-only:** measurements attributing stalls to non-DB sources; Phase-2 gates blowing past M-class. | high |

Agent-by-agent final confirmations: A (perf), B (survey + 34/22/rejected
table), C (dialect S / gated phase M), D (integration, S-class phase 1),
E (durability ladder + ops table), F (packaging both-metric accounting) —
all CONFIRM, Rounds 5 and 6, findings NONE.

---

## 6. Phased implementation plan

**Phase 0 — measurement harness first (S).**
- Files: expose `GetDatabaseDelay("CharacterDatabase")` + SqlDelayThread
  queue depth + graceful-stop drain duration + saveall-ack duration in the
  existing world status/performance channels; log first-AddRandomBots
  timestamp. Zero new machinery (F9/F19 already produce the samples).
- Exit criteria: one device session at ALIVE_REALM_320 (and one 600 preset)
  producing p50/p99 for probe RTT, world-p99 vs the 250 ms gate, drain
  duration vs 30 s, first-boot spawn total. Rollback: delete the telemetry.

**Phase 1 — control package (S per item, each independently revertible).**
1. **Null-guard helper FIRST** — one guarded enqueue member covering all 12
   `m_threadBody` sites (Database.cpp:344/:413/:567; DatabaseImpl.h ×9),
   via `native/patches/`. Load-bearing today (restart path + sticky flag —
   F50). Exit: build + lifecycle test green; build-driver tripwire greps
   that no unguarded site remains. Rollback: revert overlay.
2. **Async-enable ×4** in `world_runtime.cpp` after `InitWorldEmbedded`
   succeeds (upstream invariant: no async during startup), +
   `lifecycle.cpp` parity + an o09-lane assertion all four DBs report
   async-enabled (the F50 facade-trap guard). Exit: assertion green; probe
   15 s expiry used as the regression canary; graceful-stop soak at 320
   bots within budgets. Rollback: remove 4 calls.
3. **Fail-loud backend selection** — replace the no-op `-DDO_*` pair with
   `-DSQLITE=<bool>` + CMake assert printing the selected backend (F41).
   Required before any Phase-2 experiment can be trusted. Rollback: revert.
4. **Levers** — query connections 1→2-3 (config render), poll 10→2 ms or
   condvar (overlay), pool 128→64M as a device-class tier in
   DatabaseConfigPolicy (test pattern extends). Each keyed/anchored
   separately. Rollback: per-lever revert.
5. **First-boot spawn batching** — coalesce per-database pending SQL into
   one client session per batch (keep per-entry hash verification + ledger
   rows). ~1,650 → ~415 spawns. Exit: first-boot time drop; ledger audit
   identical. Rollback: revert loop restructure.
6. **trx_commit=2 decision (split out)** — decide the power-cut contract
   ONCE for both engines: either test-encode FULL/synchronous=FULL parity,
   or record the ≤~1 s acceptance as a decision artifact; then (if
   accepted) flip the config + extend DatabaseConfigPolicyTest + add a
   dirty-kill data-survival assertion (sentinel write → kill :world →
   recover :database → assert survival — absent today, E-lane).

**Phase 2 — gated SQLite lane (M→L; enter only on flip evidence).**
Gates (all mandatory, order roughly by dependency): G1 fail-loud selection
(P1 item 3); G2 amalgamation pin + tripwire (sources.json entry, fetch
script, symmetric with MariaDB — F32); G3 connection hardening (busy ≥
100–500 ms, BEGIN IMMEDIATE, rollback-on-commit-failure; fix double-scan/
leak/inverted-flag/QueryNamed — F26/F30); G4 durability contract resolved
(P1 item 6, covering the SQLite lane explicitly); G5 dialect: 11-statement
rewrite + guard tests (ODKU increment-fold fixture at 9/10, 29/30, 59/60;
DELETE..LIMIT by portable rowid rewrite or explicit build flag) + self-
maintaining inventory script (F28/F51); G6 seeding: EITHER rebuild the
seeder (comment-aware splitter, @var replay-order folding, ordered escape
pairs — exact expected total 49,243 sites — index regeneration,
single-source replay of the 412 manifest) OR export-derived seeding via
the G7 bridge (manifest applied MariaDB-side as system of record; seeder
shrinks to DDL translation) — cost both before choosing; row-level
fidelity harness diffing both engines' output (F29/F43/F52); G7 blob-safe
dual-provider export bridge (HEX(longblob)/X'..' TSV round-trip;
characters.sql:61/:156) + one release window carrying both providers
(F13/F44); G8 Kotlin revision/ledger engine-side reimplementation
preserving refuse-on-mismatch + the negative test (F42); G9 o09 lifecycle
port: pocket_lifecycle_test parameterized over backend through
run_realm_test.py (F42); G10 Gradle/provider rework (F34) + boot-time
PRAGMA integrity_check gate. Exit criteria per gate; overall exit = world
p99 ≤ 250 ms at 320 bots on the SQLite lane AND all Phase-1 gains
reproduced. Rollback: the dual-provider window keeps MariaDB bootable
until the cutover release.

**Standing hygiene (any phase):** close F54 (arm64 index pin guard,
two-liner); CI-cover the packaging gates via pure-JVM extraction (F53);
DB-death sentry; corrupted-datadir fixture test; dirty-kill-during-PAUSED
coverage once the parallel M5 work lands.

---

## 7. On-device measurement plan (falsifiable)

Device: Retroid Pocket 6 (arm64 lane; x86_64 emulator lane secondary).
Methodology: reuse the coexist-test pattern (`G:\NPU llm\scripts\
coexist-test-e2b.sh` style) — scripted scenario, before/after on the same
build pair, thermal coexistence with the LLM worker (cores 3–5) and the
Wine client active.

Metrics (each mapped to its digest fact):
1. World-thread DB stall time: world tick p99 per DB handle — pass/fail vs
   the 250 ms admission contract (F49); the controller's effective-target
   reductions logged as a symptom counter.
2. Save-transaction latency percentiles + saveall-ack duration vs the 30 s
   ceiling, at presets 320 (default) and 600 (F40/F48).
3. SqlDelayThread queue depth + graceful-stop drain duration (HaltDelay-
   Thread window) vs the 60 s/30 s budgets (F23/F40).
4. Bot login-burst duration + probe RTT percentiles (the ~10 s probe; F9).
5. Boot: first-boot migration total (spawn count + wall time) and steady
   boot (412 ledger checks + ~80 Load* scans) (F10/F20).
6. Steady-state RSS (mariadbd vs pool trim) and flash-write volume.
7. Thermal: coexistence deltas with LLM + client running.

Protocol: (a) baseline build → one scripted busy-party session (sustained
combat with bot party, forced saveall, 200→320-bot login ramp, graceful
stop) at each preset; (b) Phase-1 build with levers landed one at a time →
same script; (c) if Phase 2 is entered, the same script on the SQLite lane
behind the same configs. Decision rule (pre-registered): if post-Phase-1
world p99 ≤ 250 ms at 320 bots sustained AND saveall-ack < 30 s at 600 →
Phase 2 stays closed; if either fails with DB-attributed stalls → open
Phase 2 gates in §6 order.

---

## 8. Accepted caveats / unresolved questions registry

| # | Unresolved item | What would settle it |
|---|---|---|
| 1 | **F17 — no on-device DB latency/stall magnitudes exist**; every magnitude in §2 is a model | The Phase-0 harness session (§7); the probe already prints RTT |
| 2 | x86_64 download-size delta is a zlib-9 estimate (no x86_64 APK exists) | Produce one x86_64 full APK or a Gradle size-report task; zipinfo it (F31) |
| 3 | trx_commit=2 / synchronous=FULL power-cut contract undecided | The explicit one-time decision artifact + both-engine policy tests (F25/F27/F45) |
| 4 | Whether steady-boot AddRandomBots fires early enough that characters async-enable covers the boot login wave | First-AddRandomBots timestamp telemetry (Phase 0) |
| 5 | SQLite-lane behavior under real cross-process login load (F30 wedge reproduction) | G3 hardening + the cross-process contention test on device |
| 6 | Seeder-vs-bridge seeding choice for Phase 2 | Cost both (G6) against the fidelity harness |
| 7 | Parallel-session churn: PlayerbotLlmMemory.cpp / build_o09_realm_runtime.py line refs are point-in-time (files grew mid-study) | The self-maintaining dialect-inventory guard (G5) + build-driver tripwires |
| 8 | M5 companion-mode (PAUSED) crash coverage (~5 lines of new tests) | Dirty-kill-during-PAUSED acceptance test (standing hygiene) |

---

*Report generated by the Round 1–6 protocol execution, 2026-08-21. Digest
and round logs: `mariadb-replacement-research-plan.md` Parts 0–4.*
