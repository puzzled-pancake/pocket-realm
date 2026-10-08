# Pocket Realm — Production-Gaps Fix Plan (GAP-1 … GAP-9)

**Status:** fix plan rev 1 (2026-10-07), assembled from `out/gap-hunt/register.md` (round-3 triage) plus the single lens file that existed on disk (`out/gap-hunt/lens-desktop-production.md`, round 3 revised).
**Scope:** the nine production-code/evidence gaps GAP-1…GAP-9. The carried seed/routing rows (FIND-2, FIND-3, MFL-2/3/5/8, UIUX-1/2, T1/T2, the ambient-soak pair) are **not** GAPs here — they keep the owners the audit assigned (register §"Carried seed/routing rows", `register.md:106-115`) and appear only in the lineage appendix (Appendix B).

---

## 0. Inputs read, checks run, and workspace-state honesty

### 0.1 Inputs

| File | Read in full this session | Taken from it |
|---|---|---|
| `out/gap-hunt/register.md` (131 lines) | yes | the nine GAP rows, severities, evidence citations, the triage session's own check record (`register.md:5-14`), carried rows (`:106-115`), dispositions (`:117-120`), caveats (`:126-130`) |
| `out/gap-hunt/lens-desktop-production.md` (140 lines) | yes | per-finding depth for DESKPROD-1/2/4R/6/7/8, the verbatim round-1 gate command, routing table (`:21-32`), investigated-not-defects (`:129-134`) |

**No other `lens-*.md` exists in this workspace** — `ls out/gap-hunt/` returned exactly `lens-desktop-production.md` and `register.md` (run this session). The clusters routed to `lens-ui-ux.md`, `lens-test-matrix.md`, and the build-gates/multiflavor lenses are carried at pointer fidelity, exactly as the register records.

### 0.2 Checks actually run during THIS assembly (quoted results)

1. `ls out/gap-hunt/` → `lens-desktop-production.md`, `register.md` (two files; one lens).
2. `grep -n "stageBundledAsset\|target.length() == source.length()" repo/desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt` → hits at **:328, :330 (call sites), :384 (definition), :388 (`if (target.isFile && target.length() == source.length()) return target`)**. **GAP-6 is the only gap whose defective pattern is re-confirmed live in this workspace** (register header, `register.md:10`).
3. Read `ServerRuntimeFiles.kt:375-430` → the full `stageBundledAsset` body plus the neighboring `SOURCES ONLY` comment (:406-413) that names the exact failure shape for the *source* side — the author knew the shape and guarded only one side.
4. `grep -n "LORE_CARDS_FILE_NAME\|TLS_CA_BUNDLE_FILE_NAME\|PROMPT_PACK..."` → asset names at `ServerRuntimeFiles.kt:527-533` (`chatter-power.conf`, `llm_character_card.json`, `llm_prompt_pack.json`, `cacert.pem`, `lore_cards_v112.jsonl`) — the affected-asset list is real in this snapshot.
5. `grep -rn "serverFlavor\|resolves only the vanilla lane" repo/desktop/src/main/kotlin/` → **zero hits**. GAP-4's `serverFlavor()` pin **postdates this snapshot**; its evidence rests on the lens's round-1 verbatim read (E: tree `ServerRuntimeFiles.kt:629-640`).
6. `grep -n "MAX_GENERATION_TIMEOUT_SEC"` → desktop `repo/desktop/.../server/LlmRuntimePolicy.kt:67` = **240**, android `repo/android/.../server/LlmRuntimePolicy.kt:77` = **240**. GAP-8's 300-vs-360 drift **does not reproduce in this snapshot** (it postdates it); the claim rests on the lens's E:-tree reads (`lens-desktop-production.md:11,:26`).
7. `grep -n "pid" repo/desktop/.../DesktopRuntimeBackend.kt` + read `:200-270` → `observeMariaDbDatabase()` at **:231-248** reports `pid = state.pid.toInt()` — **the engine's daemon pid, not the JVM's**. GAP-5's recorded defect line (`pid = if (running) ProcessHandle.current().pid().toInt() else null`, E: tree `:219`) **does not reproduce in this snapshot**. The engine side is real here: `DesktopMariaDbEngine.kt:116` (`mariadb.pid` file), `:147` (`data class Running(val pid: Long)`), `:153` (`current?.isAlive == true → State.Running(current.pid())`), `:200-218` (pid-file readiness loop, `return State.Running(started.pid())`).
8. `grep -n "Restore\|Dialog" repo/desktop/.../ui/DiagnosticsScreen.kt` → **zero hits**; `wc -l` → the file is **168 lines** here. The restore card (lens cites :220-306 on the E: tree) **postdates this snapshot** — GAP-1/GAP-2's UI evidence is not re-verifiable in this workspace. Same for `DesktopAppModel.kt` (**242 lines** here vs the lens's :504/:781/:1318-1323) and `DesktopRuntimeBackend.kt` (**1040 lines** here vs the lens's :1777/:1900/:2207-2455).
9. `grep -rn "restoreBackup" repo/desktop/src/main/kotlin/` → **zero hits** — the restore transaction itself postdates the snapshot.
10. `grep -rn "FileDigests" repo/desktop/src/main/kotlin/` → `com.pocketrealm.fs.FileDigests` is real and already used on desktop (`DesktopSqliteSeals.kt:5,:81,:91,:171`, `DesktopSqliteSeeder.kt:10,:131,:279`, `DesktopMariaDbEngine.kt:9,:270,:273`); its test exists on the android side (`android/app/src/test/java/com/pocketrealm/fs/FileDigestsTest.kt`). GAP-6's prescribed fix helper exists.
11. Read `out/live-verification/coverage-ledger.md:265-275` → verbatim: **:270** "fixed-verified — 4 … LVF-server-db-007 (fixing row LV-server-db-14, now pass)"; **:272** "still-open — 37 … LVF-flavor-import-003 …". Both dispositions the register cites are confirmed in this workspace's copy. The original findings body `out/live-verification/findings.jsonl` is **absent** from this workspace (register `:3`,`:99`).
12. `grep -n "AlertDialog" repo/desktop/.../ui/HomeScreen.kt` → **:146** — the consent-first convention (D8) is real in this snapshot (E: tree cites :369).
13. `grep -n "fun startRealm\|includeClient" repo/desktop/.../DesktopAppModel.kt` → **:89** `suspend fun startRealm(includeClient: Boolean)` with **:94** `supervisor.start(backend.activeBotProfileId() ?: DESKTOP_PROFILE, includeClient)` — the profile resolution GAP-2 attacks is real here (line shifted from the lens's :781).
14. `grep -n "worldLifetimeSpent"` → `DesktopRuntimeBackend.kt:120` (field), `:917` (set true), `DesktopAppModel.kt:123` (`fun worldLifetimeSpent()`), `HomeScreen.kt:105` (`spentLifetime = model.worldLifetimeSpent()`) — the one-lifetime mechanic and its Home surface are real here.
15. Desktop test tree listed → existing classes: `DesktopLlmEmbeddedConfTest`, `DesktopPreparedDataPointerTest`, `DesktopServerRuntimeFilesTest`, `TimeframeStagesTest` (server), `DesktopRuntimeBackendTest`, `DesktopMariaDbEngineTest`, `DesktopAppRelauncherTest`, `DesktopSupervisorJournalTest`, etc. **No `DesktopAppModelTest` exists** — GAP-1/GAP-2 prescribe creating it. Test-name convention observed: camelCase descriptive funs, no backticks (e.g. `realmdConfigPinsTheContractShape`, `observeWithoutAStagedBinaryIsUnavailable`).
16. `grep -n "ui-test\|uiTest"` in `repo/desktop/build.gradle.kts` → **no compose UI-test dependency** — the desktop harness is JVM-unit-test only, so GAP-1's dialog is pinned by model-level tests, not a UI test (stated per-gap below).
17. detekt is wired: `repo/desktop/build.gradle.kts:26` (detekt 1.23.7), `:71-82` (config + baseline).
18. Lineage sources verified: `docs/DESKTOP-PARITY-GAPS.md` rows DESK-25 (**:1069**), DESK-47 (**:582**), DESK-53 (**:597**), DESK-93 (**:2153**), DESK-106 (**:659**), DESK-127 (**:2095**) all exist (grep run); `out/live-verification/campaign-inventory.md` multiplayer block (**MP:**\* ids, 12 rows) read in full; `out/fix-plan/performance-persistence-import.md:321` carries the **F3-3/H2-5** treatment with its ledger tick at **:395** and the landmine cross-reference at **:91**. `docs/FEATURE-PARITY-GAPS.md` and `docs/CROSS-PLATFORM-COMPLETION-LEDGER.md` are **absent from this workspace** (workspace `docs/` holds only `DESKTOP-PARITY-GAPS.md`; `repo/docs/` holds `WINDOWS_QUALIFICATION.md`, llm-\*.md, patches, wiki) — they live on the current (E:) tree.
19. **Not run by this assembly:** any gradle build/test, any pytest lane, `python tools/check_repo.py`, check_flavor/check_sources (the latter two are not even present in this snapshot's `tools/` — only `check_repo.py`, `check_sources.py`, `check_wine_dtneeded.py` + builders). The register records the *triage session* ran `python tools/check_repo.py` → `OK (1289 tracked files, 0 errors, 0 warnings)` (`register.md:9`); that is input-material record, not this session's run. Every gate command in this plan is a **prescription for the fix cycle**, and the ones this plan could not execute here are flagged as such.

### 0.3 The tree-fidelity rule this plan follows

This workspace's `repo/` snapshot (files dated Sep 29–Oct 1) **predates** the hunted tree (rounds ran Oct 6 against the current workspace, recorded as `E:/pocket_realm_complete` in the lens). Evidence line numbers below therefore carry two forms:

- **(E:)** — the hunted tree's line numbers, quoted from the lens/register (round-1/2 reads, some verbatim);
- **(snapshot)** — this workspace's `repo/` line numbers, only where this session's grep/read actually confirmed the anchor.

A fix step that cannot be anchored in this snapshot says so and anchors on the (E:) citation alone. Before executing any section, re-run that section's anchor grep on the current tree — the first work item of every section includes exactly that command.

---

## 1. Executive summary

The round-3 gap hunt consolidated nine production gaps in the desktop surface, of which **one is P1** (a shipped one-tap irreversible destructive action with no consent — GAP-1), **four are P2** (GAP-2, GAP-3 provisional, GAP-4, GAP-9 provisional), and **four are P3** (GAP-5, GAP-6, GAP-7, GAP-8). There are **no P0s**. The nine collapse into five work packages:

1. **The restore-card rework (GAP-1 → GAP-2, wave 1).** One card carries both defects: the desktop Diagnostics restore buttons begin a transaction whose success path permanently deletes the user's current realm databases with no consent dialog and no supervisor-phase gate (GAP-1, P1), and the proof boot that transaction runs carries the user's bot profile into the restored state and silently spends the process's one world lifetime, so the next ordinary Start refuses WRONG_STATE (GAP-2, P2). Fixing GAP-1 without GAP-2 ships a consent dialog that *still* doesn't name what actually happens; fixing GAP-2 without GAP-1 leaves the P1 open. They are one branch, GAP-1 first.
2. **The confirmed import-order defect (GAP-9, wave 2).** `check(pristine.isFile)` runs before the folder-lane scanner gate whose job is to own that failure — the only gap in the register that is simultaneously (a) a confirmed still-open live-verification finding (LVF-flavor-import-003, `coverage-ledger.md:272`) and (b) a product code line. Small fix, but closure requires re-running the LV leg, and the finding body (`findings.jsonl`) must be pulled from the current tree first because its absence leaves the blast radius unconfirmed.
3. **The quick independent P3s (GAP-5, GAP-6, GAP-8, wave 3).** A wrong pid in diagnostics (GAP-5), a size-equality asset cache that can silently freeze the CA bundle (GAP-6 — the one defect re-confirmed live in this workspace, `ServerRuntimeFiles.kt:388`), and a cross-platform user-override ceiling drift with no equality pin (GAP-8). All three are one-file-class fixes with focused tests; none blocks anything.
4. **The S5-flip epic (GAP-3 + GAP-4, wave 4).** ~18 hard `qualified()` checks across import/prep/runtime/status trees turn into crashes or silent degradation the moment a second flavor qualifies, and `ServerRuntimeFiles.serverFlavor()`'s hard vanilla `require` would brick the desktop server lane at the same flip. This is not reachable while only vanilla is qualified (`ClientFlavor.kt:215`), it is owned cross-lens (UIUX-3's census; MFL-8's deliberate pin per the DESK-93 KDoc), and it has a **prior treatment already on the books**: the F3-3/H2-5 P0 row of the parity register, carried by `out/fix-plan/performance-persistence-import.md:321`. These two GAPs fold into that treatment; they must not land independently of the MFL tool legs (MFL-2/MFL-3) or they produce a dead server lane instead of a second lane.
5. **The evidence-hygiene pass (GAP-7, any time, best last).** The completion ledger still documents the guestBans read-path hole as open although the tree closed it — and its sweep step ("re-verify the ledger's other open-hole claims") should run *after* waves 1–3 so this plan's own fixes get ledger rows in the same pass.

Verification posture: the per-gap gates are focused gradle classes matching the lens's own verbatim round-1 command shape (`cd desktop && ./gradlew.bat test --tests … --console=plain -q`); Appendix A gives the full post-fix sequence (desktop suite, detekt, android unit, pytest hook-selection, check_repo/check_flavor/check_sources, supervisor/save-exit gates, LV legs) with explicit flags for the gates that neither the hunt nor this assembly ever ran.

---

## 2. Tier tables

### 2.1 By severity

| Tier | Count | Gaps | One-line character |
|---|---|---|---|
| **P0** | **0** | — | none filed by the round-3 hunt |
| **P1** | **1** | GAP-1 | shipped one-tap irreversible destructive action, no consent, no phase gate |
| **P2** | **4** | GAP-2, GAP-3 (provisional), GAP-4, GAP-9 (provisional) | deterministic restore corruption + lifetime spend (2); conditional-on-S5-flip whole-lane breakage (3, 4); confirmed still-open LV finding on a product line (9) |
| **P3** | **4** | GAP-5, GAP-6, GAP-7, GAP-8 | wrong diagnostic pid (5); same-size asset freeze incl. CA bundle (6); stale ledger provenance row (7); cross-platform ceiling drift (8) |

### 2.2 By work package / suggested execution order

| Wave | Package | Gaps | Depends on | Estimated touch surface |
|---|---|---|---|---|
| 1 | Restore-card rework | GAP-1 → GAP-2 | GAP-2 depends on GAP-1 (same card) | `DiagnosticsScreen.kt`, `DesktopAppModel.kt`, `DesktopRuntimeBackend.kt`; new `DesktopAppModelTest` |
| 2 | Folder-lane import order | GAP-9 | none (pull `findings.jsonl` from current tree first) | `DesktopRuntimeBackend.kt` (~:1777 E:), `DesktopRuntimeBackendTest` |
| 3 | Independent P3s | GAP-6, GAP-5, GAP-8 | none between them | `ServerRuntimeFiles.kt`; `DesktopMariaDbEngine.kt` + backend observe; both `LlmRuntimePolicy.kt` twins |
| 4 | S5-flip epic | GAP-3 + GAP-4 (one branch) | each other; MFL-2/MFL-3 tool legs; UIUX-3 census consolidation | ~18 sites per owner census + `ServerRuntimeFiles.kt`/`DesktopRuntimeBackend.kt` |
| any (best last) | Evidence hygiene | GAP-7 | none; sweep most valuable after waves 1–3 | `docs/CROSS-PLATFORM-COMPLETION-LEDGER.md` (current tree) |

### 2.3 By conditionality (when does the defect actually fire?)

| Condition | Gaps that fire |
|---|---|
| Today, on ordinary user actions | GAP-1 (any restore tap), GAP-2 (every restore with a bot profile selected), GAP-9 (folder-lane import hitting the missing-pristine case), GAP-6 (any same-size content refresh), GAP-5 (every MariaDB-lane observation), GAP-7 (every ledger reader) |
| Only at the S5 flag flip (second flavor qualified) | GAP-3 (crash/degradation at ~18 sites), GAP-4 (whole desktop server lane refuses) |
| Only on cross-platform settings comparison | GAP-8 (desktop clamps a value Android accepts) |

---

## 3. GAP sections

---

### GAP-1 — Restore buttons: destructive datadir swap, no consent, no UI-state gate *(P1; was DESKPROD-1)*

**What is wrong.** One tap on "Restore newest backup" or a per-row "Restore" in the desktop Diagnostics screen begins the transactional restore that quarantines the live datadir and — on success — **permanently deletes the user's current databases** (`DesktopRuntimeBackend.kt:2247-2252` (E:), the COMMIT leg `check(pending.quarantine.deleteRecursively())` after the swap at :2236). There is no consent dialog anywhere in the screen; the buttons' only enablement gate is the local `busy` flag; nothing in the UI checks the supervisor phase. The backend refuses an at-rest violation (`beginSnapshotRestore` errors via `runtimesAtRestRefusal()` at `DesktopRuntimeBackend.kt:2208` (E:), plus the leftovers gate :2209-2216), so a restore pressed while running surfaces "RESTORE-REFUSED" instead of performing the swap — but that fail-closed refusal is the *entire* protection. The static card copy (`DiagnosticsScreen.kt:220-222` (E:)) describes rollback-on-failure and never says success **replaces** the current world.

**Evidence.**
- `desktop/src/main/kotlin/com/pocketrealm/desktop/ui/DiagnosticsScreen.kt:246-258` (E:) — `OutlinedButton("Restore newest backup")`, `enabled = !busy && backups.isNotEmpty()`, onClick calls `model.restoreBackup(newest.snapshotId)` directly; `:295-306` (E:) — per-row `OutlinedButton("Restore")`, `enabled = !busy`, same direct call.
- `grep -n "Dialog|confirm" DiagnosticsScreen.kt` → zero hits (lens round 1; no Dialog import in the file). This session's check on the snapshot: `grep -n "Restore\|Dialog"` over the snapshot's 168-line file → zero hits — the card postdates the snapshot, so the (E:) citations stand unrebutted and unverified here.
- The guard that exists: `DesktopRuntimeBackend.kt:2207-2216` (E:) at-rest refusal + leftovers gate; everything past it is destructive (:2236 swap; :2247-2252 delete).
- The repo's own consent-first convention for exactly this class of action: `HomeScreen.kt:369` (E:) gates stop-with-guests behind an `AlertDialog` (D8, design-lan §7) — **(snapshot) `HomeScreen.kt:146` has the `AlertDialog`, confirmed this session** — and the landed multiplayer campaign row **MP:STOP-WITH-GUESTS** (`campaign-inventory.md:244`) records the same D8 consent shipping on the stop path. The Android twin's import lane confirms before the same `restoreBackup` (sibling UIUX-2, owner lens absent from this workspace).

**Root cause analysis.** The restore card was built *against the backend's fail-closed refusal* instead of *against the desktop convention of consent-first destructive actions*. The backend refusal answers "can the swap run right now?" — a machine question. Nothing in the UI answers the human questions: "do you know this replaces your world?", "is the supervisor in a state where the button can do anything but refuse?". The `busy` flag guards coroutine re-entrancy, not world state. The convention gap is visible in the repo's own history: the multiplayer campaign already had to retrofit the identical D8 consent onto stop-with-guests (MP:STOP-WITH-GUESTS row), so this is the second instance of the same pattern, not a one-off.

**Step-by-step fix.**
1. Re-anchor on the current tree: `grep -n "Restore" desktop/src/main/kotlin/com/pocketrealm/desktop/ui/DiagnosticsScreen.kt` (expect the (E:) :246-258/:295-306 sites) and `grep -n "AlertDialog" desktop/src/main/kotlin/com/pocketrealm/desktop/ui/HomeScreen.kt` (expect :369) — copy both snippets as the in-repo reference pattern.
2. **`DesktopAppModel.kt`** — add an explicit gate surface, e.g. `fun restoreGate(): RestoreGate` where `RestoreGate` is a sealed type (`Ready(snapshotId)` / `Blocked(reason)`), consulting the supervisor phase the same way the model already surfaces `worldLifetimeSpent()` (`DesktopAppModel.kt:123` (snapshot)) for HomeScreen. Have `restoreBackup()` re-check the same gate before delegating, so the model refuses with the same reason the UI disabled with (defense in depth; the backend at-rest refusal remains the final arbiter).
3. **`DiagnosticsScreen.kt`** — wrap both buttons' onClick: store the pending snapshot id in `remember { mutableStateOf }`, show one `AlertDialog` (title: "Restore `<snapshotId>`?", body: "Your current realm databases are replaced; the current state is deleted after the proof boot passes." — extended per GAP-2 once its copy lands), confirm button calls `model.restoreBackup(id)`. Change both `enabled` expressions from `!busy`/`!busy && backups.isNotEmpty()` to the gate: `restoreGate is Ready && !busy && backups.isNotEmpty()`.
4. **Copy**: extend the static card copy (:220-222 (E:)) to state that success **replaces** the current world and deletes the pre-restore state after the proof boot.
5. **New tests** (new class — none exists today; this session confirmed the desktop test tree has no `DesktopAppModelTest`, and `build.gradle.kts` has **no compose UI-test dependency**, so the dialog composition itself is pinned by the model gate + review, not a UI test — stated honestly):
   - `DesktopAppModelTest.restoreGateBlocksWhileTheSupervisorHoldsAWorld`
   - `DesktopAppModelTest.restoreGateReadyWhenSupervisorIsAtRest`
   - `DesktopAppModelTest.restoreBackupRefusesWithTheSameReasonWhenTheGateBlocks`
6. **Gate command that proves it:**
   `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopAppModelTest" -PtestResultsDir=build/test-results-fixplan-gap1 --console=plain -q` (shape matches the lens's verbatim round-1 command, `lens-desktop-production.md:11`). Complement: `grep -c "AlertDialog" DiagnosticsScreen.kt` ≥ 1 on the current tree (the round-1 zero-hit grep is the defect's fingerprint).

**Dependencies and suggested order.** None — this is the entry point of the restore-flow pair. Execute first (wave 1), with GAP-2 on the same branch immediately after (its copy edit lands in the dialog created here).

**Risk notes.** (a) The consent copy must not ship before GAP-2's copy lands, or the dialog becomes the second stale-copy defect (it would still not name the bot-population mutation or the lifetime spend). (b) Do **not** weaken or remove the backend at-rest refusal — the UI gate is UX, the backend refusal is the enforcement; a busy-flag race must surface RESTORE-REFUSED, never a swap. (c) Keep the dialog's confirm action idempotent from double-taps (the existing `busy` flag continues to serve that). (d) Severity is the register's P1 and the hunt's only P1 — do not let the rework silently descope the phase-gated enablement: a consent dialog alone leaves the "RESTORE-REFUSED as UI" cliff in place.

---

### GAP-2 — Restore proof boot carries the user's bot profile and spends the one world lifetime *(P2; was DESKPROD-2)*

**What is wrong.** `restoreBackup` proves the restored candidate by booting the stack through the **ordinary start path**, which does two things the user never asked for: (a) the launch spec always carries the settings-resolved bot profile, and a bots-enabled boot **provisions the population's accounts before READY** — so the committed state is X **plus a freshly provisioned bot population**, not the snapshot X the user picked; (b) the proof reaches READY, which sets `worldLifetimeSpent = true` — the process's one-lifetime-per-world budget is spent by the *proof*, so the next ordinary Start refuses WRONG_STATE until the user relaunches the app. Neither consequence appears in the restore copy.

**Evidence.**
- The proof: `DesktopAppModel.bootRestoredStackWorldReadyProof` → `startRealm(includeClient = false)` (`DesktopAppModel.kt:1318-1323` (E:)). Mechanism real in this snapshot: `startRealm(includeClient: Boolean)` at **:89** resolving `backend.activeBotProfileId() ?: DESKTOP_PROFILE` at **:94** (confirmed this session; line shifted from the lens's :781).
- The model's own comment states the consequence: "a bots-enabled boot provisions the population's accounts before READY" (`DesktopAppModel.kt:743-746` (E:)).
- The lifetime spend: `startWorld` sets `worldLifetimeSpent = true` when the boot reaches READY (`DesktopRuntimeBackend.kt:2450-2455` (E:); DESK-25 contract :312-320, remedy :817-825). Mechanism real in this snapshot: field at **:120**, set at **:917**, surfaced via `DesktopAppModel.kt:123` and consumed by `HomeScreen.kt:105` (`spentLifetime = model.worldLifetimeSpent()`) — confirmed this session. The DESK-25 register row itself verified at `docs/DESKTOP-PARITY-GAPS.md:1069` ("World runtime restart cycles").
- The restore copy names neither effect (`DiagnosticsScreen.kt:220-222` (E:)).

**Root cause analysis.** The proof boot reuses the ordinary launch spec instead of a **minimal proof spec**. Reuse was economical — the ordinary path is the one that is known to reach READY — but the proof's job is to demonstrate the *restored bytes* boot, not to re-run the user's configuration. Two hidden parameters ride along when it does: the bot profile (which writes new accounts into the candidate during proof) and the world lifetime (which the proof consumes). The lifetime spend is by design (DESK-25) — the defect is that the restore flow neither avoids the avoidable spend-context (the profile) nor discloses the unavoidable consequence (the relaunch requirement).

**Step-by-step fix.**
1. Re-anchor: `grep -n "activeBotProfileId\|bootRestoredStack" desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopAppModel.kt` on the current tree (expect (E:) :781, :1318-1323).
2. **`DesktopAppModel.kt`** — thread a profile override through the proof path: extend `startRealm(includeClient: Boolean, profileOverride: String? = null)` (existing signature confirmed at :89 (snapshot)) and have `launchSpec` resolve `profileOverride ?: backend.activeBotProfileId() ?: DESKTOP_PROFILE`. The restore proof passes the explicit bots-disabled profile — the `DESKTOP_PROFILE` default is exactly the no-bots profile whose conf block pins `LLMEnabled = 0` with no chance line (`ServerRuntimeFiles.kt:177-185` (E:), verified clean by the lens for the soak row), so the proof writes no population.
3. **`DesktopAppModel.kt` / `DiagnosticsScreen.kt`** — disclose the lifetime consequence: the restore status copy gains "the next start needs an app relaunch", **and** the Diagnostics card offers the DESK-25 relaunch action (the relauncher exists: `DesktopAppRelauncherTest.kt` in this snapshot's test tree) instead of leaving the user to discover WRONG_STATE on the next Start.
4. **`DesktopRuntimeBackend.kt`** — only if the threading requires it: allow the proof boot to specify its profile at the backend boundary (`startWorld`'s launch-spec assembly), keeping the DESK-25 lifetime semantics untouched — the fix must **not** reset `worldLifetimeSpent` or fake a fresh lifetime; DESK-25's contract is that the spend is real and durable.
5. **New tests:**
   - `DesktopAppModelTest.restoreProofBootsTheBotsDisabledProfile` (asserts the launch spec the proof built carries the override, not the settings-resolved profile)
   - `DesktopAppModelTest.restoreCompletionSurfacesTheLifetimeSpend` (status copy/flag transitions after a successful restore)
   - `DesktopRuntimeBackendTest.proofBootDoesNotProvisionBotPopulation` (asserts no population accounts appear in the restored candidate when the proof runs bots-disabled)
6. **Gate command that proves it:**
   `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopAppModelTest" --tests "com.pocketrealm.desktop.DesktopRuntimeBackendTest" -PtestResultsDir=build/test-results-fixplan-gap2 --console=plain -q`.
   Deeper exit criterion (recorded **not run** in both the hunt and this assembly — flag, don't skip): the supervisor start gate (`supervisorStartGate`) exercising a restore → proof → ordinary-start sequence end to end.

**Dependencies and suggested order.** Depends on GAP-1 (same card: the consent dialog and copy land together). Execute immediately after GAP-1 on the same branch (wave 1).

**Risk notes.** (a) A bots-disabled proof no longer exercises the profile-conf staging path during restore — acceptable, because every ordinary Start exercises it; note it in the test comment so nobody "fixes" it back. (b) Do not implement the disclosure as an auto-relaunch: a surprise app restart mid-session is a second consent problem — offer the action, don't take it. (c) The `worldLifetimeSpent` mechanic is DESK-25 contract (`docs/DESKTOP-PARITY-GAPS.md:1069`); any temptation to make restore "not count" is out of scope and would mask real restart-cycle limits. (d) If the current tree has gained a second restore caller since the lens read, both call sites need the override — grep `restoreBackup` before editing.

---

### GAP-3 — qualified()-landmine cluster: ~18 hard qualified-flavor checks crash or silently degrade at the second-flavor flip *(P2 provisional; owner UIUX-3; ex-DESKPROD-5/5M; pointer row)*

**What is wrong.** Hard `qualified` checks across the import, data-prep, runtime, and status trees fire the moment a second `ClientFlavor` qualifies (the S5 flip): crashes at most sites, silent degradation at the status surface (`RealmStatusController.kt:542` (E:), the one site whose failure mode is wrong-copy rather than an exception). The round-3 audit merged the dueling filings into ONE owner — **UIUX-3 (`lens-ui-ux.md`), which is not present in this workspace** — and that owner file carries the full 18-site census (9 desktop + 9 android, re-verified by its own grep per `lens-desktop-production.md:16`). This register carries the merged row at pointer fidelity until that file is consolidated.

**Evidence.**
- Desktop census carried verbatim (`lens-desktop-production.md:23`): `DesktopAppModel.kt:504`, `DataPreparationStore.kt:376`/`:410`, `DesktopRuntimeBackend.kt:1900`/`:1920`/`:2153`, `RealmStatusController.kt:542` (silent-degradation wording), `ClientGenerationStore.kt:74` — all (E:) reads; **not re-verifiable in this snapshot** (e.g. `DesktopAppModel.kt` is 242 lines here — confirmed this session).
- The android twins and the 18-site superset live in the owner file; the prior-register anchor is `docs/FEATURE-PARITY-GAPS.md:33` (the F3-3/H2-5 P0 row), **absent from this workspace**, but its treatment **is present**: `out/fix-plan/performance-persistence-import.md:321` — "### F3-3/H2-5 — Single-qualified-lane landmine: thread `ClientFlavor` through import/planner/prep; cumulative cross-flavor workspace cap" — with ledger tick `:395` and, at `:91`, an explicit statement that `ServerRuntimeFiles.serverDatabases()`'s single-qualified-lane guard "is itself the F3-3/H2-5 landmine … that this gap's routing must not extend until the S5 threading lands" (read in full this session). **This gap has a prior, ledgered treatment; the GAP-3/GAP-4 work is its desktop-code leg.**
- Unreachability today: only vanilla is qualified (`ClientFlavor.kt:215`, per `lens-desktop-production.md:76`).
- The routing-safe counterexample the treatment mirrors: `PreparedDataStore.requireActive(flavor = ActiveFlavor.resolve(SERVER_DATA_LANE))` (android `.../server/PreparedDataStore.kt:72, :133` — lens round-1 read).

**Root cause analysis.** Flavor qualification was modeled as an **invariant** (`require`) instead of a **resolved lane**. While exactly one flavor qualified, `require(qualified.size == 1)` and friends were indistinguishable from routing. The S5 flip turns every such guard into a landmine because the guard's premise (uniqueness) becomes false without any code change on the consumer side. The repo already contains the correct model — `ActiveFlavor.resolve(<lane>)` — used by the prepared-data store; the cluster is the set of sites that predate or never adopted it.

**Step-by-step fix.**
1. **Consolidate the owner census first.** Pull `out/gap-hunt/lens-ui-ux.md` from the current tree (absent here). Its 18-site census is the checklist; this plan does not re-derive it. If the census and the desktop rows above disagree, the owner file wins (the audit's ownership pass).
2. **Fold into the F3-3/H2-5 treatment** (`out/fix-plan/performance-persistence-import.md:321`): the import/planner/prep legs are already owned and ledgered there. The GAP-3 work in this plan is the desktop **runtime/status** legs of the same census, executed on the same branch so the census closes once.
3. Per site: replace the hard `require(<qualification>)` with ActiveFlavor-resolved handling — mirror `requireActive(flavor = ActiveFlavor.resolve(SERVER_DATA_LANE))`; at each site the resolved behavior must be either a *lane-correct result* or a *fail-loud refusal naming the lane* (never a silent default). The silent-degradation site (`RealmStatusController.kt:542` (E:)) additionally needs its copy corrected to name the active flavor.
4. **Tests** (one per census site, named on the convention the desktop suite already uses): `<consumer>ResolvesTheActiveLaneWhenTwoFlavorsQualify` for every crash-class site, plus `realmStatusNamesTheActiveFlavorWhenTwoQualify` for the degradation site. The test harness needs a two-qualified-flavors fixture — `schemas/flavor.json`'s `qualified` flags flipped in-test (the flip is S5's own lever; `tools/check_flavor.py`, present on the current tree, is the gate that keeps the repo's real flag state vanilla-only).
5. **Gate command that proves it:**
   `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.server.DesktopServerRuntimeFilesTest" --tests "com.pocketrealm.desktop.DesktopRuntimeBackendTest" -PtestResultsDir=build/test-results-fixplan-gap3 --console=plain -q` for the desktop legs, plus `python tools/check_flavor.py` (current tree) to prove the repo's committed qualification state is unchanged.

**Dependencies and suggested order.** Root of the flip epic; no upstream GAP dependency, but strong coordination dependencies: UIUX-3 (census), MFL-8 (the deliberate pin this gap's fix unravels), MFL-2/MFL-3 (tool legs). Execute in wave 4, after waves 1–3, on one branch with GAP-4.

**Risk notes.** (a) **Severity is provisional (P2)** — conditional on the S5 flip; the owner file may correct it (register `:43`). (b) The dangerous site is the silent-degradation one: a test that asserts only "no crash" would pass the fix while leaving wrong status copy — assert the wording. (c) Scope control: touch only census sites; every conf-emission change is fenced by the vanilla-regression guards (fix-plan §2.7, G3-1..G3-10) because conf digests are frozen identity inputs (fix-plan §2.3 header). (d) Do not let this branch flip the real S5 flag — it makes the *handling* flip-safe; the flip itself remains MFL-owned sequencing.

---

### GAP-4 — `serverFlavor()`'s hard vanilla require pins the desktop server lane; the S5 flip bricks it *(P2; was DESKPROD-4R; named leg of the MFL-owned S5 gap)*

**What is wrong.** Even after flavor.json/ClientFlavor flip a second flavor to qualified, `ServerRuntimeFiles.serverFlavor()`'s hard vanilla `require` refuses **every conf write, realmlist projection, and the SRP6 logon lane** — and its refusal copy ("pass its ClientFlavor explicitly") names a parameter no caller can supply. Without this leg threaded, the S5 fix produces a dead server lane instead of a second lane. MFL-8 records the pin itself as **deliberate routing-now state** per the DESK-93 KDoc (`docs/DESKTOP-PARITY-GAPS.md:2153`, "Per-flavor conf emission & database-name resolution" — row verified this session); MFL-2/MFL-3 own the tool legs (`tools/seed_realm_db.py:60-61`, `tools/win_prepare_data.py:474-478`), verified round 1 by the lens and not filed by it.

**Evidence.**
- `desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt:629-640` (E:) — `require(qualified.size == 1)` then `require(flavor.flavorId == ClientFlavors.VANILLA.flavorId) { "server runtime resolves only the vanilla lane; pass its ClientFlavor explicitly" }` (verbatim, lens round-1 read). **This session: `grep -rn "serverFlavor\|resolves only the vanilla lane"` over the snapshot → zero hits — the pin postdates this snapshot; evidence rests on the (E:) verbatim read.**
- Consumers inheriting the pin via `serverDatabases()` (:642-650 (E:)): `DesktopRuntimeBackend.kt:722-723` (projectRealmAdvertisement's realmd row), `:753-754`, `:768-769` (projectedRealmAddress/Name); cross-package consumers named at :622-627 (E:): `AuthGate`/`RpWowClient`, `RpHost`.
- The already-routing-safe counterexample: `PreparedDataStore.requireActive(flavor = ActiveFlavor.resolve(SERVER_DATA_LANE))` (android `.../server/PreparedDataStore.kt:72, :133`).
- The prior treatment naming this exact guard: `out/fix-plan/performance-persistence-import.md:91` — "`ServerRuntimeFiles.serverDatabases()` already resolves them — but only through the single-qualified-lane guard (`ServerRuntimeFiles.kt:404-414`), which is itself the F3-3/H2-5 landmine" (plan-era line numbers; read this session).

**Root cause analysis.** A vanilla-only routing shortcut hardened into a `require`. At the time (DESK-93's conf/db-name resolution work) only vanilla was qualified, so the require was a cheap invariant; the refusal copy's "pass its ClientFlavor explicitly" shows the author already saw the multi-flavor future but the threading was never done — no caller *can* pass the parameter because the function takes none. The fix is not to weaken the require into silence but to replace it with resolution: the lane is a property of the active flavor, not an assertion about the world.

**Step-by-step fix.**
1. Re-anchor: `grep -n "only the vanilla lane" desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt` on the current tree (expect (E:) :629-640); also grep the four consumer sites (`serverDatabases()` call sites in `DesktopRuntimeBackend.kt`).
2. **`ServerRuntimeFiles.kt`** — thread `ClientFlavor` (constructor parameter or per-call argument, mirroring `requireActive(flavor)`); resolve `serverFlavor()`/`serverDatabases()` through `ActiveFlavor.resolve(...)`: the active qualified flavor drives realmd/world conf, projection, and the SRP6 lane; keep a fail-loud refusal when *no* flavor is qualified (that refusal stays honest), but drop the vanilla-only `require`.
3. **`DesktopRuntimeBackend.kt`** — update the four consumer sites (:722-723/:753-754/:768-769 (E:)) to pass/respect the resolved flavor; verify `AuthGate`/`RpWowClient`/`RpHost` compile against the new shape (they are named consumers at :622-627 (E:)).
4. **Coordinate with MFL-8:** the pin is deliberate routing-now state; landing the unpin requires the multiflavor-lanes owner's sign-off in the same review, and the MFL-2/MFL-3 tool legs should land or be concurrently staged so the flip has working tools when it comes.
5. **New tests** (`DesktopServerRuntimeFilesTest`, which ran green round-1 with 36 tests — `lens-desktop-production.md:11`):
   - `DesktopServerRuntimeFilesTest.serverDatabasesResolveTheActiveLaneWithTwoQualifiedFlavors`
   - `DesktopServerRuntimeFilesTest.realmdConfigTracksTheActiveFlavorAtTheS5Flip`
   - `DesktopServerRuntimeFilesTest.projectRealmAdvertisementProjectsTheActiveFlavorsRealmlist`
6. **Gate command that proves it:**
   `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.server.DesktopServerRuntimeFilesTest" -PtestResultsDir=build/test-results-fixplan-gap4 --console=plain -q` — the exact class the lens ran round-1 (exit 0, 36 tests), so the delta is attributable.

**Dependencies and suggested order.** GAP-3 (leg of the same S5/flip treatment — same branch, GAP-3's census first so this site's test harness exists). Coordination: MFL-2/MFL-3 (tools), MFL-8 (pin ownership), and the vanilla golden pins constraint below. Wave 4.

**Risk notes.** (a) **Vanilla must stay byte-identical**: the golden conf pins (`VanillaConfGoldenTest` per the MP:CONDITIONAL-LOCKOUT row, `campaign-inventory.md:245`; TF-4's desktop `DesktopServerRuntimeFilesTest` pins) must not move — the fix changes *resolution*, not vanilla bytes. (b) The SRP6 logon lane is in the blast radius: the parity hunt's `authGate` (SRP6 M2 vs real native realmd, green per `docs/DESKTOP-PARITY-GAPS.md` convergence note) is the deep gate for it — run it if the logon path's wiring changes, and record it if it does (it has never run in the gap-hunt sessions). (c) Landing this before GAP-3's census treatment risks half-threaded consumers that crash at the flip anyway. (d) Not reachable today (`ClientFlavor.kt:215` — only vanilla qualified): do not let the unreachability argue for descope; the S5 plan explicitly depends on this leg (`performance-persistence-import.md:91`).

---

### GAP-5 — MariaDB-lane DATABASE observation reports the app JVM's pid, not the daemon's *(P3; was DESKPROD-6)*

**What is wrong.** A diagnostics/support-bundle consumer reading `ComponentObservation.pid` for the DATABASE component under the MariaDB lane inspects — or aims a kill at — the **wrong process**: the value is the app JVM's own pid, while the real daemon (a spawned `mariadbd`) has its pid tracked by the engine but never surfaced through the observation.

**Evidence.**
- `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt:219` (E:) — `pid = if (running) ProcessHandle.current().pid().toInt() else null` (the JVM's own pid).
- The engine — `desktop/src/main/kotlin/com/pocketrealm/database/DesktopMariaDbEngine.kt:243` (E:) — `.put("pid", ProcessHandle.current().pid())` in the status JSON, while the daemon is spawned at :96-140 (`ProcessBuilder(... mariadbd ...)` → `process.pid()`), tracked in `daemonPid` (:192, :751) and `mariadb.pid` (:169), all available in `status()` scope.
- The SQLite lane's `pid = null` is honest (`DesktopDatabaseComponent.kt:39` (E:)) — in-process materialization; the MariaDB lane's value is neither the daemon nor null.
- **This session's snapshot check (fidelity):** the recorded defect does **not** reproduce here — `observeMariaDbDatabase()` at `DesktopRuntimeBackend.kt:231-248` (snapshot) reports `pid = state.pid.toInt()`, and the engine's daemon tracking is real (`DesktopMariaDbEngine.kt:116` pidFile, `:147` `data class Running(val pid: Long)`, `:153` alive-check from the handle, `:200-218` pid-file readiness). The defect is on the **current tree only**; the fix and its gate must run there.

**Root cause analysis.** The observation was populated from **ambient process context** (`ProcessHandle.current()`) instead of the engine's own daemon handle. The engine did the hard part — it spawns, waits for the pid file, and keeps `State.Running(pid)` — and the observation layer threw that away and substituted the only pid in lexical scope: the JVM's. Because pid is display/journal data (ownership keys off `ComponentOwner`, not pid — hence P3, not P1), nothing *broke*; the value simply lies to humans and support bundles, which is its entire purpose.

**Step-by-step fix.**
1. Re-anchor on the current tree: `grep -n "ProcessHandle.current().pid()" desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt desktop/src/main/kotlin/com/pocketrealm/database/DesktopMariaDbEngine.kt` (expect the (E:) :219/:243 forms).
2. **`DesktopMariaDbEngine.kt`** — add the daemon pid to the engine's status JSON: `daemonPid` when `State.Running`, absent/NULL when stopped (mirror the existing `binarySha256` nullable-put pattern at :270 (snapshot)). Source it from the tracked daemon handle, never from `ProcessHandle.current()`.
3. **`DesktopRuntimeBackend.kt`** — map it: `observeMariaDbDatabase()`'s `ComponentObservation.pid` takes the engine's daemon pid; `null` when Stopped/Unavailable (keep the SQLite lane's honest `null` untouched).
4. **New tests:**
   - `DesktopMariaDbEngineTest.statusJsonCarriesTheDaemonPidWhenAliveAndOmitsItWhenDown`
   - `DesktopRuntimeBackendTest.mariadbObservationReportsTheDaemonPid`
5. **Gate command that proves it:**
   `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopMariaDbEngineTest" --tests "com.pocketrealm.desktop.DesktopRuntimeBackendTest" -PtestResultsDir=build/test-results-fixplan-gap5 --console=plain -q` (test-class packages per this snapshot's test tree; if the current tree has moved the engine to the `database` package as its source path suggests, adjust the `--tests` filter to the moved package).

**Dependencies and suggested order.** None. Wave 3 (independent P3s).

**Risk notes.** (a) Grep `ComponentObservation.pid` consumers before editing — if anything on the current tree keys behavior off that pid (the register says ownership keys off `ComponentOwner`; verify), the value change is a behavior change, not a display fix. (b) Support-bundle redaction rules (fix-plan §2.5 P2-8 family) may allowlist status-JSON keys; a new `daemonPid` key must be reviewed for the bundle's redaction/allowlist so it neither leaks nor gets silently stripped. (c) Keep `null` when the daemon is down — a fabricated pid is the defect class here; do not replace one wrong value with another plausible one. (d) The (E:)-vs-snapshot package drift (`desktop` vs `database` package for the engine) means the current tree may have already half-moved this code — read before editing.

---

### GAP-6 — `stageBundledAsset` re-stages on size equality only; a same-size content refresh is shadowed forever *(P3; was DESKPROD-7; only gap re-confirmed live in this workspace)*

**What is wrong.** The bundled app assets staged next to the world conf — lore cards, **the CA bundle (`cacert.pem`)**, prompt pack/default files, chatter-power file — are cached by `target.length() == source.length()`. A content refresh that preserves the byte count **never lands in an existing install's run dir**: the stale staged copy verifies itself against the fresh source by size and shadows it forever. For the CA bundle this is a silent security-asset freeze.

**Evidence.**
- **(snapshot, confirmed live this session)** `repo/desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt:384-392` — `private fun stageBundledAsset(subdir, name)` with the cache check at **:388**: `if (target.isFile && target.length() == source.length()) return target; source.copyTo(target, overwrite = true)`. **(E:)** same code at :397-404 (line shift only).
- The file's own neighboring comment names exactly this failure shape for the *source* side — **(snapshot)** :406-413: "SOURCES ONLY - the run dir is the staging TARGET, never a source: listing it here would let a previously staged copy verify itself (size == its own size) and shadow a refreshed repo/app asset." The author knew the shape and guarded only one side of it.
- Affected assets — **(snapshot)** :527-533: `CHATTER_POWER_FILE_NAME = "chatter-power.conf"`, `DEFAULT_PROMPTS_FILE_NAME = "llm_character_card.json"`, `PROMPT_PACK_FILE_NAME = "llm_prompt_pack.json"`, `TLS_CA_BUNDLE_FILE_NAME = "cacert.pem"`, `LORE_CARDS_FILE_NAME = "lore_cards_v112.jsonl"` (E: cites :657-661 for the same list). Call sites at **(snapshot)** :328/:330 wrap staging in `runCatching` (missing-everywhere yields null — fail-open contract to preserve).
- The prescribed helper is real: `com.pocketrealm.fs.FileDigests.sha256` — used on desktop at `DesktopSqliteSeals.kt:81/:91/:171` and `DesktopSqliteSeeder.kt:131/:279` (grep run this session); test coverage exists on the android side (`android/app/src/test/java/com/pocketrealm/fs/FileDigestsTest.kt`).

**Root cause analysis.** Size was used as a **content proxy**. It is a correct proxy only under the assumption that content never changes without changing length — false for every one of these assets (a prompt tweak, a lore-card edit, or a CA-bundle rotation can all be length-preserving). The neighboring comment proves the failure mode was understood; the guard was simply applied to the wrong side (source resolution) and not to the cache check where it bites. Cost is not a defense: the call runs once per world start, and the files are small.

**Step-by-step fix.**
1. **`ServerRuntimeFiles.kt`** — replace the size equality at :388 (snapshot)/:397-404 (E:) with a digest comparison: `if (target.isFile && FileDigests.sha256(target) == FileDigests.sha256(source)) return target; source.copyTo(target, overwrite = true)`. Keep everything else — the `resolveBundledAsset` source order (app dir → android asset tree), the fail-open `FileNotFoundException` contract, and the SOURCES-ONLY comment (extend it to note the cache is now digest-verified on both sides).
2. Alternative (only if profiling ever objects): an embedded version marker line per asset — but the digest is simpler and matches the repo's existing seal/digest idiom; take the digest.
3. **New tests** (`DesktopServerRuntimeFilesTest` — the class ran green round-1, 36 tests, exit 0, `lens-desktop-production.md:11`):
   - `DesktopServerRuntimeFilesTest.stageBundledAssetRestagesWhenContentChangesAtTheSameSize` — stage, rewrite the source with same-length different bytes, stage again, assert the target bytes changed (this exact sequence is the defect; it is the test that would have caught it)
   - `DesktopServerRuntimeFilesTest.stageBundledAssetKeepsTheCachedCopyWhenContentIsIdentical` — guards against the naive fix that always recopies (run-dir rewrite churn)
4. **Gate command that proves it:**
   `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.server.DesktopServerRuntimeFilesTest" -PtestResultsDir=build/test-results-fixplan-gap6 --console=plain -q`.

**Dependencies and suggested order.** None. Wave 3 — and execute it first among the P3s: it is the one gap whose defect is confirmed in *this* workspace's tree, so the fix, test, and gate are all executable end-to-end right here.

**Risk notes.** (a) Behavior change is the point: existing installs with a stale same-size staged copy **self-heal on next world start** — call it out in release notes. (b) `FileDigests.sha256` must resolve on the desktop source set — it does today via shared sources (verified), but if shared-sources membership changes, this fix silently loses its helper; the new tests fail loudly in that case, which is the right shape. (c) Do not "optimize" by hashing only when sizes differ — same-size-different-content is precisely the case to catch. (d) The CA bundle is security-relevant: after this lands, a rotated `cacert.pem` actually reaches installs; consider noting in the ledger (GAP-7's sweep) that prior same-size CA refreshes may not have landed on existing installs.

---

### GAP-7 — Ledger still documents the closed guestBans read-path hole as open *(P3; was DESKPROD-8)*

**What is wrong.** `docs/CROSS-PLATFORM-COMPLETION-LEDGER.md` claims the guestBans read-path quiesce hole is open, but the current tree **closed it** — the fix landed without a ledger entry. This is evidence staleness in the other direction: not an unfixed claimed fix, but a **fix without a recorded verdict**, which is exactly the hygiene class the completion ledger exists to prevent (it is the fix-provenance record).

**Evidence.**
- `docs/CROSS-PLATFORM-COMPLETION-LEDGER.md:438` (E:) — "Remaining hole, read: DesktopAppModel.kt:1071-1073 `internal fun guestBans()` … takes NO withDatadirQuiesced…" (lens round-1 read).
- Current tree: `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopAppModel.kt:1099-1102` (E:) — `guestBans()` wraps `guestBanStore.listBans()` in `backend.withDatadirQuiesced { … }` (round-1 read; disposition re-confirmed round 2 per the lens).
- **This workspace:** the ledger file is **absent** (workspace `docs/` holds only `DESKTOP-PARITY-GAPS.md`; `repo/docs/` holds `WINDOWS_QUALIFICATION.md`, llm-\*.md, patches, wiki — checked this session), so only the lens's quoted lines stand. Corroboration that the surrounding store is real and campaign-tracked: the **MP:GUEST-BANS** row (`campaign-inventory.md`, multiplayer block — DesktopGuestBans store, ban/suspend/unban/list, audit sink) and the guest-ban mutation hardening under **MP:HARDENING-FIXES**; the adjacent DB row LVF-server-db-007 is fixed-verified at `coverage-ledger.md:270` (grep run this session).

**Root cause analysis.** The fix landed in code with its corresponding ledger row left at its old verdict. Root cause is process, not code: the closing commit didn't carry the ledger edit, and nothing in the gate chain catches a code-vs-ledger verdict mismatch (the ledger-golden gate, `tools/check_ledger_golden.py`, is recorded as wired into check_repo for seal-critical ledger files — `campaign-inventory.md:45` — but this prose hole row evidently isn't covered by it).

**Step-by-step fix.**
1. Pull the ledger from the current tree (`docs/CROSS-PLATFORM-COMPLETION-LEDGER.md`, recorded 527 lines at campaign-inventory time).
2. **Update row :438** to: "fixed — read path wrapped in `withDatadirQuiesced` at `DesktopAppModel.kt:1099-1102`", citing the verifying evidence (the round-1/2 reads; re-grep `withDatadirQuiesced` around `guestBans` on the current tree and quote the result in the row).
3. **Sweep the rest of the ledger's open-hole claims** against the current tree before trusting any of them as open: grep the ledger for `Remaining hole|takes NO|still open` and re-verify each hit's code anchor. This sweep should run **after waves 1–3** so this plan's own fixes (GAP-1/2/5/6/8/9) get their rows in the same pass — one ledger-edit commit, not six.
4. Check whether `tools/check_ledger_golden.py` (current tree) can be extended to cover prose hole rows; if cheap, wire it so a code-vs-ledger mismatch fails `check_repo` — otherwise record the sweep as a manual step with a date and operator.
5. **Tests:** none (documentation row). The sweep's output — a quoted `file:line` per flipped verdict — is the deliverable and belongs in the row text.
6. **Gate command that proves it:** `python tools/check_repo.py` (run from the repo root of the current tree) stays green after the edit; plus `grep -n "Remaining hole" docs/CROSS-PLATFORM-COMPLETION-LEDGER.md` returning no hit for the guestBans row specifically.

**Dependencies and suggested order.** None. Any time; best executed last so the sweep covers waves 1–3's rows in one commit.

**Risk notes.** (a) **Do not flip a row to "fixed" without the code evidence in hand** — the sweep's whole value is that each flip quotes a current-tree `file:line`; a plausible flip is the same defect in reverse. (b) The ledger lives only on the current tree; nothing in this workspace can verify the edit — the gate run must happen there. (c) The guestBans code evidence itself (`DesktopAppModel.kt:1099-1102` (E:)) is a lens read, not re-verifiable in this 242-line-line snapshot — if the current tree has drifted, the row's new text must cite what the tree actually says.

---

### GAP-8 — Desktop LLM user-override ceiling (300 s) drifts from Android (360 s) *(P3; merges AP-1 / DE2-03 per the audit's delegation)*

**What is wrong.** The **user-override** generation-timeout ceiling on desktop (`MAX_GENERATION_TIMEOUT_SEC = 300`) is lower than the Android twin's (360), so desktop clamps user settings that Android accepts — a settings-parity clamp with no crash. **Distinct from the embedded-lane tier pins, which are correct and not re-filed**: LVF-bots-llm-014 holds (`DesktopLlmEmbeddedConfTest.kt:46` pins `LLMGenerationTimeout = 360` == `MEASURED_COLD_START_TIMEOUT_SEC = 360`, `LlmModelRegistry.kt:109`; `DesktopServerRuntimeFilesTest.kt:605` pins 240 == `MEASURED_GENERATION_TIMEOUT_SEC`, `LlmModelRegistry.kt:89`) — the lens ran those two focused classes round-1, exit 0.

**Evidence.**
- `lens-desktop-production.md:11, :26` — desktop `LlmRuntimePolicy.kt:54` = 300 vs android `:86` = 360 (round-1/2 E:-tree reads).
- **This session's snapshot check:** both files read **240** here (desktop `repo/desktop/.../server/LlmRuntimePolicy.kt:67`, android `repo/android/.../server/LlmRuntimePolicy.kt:77`, with the identical `coerceIn(MIN_GENERATION_TIMEOUT_SEC, MAX_GENERATION_TIMEOUT_SEC)` clamp shape at :73/:83) — the snapshot **predates the drift**; the claim rests on the E:-tree reads. Owner lens files (AP-1, DE2-03) are absent from this workspace; the audit directed triage to keep exactly one of them, and the register merged them into this row.
- Structural fact verified this session: `LlmRuntimePolicy` is **not** in `desktop/shared-sources.json` (grep → zero hits; the manifest declares 57 shared mains), i.e. the constant exists as two independently editable platform twins with nothing tying them together.

**Root cause analysis.** Platform twin constants drifted because **no cross-platform equality pin exists**. The embedded-lane tier pins prove the repo knows how to pin measured values — but they pin each platform against the shared `MEASURED_*` registry, not against each other, and the user-override ceiling has no measured-value anchor at all, so nothing notices when one twin's editor moves it. The fix is one constant decision plus the missing pin, not a hunt.

**Step-by-step fix.**
1. **Re-measure on the current tree first** — the lens's 300/360 may itself have moved: `grep -n "MAX_GENERATION_TIMEOUT_SEC" desktop/src/main/kotlin/com/pocketrealm/server/LlmRuntimePolicy.kt android/app/src/main/java/com/pocketrealm/server/LlmRuntimePolicy.kt`.
2. **Unify the ceiling** in both files at one value — **360 recommended**: it is the embedded tier's measured default (`MEASURED_COLD_START_TIMEOUT_SEC = 360`, `LlmModelRegistry.kt:109`), so a desktop ceiling of 300 clamps exactly the value the settings UI offers; whatever the unified value, it must not sit below the tier defaults the settings screens present.
3. **Add the missing equality pin** — new class `DesktopLlmUserOverrideCeilingsTest` (deliberately separate from `DesktopLlmEmbeddedConfTest`, whose tier pins must stay untouched): the desktop test walks up from its working dir to the checkout (mirroring the existing walk-up idiom in `resolveBundledAsset`, `ServerRuntimeFiles.kt:395-408` snapshot), reads the android twin's source, and asserts the two `MAX_GENERATION_TIMEOUT_SEC` values are equal — `DesktopLlmUserOverrideCeilingsTest.userOverrideCeilingMatchesTheAndroidTwin`.
   - Alternative structural fix considered and deferred: promote the constant to a shared source (add to `shared-sources.json`, enforced by `tests/test_desktop_shared_manifest.py`). Deferred because `LlmRuntimePolicy` differs between platforms in its other members (lane wiring), so promoting the file drags the android-freeness rule (`test_desktop_shared_manifest.py`'s import grep) into a file that may not satisfy it; the equality pin achieves the guarantee at zero coupling. Revisit if the files converge.
4. **Gate commands that prove it:**
   Desktop: `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.server.DesktopLlmUserOverrideCeilingsTest" --tests "com.pocketrealm.server.DesktopLlmEmbeddedConfTest" -PtestResultsDir=build/test-results-fixplan-gap8 --console=plain -q` (the embedded class is included to prove the tier pins still hold — they are the adjacent verified-clean surface).
   Android (ceiling consumers): `./gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 -PpocketLane=full` (the README-recorded unit-lane command, `repo/README.md:155`).

**Dependencies and suggested order.** None. Wave 3.

**Risk notes.** (a) Raising a ceiling is a **behavior change for settings above the old value**: verify the settings screens' max spinner/limits on both platforms match the unified value, or the clamp moves from invisible to user-visible. (b) Do not touch the `MEASURED_*` tier pins or `DesktopLlmEmbeddedConfTest`/`DesktopServerRuntimeFilesTest` pin lines — LVF-bots-llm-014 is verified holding; this gap is the user-override ceiling only. (c) The evidence chain on the 300/360 values is E:-tree-only and already contradicted by this snapshot's 240/240 — if step 1's grep finds equality on the current tree, **file the drift as already-healed and land only the equality pin** (the pin is the fix that prevents recurrence; the constant unification is then a no-op). (d) The merged AP-1/DE2-03 owner lenses are absent — if either surfaces later with different contents, re-triage this row per the audit's keep-exactly-one rule.

---

### GAP-9 — Folder-lane import: `check(pristine.isFile)` preempts the folder-lane scanner gate *(P2 provisional; seed LVF-flavor-import-003, register-carried)*

**What is wrong.** On the product import path, the pristine-file existence check at `DesktopRuntimeBackend.kt:1777` (E:) runs **before** the folder-lane scanner gate (:1790-1794 (E:)) — so the exact case the gate exists to handle (pristine file missing on a folder lane) instead dies on a raw `check` failure with no gate-authored refusal. This is a **confirmed still-open live-verification finding** with no in-hunt owner; closure belongs to the register row's own fix cycle.

**Evidence.**
- `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt:1777` (check) and `:1790-1794` (folder-lane scanner gate) — code read during the hunt's session on the E: tree (`lens-desktop-production.md:30, :128`). Not re-verifiable in this snapshot (the file is 1040 lines here vs the (E:) 2455+; confirmed this session).
- `out/live-verification/coverage-ledger.md:272` — **LVF-flavor-import-003 in the still-open list** (grep run this session; verbatim: "still-open — 37 (open or confirmed; unresolved): … LVF-flavor-import-003 …").
- **The original finding body (`out/live-verification/findings.jsonl`) is absent from this workspace** (register `:3, :99`) — so the blast radius (an edge path vs the whole folder lane) is **unconfirmed**; that is why severity stays provisional.

**Root cause analysis.** An existence check ordered ahead of the gate designed to own that failure. The scanner gate and the `check` answer the same question at different layers — the gate with a lane-authored refusal (code, copy, remediation hint), the `check` with a bare precondition crash. Ordering them wrong converts a handled case into an unhandled one on precisely the lane (folder import) that most often lacks a pristine file, because folder lanes are the user-assembled ones.

**Step-by-step fix.**
1. **Pull the finding body first** (`out/live-verification/findings.jsonl` from the current tree) to confirm blast radius before choosing the shape of the fix — this is a hard precondition of the section, not a nicety: the register explicitly records the body's absence and the severity as provisional because of it.
2. Re-anchor: `grep -n "pristine.isFile\|scanner" desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt` on the current tree (expect (E:) :1777/:1790-1794).
3. **`DesktopRuntimeBackend.kt`** — let the folder-lane scanner gate decide: scope the `check(pristine.isFile)` to the **zip lane** only (where a missing member is genuinely malformed input), or move it after the folder-lane gate — whichever preserves the zip lane's existing contract with the smaller diff. Do **not** weaken the scanner gate itself; the fix is to un-preempt it, never to widen folder-lane acceptance.
4. **New tests** (`DesktopRuntimeBackendTest` — the class exists in this snapshot's test tree):
   - `DesktopRuntimeBackendTest.folderLaneImportWithoutPristineFileTakesTheScannerGateRefusal` — assert the refusal carries the gate's authored code/copy, not a bare `IllegalStateException` from `check`
   - `DesktopRuntimeBackendTest.zipLaneImportWithoutPristineFileStillRefusesAtTheExistenceCheck` — proves the reordering didn't loosen the zip lane
5. **Gate commands that prove it:**
   Focused: `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopRuntimeBackendTest" -PtestResultsDir=build/test-results-fixplan-gap9 --console=plain -q`.
   Closure (this row is an LV finding — the ledger's own convention is that it closes on the leg, not on the unit test): re-run the flavor-import live-verification leg covering LVF-flavor-import-003 (`out/live-verification/matrix-flavor-import.md` rows; scripts under `out/live-verification/scripts/`), then flip the row at `coverage-ledger.md` with the leg's artifact path, mirroring how LVF-server-db-007's flip is recorded at `coverage-ledger.md:270`.

**Dependencies and suggested order.** None upstream; the finding body (step 1) is the entry dependency. Wave 2 — ahead of the P3 wave, because it is the only other gap firing on today's product paths, and its closure convention (LV leg) has the longest lead time.

**Risk notes.** (a) **Provisional severity (P2)** — if the finding body reveals the missing-pristine case is an edge of an edge, downgrade with the evidence; if it reveals the whole folder lane dies, this is arguably P1-adjacent and should jump the queue. (b) The scanner gate exists for a reason (SAF scanning of user-supplied trees); any diff that makes the gate reachable less often must be reviewed as a security-adjacent change, not a refactor. (c) Refusal-code discrimination is itself a verified leg in this repo (`coverage-ledger.md:249` — DESK-53's distinct-refusal-codes contract, "leg 3 HELD (DB-MIGRATION-PENDING)…"); the new folder-lane refusal must keep the gate's existing code, not reuse a generic one. (d) The two cited (E:) line numbers are ~13 lines apart; if the current tree has inserted code between them, re-derive the order from the actual control flow rather than the line arithmetic.

---

## Appendix A — Verification-gates appendix (the full sequence after all fixes)

Run from the **current tree's repo root** (this workspace's `repo/` snapshot predates the hunted tree; the hunt and this assembly between them never ran the full suite — every gate below is a prescription except where a prior recorded result is named). Order matters: fast static gates first, focused classes per package, then the suites, then the deep gates, then the live legs, then the static gates again.

| # | Gate | Command | Proves / covers | Prior recorded result (for delta attribution) |
|---|---|---|---|---|
| 1 | Repo integrity + ledgers | `python tools/check_repo.py` | tracked-file integrity; seal-critical ledger golden if wired (`campaign-inventory.md:45`) | **triage session** (register `:9`): `check_repo: OK (1289 tracked files, 0 errors, 0 warnings)` — this assembly did not re-run it |
| 2 | Flavor + sources pins | `python tools/check_flavor.py && python tools/check_sources.py` | qualification state still vanilla-only (`ClientFlavor.kt:215` premise of GAP-3/4 conditionality); 17 pinned sources | roadmap rev 3 at anchor `b447ef3`: both green (`out/fix-plan/00-roadmap.md` §0). **Not present in this snapshot's `tools/`** — run on the current tree |
| 3 | Focused classes — restore pair | `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopAppModelTest" --tests "com.pocketrealm.desktop.DesktopRuntimeBackendTest" -PtestResultsDir=build/test-results-fixplan-gaps-1-2 --console=plain -q` | GAP-1 gate tests + GAP-2 profile/lifetime tests | new class (`DesktopAppModelTest` did not exist — verified this session) |
| 4 | Focused class — import order | `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopRuntimeBackendTest" -PtestResultsDir=build/test-results-fixplan-gap9 --console=plain -q` | GAP-9 reorder tests | class exists (snapshot test tree) |
| 5 | Focused classes — P3s | `cd desktop && ./gradlew.bat test --tests "com.pocketrealm.server.DesktopServerRuntimeFilesTest" --tests "com.pocketrealm.desktop.DesktopMariaDbEngineTest" --tests "com.pocketrealm.server.DesktopLlmUserOverrideCeilingsTest" --tests "com.pocketrealm.server.DesktopLlmEmbeddedConfTest" -PtestResultsDir=build/test-results-fixplan-gaps-5-6-8 --console=plain -q` | GAP-6 digest tests; GAP-5 pid tests; GAP-8 equality pin; embedded tier pins still holding (LVF-bots-llm-014) | lens round-1 ran `DesktopLlmEmbeddedConfTest` (7) + `DesktopServerRuntimeFilesTest` (36): exit 0, 0 failures/errors/skipped (`lens-desktop-production.md:11`) |
| 6 | Full desktop suite | `cd desktop && ./gradlew.bat test --rerun --console=plain` | everything; cross-gap regressions | parity hunt recorded 315 tests / 0 failures / 42 suites (`docs/DESKTOP-PARITY-GAPS.md` convergence). **Never run in the gap-hunt sessions** (register `:14`, lens `:138`) |
| 7 | detekt | `cd desktop && ./gradlew.bat detekt` | static analysis over the new code; baseline (`build.gradle.kts:26, :71-82`) | recorded **never run** in the parity hunt — first run may surface baseline review work; not a fix-plan failure |
| 8 | Android unit lane | `./gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 -PpocketLane=full` | GAP-8 android side; shared-source twins unchanged | README-recorded command (`repo/README.md:155`) |
| 9 | pytest, hook selection | `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python -m pytest tests/ -q` | tools/manifest/gates lanes (`test_desktop_shared_manifest.py` etc.) | roadmap rev 3 at `b447ef3`: `729 passed, 23 skipped, 8 deselected, 0 failed` (bare run has 8 known-unrelated gladio/vortek/winlator lane failures — use the hook selection) |
| 10 | Supervisor + save-exit gates | the repo's `supervisorStartGate` / `saveExitGate` lanes | GAP-1/GAP-2's deep exit criterion: restore → proof → ordinary-start; quiesce paths under the reworked card | **Not run anywhere in the hunt or this assembly** (register `:14`) — required for wave-1 exit, must be executed or explicitly waived by the owner |
| 11 | Native/DLL bring-up lanes | the native lanes (`native/` gates) | GAP-4's `authGate`-adjacent surface (SRP6 logon consumers `AuthGate`/`RpWowClient`/`RpHost`) if their wiring moved | parity hunt recorded `authGate` green once (`docs/DESKTOP-PARITY-GAPS.md`); gap-hunt sessions never ran the DLL lanes |
| 12 | Live-verification legs | flavor-import leg (GAP-9 closure, LVF-flavor-import-003); desktop-app leg as touched | ledger rows flip only on leg evidence (the LVF-server-db-007 precedent, `coverage-ledger.md:270`) | LV rows currently: flavor-import-003 **still-open** (`:272`) |
| 13 | Re-run gate 1 | `python tools/check_repo.py` | the tree is clean after all of the above | — |

Gate discipline: a gap's section gate (§3) passing is **necessary, not sufficient**; wave 1 is not done until gate 10 runs, GAP-9 is not closed until gate 12's leg flips its ledger row, and the plan is not done until gate 13 is green.

---

## Appendix B — Lineage note: GAP ids against the prior LVF-/DESK-/MP: registers

Three prior registers/rule-sets carry the ancestry of these gaps. All three were consulted directly this session; absence is marked where it applies.

**LVF-\* — the live-verification findings ledger** (`out/live-verification/coverage-ledger.md`; the findings bodies live in `out/live-verification/findings.jsonl`, **absent from this workspace**). LVF ids are per-area finding numbers from the live-verification campaign; the coverage ledger is their disposition of record (fixed-verified / still-open / superseded, `:270`/`:272`).
- **GAP-9 is a direct LVF row**: seed = **LVF-flavor-import-003**, still-open at `coverage-ledger.md:272` (grep run this session). Closure convention = the leg, per Appendix A gate 12.
- **GAP-7's corroboration**: **LVF-server-db-007** fixed-verified at `:270` ("fixing row LV-server-db-14, now pass") — the same ledger whose guestBans prose row (in the other ledger file) GAP-7 corrects.
- **GAP-8's clean neighbor**: **LVF-bots-llm-014** (embedded-lane tier pins) verified holding by the lens round-1 — explicitly *not* re-filed, and GAP-8's fix must not touch it.
- Register-carried (not GAPs): LV-bot-actors-17 / LVF-bot-actors-010 ambient soak → owner lens-test-matrix T1/T2 (`register.md:113`).

**DESK-\* — the desktop parity register** (`docs/DESKTOP-PARITY-GAPS.md`, DESK-1…140; present in this workspace — rows verified by grep this session). The gap-hunt lens's finding ids (DESKPROD-\*) are **new hunt filings**, not DESK rows; their DESK ancestry is:
- **GAP-1** ← DESKPROD-1. Convention precedent from the **DESK** world's multiplayer campaign: stop-with-guests got its D8 consent dialog as **MP:STOP-WITH-GUESTS** (below); Diagnostics is a separately maintained desktop file, so the Android sibling fix (UIUX-2) did not carry over.
- **GAP-2** ← DESKPROD-2; contract owner **DESK-25** "World runtime restart cycles" (`docs/DESKTOP-PARITY-GAPS.md:1069` — verified), with the remedy at the lens-cited `DesktopRuntimeBackend.kt:817-825` (E:).
- **GAP-3** ← DESKPROD-5/5M, dropped by the lens to a pointer in round 3; owner **UIUX-3** (`lens-ui-ux.md`, absent). Prior register: the **F3-3/H2-5** P0 row (`docs/FEATURE-PARITY-GAPS.md:33`, absent here) whose ledgered treatment is `out/fix-plan/performance-persistence-import.md:321` (tick `:395`, landmine cross-ref `:91` — read this session). GAP-3 is that treatment's desktop runtime/status legs.
- **GAP-4** ← DESKPROD-4R, the named code leg of the MFL-owned S5 gap; the pin's "deliberate routing-now" status is **DESK-93** "Per-flavor conf emission & database-name resolution" (`docs/DESKTOP-PARITY-GAPS.md:2153` — verified).
- **GAP-5** ← DESKPROD-6; lane ancestry **DESK-47** (`:582`) and **DESK-106** (`:659`) — the MariaDB provider lane and its dual-provider window; the diagnostics surface it mislabels is **DESK-127** (`:2095`, the statusNative/ServerStatusJson Diagnostics surface).
- **GAP-6** ← DESKPROD-7 (no DESK row; a desktop-production defect) — the only gap whose pattern is confirmed in this workspace's snapshot (`ServerRuntimeFiles.kt:388`).
- **GAP-7** ← DESKPROD-8; the affected store's campaign row is **MP:GUEST-BANS** (below), and the ledger it corrects is the E:-tree `docs/CROSS-PLATFORM-COMPLETION-LEDGER.md` (recorded 527 lines at campaign-inventory time, `campaign-inventory.md:18` — absent from this workspace).

**MP:\* — the multiplayer deliverable ids** (`out/live-verification/campaign-inventory.md`, "multiplayer (12)" block — read in full this session; minted composite ids where no doc id existed, per the inventory's own convention note). Relevant rows:
- **MP:STOP-WITH-GUESTS** — the D8 consent-dialog precedent GAP-1 must mirror (same dialog class, `HomeScreen.kt`).
- **MP:GUEST-BANS** — the DesktopGuestBans feature whose read-path fix GAP-7's ledger row must acknowledge.
- **MP:CONDITIONAL-LOCKOUT** — records `VanillaConfGoldenTest`; the vanilla golden pins are GAP-4's hardest constraint (vanilla conf bytes must not move).
- **MP:HARDENING-FIXES** — the guest-ban mutation hardening (pragmas + BEGIN IMMEDIATE wraps) adjacent to GAP-7's read-path history.
- The inventory also defines the sibling conventions seen in this plan's inputs: **TF-\*** (timeframe, 8 rows — TF-4 pins the desktop conf golden via `DesktopServerRuntimeFilesTest`) and **BOT-\*** (bot-upgrades, 3 rows — BOT-POLICY's desktop profile admission is the surface GAP-2's bots-disabled proof bypasses).

**Ownerless threads this plan deliberately does not fold** (each keeps its audit-assigned owner; `register.md:106-115`): FIND-2 (check_repo leak_sweep — did not reproduce here, `register.md:9-10`), FIND-3 (test_win_lockfile archive probe), MFL-2/MFL-3/MFL-5/MFL-8 (S5 tool legs + pin ownership), UIUX-1 (UI copy sweep), UIUX-2 (Android restore confirm), lens-test-matrix T1/T2. When the absent owner lenses (`lens-ui-ux.md`, `lens-test-matrix.md`, build-gates, multiflavor-lanes) land in this workspace, re-run consolidation per the register's standing caveat (`register.md:128`): GAP-3's severity and census must then come from the owner file, and the FIND/MFL/UIUX rows become full GAPs.

---

## Appendix C — Standing caveats (carried from the register, re-affirmed this session)

1. **Tree fidelity.** Evidence line numbers marked (E:) are from the Oct 6 hunt on the current tree; this workspace's `repo/` snapshot (Sep 29–Oct 1) mostly predates them. Exactly one defect pattern was re-confirmed live here (GAP-6, `ServerRuntimeFiles.kt:388`); two were re-checked and **do not match** the lens's recorded values in this snapshot (GAP-5's pid line, GAP-8's 240/240 constants) because the snapshot predates them. Every section's step 1 re-anchors on the current tree before editing.
2. **Absence of owner lens files.** Only `lens-desktop-production.md` existed at triage time and still exists here. UIUX-3's 18-site census (GAP-3) and the AP-1/DE2-03 contents (GAP-8) are carried at pointer fidelity; both sections say what to do when the owner files surface.
3. **Not run by the hunt, the triage, or this assembly:** the full desktop gradle suite, native DLL lanes, saveExitGate/supervisorStartGate, pytest tools lanes, live-verification legs, the clean-checkout archive probe. They appear as Appendix A gates 6-12 with their status attached; wave-1 and GAP-9 exits depend on the ones flagged.
4. **No fix has been applied anywhere.** This document is a plan; the register entries remain register entries. The first executable work item in this workspace — should the owner want one — is GAP-6 (wave 3's first item): its defect, helper (`FileDigests.sha256`), test class, and gate are all present and runnable in this very tree.
