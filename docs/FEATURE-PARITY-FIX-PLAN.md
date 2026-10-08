# Pocket Realm — TBC/WotLK Feature-Parity Fix Plan (v1.0)

**What this plan is.** The consolidated, execution-ready fix plan for taking Pocket Realm from its shipped vanilla lane to feature parity with TBC (2.4.3.8606) and WotLK (3.3.5a.12340) on one device. It merges two layers into a single document: the **execution roadmap** (dependency-ordered waves, corrections, critical path, decisions, risks) and the **seven per-area fix-plan sections** that carry the per-gap substance — goal, current state, work items, verification, effort, dependencies, owner stage — which the roadmap's waves point into. Nothing here replaces the section content; the roadmap orders it, the sections specify it.

**Inputs.** Two documents upstream of this plan, both read in full by every section:

- `docs/FEATURE-PARITY-GAPS.md` — **the register** (converged v1.0, 2026-09-29; 9-round multi-agent red-team loop): §0 headline corrections C1–C9, §1 critical path (P0 rows), §2 register-by-area tables, §3 independent-verification record, §4 decision points DP1–DP12.
- `docs/TBC-WOTLK-MULTI-FLAVOR-PORT-PLAN.md` — **the port plan**: the S0–S10 stage model whose stage table, master table, risks, and schedule the roadmap annotates; runbook stages S2–S8 execute per flavor (RA = TBC first, then RB = WotLK), S9/S10 close out platform-wide.

Decisions named throughout are recorded in `docs/DECISIONS-PORT.md` (the Decision Log).

**How to read it.**

1. **Execution roadmap** — read first: register §0 corrections as first-class work items (§1), the register §1 critical path ordered with blocking relationships (§2), execution waves with entry/exit criteria and the RP6 measurement gates M1/M2/M3 (§3), the twelve product decisions with recommendations (§4), and the top-10 risk register (§5). Wave membership for every gap ID is stated in the waves and re-validated against each section's `Owner stage` field.
2. **Area sections in register order (§2.1–§2.7)** — one per register area, each starting on a new page break with its own heading and ending in its own coverage ledger. Cross-references between sections are by stable gap ID. Two ID collisions are disambiguated at every use — register §0 correction C2 ("MPQ sets") vs. the §2.1 XP gap `C2`, and §0 correction C3 (opcode 0x1EC) vs. the §2.6 app-feature-walk gap `C3`/`C3-walk` — see the roadmap's ID-collision conventions before following any bare `C2`/`C3`.

**Status: coverage-complete against 200 register IDs.** Every gap ID the register assigns is treated in its area section and ticked `[x]` in that section's coverage ledger — all seven ledgers complete, zero unchecked boxes (verified tick counts: server-systems 65, client-data-runtime 37, bots-llm 29, ux-addons-controls 30, performance-persistence-import 23, integration-coexistence 11, vanilla-regression-guards 10; `grep -c '^- \[ \]'` returns 0 for every file) — plus the roadmap-owned checklist (roadmap §6) covering the C1–C9 corrections and the roadmap-level P0 work (CP-2). Re-verify any time with `grep -c '^- \[x\]'` / `grep -c '^- \[ \]'` per section; per-file counts and ledger line ranges are tabulated in the roadmap's §0.

---

# Execution roadmap

**Status:** planning artifact, rev 3 (2026-09-30, after review round 3) — companion to `docs/TBC-WOTLK-MULTI-FLAVOR-PORT-PLAN.md` ("the port plan"), `docs/FEATURE-PARITY-GAPS.md` ("the register"), and the seven per-area fix-plan sections in `out/fix-plan/`.
**Purpose:** turn the per-area fix plans into one dependency-ordered, decision-annotated execution sequence: register §0 corrections as first-class work items (§1), the register §1 critical path ordered with blocking relationships (§2), execution waves with entry/exit criteria and the RP6 measurement gates (§3), the register §4 decision points with recommendations (§4), and a top-10 risk register (§5).

---

## 0. Inputs read, checks run, and workspace state

**All seven area sections — read and re-validated this round.** Canonical location: `C:/pocket_realm_complete/out/fix-plan/` (consolidated this round; see Workspace note below).

| File | Register area | Ledger ticks (verified `grep -c '^- \[x\]'`, unticked=0 for all) | Ledger lines | File lines |
|---|---|---|---|---|
| `server-systems.md` | §2.1 (63 assigned §2.1 IDs — including C2, whose ledger entry is renamed to disambiguate it from §0 correction C2 — plus 2 carriage ticks: G1-4/G1-5 carried in §G1-7 work items 5-7) | 65 | 981-1045 | 1050 |
| `client-data-runtime.md` | §2.2 (26 register table rows expanding to 36 IDs — incl. the `J2` shorthand of register row `H2-7/H4-7/J2` — plus 1 carried tick: H2-1, the register-§1 P0) | 37 | 573-609 | 609 |
| `bots-llm.md` | §2.3 (29) | 29 | 444-472 | 472 |
| `ux-addons-controls.md` | §2.4 (23 register table rows; 26 table IDs + the four register §1 P0s it carries first = 30 ticked IDs) | 30 | 767-796 | 796 |
| `performance-persistence-import.md` | §2.5 (22 + `F3-3/H2-5`, the register §1 P0 owned here per review round 1) | 23 | 375-397 | 397 |
| `integration-coexistence.md` | §2.6 (11) | 11 | 208-218 | 220 |
| `vanilla-regression-guards.md` | §2.7 (10) | 10 | 257-266 | 266 |

Every ledger ticks its full inventory: all ticks `[x]`, none unchecked, no extra IDs (checked by the grep above; `grep -c '^- \[ \]'` returned 0 for every file). **Round-3 revision note:** the review-round-3 fixes applied to this revision — the corrected §2.6 gate record, the wave-S3/S8 list completions (H3-7, H3-8 + F3-2, F1-9, H3R2-7's S3 entry half; P2-7), the `runtime-builds.json` schema-ownership designation (§2.4 H4R2-10 owns the shape; §2.2 H4-7 and §2.5 P2-9 consume it by reference), and the extended ID-collision conventions (J2-1, H4-7, J2-7/J2, C3/C3-walk with §2.6 C3 as plan of record) — shifted the §2.4 ledger by one line (re-derived in the table above); in-prose `file:line` citations elsewhere are pinned to their verification round. Register docs read in full: `docs/FEATURE-PARITY-GAPS.md` (253 lines + this round's H2-5 ID note at §1; §0 :9-21, §1 :23-37, §3 :220-222, §4 :224-237) and `docs/TBC-WOTLK-MULTI-FLAVOR-PORT-PLAN.md` (475 lines; §5 stages :134-391, §6 master table :394-417, §8 risks :433-457, §9 schedule :467-475).

**Checks executed:**

- *Planning pass (~09:00):* `git -C native/playerbots rev-parse HEAD` → `7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d`; `native/cmangos` → `ce83805d48f9c98b2af617096be5347acb8a1f17`; `native/classic-db` → `be1a5206bedccc8dc088b7c79dae31fcf3dbbfce`; Python read of `schemas/flavor.json` (schema 2; vanilla `qualified:true`, tbc/wotlk `bots`/`server_core` `status:"unresolved"`, `storage:null`, `mpq_set.required:[]`); `ls tools/` (the measurement/staging tools named below all exist; **no `soak_sampler.py`** — P1-10's premise confirmed); `ls native/` (classic lanes only — no tbc/wotlk forks yet).
- *This round (~11:00):* `ls` of both `out/fix-plan` roots + `md5sum` of every overlapping pair (all IDENTICAL — the E: mirror held byte-identical copies); consolidation executed (below); per-file ledger counts and ranges via `grep -c/-n '^- \[x\]'`; `grep -n '^### '` over every section to confirm the P0 treatments exist; owner-stage extraction via `awk` for the gaps re-placed in §3 (J2-4 = S2/S4, G2-8 = S4/S7, G2-10 = S4/S7, H3R2-7 = new:S3, H1R2-2 = S4/S6, H1-4 = S2/S4, F3-5 = S7 then S9, C3-walk = S7 + S9/S10).
- *Round 2 (~12:40):* Python ledger-composition check — server-systems 65 ticks = 63 assigned §2.1 IDs (incl. the renamed C2 entry) + exactly 2 carriage ticks (G1-4/G1-5, each ledger line self-labeled "not one of this area's 63 assigned IDs"); client-data-runtime 37 ticks = 36 §2.2 table IDs + H2-1; register table rows counted programmatically: §2.2 = 26, §2.4 = 23; ux-addons-controls 30 ticks. Straddle re-validation for H3-3/H5-9/P1: `grep -n "530/571\|Stage straddle\|desktop-prepared"` over bots-llm.md and server-systems.md confirms all five passages (:72/:74/:79; :291; :307) state the expansion legs execute in **wave S6** with the desktop lane as fallback-only — matching this roadmap's wave lists, straddle note, and R1 (no "wave S3 on desktop-prepared mmaps" text remains anywhere). `grep` also confirmed ux-addons-controls.md H4-3's Depends-on already cites G3-6 + wave 0, and the J2 owner line in client-data-runtime.md was extended to the S7/S10 split this round. A post-consolidation E: edit of `client-data-runtime.md` (11:52) was diffed against the C: copy and **merged** into C: — the sibling's H5R2-8 S5-measurement item + split owner line, cleaner H5R2-8/P1-4 wording, and corrected first-hand H2-10 `MoveMap.cpp` verification were applied on top of this workspace's warden-family and J2 fixes (every E:-unique change preserved; C: is the union, verified by diff before the E: subset copy was removed).
- *Round 3 (~14:00, all at anchor `b447ef3`, clean tree):* ledger-range re-check for all seven sections (`grep -n '^## Coverage ledger'` + first/last `^- \[x\]` + `wc -l`) — client-data-runtime.md moved to 609 lines / ticks :573-609 (the round-2 merge added one line; §0 row and the §6 bullet re-synced), the other six rows matched their printed values exactly; fast gates re-run and reproduced exactly — `check_repo: OK (1297 tracked files, 0 errors, 0 warnings)`, `check_sources: All pinned sources verified` (17 entries), `leak_sweep: OK (1114 files scanned, 961 allowlisted findings)`, `check_flavor: OK (3 flavors …)`, submodules `be1a5206`/`ce83805d`/`7e2cd2fb`; **pytest run twice** — bare `python -m pytest tests/ -q --tb=no -p no:cacheprovider` → `8 failed, 729 passed, 23 skipped in 460.75s` with exactly the 8 gladio/vortek/winlator failures, and the hook-selection invocation (the 8 ids from `.githooks/pre-commit:20-30` deselected) → `729 passed, 23 skipped, 8 deselected in 459.59s`, 0 failed — both recorded in the Provenance paragraph, which no longer claims 727/25/0 (not reproducible under either invocation). Wave-S4 S4-halves, the wave-S9 `P2-2` re-run, the wave-S9 alias-retirement close-out, and the scoped H2-3/F3-7 edges were verified present (applied in this workspace; the duality note carries the new splits).

**Workspace note (root cause of review round 1's stale-premise finding):** at review round 1 the eight plan files were split across two mirrors — the round-1 record (its `ls`/`cmp`) shows `C:/pocket_realm_complete/out/fix-plan/` holding **five** files (00-roadmap.md, server-systems.md, bots-llm.md, performance-persistence-import.md, vanilla-regression-guards.md) and `E:/pocket_realm_complete/out/fix-plan/` holding **six** (byte-identical copies of the three shared names plus client-data-runtime.md, ux-addons-controls.md, integration-coexistence.md) — i.e. **three sections were missing from C:**. **Consolidated this round:** integration-coexistence.md had landed on C: by the time the consolidation ran (md5-verified identical on both roots before any deletion — `342e02e8…` both sides), the two still-missing sections (client-data-runtime.md, ux-addons-controls.md) were copied from E: to C:, then every E: copy whose md5 matched its C: twin was deleted (each deletion preceded by an individual `md5sum` equality check; `performance-persistence-import.md` never existed on E:). End state verified by `ls`: `C:/pocket_realm_complete/out/fix-plan/` holds all eight files; `E:/pocket_realm_complete/out/fix-plan/` is empty.

**Provenance (review round 2):** line/symbol citations across the area sections were verified against the working tree at consolidation time, including uncommitted changes; commit `b447ef3` (`b447ef320be7acc317f18a6d984bb647b458313c`) is the anchor once landed — it was cut this round from that working tree with the pre-commit gate green (check_repo OK 1297 tracked files / 0 errors, all 17 pinned sources verified; pytest — corrected at review round 3 with **both invocations executed at the anchor**: the **bare suite** `python -m pytest tests/ -q --tb=no -p no:cacheprovider` reports **729 passed / 8 failed / 23 skipped** (reproduced three times at `b447ef3` — twice by review round 3 at ~460 s, once by this workspace at 460.75 s — failing exactly the 8 gladio/vortek/winlator lane tests the round-3 review names: device/build-artifact lanes, not plan-anchored tests); the **hook's documented selection** (`PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python -m pytest tests/ -q` with those same 8 ids deselected, `.githooks/pre-commit:20-30`) reports **729 passed / 23 skipped / 8 deselected / 0 failed** (this workspace, 459.59 s) — the hook's gate is green exactly when its documented deselects apply. An earlier draft of this paragraph claimed "727 passed / 25 skipped / 0 failed": that did not reproduce under either invocation and is withdrawn; first commit attempt failed only on a stale `native/.build-win-x86_64/sqlite-seam-build/CMakeCache.txt` generated under the other mirror's `C:/` path — environmental, removed, re-ran green). At verification time the S0 registry and parity files `android/app/src/main/java/com/pocketrealm/client/ClientFlavor.kt`, `android/app/src/test/java/com/pocketrealm/client/ClientFlavorTest.kt`, and `android/app/src/test/java/com/pocketrealm/client/FlavorManifestParityTest.kt` were untracked (absent from the prior HEAD `ec2783f`), as were the plan documents (`docs/DECISIONS-PORT.md`, `docs/FEATURE-PARITY-GAPS.md`, `docs/TBC-WOTLK-MULTI-FLAVOR-PORT-PLAN.md`) and the gates (`tools/check_flavor.py`, `tools/leak_sweep.py` + their tests); `out/` remains untracked — committing it trips the leak-sweep gate on §2.2/§2.4 prose, so the fix-plan sections stay workspace artifacts.

**Section status:** **seven of seven areas have landed section files with complete ledgers** (table above). Wave membership in §3 was re-validated against every section's `Owner stage` field during this round — including the S3/S6 straddle trio, re-checked explicitly this round by grep: bots-llm.md H3-3 (work items 1/3 and the Depends-on straddle at :72/:74/:79) and server-systems.md H5-9 (:291) / P1 (:307) all state the 530/571 legs execute in **wave S6** with DP12's desktop lane as fallback-only (`desktop-mmaps`-labeled datapoints, never the default input) — matching the roadmap's wave lists, straddle note, and R1. The carriers in §2 point at the real `###` treatments.

**ID-collision conventions used throughout this roadmap** (each collision below is real and disambiguated at the cited places — two correction-vs-gap pairs plus four shared-token treatment pairs):
- **C2** means register §0 correction C2 ("MPQ sets") in §1 and the wave text; the *gap* `C2` in server-systems.md is the XP-policy sibling of register row `H1-13/C2` and its heading is disambiguated there (`### C2 — XP rates … (register row H1-13/C2; not the §0 correction C2 "MPQ sets")`, server-systems.md:354, ledger entry renamed to match).
- **C3** means register §0 correction C3 (auth opcode 0x1EC) in §1 and wave S8; the *gap* `C3`/`C3-walk` in integration-coexistence.md and ux-addons-controls.md is the app-feature-walk umbrella (waves S7/S9/S10). Each usage below names which it means.
- **H2-5** (register §1 row F3-3/H2-5) has no standalone §2.x row — it is that row's own shorthand, folded into the F3-3 treatment; a note to this effect was added to the register row (`FEATURE-PARITY-GAPS.md:33`) this round.
- **J2-1** names two distinct treatments that share the register token (register rows `H1-2/J2-1/H5R2` and `H3-2/J2-1`), both ticked in their own ledgers: the **owner of record for the conf/account half is §2.1** (`### J2-1 — DK gating conf seeds + account.expansion precondition`, server-systems.md:25 — the `HEROIC_CHARACTERS_PER_REALM`/`MIN_LEVEL_FOR_HEROIC` seeds plus the `account set addon` sequencing); the **§2.3 treatment** (`### J2-1 — DK bot factory creation, slot policy, and gating`, bots-llm.md:52) is the separate bot-factory/slot-policy work package (`new:S3`, effort L) that consumes §2.1's conf emission — cross-reference halves, not duplicates.
- **H4-7** is ticked in two sections: the **owner of record is §2.4** (`### H4-7 — Crash artifacts per flavor … and per-flavor log inventory`, ux-addons-controls.md:187 — the `docs/client-artifacts.md` inventory table is the source of truth); **§2.2 H4-7** (client-data-runtime.md:177) is the cross-reference half owning the harvest/redaction/bundle machinery that consumes that inventory. Their bounded bundle entry is one deliverable — §2.2's `crash-artifacts.json` name governs; §2.4 item 3's `crash-sample.json` defers to it (noted in place).
- **J2-7 / J2**: register row `J2-7` (§2.1, S10) is owned by **§2.1** (`### J2-7`, server-systems.md:824 — the conformance soak + supervisor failure mapping, hard gate at S10); **§2.2's `### J2 (J2-7)`** (client-data-runtime.md:192) is the client-surface cross-reference half (per-flavor error-signature capture, S7 groundwork) and says so in its own intro.
- **C3 / C3-walk** (beyond the correction-vs-gap collision above): the *gap* side is itself carried twice — **§2.6 `### C3` (integration-coexistence.md:118) is the plan of record** for the app-feature-walk umbrella (it owns the sub-story mechanics and the Decision Log entries; its register row is the R7 walk row, and the §2.4 register row itself says "see §2.6"), and **§2.4 `### C3-walk`** (ux-addons-controls.md:735) is its S7 UX/device-surface execution half. The shared work items (Vulkan quarantine per lane, LLM registry/corpus identity, LAN join, first-run replay) are specified once, in §2.6 C3; §2.4 C3-walk consumes them by reference (its Ownership note maps item 2→§2.6 item 1, item 3→§2.6 item 2, item 4→§2.6 item 3, item 1→§2.6 item 4). The roadmap wave lists name the halves `C3-walk S7/S9/S10`; both treatments resolve to the same work.

---

## 1. Plan corrections (C1–C9) — first-class work items

Each correction is executable work with two deliverables: **(a)** edits to `docs/TBC-WOTLK-MULTI-FLAVOR-PORT-PLAN.md` (the rows/work items it makes wrong), and **(b)** edits to `schemas/flavor.json` (+ the `ClientFlavor` registry the parity tests pin, per P2-8 §2.5). Register §0 says "act on these first" (`FEATURE-PARITY-GAPS.md:9`). C1, C2, C4, C5, C6, C8, C9 are doc/data edits landable in **wave 0/S1**; C1, C4, C5 additionally *bind* S2/S3/S4 execution; C3, C7 bind S6–S8 decisions. Coverage for all nine is ledger-tracked in §6 (roadmap-owned checklist).

### C1 — realmlist schema: `realmbuilds` varchar(64), not `gamebuild`

- **Correction (register §0 row 1, `FEATURE-PARITY-GAPS.md:13`):** cmangos TBC/WotLK realmd uses `realmbuilds` varchar(64) — the `gamebuild` numeric column named by the port plan is TrinityCore's schema. Seed `realmbuilds='8606'` (tbc) / `'12340'` (wotlk); vanilla classic keeps `realmbuilds='5875 6005 6141'`.
- **Port plan changes:** §3 compatibility row "realmd acceptance" (:57) — replace `gamebuild=1`/`gamebuild=2` with `realmbuilds` values; §6 master-table row "realmlist schema" (:405) — same; **S2 work item 4** (:214) — "numeric `realmlist.gamebuild` column" → `realmbuilds`, driving `realmd.sql` base handling, seed row, `realmd_runtime.cpp` diagnostics, `ServerRuntimeFiles.kt` conf generation; **S8 work item 4** (:344) — LAN advertisement of the flavor's `realmbuilds` set.
- **`schemas/flavor.json` changes:** per-flavor `realmd` block — vanilla `{"accepted_builds": "5875 6005 6141"}`, tbc `{"8606"}`, wotlk `{"12340"}` — mirrored on `ClientFlavor` so projection/diagnostics and the parity tests (P2-8) read one source. No `gamebuild` field may exist anywhere (leak-sweep candidate).
- **Blocking:** blocks H2-1's locale realmlist projection (§2.2, S7/S8 — its §2.2 treatment names C1 as the composable server half, client-data-runtime.md:10), the S8 auth matrix, and S2's "realmd accepts exactly the flavor build" adversarial test (port plan :223).
- **Wave:** doc/data edit in wave 0; bound execution at S2 (per flavor).

### C2 — MPQ sets: corrected TBC/WotLK root sets *(register §0 correction — not the §2.1 XP gap `H1-13/C2`)*

- **Correction (register §0 row 2, `:14`):** TBC root = `common, expansion, patch, patch-2` plus the `Data\enUS\` locale/speech set — *not* vanilla-11+`expansion`. WotLK root = `common, common-2, expansion, lichking, patch, patch-2, patch-3` (**no `patch-4`**) plus the locale set; tolerated auxiliaries `base-enUS`, `backup-enUS`. Evidence: checksum corpora + real client load logs.
- **Port plan changes:** §3 MPQ row (:62) and §6 MPQ row (:400) rewritten to the corrected sets; **S5 work item 2** (:276) — allowlists re-derived with **exact-set semantics** (an unknown `patch-*.mpq` is a `VAL-04` naming the file — §2.2 H2-2/H2-9's treatments, client-data-runtime.md:23-52).
- **`schemas/flavor.json` changes:** at S5, fill `tbc.mpq_set.required` / `wotlk.mpq_set.required` (empty today — verified) with the corrected sets; note fields carry the tolerated-aux list (H2-2 work item 1's `requiredMpqPaths` + `toleratedAuxiliaryMpqs` data).
- **Blocking:** S5 import qualification blocks S6 extraction (the extractor consumes the staged set) and G2-5's full-set hash qualification.
- **Wave:** doc edit wave 0; executed S5.

### C3 — `SMSG_AUTH_CHALLENGE` = 0x1EC in all three branches *(register §0 correction — not the §2.6 app-feature-walk gap `C3`/`C3-walk`)*

- **Correction (register §0 row 3, `:15`):** the auth-challenge opcode is 0x1EC everywhere; the plan's "branch-defined" row is wrong. SRP6 frame *layouts* still need per-branch verification (P2-7 keeps that distinction).
- **Port plan changes:** §6 row "Auth opcode/frames" (:416) → `0x1EC all flavors (frames verified per branch)`; **S8 work item 2** (:342) — per-expansion opcode parameterization becomes one shared constant + per-flavor frame descriptors.
- **`schemas/flavor.json` changes:** no per-flavor opcode; shared-constant comment citing C3 (P2-7 work item 4 pins exactly this) + per-flavor `authFrame` descriptors (build-bytes width, proof-frame length) once S8 verifies them. The §2.2 conformance harness asserts the same constant (the `J2` client-half work item 3, client-data-runtime.md:198).
- **Blocking:** unblocks the S8 auth-matrix fixtures (P2-7/P2-8); removes a false per-flavor branch from `RpWowClient.kt`/`AuthGate.kt`.
- **Wave:** doc edit wave 0; realized at S8.

### C4 — playerbots upstream is multi-expansion; one pin serves all flavors

- **Correction (register §0 row 4, `:16`):** `cmangos/playerbots` master carries `MANGOSBOT_ZERO/ONE/TWO`, per-expansion conf dists, and a CI matrix; the fork pin 7e2cd2fb (verified this session) is post-multi-expansion. The S3 kill-switch premise is void. S3 becomes: port the LLM patches + per-flavor conf/SQL selection + disable the in-tree legacy `PlayerBot` module.
- **Port plan changes:** **S3 work item 1** (:233) — kill-switch replaced by the one-pin decision + `puzzled-pancake/playerbots` branch topology (H3-1's D-new decision, bots-llm.md:13); §6 row "Bots tree" (:403) — `TBD (risk)` → pinned multi-expansion tree; §8.1 risk 1 (:439) — demoted to "bots-tree drift/build failure on newer branches" (fallback = the recorded H1-16 decision entry, server-systems.md:414-427); §8.2 external-input item (:453) — "bots-less fallback (S3)" product call removed.
- **`schemas/flavor.json` changes:** resolve `tbc.bots`/`wotlk.bots` from `{null, null, "unresolved"}` (verified present) to pinned upstream + fork branch + commit, `status:"pinned"`, bound by `tools/check_flavor.py`'s flavor↔sources check (bots-llm.md:14).
- **Blocking:** unblocks the entire S3 wave and H1-1's build matrix.
- **Wave:** pin registration wave 0; build-matrix proof S3.

### C5 — ACID is inlined as `[ACID]` commits in tbc-db/wotlk-db

- **Correction (register §0 row 5, `:17`):** there is no `acid_*.sql` file to select; ACID arrives as `[ACID]`-tagged commits in the DB-repo history.
- **Port plan changes:** **S4 work item 2** (:255) — "`ACID` (the flavor's `acid_*.sql`)" → "`[ACID]` commits pulled from the flavor DB repo's history, digest-recorded in the ledger"; carried into `tools/stage_database_migrations.py select_inputs()` (server-systems.md F2-7 work item 1) and H1R2-5's digest pinning.
- **`schemas/flavor.json` changes:** none directly; the per-flavor ledgers (`database-migrations-{flavor}.json`, F3-1 §2.5) record `[ACID]` commit ids/digests in `source_commits`, validated by `tools/check_sources.py`.
- **Blocking:** S4 ledger construction; the wrong rule silently drops ACID and breaks encounter scripts discovered late in S3 strategy testing.
- **Wave:** doc edit wave 0; executed S4.

### C6 — WotLK exe ≈ 2.6× vanilla, fits the scanner cap; pristine MD5s known

- **Correction (register §0 row 6, `:18`):** 12340 `WoW.exe` ≈ 12.6 MB ≈ 2.6× vanilla (~4.8 MB), not "~10×"; fits `MAX_EXE_BYTES = 32 MiB`. Pristine MD5s known (12340 `45892bde…`, 8606 `57c5c030…`, anzz1/wow-client-checksums) → S5.1 = SHA-256 compute + MD5 cross-check.
- **Port plan changes:** **S5 work item 1** (:275) rewritten accordingly; §6 row "Pristine exe SHA-256" (:399) gains the MD5 cross-checks.
- **`schemas/flavor.json` changes:** at S5, `supported_client_builds` entries carry exe size + SHA-256 (+ MD5 provenance); §2.2 H2R2-3 adds `knownExeMd5`/`knownExeSizeBytes` to `ClientFlavor` (client-data-runtime.md:59) feeding P1-8's startup cap assert and P3-1's qualification invariant.
- **Blocking:** S5 import qualification; removes a phantom S5 risk (H2R2-3's negative-vector work still needs the hash half).
- **Wave:** doc edit wave 0; executed S5.

### C7 — WotLK 3.3.5a is NOT large-address-aware (2 GB VA)

- **Correction (register §0 row 7, `:19`):** resolves the plan's open "verify" — direct input to the S6.4 go/no-go and client memory budgeting. Evidence: community/warmane OOM reports.
- **Port plan changes:** **S7 work item 4** (:321) — "LAA is built into later clients? verify" → answered: not LAA; an LAA variant is a byte-patched exe requiring hash-pinned qualification (§2.2 H2-3's option branch, client-data-runtime.md:100-113), else the RAM admission gate (H2-3 item 3's `minClientFreeMemoryMiB` preflight); **S6 work item 4** (:302) — the go/no-go criteria explicitly include the 2 GB client ceiling.
- **`schemas/flavor.json` changes:** wotlk client descriptor gains `large_address_aware: false` as data; memory-budget fields arrive with §2.2 P1-4's measurements (client-data-runtime.md:115-128).
- **Blocking:** feeds DP12, the graphics-preset default (W1-8), and H2-3's qualification decision.
- **Wave:** data edit wave 0; consumed at S6 (go/no-go) and S7 (H2-3).

### C8 — TBC WTF is already 3-level; only the char-level cache set is WotLK-era

- **Correction (register §0 row 8, `:20`):** TBC already writes per-realm/per-character `SavedVariables`; genuinely WotLK-only is the char-level cache-file set: `config-cache.wtf`, `bindings-cache.wtf`, `macros-cache.txt`, `layout-local.txt`, `chat-cache.txt`.
- **Port plan changes:** §6 row "WTF layout" (:413) → `account-level | 3-level | 3-level + cache set`; **S7 work item 3** (:320) extended to TBC's SavedVariables, with H4-5's scope split accordingly (§2.4 H4-5, ux-addons-controls.md:137-161) and W1-4's `config-cache.wtf` read path (client-data-runtime.md:493-506) scoped to the WotLK-era files.
- **`schemas/flavor.json` changes:** per-flavor `wtf` descriptor (vanilla account-level; tbc 3-level; wotlk 3-level + cache set) consumed by the `InGameSettingsFiles`/`SavedVariablesCodec` rework and G3-10's WtfLayout strategy (§2.7, vanilla-regression-guards.md:230-251).
- **Blocking:** S7 settings/bindings catalogs (H4-5, H4-6, H4R2-4), W1-4, G3-10's vanilla freeze.
- **Wave:** doc/data edit wave 0; executed S7.

### C9 — WotLK VoiceChat is a 46-line inert stub

- **Correction (register §0 row 9, `:21`):** mangos-wotlk's VoiceChat swallows the opcode, exposes **no conf keys** (classic has none). H1-17's "VoiceChat conf keys" overclaim retracted. Ship as-is + docs note.
- **Port plan changes:** no plan row exists to fix; lands as a Decision Log entry + a constraint on H1-17: the conf-matrix generator (`tools/generate_conf_matrix.py`, server-systems.md:433) must not emit or expect `VoiceChat*` keys; flavor docs gain the honest "VoiceChat UI present but inert" note.
- **`schemas/flavor.json` changes:** conf-matrix data records VoiceChat under an explicit `excluded_keys` note (the F2-8 self-documenting-exclusion pattern, server-systems.md:947-961).
- **Blocking:** none (negative-space correction); protects fail-loud conf staging from a phantom key family.
- **Wave:** executed at S2/S3 when the matrix generator first runs.

---

## 2. Critical path — register §1 P0 items, ordered

Register §1 (`FEATURE-PARITY-GAPS.md:23-37`) names twelve P0 rows; grouped by shared ID they yield the nine entries below, ordered by dependency (guards → fork substrate → measurement gates → qualification landmine → content → client-facing). "Carried by" names the section file and treatment holding the detailed work.

| # | P0 item (register §1) | Blocking relationships | Carried by |
|---|---|---|---|
| CP-1 | **G3-1..G3-10 vanilla-regression quarantine hazards** (`:35`) | **Blocks every stage.** Shims (G3-1 account store, G3-2 manifests, G3-4 journal codec) and golden pins (G3-3 ledger seal sha `3771fc35…dc68`, G3-7 conf bytes, G3-6 catalog hashes) must exist *before* the surface they protect is touched: G3-3 before S1's ledger renames; G3-5 (scoped pointer pruning) before S5 activates a second flavor; G3-6/G3-10 goldens committed in wave 0 and re-checked at S7's entry, before the catalog/WTF rework; G3-8 before S3's corpus work. | §2.7 — `out/fix-plan/vanilla-regression-guards.md` (all ten treatments + gate commands) |
| CP-2 | **F1-1/F1-4/F1-5 — facade, overlay anchors, DatabaseSqlite classic-verbatim** (`:34`) | Blocked by: S1 parameterization. Blocks: everything native (S2 exit, S3 build, S6 extractors). Work: re-derive the `POCKET_EMBEDDED` facade commit (16 files) per branch's `Master.cpp` (wotlk signal-wait, no stopEvent); re-anchor the o09 overlay set (`db-sqlite-connection-hardening`, `runtime-dialect-*`, `PocketRealmInteraction.cpp`) per branch; reconcile `DatabaseSqlite` per pinned commit; per-branch anchor verification logged in the S2 evidence pack. Guarded by G3-7 goldens. | Port plan S2 items 2-3 (:207-213) + §2.7 G3-7; SQLite-seam re-derivation dependency = §2.5 P2-6. **Deliberately roadmap-level (no named §2.x gap) — made ledger-visible in §6's roadmap-owned checklist** |
| CP-3 | **G2-1/G2-2 — Warden landmine** (`:32`) | Blocked by: S2 fork existence. Blocks: S2 exit (first login must not terminate mangosd) and Android packaging. Work: lazy null-module patch (G2-3), keep-off defaults (G2-9), first-login-under-Wine smoke; **packaging enforcement = the import-extraction deny lane** (G2-6 work item 5 + §2.2 H5R2-8's deny set, measured at S5) with `sources.json` recording the licensing verdict provenance-only — DP11's mechanism. | §2.1 — `out/fix-plan/server-systems.md` G2-3, G2-9, G2-4, G2-6 (work item 5); §2.2 H5R2-8 (client-data-runtime.md:85-98) |
| CP-4 | **P1-2/P1-3/P1-4 — WotLK memory envelope unmeasured** (`:37`) | Blocked by: S2 device smoke (boot numbers), S3 bots build (bot-count curve + LLM share), S6 prep (Dalaran client RSS). Blocks: the **S6.4 go/no-go (DP12)** and every admission floor/ceiling. Work: mangosd RSS vs bot count (grid preload forbidden via P1-7's conf-presence assert), client RSS at Dalaran (C7's 2 GB VA), boot wall-clock; per-flavor floors/ceilings; LLM share via H3R2-6. | §2.2 P1-4 (the measurement campaign, client-data-runtime.md:115-128) + §2.1 P1 + §2.5 P1-7/P1-8/P1-10 + §2.3 H3R2-6 |
| CP-5 | **F3-3/H2-5 — single-qualified-lane landmine + cross-flavor storage cap** (`:33`; ID note in §0) | **Hard deadline: complete before S5 exit qualifies the second flavor** — eleven `.single()` sites throw the moment tbc qualifies (§2.5's treatment enumerates all eleven + the desktop literals). Work: thread `ClientFlavor` through import/planner/prep; cumulative cross-flavor workspace cap with deny `VAL`; S9 end state. | **§2.5 — `out/fix-plan/performance-persistence-import.md` `### F3-3/H2-5`** (:321-334, ticked in that ledger); cross-referenced as the owner from integration-coexistence.md:3 and :220 |
| CP-6 | **G1-4/G1-5 — ICC Gunship ~20% / The Lich King ~5%** (`:31`) | Blocked by: S2 wotlk fork + S4 wotlk-db pin + H1R2-5 triage. Blocks: WotLK mandatory-path progression with 1+4 bots; S10 playthrough qualification. Work: DP8-family decision per encounter, then TC port (gunship boarding/combat controller; LK defile/Val'kyr phase machine) into ScriptDevAI with `npc_spellclick_spells`/vehicle-seat data, **or** the pre-approved teleport-bypass cut + H1R2-5 aug SQL; triage rows in `docs/content-triage-wotlk.md`. | §2.1 — `out/fix-plan/server-systems.md` **G1-7 work items 5-7** (explicit G1-4/G1-5 carriage, ticked in that ledger) + §2.3 H5-2 (bot strategy half) |
| CP-7 | **H2-1 — realmlist in `Data\<locale>\realmlist.wtf`** (`:27`) | Blocked by: C1 (realmbuilds — the §2.2 treatment names it as the composable server half, not a code dependency), S5 identity (observedLocale). Blocks: *flavor clients never connect* — the most direct ship-blocker; H4R2-1 auto-login; the S8 auth matrix. Work: `projectAll` root + `Data/<locale>/` copies, launch-path + attestation wiring, `appOwnedFiles` extension, vanilla root-file golden, S8 auth-matrix leg. | **§2.2 — `out/fix-plan/client-data-runtime.md` `### H2-1`** (:7-21, owner S7 with the S8 leg); import-time neutralization twin = H2R2-4 (:69-83, S5) |
| CP-8 | **H4-1 / H4-2 / H4-3 — AndroidPort insecure Lua; vehicle/override bars; per-flavor addon catalogs** (`:28-30`) | Blocked by: S5 qualification, H4R2-11 captures, G3-6 (catalog freeze). Blocks: S7 exit ("addon lifecycle for the flavor") and TBC in-combat usability. Work: `SecureActionButtonTemplate` + attribute port with `InCombatLockdown()` guards/defers (per-flavor TOC variants 20400/30300, vanilla 11200 byte-identical); per-flavor slot resolver honoring `GetBonusBarOffset()` + override context + VEHICLE category capture; pinned catalogs (pfQuest-Reforged 30300, Zidras/DBM-WotLK, Omen3, AI-VoiceOver 3.3.5) with `sources.json` provenance. | **§2.4 — `out/fix-plan/ux-addons-controls.md`** `### H4-1` (:11-33), `### H4-2` (:37-58), `### H4-3` (:62-83) — the section's stated first four treatments |
| CP-9 | **H4R2-2 — no character-creation flow exists** (`:36`) | Blocked by: H4R2-1 GLUE machinery, H1-14 matrix fixture (edge scoped: only H1-14 work item 4 consumes the flow — the annotation is in server-systems.md's H1-14 Depends-on line), H1-2 refusal relay (S2/S8), DP4. Blocks: a fresh account is unplayable on any flavor — S7/S8 exit. Work: guided controller walk-through primary (starter-character relay = recorded fallback with trigger conditions); per-flavor creation-grid descriptors; DK gating copy per DP4. | **§2.4 — `out/fix-plan/ux-addons-controls.md` `### H4R2-2`** (:87-108, owner S7 flow / S8 gating); server halves in §2.1 (H1-2 item 4 relay text, H1-14 matrix, J2-1 conf seeds, F1-10 op) |

**Ordering note.** CP-1/CP-2 are substrate (waves 0–S2). CP-3/CP-4 are S2-wave gates (CP-4 completes at S6). CP-5 has the sharpest deadline: wired from wave 0, **complete before S5 exit**. CP-6 lands with S3/S4. CP-7/CP-8/CP-9 are the S7/S8 client-facing block; each hangs off one correction (C1, C2/C6, C8) plus substrate.

---

## 3. Waves — dependency-ordered execution

Shape per port plan §9 (:467-475) and §5 (:147-154): inside a flavor run **S2→S3/S4→S6→S8 is the critical path; S5 and S7 are parallel lanes** that start once S1 lands; the runbook S2–S8 executes twice — **RA = TBC first, RB = WotLK** (D2, :461) — then S9/S10 close out. **Gate edges are exactly the port plan's stage table (:147-148): S7's gate depends on S5 (+S1); S8's gate depends on S2+S4.** Two in-wave *milestone* dependencies exist beyond the gate edges and are documented, not serialized: (a) wave S7's device connect legs (H2-1's "connects to local realm only", the 2-h soak against a live realm) need S2's realm — in the RA/RB schedule S2 always precedes S7, and if S2 lags, S7's catalog/topology work continues on captured clients while those legs wait as milestones, never as gate blockers; (b) wave S8's device first-login/auto-login smoke waits on S7's client without blocking S8's gate — the S8 auth matrix runs on **desktop wire-level fixtures** per port plan S8's end state (:349). Waves S2–S8 execute per flavor (RA then RB); the second pass cites RA artifacts instead of redoing decisions.

The three named **RP6 measurement gates**: **M1** WotLK memory envelope (mangosd RSS vs bot count; client RSS at Dalaran; LLM share), **M2** mmaps runtime, **M3** boot wall-clock.

### Wave 0 — Foundations, pins, forks (S0 residue + S1 groundwork + corrections + fork registration)

- **Gap IDs:** G3-1, G3-2, G3-4 (optional-field shims + current-release fixtures — §2.7); **G3-3** (golden ledger pin — *must precede* S1's renames); **G3-7** (fixture capture + `VanillaConfGoldenTest` standing up — the §2.7 treatment's owner stage is S2/S3/S7, but its fixtures must exist *before* any parameterization, so the capture half is a wave-0 exit item and the golden test is the standing acceptance gate every later conf-touching wave keeps green); **G3-6 + G3-10 golden/pin halves** (their §2.7 owner stage is S7, but the pin work is host-side and needs no flavor runtime: the `addons/catalog-v1.json` sha pin into `tools/check_repo.py`, the `AddonArchiveValidator` vanilla-policy literal pins, the `WtfLayout` vanilla-strategy path-equality tests, the `BotPopulationPolicy` vanilla-row pin, and the binding-enum ordinal pins — committed here so wave S7's entry criterion is satisfiable and the catalog/WTF rework it fences is never unguarded); P2-6 (§2.5, per-flavor win SQLite-seam lockfiles); F3-4 (Decision Log entry — deny gate lands at S5); C1–C9 doc/data edits (§1 above); H3-1 *registration half* (bots branch topology decision + `sources.json` entries); S2-item-1 fork creation (`puzzled-pancake/mangos-tbc`/`-wotlk`, branch `pocket-realm`, registered with `status: unresolved` until built); S0 threading-audit residue (the registry groundwork already exists — verified: `schemas/flavor.json` schema 2, three flavors, vanilla qualified).
- **Entry criteria:** repo at the verified classic pins (`7e2cd2fb`/`ce83805d`/`be1a5206`); `tools/check_flavor.py` + `tools/check_sources.py` green vanilla-only.
- **Exit criteria:** golden fixtures committed *before* any parameterization (G3-3 `check_ledger_golden.py` green; G3-7 first capture committed; G3-6/G3-10 goldens committed — catalog sha pin, validator-policy literals, WtfLayout vanilla path-equality, bot-policy vanilla row); per-flavor naming resolver in place; forks + bots branches registered; C1–C9 plan-text edits merged; **`--flavor vanilla` byte-identical** (S1 end state, port plan :193).
- **Measured milestones:** none on RP6 — wave 0's proof is *byte-identity* (vanilla build staging, empty lockfile diff, vanilla device smoke identical to baseline).

### Wave S1 — Provenance & build parameterization (platform)

- **Gap IDs:** P2-6 (completion); S1 items 1-8 (o11 provenance-split repair `c096bada` vs `ce83805d`, per-flavor `sources.json` entries, `build_native.py --flavor`, o09 overlay split into expansion-neutral vs per-flavor definitions — the split CP-2 consumes, per-flavor extractor lockfiles, ledger *naming* (F3-1's schema design), alias/deprecation windows — **opened here, closed at wave S9**: the alias deletion + Decision Log entry is owned by F3-8's S9 retirement part per G3-3 item 2, vanilla-regression-guards.md:66); H1-17 generator design.
- **Entry criteria:** wave-0 goldens committed; G3-3 gate wired into `tools/check_repo.py`.
- **Exit criteria (reworded for the wave-0 fork registration):** the fail-loud proof applies to the **still-unregistered pins** — the tbc/wotlk DB submodules and extractor pins: `--flavor tbc|wotlk` must fail loudly with actionable messages at each (R2 negative test: an unregistered-pin run aborts naming the missing pin, port plan :194). The **core/bots pins registered in wave 0 but not yet built** exercise a distinct, also-loud failure mode — resolve-then-build fails at configure/build with the pin-mismatch error class (`Playerbots pin mismatch`, bots-llm.md:32; R2 negative test: a deliberate wrong-pin configure run fails before compilation). Both failure classes verified by R2; `--flavor vanilla` byte-identical; provenance-split divergence CI in place; no consumer reads unqualified names (the migration precondition — the alias *artifact* itself is deleted at wave S9, not here).
- **Measured milestones:** none (host-side).

### Wave S2 — Server core bring-up (runbook; RA then RB)

- **Gap IDs:** **CP-2** (F1-1/F1-4/F1-5: five fork commits re-derived — FetchContent SOURCE_DIR pin, Bionic/newer-Boost NDK fixes, `POCKET_EMBEDDED` facade per branch `Master.cpp`, internal-reference scrub, depth-fix/HEAD pin; overlay re-derivation; DatabaseSqlite reconciliation); H1-15 + J3-11 (legacy `PlayerBot` off + both-backends fail-loud — H1-15's S2 items depend only on the fork existing; its H3-1 edge belongs to the S3 build-matrix half, per the scoped Depends-on annotation in server-systems.md); J2-2 (char-enum `ORDER BY logout_time`); J2-3 (tutorial flags verification/fork fix); **J2-4 fork half** (verify the `knownTitles` column and its handlers in the wotlk fork — owner stage S2/S4); G2-3 (anticheat `Enable=0` + lazy WardenModuleMgr patch — CP-3 crash-proofing); G2-9 + H1-17 (keep-off table + conf matrix first run; **C9 exclusion enforced**); **G3-7** (conf-emission parameterization kept golden — the wave-0 fixtures are the acceptance gate for C1's realmd conf rework); C1 (realmbuilds seeding + diagnostics); F1-10 (relay `account set addon` + ABI discipline); J2-1 (heroic-class conf seeds); H1-2 conf half; **H1-4 S2 half** (assert the wotlk fork builds with `AchievementMgr` sources included and no overlay touches them — owner stage S2/S4); **H5-4 S2 half** (achievement-family groundwork assert on the fork — same owner line); P1-7 (boot budgets + staged-grid-load conf pin + boot-progress line); G1-8 inputs (vehicle DBC names → H2-11 manifest source); H1-16 (mod-playerbots fallback record); DP11 (Warden verdict — import-lane enforcement per CP-3) and DP1/DP4 first records.
- **Entry criteria:** wave S1 converged; forks registered (wave 0); RP6 window available (port plan §8.2 item 3).
- **Exit criteria (port plan :219-225):** submodule pins green; Android + Windows lanes build with lockfiles; on-device world reaches "ready" on base-SQL seed, 30-min stable session, clean Save & Exit, kill-9/corrupt-DB quarantine verified; realmd accepts exactly the flavor build (adversarial both directions); overlay digests recorded (CP-2's evidence-pack verification — ticked in §6); gauntlet converged.
- **Measured milestones:** **M3 first leg** — world-ready wall-clock with maps+mmaps, then with bots (P1-7), recorded as `worldBootBudgetMs`/`botWorldBootBudgetMs`; **M1 first leg** — mangosd RSS at minimal content and first bot population.

### Wave S3 — Bots & LLM patches (runbook; RA then RB) — the largest wave

- **Gap IDs (owner stage `new:S3` per section unless noted):** H3-1 (pin flip; consumes H1-15's S2 work), H1-1 (build matrix; `-DBUILD_AHBOT=OFF` pinned), H3-2 + H5R2 + J2-1-bot-half (DK bots), H3-3 **classic-map legs** (travel-graph inventory; the 530/571 soak legs move to wave S6 — see the straddle note), H3-4 (drift matrix + keep-off inertness), H3R2-1 (conf-key matrix derived from the dists, fail-loud staging), H3R2-2 + H5-2 (strategy inventories, flagship-first), H3R2-3 (gear pipeline), H3R2-4 (riding budgets — incl. the swapped-variable flying-budget bug, bots-llm.md:160), H3R2-5 (professions decision), H3R2-6 (LLM memory gate — **M1 LLM share**), H3R2-8 (commander surface clamps), H3R2-9 + **J1-1** (guild keys audit; DP9 — owner treatment integration-coexistence.md:98-114, this file carries the S3-side hooks), H3-9/H3-10 (personas, banter), H3-11 + F3-9 (enum pins, quotas + starvation soak), H3-12 + J1-2 (AhBot + economy DP10), J1-5 (quest-tag friction), J1-9 (befriended-bot pinning), **H3-7** (lore corpora v204/v303 — per-card provenance, the attribution/licensing manifest, Outland/Northrend target counts; effort L), **H3-8 + F3-2** (era lint externalized to per-flavor data files with corpus-driven lint tests and the zone-aware Decision Log record; F3-2 stages and digest-locks the files), **F1-9** (lore asset packaging — the `llm.lore_cards` manifest binding retires D15 item 5), **H3R2-7 S3 entry half** (speech-trigger qualification — per-branch `ChatChannelSource` re-pin + trigger-matrix derivation; the 8606/12340 device leg is wave S7's, per the duality note), **G3-8** (vanilla lore asset golden sha into `tools/check_repo.py` — *first* among the lore items, before H3-7/F1-9/F3-2 touch any asset), **G3-7** (aiplayerbot per-flavor emission kept golden); from §2.1: H1-3 + H3-5 (talents/dual-spec/glyphs), H1-13 + **C2-xp** (bot cap + XP policy — the §2.1 XP gap, not §0 correction C2), H1R2-8 (patch-port regression matrix), G1-9 (TBC heroic CC assist), G1-7 incl. **work items 5-7 = CP-6** (DP8 family), H1-5 (TBC LFG), H5-3 (**DP2** RDF), H1-6 (**DP1** Wintergrasp), H5R2-4 (WG/Halaa bots), H1-7 (BG set — S3 leg uses GM-seeded/relay-created teams; charter-created teams wait for G2-10 in S4 per the re-pointed H1-8 verification), H1-8 S3 leg + H5-7 (arena core + skirmish), H1-9 + J1-4 (phasing + bot phase sync), H1-10 player rules + H5-9 budget patch (H3R2-4's consumer; the flight soak is wave S6), H1R2-4 bot side (transports), J2-6 (/who), J2-5 (currency floors), H1R2-7 measurement half (g/hour); from §2.6: F3-7 (bot map-policy gate), H5R2-2 (**DP3** raid story, decision at S3 entry), **J1-7** (Black Morass timed event rides the H3R2-2 strategy-matrix harness — §2.4 owner stage S3).
- **Entry criteria:** S2 exit recorded; bots pins resolved (C4); per-flavor conf matrix data available.
- **Exit criteria:** bots build green with playerbots ON both lanes; bots spawn, path, level to cap, chat via the LLM lane on-device; 1-hour soak within tick budget; era-lint corpus tests prove no anachronisms and no false bans; conf staging fails loudly on unknown keys; strategy matrix docs with 3-run flagship evidence; drift matrix + keep-off proofs archived; gauntlet converged. *(Expansion-map (530/571) soak evidence is deliberately **not** an S3 exit criterion — it is wave S6's, per the straddle note.)*
- **Measured milestones:** **M1 main leg** — mangosd RSS vs bot count at `ALIVE_REALM_320` (LLM off, then external and in-process lanes on) with tick p50/p95 deltas; **M3 second leg** — `botWorldStartMs` re-measured with the LLM patch set (P1-7 item 5).

> **S3/S6 straddle (resolution of the mmaps ordering cycle).** Bots cannot path on 530/571 without the mmaps stage S6 produces (bots-llm.md H3-3: "bot movement depends on the S6 mmaps for those maps"; runbook order S2→S3/S4→S6). Resolution: wave S3 keeps the **build + travel-graph inventory on classic maps** (H3-3 graph verification, rumor-POI split, H3R2-4's budget patch that H5-9 consumes); the **expansion-map legs — H3-3's 530/571 SQL soak assertion (item 4), H5-9's flight/no-fly soak, and P1's bot-flight datapoint — execute in wave S6** beside the prep that unblocks them, and are listed there. DP12's memo (S6 exit) consumes P1's datapoint, which now runs inside S6 after prep completes — the cycle is gone. DP12's desktop lane (P1-6, `tools/win_prepare_data.py`) is the **pre-decided fallback only** if S6's own prep under-runs; any desktop-lane datapoints are labeled `desktop-mmaps` and never conflated with the on-device M2 numbers — mirrored verbatim at bots-llm.md:74-79 and server-systems.md:291/:307 (re-verified this round; see Section status).

### Wave S4 — Database content & migrations (runbook; RA then RB)

- **Gap IDs:** DB submodule pins (`native/classic-db-tbc|wotlk`); F2-7 (pipeline with **C5** `[ACID]` selection + aug lane); F2-4 (table manifests); F2-9 (measured floors); F2-1 (**DP6** Isle + coverage measurement), F2-2 (**DP7** BfU), F2-3 (dummies), F2-5 (glyph floors), F2-6 (taxi rider — also a wave-S6 input: the rider needs the prepared client data), F2-8 (locale exclusion); H1R2-1 (revision gates + zero-mismatch boot assert), H1R2-2 (**S4 half**: events matrix + activation data; the seasonal-boss acceptance runs ride wave S6 — owner stage S4/S6), H1R2-3 (attunements + bot-carried policy), H1R2-5 (triage + floors + Full_DB digest pins), H1R2-6 (guild bank), H1R2-7 (economy tuning + DP10 joint record); H1-4 + H5-4 (**S4 halves** — achievement tables/coverage + SQLite translate/bot-earn policy; the S2 asserts already ran), H1-11 (barber/calendar), H1-12 (JC/inscription floors), **H1-5 S4 half** (tbc `lfg_dungeon_rewards` seed + F2-4 tbc manifest floors — DP2's Option A consumes this table, so it is seeded the wave after the S3 decision), **H5-3 S4 half** (wotlk `lfg_dungeon_rewards` seed + wotlk manifest floors), **H1R2-4 data half** (TransportAnimation/Rotation DBC names into the H2-11 manifest source + type-42 transport GO data floors — register `:64`, stage S3/S4; the bot side ran in S3), **H5-7 season-vendor verification** (the S4 half of its S3/S4 owner stage — vendor NPCs + season-gear rows), **G1-7/CP-6 port-path staging** (encounter-script SQL + `creature_text`/spell/GO rows via the S4 lane — conditional on DP8 landing "port" at S3; when DP8 = cut, the F2-7 aug SQL carries it instead), **H1-9/J1-4 data half** (phased-chain/conditions inventory at the DB pin, feeding `docs/content-triage-*.md`), **G2-10 seed half** (guild registrar NPCs + charter items + named floors — owner stage S4/S7), **G2-8** (ticket/GM offline auto-reply — owner stage S4/S7; the device leg is wave S7), H1-7/H1-8 data halves (BG/arena templates; **H1-8's S4 charter leg** creates the team through the real G2-10 charter flow), H1-10 data half (flying trainers/CWF/tome), J1-6 (expansion-transition checklist data half; the app-checklist rider is wave S7 — §2.4 owner note); from §2.5: P1-1 (buffer pools + measured apply budget), P2-1 (datadir/grants/probes routing), P2-3 (revision Triples/formats), P2-4 (translator fail-loud + baselines), P2-5 (seed-augment per flavor), P2-2 (snapshot flavor validation), F3-1 (ledger schema v2), **F3-3/H2-5 wiring** (the CP-5 threading rides these lanes; completion gated at S5 exit); **J2-4 schema/probe half** (`knownTitles` persistence proof rides the S4 characters-schema work); G3-9 (snapshot manifest rule).
- **Entry criteria:** S2 exit; tbc-db/wotlk-db pinned; F3-1 schema designed.
- **Exit criteria (port plan :261-266):** per-flavor ledgers with digest chains; MariaDB full apply on-device within the measured budget; snapshots/backups round-trip; SQLite seed green per flavor; world boots from seeded DB, content sanity pass, numeric log-error threshold met; gauntlet converged.
- **Measured milestones:** migration-apply wall-clock + mariadbd peak RSS per flavor (P1-1); SQLite wotlk Full_DB replay time (P2-4); **M3 input** — the seeded world the S2/S6 boot numbers run against.

### Wave S5 — Client identity & import (parallel lane) — the F3-3 deadline

- **Gap IDs:** H2-2/H2-9 (locale-subfolder + exact-set semantics with **C2** sets), H2R2-3 (pristine MD5 cross-checks + negative vectors — **C6**), **H2R2-4** (import-time realmlist neutralization — owner stage S5, client-data-runtime.md:69-83), G2-5 (hash-qualify the full MPQ set **incl. the warden `.bin`/`.key` family measurement** feeding CP-3's deny lane), G2-6 (Repair/Launcher/warden exclusion at import; content-deny half of CP-3's lane), **H5R2-8 S5 half** (deny-set measurement from the qualified 8606/12340 clients — names for the warden `.bin`/`.key` family join the deny data here, in lockstep with server-systems.md G2-6 work item 5; owner stage S5/S7, client-data-runtime.md:85-98), H2-8 (**DP5** non-enUS), H2-14 (root DLL set), P3-1 (startup qualification invariant), P3-2/P1-5 groundwork (storage ceilings vs planner math), P3-3 (file/entry caps), P3-4 (encrypted-entry rejection in all lanes), P3-5 (multi-part detection), P3-9/P3-10 (per-flavor VAL copy), F3-4 (duplicate-copy deny — implemented), **F3-3/H2-5 completion gate (CP-5** — all eleven `.single()` sites threaded + the cumulative cross-flavor cap, per §2.5's treatment; **exit criterion: no `.single()` consumer remains on any lane the second qualification touches**), G3-5 (scoped pointer pruning + legacy `active.json` adoption), H4R2-6 import-copy half (§2.4 owner S5), P2-7 measurement half (win-lane identity constants).
- **Entry criteria:** S1 converged; F3-3 threading landed; genuine 8606/12340 clients available (port plan §8.2 items 1-2).
- **Exit criteria (port plan :285-290):** genuine clients import end-to-end (folder + archive lanes, resumable); negative matrix green with VAL codes; storage preflight correct; manifests carry flavor+build+locale; **CP-5 complete**; gauntlet converged.
- **Measured milestones:** extracted-tree sizes, per-MPQ hashing import-time delta (G2-5), planner math vs measured sizes — P1-8's quota inputs and M1's storage half.

### Wave S6 — Data preparation (runbook) — the go/no-go wave

- **Gap IDs:** extractor build/pin per flavor (per-flavor `.so` naming per D4); H2-10 (nav-version manifest + tile-swap adversarial), H2-11/F1-8 (required-DBC manifest — consuming G1-8/H1-3/H1-11 inputs), **P3-6** (**M2** benchmark) + **P1-6** (desktop-assisted fallback pre-decided — **DP12**), P3-7 (child-RSS guard + derated threads), P3-8 (prep peak-space), P1-8 (quotas measured), **P1-4** (**M1 completion** — the named measurement campaign, owner S6) + **P1-5** (measured storage reality), **H3-3 expansion-map legs + H5-9 flight/no-fly soak + P1 datapoint** (moved from S3 — see the straddle note; they run after this wave's own mmaps/taxi data exist), **H1R2-2 boss-acceptance runs** (the S4 half's seasonal-boss device legs — owner stage S4/S6), G1-8 enforcement, prepared-data flavor gates both directions; P1-10 groundwork (sampler harness).
- **Entry criteria:** S2 + S5 exits (S5's genuine client with MPQ content hashes).
- **Exit criteria (port plan :305-309):** extractors locked, packaging gate green; on-device prep completes with measured time/storage; manifests gate cross-flavor both ways; mangosd boots with no DBC/map errors (numeric threshold); **DP12 go/no-go recorded**; gauntlet converged.
- **Measured milestones:** **M2 gate** — mmaps wall-clock + peak RSS + output size per flavor; **M1 completion** — client RSS at Dalaran + the full RSS curve; **M3 final calibration** — boot re-run against the full prepared world; the P1 530/571 bot-flight datapoint (post-prep, feeding DP12).

### Wave S7 — Client runtime & UX (parallel lane)

- **Entry criteria: S5 exit (+S1)** — the port plan's gate edge (:147). *Documented in-wave dependency, not a gate edge:* the device connect legs (H2-1's realm-only connect test, the 2-h play soak against a live realm, H3R2-7's client lane, G2-4's capture legs) need S2's realm — the RA/RB schedule runs S2 before S7, and if S2 lags these legs wait as milestones while catalog/topology work proceeds on captured clients. **G3-6/G3-10 goldens committed (in wave 0) is also an entry criterion** — the freezes must exist before the catalog/WTF rework they fence (CP-1's ordering, made mechanical here: wave 0 commits the pins, this gate re-checks them — S7 never authors the goldens it runs against).
- **Gap IDs:** graphics route (DXVK-only per D3); **H2-1 (CP-7** root + `Data/<locale>/` projection); H2-3 (**C7-informed** LAA-variant vs RAM admission gate), H2-4 (d3d9ex × `gxTextureCacheSize` pin ≤2047 + per-DXVK×driver matrix), H2-6 (movies off + stuck-in-movie), **H2-7** (Cache/WDB/Errors/Screenshots accounting), H2-12 (shader-cache preservation), H2-13 (render QA checklist), H2-14 sound smoke, H4-7 (crash artifacts + flavor-aware `runtime-builds.json`), **H5R2-8 S7 half** (execution deny of nested/any-depth `launcher.exe`/`backgrounddownloader.exe`/`repair.exe`/warden family + the offline cold-start QA run per flavor — pairs with the S5-measured deny data); H4-1/H4-2/H4-3 (CP-8), H4-4 (interact), H4-5/H4-6 (WTF 3-level per **C8**; per-char keybindings), H4R2-1 (auto-login), H4R2-3 app half (realmName per flavor + realmflags 0x40), H4R2-4/H4R2-5 (settings catalogs + SAFE_CONFIG seed), **H4R2-7** (storage budget display; numbers land with S5/S6), H4R2-9 (IME), H4R2-11 + H4-11 (binding catalogs + chord audit), H4-12 (DK HUD), H4-13 (VoiceOver), H4-14 (out-of-date copy), H4-10 (validator caps), H4-9 (TOS/EULA), J1-3 (self-service recovery — S2 relay is its server precondition), J1-8 (bank panel), **J1-6 app-checklist rider** (§2.4 owner note: the S4 data half's in-app checklist lands here); **G2-10 manual-charter-flow half** (registrar flow on device; the S4 seeds are its input), **G2-8 device leg** (ticket auto-reply on device); **G3-6 + G3-10** (per-flavor catalog/WTF variant work executed against the wave-0-committed goldens — pins re-verified at exit, never first authored here); **G3-7** (`Config.wtf`/SAFE_CONFIG per-flavor projections kept golden); H1-14 (creation matrix) + H4R2-2 (CP-9); G2-4 (Wine platform-report qualification); W1-2/W1-4/W1-7/W1-10/P2-a (cvars, donor scrub, affinity seed), W1-8 (low-spec presets); UI copy sweep; vanilla-tweaks gated off honestly; **F3-5 S7 half** (per-flavor WTF layout resolver + retirement sweep + binding-repair scoping — integration-coexistence.md owner "S7 … then S9"); **C3-walk S7 halves** (Vulkan quarantine per graphics lane; LLM selection surface showing the active flavor's corpus/prompt identity; first-run replay story — per the §2.4 owner line); **H3R2-7 device leg** (speech-trigger qualification on the live clients — the straddle half of its S3 entry); **J2 client-half groundwork** (error-signature capture begins with the S7 soaks; the S10 gate consumes it).
- **Exit criteria (port plan :327-332):** flavor client reaches char screen via DXVK (x86 smoke green); 2-h play soak stable; auto-login 5/5 unattended both lanes; settings round-trip; WTF corrupt-recovery clean; addon lifecycle per flavor; disabled features honestly surfaced; leak sweep green; gauntlet converged.
- **Measured milestones:** **M1 client leg consolidated** (client RSS + frame-time under the pinned SAFE_CONFIG; the H2-4 negative run shows the ≥2048 FPS-halving signature and the pin removes it); DXVK attestation proof archived; auto-login rate recorded.

### Wave S8 — Accounts, auth & networking (runbook)

- **Entry criteria: S2 + S4** — exactly the port plan's stage table (:148). The auth matrix runs on **desktop wire-level fixtures** (`tools/rp_harness/probe_world_auth.py` pins; P2-7/P2-8), so S8's *gate* does not wait on S7; the **device first-login/auto-login smoke is an S8 milestone that waits on S7 without blocking the gate** (documented in-wave dependency — see the shape statement).
- **Gap IDs:** F1-10 device leg; J2-1 (expansion sequencing before first login); H1-2 device leg (DK gate refusals); **C3 the correction** (0x1EC shared constant + per-flavor frame fixtures — P2-7/P2-8); auth fixture parameterization (SRP6 frame layouts per branch); **P2-7** (win-lane per-flavor identity/auth/extractor constants — the `new:S8` owner half: `AuthGate.kt`/`RpWowClient.kt` frame descriptors, `win_prepare_data.py`/`install_client_windows.ps1` flavor routing, per-flavor extractor commit; its exe-size measurement half already ran in wave S5); LAN projection per flavor (C1's realmbuilds advertisement); H2-1 S8 leg (auth-matrix composition per its §2.2 treatment); H4R2-3 S8 half (DB + auth verification of the first-login assert); G3-1 consumption (UserAccountStore flavor field written by the expansion flow — schema stays 2, per H5R2-1's G3-1-discipline design).
- **Exit criteria (port plan :347-351):** account rows show correct `expansion` (DB-verified); the 3×3 auth matrix green — only matching pairs authenticate, every mismatch fails cleanly with wire-level fixtures; LAN matching-connects/mismatched-refused documented; gauntlet converged.
- **Measured milestones:** the auth-matrix run (pass/fail); first-login-under-Wine smoke per flavor closes CP-3's runtime leg (milestone, post-S7).

### Wave S9 — Coexistence & flavor selection (platform, one-time)

- **Gap IDs:** H5R2-1 (cross-flavor character pinning — per-flavor account stores, fail-closed pinning, honest copy), H5R2-2 copy (raid-story UI copy per DP3's recorded lever), F3-3/H2-5 end state (per-flavor storage accounting visible), **F3-5 S9 interlock** (per-flavor addon namespaces; `rollback()` restores that flavor's set only), F3-8 S9 part (per-stage allowlist retirement; the D9 `.single()` retirement — eight sites; the **migration-ledger alias retirement** — `schemas/database-migrations.json` deleted once every consumer reads `-vanilla`, `tools/check_ledger_golden.py` re-pointed to the canonical name at the same golden sha `3771fc35…dc68`, the leak-sweep allowlist entry retired with it, Decision Log entry recorded per G3-3 item 2, vanilla-regression-guards.md:66 — deployed vanilla datadirs unaffected: the seal binds the packaged asset bytes, not the schema filename), P1-9 (Save & Exit budgets + staggered autosave), P1-10 (**M1/FPS datapoint**: soak sampler shipped; 12340 ≥2 h capture, p50/p95 + thermals), P2-9 (bundle head+tail), P2-11 (update `requires-revalidate` + fail-closed boot gate), P2-12 (journal flavor field + heartbeat + flavor-aware recovery), **P2-2 S9 adversarial re-run** (cross-flavor snapshot/restore rejects in the three-flavor install — the S4 compatibility validation re-proven in the coexistence environment; shares F3-10's backup/restore surface), F3-10 (backup/restore interlock), H4R2-8 (first-run state + flavor-switch card), **H4R2-6 first-run half** (multi-flavor import flow integration — §2.4 owner S9), **C3-walk S9 halves** (LAN join per flavor with mismatch refusal; per-flavor first-run seal; chatter power-file namespace; the LLM story's S9 parts), W2-1 (UTC realm-time policy + DST event test).
- **Entry criteria:** RA and RB complete through S8 (port plan :149).
- **Exit criteria (port plan :367-370):** three flavors side-by-side; 10× alternating sessions with zero cross-flavor bleed (kill-mid-switch adversarial); storage totals correct across flavors; docs/wiki multi-flavor; gauntlet converged.
- **Measured milestones:** **M1 steady state** — the P1-10 12340 datapoint (frame-time p50/p95, thermal ceiling, ≥700 samples/2 h) and per-flavor growth quotas consolidated into admission ceilings; Save & Exit budgets recorded (P1-9).

### Wave S10 — Program qualification & release (platform, one-time)

- **Gap IDs:** device matrix (RP6 + Adreno + AVD + Windows regression); 8-hour soak per flavor; P2-8 (exhaustive parity tests + desktop parity + synthetic-client matrix), P2-10 (redaction sweep per conf surface), **J2-7 both halves** (§2.1's supervision/conformance soak + §2.2's client-error-signature classification — the register's S10 item), F3-6 (flavor-switch storm ≥10 iterations, zero bleed — S0-finding gate), **H4R2-10 bundle-audit half** (program-level bundle audit across flavors — §2.4 owner "S7 shape, S10 audit"), **F3-8 DoD assertion** (`check_flavor.py --owning-tables-only`), **C3-walk wiki pass** (per-flavor Vulkan-driver page and LAN copy — §2.4 owner "S9/S10"), W2-2 (trust & modifications acceptance doc — pinned to the S10 wiki sweep), W2-3 (cross-device restore in the device matrix), W2-4 (LAN population derate + 2-client soak before LAN exit), P2-11 exercise (update over a three-flavor install), upgrade idempotence, docs/wiki sweep, release engineering (`flavor.json` qualified ×3).
- **Entry criteria:** S9 converged.
- **Exit criteria (port plan :385-390):** full matrix green; soaks archived; no open S0–S2 program-wide; vanilla regression suite green; leak sweep green in CI; Decision Log closed or accepted; final whole-program red team converged.
- **Measured milestones:** **all three gates re-affirmed at program level** — 8-h soaks re-sample M1 under the admission ceilings; prep/boot numbers re-run on the second device; the conformance soaks close J2-7 with zero ERROR-#0/#132 events.

**Runbook duality note.** Waves S2–S8 execute twice (RA=tbc, then RB=wotlk re-using RA's re-derived overlay techniques and measured budgets — port plan :470-471); RB entries cite RA artifacts instead of redoing decisions. Straddles are noted in the wave where each half is listed: **H3R2-7**'s S3 entry carries the straddle note and its device leg is listed in wave S7; **G2-4's** capture legs are listed in wave S7 (its owner stage) and referenced from the S7 milestone dependency; **H3-3/H5-9/P1** are split S3(classic-map)/S6(expansion-map) per the straddle note; **H1R2-2** is split S4(data)/S6(acceptance runs); **H1-4/H5-4** are split S2(asserts)/S4(content); **H1-5/H5-3** are split S3(loop design + DP2)/S4(`lfg_dungeon_rewards` seeds + manifest floors); **H1R2-4** is split S3(bot side)/S4(transport DBC/GO data); **H5-7** is split S3(skirmish)/S4(season vendors); **G1-7/CP-6** is split S3(DP8 decision + device legs)/S4(port-path script-SQL staging — only when DP8 = port); **H1-9/J1-4** are split S3(phase-sync behavior)/S4(phased-chain inventory data); **J2-4** is split S2(fork)/S4(schema); **G2-8/G2-10** are split S4(seed/impl)/S7(device); **F3-5** is split S7(resolver+sweep)/S9(interlock); **H4R2-10** is split S7(shape)/S10(audit); **C3-walk** is split S7/S9/S10; **J2-7** is split S7(groundwork)/S10(gate); **F3-7** is split S3(emission-side `botRandomMaps` ⊆ flavor-maps gate, both sides from the S0 registry)/S6(adversarial verification against the prepared-data per-map inventory — wave S6's "prepared-data flavor gates both directions" carries that leg, so the S3-owned gate never blocks on a later stage's manifests).

---

## 4. Decision points — register §4's twelve product decisions

Each: recommended answer, deciding stage, and the wave that cannot pass its gate without the `docs/DECISIONS-PORT.md` entry (port plan §2 invariant 6).

| # | Decision (register §4, `:224-237`) | Recommendation | Decide at |
|---|---|---|---|
| DP1 | **Wintergrasp: port-or-cut** | Smoke the upstream Battlefield cycle behind a flag default-off; escalate to a TrinityCore port only if the cycle itself is broken (H1-6's recommendation). Bots: auto-win timer + "capture nothing" stance (H5R2-4); Halaa inert. | **S2 exit** (record), validated S3 — waves S2/S3 |
| DP2 | **RDF with bots: offline loop or disable** | Option A — offline loop: player queues → bots auto-accept via a `MANGOSBOT_TWO`-gated queue-strategy fork patch → normal flow, RDF rewards from the seeded `lfg_dungeon_rewards`; documented disable only if the loop cannot meet the tick budget (H5-3). | **S3** — wave S3 |
| DP3 | **Raid story: scaling vs bot raid group** | Record at S3 entry per integration-coexistence.md H5R2-2's three levers: recommended **(a) scaling config** — per-flavor `Rate.Creature.{Damage,SpellDamage,Health}` via `flavor.json` + conf emission (keys verified at `mangosd.conf.dist.in:1009-1020`), tuned against Karazhan/Naxx trash, with honest manning copy — because lever (b) (bot raid group) carries an unverified invite-acceptance path (`RandomBotRaidNearby` gates proximity join, not invite acceptance; if verification at S3 shows acceptance missing, that lever's cost jumps and the decision reverts to (a)); (c) documented under-manning with named cuts as the floor. One raid boss per flavor cleared on device as acceptance either way. | **S3** — wave S3 |
| DP4 | **DK unlock: blizzlike vs relaxed** | Blizzlike gate (`HEROIC_CHARACTERS_PER_REALM=1`, `MIN_LEVEL_FOR_HEROIC=55`) + in-app unlock copy; the desktop GM relay is the sanctioned shortcut. Bot-included DK population is the separate `hero_class` flavor policy (J2-1). | **S2** (conf seeds) → **S8** (player-facing gate) |
| DP5 | **Non-enUS clients: restrict or accept-with-copy** | Restrict to enUS at launch with a VAL code naming supported locales; record observedLocale (H2-8 implements the gate as `accepted_locales` data). Rationale: server data is enUS and the C2 locale MPQ sets are keyed enUS; the localized-DBC path (H2-11 coupling) is unqualified. Accept-with-copy = recorded revisit. | **S5** — wave S5 |
| DP6 | **Isle of Quel'Danas: static unlocked vs SSO progression** | Static fully-unlocked baseline via provenance-carrying aug SQL; SSO progression recorded as not simulated (F2-1). | **S4** — wave S4 |
| DP7 | **BfU / missing chains: cuts vs community-fix aug SQL** | Accepted cut with a prerequisite-bridge aug SQL (chain prerequisites marked complete, permanent unlocks granted, successors swept for dead prerequisites); community-fix SQL only if maintained + license-clean with provenance header (F2-2). | **S4** — wave S4 |
| DP8 | **Vehicle encounters (FL/Malygos/Oculus/jousting) — incl. G1-4/G1-5** | Per encounter: Oculus/Malygos P3 → nerf/skip before any bot-vehicle AI investment; Flame Leviathan → port-or-cut strictly by measured TC-port budget (teleport-bypass fallback); ICC Gunship and The Lich King decided in the same DP8-family entry per §2.1 G1-7 work items 5-7 (time-boxed TC port of the gunship controller and the LK phase machine; bypass cut + aug SQL as the pre-approved fallback); Argent jousting → cut-or-nerf. No dead-end quests (F2-7 aug lane). | **S3** — wave S3 |
| DP9 | **Guild formation: sanctioned lane vs out-of-scope** | Per the owner treatment (integration-coexistence.md J1-1): recommend **option A** — a pure conf flip, `AllowGuildBots = 1` for the flavor (bots may sign the *player's* petition only; signings are player-initiated via `PetitionSignAction`, never autonomous) while `RandomBotFormGuild`/`RandomBotGuildCount` stay 0, plus a ≥2 s per-bot signing cooldown if on-device testing shows burst disruption. No fork commit and no new `PocketRealm.*` conf key — the full signing chain already ships in the pinned tree (`PetitionSignAction.cpp`, `GuildCreateActions.cpp`, charter item 5863). Fallback **B**: emit `MinPetitionSigns = 0` (simplest, least blizzlike). **C** honest out-of-scope copy as the last resort. Bot self-organization stays proven off per flavor (H3R2-9). | **S3** — wave S3 |
| DP10 | **Player gold economy: tune vs opt-in AhBot tier** | Measure-first: g/hour at blizzlike rates (H1R2-7), then tune `Rate.Drop.Money`-family keys modestly + document honestly; AhBot stays excluded on all flavors (H3-12 build-flag + staging enforcement); opt-in tier = recorded re-open path only. | **S3/S4** — waves S3/S4 |
| DP11 | **Warden modules: exclude vs vendor** | Exclude the ~80 Blizzard `.bin`/`.key` files — **enforced by the import-extraction deny lane** (G2-6 work item 5 + §2.2 H5R2-8's deny set, file names measured from the qualified clients at S5), because `schemas/sources.json` is a build-source provenance registry and cannot remove files from a user-imported tree; it records the licensing verdict provenance-only. Ship the lazy null-module patch (G2-3) so `Anticheat.Enable=1` + missing modules can never terminate the world; note the one-conf-key-away hazard in G2-9; G2-4's Wine platform-report qualification is the only path to ever arming it. | **S2** — wave S2 |
| DP12 | **WotLK prep: on-device vs desktop-assisted** | Measured go/no-go at S6 exit — qualify the desktop-assisted lane (P1-6, `tools/win_prepare_data.py`) in parallel so the fallback exists before the benchmark; go on-device only if **M2** (mmaps runtime), **M1** (memory envelope), and **M3** (boot wall-clock) land within the admission budgets. User sign-off required if desktop-assisted becomes required (port plan :302). | **S6 exit** — wave S6 |

---

## 5. Risk register — top 10 (trigger → mitigation)

| # | Risk | Trigger (measured/observable) | Mitigation |
|---|---|---|---|
| R1 | **WotLK on-device prep infeasible** (mmaps 4-10 h+ throttled; MoveMapGen OOM) | M2 benchmark at S6 exceeds the admission budget, or P3-7's child-RSS guard fires the OOM-distinct journal failure | DP12 fallback pre-qualified in parallel (P1-6 desktop lane); go/no-go pre-decided before S6 exit; RAM-derated threads; the desktop lane stays the pre-decided **fallback only** — the 530/571 soak legs run in wave S6 after that wave's prep either way (straddle note) |
| R2 | **WotLK memory envelope exceeds the 12 GB shared device** (client 1.4-1.6 GB under a 2 GB VA ceiling per C7 + mangosd + MariaDB + Box64 + LLM KV) | M1: mangosd RSS-vs-bot curve or Dalaran client RSS breaching floors at S3/S6; P1-10 frame-time collapse | Grid preload forbidden (P1-7 conf-presence assert); per-flavor admission floors/ceilings (P1-4/H2-3's `minClientFreeMemoryMiB`); LLM corpus cap (H3R2-6); graphics preset default (W1-8); DP12 demotion (desktop prep + reduced population) |
| R3 | **Vanilla user quarantine** (schema bumps, byte drift, pointer pruning) | Any G3 gate red: ledger seal sha ≠ `3771fc35…dc68`, snapshot `length()==5` violated, journal schema ≠ 2/3, account-store schema ≠ 2, prune deleting a foreign-flavor generation, catalog/lore/conf golden mismatch | G3-1..G3-10 land across **wave 0 → S7** (G3-1/2/3/4 + the G3-7 fixture capture + the G3-6/G3-10 golden/pin halves in wave 0; G3-5 by S5; G3-8 as the first lore item in S3; G3-6/G3-10's S7 halves execute the per-flavor variants against the wave-0-committed goldens, re-verified at S7's entry and exit; G3-9 at S4) — each golden re-run at every subsequent gate |
| R4 | **Fork/overlay re-derivation effort explodes** (Bionic/NDK, newer Boost/`std::`, wotlk signal-wait `Master.cpp`) | S2 host-build failure rate; anchor-miss count in the overlay driver; DatabaseSqlite drift vs the pinned commit | Port-plan risk-3 budget buffer; host-build-first; per-overlay re-anchor log in the evidence pack (CP-2's §6 tick); G3-7 goldens catch conf-emission drift immediately |
| R5 | **Warden first-login termination** (unconditional `WardenModuleMgr` construction when `Anticheat.Enable=1` + modules absent) | First-login smoke under Wine fails at S2; adversarial `Enable=1` run throws | Lazy null-module patch (G2-3) + keep-off defaults (G2-9) + import-lane exclusion of the module family (DP11 = G2-6 item 5 + H5R2-8) + per-flavor first-login smoke as an S2 exit item |
| R6 | **Bots-tree drift or MANGOSBOT_ONE/TWO build failure** (C4 resolved existence, not execution) | H1-1 build matrix red; H3-4 drift matrix exposing hostile deltas; keep-off keys not parsing on a branch | Drift matrix + cherry-pick discipline (`cherry-picked-from:` trailers); H1-16 fallback record with testable trigger conditions; re-pin decision as a recorded D-new |
| R7 | **ICC/LK port effort overrun** (Gunship ~20%, LK ~5% upstream) | TC-port spike exceeds the stage budget; bot strategies cannot clear with 1+4 | H1R2-5 triage classifies blocking-vs-not first; teleport-bypass cut + aug SQL pre-approved (DP8 family / G1-7 items 5-7); H5-2 documents the bot-side ceiling either way |
| R8 | **Client compat traps**: d3d9ex × `gxTextureCacheSize` FPS halving; donor `processAffinityMask`/`coresDetected`; corrupt-MPQ hard boot fail | P1-10 soak shows the ≥2048 signature; first boot pins wrong SoC topology; boot error with no mapping | SAFE_CONFIG pin ≤2047 + per-DXVK×driver matrix (H2-4); import scrub + full-core seed (W1-7/W1-10/P2-a); full-MPQ hash qualification + boot-error→re-import mapping (G2-5) |
| R9 | **Cross-flavor state bleed** (invariant 4), incl. the F3-3/H2-5 landmine firing at second qualification | F3-6 storm finds foreign-flavor rows/pointers; any of the eleven `.single()` sites throwing at the S5 flip; snapshot/ledger cross-apply | CP-5 completes before S5 exit (§2.5's treatment); P2-12 journal flavor + flavor-aware recovery; P2-2 snapshot validation; G3-5 scoped pruning; any bleed is an S0 finding blocking stage exit (§4.3) |
| R10 | **Login/topology + per-client UX fragility** on new builds (auto-login choreography, float-echo format, IME, TOS/EULA gates) | <5/5 unattended auto-login on either lane at S7; settings round-trip mismatch | Evidence-based capture tooling (H4R2-1/H4R2-11); topology tests as androidTest; manual login fallback UI (port plan risk 5); per-flavor float-echo verification (W1-2/W1-4) |

---

## 6. Coverage ledger

Coverage is carried by the **seven area-section ledgers** (all `[x]`, verified this round — counts and line ranges in the §0 table):

- `server-systems.md` 65 ticks (:981-1045) — 63 assigned §2.1 IDs (incl. the C2 entry, renamed to disambiguate it from §0 correction C2) + 2 carriage ticks (G1-4/G1-5 carried in §G1-7 work items 5-7)
- `client-data-runtime.md` 37 ticks (:573-609) — 26 register table rows / 36 IDs (the `J2` shorthand of register row `H2-7/H4-7/J2` is among them) + 1 carried tick: H2-1 (register §1 P0)
- `bots-llm.md` 29 ticks (:444-472)
- `ux-addons-controls.md` 30 ticks (:767-796) — incl. the four register §1 P0s (H4-1, H4-2, H4-3, H4R2-2)
- `performance-persistence-import.md` 23 ticks (:375-397) — incl. `F3-3/H2-5` (register §1 P0, owned §2.5 per review round 1)
- `integration-coexistence.md` 11 ticks (:208-218) — with the F3-3/H2-5 ownership resolution noted at :220
- `vanilla-regression-guards.md` 10 ticks (:257-266)

**Roadmap-owned items** (no area section owns them; ticked here so every plan-of-record item is ledger-visible, re-verified at each stage's gauntlet):

- [x] C1 — realmbuilds correction (treatment §1; executed wave-0 doc edit + S2)
- [x] C2 — MPQ-set correction (treatment §1; executed wave-0 doc edit + S5)
- [x] C3 — auth-opcode correction (treatment §1; executed wave-0 doc edit + S8)
- [x] C4 — playerbots multi-expansion correction (treatment §1; wave-0 pin + S3 proof)
- [x] C5 — ACID-inline correction (treatment §1; wave-0 doc edit + S4)
- [x] C6 — exe-size/checksum correction (treatment §1; wave-0 doc edit + S5)
- [x] C7 — not-LAA correction (treatment §1; wave-0 data edit; consumed S6/S7)
- [x] C8 — WTF-layout correction (treatment §1; wave-0 data edit + S7)
- [x] C9 — VoiceChat-stub correction (treatment §1; S2/S3 matrix-generator constraint)
- [x] CP-2 / F1-1, F1-4, F1-5 — facade commit re-derivation, per-flavor overlay registries, reconciled DatabaseSqlite (register §1 P0; roadmap-level S2 work; **verification = per-branch anchor verification logged in the S2 evidence pack** — this entry is its ticked home; closest sibling treatment §2.5 P2-6)

The roadmap above is the execution view; the seven sections that follow carry the per-gap substance its waves and critical path point into, each with its own coverage ledger. §2.1 opens the register walk with the largest area — 63 server-side gameplay IDs in five themes: the expansion gameplay systems the vanilla core lacks (death knights, 61-80 talents, achievements, LFG/RDF, battlegrounds/arena, flying, phasing) and their bot-side parity, the content/data-pipeline floors, the offline-product posture, session-layer conformance, and vanilla-regression containment. All of it lands on the `puzzled-pancake/mangos-tbc|-wotlk` forks that wave S2 creates.

---

# Fix plan — §2.1 Server gameplay systems

## 2.1 Server gameplay systems (R1: H1, R2: H1R2, R4: F1, R5: G1/G2, R6: J2)

This section owns every server-side gameplay gap the register assigns to lenses H1 (core gameplay), H1R2 (round-2 server follow-ups), F1/F2 (content/data plumbing), G1/G2 (encounter scripts, anticheat/Warden posture), and J2 (session/journey conformance). Its 63 gaps group into five themes: (1) **expansion gameplay systems** that the vanilla core simply does not have — death knights, 61-80 talents, achievements, LFG/RDF, battlegrounds/arena, flying, phasing (H1-2…H1-14) and the **bot-side parity** for each of them (H5*/H3-5/J1-* siblings); (2) **content and data-pipeline floors** — DB pin freshness, events, attunements, transports, quest/raid coverage, table manifests (H1R2-*, F2-*); (3) **offline-product posture** — anticheat/Warden neutralization, conf-key matrices, tickets, charter flow, economy tuning (G2-*, H1R2-7/J1-2); (4) **session-layer conformance** — char-enum ordering, tutorial scope, titles, currency, /who, packet-error supervision (J2-2…J2-7) plus the relay expansion op (F1-10); and (5) **vanilla-regression containment** — every fork commit and conf change here is per-flavor, MANGOSBOT_ONE/TWO- or branch-gated, and guarded by the golden-byte tests of register §2.7 (G3-*). All TBC/WotLK work lands on the forked cores (`puzzled-pancake/mangos-tbc|-wotlk`, branch `pocket-realm`) that stage S2 creates; today only the classic lane is pinned in `.gitmodules` (native/cmangos = puzzled-pancake/mangos-classic @ `ce83805d…`, native/classic-db @ `be1a5206…`, native/playerbots @ `7e2cd2fb…`), so every citation below to a tbc/wotlk upstream file is register-sourced or marked "verify at pin" — the wotlk trees are not checked out in this repo yet. Repo paths cited are verified in `E:/pocket_realm_complete` as of this writing.

---

### H1-2 — DK creation gate (core-side hero-class gating)

- **Goal:** On WotLK, death-knight creation is gated exactly blizzlike (account owns a level-55 character on the realm, one DK per realm) with the two conf keys seeded, and every refusal reaches the player as a clean in-client error.
- **Current state:** The pinned classic core has no hero-class concept at all: `native/cmangos/src/game/Entities/CharacterHandler.cpp` (`HandleCharCreateOpcode`, line 332) gates only race/class legality, and no `HEROIC_CHARACTERS_PER_REALM` / `MIN_LEVEL_FOR_HEROIC` keys exist anywhere in the repo (grep over android/, native/realm-runtime/src/, tools/, schemas/ returns zero hits — verified). The gate itself lives in the mangos-wotlk branch's char-create path and `mangosd.conf.dist` (register row H1-2/J2-1/H5R2, `docs/FEATURE-PARITY-GAPS.md:45`) — verify both at the S2 pin. The app has no character-creation flow yet (register §1 H4R2-2), and `ClientFlavor.kt:16-46` carries no hero-class field, so nothing surfaces a DK-gate refusal to the player today.
- **Work items:**
  1. At S2, in `puzzled-pancake/mangos-wotlk` (branch `pocket-realm`): locate the level-55 prerequisite and one-per-realm counter in the branch's char-create handler (`src/game/Entities/CharacterHandler.cpp` upstream text; do not re-derive line numbers until the fork exists) and confirm it reads the two conf keys from the branch's `src/mangosd/mangosd.conf.dist.in`.
  2. Decide DP4 (register §4 item 4: blizzlike gate vs relaxed for solo) and record it in `docs/DECISIONS-PORT.md` — default recommendation: blizzlike gate + an in-app copy explaining how to unlock (level a character to 55, or use the sanctioned GM relay in the desktop lane).
  3. Extend the per-flavor conf generator `ServerRuntimeFiles.kt` (mangosd.conf emission starts at line 135) so the wotlk `mangosd.conf` carries `HEROIC_CHARACTERS_PER_REALM = 1` and `MIN_LEVEL_FOR_HEROIC = 55` sourced from a new `ClientFlavor` field (e.g. `heroClassPolicy`), never hard-coded; tbc/vanilla emit neither key and the unknown-key check (H1-17) fails the build if they appear.
  4. Wire the refusal text: the relay layer `native/realm-runtime/src/world_runtime.cpp` must propagate the char-create failure reason (add alongside `create_account`, line 271) so the character-creation flow (H4R2-2, §2.4) can render "requires a level 55 character" instead of a generic failure.
  5. Era lint (S3, register H3-8): the TBC ban list must contain death-knight-class concepts so the LLM lane never offers DK creation on TBC.
- **Upstream dependency:** `puzzled-pancake/mangos-wotlk` branch `pocket-realm` — fork of `cmangos/mangos-wotlk` `master` at the S2 pin (commit TBD); the gate and conf keys are upstream text, not new code.
- **Verification:** On device (wotlk realm): (a) fresh account, DK create → refused with clean client error, no character row created (`SELECT COUNT(*) FROM characters WHERE account=<id> AND class=6` == 0); (b) GM-level a character to 55 → DK create succeeds; (c) second DK attempt → refused per `HEROIC_CHARACTERS_PER_REALM=1`; (d) vanilla + tbc conf bundles byte-compare to their golden (G3-7) with no heroic keys present.
- **Effort:** M
- **Depends on:** J2-1, F1-10, H1-14, H4R2-2 (§2.4 area), H1-17
- **Owner stage:** S2/S3/S8 (register); the conf-key piece lands with S2 fork bring-up, the relay/error surfacing with S8.

### J2-1 — DK gating conf seeds + account.expansion precondition

- **Goal:** The two heroic-class conf keys, and the `account.expansion = 2` flag they presuppose, are seeded per flavor from data (never code) and fail loudly when wrong.
- **Current state:** `schemas/flavor.json` (schema 2) already declares all three flavors with `account`-side data absent — the expansion flag flows only via the S8 relay op that does not exist yet (`native/realm-runtime/src/world_runtime.cpp` has `account create` at line 283 and `account set gmlevel` at line 300; no `account set addon`). `ClientFlavor.kt:90-140` declares TBC/WotLK unqualified but carries no conf-key metadata beyond bot policy. The register (row H1-2/J2-1/H5R2 and §1 H4R2-2 "J2-1: seed `HEROIC_CHARACTERS_PER_REALM`/`MIN_LEVEL_FOR_HEROIC` conf") makes this gap the conf/data half of the DK gate.
- **Work items:**
  1. Add a `hero_class` block to the wotlk descriptor in `schemas/flavor.json` (`{"keys": {"HEROIC_CHARACTERS_PER_REALM": 1, "MIN_LEVEL_FOR_HEROIC": 55}}`) and mirror it on `ClientFlavor` (`android/app/src/main/java/com/pocketrealm/client/ClientFlavor.kt`) so the resolver is the single source; `tools/check_flavor.py` validates the block per flavor.
  2. `ServerRuntimeFiles.kt` emits the keys only for the flavor that declares them (H1-2 work item 3); the emission must read `ClientFlavors.byBuild(...)`, not a build literal (leak sweep `tools/leak_sweep.py` guards `12340`).
  3. Sequence the account bootstrap: the S8 relay op `account set addon 2` (F1-10) must execute at account creation **before** first login, because the wotlk core evaluates expansion gating (hero class availability) per account row — verify the exact upstream check at pin (`realmd`/`characters.account.expansion`).
  4. Add both keys to the keep-off/offline-posture table (G2-9) so the audit covers them.
- **Upstream dependency:** none of its own — consumes the F1-10 relay op and the S2 fork; the two key names come from the mangos-wotlk branch's conf dist (verify exact spelling at pin).
- **Verification:** Unit: `ClientFlavorTest`-style test asserting wotlk carries the two keys and vanilla/tbc do not; golden-conf test (G3-7 pattern) that vanilla's emitted `mangosd.conf` is byte-identical to today's. Device: after F1-10 lands, `world_console.py world-account <u> <p>` then a DB probe (`SELECT expansion FROM account WHERE username='<u>'`) returns 2, and a DK-create attempt reaches the H1-2 gate rather than failing earlier with "you must own an expansion".
- **Effort:** S
- **Depends on:** H1-2, F1-10, H1-17, G2-9
- **Owner stage:** S2/S8

### H5R2 — bot DK AI (rune spending, presences, runeforge)

- **Goal:** Bot death knights on WotLK play believably — runes are spent and regenerated per presence, runeforge blades are forged and enchanted, and DK premade specs exist — with zero errors in a 30-minute combat soak.
- **Current state:** The pinned `native/playerbots` tree is the multi-expansion cmangos/playerbots (register §0 C4 confirms MANGOSBOT_ZERO/ONE/TWO in one tree; the per-expansion conf dists `playerbot/aiplayerbot.conf.dist.in`, `.tbc`, `.wotlk` are checked out here and prove it). The classic (MANGOSBOT_ZERO) strategies contain no rune logic; whether the wotlk strategy set (rune-spend priorities, presence switching, `RandomPlayerbotFactory` class-6 support) is complete at the pin is exactly what this gap must prove — the register (row H1-2/J2-1/H5R2 "bot DK AI (runes/runeforge)") flags it as unverified. `playerbot/strategy/values/GlyphValues.cpp` and `strategy/actions/ChangeTalentsAction.cpp` exist in the classic tree (verified) and are the anchors the wotlk spec/glyph plumbing extends (H3-5).
- **Work items:**
  1. In the S3 bots bring-up, inventory the wotlk-side DK surface of the pinned playerbots: factory creation (`RandomPlayerbotFactory`), rune/presence strategy classes, runeforge enchant behavior; file the inventory into `docs/` triage (H1R2-5 pattern) and register missing pieces as fork patches under `native/patches/playerbots/`.
  2. If the upstream rune strategy is absent or degenerate, port the minimal viable set: a rune-aware melee/proc priority for blood/frost/unholy from the tree's existing spec framework (no new AI framework), gated `#ifdef MANGOSBOT_TWO` so classic/tbc builds are untouched.
  3. Add DK premade spec blocks to the emitted wotlk bot conf: `AiPlayerbot.PremadeSpecName.6.<specno>` / `PremadeSpecLink.6.<specno>.<level>` (the key family is verified at `native/playerbots/playerbot/aiplayerbot.conf.dist.in.wotlk:522-536`) authored per current 3.3.5 consensus builds.
  4. Runeforge: seed the runeforge NPC/item data via the S4 wotlk-db pin (verify `npc_trainer`/spell rows for runeforge at pin) and add a bot factory step that forges a runeforge enchant at creation.
  5. LLM persona note for the §2.3 owners: DK banter topics are H3-9's, but the factory must not create DK bots before the J2-1/H1-2 gate is configured — add a population-policy guard in `BotPopulationPolicy.kt` consumers.
- **Upstream dependency:** `cmangos/playerbots` `master` (multi-expansion), mirrored as `puzzled-pancake/playerbots` branch `pocket-realm`; pin verified ≥ lineage of `7e2cd2fb` and re-verified at S3 per register §3 ("verify 7e2cd2fb locally").
- **Verification:** Device soak (wotlk): spawn a 5-bot party incl. one DK each spec; 30-minute instanced combat; assert (a) rune-bar-driven GCD behavior visible in a parsed combat log (no rune-dead bot for >5 s duringBloodlust-style bursts), (b) weapon carries a runeforge enchant (`character_inventory` + enchant probe), (c) `mangosd` log error count over the soak below the S2 numeric threshold, mirroring `tests/test_playerbot_mobile_pacing.py` style assertions.
- **Effort:** M
- **Depends on:** H3-1 (§2.3), H3-2 (§2.3), J2-1, H3-5
- **Owner stage:** S3

### H1-3 — Core talent reach 61-80 + dual-spec

- **Goal:** TBC/WotLK characters earn and spend talent points through level 70/80, and WotLK dual-spec (learn at trainer lvl 40 for 1000g, instant swap) works per 3.1+ rules — with bots exempt from the cost.
- **Current state:** The classic core caps talent content at 60 (`MaxPlayerLevel = 60` at `native/cmangos/src/mangosd/mangosd.conf.dist.in:826`) and has no dual-spec; the TBC/WotLK branches carry the extended talent trees and (wotlk) the dual-spec machinery upstream — verify both at the S2 pin. Nothing in the app references talent data today: the o11 DBC floor is count-only (`android/app/src/main/java/com/pocketrealm/importer/DataPreparationStore.kt:44-45,420-421` — `MIN_DBC = 100`), so a missing/wrong-flavor `Talent.dbc`/`TalentTab.dbc` set would not be caught (H2-11, §2.2, owns the named-DBC manifest; this gap owns the gameplay proof).
- **Work items:**
  1. At S2 pin, verify the tbc/wotlk branches' `mangosd.conf.dist.in` talent-relevant keys and record them into the per-flavor conf matrix (H1-17 data) — including the dual-spec cost/level keys on wotlk.
  2. Verify the flavor's DBC extraction includes `Talent.dbc`, `TalentTab.dbc` and (wotlk) `TalentTree.dbc`-era files; the named-DBC manifest from H2-11 (§2.2) is the gate this gap consumes — do not re-implement, only file the required names into the manifest source.
  3. Functional bring-up per flavor: trainer respec and (wotlk) dual-spec learn/swap against the pinned tbc-db/wotlk-db trainer/NPC rows; confirm the gold cost path (dual-spec 1000g, respec 1g-50g curve) executes and the player's gold-wall accounting (H1R2-7) reflects it.
  4. Bot exemption: confirm bots learn/swap specs free (playerbots handles this internally — verified key surface `PremadeSpecProb` in `.wotlk:527`); if a bot is charged, file a fork patch in `native/patches/playerbots/`.
  5. Add the swap path to the S3 regression matrix H1R2-8 (downrank/ratings/flying-dismount/battle-rez) so swap semantics are re-proven per branch.
- **Upstream dependency:** `puzzled-pancake/mangos-tbc|-wotlk` branch `pocket-realm` (S2 pin, TBD) for core behavior; `cmangos/tbc-db`/`cmangos/wotlk-db` `master` for trainer/NPC/gold data.
- **Verification:** Device (per flavor): level-70 (tbc) / level-80 (wotlk) character via GM relay; respec at trainer succeeds and charges gold; wotlk: learn dual spec, swap in combat-free zone, assert second action bar set + talent reset per active spec (client-visible, DB `character_action` rows swap); bots: `.bot` spec swap free of charge. Re-run under `tests/test_differential_lane.py` on desktop to compare MariaDB vs SQLite lanes.
- **Effort:** M
- **Depends on:** H1-13, H3-5, H1R2-8, H2-11 (§2.2), H1R2-7
- **Owner stage:** S3

### H3-5 — Bot talent specs, glyph loadouts, pet talents per flavor

- **Goal:** Random and player-partner bots build era-correct specs at every level band (1-70 tbc, 1-80 wotlk) including glyphs (`PremadeSpecGlyp`) and wotlk pet talent trees, with no anachronistic talents (era lint clean).
- **Current state:** The classic playerbots tree already ships the machinery and its config surface: `ChangeTalentsAction.cpp`/`.h` and `strategy/values/GlyphValues.cpp` (verified under `native/playerbots/playerbot/strategy/`), and the wotlk conf dist declares the full premade-spec family — `PremadeSpecName/Prob/Link` (`aiplayerbot.conf.dist.in.wotlk:526-529`) and `PremadeSpecGlyp.<class>.<specno>.<level>` with talent-conditional glyph names (`:531-536`). What does not exist is the *content*: no per-expansion spec tables for 61-70/71-80 bands, no pet-talent loadouts, and `ServerRuntimeFiles.kt` emits bot conf from `BotProfiles` (`:123-124`) without any per-flavor spec payload.
- **Work items:**
  1. Author per-flavor premade spec data: for each class, 1-3 specs with `PremadeSpecLink` wowhead-style talent strings at level checkpoints (30/50/70 tbc; 30/50/70/80 wotlk) and matching `PremadeSpecGlyp` chains; store as data files staged via `ServerRuntimeFiles` (same lane as LLM prompt packs), not inside Kotlin.
  2. Emit them in the per-flavor `aiplayerbot-<id>.conf` (`ServerRuntimeFiles.kt:123`) keyed by `ClientFlavor` — tbc dist gets no `PremadeSpecGlyp` keys (they are wotlk-only; H3R2-1 §2.3 confirms the divergence), and the unknown-key guard must fail staging if they leak into the tbc conf.
  3. Pet talents (wotlk): verify playerbots handles hunter/warlock/mage? pet points (hunter pet trees; warlock demonic knowledge is passive — scope to hunter pets); if upstream lacks pet-spec logic, record as documented limitation in the triage doc rather than a fork patch (pet AI is cosmetic for parity).
  4. Extend the era-lint corpus tests (register H3-8) with a talents probe list: no WotLK talents offered on TBC bots, no TBC-only talents on vanilla bots (rule: earlier-content talents always legal).
  5. Random-bot spec distribution: set `PremadeSpecProb` values so random populations are spec-diverse; keep vanilla's emitted conf byte-identical (G3-7).
- **Upstream dependency:** `puzzled-pancake/playerbots` branch `pocket-realm` (same pin as H5R2); spec/glyph content is authored data, no upstream commit required.
- **Verification:** Host soak: 100 fresh random bots per flavor via `scripts/build_native.py`-built world + `tools/world_console.py world-bots 100`; assert every bot above the first talent band has talents spent (`character_talent`-equivalent probe via relay `world-persistence`), glyph panels populated on wotlk, zero "unknown talent" entries in the world log; era-lint unit tests green for the probe list.
- **Effort:** M
- **Depends on:** H1-3, H3-1 (§2.3), H3R2-1 (§2.3), H1-17
- **Owner stage:** S3

### H1-4 — Achievements: core carry-through + criteria audit

- **Goal:** The WotLK achievement system ships complete at the core/DB layer — `AchievementMgr` carried at the fork, `achievement*` tables in the wotlk characters schema, and a written audit of criteria coverage versus a known 3.3.5 matrix.
- **Current state:** The classic core has no achievement subsystem (no `src/game/Achievement*` directory — verified against `native/cmangos/src/game/` listing), so this is purely a TBC/WotLK-lane gap. The register row (H1-4/H5-4, `docs/FEATURE-PARITY-GAPS.md:47`) states upstream cmangos-wotlk criteria coverage is *partial* relative to TrinityCore 3.3.5 and orders an audit vs the TC matrix. No per-flavor characters schema exists in this repo yet (`.gitmodules` has only the classic three entries — verified), so the `achievement*` table set must be confirmed at the S4 pin (`sql/base/characters.sql` of the wotlk branch).
- **Work items:**
  1. S2 fork bring-up: assert the wotlk branch builds with its `AchievementMgr` sources included and no Pocket Realm overlay touches them (the o09 overlay set re-derivation lists `ObjectMgr.cpp`/`DatabaseSqlite.*`/`PocketRealmInteraction.cpp` — achievements are not among them; keep it that way).
  2. S4: confirm the wotlk characters base seeds the achievement table family (achievement/progress on the characters DB) and add the family to the per-flavor table manifest (F2-4) with measured row floors.
  3. Build `tools/achievement_coverage.py`: reads the pinned wotlk-db `achievement_criteria` rows, diffs against a vendored TrinityCore 3.3.5 achievement/criteria ID list (TC `data`/`game` tables, GPL-compatible — record provenance in `schemas/sources.json`), and emits a coverage report `docs/achievement-coverage-wotlk.md` classifying each missing criteria as broken/cosmetic/impossible-offline.
  4. Decide the honest-shipping line: which missing criteria get documented vs which get aug SQL; the *bot-earn policy* half is H5-4's (sibling gap).
  5. Register the TBC lane explicitly as achievements-absent (TBC has the achievement *UI* in 2.4 but no server-side tracking in cmangos-tbc — verify at pin and state it in the flavor's docs page; do not silently ship a dead UI).
- **Upstream dependency:** `puzzled-pancake/mangos-wotlk` + `cmangos/wotlk-db` `master` at S2/S4 pins (TBD); TrinityCore 3.3.5 `master` branch read-only as the criteria reference matrix.
- **Verification:** Device: kill a dungeon boss → achievement toast fires, row lands in the wotlk characters DB, survives Save & Exit and restore; run `tools/achievement_coverage.py` in CI — report generated, zero *unclassified* criteria; MariaDB and SQLite lanes both load the system (SQLite half is H5-4's verification).
- **Effort:** M
- **Depends on:** F2-4, H5-4, H1R2-1, F2-9
- **Owner stage:** S2/S4

### H5-4 — Achievements: SQLite lane carry-through + bot-earn policy

- **Goal:** Every `achievement*` table survives the SQLite seed lane intact, bot-earned achievements follow a recorded policy, and achievement rewards are claimable offline.
- **Current state:** The SQLite lane is proven for the classic set: `schemas/sqlite-seed-baseline.json` records 414 translated entries with per-DB statement/row counts and translation digests (verified, `classiccharacters` 1,110,192 statements), and `tools/sqlite_seed_translator.py` handles generic DDL plus positional-ALTER table rebuilds fail-loud (`:25-42`, `:509+`). But nothing proves the wotlk achievement family translates: it does not exist in any baseline, and the translator's fail-loud on opaque/trigger-bearing tables (register P2-4, §2.5) has never seen wotlk schema text. The bot-earn question is open: random bots completing criteria could flood the single player's achievement feed.
- **Work items:**
  1. Extend the per-flavor SQLite baseline (`schemas/sqlite-seed-baseline.json` gains wotlk/tbc siblings per S4 work item 5 of the port plan) with the achievement family row counts measured from the actual wotlk-db import — no guessed numbers.
  2. Add translator regression coverage: seed the wotlk characters base through `tools/sqlite_seed_translator.py` in CI (extend `tests/test_sqlite_seeding.py` with a wotlk-schema fixture sized to the achievement tables, not the whole 30 MB DB) so any unsupported construct fails loudly with the table named.
  3. Bot-earn policy decision (record in `docs/DECISIONS-PORT.md`): recommended — bots earn achievements silently into their own rows (DB-truthful, zero fork work) and the *feed/announcement* channel stays player-scoped; if upstream broadcasts guild/server-wide announcements per achievement, add a `MANGOSBOT_TWO`-gated fork patch to suppress bot broadcasts.
  4. Reward claim path: verify achievement reward mails/items (mounts, titles) work offline through the normal mail subsystem; list any reward requiring online-only infrastructure in the coverage doc from H1-4.
  5. Wire the family into `tools/seed_realm_db.py` floors so a truncated wotlk seed fails naming `achievement_criteria` (pattern: classic floor checks).
- **Upstream dependency:** none new — consumes H1-4's pins; translator work is repo-local (`tools/sqlite_seed_translator.py`, `tools/seed_realm_db.py`).
- **Verification:** (a) CI: wotlk schema fixture translates green; negative fixture deleting an achievement table from the manifest fails the floor with the table named; (b) device: bot earns an achievement during soak → row present after Save & Exit on the SQLite lane, restore round-trips it; (c) `tests/test_sqlite_seeding.py` extended suite green.
- **Effort:** M
- **Depends on:** H1-4, P2-4 (§2.5), G3-9 (§2.7), F2-4
- **Owner stage:** S2/S4

### H1-5 — TBC LFG tool: rewards seed + offline queue design

- **Goal:** The TBC 2.4.3 LFG tool works offline: `lfg_dungeon_rewards` is seeded, solo players queue, bots fill the group, and the run completes with era-correct rewards — or a documented disable with honest copy ships.
- **Current state:** The classic core already carries a `src/game/LFG/` subsystem (verified: `LFGDefines.h`, `LFGHandler.cpp`, `LFGMgr.cpp`, `LFGQueue.cpp`) — vanilla-era meeting-stone/LFG-chat semantics. The TBC branch extends it toward the 2.4.3 dungeon tool; the register row (H1-5/H5-3, `:48`) names `lfg_dungeon_rewards` as the table to seed and orders either an offline queue-with-bot-fill loop or a documented disable (register §4 decision point 2). No `lfg_dungeon_rewards` seed exists anywhere in this repo (grep verified: zero hits), and bot-side fill is H5-3's problem — this gap owns the TBC half of the split.
- **Work items:**
  1. At the S4 tbc-db pin, seed `lfg_dungeon_rewards` (dungeon IDs × level bands × reward items per 2.4.3 rules) from the tbc-db world DB; add it to the tbc table manifest (F2-4) with measured row counts.
  2. Verify the tbc branch's LFG opcode surface matches the 2.4.3 client (`CMSG_LFG_*` family — assert via the `tools/rp_harness/protocol.py` opcode fixtures, which today pin vanilla only).
  3. Design the offline loop (decision DP2, TBC half): bots must be queue participants, not teleported-in NPCs — use the playerbots group/dungeon strategy lane (§2.3 H3R2-2) as the filler; document the queue-fill loop (leader queues → N bots accept via role strategy → LFG teleports group) in the triage doc before implementing.
  4. Feature flag: if the branch exposes an LFG enable key, seed it; if not, the disable alternative is a fork patch removing the LFG stone/queue UI affordances — prefer conf over patch; either way the app copy (S7) states which shipped.
  5. Keep `Channel.RestrictedLfg = 0` in the emitted tbc conf (offline posture; the key is named by register row H1-17/G2-9) so the LFG channel is not silently filtered.
- **Upstream dependency:** `puzzled-pancake/mangos-tbc` + `cmangos/tbc-db` at S2/S4 pins (TBD); no TrinityCore dependency for TBC (no RDF backport per register).
- **Verification:** Device (tbc): level-70 character queues a normal Outland dungeon; ≥3 bots accept within 30 s; group is teleported; boss kill grants `lfg_dungeon_rewards` (mail/bag check); negative: with the flag off, queue UI is inert and the log shows no LFG errors. RP6 capture archived via `tools/capture_rp6.py`.
- **Effort:** L
- **Depends on:** H5-3, H3R2-2 (§2.3), H1-17, F2-4, F2-7
- **Owner stage:** S3/S4

### H5-3 — WotLK RDF: offline dungeon-finder loop or documented disable

- **Goal:** Random Dungeon Finder on 3.3.5a either works end-to-end offline (bots queue, fill roles, teleport, complete heroics with RDF rewards) or is provably disabled with copy that says so — no half-alive UI.
- **Current state:** The wotlk branch implements RDF atop the LFG subsystem with role checks, the dungeon-cooldown/deserter state machine, and reward rolls driven by `lfg_dungeon_rewards` (register row H1-5/H5-3 splits TBC tool vs RDF; `docs/FEATURE-PARITY-GAPS.md:48`). None of this exists in the repo today: no wotlk core, no wotlk-db rewards seed, no bot queue participant. The offline hazard is structural — RDF assumes a server population; with 1 human + N bots the queue must be resolved *by design*, not by accident (that is register decision point 2).
- **Work items:**
  1. Write the DP2 decision first (`docs/DECISIONS-PORT.md`): Option A — offline loop (recommended): player queues → bots auto-accept via a queue-strategy (fork patch under `native/patches/playerbots/`, `MANGOSBOT_TWO`-gated, subscribing to LFG proposal packets and answering roles from spec data of H3-5) → normal dungeon flow; RDF rewards granted per seeded table. Option B — disable: fork/conf kills the RDF UI affordances while keeping manual grouping; S7 ships honest copy.
  2. Seed `lfg_dungeon_rewards` (wotlk half): heroic + normal reward rows per 3.3.5 (two emblems per heroic random etc.) from wotlk-db at the S4 pin; measured row floors into the wotlk table manifest (F2-4).
  3. Verify the LFG DBC set (`LFGDungeons.dbc`, `LFGDungeonGroup.dbc` era files) is extracted by the wotlk o11 lane and named in the required-DBC manifest (H2-11 §2.2 consumption — same as H1-3 item 2).
  4. Deserter/cooldown integrity: RDF's 15-minute random-dungeon deserter and instance lockouts must persist across Save & Exit (characters DB round trip — assert in the H1-9 persistence harness).
  5. If Option A: cap the loop's bot-concurrency so a queue storm cannot spin the admission controller (coordinate with R4 budgets; the fill loop must respect `BotPopulationPolicy` ceilings).
- **Upstream dependency:** `puzzled-pancake/mangos-wotlk` + `cmangos/wotlk-db` at S2/S4 pins (TBD); the queue-bot patch is Pocket Realm-authored against the pinned playerbots API.
- **Verification:** Device (wotlk, Option A path): solo queue as level-80 → proposal appears → bots lock roles → teleport-in → heroic cleared → the RDF reward rolls credit (emblems visible in the currency tab, J2-5 tie-in); 8-hour soak shows no proposal leaks or duplicated teleports. Option B path: queue button produces the documented "unavailable offline" state with no packet sent (rp_harness session capture proves zero `CMSG_LFG_SET_ROLES` traffic).
- **Effort:** L
- **Depends on:** H1-5, H3-5, H3R2-2 (§2.3), H1-9, J2-5
- **Owner stage:** S3/S4

### H1-6 — Wintergrasp: core battlefield cycle smoke + port-or-cut

- **Goal:** Wintergrasp on WotLK either runs its battle cycle (start/end timers, rank progression, siege engines) from the upstream Battlefield implementation behind a default-off flag, or is cut with a recorded decision and inert-but-clean UI state.
- **Current state:** The classic core's outdoor PvP surface is exactly `OutdoorPvPEP`/`OutdoorPvPSI` under `native/cmangos/src/game/OutdoorPvP/` (verified) — no battlefield framework, no vehicles. The wotlk branch ships Wintergrasp as a Battlefield but the register (row H1-6/H5R2-4, `:49`) states it is *thin* — vehicles/siege engineering incomplete — and register §1 G1-4/G1-5 and decision point 1 frame port-or-cut. No WG code, flag, or copy exists in this repo.
- **Work items:**
  1. Record DP1 (register §4 item 1) at S2 exit: recommendation — smoke the upstream cycle behind a flag default-off; only escalate to a TrinityCore port (GPL-2.0-compatible per register) if the cycle itself is broken, not for polish.
  2. S2/S3: exercise the upstream cycle on-device — battle timer start/finish, team rank accrual from kills, workshop capture, siege-engine spawn; file every divergence as a wotlk-fork issue in the content triage doc (H1R2-5 list).
  3. Feature flag: add the branch's battlefield/WG enable key to the keep-off table (G2-9) with default `0`; emitted `mangosd.conf` (via `ServerRuntimeFiles.kt:135` lane) carries it only for wotlk.
  4. Honest UI copy handoff (S7, §2.4): a "Wintergrasp is disabled in this build" state; the register's alternative "Dalaran-centric with WG disabled" copy if DP1 lands on cut.
  5. If cut: verify the zone loads clean — no orphan siege NPCs, no error-spam from WG scripts at boot (numeric log threshold from S4 end state).
- **Upstream dependency:** `puzzled-pancake/mangos-wotlk` (S2 pin, TBD) — upstream Battlefield sources; TrinityCore 3.3.5 `master` only if DP1 chooses the port path.
- **Verification:** Device (wotlk): (a) flag off (default): 2-hour soak in Northrend with zero WG tick errors and no WG NPCs aggroing; (b) flag on: one full battle cycle observed with bots present (start banner → end banner → cycle timer reset); either way `tools/capture_rp6.py` evidence archived; log error count below the S4 threshold.
- **Effort:** L
- **Depends on:** H5R2-4, G1-7, G2-9, H1-17
- **Owner stage:** S2/S3

### H5R2-4 — WG/Halaa bot strategies + default-off enforcement

- **Goal:** Bots behave correctly around outdoor PvP: Halaa (Nagrand, map 530) capture play is bounded, Wintergrasp participation follows the flag, and an auto-win timer path exists so bots never strand the cycle.
- **Current state:** Classic bots have no outdoor-PvP strategies beyond the vanilla OutdoorPvP zones the classic core knows (EP/SI — verified above). Playerbots upstream carries RPG/travel strategies that route bots through Nagrand (map 530 is in `botRandomMaps` for tbc — `ClientFlavor.kt:111`), but nothing bounds Halaa capture behavior; for wotlk, WG participation is unmodeled. The register row (H1-6/H5R2-4) orders "bot WG/Halaa strategies or auto-win timers".
- **Work items:**
  1. Halaa: decide bounded-play vs inert (recommend inert — record in DP1's decision entry as a TBC-side note): if a bot capture event can strand the zone in a hostile state for the solo player, gate bot Halaa interaction off in the bots conf (per-flavor key from the tbc dist; H1-17 matrix) rather than writing new capture AI.
  2. WG bots: with the flag off (H1-6), assert bots never path into an active battlefield; with the flag on and bots inside during a cycle, implement the auto-win timer fallback (bots hold one side so the cycle completes instead of stalling) — smallest implementation is a bot-side "capture nothing, stand off" stance plus the upstream cycle timer.
  3. Enforce the default-off in staging: `ServerRuntimeFiles.kt` emits the WG key from the keep-off table (G2-9) — an explicit test that the emitted wotlk conf has it `0` unless the settings surface (S7) turns it on.
  4. Hand the UI copy strings (flag on/off) to the S7 flavor sweep (§2.4 work item 7) as part of this gap's deliverable.
- **Upstream dependency:** `puzzled-pancake/playerbots` + `puzzled-pancake/mangos-wotlk` (S3/S2 pins, TBD); no new upstream commit required if the inert path is chosen.
- **Verification:** Soak: 300-bot tbc world for 2 hours with bots routed through Nagrand — zero Halaa capture events (or bounded count if bounded-play chosen); wotlk with flag off — no bot enters WG zone; flag on — cycle completes within the upstream timer budget with bots present; admission-controller tick budget not exceeded (R4 evidence pack).
- **Effort:** M
- **Depends on:** H1-6, H3R2-2 (§2.3), G2-9, H3-3 (§2.3)
- **Owner stage:** S2/S3

### H1-7 — Battleground set per flavor (EotS, SotA, IoC)

- **Goal:** Every battleground a flavor's client can queue for exists as a working template with bot soak, and any broken map is provably unqueueable via config, not silently broken.
- **Current state:** The classic core ships exactly three BGs — `BattleGroundAB/AV/WS` under `native/cmangos/src/game/BattleGround/` (verified). TBC adds Eye of the Storm; WotLK adds Strand of the Ancients and Isle of Conquest (register row H1-7, `:50`: "templates from DB"). The BG templates live in the flavor DBs (`battleground_template` + map/GO data), which are absent until S4; bot soak per map depends on playerbots BG strategies existing upstream per expansion (verify at the S3 pin — classic playerbots carry AV/AB/WS strategies; EotS/SotA/IoC coverage is unproven).
- **Work items:**
  1. At the S4 pin, enumerate the flavor's `battleground_template` rows and the matching core BG classes; produce a per-flavor BG matrix in the content triage doc (H1R2-5): map → template present → core class present → bot strategy present → verdict (enable / disable / document).
  2. Enable the working set per flavor via DB/conf only; for a broken map (e.g., if SotA vehicle gates misbehave), remove it from the queueable set — prefer deleting/flagging the template row in a seed-aug SQL with provenance (F2-7 lane) over core patches.
  3. Bot soak per enabled map: extend the §2.3 strategy inventory (H3R2-2) with per-map assertions; where a map lacks a bot strategy entirely, mark it "player-only" in the matrix and document the queue experience honestly (a BG with no opponents ends fast — decide: leave queueable with bot fill via BG queue join, or disable).
  4. Wire per-map keep-off keys into the conf matrix (H1-17) if the branch exposes them; else the DB route is the single lever — assert it in staging.
- **Upstream dependency:** `cmangos/tbc-db`/`cmangos/wotlk-db` `master` (S4 pins, TBD) for templates/GO data; `puzzled-pancake/mangos-tbc|-wotlk` for BG classes (S2 pins).
- **Verification:** Per flavor × enabled map: 30-minute device soak — queue → join → match completes (win or timer) with bots filling; zero BG-script error spam; disabled maps: queue attempt fails cleanly client-side with no server-side join; matrix doc committed with the triage package.
- **Effort:** M
- **Depends on:** H3-4 (§2.3), H5-7, F2-7, H1-17
- **Owner stage:** S3/S4

### H1-8 — Arena teams and ratings per flavor (core)

- **Goal:** TBC/WotLK arena works at the core/DB layer: team creation via registrar, queue/skirmish matches, era-correct rating math (TBC formula vs WotLK personal-rating matchmaker), and persistent `character_arena_team*` state.
- **Current state:** The classic core has no arena subsystem (no `ArenaTeam*` sources under `native/cmangos/src/game/` — verified against the directory listing). Arena teams, points and ratings arrive with the TBC branch; WotLK adds personal/team rating split and matchmaking deltas (register row H1-8/H5-7, `:51`: "TBC's signature PvP is dead without it"). The characters schema tables (`character_arena_team`, `character_arena_team_member`, wotlk `character_arena_stats`-era) come from the flavor bases at S4. Team creation depends on the charter/registrar flow — G2-10 (sibling below) owns registrar NPCs; this gap owns the competitive core.
- **Work items:**
  1. S4: verify the flavor characters base seeds the arena table family; add to the per-flavor table manifest (F2-4) with measured row floors (likely zero rows — floor is *table present*).
  2. Verify the arena conf surface per branch at pin (season ID, points curve keys — record in the H1-17 matrix); seed season config so vendor gear (H5-7) matches the season.
  3. Queue/skirmish plumbing: prove 2v2 team queued → match → rating delta per the flavor's formula; TBC vs WotLK formulas differ — encode both expected-value vectors in the regression matrix (H1R2-8).
  4. Personal rating decay (wotlk) and team decay rules: verify upstream handling; document behavior for a single-player world (decay may need an accelerated-sim knob for testing — H5-7).
  5. Persistence: arena state rides the characters DB — include arena tables in the Save & Exit round-trip assertion set (H1-9 harness).
- **Upstream dependency:** `puzzled-pancake/mangos-tbc|-wotlk` (S2) + `cmangos/tbc-db|-wotlk` (S4), commits TBD.
- **Verification:** Device per flavor: **S3 leg** — queue skirmish vs bot team (H5-7) from a GM-seeded/relay-created arena team (charter not required yet), win → rating delta matches the flavor's expected vector (regression matrix); logout/login and Save & Exit preserve team, ratings, and weekly state; zero arena-script errors in a 2-hour soak. **S4 leg (re-pointed: G2-10's registrar seeds land in wave S4, its manual charter flow in S7)** — create the team through the real charter flow (buy petition at the registrar, signatures per DP9, turn-in), then repeat one queued skirmish on the charter-created team. Team-creation-via-charter is *not* an S3 exit criterion.
- **Effort:** M
- **Depends on:** H5-7, G2-10 (S4 seed half gates the S4 charter leg; the S3 leg above needs no charter), H1R2-8, F2-4, H1-9
- **Owner stage:** S3/S4

### H5-7 — Arena bot skirmish: teams, decay sim, season vendors

- **Goal:** Bots function as arena teammates and opponents — skirmish mode by default, with a rating-decay simulation and season vendors stocking the flavor's gear — so TBC's signature PvP loop is playable solo.
- **Current state:** Playerbots upstream has BG strategies for the classic maps, but arena-team bot behavior (teammates joining a team, opponents queuing as a unit, skirmish scoring) is unproven at the pin — the register row (H1-8/H5-7, `:51`) explicitly orders "bot skirmish mode (teammates/opponents, rating decay sim, season vendors)". `BotProfiles.kt` (1,734 lines) and the BotPopulationPolicy account math (`BotPopulationPolicy.kt:27` — 9 chars/account) have no arena concept.
- **Work items:**
  1. Verify playerbots arena support at the S3 pin (`MANGOSBOT_ONE/TWO` strategy classes for arena behaviors); inventory into the H3R2-2 (§2.3) strategy matrix and file missing pieces as fork patches under `native/patches/playerbots/`.
  2. Skirmish default: bots form opponent teams on demand (fixed-name teams per flavor for stability — reuse the persona pool, H3-9 §2.3) and accept queued skirmishes; rating changes in skirmish mode recorded but non-punitive per the DP decision in H1-8.
  3. Season vendors: S4 verifies the flavor's season vendor NPCs and loot ids (tbc-db/wotlk-db); bots as vendors are out of scope — the vendors are world NPCs; assert they stock gear matching the seeded season config from H1-8 item 2.
  4. Rating-decay sim: an admin-conf knob (recorded in the conf matrix, H1-17) that compresses the decay week so the app's honest "idle decay happens" copy (S7) is demonstrable; default blizzlike.
  5. Population guard: arena teams consume bot slots — assert team creation respects `BotPopulationPolicy` ceilings (`BotPopulationPolicy.kt:61-65` `validateTarget`).
- **Upstream dependency:** `puzzled-pancake/playerbots` (S3 pin); `cmangos/tbc-db|-wotlk` vendor rows (S4).
- **Verification:** Device: 2v2 skirmish — player + 1 bot vs 2 bots completes; ratings move per skirmish policy; decay knob at accelerated setting demonstrates decay within one session; season vendor sells the flavor's S-gear; 8-hour soak with periodic skirmishes shows stable tick budget and no team-table corruption (DB invariant check).
- **Effort:** M
- **Depends on:** H1-8, H3R2-2 (§2.3), H1-17, H3-9 (§2.3)
- **Owner stage:** S3/S4

### H1-9 — Phasing content pass (TBC SSO chains, WotLK phased quests)

- **Goal:** Every phased quest chain a flavor ships (Sunwell Isle on TBC; Wrathgate/Battle-for-Undercity-era and DK-chain phases on WotLK) progresses correctly for the solo player, and phase state persists across Save & Exit.
- **Current state:** Vanilla has no phasing — the classic core has no phase machinery beyond the basic `phaseMask` column (verified: no PhaseMgr in `native/cmangos/src/game/`). TBC phasing is DB/conditions-driven (Sunwell Isle SSO state — register F2-1 says only 2 of its quests are scripted, so the *content* half of TBC phasing is F2-1's static-unlocked decision); WotLK phasing is quest/condition-driven in the core. This gap owns the *behavioral* pass: chains advance, phases swap on quest credit, and `character_queststatus` round-trips.
- **Work items:**
  1. Inventory per flavor (content triage doc, H1R2-5 package): which chains phase (DK start chain, Wrathgate chain, Sunwell unlocks), which conditions/tables drive each phase flip, and the expected phase sequence for a solo player.
  2. Device-test each inventoried chain end-to-end (quest credit → phase flip → next NPC visible) and file divergences to the triage list (wotlk-db issue links where upstream-acknowledged — register §3 confirms wotlk-db #240 BfU missing as the known case, handled in F2-2).
  3. Persistence: assert the phase-relevant tables (`character_queststatus`, wotlk condition/phase state) are in the Save & Exit round-trip set — extend the relay's `world-persistence <u> <char>` probe (`tools/world_console.py` surface, verified) to sample a phased zone.
  4. Bot-master sync is J1-4's sibling gap — keep this gap's scope player-scoped but record phase-affected bot interactions found during the pass in J1-4's checklist.
- **Upstream dependency:** `cmangos/tbc-db|-wotlk` (S4 pins) for chain data; `puzzled-pancake/mangos-wotlk` for phase behavior (S2 pin).
- **Verification:** Device (wotlk): DK chain from waking up to Light's Hope with phase flips at each gate — mid-chain Save & Exit, restore, resume: same phase, same quest state, no phasing flicker; (tbc): Sunwell Isle in the F2-1 baseline state loads with no phase errors; log error threshold met in both.
- **Effort:** M
- **Depends on:** J1-4, F2-1, F2-2, H1R2-5
- **Owner stage:** S3/S4

### J1-4 — Bot-master phase sync + phase persistence for bots

- **Goal:** Grouped bots always share the player's phase (DK chain included), bot summons never tear a bot out of phase, and bot phase state survives session restart.
- **Current state:** Playerbots group/summon logic predates phasing-heavy content; whether the wotlk playerbots build syncs `phaseMask` on group join/summon is unverified at the pin — the register row (H1-9/J1-4, `:52`) orders "bot-master phase sync (DK chain!), phase persistence across Save & Exit". The classic lane must remain untouched: any sync patch is `MANGOSBOT_TWO`-gated (vanilla/tbc phase sets are static or absent).
- **Work items:**
  1. At the S3 pin, test bot-master phase behavior on the DK chain (the register's marquee case): player progresses a phase flip with bots grouped — observe visibility/interaction.
  2. If bots desync: fork patch in `native/patches/playerbots/` — on group-join and on summon, copy the leader's phase mask; subscribe the bot to the leader's quest-credit-driven phase events where the upstream event loop allows; keep the patch minimal and `MANGOSBOT_TWO`-gated.
  3. Persistence: bot phase state derives from quest/condition tables per bot character — assert via the `world-persistence` probe that a phased bot's state matches after Save & Exit + restore (same probe harness as H1-9 item 3).
  4. Vanilla regression: the sync path must be a no-op on MANGOSBOT_ZERO — guard with a compile-time flag and a unit assertion in the patch's test.
- **Upstream dependency:** `puzzled-pancake/playerbots` (S3 pin) — patch authored by Pocket Realm against the pinned API.
- **Verification:** Device (wotlk): DK chain run with 4 grouped bots — every phase flip leaves all bots visible/interactable; mid-chain Save & Exit → restore → bots still in phase; one scripted de-sync fault injection (bot teleported while leader flips phase) recovers to synced state within one group tick; vanilla build compiles with the patch compiled out and its behavior byte-equivalent (golden soak).
- **Effort:** M
- **Depends on:** H1-9, H3-1 (§2.3)
- **Owner stage:** S3/S4

### H1-10 — Flying lifecycle (225/300, CWF, Dalaran no-fly, 310%)

- **Goal:** The full flying lifecycle is era-correct per flavor: riding 225/300, flying in 530 at 70 (TBC) and 571 only with Cold Weather Flying (lvl 77, 1000g, or the Tome at 80), Dalaran auto no-fly enforced, and 310% mounts (Onyxian Drake) computed correctly.
- **Current state:** The classic core has no flying (vanilla). All lifecycle data lives in the tbc/wotlk branches + DBs: riding/flying skill spells, `SkillLineAbility`/`SpellArea`-era gating for CWF, Dalaran no-fly enforcement (wotlk), and mount speed stacking. Register row H1-10/H5-9/P1 (`:53`) fixes the parameter set. Nothing in the repo references flying: `ClientFlavor.kt` has bot maps but no flying policy; the app-side rider/HUD concerns are §2.4's (H4-2 vehicle bars).
- **Work items:**
  1. At the S2/S4 pins, verify per branch: flying skill spell chain (225/300 ranks), TBC 530 gating (level 70 + Outland-only), WotLK 571 CWF gating (lvl 77 + 1000g trainer + Tome of the Cold Weather Flight item path at 80), Dalaran no-fly enforcement point (aura/zone check), 310% speed handling.
  2. Seed the purchase/trainer data from tbc-db/wotlk-db (trainer NPCs, spell/price rows) and add them to the seed floors (F2-9) — flying trainers and the Tome vendor are named floors.
  3. Gold-wall integration: CWF/epic flying are the register's named gold sinks (H1R2-7) — verify the costs and record them in the economy measurement.
  4. Regression matrix: add flying entries to H1R2-8 (dismount rules on landing/zone enter — the register names "flying dismount" explicitly).
  5. Bot-side flight behavior and the device load datapoint are H5-9 and P1 respectively — this gap stays player-scoped.
- **Upstream dependency:** `puzzled-pancake/mangos-tbc|-wotlk` + `cmangos/tbc-db|-wotlk` (S2/S4 pins, TBD).
- **Verification:** Device (tbc): level-70 char buys 300 riding in Outland, flies Hellfire→Netherstorm; flying refuses in old-world maps. Device (wotlk): pre-77 char cannot fly in 571 (clean error), buys CWF at 77 for 1000g, flies; Dalaran dismounts on entry; 310% mount shows correct speed vs a 280% control. All costs cross-checked against the H1R2-7 economy table.
- **Effort:** M
- **Depends on:** H5-9, P1, H1R2-7, H1R2-8, F2-9
- **Owner stage:** S3/S4/S6 (register; device legs land with S7-adjacent session tests)

### H5-9 — Bot flight on 530/571 (travel graph, purchases, no-fly compliance)

- **Goal:** Bots fly correctly on expansion maps: they acquire riding/flying at the era thresholds, use flight to reach 530/571 objectives, and never violate Dalaran no-fly or strand themselves flightless.
- **Current state:** The register (§2.3 H3R2-4, `:135`) states the bots' riding budget logic — `BudgetValues` — hardcodes basic 40 / epic 60 and zeroes flying, so expansion bots never buy flight; travel then degrades to ground paths that do not exist across Outland/Northrend geometry. `native/playerbots` carries the RPG/travel machinery (verified: `sql/world/ai_playerbot_rpg_races.sql` + strategy tree), but the flavor-threshold work is assigned to H3R2-4 (§2.3); this gap owns the *gameplay behavior on 530/571* — the soak-visible part (register row H1-10/H5-9/P1: "bot flight smoke on 530/571").
- **Work items:**
  1. Consume H3R2-4's flavor-threshold patch (flying budgets un-zeroed for MANGOSBOT_ONE/TWO, CWF purchase at 77) and verify its behavioral consequences: expansion bots above the threshold actually mount and fly between POIs.
  2. No-fly compliance: assert bots never fly in Dalaran (movement-mode probe on bot sessions during a Dalaran-concentrated soak — the LLM city banter makes Dalaran a high-traffic bot zone).
  3. Travel-graph sanity on 571: bots reaching Northrend POIs without flight (pre-77 population) must not loop-stuck at zone borders — assert zero stuck-loop (>60 s no progress) events in the soak telemetry.
  4. Flight-path vs mount usage: verify bots use taxi network for long hauls (F2-6 taxi verification feeds this) and mount-flight for short hops; record the observed split as the H5-9 baseline.
  5. Feed the load datapoint to P1 (same row): the flight soak doubles as the 530/571 grid-load measurement leg.
- **Upstream dependency:** `puzzled-pancake/playerbots` (S3 pin) — depends on the H3R2-4 (§2.3) patch landing in the fork.
- **Verification:** Soak (wotlk): 300 bots, 2 hours — ≥N bots observed airborne on 530/571 (session telemetry), zero no-fly violations in Dalaran, zero stuck-loops; (tbc): same for 530. Assertion script added to the soak suite (`tools/rp_harness/desktop_soak.py` extension).
- **Effort:** M
- **Depends on:** H1-10, H3R2-4 (§2.3), H3-3 (§2.3), F2-6, P1
- **Owner stage:** S3 (budget-patch half) / S6 (the 530/571 flight/no-fly soak legs; register stage S3/S4/S6)
- **Stage straddle (resolved S3/S6 split — the roadmap's S3/S6 straddle note):** bots cannot path on 530/571 without mmaps, which the runbook produces at S6. Resolution: wave S3 carries this gap's **S3 half only** — consuming H3R2-4's riding-budget patch so the budget logic is proven on classic maps — while the **530/571 flight/no-fly soak legs execute in wave S6**, beside the prep wave whose mmaps unblock them (roadmap wave S6 lists them explicitly; S3's exit deliberately excludes expansion-map evidence). DP12's desktop lane (`tools/win_prepare_data.py`) is only the pre-decided fallback if S6's own prep lands too late — no default datapoint depends on it. The datapoint source is S6's on-device mmaps (M2).

### P1 — Bot-flight device datapoint on 530/571 (R4 measurement leg)

- **Goal:** The bot-flight soak produces the measured device datapoint (mangosd RSS, tick p95, client frame time) for expansion-map bot populations, feeding the S6.4 go/no-go and per-flavor admission floors.
- **Current state:** The register splits the flying row's third sibling as the measurement leg (stage column "S3/S4/S6" — the S6 portion; register §1 P1-2/P1-3/P1-4 order the WotLK memory envelope work, and this gap is its §2.1-scoped contribution). Existing tooling: `tools/run_bot_pressure_benchmark.py` and `tools/battery_sampler.py` exist (verified in `tools/`), and `tools/capture_rp6.py` is the device capture lane — none of them has ever run a 530/571 bot-flight scenario (no TBC/WotLK world exists yet).
- **Work items:**
  1. Extend `tools/run_bot_pressure_benchmark.py` with a per-flavor scenario matrix: vanilla (existing baseline), tbc-530-flight, wotlk-571-flight — same bot count, same duration, grid-loading pattern dominated by flight.
  2. Instrument the soak to record: mangosd RSS (per §1 P1-2's methodology), world tick p50/p95, bot count online, and (client leg) frame-time via the `capture_rp6.py` capture — one archive per flavor per scenario.
  3. Feed results to the R4-owned admission ceilings (register §1: "per-flavor admission floors + memory ceilings") — publish the numbers in the stage evidence pack and the planner inputs (§2.5 P3-2/P1-5 own the storage side; this is the runtime side).
  4. Forbid full grid preload for wotlk in the boot path if measurements demand it (register §1 names the ~5 GB TC preload hazard) — coordinate with the S2 boot bring-up; record in the Decision Log.
- **Upstream dependency:** none — pure measurement on top of the S3/S4 builds.
- **Verification:** Archived RP6 evidence: wotlk 300-bot flight soak datapoint (RSS vs time, tick p95 vs vanilla baseline delta < the admission budget), tbc equivalent; numbers cited in the S6.4 go/no-go memo. This check is a device run — it cannot be executed on host.
- **Effort:** S
- **Depends on:** H5-9, S3/S4 builds; P1-2/P1-3/P1-4 (register §1, other area)
- **Owner stage:** S6 (register stage S3/S4/S6; the datapoint is wave S6's post-prep milestone feeding the DP12 memo)
- **Stage straddle (resolved S3/S6 split — the roadmap's S3/S6 straddle note):** the datapoint executes in **wave S6**, after that wave's mmaps/taxi prep exists (roadmap wave S6 lists it explicitly; wave S3's exit deliberately excludes expansion-map evidence, so no S3/S6 cycle remains). If DP12's desktop fallback (`tools/win_prepare_data.py`) is ever exercised because S6's own prep under-runs, its datapoints are labeled `desktop-mmaps` and never conflated with the on-device S6/M2 numbers — no default datapoint depends on the desktop lane.

### H1-11 — Barber shop + calendar (WotLK)

- **Goal:** WotLK's barber shop and calendar systems are present and functional: `gtBarberShopCost` DBC in the extraction floor, `calendar_*` tables in the wotlk characters lane (MariaDB and SQLite), and both features exercisable on-device.
- **Current state:** The classic core has neither (no barber/calendar sources in `native/cmangos/src/game/` — verified). The wotlk branch ships both; the register row (H1-11, `:54`) orders `calendar_*` tables into the wotlk SQLite lane and `gtBarberShopCost` into the extractor floor. Today's extraction floor is count-only — `DataPreparationStore.kt:420-421` (`MIN_DBC = 100`) — so a missing `gtBarberShopCost.dbc` would boot silently and barber costs would read garbage; the named-DBC manifest that catches this is H2-11 (§2.2), which this gap feeds and consumes.
- **Work items:**
  1. File `gtBarberShopCost.dbc` (and the wotlk calendar-era DBCs if separate) into the per-flavor required-DBC manifest source consumed by H2-11 (§2.2) and enforced at `PreparedDataStore` gating (flavor+build+family match, port plan S6.3) — the server-side consumption of a missing file must be a named launch failure, not a DBC-read crash.
  2. S4: confirm the wotlk characters base seeds the `calendar_*` table family; add to the wotlk table manifest (F2-4) with measured row floors (tables-present floors; content rows are user-generated).
  3. SQLite lane: the family must translate through `tools/sqlite_seed_translator.py` — extend the wotlk schema fixture (H5-4 item 2) to cover the calendar tables (they carry user indexes/keys the translator must rebuild correctly).
  4. Device bring-up: barber NPC interaction (cost charged per `gtBarberShopCost` band), calendar open (API responds, no client error) — both are wotlk-only; assert tbc/vanilla builds contain no calendar tables (schema diff negative test).
- **Upstream dependency:** `puzzled-pancake/mangos-wotlk` + `cmangos/wotlk-db` (S2/S4 pins, TBD); extractor DBC list comes from the flavor's `contrib` extractor at the S6 pin.
- **Verification:** Device (wotlk): restyle at a barber — cost debited per the DBC band, change persists after relog; calendar opens and accepts a personal event; Save & Exit round trip preserves both. CI: wotlk seed floor fails if `calendar_*` missing; prepared-data gate fails naming `gtBarberShopCost.dbc` when removed (fault injection).
- **Effort:** S
- **Depends on:** H2-11 (§2.2), F2-4, H5-4, F2-9
- **Owner stage:** S4/S6

### H1-12 — Jewelcrafting / inscription content floors

- **Goal:** TBC jewelcrafting and WotLK inscription are trainable end-to-end per flavor: trainers present, recipe/spell chains complete to 375/450, gem-cut and glyph items seeded, with row floors in the seed pipeline.
- **Current state:** Vanilla has 300-skill professions only; the classic seed floors know nothing of JC (TBC addition) or inscription (WotLK addition). The data lives in tbc-db/wotlk-db: `npc_trainer` rows, spell chains, item templates for gems/glyphs (glyph item floors are F2-5's dedicated gap). The register row (H1-12, `:55`) orders "trainers/recipes/gem cuts/glyphs floors per flavor". No per-flavor floor machinery exists yet — `tools/seed_realm_db.py` and `schemas/sqlite-seed-baseline.json` are classic-only today (verified: `databases` keyed `classic*` only).
- **Work items:**
  1. At the S4 pin, verify per flavor: JC trainers (tbc: Silvermoon/Exodar + Outland hubs) and inscription trainers (wotlk: major cities) exist with full spell chains; measure recipe/spell row counts and record as the floor inputs.
  2. Add profession floors to the per-flavor seed floor set (F2-9's mechanism): `npc_trainer` JC rows (tbc), inscription rows (wotlk), gem item templates (tbc/wotlk), plus the design-slot items (`item_template` gem-slot gear sanity is a content pass, not a floor — keep floors to counts).
  3. Cross-check glyph coverage handoff to F2-5 (per-class glyph floors) so inscription content and glyph items do not double-count.
  4. Bots-side note for §2.3 (H3R2-5 professions caps 300/375/450): this gap only guarantees the *world data* supports the caps; bot leveling behavior stays §2.3's.
- **Upstream dependency:** `cmangos/tbc-db` + `cmangos/wotlk-db` `master` (S4 pins, TBD).
- **Verification:** CI: seed floors green per flavor at the measured counts; negative: halve the JC trainer rows → floor fails naming the table. Device (wotlk): train inscription 1-75 at a starter trainer, craft a scroll; (tbc): train JC 1-50, cut a gem from an Ore-sourced item — both without "trainer has nothing" errors.
- **Effort:** S
- **Depends on:** F2-9, F2-5, H3R2-5 (§2.3)
- **Owner stage:** S4

### H1-13 — Per-flavor bot max level + automated cap test

- **Goal:** Bots level exactly to the flavor cap (70/80) and never beyond, with the cap flowing from `ClientFlavor.botMaxLevel` into conf and an automated post-soak cap assertion.
- **Current state:** The data half exists: `ClientFlavor.kt:44,82,110,138` carries `botMaxLevel` 60/70/80 and `botRandomMaps` per flavor, and the upstream conf surface is verified — `native/playerbots/playerbot/aiplayerbot.conf.dist.in.wotlk:53-54` (`AiPlayerbot.RandomBotMinLevel = 1` / `RandomBotMaxLevel = 80`). The wiring half is unproven: `ServerRuntimeFiles.kt:123-124` emits `aiplayerbot-<id>.conf` from `BotProfiles` config text (`it.playerbotConfig()`), and nothing shows the emitted `RandomBotMaxLevel` is flavor-routed rather than a fixed classic value; there is no cap test anywhere (grep verified: no `RandomBotMaxLevel` hit in android/tools/schemas).
- **Work items:**
  1. Trace and flavor-route the value: `BotProfiles.kt` → per-profile conf text → `ServerRuntimeFiles.kt:123`; the `RandomBotMaxLevel` line must be generated from `ClientFlavor.botMaxLevel` (or asserted equal to it) with a unit test per flavor.
  2. Golden-conf tests (G3-7 pattern): vanilla emitted conf byte-identical to today's; tbc shows `= 70`, wotlk `= 80`; the level cap in the core's own conf (`MaxPlayerLevel` — classic value 60 at `native/cmangos/src/mangosd/mangosd.conf.dist.in:826`; branch dists at pin) asserted consistent with `botMaxLevel` per flavor.
  3. Automated cap test: extend the soak harness (`tools/rp_harness/desktop_soak.py` or the world relay `world-bots` surface) to run after every bot soak: query max level across bot accounts via the relay persistence probe and assert `<= flavor cap`; wire into CI-representable form on the desktop lane.
  4. XP-rate inputs that make caps reachable in soak time are C2's sibling gap — coordinate the soak's XP policy.
- **Upstream dependency:** none (data is already in `ClientFlavor`); the branch conf dists are verified at the S3 pin.
- **Verification:** Unit tests per flavor on emitted conf; after the first wotlk bot soak: `SELECT MAX(level) FROM characters WHERE account IN (bot accounts)` == 80 (and the tbc soak returns 70); adversarial: hand-edit a profile to 85 → staging fail-loud (unknown/inconsistent key) per H1-17's guard.
- **Effort:** S
- **Depends on:** C2, H1-17, H3-1 (§2.3)
- **Owner stage:** S3

### C2 — XP rates and rested-XP knobs per flavor (register row `H1-13/C2`; **not** the §0 correction C2 "MPQ sets")

- **Goal:** XP economics are per-flavor data: blizzlike player XP (with rested-XP knobs surfaced), a bot XP policy that lets the population reach cap inside soak budgets, and every knob recorded in the conf matrix.
- **Current state:** The core's XP surface is verified in the classic dist — `Rate.XP.Kill/Quest/Explore` at `native/cmangos/src/mangosd/mangosd.conf.dist.in:1364-1366`, `StartPlayerLevel`/`StartPlayerMoney` at `:574/:827-828`; the tbc/wotlk dists carry the same family plus rested-XP keys (verify exact names at pin — do not guess). The register row (H1-13/C2, `:56`) orders "per-flavor bot max level + XP-rate conf; automated cap test; rested-XP knobs". **ID-collision disambiguation (read before cross-referencing "C2"):** the token `C2` names two unrelated register items. *This* section is the XP/caps gameplay gap, the second column of register §2.1 row `H1-13/C2` (`:56`). The *other* C2 is the register §0 headline correction "MPQ sets" (`FEATURE-PARITY-GAPS.md:14`), owned by §2.2/S5 and carried as roadmap §1 "C2 — MPQ sets" (which names the *exact-set MPQ semantics* wherever it says "C2 sets"). Conversely, roadmap wave-S3 text of the form "H1-13 + C2 (bot cap + XP policy)" means *this* gap. Any cross-reference to bare `C2` must be resolved by its subject (MPQ sets → roadmap §1 C2; XP/caps → this section), and the ledger entry below carries the same tag.
- **Work items:**
  1. Conf matrix entries (H1-17 data) per flavor: `Rate.XP.*` at 1.0 (blizzlike default), rested-XP rate keys (verify names in the branch dist at pin), `StartPlayerMoney` per flavor default (keep 0; money walls are H1R2-7's decision).
  2. Bot XP policy: playerbots carries its own XP handling for random bots (verify the key surface at the S3 pin — e.g. random-bot XP rate/level-band keys in the dist); set bots to a rate that reaches cap within the soak window without distorting the economy, and record the chosen value + measured level-time in the H1-13 cap test.
  3. Keep emitted vanilla conf byte-identical (G3-7): all new keys are per-flavor matrix rows, emitted only for tbc/wotlk.
  4. Document the player-visible rested-XP behavior (offline rest accrual) in the flavor docs — it is a gameplay-expectation item for a device-time realm (ties W2-1 §2.6 DST/device-clock policy for *event* schedules, not XP).
- **Upstream dependency:** none beyond the S2/S3 pins (key names verified there); no DB change.
- **Verification:** Golden-conf per flavor (vanilla byte-identical); measured bot level-time at the chosen rate recorded in the H1-13 artifact; negative: unknown XP key in the emitted conf → staging fails (H1-17 guard); player rested-XP accrues while the world is shut down (device: rest bar grows between sessions).
- **Effort:** S
- **Depends on:** H1-13, H1-17, H1R2-7
- **Owner stage:** S3

### H1-14 — Race/class creation matrix per flavor (adversarial both directions)

- **Goal:** Every legal race/class combination per flavor creates successfully (BE/draenei on TBC; DK on WotLK gated by H1-2), every illegal one is refused with the client's native error, and the matrix is tested adversarially in both directions.
- **Current state:** The classic core's create path gates only vanilla-legal combos (`native/cmangos/src/game/Entities/CharacterHandler.cpp:332` `HandleCharCreateOpcode`); the DBC-driven legality (`ChrClasses`/`ChrRaces`/`CharBaseInfo` per flavor) comes with each branch's client data at S5/S6. The app has no character-creation flow at all (register §1 H4R2-2), so today nothing exercises this matrix; `ClientFlavor.kt` carries no creation data. The register row (H1-14, `:57`) orders "adversarial both directions".
- **Work items:**
  1. Encode the expected matrix as data per flavor (a fixture table in the test source set, cross-checked against `CharBaseInfo.dbc` content at the S6 pin): race × class × expected accept/reject, including expansion-boundary negatives (wotlk-only classes offered to a TBC client; BE to a vanilla client).
  2. Server-side: verify the branch create path refuses illegal combos with era-correct errors; the relay surfaces the refusal reason (H1-2 item 4's relay extension) so the creation UI can render era-correct copy.
  3. Adversarial client-side: a tampered/unusual client must not be able to create an illegal combo — the server gate is authoritative; prove via protocol-level create attempts (rp_harness `session.py` crafting create packets per flavor's opcode surface).
  4. Integrate with the H4R2-2 creation flow (§2.4): the matrix fixture becomes its acceptance test data; DK entries additionally honor the H1-2 gate.
- **Upstream dependency:** `puzzled-pancake/mangos-tbc|-wotlk` create-path text (S2 pins); client DBCs arrive with the qualified clients (S5).
- **Verification:** androidTest matrix per flavor: every accept-combo creates a character row; every reject-combo refused with zero rows created; both directions across flavors (tbc client → wotlk realm refused at auth per S8, so the client-mismatch leg is covered by the S8 auth matrix); packet-crafted illegal create rejected server-side with clean error.
- **Effort:** M
- **Depends on:** H1-2, J2-1, F1-10; H4R2-2 (§2.4 area) — **edge scoped:** the matrix fixture (work items 1-3) *precedes* and is input data for H4R2-2's creation flow (roadmap CP-9 orders it this way), so the H1-14→H4R2-2 dependency applies only to work item 4 (flow integration); no cycle — H1-14 items 1-3 are blocked only by the S5/S6 qualified-client DBC data.
- **Owner stage:** S7

### H1-15 — Disable in-tree legacy PlayerBot in tbc/wotlk builds

- **Goal:** The legacy in-tree `PlayerBot` module never links into a tbc/wotlk build, and the build system proves it every time.
- **Current state:** The classic tree contains the legacy module at `native/cmangos/src/game/PlayerBot/` — 28 files (verified: AI/, Base/, config.h, config.h.in, playerbot.conf.dist.in, revision_sql.h), matching the register's "~29-32 files" (row H1-15/J3-11, `:58`). The exclusion already exists as a CMake contract in classic: `native/cmangos/CMakeLists.txt:343-352` forces `BUILD_DEPRECATED_PLAYERBOT` off when `BUILD_PLAYERBOTS` is on, and `src/CMakeLists.txt:25-46` FetchContent-populates the modern tree into `src/modules/PlayerBots` from the mirror `scripts/build_native.py:248` (`ensure_playerbots()`, called at `:376`) sets up. The tbc/wotlk forks must carry the same contract — verify at the S2 fork (upstream cmangos branches have the same structure per register C4).
- **Work items:**
  1. At the S2 fork of each branch, diff the fork's CMake gates against classic's `:343-352` block; if the branch lacks the mutual-exclusion (older upstream), port the classic block as one of the five fork commits (port plan S2.2).
  2. Extend `scripts/build_native.py`: after configure, assert the cmake cache has `BUILD_PLAYERBOTS=ON` **and** `BUILD_DEPRECATED_PLAYERBOT=OFF` for every flavor build (fail-loud naming the cache variable) — a flavor build with the legacy module is a provenance failure.
  3. Package gate: symbol check in the built server lib — when playerbots is enabled, no legacy-`PlayerBot` symbols may be present (script under `tools/`, run by `tests/test_win_lockfile.py`-style CI on the Windows lane and by the Android packaging gate).
  4. J3-11 (sibling) adds the runtime fail-loud for pathological builds; this gap owns the build-time side.
- **Upstream dependency:** `puzzled-pancake/mangos-tbc|-wotlk` branch `pocket-realm` (S2 forks, TBD) — CMake block verified/ported there.
- **Verification:** `python scripts/build_native.py --flavor tbc` (and wotlk) logs the forced-off status line and passes the cache assertion; fault injection: configure with `BUILD_DEPRECATED_PLAYERBOT=ON` → build aborts with the named message; symbol check green on all six flavor×lane artifacts.
- **Effort:** S
- **Depends on:** **S2 items (all of this section as scheduled in wave S2): none beyond the S2 fork existing** — the CMake mutual-exclusion gate and the `build_native.py` cache assert are fork/build-side work that precedes the §2.3 bots pin flip. **S3 build-matrix half only:** H3-1 (§2.3). The H1-15↔H3-1 pair is therefore not circular as executed: wave S2 runs H1-15+J3-11 (against the wave-0 fork registration) and wave S3's H3-1 pin flip consumes it — the edge reads H1-15(S2) → H3-1(S3), never both directions in one wave.
- **Owner stage:** S2/S3

### J3-11 — Fail-loud if both bot backends ever come on (runtime guard)

- **Goal:** Even a misconfigured build that links both bot backends fails at boot with a clean error instead of registering conflicting bot command surfaces.
- **Current state:** The register row (H1-15/J3-11, `:58`) pairs the build-time disable (H1-15) with "fail-loud if both backends on". Today the runtime relay `native/realm-runtime/src/world_runtime.cpp` has a single READY-gated command path (`issue_command`, used at `:284/:301`) and no bot-backend collision check; the Windows lane has its own build entry (`tools/build_win_realm_runtime.py`) and MSVC smoke (`tests/test_win_msvc_smoke.py` exists — verified) that a personal build could bypass.
- **Work items:**
  1. Boot-time guard in the realm-runtime overlay set (the o09 re-derivation lane, port plan S2.3): at startup, detect legacy `PlayerBot` command-table registration alongside the playerbots CommandServer registration (e.g., duplicate chat-command hooks on the `.bot` family); on collision, transition to `POCKET_SERVER_FAILED` with a distinct failure code and log line instead of proceeding.
  2. Expose the guard state through the existing runtime-status surface (relay state machine — `m_state.state()` checks at `:273` show the pattern) so the supervisor reports an actionable failure ("two bot backends linked; rebuild with BUILD_DEPRECATED_PLAYERBOT=OFF").
  3. Add the audit to the Windows lane: `tools/build_win_realm_runtime.py` refuses to produce a lockfile for a both-on configure (mirrors H1-15 item 2 on the second lane), and `tests/test_win_msvc_smoke.py` gains the negative fixture.
  4. Keep the guard branch-flavor-agnostic (classic included) — it protects all three lanes against future regressions.
- **Upstream dependency:** none — overlay-authored code in the forks (same text re-anchored per branch at S2).
- **Verification:** Negative fixture build (both defines on) → boot refuses with the named failure code, supervisor shows the actionable copy, no partial READY; correct builds (all six artifacts) boot normally; the supervisor failure path test mirrors `tests/test_world_runtime_honest_ready.py`'s style.
- **Effort:** S
- **Depends on:** H1-15
- **Owner stage:** S2/S3

### H1-16 — mod-playerbots (AC 3.3.5) recorded as documented fallback

- **Goal:** The AzerothCore mod-playerbots tree is evaluated and *recorded* — with license, port-cost estimate, and trigger conditions — as the WotLK bots fallback, so a late S3 failure has a pre-analyzed escape hatch instead of an improvisation.
- **Current state:** The primary tree is settled (register §0 C4: cmangos/playerbots master is multi-expansion; one pin serves all flavors — the S3 kill-switch premise is void). The register row (H1-16, `:59`) still orders the AC 3.3.5 fallback to be "recorded with trigger conditions" (P3). Nothing in the repo references it (grep verified: no mod-playerbots hit in schemas/ or docs/).
- **Work items:**
  1. Write the Decision Log entry (`docs/DECISIONS-PORT.md`): candidate repo (liyunfan123/mod-playerbots, AzerothCore 3.3.5, GPL), license compatibility note (GPL — compatible as a source obligation for the fork lane), and the port-cost caveat: it is an AC-module, so porting to cmangos-wotlk is a *strategy-layer* re-implementation, not a code move — estimate the effort explicitly and contrast with the "ship flavor without bots" kill-switch already allowed by the port plan (§8.1 risk 1).
  2. Trigger conditions (write them as testable statements): (a) cmangos/playerbots `MANGOSBOT_TWO` fails to build with the flavor core for >1 stage round after patches, or (b) missing DK/vehicle strategies block the S3 exit criteria with no upstream fix path — then the fallback review is mandatory at that gate.
  3. Explicitly record the recommendation: fallback is **not pinned** now (no submodule, no sources.json entry) to avoid provenance surface for a tree we do not ship.
- **Upstream dependency:** none (documentation-only; the candidate is evaluated read-only).
- **Verification:** Decision Log entry exists and is reviewed at the S3 exit gauntlet (R1 charter checks Decision Log coverage); no repo/build changes — `git status` clean except docs.
- **Effort:** S
- **Depends on:** H3-1 (§2.3)
- **Owner stage:** S3

### H1-17 — Per-flavor conf-key matrix from each core's .conf.dist

- **Goal:** Every emitted conf file per flavor references only keys that exist in that flavor's pinned `.conf.dist` surfaces, the full key matrix is build-time data, and unknown keys fail staging loudly.
- **Current state:** The conf surfaces in play: core `mangosd.conf.dist.in` + `realmd.conf.dist.in` + `mods.conf.dist.in` (`native/cmangos/src/mangosd/` — verified), anticheat `anticheat.conf.dist.in` (`src/game/Anticheat/module/` — verified, defaults `Enable = 1` at `:22`), core-bundled ahbot `src/game/AuctionHouseBot/ahbot.conf.dist.in`, legacy `src/game/PlayerBot/playerbot.conf.dist.in`; bots-side `aiplayerbot.conf.dist.in` (+`.tbc`/`.wotlk` — verified in `native/playerbots/playerbot/`) and `ahbot/ahbot.conf.dist.in` (verified: `AhBot.Enabled = 1` at `:18`). The app emits conf at `ServerRuntimeFiles.kt` (`realmd.conf` at `:55`, bot confs at `:123-132`, `mangosd.conf` at `:135`) — but only the bots lane has the port-plan S3.6 fail-loud contract today; the core/anticheat/ahbot surfaces have no unknown-key guard, and no per-flavor matrix data exists.
- **Work items:**
  1. Build the matrix as *generated* data, not hand-maintained: a tool (`tools/generate_conf_matrix.py`) parses each flavor's pinned `.conf.dist` files (from the submodules at S2/S3) and emits `schemas/conf-matrix-{vanilla,tbc,wotlk}.json` (key → dist file → default → our value or "unset"); `tools/check_repo.py` re-generates and diffs in CI.
  2. Extend the fail-loud staging check from the bots lane to every emitted conf: `ServerRuntimeFiles.kt` (and any other conf writer) validates each emitted key against the flavor's matrix; unknown key → staging abort naming key+file (port plan §3 bots item 6 generalized).
  3. Parse, don't duplicate: the dist files are `.in` templates — strip the `@...@` substitution tokens before parsing; test the parser against all three flavors' dists.
  4. Seed the offline-posture keys through the matrix (Channel.RestrictedLfg=0 per register row H1-17/G2-9; Anticheat.Enable=0 per G2-3) so the keep-off table (G2-9) and this matrix agree by construction.
  5. Vanilla golden (G3-7): the vanilla matrix must reproduce today's emitted conf byte-for-byte — the first CI run of the generator is that proof.
- **Upstream dependency:** the pinned submodule dist files themselves (S2/S3 pins); no upstream changes.
- **Verification:** CI: matrix generated for all flavors and diff-clean; unit tests — unknown key injected into a wotlk emission → staging fails naming it; vanilla conf byte-identical (golden); `Channel.RestrictedLfg=0` present in tbc/wotlk emissions (grep of staged bundle).
- **Effort:** M
- **Depends on:** G2-9, G2-3, H1-13, H3R2-1 (§2.3), G3-7 (§2.7)
- **Owner stage:** S2/S3

### G2-9 — Keep-off / offline-posture key audit table

- **Goal:** A single data table lists every conf key whose value must differ from upstream defaults because this is an offline single-user product, and staging enforces it per flavor.
- **Current state:** Upstream defaults are actively wrong for this product in several places — verified: `anticheat.conf.dist.in:22` ships `Enable = 1` (with kick/ban delays at `:32-51`); `ahbot.conf.dist.in:18-33` ships `AhBot.Enabled = 1` with `AlwaysAvailableMoney = 2000000`; classic `realmd.conf.dist.in` has no Warden keys (grep verified — consistent with register §0 C9 that classic has none), while wotlk's Warden surface is the register §1 G2-1/G2-2 hazard. The register row (H1-17/G2-9, `:60`) names `Channel.RestrictedLfg=0` for offline. Today nothing records or enforces these deviations.
- **Work items:**
  1. Create `schemas/keep-off-keys.json`: per flavor, entries `{key, file, value, reason, register_ref}` — seed with: `Anticheat.Enable=0` (kick/ban heuristics would ban the sole user — register G2-3), `Channel.RestrictedLfg=0`, Warden keys `=0`/absent per branch (G2-1/G2-2 nuance: default-off conf per J3 record), ahbot keys per the J1-2 decision, WG/battlefield flag from H1-6.
  2. `tools/check_flavor.py` (or the conf-matrix generator from H1-17) cross-validates: every keep-off entry must exist in that flavor's dist and the emitted-conf golden tests must show the keep-off value.
  3. The settings surface (S7) may turn keys on deliberately — the audit table is the *default* posture; a flipped key must be a recorded user action, so staging only enforces defaults, and the bundle capture shows the live values (diagnostics).
  4. Vanilla: assert the keep-off table has zero vanilla entries beyond what today's conf already emits (byte-identical golden, G3-7).
- **Upstream dependency:** none — data + validation over the pinned dists.
- **Verification:** CI: keep-off table validates against all three matrices; golden tests show `Anticheat.Enable=0` and `Channel.RestrictedLfg=0` in tbc/wotlk emissions; adversarial: set `Anticheat.Enable=1` in the emitted conf and boot → the G2-3 lazy-null patch keeps the world alive (defense in depth), and the diagnostics bundle exposes the deviation.
- **Effort:** S
- **Depends on:** H1-17, G2-3, J1-2, G2-1/G2-2 (register §1)
- **Owner stage:** S2/S3

### H1R2-1 — DB pin freshness gates (per-flavor expected revisions + zero-mismatch boot assert)

- **Goal:** Each flavor's seeded databases carry the exact revision string its pinned core requires (`required_z2820…` classic / `required_s2492…`-style tbc / numeric `14104`-style wotlk), and any mismatch fails at seed/boot — never silently at runtime.
- **Current state:** The classic gate is fully wired: `schemas/database-migrations.json` `expected_revisions` = `required_z2820_01_realmd_joindate_datetime` / `z2819 characters` / `z2778 logs` / `z2830 world` (verified via `json.load`), `tools/seed_realm_db.py` carries the version gate for the SQLite seed lane — `REQUIRED_VERSIONS` at `:292` (consumed at `:310`) with `verify_version_table` at `:300` — and the cores themselves abort on revision mismatch at boot (`revision_sql.h` per branch — the register row H1R2-1, `:61` fixes the tbc/wotlk formats). The per-flavor ledgers (`database-migrations-{flavor}.json`) and flavor-routed `REQUIRED_VERSIONS` do not exist yet — today both files are classic-shaped.
- **Work items:**
  1. At the S4 pins, read each branch's revision surface (tbc-db/wotlk-db `revision_sql.h`-equivalent and the DB's `Updates/` tail) and record the exact expected strings per DB into `schemas/database-migrations-{tbc,wotlk}.json` `expected_revisions` (ledger split per S1.7).
  2. Flavor-route the version gate in `tools/seed_realm_db.py` — `REQUIRED_VERSIONS` (`:292`, consumed at `:310`): key by flavor's DB-name set (`tbc*`/`wotlk*` per `ClientFlavor.DatabaseNames`, `ClientFlavor.kt:98-102,126-131`), with the classic keys untouched (regression guard: `tests/test_sqlite_seeding.py` stays green unchanged).
  3. `tools/stage_database_migrations.py` `expected_revisions` emission (`:213`) parameterized per flavor — same ledger, same digest chain, per-flavor revision strings.
  4. Zero-mismatch boot assert: the core's native revision check is the boot gate; add a *seed-side* fail-loud test (extend `tests/test_seed_realm_db_fail_loud.py`) that a wotlk DB seeded with a classic revision string (or stale tbc string) aborts with the `DB-REVISION`-family error naming both strings — the S4 end state's fail-loud behavior, proven per flavor.
  5. Cross-flavor adversarial (R2): a tbc-revision ledger applied to wotlk DBs must refuse before any statement runs (ledger header carries the flavor; P2-2 §2.5 owns the snapshot-side equivalent).
- **Upstream dependency:** `cmangos/tbc-db` + `cmangos/wotlk-db` `master` (S4 pins, TBD) — revision formats per register row H1R2-1.
- **Verification:** CI: three ledgers build with full digest chains; negative tests (wrong-flavor revision, stale revision) abort with named strings on both the MariaDB staging path and the SQLite translator path; on-device first boot per flavor reaches READY (proving the core accepted the seeded revision).
- **Effort:** M
- **Depends on:** F3-1 (§2.5), P2-1 (§2.5), F2-9, S4 pins
- **Owner stage:** S4

### H1R2-2 — World events / seasonal activation matrix + boss acceptance tests

- **Goal:** Each flavor ships a declared set of activated world events (Brewfest, Hallow's End, Midsummer, Lunar Festival, Harvest, …) with seasonal bosses spawning only in-window, verified by acceptance tests per event.
- **Current state:** Event data is `game_event`/`game_event_*` in the flavor DBs (register F2-9: 153 events tbc / 300 wotlk — measured counts to be re-verified at pin). The classic lane already boots events, but no per-flavor activation policy exists, and the register row (H1R2-2, `:62`) orders "world events/seasonal activation matrix per flavor; seasonal boss spawn acceptance tests". Device-time-as-realm-time (register W2-1, §2.6) makes absolute-date rows fragile on a device whose clock is the realm clock.
- **Work items:**
  1. At the S4 pin, dump each flavor's `game_event` rows (id, schedule type, dates) and classify: recurring (fine), absolute-date (audit — device clock is 2026; expired absolute windows must be normalized to recurring via a seed-aug SQL with provenance, or explicitly left expired), and always-off.
  2. Encode the activation matrix in the flavor data (`flavor.json` `events` block or `schemas/events-{flavor}.json`): event id → activate → rationale; the seed-aug SQL that normalizes schedules is generated from it (F2-7 lane).
  3. Seasonal boss acceptance tests (one per enabled event boss): spawn-in-window assertion — via GM `.event start <id>` (the console command path `world_console.py` can drive) — boss + loot present; out-of-window: absent; no orphan spawns after event end (the S4 numeric log-threshold applies).
  4. Coordinate DST/device-clock policy with W2-1 (§2.6 owns it): this gap provides the event-schedule test data it needs.
- **Upstream dependency:** `cmangos/tbc-db|-wotlk` `master` (S4 pins, TBD); no core change if normalization stays in aug SQL.
- **Verification:** CI: matrix validates against the pinned DB's `game_event` ids (no unknown ids); device: for ≥3 representative events per flavor (incl. one with a boss — Headless Horseman wotlk / the apothets-equivalent tbc), in-window spawn + loot + clean despawn proven; aug SQL provenance recorded in the ledger.
- **Effort:** M
- **Depends on:** F2-9, F2-7, W2-1 (§2.6)
- **Owner stage:** S4/S6

### H1R2-3 — TBC heroic keys/attunements seeded + bot-carried

- **Goal:** TBC heroic entry rules work: attunement/key quest chains are completable (The Master's Key confirmed present — item 24490 per register), bots either carry keys or are gate-exempt by recorded policy, and Shattered Halls heroic entry is the acceptance test.
- **Current state:** The data lives in tbc-db (register row H1R2-3, `:63` — "The Master's Key 24490 confirmed present" per the independent-verification round). Nothing in this repo references attunements (grep verified). The open design point is the bot side: a bot-filled heroic party will hit the entry check; forcing bots through quest chains is not viable, so the policy is seed-keys-onto-bot-leads or core-side bot exemption — the register's "bot-carried" phrasing plus the H3R2-2 strategy matrix decide it.
- **Work items:**
  1. S4 content audit: enumerate TBC heroic attunements/keys (The Master's Key chain, Shattered Halls/Shadow Labyrinth/Arcatraz keys, Karazhan attunement) — per-key: quest chain present in tbc-db? entry check enforced in the core branch (map/instance requirement rows)? Record in the triage doc (H1R2-5 package).
  2. Player path: device-test each chain to key drop/turn-in; heroic entry works with the key, refuses without (the named Shattered Halls heroic test).
  3. Bot path decision (record in `docs/DECISIONS-PORT.md`, tied to DP-decision discipline): recommended — bots are entry-exempt via the branch's bot/instance hooks if upstream provides one (verify at pin); else the LFG fill loop (H1-5) grants a temporary key-flag to bot leads at queue time via a `MANGOSBOT_ONE`-gated fork patch.
  4. Floors: key items + chain quest rows enter the tbc seed floors (F2-9 mechanism) so future DB bumps cannot silently drop an attunement.
- **Upstream dependency:** `cmangos/tbc-db` `master` (S4 pin, TBD); `puzzled-pancake/mangos-tbc` for the entry-check path (S2 pin).
- **Verification:** Device (tbc): with key → Shattered Halls heroic entered; without → refused with era-correct error; bot party enters via the recorded policy path; chain completable start-to-key on a fresh character; floors green in CI.
- **Effort:** M
- **Depends on:** H3R2-2 (§2.3), H1-5, F2-9, H1R2-5
- **Owner stage:** S4

### H1R2-4 — Transports: TransportAnimation/Rotation DBCs + type-42 GO data

- **Goal:** Every transport in a flavor (boats/zeppelins incl. the Menethil→Valiance Keep route) rides correctly: transport DBCs extracted, type-42 gameobject data seeded, and the full route ridden without desync.
- **Current state:** Vanilla transports work on the classic lane (the app's vanilla world ships them), but the register row (H1R2-4, `:64`) orders the wotlk-era transport machinery per flavor: `TransportAnimation.dbc`/`TransportRotation.dbc` extraction and `gameobject_template` type-42 (map object transport) rows. The extraction floor is count-only (`DataPreparationStore.kt:420` `MIN_DBC = 100`), so missing transport DBCs would boot silently; the named-DBC manifest (H2-11 §2.2) is the catching gate, fed by this gap.
- **Work items:**
  1. File the transport DBC names into the per-flavor required-DBC manifest source (wotlk: `TransportAnimation`, `TransportRotation`; tbc per its extractor output at the S6 pin — verify which the branch's `ad` extractor emits).
  2. S4: verify type-42 GO rows per flavor in tbc-db/wotlk-db (Menethil→Valiance Keep boat, Orgrimmar/Grommush-type zeppelins, Austin-equivalents) — add a named-transport floor (each route's GO entry present with expected map/taxi nodes).
  3. Device ride test: board at Menethil, ride to Valiance Keep — arrival on the 571 dock; assert no client rubber-banding (position probe via `tools/rp_harness/session.py`) and no server-side transport errors (log threshold).
  4. Desync mapping: a transport desync surfaces as a client ERROR (2.4.3 ERROR #0 / 3.3.5 #132 family — J2-7); ensure the supervisor's error harvest (H4-7 §2.4) catches the crash text so this test's failures are diagnosable, not just red.
- **Upstream dependency:** `puzzled-pancake/mangos-tbc|-wotlk` extractor + transport code (S2/S6 pins); `cmangos/tbc-db|-wotlk` GO rows (S4 pins).
- **Verification:** Device: full route ridden (both directions), arrival position within tolerance, zero transport errors in the world log; prepared-data gate fault injection: remove `TransportAnimation.dbc` → named refusal at launch; floors name each required transport GO.
- **Effort:** M
- **Depends on:** G1-8, H2-11 (§2.2), F2-4, J2-7, H4-7 (§2.4)
- **Owner stage:** S3/S4

### H1R2-5 — Raid/dungeon content floors + upstream issue triage list

- **Goal:** Every raid/dungeon a flavor ships has a declared content floor (boss NPCs present, loot non-empty, entry triggers) and a triage entry for every known-broken encounter — with the blocking-solo-playthrough list driving the port/cut decisions.
- **Current state:** The register rows are blunt: §1 G1-4/G1-5 ("ICC Gunship Battle 20% scripted, The Lich King 5%") and this row (H1R2-5/F2-7, `:65`) order the triage list + floors + "digest-pin Full_DB blobs". Today there is no triage doc, no per-flavor floors, and no wotlk-db/tbc-db pin at all (`.gitmodules` classic-only — verified). The classic lane shows the target shape: `Full_DB/ClassicDB_1_12_1_z2815.sql.gz` + `ACID/acid_classic.sql` seeded via `tools/stage_database_migrations.py` (`:134` selects ACID; register §0 C5 notes ACID is inlined as [ACID] commits in tbc-db/wotlk-db, so the selection rule changes per flavor), with digests recorded in `schemas/sqlite-seed-baseline.json` (`translation.escape_files` includes both classic blobs — verified).
- **Work items:**
  1. Create `docs/content-triage-{tbc,wotlk}.md`: per dungeon/raid row — encounter → upstream script status → blocking-solo? → decision (port TC / accept cut / nerf / record) with issue links (wotlk-db tracker; the Gunship/LK entries carry their %s from register §1).
  2. Define per-instance content floors: boss NPC count per instance, loot-template non-emptiness for each boss, entry/teleport GO/NPC presence — data-driven, measured at the S4 pin (register F2-9's principle: "measured, not guessed").
  3. Digest-pin each flavor's `Full_DB` blob (sha256 into `database-migrations-{flavor}.json` + the SQLite baseline `transcript_digests` pattern — classic precedent verified) so any upstream re-pull that changes content is a visible, gated event.
  4. Hand the encounter-decision feed to the S3 strategy owners (H3R2-2 §2.3) and the cut/teleport-bypass decisions to DP8/DP7 (register §4 items 7-8) — this gap produces the list they consume.
- **Upstream dependency:** `cmangos/tbc-db` + `cmangos/wotlk-db` `master` (S4 pins, TBD); TrinityCore 3.3.5 `master` read-only as port source for listed encounters (GPL-compatible).
- **Verification:** Floors run in CI per flavor (green at pin measurements; negative: drop one boss NPC row → floor fails naming instance+boss); triage docs exist with every mandatory-path encounter classified; Full_DB digests present in both ledgers and stable across re-staging.
- **Effort:** L
- **Depends on:** F2-7, G1-4/G1-5 (register §1), F2-9, H3R2-2 (§2.3)
- **Owner stage:** S4

### F2-7 — Content-floor pipeline execution (aug SQLs, floors, digest pins)

- **Goal:** The triage decisions from H1R2-5 are *executed in the pipeline*: provenance-carrying aug SQLs, per-flavor floor enforcement in the seed tools, and Full_DB digest pins in the SQLite baseline.
- **Current state:** The classic pipeline is the template: `tools/stage_database_migrations.py:73-160` `select_inputs()` builds the ledger from pinned sources (core `Updates` at `:83`, instances at `:87`, dbc `original_data`/`cmangos_fixes` at `:124-128`, scriptdev2 at `:132`, `acid_classic.sql` at `:134`, LLM SQLs at `:140-155`); `schemas/sqlite-seed-baseline.json` records per-DB statement/table/row counts + translation digests (verified); `schemas/seed-augment/` exists as the augmentation lane (directory verified). What does not exist: any per-flavor ledger, per-flavor floors, or aug SQLs for TBC/WotLK content.
- **Work items:**
  1. Extend `select_inputs()` per flavor behind the S1 flavor parameter: tbc/wotlk selection rules per register §0 C5 — pull `[ACID]` commits from the DB-repo history instead of an `acid_*.sql` file; keep instances/dbc/scriptdev2/LLM ordering; per-flavor core-`sql/updates` cutoff above the DB's installed revision (classic's integrated-revision logic at `:90-101` is the model).
  2. Add the aug SQL lane: `schemas/seed-augment/{tbc,wotlk}/*.sql` (training dummies F2-3, Isle baseline F2-1, event normalization H1R2-2, BfU F2-2 decision output) — each file carries a provenance header (source repo/issue/commit) and is digest-recorded in the ledger; `tools/check_repo.py` enforces the header.
  3. Implement the floors in `tools/seed_realm_db.py` + `tools/verify_seed_sqlite_floor.py`: per-flavor named-table minimums from H1R2-5's measured numbers; failure output names table+expected+actual (classic fail-loud style — `tests/test_seed_realm_db_fail_loud.py` extends).
  4. Update `schemas/sqlite-seed-baseline.json` per flavor after first measured seeds (classic's 414 entries / per-DB counts shape — verified) with `manifest_sha256` tripwires per flavor (S4.4 of the port plan).
- **Upstream dependency:** `cmangos/tbc-db|-wotlk` (S4 pins) incl. their `[ACID]` commit history; community-fix sources per aug file (provenance headers).
- **Verification:** CI: per-flavor ledgers build with digest chains; floors green at measured numbers and red on injected deficits (named-table failure); aug SQL provenance validated; SQLite seed green with per-flavor baselines (`tests/test_sqlite_seeding.py` extended).
- **Effort:** M
- **Depends on:** H1R2-5, F2-9, F2-3, F2-1, P2-5 (§2.5)
- **Owner stage:** S4

### H1R2-6 — Guild bank tables in per-flavor characters base + migration smoke

- **Goal:** TBC/WotLK guild banks work: the table family is in the flavor characters base, survives the SQLite lane, and deposit/withdraw/permissions are smoke-tested through a real guild.
- **Current state:** The classic characters schema has **no** guild bank (grep of `native/cmangos/sql/base/characters.sql` for `guild_bank` — zero hits, verified): guild banks arrived in TBC 2.3. The tables (`guild_bank`, `guild_bank_tab`, `guild_bank_item`, `guild_bank_right`, `guild_bank_event`-family) come with the tbc/wotlk characters bases at the S4 pin. The guild itself needs the charter flow (G2-10) and, for a solo player, bot signatures (J1-1/H3R2-9 §2.3 decision DP9). The register row (H1R2-6, `:66`) orders "guild bank tables in per-flavor characters base + migration smoke (deposit/withdraw/perms)".
- **Work items:**
  1. S4: assert the family is present in each flavor's characters base; add table-present floors to the per-flavor manifest (F2-4) — content rows start at zero.
  2. SQLite lane: cover the family in the wotlk/tbc schema fixtures (H5-4 item 2 lane) — guild_bank tables carry composite keys/indexes the translator must rebuild (its fail-loud on untracked ALTERs is the risk point).
  3. Migration smoke on device: create guild (G2-10 flow) → buy a tab (gold-wall interplay recorded in H1R2-7) → deposit items → set member rank permissions → a second character (or bot alt) withdraws within permissions and is refused outside them → `guild_bank_event` log rows written.
  4. Persistence: tab contents + permissions survive Save & Exit and restore round trip (characters DB invariant check added to the H1-9 harness family).
- **Upstream dependency:** `cmangos/tbc-db|-wotlk` characters bases (S4 pins, TBD); `puzzled-pancake/mangos-tbc|-wotlk` guild code (upstream, no change expected).
- **Verification:** Device per flavor: full deposit/withdraw/perms smoke green; permission violation refused with era-correct message; SQLite-lane world (or characters) snapshot restores with bank intact; floors green in CI.
- **Effort:** M
- **Depends on:** G2-10, H3R2-9 (§2.3), P2-4 (§2.5), F2-4
- **Owner stage:** S4

### H1R2-7 — Bot economy per flavor: gold walls vs quest-only income

- **Goal:** The player gold economy is measured and deliberately tuned: every named gold wall (epic riding 5000g TBC, CWF 1000g, dual-spec 1000g, guild tabs) is reachable by play at documented g/hour, with the AhBot decision (J1-2) as the recorded lever.
- **Current state:** Quest-only income in a single-player world cannot plausibly fund the walls (register row H1R2-7/J1-2, `:67`: "player gold walls (5000g/CWF/dual-spec) vs quest-only income — tune or opt-in AhBot tier; document g/hour"; decision point 10). The ahbot surface is verified in-repo: `native/playerbots/ahbot/ahbot.conf.dist.in:18-33` — `AhBot.Enabled = 1`, `PriceMultiplier = 0.35`, `AlwaysAvailableMoney = 2000000`, `SendMail = 1` — i.e., upstream defaults are the *opposite* of this product's posture; the core also bundles its own ahbot (`native/cmangos/src/game/AuctionHouseBot/ahbot.conf.dist.in` — verified). No economy measurement exists.
- **Work items:**
  1. Measure: script a representative playthrough (quest arcs per flavor via GM-verified path or bot-assisted run) and record g/hour at blizzlike rates into the Decision Log entry — the number the tuning hangs on.
  2. Decide DP10 with the measurement in hand; the lever set, in preference order: (a) keep blizzlike and *document* the grind honestly, (b) raise `Rate.Drop.Money`-family keys per flavor (conf matrix, H1-17), (c) opt-in AhBot tier — requires J1-2's enforcement flip plus a price-profile audit (`AhBot.PriceMultiplier`/`PricingStrategy` — `native/playerbots/ahbot/PricingStrategy.cpp` exists, verified) so bot-sold goods don't crater the economy.
  3. Verify the walls: at chosen knobs, a fresh character reaches epic riding (tbc) and CWF+dual-spec (wotlk) within the documented playtime — the H1-10 cost checks consume this.
  4. Record `StartPlayerMoney` decision (classic default 0 — `mangosd.conf.dist.in:574`; a starting-stipend variant is a cheap knob if (b) is chosen).
- **Upstream dependency:** none mandatory; (c) uses the pinned playerbots ahbot tree (S3 pin).
- **Verification:** Measured g/hour table in the Decision Log; device: fresh char playthrough reaches the flavor's walls within documented time; conf golden tests reflect chosen knobs; if (c): the J1-2 staging check proves the tier is opt-in (default off).
- **Effort:** M
- **Depends on:** J1-2, C2, H1-10, H1R2-6
- **Owner stage:** S3/S4

### J1-2 — AhBot keep-excluded decision recorded + build/staging enforcement

- **Goal:** AhBot is off by default per flavor with the decision recorded, the build flag surfaced, and a staging check that the emitted conf and DB state match the decision.
- **Current state:** Two ahbot implementations exist upstream: the core-bundled `AuctionHouseBot` (`native/cmangos/src/game/AuctionHouseBot/` with its own `ahbot.conf.dist.in` — verified) and the playerbots `ahbot/` tree (verified, defaults `Enabled = 1`). The register assigns the *decision record + build flag + staging check* to J1-2 (row H1-17/G2-9 sibling and §2.3 H3-12/J1-2, `:145`: "AhBot keep-excluded decision recorded per flavor (build flag + staging check)"). The classic lane's current posture must be preserved byte-identical (G3-7) — check what today's emitted conf does with ahbot keys before changing anything.
- **Work items:**
  1. Record the per-flavor decision in `docs/DECISIONS-PORT.md` (referencing H1R2-7's DP10): default `AhBot.Enabled = 0` + core `AuctionHouseBot` disabled for tbc/wotlk; vanilla exactly as today (if today's conf never emitted ahbot keys, keep it that way — verify, do not assume).
  2. Build flag: if either ahbot is compiled in the flavor's core/bots build, keep it compiled but key-disabled (no provenance churn); record the build flag name in the decision (the audit asks "which binary surface exists", not for removal).
  3. Staging check: extend the keep-off table (G2-9) with the ahbot entries and add the emitted-conf assertion; adversarial test — flipping `AhBot.Enabled=1` in a staged bundle is *detected* in the diagnostics capture (G2-9 verification) and, when the flavor's policy says off, the keep-off audit fails staging.
  4. If H1R2-7 lands on the opt-in tier: the settings surface (S7) flips the keys via the normal conf pipeline, and this gap's check enforces that the *default* remains off in every shipped profile.
- **Upstream dependency:** none (pinned trees only).
- **Verification:** Golden conf per flavor shows ahbot disabled (or unchanged-vanilla); negative fixture (enabled in emitted conf + policy off) fails staging naming the key; Decision Log entry reviewed at the S3/S4 gauntlets.
- **Effort:** S
- **Depends on:** H1R2-7, H1-17, G2-9, H3-12 (§2.3)
- **Owner stage:** S3

### H1R2-8 — Patch-port regression matrix (downrank, ratings, flying dismount, battle-rez)

- **Goal:** A scripted per-branch smoke proves the signature 2.4.3/3.3.5 mechanic changes actually behave: spell downranking per the 2.4 formula, ratings/resilience math, flying dismount rules, and battle-rez (Rebirth) in-combat rules.
- **Current state:** Nothing in the repo tests expansion mechanics — the test surface is vanilla-shaped (`tests/test_pocket_realm_nearby_interact.py`, `tests/test_differential_lane.py`, `tools/rp_harness/` probes — all verified, all vanilla-era). The register row (H1R2-8, `:68`) orders "runtime smoke per branch" for exactly these four mechanic families; they are the highest-risk port regressions because each depends on branch core math + DBC data + client expectation lining up.
- **Work items:**
  1. Build the suite as rp_harness suites (extend `tools/rp_harness/suites/` + `session.py`): per mechanic a scripted scenario with *expected numeric vectors* — downrank: cast a downranked spell, compare damage/coefficient to the 2.4 formula; ratings: equip a known resilience item, observe crit-damage/dot reduction vs the computed expectation; flying: Dalaran entry auto-dismount + landing dismount (H1-10 rules); battle-rez: in-combat Rebirth on wotlk (and its TBC-era in-combat restriction) — character state asserted via the relay.
  2. Encode expected vectors as data with provenance (wowhead/patch-notes citations in the fixture headers) so a future branch bump re-runs the same expectations.
  3. Wire the suite into the per-flavor stage exit gates (S3 for tbc/wotlk) and the differential lane (`tools/run_differential_parity.py` exists — verified) where a MariaDB/SQLite split matters (it shouldn't for these, but run it once to prove that).
  4. Cover the *bot* side of each mechanic where it applies (bots cast downranked spells — playerbots downrank logic; bots respect dismount) — failures there go to the §2.3 owners with the matrix entry as the repro.
- **Upstream dependency:** none beyond the pins (pure measurement).
- **Verification:** `python tools/run_differential_parity.py --flavor tbc|wotlk` (extended invocation) green with all four families' vectors matching; each family has one archived device run in the stage evidence pack; a deliberately-regressed build (e.g., pre-2.4 coefficient patched in) is caught by the downrank vector (negative fixture, run once).
- **Effort:** M
- **Depends on:** H1-3, H1-10, H3-4 (§2.3), F2-4
- **Owner stage:** S3

### G1-7 — Vehicle encounters (Flame Leviathan, Malygos P3, Oculus) + P0 carriage: ICC Gunship Battle (G1-4) / The Lich King (G1-5)

- **Goal:** Every mandatory WotLK encounter in the vehicle/TC-port decision family — Flame Leviathan, Malygos P3, Oculus, **ICC Gunship Battle (register §1 G1-4, ~20% scripted), and The Lich King (register §1 G1-5, ~5% scripted)** — is either bot-completable (server-side encounter scripts + bot strategies) or a documented, honest cut/nerf, with each per-encounter decision DP-recorded and the in-client experience consistent with it.
- **Current state:** The classic core has no vehicle system (no vehicle sources in `native/cmangos/src/game/` — verified); wotlk vehicles arrive with the branch. The register row (G1-7, `:69`) names the trio — Flame Leviathan, Malygos P3, Oculus — and orders "bot vehicle-driver AI or documented cut/nerf decision" (decision point 8, register §4). **The register §1 P0 pair G1-4/G1-5 (`FEATURE-PARITY-GAPS.md:31` — "ICC Gunship Battle 20% scripted, The Lich King 5% — mandatory-path WotLK progression blockers for 1+4 bots"; parity plan "port both from TrinityCore (GPL-2.0 compatible) … or recorded cut with teleport bypass") is owned here rather than in its own sections: it shares this gap's port-or-cut machinery, the ScriptDevAI lane, and the H1R2-5 triage doc, with the bot-strategy half consumed by §2.3 H5-2 (`bots-llm.md:127-140`, which already points the ICC/LK port boundary at §2.1).** The encounter-DBC prerequisites are G1-8 (sibling below); the strategy-layer inventory is H3R2-2 (§2.3).
- **Work items:**
  1. Record DP8 per encounter in `docs/DECISIONS-PORT.md`: recommendation — Oculus/Malygos P3: *nerf/cut* (script patch making the drake phase solo-tolerable or a skip trigger) before any bot-vehicle AI investment; Flame Leviathan: *port-or-cut* strictly by measured effort (a TC `FlameLeviathan`-family port into the fork's ScriptDevAI is the only source — GPL-compatible; budget it, and if it exceeds the stage budget, take the teleport-bypass cut with in-app copy).
  2. For the cut path: fork-patch the encounter scripts in the branch's scriptdev2/ScriptDevAI lane (the S4 pipeline already stages `sql/scriptdev2` — `tools/stage_database_migrations.py:132` verified): add a quest/GM bypass that teleports past the vehicle gate and records the cut in the instance state so the game honest-tracks it.
  3. For any kept encounter: bot strategy entries in the §2.3 matrix (H3R2-2) with the vehicle-seat handling from G1-8's `npc_spellclick_spells` data.
  4. Mark every decision's in-client surface: a cut encounter must not leave a dead-end quest or unreachable boss — the F2-7 aug lane adjusts quest chains where needed.
  5. **G1-4 (ICC Gunship Battle) — decide, then implement:** in the DP8-family Decision Log entry, record Gunship separately with the measured spike (a time-boxed TC 3.3.5 port of the gunship boarding/combat controller — cannon operation, jet-pack boarding waves, mage rocket-marking — into the fork's ScriptDevAI, GPL-2.0-compatible). *Port path:* land the encounter script + `creature_text`/spell/GO rows via the S4 lane, then H5-2 (§2.3) authors the bot-side cannon/boarding strategies this section's `npc_spellclick_spells`/vehicle-seat data enables. *Cut path:* the teleport-bypass (work item 2's mechanism) placed after the Airship entry, plus the H1R2-5 aug SQL so the Icecrown quest chain (`The Gunship Battle` step) auto-completes past the encounter — no dead-end quest.
  6. **G1-5 (The Lich King) — decide, then implement:** the register's ~5% upstream script state makes the defile/Val'kyr/shadow-trap phase controller the port target: port the TC 3.3.5 boss script's phase machine ( defile spread handling, Val'kyr shadowmist grab/focus, Fury of Frostmourne transition) into ScriptDevAI, with the *bot strategy* half (defile spread movement, Val'kyr focus-fire) specified in this section and implemented by H5-2 (§2.3). If the measured port exceeds the stage budget, the pre-approved fallback (register R7 in roadmap §5) is the recorded teleport-bypass cut directly before the Frozen Throne platform plus the H1R2-5 aug SQL marking the Lich-King quest steps complete — the plateau's post-kill quest state must stay coherent (F2-2's bridge pattern).
  7. Both P0 encounters get triage-doc rows (H1R2-5's `docs/content-triage-wotlk.md`) with the decision, the TC source commits used (if ported), and the verification evidence links — the roadmap CP-6 row cites this section as the carriage point.
- **Upstream dependency:** `puzzled-pancake/mangos-wotlk` + its scriptdev2 (S2/S4 pins, TBD); TrinityCore 3.3.5 `master` read-only for ported encounter scripts (Gunship/LK scripts incl. `boss_the_lich_king`/gunship combat controllers); `cmangos/wotlk-db` for encounter GO/spell/creature rows (S4 pin).
- **Verification:** Per recorded decision, per encounter: (kept) **device kill with 1+4 bots** — Flame Leviathan/Malygos/Oculus incl. their vehicle phases; Gunship Battle cleared to the exit; The Lich King killed through all phase transitions (defile no-hit asserts on the bot raid via the H5-2 3-run evidence bar) — no stuck seats, loot awarded, zero error spam; (cut) bypass reachable in-game, teleport lands past the gate, quest state consistent (successor quests obtainable), zero error spam. Every encounter's verdict row lands in `docs/content-triage-wotlk.md` with the decision and evidence links.
- **Effort:** XL (full port path incl. LK) / L (Gunship port + cuts) / M (all-cut path) — budget as L until the DP8-family spike decides per encounter
- **Depends on:** G1-8, H3R2-2 (§2.3), H5-2 (§2.3, bot strategies for Gunship/LK), F2-7, H1R2-5 (triage rows + aug SQL); carriage of register §1 G1-4/G1-5 (no separate sections)
- **Owner stage:** S3/S4

### G1-9 — TBC heroic crowd-control assist (bot CC or documented zerg-degrade)

- **Goal:** TBC heroic trash pulls are survivable with a bot party — via bot crowd-control assist (sheep/trap/fear/shackle on marked targets) — or the degrade is documented and tuned as a recorded decision.
- **Current state:** TBC heroic design assumes CC; a zerg-with-bots pull in e.g. Shattered Halls heroic is a wipe generator. The register row (G1-9, `:70`) orders "bot crowd-control assist (sheep/trap/fear) or zerg-degrade documented". The playerbots tree carries targeting/strategy machinery (verified: `native/playerbots/playerbot/strategy/` with values/actions incl. `GlyphValues`), but CC-assist behavior on `MANGOSBOT_ONE` is unverified at the pin — this gap's first job is that inventory.
- **Work items:**
  1. Inventory upstream: does the pinned playerbots carry CC marking/assist (CC-target selection from raid-target icons, polymorph/trap priority in pull logic)? File the answer into the H3R2-2 (§2.3) strategy matrix.
  2. If present: configure per-class CC assignment (mage/sheep, hunter/trap, warlock/fear, priest/shackle) in the per-flavor bot conf (H1-17 matrix keys) and validate in heroic pulls.
  3. If absent or weak: minimal fork patch (`native/patches/playerbots/`, `MANGOSBOT_ONE`-gated): respect a marked CC target (hold dps, re-sheep on break) reusing the existing target-icon value classes; keep it small — this is assist, not a CC AI framework.
  4. Fallback documentation: if investment is denied, record the zerg-degrade — measured wipe rates on representative heroic pulls + the recommended player-side tactics copy (S7 docs) — and optionally a trash-tuning knob (conf matrix) the user can raise, clearly labeled non-blizzlike.
- **Upstream dependency:** `puzzled-pancake/playerbots` (S3 pin); no core change.
- **Verification:** Device (tbc): Shattered Halls heroic, 5-bot party, three consecutive trash pulls — marked CC target controlled (CC uptime ≥80% until its kill turn), wipe rate recorded; the regression matrix (H1R2-8) gains a heroic-pull entry; fallback path (if chosen): documented wipe-rate table + tuning knob in the conf matrix.
- **Effort:** M
- **Depends on:** H3R2-2 (§2.3), H3-1 (§2.3), H1-17, H1R2-3
- **Owner stage:** S3

### G1-8 — Encounter DBC manifest: Vehicle/VehicleSeat + npc_spellclick_spells floors

- **Goal:** The data substrate for vehicle encounters reaches the server and is *gated*: Vehicle/VehicleSeat DBCs named in the per-flavor extraction manifest, and `npc_spellclick_spells` rows floored in the seed pipeline.
- **Current state:** The extraction floor is a bare count (`DataPreparationStore.kt:44-45,420-421` — `MIN_DBC = 100`, `MIN_MAP_TILES = 100`), so a flavor missing `Vehicle.dbc` boots and fails later at encounter time; the named-DBC manifest is H2-11 (§2.2, `:106`) — this gap is the §2.1-side *server consumption* half: what the server needs present and how the seed tools prove it. The register row (G1-8, `:71`) orders exactly this pairing ("Vehicle/VehicleSeat (WotLK) + npc_spellclick_spells rows in extractor/seed floors", stage S2/S6).
- **Work items:**
  1. Named-DBC inputs: file `Vehicle.dbc`, `VehicleSeat.dbc` (wotlk; verify tbc-era needs at pin) plus the H1-11 barber and H1-3 talent DBCs into the manifest data H2-11 owns — one list, both consumers (extractor floor at S6; `PreparedDataStore` consumer gate at launch).
  2. Seed floors: `npc_spellclick_spells` row minimums per flavor (wotlk ≥ measured count; tbc likely zero — floor = table present), plus the vehicle-adjacent tables the branch reads (`vehicle_template_accessory`-family — verify names at pin); measured numbers per F2-9 discipline.
  3. Consumer gate: the launch path must fail *named* — a missing DBC or dropped table yields a launch refusal naming the file/table (ties into the S6.3 `PreparedDataStore` flavor+build+family gate; the failure copy is shared with the S7 diagnostics lane).
  4. Packaging note (D4): per-flavor extractor .so names already decided — this gap only adds the manifest entries; no packaging change.
- **Upstream dependency:** `puzzled-pancake/mangos-wotlk` extractor output list (S6 pin); `cmangos/wotlk-db` (S4 pin).
- **Verification:** Fault injections (CI): remove `Vehicle.dbc` from a staged prep output → launch refuses naming it; drop `npc_spellclick_spells` from the wotlk seed → floor fails naming the table; positive: full wotlk seed + prep passes with the manifest green and a spellclick-driven NPC (Argent Tournament jousting-era or Oculus drake) spellclickable on device.
- **Effort:** M
- **Depends on:** H2-11 (§2.2), G1-7, F2-9, F2-4
- **Owner stage:** S2/S6

### G2-3 — Anticheat offline posture: `Anticheat.Enable = 0` + lazy null-module patch

- **Goal:** The anticheat module can never ban or kick the sole offline user (conf default 0) and can never terminate the world even if enabled (lazy WardenModuleMgr construction).
- **Current state:** Verified in-repo: `native/cmangos/src/game/Anticheat/module/anticheat.conf.dist.in` ships `Enable = 1` (`:22`) with kick/ban delay keys at `:32-51` — upstream's default posture is hostile to a single-user device. The register rows (G2-3, `:72`; §1 G2-1/G2-2, `:32`) add the wotlk-specific landmine: the module constructs `WardenModuleMgr` unconditionally when `Anticheat.Enable=1` and *throws* (mangosd terminates at first login) if `warden_modules` files are absent; `Warden.Enable` is parsed-but-dead. The keep-off table (G2-9) and conf matrix (H1-17) carry the conf side; this gap owns the posture and the crash-proofing fork patch.
- **Work items:**
  1. Keep-off entries: `Anticheat.Enable = 0` for tbc/wotlk (G2-9 table; the classic lane's emitted conf must be checked first — if vanilla emits anticheat keys today, keep vanilla byte-identical and record the difference as flavor-scoped).
  2. Fork patch (per branch carrying the module, at S2): make `WardenModuleMgr` construction lazy/null-tolerant — no module files → empty manager + warning log, no throw; `MANGOSBOT`/branch-gated so vanilla is untouched.
  3. Verify the module's kick/ban heuristics are fully inert at `Enable=0` (no periodic task registered) — read the branch's module init path at pin and assert via code review + soak.
  4. Coordinate with G2-1/G2-2 (register §1): the ~80 Blizzard `.bin`/`.key` Warden files are kept out of the managed copy by the **import-extraction deny lane** — G2-6 work item 5 (this area) and §2.2 H5R2-8's deny set, measured from the qualified clients at S5 — because `schemas/sources.json` is a build-source provenance registry and cannot remove files from a user-imported client tree; `sources.json` records the licensing verdict provenance-only (roadmap DP11 names this same enforcement point). This gap records the interlock: no `warden_modules` content on disk ⇒ the lazy null-module patch (item 2) is belt-and-suspenders, not the primary control.
- **Upstream dependency:** `puzzled-pancake/mangos-tbc|-wotlk` (S2 pins) — the lazy-null patch is Pocket Realm-authored.
- **Verification:** Adversarial device run (wotlk): `Anticheat.Enable=1` + no `warden_modules` → first login succeeds, world stays READY, warning logged (patch proven); default posture: emitted conf shows `= 0` (golden tests), no anticheat log output in a 2-hour soak; vanilla conf byte-identical.
- **Effort:** M
- **Depends on:** G2-9, H1-17, G2-1/G2-2 (register §1), G2-4
- **Owner stage:** S2

### G2-4 — Client OS/platform report under Wine vs Warden kick path

- **Goal:** Before anticheat/Warden is ever enabled on a flavor, the client's platform self-report under Wine is qualified and recorded — proving the kick path (if ever armed) would not false-positive on the managed runtime.
- **Current state:** The classic `realmd.conf.dist.in` has no Warden keys (grep verified — consistent with register §0 C9's "classic has none"); tbc/wotlk carry Warden surfaces whose behavior under Wine is exactly what register row G2-4 (`:73`) orders qualified. The device lane is Wine-based (`WineRuntimeStore.kt` is a pinned artifact in port plan §7), so the client's OS/platform/version report is a Wine artifact, not a Windows one — a mismatch is a kick vector.
- **Work items:**
  1. Instrument: during the S7 soak, capture the client's platform-related packets (rp_harness `protocol.py` extension; the auth/warden handshake surface per flavor — verify opcode names at the S2 pin, register C3 fixes `SMSG_AUTH_CHALLENGE = 0x1EC` across all branches).
  2. Record the observed report per flavor × lane (x86/ARM) in the flavor's qualification docs: what the client claims, what Wine provides, whether the wotlk core's warden path would accept it (static read of the branch's warden code — no modules needed for the read).
  3. Write the qualification verdict into the G2-3 decision: anticheat stays off in shipped profiles (G2-9), and this doc is the *only* path to ever flipping it (a future enable must re-run this qualification).
  4. Negative soak: with the wotlk core's Warden keys armed-but-module-less (post G2-3 patch), 2-hour client session — zero kicks, zero ERROR dialogs (the J2-7 soak harness is the instrument).
- **Upstream dependency:** `puzzled-pancake/mangos-wotlk` warden source read (S2 pin); no client changes permitted (provenance).
- **Verification:** Recorded platform-report table for tbc/wotlk × both lanes; armed-core negative soak green; the verdict doc exists and the G2-9 table cites it; archived `capture_rp6.py` evidence.
- **Effort:** S
- **Depends on:** G2-3, J2-7, G2-1/G2-2 (register §1)
- **Owner stage:** S7 (register) — capture legs run with the S7 soaks

### G2-5 — Client self-check on corrupt patch MPQs → hash-qualify full MPQ set at import

- **Goal:** Import hash-qualifies the *entire* MPQ set (not just the exe) so a corrupt patch MPQ — which hard-boot-fails the client with an opaque error — is rejected at import with a named file, and a boot-time MPQ failure maps to a re-import action.
- **Current state:** The identity surface exists per flavor: `ClientFlavor.kt:38-39` carries `requiredMpqs` (vanilla's 11-file set populated at `:76-79`, tbc/wotlk `emptySet()` until S5 qualification), and the register headline C2 (`:14`) fixes the real TBC/WotLK root sets (`common/expansion/patch/patch-2` + locale set; wotlk adds `common-2/lichking/patch-3`, no `patch-4`). The scanner side is pinned in the port plan §7 (`SafClientScanner.kt:170-174`, `ArchiveClientScanner.kt:128-132` — MPQ magic check + `EXPECTED_VERSION`/`REQUIRED_MPQS` constants). What is missing per register row G2-5 (`:74`): per-file content qualification (hash/size bands) and the boot-error mapping.
- **Work items:**
  1. At S5 qualification, hash every MPQ of the genuine client per flavor (SHA-256 + size) into the `ClientFlavor`/`flavor.json` data (extend the `mpq_set` block — `flavor.json` mpq_set already exists for vanilla with a note field, verified) — exact-set semantics stay §2.2 H2-2/H2-9's; this gap adds *content* trust for the accepted set.
  2. Import: compute per-MPQ hashes during staging (streamed, budget-aware — the files run to ~1.2 GB for `lichking.mpq` per plan S5.7) and record them in the managed-copy manifest; mismatch → `VAL-*` naming the file.
  3. Boot-failure mapping: the supervisor's failure-copy lane (`android/app/src/main/java/com/pocketrealm/supervisor/RuntimeFailureCopy.kt` exists — verified in the supervisor listing) gains a diagnostic pattern for the client's corrupt-MPQ boot error (harvest text per H2-7/H4-7 §2.4) that maps to "re-import this copy" with the VAL code.
  4. Budget: hashing adds wall time to import — measure and record the import-budget delta (R4) and stream hashes incrementally so staging stays resumable.
- **Upstream dependency:** the genuine qualified clients (S5 external inputs, plan §8.2); no upstream code.
- **Verification:** Negative fixtures per flavor: flip one byte in `patch-2.mpq` (tbc) / truncate `lichking.mpq` (wotlk) → import rejects naming the file before publish; positive: genuine client imports with hashes recorded; fault-injected corrupt MPQ on an already-imported copy boots → supervisor shows the re-import action; measured import-time delta recorded.
- **Effort:** M
- **Depends on:** H2-2/H2-9 (§2.2), C2 (register §0 headline), H2-7 (§2.2)
- **Owner stage:** S5

### G2-6 — Repair.exe / Launcher.exe excluded from the managed copy

- **Goal:** The managed copy never contains (and never launches) `Repair.exe`, `Launcher.exe`, **or the Blizzard Warden module/key file family** — Repair reverts a byte-patched exe to retail, Launcher reaches for the internet, and the ~80 Warden `.bin`/`.key` files are Blizzard IP with no redistribution verdict (register §1 G2-1/G2-2) — with every exclusion logged at import.
- **Current state:** The import policy lane exists: `ImportExtractionPolicy.kt` with `ROOT_FILES`/`ROOT_DIRECTORIES` (pinned in plan §7 at `:107-115`) — the S5.5 work item *adds* the flavor root layouts (TBC/WotLK roots include `BackgroundDownloader.exe`, `Launcher.exe`, `WowError.exe`, `Repair.exe` candidates per plan S5.5's derive list). The register row (G2-6, `:75`) makes the Repair/Launcher exclusion an explicit S5 requirement; the deny-list *execution* side (blocking a user-launched Repair) is H2R2-4/H5R2-8 (§2.2, `:99`) — this gap owns the managed-copy content side.
- **Work items:**
  1. Add `Repair.exe`, `Launcher.exe` (and `BackgroundDownloader.exe` per H2R2-4's deny list — coordinate so content-exclusion and execution-deny agree on the same names) to the per-flavor extraction deny set: excluded files are *not copied* into the managed copy and are listed in the import log/report.
  2. `tools/install_client_windows.ps1` and `tools/stage_o07_client.py` (both verified present) apply the same exclusion on the desktop/test lane so fixtures match device behavior.
  3. Diagnostics: the import report names every excluded file with its SHA-256 (provenance of what was dropped), and the managed-copy manifest records the exclusion list so audits can diff source vs managed trees.
  4. Vanilla regression: the vanilla policy set is untouched — the new names join per-flavor data (the vanilla lane never had them).
  5. **Warden module file family (register §1 G2-1/G2-2 / roadmap DP11 enforcement point):** the `warden_modules` tree — the ~80 Blizzard `.bin`/`.key` files — joins the per-flavor extraction deny set, with the exact file names/paths **measured from the qualified 8606/12340 clients at S5 qualification** (they arrive inside the imported client, so `schemas/sources.json` — a build-source provenance registry — cannot remove them; it records the licensing *verdict* provenance-only). Enforcement is this import lane: the files are not copied into the managed copy, each exclusion lands in the import journal naming file + SHA-256, and diagnostics/snapshot/bundle walks (§2.5 P2-9/P2-10) therefore never see them. Shared with §2.2 H5R2-8's deny lane (`out/fix-plan/client-data-runtime.md`, work item 1 — updated in lockstep with this item) so content-exclusion and execution-deny stay one list.
- **Upstream dependency:** none — app-side import policy over the qualified clients' known layouts.
- **Verification:** Import a client archive containing `Repair.exe` + `Launcher.exe` + the warden `.bin`/`.key` family (tbc/wotlk fixtures): managed copy lacks all three groups, import journal names each excluded file with its hash; adversarial diff of source vs managed tree finds no `*.bin`/`*.key` warden remnant; negative-inverse: all *required* MPQs/exes still present (exact-set check from §2.2 stays green); desktop-lane fixture import behaves identically; a snapshot restored from a post-import bundle contains no warden files (P2-2 §2.5 round trip).
- **Effort:** S
- **Depends on:** H2R2-4 (§2.2), S5 qualification data (measures the warden file family), F3-4 (§2.5 duplicate policy); G2-1/G2-2 (register §1 — the licensing verdict this lane enforces)
- **Owner stage:** S5

### G2-8 — Ticket / GM offline copy

- **Goal:** A player who submits a ticket gets an immediate, honest offline auto-reply, and submitted tickets are harvested into the diagnostics bundle.
- **Current state:** The classic core carries the ticket subsystem (`native/cmangos/src/game/GMTickets/` — verified directory) and the harness already has a reply concept: `tools/rp_harness/auto_reply.py` exists (verified) for probe sessions, but it is test-lane plumbing, not a product surface. The register row (G2-8/G2-10, `:76`) orders "Ticket/GM offline copy" for the new flavors; nothing today answers a ticket on any flavor.
- **Work items:**
  1. Productize the auto-reply: on ticket submit (per flavor, the subsystem is branch-carried), the server issues the canned offline response (mail or whisper per the flavor's ticket UX — verify the branch's behavior at pin) with honest copy: "this world is single-player; tickets are collected with your diagnostics".
  2. Harvest: the diagnostics bundle (S9/S10 lane) includes open/resolved ticket texts (redaction rules per P2-9/P2-10 §2.5 apply — no account PII beyond the local user's own).
  3. Conf: if the branch exposes ticket auto-reply conf keys, route through the matrix (H1-17); else implement in the o09 overlay/lifecycle lane (the `PocketRealmInteraction.cpp` overlay family — re-anchored per branch at S2).
  4. Keep vanilla behavior *better-or-equal*: if the vanilla lane currently leaves tickets unanswered, extending the same reply to vanilla is allowed but must re-run the vanilla golden conf/soak (G3-7) — default: flavor-scoped.
- **Upstream dependency:** none beyond the pins (overlay/conf work).
- **Verification:** Device per flavor: submit a ticket in-game → canned reply arrives within N seconds; ticket text appears in the diagnostics bundle; zero log errors; vanilla lane conf/soak unchanged (golden).
- **Effort:** S
- **Depends on:** H1-17, P2-9 (§2.5)
- **Owner stage:** S4/S7

### G2-10 — Guild charter registrar NPCs seeded + manual charter flow

- **Goal:** Guild formation is possible in-world: registrar NPCs exist per flavor with charter items, and the manual charter flow (buy → sign → turn in) works for a solo player via the recorded bot-signature decision.
- **Current state:** Registrar NPCs and charter item data live in the flavor DBs (tbc-db/wotlk-db). The **core's** charter machinery is present upstream in the pinned classic tree — `native/cmangos/src/game/Entities/PetitionsHandler.cpp` (petition create/sign/turn-in handlers; the signs check reads `CONFIG_UINT32_MIN_PETITION_SIGNS` at `PetitionsHandler.cpp:558`, seeded by `setConfigMinMax(..., "MinPetitionSigns", 9, 0, 9)` at `native/cmangos/src/game/World/World.cpp:589`; also `ItemPrototype.h`, `Player.cpp`, `GuildHandler.cpp` — grep-verified this round), the same machinery §2.3's J1-1/DP9 treatment cites — so what is genuinely missing is narrower: **no charter references anywhere in the app/tools test surface** (grep over `android/app/src/main/java` and `tools/` returns zero hits — verified), i.e. there is no guild-creation test lane, and the classic lane has no `guild_bank` schema (verified round 1). The register row (G2-8/G2-10, `:76`) orders "guild charter registrar NPCs seeded; manual charter flow (bots guild: J1-1)". The 9-signature problem is DP9 (register §4 item 9) with the sanctioned-bot-signature lane owned by H3R2-9/J1-1 (§2.3, `:140`) — this gap owns the *world data + player-side flow*.
- **Work items:**
  1. S4: verify per flavor the guild registrar NPCs (Stormwind/Orgrimmar/etc. guild masters), charter items, and turn-in NPCs are seeded; where a registrar is missing from the DB, add via the aug lane (F2-7) with provenance.
  2. Floors: registrar NPC rows + charter item templates enter the per-flavor seed floors (F2-9 mechanism) — one named floor per capital-city registrar.
  3. Player flow on device: buy charter (10s silver cost), name the guild, collect signatures per the DP9 decision (bot-signature lane if sanctioned — the §2.3 lane's command; else the honest out-of-scope copy ships and *guild formation is documented unavailable*), turn in, guild created.
  4. Hand-off: a created guild unlocks H1R2-6's bank smoke and H5-7's team-adjacent social surfaces; the DP9 outcome is recorded once and consumed by both.
- **Upstream dependency:** `cmangos/tbc-db|-wotlk` (S4 pins, TBD); `puzzled-pancake/playerbots` if the signature lane is sanctioned (S3 pin).
- **Verification:** Device per flavor: charter purchased at the registrar (NPC present via floor), flow completes to guild creation per the recorded decision; negative: missing registrar NPC → floor fails naming it (CI); H1R2-6 smoke runs immediately after this gap's device test on the same guild.
- **Effort:** M
- **Depends on:** H1R2-6, H3R2-9 (§2.3), F2-9, F2-7
- **Owner stage:** S4/S7

### J2-2 — Character enumeration `ORDER BY guid` → recency order per flavor

- **Goal:** The character-select list shows most-recently-played characters first on tbc/wotlk (retail-era expectation), via a per-flavor fork commit — vanilla behavior untouched.
- **Current state:** Verified in-repo: the char-enum query at `native/cmangos/src/game/Entities/CharacterHandler.cpp:319-329` ends `"WHERE characters.account = '%u' ORDER BY characters.guid"` (`:328`) — creation order, exactly the register's claim (row J2-2, `:77` orders the per-flavor fork commit). The characters schema's `logout_time` column must be confirmed in the tbc/wotlk bases at pin (classic's characters table carries it in cmangos — verify at the S4 pin before writing the ORDER BY).
- **Work items:**
  1. Fork commit per branch (tbc + wotlk, `pocket-realm`): change the enum query tail to `ORDER BY characters.logout_time DESC` (guard: keep `characters.guid` as a deterministic tiebreaker) — one of the five re-derived fork commits tracked in S2.2.
  2. Verify `logout_time` is maintained on logout in the branch's logout path (it is in classic; assert at pin, else the order degrades to guid — unacceptable, so the verification is mandatory).
  3. Vanilla regression: the classic query text is untouched; the leak-sweep-style proof is the vanilla golden soak plus a unit-level SQL-text assertion for the classic build.
  4. Index note: wotlk account sizes here are tiny (≤10 chars), so no index work is warranted — record that reasoning in the commit message.
- **Upstream dependency:** `puzzled-pancake/mangos-tbc|-wotlk` branch `pocket-realm` (S2 forks) — commit authored by Pocket Realm.
- **Verification:** Device (wotlk + tbc): create chars A then B, play A, logout → char select lists A first; relog and repeat with B → B first; deterministic tiebreaker visible with two never-played chars; vanilla device run unchanged (char order creation-based, golden soak).
- **Effort:** S
- **Depends on:** S2 fork bring-up
- **Owner stage:** S2

### J2-3 — Tutorial flags per-account (server) vs per-character (client expectation)

- **Goal:** WotLK's intro-tutorial state tracks per character as the 3.3.5 client expects, via a per-flavor fork fix — vanilla's per-account storage unchanged.
- **Current state:** Verified in-repo: `native/cmangos/sql/base/characters.sql:613-614` defines `character_tutorial` keyed by `account` (`account bigint ... COMMENT 'Account Identifier'`) with `tut0..tut7` columns — the register row (J2-3, `:78`) flags that the *client* in wotlk treats tutorial state per character, so a second character re-lives or skips the tutorial inconsistently if the server answers from the account row. The wotlk branch's actual storage/behavior must be verified at pin (it may already differ from classic — the fix is ordered as "per-flavor fork fix", implying upstream wotlk shares the account-keyed shape).
- **Work items:**
  1. At the S2 pin, verify the wotlk branch's tutorial read/write path (char-enum/login surface) and the client expectation (2 tutorial-intro state exchanges per character — observe in the packet capture lane).
  2. Fork fix if account-keyed: add character-scoped tutorial rows on wotlk (either a branch schema addition in `sql/base/characters.sql` + code, or map the account row to a per-char index if upstream's column layout allows) — prefer the smallest schema-compatible change; the ledger carries the schema delta per S4.
  3. If upstream wotlk is already per-character: record the verification in the Decision Log and close this gap as verified-no-change (the register ordered the fix conditionally).
  4. Vanilla: `character_tutorial` stays account-keyed and untouched (G3 golden).
- **Upstream dependency:** `puzzled-pancake/mangos-wotlk` (S2 pin) — commit authored only if the verification shows the account-keyed behavior.
- **Verification:** Device (wotlk): level a first character through the starting zone (tutorial advances); create a second character → tutorial intro plays per retail behavior for the new char; first char's state unaffected; SQLite + MariaDB lanes both round-trip the state; vanilla device run unchanged.
- **Effort:** S
- **Depends on:** S2/S4 schema handling
- **Owner stage:** S2

### J2-4 — WotLK titles: `knownTitles` column + handlers in the fork

- **Goal:** Title earning on WotLK persists correctly through the `knownTitles` bitmask and the character screen's title dropdown works — sparse at first (achievements grant most titles), which is expected and documented.
- **Current state:** The classic characters schema has no `knownTitles` (grep of `native/cmangos/sql/base/characters.sql` — zero hits, verified); the column and its handlers arrive with the wotlk branch (register row J2-4, `:79`: "`knownTitles` column + handlers in fork; sparse dropdown until achievements"). The fork-side work is verification + any handler gap; the sparse-dropdown *expectation* is a docs/copy item so the behavior is not mistaken for a bug.
- **Work items:**
  1. At the S2/S4 pins: verify the wotlk characters base seeds `knownTitles` (two `uint32` mask columns upstream — verify shape) and the branch's title earn/apply handlers (quest-reward titles, achievement titles) are intact in the fork.
  2. Fork work only if the Pocket Realm overlays interact with the characters-table surface (the relay's character probe at `world_runtime.cpp:366-369` SELECTs explicit columns — add `knownTitles` awareness only if a consumer needs it; default: leave the probe as-is).
  3. Persistence proof: a title earned mid-session survives Save & Exit + restore (mask round trip in both DB lanes).
  4. Docs: the flavor page notes the dropdown fills via achievements (H1-4) and quests; sparse is correct.
- **Upstream dependency:** `puzzled-pancake/mangos-wotlk` + `cmangos/wotlk-db` (S2/S4 pins, TBD).
- **Verification:** Device (wotlk): earn a quest title → equip it → visible in char list; Save & Exit → restore → title still equipped and listed; `knownTitles` mask bytes match pre/post restore (DB diff); zero title-handler errors in the soak log.
- **Effort:** S
- **Depends on:** H1-4, F1-10 (only if the character probe changes)
- **Owner stage:** S2/S4

### J2-5 — Currency tab (bag slots 118-150): keep currency items through the pipeline

- **Goal:** WotLK currency items (emblems, marks — the client's currency tab, bag slots 118-150) survive the seed/pipeline floors and are proven in-game: a boss kill credits an emblem that persists across relog.
- **Current state:** Vanilla has no currency-tab concept; the slot range and currency flags are wotlk client/server conventions (register row J2-5, `:80`: "keep currency-flagged items through era-lint/pipeline; emblem smoke test"). Two pipeline hazards exist: the era lint (§2.3 H3-8, externalized per-flavor data files) could flag currency items as anachronistic in the wrong flavor, and the seed floors could miss the item rows entirely. Neither hazard is realized today (no wotlk data exists yet) — this gap is a *keep*-requirement plus smoke.
- **Work items:**
  1. S4 floors: count currency-flagged item rows per flavor (emblem of heroism/triumph family, battleground marks) into the wotlk table manifest/floors (F2-9 mechanism) — named item-class floor.
  2. Era-lint coordination (§2.3 H3-8): add currency item names to the wotlk *allow* list and tbc/vanilla *ban* lists in the lint data files, with corpus tests proving no false bans on wotlk and correct bans elsewhere.
  3. Emblem smoke (device): kill a Naxx-25 boss (or the 10-player equivalent) → emblem credited → visible in the currency tab → relog → count persists (`character_inventory` slot-range probe 118-150 via the relay persistence surface).
  4. Save & Exit: currency rows are plain inventory rows — include the slot range in the persistence round-trip assertion set (H1-9 harness family).
- **Upstream dependency:** `cmangos/wotlk-db` (S4 pin); no core change expected.
- **Verification:** CI: floors green (named currency-item floor); era-lint corpus tests green; device emblem smoke green incl. relog persistence and Save & Exit round trip on both DB lanes.
- **Effort:** S
- **Depends on:** F2-4, F2-9, H3-8 (§2.3), H1-9
- **Owner stage:** S3

### J2-6 — /who lists all bots → product decision (tag + skip or config)

- **Goal:** /who reflects a sane single-player world: the player sees either only themselves (bots filtered) or a documented bot-inclusive result — decided once, recorded, and enforced.
- **Current state:** Bot characters are ordinary rows in `characters` (the relay counts them as such — `world_runtime.cpp:1007-1024` aggregates `randomBotAccounts`), so /who enumeration returns the full bot population; the register row (J2-6, `:81`) orders "product decision (tag+skip or config)". Playerbots may already carry a who-filter upstream (verify at the S3 pin — the classic dist has bot-visibility keys to inventory); if so this is a config default, not a fork patch.
- **Work items:**
  1. Verify upstream: inventory the pinned playerbots' visibility/who surface at the S3 pin; record whether a supported filter exists (and its key name) in the conf matrix (H1-17).
  2. Product decision (small, record in the Decision Log): recommended — bots hidden from /who by default on all flavors (it is a population-detail leak that breaks the single-player illusion), with a settings toggle if the upstream key supports it; vanilla posture preserved byte-identical.
  3. Implement per the decision: conf default via the keep-off/matrix table (G2-9) if a key exists; else a minimal `MANGOSBOT_*`-gated fork patch filtering bot accounts from the who-reply in the branch's who handler.
  4. LLM-lane coordination: chatter/immersion surfaces (§2.3) do not depend on /who — note it so nobody "fixes" visibility there.
- **Upstream dependency:** `puzzled-pancake/playerbots` (S3 pin) — key inventory; fork patch only if upstream lacks the filter.
- **Verification:** Device: /who returns the expected filtered set per the decision (or the documented unfiltered behavior); toggle (if shipped) flips it live; vanilla /who behavior unchanged; conf golden tests carry the default.
- **Effort:** S
- **Depends on:** H3-1 (§2.3), H1-17, G2-9
- **Owner stage:** S3

### J2-7 — Packet-conformance error surfaces (ERROR #0 dialogs, #132 crashes) → conformance soak + supervisor failure mapping

- **Goal:** Struct-mismatch packet bugs surface as *session failures with harvested diagnostics* — the supervisor treats client ERROR dialogs as session failure — and each flavor passes a long conformance soak with zero such dialogs.
- **Current state:** The register row (J2-7, `:82`) fixes the failure modes: 2.4.3 shows `ERROR #0` dialogs on struct mismatch; 3.3.5 crashes with `#132`. The supervision surface exists: `android/app/src/main/java/com/pocketrealm/supervisor/` carries `DurableRuntimeSupervisor.kt`, `RuntimeFailureCopy.kt`, `AtomicSupervisorJournal.kt` (verified listing), and the client-side artifact harvest is §2.4 H2-7/H4-7 (`:103`, `:157` — `Errors/*.txt`+`dmp`). Nothing today maps a client ERROR dialog to a *session failure* — the supervisor watches the server process, not client-side error state.
- **Work items:**
  1. Define the failure signal: the client's `Errors/` directory (path map per flavor — H4-8 §2.4 owns the map) gaining a crash/error artifact mid-session, or the client process exiting non-zero after a dialog, is elevated by the supervisor into a session-failure journal entry (reuse `AtomicSupervisorJournal.kt`'s entry pattern; flavor is an optional field per G3-4 §2.7).
  2. Copy: the user-facing failure copy names what was harvested ("the client hit an internal error; logs were saved") and offers recovery (relaunch session) — `RuntimeFailureCopy.kt` gains the entries.
  3. Conformance soak: extend `tools/rp_harness/desktop_soak.py` + `session.py` for per-flavor long runs (target: the S10 8-hour soak per plan §5 S10.2) that assert zero ERROR artifacts and zero #132-class exits, with the harvest probe (H2-7) as the detector.
  4. Fault injection: the desktop harness (`RpWowClient.kt` is the pinned SRP6 client — plan §7) or a scripted malformed-packet injection proves the pipeline end-to-end: dialog/artifact → journal entry → failure copy → clean recovery.
  5. Feeds: J2-7 is the register's S10 item — the soak hard-gates S10 qualification per flavor (register §2.7 guard family applies to the journal schema).
- **Upstream dependency:** none — supervision/harness work over the pinned clients.
- **Verification:** Fault-injection test green (artifact → session failure → copy → recovery, journal carries flavor); 8-hour conformance soak per flavor with zero ERROR dialogs archived in the S10 evidence pack; `capture_rp6.py` capture includes the error-harvest probe.
- **Effort:** M
- **Depends on:** H2-7 (§2.2), H4-7 (§2.4), H4-8 (§2.4), P2-12 (§2.5), G3-4 (§2.7)
- **Owner stage:** S10 (register; implementation lands S3-S9, gate at S10)

### F1-10 — Relay expansion op (`account set addon`) + ABI version-bump discipline

- **Goal:** The world relay can set an account's expansion flag (`account set addon <n>`) with the same READY-gated contract as its siblings, the Kotlin/JNI surface follows, and the relay ABI is version-bumped whenever its surface changes so mismatched app↔runtime pairs fail closed.
- **Current state:** Verified in-repo: `native/realm-runtime/src/world_runtime.cpp` implements the relay ops with a uniform pattern — `create_account` (`:271-290`, `issue_command("account create …")` at `:283`), `set_account_gmlevel` (`:292-304`, `"account set gmlevel"` at `:300`), `account_info` (`:320-339`, guarded `SELECT id,gmlevel FROM account` at `:338-339`), JNI entry points at `:1398-1421` — and the contract test `tests/test_world_runtime_relay_contract.py` exists (verified listing). There is **no** expansion op and **no** ABI discipline: the plan (S8.1) orders both. The port plan §7 pins the lockfile family `schemas/realm-runtime-lockfile*` (files verified present, incl. `-sqlite` and `-win` variants) as the version carrier.
- **Work items:**
  1. Add `set_account_expansion(username, expansion, timeout_ms)` to `world_runtime.cpp` beside `set_account_gmlevel` (`:292`): same READY-state guard (`:294` pattern), same token validation, `issue_command("account set addon " + username + " " + std::to_string(expansion))`; validate `expansion ∈ {0,1,2}`; verify the exact command syntax against the branch's account-command table at the S2 pin (the plan names `account set addon <n>`).
  2. JNI + Kotlin: new native + `WorldNative` method; the account-creation service calls it after `create_account` with the flavor's expansion (1/2; vanilla stays 0 and may skip the call) — feeding J2-1's precondition.
  3. Extend `account_info` (`:338`) to also return `expansion` so tests and the app can assert the flag (schema-safe: the column exists in all three realmd schemas).
  4. ABI discipline: introduce an explicit relay-ABI integer (header constant + lockfile field in `realm-runtime-lockfile*.json`); the app refuses to load a runtime whose ABI is older than expected (fail-closed, actionable update copy); every relay-surface change bumps it in the same commit — CI check ties the header to the lockfile.
  5. Extend `tests/test_world_runtime_relay_contract.py` with the new op incl. wrong-state, invalid-argument, and already-set cases.
- **Upstream dependency:** `puzzled-pancake/mangos-tbc|-wotlk` (S2 pin) only to verify the `account set addon` command text; the op is Pocket Realm code.
- **Verification:** Extended contract test green (host); device: create account on wotlk realm → `world-account-status` / DB probe shows `expansion = 2`; adversarial: app built against ABI N + runtime at ABI N-1 refuses to boot with the update-directed copy (test fixture); vanilla lane: expansion op unused, ABI bump still enforced (vanilla runtime rebuilt in lockstep).
- **Effort:** M
- **Depends on:** J2-1, H1-2, S8 auth work
- **Owner stage:** S2/S8

### F2-4 — Per-flavor table manifests for runtime-read DB tables + translator support

- **Goal:** Every world/characters table a flavor's core *reads at runtime* — wotlk's `spell_template` (~50,889 rows), `quest_poi`/`quest_poi_points` (~56,432 rows) named by the register — is declared in a per-flavor table manifest with floors, translated correctly by the SQLite lane, and proven by a boot-plus-gameplay test.
- **Current state:** The register row (F2-4, `:84`) makes this P1: wotlk-db *reads* DB spell data and quest POIs (cross-referencing P2-4 §2.5, `:189` — translator baselines). The repo evidence shows the machinery is classic-shaped: `tools/stage_database_migrations.py:117` already special-cases `spell_template` ("original_data/Spell.sql recreates spell_template with the coefficient" — the classic pre-dawn pattern), `tools/sqlite_seed_translator.py` translates generic DDL with fail-loud positional-ALTER rebuilds (the DDL contract docstring at `:25-42`; the positional-ALTER rebuild counters at `:90-92` and `_emit_positional_rebuild` at `:509`), and `schemas/sqlite-seed-baseline.json` holds classic-only counts (verified: `databases.classic*` keys only). The per-flavor version-verification constants (the `REQUIRED_VERSIONS` family at `tools/seed_realm_db.py:292`) exist only in classic shape today — the wotlk/tbc entries are **created by this gap's work**, not present. No wotlk/tbc manifests exist.
- **Work items:**
  1. Create `schemas/table-manifests/{tbc,wotlk}.json`: every runtime-read table per flavor (wotlk: `spell_template`, `quest_poi`, `quest_poi_points`, `achievement_criteria`, vehicle/currency families from G1-8/J2-5; tbc: its equivalent set, verified at pin) with role (core-read / content / user-data) and the floor source.
  2. Measure at the S4 pin and record: `spell_template` and `quest_poi*` row counts on the *imported* wotlk-db (the register's uncertain-count discipline from §3 — settle numbers by import, not tracker claims).
  3. Translator support: seed the wotlk world DB through `tools/sqlite_seed_translator.py` in CI (the 30 MB Full_DB-scale baseline is P2-4 §2.5's deliverable; this gap owns the *table coverage* assertion — the manifest tables present with floors met in the SQLite output, not just MariaDB).
  4. Boot + gameplay proof: wotlk world boots reading the translated DB with zero missing-table errors; quest POIs demonstrably work in-game (quest map markers / POI gossip on a WotLK quest with poi data).
  5. `quest_poi` indexes: if the DB ships index SQL for POI lookups, stage it (the `ai_playerbot_indexes.sql` precedent — `native/playerbots/sql/world/` verified) and record index set per flavor.
- **Upstream dependency:** `cmangos/wotlk-db` + `cmangos/tbc-db` `master` (S4 pins, TBD); P2-4 (§2.5) for translator baselines.
- **Verification:** CI: manifests validate against the imported DBs (every manifest table exists, floors met, both lanes); negative: drop `quest_poi` from the seed → floor + boot both fail naming it; device: WotLK quest with POI data shows correct markers from the SQLite lane world.
- **Effort:** L
- **Depends on:** P2-4 (§2.5), F2-9, H1R2-1, H5-4
- **Owner stage:** S4

### F2-6 — TBC taxi network verification (fly every Outland FP)

- **Goal:** The TBC flight-path network is *verified by riding it*: every Outland FP route is flown end-to-end with arrival assertions, and upstream taxi defects found are ported/fixed with provenance.
- **Current state:** The register row (F2-6, `:85`) states the risk plainly: the taxi rework landed in wotlk-db, the tbc-db tracker is disabled, so TBC taxi data is effectively unverified upstream. The classic lane's taxi data works (vanilla ships taxis), but nothing in the repo tests taxi geometry. Taxi routes are pure DB data (`taxi_nodes`/`taxi_path`/`taxi_path_node`) plus client DBCs — a mismatch shows as the client never arriving or clipping through terrain (the latter is detectable via the position probe).
- **Work items:**
  1. At the S4 tbc-db pin, enumerate all map-530 FPs and their paths; build a scripted rider: a GM-flagged character learns all FP connections (core taxi command path or direct `character_...` taxi-flag seeding) and flies each route via the client (rp_harness `session.py` drives the taxi packet exchange).
  2. Assertions per route: arrival event within an expected time band; final position within tolerance of the destination node; no mid-route stall (>120 s without progress); no server taxi errors (log threshold).
  3. Defect handling: for each failing route, port the wotlk-db taxi fix (same node/path rows reworked upstream) as a provenance-carrying tbc-db aug commit (the F2-7 aug lane, `[taxi]`-tagged) rather than hand-editing coordinates.
  4. Re-run the full rider after any tbc-db bump (the digest pins from H1R2-5/F2-7 make bumps visible) — the rider is a stage-gate test, not a one-off.
- **Upstream dependency:** `cmangos/tbc-db` `master` (S4 pin, TBD); the wotlk-db taxi rework commits as the fix source.
- **Verification:** Rider green: 100% of map-530 routes arrive in-band; defect fixes (if any) recorded as aug commits with digests; rider re-run green after the final tbc-db pin bump before RA exit.
- **Effort:** M
- **Depends on:** H1R2-4, H5-9, F2-7
- **Owner stage:** S4

### F2-2 — Battle for the Undercity questline absent (wotlk-db #240) → cut-or-fix decision

- **Goal:** The Battle for the Undercity chain reaches a *consistent* state for the player — either fixed via community aug SQL or accepted as a cut with a documented bypass — and no player can dead-end on it.
- **Current state:** Register row F2-2 (`:86`) and the §3 independent-verification record confirm wotlk-db issue #240: the BfU chain is absent/unscripted. The repo has no wotlk data (no pin yet). This is decision point 7 (register §4: "accepted cuts vs community-fix aug SQL"). The related phasing context (the chain is phase-heavy) is H1-9's; this gap owns the content decision and its DB outcome.
- **Work items:**
  1. Write the DP7 decision (`docs/DECISIONS-PORT.md`): recommendation — accepted cut with a *prerequisite bridge*: aug SQL that marks the unscripted chain's prerequisites complete (or grants the chain's permanent unlocks — throne port etc.) so post-chain content (Orgrimmar/Undercity state, subsequent quests) is coherent; community-fix aug SQL only if a maintained, license-clean patch exists (provenance header mandatory).
  2. Implement via the aug lane (`schemas/seed-augment/wotlk/…`, F2-7): quest-state seed rows and/or chain-terminating quest availability fix; digest + provenance recorded.
  3. Player-facing honesty: the flavor docs + in-app note (S9 first-run copy family) mention the cut if it alters the expected storyline flow (J1-6 §2.4 owns the expansion-transition handoff checklist — feed it).
  4. Verify no downstream breakage: quests unlocked *by* the chain must be obtainable post-cut (the register's broader "missing chains" concern — sweep the immediate successors of BfU for dead prerequisites while here).
- **Upstream dependency:** `cmangos/wotlk-db` (S4 pin) + wotlk-db issue #240 as the reference; community-fix source only if the fix path is chosen (provenance required).
- **Verification:** Device: a character that should have completed BfU reaches its unlocks; successor quests obtainable and completable; no orphan quest items in the bag (the chain's items handled by the aug SQL); zero error spam around Undercity; decision + provenance in the ledger.
- **Effort:** S (cut path) / M (fix path)
- **Depends on:** F2-7, H1-9, J1-6 (§2.4)
- **Owner stage:** S4

### F2-1 — TBC quest coverage (~6.6k/9k) + Isle of Quel'Danas SSO baseline decision

- **Goal:** TBC quest coverage is measured per zone against reference counts with declared floors, and the Isle of Quel'Danas ships in a *decided* state — static fully-unlocked baseline (recommended) with the SSO progression recorded as not simulated.
- **Current state:** Register row F2-1 (`:87`) states coverage ~6.6k of ~9k rows and only 2 of the Isle's SSO quests scripted; the §3 record marks the exact `quest_template` count as UNCERTAIN-settle-at-S4 ("import Full_DB, `SELECT COUNT(*) FROM quest_template`"). Decision point 6 (register §4) owns the Isle choice. The per-zone floor mechanism is F2-7's; this gap is the measurement + the Isle decision + its aug SQL.
- **Work items:**
  1. At the S4 pin: import tbc-db Full_DB, measure `SELECT COUNT(*) FROM quest_template` (settles the §3 uncertainty), and build the per-zone coverage table (zone → quest rows → reference count → floor) into the content triage doc (H1R2-5 package).
  2. Set per-zone floors at measured values (deficit documented, not hidden) — the floors protect against *regressions* on DB bumps, not against upstream's existing gaps.
  3. Isle decision (DP6): implement the static fully-unlocked baseline — aug SQL enabling the final-phase Isle state (portals active, Shattered Sun NPCs at final state, no offensive/retaking progression); provenance header; the *progression* is recorded as not simulated in the flavor docs.
  4. Device walk: the Isle loads at the baseline state with NPCs/vendors present, daily NPCs interactive, no phase flicker (the TBC phasing context ties to H1-9).
- **Upstream dependency:** `cmangos/tbc-db` `master` (S4 pin, TBD); Wowhead-derived reference counts (provenance in the triage doc).
- **Verification:** Measured count recorded (settles register §3's uncertain item); per-zone floors green in CI; negative: removed quest row fails the zone floor; device Isle walk green at the baseline state; DP6 decision recorded and consumed by the docs.
- **Effort:** L
- **Depends on:** H1R2-5, F2-7, H1-9
- **Owner stage:** S4

### F2-3 — Training dummies missing from wotlk-db (#400/#493/#494) → seed-aug SQL

- **Goal:** Training dummies exist in the WotLK world's major cities, are floored in the seed pipeline, and are usable for DPS parsing without error spam.
- **Current state:** Register row F2-3 (`:88`) cites wotlk-db issues #400/#493/#494 — dummies absent. Vanilla has no dummies (correct for the era); wotlk expects them in starting/major cities. The aug lane is the fix vehicle (`schemas/seed-augment/` exists — verified; F2-7 defines the lane's provenance contract).
- **Work items:**
  1. Author `schemas/seed-augment/wotlk/training_dummies.sql`: creature rows per city (Stormwind/Orgrimmar/Ironforge/Undercity/Darnassus/Thunder Bluff/Shattrath-era equivalents per 3.3.5 layout — derive exact entries/cities from the wowhead-3.3.5 reference, provenance header), spell-focus/GO bindings if the branch's dummy implementation needs them.
  2. Add the named floor: dummy creature entries present (one floor row per city) in the wotlk seed floors.
  3. Combat check: dummies take damage, hold no threat/loot semantics, and don't fight back (verify against the branch's dummy handling at pin); a 5-minute bot-DPS parse against a dummy is a clean regression instrument (reuse the `tools/run_bot_pressure_benchmark.py` harness pattern).
  4. Keep tbc/vanilla clean: no dummy rows in tbc (2.4.3 did not have city dummies — verify the era expectation and record).
- **Upstream dependency:** `cmangos/wotlk-db` (S4 pin) + issues #400/#493/#494 as reference; the aug data is Pocket Realm-authored with provenance.
- **Verification:** CI: wotlk floors green incl. the dummy rows; tbc floors prove absence; device: attack a Stormwind dummy 5 min — damage flows, no error spam, no combat log anomalies; DPS parse produced.
- **Effort:** S
- **Depends on:** F2-7, F2-9
- **Owner stage:** S4

### F2-5 — Glyph item floors per class

- **Goal:** The glyph item set is counted per class against the Wowhead 3.3.5 reference, floored at measured values, and any class-shaped deficit is a documented gap rather than a silent absence.
- **Current state:** Register row F2-5 (`:89`): "~370 glyph items present — count floor vs Wowhead per class" (P3). The items come from wotlk-db at the S4 pin; no glyph data exists in this repo yet. The consumers: inscription trainers (H1-12), bot glyph loadouts (H3-5's `PremadeSpecGlyp`), and the era-lint allow lists (J2-5 item 2).
- **Work items:**
  1. At the S4 pin: count glyph items per class (major/minor × spec where the item naming allows) from `item_template`; build the per-class table into the triage doc.
  2. Floors: per-class minimums at measured values (deficit documented vs the Wowhead reference; do not floor at the *reference* count — floor at what we ship, and document the delta).
  3. Cross-check against H3-5's `PremadeSpecGlyp` loadouts: every glyph a bot loadout references must exist as an item (fixture-level join test) — a loadout referencing a missing glyph fails loudly at conf staging (H1-17 guard).
  4. If a class-shaped deficit blocks bot loadouts: either trim the loadouts to available glyphs (preferred, recorded) or aug-add the missing items (provenance) — decided per class, recorded in the triage row.
- **Upstream dependency:** `cmangos/wotlk-db` `master` (S4 pin, TBD); Wowhead 3.3.5 glyph reference (provenance recorded).
- **Verification:** CI: per-class floors green; loadout↔item join test green; triage table documents measured-vs-reference per class; a trimmed loadout case (if any) is recorded with its reasoning.
- **Effort:** S
- **Depends on:** F2-9, H3-5, H1-17, H1-12
- **Owner stage:** S4

### F2-8 — Locale tables partial (zh/de only) — verified non-gap; exclude from floors

- **Goal:** The register's verified finding — per-flavor locale tables are partial upstream and irrelevant to this product — is *encoded*: excluded from every floor/manifest with the rationale recorded, so a future DB bump cannot accidentally make them required.
- **Current state:** Register row F2-8 (`:90`): "verified non-gap; exclude from floors". The enforcement surface is the floor/manifest machinery (F2-4's manifests, F2-7's floors, `schemas/sqlite-seed-baseline.json`) — today classic-only, so this is a *rule to carry into* the per-flavor work, plus one guard test so the exclusion is self-documenting.
- **Work items:**
  1. In the per-flavor table manifests (F2-4), add an explicit `excluded` section naming the locale-table family (zh/de partial tables per the register) with the reason string from the register row — manifests document what we deliberately do not require.
  2. Guard test: the floor runner refuses a floor entry naming an excluded table (typo-proofing) and the seed passes without them (it will trivially — but the test pins intent).
  3. Docs: one line in the flavor DB page ("locale tables partial upstream; not required by the product").
  4. The client-side locale story (non-enUS clients) is §2.2 H2-8's gap — do not conflate; this gap is server DB data only.
- **Upstream dependency:** none.
- **Verification:** CI: manifests carry the exclusion; guard test green; seed green without locale tables on all flavors.
- **Effort:** S
- **Depends on:** F2-4, F2-7
- **Owner stage:** S4

### F2-9 — Adopt measured content floors (vendors, loot, events, keys, glyphs)

- **Goal:** Every per-flavor seed floor starts from the register's measured numbers and is re-measured at our pins before adoption — vendors 6,444 (tbc) / 34,028 (wotlk); `creature_loot*` 174k / 220k; `game_event` 153 / 300; keys/glyphs present — with each adoption carrying its measurement evidence.
- **Current state:** The register row (F2-9, `:91`) provides the starting numbers ("measured floors"). The classic lane's floor reality: `schemas/sqlite-seed-baseline.json` records measured per-DB statement/table/row counts (verified — `classiccharacters` 1,110,192 statements), and `tools/seed_realm_db.py` performs the seed-side checks; the *named-table* floor mechanism for tbc/wotlk is built by F2-7. This gap is the adoption pass: numbers → floors → evidence, per flavor.
- **Work items:**
  1. For each register number per flavor: re-measure on the *imported* DB at our pin (register §3's discipline — the numbers date from the register's pins; ours may differ) and record old/new in the floor definitions with provenance (register row reference + our measurement command output).
  2. Emit the floors into the F2-7 mechanism: named-table minimums per flavor consumed by `tools/seed_realm_db.py` and `tools/verify_seed_sqlite_floor.py` (the latter currently pins SQLite-version floors 3.18/3.32 — verified `DEFAULT_FLOORS` at `:62` — extend with data floors, not version floors).
  3. Update `schemas/sqlite-seed-baseline.json` per flavor after first green seed (classic precedent: `entries`, `databases`, `transcript_digests` blocks — verified structure).
  4. Review gate: the floors diff is reviewed by the R5 content lens at the S4 gauntlet (per port plan §4.1 R5 charter) before it pins.
- **Upstream dependency:** `cmangos/tbc-db|-wotlk` (S4 pins, TBD).
- **Verification:** Floors green per flavor with evidence-carrying definitions; negative: halve a vendor count → floor fails naming the table with expected/actual; baseline JSON updated with measured blocks for tbc/wotlk; R5 sign-off recorded at the S4 gate.
- **Effort:** M
- **Depends on:** F2-7, H1R2-1, F2-4
- **Owner stage:** S4

---

## Coverage ledger

- [x] H1-2
- [x] J2-1
- [x] H5R2
- [x] H1-3
- [x] H3-5
- [x] H1-4
- [x] H5-4
- [x] H1-5
- [x] H5-3
- [x] H1-6
- [x] H5R2-4
- [x] H1-7
- [x] H1-8
- [x] H5-7
- [x] H1-9
- [x] J1-4
- [x] H1-10
- [x] H5-9
- [x] P1
- [x] H1-11
- [x] H1-12
- [x] H1-13
- [x] C2 — XP-policy sibling of register §2.1 row `H1-13/C2` (NOT the §0/roadmap §1 correction C2 "MPQ sets")
- [x] H1-14
- [x] H1-15
- [x] J3-11
- [x] H1-16
- [x] H1-17
- [x] G2-9
- [x] H1R2-1
- [x] H1R2-2
- [x] H1R2-3
- [x] H1R2-4
- [x] H1R2-5
- [x] F2-7
- [x] H1R2-6
- [x] H1R2-7
- [x] J1-2
- [x] H1R2-8
- [x] G1-7
- [x] G1-9
- [x] G1-8
- [x] G2-3
- [x] G2-4
- [x] G2-5
- [x] G2-6
- [x] G2-8
- [x] G2-10
- [x] J2-2
- [x] J2-3
- [x] J2-4
- [x] J2-5
- [x] J2-6
- [x] J2-7
- [x] F1-10
- [x] F2-4
- [x] F2-6
- [x] F2-2
- [x] F2-1
- [x] F2-3
- [x] F2-5
- [x] F2-8
- [x] F2-9
- [x] G1-4 — register §1 P0 (ICC Gunship Battle ~20% scripted): **carried in §G1-7 work items 5/7** — DP8-family decision, TC port or teleport-bypass cut, device-kill/bypass verification, H1R2-5 triage row (not one of this area's 63 assigned IDs; no standalone section)
- [x] G1-5 — register §1 P0 (The Lich King ~5% scripted): **carried in §G1-7 work items 6/7** — defile/Val'kyr phase-controller port or recorded bypass, H5-2 bot-strategy half, triage row (not one of this area's 63 assigned IDs; no standalone section)

§2.2 turns to the client data contract and the on-device runtime envelope: 36 IDs across identity exactness (the MPQ sets, exe identity, and realmlist/locale files import must qualify exactly — including register §1 H2-1, the plan's most direct ship-blocker), import robustness and honest rejection, prep scaling and provenance, and the runtime environment (DXVK traps, the WotLK memory envelope, shader cache, WTF/cvar policy, crash artifacts) that wave S7 launches against. Its recurring structural fact: the S0 flavor registry already exists, so most of this work widens single-flavor-shaped gates rather than inventing new architecture.

---

## 2.2 Client data & runtime (R1: H2, R2: H2R2, R3: P3, R8: W1)

This section owns the client-side data contract and the on-device runtime envelope for TBC (2.4.3.8606) and WotLK (3.3.5a.12340): the MPQ sets, exe identity, and realmlist/locale files that import must qualify exactly; the archive-lane policies (caps, encrypted/multi-part rejection, per-flavor VAL copy) that decide what may reach a managed copy; the data-preparation pipeline (DBC/maps/vmaps/mmaps) that turns a qualified client into server-consumable generations; and the runtime environment (DXVK traps, memory envelope, shader cache, WTF/cvar policy, crash artifacts) that S7 launches against. The gaps group into four themes: **identity exactness** (H2-2/H2-9/H2R2-3/H2-8 — today's scanners verify a flat `Data/*.mpq` presence set only, `SafClientScanner.kt:131-149`, and treat any extra archive as acceptable); **import robustness and honest rejection** (H2R2-4/H5R2-8/P3-3..P3-5/P3-9/P3-10); **prep scaling and provenance** (H2-10/H2-11/F1-8/P3-6..P3-8/P1-5/P3-2/P1-6/P3-1); and **runtime environment parity** (H2-3/P1-4/H2-4/H2-6/H2-7/H4-7/J2/H2-12/H2-13/H2-14/W1-*). A recurring structural fact used throughout: the S0 flavor registry already exists (`android/app/src/main/java/com/pocketrealm/client/ClientFlavor.kt`, `schemas/flavor.json` schema 2, TBC/WotLK declared `qualified=false`), so most of this section is *widening data-driven gates that are currently single-flavor-shaped*, not inventing new architecture. Every gap below names the file it lands in; external inputs required are the two genuine clients (port plan §8.2) and RP6 device windows.

---

### H2-1 — Flavor clients read realmlist from Data\<locale>\realmlist.wtf: project root + locale copies at launch

- **Goal:** Every launch projects `set realmlist <endpoint>` (and nothing else) into the root `realmlist.wtf` *and* every `Data\<observedLocale>\realmlist.wtf` in the managed copy — the file the TBC/WotLK clients actually read (register §1 P0 row H2-1, `FEATURE-PARITY-GAPS.md:27`) — so a flavor client connects to the local realm only.
- **Current state:** The projection is single-file: `ClientRealmEndpointProjection.text` writes exactly `set realmlist <address>` (`ClientRealmEndpointProjection.kt:10`) and `project()` atomically replaces one target (`:12-27`); the launch path projects only `<workingDir>/realmlist.wtf` (`WineRuntimeStore.kt:2229-2233`) and the pre-launch attestations verify only that root copy (`WineRuntimeStore.kt:1738-1742`; `ManagedClientStore.kt:114-121`). Per register C2, the 2.4.3/3.3.5 clients read `Data\<locale>\realmlist.wtf` — which no code path writes, scrubs at launch, or attests — so flavor clients never see the app's endpoint and never connect: this is the register's most direct ship-blocker. The server-side twin is register correction C1 (`realmbuilds`/`gamebuild` realmlist schema, S2): the projected endpoint only authenticates if the flavor's realmd advertises the right build acceptance.
- **Work items:**
  1. Extend `ClientRealmEndpointProjection` with a multi-target `projectAll(managedRoot, endpoint)`: write the root file (byte-identical to today, `:10-27` discipline) plus `Data/<locale>/realmlist.wtf` for every locale directory present under `Data/` at launch time, plus the import-recorded `observedLocale` (H2-8) if its directory exists. Same atomic temp+fsync+rename per file; the text stays the single `set realmlist` line — patchlist/realmlistbn/portal lines are never written (H2R2-4 owns removing them from the *imported* copy; this owns the *launch* write).
  2. Wire `projectAll` into the launch path at `WineRuntimeStore.kt:2229-2233` (replacing the single `project` call) and extend the pre-launch attestation (`:1738-1742`) and `ManagedClientStore` attest (`:114-121`) to require every projected copy to match — a stale or foreign locale copy fails the prepare, not the login.
  3. Extend `appOwnedFiles` (`ClientGenerationStore.kt:111`) to cover `Data/*/realmlist.wtf` (same set H2R2-4 item 2 names), so snapshots/validators treat the locale copies as app-owned like the root file.
  4. Vanilla golden (G3-7, §2.7): vanilla trees have no `Data/<locale>/` subdirectories, so the multi-copy branch never activates — pin the vanilla root-file projection bytes in a golden test so the root path is provably untouched (invariant 1).
  5. The S8 auth-matrix leg: the runtime test below composes with register C1 — after S2 seeds `realmbuilds='8606'/'12340'`, verify a wrong-flavor client pointed at this projected realm fails login cleanly with no account row (port plan S8 End State, both directions).
- **Upstream dependency:** none (register C1's `realmbuilds` seeding is S2 server work this composes with, not a code dependency of the projection).
- **Verification:** The register's named runtime test per flavor on RP6: launch → realm list shows only the local realm → auto-login lands on char select, 5/5 attempts. Negative: a doctored `Data/enUS/realmlist.wtf` carrying `set realmlist us.version.worldofwarcraft.com` + `set patchlist …` is overwritten by the launch projection (and caught by the attestation if projection is skipped); offline launch connects nowhere (H5R2-8's cold-start run reuses this assertion). Vanilla: root-file bytes golden-identical.
- **Effort:** M
- **Depends on:** H2-8 (observedLocale record), H2R2-4 (import-time neutralization of the same files)
- **Owner stage:** S7 (with the S8 auth-matrix leg)

### H2-2 — Locale-subfolder MPQ verification (Data\<locale>\ sets)

- **Goal:** Import verifies the flavor's complete MPQ set — root MPQs plus the `Data\<locale>\` locale/speech MPQs — derived from the qualified client, so a TBC/WotLK copy with MPQs in the locale subfolder qualifies and one with a broken/missing locale set fails VAL-04.
- **Current state:** Both scanners inspect only the *root* of `Data/`: `SafClientScanner.kt:131-149` lists `children(data.id)` and filters `*.mpq` at that single level, and `ArchiveClientScanner.verifyExtracted` (`ArchiveClientScanner.kt:101-117`) does `data.listFiles()` the same way. There is no traversal into `Data\enUS\` (or any locale folder). Per register correction C2, TBC 2.4.3 and WotLK 3.3.5a load root MPQs (`common/expansion/patch/patch-2` for TBC; `common, common-2, expansion, lichking, patch, patch-2, patch-3` for WotLK) **plus** a locale/speech set that lives in `Data\<locale>\` — so today a genuine 8606/12340 client would fail `VAL-04: missing base MPQ set` for every locale MPQ that is not present at `Data/` root, or pass spuriously if a repack flattened them. `ClientFlavor.requiredMpqs` (`ClientFlavor.kt:39`) is a flat `Set<String>` with no path component.
- **Work items:**
  1. Extend `ClientFlavor` with a path-aware MPQ requirement: replace `requiredMpqs: Set<String>` with a list of `{ path, name }` (or `requiredMpqPaths: Set<String>` of slash-normalized `Data`-relative paths, plus a `toleratedAuxiliaryMpqs` set for WotLK's `base-enUS`/`backup-enUS` per C2) in `ClientFlavor.kt`, `schemas/flavor.json` (mirror field under each flavor block), and `tools/check_flavor.py` validation. Populate TBC/WotLK sets from the qualified clients during S5 qualification (initial data from register C2; exact locale/speech names measured, not guessed).
  2. Rework the MPQ verification block in `SafClientScanner.scanAccess` (`SafClientScanner.kt:134-147`) to resolve each required entry case-insensitively at its declared path: lookup first among `Data` root children, then among `children("Data/<dir>")` for each declared locale directory observed on the tree (the locale directory name comes from the flavor's declared locale list or the observed `Data/*` directory listing; H2-8 records the observed locale). Keep the 4-byte `MPQ\x1a` header check (`SafClientScanner.kt:143-145`) for every verified file.
  3. Mirror the same resolution in `ArchiveClientScanner.verifyExtracted` (`ArchiveClientScanner.kt:104-116`) using `java.io.File` walks bounded to `Data/` (two levels deep, no symlink follow), and in the ZIP/7z detection path via `ArchiveClientScanner.ArchiveAccess` (`ArchiveClientScanner.kt:209-265`), whose synthesized directory tree already supports `children("Data/enUS")` — only the *query* needs the locale path.
  4. Keep the "flavor == null ⇒ no MPQ comparison" guard (`SafClientScanner.kt:138-141`) so foreign clients still fail VAL-03 first.
  5. Fixtures: extend `SyntheticClientArchives.kt` (`android/app/src/debug/java/com/pocketrealm/importer/SyntheticClientArchives.kt`) with per-flavor synthetic trees that place locale MPQs under `Data/enUS/`, and negative variants (locale set in wrong folder, one locale MPQ corrupt). Wire into `ArchiveDetectionTest.kt` and `SafClientScannerMessageTest.kt` (JVM) plus `O12ArchiveImportTest.kt`/`O11ManagedImportTest.kt` (androidTest).
- **Upstream dependency:** none (set contents come from the user-supplied qualified clients + register C2; no upstream repo change).
- **Verification:** JVM: `SafClientScannerMessageTest` green with the new locale-set fixtures; negative fixture (locale MPQ missing from `Data\enUS\`) fails with `VAL-04` naming the path. Device (S5 exit): genuine 8606 and 12340 enUS clients import end-to-end through folder and archive lanes; a tree with the locale MPQs deleted post-scan but pre-publish is caught by `verifyExtracted`.
- **Effort:** M
- **Depends on:** none (needs the genuine 8606/12340 clients from port plan §8.2, an external input, not a gap ID)
- **Owner stage:** S5

### H2-9 — Exact-set MPQ semantics (unknown patch-*.mpq ⇒ VAL-04 naming the file)

- **Goal:** An MPQ that is not in the flavor's required or tolerated-auxiliary set — e.g. an unknown `patch-4.mpq`, a repack's custom archive — fails import with `VAL-04` that names the offending file, instead of being silently accepted.
- **Current state:** Verification is one-directional: `SafClientScanner.kt:140-141` and `ArchiveClientScanner.kt:109-110` compute `requiredMpqs` *missing from* the tree and never compare in the other direction. Any extra `*.mpq` beside the required set passes, changes the extraction load order (patch MPQs overlay earlier data), and invalidates the pristine-client assumption the exe hash attests (H2R2-3). This is exactly the R2-adversarial hole the register names for H2-9.
- **Work items:**
  1. In the same scanner blocks touched by H2-2 (`SafClientScanner.kt:134-147`, `ArchiveClientScanner.kt:104-116`), after the missing-check, compute `unexpected = discoveredMpqKeys - requiredKeys - toleratedAuxiliaryKeys` (path-aware keys from H2-2) and emit `failures += "VAL-04: unexpected MPQ not in the qualified build-<flavor.build> set: <name> — re-import the unmodified client"`. The wording must be added to the pinned-message test `SafClientScannerMessageTest.kt`.
  2. Encode the policy as data: an `exactSet: Boolean` (default true) per flavor in `ClientFlavor.kt` + `schemas/flavor.json` so a future legitimate CDec-based set change is a data edit; `tools/check_flavor.py` rejects a flavor with `exactSet=true` and empty `requiredMpqPaths`.
  3. Precedence rule (record in Decision Log): VAL-03 (wrong build) wins over VAL-04 (set mismatch) — keep the existing guard that a foreign client never reaches MPQ comparison (`SafClientScanner.kt:138-141`).
  4. Negative vectors in `ArchiveDetectionTest.kt`: (a) vanilla 11-MPQ set + `patch-4.mpq` → reject naming `patch-4.mpq`; (b) WotLK set + `patch-4.mpq` (C2: no patch-4) → reject; (c) WotLK set + tolerated `base-enUS.mpq` → accept; (d) renamed required MPQ (`patch-2.MPQ` case variants) → accept via case-fold, `patch-2.mpq.bak` → ignore (not `.mpq`).
- **Upstream dependency:** none.
- **Verification:** The four JVM negative vectors above green; R2-authored doctored-set adversarial case from port plan S5 End State ("doctored MPQ set rejected") re-run with the exact-set rule and shown to reject with the file named; `tools/leak_sweep.py` untouched (no new constants).
- **Effort:** S
- **Depends on:** H2-2 (path-aware MPQ keys and tolerated-aux data land with it)
- **Owner stage:** S5

### H2R2-3 — Pristine MD5 cross-checks + negative vectors for modified exes

- **Goal:** Every qualified flavor carries the corpus-known pristine exe hash pair (MD5 + existing SHA-256), a modified exe is flagged by a loud (not warning-only) signal for known-bad classes (LAA-patched, d3d9ex-proxy, repack), and the qualification record cites the checksum corpus.
- **Current state:** `ClientFlavor.knownExeSha256` (`ClientFlavor.kt:37`, vanilla value at `:75`) is compared **warning-only**: `SafClientScanner.kt:123-127` adds either "hash matches the inspected build-5875 copy" or the softer "build confirmed; … accepted for launch" warning — a hash mismatch never fails import. `ClientPeIdentity.parse` (`ClientPeIdentity.kt:73-74`) computes SHA-256 over the full exe bytes (bounded by `MAX_EXE_BYTES = 32 MiB`, `SafClientScanner.kt:174` — register C6 says the 12340 exe is ~12.6 MB, so the cap fits). There is no MD5 field and no negative-vector fixtures for the common modified-exe classes the register names. `ClientTweaks.kt` proves the app can *detect* patch-class signatures: its LAA patch writes COFF characteristics bit 0x20 at offset 0x126 (`ClientTweaks.kt:248-253`), which is precisely what an LAA'd exe carries.
- **Work items:**
  1. Add `knownExeMd5: String?` (and optionally `knownExeSizeBytes: Long?`) to `ClientFlavor` + `schemas/flavor.json`; qualify S5 with corpus values: 8606 `57c5c030…`, 12340 `45892bde…` (register C6, corpus `anzz1/wow-client-checksums`); re-derive the full SHA-256s on the genuine clients and record both in `supported_client_builds` (the field already exists in `flavor.json` with a note that it gates once populated).
  2. Decision (Decision Log entry): hash mismatch on a *qualified* flavor = hard `VAL-03`-class failure naming both hashes — not the current soft warning — **unless** the exe is the app's own qualified `WoW.exe.patched` lane (`WineRuntimeStore.kt:1710-1717` already requires `AUTHORIZED_CLIENT_SHA256` pristine identity for tweaks; that constant is vanilla's — make it per-flavor via `ClientFlavor.knownExeSha256`). This preserves H4-1-class tweak flows without letting a foreign byte-patch masquerade.
  3. Negative-vector fixtures in `ClientFlavorTest.kt` / `SafClientScannerMessageTest.kt` using `SyntheticClientArchives` exe bytes mutated in the three named classes: (a) flip COFF characteristics bit 0x20 at the LAA offset (reuse `ClientTweaks` offset logic), (b) append/rename a `d3d9ex`-style proxy `d3d9.dll` beside the exe (this must *also* trip the existing VAL-10 unrecognized-root-DLL warning path, `SafClientScanner.kt:151-154` — keep warning-level there, failure comes from the hash), (c) repack = one byte changed in `.text` → hash mismatch reject.
  4. Record the corpus provenance (repo URL, commit used) in `schemas/flavor.json` next to the hashes so `tools/check_sources.py`-style provenance discipline extends to client identity data.
- **Upstream dependency:** `anzz1/wow-client-checksums` (read-only corpus for pristine MD5/SHA values; no pin required — values are transcribed into `flavor.json` at qualification).
- **Verification:** JVM: mutated-exe negative vectors each fail with the expected VAL code and message; genuine vanilla exe bytes still pass byte-for-byte. Device (S5): vanilla import unchanged (invariant 1), and the S5-qualified 8606/12340 exes hash-match on import.
- **Effort:** M
- **Depends on:** H2-2 (lands in the same scanner qualification pass), none otherwise
- **Owner stage:** S5

### H2R2-4 — Neutralize `set patchlist` / `realmlistbn` / `portal` at import

- **Goal:** A managed TBC/WotLK copy contains no line that can redirect the client to Blizzard infrastructure — `set realmlist`, `set patchlist`, `set realmlistbn`, `set portal` are all absent from root and `Data\<locale>\` realmlist files at import time, and every launch re-projects only `set realmlist`.
- **Current state:** `ClientRealmEndpointProjection.text` writes exactly `"set realmlist <address>\r\n"` (`ClientRealmEndpointProjection.kt:10`) and `project()` atomically replaces the single target file; the launch path writes it into the client working dir (`WineRuntimeStore.kt:2229-2233`) and `WineRuntimeStore.kt:1738-1742` verifies the root `realmlist.wtf` matches before launch. So for the *root* file the neutralization is de-facto (whole-file replace). What is missing: (a) the **imported** copy keeps whatever realmlist lines the source tree carried — `ClientGenerationStore` seeds a clean root `realmlist.wtf` (`ClientGenerationStore.kt:154`) but nothing scrubs `Data\<locale>\realmlist.wtf`, which per register H2-1 is the file TBC/WotLK clients actually read; (b) `ArchiveClientScanner.readRealmTarget` (`ArchiveClientScanner.kt:198-206`) reads only the root file for its informational `realmTarget`, so a `set patchlist <blizz>` line in the locale realmlist is never even observed; (c) `ClientGenerationStore.kt:111` lists `appOwnedFiles = ["realmlist.wtf", "WTF/Config.wtf"]` — app-ownership is not extended to the locale copies.
- **Work items:**
  1. Add a `realmlistNeutralization` step to publish: after the generation rename in `ClientGenerationStore` (alongside the existing `atomicWrite(File(staging, "realmlist.wtf"), …)` at `:154`), write the same two-line content to every `Data/<locale>/realmlist.wtf` observed in the staging tree (create the file if the locale dir exists without one; never create locale directories that are absent). Content: `set realmlist 127.0.0.1\r\n` only — patchlist/realmlistbn/portal lines are dropped by construction, not by regex-stripping, which is simpler and total.
  2. Extend `appOwnedFiles` in `ClientGenerationStore.kt:111` to the glob set `realmlist.wtf` + `Data/*/realmlist.wtf` (and keep `WTF/Config.wtf`), so snapshots/validators treat them as app-owned like the root file.
  3. Extend `WineRuntimeStore.kt:1738-1742`'s pre-launch attestation to check every existing `Data/*/realmlist.wtf` matches the projected content (or is absent), so a stale/locale divergence cannot survive to launch.
  4. Scanner observation only (no gate): extend `readRealmTarget` (`ArchiveClientScanner.kt:198-206`) to also record any `patchlist`/`realmlistbn`/`portal` lines found in either file into `warnings` for provenance (journal records them via the existing warnings channel).
  5. This composes with H2-1 — H2-1 owns *writing* the locale copies at launch; this gap owns *import-time* neutralization so the managed copy is inert even before first launch.
- **Upstream dependency:** none.
- **Verification:** JVM (`ClientGenerationStore`-level test or new `realmlist scrub` test): a staging tree whose `Data/enUS/realmlist.wtf` contains `set realmlist us.version.worldofwarcraft.com`, `set patchlist us.version.worldofwarcraft.com`, `set portal eu` publishes with all three gone. Device: publish a TBC synthetic with poisoned locale realmlist, launch, and confirm via `adb shell` file dump that every realmlist file under the managed root contains only `set realmlist 127.0.0.1`.
- **Effort:** S
- **Depends on:** none (coordinates with, but does not block on, H2-1)
- **Owner stage:** S5

### H5R2-8 — Deny-list Launcher/BackgroundDownloader/Repair execution; offline cold-start QA

- **Goal:** No non-WoW executable from the imported tree can execute under the managed runtime, and a cold start with no network performs no download/repair/launcher behavior — verified per flavor.
- **Current state:** The copy lane already excludes them: `excludeFromSafeRuntime` (`ManagedClientImporter.kt:682-687`) skips every root `*.exe` except `wow.exe` and every root `*.dll` not in `STANDARD_DLLS` (`:728-730`), journaling each as "safe-mode excludes non-primary root executable or injected DLL" (`:356-362`); `ImportExtractionPolicy.ROOT_FILES` (`ImportExtractionPolicy.kt:107-111`) still classifies `repair.exe`/`launcher.exe`/`backgrounddownloader.exe` as copy-eligible *names* so the skip is journal-visible rather than a silent exclusion. Register G2-6 (§2.1) covers Repair.exe's byte-revert hazard from the packaging side. What remains for this gap: (a) the exclusion is root-level only — a `Launcher.exe` nested anywhere below root would copy (archives rarely nest it, but folder lanes can); (b) there is no *runtime* deny (Wine will happily execute a nested exe if anything invokes it), and no cold-start QA that proves the TBC/WotLK clients never spawn `BackgroundDownloader.exe` (they historically spawn it for patch downloads when `patchlist` is set — neutralized by H2R2-4) or show Launcher/EULA-download UI offline.
- **Work items:**
  1. Extend `excludeFromSafeRuntime` to match on `relative.substringAfterLast('/')` for the deny set {`repair.exe`, `launcher.exe`, `backgrounddownloader.exe`, `wowerror.exe`-adjacent helpers kept out deliberately: `uninstall.exe`}, at any depth, with the same journal reason plus the relative path. Keep `wowerror.exe` in the copy set (harmless crash reporter; produces the `Errors/` artifacts H2-7 harvests) — record the reasoning in the Decision Log. **Warden module file family (register §1 G2-1/G2-2; the enforcement half of roadmap DP11):** the `warden_modules` tree — the ~80 Blizzard `.bin`/`.key` files — joins this same deny set, with exact names/paths **measured from the qualified 8606/12340 clients at S5** (they arrive inside the imported client; `schemas/sources.json` is a build-source provenance registry and records the licensing *verdict* provenance-only, per server-systems.md G2-6 work item 5 — this lane is the enforcement point). Excluded files are journaled with path + SHA-256 so diagnostics/snapshot/bundle walks (§2.5 P2-9/P2-10) never see them; content-exclusion (G2-6) and execution-deny (this gap) stay one list.
  2. Defense in depth at launch: `ClientRuntime.armClientArguments` (`ClientRuntime.kt:150-160`) launches only the attested executable; add a post-prepare directory assertion in `WineRuntimeStore` (near the `:1738` attestation block) that no non-attested `*.exe` exists in the managed root or one level below — fail the prepare, not the launch.
  3. Cold-start QA script: add a step to the S7 device runbook (`tools/capture_rp6.py` evidence run) that launches the flavor client with Airplane-mode/Wi-Fi off and asserts (a) no process other than `WoW.exe` appears in the Wine process list (session log grep), (b) no `BackgroundDownloader` window in the login-topology capture (the S7 topology machinery already screenshots the login window), (c) login proceeds to char select against the loopback realm only.
  4. **S5 measurement half:** the deny set itself is measured from the qualified 8606/12340 client trees during S5 qualification — including the warden `.bin`/`.key` module family named in item 1 (register §1 G2-1/G2-2; roadmap DP11's enforcement point) — in lockstep with server-systems.md G2-6 work item 5's packaging deny list: one measured source, two consumers (packaging verdict and copy/execution deny). The S5 half of this gap is that measurement; the S7 half is the execution deny + offline cold-start QA below.
  5. Per-flavor note in the WotLK lane: register H4R2-6 routes torrent-downloader WotLK distributions to the PC-extract-first lane — cross-reference, do not duplicate.
- **Upstream dependency:** none.
- **Verification:** JVM: importer test with a tree containing `Interface/Addons/evil/Launcher.exe` and root `Launcher.exe` — both journaled as skipped. Device (S7, per flavor): cold-start run with networking disabled produces the three assertions above; evidence pack archived with the S7 exit.
- **Effort:** M
- **Depends on:** H2R2-4 (patchlist neutralization removes the trigger for BackgroundDownloader)
- **Owner stage:** S7 (deny-set measurement half at S5, lockstep with G2-6 item 5)

### H2-3 — WotLK client memory ceiling: LAA-variant qualification or RAM admission gate

- **Goal:** A WotLK client session on RP6-class hardware is proven to fit the shared 12 GB envelope — either via a hash-pinned large-address-aware (LAA) exe variant qualified through the vanilla-tweaks machinery, or via an explicit RAM admission gate with honest copy — with the choice recorded and the graphics-preset default measured (register C7: 3.3.5a is *not* large-address-aware, 2 GB VA).
- **Current state:** The machinery to patch and gate an LAA variant exists but is vanilla-locked: `ClientTweaksConfig.largeAddressAwareEnabled` (`ClientTweaks.kt:28`) drives a COFF-characteristics `or 0x20` write at hard-coded offset 0x126 (`ClientTweaks.kt:248-253` — that offset is a build-5875 PE layout fact; `expectedPatchedBytes` even `require`s "too small for build 5875" at `:243`); publication requires `managed.executableSha256 == ClientTweaksConfig.AUTHORIZED_CLIENT_SHA256` (`ClientTweaks.kt:103`, `:139`; enforced again at `WineRuntimeStore.kt:1710-1717`). Upstream `brndd/vanilla-tweaks` v1.6.0 (vendored, MIT, pinned `fbbe31ad…` in `native/vanilla-tweaks/BUILD_PROVENANCE.json`) ships the `--no-largeaddressaware` flag, so the patch primitive is upstream. Port plan S7.4 currently says vanilla-tweaks is feature-gated **off** for tbc/wotlk. The vanilla LAA flag offset 0x126 is PE-header-relative and almost certainly differs for the 12340 image (different e_lfanew); the register H2-3 row explicitly offers "reusing vanilla-tweaks machinery" for a hash-pinned LAA variant, *or* a RAM admission gate (`BotAdmissionController.kt:156-157` already has `minFreeMemoryMiB` flooring for bots — the client-side analog does not exist).
- **Work items:**
  1. Measure first (feeds P1-4): on RP6, run stock 12340 at Dalaran with the S7 default preset and record client RSS/VA pressure; decide go/no-go for stock-exe-only (Decision Log). If stock fits under the envelope, ship the gate only and skip items 2-3 (record as the outcome; the register allows either branch).
  2. LAA variant branch: generalize `ClientTweaks.expectedPatchedBytes` to compute the COFF characteristics offset from the image (`e_lfanew` at 0x3C + 0x16) instead of the literal `0x126`, gated to the LAA patch only so the other 5875-anchored offsets keep their hard-coded require. Add `ClientFlavor.tweaksCapable: Boolean` + a per-flavor `AUTHORIZED` pristine sha (replace the single `AUTHORIZED_CLIENT_SHA256` reads at `ClientTweaks.kt:103/:139` and `WineRuntimeStore.kt:1714` with the flavor-resolved value). Produce `WoW.exe.patched` for 12340 with the independent expected-output model (`BUILD_PROVENANCE.json` documents this discipline) and re-verify under Wine on device (2 GB→4 GB VA; assert via a test-mode allocation probe or Dalaran+40-player-stress RSS).
  3. RAM admission gate (either branch): add a client-session preflight beside the bot admission floor — `WineRuntimeStore` prepare-time check comparing `StorageManager.getAllocatableBytes`+`ActivityManager.MemoryInfo.totalMem*` against a per-flavor `minClientFreeMemoryMiB` in `schemas/flavor.json` (vanilla: no gate; wotlk: measured value); denial produces an actionable card, not a silent downgrade. Register P1-4 is the measurement campaign this consumes.
  4. Graphics preset default: seed the WotLK `SAFE_CONFIG`/first-login defaults (`ClientGenerationStore.kt:237-256`) with the measured-from-decision preset (see W1-8) — the memory decision names the preset it was measured with.
- **Upstream dependency:** `brndd/vanilla-tweaks` v1.6.0 @ `fbbe31add71b23602d981d70f1a58520fc349b47` (already vendored at `native/vanilla-tweaks/`, MIT — LAA patch primitive reused; no new upstream needed if the offset generalization is done in-app).
- **Verification:** Device (S7/RB): with the chosen branch active, a 2-hour Dalaran play soak at the default preset on RP6 with `adb shell dumpsys meminfo`/`showmap` samples archived; no lowmemorykiller kills of the client process; if the gate branch: a forced low-memory denial shows the card. JVM: LAA byte-model test on a synthetic 12340-shaped image.
- **Effort:** M
- **Depends on:** H2R2-3 (per-flavor pristine hash the variant attests against — prerequisite, S5). P1-4 composes with this gate, not a code prerequisite of it: the S7 LAA-variant-vs-gate decision proceeds on register C7's static 2 GB fact plus the S5 hashes, and P1-4's S6/M1 envelope numbers finalize `minClientFreeMemoryMiB` afterward (same scoping as the F3-3/H2-5↔P1-8 edge, performance-persistence-import.md:336, and H2-1's C1 edge) — the gate-*value* finalization waits on M1 completion; the S7 gate mechanism itself does not.
- **Owner stage:** S7

### P1-4 — WotLK client+server memory envelope measurement (RP6 milestone)

- **Goal:** The register-headline P1-2/P1-3/P1-4 milestone exists as archived evidence before the S6.4 go/no-go: mangosd-wotlk RSS vs bot count, client RSS at Dalaran, boot wall-clock — per flavor, with per-flavor admission floors and memory ceilings derived from the numbers, and grid preload forbidden by config.
- **Current state:** There is no WotLK datapoint (register P1-10: "none exists"). The measurement harnesses exist: `tools/capture_rp6.py` already parses `/proc/meminfo`, zram, thermal and display state (`_parse_meminfo`, `_parse_zram`, `_parse_thermal_sysfs` at `tools/capture_rp6.py:184-402`) and is the port-plan §4.5 evidence tool; `BotAdmissionController` consumes `minFreeStorageMiB`/`minFreeMemoryMiB` per profile (`BotAdmissionController.kt:156-157`) but its profile values are single-lane; the RP6 harness suite (`tools/rp_harness/`) has soak probes (`desktop_soak.py`, `probe_admission.py`). Register C7 (12340 not LAA) and the TC ~5 GB full-grid preload comment (register P1-2/P1-3) bound the problem; the WotLK map count (~800+ tiles vs vanilla's much smaller set) is what makes `MMAP_THREADS`/preload decisions matter (see P3-6/P3-7).
- **Work items:**
  1. Define the measurement matrix in a new `tools/rp_harness/memory_envelope.py` (or extend `capture_rp6.py`): per flavor {vanilla control, wotlk}: boot wall-clock to world-ready, mangosd RSS at bot counts {0, default population, max}, MariaDB RSS, client RSS after 10 min at the flavor's busiest hub (Dalaran for wotlk), sampled at 30 s via `dumpsys meminfo` + `/proc/<pid>/status` VmRSS.
  2. Forbid grid preload explicitly: the WotLK `mangosd` conf emitted by `ServerRuntimeFiles.kt` must set the preload-adjacent knobs off — concretely: verify the cmangos-wotlk conf dist's preload/`GridUnload` semantics and pin the no-preload equivalent; the point is a named conf key with a measured number behind it, recorded in the per-flavor conf-key matrix (register H1-17).
  3. Derive and write per-flavor constants: `minClientFreeMemoryMiB`/`maxTotalWorkspaceMiB` into `schemas/flavor.json` (consumed by H2-3's gate) and the bot admission profile floors (`BotAdmissionController` profile source) — with the measured evidence sha in the Decision Log entry.
  4. Archive: the evidence pack for the S6.4 go/no-go (port plan S6.4 names this decision point) contains the raw samples + derived constants; R4 reviews the numbers (port plan §4.1).
- **Upstream dependency:** none (device window from port plan §8.2 item 3 is an external input).
- **Verification:** The evidence pack exists with ≥ 2 h wotlk world uptime at default bot population and the three named series sampled; the derived `flavor.json` constants cite sample hashes; `tools/capture_rp6.py` output for the runs is committed under the S6 exit artifacts.
- **Effort:** M
- **Depends on:** none (this is the input to H2-3/P3-6/P3-7; requires S2/S4 wotlk bring-up to have a server to measure — sequencing note, not a gap dependency)
- **Owner stage:** S6

### H2-4 — d3d9ex × gxTextureCacheSize DXVK trap: WotLK SAFE_CONFIG pin

- **Goal:** WotLK sessions cannot enter the known ≥2048 `gxTextureCacheSize` + `d3d9ex` DXVK FPS-halving trap: the SAFE_CONFIG seed and the in-game settings catalog pin `gxTextureCacheSize ≤ 2047` (and the chosen `d3d9ex` posture), qualified per DXVK version × Vulkan driver actually shipped.
- **Current state:** The app's config surface has no `d3d9ex` or `gxTextureCacheSize` anywhere (repo-wide grep over `android/app/src/main/java` for `d3d9ex|gxTextureCacheSize` returns nothing): `ManagedConfigPolicy.enforcedKeys` (`ManagedConfigPolicy.kt:36-92`) enforces `gxApi "d3d"` under the DXVK route (`:38`), `gxResolution`/`gxWindow*`/`maxFPS`, and the first-login seed `SAFE_CONFIG` (`ClientGenerationStore.kt:237-256`) pins none of them — so a WotLK client's own defaults (or a user raising texture cache in the in-game editor) can walk into the trap, and DXVK 2.4.1 (`RendererPackageCatalog.kt:37-45`, legacy lane 1.10.3 `:38,:58-60`) is the shipped translator pair that must be qualified against. The enforcement machinery itself (`ConfigWtfCodec.merge` + `EnforcedLine(key, value|null)` write-or-delete semantics, `ManagedConfigPolicy.kt:5-17`) is exactly the right vehicle — it just has no per-flavor key list.
- **Work items:**
  1. Make the enforced-key set per-flavor: `ManagedConfigPolicy.enforcedKeys(conditions)` gains a `ClientFlavor` (or a per-flavor policy struct) parameter; the call sites at `WineRuntimeStore.kt:2206`-region and `InGameSettingsEditor.kt:66`-region pass the session's flavor. For wotlk: add `EnforcedLine("gxTextureCacheSize", "1024")` (measured default ≤2047; pick the measured value, register says pin ≤2047) and the `d3d9ex` posture decided by measurement — for the DXVK route the honest default is `d3d9ex "0"` (DXVK implements its own swapchain; `d3d9ex` mainly changes device creation), recorded in the Decision Log with the A/B FPS numbers.
  2. Seed the same keys into the wotlk first-login `SAFE_CONFIG` (`ClientGenerationStore.kt:237-256`) so a fresh generation starts pinned, and list both keys in the WotLK settings catalog (`WowWotlkSettingsCatalog`, S7 deliverable) as read-only/app-owned entries so the editor labels them blocked like other enforced keys (`ManagedConfigPolicy` enforcement already drives that UI state).
  3. Qualification matrix: on RP6 × {Turnip, system Adreno, one user-imported driver} × {DXVK 2.4.1, 1.10.3}, capture 5-minute Dalaran frame-time samples (`DXVK_LOG_LEVEL=info` logs already land per-session via `DXVK_LOG_PATH`, `ArmSessionEnvironment.kt:76-77`) with gxTextureCacheSize 512/2047/4096 × d3d9ex 0/1; archive the matrix in the S7 evidence pack and record the pin values from it.
  4. Guard: `tools/leak_sweep.py` is not affected; the pin is data in `flavor.json` (`runtime.config_pins` block) validated by `tools/check_flavor.py`.
- **Upstream dependency:** none (DXVK pins already vendored: `box64-dxvk-2.4.1`, `box64-dxvk-1.10.3`).
- **Verification:** Device: the qualification matrix archived; a WotLK session launched with a doctored base `Config.wtf` carrying `SET gxTextureCacheSize "4096"` is corrected by the prepare-time merge (post-merge file shows ≤2047 — extend the `WineRuntimeStore.kt:1746-1753` config attestation test); frame-time A/B shows the ≥2048 degradation exists on this stack (proving the pin matters) and is absent under the pin.
- **Effort:** M
- **Depends on:** none (coordinates with W1-8's preset naming)
- **Owner stage:** S7


### H2-6 — Intro/expansion movies: pre-seeded off, stuck-in-movie detection

- **Goal:** A fresh TBC/WotLK generation never plays the DivX/DirectShow intro or expansion cinematics on first launch, and if a movie ever stalls the launch path, the session fails with an actionable "stuck in movie" state instead of a silent black screen.
- **Current state:** Vanilla's protection is `movie` only: `ManagedConfigPolicy.enforcedKeys` writes `EnforcedLine("movie", "0")` every prepare (`ManagedConfigPolicy.kt:43`) and the first-login `SAFE_CONFIG` seeds `SET movie "0"` (`ClientGenerationStore.kt:243`) — but there is no `expandMovie`-class key (grep for `expandMovie` in `android/app/src/main/java` returns nothing), which is the register-named second knob for the TBC/WotLK expansion cinematics. The movie assets live in the client's `Movies/` folder — `ImportExtractionPolicy.ROOT_DIRECTORIES` already accepts `movies` (`ImportExtractionPolicy.kt:114`) so the files *are* copied — and decode depends on DirectShow/DivX (`divxdecoder.dll`, accepted in `ROOT_FILES` `:107-111` and `STANDARD_DLLS` `ManagedClientImporter.kt:728-730`). Under Wine the DirectShow path is the classic stuck-at-black-screen failure; the launch-topology machinery that would detect it is S7's per-flavor login topology (port plan S7.5, `Build5875LoginTopology` descendants).
- **Work items:**
  1. Per-flavor enforced keys (same `ClientFlavor`-parameterized `enforcedKeys` from H2-4): for tbc and wotlk add `EnforcedLine("movie", "0")` (already generic) and `EnforcedLine("expandMovie", "0")`; verify the exact cvar name per client on the qualified binary (inspect the client's own `Config.wtf` output after one controlled first run — if 8606/12340 uses a different key name, record it in `flavor.json` `runtime.config_pins`; the register's naming is the plan of record until measured). Keep the write-or-delete semantics so stale `movie "1"` lines from a donor tree are removed, matching the `ManagedConfigPolicy.kt:8-16` contract.
  2. Seed both keys in the per-flavor first-login `SAFE_CONFIG` (`ClientGenerationStore.kt:237-256` — new wotlk/tbc seed blocks or a parameterized template).
  3. Topology skip step: the S7 auto-login topology per flavor inserts a "cinematic may be playing" first probe (fullscreen render window, no login window class) before the login-window probe; if the probe times out in that state, the session record gets `state=stuck-in-movie` and the user copy says "reset the client and try again — intro movies are disabled automatically" (the supervisor already persists `last-session.json`, consumed at `DiagnosticsScreen.kt:87-93`).
  4. Optional belt (Decision Log): exclude the `movies` root directory from the TBC/WotLK copy (`ImportExtractionPolicy.ROOT_DIRECTORIES` is a hard-coded set today at `:112-115`; make it flavor-data) — saves hundreds of MB and removes the codec dependency entirely; if excluded, the topology step is belt-and-suspenders only. Prefer the exclusion if measured asset sizes confirm.
- **Upstream dependency:** none.
- **Verification:** Device (S7, per flavor): fresh prefix + fresh generation first launch reaches the login window with zero cinematic frames (topology probe log); a forced case (movie keys stripped post-prepare) yields `stuck-in-movie` in `last-session.json` and the honest copy; `ConfigWtfCodec` merge test proves write-or-delete of `expandMovie`.
- **Effort:** M
- **Depends on:** H2-4 (per-flavor enforced-key plumbing lands there)
- **Owner stage:** S7

### H2-7 — Runtime artifacts: Cache/WDB growth, Errors, Screenshots in storage accounting

- **Goal:** Every directory the flavor client grows at runtime — `Cache/` (WDB client caches), `Errors/` (crash texts+dumps), `Screenshots/` — is accounted in the storage picture, tolerated by Save & Exit (no quarantine or size surprise), and crash texts are harvestable into diagnostics with redaction.
- **Current state:** The importer drops unknown root directories, and `ROOT_DIRECTORIES` (`ImportExtractionPolicy.kt:112-115`) contains `data, wtf, interface, fonts, screenshots, documentation, errors, movies, music, utilities` — **no `cache`, no `logs`** — so a donor tree's `Cache/` is excluded (good), and the client recreates `Cache/WDB` on first run inside the writable managed root (untracked). Nothing measures it: `ImportStoragePlanner` counts only import-time roots (`ImportStoragePlanner.kt:40-43`), and the Settings per-flavor usage display is S9 scope. `DiagnosticsScreen` lists `Errors/*` per generation (`DiagnosticsScreen.kt:97-110`, capped at `MAX_CLIENT_CRASH_DUMP_ROWS`) but reads nothing from `Cache/` or `Screenshots/`, and `SupportBundleExporter` deliberately carries no client files (`SupportBundleExporter.kt:18`). WDB rebuild after wipe is the first-run cost H4-8 (§2.4) documents — this gap owns the *accounting and harvest* half.
- **Work items:**
  1. Storage accounting: add a runtime-usage walker beside `ImportStoragePlanner.directoryBytes` (`ImportStoragePlanner.kt:40-43`) reporting per flavor {client generation, data generation, prefix, `Cache/`, `Errors/`, `Screenshots/`}, bounded like `InGameSettingsFiles.MAX_TREE_ENTRIES` (`InGameSettingsFiles.kt:19`); expose it in the S9 per-flavor usage screen (S9 owns the screen; this gap delivers the walker + the three-directory breakdown).
  2. Budget realism: measure `cacheWdbGrowthBytes` per flavor (wipe-cache cold start → 30 min play → measure) into `flavor.json` storage so the P3-2 budget review and S9 planner math include runtime growth, not just import-time bytes.
  3. Save & Exit tolerance: supervisor test (androidTest, beside `client/ClientRuntimeLifecycleTest.kt`): kill the client mid-WDB-write, Save & Exit completes, next boot rebuilds `Cache/WDB` without quarantine — WDB files are not in the publish manifest (verify against the manifest scope at `ClientGenerationStore.kt:111`) and the journal records a rebuild, not an error.
  4. Crash-text harvest with redaction: extend `SupportBundleExporter` with a bounded `client-errors.json`: newest N `Errors/*.txt` (reuse the `DiagnosticsScreen.kt:97-110` discovery), first 4 KiB per file through the existing `SecretRedactor` (`SupportBundleExporter.kt:26,:55`) under the `MAX_ENTRY_CHARS`/`MAX_BUNDLE_BYTES` caps (`:107-108`); `*.dmp` stays excluded (bundle remains text-only per `:18`). `SupportBundleTest.kt` gains the fixture + canary checks (stack paths, account names must not leak).
- **Upstream dependency:** none.
- **Verification:** Device: WDB growth measured into `flavor.json`; forced client crash produces `Errors/` text (+ `.dmp`), the Diagnostics pane lists it (existing path), and an exported bundle contains the redacted text entry and no `.dmp`; Save & Exit after a mid-write kill completes clean.
- **Effort:** M
- **Depends on:** H4-8 (§2.4 — the Cache/WDB path map this accounting reports on)
- **Owner stage:** S7

### H4-7 — Per-flavor crash-artifact inventory + per-flavor runtime-builds.json in bundles

- **Goal:** Crash artifacts for each flavor land in known places, are named in the support bundle's `runtime-builds.json` with the *flavor's* build identities (not the vanilla hard-codes), and bundle consumers can tell which flavor produced the evidence.
- **Current state:** `SupportBundleExporter` writes `runtime-builds.json` with single-lane identities: `"server": "o09-cmangos-c096bada-nobots-v1"`, `wine`, `renderer` (`SupportBundleExporter.kt:28-38`) — and `ClientRuntime.kt:85` hard-codes `RUNTIME_BUILD_ID = "kron4ek-wine-11.14-amd64-wow64-vanilla"`, so the bundle literally says "vanilla" regardless of session flavor. Error-text discovery exists only in the Diagnostics UI (`DiagnosticsScreen.kt:97-110`); the bundle has no flavor field (`device.json` at `:39-41` is flavor-blind). The per-flavor artifact *inventory* (which files each build produces: `Crash.txt`, `*.dmp`, `report.txt`) is unrecorded.
- **Work items:**
  1. Flavor-aware `runtime-builds.json`: `SupportBundleExporter.export` gains the active session's `ClientFlavor` (from the session/supervisor record the bundle already reads at `SupportBundleExporter.kt:43-44`) and fills the schema-2 shape **owned by §2.4 H4R2-10** (`{"schema":2,"flavors":[…]}` with per-flavor server/database/wine/renderer and the active session's flavor marked — this gap consumes that schema by reference and never re-specifies it): the session's `clientId`/`clientBuild` land in the active flavor's entry (and on `device.json`), with per-flavor server/renderer ids resolved from the session — never the bare constants at `ClientRuntime.kt:85-95` (those constants get flavor-parameterized by the S1/S2 lockfile work; the bundle must read the *session's* identity).
  2. Artifact inventory entry: `crash-artifacts.json` listing per generation: file name, size, mtime, and the first line of each `Errors/*.txt` (kind discriminator: `#132`-style assert vs `ERROR #0` dialog text), `.dmp` by name only; bounded like H2-7's harvest.
  3. Per-flavor inventory: during each flavor's S7 soak, one induced crash archives the produced file set into the Decision Log (the "per-flavor log inventory" the register row names) and fixes `flavor.json` `runtime.crash_artifacts` so future diagnostics know what to look for.
  4. Tests: `SupportBundleTest.kt` — flavor field present, server id is the session's, `crash-artifacts.json` passes the `SAFE_ENTRY` regex (`SupportBundleExporter.kt:58`) and stays inside `MAX_BUNDLE_BYTES` (`:108`).
- **Upstream dependency:** none.
- **Verification:** Bundle exported after a TBC session shows `flavor: "tbc"` + the TBC server build id; after a WotLK crash it lists `Errors/Crash.txt` + `.dmp` names; `SupportBundleTest` green; the S10 bundle-budget check (register P2-9) still passes with the new entries.
- **Effort:** M
- **Depends on:** H2-7 (harvest + redaction machinery)
- **Owner stage:** S7

### J2 (J2-7) — Packet-conformance error surfaces: per-flavor soak + ERROR-dialog-as-failure

- **Goal:** Struct-mismatch packet errors surface as *treated* failures, not silent weirdness: per-flavor conformance soaks archive each client build's error signature (2.4.3 `ERROR #0` dialogs, 3.3.5 `#132` asserts), and the supervisor classifies a client ERROR dialog as a session failure with the artifact captured.
- **Current state:** Nothing in the app detects client error dialogs: the supervisor session record (`wine/last-session.json`, consumed at `DiagnosticsScreen.kt:87-93`) tracks only `state`/`cleanExit`; the auto-login topology (`SinglePlayerAutoLogin`) interacts with the login window only. The artifact side arrives with H2-7/H4-7. The desktop harness already reads world-side liveness (`tools/rp_harness/probe_world_auth.py`, `tests/test_g1_realmd_liveness.py`), so a wire-level conformance probe has a home. Register J2-7 (§2.1, S10, P2) is the **owner of record** for this token (§2.1's `### J2-7` — the S10 conformance gate + supervisor failure mapping, server-systems.md:824); this treatment is the client-surface cross-reference half (S7 error-signature groundwork) paired with H4-7's artifacts — roadmap §0 ID-collision conventions, J2-7/J2 bullet.
- **Work items:**
  1. Conformance soak per flavor (S10 device matrix, port plan §S10.1): scripted play — login, world entry, one teleport, one Save & Exit, plus one deliberately mismatched session (flavor-X client vs flavor-Y realm, from the S8 auth-matrix fixtures) — capturing client-side error surface each time: screenshot + `Errors/` text + session log. Archive as the per-flavor "known error signatures" table in `flavor.json` (`runtime.error_signatures`: dialog title/class patterns per build).
  2. Supervisor classification: extend the client-watch logic that records `last-session.json` (the `ClientRuntimeService` component; cf. the TZ note at `ClientRuntimeService.kt:648`) to detect a modal dialog matching `runtime.error_signatures`; on match, mark the session failed with reason `client-error-dialog` and route the newest `Errors/*.txt` into H2-7's harvest so the bundle carries the mismatch text.
  3. Wire-level guard: parameterize the opcode/frame pins in `tools/rp_harness/probe_world_auth.py` per flavor (register C3: `SMSG_AUTH_CHALLENGE` = 0x1EC in all three branches — assert that constant, fail loud on any other), so server-side struct drift is caught by the harness before a client ever shows `#132`.
  4. Copy: the failure card names the flavor and says the session hit a client/server mismatch and to re-run data preparation (manifest gates P3-1/H2-10 are the usual true cause).
- **Upstream dependency:** none.
- **Verification:** Supervisor test: replay/inject a recorded error-dialog signature → session marked `client-error-dialog`, artifact harvested, bundle contains redacted text. Harness: `probe_world_auth.py` against all three flavors' realmd asserts 0x1EC and rejects a doctored variant. S10 matrix evidence archived per flavor.
- **Effort:** M
- **Depends on:** H2-7 (artifact harvest), H4-7 (signature/inventory home)
- **Owner stage:** S7 (error-signature groundwork with the S7 soaks — capture begins per roadmap wave S7) / S10 (conformance gate) — mirrors the split annotations H3R2-7 (§2.3) and H5-9/P1 (§2.1) carry.

### H2-8 — Non-enUS clients: record observedLocale, restrict-vs-accept decision, negative fixture

- **Goal:** The app records the observed locale of every import, applies the product decision for non-enUS clients (restrict-enUS or accept-any-with-localized-DBC copy) as a gate, and a deDE-shaped fixture import is tested either way.
- **Current state:** Locale handling is an unconditional inference: `SafClientScanner.kt:148` appends `VAL-05: FLAT_ENGLISH_LOCALE_INFERRED` on *every* scan and `ArchiveClientScanner.verifyExtracted:126` hard-codes the same string for the RAR lane — it is never computed from the tree. The only real locale read is `ArchiveClientScanner.readConfigLocale` (`ArchiveClientScanner.kt:188-196`, parses `SET locale` from `WTF/Config.wtf`), and its result feeds display only (`ManagedClientImporter.kt:244`). Publish identity hard-codes `"locale": "enUS-flat-classic"` (`ManagedClientImporter.kt:585`). `ClientFlavor` has no locale field at all (`ClientFlavor.kt:16-55`). Register decision point 5 (§4) defers restrict-vs-accept to S5.
- **Work items:**
  1. Compute, don't infer: derive observedLocale from (a) `WTF/Config.wtf` `SET locale` (existing reader), (b) the `Data/<locale>/` directories present (H2-2's scan), (c) flat-tree fallback: no locale subdirs + enUS-only MPQ names ⇒ "enUS". Replace both hard-coded `VAL-05` strings (`SafClientScanner.kt:148`, `ArchiveClientScanner.kt:126`) with the computed value and a distinct "locale not determinable" message; keep `VAL-05` as the code family and pin wording in `SafClientScannerMessageTest.kt`.
  2. Decision-gate: implement the S5 product decision (register §4 item 5) as data — `flavor.json` per flavor gains `accepted_locales: ["enUS"]` or `["*"]`; the publish gate rejects non-accepted locales with an actionable `VAL-05` naming the observed locale and the accepted set. Default until decided: accept with warning (never strand an owner of a localized box; the "accept-any-with-localized-DBC copy" branch requires H2-11's DBC floors to key off the observed locale — note that coupling in the Decision Log).
  3. Record observedLocale in the publish identity: replace the hard-code at `ManagedClientImporter.kt:585` with the computed locale + flavor id (also fixes the misleading `-flat-classic` suffix for non-vanilla imports).
  4. Fixtures: `SyntheticClientArchives.kt` (`android/app/src/debug/java/com/pocketrealm/importer/`) gains a deDE-shaped tree (`Data/deDE/` MPQ names, `SET locale "deDE"`); negative fixture asserts the gate behavior chosen in step 2; positive fixture asserts the recorded identity.
- **Upstream dependency:** none (the register §4.5 product decision is a user input).
- **Verification:** JVM: enUS/deDE/undeterminable fixtures each produce the right VAL-05 text and publish identity; device: vanilla import behavior unchanged (invariant 1); the S5 evidence pack records the decision and its gate result.
- **Effort:** M
- **Depends on:** H2-2 (locale-subfolder scan provides the observation source)
- **Owner stage:** S5

### H2-10 — mmtile nav-generator version in manifest + consume-time check; adversarial tile swap

- **Goal:** A data generation's manifest records the navmesh format version its `MoveMapGen` emits, consumption re-verifies it app-side (the server-side check already exists), and swapping a `.mmtile`/`.mmap` from another flavor's generation is refused loudly instead of loading a mismatched navmesh.
- **Current state:** The pinned core already checks at load: `native/cmangos/src/game/MotionGenerators/MoveMap.cpp:242-245` (the `MMAP:loadMap` path) and `:319-322` (the `MMAP:loadGameObject` path) each compare `fileHeader.mmapVersion != MMAP_VERSION` against `MMAP_VERSION 8` (`MoveMapSharedDefines.h:26`) — two `mmapVersion` guards on the two nav-file load paths (both sites read this session; their error strings name the map-tile file and the gameobject file respectively, so no per-file-kind attribution is made here). The app-side manifest records *provenance* (four `libpocket_*.so` sha256s + `cmangosCommit`, `DataPreparationStore.generatorProvenance` at `DataPreparationStore.kt:354-362`; constant `CMANGOS_COMMIT` at `:425`) and the consumer gates `clientBuild`/`cmangosFamily` (`PreparedDataStore.kt:44-49`) — but **no field records the nav version**, `counts` (`DataPreparationStore.kt:264-267`) counts tiles without version, so a regenerated generation built by a re-pinned extractor with a bumped `MMAP_VERSION` is invisible until mangosd boot errors. Content swaps between generations are caught by the per-file sha256 chain (`PreparedDataStore.requireActive`, `:62-78`) and a re-sealed manifest by the `manifestSha256` seal (`:37-42`) — but no test exercises any of the swap paths.
- **Work items:**
  1. Manifest: in `DataPreparationStore.publish` (`:245-288`) parse the version field once from a produced `.mmap` header during the existing count walk and add `navVersion` to the manifest `counts`/`format` block; `PreparedDataStore.requireActiveEnvelope` (`:38-56`) asserts it equals the expected constant for the running family — the constant comes from `flavor.json` `extractors.nav_version` (per-flavor, because tbc/wotlk branches may bump `MMAP_VERSION`).
  2. Keep the server-side check authoritative and add to the S2 checklist: confirm the tbc/wotlk forks retain the `MoveMap.cpp:242/:319` guards when re-pinned.
  3. Adversarial tests (androidTest, `O11HostClientPreparationTest.kt` neighborhood): (a) overwrite one `.mmtile` with a synthetic header carrying `navVersion+1` and re-seal the manifest (modeling the strongest attacker) → `requireActiveEnvelope` fails naming `navVersion`; (b) swap a `.mmtile` between two generations without re-sealing → per-file sha256 mismatch at `requireActive` (`PreparedDataStore.kt:62-78`).
  4. Register `extractors.nav_version` per flavor when each extractor pin lands (S6 work item 1) so the check is data-driven.
- **Upstream dependency:** extractor sources from the per-flavor pinned forks (`puzzled-pancake/mangos-tbc|wotlk`, branch `pocket-realm`, pins registered at S2; per-branch `MMAP_VERSION` read from that tree's `MoveMapSharedDefines.h`).
- **Verification:** Both adversarial cases fail with the expected messages; genuine vanilla re-prep passes (invariant 1); the wotlk generation's manifest shows its measured `navVersion` and mangosd boots with zero `MMAP`-version mismatch log lines (within the S6 numeric error threshold).
- **Effort:** S
- **Depends on:** none (S2/S6 sequencing supplies the pins)
- **Owner stage:** S6

### H2-11 — Per-flavor required-DBC manifest (name + min rows + size band)

- **Goal:** Each flavor declares the DBC files its server requires with per-file minimum row counts and plausible size bands; preparation verifies them (not just a global count), and the consumer gate refuses a generation missing a required DBC — including WotLK-only `Vehicle`/`VehicleSeat` and the `gtBarberShopCost`/talent/lfg families.
- **Current state:** Verification is two flat counters: `MIN_DBC = 100` and `MIN_MAP_TILES = 100` applied at `DataPreparationStore.kt:44-45` (constants `:420-421`), re-checked as `counts.dbc >= 100 && counts.maps >= 100` with literals at `PreparedDataStore.kt:50-54`. Nothing is per-file: a WotLK extraction that dropped `Vehicle.dbc`/`VehicleSeat.dbc` (register G1-8's extractor floor), `gtBarberShopCost.dbc` (register H1-11), or the LFG DBCs still passes if ≥100 other DBCs exist, and mangosd then boots with error-spam or silently missing systems.
- **Work items:**
  1. Data: `schemas/flavor.json` per flavor gains `extractors.required_dbc`: `{ name, min_rows, size_band_bytes: [lo, hi] }` entries. WotLK must include `Vehicle`, `VehicleSeat`, `gtBarberShopCost`, the talent/gt family, `LfgDungeons`, and the taxi/map DBCs; TBC its talent/taxi set; vanilla's block is generated from the current extraction and frozen (G3-7 golden-data discipline). Floors are measured from one genuine extraction per flavor (register F2-9 style), never guessed.
  2. Verification: extend the post-`ad` step (`DataPreparationStore.kt:43-46`) to parse each required DBC's row count (first uint32 after the fixed DBC header) and check size bands in the same walk that currently only counts files; failures name the DBC: `required DBC Vehicle.dbc missing / below <N> rows`.
  3. Consumer gate: extend the manifest `counts` block (`DataPreparationStore.kt:265`) with per-DBC rows and enforce the floor in `PreparedDataStore.requireActiveEnvelope` (`:44-56`) against `flavor.json`, replacing the `100` literals — so a generation built before a floor raise is refused at the boot gate.
  4. `tools/check_flavor.py` validates the block shape (names non-empty, floors > 0, size bands ordered) so a half-filled manifest cannot ship.
  5. Cross-check with reality: the S6 boot threshold (port plan S6 End State) includes zero "missing DBC" mangosd log lines.
- **Upstream dependency:** none beyond the S2/S6 pins (floors measured from `puzzled-pancake/mangos-tbc|wotlk` extractor output on the qualified clients).
- **Verification:** Negative: synthetic wotlk generation missing `Vehicle.dbc` fails preparation naming it; positive: genuine wotlk prep passes with the block populated; `DataPreparationStoreTest` covers both directions; vanilla behavior byte-equivalent (invariant 1).
- **Effort:** M
- **Depends on:** H2-8 (if accept-any-locale is chosen, floors must key off the locale copy), P3-1 (startup invariant that the block exists)
- **Owner stage:** S6

### F1-8 — Per-flavor DBC/extractor content-parity inputs (derivation + provenance for the H2-11 manifest)

- **Goal:** The *data behind* H2-11's manifest exists per flavor with full provenance — required-DBC list, floor numbers, and extractor outputs derived from the pinned flavor trees and recorded in `flavor.json` citing the same commits as the extractor lockfiles; no hand-maintained parallel truth.
- **Current state:** The only DBC-adjacent provenance today is `generatorProvenance` (`DataPreparationStore.kt:354-362`: the four `libpocket_*.so` sha256s + `CMANGOS_COMMIT` hard-code at `:425`; the S1 provenance-split repair at `tools/build_o09_realm_runtime.py:37` covers the vanilla commit per port plan §S1.1, tbc/wotlk get their own when S1 parameterizes). Content-drift risk is per-branch: `Vehicle`/`VehicleSeat` exist only in WotLK (register G1-8), `gtBarberShopCost` only where the barber exists (register H1-11), talent DBC row counts differ per expansion, and each branch's extractor output needs its own baseline rather than a vanilla copy (the register's F1-1 family of "classic-verbatim" drift concerns).
- **Work items:**
  1. Baseline capture tool: `tools/capture_dbc_floors.py` — runs the flavor's `libpocket_ad.so` over a qualified client (or a checked-in minimal extraction fixture) and emits the per-DBC {rows, size} JSON used to write `flavor.json`'s `required_dbc` block (H2-11 item 1). Store the raw baseline under `schemas/seed-augment/` (directory exists) with client identity + extractor .so sha256 so every floor number is re-derivable.
  2. Cross-file commit check: when `tools/build_o11_extractors.py` is flavor-parameterized (port plan §S1.5), each flavor's `o11-extractor-lockfile*.json` carries the branch commit; extend `tools/check_flavor.py` to assert `flavor.json`'s `extractors.commit` equals the lockfile's — divergence fails loud (the S1 divergence-CI discipline applied to this data).
  3. WotLK-only families asserted on day one: `Vehicle`/`VehicleSeat` floors (G1-8 links these to `npc_spellclick_spells` DB rows — DB-side floors stay §2.1's, cross-referenced) and `gtBarberShopCost` (H1-11), so the manifest is complete before any system silently no-ops.
  4. Vanilla freeze: `FlavorManifestParityTest.kt` pins vanilla's block against the committed baseline so parity work can never drift it (§2.7 G3-class guard).
- **Upstream dependency:** extractor sources per flavor as in H2-10 (`puzzled-pancake/mangos-tbc|wotlk`, branch `pocket-realm`, pins TBD at S2 registration); DBC content from the user-supplied qualified clients.
- **Verification:** `tools/capture_dbc_floors.py` regenerates each flavor's block bit-identically from its recorded baseline; `check_flavor.py` fails a doctored `flavor.json` whose extractor commit diverges from the lockfile; `FlavorManifestParityTest` green.
- **Effort:** M
- **Depends on:** H2-11 (consumes this data), G1-8 (§2.1 — Vehicle DBC floor coordination), H1-11 (§2.1 — gtBarberShopCost coordination)
- **Owner stage:** S6

### H2-12 — DXVK shader-cache preserved across prefix/generation regeneration

- **Goal:** The DXVK state cache and Mesa shader cache survive renderer/runtime generation changes (re-pin, driver switch, prefix schema bump), first-session stutter is covered by honest copy, and soaks warm the cache before measuring performance.
- **Current state:** Caches are per-generation: `ARM_CACHE_DIRECTORY = "cache"` under the generation root (`WineRuntimeStore.kt:1938`; generation root built at `:776`), with per-renderer subdirectories `dxvk/mesa/xdg` (`armCacheSubdirectories`, `:1110-1115`) and the session env pointing `DXVK_STATE_CACHE_PATH`/`MESA_SHADER_CACHE_DIR` there (`ArmSessionEnvironment.kt:72-79`). `pruneInactiveArmGenerations` (`WineRuntimeStore.kt:1118+`) deletes old generation directories — cache included — when a new graphics generation activates, and nothing migrates state across a DXVK/driver tuple change: every driver re-pin or prefix regeneration pays full pipeline recompilation on the Adreno (first-session stutter), and FPS numbers taken cold are wrong (polluting H2-4's matrix and P1-10's soaks).
- **Work items:**
  1. Shared cache store: hoist `dxvk`/`mesa` caches to a per-renderer-kind shared root beside the generations directory (e.g. `noBackupFilesDir/client/shader-cache/<rendererKind>/`), and point `DXVK_STATE_CACHE_PATH`/`MESA_SHADER_CACHE_DIR` (`ArmSessionEnvironment.kt:73-74`) at it. The prepare-time attestation that cache subdirs live inside the tuple (`WineRuntimeStore.kt:1100-1114`) gets a carve-out: the shared root is bound and attested at prepare time but not pruned with the tuple.
  2. Hygiene: DXVK tolerates stale state-cache entries (worst case recompiles), but bound the store anyway — LRU prune above `flavor.json` `runtime.shader_cache_max_bytes`, membership validated (no symlinks, canonical parent — the discipline at `WineRuntimeStore.kt:1101-1106`).
  3. Copy + soak: the renderer-change card gets one honest stutter line (S7 UX); `tools/capture_rp6.py` runs and the S10 soaks add a 5-minute warm-up before frame-time sampling.
  4. Tests: `WineRuntimeGenerationIdentityTest.kt` (generation change retains the shared cache), `UserVulkanGenerationIdentityTest.kt` (user-driver lane), pruning cap test.
- **Upstream dependency:** none (a documented property of the vendored DXVK 2.4.1/1.10.3 builds, `RendererPackageCatalog.kt:37-38`).
- **Verification:** Device: run a WotLK session 10 min (cache warms), switch Vulkan driver, relaunch — the `DXVK_LOG_PATH` log shows state-cache reuse and frame-times settle in the first minute, not the fifth; prune test keeps the store under cap; generation-retirement test proves tuple deletion leaves the shared root intact.
- **Effort:** S
- **Depends on:** H2-4 (qualification matrix must run on warm caches — same-lane ordering)
- **Owner stage:** S7

### H2-13 — Per-flavor render QA checklist in the capture_rp6 scenario

- **Goal:** The S7 evidence run per flavor includes a named render-QA checklist — fog ring, water, shadows, weather, zone-border culling — captured and archived, so "renders correctly" is a checked artifact rather than an impression.
- **Current state:** `tools/capture_rp6.py` captures device state (display, memory, thermal, battery parsers at `tools/capture_rp6.py:184-402`) as the port-plan §4.5 evidence tool, but it has no scenario/checklist concept and no per-flavor visual probes; the S7 soak is a 2-hour play session with "graphics/audio stable" as the criterion (port plan S7 End State). Nothing in the repo enumerates per-flavor rendering risk items — and the register names exactly the five areas where 2.4.3/3.3.5 under DXVK differ from 1.12 (weather/sky shaders, water rendering, shadow levels, fog distances at higher farclip, and culling at zone borders in expanded worlds).
- **Work items:**
  1. Add `tools/render_qa_checklist.py` (or a `--render-qa` mode in `capture_rp6.py`): a scripted per-flavor run — teleport to a fixed per-flavor location set (vanilla control: Elwynn/Wetlands; TBC: Hellfire/ Zangarmarsh; WotLK: Dalaran/Grizzly Hills), fixed in-game time-of-day and weather via GM commands through the existing relay/harness (`tools/rp_harness/session.py`), capturing N screenshots at each station plus the DXVK log segment.
  2. The checklist itself is data: `docs/render-qa-{tbc,wotlk}.md` (or a YAML the tool consumes) enumerating the five register items with pass criteria ("fog ring visible at farclip X without banding artifacts", "water renders with animation at ~N fps", "shadow level 2 stable 10 min", "weather particles render", "no holes/popping at the 530/571 zone borders") — each checked by a human reviewer against the archived screenshots; the tool records the checklist version + verdicts into the evidence JSON.
  3. Fixed stations give comparability across driver/DXVK changes (H2-4 matrix reuse): the same stations re-run when a Vulkan driver or DXVK version changes, so regression is visible.
  4. Wire into the S7 exit checklist: the flavor's evidence pack must include the checklist JSON + screenshots; R5/R6 review them (port plan §4.1 charters).
- **Upstream dependency:** none.
- **Verification:** One full checklist run archived per flavor during S7 (RA then RB); a re-run after an intentional DXVK downgrade shows the tooling detects the visual delta (proves the stations are sensitive); the JSON verdicts parse and cite checklist version.
- **Effort:** S
- **Depends on:** none (uses S7 topology/relay machinery)
- **Owner stage:** S7

### H2-14 — Per-flavor root DLL set (fmod.dll suspicious on TBC/WotLK) + WotLK sound smoke

- **Goal:** The root-DLL allow/warn/deny sets are per-flavor data — vanilla's `fmod.dll` allowance must not silently bless a TBC/WotLK `fmod.dll` (those builds link FMOD statically, so a root `fmod.dll` there is a foreign DLL, i.e. a classic injection vector) — and a WotLK sound smoke proves audio end-to-end.
- **Current state:** Two hard-coded sets exist: `SafClientScanner.STANDARD_ROOT_DLLS = {dbghelp, divxdecoder, fmod, ijl15, scan, unicows}` (`SafClientScanner.kt:175`, drives the VAL-10 unrecognized-DLL *warning*) and `ManagedClientImporter.STANDARD_DLLS` (`ManagedClientImporter.kt:728-730`, identical set, drives what the safe-runtime copy *keeps* at `excludeFromSafeRuntime:686`). Both are vanilla-truth: 1.12 ships `fmod.dll` (FMOD 3); 2.4.3/3.3.5 ship `fmod.dll` too in most distributions — but per the register, FMOD Ex is statically linked in those builds, so a *present* `fmod.dll` is not part of the known-good set and should at minimum warn differently and by default be denied in the copy. Sound smoke: audio lane exists (`AlsaServerProtocolAcceptanceTest.kt` is server-side; client-side audio keys are the `SoundMixRate`/`SoundBufferSize`/`SoundSoftwareChannels` enforcements at `ManagedConfigPolicy.kt:72-81` and the `winealsa.drv=d` disable under audio-off at `ClientRuntime.kt:166-170`), but no per-flavor client sound verification exists.
- **Work items:**
  1. Move both DLL sets into `ClientFlavor`/`flavor.json` (`runtime.root_dlls: {allowed: [...], denied: [...]}`): vanilla = the current six; tbc/wotlk = the qualified client's measured root set minus `fmod.dll` (which moves to `denied` for those flavors pending a measured reason — if the genuine 8606/12340 trees *do* ship a root `fmod.dll`, it is still flagged: warn + copy-deny by default, Decision Log records the verdict either way).
  2. Consume: `SafClientScanner.kt:151-154` resolves the set from the matched flavor (foreign clients keep the generic warning), and `excludeFromSafeRuntime` (`ManagedClientImporter.kt:682-687`) takes the flavor's `allowed` set — a TBC `fmod.dll` is journaled as skipped with an explicit reason naming the policy.
  3. WotLK sound smoke (S7): device run asserting (a) audio-on launch emits no `winealsa`-disabled override (`ClientRuntime.kt:166-170` builds the override string — assert the dxvk+audioOn path), (b) the client's sound cvars survive a round-trip (`SoundMixRate 48000` etc. enforced at `ManagedConfigPolicy.kt:72-81` — the WotLK sound cvar family differs per port plan S7.2, so the WotLK catalog re-derives which keys are live), (c) a captured 30 s in-game segment has non-silent audio (measured on the harness host from the Alsa server side or manual QA checklist).
  4. VAL-10 copy per flavor: "unrecognized root DLLs" message names the flavor's expected set so the user can act.
- **Upstream dependency:** none (sets measured from the user-supplied qualified clients).
- **Verification:** JVM: vanilla six-DLL tree unchanged (invariant 1); TBC tree + `fmod.dll` → VAL-10 warning names it and the copy journal shows it skipped; genuine TBC/WotLK trees import clean. Device: WotLK sound smoke checklist green in the S7 pack.
- **Effort:** S
- **Depends on:** H2R2-3 (same per-flavor qualification pass records the measured root set)
- **Owner stage:** S5

### P3-1 — Qualification invariant enforced at startup, not first import

- **Goal:** If any flavor is marked `qualified=true`, its required data (non-null MPQ set, exe hashes, storage budgets, and the S5/S6 additions) is validated at app startup and by CI — a half-qualified flavor fails closed immediately with a named field, never at first import.
- **Current state:** The invariant is enforced lazily and unevenly: `ImportStoragePlanner` fails at planner construction via `requireNotNull(flavor.extractedEstimateBytes)` (`ImportStoragePlanner.kt:73-88`), `ImportLimits.maxTotalBytes` likewise (`ImportModel.kt:89-94`), and the storage-prep consumer via `ClientFlavors.qualified().single()` (`PreparedDataStore.kt:43`, `DataPreparationStore.kt:280`, `ImportModel.kt:75-82`) — i.e., a user discovers a half-qualified flavor mid-import with a crash-shaped failure. `ClientFlavors.qualified()` (`ClientFlavor.kt:145`) filters only on the boolean. `schemas/flavor.json` carries `supported_client_builds: []` with a note that the flag gates (verified in the file). `tools/check_flavor.py` exists but its coverage of "qualified ⇒ all fields present" needs to be the authoritative CI gate (parity with the in-app check pinned by `FlavorManifestParityTest.kt`).
- **Work items:**
  1. Single validator: add `ClientFlavors.requireQualificationInvariants()` in `ClientFlavor.kt` — for each qualified flavor assert non-null: `requiredMpqPaths` (H2-2), `knownExeSha256` (+ MD5, H2R2-3), `extractedEstimateBytes`, `maxTotalBytes` (and after their landing: `required_dbc`/`nav_version`, accepted-locale policy, root-DLL set). Return the first violation as a named message (`flavor 'tbc' is qualified without a measured storage budget` style, matching the existing copy at `ImportStoragePlanner.kt:76`).
  2. Call it at process start: the app's startup path (where `ClientRuntimeProvider`/`StorageRoots` initialize) invokes it and crashes/flags loudly in debug, and disables the flavor with a diagnostics banner in release — a qualified-but-incomplete flavor must never reach the import UI silently.
  3. CI: `tools/check_flavor.py` implements the identical rule over `schemas/flavor.json` and `FlavorManifestParityTest.kt` pins the Kotlin registry to the JSON so the two validators cannot drift.
  4. Migrate the lazy checks to delegate to the validator (keep the existing messages — they are pinned by `ImportStoragePlannerTest.kt`), so there is one definition of "qualified".
- **Upstream dependency:** none.
- **Verification:** Unit test: flip `TBC.qualified=true` in a test registry without budgets → startup validator rejects naming `extractedEstimateBytes`; with all fields set it passes; `check_flavor.py` run in CI proves the JSON-side rule; `FlavorManifestParityTest` fails a Kotlin registry that diverges from JSON.
- **Effort:** S
- **Depends on:** none (S0 registry exists)
- **Owner stage:** S5

### P3-2 — Per-flavor storage budgets in the planner (~25 GiB TBC / ~35 GiB WotLK)

- **Goal:** `maxTotalBytes` and `extractedEstimateBytes` carry measured per-flavor values (register: ~25 GiB TBC, ~35 GiB WotLK total; ~9-10 GB / ~16-17 GB extracted) and every consumer that reads a budget resolves it through the flavor, verified against planner arithmetic.
- **Current state:** The plumbing is flavor-shaped already: `ClientFlavor.extractedEstimateBytes`/`maxTotalBytes` exist with vanilla 4 GiB/20 GiB (`ClientFlavor.kt:80-81`), TBC/WotLK are `null` (`:108-109`, `:136-137`), `flavor.json` storage blocks carry the same values, `ImportLimits.maxTotalBytes` defaults through `qualifiedMaxTotalBytes()` (`ImportModel.kt:61,:89-94`), and `ImportStoragePlanner` defaults through `flavorExtractedEstimate()` (`ImportStoragePlanner.kt:15,:73-88`). What is missing is only the *values* and the verification that the register's targets hold up against the planner math (source + staged archive + scratch + extracted + database + wine + snapshot + 20 % margin, `ImportStoragePlanner.calculate` at `:46-63`) on real RP6 storage.
- **Work items:**
  1. Measure during S5/S6 (register rows say S5/S6): extracted tree sizes for genuine 8606/12340 trees; set `extractedEstimateBytes` and `maxTotalBytes` per flavor in `ClientFlavor.kt` + `flavor.json` (initial register targets ~25/35 GiB total; the WotLK total must additionally absorb the data-generation cost — the mmaps size measurement from P3-6 feeds this; revisit after measurement).
  2. Re-derive the planner check: `ImportStoragePlannerTest.kt` gains per-flavor cases — a WotLK import of ~16-17 GiB extracted + staged archive must produce `canProceed`/`lowHeadroom` verdicts consistent with a 12x-gen RP6's free storage; the `lowHeadroom` margin (`ImportModel.kt:114`, +2 GiB) is re-reviewed by R4 against WotLK-scale staging (port plan S5 End State names this review).
  3. `maxFileBytes` check: 8 GiB default (`ImportModel.kt:62`) comfortably covers the ~1.2 GB `lichking.mpq` (port plan S5.7) — assert that in the same test so a future data change cannot regress it silently.
  4. Keep the fail-loud rule: a flavor qualified with null budgets still trips P3-1's startup validator (this gap only fills the numbers).
- **Upstream dependency:** none.
- **Verification:** `ImportStoragePlannerTest` per-flavor budget cases green; device: a TBC and a WotLK import complete with the planner's `requiredBytes` within +15 % of observed post-import usage (measured in the S5/S6 evidence packs); `flavor.json`/`ClientFlavor` values equal (parity test).
- **Effort:** S
- **Depends on:** P3-1 (validator exists to catch nulls), P1-5 (the measurement campaign that produces the numbers)
- **Owner stage:** S5

### P1-5 — Measured per-flavor storage reality vs planner math

- **Goal:** The register's budget targets are not guesses: a measured campaign records real extracted sizes (~9-10 GB TBC / ~16-17 GB WotLK), data-generation sizes, and staging peaks per flavor, and the planner constants are re-derived from those numbers with the evidence archived.
- **Current state:** No TBC/WotLK sizes exist anywhere in the repo (both budget fields null, `ClientFlavor.kt:108-109,:136-137`; `flavor.json` storage blocks unmeasured for those flavors). The measurement surfaces that exist: `ImportBenchmark` journal records (`ImportModel.kt:29-46` — `mmapMaps`/`mmapThreads`/stage durations, persisted by the importer), `DataPreparationStore` checkpoints byte-count the mmaps stage (`:167,:173`), and `ImportStoragePlanner.plan` computes live `allocatableBytes` (`:34-35`). None of these are aggregated into a per-flavor storage-reality report.
- **Work items:**
  1. Campaign script (S5/S6 device runs): import + prep each flavor once on RP6, then dump {source bytes, staged bytes, scratch peak, extracted bytes, data-generation bytes (dbc/maps/vmaps/mmaps split from the checkpoint rows), peak free-storage delta} into `docs/measured-storage-{tbc,wotlk}.json` committed with the stage evidence.
  2. Re-derive: `extractedEstimateBytes`, `maxTotalBytes` (P3-2 item 1) and the WotLK `minFreeStorage` headroom from the measured peaks (not the register's estimates — register numbers are the prior, measurement is the truth; if they disagree, the Decision Log records why).
  3. Keep the numbers honest over time: `ImportBenchmark` rows already land in the journal — add a CI-side assertion (or a check in `tools/check_flavor.py`) that `flavor.json` budgets are ≥ the latest committed measurement, so a content update that grows the client cannot silently invalidate the budget.
  4. Feed S9: the per-flavor usage totals the S9 coexistence screen shows (port plan §S9.2) use these same constants — one source of truth.
- **Upstream dependency:** none (device windows per port plan §8.2 are external inputs).
- **Verification:** `docs/measured-storage-*.json` committed for both flavors with the six named series; `flavor.json` budgets re-derived from them (diff shown in the Decision Log); the ≥ assertion in CI green; the S5/S6 evidence packs reference the JSON.
- **Effort:** M
- **Depends on:** P3-2 (consumer of the numbers), P3-6 (mmaps size component for WotLK)
- **Owner stage:** S6

### P3-3 — maxFiles/maxEntries vs 150-200k-file extracted trees

- **Goal:** The importer's file/entry caps accept a real WotLK client tree (register: 150-200k files once extracted under SAF inventory) — raised as flavor data or replaced by streaming — without weakening the zip-bomb protection the caps exist for.
- **Current state:** Caps are hard-coded in `ImportLimits`: `maxFiles = 100_000`, `maxEntries = 110_000` (`ImportModel.kt:57-58`), enforced in `ImportExtractionPolicy.classify` (`ImportExtractionPolicy.kt:81,:87-89`) and in the RAR header walk (`RarArchiveSource.kt`, `entries.size > 110_000` guard). `flavor.json`'s storage block already carries `max_files`/`max_entries` fields (vanilla 100000/110000) but the Kotlin side does not read them — they are documentation, not data. A genuine WotLK tree at ~150-200k files exceeds both caps and would fail VAL-08 mid-import. The SAF lane inventories the full tree (register P3-3 note), so the cap bites hardest exactly where the file count is largest.
- **Work items:**
  1. Route the caps through the flavor: `ImportLimits` gains the flavor's `maxFiles`/`maxEntries` from `flavor.json` (the fields exist; make `ImportModel.kt:57-58` defaults derive like `maxTotalBytes` already does at `:61`), with `tools/check_flavor.py` enforcing `maxEntries >= maxFiles` (mirroring the `init` require at `:67-68`).
  2. Set the numbers from measurement, not the register's guess: count the genuine 8606/12340 trees at S5 qualification (file + entry count) and set the flavor values at measured +25 % headroom; record in the measured-storage JSON (P1-5).
  3. Keep the anti-zip-bomb property: the caps exist to bound inventory materialization (`ArchiveClientScanner.locate` builds the full entry list; `ImportExtractionPolicy.classify` sorts it). Raise rather than remove, and bound memory by capping the *entry-list bytes* instead of only count if the WotLK count forces >250k entries (Decision Log records which knob moved).
  4. Update the RAR-lane literal (`RarArchiveSource.kt` `> 110_000`) to read the same limit — today it is a second hard-code that would drift.
  5. `minFiles = 20` (`ImportModel.kt:55`) stays: a WotLK tree far exceeds it; no change needed.
- **Upstream dependency:** none.
- **Verification:** JVM: `ImportExtractionPolicy`/`ArchiveDetectionTest` cases at the new WotLK caps (a 199k-entry synthetic list classifies, a 999k-entry one still rejects VAL-08); device: genuine WotLK folder-lane import passes the DISCOVERING phase where it previously failed VAL-08; vanilla caps byte-identical (invariant 1).
- **Effort:** S
- **Depends on:** P3-2 (same flavor-data plumbing pattern), P1-5 (measurement campaign records the counts)
- **Owner stage:** S5

### P3-4 — Encrypted-entry rejection in every archive lane before staging

- **Goal:** A password-protected archive in *any* lane — ZIP, 7z, RAR, Inno — fails fast with the pinned VAL-13 copy before any staging-copy work is spent.
- **Current state:** The register says "currently Inno-lane only" — verified reality is better but still one lane short. The ZIP lane populates the encrypted flag (`ArchiveSources.listZipEntries`, `ArchiveSources.kt:131-149`, via `entry.generalPurposeBit.usesEncryption()`) and the RAR lane via `ArchiveEntry.isEncrypted` (`RarArchiveSource.kt:141-170`); `ManagedClientImporter.kt:151-153` rejects any encrypted entry with the pinned `VAL-13` message, and `ScratchArchiveExtractor.kt:29-31` rejects for the Inno scratch lane. **The 7z lane does not populate the flag**: `listSevenZipEntries` (`ArchiveSources.kt:152-160`) constructs `RawEntry(entry.name, entry.isDirectory, entry.size)` leaving `encrypted = false` (the data-class default, `ArchiveClientScanner.kt:19`), so a password-protected 7z sails past the check and dies later mid-extraction with a raw decoder error instead of the actionable copy.
- **Work items:**
  1. Populate the flag in `listSevenZipEntries` from the Commons Compress entry (`archive.nextEntry` returns `SevenZArchiveEntry`, which exposes its encryption state; the tukaani codec dependency for 7z streams is already present per `android/app/build.gradle.kts:2146`), then the existing `ManagedClientImporter.kt:151` check covers all three lanes with zero new rejection logic.
  2. Verify the rejection happens *before* the staging copy is counted against the user: the check already sits right after entry enumeration on the staged file (`ManagedClientImporter.kt:137-153`) — add a comment/test pinning that order so a future refactor doesn't move it past staging.
  3. Tests in `StagedArchiveTest.kt`/`ArchiveDetectionTest.kt`: an encrypted-member 7z fixture (single AES-encrypted entry) → `VAL-13` with the pinned wording; same fixture minus password → passes; ZIP/RAR variants stay green (regression).
  4. Copy check: the message says "re-pack it without a password (or extract on a PC and import the folder)" — keep per-flavor-neutral (this is a container property, not a flavor property; P3-9/P3-10 own flavor copy).
- **Upstream dependency:** none (commons-compress already in the dependency tree).
- **Verification:** The three lane fixtures each produce `VAL-13` before staging progresses (assert via the import journal: no COPYING-phase row); existing unencrypted lanes unchanged.
- **Effort:** S
- **Depends on:** none
- **Owner stage:** S5

### P3-5 — Multi-part archive detection (.part1.rar / .7z.001) with actionable copy

- **Goal:** A user picking the first volume of a multi-part RAR/7z gets an immediate, actionable rejection naming the multi-part pattern, instead of a mid-extraction decoder failure or a truncated-client import.
- **Current state:** No multi-part handling exists anywhere in the importer (repo-wide search for `part1`/`.7z.001`/multipart in `android/app/src/main/java` returns nothing import-related). What happens today: a `.part1.rar` selected alone often *extracts successfully* (first volume carries the header; subsequent volumes missing) and the failure surfaces deep in the copy/verify phase as a corrupt stream — or worse, a partially-valid tree reaches the scanners and fails with a confusing VAL-01/VAL-04. `ArchiveQuickCheck.evaluate` (`ArchiveFormatSniffer.kt:61-82`) rejects on format/missing-WoW.exe but never inspects the file *name*.
- **Work items:**
  1. Name-based pre-check in `ArchiveQuickCheck.evaluate` (it already receives the candidate name via its caller for the header read — if not, thread the display name in): reject when the file name matches `(?:\.part\d+\.rar|\.r\d+|\.7z\.\d{3}|\.zip\.\d{3}|\.z\d{2})$` (case-insensitive) with pinned copy: `VAL-13: this is volume N of a multi-part archive — re-pack the client into a single .zip/.7z/.rar (or import the already-extracted folder)`.
  2. Sibling-aware confirmation where the source is a seekable local channel (the quick check's entryNames are already available for local files): if any sibling matches the same volume family, name it in the copy ("volumes .001-.045 detected — single-file import cannot reassemble them").
  3. Entry-list belt for the archive lanes: if the staged archive's *last* entry is truncated relative to its declared size, or `ArchiveClientScanner.findClientRoot` (`ArchiveClientScanner.kt:142-168`) fails after a `.part*` name was seen in the original selection, upgrade the error copy to the multi-part explanation (post-staging path can't be avoided for SAF-picked trees, but the diagnosis can).
  4. Tests: `StagedArchiveTest.kt` — `.part1.rar`, `client.7z.001`, `client.zip.003` each rejected with the pinned copy before staging; a `.part0.rar`-style non-archive gets the generic path.
- **Upstream dependency:** none.
- **Verification:** The three name fixtures reject pre-staging with the volume-copy; the WotLK torrent-downloader scenario from register H4R2-6 (PC-extract-first lane copy) cross-references this message so both UX surfaces agree.
- **Effort:** S
- **Depends on:** none
- **Owner stage:** S5

### P3-6 — WotLK mmaps runtime on RP6: benchmark before the S6 exit

- **Goal:** The WotLK `MoveMapGen` wall-clock on RP6 is measured (register estimate: 4-10 h throttled; desktop band 2-12 h) and archived with tile counts, so the S6 go/no-go for on-device WotLK prep is decided on data, with the desktop-assisted fallback (P1-6) pre-agreed if the number lands outside the product envelope.
- **Current state:** The on-device mmaps stage is per-map sequential with a fixed thread count: `DataPreparationStore.prepareMmaps` (`DataPreparationStore.kt:127-186`) loops `derivedMmapMapIds` and runs `libpocket_movemapgen.so` with `--threads MMAP_THREADS` (`MMAP_THREADS = 6`, `:424`), checkpointing after each map (`:167`) so an interrupted run resumes — the machinery is sound, but there is no WotLK measurement (vanilla-only numbers in `ImportBenchmark` rows), no estimate model, and the register P1-10 confirms no 12340 datapoint exists. WotLK has ~2-3× vanilla's tile count (register: "WotLK has far more tiles", port plan S6.2), so wall-clock is the dominant unknown in the S6.4 decision.
- **Work items:**
  1. Benchmark protocol (part of P1-4's campaign): one full WotLK mmaps run on RP6 at the default thermal posture, recording per-map wall-clock (the `reportMmapProgress` tick already samples tiles + child CPU, `:189-198`), total wall-clock, peak mmaps bytes, and thermals (`tools/battery_sampler.py` exists). Run once unthrottled-equivalent and once with the admission controller active if it engages.
  2. Extrapolation model: per-map cost scales with ADT count — publish tiles/map from the measured run so a partial run can project the total (the UI can then show an honest ETA instead of the unknown-length bar the MANIFEST-stage comment at `:249-252` describes).
  3. Decision gate: if measured wall-clock exceeds the product envelope (Decision Log records the envelope; register's 4-10 h throttled is the working prior), the desktop-assisted lane becomes required for WotLK — trigger P1-6's qualification rather than shipping a 10-hour import UX.
  4. `MMAP_THREADS` on device stays conservative (P3-7 owns the RAM-derated value); the benchmark records whether 6 threads was thermal-safe.
- **Upstream dependency:** `libpocket_movemapgen.so` built from the flavor's pinned fork (S2/S6 pins) — same sources as H2-10.
- **Verification:** The benchmark JSON (total hours, per-map series, tile counts, thermals) committed with the S6 evidence; the S6.4 Decision Log entry cites it; the go/no-go outcome (on-device vs desktop-assisted) is recorded with user sign-off per port plan §S6.4.
- **Effort:** L
- **Depends on:** P1-4 (shared measurement campaign + device window)
- **Owner stage:** S6

### P1-6 — Desktop-assisted WotLK prep fallback: pre-decided before S6 exit

- **Goal:** If P3-6's measurement says on-device WotLK prep does not fit the envelope, the desktop-assisted lane (`tools/win_prepare_data.py` + `tools/prepare_o11_bot_data.py`) is *pre-emptively* qualified as an official alternative — with the same fail-closed gates (flavor+build+family manifest, per-file sha256 chain) as on-device prep — and the decision is recorded before S6 exits.
- **Current state:** The tooling exists: `tools/win_prepare_data.py` and `tools/prepare_o11_bot_data.py` are in `tools/` (verified by listing), and the port plan names the fallback at §S6.4 and risk #2, but nothing ties the desktop-produced generation into the app's consumer gates: `PreparedDataStore.requireActiveEnvelope`/`requireActive` (`PreparedDataStore.kt:38-78`) verify schema, `clientBuild`, `cmangosFamily`, `manifestSha256` seal, per-file sizes + sha256s — a desktop-produced generation passes *if and only if* it emits the same `data-manifest.json` shape (`DataPreparationStore.publish`, `:245-288`) and `active.json` pointer layout. The bot-data mirror (`tools/prepare_o11_bot_data.py`) and the Windows lockfile lane (`tests/test_win_lockfile.py`) exist for provenance.
- **Work items:**
  1. Manifest-compatible producer: a desktop runner that invokes the same `publish` manifest logic (extract it into a shared form or mirror its exact JSON contract in `tools/win_prepare_data.py` — schema 1, `complete`, `mode NORMAL`, `clientBuild`, `cmangosFamily`, `generator.provenance` with the Windows lane's .so/.dll digests + commit, per-file sha256 list) and writes `active.json` with `manifestSha256`.
  2. Provenance: the Windows extractor lane gets its own lockfile entries (`realm-runtime-lockfile-sqlite-win.json` / `o11-extractor-lockfile-win` naming pattern already exists for the win lane) so the desktop-built extractors are pinned like the Android ones; `tools/check_repo.py` extends to the new artifacts (invariant 3).
  3. Consumer identity: the app must show *which* lane produced the active generation (manifest gains `preparedOn: "device"|"desktop"` — optional field, G3-9 discipline: readers treat absence as "device" so existing manifests stay valid).
  4. Transfer path + integrity: the desktop-produced generation is sideloaded (adb/scp into the app's `content/o11-server/generations/` via a debug/import affordance) and the app runs the full `requireActive` chain on first consume — no trust shortcut; document the transfer in the wiki (S10 docs work item).
  5. Trigger: activated by P3-6's go/no-go; if on-device passes measurement, this ships as a supported-but-secondary lane anyway (it costs little and de-risks future content growth) — Decision Log records either way.
- **Upstream dependency:** none (desktop tooling is in-repo; Windows lane pins per S2's `tools/build_win_realm_runtime.py` parameterization).
- **Verification:** End-to-end desktop→device test: prepare a (vanilla or synthetic-minimal) generation on the Windows lane, transfer, boot mangosd against it on RP6 with zero manifest-gate deltas; adversarial: a desktop generation with a tampered file fails `requireActive` identically to a device one; `tests/test_win_lockfile.py` extended to the new lockfile.
- **Effort:** M
- **Depends on:** P3-6 (measurement trigger), P2-7 (§2.5 — Windows-lane per-flavor identity constants)
- **Owner stage:** S6

### P3-7 — MoveMapGen child-RSS guard + RAM-derated threads + OOM-distinct journal failure

- **Goal:** The mmaps stage cannot OOM the device: `MMAP_THREADS` is derived from device RAM, the generator child's RSS is sampled and guarded, and a kill-by-LMK is journaled as a distinct, actionable failure (resume-able) instead of a generic stage failure.
- **Current state:** `MMAP_THREADS = 6` is a constant (`DataPreparationStore.kt:424`) passed to every map (`:152`); the child is monitored only for liveness and progress — `reportMmapProgress` reads child CPU via procfs (`:189-204`, `generatorPid` at `:200`) but never its RSS; failures funnel through the generic `catch` at `:185-187` (`journal.failDataStage` with `error.message`), so an LMK kill of the child surfaces as a tool-exit error indistinguishable from a bad tile. `DeviceProfile` exists in the importer (`android/app/src/main/java/com/pocketrealm/importer/DeviceProfile.kt`) and the admission controller already consumes RAM floors (`BotAdmissionController.kt:156-157`) — the RAM signal is available, just unused here.
- **Work items:**
  1. Derate: compute threads at prepare time from `ActivityManager.getMemoryInfo`/`DeviceProfile` (e.g. ≥8 GB → 6, 6-8 GB → 4, <6 GB → 2; exact table from the P1-4/P3-6 measurements) and record the chosen value in the manifest/journal so a rerun on different hardware explains a different pace.
  2. RSS guard: extend the existing procfs sampler (`reportMmapProgress`, `:189-198`) to read the child's `VmHWM`/`VmRSS` each tick; abort the map (clean, checkpointed) when RSS crosses a derated ceiling, journaling `mmaps-child-rss` with the sampled number — this converts a 30-minute death into a fast, legible failure and lets the user switch to the desktop lane (P1-6) instead of retrying into the same wall.
  3. OOM-distinct journal: on child exit with signal (SIGKILL under LMK) or the RSS guard, `journal.failDataStage` gets a distinct error kind (`oom` vs `tool-error`) so the UI/copy can say "device memory was exhausted — resume with fewer threads or prepare on desktop"; the checkpoint machinery (`:167`) already makes the retry resume per-map.
  4. Test: JVM/androidTest with a stub generator that allocates past the guard → distinct failure kind journaled, checkpoint preserved, resume completes with fewer threads.
- **Upstream dependency:** none (the generator binary is the pinned flavor extractor).
- **Verification:** Forced-OOM test green with the distinct journal kind and successful resume; the P3-6 benchmark records the derated threads actually used and confirms no LMK kills at the chosen table; vanilla prep behavior unchanged except thread derivation (same result set — tile sha256s equal).
- **Effort:** M
- **Depends on:** P3-6 (measurement sets the thread table), P1-4 (device RAM context)
- **Owner stage:** S6

### P3-8 — Prep peak-space accounting (staging + publish sha256 walk) in the planner

- **Goal:** The storage plan for an import that includes data preparation accounts for the *peak* — staging tree + published generation coexisting during the manifest sha256 walk — not just the end state, so prep cannot die at 90 % on a full device.
- **Current state:** `ImportStoragePlanner.calculate` (`ImportStoragePlanner.kt:46-63`) sums source + stagedArchive + scratchArchive + extracted + database + wine + snapshot with a 20 % margin — it models import-time peak but has no term for the data-generation duplication: `DataPreparationStore.prepare` extracts into `generation(".staging-$importId")` and publishes by `Os.rename` (`:280-284`), so dbc/maps/vmaps/mmaps exist once — but the publish *manifest walk* (`:249-257`) and the recover path (`:291+`) re-hash files, and the app also holds the *previous* generation until activation (generation directories under `content/o11-server/generations/`). The real peak to model: extracted client + new staging data generation + old data generation (if re-prepping) simultaneously. Today a re-prep on a full device fails mid-walk with ENOSPC rather than being pre-flighted.
- **Work items:**
  1. Add a `prep` term to the planner: `plan(...)` gains the projected data-generation size (from the flavor's measured `dataGenerationBytes` in the P1-5 JSON) and, when an active generation already exists (re-prep), counts it as concurrently held; `calculate`'s subtotal/margin math (`:56-60`) absorbs it so `canProceed`/`lowHeadroom` reflect prep peak.
  2. Plumb the re-prep signal: the prepare entry point (`DataPreparationStore.prepare`, `:31`) knows whether an active generation exists — expose that to the planner call site so the term is conditional.
  3. The 20 % margin (`:60`) is re-reviewed by R4 at WotLK scale (the margin is percentage-of-subtotal; at 16-17 GiB extracted + 10+ GiB data the absolute margin is large but the *peak ordering* matters more — the test pins a scenario where prep peak, not import total, is the binding constraint).
  4. Tests: `ImportStoragePlannerTest.kt` — re-prep scenario with tight allocatable bytes denies before staging starts with actionable copy (extend the low-storage path the S9 End State tests).
- **Upstream dependency:** none.
- **Verification:** Planner unit cases: fresh-import vs re-prep peaks differ by the data-generation term and the denial fires pre-stage; device: a re-prep on a deliberately under-provisioned device is refused with the storage card before any bytes move.
- **Effort:** S
- **Depends on:** P3-2 (budget plumbing), P1-5 (measured data-generation sizes)
- **Owner stage:** S6

### P3-9 — Per-flavor VAL rejection copy: ISO and NSIS/installer-class

- **Goal:** Every pre-staging rejection copy is per-flavor truthful: the ISO message stops claiming "1.12.1 ISOs", the installer message stops claiming "only 1.12.1 Inno installers", each naming the selected flavor's actual expectations.
- **Current state:** Two pinned vanilla-only messages: `VAL11_ISO = "VAL-11: disc images are not supported — the 1.12.1 ISOs ship older installers (1.0.1 / 1.10.0); Pocket Realm needs the extracted 1.12.1.5875 client, not an installer"` (`ArchiveFormatSniffer.kt:54-55`) and `VAL12_NOT_INNO = "VAL-12: … only original Inno Setup-based 1.12.1 installers are extractable on device …"` (`:56-58`). Once a TBC client import is possible, both strings lie (a TBC ISO would be told it needs the 1.12.1.5875 client). The JVM suite pins the exact wording (`SafClientScannerMessageTest.kt` / `ArchiveDetectionTest.kt` assert the "VAL-11: " prefix and phrases, per the comment at `SafClientScanner.kt:9-14`), so the change is wording-plus-test, not structure.
- **Work items:**
  1. Parameterize the copy on the *qualified flavor set* (the scanner does not yet know which flavor the user intends — the archive may contain any): the honest form is flavor-set-aware: `"VAL-11: disc images are not supported — disc ISOs ship outdated installers; import the extracted <qualified builds list> client folder or a .zip/.7z/.rar of it"`, generated from `ClientFlavors.qualified()`'s version resources (the same pattern as `ClientPeIdentity.expectedVersions`, `ClientPeIdentity.kt:24-25`). Same for VAL-12: `"(only Inno Setup-based installers of the supported builds are extractable on device)"`.
  2. Keep the pinned-prefix discipline: tests assert the `VAL-11:`/`VAL-12:` prefixes and the discriminating phrases per qualified-flavor configuration (one qualified flavor → that build named; multi-flavor app after S9 → the set named).
  3. The G3-7 golden-copy rule (§2.7): vanilla single-flavor configuration must render byte-identical to today's strings — if the generated form cannot match the current bytes exactly, grandfather the vanilla-only rendering explicitly and pin it.
- **Upstream dependency:** none.
- **Verification:** JVM: single-qualified (vanilla) rendering byte-identical (golden test); a tbc-qualified test registry renders the TBC build number in both messages; ISO/NSIS fixtures still reject pre-staging.
- **Effort:** S
- **Depends on:** P3-1 (qualified-flavor resolution the copy derives from)
- **Owner stage:** S5

### P3-10 — Per-flavor VAL rejection copy: Blizzard Downloader and macOS .app wrappers

- **Goal:** The two remaining wrong-container shapes a TBC/WotLK user hits — Blizzard BackgroundDownloader partial-download folders and macOS `.app` bundle wrappers — get their own pre-staging rejection copy per platform reality, instead of generic "no WoW.exe" failures.
- **Current state:** Both shapes currently fall into generic paths: a downloader partial (a folder of `*.download`/`partial` files plus `Downloader.exe`-class artifacts, the register H4R2-6 "installer-era guidance: WotLK torrent downloaders" case) fails `findClientRoot`'s `VAL-01: no WoW.exe at the archive root or inside a single wrapper folder` (`ArchiveClientScanner.kt:154-157`) or the quick-check's `VAL-01: no WoW.exe anywhere in the archive` (`ArchiveFormatSniffer.kt:75-79`) — true but not actionable for this user. A macOS `.app` wrapper (a directory named `WoW.app/Contents/...` from a Mac copy of the client) rebases under `findClientRoot` only if `WoW.exe` sits within depth ≤ 2 (`ArchiveClientScanner.kt:148-151`); the x86 `WoW.exe` inside `WoW.app/Contents/MacOS/` is at depth 3 and fails VAL-01 with no hint that the *Windows* client is required; if a wrapper is flattened, the PE check (`ClientPeIdentity.kt:47-49`, IMAGE_FILE_MACHINE_I386) catches Mach-O binaries with a bare `VAL-02`.
- **Work items:**
  1. Downloader detection: extend `ArchiveQuickCheck`/the folder-lane scan to recognize the shape (names matching `backgrounddownloader.exe`/`downloader.exe`, `*.download`, `*.partial` dominance in the entry list, or `WOW-*-part*`-style spread files) and emit dedicated copy: `VAL-01: this is a Blizzard Downloader partial download — the download must be completed on a PC; import the finished, extracted client folder` (ties to H4R2-6's PC-extract-first guidance).
  2. macOS-wrapper detection: on VAL-01 failure, if any entry path contains `.app/` or `Contents/MacOS`, upgrade the message: `VAL-01: this is the macOS client wrapper — Pocket Realm needs the Windows client (WoW.exe beside Data/)`; on a Mach-O binary reaching the PE parse, add the same hint when the entry list shows `.app` ancestry (keep `VAL-02` for the generic case).
  3. Copy lives with the other pinned strings in `ArchiveFormatSniffer.kt`/`SafClientScanner.kt` and gets the same pinned-message tests; golden-copy rule as in P3-9 for the vanilla-only rendering.
  4. Wiki cross-link (S10 docs): the Game-Files-and-Import page per flavor lists these two shapes with the same wording (register H4R2-6).
- **Upstream dependency:** none.
- **Verification:** JVM fixtures: a downloader-partial tree and a `.app`-wrapper tree each produce the new dedicated copy (not generic VAL-01/VAL-02); vanilla negative fixtures unchanged (golden test).
- **Effort:** S
- **Depends on:** P3-9 (shared copy-parameterization pattern)
- **Owner stage:** S5

### W1-4 — Per-flavor camera cvars + touch-tuned defaults; catalog reads config-cache.wtf

- **Goal:** The camera control surface is correct per flavor — the WotLK-era camera cvars (`cameraYawSmooth*`/`cameraPitchSmooth*`, `cameraSmoothTrackingStyle`, `cameraPivot`, `mouseInvertPitch`) exist in the flavor's settings catalog with touch-tuned defaults, and the editor reads values that the client persists into `config-cache.wtf`, not just `Config.wtf`.
- **Current state:** The vanilla catalog (`WowVanillaSettingsCatalog.kt`, 109 entries counted via `id = `) already carries the camera family: `cameraTerrainTilt` (`:475`), `cameraWaterCollision` (`:487`), `cameraPivot` (`:493`), `cameraSmoothStyle` (`:499`), `cameraSmoothTrackingStyle` (`:510`), `cameraYawSmoothSpeed` with paired `cameraPitchSmoothSpeed` (`:522-524`), `cameraYawMoveSpeed` with paired `cameraPitchMoveSpeed` (`:531-533`), `mouseInvertPitch` (`:297`). What is missing per the register: (a) several of these persist to **`config-cache.wtf`** in the WotLK era (the client rewrites camera cvars there at exit), while the editor only reads `WTF/Config.wtf` — `InGameSettingsFiles.kt` models account `SavedVariables.lua` and `bindings-cache.wtf` (`:26-36`) but has **no `config-cache.wtf` accessor at all** (repo grep confirms: no `config-cache` reference in `android/app/src/main/java` beyond bindings-cache in the addon repair lane); (b) the "touch-tuned defaults" (deliberately small smoothing, inverted-pitch affordance for thumb steering) are a per-flavor seed decision not yet encoded; (c) no TBC/WotLK catalog exists yet (S7 deliverable, `WowWotlkSettingsCatalog`).
- **Work items:**
  1. `InGameSettingsFiles` gains `accountConfigCache(clientRoot, account)` → `WTF/Account/<name>/config-cache.wtf` (same safe-segment discipline, `:82-86`) plus a scoped variant; the settings editor snapshot (the `WineRuntimeStore.kt:2160-2215` region that assembles live values via `ConfigWtfCodec`) merges `config-cache.wtf` values *under* `Config.wtf` values for catalog keys flagged `persistedIn: config-cache` (precedence matters: the client reads cache at startup and rewrites at exit — the editor must show what the client will actually honor).
  2. Per-flavor catalog: `WowWotlkSettingsCatalog`/`WowTbcSettingsCatalog` (S7 deliverable) enumerate the camera family with per-build value forms (verify the 3.3.5 float echo against `ConfigWtfCodec.formatValue`'s `%.6f` pin at `ConfigWtfCodec.kt:120-122` — the pin was captured from 5875; W1-2's verification covers the clamp, this covers the format).
  3. Touch-tuned defaults as data: per-flavor `first_login` camera defaults (e.g. `cameraSmoothStyle 0`, reduced `cameraYawSmoothSpeed`, `mouseInvertPitch` per controller contract) added to the first-login seed (`ClientGenerationStore.kt:237-256` / `WineRuntimeStore.FIRST_LOGIN_UVAR_DEFAULTS`, `:1953`) — values decided in S7 play-testing, recorded in the Decision Log.
  4. Register H4-5/H4-6 (§2.4) own the broader WTF-scope and per-char binding rework; this gap only adds the `config-cache.wtf` read path the camera catalog needs.
- **Upstream dependency:** none.
- **Verification:** Device: change a camera cvar in-game on 3.3.5, exit cleanly, reopen the editor → shows the client-written value (read from `config-cache.wtf`); change it from the editor → the client honors it next session; corrupt `config-cache.wtf` → clean recovery per the H4-5 repair tests (cross-ref).
- **Effort:** M
- **Depends on:** H4R2-4 (§2.4 — per-flavor settings catalogs), H4-5 (§2.4 — WTF scope machinery)
- **Owner stage:** S7

### W1-7 — Donor-machine cvar scrub at import (coresDetected / processAffinityMask)

- **Goal:** Machine-specific cvars a donor install carries — `coresDetected`, `processAffinityMask`, `gxMaximize`-class host-state keys, and the `config-cache.wtf` machinery keys — are scrubbed or reset at import, so a copied tree cannot pin the client to the donor's wrong SoC topology on the RP6.
- **Current state:** Nothing scrubs donor WTF state at import: `ImportExtractionPolicy` copies everything under `WTF/` wholesale (only root-*level* names are filtered, `ImportExtractionPolicy.kt:62-79`), so `WTF/Config.wtf` and `WTF/Account/<donor>/config-cache.wtf` arrive with whatever the donor machine wrote — on WotLK, `coresDetected`/`processAffinityMask` persist into `config-cache.wtf` and directly control thread-pool sizing/affinity; a donor value pinning 2 cores of a different topology starves or oversubscribes Box64 threads on the RP6. The app does own `WTF/Config.wtf` (appOwnedFiles, `ClientGenerationStore.kt:111`) and re-seeds it (`:154`, `SAFE_CONFIG` at `:237-256`), but account-level `config-cache.wtf` is untouched — and it does not even exist as a concept in the code (W1-4 adds the read path). Register W2-a/P2-a point here from the perf area.
- **Work items:**
  1. Import-time scrub (publish step, beside the H2R2-4 realmlist neutralization in `ClientGenerationStore`): for every `config-cache.wtf` and `Config.wtf` in the staging tree, delete (via the `ConfigWtfCodec` line-removal path — write-or-delete semantics exist) the key set: `coresDetected`, `processAffinityMask`, `timingMethod`, `maxFPS` (app-owned), `gxApi`/`gxResolution` (renderer-dependent), and any `MOVIE*-class` donor state; journal each removal like the copy-skip journal does (`ManagedClientImporter.kt:356-362` pattern). The scrub runs at **one posture for all flavors**: it is a no-op on genuine vanilla trees — 1.12.1 writes none of these keys (`coresDetected`/`processAffinityMask` are TBC/WotLK-era cvars; verified: repo-wide app grep for both returns nothing) — so published vanilla bytes are unchanged and G3-7's golden-byte rule holds without needing an exception.
  2. Fresh-generation discipline: the first-login seed writes the flavor's intended values for the machine keys (see W1-10) so the scrub is followed by a known-good seed rather than a vacuum — `ClientGenerationStore.kt:237-256` template gains the flavor's topology keys.
  3. The scrub list is flavor data (`runtime.donor_scrub_keys` in `flavor.json`) — TBC's set may differ from WotLK's (TBC has no `config-cache.wtf`); the scrub step applies the union of (a) `Config.wtf` keys and (b) `config-cache.wtf` keys when the file exists.
  4. Never delete user gameplay state: the scrub touches only the enumerated machine keys, never SavedVariables/bindings — pinned by test.
- **Upstream dependency:** none.
- **Verification:** JVM: a donor `config-cache.wtf` carrying `SET processAffinityMask "3"` + `SET coresDetected "4"` publishes with both removed and a journal row each; gameplay keys in the same file survive byte-identical. Device: import a donor tree, boot WotLK, and read back the seeded topology keys (correct for RP6).
- **Effort:** M
- **Depends on:** W1-4 (config-cache.wtf codec/read path), H2R2-4 (same publish-step location)
- **Owner stage:** S7

### W1-10 — SAFE_CONFIG full-core mask + hwDetect=0 per flavor

- **Goal:** The app seeds known-good topology cvars — full-core affinity mask for the RP6 SoC, `hwDetect 0` — so the client never re-runs hardware detection or narrows its own affinity, as per-flavor seed data.
- **Current state:** `hwDetect` exists in the vanilla settings catalog (`WowVanillaSettingsCatalog.kt:122`) but nothing seeds or enforces it; there is no `processAffinityMask`/`coresDetected` seeding anywhere (repo grep: zero matches in `android/app/src/main/java` for `processAffinityMask|coresDetected`). W1-7 removes donor values; this gap writes the replacement values: the register row names "per-flavor SAFE_CONFIG full-core mask, hwDetect=0". The full-core mask is `(1 << coreCount) - 1` computed from the device (big.LITTLE caveat: the client's notion of cores is fine — a full mask lets the OS schedule; the *wrong* mask is the donor's), so the seed is computed at first-login seed time, not hard-coded.
- **Work items:**
  1. Compute-at-seed: the first-login seed path (`WineRuntimeStore.seedFirstLoginAccountDefaults`, `:2117-2133`) gains topology keys written into the seeded `Config.wtf` (and `config-cache.wtf` when the flavor uses it, post-W1-4): `SET processAffinityMask "<(1<<Runtime.availableProcessors())-1>"`, `SET coresDetected "<availableProcessors>"`, `SET hwDetect "0"` — computed from the device at seed time, values recorded in the generation journal for diagnostics.
  2. Per-flavor gating: TBC/WotLK only (vanilla's working lane is untouched — invariant 1; if measurement later shows vanilla wants the same, it is a separate decision), so the keys live in the flavor's seed template, not the shared `SAFE_CONFIG` string at `ClientGenerationStore.kt:237-256` — parameterize that template per flavor (the same split H2-4/H2-6 introduce). **Posture resolution (review round 1, vs performance-persistence-import.md P2-a):** the single program posture is — scrub donor machine keys on every flavor (W1-7; vanilla arm is a no-op on genuine vanilla trees, see W1-7 item 1), seed the replacement mask for tbc/wotlk only. P2-a's "vanilla seed gains only these lines (delta-golden test, G3-7)" is withdrawn: seeding vanilla would change `SAFE_CONFIG` bytes and violate both G3-7 and the wave-0 "`--flavor vanilla` byte-identical" exit criterion; if vanilla seeding is ever wanted, it requires a Decision Log entry amending G3-7 and that criterion in the same commit — not planned.
  3. Enforcement posture: seed-only, not enforced-per-launch (the client rewrites these at exit; fighting it every launch creates the transition-delete complexity `ManagedConfigPolicy` exists to avoid) — record the posture in the Decision Log; revisit only if soak evidence shows drift.
  4. Copied-tree case: W1-7's scrub + this seed together mean a donor tree gets known-good values on first post-import launch.
- **Upstream dependency:** none.
- **Verification:** Device: fresh WotLK generation seeds the three keys with RP6-correct values (read back from WTF after first launch); a donor tree import ends with the same values; vanilla seed output byte-identical to today (invariant 1, golden test on the seed template).
- **Effort:** S
- **Depends on:** W1-7 (scrub clears donor values first), W1-4 (config-cache path where applicable)
- **Owner stage:** S7

### W1-2 — Per-flavor UI-scale clamp verification (sub-slider uiScale in 3.3.5)

- **Goal:** The app's UI-scale management is verified per flavor: the 3.3.5 client's finer-grained (sub-slider) `uiScale` values round-trip through the app's `%.6f` writer and its 0.5-2.0 clamp without the client and app fighting over the value.
- **Current state:** The clamp is hard-coded and vanilla-derived: accepted range `0.5f..2.0f` with `require` (`Settings.kt:509`, `:639`) and effective scale `coerceIn(0.5f, 2f)?.coerceAtMost(virtualHeight / 512f)` (`Settings.kt:522`); enforcement writes `useUiScale "1"` + `uiScale` via `ConfigWtfCodec.formatValue`'s `%.6f` (`ManagedConfigPolicy.kt:59-62`, `ConfigWtfCodec.kt:120-122` — documented as "the way build 5875 echoes CVar values"). WotLK's slider is finer-grained and its max differs; if 3.3.5 echoes a different decimal form or rewrites `uiScale` to a sub-clamp value at exit, the transition-delete logic (`ManagedConfigPolicy.uiScaleTransitionDelete`, `:124-138` — which compares the recorded vs file value with `compareTo`) could mis-detect a user edit and strand stale enforced lines.
- **Work items:**
  1. Per-build verification first (this gap is primarily a *verification* work item): on the qualified 8606 and 12340 clients, capture the exit-rewrite echo of `uiScale` for a set of probe values (0.5, 0.64, 1.0, 1.25, 1.5, 2.0, and above-max input) — record the echo format and the client's own clamp into `flavor.json` (`runtime.ui_scale: {min, max, echo_format}`).
  2. Adjust the clamp data if 3.3.5 differs: make the accepted range and the `virtualHeight / 512f` cap flavor-derived (`Settings.kt:509/:522` read the flavor block; default = today's values so vanilla is bit-identical, G3-7 discipline).
  3. Verify the transition-delete contract per flavor: a managed→unmanaged flip on 3.3.5 after an in-game scale change must leave the user's value in place (the `:130-133` guards hold when the echo format matches — the test encodes the captured echo).
  4. `FlavorManifestParityTest`/settings tests pin the per-flavor range; the S7 in-game editor round-trip End State (port plan S7.3) includes a uiScale case per flavor.
- **Upstream dependency:** none.
- **Verification:** Device per flavor: set managed scale 1.25 → launch → in-game change to another value → exit → app shows the user value unmanaged; re-enable management → enforced pair written and honored; the captured echo table in `flavor.json` cites the probe evidence.
- **Effort:** S
- **Depends on:** H4R2-4 (§2.4 — per-flavor settings catalog plumbing), P3-1 (flavor-data resolution)
- **Owner stage:** S7

### W1-8 — Name the TBC/WotLK low-spec cvar sets explicitly

- **Goal:** The plan's "graphics preset default" for TBC/WotLK is a named, explicit cvar set (e.g. `M2UsePixelShaders`, shadow family `shadowLOD`/`shadowLevel`, `groundEffectDist`, weather density) stored as per-flavor data and seeded as the default preset — not an undocumented "turn things down".
- **Current state:** The enforced-key set today has exactly one quality knob: `M2UseShaders` forced `0` only under the legacy GL renderer (`ManagedConfigPolicy.kt:82` — inert under the DXVK-only route the new flavors use, port plan §S7.1/D3). No low-spec preset exists anywhere: the first-login `SAFE_CONFIG` (`ClientGenerationStore.kt:237-256`) seeds a handful of keys (`farclip 177`, `ffxGlow 0`, `ffxDeath 0`, `gxVSync 0`, `gxMultisample 1`) tuned for vanilla-5875-on-RP6, and the register W1-8 row asks that the TBC/WotLK low-spec sets — the cvar names that exist and matter in 2.4.3/3.3.5 (`M2UsePixelShaders`, `M2UseShaders`, `shadowLevel`, `shadowLOD`, `groundEffectDensity`/`groundEffectDist`, `weatherDensity`, `particleDensity`, `environmentDetail`, `textureFilteringMode`) — be named explicitly in the plan rather than left implicit.
- **Work items:**
  1. Define per-flavor preset data in `flavor.json` (`runtime.graphics_presets: {low: {cvar: value,...}, medium: {...}}`), keyed by the cvars that exist in that build (the vanilla catalog's "WotLK-era inert" comments become live keys per port plan S7.2 — the set is captured from the qualified client's own options panel + cvar dump, values proven on device).
  2. Seed the *low* preset as the WotLK default (measured in P1-4/H2-3's memory work; H2-4's `gxTextureCacheSize` pin is part of the same block), and expose preset choice in the S7 settings UI as one selector writing the whole block through the existing `ConfigWtfCodec.merge` machinery (whole-block writes keep transition semantics simple: one key family, written-or-deleted together).
  3. The renderer-conditional `M2UseShaders` line at `ManagedConfigPolicy.kt:82` stays renderer-scoped and is *not* silently reused as the preset mechanism (different concern: GL-lane compatibility vs quality preset) — the preset block is separate data.
  4. Every preset value must survive a client exit-rewrite (echo format check like W1-2's) — values that the client clamps or drops are recorded at their effective values, with the verification in the S7 render-QA checklist (H2-13 stations re-run per preset).
- **Upstream dependency:** none.
- **Verification:** `flavor.json` carries the named low/medium sets per flavor; device: fresh WotLK generation boots at the low preset, H2-13's stations captured at it; the selector round-trips (preset → exit → reopen → same preset detected); vanilla presets: none added (invariant 1).
- **Effort:** S
- **Depends on:** H2-4 (texture-cache pin in the same block), H2-13 (render QA validates the preset), P1-4 (measurement behind the default choice)
- **Owner stage:** S7

---

## Coverage ledger

- [x] H2-2
- [x] H2-9
- [x] H2R2-3
- [x] H2R2-4
- [x] H5R2-8
- [x] H2-3
- [x] P1-4
- [x] H2-4
- [x] H2-6
- [x] H2-7
- [x] H4-7
- [x] J2
- [x] H2-8
- [x] H2-10
- [x] H2-11
- [x] F1-8
- [x] H2-12
- [x] H2-13
- [x] H2-14
- [x] P3-1
- [x] P3-2
- [x] P1-5
- [x] P3-3
- [x] P3-4
- [x] P3-5
- [x] P3-6
- [x] P1-6
- [x] P3-7
- [x] P3-8
- [x] P3-9
- [x] P3-10
- [x] W1-4
- [x] W1-7
- [x] W1-10
- [x] W1-2
- [x] W1-8
- [x] H2-1

§2.3 covers the playerbots population engine and the Pocket Realm LLM speech lane — 29 IDs in five themes: source resolution and build plumbing (register correction C4 voids the port plan's S3 kill-switch; one multi-expansion bots pin serves all flavors), bot behavior parity (DK bots, expansion-zone travel, strategy inventories, gear, riding, professions, guilds), configuration policy with fail-loud staging, the LLM speech lane's memory/KV and speech-trigger budgets on the shared 12 GB device, and the lore corpora. Nearly every item depends on the H3-1/H1-1 pin landing first, and nearly every conf-emission change is fenced by the §2.7 guards.

---

## 2.3 Bots & LLM (R1: H3, R2: H3R2, R4: F1-9, R6: J1/J2, R7: C2)

This area covers the playerbots population engine and the Pocket Realm LLM speech lane for the TBC (2.4.3/8606) and WotLK (3.3.5a/12340) flavors, mirroring what already works on the vanilla lane. Its 29 gaps group into five themes: (1) **source resolution and build plumbing** — pinning the multi-expansion `cmangos/playerbots` tree (register correction C4 voids the port plan's S3 kill-switch: the tree already carries `MANGOSBOT_ZERO/ONE/TWO` and per-flavor conf dists at `native/playerbots/CMakeLists.txt:137-143,183-186`), proving the build matrix per branch, and re-anchoring the ~25-file LLM patch set that `tools/build_o09_realm_runtime.py:5878-5902` currently overlays onto classic text only; (2) **bot behavior parity** — DK bots, expansion-zone travel/questing, strategy inventories (which stop at Karazhan/Mechanar/Naxxramas in the pinned tree), gear, riding, professions, guilds; (3) **configuration policy** — per-flavor conf-key matrices with fail-loud staging, keep-off audits (BG/arena/AhBot/guild-growth), and the flavor-blind commander surface; (4) **the LLM speech lane** — memory/KV budget gating on a 12 GB shared device, speech-trigger qualification on 8606/12340 clients, era lint, personas, banter, and per-branch chat-enum pins; (5) **lore corpora** — v204/v303 builds with provenance and per-flavor packaging. Nearly every item depends on the H3-1/H1-1 pin landing first; nearly every conf-emission change is fenced by the vanilla-regression guards (G3-7/G3-8) because `BotProfiles.playerbotConfig()` digests are frozen identity inputs.

---

### H3-1 — Pin the multi-expansion playerbots tree for TBC/WotLK

- **Goal:** `schemas/flavor.json` `bots` entries for `tbc` and `wotlk` are resolved from `status:"unresolved"` to pinned forks/branches/commits that both flavors build from, retiring the port plan S3 kill-switch (risk #1) by evidence.
- **Current state:** Vanilla pins `puzzled-pancake/playerbots @ 7e2cd2fbbbb4eaa3…` branch `pocket-realm` (`schemas/flavor.json:28-32`; verified live: `git rev-parse HEAD` in `native/playerbots` returns `7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d`). The pinned tree is already multi-expansion — `MANGOSBOT_ZERO/ONE/TWO` definitions and per-flavor conf dists (`native/playerbots/CMakeLists.txt:137-143`, `183-186`; dists verified this session at `playerbot/aiplayerbot.conf.dist.in{,.tbc,.wotlk}` with two stated, re-runnable counts: uncommented `key =` lines **3554/393/328** via `grep -cE '^[A-Za-z][A-Za-z0-9_. ]* *=' <dist>`, and unique top-level key families **93/79/79** via `grep -oE '^AiPlayerbot\.[A-Za-z]+' <dist> | sort -u | wc -l`; the extraction rule the conf gate builds on is pinned in H3R2-1 item 1). The tbc/wotlk `flavor.json` blocks still carry `"bots": {"upstream": null, "commit": null, "status": "unresolved"}` (`schemas/flavor.json:99-103,160-164`). The upstream `cmangos/playerbots` master is the C4-confirmed multi-expansion source; the local fork is a Pocket Realm carrier fork.
- **Work items:**
  1. Record the S3 decision in `docs/DECISIONS-PORT.md`: one bots pin serves all flavors (per register C4), forked per branch. Candidate evaluation (branch compatibility, GPL license — license header verified at `native/playerbots/CMakeLists.txt`, maintenance, API shape) is a checklist in the decision entry, not a new search.
  2. Create `puzzled-pancake/playerbots` branches `pocket-realm-tbc` and `pocket-realm-wotlk` off the upstream multi-expansion master commit selected for the pin (cherry-carrying the Pocket Realm factory/mgr commits that `tools/build_o09_realm_runtime.py:5845-5902` anchors — see H3-4 for the drift reconciliation), or keep a single `pocket-realm` branch if the classic/tbc/wotlk deltas stay macro-switched; record the choice as D-new in the Decision Log.
  3. Register both in `.gitmodules` (new `native/playerbots-tbc`, `native/playerbots-wotlk` paths, branch entries), `schemas/sources.json` (mirror the existing `playerbots` entry shape at `schemas/sources.json:83-92`), and bind via `tools/check_flavor.py`'s D16 flavor↔sources pin binding.
  4. Fill `flavor.json` `bots.upstream`/`commit`/`status:"pinned"` for tbc and wotlk; flip `native/classic-db`-style submodule prefixes for `scripts/build_native.py`'s `PLAYERBOTS_SUBMODULE` constant (`scripts/build_native.py:73`) to resolve through the flavor.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ master (select and record the exact pinned commit at execution); fork branches under `https://github.com/puzzled-pancake/playerbots`.
- **Verification:** `python tools/check_flavor.py` and `python tools/check_sources.py` green with all three flavors' `bots` pins resolved; `git submodule status` shows the tbc/wotlk bots pins; the pin caveat from register §3 ("verify 7e2cd2fb locally") is discharged by the recorded `git rev-parse` evidence above.
- **Effort:** M
- **Depends on:** H1-15 (only one bot backend may build per flavor — H1-15's S2 CMake-gate/build-assert half lands in wave S2 and precedes this S3 pin flip, so the H1-15↔H3-1 pair executes as H1-15(S2)→H3-1(S3), not circular; see server-systems.md H1-15's scoped edge), S2 fork pins landing first (stage order).
- **Owner stage:** new:S3 (RA first, then RB re-runs the runbook)

### H1-1 — Build matrix: playerbots ON per branch, both lanes

- **Goal:** `playerbots` builds green with the bots tree ON for mangos-tbc and mangos-wotlk on the Android (arm64-v8a) and Windows lanes, proven by a per-branch build matrix that runs in CI forever after.
- **Current state:** The vanilla lane builds via `scripts/build_native.py` with `-DBUILD_PLAYERBOTS=ON` (`scripts/build_native.py:397`) after `ensure_playerbots()` mirrors `native/playerbots` into `native/cmangos/src/modules/PlayerBots` for the FetchContent SOURCE_DIR form (`scripts/build_native.py:248-278`; contract recorded at `schemas/sources.json:30`). The overlay driver asserts the exact pins before touching trees (`tools/build_o09_realm_runtime.py` `prepare_cmangos_source()`, `CMANGOS_COMMIT`/`PLAYERBOTS_COMMIT` mismatch → hard error) and re-mirrors the bots tree fresh every build. There is no tbc/wotlk lane at all yet — `build_native.py` has no `--flavor` (port plan S1 item 3), and `ensure_playerbots` hardcodes `native/cmangos`.
- **Work items:**
  1. Land S1's `--flavor {vanilla,tbc,wotlk}` on `scripts/build_native.py`; parameterize `PLAYERBOTS_SUBMODULE` and `PLAYERBOTS_IN_TREE` to `native/playerbots-{flavor}` → `native/cmangos-{flavor}/src/modules/PlayerBots`.
  2. In `tools/build_o09_realm_runtime.py` and `tools/build_win_realm_runtime.py`, route `CMANGOS_COMMIT`/`PLAYERBOTS_COMMIT` through the per-flavor pins and keep the clean-tree tripwires (unrecorded-changes checks in `prepare_cmangos_source()`) per flavor.
  3. Add a CI matrix job: {tbc, wotlk} × {android-arm64-v8a, win} × playerbots=ON, asserting `-DBUILD_AHBOT=OFF` stays pinned (both lanes already pass it explicitly: `tools/build_o09_realm_runtime.py:6350`, `tools/build_win_realm_runtime.py:129`) and that the legacy in-tree `PlayerBot` module is off (H1-15's fail-loud "both backends on" check belongs here).
  4. Write per-flavor `realm-runtime-lockfile*` entries covering the playerbots mirror digests; the existing `patches_content_digests()` walk (`tools/build_o09_realm_runtime.py:201+`, sha256 of every patch file under `native/patches/`) must pick up the per-flavor patch sets automatically.
- **Upstream dependency:** H3-1's pinned branches (exact commits recorded there); `https://github.com/puzzled-pancake/mangos-tbc`/`-wotlk` branch `pocket-realm` from S2.
- **Verification:** `python scripts/build_native.py --flavor tbc` and `--flavor wotlk` complete with playerbots ON on both lanes; CI matrix green on two consecutive rounds; `git submodule status` + `check_sources.py` green; a deliberate wrong-pin run fails with the existing `Playerbots pin mismatch` error (R2 negative test).
- **Effort:** M
- **Depends on:** H3-1; H1-15.
- **Owner stage:** new:S3

### H3-2 — DK bot AI: rune/runeforge strategies per flavor

- **Goal:** A WotLK death-knight bot plays its spec (blood/frost/unholy) with rune-driven rotation and runeforge-equipped weapon, surviving a scripted dungeon pull, indistinguishably in kind from a vanilla bot of its class.
- **Current state:** The pinned multi-expansion tree already carries DK AI: `native/playerbots/playerbot/strategy/deathknight/` (per-spec strategy dirs like every class), `AiFactory.cpp` case `CLASS_DEATH_KNIGHT` at lines 45, 211, 272, 510, 877, and spec naming `ChatHelper.cpp:205-207` (`"dk"`, `"blood"`, `"frost"`). `GuildCreateActions.h`-adjacent factory code switches on `MANGOSBOT_TWO` for DK profession pools (`PlayerbotFactory.cpp` `InitTradeSkills()`, `#ifdef MANGOSBOT_TWO case CLASS_DEATH_KNIGHT`). None of this has ever compiled or run under the Pocket Realm overlay stack — every patch anchor in `tools/build_o09_realm_runtime.py` was written against the classic build (MANGOSBOT_ZERO text).
- **Work items:**
  1. Compile the tree with `MANGOSBOT_TWO` (falls out of H1-1) and fix the DK-path compile deltas in the overlay anchors only where the anchored upstream text differs per branch — log every re-anchored overlay id in the S3 evidence pack.
  2. Add a DK strategy probe to the bot soak harness: spawn 3 DK bots (one per spec) via the existing bench profiles (`BotProfiles.BENCH_AUTOLOGIN_50` pattern in `android/app/src/main/java/com/pocketrealm/bots/BotProfiles.kt`), drive a trash pull in Utgarde Keep's entrance via the realm test lane (`tools/run_realm_test.py`), assert: runes spend and regenerate, no bot death, spec-appropriate spell log (parse `world.log` for Icy Touch/Plague Strike/Death Strike presence per spec).
  3. Verify runeforge: assert the bot's weapon carries a runeforge enchant after `PlayerbotFactory` init (DB check on the character's item enchantment id in `wotlkcharacters.item_instance`), or record the factory gap and add a fork commit in `PlayerbotFactory::InitEquipment()` to apply it.
  4. Emit the DK conf surface per flavor in the H3R2-1 matrix (e.g. `ClassRaceProb.6.*` exists only in `aiplayerbot.conf.dist.in.wotlk` — verified by key-set diff).
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ the H3-1 pin (DK strategies are upstream code, no new upstream work).
- **Verification:** The three-spec pull test green on device (RP6) with log evidence; runeforge enchant present in DB; `world.log` has zero unhandled-spell errors for class 6 during the pull.
- **Effort:** M
- **Depends on:** H3-1, H1-1, J2-1 (a DK must exist to drive).
- **Owner stage:** new:S3

### J2-1 — DK bot factory creation, slot policy, and gating

- **Goal:** WotLK bot pools include correctly-created DK characters (level-55 start, class 6, hero-class gating honored) without disturbing the 9-characters-per-account slot invariant, and the player-facing DK unlock decision is reflected in bot policy. *(Register-token note: `J2-1` names two sibling treatments — this bot-factory/slot-policy package (`new:S3`) and §2.1's conf-seeds/account-expansion treatment (owner of the `HEROIC_CHARACTERS_PER_REALM`/`MIN_LEVEL_FOR_HEROIC` half), both ticked in their own ledgers; see the roadmap §0 ID-collision conventions.)*
- **Current state:** `BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT = 9` is derived from the pinned `RandomPlayerbotFactory` (`android/app/src/main/java/com/pocketrealm/bots/BotPopulationPolicy.kt:27`, citing `RandomPlayerbotFactory.cpp` `maxAllowed = 9 - count` on the non-MANGOSBOT_TWO path) — the WotLK branch's account-slot arithmetic is explicitly unverified ("non-MANGOSBOT_TWO path"). `flavor.json` already carries `bot_policy.characters_per_account: 9` per flavor with a note (`schemas/flavor.json:47-49,117-120,178-180`), and Decision D15 item 6 records that this value is duplicated in Kotlin with no registry projection — retirement is owned by this stage. The wotlk core's hero-class gating conf (`HEROIC_CHARACTERS_PER_REALM` / `MIN_LEVEL_FOR_HEROIC`, per register §2.1 H1-2/J2-1) does not exist in any shipped conf yet.
- **Work items:**
  1. Verify the MANGOSBOT_TWO slot path in the H3-1-pinned tree: re-read `RandomPlayerbotFactory.cpp` account-creation arithmetic under `MANGOSBOT_TWO`; if wotlk accounts still hold 9 bot chars, keep 9; if the branch raises it, update `flavor.json.wotlk.bot_policy.characters_per_account` AND the Kotlin constant via a new registry projection (retire D15 item 6): `BotPopulationPolicy.charactersPerAccount(flavor)` reading `ClientFlavor.botPolicy.charactersPerAccount`.
  2. Add a `bot_policy.heroic_class_policy` field to `flavor.json` wotlk (`"player-gated"` / `"bots-included"`); when `"bots-included"`, extend `RandomPlayerbotFactory` (fork commit) to roll DK characters into the pool at the core's hero-class rate, respecting the core gate conf emitted by `ServerRuntimeFiles.kt` `worldConfig()` (new `MinPetitionSigns`-style conf lines block).
  3. Assert factory-created DKs start at 55 with correct starting items/spells: SQL floor on `wotlkcharacters.characters` (`level=55 AND class=6`) after a forced factory batch.
  4. Keep vanilla behavior byte-identical: the emission for the vanilla flavor must not change (G3-7 golden conf-byte test on `playerbotConfig()`).
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin (MANGOSBOT_TWO factory path); wotlk core conf keys from the S2 `mangos-wotlk` pin.
- **Verification:** Factory batch on wotlk produces DK rows at level 55 (SQL assert); `characters_per_account` threading unit test (Kotlin) proves flavor routing; vanilla conf digest unchanged (existing adv-identity tests green).
- **Effort:** L
- **Depends on:** H3-1, H3R2-1 (conf emission plumbing), F3-3 (D15/D9 retirement context).
- **Owner stage:** new:S3

### H3-3 — Expansion-zone bot travel and questing soak (530/571)

- **Goal:** After a 2-hour soak on each new flavor, bots demonstrably live in the expansion's zones: bots at level 61-70 are inside map 530 (TBC) / 71-80 inside 571 (WotLK), having traveled there by flight path/transport/ground — not teleported there by spawn magic.
- **Current state:** The flavor-correct knobs already exist and are flavor-routed: `AiPlayerbot.RandomBotMaxLevel` and `RandomBotMaps` are emitted from the qualified flavor's `bot_policy.max_level` / `random_maps` (`BotProfiles.kt:147-148` via `flavorBotMaxLevel()`/`flavorBotRandomMaps()`; values 70/[0,1,530] and 80/[0,1,530,571] at `schemas/flavor.json:115-118,176-179`). The rumor POI set feeding bot gossip is a frozen **vanilla-only** 40-entry list (`kRumorPoiTitles` at `native/patches/playerbots/PlayerbotLlmChatter.cpp:229-249` — "Goldshire … Karazhan"), so TBC/WotLK bots have no expansion POI texture. Whether the upstream travel graph (TravelMgr/TravelNode at `native/playerbots/playerbot/TravelMgr.cpp`, `TravelNode.cpp`) resolves taxi/transport paths on maps 530/571 is unverified, and bot movement depends on the S6 mmaps for those maps (`tools/prepare_o11_bot_data.py`, plan S6 item 5 "bot-data host mirror per flavor (maps list per flavor)").
- **Work items:**
  1. Verify and extend the travel graph per flavor: run the H1-1 build, spawn bots at Honor Hold/Valiance Keep, force RPG/travel actions, and log whether `TravelMgr` routes across zone borders and transports (Menethil→Valiance Keep ride mirrors H1R2-4's test); file upstream issues with repro logs for any graph hole and land fixes as fork commits on the H3-1 branch (this is the register's "transport/FP graph" item). The graph-inventory leg runs on classic maps in wave S3; the 530/571 graph re-validation runs in **wave S6** against that wave's freshly prepared mmaps (see the straddle in **Depends on**).
  2. Split `kRumorPoiTitles` per flavor: move the list into per-flavor data (the H3-8 externalization file format) or a `MANGOSBOT_ONE/TWO`-switched table in `PlayerbotLlmChatter.cpp` — Outland set (Honor Hold, Thrallmar, Shattrath, Telredor, Area 52, …) and Northrend set (Valiance Keep, Dalaran, Gadgetzan-ferry ports, …); keep the vanilla list byte-identical for MANGOSBOT_ZERO.
  3. Confirm the map-policy gate: `random_maps` ⊆ flavor maps is F3-7's gate — reference it, don't duplicate; the expansion soak runs in **wave S6** on that wave's own 530/571 mmaps (the roadmap's S3/S6 straddle resolution: S3 keeps classic-map legs, S6 carries the expansion legs it unblocks). DP12's desktop lane (`tools/win_prepare_data.py`) remains the pre-decided fallback only if S6's own prep lands too late.
  4. Add the soak assertion: after the plan-standard ≥2 h soak (plan §4.5), run `SELECT map, COUNT(*) FROM characters WHERE level BETWEEN 61 AND 70 GROUP BY map` (tbc) / `71 AND 80` (wotlk) on `tbccharacters`/`wotlkcharacters` and require >30% of online bots inside the expansion map(s) at soak end; wire into the soak evidence script. Datapoints come from S6/M2's on-device prep; if DP12's desktop fallback is ever exercised, desktop-lane datapoints are labeled `desktop-mmaps` so the S6.4/DP12 memo never conflates the two.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin (TravelMgr fixes cherry-picked or authored); tbc-db/wotlk-db pins (S4) for quest availability.
- **Verification:** The SQL soak assertion green per flavor with archived soak logs (executed in **wave S6** per the straddle in Owner stage — deliberately not an S3 exit criterion, matching the roadmap's wave-S3 exit); travel log shows at least one FP/transport route taken by a bot per flavor (classic-map leg evidence lands with the wave-S3 soak); rumor lines naming expansion POIs appear in `world.log` during the soak.
- **Effort:** L
- **Depends on:** H1-1, H3-1; F3-7 (map gate), H1R2-4 (transport DBC/data), S6 mmaps (stage) — **straddle (resolved, roadmap S3/S6 note):** the classic-map graph leg runs in wave S3; the 530/571 soak legs execute in **wave S6** on that wave's own mmaps (mirrored by server-systems.md H5-9/P1 "Stage straddle" notes); DP12's desktop lane is only the pre-decided fallback, never the default input.
- **Owner stage:** new:S3 — classic-map graph leg in wave S3; the expansion-map soak legs straddle into **wave S6**, which also carries H5-9's flight/no-fly soak and P1's bot-flight datapoint

### H3-4 — Upstream drift matrix since 7e2cd2fb + keep-off keys proven inert

- **Goal:** A recorded drift matrix enumerates every playerbots upstream commit between the current fork base and the H3-1 pin, classifying each as (a) already carried, (b) cherry-picked for travel/quest correctness, or (c) declined with reason — and the BG/arena/guild-growth keep-off conf keys are proven parsed-and-inert on both new branches.
- **Current state:** The fork pin 7e2cd2fb is a Pocket Realm carrier fork; register correction C4 and §3 note the upstream multi-expansion tree moves independently ("verify 7e2cd2fb locally"). The app pins the keep-off keys today: `RandomBotJoinBG = 0`, `RandomBotAutoJoinBG = 0`, `RandomBotArenaTeamCount = 0`, `AllowGuildBots = 0`, `RandomBotFormGuild = 0`, `RandomBotGuildCount = 0`, `RandomBotJoinLfg = 0`, `EnableBroadcasts = 0`, `CommandServerPort = 0` (`BotProfiles.kt:184-208`). Whether the TBC/WotLK builds of those keys still exist and parse (the conf-key matrix shifts per flavor — see H3R2-1) is unproven; a silently-ignored keep-off key would fail open.
- **Work items:**
  1. `git fetch https://github.com/cmangos/playerbots master` in `native/playerbots`; generate the drift matrix `git log --oneline 7e2cd2fb..<PIN>` and classify per commit into a table committed at `docs/patches/playerbots-drift-tbc.md` / `-wotlk.md`.
  2. Cherry-pick travel/quest correctness fixes the H3-3 soak exposed, plus any BG/arena-adjacent fixes needed only to keep the keep-off keys parsed; every cherry-pick lands on the H3-1 branch with `cherry-picked-from:` trailers.
  3. Prove keep-off inertness per flavor: in the H1-1 build, for each keep-off key assert (a) `PlayerbotAIConfig.cpp` parses it on that branch (grep the generated config parse list), (b) the staging fail-loud check (H3R2-1) does not flag it, and (c) a 30-minute soak shows zero BG/arena team/guild-churn rows in the characters DB (`SELECT COUNT(*) FROM ...` on battleground/arena/guild tables pre/post).
  4. Record the matrix + inertness proofs in the S3 evidence pack; the Decision Log gets one entry per declined upstream fix class.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ master vs the H3-1 pin (exact delta measured at execution — not measurable offline in this planning pass).
- **Verification:** Drift matrix files exist with per-commit classification; the keep-off soak shows zero deltas on the asserted DB tables for both flavors; CI runs the drift-matrix freshness check (pin not stale by >N commits without a recorded decision).
- **Effort:** M
- **Depends on:** H3-1, H1-1, H3R2-1.
- **Owner stage:** new:S3

### H3R2-1 — Per-flavor aiplayerbot conf-key matrix with fail-loud staging

- **Goal:** `ServerRuntimeFiles` emits aiplayerbot conf only from the flavor's real key universe; an unknown or wrong-flavor key aborts staging with an actionable error, and the keep-off audit is re-baselined per flavor.
- **Current state:** The app emits the base bot conf from `BotProfile.playerbotConfig()` (`BotProfiles.kt:135-211`) plus the LLM block from `ServerRuntimeFiles.llmOverrides()` (`ServerRuntimeFiles.kt:525-614`). Plan S3 item 6 requires fail-loud unknown-key staging, which does not exist. The flavor divergence is real and mechanically derivable from the pinned tree: `aiplayerbot.conf.dist.in.tbc` vs `.in.wotlk` key-set diff shows wotlk-only `AiPlayerbot.ClassRaceProb.6.*` (DK) and wotlk-only `PremadeSpecGlyp.*` (verified `grep PremadeSpecGlyp` in the wotlk dist, absent in tbc), while tbc carries `PremadeSpecLink.*.70` where wotlk carries `.*.80`. **Register correction found during this planning pass:** the register's claim that the tbc dist lacks `PremadeSpecProb` is wrong — `aiplayerbot.conf.dist.in.tbc` contains 61 `PremadeSpecProb` rows (`grep -c 'PremadeSpecProb' aiplayerbot.conf.dist.in.tbc`); the matrix must be derived from the dists at build time, not from the register's list. Baseline key-universe counts (re-runnable, run this session on `playerbot/aiplayerbot.conf.dist.in{,.tbc,.wotlk}`): uncommented `key =` lines **3554/393/328** (`grep -cE '^[A-Za-z][A-Za-z0-9_. ]* *='`), unique top-level key families **93/79/79** (`grep -oE '^AiPlayerbot\.[A-Za-z]+' | sort -u | wc -l`).
- **Work items:**
  1. Add a generator (extend `tools/check_flavor.py` or a new `tools/gen_bot_conf_matrix.py`) that extracts the active key set from the flavor's conf dist at pin commit and writes it into `flavor.json.bot_policy.known_keys` (or a sibling per-flavor data file gated by `check_repo`). **The extraction rule is fixed and mechanical, so the gate is re-derivable — an "active key" is exactly one of:** (a) **dist rows** — the literal token before `=` on any *uncommented* line of the flavor-matching dist file (`aiplayerbot.conf.dist.in` for vanilla, `.in.tbc`, `.in.wotlk`; branch selection is the CMake `configure_file` choice at `native/playerbots/CMakeLists.txt:183-186`, so reading the flavor's own file *is* the post-substitution, post-branch-selection text) matching `grep -E '^AiPlayerbot\.[A-Za-z0-9.]+ *='` — `#`-prefixed rows are documentation, not parse surface — stored as **literal keys** (baseline: 393 literal keys for tbc, 328 for wotlk; 79 unique `AiPlayerbot.<Family>` prefixes each); plus (b) **code-built families** — keys `PlayerbotAIConfig.cpp` constructs at runtime via `ostringstream` prefixes and therefore never appear as whole dist rows: `GearProgressionSystem.<phase>.*` (`:757-771`), `PremadeSpecProb.<cls>.<spec>` (`:1142`), `PremadeSpecGlyp.<cls>.<spec>.<level>` (`:1190`) — stored as **prefix patterns** `AiPlayerbot.<Family>.*` derived by grepping the config-parse source per branch. The fail-loud check in item 2 accepts an emitted key only if it matches a literal from (a) or a prefix pattern from (b) for the qualified flavor; anything else aborts staging.
  2. In `ServerRuntimeFiles.kt`, before writing `aiplayerbot-<profile>.conf` (line 122-124 path), validate every emitted `AiPlayerbot.*` key against the qualified flavor's known set; unknown → `IllegalStateException` with the key name and flavor (fail-closed per plan invariant 2).
  3. Route the LLM-block keys through the same check — they enter via the anchor overlay `PB_LLM_CONFIG_HEADER_ANDROID` (`tools/build_o09_realm_runtime.py:1489-1530`), so the native parse surface for the LLM keys is overlay-defined and must be recorded per flavor alongside the dist-derived set.
  4. Re-baseline the keep-off audit (H3-4 step 3) against the per-flavor matrix; G3-7's vanilla golden-byte test keeps the vanilla emission untouched.
- **Upstream dependency:** H3-1 pin (the dists are in-tree at `native/playerbots-tbc/wotlk/playerbot/`).
- **Verification:** Negative test (R2-authored): append a fabricated `AiPlayerbot.NotAKey = 1` via a test profile → staging aborts naming the key; positive matrix test proves all `BotProfiles`-emitted keys parse for each flavor; vanilla byte-digest test still green.
- **Effort:** M
- **Depends on:** H3-1, H1-1; G3-7 (vanilla conf golden bytes).
- **Owner stage:** new:S3

### H3R2-2 — TBC dungeon/raid strategy inventory (flagship-first)

- **Goal:** TBC flagship dungeons have bot strategies with heroic compositions working, and the documented limit of what the generic fallback can clear is recorded and surfaced honestly.
- **Current state:** The pinned tree's authored encounter support is exactly Karazhan, Mechanar, and Naxxramas — `strategy/actions/KarazhanDungeonActions.{cpp,h}`, `MechanarDungeonActions.{cpp,h}`, `NaxxramasDungeonActions.{cpp,h}` and matching `strategy/generic/*DungeonStrategies.{cpp,h}` (verified by `find -iname "*karazhan*" -o -iname "*mechanar*" -o -iname "*naxx*"`). Everything else falls to the generic dungeon engine (Cthic/crowd-control-less zerg), which the register flags as degraded for TBC heroics (sibling gap G1-9: CC-trash). Heroic composition knobs (`AiPlayerbot.EnableOffSpecStrategies`, `botActiveAlone`, class/spec premade probabilities `PremadeSpecProb.*` verified in the tbc dist) exist but are untested on TBC instance geometry.
- **Work items:**
  1. Inventory-first: run bot groups through the TBC tier list on the H1-1 build (Hellfire Ramparts, Blood Furnace, Shattered Halls, Sethekk, Shadow Labyrinth, Mechanar, Botanica, Arcatraz, Black Morass, Old Hillsbrad; Karazhan as the raid), recording boss-by-boss clear/fail in a matrix doc (`docs/patches/bot-strategy-matrix-tbc.md`).
  2. Port/author flagship-first, where "flagship" = dungeons gating the player's progression (Shattered Halls heroic, Black Morass timed event — the latter is J1-7's cross-reference) and Karazhan: extend the existing `*DungeonActions` pattern (mechanic-named actions: interrupt/spread/move-out), plus heroic-mode compositions (tank+heal+3 DPS comps per `PremadeSpecProb`).
  3. Document the generic-fallback limit per dungeon in the matrix doc ("bots clear normal; heroic needs strategy X") and surface the honest copy in the app where relevant (ties to H5R2-2's raid story decision).
  4. Add the matrix to the S3 verification suite as a repeatable scenario (realm-test lane), not a one-off.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin (upstream may carry additional strategy files to cherry-pick — the drift matrix H3-4 governs).
- **Verification:** The strategy matrix doc shows the flagship set passing 3/3 runs per boss; non-flagship failures carry documented fallback-limit copy; soak tick-time regression within the admission budget (R4 evidence).
- **Effort:** L
- **Depends on:** H1-1, H3R2-1, H3-4; G1-9 (CC-assist decision), J1-7 (Black Morass).
- **Owner stage:** new:S3

### H5-2 — WotLK raid/dungeon strategy inventory + generic-fallback limits

- **Goal:** The WotLK strategy story is measured and documented end-to-end: Naxxramas works, the wotlk flagship dungeons are covered, and every uncovered raid carries an explicit "generic fallback ceiling" statement (feeding H5R2-2's honest raid story).
- **Current state:** Same tree inventory as H3R2-2 — the only authored WotLK raid support is `NaxxramasDungeonActions/Strategies`. Nothing exists for The Eye of Eternity, The Obsidian Sanctum, Ulduar, Trial of the Crusader, Onyxia's Lair, Vault of Archavon, or Icecrown Citadel (the G1-4/G1-5 Gunship/LK gaps live in §2.1 and are ported from TrinityCore there — this gap owns the *bot-side* inventory). WotLK premade spec knobs exist in the dist (`PremadeSpecGlyp.*.80`, `PremadeSpecProb.*` — verified by key diff).
- **Work items:**
  1. Run the same inventory matrix on wotlk (Naxx10/25, OS+twilight, EoE with and without vehicle AI per G1-7's decision, VoA, Onyxia, Ulduar frontline bosses, ICC frontline bosses up to the G1-4/G1-5 port boundary) — record clear/fail per boss in `docs/patches/bot-strategy-matrix-wotlk.md`.
  2. Author/port strategies flagship-first for Naxx-adjacent progress: OS (solo-dragon complications), VoA (short), Onyxia (deep-breath movement), then measure and document EoE/Ulduar/ICC against the §2.1 vehicle/encounter decisions rather than duplicating them.
  3. Heroic dungeon comps: HoR/Pit of Saron/Forge of Souls (the ICC trio gating the ICC attunement story), Utgarde pair, Nexus trio — matrix rows each.
  4. Emit the "what bots cannot do at this flavor" copy into the documented limits file consumed by H5R2-2's UI story.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin; TrinityCore ported encounter scripts land via §2.1 G1-4/G1-5 (server side) — bot strategies consume those scripts.
- **Verification:** Matrix doc complete per boss with 3-run evidence for the covered set; documented-limit entries for every uncovered encounter; Naxx25 clear by a 10-bot raid group within tick budget on RP6 (R4 evidence attached).
- **Effort:** L
- **Depends on:** H1-1, H3R2-2 (shared matrix harness), G1-4, G1-5, G1-7 (server-side encounter decisions), H5R2-2 (raid story).
- **Owner stage:** new:S3

### H3R2-3 — Bot gear pipeline: badges/emblems, periodic re-equip, diff ceiling

- **Goal:** Bots at the flavor's level cap wear expansion-appropriate gear: the re-equip loop runs periodically with era-correct weightscales, badge/emblem currency is acquired and spent (or its absence documented), and `RandomGearMaxDiff` ceilings are flavor-correct.
- **Current state:** The gear engine exists: `AiPlayerbot.RandomGearMaxDiff` parses with default 9 (`native/playerbots/playerbot/PlayerbotAIConfig.cpp:159`, field at `PlayerbotAIConfig.h:148`), `RandomGearUpgradeEnabled` default false (`PlayerbotAIConfig.cpp:160`) and the app pins it to 0 (`BotProfiles.kt:190`), weightscales/stat-weights live in `native/playerbots/playerbot/RandomItemMgr.h/.cpp`, and `PlayerbotFactory::InitEquipment()` (called at `PlayerbotFactory.cpp:269`) equips at creation only — no periodic re-equip on the current pin. Badge/emblem acquisition (TBC badges, WotLK emblems) has no dedicated bot lane in this tree.
- **Work items:**
  1. Measure-first: on the H1-1 builds, record what cap-level bots actually wear after N hours of questing (SQL: average item level by slot from `characters.character_inventory` × `item_template`), establishing the "degradation curve" the re-equip loop must fix.
  2. Enable the gear-upgrade lane per flavor behind a new profile knob (e.g. `randomGearUpgrade: Boolean` on `BotProfile`, emitted as `AiPlayerbot.RandomGearUpgradeEnabled = 1` only for flavors where the soak proves tick-time safety — measured-first law, mirroring the `BENCH_*` twin pattern in `BotProfiles.kt:862-904`), and add the periodic re-equip trigger (upstream may carry one — H3-4 drift matrix governs; otherwise fork commit in `RandomPlayerbotMgr`'s update loop).
  3. Badge/emblem lane: verify bots acquire TBC heroic badges / WotLK emblems through the dungeon loop in the H3R2-2/H5-2 matrices; if acquisition is dead (no dungeon queued — `RandomBotJoinLfg = 0`), decide spend-vs-quest-only and record; set `RandomGearMaxDiff` per flavor from measured ilvl spreads, not the vanilla default 9.
  4. All new keys go through the H3R2-1 known-key matrix.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin.
- **Verification:** Re-equip soak shows the ilvl curve rising then holding (numbers archived); zero tick-budget regression; `RandomGearMaxDiff` value per flavor recorded with its measurement; vanilla emission unchanged.
- **Effort:** M
- **Depends on:** H1-1, H3R2-1, H3R2-2/H5-2 (dungeon context for badges).
- **Owner stage:** new:S3

### H3R2-4 — Riding automation: flavor thresholds, flying budget fix, CWF at 77

- **Goal:** `BudgetValues` riding math is flavor-correct and not arithmetically broken: bots budget for training/skill/mounts per expansion, WotLK bots purchase Cold Weather Flying at 77, and the flying-budget bug is fixed on all branches.
- **Current state:** `native/playerbots/playerbot/strategy/values/BudgetValues.cpp:251-291` carries per-expansion branches (`MANGOSBOT_ZERO`: basic 40/epic 60, flying zeroed; `MANGOSBOT_ONE`: 30/60, flying 70/70; `MANGOSBOT_TWO`: 20/40, flying 60/70 — this is the register's "hardcodes basic 40/epic 60, flying zeroed": true only as the MANGOSBOT_ZERO compile). The flying legs at lines 286-289 are swapped-variable bugs: `if (level >= flyingMountCost …)` compares a *level* to a *cost* (100×GOLD → never true), and `moneyWanted += basicFlyingRidingLevel` adds a *level number* (70) as copper — so every flavor's flying budget is effectively zeroed regardless of branch. Line 290 carries upstream's own `//todo Wotlk frozen weather flying.` — CWF (1000g at 77 in 3.3.5) is unmodeled.
- **Work items:**
  1. Fork commit on the H3-1 branches fixing the comparisons: `moneyWanted += flyingMountCost` gated on `level >= basicFlyingRidingLevel` (and the epic leg symmetrically), preserving the per-branch cost constants.
  2. Add the WotLK CWF leg under `MANGOSBOT_TWO`: at level ≥ 77, budget `coldWeatherFlyingCost` (1000g) when the bot lacks spell 54197, honoring the tome path decision if the player-economy side picks one (H1R2-7).
  3. Re-tune the `MANGOSBOT_ONE/TWO` cost constants against the flavor DB's actual trainer prices (tbc: 600g/100g riding tiers; wotlk: expert 600g/artisan 5000g journeyman paths) — read from `tbc-db`/`wotlk-db` `npc_trainer`/`trainer_template` at execution, not from memory.
  4. Because the gold walls interact with economy tuning, cross-link H1R2-7 (player gold walls 5000g/CWF/dual-spec) so bot budgets and player walls are tuned in one pass.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin (fix lands as Pocket Realm fork commit; upstream PR optional).
- **Verification:** Unit-style host test on `BudgetValues` math (the file compiles host-side in the strategy tests dir); device check: a fresh level-77 wotlk bot acquires flying within its budget window (DB spell assert + gold ledger delta); tbc bot at 70 owns a flying mount after budget accrual.
- **Effort:** S
- **Depends on:** H3-1, H1-1; H1R2-7 (gold-wall tuning), H1-10 (flying lifecycle tests).
- **Owner stage:** new:S3

### H3R2-5 — Bot professions: accept creation-only or add a bounded leveling tick

- **Goal:** A recorded decision — accept creation-only professions (with documented user-facing copy) or land a bounded crafting/materials tick — and per-flavor profession caps (300/375/450) verified present at creation.
- **Current state:** `PlayerbotFactory::InitTradeSkills()` (`PlayerbotFactory.cpp:3882-3960+`) assigns two professions per bot with expansion-aware pools (`#ifdef MANGOSBOT_ZERO` gates jewelcrafting out, `MANGOSBOT_TWO` adds DK blacksmithing), persisted via `sRandomPlayerbotMgr.SetValue(bot, "firstSkill"/"secondSkill")`; `UpdateTradeSkills()` (`PlayerbotFactory.cpp:4107-4113`) resets value-1 skills. There is no crafting/leveling tick — professions are set at creation and never advance; nothing in the conf dist exposes a profession-leveling knob.
- **Work items:**
  1. Decide and record in `docs/DECISIONS-PORT.md`: the register allows either accept+document or a bounded leveling tick. Recommendation to record: accept creation-only for launch (crafting AI is a large surface with poor payoff) and document; re-open if the H3R2-3 gear pipeline shows bots needing crafted items.
  2. Verify creation correctness per flavor: spawn 50 bots per flavor, assert every bot has ≤2 primary professions with skill caps at the flavor ceiling (300/375/450 — from the flavor core's skill maxima) and that no vanilla-pool leak exists (no jewelcrafting on vanilla flavor bots).
  3. If (and only if) the decision goes the other way: implement a materials-bounded leveling tick gated by a new conf key added to the per-flavor dist overlay AND the H3R2-1 matrix — never an unparsed key.
  4. Document the accepted limitation in the flavor's honest-copy surface (same file the H5-2 fallback limits feed).
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin (no upstream work for the accept path).
- **Verification:** The 50-bot creation assert per flavor green; the decision entry exists with rationale; (conditional) leveling-tick soak with materials-bounded progress curve if authored.
- **Effort:** M
- **Depends on:** H1-1, H3R2-1.
- **Owner stage:** new:S3

### H3R2-6 — LLM memory gate: KV budget vs 320-bot RSS on a 12 GB shared device

- **Goal:** The LLM lane's worst-case memory footprint is measured on RP6 at the default 320-bot population, the per-flavor corpus token budget is capped so prompts never overflow the configured context, and admission floors encode the measured numbers.
- **Current state:** The runtime allocates `n_ctx = llmCtxSize × slotCount` (`native/patches/playerbots/PlayerbotLlamaRuntime.cpp:117`), with the tuned E2B at 12288 tokens × 2 slots and Qwen at 6144 × 2 (`android/app/src/main/java/com/pocketrealm/llm/LlmModelRegistry.kt:98,126` — the register's "12288/6144"); the debug-block default is `LLMCtxSize = 4096`, `LLMSlots = 4` (`ServerRuntimeFiles.kt:611-612`), and `LLMEnabled = 0` ships dark in every profile's base conf (`BotProfiles.kt:209`). KV cache for Gemma-4 E2B at 24k total tokens is the dominant add-on RAM cost; the 320-bot default profile is `ALIVE_REALM_320` (`BotProfiles.kt:992`); the P1-2/P1-4 measurement milestone (§2.1) owns the general memory envelope but this gap owns the LLM-lane share and the corpus budget. The corpus is 585 cards (`android/app/src/main/assets/lore/lore_cards_v112.jsonl`, `wc -l` = 585) retrieved per-prompt, so v303's larger corpus grows prompt tokens, not KV.
- **Work items:**
  1. Extend the RP6 measurement milestone: with `ALIVE_REALM_320` and the LLM enabled (external lane AND in-process lane separately), sample mangosd RSS every 30 s for 2 h (`tools/battery_sampler.py` pattern); record llama-lane RSS share for E2B-12288×2 and Qwen-6144×2.
  2. Add a prompt-token budget check per flavor: compute the max renderable prompt (identity + seasoning + facts + memories + lore cards + reply cap) against `contextLength` from `LlmModelRegistry`, and cap the lore-card retrieval count so v204/v303 corpora cannot overflow — the cap is data in `flavor.json.llm` (new field `max_prompt_tokens`), not code.
  3. Encode measured floors: update `BotAdmissionLimits.minFreeMemoryMiB` guidance for LLM-on profiles (Kotlin constants + docs), and record the gate in the Decision Log.
  4. Cross-check the overflow guard already in the runtime: `PlayerbotLlamaRuntime.cpp:551-552` refuses decode past per-slot context — the test must prove the cap prevents ever reaching that failure path.
- **Upstream dependency:** none (measurement + app-side caps; llama.cpp pin `6d054983…` per `native/llm/lockfile-arm64-v8a.json` stays).
- **Verification:** Archived RSS curves for both models at 320 bots; a corpus-cap unit test (synthetic oversized corpus → retrieval truncated, no decode failure in 1 h soak); admission floor numbers recorded in the flavor docs.
- **Effort:** M
- **Depends on:** P1-2/P1-4 (measurement milestone), H3-7 (corpus sizes), F1-9 (corpus packaging).
- **Owner stage:** new:S3

### H3R2-7 — Speech-trigger qualification per flavor (whisper/say/party on 8606/12340)

- **Goal:** Every conversation trigger lane (whisper always; say/party when addressed by a real player; general/trade/yell never) is proven live on both new flavor clients, with per-branch channel-source resolution pinned.
- **Current state:** The trigger law is pure and host-tested: `PlayerbotLlmGates.h:66-85` (`HardTriggerAllowed`) with `GATE_SRC_*` mirroring `ChatChannelSource` from `native/playerbots/playerbot/PlayerbotAI.h:120-141` (SRC_SAY=8, SRC_WHISPER=9, SRC_PARTY=13, SRC_RAID=14, SRC_UNDEFINED=15) and `GetChatChannelSource()` at `native/playerbots/playerbot/PlayerbotAI.cpp:2930+` resolving via `ChatChannelId` — which itself diverges per branch (`PlayerbotAI.h:105-118`: `LOOKING_FOR_GROUP = 24` under `MANGOSBOT_ZERO` vs `26` otherwise; `GUILD_RECRUITMENT = 25`). Delivery layers are `LAYER_MURMUR/PARTY/GLOBAL/FLOOR` (`native/patches/playerbots/PlayerbotLlmChatterCore.h:65-68`), and the GLOBAL layer's General-channel join carries a branch-split API call already (`native/patches/playerbots/PlayerbotLlmChatter.cpp:754-758` — `#ifdef MANGOSBOT_ZERO` at :754, `GetJoinChannel(nameBuf)` at :755, `GetJoinChannel(nameBuf, channel->ChannelID)` at :757; grep-verified this session). None of this has executed against an 8606/12340 client.
- **Work items:**
  1. Re-derive the `ChatChannelId`/`ChatChannelSource` tables per branch from the H3-1 trees and re-pin the static_assert bridges (H3-11) — including any channel-id drift in the TBC/WotLK core's `ChatChannelsEntry` DBC.
  2. Device qualification script (rp_harness): for each flavor, drive a real client through whisper → expect bot reply within the passive-delay bound (`BotLlmSpeech`/`passiveDelayMs = 3_000` on experience presets, `BotProfiles.kt:58`), say with bot name addressed, party line addressed, and negative lanes (yell, trade, unaddressed party) → expect silence; assert via `world.log` req-id markers (the delivery observability anchors `PB_SDP_*`/`PB_SAY_DELIVER` in `tools/build_o09_realm_runtime.py`).
  3. Verify the General-channel naming pattern per locale/build via `sChatChannelsStore` pattern lookup on-device (zone-scoped General differs subtly across expansions) and record any pattern mismatch as a fork fix.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin (branch-switched code is in-tree).
- **Verification:** The rp_harness trigger matrix green on 8606 and 12340 (both lanes), with logged req-id evidence; negative lanes proven silent 20/20 attempts.
- **Effort:** M
- **Depends on:** H1-1, H3-11 (enum pins), S7 (client runtime for the device leg).
- **Owner stage:** new:S3

### H3R2-8 — Commander surface: flavor-blind clamps and the .bot cheat-sheet

- **Goal:** The Bots screen and profile identity system use the qualified flavor's legal ranges (no 60-level assumptions), and a per-flavor command cheat-sheet ships so the player knows how to drive bots in-game.
- **Current state:** Two vanilla-hard-codings exist in the identity layer: `BotProfile` requires `syncLevelNoPlayer in 1..60` (`android/app/src/main/java/com/pocketrealm/bots/BotProfiles.kt:123`) and the advanced-identity target window is `target in 25..700` (`BotProfiles.kt:1507`) — the level clamp is a vanilla-cap constant, the target band is fine but worth a flavor note. The commander UI is `android/app/src/main/java/com/pocketrealm/ui/BotsScreen.kt` (2100 lines; leveling section at 1455+, steppers at 1266-1447). No `.bot`-command cheat-sheet surface exists in the app.
- **Work items:**
  1. Replace the 1..60 clamp with the qualified flavor's `bot_policy.max_level` (the same `ClientFlavor` projection `flavorBotMaxLevel()` already uses at `BotProfiles.kt:216`) — keeping vanilla validation semantics identical (G3 guard: the same value 60 for vanilla).
  2. Sweep `BotsScreen.kt` and `BotAdvancedSettings` for any other cap-flavored ranges (level-sync steppers, `RandomBotMaxLevel`-adjacent copy); flavor-aware strings via the S7 copy-sweep pattern.
  3. Add a cheat-sheet card (Bots screen footer + docs/wiki): the sanctioned in-game command set (`llm` triggers, follow/stop behaviors surfaced through the app, whisper-addressing rules from `PlayerbotLlmGates.ContainsNameIgnoreCase` semantics — "say the bot's whole name"), generated from one per-flavor data table so copy can't drift from the conf.
  4. Adv/usr5 identity digests: the level-clamp change must not alter any existing identity encoding for vanilla (the clamp is validation-only, but prove it with the adv4 digest tests in `BotProfiles.kt:1442-1451` catalogs).
- **Upstream dependency:** none.
- **Verification:** Unit tests: wotlk-qualified lane accepts `syncLevelNoPlayer = 80`, vanilla rejects it exactly as today; identity-digest tests green unchanged; cheat-sheet copy asserts against the conf data in a test.
- **Effort:** S
- **Depends on:** H3R2-1 (flavor registry plumbing); G3-7 (vanilla identity digests).
- **Owner stage:** new:S3

### H3R2-9 — Bot guild keys per-flavor audit; keep-off defaults proven

- **Goal:** The guild-growth key set is audited per flavor against the real parse surface, shipped defaults stay keep-off with proof, and the audit is wired into the fail-loud conf check.
- **Current state:** The app pins guild growth off: `AiPlayerbot.GuildOrderAlwaysActive = 0`, `AllowGuildBots = 0`, `RandomBotFormGuild = 0`, `RandomBotGuildCount = 0`, `RandomBotArenaTeamCount = 0` (`BotProfiles.kt:159,184-193`). Upstream defaults are permissive (`randomBotFormGuild` default **true** at `native/playerbots/playerbot/PlayerbotAIConfig.cpp:620`; the wotlk dist ships `RandomBotGuildCount = 20` at `aiplayerbot.conf.dist.in.wotlk:95`) — the app's keep-off is the only fence. The guild machinery itself is upstream code: `strategy/actions/GuildCreateActions.h` (BuyPetition/PetitionOffer/PetitionTurnIn/BuyTabard), `BuyGuildBankTabAction.{cpp,h}`, `GuildShareAhBuyAction.cpp`, and the fork's own `GuildManageNearbyAction` work (fork HEAD commit "E2: register audit — six worst-offender inline literals in GuildManageNearbyAction", verified via `git log`). **Register-precision note:** the register's key names "CreateRandomGuilds/GuildBank/BuyGuildBankTab" do not exist as conf keys at this pin — the real conf keys are the `RandomBot*Guild*`/`AllowGuildBots` family plus code-side actions; the audit must use the real names.
- **Work items:**
  1. Grep the H3-1 branch trees for every guild-family conf key parsed by `PlayerbotAIConfig.cpp` (both branches) and the strategy registrations referencing them; emit the list into the H3R2-1 matrix with `keep_off` annotations.
  2. Extend the staging fail-loud check to reject any emission of a `keep_off` key with a non-zero value (defense in depth beyond `playerbotConfig()`'s literals).
  3. Negative soak per flavor: 1 h with a fresh pool, assert zero new rows in `guild`/`guild_member`/`petition` tables beyond the player-owned ones.
  4. Record the guild-growth keep-off rationale in the flavor docs (ties to J1-1 for the player-driven guild lane).
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin.
- **Verification:** The keep-off soak SQL delta is zero on both flavors; a mutation test flipping a keep-off value fails staging; the key matrix lists the audited set per flavor.
- **Effort:** S
- **Depends on:** H3-1, H3R2-1; J1-1 (the sanctioned player-guild lane).
- **Owner stage:** new:S3

### J1-1 — Guild formation for the player: sanctioned bot-signature lane or honest out-of-scope

- **Goal:** Decision point 9 (register §4) is resolved through the **owner treatment in `integration-coexistence.md` J1-1** (option A sanctioned bot signatures / option B `MinPetitionSigns = 0` conf lever / option C honest out-of-scope copy), with this section carrying only the S3-side hooks: per-flavor key verification, H3R2-1 matrix registration, and the keep-off fence.
- **Current state:** **Cross-reference — `integration-coexistence.md` J1-1 owns the mechanism and its upstream inventory is the deeper evidence**: the full player-guild chain already ships in the pinned tree (`PetitionSignAction.cpp:1-40`, `GuildCreateActions.cpp` BuyPetition `:75` / PetitionTurnIn `:298` / BuyTabard `:349`, charter item 5863 in `GuildValues.h:274`), `allowGuildBots` gates bots sitting in the *master's* guild (`PlayerbotMgr.cpp:540`) — so option A is a pure conf flip, **no fork commit and no new `PocketRealm.*` conf key** (this section's earlier `PocketRealm.PlayerPetitionBots`-style fork proposal is withdrawn; roadmap DP9 now states the same single mechanism). Core facts verified against the classic tree: charter math `MinPetitionSigns` default 9, max 9 (`native/cmangos/src/game/World/World.cpp:589`, checked in `src/game/Entities/PetitionsHandler.cpp:558`) — the register's "9 signatures"; the app keeps the guild-growth family off (`BotProfiles.kt:159,184-193`: `GuildOrderAlwaysActive = 0`, `AllowGuildBots = 0`, `RandomBotFormGuild = 0`, `RandomBotGuildCount = 0` against upstream defaults `true`/`true`/20 at `PlayerbotAIConfig.cpp:620/:503/:191`, per the owner section). The wotlk core's `MinPetitionSigns` default/max cannot be checked locally — no `native/cmangos-wotlk` tree exists yet; the owner section defers it to S2.
- **Work items:**
  1. Adopt the owner's option set verbatim (integration-coexistence.md J1-1): recommended **A** — `AllowGuildBots = 1` via a `guildSignatures` knob on `BotProfiles` that flips only that key, `RandomBotFormGuild`/`RandomBotGuildCount` staying 0; fallback **B** — emit `MinPetitionSigns = 0` from `ServerRuntimeFiles.worldConfig` for opted-in flavors; **C** — honest copy. Record at S3 as DP9.
  2. Register whichever lever ships in the H3R2-1 per-flavor known-key matrix (option A's `AllowGuildBots` and option B's `MinPetitionSigns` are both existing upstream keys — the matrix records them as permitted-with-decision, not keep-off).
  3. Verify the key spellings on the tbc/wotlk playerbots trees at S3 — the owner section grepped only the classic tree (`PlayerbotAIConfig.cpp`, `PetitionSignAction.cpp` under MANGOSBOT_ONE/TWO).
  4. Keep the bot-initiated guild keep-off fence intact in the same audit (H3R2-9 untouched) and add the golden-conf guard for vanilla (option changes must not shift vanilla's byte-identical emission unless the decision explicitly changes vanilla too).
- **Upstream dependency:** pinned playerbots `puzzled-pancake/playerbots@7e2cd2fb` (`PetitionSignAction.cpp`, `GuildCreateActions.cpp`, `PlayerbotAIConfig.cpp:191,503,620`) — per integration-coexistence.md J1-1's inventory; flavor cores' `MinPetitionSigns` existence confirmed at the S2 pin.
- **Verification:** executed per the owner section's options on device (A: player charter reaches 9/9 bot signatures → `PetitionTurnInAction` succeeds → guild row exists with the player as guild master, and the staged conf still shows `RandomBotFormGuild = 0`/`RandomBotGuildCount = 0`; B: solo charter turns in with `MinPetitionSigns = 0` staged; C: R6 copy review), plus this section's additions: the H3R2-1 matrix entry exists for the shipped lever and the per-flavor key-spelling grep is archived.
- **Effort:** S
- **Depends on:** integration-coexistence.md J1-1 (DP9 owner), H3R2-9 (keep-off fence), H3R2-1 (conf matrix).
- **Owner stage:** new:S3

### H3-7 — Lore corpora v204/v303: provenance, licensing, target counts

- **Goal:** TBC and WotLK lore card corpora are built with the same era-scrub discipline as v112, each card carries provenance, the wiki harvest's license terms are recorded in an attribution manifest, and per-flavor card counts meet recorded Outland/Northrend arc targets.
- **Current state:** The v112 pipeline is `tools/llm_lab/build_lore_cards.py`: it reads the harvested `warcraft.wiki.gg` pages from `LLM_LAB_LORE_CORPUS` (env-gated, lines 31-36), scrubs to "the Vanilla 1.12 frame (Year 24)", era-lints before writing, and emits `android/app/src/main/assets/lore/lore_cards_v112.jsonl` (585 cards verified). The scrub policy encodes vanilla-era decisions (`EXPANSIONS` markers, `DROP_SECTIONS`, lines 40-60+); the C++ side re-lints the shipped file at load (`PlayerbotLlmTruthCore.h:635-641`, `EraLintCards`, dropped-count accessor at :671). No v204/v303 corpus, no attribution manifest, and no licensing record exist; `flavor.json.llm.lore_cards` is `null` for both new flavors (`schemas/flavor.json:132-135,193-196`).
- **Work items:**
  1. Parameterize the builder: `--flavor {vanilla,tbc,wotlk}` with per-flavor scrub frames (TBC: Year 25, allow Outland/flying/Burning-Crusade-legitimate concepts, ban WotLK+; WotLK: Year 26, allow Northrend/DK, ban Cataclysm+), reusing the existing corrected-era policy structure (never bare-ban earlier-legitimate terms — the builder's documented law).
  2. Provenance per card: extend the emitted JSON line with `{"src":"warcraft.wiki.gg/<Page>","rev":"<harvest date>"}`; the C++ loader must tolerate the extra keys (verify `ParseLoreCards` in `PlayerbotLlmTruthCore.h` — it is the only loader).
  3. Attribution/licensing manifest: `android/app/src/main/assets/lore/CORPUS-SOURCES.md` recording the harvest source, its license terms (read from the wiki at execution — do not assume), build command, and per-flavor counts; gate it in `tools/check_repo.py`.
  4. Target counts: define and record floors (e.g. ≥150 Outland-arc cards for TBC, ≥200 Northrend-arc for WotLK, plus shared base set), asserted by a lint test.
  5. Feed the H3R2-6 token budget with the measured per-flavor corpus sizes.
- **Upstream dependency:** none (local harvest corpus; the wiki is the upstream source of record — record its exact licensing at execution).
- **Verification:** `build_lore_cards.py --flavor tbc|wotlk` produces corpora passing the C++ load-time era lint with `EraLintDropped() == 0`; count floors asserted; attribution manifest present and checked by `check_repo.py`.
- **Effort:** L
- **Depends on:** H3-8 (era-lint per-flavor lists), H3R2-6 (budget), F1-9 (packaging).
- **Owner stage:** new:S3

### H3-8 — Era lint: externalize per-flavor lists; corpus-driven tests; zone-aware decision

- **Goal:** The always-ban/sense-phrase era lists live in per-flavor data files (not compile-time arrays), every flavor's lint passes corpus-driven tests (no anachronisms AND no false bans), and the "wotlk bot mentions Elwynn" zone-vs-era question has a recorded decision.
- **Current state:** The era lists are C statics: `EraAlwaysBanTerms()` (`native/patches/playerbots/PlayerbotLlmTruthCore.h:824-833` — "shattrath", "draenei", "pandaren", "acherus"), `EraSensePhrases()` (:835+, "flying mount(s)", "portal is open", expansion titles…), and pseudo-entity tables `eraPeople`/`eraThings` (:462-463); the reply scanner is `EraHit` (:865-871+). The Python mirror is `EXPANSIONS` + drop lists in `tools/llm_lab/build_lore_cards.py:40-60+`. All are vanilla-frame. The host battery (`tests/test_llm_truth.py`, run via `tools/test_llm_truth.cpp`) pins the wired behavior (`test_era_bias_splice_and_filter_stack_wired_into_generate` at :127 proves `PlayerbotLlmFilters::EraBiasJson()` reaches `Generate`), and an era logit-bias switch exists in config (`llmEraBias`, header overlay at `tools/build_o09_realm_runtime.py:1505-1506`).
- **Work items:**
  1. Externalize: define `lore/era-lint-{vanilla,tbc,wotlk}.json` (two lists + pseudo-entity tables + sense-phrase pairs with their negation-escape flags); load them via a new staged file conf key (follow the `LLMLoreFile` staging pattern in `ServerRuntimeFiles.kt:589-590`), compiled-fallback to the current statics for vanilla so a missing file cannot change vanilla behavior.
  2. Per-flavor curation exactly per the plan's S3 item 4 rule (earlier-legitimate terms never banned): TBC unbans shattrath/draenei/flying senses; WotLK unbans acherus/death-knight senses; all three ban Cataclysm+ senses.
  3. Corpus-driven tests: for each flavor, a probe list (R5-curated) run through the host scanner — anachronism probes must hit, era-legitimate probes must pass (including "Elwynn" from a wotlk bot); wire into `tests/test_llm_truth.py`.
  4. Zone-aware lint decision (record in the Decision Log): recommendation — lint stays flavor-global (a wotlk bot may say "Elwynn"; a vanilla bot may not say "Netherstorm"), NOT zone-scoped: zone-scoping buys nothing (travel is real) and costs a map-id thread through the pure core.
- **Upstream dependency:** none.
- **Verification:** Per-flavor host lint tests green with zero false bans on the probe list; vanilla file byte-equivalent to the statics (golden test); a TBC-flavored reply containing "death knight" passes while "Lich King" is masked per the flavor's list.
- **Effort:** M
- **Depends on:** H3-7 (corpora feed the same lint), F3-2 (staging twin).
- **Owner stage:** new:S3

### F3-2 — Era-lint data files staged by ServerRuntimeFiles; provenance + G3 guards

- **Goal:** The era-lint data files from H3-8 are staged, provenance-locked, and vanilla-safe: they ride the same `ServerRuntimeFiles` staging discipline as the lore cards, are digest-locked per flavor, and cannot regress the vanilla lane's behavior or assets.
- **Current state:** The staging pattern to reuse is `stageLoreCards()` (`ServerRuntimeFiles.kt:318-332`: asset `lore/<name>` → run-dir temp file → atomic rename, run `runCatching` fail-open so a lore problem never fails world start) gated on the LLM block being emitable (`ServerRuntimeFiles.kt:194-201`). Provenance for staged runtime files is the `patches_content_digests()` walk (`tools/build_o09_realm_runtime.py:201+`) plus `check_repo`; the vanilla-regression hazards are G3-7 (golden conf bytes) and G3-8 (lore asset name kept for vanilla) — the era-lint file must not become a quarantine trigger for existing vanilla users.
- **Work items:**
  1. Add `stageEraLintFile()` mirroring `stageLoreCards()` (same temp+rename discipline, same LLM-enabled gate), emitting `AiPlayerbot.LLMEraLintFile = "<abs path>"` alongside the lore line.
  2. Read the per-flavor file name from `flavor.json.llm` (new `era_lint` field, vanilla = the compiled-fallback sentinel so vanilla stages nothing and behaves identically — retiring the D15-style duplication hazard before it forms).
  3. Digest-lock: record sha256 of each per-flavor era-lint file in the flavor's provenance set; `tools/check_repo.py` asserts the shipped asset digests match.
  4. G3 guards: golden test proving the vanilla lane emits NO era-lint line and no new asset file lands in the vanilla run dir; leak-sweep v2 (D18 whole-tree scope) must classify the new files as owning-table data, never banned literals.
- **Upstream dependency:** none.
- **Verification:** Staging unit tests per flavor (file lands, conf line present, corrupt-asset fails open to compiled fallback with the startup log line); vanilla diff test shows zero staging delta; `check_repo` green with digest lock.
- **Effort:** M
- **Depends on:** H3-8 (the lists), G3-7/G3-8 (guards).
- **Owner stage:** new:S3

### H3-9 — Persona pools: blood-elf/draenei/DK personas per flavor

- **Goal:** Bot persona selection (verbal tics, voice bibles, authored pools) covers the new flavors' new race/class space, so a blood-elf warlock and a draenei shaman don't speak with vanilla-human pool texture only.
- **Current state:** The persona engine is `native/patches/playerbots/PlayerbotLlmPersona.cpp` (authored pools selected via the mutex-guarded `BanterState` map, verbal tics via `TicOf/ShouldTic/TicPrefix` at lines 51-77) and the four trained voice bibles referenced at `LlmPromptPack.kt:252-255` ("gruff/wry, loud/boastful, soft/precise, quiet/dry … picked by stable id hash"). Pools are authored per *situation* (kill/greet/ambient/busy), not per race/era — nothing prevents a draenei from drawing vanilla-flavored lines, but no BE/draenei/DK-specific persona cells or tics exist. Character names come from the factory/core name generator (`RandomPlayerbotFactory.cpp:31-32` name mutex/init), whose new-race coverage is unverified for the wotlk DK path.
- **Work items:**
  1. Verify name generation for BE/draenei/DK on the H1-1 builds: factory-batch 200 chars per new race, assert names pass `llm_banter_core.h:47` `LineIsValid` (printable ASCII, ≤200) and the core name rules; fix leaks (e.g. apostrophe-heavy names hitting the banter parser) as fork commits.
  2. Author per-race persona cells: add BE (haughty/elegant tics), draenei (formal/accented), DK (cold/sepulchral) entries to the persona tables in `PlayerbotLlmPersona.cpp` keyed by race/class, gated per branch (`MANGOSBOT_ONE/TWO`) so vanilla selection is untouched.
  3. Extend the id-hash bible selection only if a new bible is added — otherwise reuse the four trained ones (trained weights must not see unseen shapes; note this constraint in the decision).
  4. Host tests: pool-exhaustion and determinism tests extended to the new pools (`tests/test_llm_persona_lock_scope.py` pattern).
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin (name generator); persona content is Pocket Realm authored.
- **Verification:** Deterministic persona tests cover new races; device spot-check: 10 BE bots speak with the authored cell hit-rate in a 30-min soak (log evidence); vanilla pools byte-identical (host snapshot test).
- **Effort:** S
- **Depends on:** H1-1, H3-10 (banter topics ride the same pools).
- **Owner stage:** new:S3

### H3-10 — Banter topics per flavor (Outland/Northrend arcs)

- **Goal:** Authored banter (greetings, combat/loot beats, ambient topics) includes expansion-appropriate topics so TBC/WotLK worlds don't gossip exclusively about vanilla places and things.
- **Current state:** `llm_banter_core.h` is the pure authored-line core ("fallback beats, greetings, combat/loot banter" — header lines 2-11, 2051 lines); every line must pass `LineIsValid` (:47+: ≤200 chars, printable ASCII, no protocol bytes, `{P}/{B}` placeholders only). The rumor POI surface is the frozen vanilla 40-entry list (`PlayerbotLlmChatter.cpp:229-249`, owned by H3-3's split) and the gossip DB pool is `world_gossip` (`native/llm/sql/world_gossip.sql`, seeded via `tools/stage_database_migrations.py:141`). No expansion topic rows or beats exist.
- **Work items:**
  1. Author per-flavor topic rows: Outland set (Dark Portal, Shattrath, the Nail in Hellfire, Mana-Tombs rumors, Aldor/Scryer) and Northrend set (the fury at the top of the world, Dalaran's settling, Argent vanguard, Helheim tales), each ≤200 chars, ASCII, placeholder-law compliant; add them as branch-gated pools (`MANGOSBOT_ONE/TWO`) in the banter pools file with the vanilla pools untouched.
  2. Seed the per-flavor `world_gossip` pool: extend the staging to a flavor-qualified gossip SQL (`native/llm/sql/world_gossip_tbc.sql` / `_wotlk.sql`) selected by `tools/stage_database_migrations.py` per flavor (the current explicit `Input("playerbot-world", …)` selection at line 141 becomes flavor-routed).
  3. Host tests: every new line passes `LineIsValid` and the dedupe rings (`tests/test_llm_banter_pools_e0.py` pattern); era-lint cross-check (a TBC topic must not carry WotLK+ senses — reuse H3-8's scanner).
- **Upstream dependency:** none.
- **Verification:** Host pool tests green per flavor; soak evidence shows expansion topics delivered (log grep for two authored topic keys per flavor); vanilla pool snapshot unchanged.
- **Effort:** S
- **Depends on:** H1-1, H3-8, H3-9; F1-9 (staging path).
- **Owner stage:** new:S3

### H3-11 — ChatMsg/ChatChannelSource enum static_asserts per branch

- **Goal:** Every chat-enum literal the LLM lane embeds is build-pinned per branch: an upstream renumber fails the compile, and the per-channel quota weights (F3-9's twin) key off the pinned symbols, never raw ints.
- **Current state:** `PlayerbotLlmGates.h:31-41` mirrors `ChatChannelSource` as `GATE_SRC_PARTY = 13, RAID = 14, UNDEFINED = 15, SAY = 8, WHISPER = 9, TRADE = 3, GENERAL = 2, YELL = 12` with the documented contract "bridged by static_asserts at the call site … so an upstream renumber fails the build" (header lines 10-13, 29-30) — the bridges live at the overlay-anchored SayAction sites (`PB_SAY_GATE` anchor family, `tools/build_o09_realm_runtime.py` ~5910). The source enum is `native/playerbots/playerbot/PlayerbotAI.h:120-141` (values match the mirror today), and the wire-level `ChatMsg` enum is `native/cmangos/src/game/Globals/SharedDefines.h:1507+` (SAY=0x00 … CHANNEL=0x0E) — a plan-§7 pinned artifact (`SharedDefines.h:1968`) whose per-branch text differs. None of the bridges have ever compiled against MANGOSBOT_ONE/TWO.
- **Work items:**
  1. Make the bridges explicit and per-branch: a `llm_enum_pins.h` in the overlay set carrying `static_assert((int)SRC_SAY == 8, …)` etc. for every mirrored value, included by the SayAction anchor sites on all three branches — so the assertion exists even where the anchor text shifts.
  2. Add wire-level pins for the ChatMsg values the gates actually consume (SAY/PARTY/RAID/WHISPER/YELL/CHANNEL) against each branch's `SharedDefines.h` (asserted values are stable 0x00-0x0E across all three, but the assert is what makes future drift loud).
  3. Sweep the overlay set for any remaining raw-int chat literals (grep `= 13|= 8|= 9` in `native/patches/playerbots/`) and route them through the pins.
  4. Host-test note: `tools/llm_lab/s10_chatter_gates.py`/`tests/test_llm_gates.py` test the pure core with the same literals — add a test that fails if the C++ pins and the Python fixtures diverge.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin.
- **Verification:** The three branches compile with the pins (H1-1 matrix green); an injected off-by-one in the mirror fails the build (R2 negative compile test); cross-language parity test green.
- **Effort:** S
- **Depends on:** H1-1; F3-9 (consumes the pins).
- **Owner stage:** new:S3

### F3-9 — Per-channel speech-quota weights per flavor + starvation soak

- **Goal:** Ambient speech quotas (murmur/party/global layers and the cloud-lane quotas) carry per-flavor weights so a busy expansion world doesn't starve any lane, proven by a starvation soak that measures per-layer delivery rates.
- **Current state:** The quota machinery is layered: per-line fatigue with a daily quota (`FatigueAdmits`/`FatigueRecordTelling`, `PlayerbotLlmChatter.cpp:723,788-789`), roster-level cloud quotas (`CloudQuotaAdmits("saga", llmSagaPerDay)` at :1088, `("roundtable", …)` at :1177), the player-owns-the-channel yield (:725-726, `PlayerHoldsChannel` grep-verified this session), and the four delivery layers (`PlayerbotLlmChatterCore.h:65-68`) with rung gating (`RUNG_OFF/EMERGENCY/…/NORMAL`, :50-59). The memory-side quota ledger documents "the same documented reset semantics every quota carries" (`PlayerbotLlmMemory.cpp:469`). All weights are global constants; nothing is per-flavor, and no starvation measurement exists for an expansion-density world.
- **Work items:**
  1. Make layer weights conf-read per flavor: extend the appended LLM conf block (`ServerRuntimeFiles.llmOverrides` → `LlmRuntimePolicy.confBlock` in `android/app/src/main/java/com/pocketrealm/server/LlmRuntimePolicy.kt`) with explicit per-layer hourly caps (murmur/party/global/floor), defaulted to today's constants; register the keys in the H3R2-1 matrix (the native side already stores unknown conf keys safely per the `llmPromptBlockOverride` precedent, `tools/build_o09_realm_runtime.py:1523-1527`).
  2. Pick the weights from the soak, not by taste: measure per-layer delivery counts/hour at `ALIVE_REALM_320` on each flavor, then set floors (e.g. global ≥1 set piece/hour/zone at NORMAL rung) and encode them as the emitted defaults.
  3. Starvation soak: 4 h per flavor with the instrumentation counters (the delivery-time ledger at `PlayerbotLlmChatter.cpp:787-790`) dumped hourly; assert every enabled layer delivered ≥ floor and the fatigue quotas didn't collapse any lane to zero for >30 consecutive minutes.
  4. Tie the rung dim (low-battery courtesy, `ChatterRung`) into the verification so the starvation test runs at NORMAL only.
- **Upstream dependency:** none (weights are Pocket Realm conf surface on the pinned core).
- **Verification:** Archived per-layer hourly delivery tables for tbc/wotlk at 320 bots; the soak assertions green; the emitted conf block per flavor recorded in the evidence pack; vanilla default emission byte-identical (G3-7).
- **Effort:** M
- **Depends on:** H3-11 (enum pins), H3R2-1 (conf matrix), H3R2-6 (measurement rig).
- **Owner stage:** new:S3

### H3-12 — AhBot keep-excluded decision recorded per flavor (build flag + staging check)

- **Goal:** The AhBot exclusion is an explicit, recorded, per-flavor decision enforced by the build flag AND a staging check — not an accident of omitted flags.
- **Current state:** The exclusion is already explicit in both lanes: `-DBUILD_AHBOT=OFF` (`tools/build_o09_realm_runtime.py:6350`, `tools/build_win_realm_runtime.py:129`) against the CMake guard `if (BUILD_AHBOT)` (`native/playerbots/CMakeLists.txt:151-153`), with defense-in-depth anchors that force the AhBot conf to a disabled state when the code is present (`AHBOT_UPDATE_GATE`/`AHBOT_CONFIG_DISABLE` overlays at `tools/build_o09_realm_runtime.py:169,185`). `BotProfiles.kt:133` documents "the core BUILD_AHBOT target is excluded", and the base conf keeps the AH-adjacent keys off (`ShouldQueryAHListingsOutsideOfAH = 0`, `BotCheckAllAuctionListings = 0`, `BotProfiles.kt:194-195`). What's missing: a Decision Log entry naming the decision per flavor, and a mechanical staging check that the flag stays off.
- **Work items:**
  1. Decision Log entry (D-new): AhBot excluded on all flavors — rationale: single-player economy integrity, no AH automation against the sole user; note the opt-in tier re-open path (register §4 decision 10 lives with §2.1 H1R2-7).
  2. Mechanical check: extend `tools/check_repo.py` (or the H1-1 CI matrix) to assert every build invocation file keeps `BUILD_AHBOT=OFF` (grep the two build drivers + `scripts/build_native.py`) and that `flavor.json` gains an explicit `bot_policy.ahbot: "excluded"` field so the decision is data-visible per flavor.
  3. Staging check: the H3R2-1 known-key matrix records the AhBot conf family as prohibited keys — any emission aborts staging.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin (ahbot tree in-repo; stays unbuilt).
- **Verification:** CI check green; a mutation flipping the flag to ON fails the gate test; the `ahbot:"excluded"` field present for all three flavors in `flavor.json`.
- **Effort:** S
- **Depends on:** H3R2-1 (matrix), H1-1 (drivers); H1R2-7 (the §2.1 economy twin).
- **Owner stage:** new:S3

### J1-2 — AhBot/bot economy decision: tune vs opt-in tier; g/hour documented

- **Goal:** The bot-driven economy posture per flavor is decided and documented — AhBot stays excluded (H3-12), bot gold income is measured (g/hour), and the player's gold walls (5000g mounts, CWF, dual-spec) are tuned against that measured income or given an opt-in lever.
- **Current state:** The economy surface on the current pin: `BudgetValues` riding/mount budgets (H3R2-4 — with the flying legs broken), quest/training spend, `BuyAction`/`AhAction` in the strategy actions (`strategy/actions/AhAction.cpp` references `BudgetValues`), and the app's keep-off keys for AH automation (`BotProfiles.kt:194-195`). §2.1's H1R2-7 owns "ahbot SQL from playerbots per-flavor trees, price profiles, player gold walls — tune or opt-in AhBot tier; document g/hour" — this gap is its §2.3 half: the *decision record* and the bots-side knobs that must not silently re-enable AH trading. There is no measured g/hour number for any flavor in the repo today.
- **Work items:**
  1. Measure: 4 h soak per flavor at the default profile with quest AI on; sample bot gold (characters DB) hourly → g/hour per flavor; publish in the same doc as H1R2-7's wall tuning.
  2. Record the joint decision with §2.1: whether the 5000g/CWF/dual-spec walls are reachable in a solo playthrough at the measured rate, and which lever applies (tune quest rewards via seed-aug = §2.1's lane, or an opt-in conf tier documented in the app).
  3. Guard the exclusions this area owns: `ShouldQueryAHListingsOutsideOfAH`/`BotCheckAllAuctionListings`/`GuildShareAhBuyAction`-adjacent keys stay in the keep-off set of the H3R2-1 matrix with the same staging abort as H3-12.
  4. Cross-link the wotlk dual-spec (3.1: lvl 40/1000g) cost into the wall math (register §2.1 H1-3/H3-5 row).
- **Upstream dependency:** none beyond the H3-1 pin (measurement + decision record).
- **Verification:** g/hour numbers archived per flavor with soak logs; the decision entry references them; keep-off mutation tests green; the wall-reachability statement exists in the flavor docs.
- **Effort:** S
- **Depends on:** H3-12, H1R2-7 (joint owner), H3R2-4 (budget math), H3R2-1.
- **Owner stage:** new:S3

### J1-5 — Quest-tag friction: bots must not steal the player's quest targets

- **Goal:** The player's active quest mobs are not one-shotted by nearby higher-level bots before the player can tag them — either by excluding the player's active quest targets from bot objectives or by guaranteeing shared credit — with the chosen law recorded and soaked.
- **Current state:** Bots quest aggressively near the player by design: `forceActiveWhenNearPlayer = true` plus near-player relocation on the experience presets (`BotProfiles.kt:463-465` and the preset family), `autoDoQuests` on the natural/social behavior presets (`BotAdvancedSettings`/`BotBehaviorPreset`, `BotProfiles.kt:339-371`), and the overlay family already reshapes manager behavior near players (`PB_MGR_TELEPORT`, `PB_MGR_RANDOMIZE_*`, `PB_MGR_LEVEL_GUARD` anchors in `tools/build_o09_realm_runtime.py` ~5893-5900). Nothing in the tree or the overlays exempts the player's active quest targets from bot attack selection, and a level-80 WotLK bot questing in a shared vanilla zone (the F3-7 map-policy question) one-shots everything it touches.
- **Work items:**
  1. Fork commit on the H3-1 branches: in the bot target/loot selection path (the `LootObjectStack`/target-value layer — `native/playerbots/playerbot/LootObjectStack.cpp` and the targeting values), skip creatures that are objectives of any *real player's* active quests within the bot's engage radius, conf-gated (`AiPlayerbot.AvoidPlayerQuestTargets = 1` default-on, added to the per-flavor dist overlays and the H3R2-1 matrix).
  2. Alternatively (or additionally) guarantee shared credit by making bots join the player's group before engaging a quest-tagged mob — only if the exclusion proves insufficient in soak; record which law shipped.
  3. Cap the cross-era stomp: bots whose level exceeds the zone's content by >10 only engage quest-tagged mobs under the exclusion (this is the "unsynced high-level bots one-shotting vanilla zones" clause); the vanilla-zone-bots population decision itself belongs to F3-7 — reference, don't duplicate.
  4. Soak harness: a scripted scenario — player has a 10-mob kill quest in a zone with 12 active bots; measure the player's mob-tag success rate pre/post (≥90% post).
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin (fork commit authored here; upstream PR recommended).
- **Verification:** The tag-success scenario ≥90% across 3 runs per flavor; zero measurable tick-time regression from the quest-target query (R4 check); conf key present in the flavor matrix.
- **Effort:** M
- **Depends on:** H1-1, H3R2-1; F3-7 (vanilla-zone-bots decision), J2-6 (/who and population optics).
- **Owner stage:** new:S3

### J1-9 — Bot social continuity: pin befriended bots against reroll churn

- **Goal:** Bots the player has befriended (relationship tier, facts, or friends-list entries) survive the periodic reroll/randomize cycle — their identity, name, and the player-relationship ledger persist, so "your" bots stay yours across sessions.
- **Current state:** Two churn sources exist: the `RandomBotTimedLogout`/randomize cycle (`AiPlayerbot.InstantRandomize = 1`, `MinRandomBotRandomizeTime/MaxRandomRandomizeTime` in `BotProfiles.kt:178-181`) rerolls bot characters on a timer, and the overlay set already has an exemption precedent — the village-ring "settlers" are excluded from the randomize event and relocation (`PB_MGR_RANDOMIZE_SETTLER` anchor, `tools/build_o09_realm_runtime.py` ~5897-5900; the Kotlin side documents the settler semantics at `BotProfiles.kt:70-79`). The player-side relationship ledger is `bot_player_relationship` (tier stranger→trusted, `native/llm/sql/ai_playerbot_llm_memory.sql:20-33` in the characters DB) plus `bot_player_facts` — but nothing links those rows' survival to the bot's character row when the randomize event replaces it.
- **Work items:**
  1. Fork commit on the H3-1 branches: extend the settler-style exemption to "befriended" bots — a bot with any `bot_player_relationship` row of tier ≥ acquaintance (or a `bot_player_facts` row, or a `characters_social` friend entry from the real player) is skipped by the randomize/rename/relocation events, conf-gated (`AiPlayerbot.PreserveBefriendedBots = 1`, default-on, into the dist overlays + matrix).
  2. Keep the population math honest: pinned bots are exempt from *churn*, not from population targets — verify the admission controller (reduce/increase steps, `BotAdmissionLimits`) doesn't count pinned bots twice or strand the target when many bots get pinned (unit test at the Kotlin policy level + soak).
  3. Persistence check: the relationship rows are keyed by bot guid — assert the pinned bot's guid survives (the randomize path must not delete the character row; if upstream reroll deletes-and-recreates, pinning must skip the delete, not just the rename).
  4. Save & Exit interaction: pinned bots persist across the 6 h cap / Save & Exit cycle (P1-9's staggered autosave lane) — one interleave test.
- **Upstream dependency:** `https://github.com/cmangos/playerbots` @ H3-1 pin (exemption pattern exists as settlers; this extends it).
- **Verification:** Soak: befriend 5 bots (whisper conversations to build tier), force two randomize cycles, assert all 5 still exist with name/guid/relationship tier intact; un-pinned control bots did reroll; population target still met at soak end.
- **Effort:** M
- **Depends on:** H1-1, H3R2-1; P1-9 (Save & Exit budgets), J1-4 (phase persistence pattern for per-bot state).
- **Owner stage:** new:S3

### F1-9 — Lore asset packaging per flavor (flavor.json llm.lore_cards, staging, registry)

- **Goal:** Each flavor ships its lore corpus end-to-end as data: `flavor.json.llm.lore_cards` names the asset, `ServerRuntimeFiles` stages that file (no hard-coded name), the corpus build is provenance-locked in the repo gates, and vanilla's shipped asset is byte-identical.
- **Current state:** The vanilla asset ships at `android/app/src/main/assets/lore/lore_cards_v112.jsonl` (585 cards) and is staged by `stageLoreCards()` (`ServerRuntimeFiles.kt:326-332`) under the hard-coded name `LORE_CARDS_FILE_NAME = "lore_cards_v112.jsonl"` (`ServerRuntimeFiles.kt:476-477`). `flavor.json.llm.lore_cards` exists per flavor (vanilla = `"lore_cards_v112.jsonl"`, tbc/wotlk = `null`, `schemas/flavor.json:71-74,132-135,193-196`) but nothing binds the two — Decision **D15 item 5** explicitly defers "llm.lore_cards is duplicated as ServerRuntimeFiles LORE_CARDS_FILE_NAME with no manifest binding … retire: S3 stage of the runbook". No registry/provenance entry exists for the corpus (grep of `schemas/sources.json` and `tools/check_repo.py` for "lore" returns nothing).
- **Work items:**
  1. Retire D15(5): delete the Kotlin constant; read `LORE_CARDS_FILE_NAME` from the qualified flavor's `llm.lore_cards` via the `ClientFlavor` projection (the file already imports `ClientFlavor`/`ClientFlavors`, `BotProfiles.kt:3-4` shows the import pattern). Vanilla's resolved name must be byte-identical (G3-8: lore asset name kept for vanilla).
  2. Ship the per-flavor assets from H3-7 (`lore_cards_v204.jsonl`, `lore_cards_v303.jsonl` + `CORPUS-SOURCES.md`) in `android/app/src/main/assets/lore/`; keep the APK-size delta measured (R4) since assets ride every APK.
  3. Provenance: register the corpora in the repo gates — either `schemas/sources.json` (harvest corpus as an external input with digest) or a new lockfile entry consumed by `check_repo.py`; record the builder command + `LLM_LAB_LORE_CORPUS` digest so the build is reproducible.
  4. Staging gate: a flavor with `llm.lore_cards` non-null but a missing asset fails staging **loudly** only when the LLM lane is enabled (the current `runCatching` fail-open at `ServerRuntimeFiles.kt:198-199` is correct for the disabled path — keep it, but make the enabled-path missing-asset a visible diagnostics entry, not silence).
- **Upstream dependency:** none.
- **Verification:** Unit tests: per-flavor staging resolves the manifest-named asset; vanilla path byte-identical to today (golden); a deliberately-missing wotlk asset with LLM enabled produces the diagnostics entry; `check_repo` provenance green; APK size delta recorded.
- **Effort:** M
- **Depends on:** H3-7 (the corpora), H3R2-6 (size budget); G3-8 (vanilla asset name), D15(5) retirement.
- **Owner stage:** new:S3

---

## Coverage ledger

- [x] H3-1
- [x] H1-1
- [x] H3-2
- [x] J2-1
- [x] H3-3
- [x] H3-4
- [x] H3R2-1
- [x] H3R2-2
- [x] H5-2
- [x] H3R2-3
- [x] H3R2-4
- [x] H3R2-5
- [x] H3R2-6
- [x] H3R2-7
- [x] H3R2-8
- [x] H3R2-9
- [x] J1-1
- [x] H3-7
- [x] H3-8
- [x] F3-2
- [x] H3-9
- [x] H3-10
- [x] H3-11
- [x] F3-9
- [x] H3-12
- [x] J1-2
- [x] J1-5
- [x] J1-9
- [x] F1-9

§2.4 is everything the player touches: 26 table IDs plus the four register §1 critical-path gaps it carries first, in register §1 order — H4-1 (insecure Lua vs `InCombatLockdown`), H4-2 (vehicle/override bars), H4-3 (per-flavor addon catalogs), H4R2-2 (character creation). Its method is parameterization expressed as new per-flavor data, never edits to the build-5875-pinned vanilla constants, with wave S7 owning the bulk, S3/S4 two bot/content riders, and S9 the coexistence chrome.

---

# §2.4 App UX, addons & controls — fix plan (R1: H4, R2: H4R2, R6: J1)

## 2.4 App UX, addons & controls (R1: H4, R2: H4R2, R6: J1)

This area covers everything the player touches around the game: the controller/touch input contract and its interaction primitive (H4-4, H4R2-11, H4-11, J1-8), the client's on-disk settings surface (WTF trees, Config.wtf seeds, settings/bindings catalogs — H4-5, H4-6, H4R2-4, H4R2-5), the observability and recovery surfaces (crash artifacts, bundles, cache/WDB, self-service recovery — H4-7, H4R2-10, H4-8, J1-3), the unattended session entry path (auto-login topology, realm preselection, TOS/EULA, IME — H4R2-1, H4R2-3, H4-9, H4R2-9), the addon lifecycle (catalogs, validator policy, VoiceOver, copy — H4-10, H4-13, H4-14), the flavor-aware product copy and coexistence chrome (H4R2-6, H4R2-7, H4R2-8, H4-12, C3-walk), and two gameplay-journey UX items that live in the bot/content lanes (J1-6, J1-7). It also carries the four register §1 critical-path gaps whose substance is UX/addon work and which no other section owns: **H4-1** (AndroidPort insecure Lua vs 2.0+ `InCombatLockdown`), **H4-2** (WotLK vehicle/override bars), **H4-3** (per-flavor addon catalogs), and **H4R2-2** (character-creation flow) — they are treated first, in register §1 order. The remaining gaps group into three themes: **parameterize the pinned-vanilla UX machinery per flavor** (today `InGameSettingsFiles.kt:11-16`, `SavedVariablesCodec.kt:4-7`, `WowVanillaSettingsCatalog.kt`, `WowVanillaBindingCatalog.kt:44-53` and `Build5875LoginTopology` (`SinglePlayerAutoLogin.kt:39-86`) are all build-5875-pinned by design), **extend the safety story per flavor** (crash artifacts, cache routing, validator caps), and **close journey-level product gaps** that the original app never needed (DK HUD, expansion handoffs, recovery lane, flavor switching). Stage S7 (client runtime & UX) owns the bulk; S3/S4 own the two bot/content riders; S9 owns coexistence chrome. The vanilla-regression guards of §2.7 (G3-6, G3-7, G3-10) apply to every item here: vanilla catalogs, policies, WTF layout, and conf bytes must remain byte-identical, so every parameterization below is expressed as *new per-flavor data* (a `client_ux` block in `schemas/flavor.json`, mirrored into `ClientFlavor` (`android/app/src/main/java/com/pocketrealm/client/ClientFlavor.kt:16-55`)), never as edits to vanilla constants.

Conventions used below: "flavor block" = the per-flavor descriptor in `schemas/flavor.json` (schema 2, verified: `vanilla`/`tbc`/`wotlk` entries each carry `wine`, `storage`, `mpq_set`, `llm`, `addon_interfaces`, `bot_policy`); "ClientFlavor" = the Kotlin mirror `com.pocketrealm.client.ClientFlavors` (VANILLA at `ClientFlavor.kt:59-84` qualified=true, TBC `:90-112` and WOTLK `:118-140` declared-but-disabled). All repo paths verified against `E:/pocket_realm_complete` on 2026-09-30.

---

### H4-1 — AndroidPort insecure Lua → SecureActionButtonTemplate port (InCombatLockdown-safe)

- **Goal:** on TBC (2.0+) and WotLK, every AndroidPort action that touches protected state either works in combat through `SecureActionButtonTemplate` attributes or is explicitly deferred with visible feedback — while the vanilla Interface-11200 addon stays byte-identical (G3-6).

- **Current state:** the addon does protected work from insecure Lua, which 2.0+ clients block in combat (register §1, `docs/FEATURE-PARITY-GAPS.md:28`): action buttons call `UseAction` from the binding handler (`android/app/src/main/assets/addons/android-port/AndroidPort/ActionBars.lua:79` — `if HasAction(actionSlot) then UseAction(actionSlot) end`), the drag/edit path calls `PickupAction` (`ActionBars.lua:196`), sell-junk calls `UseContainerItem(bag, slot)` (`Bags.lua:708`), and `FrameMover.lua:13-20` re-anchors curated stock frames (protected `SetPoint`-family work on 2.0+). All of it is legal on 5875 (pre-2.0 restrictions) and none of it is flavor-gated: the addon ships as one Interface-11200 tree (`addons/AndroidPortPackage.kt:11-18`; the built-in check at `addons/AddonRepository.kt:878-879` requires `## Interface: 11200`), and per port plan S7.6 its per-flavor scope is an explicit decision point ("port to the flavor's UI API or exclude per flavor with honest copy"). The app-side key-injection path (bindings written through `ingame/BindingsFileCodec.kt` into the client's own binding system) stays the *secure primary* input path (register: "bindings stay primary path") — key events execute through the client's secure handler, not Lua.

- **Work items:**
  1. Record the S7.6 decision (port plan §8.2.4) in `docs/DECISIONS-PORT.md`: port the addon per flavor (the controller contract depends on it); exclusion remains the documented fallback with honest copy, and H4-12/J1-8 ride the same 20400/30300 variants.
  2. Per-flavor TOC and Bindings.xml variants: keep `AndroidPort.toc` (11200, byte-unchanged) and add `AndroidPort_TBC.toc` (`## Interface: 20400`) and `AndroidPort_WotLK.toc` (`## Interface: 30300`) sharing the Lua sources behind per-flavor feature gates; in the 20400/30300 `Bindings.xml` variants, re-express `AP_ACTION_1..40` as **CLICK pseudo-bindings** — binding commands of the form `CLICK <frameName>:<buttonN>` (the client's `CLICK ` prefix makes the bound key synthesize a hardware click on the named frame) targeting the secure buttons' deterministic names — and leave the vanilla 11200 `Bindings.xml` byte-identical; relax the built-in check at `AddonRepository.kt:878` to validate against `ClientFlavor.addonInterfaces` while keeping the vanilla assertion literal.
  3. Action buttons: on 20400/30300 create the AP action buttons as `SecureActionButtonTemplate` frames with `SetAttribute("type", "action")` / `SetAttribute("action", slot)` using the existing slot math (`ActionBars.lua:77`: `actionSlot = (page - 1) * 12 + buttonIndex`), named deterministically so the CLICK bindings can target them. The CLICK pseudo-binding is the one key-driven path that is a *hardware* event on a secure button — the keypress goes binding-system → secure click, combat-legal on 2.0+/3.x — so the app's key injection (`useLootClickLocked`-style chord output, `InputContract.kt:1543-1560`) activates the button through the binding system and never through addon Lua: `secureFrame:Click()` from a handler is still insecure code and stays blocked in combat, which is precisely the H4-1 failure this port closes. Verify the exact `CLICK <frame>:<button>` token form against the H4R2-11 FrameXML captures before pinning. The out-of-combat path and the drag editor (`PickupAction`, `:196`) stay gated on `InCombatLockdown()` with a visible "in combat" rejection.
  4. Bag-use guard: wrap `Bags.lua:708` in an `InCombatLockdown()` check; in combat the sell/deferred use lands in a pending queue flushed on `PLAYER_REGEN_ENABLED` with a toast — the register's "guard/defer bag-use".
  5. FrameMover guard: journal/apply of layout (`FrameMover.lua:13-20`) checks `InCombatLockdown()`; combat-time moves are journaled pending and applied at regen end.
  6. G3-6: `AndroidPortAssetTest.kt` asserts the 11200 source set contains none of the new gating code and its bytes are unchanged.

- **Upstream dependency:** none (addon is project-owned; FrameXML API facts from the H4R2-11 captures).

- **Verification:** device, TBC + WotLK: pull a training dummy and press action keys mid-combat — abilities fire (the CLICK-bound keypress activates the secure button); attempt sell-junk and a frame move mid-combat — both deferred with toasts and apply after combat; bindings-primary path regression green on all flavors; vanilla asset test proves byte-identity.

- **Effort:** L (1–2 weeks).

- **Depends on:** H4R2-11 (per-build FrameXML captures), S5 (qualified clients); H4-2 and H4-12 consume the variants this creates.

- **Owner stage:** S7 (register §1 row; port plan S7.6).

---

### H4-2 — WotLK vehicle/override bars in the action-slot model and controller profile

- **Goal:** the controller's action grid resolves the *right* slots in every action context per flavor — vanilla/TBC paging plus WotLK's bonus-bar offsets and the vehicle/OverrideActionBar context — with a vehicle chord map or honest copy where a context cannot be driven.

- **Current state:** the model is the addon's pages-of-8 mapping onto the stock 12-slot bars (`ActionBars.lua:70-77`: `actionSlot = (page - 1) * 12 + buttonIndex`; 40 bindings = 5 pages of 8, `Bindings.xml` `AP_ACTION_1..40`), with the binding catalog's `BONUS_ACTION_BAR`/`ACTION_PAGES` categories describing vanilla's bonus bar (`WowVanillaBindingCatalog.kt:16-31`). WotLK adds two contexts the model does not know (register §1, `:29`: "unmodeled in the 8×4 action model and controller profile"): `GetBonusBarOffset()` paging (stances/forms — WotLK extends the offset set) and the OverrideActionBar, which while a vehicle is driven *displaces* the main bar's slots with the vehicle's own 12 — the paging model silently targets the wrong actions. H4R2-11's WotLK catalog will capture the `VEHICLE` binding category, but nothing maps it to the controller grid today.

- **Work items:**
  1. Per-flavor slot resolver: extract the active-slot math from `ActionBars.lua` behind a resolver driven by captured FrameXML facts — page → stock slots (all flavors), bonus-bar offset via `GetBonusBarOffset()` (TBC/WotLK), and the WotLK override context detected via the 30300 API surface (`UnitOnVehicle("player")` / override-UI query — verify exact names against the H4R2-11 capture, never from memory).
  2. Vehicle chord map: while the override context is active, map the 8×4 grid onto the override bar's slots (primary 8 + chord for 9–12) and show an app-side "vehicle controls" indicator (the `TouchOverlay` movable-cluster precedent, `ui/TouchOverlay.kt:492-502`); exiting the vehicle restores paging automatically.
  3. Honest copy: where a vehicle seat exposes no actions (passenger seats), `ui/ControlsScreen.kt` copy states exactly what the controller can and cannot do on vehicles per flavor.
  4. Controller profile: `InputProfile` gains a per-flavor/context slot table (a `ClientFlavor` field) so key labels and the HUD match the active context.
  5. Register the `VEHICLE` binding category from the H4R2-11 WotLK catalog so the bindings editor can show (not edit) vehicle bindings like any stock category.

- **Upstream dependency:** none (context behavior captured from the qualified 12340 client; no core work — vehicles are server-side and already modeled by S3's vehicle work per register G1-7, §2.1).

- **Verification:** device WotLK: enter a vehicle (Argent Tournament jousting mount — present in build 12340) → grid buttons fire the vehicle's first 8 abilities, the chord reaches 9–12, exit restores main-bar paging (binding dump + screenshots before/during/after); TBC: druid form shift pages via the bonus-bar offset; vanilla paging byte-identical (regression capture diff).

- **Effort:** L (1–2 weeks).

- **Depends on:** H4-1 (the per-flavor addon variants host the resolver), H4R2-11 (VEHICLE category + 30300 FrameXML capture), S5 (qualified client).

- **Owner stage:** S7 (register §1 row; port plan S7.3).

---

### H4-3 — Per-flavor addon catalogs: research + pin (pfQuest-Reforged 30300, Zidras/DBM-WotLK, Omen3, AI-VoiceOver 3.3.5)

- **Goal:** every flavor has a first-class researched catalog with pinned repos/commits/checksums — the vanilla `catalog-v1.json` discipline — and `clientTarget` validation that shows each flavor only its own verified entries.

- **Current state:** one catalog exists: `android/app/src/main/assets/addons/catalog-v1.json` (schema 1, 154 entries, `researchedAt` 2026-08-11 — verified by JSON dump this session). Its `clientTarget` is free text ("1.12.1 + 2.4.3" on 5 entries, "3.3.5" on 2, everything else vanilla — counted this session), which is exactly the register §1 verdict (`docs/FEATURE-PARITY-GAPS.md:30`): "catalog caps at TBC entries; zero WotLK". `addons/AddonCatalog.kt:17,135` loads `clientTarget` as a plain string; nothing filters the catalog by the running flavor. The register's parity plan names the WotLK marquee set: pfQuest-Reforged (30300), Zidras/DBM-WotLK, Omen3, AI-VoiceOver 3.3.5 — "pinned repos/commits/checksums like vanilla".

- **Work items:**
  1. Pin procedure (this plan fixes procedure + marquee set; commits/hashes are recorded at execution, never guessed here): each artifact enters `schemas/sources.json` (or the flavor block's `addon_catalog` section) with repo URL, commit, release tag, and SHA-256, gated by `tools/check_sources.py` — the same provenance chain vanilla's pins flow through.
  2. WotLK marquee set (register-named): pfQuest-Reforged (Interface 30300), Zidras/DBM-WotLK, Omen3, the AI-VoiceOver 3.3.5 pair (resolver work is H4-13's). TBC marquee set: researched at execution by the same procedure — at minimum a map/quest-notes addon (pfQuest family with 2.4.3 support) and a boss-mods addon, each pinned or honestly omitted.
  3. Catalog data: emit `catalog-tbc-v1.json` / `catalog-wotlk-v1.json` with the same entry schema and a *normalized* `clientTarget` (exact client ids from `ClientFlavors`, not prose); `AddonCatalog` filters by the active flavor and `AddonsScreen` renders the flavor's catalog (H4-14 displays `clientTarget`).
  4. Downstream integration: the pinned artifacts are H4-10's cap-measurement corpus and H4-13's resolver inputs; wrong-flavor artifacts must fail validation with the flavor's copy.
  5. G3-6: `catalog-v1.json` stays byte-identical — add a hash test.

- **Upstream dependency:** upstream repos to be pinned at execution per item 1 — register-named: `Zidras/DBM-WotLK` (slug as given in the register); pfQuest-Reforged, Omen3, and the AI-VoiceOver 3.3.5 artifact (H4-13's research gate) get their exact repos/commits/checksums recorded at pin time; no pin exists at plan time and none is invented here.

- **Verification:** per flavor: the Addons screen lists exactly that flavor's pinned entries; the WotLK marquee artifacts install end-to-end under H4-10's policies and load in the 12340 client (DBM bars appear on a boss, pfQuest map notes render); `tools/check_repo.py` green over the new pins; `catalog-v1.json` hash unchanged.

- **Effort:** L (1–2 weeks, research-heavy).

- **Depends on:** G3-6 (catalog sha/policy pins must land before per-flavor catalogs — roadmap wave 0, §2.7); feeds H4-10, H4-13, H4-14.

- **Owner stage:** S7 (register §1 row; port plan S7.6).

---

### H4R2-2 — Character-creation flow (fresh account = zero characters)

- **Goal:** a fresh account on any flavor can create its first character unattended — a guided controller walk-through through each build's native creation screens with per-flavor grid maps (server-side starter-character relay recorded as the fallback) — honoring the DP4 death-knight gate decision.

- **Current state:** no creation flow exists on any flavor (register §1, `:36`). The app creates the *account* (`native/realm-runtime/src/world_runtime.cpp:270-288` `create_account`) but nothing drives the client's character-create screens, so a fresh account is unplayable. The server half is owned in server-systems.md: H1-2 item 4 propagates the DK-gate refusal reason through the relay "so the character-creation flow (H4R2-2, §2.4) can render" it, and H1-14's creation-matrix fixture is designated input data for this flow ("Integrate with the H4R2-2 creation flow (§2.4)", server-systems.md:375). The creation screens are GLUE (char-select/create FrameXML per build), so the window automation H4R2-1 builds for login extends naturally; J2-1 seeds `HEROIC_CHARACTERS_PER_REALM`/`MIN_LEVEL_FOR_HEROIC` (H1-2's conf work).

- **Work items:**
  1. Decision (Decision Log): guided walk-through is primary — it reuses the GLUE automation, forks nothing server-side, and exercises the real client path; the register's alternative ("server-side starter-character relay, mirroring the `account create` path") is recorded as the fallback with explicit trigger conditions (a build whose creation screens prove undrivable on a lane), not implemented by default.
  2. Per-flavor creation-grid descriptors in the flavor block (same shape as H4R2-1's `loginTopology`): race/class/faction grid positions, random-name control, name field, accept control — captured from the qualified clients per lane with H4R2-1's capture tooling; the grids are gated by H1-14's matrix fixture (a combo the server would refuse is never offered).
  3. Name entry: the bounded keymap path (`client/ImeCharMap.kt`) serves the name field, and the flow defaults to the client's random-name button so no typing is required at all (H4R2-9's honest matrix applies).
  4. DK gating copy: on WotLK, a DK attempt before unlock renders H1-2 item 4's propagated refusal ("requires a level 55 character") plus the sanctioned unlock copy per DP4 (port plan §4 item 4 — blizzlike vs relaxed is decided there and referenced, not re-decided here); TBC/vanilla grids never contain the DK entry (matrix data).
  5. Flow integration: creation completes → char-select → H4R2-1's Enter World injection takes over; H1-14 item 4 consumes these same descriptors as its acceptance harness (one capture, two consumers).

- **Upstream dependency:** none (GLUE behavior captured from qualified clients; server gating is H1-2/J2-1's, `puzzled-pancake/mangos-wotlk` branch `pocket-realm`).

- **Verification:** per flavor: fresh account → unattended first-creation 5/5 (matrix-selected race/class, including BE/draenei on TBC) → character enters world; WotLK pre-unlock DK attempt → refusal copy rendered, `SELECT COUNT(*) FROM characters WHERE account=<id> AND class=6` == 0 (H1-2's check); grid descriptors diff against the captured FrameXML; post-unlock DK creation succeeds once and a second attempt is refused per `HEROIC_CHARACTERS_PER_REALM=1`.

- **Effort:** L (1–2 weeks).

- **Depends on:** H4R2-1 (GLUE machinery), H1-14 + H1-2 (server-systems.md — matrix fixture and refusal relay), H4R2-9 (name-entry matrix), S5 (qualified clients).

- **Owner stage:** S7 (flow/topology) / S8 (gating conf, H1-2's half) — register §1 row.

---

### H4-4 — Interact primitive per flavor (WotLK INTERACTTARGET vs TBC whisper surrogate)

- **Goal:** the controller's one-button "nearby use / open" works on every flavor — WotLK through the client's native `INTERACTTARGET` binding, TBC through the self-whisper surrogate — with rate-limit behavior re-verified per flavor and no visible chat leakage on either.

- **Current state:** the vanilla lane's interact chain is verified: the AndroidPort addon registers `AP_NEARBY_INTERACT` (`android/app/src/main/assets/addons/android-port/AndroidPort/Bindings.xml:44`), whose handler sends one self-whisper `SendChatMessage("PR6I:1 INTERACT", "WHISPER", nil, UnitName("player"))` (`Core.lua:805`); the server consumes that exact string in `native/patches/cmangos/PocketRealmInteraction.cpp:34`, selects a nearby creature/gameobject within `INTERACTION_DISTANCE` (`OK_LOOT`/`OK_USE`/`NO_TARGET`/`BLOCKED`, `:171-174`), gated by `PocketRealm.NearbyInteract`/`PocketRealm.NearbyInteractCooldownMs` emitted at `android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt:175-176`, bounded by `NearbyInteractPolicy.kt:5-9` (250 ms default, 100–2000 ms). The header (`PocketRealmInteraction.cpp:1-11`) names the gap's driver: 1.12 has no `INTERACTTARGET` and each surrogate whisper counts toward the chat flood filter (~10 presses/second can briefly mute chat). The controller trigger is the secondary click (`InputContract.kt:1543-1560`). TBC lacks the binding too, so it keeps the surrogate; WotLK *defines* `INTERACTTARGET` (the vanilla catalog notes 1.12.1 defines neither it nor LOOTALL — `WowVanillaBindingCatalog.kt:55`), so the WotLK lane should stop whispering entirely.

- **Work items:**
  1. Extend the `client_ux` block (new, see H4-5 item 1) in `schemas/flavor.json` with `interact.mode: "whisper-surrogate" | "native-binding"`; mirror it as a `ClientFlavor.interactMode` field (`ClientFlavor.kt:16-55`); `vanilla`/`tbc` = `"whisper-surrogate"`, `wotlk` = `"native-binding"`.
  2. WotLK lane: add `INTERACTTARGET` to the WotLK binding catalog produced by H4R2-11, and in `InputProfile.kt`/`InputContract.kt` map the secondary-interaction control to the `INTERACTTARGET` keyboard binding (a plain `bind` in `bindings-cache.wtf` through the existing `BindingsFileCodec` path) instead of injecting the F7 chord.
  3. TBC lane: keep the surrogate; re-anchor `PocketRealmInteraction.cpp` into the tbc branch's `WorldSession` chat handler during the S2 overlay re-derivation (port plan S2.3 already names this: "re-test the 'Interact Target' semantics per client"); keep the trigger string `PR6I:1 INTERACT` and the `PocketRealm.*` conf keys identical so `ServerRuntimeFiles.kt:175-176` and `NearbyInteractPolicy` stay shared.
  4. Rate-limit re-verification: measure each flavor's chat flood filter behavior with the surrogate held down (the cooldown gate at `PocketRealmInteraction.cpp:198-206` is the control); if TBC/WotLK flood thresholds differ from vanilla, tune `NearbyInteractPolicy.DEFAULT_TRIGGER_GUARD_MS` per flavor via a `client_ux.interact.cooldownMs` value rather than changing shared code.
  5. Update `tests/test_pocket_realm_nearby_interact.py` and `android/app/src/test/java/com/pocketrealm/addons/AndroidPortAssetTest.kt:178` (which asserts the exact send line) to be flavor-parameterized; add a negative test that the WotLK lane never emits `PR6I:` (leak-sweep-friendly).

- **Upstream dependency:** `puzzled-pancake/mangos-tbc` and `puzzled-pancake/mangos-wotlk`, branch `pocket-realm` (forks created in S2; commit pins recorded at S2.1 — they do not exist at plan time). No new upstream commits required beyond the S2 overlay re-derivation.

- **Verification:** RP6 device run per flavor: (a) TBC — stand within `INTERACTION_DISTANCE` of an NPC and a chest, press the secondary control, observe the interaction dialog/loot window opens, confirm no `PR6I` line appears in any chat frame, then hold the trigger >1 s at 60 taps and confirm chat is never muted; (b) WotLK — bind `INTERACTTARGET`, press secondary on an NPC: interaction fires with zero whisper traffic on the wire (verify via `tests/test_pocket_realm_nearby_interact.py` port for wotlk). Vanilla re-run of the existing suite must stay green.

- **Effort:** M (1–3 days).

- **Depends on:** H4R2-11 (WotLK binding catalog to host `INTERACTTARGET`), S2 overlay re-derivation (TBC/WotLK `PocketRealmInteraction.cpp` anchors).

- **Owner stage:** S7.

---

### H4-5 — WTF scope beyond SavedVariables (per-realm/per-char files, snapshots, corrupt-recovery)

- **Goal:** the app reads, writes, snapshots, and recovers every WTF file each flavor actually produces — not just the account-level `SavedVariables.lua` — with per-level corrupt recovery and no silent loss, while the vanilla file layout stays byte-identical.

- **Current state:** `android/app/src/main/java/com/pocketrealm/ingame/InGameSettingsFiles.kt:10-16` pins the tree to the verified build-5875 layout: account uvars at `WTF/Account/<name>/SavedVariables.lua`, account bindings at `WTF/Account/<name>/bindings-cache.wtf`, per-character binding overrides at `WTF/Account/<name>/<server>/<char>/bindings-cache.wtf` (`:32-40`). That is the complete model: the app knows nothing about `config-cache.wtf`, `macros-cache.txt`, `layout-local.txt`, or `chat-cache.txt`, and its SavedVariables handling is a single account-level file. The register (correction C8) establishes the flavor split: TBC WTF is *already* 3-level (per-realm/per-char SavedVariables), and the per-character cache-file set (`config-cache.wtf`, `bindings-cache.wtf`, `macros-cache.txt`, `layout-local.txt`, `chat-cache.txt`) is the WotLK-era addition. `SavedVariablesCodec.kt:3-13` is a strict *scalar* codec for the 5875 account file (`\r\r\n` terminators, top-level scalars only) — WotLK's SavedVariables are per-addon `.lua` tables under `WTF/Account/<name>/<realm>/<char>/SavedVariables/`, which this codec must never be pointed at. The only app-owned WTF paths today are `realmlist.wtf` and `WTF/Config.wtf` (`importer/ClientGenerationStore.kt:111`), and writes go through `InGameSettingsFiles.writeAtomic` (`:89-112`).

- **Work items:**
  1. Add a `client_ux.wtf` block per flavor to `schemas/flavor.json`: `savedVariablesLayout: "account-single-file" (vanilla) | "three-level" (tbc, wotlk)` and `charCacheFiles: [] (vanilla, tbc) | ["config-cache.wtf","bindings-cache.wtf","macros-cache.txt","layout-local.txt","chat-cache.txt"] (wotlk)`; mirror into `ClientFlavor`.
  2. Rework `InGameSettingsFiles.kt` around that descriptor: `accountSavedVariables()` gains a three-level variant returning the per-realm/per-char directory; keep `accountSavedVariables()` itself vanilla-verbatim (G3-10). `characterScopesWithBindings` (`:54-71`, depth-8 walk, `MAX_TREE_ENTRIES = 10_000` at `:19`) generalizes to enumerate per-character SavedVariables directories for wotlk, with the same entry cap and symlink refusal.
  3. Extend the corruption story: for every file the app now touches (the five wotlk cache files included), define recover-on-read — an unparsable or oversized file is quarantined to `<name>.corrupt-<epoch>` and regenerated by the client on next login, exactly the discipline `SavedVariablesCodec.assign` uses when it returns null instead of editing structure; add this to the WtfCodecsTest matrix (`android/app/src/test/java/com/pocketrealm/ingame/WtfCodecsTest.kt`).
  4. Snapshot tolerance: before flavor session start, copy the char-level cache set into the session journal directory so Save & Exit (P1-9, §2.5) and the S9 per-flavor backup/restore lane (P2-2) can round-trip them; the support bundle must continue to exclude WTF content except as redacted samples (bundle policy, H4R2-10).
  5. Addon-rollback interplay: when `AddonRepository.retireRemovedProducts` (`addons/AddonRepository.kt:774-784`) retires an addon on a three-level flavor, sweep its SavedVariables from every character scope (this is F3-5 §2.6 — coordinate, do not duplicate).

- **Upstream dependency:** none (client-behavior facts come from the qualified clients at S5 capture time; register C8).

- **Verification:** androidTest on a qualified flavor tree seeded with synthetic 3-level WTF fixtures: editor round-trips per-char uvars and bindings for two characters in two realms; corrupt each of the five wotlk cache files in turn → clean relaunch, quarantine file present, no crash, no silent loss; vanilla `WtfCodecsTest` diff proves zero behavior change on the single-file lane.

- **Effort:** L (1–2 weeks).

- **Depends on:** S5 (qualified client produces the real trees), F3-5 (addon rollback sweep), H4R2-4 (catalog scope resolution consumes the same descriptor).

- **Owner stage:** S7.

---

### H4-6 — Per-character keybindings on WotLK (codec scope resolution + repair journal on 3-level trees)

- **Goal:** binding edits and the AndroidPort binding-repair journal are correct per *character scope* on WotLK's 3-level tree — an edit made for character A never lands on character B, and repair restores exactly what each character had.

- **Current state:** the vanilla lane already supports two scopes: account (`WTF/Account/<name>/bindings-cache.wtf`) and per-character (`WTF/Account/<name>/<server>/<char>/bindings-cache.wtf`). `BindingsFileCodec.kt:1-9` parses/emits `bind KEY COMMAND` lines with chord prefixes; `InGameSettingsEditor.bindingScopes()` (`ingame/InGameSettingsEditor.kt:116-118`) merges account scopes with `characterScopesWithBindings(...)` mapped to `"Character"`; per-scope writes check the scope file exists first (`:269-272`). The repair side, `addons/AndroidPortBindingRepair.kt`, fsyncs a host-side JSON journal (schema 1, `:64-67`) before WoW can load the addon (`:15-19`), capturing "the key chords this repair owns on behalf of the controller overlay" (`:55`) via `captureBeforeLaunch` (`:61-105`) and restoring on removal (`restoreAfterRemoval`, `:106-142`). The gap: the journal is written per managed client, not per scope — on WotLK, where each character owns a `bindings-cache.wtf`, `captureBeforeLaunch` runs before the *selected character* is known at launch-preparation time, and a journal written under character A's session can restore chord claims into character B's file after addon removal.

- **Work items:**
  1. Extend the journal schema (schema 1 → 2, additive): each captured chord entry gains `scope` (`"account"` or `"account/server/char"`) and `flavor` (optional field, defaulting `"vanilla"` — the G3-4 journal-codec rule). Readers accept schema 1 and 2; schema-1 entries keep their current meaning (account scope, vanilla).
  2. Split `captureBeforeLaunch` into the launch-time part (chords *claimed*, flavor-wide, as today) and a post-character-select part: once H4R2-1's char-select topology identifies the active character scope, record that scope in the session journal entry used by `restoreAfterRemoval`.
  3. Route `restoreAfterRemoval` through `InGameSettingsFiles.bindingsForScope` per entry's scope, never through a single file; add the per-entry scope check to the existing size/JSON guards (`:65-67`, `:112-114`).
  4. Unit tests in `WtfCodecsTest.kt` + a new `AndroidPortBindingRepairScopeTest`: two character scopes, chords captured in one, restore in the other → no cross-contamination; schema-1 journal still restores byte-identically (vanilla regression).
  5. Update `ui/InGameBindingsScreen.kt` scope picker copy (`:202` builds `"$id ($kind)"`) to show realm names on three-level flavors.

- **Upstream dependency:** none.

- **Verification:** device test on the wotlk flavor: set a controller chord, log in as character A, install/remove the AndroidPort addon; verify A's `bindings-cache.wtf` is restored to pre-install content and B's is untouched (file-hash comparison before/after); corrupt A's journal → restore refuses loudly (fail-closed) instead of writing partial state. Vanilla: existing journal tests green unchanged.

- **Effort:** M (1–3 days).

- **Depends on:** H4-5 (3-level scope enumeration), F3-5 (retirement sweep shares the scope model).

- **Owner stage:** S7.

---

### H4-7 — Crash artifacts per flavor (Errors/Crash.txt + .dmp) and per-flavor log inventory

- **Goal:** every crash or client-error artifact each flavor can produce is discovered, surfaced in Diagnostics, harvested (redacted) into the support bundle, and tolerated by Save & Exit — per flavor, with an explicit inventory table as the source of truth. **Ownership (register token shared with §2.2):** this treatment is the owner of record for register row `H4-7/H4R2-10` — the `docs/client-artifacts.md` inventory is the source of truth; §2.2's `### H4-7` (client-data-runtime.md) is the cross-reference half owning the harvest/redaction/bundle machinery that consumes it (roadmap §0 ID-collision conventions).

- **Current state:** the app surfaces client crash evidence from one place: `ui/DiagnosticsScreen.kt:100-108` lists up to `MAX_CLIENT_CRASH_DUMP_ROWS = 5` (`:44`) newest files under `clients/generations/<gen>/Errors/`, with the explanatory copy at `:157` ("Crash dump texts live in the client generation's Errors/ folder (Wine timestamps are UTC before the TZ fix)"). The Windows lane has its own artifact: `native/realm-runtime/src/win_exception_shim.cpp:135` writes `%LOCALAPPDATA%\PocketRealm\crash-trace.txt`. That inventory is vanilla-shaped: 1.12 writes plain text crash files. The register (H4-7 row) adds the per-flavor reality — WotLK's crash handler produces `Errors/` text plus binary `.dmp` minidumps ("Crash.txt+dmp"), and each branch's server log posture differs (the app already sets an errors-only world log level, `server/ServerRuntimeFiles.kt:488`). There is no harvest of `Errors/` content into the support bundle (the bundle carries only logs/journals — `diagnostics/SupportBundleExporter.kt:40-48`), no size accounting for artifact growth (register H2-7 groups Cache/WDB + Errors + Screenshots as storage-accounting items), and no statement of which artifacts are safe to upload.

- **Work items:**
  1. Create `docs/client-artifacts.md` (or a `client_ux.artifacts` block in `schemas/flavor.json`) listing per flavor: root (`Errors/*.txt`), wotlk additions (`Errors/*.dmp`, `Errors/Crash.txt` per register), producer (client crash handler vs Wine), redaction class, and retention policy. This table is the "per-flavor log inventory" the register asks for; `DiagnosticsScreen` and the bundle exporter consume it as data.
  2. Extend `DiagnosticsScreen.kt:100-108` to filter by the flavor's artifact pattern (e.g. also show `.dmp` presence and size for wotlk) and to show total bytes occupied by `Errors/` per generation, feeding the per-flavor storage accounting (P1-8 §2.5).
  3. Harvest into the bundle: add a bounded crash-artifact inventory entry to `SupportBundleExporter` — named `crash-artifacts.json` per §2.2 H4-7 (one bundle entry, not two) — for each generation, the newest crash *text* file's first N lines (redacted by `SecretRedactor`, capped by the existing `MAX_ENTRY_CHARS` guard at `SupportBundleExporter.kt:107`); never include `.dmp` binaries (bundle stays text-only per its header comment `:15`).
  4. Supervisor tolerance: verify Save & Exit and crash-quarantine treat a session that ends with a fresh `Errors/` artifact as "session failed with artifact" (per-flavor journal field), wiring into P2-12/F3-6 (§2.5).
  5. Redaction: extend `diagnostics/SecretRedactor.kt` patterns for account names/realm names appearing in wotlk crash text (they embed the WTF account folder path).

- **Upstream dependency:** none (artifact shapes confirmed against the qualified clients at S7; the register's Crash.txt+dmp claim is the input).

- **Verification:** device run per flavor: force a client crash (kill under DXVK with a corrupted texture path is enough to trigger the error handler), confirm Diagnostics lists the new artifact type with size, bundle export contains the redacted crash sample and passes `SupportBundleExporter.verify` (`:96`+), and a subsequent Save & Exit journal records the failed-session flavor field.

- **Effort:** M (1–3 days).

- **Depends on:** none (flavor runtime must boot first, which is S7's premise).

- **Owner stage:** S7.

---

### H4R2-10 — runtime-builds.json per flavor in support bundles

- **Goal:** every support bundle identifies, per flavor, exactly which server core, database runtime, Wine, and renderer builds produced the session — derived from lockfiles, never from hand-written strings. **This treatment owns the `runtime-builds.json` schema (the item-2 schema-2 shape) — it is the plan of record for the bundle shape: §2.2 H4-7 (client-side bundle half) and §2.5 P2-9 (budget mechanics) fill/extend it by reference and do not re-specify it.**

- **Current state:** `diagnostics/SupportBundleExporter.kt:28-35` writes a single `runtime-builds.json` with schema 1 carrying `appVersion`, `appBuild`, a database provider id selected by ABI, and *hard-coded strings* `"server": "o09-cmangos-c096bada-nobots-v1"`, `"wine": ClientRuntimeContract.RUNTIME_BUILD_ID`, `"renderer": ClientRuntimeContract.RENDERER_BUILD_ID`. Two defects in a multi-flavor world: (a) there is no flavor dimension at all — a bundle from a TBC session would still claim the classic server build; (b) the server string is exactly the stale constant the port plan flags in S1.1 (the `c096bada…` vs `ce83805d…` provenance split between `DataPreparationStore.kt:412` and `tools/build_o09_realm_runtime.py:37`), i.e. it can lie today and will lie harder with three flavors. The register pairs this gap with H4-7 ("per-flavor log inventory + runtime-builds.json in bundles", stage S7/S10).

- **Work items:**
  1. After S1 lands per-flavor lockfiles (`schemas/realm-runtime-lockfile-{flavor}[-abi].json`), generate the content at build time: extend the packaging/gradle step to embed a `runtime-builds` resource per flavor read from the lockfiles (commit + digest per component), so the app never carries a literal build string in Kotlin.
  2. Rework `SupportBundleExporter.kt:28-35` into `runtime-builds.json` schema 2: `{ "schema": 2, "flavors": [ {"flavor": "vanilla", "qualified": true, "server": …, "database": …, "wine": …, "renderer": …}, … ] }`, with the *active session's* flavor marked; keep the vanilla-only output byte-equivalent in the fields a supporter reads (schema bump is fine — bundles are not golden-pinned, but note the diff in the vanilla regression review).
  3. Add the session flavor to `device.json`/logs context so a bundle from a mixed evening (flavor switching, S9.3) is attributable per session segment: the supervisor journal (already bundled at `:41`) gains the flavor field per P2-12 (§2.5) — reference, don't duplicate.
  4. Add a `test_check_sources`-style unit test asserting the embedded resource digests match the lockfiles, and extend `tools/leak_sweep.py` to fail on any literal `o09-cmangos-*` string in app Kotlin sources.

- **Upstream dependency:** none beyond S1's lockfile set (the values come from `schemas/sources.json` + per-flavor lockfiles).

- **Verification:** build the app with vanilla-only qualified: bundle contains one flavor entry whose digests equal the vanilla lockfile (diffed in CI). After S2/S5: run one TBC session, export a bundle, confirm the TBC entry names the tbc lockfile commits and the vanilla entry is unchanged; negative test — corrupt a lockfile digest at build time → packaging gate fails loudly.

- **Effort:** S (<1 day) after S1; the S1 lockfile work itself is that stage's.

- **Depends on:** S1 (per-flavor lockfiles), P2-12 (journal flavor field).

- **Owner stage:** S7 (bundle shape), S10 (program-level bundle audit).

---

### H4-8 — Cache/WDB path map per flavor (wipe, validation, bundle routing, first-run warning)

- **Goal:** the app knows every client-side cache location per flavor, wipes them when server identity changes, accounts for their growth, warns about first-run cache building, and keeps them out of support bundles.

- **Current state:** the app has *zero* handling of `Cache/` or `WDB`: a repo-wide grep of `android/app/src/main/java/com/pocketrealm` for `WDB` returns no hits (run this session), and the only related concept is the planner's coarse `winePrefixAndCacheBytes` bucket (`importer/ImportModel.kt:107`). Client WDB files cache server-authored data (creature/GO/gameobject-cache and query caches); if they survive a change of realm database generation, the client can display stale rows. The register (H4-8) defines the flavor split to implement: vanilla keeps `Cache/WDB` at the client root, while WotLK writes locale subfolders (`Cache/WDB/<locale>/`), with TBC to be confirmed at capture (register C8's per-expansion layout discipline). Nothing in `ClientGenerationStore`'s app-owned file list (`ClientGenerationStore.kt:111`: `realmlist.wtf`, `WTF/Config.wtf`) covers Cache, and the support bundle correctly includes no cache content today — that must be *stated* per flavor rather than accidental.

- **Work items:**
  1. Add `client_ux.cachePaths` per flavor to `schemas/flavor.json`: `["Cache/WDB"]` (vanilla; tbc confirmed at capture) and `["Cache/WDB", "Cache/WDB/<locale>"]` semantics for wotlk (locale resolved from the recorded observed locale, H2-8); mirror into `ClientFlavor`.
  2. Implement `ClientCachePolicy` in `android/app/src/main/java/com/pocketrealm/client/` : `wipeForSession(clientRoot, flavor)` called at every launch preparation (next to the realmlist projection at `WineRuntimeStore.kt:2229-2230`), deleting only the flavor's cache paths; log bytes removed to the session journal.
  3. First-run cache-build warning: on the first session after import or cache wipe (detected by absence of the cache dir), surface copy in `ui/ClientScreen.kt` ("first minutes in a new world build the client's data cache; brief hitching is expected") — per-flavor wording from the flavor block.
  4. Storage accounting: fold wiped/cache bytes into the per-flavor storage display (H4R2-7) and the P1-8 quota measurement (§2.5).
  5. Bundle routing: add an explicit negative assertion in `SupportBundleExporter` tests that no `Cache/` content can enter (the exporter only reads fixed file names, `:46-53`, but the test pins it).
  6. Adversarial (R2): stale-cache test — populate `Cache/WDB` from generation N, boot generation N+1 after a DB content change, verify the wipe ran (dir mtime) so stale rows cannot render.

- **Upstream dependency:** none.

- **Verification:** RP6: import → launch → kill after world load → confirm expected cache path exists per flavor (root for vanilla/tbc, locale subfolder for wotlk); relaunch → wipe logged; export bundle → no cache entries; storage screen shows the accounted bytes. Vanilla capture-diff review confirms zero behavioral change (wipe timing identical to today's implicit nothing).

- **Effort:** M (1–3 days).

- **Depends on:** S5 (qualified client, observed locale), H4R2-7 (storage display surface).

- **Owner stage:** S7.

---

### H4R2-1 — Auto-login GLUE stage: realm preselection, char-select topology, Enter World injection

- **Goal:** on every flavor, an unattended launch lands the player in the world (not merely at the character screen), driven by a per-build login *and* character-select topology, 5/5 attempts, with secrets dropped at the same trust boundary as today.

- **Current state:** `client/SinglePlayerAutoLogin.kt` implements a state machine (`NEW/WATCHING/INJECTING/COMPLETE/CANCELLED`, `:114`) that polls mapped windows, waits for a *qualified topology*, then queues credentials via the X keyboard path (`queueCredentials`, bridge interface `:87-93`). The only topology model is `Build5875LoginTopology` (`:39-86`): x86-direct requires exactly one `wow.exe`-class window at the expected size plus one pid-0 decoy; the ARM lane requires exactly one exact-size non-desktop top-level surface. After credential injection the controller drains and finishes — there is *no* character-select handling anywhere (`grep -i "charselect|enterworld|glue"` over `SinglePlayerAutoLogin.kt` and `ClientRuntimeService.kt` returns nothing — run this session), so today the client sits at the character screen and a human taps Enter World; the port plan's S7 end state ("lands on the character screen unattended") codifies that partial contract. Realm preselection is currently achieved indirectly through the `realmName` cvar (see H4R2-3). TBC/WotLK login and character screens differ from vanilla (different window titles/classes/geometry), so both topologies and the Enter World choreography must be captured per build, per lane (x86-direct vs ARM/Box64), per port plan S7.5.

- **Work items:**
  1. Capture per flavor × lane: extend the capture tooling used for `ClientBuild5875LoginTest.kt` (androidTest, `:34-36`, `:183` logs `O07_LOGIN_ACCEPTANCE`) to record window class/name/geometry tables for the 8606 and 12340 login *and* character-select screens on both lanes; store as `client_ux.loginTopology` descriptors in `schemas/flavor.json` (window-class matchers, expected sizes, decoy rules) — data, not code, per the port-plan pattern.
  2. Generalize `Build5875LoginTopology` into `LoginTopology` consuming those descriptors; keep the vanilla descriptor byte-equal in behavior (same matcher outcomes on the vanilla windows table — lock it with `SinglePlayerAutoLoginTest.kt` fixtures).
  3. Add the GLUE stage machine: after `COMPLETE` of credential injection, a second watching phase matches the character-select topology, then injects the Enter World chord (the client's default Enter/`AP_ENTERWORLD` path) — only while `isInputNeutral` remains true, preserving the one-shot-claim discipline (`poll()`, `:178-233`).
  4. Failure choreography: if the char-select topology does not appear within a per-flavor timeout, cancel cleanly (state `CANCELLED`, secrets already dropped at `dropSecrets`, `:259-262`) and surface manual entry in `ClientScreen` copy — never retry injection (reuse the generation's sole-attempt rule, `:225-232`).
  5. Per-flavor androidTests: `ClientBuild8606LoginTest.kt`, `ClientBuild12340LoginTest.kt` cloned from the 5875 test with flavor manifests; both lanes.

- **Upstream dependency:** none (client behavior captured from qualified imports).

- **Verification:** device: 5/5 unattended launches reach the *world* (not char select) on each qualified flavor, each lane; 5/5 clean cancellations when the character list is empty (fresh account — H4R2-2's creation flow is what populates it); vanilla `ClientBuild5875LoginTest` unchanged and green.

- **Effort:** L (1–2 weeks).

- **Depends on:** S5 (qualified clients), H4R2-3 (realm preselection must be deterministic before login), H4-9 (TOS/EULA must not block the GLUE stage).

- **Owner stage:** S7.

---

### H4R2-3 — realmName hard-coded "MaNGOS" → per flavor; realmlist realmflags 0x40; char-select assertion

- **Goal:** the loopback realm presents itself with a per-flavor name, is advertised as recommended (realmflags 0x40) in the flavor's `realmlist` row, and the first login provably lands on that realm's character select — no flavor sees another's name.

- **Current state:** "MaNGOS" is written in two places: the fresh-generation SAFE_CONFIG seed ends with `SET realmName "MaNGOS"` (`importer/ClientGenerationStore.kt:239-265`, last line of the constant) and `ingame/ManagedConfigPolicy.kt:87-88` re-enforces `realmName "MaNGOS"` every launch when `conditions.realmLoopback` is true (the `add(ConfigWtfCodec.EnforcedLine("realmName", if (conditions.realmLoopback) "MaNGOS" else null))` block at `:86-90`). The realm name must match the `realmlist` table's name column of the seeded realm — the DB side is owned by S2/S4 (and the register correction C1 fixes the plan's schema claim: cmangos TBC/WotLK use `realmbuilds` varchar(64), not `gamebuild` — seed `realmbuilds='8606'/'12340'`). The client-side realmlist projection writes only `set realmlist <address>` (`client/ClientRealmEndpointProjection.kt:11-13`, projected at `WineRuntimeStore.kt:2229-2230`; also seeded at `ClientGenerationStore.kt:154`). The register asks for realmflags 0x40 ("recommended") in the realmlist row and an assertion that the first login lands on char select of the right realm.

- **Work items:**
  1. Introduce one canonical realm name per flavor in the flavor block (`realm.displayName`, e.g. "Pocket Realm — Vanilla/TBC/WotLK"); mirror into `ClientFlavor`. Change `ManagedConfigPolicy.kt:87-88` and the SAFE_CONFIG tail (`ClientGenerationStore.kt:265`) to emit `realmName` from the active flavor — with the vanilla value kept exactly `"MaNGOS"` unless the DB seed is changed in the same commit (both sides must move together; if kept, the other flavors may still adopt their own names — Decision Log entry either way, per invariant 6).
  2. Realm flags: extend the realmd seed SQL handling (S2.4/S4) so the flavor's `realmlist` row carries `realmflags` including 0x40 (recommended); the app-side `ServerRuntimeFiles` realmd diagnostics (the S2.4 item that touches `realmd_runtime.cpp` behavior) asserts the flag post-seed.
  3. Assertion: add a post-login check to the H4R2-1 topology work — the character-select topology descriptor includes the expected realm name window element where available, and the relay's `realm_info` (`native/realm-runtime/src/world_runtime.cpp`, JNI `realmInfoNative` at the `WorldNative` block) is extended to report the booted realm name so an androidTest can compare DB name ↔ cvar `realmName` ↔ client screen.
  4. Negative test (R2): point a wotlk client at the vanilla realm (wrong flavor) — login fails cleanly per S8's matrix and no `realmName` cvar for wotlk is ever written into a vanilla tree (leak-sweep assertion on WTF contents).

- **Upstream dependency:** `puzzled-pancake/mangos-tbc` / `-wotlk` branch `pocket-realm` — realmd seed row + `realmbuilds` handling per register C1 (base SQL from `cmangos/mangos-tbc[–wotlk]` `sql/base/realmd.sql`); commits pinned at S2.1.

- **Verification:** per flavor: seed → boot → first unattended login lands on char select (H4R2-1 harness) with the flavor's realm auto-selected; DB query shows `realmflags` bit 0x40 set; vanilla golden byte-test on `Config.wtf` output unchanged (G3-7).

- **Effort:** M (1–3 days).

- **Depends on:** S2/S4 (realmd seed per C1), S8 (auth matrix), H4R2-1 (topology assertion).

- **Owner stage:** S7 (app side) / S8 (DB + auth verification).

---

### H4R2-4 — Settings editor catalogs per flavor (109-cvar vanilla catalog → per-flavor data)

- **Goal:** the in-game settings editor shows each flavor's *own* fixed-settings catalog (names, ranges, fixed reasons, provenance pins) with per-build float formatting, driven by captured data — vanilla catalog untouched.

- **Current state:** `ingame/WowVanillaSettingsCatalog.kt` is a hand-built, integrity-conscious manifest: header (`:1-23`) documents 109 verified fixed settings (11 sound, 24 graphics, 37 interface, 37 advanced), `CATALOG_VERSION = 2`, provenance pinned to the MOUZU mirror of Blizzard's 1.12.1 FrameXML (commit `776d64e…`) plus a 2026-08-16 RP6 WTF capture; entries are `WowSettingDefinition` records with `backend/key/choices/fixedReason`. The editor consumes it via `WowGameSettings`/`WowSettingModel`, and the float echo is pinned by `ConfigWtfCodec.formatValue` (`ingame/ConfigWtfCodec.kt:122` — `"%.6f"` US locale), a 5875-format assumption the port plan (S7.2) says needs per-build verification. There is no TBC/WotLK catalog and no per-flavor registry. Upstream provenance reality (verified via web this session): the MOUZU/Blizzard-WoW-Interface mirror contains `1.12.1/` and `2.4.3/` folders on master — *no 3.3.5* — so the WotLK catalog needs a different FrameXML provenance source to pin.

- **Work items:**
  1. Capture, don't transcribe: extend the WTF write-out capture used for vanilla (the S7.3 plan item "capture from the qualified client") to dump each flavor's settings surface; build `WowTbcSettingsCatalog.kt` and `WowWotlkSettingsCatalog.kt` with the same `WowSettingDefinition` schema, `CATALOG_VERSION = 1`, per-catalog `SOURCE_COMMIT`/`PUBLIC_..._SHA256`-style integrity fields mirroring `WowVanillaBindingCatalog.kt:48-53`'s pattern.
  2. WotLK FrameXML provenance: pin a 3.3.5 FrameXML source through `tools/check_sources.py` (candidate: a community mirror; must resolve commit + digests — do not proceed without a pin; record the choice in the Decision Log). TBC uses MOUZU `2.4.3/` (verified present).
  3. Registry: `ClientFlavor` gains `settingsCatalogId`; `InGameSettingsEditor`/`InGameSettingsScreens` resolve the catalog per flavor; scope resolution follows H4-5's `wtf` descriptor (per-char uvar files on three-level flavors).
  4. Float format: capture one float-echo per flavor from the qualified client's Config.wtf write-out; add `floatFormat` to the codec selection (default remains `%.6f` — verify, don't assume; wotlk commonly writes full-precision decimals, confirm on device) and unit-test each flavor's round-trip in `WtfCodecsTest.kt`.
  5. Editor risk cap (port plan risk 7): if the wotlk surface is much larger, scope the editor to a curated subset and record the waiver — the catalog still captures everything, the UI filters.

- **Upstream dependency:** MOUZU/Blizzard-WoW-Interface master (commit to be pinned for `2.4.3/`); a WotLK 3.3.5 FrameXML mirror (to be pinned in-stage via `tools/check_sources.py`); qualified TBC/WotLK clients for device captures.

- **Verification:** per flavor: change N settings in the app editor, launch, confirm the client's own Options panel reflects them and Config.wtf round-trips byte-stably (editor re-read); catalog integrity hash test per flavor; vanilla catalog diff shows zero changes (G3-6).

- **Effort:** L (1–2 weeks).

- **Depends on:** S5 (qualified clients), H4-5 (WTF layout descriptor).

- **Owner stage:** S7.

---

### H4R2-5 — SAFE_CONFIG per-flavor seed (autoLootDefault, sticky targeting, click-to-move for the controller contract)

- **Goal:** every flavor's fresh `Config.wtf` seed carries that build's correct controller-friendly defaults (loot, targeting, movement), written as flavor data — the vanilla seed bytes unchanged.

- **Current state:** the seed is one vanilla-only constant: `importer/ClientGenerationStore.kt:239-265` (`SAFE_CONFIG`) writes `readTOS/readEULA/readScanning`, `movie 0`, `gxResolution 1280x720`, `gxVSync/gxMultisample*`, `maxFPS 30`, `MasterSoundEffects`, `autoSelfCast 1`, `statusBarText`, `UnitName*`, `cameraSmoothStyle 0`, `ffxGlow/ffxDeath 0`, `farclip 177`, `realmName "MaNGOS"`. Its own comment (`:232-238`) says the interface-default keys sit outside `ManagedConfigPolicy`'s enforced set so the client owns them after first launch. The register (H4R2-5) names the flavor deltas: `autoLootDefault` (a TBC+ cvar; 1.12 has no auto-loot default), sticky targeting (exists across flavors under different panel wiring), and click-to-move (TBC+; the vanilla catalog carries `interface.clickToMove` rows only as *stock-panel* reference — `WowVanillaSettingsCatalog.kt:301`, `:516` — the seed has none, correctly). Port plan S7.2 already schedules "per-flavor SAFE_CONFIG sets" for the enforced-keys side; this gap is the *seed* side.

- **Work items:**
  1. Capture-verify each flavor's cvar names/values from the qualified clients (do not trust memory for cvar spelling per build — e.g. confirm `autoLootDefault` on 8606 and 12340 write-out, and the click-to-move key(s) the 2.x/3.x options panels write). Record as `client_ux.seedConfig` (list of `key,value,numberForm`) per flavor in `schemas/flavor.json`.
  2. Replace the single `SAFE_CONFIG` constant with: shared header (TOS/EULA/scanning/movie/renderer keys — still validated per flavor against `ManagedConfigPolicy`'s re-derived enforced set) + per-flavor tail from the flavor data; vanilla tail must produce byte-identical output to today's constant (golden byte-test, G3-7).
  3. Controller contract seeding: for tbc/wotlk set the flavor's auto-loot and click-to-move keys to the values the controller profile depends on (auto-loot on for one-stick looting; click-to-move off — the stick is the mover), and add the same lines to the *editor catalog* (H4R2-4) so the player can change them; document which keys `ManagedConfigPolicy` re-enforces per launch (seed keys are client-owned afterward, as the `:232-238` comment establishes).
  4. Keep `FIRST_LOGIN_UVAR_DEFAULTS` (`client/WineRuntimeStore.kt:1953-1961`, written by `seedFirstLoginAccountDefaults` `:2109-2127`) flavor-parameterized the same way — wotlk's uvar set differs (register S8.5), so split that list per flavor too, vanilla list unchanged.
  5. Negative test: unknown seed key for a flavor → staging fails loudly (the fail-loud config discipline of port plan S3.6, applied to the client side).

- **Upstream dependency:** none (client write-outs captured at S5/S7).

- **Verification:** fresh generation per flavor → Config.wtf contains exactly the flavor's seed set; in-game change of auto-loot survives relaunches (client-owned rule holds); golden byte-test proves the vanilla seed is unchanged vs. the current constant; R2 negative: injecting an unsupported key into the flavor data fails staging with an actionable message.

- **Effort:** M (1–3 days).

- **Depends on:** S5 (capture), H4R2-4 (editor rows for the seeded keys).

- **Owner stage:** S7.

---

### H4R2-6 — Import/tutorial copy per flavor (installer-era guidance; WotLK torrent downloaders → PC-extract-first)

- **Goal:** every import-facing and tutorial-facing string is flavor-correct — client version, sizes, and the import-lane guidance — so a WotLK user with a torrent-downloader install knows exactly what this app accepts before wasting a transfer.

- **Current state:** the first-run tutorial hard-codes vanilla: `ui/FirstRunTutorial.kt:31` ("You need your own copy of the World of Warcraft 1.12.1 (build 5875) …") and `:57` (title "What you need: an extracted WoW 1.12.1 client"). The addons screens are equally pinned (`ui/AddonsScreen.kt:102` "the researched Vanilla 1.12 catalog", `:384` "requires Vanilla Interface 11200 metadata"). The import pipeline is already flavor-routed where it matters (scanner constants route through `ClientFlavor` per S0/S5; storage planner reads the flavor block, `importer/ImportStoragePlanner.kt:12-20`) — this gap is the *copy* layer. The register (H4R2-6 row, shared with H4R2-7) adds the installer-era reality: WotLK 3.3.5 was commonly distributed as Blizzard's downloader/torrent output (a partial-download layout), which this app's archive/Inno lanes do not accept; the fix is guidance, i.e. "extract on PC first" — aligning with §2.2's P3-9/P3-10 VAL rejection copy work.

- **Work items:**
  1. Flavor-aware copy resources: replace the literals in `FirstRunTutorial.kt:31,57` with strings parameterized from `ClientFlavor.expansion` / `clientVersion` / flavor storage numbers; the import screen's "what you need" card renders per selected/importable flavor (S9's multi-flavor import flow decides which flavor cards show — coordinate).
  2. Add a per-flavor "supported install shapes" note to the import screen and `docs/wiki/Game-Files-and-Import.md`: folder lane, supported archives (zip/7z/rar/Inno where applicable), and the explicit warning that WotLK torrent/downloader partial downloads and `Installer.exe`-era outputs are rejected with their `VAL-*` code and must be extracted to a folder on PC first (P3-9/P3-10 own the per-flavor VAL copy text — link it here).
  3. Tutorial steps per flavor: the checklist items (MPQ set, disk space) derive from the flavor block (`mpq_set.required`, `storage.extracted_estimate_bytes`) instead of prose; verify the rendered numbers match `ImportStoragePlanner` math.
  4. Sweep the remaining user-visible vanilla strings the port plan S7.7 already lists (`HomeScreen` chip, `SettingsScreen` tweaks copy, `AddonsScreen`, `ControlsScreen`, `DiagnosticsScreen` flavor line) — H4R2-6 owns the tutorial/import subset; assert no bare "1.12"/"5875" remains outside `ClientFlavor`/flavor-registry definitions via a new `tools/leak_sweep.py` UI-strings rule (the sweep already greps `1.12`, `5875` — extend to resXML/compose string tables if they bypass it).
  5. Wiki: `docs/wiki/Getting-Started.md` + `Game-Files-and-Import.md` gain per-flavor sections (S10.4 de-vanilla-fication shares this work — coordinate so it's done once).

- **Upstream dependency:** none.

- **Verification:** string-sweep test (unit) asserting no hard-coded build strings in UI modules; device walkthrough per flavor: start tutorial with only that flavor importable → every step names the right client, sizes, and lanes; negative: attempt a WotLK downloader-partial import → rejection copy names the exact VAL code and the PC-extract-first instruction.

- **Effort:** M (1–3 days).

- **Depends on:** S5 (import gates finalized so copy can't promise what the scanner rejects), P3-9/P3-10 (§2.2, VAL copy per flavor).

- **Owner stage:** S5 (import copy — the register's H4R2-6/H4R2-7 owner and the stage whose gates the copy must match) / S9 (first-run integration, multi-flavor import flow).

---

### H4R2-7 — Storage budget display per flavor (sizes, installer-era budget guidance)

- **Goal:** before and during import, the app shows each flavor's real budget math — extracted estimate, total cap, free-space verdict — from the flavor block, with honest numbers for TBC/WotLK once measured.

- **Current state:** the plumbing is already flavor-routed (S0/S5 groundwork landed): `ImportStoragePlanner` takes `extractedEstimate` from `flavorExtractedEstimate()` (`importer/ImportStoragePlanner.kt:15`, `:73`), and `ImportLimits.maxTotalBytes` defaults to `qualifiedMaxTotalBytes()` (`importer/ImportModel.kt:61`, resolved from the qualified flavor at `:91` with a `requireNotNull` fail-loud). In `schemas/flavor.json` only vanilla carries measured `storage` values (verified: vanilla `extracted_estimate_bytes = 4294967296`, `max_total_bytes = 21474836480`, `max_files = 100000`, `max_entries = 110000`; tbc/wotlk storage fields exist but unqualified/null pending S5/S6 measurement — port plan §6 says "measure"/"~25 GiB"/"~35 GiB confirm"). What's missing is (a) the measured values, and (b) the *display* surface: the plan verdict (allocatable vs. required, including staged-archive and scratch allowances from `plan()` at `ImportStoragePlanner.kt:24-36`) reaching the user as flavor-specific text rather than generic copy.

- **Work items:**
  1. Measurement riders on S5/S6: when the flavor's client is qualified, record real extracted size (register P3-2 targets ~25 GiB TBC / ~35 GiB WotLK — verify, don't copy) into the flavor block's `storage` fields; `ImportModel.kt:91`'s requireNotNull then fails for the right reason until measurement lands.
  2. Budget display: extend the import progress/presentation layer (`importer/ImportProgressPresentation.kt`) to render the planner's `StoragePlan` per flavor: "needs ≈ X GiB extracted + Y GiB staging; Z GiB free → fits / tight / will not fit" with the flavor name; include the WotLK-specific advice (archive staging doubles the transient footprint — say so when `stagedArchiveBytes > 0`, the field already exists at `:27`).
  3. Low-headroom choreography per flavor: denial copy at the second import (S9.2's "deny second import with actionable message") must quote the flavor's own numbers, and H4R2-6's import copy cross-links it.
  4. Unit tests: `ImportStoragePlanner.calculate` (companion, `:52-54`) already takes raw numbers — add per-flavor golden cases once measured, plus a test that an unqualified flavor's null budgets produce the fail-loud path (both directions of R2's matrix).

- **Upstream dependency:** none (numbers measured from qualified imports; §2.2 P3-2 owns the verification of the cap math).

- **Verification:** after TBC qualification: import on a device with known free space → displayed math matches a hand-computed `calculate()` for the TBC numbers within 1%; denial path on a artificially small root shows the flavor-specific copy; vanilla display values unchanged (golden).

- **Effort:** S (<1 day) for the display, riding the S5/S6 measurement work.

- **Depends on:** S5/S6 (measured storage per flavor), P3-2/P3-8 (§2.5 cap and peak-space verification), H4R2-6 (copy surface).

- **Owner stage:** S7 (display), numbers land with S5/S6.

---

### H4R2-8 — Per-flavor first-run state + flavor-switch card

- **Goal:** first-run completion, tutorial gating, and the Home-screen flavor presentation are per flavor — importing a second flavor gets its own first-run experience, and switching flavors is a visible, safe card flow.

- **Current state:** first-run state is global: `ui/FirstRunTutorial.kt:101-107` gates the tutorial on `setupComplete == false && hasImportedClient == false` with a process-wide `TutorialReplayRequests` replay channel (`:124-128`, "Settings → Setup → 'Show first-run setup'"); these keys live in the single `pocket_settings` DataStore (`storage/Settings.kt:64-65`, store name `pocket_settings`). The Home screen chip is vanilla-only in wording ("· Client tweaks on / · Vanilla client", `ui/HomeScreen.kt:822`). S9's plan (work items 1–3) owns the multi-copy registry, per-flavor namespaces, and the switch-with-full-Save-&-Exit policy; this gap is the UX state layer on top: which flavor's tutorial ran, and what the Home screen shows while multiple flavors coexist.

- **Work items:**
  1. Split the first-run keys per flavor in `storage/Settings.kt`: `setupComplete_<flavor>`, `hasImportedClient_<flavor>` (optional fields with legacy read fallback to the existing global keys = vanilla — the G3 optional-field discipline), keeping `FirstRunTutorial.kt:107`'s gate computing from the *selected* flavor's keys.
  2. Flavor-switch card on Home: when ≥2 flavors are qualified, a card per imported client (flavor, client id, last-played, storage used from the S9.2 accounting) with "Play" / "Switch" actions; switching routes through the S9.3 session policy (full Save & Exit of the previous realm before the next boot) and shows that cost honestly ("switching ends the current session and saves the world").
  3. Per-flavor "what you need" text on each card (S9.6) reusing H4R2-6's copy.
  4. Adversarial: kill the app mid-switch (R2's marquee S9 scenario) → relaunch shows neither flavor mid-session; journals carry flavor per P2-12 — this gap only owns the *card state*, but must render the recovered state truthfully (no flavor shown as running unless its journal says READY).
  5. Tests: DataStore migration test (old global keys → vanilla keys); Compose UI test of the two-flavor card matrix.

- **Upstream dependency:** none.

- **Verification:** with vanilla+TBC installed: each flavor's first tutorial shows once; replay request replays the selected flavor's; switching 10× interleaved (S9 end state) leaves the cards accurate against journal state; vanilla single-flavor behavior pixel-identical (card hidden while only one flavor exists).

- **Effort:** M (1–3 days).

- **Depends on:** S9.1–S9.3 (multi-copy registry, namespaces, switch policy), H4R2-6 (copy).

- **Owner stage:** S9.

---

### H4R2-9 — IME per flavor (WotLK native IME under Wine vs the Talk.lua surrogate; support matrix on Controls)

- **Goal:** text entry is honest per flavor: the bounded keymap path works identically, WotLK's native IME is either verified-working under Wine or explicitly unsupported, and the Controls screen states the matrix instead of leaving users to discover rejection.

- **Current state:** input text goes through `client/ImeCharMap.kt`, whose header (`:1-30`) defines the contract: each supported character maps to a proven Android keycode + shift state through the winlator keysym table; unsupported characters (accented letters, CJK, emoji) are *rejected visibly* with their codepoints, never substituted. That is deliberately not an IME. The addon-side assist is `assets/addons/android-port/AndroidPort/Talk.lua:1-16`: the radial Talk action resolves the addressee (current target, else the freshest nearby whisperer/speaker) and opens the composer pre-filled with `/w <name> ` — "no name typing, ever". The register (H4R2-9) asks: WotLK ships a native IME (client-side candidate window driven by `imm32`); under Wine that requires Wine's IME implementation to be functional in the prefix — verify per flavor, enable if it works, else keep the surrogate and say so; and put the support matrix on the Controls screen (whose reference section is at `ui/ControlsScreen.kt:397-400`, "WoW 1.12.1 action reference").

- **Work items:**
  1. Verification spike on the qualified wotlk client: launch under the standard DXVK/Wine prefix, enable the client's IME/language settings, drive an Android CJK/JP IME into the chat edit box, and record whether candidate windows render and commit text (x86-direct and ARM lanes separately — the winlator keyboard path differs). Record the result as `client_ux.ime.native: "works" | "broken"` per flavor × lane in `schemas/flavor.json`; if "works", document required prefix components (Wine `imm32` builtin vs native) in the flavor block's `wine` section and qualify it in the S7 prefix work (port plan S7.8).
  2. Whatever the spike shows, extend the Talk.lua surrogate to all flavors that keep it: TBC (Interface 20400 port of the AndroidPort addon, per the S7.6 AndroidPort decision) needs the same target/last-whisperer resolution re-verified against 2.4.3 chat events; keep vanilla behavior byte-equal.
  3. Support matrix on the Controls screen: replace the vanilla-only reference heading (`ControlsScreen.kt:397`) with per-flavor text stating: which characters are keymap-supported (the `ImeCharMap` bounded set), whether native IME works on this flavor/lane, and the Talk radial as the no-typing path; link `docs/wiki/Controls.md`.
  4. Rejection telemetry: `ImeCommitResult.rejectedChars` (header `:24-27`) surfaces in Diagnostics as a count — so a supporter can see "user's keyboard language unsupported" instead of a mystery.
  5. Wiki `Controls.md` + `Accounts-Security-and-Networking.md` (account-name entry constraints) updated with the matrix (S10.4 rider).

- **Upstream dependency:** none (Wine/Box64 behavior verified on device; no upstream change requested).

- **Verification:** per flavor × lane: type US-ASCII chat (works), type a CJK string (either commits via verified native IME, or is rejected with visible codepoints — never mojibake); Talk radial on a bot whisper opens the pre-filled composer on every flavor where the AndroidPort addon is installed; Controls matrix text matches measured reality.

- **Effort:** M (1–3 days) including the spike.

- **Depends on:** S5 (qualified clients), S7.8 (prefix qualification), H4R2-1 (login text entry uses the same keymap).

- **Owner stage:** S7.

---

### H4R2-11 — Binding catalog capture per flavor (per-build Bindings.xml manifests)

- **Goal:** each flavor has a versioned, integrity-hashed catalog of its own stock binding IDs (categories, semantics, hidden-pointer set), so the bindings editor, action picker, and interact/bonus-bar features resolve real binding names per build — never invented ones.

- **Current state:** `client/WowVanillaBindingCatalog.kt` is the template: `CATALOG_VERSION = 2`, `WOW_CLIENT_VERSION "1.12.1"`, `WOW_CLIENT_BUILD 5875`, `SOURCE_COMMIT 776d64ecf708540969e34df9680ffdacb3e8b555` (MOUZU 1.12.1 FrameXML), plus two SHA-256 order hashes over the 211 public IDs + 3 supported hidden pointer IDs (`:44-53`); categories at `:16-31` (movement/chat/action bars/bonus bar/targeting/…/internal pointer), semantics `KEY_LIKE` vs `INTERNAL_POINTER_HOLD` (`:33-42`), and the guard comment at `:55`: 1.12.1 defines neither INTERACTTARGET nor LOOTALL — "do not manufacture modern binding IDs". TBC/WotLK catalogs don't exist; the register (H4-3) makes per-flavor catalogs first-class for addons, and H4-2 (this section) needs the WotLK *vehicle/override bar* binding categories captured. Provenance reality (web-verified this session): MOUZU's mirror carries `1.12.1/` and `2.4.3/` only — no 3.3.5 tree — so the WotLK capture needs another FrameXML source or a qualified-client WTF `Bindings.xml`/binds read (the port plan S7.3 names exactly that capture: "capture from the qualified client (WTF write-out + Bindings.xml/binds read)").

- **Work items:**
  1. Build `WowTbcBindingCatalog.kt` / `WowWotlkBindingCatalog.kt` with the same data schema (id/label/category/description/advanced/semantics), each with `CATALOG_VERSION = 1`, per-catalog `WOW_CLIENT_BUILD`, `SOURCE_COMMIT`, and order SHA-256 computed exactly like `:48-53`.
  2. WotLK specifics: capture the vehicle/override-bar binding categories and `INTERACTTARGET` (consumed by H4-2 and H4-4), and mark new hidden/system bindings per the same exclusion rules (no debug/iTunes/commented entries); TBC: confirm bonus-bar naming (the 2.4.3 bonus bar differs from 1.12's) from the captured Bindings.xml.
  3. Flavor registry: `ClientFlavor` gains `bindingCatalogId`; `ui/InGameBindingsScreen.kt`, the action picker, and `InputProfile` resolve per flavor; the vanilla catalog object stays the imported constant (G3-10: catalogs verbatim for vanilla).
  4. Integrity tests per flavor mirroring `client/WowVanillaBindingCatalogTest.kt` (order hash, no invented IDs, hidden IDs excluded from the public picker).
  5. Capture tooling: extend the S7.3 capture script to emit a candidate catalog JSON from the qualified client's `Bindings.xml` + WTF binds, human-reviewed before pinning (provenance chain per invariant 3).

- **Upstream dependency:** MOUZU/Blizzard-WoW-Interface master `2.4.3/` folder (pin commit via `tools/check_sources.py`); for WotLK, a pinned 3.3.5 FrameXML mirror or the qualified client's own `Bindings.xml` (record the chosen provenance in the Decision Log).

- **Verification:** per flavor: catalog hash test green; editor shows only that build's IDs (spot-check 10 known WotLK-only bindings present, 10 vanilla-only absent); `WowVanillaBindingCatalogTest` unchanged; binding round-trip on device — assign a WotLK-only binding from the editor, relaunch, survives (bindings-cache write path per H4-6).

- **Effort:** L (1–2 weeks) for both catalogs + tooling.

- **Depends on:** S5 (qualified clients), H4-2 (this section — vehicle/override bar slot model consumes these categories).

- **Owner stage:** S7.

---

### H4-11 — Owned-chord collision audit vs 3.3.5 defaults

- **Goal:** every key chord the controller overlay claims (F7, Select+chords, stick clicks) is audited against each flavor's *default* bindings — collisions are either re-owned by the repair journal with correct restore, or the claimed key moves — proven by a generated audit table per flavor.

- **Current state:** the overlay owns a set of chords on the player's behalf: `AndroidPortBindingRepair.kt:55` ("The key chords this repair owns on behalf of the controller overlay") captures pre-launch binding state and restores it on addon removal (`:61-142`); the interact binding self-claims F7 (`Core.lua:879-902`: checks `GetBindingAction("F7") == "AP_NEARBY_INTERACT"`, sets it if free, records the owned key); `InputProfile` defines the physical-to-keyboard mapping including sticky-modifier handling (`InputProfile.kt:85` Num Lock note) and chorded access (Select+R2 etc., `InputContract.kt:1569+`). All of that was tuned against 1.12 defaults. 3.3.5's default `Bindings.xml` binds far more keys by default (action bars 1–12 plus stances/vehicles, plus defaults on NUMPAD/arrow-adjacent keys), so a chord the vanilla lane safely claimed may clobber a stock WotLK binding — or be clobbered by it at login when the client normalizes defaults. No audit artifact exists today; the register (H4R2-11/H4-11 row) asks for this audit explicitly at S7.

- **Work items:**
  1. Generate per flavor an audit table: input = the overlay's claimed chord list (extract from `InputProfile` + the AndroidPort `Bindings.xml` defaults) × the flavor catalog's default bindings (from H4R2-11's captured data, including which IDs are bound by default in a fresh WTF); output `docs/bindings-audit-<flavor>.md` + a machine-readable JSON in `android/app/src/main/assets/` consumed by a unit test so drift fails CI.
  2. Resolve each collision one of two ways: (a) re-map the overlay claim (per-flavor default chord overrides in the flavor block — e.g. WotLK's default use of a contested key moves the interact claim), or (b) accept and journal: `AndroidPortBindingRepair` captures the default binding and restores it on removal — this already works mechanically, but the *test* must prove restore of a 3.3.5 default value, not just vanilla's.
  3. Extend `AndroidPortAssetTest.kt` and `WowVanillaBindingCatalogTest.kt` patterns with a `BindingsAuditTest` per flavor: no claimed chord equals a flavor default-bound key unless the repair journal owns it.
  4. Re-verify the sticky-modifier hazards per flavor (Num Lock, `InputProfile.kt:85`) — WotLK uses numpad defaults heavily; if the winlator sticky-NumLock path can clobber them, pin `InputProfile` away from those keys for tbc/wotlk.

- **Upstream dependency:** none beyond H4R2-11's captured catalogs.

- **Verification:** audit table generated for all three flavors with zero unowned collisions (CI test); device: on wotlk, trigger every overlay control once, uninstall the addon → each stock default binding is byte-restored in `bindings-cache.wtf`; vanilla audit table shows the same result as today's behavior (no diff).

- **Effort:** M (1–3 days).

- **Depends on:** H4R2-11 (per-flavor catalogs), H4-6 (journal scope correctness).

- **Owner stage:** S7.

---

### H4-12 — DK rune HUD widget (or documented absence) + hero-class first-run copy

- **Goal:** a WotLK death knight gets a readable rune resource display suited to the controller overlay — as an AndroidPort HUD widget on Interface 30300 — or, if the AndroidPort-per-flavor decision excludes the addon, an honest in-app note; plus first-run copy that introduces the hero class per flavor.

- **Current state:** the AndroidPort addon is a clean-room Interface-11200 project (`addons/AndroidPortPackage.kt:11-18`, VERSION 0.6.1) whose `Hud.lua` today provides chat placement and an experience strip (`Hud.lua:1-16`) with `MAX_PLAYER_LEVEL = 60` hard-coded (`Hud.lua:27`) — it cannot render a rune bar (no such resource in 1.12), and it is not installed for tbc/wotlk at all pending the S7.6 decision ("AndroidPort addon: port to the flavor's UI API or exclude per flavor with honest copy"). Repository-level policy is also pinned vanilla: `addons/AddonRepository.kt:878-879` requires the built-in addon to declare `## Interface: 11200`. Meanwhile WotLK's death knight (availability gated by J2-1/H1-14, §2.1) needs a rune HUD because the stock 3.3.5 rune frame sits in the default UI where the controller's touch overlay and action clusters fight it for space (the overlay's movable-cluster system, `ui/TouchOverlay.kt:492-502`, is the app-side precedent for repositionable HUD).

- **Work items:**
  1. Decision first (port plan §8.2.4): if AndroidPort is ported to 30300 (the likely choice — the addon is the controller contract), extend `Hud.lua` with a rune widget: six rune slots read via the 3.3.5 API (`UnitPower("player", SPELL_POWER_...)`-equivalent rune APIs of 3.3.5: `GetRuneCooldown(i)` / `UnitRunePower` era functions — confirm exact 30300 API names from the captured FrameXML at implementation, do not guess), rendered as plain texture-less status blocks consistent with the existing XP strip style; gate on `select(2, UnitClass("player")) == "DEATHKNIGHT"`.
  2. Flavor-gate the addon build: per-flavor asset variants of `AndroidPort.toc`/`Interface` numbers; relax `AddonRepository.kt:878` to validate against the flavor's interface set (`ClientFlavor.addonInterfaces`) while keeping the vanilla assertion literal.
  3. Fix the Hud constants that are vanilla-only: `MAX_PLAYER_LEVEL` from unit data / flavor (`Hud.lua:27`).
  4. If the decision is *exclude*: instead add the honest copy surface — `ui/ClientScreen.kt` or the Controls screen states "rune display: use the stock rune frame; drag it via the mover module (FrameMover.lua)" and H4-1's secure-action port (this section) carries the mover.
  5. Hero-class first-run copy: when the first wotlk login lands (H4R2-1) and the selected character is a DK, show a one-card primer (runes, runic power, presence) in the tutorial layer, flavor-gated; vanilla sees nothing.
  6. Tests: `AndroidPortAssetTest.kt` gains rune-widget assertions for the 30300 variant (the existing pattern at `:178` asserts Lua lines); vanilla asset assertions unchanged.

- **Upstream dependency:** none (30300 API surface taken from the qualified client's FrameXML capture, H4R2-11 tooling).

- **Verification:** device, wotlk: create/lease a DK, spend runes in combat → widget empties/refills in sync with the stock frame (screen-capture comparison at 1 s intervals for 30 s); non-DK characters see no widget; vanilla/TBC builds contain no rune code paths (asset test); if excluded, the copy card is present and truthful.

- **Effort:** M (1–3 days).

- **Depends on:** S7.6 AndroidPort per-flavor decision; H4R2-11 (30300 FrameXML capture); J2-1/H1-14 (§2.1, DK actually playable).

- **Owner stage:** S7.

---

### H4-13 — VoiceOver per-flavor policy (folder names, TOC deps, asset names; TBC coverage honesty note)

- **Goal:** VoiceOver installs work per flavor where a pinned per-expansion artifact exists, and where it does not, the UI says so plainly — with the vanilla resolution chain untouched.

- **Current state:** the vanilla lane resolves exactly two upstream artifacts from `mrthinger/wow-voiceover`: `addons/VoiceOverReleaseResolver.kt:6-10` pins `INSTALL_ID = "mrthinger__wow-voiceover"`, `PLAYER_FOLDER = "AI_VoiceOver"`, `DATA_FOLDER = "AI_VoiceOverData_Vanilla"`; `resolve()` (`:24-45`) requires the player asset named `AI_VoiceOver-WoW_1.12-$tag.zip` (`:31`) and exactly one data-pack reference in the release body. Validator policy pairs with it: `AddonArchiveValidator.Policy.VOICEOVER_PLAYER` (expects folder `AI_VoiceOver`, root TOC `AI_VoiceOver.toc`) and `VOICEOVER_DATA` (interfaces `{11200, 100000}`, `OptionalDeps: AI_VoiceOver_112, AI_VoiceOver`, cap 1536 MiB archive/expanded — `AddonArchiveValidator.kt:26-38`, `:255-256`), applied at `AddonRepository.kt:504-510`. For TBC/WotLK nothing exists. Web verification this session: the `mrthinger/wow-voiceover` repository page shows only `AI_VoiceOverData_Vanilla` and Vanilla-oriented install docs — the register's premise (port plan S7.6: "AI_VoiceOver has per-expansion artifacts") is *not confirmed on that upstream today*; the TBC/WotLK artifact identity (different fork? different asset naming? different `OptionalDeps` like `AI_VoiceOver_2xx`?) must be established by research before any policy is written. This gap therefore has a hard research gate and a default of honest absence.

- **Work items:**
  1. Research & pin (gate): identify the VoiceOver addon + data-pack artifacts for 20400 and 30300 (community VoiceOver forks for TBC/WotLK exist in the ecosystem; candidates must satisfy license review and the provenance chain — exact repo/tag/asset-name/SHA-256, recorded in the flavor block and `tools/check_sources.py`). If no artifact passes, stop at item 4.
  2. Per-flavor resolver: generalize `VoiceOverReleaseResolver` with a per-flavor descriptor (install id, player/data folder names, asset name template, TOC dep strings); the vanilla descriptor must resolve byte-identically (golden test on the current fixture).
  3. Per-flavor validator policies: `Policy.VOICEOVER_PLAYER_TBC/WOTLK`, `VOICEOVER_DATA_TBC/WOTLK` (expected folders like `AI_VoiceOverData_TBC`/`_WotLK` *as measured*, accepted interfaces `{20400,…}` / `{30300,…}` per the found artifacts), caps re-derived per H4-10's measurement work.
  4. Honesty note (required regardless): the Addons screen VoiceOver card states per flavor: supported with named source, or "not available for this flavor — no qualified VoiceOver pack" with a one-line coverage statement for TBC (register: "TBC coverage honesty note" — quest-voice coverage on TBC packs is partial; say so).
  5. G3-6 guard: the vanilla `VOICEOVER_DATA` policy stays byte-pinned; add a unit test that the vanilla policy object is untouched by the generalization.

- **Upstream dependency:** for vanilla: unchanged (`mrthinger/wow-voiceover` releases). For tbc/wotlk: *to be pinned* — only artifacts passing the research gate; the register/catalog work (H4-3, this section) coordinates.

- **Verification:** vanilla: existing resolver tests green unchanged. If pinned per flavor: install flow end-to-end on device — release resolve, download, validate under the flavor policy, addon loads in the flavor client and speaks a quest line; negative: a vanilla data pack offered to the wotlk lane is rejected with the flavor's copy; the honesty note renders when no pack is qualified.

- **Effort:** M (1–3 days) implementation + research gate (time-boxed; record the outcome either way).

- **Depends on:** H4-3 (this section, per-flavor catalog pins), H4-10 (caps/policy sets).

- **Owner stage:** S7.

---

### H4-14 — Out-of-date addon client-behavior copy per flavor

- **Goal:** when an addon's `## Interface` doesn't match the running flavor, every surface that mentions it — validator error, catalog card, install gate — explains exactly what will happen if the player forces it and what the supported number is, per flavor.

- **Current state:** the rejection exists but is vanilla-flavored in wording and single-flavored in policy: `AddonArchiveValidator.kt:156-157` fails with `"$path does not declare a supported Vanilla interface"`, and the default `Policy.acceptedInterfaces = setOf(11200)` (`:18`). The Addons screens reinforce it in user copy: `ui/AddonsScreen.kt:348` ("validates archive structure and Vanilla interface metadata"), `:384` ("requires Vanilla Interface 11200 metadata"), `:102`/`:111` ("Vanilla 1.12 catalog", "Vanilla-compatible repository"). Client behavior for mismatched interfaces differs per build (a 11200 addon on 3.3.5 typically loads with Lua errors or is flagged out-of-date by the client's own addon list depending on the client's "load out of date addons" state), and the register asks for that behavior to be *described honestly* per flavor rather than left to folklore. This gap deliberately does not decide whether to *allow* forced loading — the validator stays a hard gate on structure and declared interface; the deliverable is per-flavor copy plus the policy plumbing.

- **Work items:**
  1. Flavor-aware messages: the validator's error strings take the flavor's interface set (from the caller's `Policy`, which H4-10 makes per-flavor) — "declares Interface 11200; this client (3.3.5a) accepts 30300" — and the generic fallback drops the word "Vanilla".
  2. Behavior copy table: `docs/wiki/Add-ons.md` (S10.4 rider) gains a per-flavor section stating what an out-of-date-interface addon does on that client (TBC 2.4.3 and WotLK 3.3.5 present an out-of-date checkbox/addon-list state; vanilla 1.12 loads regardless — verify each statement against the qualified clients and the captured FrameXML, and write only what was observed).
  3. Card-level metadata: the researched catalog (`assets/addons/catalog-v1.json` — verified this session: 154 entries, all tagged "1.12"-family in `clientTarget`, only 5 mention 2.4.3 and 2 mention 3.3.5) gains per-flavor `clientTarget` normalization in H4-3's catalog work; H4-14's UI change renders that field on the detail card ("works with: TBC (20400)" / "vanilla only").
  4. Tests: extend `AddonArchiveValidatorTest.kt` (register §7 cites `:101-215`) with per-flavor acceptance/rejection matrices and the exact message strings; string test that no user-facing surface says "Vanilla" when the flavor isn't.

- **Upstream dependency:** none (copy verified against qualified clients).

- **Verification:** per flavor: attempt to install a wrong-interface archive → rejection names both numbers; attempt a right-interface archive → accepted; wiki section reviewed by R6 against observed client behavior on device (one forced-load demonstration per flavor recorded in the evidence pack, validator still refusing).

- **Effort:** S (<1 day).

- **Depends on:** H4-10 (per-flavor Policy sets), H4-3 (this section, per-flavor catalogs for card metadata).

- **Owner stage:** S7.

---

### H4-10 — Validator caps re-derived vs real WotLK packs; per-flavor Policy sets

- **Goal:** the addon validator's size/entry caps and policy set are per flavor, derived from measuring real candidate packs for each expansion — rejecting genuine packs is a defect just as much as accepting a malicious one — while the vanilla policies stay byte-pinned.

- **Current state:** `AddonArchiveValidator.kt:248-254` fixes the caps as constants: `MAX_ARCHIVE_BYTES = 128 MiB`, `MAX_EXPANDED_BYTES = 512 MiB`, `MAX_FILE_BYTES = 128 MiB`, `MAX_TOC_BYTES = 256 KiB`, `MAX_ENTRIES = 20_000`, `MAX_ADDONS = 128`, `MAX_DEPTH = 16`; `Policy` (`:13-26`) defaults `acceptedInterfaces = {11200}` with named variants `VANILLA` and the two `VOICEOVER_*` policies (the data policy already relaxes archive/expanded to 1536 MiB — `:255-256` — proving per-policy caps are the established pattern). The register (H4-10) warns the caps were tuned on vanilla packs: real WotLK-era packs (DBM-WotLK with all modules, Omen3, pfQuest-Reforged with its full data packs, a WotLK VoiceOver data pack if H4-13 pins one) can plausibly exceed `MAX_ENTRIES`/512 MiB expanded, and TBC data packs (pfQuest TBC databases) likewise. Nothing today selects a policy by flavor: `AddonRepository` applies `Policy.VANILLA` implicitly (`:504-510` only overrides for the two VoiceOver products).

- **Work items:**
  1. Measurement pass (block on H4-3's pinned candidates): for each flavor's pinned catalog entries, record zip size, expanded size, entry count, max file size, TOC sizes — from the actual release assets — into `docs/addon-cap-measurements.md`; derive per-flavor caps = measured max × 2 headroom (document the formula), floors never below vanilla's.
  2. Per-flavor policy sets: `Policy.TBC`, `Policy.WOTLK` (caps + `acceptedInterfaces {20400}` / `{30300}` + any flavor-specific `requiredTocFields`), selected in `AddonRepository` from the importing/running flavor; `Policy.VANILLA` stays the existing literal object (G3-6 byte-pinning) with a test asserting its fields are unchanged.
  3. Keep the security posture: caps are ceilings, not gates-down — `MAX_FILE_BYTES`, `MAX_DEPTH`, the path-safety checks (`:236+`) and the encrypted-entry posture stay shared and unrelaxed; any pack needing a weaker *safety* property is rejected, full stop (record in Decision Log).
  4. Update the error copy to quote the *policy's* numbers (`:50` already interpolates `maxArchiveBytes` — verify per-policy values flow through) and feed H4-14's messages.
  5. Tests: per-flavor validator matrices including boundary fixtures (one synthetic pack at cap−1, one at cap+1 per flavor); a regression test that a vanilla pack under the *old* caps still validates identically.

- **Upstream dependency:** the H4-3 (this section) pinned per-flavor addon artifacts (repos/commits named there — e.g. Zidras/DBM-WotLK, Omen3, pfQuest-Reforged 30300) are the measurement corpus.

- **Verification:** every pinned catalog artifact for each flavor validates end-to-end (download → policy → installed) on device; a synthetic oversized/over-entries fixture per flavor is rejected with the policy's numbers in the message; `Policy.VANILLA` golden test green.

- **Effort:** M (1–3 days) after pins exist.

- **Depends on:** H4-3 (this section — the pinned WotLK/TBC catalogs), H4-14 (message copy).

- **Owner stage:** S7.

---

### H4-9 — TOS/EULA scroll-gated Accept qualification per flavor + fallback choreography

- **Goal:** on every flavor, a managed launch either provably skips the TOS/EULA/scrolling acceptance screens (because the seeded cvars are honored) or drives them deterministically — and if neither holds on a build, the session fails with honest copy instead of an unattended stall on the EULA screen.

- **Current state:** the vanilla lane never shows the EULA because the app pre-accepts: the SAFE_CONFIG seed writes `SET readTOS "1"`, `SET readEULA "1"`, `SET readScanning "1"` (`importer/ClientGenerationStore.kt:240-242`) and `ManagedConfigPolicy.enforcedKeys` re-enforces all three every launch (`ingame/ManagedConfigPolicy.kt:40-42`, with a comment that these are "written or deleted", never stale). This is a cvar-preseed contract: it works because the 5875 GLUE honors the cvars. TBC (2.4.3) and WotLK (3.3.5) reworked the acceptance flow (the EULA/TOS screens became scroll-gated dialogs with an Accept button; the client stores its own acceptance state) — whether the same three cvars still short-circuit them on 8606/12340 is exactly what the register says to qualify per flavor ("TOS/EULA scroll-gated Accept qualification per flavor + fallback choreography"). Unqualified, the failure mode is H4R2-1's auto-login waiting forever on a topology that never matches because a modal EULA window is in the way.

- **Work items:**
  1. Qualification spike per flavor × lane: boot the qualified client with the vanilla three-cvar seed and record GLUE behavior (does the EULA screen appear? does a scroll+Accept complete it? does acceptance persist in Config.wtf — which keys?).
  2. If cvars short-circuit: add the flavor's key set to `client_ux.seedConfig` (H4R2-5) and to `ManagedConfigPolicy`'s per-flavor enforced set, and lock it with an androidTest asserting the login topology appears within N seconds of first launch.
  3. If not: fallback choreography — extend the H4R2-1 topology descriptors with an optional `eulaModal` stage (window matcher + choreography: scroll-to-bottom events, then the Accept control), executed once per generation, journal-recorded ("eula-accepted-at"), never retried after the first completion; if the modal cannot be driven reliably on a lane, the session must fail with copy "accept the license once manually — this app will remember it" instead of stalling.
  4. Keep the enforcement discipline: whatever keys the acceptance writes, `ManagedConfigPolicy` treats them as client-owned afterward (they are consent state, not app policy — do not enforce them away).
  5. Licensing note in the wiki: the app drives acceptance of Blizzard's in-client license for a client the user imports; the copy must make clear the user is accepting Blizzard's terms, once, visibly (S10.4 docs rider).

- **Upstream dependency:** none (client behavior observed on qualified imports).

- **Verification:** per flavor × lane: fresh managed tree → launch → either no EULA appears (cvar path, logged) or it is accepted by the choreography with a journal record and the second launch shows no modal; 5/5 unattended; then H4R2-1's end-to-end 5/5 in-world landing stands on top.

- **Effort:** M (1–3 days).

- **Depends on:** S5 (qualified clients), H4R2-1 (topology machinery to host the modal stage).

- **Owner stage:** S7.

---

### J1-3 — Self-service recovery lane (stuck teleport, FP unlock, hearth reset) as bounded in-app actions

- **Goal:** the app offers three bounded, allow-listed recovery actions for the player's own character — unstuck-teleport, flight-path unlock, hearthstone-cooldown reset — executed through the same relay/`issue_command` discipline as account ops, with each command proven to exist in the flavor's core.

- **Current state:** the relay has exactly the ops it grew: `native/realm-runtime/src/world_runtime.cpp:270-288` `create_account` (issues `"account create <user> <pass>"` through `issue_command`, classifying "already exist" output) and `:290-305` `set_account_gmlevel`, both gated on `POCKET_SERVER_READY` with strict argument validation; `issue_command` (`:1130`) is the single command channel; JNI surface in the `WorldNative` block (`:1436+`), Kotlin side `server/WorldNative.kt:14` + `server/WorldRuntimeService.kt:216-221`. There is no player-recovery op and no UI surface for one; a character stuck in a broken teleport state (register J1-3's trigger scenario) today requires DB surgery or a lost character. The cores ship GM commands that fit (`tele`, `taxicheat`, `cooldown` in the cmangos command set — *verify per branch at implementation*; the Hearthstone cooldown is its cast spell's cooldown, 8690-class — confirm the id per branch before wiring).

- **Work items:**
  1. Relay ops in `world_runtime.cpp`, following the `set_account_gmlevel` template exactly (state gate, argument validation with an alnum/safe-name check for character names, `issue_command`, output classification): `recover_teleport(player, hub)` → `.tele <hub> <player>`; `recover_taxicheat(player)` → `.taxicheat on <player>` (all-FP unlock — the copy must say "unlocks all flight paths for this character", not "discovers like legit play"); `recover_hearth(player)` → `.cooldown <hearthSpellId> <player>`. Each op's target hub/spell id comes from per-flavor data passed from Kotlin (flavor block: safe hub per flavor — Stormwind/Orgrimmar, Shattrath, Dalaran).
  2. Verify command availability per branch during S2 (grepping the flavor core's `ChatHandler` command table); if a branch lacks a command, implement the equivalent inside the existing PocketRealm overlay surface (same place as `PocketRealmInteraction.cpp`) rather than a new gm command — keep the relay's grammar stable across flavors.
  3. Kotlin plumbing: `WorldNative.kt` externals + `WorldRuntimeService` guarded wrappers + `RealmRuntime` service interface, matching the account-op pattern (`WorldRuntimeService.kt:216-221`).
  4. UI: a "Recovery" card in the in-session surface (`ui/ClientScreen.kt` utility drawer or `TouchOverlay`'s `UtilityDrawer`, `TouchOverlay.kt:625+`): three actions, each with a confirm step and an honest description; results surface as toasts from the op's classified output; every invocation journaled (who/what/when) for the support bundle.
  5. Abuse-of-self guard: ops target only the provisioned player's character (the relay knows it — single-player realm), never bot characters; validation mirrors `create_account`'s strictness.
  6. ABI discipline: these are new relay ops — follow F1-10's (§2.1) version-bump rule for the runtime ABI.

- **Upstream dependency:** `puzzled-pancake/mangos-tbc` / `-wotlk` branch `pocket-realm` — only to *verify* gm command availability (and, if required, add the overlay helper); commits pinned at S2.1.

- **Verification:** device per flavor: place the character in a known-bad teleport state (`.tele` to a removed location in a test DB build), run Recover → character lands at the flavor's hub with world intact; hearth reset works immediately after use; FP unlock shows all connected flight paths on the map; negative: ops refused while world is not READY (state gate) and with malformed names.

- **Effort:** M (1–3 days).

- **Depends on:** S2 (relay runtime per flavor), F1-10 (§2.1, ABI discipline).

- **Owner stage:** new:S7 (the register marks this "new UX"; it rides S7's client-runtime stage with the S2 relay as its server precondition).

---

### J1-6 — Expansion transition handoffs (Dark Portal quest+portal, boat/orb arrival) per-flavor checklist

- **Goal:** crossing from classic content into an expansion is a *guided, verified* moment: the content chain exists in the flavor's DB (S4's job), the bots can follow the player through it (S3's job), and the app presents a per-flavor handoff checklist so the player knows what the crossing requires and what they'll see on the other side.

- **Current state:** the bot-side world knowledge is vanilla-hard-coded where it matters for the journey: the chatter/POI tables in `native/patches/playerbots/PlayerbotLlmChatter.cpp:233-238` name only classic places ("Goldshire", "Stormwind", "Ironforge", "Darnassus", "Orgrimmar", … "Kharanos", "Coldridge Valley", "Northshire", "Lakeshire"), and bot travel/questing in expansion zones is H3-3's (§2.3) soak target ("bots level 61-70/71-80 inside 530/571"). The register assigns this gap to S4 (content): the Dark Portal quest chain, the portal itself, and the arrival surfaces (TBC: boat from Menethil→Auberdine-ish/portal; WotLK: boat to Valiance Keep / orb to Shattrath-style handoffs — H1R2-4's transport work rides the same content) must exist and function in `tbc-db`/`wotlk-db` before any UX claims them. The *app-side* deliverable in my area is the checklist/copy surface plus the per-flavor bot POI data that makes the destination feel inhabited.

- **Work items:**
  1. Content floor (S4 rider, coordinates with H1R2-4/F2-1): per flavor, a scripted acceptance test that the handoff chain is enterable — TBC: the Dark Portal quest turn-in + stepping through the portal lands the character in Hellfire Peninsula; WotLK: boarding at Menethil/Stormwind docks arrives at Valiance Keep (register H1R2-4's ride test is the same evidence).
  2. Bot accompaniment (S3 rider): extend the LLM chatter POI tables (`PlayerbotLlmChatter.cpp:233-238`) with per-flavor POI lists (Honor Hold/Thrallmar/Shattrath for tbc; Valiance Keep/Dalaran/etc. for wotlk) selected by the flavor's era data (the H3-8/H3-9 §2.3 flavor data files — consume theirs, don't fork a third list).
  3. App checklist card (this area): when a character on flavor N reaches the pre-crossing state (level band + zone), the in-session drawer (same surface as J1-3's recovery card) offers "Crossing to <expansion>: what to expect" — a static, per-flavor checklist (quest to pick up, where the portal/boat is, what the first hub is, flying-not-required honesty note); copy from the flavor block.
  4. Verification tie-in: the checklist's claims must match the S4 content test — one source of truth (the flavor block's `transition` data written when S4's test passes).

- **Upstream dependency:** `cmangos/tbc-db`, `cmangos/wotlk-db` (pins at S4.1) for the quest/portal/transport content; register F2-2 (BfU missing) shows wotlk-db chains can be incomplete — the checklist copy must not promise a chain that S4 accepted-cut.

- **Verification:** S4 content test green per flavor (crossing completes unattended-with-bots); device: checklist appears at the handoff, its steps match reality (each step demonstrable on the RP6); bots present and chatting about the destination hub on arrival.

- **Effort:** M (1–3 days) app-side; S4/S3 riders have their own sizing.

- **Depends on:** S4 (content floor), H3-3/H3-9 (§2.3, bot travel + per-flavor POI data), H1R2-4 (transports).

- **Owner stage:** S4 (register's owner) — app checklist rider lands in S7.

---

### J1-7 — Attunement/key dungeons with bots (Black Morass timed event) in the strategy matrix

- **Goal:** the bot strategy matrix covers the attunement/key dungeons that gate flavor progression — concretely TBC's Black Morass timed rift defense with bots actively protecting Medivh and prioritizing rifts — so a solo player can actually earn The Master's Key with their group.

- **Current state:** the pinned bots tree already carries flagship dungeon strategies in `native/playerbots/playerbot/strategy/generic/`: `KarazhanDungeonStrategies.cpp`, `MechanarDungeonStrategies.cpp`, plus classic `MoltenCoreDungeonStrategies.cpp`, `OnyxiasLairDungeonStrategies.cpp`, `BlackwingLairDungeonStrategies.cpp`, `NaxxramasDungeonStrategies.cpp` (directory listing verified this session), each with sibling `actions/` and `triggers/` files (e.g. `actions/KarazhanDungeonActions.cpp`, `triggers/KarazhanDungeonTriggers.cpp`) registered through `StrategyContext.h`/`TriggerContext.h`/`ActionContext.h`. A repo-wide grep for `Morass|Medivh` over `native/playerbots` returns nothing — Black Morass (the timed opening of the Dark Portal, the TBC attunement lynchpin; register H1R2-3 confirms The Master's Key item 2449x is present in tbc-db) has no strategy, so bots will treat a timed rift event like ordinary trash: no priority on rift keepers, no stacking on Medivh's adds, no escape-from-Durnholde support either. The register places this gap in the S3 strategy matrix (H3R2-2 §2.3 owns the inventory; J1-7 names this specific hole).

- **Work items:**
  1. Author `BlackMorassDungeonStrategies.{cpp,h}` + `actions/BlackMorassDungeonActions.{cpp,h}` + `triggers/BlackMorassDungeonTriggers.{cpp,h}` in `native/playerbots/playerbot/strategy/`, following the Mechanar files' structure exactly (they are the closest TBC template): a trigger for an active rift (creature set by entry/aura — entry IDs from tbc-db, verified at implementation), an action to prioritize the rift elite + adds over ordinary mobs, a protect-Medivh positioning multiplier, and a timed-phase awareness (interrupt/portals priority ramping with the timer).
  2. Register the strategy in `StrategyContext.h` and wire actions/triggers into `AiObjectContext.cpp` (the same three registration points the Karazhan set uses).
  3. Generalize where honest: the same rift-defense pattern covers Old Hillsbrad's escape if the trigger set parameterizes encounter entries — do it only if the diff stays small; otherwise record as follow-up (H3R2-2's inventory tracks it).
  4. Per-flavor conf: ensure the strategy loads only for the tbc/wotlk bots builds (the multi-expansion tree ships per-expansion conf dists — `playerbot/aiplayerbot.conf.dist.in.tbc/.wotlk` verified present — so gate there, per H3R2-1's conf discipline).
  5. Portability note: the patches live under `native/patches/playerbots/`; new strategy files belong in the bots tree itself (like Karazhan's) so the LLM patch set (port plan S3.3) stays separate — keep the layering the repo already uses.
  6. Test hooks: `playerbot/BotTests.cpp` exists in-tree — add a headless strategy-activation test if the harness supports it; otherwise the soak in verification is the gate.

- **Upstream dependency:** `puzzled-pancake/playerbots` branch `pocket-realm` @ `7e2cd2fb` (registered in `.gitmodules` and `schemas/flavor.json` `bots.commit` — verified this session; the register's C4 caveat "verify 7e2cd2fb locally" is satisfied by the submodule). Strategies are authored in-fork, not upstream.

- **Verification:** device soak: player + 4 bots enter Black Morass with the key quest active — bots engage rift spawns within 3 s of spawn, Medivh survives the full timed run in ≥3/5 attempts at normal tuning, key obtained; regression: Karazhan/Mechanar strategies still load (existing soak); wotlk build unaffected (strategy gated off).

- **Effort:** L (1–2 weeks) including tuning soaks.

- **Depends on:** H3-1 (§2.3, bots build per flavor), H3R2-2 (§2.3, strategy matrix structure), S4 (tbc-db content for entries).

- **Owner stage:** S3 (register's owner).

---

### J1-8 — Bank panel in the Bags container or D-pad navigation of the stock bank frame (WotLK 7-slot row)

- **Goal:** bank access is controller-usable on every flavor: either the AndroidPort Bags container gains a bank view (preferred — consistent with the all-in-one bag UX), or the stock bank frame is fully D-pad navigable — and on WotLK the extra bank-bag row (7 slots) is modeled, not clipped.

- **Current state:** the addon's `Bags.lua` already replaces the stock containers with a OneBag-style all-in-one: real bag slots in the header, "all" and key views, sell-junk (gray-only, one per tick, never scans the keyring — `Bags.lua:10-30`), with `stockBags = {0,1,2,3,4}` / `allBags = {0,1,2,3,4,-2}` (`:31-33`). It has **no bank view** — the bank frame is untouched stock UI, controller-navigable only via pointer mode. There is no bank binding to leverage either: the vanilla catalog's BAGS category has no bank-open binding (grep over `WowVanillaBindingCatalog.kt` for `bank` returns nothing — the stock bank opens by interacting with the banker), and 3.3.5 adds a second bank bag row (7 slots, per the register) that a naive copy of the vanilla grid would miss. App-side precedent for frame navigation exists in the input layer (gamepad chords to keyboard actions, `InputProfile`) and `TouchOverlay`'s movable clusters (`:492+`).

- **Work items:**
  1. Preferred path — bank view in `Bags.lua`: while the bank frame is open (banker interaction), render a bank panel listing `BANK_BAG`-family slots plus the main bank inventory using the 1.12/2.4.3/3.3.5 bank container indices (confirm per build from the captured FrameXML — the WotLK bank is `NUM_BANKBAGSLOTS = 7`; verify the constant per build); reuse the module's existing slot-button and refresh machinery (`:10-30`), keep clean-room status, and keep the keyring exclusion discipline.
  2. Per-flavor gating: bank panel code paths conditional on the flavor's constants (vanilla 24-slot main bank + 6 bag slots vs wotlk 28 + 7 — confirm exact numbers from FrameXML captures, H4R2-11's tooling), so one Lua file serves all flavors without inventing indices.
  3. D-pad fallback (if the addon is excluded on a flavor per the S7.6 decision): add a "bank navigation" chord set to `InputProfile` (D-pad → arrow/Tab focus movement over the stock frame, A → click) — scope this to arrow-key emulation the bank frame already supports; verify on device that focus traversal reaches every slot including WotLK's bag row.
  4. Deposit/withdraw helpers: keep parity with Bags' existing behaviors only (no auto-sort inventions beyond what stock offers); withdraw-all /deposit of favorites is out of scope (record).
  5. Tests: `AndroidPortAssetTest.kt` gains bank-view Lua assertions per flavor variant; device test below.

- **Upstream dependency:** none (bank indices from captured FrameXML; no server work — bank state lives in the characters DB handled by core).

- **Verification:** device per flavor: open bank with controller → every main-bank item and every bank bag is reachable and usable through the panel (deposit + withdraw a representative item set); WotLK: all 7 bank-bag slots visible and usable; vanilla behavior of Bags unchanged when bank closed; addon-excluded flavor: D-pad traversal reaches 100% of slots (measured walk).

- **Effort:** M (1–3 days).

- **Depends on:** S7.6 AndroidPort per-flavor decision; H4-1 (this section, secure action-bar port — same addon surface); H4R2-11 (FrameXML captures for per-build bank constants).

- **Owner stage:** S7.

---

### C3-walk — First-run replay story per flavor; Vulkan catalog/import/quarantine per lane; LLM model registry per flavor; LAN mode per flavor

- **Goal:** the four app-feature walk surfaces the register flags — first-run replay, the Vulkan driver catalog/quarantine, the LLM model registry, and LAN mode — each gain an explicit per-flavor story, so a TBC or WotLK user gets correct driver quarantine, correct model profiles, correct LAN join semantics, and a replayable per-flavor onboarding instead of vanilla assumptions.
- **Ownership:** §2.6's `### C3` (integration-coexistence.md) is the **plan of record** for this umbrella — it owns the shared mechanics and the Decision Log records; this treatment is its S7 UX/device-surface execution half. Where the items below overlap §2.6 C3's work items, §2.6 governs: item 2's quarantine mechanics are §2.6 C3 item 1 (this item owns the catalog presentation and settings/renderer-surface halves; where this item's per-(driver, flavor) key differs from §2.6's (driverId, graphics route) record, §2.6 governs and this item's session-record fields feed it); item 3 is governed by §2.6 C3 item 2's Decision Log record — the *registry stays flavor-independent*, and what varies per flavor is corpus/prompt identity and the conf block, not registry entries (this item owns the `LlmScreen`/selection-surface and conf-emission halves); item 4's join resolution and mismatch refusal are §2.6 C3 item 3 (this item owns the `LanScreen` card/copy half); item 1's per-flavor seal work is §2.6 C3 item 4 (this item rides H4R2-8's flavor-scoped keys).

- **Current state:** (four sub-surfaces, each verified this session)
  - *First-run replay:* `ui/FirstRunTutorial.kt:101-128` — a global gate plus process-wide `TutorialReplayRequests` (Settings → Setup); per-flavor state is H4R2-8's work; the missing piece is the replay *story* per flavor (a second flavor's import should offer that flavor's tour).
  - *Vulkan catalog/quarantine:* `client/VulkanDriverCatalog.kt` (packaged Turnip ICD, Adreno matching `:111-129`), `GeneratedVulkanDriverCatalog.kt`/`CommunityVulkanDrivers.kt` (user-imported drivers), `UserVulkanCrashGuard.kt` + `drivers/session-record.json` (crash streaks feeding quarantine, bundled at `SupportBundleExporter.kt:45-47`), and per-flavor graphics policy already in data: `schemas/flavor.json` `wine.graphics_routes` = `[dxvk, opengl, virgl]` vanilla vs `[dxvk]` tbc/wotlk (verified by dumping the flavor blocks). Quarantine is flavor-blind today: a driver that crashes under wotlk must be quarantined for the *lane it failed on*; the Choosing-a-Vulkan-Driver wiki page must become per-flavor (S10.4 names the caveat).
  - *LLM model registry:* `llm/LlmModelRegistry.kt` defines `LlmSamplingProfile`/`LlmTierProfile` (`:25-60`); `LlmRuntimePolicy.kt` resolves `TUNED_E2B.profile/tierProfile` (`:190-191`) into the conf block. The register wants per-flavor registry entries: memory budgets differ per flavor (H3R2-6 §2.3: llama ctx 12288/6144 KV on 12 GB shared, measured at 320 bots) and corpora differ (`llm.lore_cards`: `v112` vanilla, `null` tbc/wotlk in the current flavor.json).
  - *LAN mode:* `ui/LanScreen.kt` is flavor-blind (grep: no flavor references), while S8.4 makes LAN advertising per flavor (`realmbuilds` per register C1) and §2.6's C3 row asks for a LAN join lane per flavor with W2-4's population math.

- **Work items:**
  1. *First-run replay:* ride H4R2-8's per-flavor keys — the replay action replays the *selected* flavor's tour, and after a new flavor qualifies, Home offers "take the <flavor> tour" once; verify the replay gate (`FirstRunTutorial.kt:107`) reads flavor-scoped keys.
  2. *Vulkan per lane:* add `flavor` + `graphicsRoute` to the crash-guard session record (`drivers/session-record.json`, written by `UserVulkanCrashGuard`) and quarantine per (driver, flavor) pair — a driver with a clean wotlk streak is not quarantined because it crashed under vanilla GL (and vice versa); the catalog presentation (`ui/UserVulkanDriverPresentation.kt`) shows per-flavor verdicts; `wine.graphics_routes` stays the gate (dxvk-only flavors never offer the GL driver pickers — verify `SettingsScreen`'s renderer choices hide Gladio options per flavor with honest copy).
  3. *LLM registry:* parameterize `LlmModelRegistry` entries per flavor (profile ids like `TUNED_E2B_TBC/WOTLK` or per-flavor overrides in the flavor block's `llm` section once H3R2-6's measurements land); `LlmRuntimePolicy` resolves by the session's flavor; conf emission (`ServerRuntimeFiles`) writes the flavor's values; the corpus id flows from the same block (`llm.lore_cards`).
  4. *LAN per flavor:* `LanScreen` shows the host realm's flavor on the card; join flow warns on flavor mismatch before the client is even launched (the S8 auth matrix is the hard gate — this is the honest UX in front of it); the LAN page copy per flavor lands with the S10.4 wiki pass.
  5. Sweep: extend `tools/leak_sweep.py` with the runtime assertions for items 2–4 (quarantine pair isolation; conf block matches session flavor; LAN card flavor matches realm advertisement).

- **Upstream dependency:** none (Vulkan driver packages unchanged; LLM models unchanged — only their per-flavor selection).

- **Verification:** replay: two flavors installed → each tour offered once, replayable independently; Vulkan: force a crash under wotlk with driver D → wotlk lane quarantines D while vanilla lane still selects it (unit + device); LLM: switch session flavor → conf block's model/profile/corpus lines match that flavor (golden conf test, G3-7 for vanilla); LAN: wrong-flavor client sees the mismatch warning pre-join and the S8 matrix still refuses at auth (defense in depth).

- **Effort:** L (1–2 weeks) across the four surfaces.

- **Depends on:** H4R2-8 (flavor-scoped first-run state), S1 (per-flavor lockfiles feeding registry data), S3/H3R2-6 (§2.3, LLM budgets/corpora), S8.4 (LAN realm advertisement), C3 (§2.6 — the plan of record for this umbrella; see the Ownership note above).

- **Owner stage:** S7 (replay, Vulkan, LLM selection), S9/S10 (LAN copy + wiki pass).

---

## Coverage ledger

- [x] H4-1
- [x] H4-2
- [x] H4-3
- [x] H4R2-2
- [x] H4-4
- [x] H4-5
- [x] H4-6
- [x] H4-7
- [x] H4R2-10
- [x] H4-8
- [x] H4R2-1
- [x] H4R2-3
- [x] H4R2-4
- [x] H4R2-5
- [x] H4R2-6
- [x] H4R2-7
- [x] H4R2-8
- [x] H4R2-9
- [x] H4R2-11
- [x] H4-11
- [x] H4-12
- [x] H4-13
- [x] H4-14
- [x] H4-10
- [x] H4-9
- [x] J1-3
- [x] J1-6
- [x] J1-7
- [x] J1-8
- [x] C3-walk

§2.5 treats the 22 performance/persistence/import-depth gaps plus — per review round 1 — the register §1 P0 row F3-3/H2-5 (roadmap CP-5), whose qualified()-consumer and planner-cap work this area owns: measured budgets replacing vanilla hard-coded constants, persistence lanes routed through the existing `ClientFlavor.DatabaseNames` registry, diagnostics/update/recovery at multi-flavor scale, and import-depth deny/scrub policy. Two cross-cutting constraints apply throughout: never schema-bump the §2.7-protected artifacts, and measure every value on RP6 rather than guessing.

---

## 2.5 Performance, persistence, import depth (R3: P1/P2/P3, R4: F3)

This section treats the 22 gaps of register §2.5 (`docs/FEATURE-PARITY-GAPS.md:178-198`), plus — added at review round 1 — the register §1 P0 row F3-3/H2-5 (`:33`, roadmap CP-5), whose qualified()-consumer and planner-cap work belongs to this area's import/planner/prep coverage and whose phantom references elsewhere in this file it now resolves. They group into four themes. **Theme A — measured budgets on shared hardware (P1-1, P1-7, P1-8, P1-9, P1-10):** every performance constant the vanilla lane hard-codes (`DatabaseConfigPolicy.kt:31`, `RuntimeContracts.kt:76-98`, `DatabaseEngine.kt:596-600`, `SafClientScanner.kt:174`) was tuned against a ~73 MiB world DB and a 1.12 client; TBC/WotLK triple the world-DB volume and roughly double the client footprint, so each constant needs a per-flavor measured value carried in `ClientFlavor`/`schemas/flavor.json` instead of a literal. **Theme B — persistence lanes hard-coded to `classic*` (P2-1, P2-3, P2-2, P2-4, P2-5, P2-6, F3-1):** the checkpoint tool, the DatabaseEngine's GRANT/CREATE/probe SQL, the revision-probe Triples, the snapshot `compatibility` payload, the seed translator baselines, the seed-augment directory, and the Windows SQLite-seam lockfile all name the classic database set or a single revision format today; all of them route through the `ClientFlavor.DatabaseNames` registry that already exists (`android/app/src/main/java/com/pocketrealm/client/ClientFlavor.kt:47-52`). **Theme C — diagnostics, update, and recovery at multi-flavor scale (P2-7, P2-8, P2-9, P2-10, P2-11, P2-12, F3-6, F3-10):** support bundles, redaction rules, the update coordinator's revalidation fence, and the supervisor journal must carry flavor and survive the S9 adversarial "kill mid-switch" scenario. **Theme D — import-depth policy (F3-4, P2-a):** same-build duplicate copies and donor-machine cvars that arrive inside imported client trees need explicit deny/scrub policy. Two cross-cutting constraints apply everywhere: the vanilla-regression quarantine hazards of register §2.7 (optional-field shims, never schema-bumping `schema==2` artifacts — G3-1..G3-10), and the invariant that every value introduced here is *measured on RP6* (plan §4.1 R4 charter), not guessed. Device measurements below require the RP6 windows listed as external inputs in plan §8.2; the CI-side tests named in each Verification are runnable without hardware.

---

### P1-1 — Per-flavor world-DB buffer-pool floor and measured migration-apply budget

- **Goal:** MariaDB/SQLite tuning and migration-apply timeouts are per-flavor measured budgets (buffer-pool floor, `max-allowed-packet`, apply cap) carried in `ClientFlavor`/`flavor.json`, proven by a timed full apply of tbc-db and wotlk-db on RP6 within those budgets.
- **Current state:** The MariaDB policy is a fixed vanilla-tuned block: `android/app/src/main/java/com/pocketrealm/database/DatabaseConfigPolicy.kt:31-33` sets `innodb-buffer-pool-size=128M`, `innodb-buffer-pool-instances=1`, `innodb-log-file-size=32M`, and `DatabaseEngine.kt:241` launches mariadbd with `--max-allowed-packet=8M`. The apply timeout scales only with SQL text length: `DatabaseEngine.kt:596-600` computes `30_000L + (sql.length()/1024L)*40L` coerced to a 600_000 ms cap, with the comment "The pinned full world database is ~73 MiB". Register §2.5 puts the TBC world dump at 65 MB gz and WotLK at 60 MB gz — decompressed these are multiples of the classic 73 MiB SQL (classic-db Full_DB `ClassicDB_1_12_1_z2815.sql.gz` per `tools/seed_realm_db.py:41`), so the 10-minute cap and the 8 MiB packet ceiling (large `INSERT` blobs in `creature_loot_template`, 174k/220k rows per register F2-9) can trip and get misclassified as `DB-REVISION: migration … failed`. `DatabaseConfigPolicy.kt:4` documents the policy as "Fixed MariaDB policy".
- **Work items:**
  1. Add per-flavor tuning fields to `ClientFlavor` (`ClientFlavor.kt:16-46`): `innodbBufferPoolBytes`, `innodbLogFileSizeBytes`, `maxAllowedPacketBytes`, `migrationApplyTimeoutMsCap`. Seed vanilla with today's exact literals (128M/32M/8M/600s) — byte-identical behavior is the G3-7 golden-conf guard.
  2. Extend `schemas/flavor.json` (schema 2) with a `database` block per flavor holding the same values; extend `FlavorManifestParityTest` (`android/app/src/test/java/com/pocketrealm/client/FlavorManifestParityTest.kt:18`) to pin the parity.
  3. Make `DatabaseConfigPolicy` take the flavor's values instead of the fixed block (it is pure data today — `DatabaseConfigPolicy.kt:20-38` — so the change is parameter injection plus a golden-bytes test for the vanilla output).
  4. Raise/route the apply budget in `DatabaseEngine.kt:596-600`: keep the linear formula but take the cap and the per-MiB slope from the flavor; the cap must be re-derived from the measured full apply (step 6), not guessed.
  5. In the SQLite lane, add a per-flavor page-cache pragma floor to `DatabaseSqliteControlPlane` (default `PRAGMA cache_size` equivalent of the 128 MiB floor) — the seed replay path is `DatabaseEngine.kt:1661,2035`.
  6. Measure on RP6 (external device window per plan §8.2): full `stage_database_migrations.py` ledger apply for tbc-db and wotlk-db (`python tools/stage_database_migrations.py --flavor tbc|wotlk`), record wall-clock and peak RSS of mariadbd; feed back into step 4.
- **Upstream dependency:** none directly; consumes the flavor DB pins registered at S4 (`cmangos/tbc-db`, `cmangos/wotlk-db` — commit TBD per plan §6; classic-db pinned @ be1a5206).
- **Verification:** unit: golden-bytes test that vanilla's generated `[mariadbd]` config is byte-identical to today's output, and that `--flavor wotlk` yields the measured floor values; integration: `tests/test_differential_lane.py` apply-budget probe extended to assert a wotlk-scale synthetic ledger applies under the routed timeout without a false `DB-REVISION` failure; device: RP6 timed apply of the real tbc/wotlk ledgers, numbers archived in the S4 evidence pack (plan §4.5) and recorded as `applyBudgetMs` in the Decision Log.
- **Effort:** M
- **Depends on:** F3-1 (per-flavor migration ledger is the vehicle the measured budget rides in)
- **Owner stage:** new:S4

### P1-7 — Per-flavor supervisor boot-timeout budget and staged world boot

- **Goal:** Every boot timeout in `RuntimeTimeouts` is derived from the flavor's measured boot wall-clock (with the map/vmap/mmap data present), and world boot proceeds staged (maps lazy-loaded, no full-grid preload) so a slow-but-healthy boot is never killed as a failure.
- **Current state:** `RuntimeContracts.kt:76-98` fixes `databaseStartMs=1_800_000`, `realmStartMs=30_000`, `worldStartMs=120_000`, `botWorldStartMs=600_000`, `clientStartMs=90_000` — constants sized for the vanilla world. `DurableRuntimeSupervisor.kt:560,578,612` wraps each component start in `withTimeout(timeouts.start(...))`. Register P1-7 records desktop boot reports of 15 min–2 h when navigation/bot initialization runs against the bigger worlds; a WotLK world boot that exceeds `worldStartMs`/`botWorldStartMs` is indistinguishable from a hang under the current code — the only progress signal is component state, and the core-side stuck-watchdog is disabled (`ServerRuntimeFiles.kt` worldConfig emits `MaxCoreStuckTime = 0`, android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt:161). No grid-preload keys are emitted at all (worldConfig body, `ServerRuntimeFiles.kt:135-189`), so behavior depends on the branch default — which must be asserted, not assumed.
- **Work items:**
  1. Add `RuntimeTimeouts` derivation per flavor: move the literals into `ClientFlavor`/`flavor.json` as measured boot budgets (fields `worldBootBudgetMs`, `botWorldBootBudgetMs`) and have `RuntimeTimeouts` defaults computed from the qualified flavor (keep the current values as the vanilla baseline, G3 golden guard).
  2. During S2 host+device smoke (plan S2 work items 6-7), measure per-flavor: world "ready" wall-clock with maps+mmaps present, then with bots enabled; record in the stage evidence pack. These numbers populate the fields from step 1.
  3. Staged grid load: pin the flavor branch's lazy-load posture explicitly in the emitted `mangosd.conf` (extend the worldConfig block at `ServerRuntimeFiles.kt:135`) — set/verify the cmangos grid keys (`GridUnload=1` posture, no preload of all maps) against the actual `mangosd.conf.dist` of the pinned mangos-tbc/mangos-wotlk fork, and add a conf-key presence assert so a branch default flip fails staging (same fail-loud mechanism as plan S3 item 6).
  4. Differentiate slow-boot from hang: emit a periodic "boot progress" line from the world component (existing world log channel — `LogFile`/`world.log` at `ServerRuntimeFiles.kt:152`) and have the supervisor's start-wait consume it (heartbeat design is F3-6's item 2 — build the plumb-through here).
  5. Re-run `tools/run_bot_pressure_benchmark.py` per flavor once bots land (S3) to re-measure `botWorldStartMs` with the LLM patch set loaded.
- **Upstream dependency:** `puzzled-pancake/mangos-tbc` and `puzzled-pancake/mangos-wotlk`, branch `pocket-realm` (commits TBD — forked at S2 per plan §5 S2 item 1); conf keys verified against each branch's `mangosd.conf.dist`.
- **Verification:** androidTest: supervisor start of a simulated slow world (test backend that reports progress lines slower than vanilla budget but within the flavor budget) reaches READY, while one that exceeds the flavor budget with no progress lines is failed with the hang copy; device: RP6 boot of the flavor realm, "world reaches ready" wall-clock ≤ recorded budget ×1.2 across 5 boots (plan S2 end-state "30-minute stable session" covers stability; this adds the boot number); unit: `RuntimeTimeouts` derivation table test per flavor.
- **Effort:** M
- **Depends on:** none (feeds F3-6's heartbeat semantics)
- **Owner stage:** new:S2

### P1-8 — Per-flavor device quotas: prefix/cache/log growth and scanner caps

- **Goal:** Import-time storage planning and runtime quotas (wine prefix, client Cache/WDB, server logs) carry per-flavor measured numbers, and the exe-scanner cap is proven to admit the 12340 client (12.6 MB) with margin.
- **Current state:** The scanner cap already accommodates C6's ~12.6 MB WotLK exe: `SafClientScanner.kt:174` (`MAX_EXE_BYTES = 32 * 1024 * 1024`) — the plan's S5 note that the cap might need raising is settled by C6 (no raise needed; add the assert anyway). Storage budgets exist per flavor but are null for the new flavors: `ClientFlavor.kt:41-42` (`extractedEstimateBytes`, `maxTotalBytes` — vanilla 4 GiB/20 GiB, tbc/wotlk null) and `ImportStoragePlanner.kt:11-15` routes `flavorExtractedEstimate()` from flavor data; plan §6 targets ~25 GiB (tbc) / ~35 GiB (wotlk) `maxTotalBytes` and register P3-2/P1-5 the ~9-10 / ~16-17 GiB extracted sizes. Runtime growth quotas are vanilla-shaped: `ServerRuntimeFiles.kt:473` `MAX_NORMAL_LOG_BYTES = 4 MiB` with `rotateRestartLog` (:465-471); there is no measured model of wine-prefix growth, `Cache/WDB` growth (register H2-7), or log volume for the bigger worlds. Entry-count caps are single-valued: `ImportModel.kt:58` `maxEntries = 110_000` — P3-3 (150-200k-file trees) is a sibling gap owned in §2.2, but the planner quota math here must not contradict it.
- **Work items:**
  1. Measure on RP6 during RA (S6) and record: extracted client tree size (tbc/wotlk), wine-prefix size after a 2 h session, `Cache/WDB` bytes after N logins, world.log + database-errors.log bytes per hour at default bot population, mariadb err-log growth. Script it as an extension of `tools/capture_rp6.py` output keys so the numbers land in the capability record.
  2. Fill `ClientFlavor.TBC/WOTLK` `extractedEstimateBytes`/`maxTotalBytes` (register P3-2 values as the working hypothesis, then correct to measured) and mirror in `schemas/flavor.json` + `FlavorManifestParityTest`.
  3. Add per-flavor log-quota fields (e.g. `maxWorldLogBytes`) consumed by `rotateRestartLog` (keep vanilla at exactly 4 MiB).
  4. Add a startup assert that `SafClientScanner.MAX_EXE_BYTES` ≥ each qualified flavor's registered exe size (new `ClientFlavor.knownExeSizeBytes` field, set from the S5 qualification measurement; null for unqualified flavors) so C6 regressions fail at startup (register P3-1's fail-at-startup posture).
  5. Cross-check `ImportStoragePlanner.calculate` (`ImportStoragePlanner.kt:47-60`) against the per-flavor totals at full multi-flavor occupancy — the planner currently accounts one database and one snapshot (`directoryBytes(roots.databaseSnapshots)` at :29-30); the S9 multi-flavor total is F3-3/H2-5's (its owning section is below in this file), so here only assert the single-flavor math holds with the new numbers.
- **Upstream dependency:** none (measurement + registry data; exe sizes from anzz1/wotlk-client-checksums as cross-check per register C6).
- **Verification:** device: RP6 import of genuine 8606/12340 clients succeeds with the planner accepting ≥ the measured extracted size and denying at `maxTotalBytes` with `VAL-` copy (R2 negative: synthetic oversized tree); unit: planner math table test per flavor; golden: vanilla planner output unchanged; grep: `tools/leak_sweep.py` extended so no code path hard-codes the new byte caps.
- **Effort:** M
- **Depends on:** P1-10 (soak harness produces the growth samples)
- **Owner stage:** new:S6

### P1-9 — Per-flavor Save & Exit save budget and staggered bot autosave

- **Goal:** Save & Exit completes within a measured per-flavor budget at max-level characters with a full bot population (stop timeouts, snapshot cost, and bot save cadence all flavor-derived), proven by a timed device Save&Exit at level 70/80.
- **Current state:** The Save&Exit path is one graceful stop: `RealmService.kt:205` `ACTION_SAVE_EXIT -> supervisor.stop(StopMode.GRACEFUL)`; the supervisor saves the world once via `DurableRuntimeSupervisor.kt:907` (`withTimeout(timeouts.componentStopMs) { backend.saveWorld(world) }` with `componentStopMs = 60_000` and `databaseStopMs = 30_000`, `RuntimeContracts.kt:84-85`), then stops DATABASE→REALM→WORLD (ordering comment `DatabaseEngine.kt:839`). Nothing in the emitted conf paces bot persistence: the worldConfig body (`ServerRuntimeFiles.kt:135-189`) sets no core save-interval keys and the per-profile `aiplayerbot-<id>.conf` (:123-125) sets no `AiPlayerbot` save-cadence keys — bot and player character saves all hit the DB at stop time, and level-80 characters plus WotLK character-table row width make the stop-time write set larger. Snapshot/backup cost is a full tree walk with per-file SHA-256 (`DatabaseSnapshotStore.kt:33-42`, used by `createNamedBackup` at `DatabaseEngine.kt:2443-2461`), which grows with the datadir. Honest note: **no 6-hour cap is implemented anywhere in the repo** — searches for session/hour caps across `android/app/src/main` and `tools/` return only timestamp formatting (`ImportProgressPresentation.kt:201-205`) and LAN-loss timing (`RealmService.kt:484-489`); the register's "6h cap" is the assumed session envelope, and the S10 soak (8 h, plan §5 S10 item 2) already exceeds it, so the deliverable here is the measured save budget, not a new cap.
- **Work items:**
  1. Measure per flavor on RP6: Save&Exit wall-clock split by phase (saveWorld → component stops → DB stop) at max-level characters with default bot population; record the character-table row bytes delta per session (register's "bigger blobs").
  2. Route `componentStopMs`/`databaseStopMs` per flavor through `ClientFlavor` (same pattern as P1-7; vanilla keeps 60 s/30 s byte-for-byte).
  3. Emit core + bot save-cadence keys per flavor in the worldConfig block (`ServerRuntimeFiles.kt:135`) and the per-profile bot conf (:123): a modest core character-autosave interval (verify the exact key name against the pinned branch's `mangosd.conf.dist` — do not invent it) and `AiPlayerbot` autosave pacing, so stop-time is amortized; keep-off audit per flavor as in plan S3 item 6.
  4. Budget the stop-time DB snapshot: `createNamedBackup`/snapshot creation must be timed per flavor and its cost asserted against the flavor's stop budget; if the SHA-256 walk (`DatabaseSnapshotStore.kt:33-42`) exceeds it, switch the walk to parallel chunked digesting (same digests, same manifest — byte-identical output asserted by test).
  5. Extend `RuntimeSnapshotJson`/supervisor stop telemetry to record the measured save-phase durations in the journal detail (bounded by `DETAIL_MAX_CHARS`, `RuntimeSnapshotJournalCodec.kt:97`).
- **Upstream dependency:** `puzzled-pancake/mangos-tbc`/`-wotlk` branch `pocket-realm` (conf key names verified against `mangosd.conf.dist`); playerbots tree per register C4 (pin 7e2cd2fb class, per-expansion conf dists).
- **Verification:** device (RP6, external window): 5× timed Save&Exit per flavor at max level with bots — all phases within the flavor's recorded budget, `world.log` shows the staggered saves, and a post-stop `PRAGMA quick_check`/`quick_check`-equivalent + restart proves no dirty state; unit: golden conf test (vanilla unchanged; per-flavor keys present); journal detail telemetry asserted in `RuntimeSnapshotJson` tests.
- **Effort:** L
- **Depends on:** P1-7 (timeout-routing pattern and boot measurements), P2-12 (journal carries the per-flavor save telemetry)
- **Owner stage:** new:S9

### P1-10 — Client FPS/frame-time and thermal sampling in the soak harness

- **Goal:** The soak harness produces continuous FPS/frame-time and thermal samples for the flavor client under Box64 on RP6 (a 12340 datapoint exists — none does today), and the S10 go/no-go consumes them.
- **Current state:** `tools/capture_rp6.py` is a one-shot capability snapshot, not a soak sampler: display-mode FPS is parsed once from a SurfaceFlinger dump (`capture_rp6.py:258`, regex on `width=…, fps=…`), thermals are one `dumpsys thermalservice` parse plus a single `/sys/class/thermal` sweep (:336-364, :659-664, :739-741). `tools/battery_sampler.py` samples power, and `tools/battery_window_report.py` aligns CSVs to manually-marked soak windows — neither records client frame pacing. There is no repeated in-session sampling loop anywhere in `tools/`, so "RP6 datapoint for 12340 under Box64" (register P1-10) genuinely cannot exist yet. The register's DXVK interaction (H2-4: `d3d9ex` × `gxTextureCacheSize`) is the concrete risk these samples must catch (FPS halving).
- **Work items:**
  1. Add a soak-sampler mode to `tools/capture_rp6.py` (or a sibling `tools/soak_sampler.py` importing its parsers): a `--duration`/`--interval` loop that records per-tick `{t, fps (SurfaceFlinger), thermal (service + sysfs zones), battery (battery_sampler CSV join), meminfo}` into one CSV/JSON; reuse the existing redaction rules (no serials, per the tool header).
  2. Drive a reproducible client load: the x86/ARM lane launches the flavor client into a fixed scenario (login → fixed zone → camera orbit via the existing auto-login topology from plan S7 item 5); mark scenario windows so `battery_window_report.py`-style averaging works per window.
  3. Record the vanilla baseline run once (this is a new datapoint for vanilla too — today nothing samples in-session; the vanilla lane must not regress, so its baseline becomes the comparison anchor).
  4. Capture the 12340 datapoint: ≥ 2 h soak on RP6 with the sampler running; produce frame-time p50/p95, thermal max/throttle events, and the H2-4 d3d9ex trap check (run once with `gxTextureCacheSize ≥ 2048` to confirm the FPS-halving signature, once with the W1/H2-4 SAFE_CONFIG pin).
  5. Feed P1-8's growth quotas and the S6.4 go/no-go (plan §5 S6 item 4) with the recorded numbers; archive per plan §4.5.
- **Upstream dependency:** none (device measurement; requires RP6 windows per plan §8.2 item 3).
- **Verification:** run `python tools/soak_sampler.py --duration 7200 --interval 10` against a 12340 client on RP6: output CSV with ≥ 700 samples, zero gaps > 60 s, and a summary JSON containing p50/p95 frame time and thermal ceiling; the H2-4 negative run shows the documented FPS drop and the pinned run does not; CI: a schema test validates the sampler's output record (unit, no device).
- **Effort:** M
- **Depends on:** none (blocks P1-8's growth numbers and informs H2-4/W1-8 pins)
- **Owner stage:** new:S9 (register says "S9/R4"; R4 is the reviewer lens that consumes the evidence — the harness work item itself lands with the S9 coexistence soaks, with the vanilla baseline runnable immediately)

### P2-1 — Flavor-routed DB datadir iteration, grants/creates/probes (checkpoint_dbs.py + DatabaseEngine)

- **Goal:** Every tool and engine path that enumerates or provisions the four databases — `tools/checkpoint_dbs.py`, the MariaDB bootstrap GRANT/CREATE block, the realmlist projection, and the SQLite control plane — resolves names and datadirs through `ClientFlavor.DatabaseNames`, so tbc*/wotlk* sets work with zero `classic` literals.
- **Current state:** `tools/checkpoint_dbs.py:7-8` hard-codes both the datadir (`$LOCALAPPDATA/PocketRealm/database/sqlite-datadir`) and the exact four names `classicrealmd/classicmangos/classiccharacters/classiclogs`. In the app, `DatabaseEngine.kt` hard-codes the same set at every layer: CREATE DATABASE + GRANT bootstrap (:257-267, including the wider world-DB grant with `EXECUTE/CREATE ROUTINE` at :267), the realmlist UPDATE/probe (:432-435), the user-state routing key (`"classicrealmd" -> DatabaseUserStateBridge.REALMD_USER_STATE_TABLES`, :731), the retired-datadir inventory (:1511-1516), and the provenance classifier (`source_path.startsWith("native/classic-db") -> "classic_db"`, :1763). `DatabaseSqliteControlPlane.kt:23` hard-codes `DATABASES = ["classicrealmd", "classiccharacters", "classiclogs", "classicmangos"]`. The per-flavor names already exist (`ClientFlavor.kt:67-72,98-103,126-131`) and `ServerRuntimeFiles.serverDatabases()` already resolves them — but only through the single-qualified-lane guard (`ServerRuntimeFiles.kt:404-414`), which is itself the F3-3/H2-5 landmine (owned by that gap's section below) that this gap's routing must not extend until the S5 threading lands.
- **Work items:**
  1. `tools/checkpoint_dbs.py`: accept `--flavor` (default vanilla) and resolve names + datadir from `schemas/flavor.json` (import the resolver used by `tools/check_flavor.py`) instead of the literal tuple; keep the no-arg invocation byte-compatible for vanilla.
  2. `DatabaseSqliteControlPlane.kt:23`: make the DATABASES list a function of the flavor's `DatabaseNames` (threaded from the caller — the control plane is the shared desktop/Android surface per `build_win_sqlite_seam.py` header), with `META_DATABASE` unchanged.
  3. `DatabaseEngine.kt` bootstrap (:257-267): generate CREATE/GRANT from the flavor's names; keep the world-DB-only extra grants (`EXECUTE, CREATE ROUTINE, ALTER ROUTINE`) keyed to the world name; golden-string test that vanilla's generated SQL is byte-identical.
  4. Route :432-435 (realmlist), :731 and :1511-1516 (user-state + retired inventory) through the same names object; :1763's provenance classifier becomes family-driven (`native/classic-db` vs `native/classic-db-tbc|wotlk` per plan S4 item 1 submodule names).
  5. Update `DatabaseSqliteControlPlaneTest.kt` and `tests/test_sqlite_seeding.py` fixtures to parameterize names, with a vanilla case asserting the old literals.
- **Upstream dependency:** none (repo-internal routing; flavor name sets already registered in `ClientFlavor.kt` and `schemas/flavor.json`).
- **Verification:** `grep -rn "classicrealmd" android/app/src/main desktop/src/main tools/` returns only the `ClientFlavor`/flavor-registry definitions and vanilla-golden fixtures (extend `tools/leak_sweep.py` SCAN targets accordingly — the sweep already carries `classicrealmd` in its banned-constant list, plan §4.6); unit: control-plane + engine bootstrap tests parameterized over all three flavors; tool: `python tools/checkpoint_dbs.py --flavor vanilla` output identical to today's on a fixture datadir.
- **Effort:** M
- **Depends on:** none (the mechanical routing F3-1/P2-3 build on)
- **Owner stage:** new:S4

### P2-3 — Per-flavor revision probe Triples and revision formats (s-rev vs numeric)

- **Goal:** The revision-probe triples (component → database → revision column) and the expected-revision value *format* (classic named s-rev vs TBC/WotLK formats) come from the per-flavor ledger manifest, and each flavor's mismatch negative-vector is derived from its own format.
- **Current state:** The MariaDB probe is hard-coded in `tools/stage_database_migrations.py:35-43` (expected revisions `required_z2820_01_realmd_joindate_datetime` etc. and the classic DB-name map) and mirrored into `schemas/database-migrations.json` `expected_revisions` (verified: `realm/characters/logs/world` → `required_z…` strings). The SQLite probe is the hard-coded `Triple` list at `DatabaseEngine.kt:1832-1835` (`("realm","classicrealmd","realmd_db_version") …`), with the mismatch negative test pinned to classic's format at :1843 (`required_z9999_not_real` against `classicmangos.db_version`) and its MariaDB twin at :617. `tools/seed_realm_db.py:293-296` pins the same columns for the seeding tool. Register H1R2-1 states the flavor formats differ: TBC `required_s2492…`-class strings, WotLK numeric-revision class (`14104`) — i.e. the classic `required_z…` glob is not reusable verbatim.
- **Work items:**
  1. Extend the ledger manifest schema (`schemas/database-migrations.json` → per-flavor ledgers per plan S1 item 7) with per-flavor `probe` entries: `{component, database, revisionColumn, expectedRevision, expectedFormat}`; `stage_database_migrations.py:35-43` generates them from the flavor core's `revision_sql.h` (plan S4 item 2's expected-revision source).
  2. `DatabaseEngine.kt:1830-1846`: build the SQLite Triples from the manifest's probe entries (fall back to the current literals only when `flavorId == "vanilla"`, keeping the G3 readers-compatible rule), and derive the negative-vector revision string per format (classic `required_z9999_not_real`; tbc/wotlk format-equivalents — an s-form and a numeric form that must be rejected).
  3. Same for the MariaDB negative test at `DatabaseEngine.kt:617` and the seeding tool's map (`tools/seed_realm_db.py:293-296` — parameterize by flavor).
  4. Confirm the exact per-branch revision column names and value formats by reading the pinned forks' `sql/base/*_db_version` seeds and `revision_sql.h` (register H1R2-1's `required_s2492…` / `14104` values are the hypothesis to verify, not facts to code against).
- **Upstream dependency:** `cmangos/mangos-tbc` / `cmangos/mangos-wotlk` `revision_sql.h` + `sql/base` (pins TBD at S2/S4 per plan §6); `cmangos/tbc-db`, `cmangos/wotlk-db` installed-revision strings.
- **Verification:** unit: probe-table test per flavor asserting (a) correct triples, (b) the real expected revision passes, (c) both a wrong-value and wrong-format revision are rejected (`DB-REVISION: mismatch negative test was incorrectly accepted` must not fire); integration: `tests/test_sqlite_seeding.py` runs the seeded flavor DB through the probe; device: zero-mismatch boot assert per flavor (plan H1R2-1's gate).
- **Effort:** M
- **Depends on:** P2-1 (name routing), F3-1 (per-flavor manifest as the carrier)
- **Owner stage:** new:S4

### P2-2 — Snapshot/restore flavor validation (manifest compatibility gains flavor, db-set, table inventory)

- **Goal:** A snapshot records which flavor/db-set it was taken from inside the existing schema-2 `compatibility` object; restoring a snapshot across flavors is rejected with an actionable code, while every existing vanilla snapshot restores unchanged.
- **Current state:** `DatabaseSnapshotStore.kt:43-45` writes manifest `schema: 2` with a free-form `compatibility` JSONObject, and `validatePublished` (:155-157) enforces `manifest.length() == 5` — so **top-level keys cannot be added without quarantining existing snapshots** (register G3-9 names exactly this hazard). The compatibility payload is produced by `databaseCompatibility()` (`DatabaseEngine.kt:3025-3035`: provider id, provider/bootstrap/migration-manifest SHA-256s, migration count, generation UUID) and checked by `requireCompatibleSnapshot()` (:3046-3057) — all provider-level, nothing flavor-level. `createNamedBackup` (`DatabaseEngine.kt:2443-2456`) adds `runtimeBuildId: "o09-cmangos-c096bada-nobots-v1"` and `databaseFamily: "cmangos-classic"` (:2453-2454) — the only flavor-ish markers today, both hard-coded vanilla strings.
- **Work items:**
  1. Extend `databaseCompatibility()` with three optional keys: `flavorId` (from the active flavor), `databaseNames` (the 4-name set), `tableInventory` (per-database table counts or a digest of `information_schema`-derived names — cheap at snapshot time). Do **not** add top-level manifest keys; the schema-2 length==5 invariant holds.
  2. Extend `requireCompatibleSnapshot()` to compare the new keys **only when present**: absent keys (legacy vanilla snapshots) default to `vanilla`/classic set (register G3-9's "relax to ≥"/optional-field shim); present-and-mismatched keys reject with a new `DB-SNAPSHOT: snapshot flavor <x> is incompatible with active flavor <y>` message.
  3. Replace the hard-coded `runtimeBuildId`/`databaseFamily` literals in `createNamedBackup` (`DatabaseEngine.kt:2453-2454`) with flavor-derived values (the same literals must remain for vanilla — golden test).
  4. Cross-flavor reject tests (R2-authored, plan §4.2): a wotlk snapshot restored over a vanilla datadir refuses at the compatibility gate and vice versa; a legacy (pre-field) vanilla snapshot still restores.
  5. The S9 side (namespace-per-flavor snapshot roots) lands with H5R2-1's coexistence work; here only the in-manifest validation ships.
- **Upstream dependency:** none.
- **Verification:** unit: `DatabaseSnapshotStore`/engine tests — legacy manifest restores; cross-flavor manifest rejected with the named message; golden: today's vanilla snapshot bytes unchanged (`schema`/5 keys/compatibility keys identical); androidTest: backup→wipe→restore round-trip per flavor in the S4 lane.
- **Effort:** M
- **Depends on:** P2-1 (name routing supplies `databaseNames`)
- **Owner stage:** new:S4 (validation) with the S9 cross-flavor adversarial re-run

### P2-4 — SQLite translator: unsupported-construct fail-loud + per-flavor baselines

- **Goal:** The MariaDB→SQLite seed translator fails loudly naming the construct when it meets SQL it cannot faithfully translate (triggers, stored procedures/functions, and any new flavor-branch construct), and per-flavor seed baselines pin the wotlk Full_DB replay at scale.
- **Current state:** `tools/sqlite_seed_translator.py` (1,872 lines) documents string-aware, DELIMITER-aware statement splitting (header, :14) but a case-insensitive grep finds **no** `TRIGGER`/`PROCEDURE`/`FUNCTION`/`VIEW` handling and no unsupported-construct rejection — unsupported DDL would either fail deep in the applier or, worse, translate syntactically. The single baseline `schemas/sqlite-seed-baseline.json` pins classic only (verified content: `entries: 414`, `classicrealmd/classiccharacters/classiclogs/classicmangos` blocks with statement/row counts, translation stats citing `native/classic-db/Full_DB/ClassicDB_1_12…`), and `tests/test_win_lockfile.py:26` pins `BASELINE` to that one file. Register F2-4 records the wotlk scale to expect: `spell_template` 50,889 rows, `quest_poi` 56,432; register P2-4 cites a ~30 MB wotlk Full_DB replay.
- **Work items:**
  1. Add an explicit construct gate to the translator: a pre-apply scan per statement for `CREATE TRIGGER|PROCEDURE|FUNCTION|EVENT|VIEW` and `DELIMITER`-body constructs; on hit, abort with `SQLITE-TRANSLATE: unsupported construct <kind> in <file>:<lineno>` — never approximate. (TBC/WotLK base SQL may legitimately contain procedures where classic had none; if the flavor needs them, the Decision Log records a translator extension, not a silent pass-through.)
  2. Parameterize the translator + `tools/seed_realm_db.py` entry lists (`seed_realm_db.py:40-50` hard-codes `native/cmangos/sql/base` and the classic Full_DB path) by flavor: base SQL from the flavor core, Full_DB from `native/classic-db-tbc|wotlk`.
  3. Produce per-flavor baselines `schemas/sqlite-seed-baseline-{vanilla,tbc,wotlk}.json` (keep the unqualified name as the vanilla alias during the deprecation window per plan S1 item 8); each pinned by `manifest_sha256` like today's.
  4. Update `tests/test_win_lockfile.py:68-77` (seed-pin equality with the sibling android lane) to compare per-flavor pin sets, and `tools/verify_seed_sqlite_floor.py` to take the flavor's floor file.
  5. Content floors per flavor from register F2-9's measured numbers (vendors 6,444/34,028; creature_loot 174k/220k) — re-measure at seed time, don't trust the register blindly (its own §3 flags the TBC quest-row count as UNCERTAIN).
- **Upstream dependency:** `cmangos/tbc-db`, `cmangos/wotlk-db` Full_DB archives (pins TBD at S4 per plan §6; ACID is inlined [ACID] commits per register C5, so no `acid_*.sql` selection rule here).
- **Verification:** unit: translator negative tests — a synthetic base SQL containing a trigger/procedure aborts with the named error before any file write; `tests/test_sqlite_seeding.py` green per flavor baseline; tool: `python tools/seed_sqlite_from_manifest.py --flavor wotlk` replays the wotlk Full_DB with the baseline's counts matching within the floor tolerance; time the replay and record it in the S4 evidence pack (the ~30 MB figure from the register is the expectation to confirm).
- **Effort:** L
- **Depends on:** P2-1 (db names), P2-3 (revision probes for the seeded DBs)
- **Owner stage:** new:S4

### P2-5 — Per-flavor seed-augment capture and staging

- **Goal:** The seed-augment lane (bot equip/rnditem caches) is captured, staged, and digest-bound per flavor, with the augment directory and provenance namespacing the flavor.
- **Current state:** One augment directory for the whole repo: `tools/seed_sqlite_from_manifest.py:43` `AUGMENT_DIR = ROOT / "schemas" / "seed-augment"`, and `schemas/seed-augment/` contains exactly `PROVENANCE.json` + `classiccharacters.sql.gz` (verified listing) — the classic characters DB augment only. `apply_seed_augments` (:75-191) validates each augment's provenance against the manifest's `manifest_sha256` tripwire and rejects non-INSERT lines/embedded semicolons (:133-146) — those guards are good and stay; only the routing is single-flavor. Plan S4 item 4 requires a *new* per-flavor capture bound to the flavor's `manifest_sha256`.
- **Work items:**
  1. Reshape to `schemas/seed-augment/{flavor}/` directories (`vanilla/` holding the existing pair, byte-identical); keep the unqualified path as a vanilla alias during the deprecation window (plan S1 item 8 pattern).
  2. Parameterize `AUGMENT_DIR` and the manifest lookup in `seed_sqlite_from_manifest.py` by flavor; the manifest's `database` field (:11-12) already names target DBs — validate them against the flavor's `ClientFlavor.DatabaseNames` so a classic-named augment cannot apply to `tbccharacters` (fail-closed, plan invariant 2).
  3. Capture the TBC and WotLK augments during RA/RB S4 (bot equip/rnditem caches after a standard bot-population soak), bind each to the flavor ledger's `manifest_sha256`, and extend `PROVENANCE.json` to a per-flavor map.
  4. Add a per-flavor registry key in the packaging gate (plan S3 item 7 analog) so a flavor shipping without its augment fails staging loudly rather than seeding bots with empty caches.
- **Upstream dependency:** none (captures are produced in-repo from the flavor's seeded DB + bots build; the S1 per-flavor ledger rename provides the `manifest_sha256` values).
- **Verification:** unit: `apply_seed_augments` tests parameterized per flavor, including the negative "vanilla augment offered to wotlk seed" rejection with the actionable message; tool: `python tools/seed_sqlite_from_manifest.py --flavor tbc` applies exactly the tbc augment set and reports the per-DB statement counts in its report (the existing report field at :191); provenance: digest of every augment file recorded in the flavor ledger and checked by `tools/check_repo.py`.
- **Effort:** S
- **Depends on:** P2-4 (per-flavor baselines/binding), P2-1 (name validation)
- **Owner stage:** new:S4

### P2-6 — Per-flavor Windows SQLite-seam lockfiles

- **Goal:** The Windows SQLite-seam lane pins per-flavor source overlays and seed transcripts in per-flavor lockfiles, with the win-lane test asserting sibling parity per flavor instead of against the single vanilla file.
- **Current state:** One win lockfile: `schemas/realm-runtime-lockfile-sqlite-win.json` (verified keys: `schema, abi, database_backend, cmangos_commit, playerbots_commit, cmangos_source_overlays, playerbots_source_overlays, patches_content, sqlite_amalgamation, artifacts, seed_transcripts, toolchain`) — a single `cmangos_commit`/overlay set with no flavor dimension. `tools/build_win_sqlite_seam.py` builds `pocket_sqlite.dll` over the pinned SQLite 3.46.1 amalgamation (`PINNED_VERSION = "3.46.1"`, :36) and imports `build_win_realm_runtime as realm` for pins (:30). `tests/test_win_lockfile.py:24-26` pins `WIN_LOCKFILE`, `SIBLING_SQLITE` (`realm-runtime-lockfile-sqlite.json`), and `BASELINE` — all singletons; its `test_source_pins_mirror_the_android_sqlite_lane` (:41-56) asserts one lane's overlays equal the android sqlite lane's, which is exactly the property that must become per-flavor once `db-sqlite-connection-hardening` is re-derived per branch (plan S2 item 3).
- **Work items:**
  1. Naming convention per plan S0 item 3: `realm-runtime-lockfile-sqlite-win-{vanilla,tbc,wotlk}.json`; keep the unqualified name as the vanilla alias (plan S1 item 8).
  2. Extend `build_win_sqlite_seam.py` with `--flavor`, resolving the per-flavor overlay sets (the o09 overlay split into expansion-neutral vs per-flavor definitions is plan S1 item 4's job — this tool consumes the split), and writing the flavor's lockfile; fail loudly when a flavor's overlay is not yet registered (expected pre-S2, per plan S1 end-state).
  3. `tests/test_win_lockfile.py`: loop the flavor set — each flavor's win lockfile mirrors the android sqlite lane of the same flavor; seed pins equal that flavor's `sqlite-seed-baseline-{flavor}.json` (pairs with P2-4 item 3); vanilla assertions byte-identical to today's.
  4. The seam DLL itself is flavor-agnostic (SQLite engine, not core code) — record that in the Decision Log so nobody forks the amalgamation per flavor; only pins differ.
- **Upstream dependency:** overlay pins re-derived per branch at S2 (plan S2 item 3: `db-sqlite-connection-hardening` re-anchored against `puzzled-pancake/mangos-tbc`/`-wotlk` `pocket-realm`, commits TBD).
- **Verification:** `python tools/build_win_sqlite_seam.py --flavor vanilla` reproduces today's DLL hash (regression proof, plan S1 end-state pattern); `--flavor tbc|wotlk` fails loudly until S2 pins exist, then green; `python -m pytest tests/test_win_lockfile.py` green across all three flavors with per-flavor sibling parity; JNI export table check (existing `verify` step) unchanged.
- **Effort:** S
- **Depends on:** P2-1 (control-plane names in the seam's replay legs), P2-5 (per-flavor seed pins)
- **Owner stage:** new:S1

### P2-7 — Windows lane per-flavor identity/auth/extractor constants

- **Goal:** Every Windows-lane tool and desktop fixture that names the client identity, the auth frame layout, or the extractor input resolves constants per flavor from the registry, so `--flavor tbc/wotlk` runs need no hand edits.
- **Current state:** Hard-coded vanilla everywhere in the win lane: `tools/win_prepare_data.py:44` `DEFAULT_CLIENT = Path("C:/Vanilla wow 1.12.1")`, :190 rejects non-`1.12.1` roots ("does not look like a 1.12.1 client root"), :318 writes `"clientBuild": 5875` into the manifest; `tools/install_client_windows.ps1:96-98` checks `$EXPECTED_EXE_BYTES` and version `1.12.1.5875*` and :191 prints the identity line; the desktop auth fixtures pin the SRP6 build: `desktop/src/main/kotlin/com/pocketrealm/desktop/AuthGate.kt:265` `BUILD_5875 = 5875`, :293-297 the little-endian reversed build bytes, :357 the 26-byte proof-response frame; `desktop/src/main/kotlin/com/pocketrealm/desktop/RpWowClient.kt:40` `SMSG_AUTH_CHALLENGE = 0x1EC` (register C3 confirms 0x1EC holds for all branches — the opcode survives, the frames around it do not necessarily) and :46 `BUILD_5875`. Extractor provenance is single-commit: `DataPreparationStore.kt:425` `CMANGOS_COMMIT = "c096bada9e4ed23ad4ca706c67160a26d7121337"` (the split `tools/build_o09_realm_runtime.py:37` vs lockfile records is plan S1 item 1's repair; the per-flavor routing is this gap).
- **Work items:**
  1. Add to `ClientFlavor`/`flavor.json` the win-lane constants: `exeSizeBytes`, `prepareClientRootHint`, `authFrame` descriptor (build bytes width, proof-frame length), `extractorCommit`. Vanilla values = today's literals (golden); tbc/wotlk values measured at S5/S2 qualification (per-flavor pristine sizes from register C6: 8606 `57c5c030…`, 12340 `45892bde…` MD5s are cross-checks; sizes measured locally).
  2. `tools/win_prepare_data.py`: `--flavor` argument routing identity checks (:190), default root (:44), and the emitted `clientBuild` (:318) through the registry (read `schemas/flavor.json` the same way `checkpoint_dbs.py` will, P2-1 item 1).
  3. `tools/install_client_windows.ps1`: parameterize `EXPECTED_EXE_BYTES`/version string (:96-98) and the identity copy (:191) via a `-Flavor` parameter with the values injected from flavor.json by the caller (`tools/stage_o07_client.py` analog).
  4. `AuthGate.kt`/`RpWowClient.kt`: replace `BUILD_5875` with a `ClientFlavor`-derived frame descriptor; keep 0x1EC shared (C3) but move it to the shared contract with a static comment citing C3; the 26-byte proof frame (:357) becomes per-flavor with WotLK/TBC frames verified against the branches' `AuthSocket.cpp` at S8 (plan S8 item 2 already schedules the wire fixtures).
  5. `DataPreparationStore.kt:425`: `CMANGOS_COMMIT` becomes per-flavor (vanilla keeps `c096bada…`; tbc/wotlk from the S2 forks), asserted against `schemas/o11-extractor-lockfile-{flavor}*.json`.
- **Upstream dependency:** `cmangos/mangos-tbc`/`-wotlk` `AuthSocket.cpp` frame verification (S8); qualified clients `wow-2.4.3-8606`/`wow-3.3.5a-12340` for size/version measurement (plan §8.2 items 1-2).
- **Verification:** tool: `python tools/win_prepare_data.py --flavor vanilla` output byte-identical to today's manifest; negative: `--flavor wotlk` against a 1.12 root fails with the flavor-aware copy (and vice versa); desktop harness: `tools/rp_harness/probe_world_auth.py` matrix per plan S8 end-state (matching pairs authenticate, all mismatches fail cleanly) — the 12340/8606 legs require the S8 fixtures; unit: parity test extends `FlavorManifestParityTest` to the new fields.
- **Effort:** M
- **Depends on:** none directly (S1's registry work is the substrate; P2-8 pins the tests)
- **Owner stage:** new:S8

### P2-8 — Per-flavor ParityTest clients

- **Goal:** The flavor-manifest parity gate and its test clients cover the full per-flavor constant surface (win-lane identity/auth/extractor, storage, scanner, timeouts) so the manifest and every consumer provably cannot diverge per flavor.
- **Current state:** `FlavorManifestParityTest` (`android/app/src/test/java/com/pocketrealm/client/FlavorManifestParityTest.kt:18`) walks all three flavors and asserts every shared field between `schemas/flavor.json` (schema 2) and `ClientFlavors` — databases, addon interfaces, wine prefix, exe hash, MPQ set (verified body, :26-60) — but only for the fields that exist *today*; each new per-flavor field added by P1-1/P1-7/P1-8/P2-7 (buffer pool, boot budgets, quotas, win constants) extends the divergence surface without coverage unless this test grows with them. The desktop lane has its own twin tests (`DesktopRuntimeBackendTest`, `DesktopSqliteSeederTest`, `DesktopServerRuntimeFilesTest` — all in the `classicrealmd` grep hits) that are vanilla-only today.
- **Work items:**
  1. Make the parity test exhaustive-by-construction: reflect over `ClientFlavor`'s properties and require a matching `flavor.json` key for each (a missing manifest key fails with "field <x> unpinned"), so adding a constant without manifest data breaks CI rather than drifting.
  2. Add a desktop-side parity test mirroring the same manifest against the desktop constants (`AuthGate`/`RpWowClient`/`DesktopRuntimeBackend`) so the JVM twin cannot silently diverge from the Android registry.
  3. Add a "clients" fixture matrix: for each flavor, a synthetic `SyntheticClientArchives`-style client (plan S5 item 8) whose exe size/version/MPQ set encode the flavor constants, driven through the scanner and identity gates in a unit test — this is the runnable stand-in for the genuine-client device runs and catches constant drift without hardware.
  4. Wire the suite into the leak-sweep companion: `tools/leak_sweep.py` and this test together assert no flavor constant lives outside the registry (plan §4.6 runtime-assertion class).
- **Upstream dependency:** none (consumes the fields defined by P1-1, P1-7, P1-8, P2-7).
- **Verification:** `python`-side: `pytest tests/test_leak_sweep.py` extended with the new constant classes; JVM: `./gradlew :app:testDebugUnitTest --tests "com.pocketrealm.client.FlavorManifestParityTest"` plus the new desktop parity test green for all three flavors; mutation check: temporarily corrupt one `flavor.json` constant in a scratch tree and confirm both the parity test and the corresponding consumer test fail (run once locally, then reverted).
- **Effort:** M
- **Depends on:** P2-7 (the constants it pins), P1-1/P1-7/P1-8 (their fields enter the manifest)
- **Owner stage:** new:S10 (program qualification gate; authored incrementally as each constant lands from S4 on)

### P2-9 — Support-bundle budgets at WotLK scale (head+tail truncation)

- **Goal:** Support bundles stay bounded at multi-flavor scale without losing the newest evidence: per-entry head+tail truncation replaces the current tail-dropping `take()`, and the hard-coded vanilla server identity in `runtime-builds.json` becomes flavor-aware.
- **Current state:** `SupportBundleExporter.kt:55` redacts then truncates every entry with `.take(MAX_ENTRY_CHARS)` (512 KiB, :107) — the **tail** is discarded, which is exactly where the most recent log lines live; entries larger than 512 KiB that arrive from disk are dropped entirely (`fixedFile` :84 `length() <= MAX_ENTRY_CHARS`). The bundle cap is 4 MiB (`MAX_BUNDLE_BYTES`, :108) with a `verify()` re-read (:87-100). The fixed `runtime-builds.json` entry hard-codes the vanilla server string: `SupportBundleExporter.kt:36` `.put("server", "o09-cmangos-c096bada-nobots-v1")` — the same literal as `DatabaseEngine.kt:2454`, so at multi-flavor scale a wotlk bundle would misreport its server build. At WotLK scale, `world.log` (bigger worlds, more bots) and the supervisor journal routinely exceed 512 KiB, so today's bundles lose precisely the crash-adjacent tail (register H4-7/H4R2-10 route the crash-artifact inventory; this gap owns the budget mechanics).
- **Work items:**
  1. Add a `headAndTail(value, budget)` helper keeping the first N and last N bytes with an explicit elision marker carrying the omitted byte count (so the manifest's `bytes` field stays truthful — `records.put(... "bytes", value.toByteArray().size)` at :59 counts what is written).
  2. Apply it in the `redacted.mapValues` step (:55) instead of `.take(...)`; keep `MAX_ENTRY_CHARS` as the vanilla budget default and derive per-flavor/per-entry budgets (logs get more than JSON manifests) from a small table; total still bounded by `MAX_BUNDLE_BYTES` with a deterministic overflow policy (largest-entry first reduction, asserted by test).
  3. Replace the `server` literal (:36) with the active flavor's runtime-build id from the registry (same string for vanilla — golden), landing inside the schema-2 `runtime-builds.json` shape **owned by §2.4 H4R2-10** (`{"schema":2,"flavors":[…]}`, the active session's flavor marked — consumed by reference; this gap's budgets/truncation apply to the entries and it does not add a separately-designed top-level `flavor` field).
  4. Keep every `verify()` invariant (:87-100) — canaries, path/IP regexes — now asserted against head+tail output too.
- **Upstream dependency:** none.
- **Verification:** unit: golden bundle test with a 2 MiB synthetic `logs.json` — output contains the first and last 4 KiB, the elision marker with the correct omitted count, manifest `bytes` matches, cap respected; redaction canary still detected inside both head and tail; vanilla bundle for a small log set is byte-identical to today's (no head+tail when under budget); `flavor` field asserted per `ClientFlavors` fixture.
- **Effort:** S
- **Depends on:** none (feeds H4-7's crash-artifact harvesting with usable tails)
- **Owner stage:** new:S9

### P2-10 — Redaction key sweep per conf surface

- **Goal:** Every conf surface the app stages per flavor (mangosd.conf, realmd.conf, aiplayerbot-*.conf, LLM overrides, database info strings) has its keys swept against the redactor's sensitive-key rules, so a new flavor's conf keys can never smuggle a secret past `SecretRedactor` into a bundle.
- **Current state:** `SecretRedactor.kt:53-56` `SENSITIVE_KEY` regex covers `password|passwd|secret|credential|instance.?token|owner.?token|document.?uri|source.?uri|absolute.?path|android.?account` — a fixed list built against the vanilla conf surface. The conf surfaces grow per flavor: `ServerRuntimeFiles.kt` emits `mangosd.conf` (:135-189), `realmd.conf` (:52-66), per-profile `aiplayerbot-<id>.conf` (:123-125), and `databaseInfo()` (:427-443) embeds the 48-hex core secret into connection strings — the secret is protected today by the file-mode `secureWrite` (:451-462) and the `secret` regex key, but TBC/WotLK dist files add whole new key families (register H1-17/H3R2-1: per-flavor conf-key matrices; register G2-1's Warden keys; `Warden.Enable`, anticheat keys) whose *value* shapes (tokens? keys? paths?) are unreviewed. The redaction sweep that exists is the bundle-time canary mechanism (`SupportBundleExporter.verify` :94-96), which only checks the export's own canaries.
- **Work items:**
  1. Build a conf-surface inventory test: for each flavor, generate the full staged conf set from `ServerRuntimeFiles` (unit-level, using the pure companion helpers where they exist — `worldLogFileLevel`/`llmOverrides` at :503/:525) and assert (a) every key name that matches `SENSITIVE_KEY` carries a value that is itself redacted-safe, and (b) no value in any staged conf matches the `SecretRedactor` value regexes (paths/IPs/emails) unless deliberately non-secret.
  2. Add the per-flavor key matrix from each core's `.conf.dist` (plan H1-17's fail-loud unknown-key check) as the source of the key list, so the sweep covers keys the app *could* emit, not just the ones it does.
  3. Extend `pii_markers.local.json` (the repo's canary mechanism, `tools/pii_markers.local.json.example`) with per-flavor canaries (bot conf tokens, realmd password shapes) and wire them into the bundle-export test path.
  4. If a flavor's legitimate keys need new `SENSITIVE_KEY` patterns, extend the regex with the golden rule that vanilla redaction output is unchanged (byte-golden test on a fixed vanilla bundle fixture).
- **Upstream dependency:** none (key lists from the pinned branches' `*.conf.dist` at S2/S3).
- **Verification:** unit: the sweep test green for all three flavors; negative: inject a synthetic `AiPlayerbot.LLMToken = <canary>` key and assert both the staging sweep and the bundle `verify()` reject it; golden: vanilla `SecretRedactor` output on the fixed fixture unchanged; CI: sweep runs in the same job as `tests/test_leak_sweep.py`.
- **Effort:** S
- **Depends on:** P2-9 (the bundle surface it protects); consumes H1-17's per-flavor conf matrix (other area)
- **Owner stage:** new:S10

### P2-11 — Update coordinator writes per-flavor requires-revalidate (ledger sha + extractor version); boot gate fails closed

- **Goal:** After an app update, each flavor's prepared data and DB ledger are revalidated against the new APK's pins; a mismatch writes a per-flavor `requires-revalidate` flag and the world boot gate refuses that flavor until re-prep/re-apply — fail closed, never silently serve stale data.
- **Current state:** `AppUpdateCoordinator.kt` verifies only the update artifact itself (manifest SHA-256 gating at :244 `isVerifiedUpdate`, host allowlist :50, download headroom :124-127); the "manual migration" path at :99 is a message, not a per-flavor data gate. Two staleness fences exist but are not wired to the update flow: `NativeRuntimeFreshness.kt` (referenced from `ServerRuntimeContract.kt:12` as `validateNativeRuntimeFreshness`) compares staged native artifacts against `schemas/sources.json` commit pins, and `DataPreparationStore.kt:425` pins `CMANGOS_COMMIT = "c096bada…"` for extractor provenance. The durable DB lane self-checks revisions at start (`DB-REVISION: pinned migrations are not current`, `DatabaseEngine.kt:313,985,1193`) — but against the *packaged* manifest, which by definition matches the new APK after an update; what is missing is the inverse direction: the *staged on-device* prepared-data generation (o11 manifest carries `clientBuild`/family per plan S6 item 2) and the applied ledger generation checked against the *new* APK's ledger sha + extractor version, per flavor.
- **Work items:**
  1. Compute at update-install time (or first boot after) per flavor: `expected = sha256(database-migrations-{flavor}.json asset) + extractorCommit` vs `staged` (the generation's recorded values in `DataPreparationStore`'s manifest and the engine's ledger generation marker).
  2. On mismatch, write a durable per-flavor `requires-revalidate` record (alongside the existing generation pointers — `clients/active.json` pattern, `ManagedClientStore.kt:60-65`) with the expected/observed pair; `RealmState.kt:83` already has `SaveReason.UPDATE_REQUIRED` — the boot-side surface exists.
  3. Gate world start on it: the launch path that already checks prepared-data identity (plan S6 item 3's consumer gate in `PreparedDataStore.kt:38-99`) additionally refuses when `requires-revalidate` is set for the session's flavor, with copy that names the remedy (re-prep / re-apply).
  4. Clearing the flag happens only through the successful re-prep (o11) or ledger re-apply path, which writes the new provenance — same fail-closed discipline as `DB-REVISION`.
  5. S10's upgrade matrix (plan §5 S10 item 3) executes this for real: update over an install with all three flavors present.
- **Upstream dependency:** none (per-flavor ledger/lockfile sets arrive from S1/S4; extractor commits from S2/S6).
- **Verification:** androidTest: install-verification harness stages a stale prepared-data generation (old extractor commit), boots the flavor → refused with the revalidate copy; re-prep clears the flag and boot succeeds; vanilla regression: an update where nothing changed writes no flag and boot is untouched (golden); unit: expected-vs-staged comparator table per flavor including the both-null and partial-record cases.
- **Effort:** M
- **Depends on:** F3-1 (per-flavor ledger shas are one side of the comparison)
- **Owner stage:** new:S9 (mechanism), exercised at S10

### P2-12 — Save & Exit + supervisor recovery per flavor: flavor in the journal, heartbeat, per-flavor stop budgets

- **Goal:** The supervisor journal records the session's flavor as an optional field; Save&Exit and every recovery path (orphan adoption, crash restart, mid-stop kill) resolve and resume the *journaled* flavor; a slow boot is distinguishable from a hang by progress heartbeat rather than only by timeout expiry.
- **Current state:** The journal codec has no flavor dimension: `RuntimeSnapshotJournalCodec.kt:13-37` encodes `schema/sessionId/phase/requestedProfile/runtimeMode/realmEndpoint/ports/clean/components/lastDurableAction/lastError/updatedAt*/recoverability`; `decode` (:39-92) accepts `LEGACY_SCHEMA = 2` or the current `JOURNAL_SCHEMA` (:96) — adding a top-level key is a codec change with a legacy-reader rule (register G3-4: "journal codec: optional flavor field"). Recovery semantics are flavor-blind today because there is exactly one lane: orphan adoption (`DurableRuntimeSupervisor.kt:858`), recovery deadlines (:797), and the stop ordering (:907) all act on components without knowing which datadir/prefix/DB set they belong to. Stop budgets are the shared literals of `RuntimeContracts.kt:84-85` (P1-9 routes them per flavor; this gap makes the *recovery* decisions flavor-aware). The heartbeat half: start-waits are pure `withTimeout` (:560-612) with no progress observation, and the core watchdog is disabled (`MaxCoreStuckTime = 0`, `ServerRuntimeFiles.kt:161`).
- **Work items:**
  1. Journal: add optional `flavorId` to `RuntimeSnapshot` + codec encode/decode; decode defaults absent → `vanilla` (G3-4 shim); bump `JOURNAL_SCHEMA` and keep `LEGACY_SCHEMA` accepted; `RuntimeSnapshotJson` tests cover old-schema fixtures from the current release (register G3 fixture rule).
  2. Heartbeat: extend the start path to sample the component's progress signal (P1-7 item 4's boot-progress line; DB start = migration-ledger progress lines already flowing to `mariadb.err`/apply stdout) and fail with distinct copy: "still starting (progress N seconds ago)" vs "no progress — presumed hung" — the latter alone escalates to force-stop.
  3. Recovery: every recovery branch (orphan adopt :858, `recoverDatabase` :797, crash restart) reads the journaled flavor and validates the components it touches against that flavor's datadir/prefix/DB set before acting — a journal naming `wotlk` must never adopt a vanilla realm process (cross-flavor invariant 4).
  4. Save&Exit: the graceful-stop path stamps the journal with the flavor before the first stop action, so a kill mid-Save&Exit resumes into the correct flavor's state (this is F3-6's device proof).
  5. Per-flavor stop budgets (from P1-9's measurements) replace the shared literals in the stop timeouts.
- **Upstream dependency:** none.
- **Verification:** unit: codec round-trip with and without `flavorId`; legacy schema fixture decodes to vanilla; androidTest: kill -9 mid-Save&Exit → restart resumes the same flavor with clean recovery (the S9 gauntlet scenario, plan §5 S9 end-state); the hang simulation (component with no progress lines) produces the "presumed hung" copy and force-stop, while a slow-progress component survives to its budget; golden: vanilla journal bytes for a no-op session unchanged except the new field.
- **Effort:** L
- **Depends on:** P1-7 (boot budgets + progress signal), P1-9 (stop budgets)
- **Owner stage:** new:S9

### F3-6 — Mid-switch kill resumes the journaled flavor (program qualification scenario)

- **Goal:** The S10 marquee adversarial scenario — interleaved sessions across all three flavors with a kill mid-switch — leaves zero cross-flavor state bleed: the restart resumes exactly the journaled flavor's session, and journals/backups/DBs stay per-flavor consistent.
- **Current state:** The mechanics this scenario needs are exactly P2-12's deliverables (journal flavor field, flavor-aware recovery, heartbeat), which land at S9; F3-6 is the *qualification* of the whole scenario at program level, where the remaining seams live outside the supervisor: generation pointers (`clients/active.json` — one pointer, `ManagedClientStore.kt:60-65`), the wine prefix per flavor (`winePrefixSegment`, `ClientFlavor.kt:34,74,105,133`), the active DB provider marker (`DatabaseDurableState` ACTIVE_PROVIDER_MARKER, referenced at `ServerRuntimeFiles.kt:430-431` — a single marker, so the provider mode itself is global, not per-flavor), and backup namespacing (`manual-*` snapshots in one root, `DatabaseEngine.kt:2463`). The register row pairs P2-12/F3-6 deliberately: P2-12 builds, F3-6 proves at the S10 gate.
- **Work items:**
  1. Author the scenario as a repeatable device harness (extend `tools/run_differential_parity.py`'s drive_server pattern, :156-190, or a sibling `tools/run_flavor_switch_storm.py`): loop {boot flavor A → play N min → Save&Exit → boot flavor B → kill -9 mid-switch → restart → assert journaled flavor resumed}.
  2. Assert per iteration: the journal's `flavorId` matches the resumed session; the DB provider marker/generation UUID belongs to that flavor; no foreign-flavor table names appear in the active datadir (name sets from `ClientFlavor.DatabaseNames`); prefix path segments are the flavor's.
  3. Add the app-update-mid-switch leg (pairs with P2-11): kill during an update with `requires-revalidate` pending → restart must refuse the stale flavor and boot the clean one.
  4. Add the backup-consistency leg (pairs with F3-10): a backup taken right before the switch restores into the right flavor after the storm.
  5. File the results in the S10 evidence pack; any bleed is an S0 finding under plan §4.3 (invariant 4).
- **Upstream dependency:** none (consumes S9's mechanisms; device windows per plan §8.2).
- **Verification:** the harness run: ≥ 10 interleaved switch/kill iterations across {vanilla, tbc, wotlk} on RP6 with zero bleed assertions failing; the harness is checked into CI in device-lane form (emulator AVD per plan S10 item 1) with a shortened loop; negative control: deliberately point the resumed session at the wrong flavor's datadir in a debug build and confirm the flavor-consistency assert fires (proves the checks can fail).
- **Effort:** M
- **Depends on:** P2-12 (journal flavor + recovery), P2-2 (snapshot flavor validation), P2-11 (revalidate flag), F3-10 (backup interlock)
- **Owner stage:** new:S10

### F3-1 — Revision-gate check tuples routed through the per-flavor manifest (DB names + rev formats)

- **Goal:** The zero-mismatch boot assert (register H1R2-1) reads its check tuples — database names, revision columns, expected revision strings, format — exclusively from the flavor's own `database-migrations-{flavor}.json`, so adding a flavor is a manifest change and the engine has no per-flavor literals.
- **Current state:** The engine loads exactly one ledger asset: `DatabaseEngine.kt:3260` `MIGRATION_MANIFEST = "database/migrations/manifest.json"` (read at :80; assets staged as numbered `.sqlz` by `tools/stage_database_migrations.py:186`, packaged via `android/app/build.gradle.kts`), and its probe tables are the hard-coded Triples and literals already catalogued under P2-3 (`DatabaseEngine.kt:617,1832-1843`). The manifest schema is enforced as `schema == 1` (`DatabaseEngine.kt:561`) with `expected_revisions` keyed `realm/characters/logs/world` — no flavor dimension, no DB-name dimension (the names live in code). This gap is the manifest-side complement to P2-3's consumer-side work: it defines the ledger format; P2-3 rewires the readers.
- **Work items:**
  1. Ledger schema v2 (per flavor): `database-migrations-{vanilla,tbc,wotlk}.json` adding `flavorId`, `databaseNames {realm,characters,logs,world}` (values matching `ClientFlavor.DatabaseNames`), `probe` entries (P2-3 item 1's tuples), and `revisionFormat` (`classic-zrev` / `tbc-srev` / `wotlk-numeric` — exact enum set from the fork verification in P2-3 item 4). Keep the unqualified `manifest.json` asset as the vanilla lane's name (packaging alias, plan S1 item 8) so the vanilla boot path is byte-identical.
  2. Asset layout: `database/migrations/{flavor}/manifest.json` + per-flavor `.sqlz` sets; `stage_database_migrations.py` gains `--flavor` and emits the split; `android/app/build.gradle.kts` packages all three (small: ledgers are manifests; the heavy SQL is per-flavor by nature).
  3. Engine routing: `DatabaseEngine` resolves the manifest path by the session's flavor (the same `ClientFlavor` the server runtime already resolves in `ServerRuntimeFiles.serverDatabases()`), enforces `schema` (1 for legacy vanilla file, 2 for split manifests) and cross-checks `databaseNames` against the flavor registry — a ledger whose names disagree with `ClientFlavors` fails closed with `DB-REVISION: ledger flavor mismatch`.
  4. `check_repo`/`tools/check_sources.py` extend to validate each flavor ledger's source_commits against `schemas/sources.json` per-flavor entries (plan S1 item 2) — the divergence CI of plan S1 item 3.
- **Upstream dependency:** `cmangos/tbc-db`, `cmangos/wotlk-db` (installed-revision strings); forks' `revision_sql.h` (pins TBD at S2/S4).
- **Verification:** unit: manifest parser accepts v1-vanilla and v2 per-flavor; rejects a v2 whose `databaseNames` disagree with the registry; integration: `tests/test_differential_lane.py` asserts the flavor-X ledger never applies to flavor-Y's database (plan §4.6's runtime assertion, now with real per-flavor ledgers); device: zero-mismatch boot assert green per flavor (H1R2-1's gate); packaging: `check_repo` green with all three ledgers.
- **Effort:** M
- **Depends on:** P2-1 (registry names), P2-3 (probe tuple/format discovery feeding the manifest)
- **Owner stage:** new:S4

### F3-4 — Duplicate same-build copy policy (2nd copy of a qualified flavor)

- **Goal:** Importing a second managed copy of an already-imported flavor has an explicit, recorded policy — deny with actionable copy at S5, with per-copy namespaces as the recorded S9 follow-up — and the negative test proves no silent overwrite or coexistence corruption.
- **Current state:** The managed-client registry is one-pointer-per-client-id: `ManagedClientStore.kt:58-65` loads via `clients/active.json` (schema 1) and requires the pointer's `clientId` to equal the requested id; there is no second-slot concept, no duplicate detection in the importer (`ManagedClientImporter.kt` recovers *its own* published generation per importId at :331-343 — resume, not dedupe), and the storage planner accounts one client lane (`ImportStoragePlanner.kt:29-30`). So a second genuine 12340 import today would either replace the active pointer (losing the first copy's pairing with its generations) or collide at publish. Register F3-4 asks for the policy decision; plan §5 S9 item 1 ("multiple managed copies, one per flavor at minimum") implies multi-copy support eventually, but the parity effort needs the *safe* default now.
- **Work items:**
  1. Record the decision (Decision Log per plan §2 item 6): **deny** a second managed copy of a flavor that already has one, at import time, with `VAL-` copy naming the flavor and the existing copy's import date; the deny is per flavor, not per client id, once `qualified().size > 1` (the S9 world allows revisit).
  2. Implement the gate in `ManagedClientImporter` before staging (cheapest rejection point): query the managed registry for an existing generation whose `clientId` resolves to the same flavor (`ClientFlavors.byClientId(...).flavorId`); deny matches.
  3. Exempt the legitimate re-import cases explicitly: same importId resume (:331-343 path), and a *replacement* flow (delete-then-import) that requires explicit user confirmation and a clean stopped state (no live session — reuse the stopped-state discipline of `DatabaseSnapshotStore.create`'s `databaseStopped` check pattern).
  4. Write the S9 follow-up decision entry: per-copy namespaces (client ids suffixed per copy, generation pointers per slot) deferred until H5R2-1's cross-flavor pinning lands — do not half-build it now.
  5. Storage-planner note: the deny keeps planner math single-copy-per-flavor (P1-8 item 5's assumption stays true).
- **Upstream dependency:** none.
- **Verification:** androidTest: import genuine/synthetic 12340 twice → second attempt rejected with the named copy; replace-flow test: confirmed delete-then-import succeeds while a session is running → rejected (stopped-state guard); vanilla regression: re-import of vanilla after delete unchanged; unit: the flavor-resolution dedupe helper (same clientId ≠ same flavorId edge — two client ids mapping to one flavor must deny too).
- **Effort:** S
- **Depends on:** none (S9 namespace follow-up depends on H5R2-1, other area)
- **Owner stage:** new:S5

### F3-3/H2-5 — Single-qualified-lane landmine: thread `ClientFlavor` through import/planner/prep; cumulative cross-flavor workspace cap

*(Register §1 P0 row, `FEATURE-PARITY-GAPS.md:33`; roadmap CP-5. Owned by §2.5 per review round 1 — the consumers live in the import/planner/prep lanes this section covers. Hard deadline: complete before S5 exit qualifies a second flavor.)*

- **Goal:** Every `ClientFlavors.qualified()` consumer resolves its flavor from an explicit `ClientFlavor` parameter instead of `qualified().single()`, and the storage planner enforces a cumulative cross-flavor workspace cap with an actionable deny `VAL` — importing, planning, or preparing a second flavor neither crashes a lane nor silently over-commits the device.
- **Current state:** Eleven production sites resolve the qualified lane through `.single()`. Four are helper-form with fail-loud guards whose messages name the lane — and every one of those guards **throws the moment tbc qualifies**: bot population policy (`BotProfiles.kt:225-230`, "bot population policy resolves the qualified lane"), import size limits (`ImportModel.kt:78-81`), storage budgets (`ImportStoragePlanner.kt:84-87`), and the server DB names (`ServerRuntimeFiles.kt:404-414`, which additionally requires the qualified lane to be vanilla at :411-413). Seven are inline uses: prep manifest resolution (`DataPreparationStore.kt:267`, `:296`), prepared-data consumer gate (`PreparedDataStore.kt:39`), in-game settings acquisition (`InGameSettingsEditor.kt:90`), wine build label (`WineRuntimeStore.kt:660`), tweaks-skip log and backend build id (`AndroidRuntimeBackend.kt:720`, `:1121`). The desktop lane bypasses the registry entirely with literals (`desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt:63,108` pass `"classicrealmd"` to `databaseInfo`). On the cap side, `ImportStoragePlanner.plan()` accounts exactly one database and one snapshot (`ImportStoragePlanner.kt:29-30`) against a *per-flavor* `maxTotalBytes` (`ClientFlavor.kt:41-42`) — three flavors each within their own cap can jointly exhaust the device with no gate firing.
- **Work items:**
  1. Thread `ClientFlavor` through the module boundaries (plan S0 item 4's module list), site by site: import/prep take the importing session's flavor (`DataPreparationStore`, `PreparedDataStore`); the planner defaults become explicit constructor args supplied by the import flow (`ImportModel.kt:81`, `ImportStoragePlanner.kt:87`); server start passes the flavor to `serverDatabases()` (the vanilla-only require at `ServerRuntimeFiles.kt:411-413` stays until S9 coexistence lands, then drops); bots (`BotProfiles`) and runtime/UX (`WineRuntimeStore`, `AndroidRuntimeBackend`, `InGameSettingsEditor`) take the session's flavor.
  2. Desktop twin: route the `databaseInfo("classic…")` literals (`desktop/.../ServerRuntimeFiles.kt:63,108`) through the same `ClientFlavor.DatabaseNames` so the Windows lane cannot silently stay vanilla once a second flavor qualifies.
  3. CI guard: extend `tools/leak_sweep.py` with a rule that `qualified().single()` / the `require(qualified.size == 1)` pattern appear only inside the `ClientFlavor.kt` registry module — a new consumer fails CI instead of failing in the field at qualification time.
  4. Cumulative cap: extend `ImportStoragePlanner.plan()` to sum all flavors' managed footprints (managed copies, prepared data, datadirs + snapshots, wine prefixes via `StorageRoots`) and enforce `Σ per-flavor usage + new import ≤ min(allocatable − reserve, Σ maxTotalBytes)` — the ceilings start from the S5-registered per-flavor `maxTotalBytes` budgets (register P3-2: ~25 GiB tbc / ~35 GiB wotlk; plan §6 owner "S5"), and P1-8's S6-measured quota numbers refine them afterward without changing this gate's shape; deny with a new `VAL` code naming the flavors and the over-cap bytes; the single-flavor case must produce output byte-identical to today's math (G3 golden).
  5. S9 end state consumes this: plan §5 S9 item 2's per-flavor storage accounting and the deny copy are the same numbers this work item computes.
- **Upstream dependency:** none.
- **Verification:** the review's pair, both runnable: (a) androidTest with `tbc` marked qualified in a test fixture — import → plan → prepare a tbc client while a vanilla managed copy exists: **no crash** from any of the eleven `single()` sites (today every one of them throws `IllegalArgumentException`); (b) unit planner table: cumulative cap exceeded → deny `VAL` naming flavors + bytes; under cap → single-flavor plan identical to today's (golden); (c) `python tools/leak_sweep.py` green with the new `.single()` rule, and negative-tested by temporarily adding a `single()` consumer in a scratch tree.
- **Effort:** M
- **Depends on:** P3-2's per-flavor `maxTotalBytes` (S5-registered — the cap values this S5 gate enforces); P1-8 composes with this gate, not a code dependency of it — its new:S6-measured quota numbers refine the ceilings after the gate lands (same scoping as H2-1's C1 edge, client-data-runtime.md), so the CP-5 S5 deadline carries no S6 prerequisite; F3-4 (deny policy shares the planner math); integration-coexistence.md's H5R2-1 and F3-8 `Depends on` entries resolve here
- **Owner stage:** new:S5 (hard deadline per roadmap CP-5: before S5 exit qualifies the second flavor)

---

### F3-10 — Backup/restore × session-drain interlock

- **Goal:** Backup and restore are impossible while any session component runs, by construction: the interlock refuses with actionable copy during a live session, and a drain (Save&Exit) must complete before a backup is taken — verified including the kill-mid-backup window.
- **Current state:** The guard exists at the DB layer: `createNamedBackup` requires `requireProvisioningIdle()` + `requireStopped()` + clean/current generation (`DatabaseEngine.kt:2443-2447`), and restore commits require the daemon drained (`requireDaemonDrained("restore commit")`, :2921). But the interlock is only as good as its callers: the user-facing path is `DatabaseService.kt:141` `createNamedBackup(name)` — a Binder entry with no session-awareness of its own; the supervisor's world/realm/client components are stopped through `DurableRuntimeSupervisor`, and nothing in the *service* layer refuses "backup" while the client is mid-play — the engine's `requireStopped()` catches the DB, but the client process can still be running against a stopped DB (a confusing half-state the copy must prevent). Restore has a pending-record window (`check(!restoreRecord.exists())`, :2449) and the two-phase COMMITTING protocol (:2920-2926) — a kill mid-restore is recovered (:2921ff), but a backup killed mid-`snapshotStore.create` relies on the staging-dir cleanup (:56-58 `finally { staging.deleteRecursively() }`), which is in-process only; a process death mid-copy leaves a `.partial` directory (the `.{$id}.${UUID}.partial` at :29) for the next `list()` to ignore (it filters for `manifest.json`, :115-118) — orphaned bytes that count against P1-8's quotas.
- **Work items:**
  1. Service-layer interlock: `DatabaseService.createNamedBackup` (and the restore Binder legs) first query the supervisor's session state (via `RuntimeSupervisorClient`) and refuse with actionable copy ("Save & Exit before backing up") while any component is not STOPPED; keep the engine-level guards as defense in depth.
  2. Partial-snapshot janitor: at engine init, sweep `snapshotsRoot` for `.*.partial` directories older than a threshold and durably delete them (they are unreferenceable by `list()`), with the sweep recorded in status output so quota accounting (P1-8) stays honest.
  3. Ordered drain helper: expose one `drainForMaintenance()` supervisor operation = the Save&Exit sequence (saveWorld → stop components → require stopped) used by backup/restore/update-revalidate (P2-11) alike, so the interlock has a single blessed path instead of each caller reimplementing ordering.
  4. Wire the S9/F3-6 storm leg: backup requested during a flavor switch is refused until the switch settles (journal phase not READY/STOPPED-stable).
- **Upstream dependency:** none.
- **Verification:** androidTest: backup while playing → refused with the copy; backup after Save&Exit → succeeds; kill -9 mid-backup → restart shows no `.partial` residue after the janitor sweep and quota accounting matches; restore with a live session → refused; restore two-phase kill window → recovers (existing protocol, re-asserted per flavor); unit: janitor and interlock predicate table tests.
- **Effort:** S
- **Depends on:** P2-12 (journal/supervisor state the interlock reads), P1-9 (drain budgets)
- **Owner stage:** new:S9

### P2-a — Donor-machine cvars: `processAffinityMask`/`coresDetected` scrub + full-core SAFE_CONFIG seed (register: "see W1-7")

- **Goal:** Machine-specific client cvars that arrive inside imported client trees (`coresDetected`, `processAffinityMask`, and the hwDetect-derived family) are scrubbed at import **on every flavor** and replaced — **for tbc/wotlk only** — by per-flavor seed values pinning a full-core mask, so a client carried over from a donor device never pins the wrong SoC topology and the vanilla lane's SAFE_CONFIG bytes stay byte-identical. Single program posture, unified with client-data-runtime.md W1-7/W1-10 (review round 1 resolution; this section no longer seeds vanilla).
- **Current state:** The app-owned first-run config is the vanilla SAFE_CONFIG literal: `ClientGenerationStore.kt:232-261` (ends `SET realmName "MaNGOS"`), written over any imported `WTF/Config.wtf` at publish (:155, with the app-owned-files manifest listing `realmlist.wtf`, `WTF/Config.wtf` at :107-111) — so root `WTF/Config.wtf` *is* replaced. But nothing scrubs the rest of the imported WTF tree: donor `WTF/Config.wtf` values survive in `config-cache.wtf`-class files and per-realm/per-char subfolders on the flavors where those exist (register C8: TBC WTF is already 3-level; H4-5 owns the write/snapshot scope) — the client rewrites `Config.wtf` at exit from its live cvar state, which was seeded from the donor files it read at first boot. Neither `coresDetected` nor `processAffinityMask` appears anywhere in app code today (repo-wide grep: only the register/docs hits), i.e. there is no seed and no scrub — the gap is real, not partially done. W1-4's companion finding (catalog must read both `Config.wtf` and `config-cache.wtf`) is the §2.2 side.
- **Work items:**
  1. Add to `ClientFlavor`/`flavor.json` a `machineSpecificCvars` deny-list (vanilla: `coresDetected`, `processAffinityMask`, `hwDetect`; tbc/wotlk re-verified against the qualified clients' written cvar sets — derive from a donor-import QA run, do not assume the sets match) and a `safeAffinitySeed` value (full-core mask computed on-device at seed time, not baked). One scrub posture for all flavors (W1-7 item 1): genuine vanilla trees write none of these keys (1.12-era cvars — the repo-wide app grep returns only docs hits), so the vanilla scrub arm is a no-op and G3-7's golden-byte rule holds **without any exception entry**.
  2. Import scrub (shared lane — W1-7 in client-data-runtime.md owns the identical scrub; this section adopts its posture rather than defining a second one): at publish, `ClientGenerationStore` walks the imported `WTF/` tree (all levels) and strips the deny-listed `SET` lines from every `*.wtf`/`*.txt` file it can parse with the existing codec (`ConfigWtfCodec` per plan §7); unparsable files are left untouched and named in the import report (fail-open on format, fail-closed on known keys).
  3. Seed gating (W1-10 item 2's posture, adopted here — **supersedes this section's original delta-golden, which is withdrawn**): the full-core `processAffinityMask` (computed from `Runtime.getRuntime().availableProcessors()` at seed time) and `SET hwDetect "0"` are written into the **tbc/wotlk seed template only**, never into the shared vanilla `SAFE_CONFIG` literal (`ClientGenerationStore.kt:240`). Seeding vanilla would change SAFE_CONFIG bytes against G3-7 (vanilla-regression-guards.md:157) and the wave-0 "`--flavor vanilla` byte-identical" exit criterion; if vanilla seeding is ever wanted it requires a Decision Log entry amending both in the same commit — not planned.
  4. Save&Exit persistence check (tbc/wotlk): after a session, assert the client's rewritten `Config.wtf` still carries the seeded mask (it should — the client persists what it was given) and that no donor value re-appeared.
- **Upstream dependency:** none (cvar behavior verified against the qualified clients per plan §8.2 items 1-2).
- **Verification:** unit: scrub tests on synthetic WTF trees (donor `processAffinityMask "0x00000003"` in root/per-realm/char-level files → stripped; unparsable file reported not corrupted); golden: **vanilla SAFE_CONFIG output byte-identical to today's bytes** (no delta — the withdrawn seeding is gone); device: import a donor-tree fixture on RP6 → tbc/wotlk first boot runs full-core (verify via the client's own cvar echo through the W1-4 catalog read), 5 boots stable, values persist across Save&Exit; vanilla boot unchanged (no topology keys seeded).
- **Effort:** S
- **Depends on:** none (pairs with W1-7/W1-4 in §2.2, which own the catalog semantics)
- **Owner stage:** new:S7

---

## Coverage ledger

- [x] P1-1
- [x] P1-7
- [x] P1-8
- [x] P1-9
- [x] P1-10
- [x] P2-1
- [x] P2-3
- [x] P2-2
- [x] P2-4
- [x] P2-5
- [x] P2-6
- [x] P2-7
- [x] P2-8
- [x] P2-9
- [x] P2-10
- [x] P2-11
- [x] P2-12
- [x] F3-6
- [x] F3-1
- [x] F3-4
- [x] F3-3/H2-5 — register §1 P0 row, owned by §2.5 per review round 1 (planner/import depth; roadmap CP-5 carrier)
- [x] F3-10
- [x] P2-a

§2.6 covers the app once it stops being one-flavor: 11 IDs across per-flavor identity and isolation (H5R2-1 pinning, F3-5 addon rollback, F3-8 leak-sweep endgame), honest bot/population policy (H5R2-2, F3-7, J1-1), the R7 app-feature-walk items (C3), and multi-device/multi-human operation (W2-1..W2-4). The S9 platform stage hosts it, the bot-policy gaps ride S3, and the device-matrix gaps land as S10 qualification deliverables; the F3-3/H2-5 P0 is explicitly owned by §2.5, not double-owned here.

---

## 2.6 Integration & coexistence (R4: F3, R6: J3, R7: C3)

This area covers what happens when Pocket Realm stops being a one-flavor app: several flavors installed and alternated on one device, bots and products honestly tuned per flavor, and the app's own features (addons, Vulkan drivers, LLM runtime, LAN, first-run, backups) carrying an explicit per-flavor story instead of a silent vanilla assumption. Its gaps group into four themes: **(1) per-flavor identity and isolation** — account/character pinning (H5R2-1), addon rollback vs. the 3-level WTF tree (F3-5), and the endgame migration of every per-flavor constant into its owning table (F3-8); **(2) honest bot/population policy per flavor** — the raid story for ≤5 companions (H5R2-2), the bot map-policy gate (F3-7), and guild formation (J1-1); **(3) app-feature parity stories** confirmed missing by the R7 app-feature walk (C3); and **(4) multi-device / multi-human operation** — DST-time policy (W2-1), the client-trust acceptance (W2-2), cross-device restore (W2-3), and the N≥2-human LAN configuration (W2-4). Every gap here assumes the port plan's S9 "Coexistence & flavor selection" stage (plan §S9) as its platform host; the bot-policy gaps ride the S3 runbook, and the device-matrix gaps land as S10 qualification deliverables. One boundary note: the register §1 P0 row F3-3/H2-5 (roadmap CP-5) is **owned by §2.5** — `out/fix-plan/performance-persistence-import.md` `### F3-3/H2-5` — because its consumers live in the import/planner/prep lanes; the H5R2-1 and F3-8 `Depends on` entries below resolve to that section, and the coverage ledger at the end of this file marks it explicitly rather than double-owning it. Provenance: every file:line citation in this section was verified against the working tree at consolidation time, including then-uncommitted changes; anchor commit `b447ef3` (`b447ef320be7acc317f18a6d984bb647b458313c`) lands exactly that tree. Gate record at that commit, both runs stated so the stamp is re-runnable — pytest figures corrected at review round 3 to match the roadmap §0 Provenance paragraph (the earlier "727 passed / 25 skipped / 0 failed" claim did not reproduce under either invocation and is withdrawn): `python tools/check_repo.py` → `check_repo: OK (1297 tracked files, 0 errors, 0 warnings)`; the **bare suite** `python -m pytest tests/ -q --tb=no -p no:cacheprovider` at the same commit reports **729 passed / 8 failed / 23 skipped** — failing exactly the 8 device/build-artifact lane tests named at `.githooks/pre-commit:20-30` (test_gladio_client_unpack_transport ×2, test_vortek_lifecycle_hardening ×4, test_vortek_winlator_baseline ×2), not plan-anchored tests; the **hook's documented selection** (`PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python -m pytest tests/ -q` with those same 8 ids deselected, `.githooks/pre-commit:20-30`) reports **729 passed / 23 skipped / 8 deselected / 0 failed** — the hook's gate is green exactly when its documented deselects apply, and that hook-selection run is the commit-time pre-commit output; at verification time `ClientFlavor.kt`, `ClientFlavorTest.kt`, `FlavorManifestParityTest.kt`, the plan documents, and the `check_flavor.py`/`leak_sweep.py` gates were still untracked in the repo (`out/` remains untracked).

---

### H5R2-1 — Cross-flavor character pinning

- **Goal:** Each flavor owns its own realm account and character roster end-to-end — the app's stored account identity is keyed per flavor, provisioning resolves per flavor, cross-flavor sharing is impossible by construction, and the optional one-way character-copy tool is recorded as a later, separate decision.
- **Current state:** Databases are already per flavor (`ClientFlavor.kt:67-72` vanilla `classic*`, `:98-103` tbc `tbc*`, `:126-131` wotlk `wotlk*`), but the app stores exactly **one** account identity: `UserAccountStore.kt:31-44` `save(username, password, accountId, gmLevel)` with `SCHEMA = 2` and no flavor field (`UserAccountStore.kt:129`), and `SinglePlayerCredentialStore.kt:33-34` `markProvisioned(credentials, accountId)` likewise. The accountId is consumed by the auto-login proof check (`AutoLoginPolicy.kt:64` `accountId == expectedAccountId`) and displayed via `world_runtime.cpp:431`. Accounts are created per active world via the console relay (`world_runtime.cpp:271-289`, `account create <u> <p>`); `account.expansion` gates expansion (`native/cmangos/sql/base/realmd.sql:58` — `expansion tinyint(3) unsigned NOT NULL DEFAULT '0'`). Today only vanilla is qualified (`ClientFlavor.kt:145`; the D9 `qualified().single()` loud-fail pattern), so the landmine is invisible: when TBC qualifies, provisioning the same username into `tbcrealmd` yields a *different* auto-increment id and `expansion=1`, and the single stored accountId can only ever match one flavor — the auto-login proof then fails confusingly on the second flavor (fail-closed, but with no per-flavor recovery path). Character data is per-flavor by DB separation; no migration/copy path exists at all.
- **Work items:**
  1. **Schema-stable flavor key (G3-1 discipline).** `UserAccountStore` keeps `SCHEMA = 2`. Add an optional `"flavor"` string (read via `value.optString("flavor", "vanilla")` in `readLocked`, `UserAccountStore.kt:70-74` — the optLong/optInt pattern is already there) and an optional `"accountIds"` object mapping flavor → accountId. Readers accept both old (field absent = vanilla) and new records; quarantine only on *invalid* values, never on absence. `writeLocked` (`:77-96`) emits the new fields only when non-vanilla so a vanilla-only install stays byte-identical (G3-7 golden test).
  2. Mirror the same optional field in `SinglePlayerCredentialStore` (flavor-keyed accountId map; `markProvisioned` gains a `flavor: String = "vanilla"` default parameter so existing call sites compile unchanged).
  3. **Per-flavor provisioning flow.** Provisioning already runs against the active world's realmd (`DurableRuntimeSupervisor.provisionAccount`, `DurableRuntimeSupervisor.kt:475-507`); add: after `backend.provisionAccount` returns, persist the returned id under the *session's* flavor key, and make `AutoLoginPolicy`'s `expectedAccountId` resolve through the same per-flavor map (not a single field). The S8 expansion flag (`account set addon`, F1-10) is set at provisioning time per flavor — if the cmangos console op name differs on the tbc/wotlk branches (not verified in this session), fall back to a direct `LoginDatabase` `UPDATE account SET expansion = N` in `world_runtime.cpp` next to `account_info` (`:320-338`), which already queries that DB in-process.
  4. **Fail-closed pinning.** A flavor's runtime may never consume another flavor's accountId: the launch spec already carries the flavor (post-F3-3 threading); assert the resolved account row exists in *that* flavor's realmd before client start, else surface `ACCOUNT_NOT_PROVISIONED_<FLAVOR>` with a provisioning action.
  5. **Honest UI copy.** `AccountStatusPresentation.kt` and the S9 flavor-switch card (H4R2-8) state per-flavor rosters and that characters do not travel between flavors.
  6. **Optional one-way copy tool — deferred, recorded.** A vanilla→TBC character exporter needs a per-expansion characters-table column map (e.g. WotLK's `knownTitles`, register J2-4). Record as a Decision Log entry with trigger conditions (user demand); do not build in this program without a signed decision.
- **Upstream dependency:** none (app-side). The per-branch expansion flag op rides S8/F1-10; the `account set addon` console spelling on mangos-tbc/wotlk must be verified at S8 before use.
- **Verification:** androidTest: provision on vanilla lane → boot tbc lane (test fixture, tbc unqualified so this runs at RA exit) → `accountExists=false` → provision → both stores resolve distinct ids; DB assertion `SELECT id, expansion FROM account` in `classicrealmd` vs `tbcrealmd` returns matching-username rows with expansion 0 vs 1. Device (S9 marquee scenario, plan §S9 End State): alternate flavors 10×; each lane auto-logs in 5/5; zero cross-flavor account bleed. Golden test: vanilla-only `account.json` byte-identical to pre-change.
- **Effort:** M
- **Depends on:** G3-1 (optional-field shim pattern), F1-10 (expansion relay op, S8), F3-3/H2-5 (ClientFlavor threading through the import/planner/prep lanes — owned by §2.5: `out/fix-plan/performance-persistence-import.md` `### F3-3/H2-5`; this section's per-flavor account store consumes its threaded flavor parameter)
- **Owner stage:** S9 (new work item added to plan §S9.2's per-flavor durable-state namespace item)

---

### H5R2-2 — Honest raid story (10/25 content with ≤5 companions)

- **Goal:** Decision point 3 (register §4) is recorded and implemented: TBC/WotLK raid content is either run under-manned with an explicit, per-flavor scaling configuration, or staffed by a bot raid group — whichever the product owner signs — and one raid boss per flavor is cleared on device as acceptance.
- **Current state:** The companion model is a party of ≤5 (the `playerbotConfig()` emission in `BotProfiles.kt` sets `AiPlayerbot.RandomBotGroupNearby = ${if (groupNearby) 1 else 0}` — line 201 at anchor commit `b447ef3` (`b447ef320be7acc317f18a6d984bb647b458313c`), which lands the working tree this plan verified; `git show b447ef3:android/app/src/main/java/com/pocketrealm/bots/BotProfiles.kt` reproduces both lines; client party cap 5). Raid behavior is hard-off: the same block emits `AiPlayerbot.RandomBotRaidNearby = 0` (`BotProfiles.kt:202`, same anchor — grep fallback: both emitted keys are unique strings in the tree, so the citation survives refactors), while upstream default is `true` (`native/playerbots/playerbot/PlayerbotAIConfig.cpp:538`), and the emitted block's doc comment pins the design: "battleground/arena/guild growth stay disabled by design" (`BotProfiles.kt:135-137`). No raid-scaling knob is emitted at all. Two levers exist and are verified in this repo: (a) **scaling config** — the core's `Rate.Creature.{Normal,Elite,RAREELITE,WORLDBOSS}.{Damage,SpellDamage}` (+ health) family in `native/cmangos/src/mangosd/mangosd.conf.dist.in:1009-1020` is a flavor-global multiplier set the conf generator currently never touches; (b) **bot raid group** — the pinned playerbots tree parses `RandomBotRaidNearby` (`PlayerbotAIConfig.cpp:538`), so per-flavor re-enablement is a conf change plus invite/convert wiring. Boss-script coverage deficits are *not* this gap (ICC Gunship 20% / LK 5% are G1-4/G1-5; strategy inventory is H3R2-2).
- **Work items:**
  1. Record the product decision (register decision point 3) in `docs/DECISIONS-PORT.md`: (a) scaling config, (b) bot raid group, or (c) documented under-manning with named cuts. The register's honest-UI principle forbids shipping 10/25 content silently broken.
  2. **If (a):** add a per-flavor `raidScaling` block to `schemas/flavor.json` `bot_policy` (or a sibling `combat_policy`), project it through `ClientFlavor` (S0 registry), and emit `Rate.Creature.*` lines from `ServerRuntimeFiles.worldConfig` (`ServerRuntimeFiles.kt:120`+) only for flavors that opt in — vanilla emission must stay byte-identical (G3-7 golden conf test). Pick values by measured boss-HP/damage vs. a 5-character group's realistic DPS (tune against Karazhan Attumen / Naxx Spider-wing trash first).
  3. **If (b):** add a "raid night" preset: `RandomBotRaidNearby = 1` for the preset only, plus explicit raid conversion of the companion group; verify the pinned playerbots raid-invite path actually supports player-initiated raid invites with bots (grep the pinned tree at implementation time; `RandomBotRaidNearby` gates proximity raid join, not invite acceptance — if acceptance is missing, this lever's cost jumps and the decision should revert to (a)). Keep the 8×4 action model and 5-slot party UI untouched (H4-2 owns vehicle/override bars).
  4. **Honest copy** either way: BotsScreen/raid content cards state the manning truth (e.g. "Karazhan: 10-player raid — played here with scaling" / "…with a bot raid group"), per the register's parity-plan wording.
  5. Soak note: the chosen lever must be covered by the admission controller's existing sample loop (`BotAdmissionController.kt:35` `observe(sample)`) — a raid preset raises per-tick load; confirm no admission oscillation during a raid attempt.
- **Upstream dependency:** pinned playerbots `puzzled-pancake/playerbots@7e2cd2fb` (`native/playerbots`, `.gitmodules`; `randomBotRaidNearby` at `PlayerbotAIConfig.cpp:538`); `Rate.Creature.*` keys on the mangos-tbc/mangos-wotlk branches (verify each flavor's `mangosd.conf.dist.in` at S2 — only `native/cmangos` classic was checked here).
- **Verification:** Device: clear one TBC raid boss (Karazhan Attumen or similar) and one WotLK raid boss with the chosen lever, mangosd log free of script errors, boss kill credited. CI: per-flavor conf staging test (emitted `Rate.Creature.*` matches flavor.json; vanilla golden bytes unchanged); if (b), an androidTest that the raid preset emits `RandomBotRaidNearby = 1` and the normal presets still emit `0`.
- **Effort:** M (lever a) / L (lever b)
- **Depends on:** H3-1 (bots pin/build), H3R2-2 (strategy inventory defines which raid content is attempted), product sign-off on decision point 3; G1-4/G1-5 own the ICC-specific scripts this story exercises
- **Owner stage:** S3 (new:S3 work item in the runbook's bots stage; decision recorded at S3 entry)

---

### F3-5 — Addon rollback × 3-level WTF (retirement sweep + binding-repair scoping)

- **Goal:** Removing or rolling back an addon sweeps that addon's SavedVariables at every WTF scope level the flavor defines (account / per-realm / per-character), binding-repair journals never replay across flavors, and the vanilla lane's addon behavior is byte-for-byte unchanged.
- **Current state:** Addon state is one global namespace: `AddonRepository.kt:36-40` — `noBackupFilesDir/addons` with `registry.json`, `registry.previous.json`, `registry.transaction.json`. `rollback()` (`AddonRepository.kt:206-231`) and `remove()` (`:186-204`) swap the registry and reconcile the Android Port preset, but never touch the WTF tree; `AddonRuntimeProjector.kt:22`+ projects owned `Interface/AddOns` folders only, and its header comment (`:26-29`) treats a mistaken SavedVariables retirement as a bug to avoid. That is correct for vanilla's single-file account uvars and harmless at one flavor — but TBC/WotLK multiply the SV surface (register C8: TBC is already 3-level per-realm/per-char; WotLK adds the char-level cache-file set), so a retired addon leaves per-char SV files behind in every character directory, which then load stale-format state into a reinstall. The WTF helpers are still build-5875-pinned (`InGameSettingsFiles.kt:12-16`: account uvars `WTF/Account/<name>/SavedVariables.lua`, account/char `bindings-cache.wtf`). Binding repair is owned-binding-scoped with a journal (`AndroidPortBindingRepair.kt:15-19`, scopes journal `:63-99`) — its scope keys must survive the per-flavor prefix segments (`wow5875`/`wow8606`/`wow12340`, `ClientFlavor.kt:74/105/133`).
- **Work items:**
  1. **Per-flavor WTF layout resolver** (builds on H4-5/H4-6): extend `InGameSettingsFiles` with layout resolvers selected by the managed client's flavor — vanilla keeps today's paths; TBC/WotLK add the per-realm/per-char `SavedVariables/<Addon>.lua` directories (and WotLK char cache files, which H4-5 owns). No vanilla path changes.
  2. **Retirement sweep with journal discipline:** when a registry diff (install/remove/rollback) retires addon folder *A*, enumerate and delete *A*'s SV artifacts at every level the flavor's resolver defines, under the same fsync-before-touch journal/recovery discipline `AndroidPortBindingRepair` uses (`AndroidPortBindingRepair.kt:15-19`). Vanilla policy: keep current behavior (account-uvar keys of removed addons may remain) unless the product call changes it — record in Decision Log.
  3. **Binding-repair scoping:** key the repair journal and ownership lookups so a journal produced under one flavor's client prefix cannot replay against another flavor's WTF; a cross-flavor replay is a logged no-op. Verify the existing scope keys are prefix-distinct (they key on client-root paths, which differ per `winePrefixSegment`) and add an explicit test for it.
  4. **Rollback interlock with S9 namespaces:** once addon state is per flavor (plan §S9.2), `canRollback()`/`rollback()` (`AddonRepository.kt:206,250`) restore *that flavor's* previous set only; the global `registry.previous.json` becomes per-flavor namespaced files with the current names as the vanilla alias during migration (S1-style aliasing).
  5. **Tests:** androidTest with synthetic 3-level WTF fixtures per flavor (SyntheticClientArchives-style): retire addon → SVs gone at account + char level; reinstall → clean first-load; corrupt-journal recovery proven by R2; vanilla fixture set proves zero behavior change (G3-10).
- **Upstream dependency:** none (app-side).
- **Verification:** androidTest matrix above green; device run: install a TBC addon (e.g. pfQuest-Reforged from the H4-3 catalog), create two characters, remove the addon, log in both — no stale SV files remain under `WTF/Account/<name>/<realm>/<char>/SavedVariables/`, bindings intact; vanilla regression: existing addon lifecycle tests (AddonArchiveValidatorTest, projector tests) unchanged-green.
- **Effort:** M
- **Depends on:** H4-5 (WTF scope beyond SavedVariables, S7), H4-6 (per-character keybindings codec scoping), plan §S9.2 (per-flavor addon namespaces)
- **Owner stage:** S7 (resolver + sweep land with the flavor's client runtime stage) then S9 (namespace interlock)

---

### F3-7 — Bot map-policy gate (botRandomMaps ⊆ flavor maps; Dalaran no-fly; vanilla-zone-bots decision)

- **Goal:** The emitted `AiPlayerbot.RandomBotMaps` value is provably a subset of the flavor's actually-prepared maps (fail-loud at staging when not), the vanilla-zone-bots population decision is recorded as per-flavor manifest data, and no-fly/city zones (Dalaran on 571) cannot strand bots.
- **Current state:** Emission is already registry-driven: `BotProfiles.kt:148` `AiPlayerbot.RandomBotMaps = ${flavorBotRandomMaps()}` with `botRandomMaps` = `[0,1]` / `[0,1,530]` / `[0,1,530,571]` (`ClientFlavor.kt:83/111/139`) resolved through the loud-fail `qualifiedFlavor()` (`BotProfiles.kt:230`). What is missing is the **gate**: nothing checks the emitted ids against the prepared data — `DataPreparationStore.kt:44-45` only *counts* files (`MIN_DBC = 100`, `MIN_MAP_TILES = 100`, `:420-421`) and `PreparedDataStore.kt:101` checks directory presence (`REQUIRED_DIRS = dbc/maps/vmaps/mmaps`). The in-tree default is the WotLK set — `native/cmangos/src/modules/PlayerBots/playerbot/PlayerbotAIConfig.cpp:194` `GetStringDefault("AiPlayerbot.RandomBotMaps", "0,1,530,571")` — so a conf that ever *omits* the key silently imports the all-flavor default. The "vanilla-zone-bots" question (does a TBC realm populate Kalimdor/Eastern Kingdoms with bots?) is currently answered implicitly by the registry lists, never recorded as a decision. Dalaran (map 571 city, no-fly) is unhandled: `EnableRandomTeleports`/`RandomBotTeleportNearPlayer` (`BotProfiles.kt:197-199`) can drop a bot into a no-fly/city cell.
- **Work items:**
  1. **Staging-time subset gate.** In the conf emission path (`ServerRuntimeFiles.worldConfig` / the bot-conf writer, `ServerRuntimeFiles.kt:123`+), before emitting `RandomBotMaps`, verify each id *n* has `maps/Map%04u.map` in the active prepared-data root; a missing map aborts staging with a fail-loud error naming the file (same discipline as plan §S3.6's unknown-key abort). This turns the ⊆ relation into a runtime-checked invariant instead of a hope.
  2. **Defang the in-tree default:** the key is always emitted explicitly today; add a staging assertion that the emitted conf contains a `RandomBotMaps` line (present-value, not absent-key) for every flavor, and note in the flavor manifest that `bot_policy.random_maps` is the sole owner of these ids (feeds F3-8).
  3. **Record the vanilla-zone-bots decision** as data: keep or change `random_maps` per flavor in `schemas/flavor.json` `bot_policy` (vanilla block `:44-48`; tbc `:118`; wotlk `:179` — `characters_per_account: 9` sits next to it) with an explicit note ("vanilla continents populated for village/market ambience" vs. "expansion zones only"), signed off as a Decision Log entry. Emission stays registry-driven, so the change is data-only.
  4. **No-fly safety:** either exclude city/no-fly subzones from the teleport-near-player target selection (core-side zone check in the bots tree) or document the known limitation and cap `RandomBotTeleportNearPlayerMaxAmountRadius`; add a soak probe (H3-3's travel soak) asserting no bot parks in Dalaran/no-fly cells after teleport events on 571.
  5. **Tests:** unit test — synthetic prepared data lacking `Map0530.map` → TBC staging aborts naming the file; synthetic data with all maps → conf emits exactly the registry list; golden conf test — vanilla emission byte-identical (G3-7).
- **Upstream dependency:** none for the gate (map-id set is owned by `schemas/flavor.json` `bot_policy.random_maps`). The zone-exclusion option (item 4) would be a pinned-playerbots patch — `puzzled-pancake/playerbots@7e2cd2fb` or the per-flavor bots trees, only if chosen over documentation.
- **Verification:** the negative staging test above (gate fires, error names the missing map); TBC device soak (1 h, H3-3 style) with bots leveling inside 530 and zero bot activity on 571; vanilla conf golden test green; `python tools/leak_sweep.py` still clean (map ids live in the owning manifest).
- **Effort:** S
- **Depends on:** H3-1 (bots build for the flavor), F3-8 (owning-table placement of map ids). The S6 edge is scoped, not blocking: the gate's *emission-side* check (`botRandomMaps` ⊆ flavor maps, both sides from the S0 registry/`flavor.json`) lands at S3 with no S6 input; the S6 prepared-data manifests (per-map inventory from the extraction; H2-10/H2-11 floors) feed only the *adversarial/verification* leg — the cross-flavor negative staging test — which wave S6's "prepared-data flavor gates both directions" already carries. This S3(emission)/S6(verification) split mirrors H3-3/H5-9/P1's straddle resolution and is annotated in the roadmap duality note.
- **Owner stage:** S3

---

### F3-8 — Leak-sweep endgame: owning-tables-only at S10 DoD

- **Goal:** By the S10 Definition of Done, every per-flavor constant lives in its owning table (`schemas/flavor.json`, the `ClientFlavors` registry, per-flavor data files) and `tools/leak_sweep.py`'s ALLOWLIST contains only permanent owning-table/prose classes — zero stage-scoped "Sx retires" entries remain, and a new DoD check enforces that forever.
- **Current state:** The sweep exists and is green (reproduced this session at the anchor commit: `python tools/leak_sweep.py` → `leak_sweep: OK (1114 files scanned, 961 allowlisted findings)` — the S0-exit run at the pre-landing state reported 1104 files / 725 findings; the anchor commit tracks the plan documents, whose prose carries allowlisted literals): banned literals and normalized numbers (`tools/leak_sweep.py:47-67`+) are checked against an ALLOWLIST of **84** entries, each carrying a `retires` field (`:135`+). Count method (loader-based, re-runnable — a raw line-grep miscounts because one entry's `retires` value is written across three lines): `python -c "import sys; sys.path.insert(0,'tools'); import leak_sweep; print(len(leak_sweep.ALLOWLIST))"` → **84**. Distribution, by the head of each entry's `retires` field (same session): **35** begin "S4 retires" (34 plain + 1 combined "S4 retires (DB names); 5875 retires at S8"), **26** "S5 retires", **12** "S7 retires", **2** "S8 retires" (1 plain + 1 combined "S8 retires (5875 auth frames); classicrealmd retires at S4"), **8** permanent "never …" classes, and **1** gated on code migration ("when the last comparator migrates to ClientFlavors (drift guarded by ClientFlavorTest)" — the multi-line entry whose `"retires": (` opens at `:150`); 35+26+12+2+8+1 = 84. A raw `grep -c '"retires":' tools/leak_sweep.py` returns 85: the 84 entry occurrences plus the JSON-writer's `"retires": entry["retires"]` at `:914` — exactly one non-entry hit. The self-check retires stale entries automatically (`:36-40`). What the endgame must still retire: (1) the D9 `ClientFlavors.qualified().single()` loud-fail sites — `BotProfiles.kt:230`, `WineRuntimeStore.kt:660`, `DataPreparationStore.kt:267,296`, `InGameSettingsEditor.kt:90`, `PreparedDataStore.kt:39`, `AndroidRuntimeBackend.kt:720,1121`; (2) the D15 duplicated constants — `BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT = 9` (`BotPopulationPolicy.kt:27` vs `flavor.json` `characters_per_account`, present for all three flavors at `:47/:118/:179`) and `ServerRuntimeFiles.LORE_CARDS_FILE_NAME` (`ServerRuntimeFiles.kt:327-328` vs `flavor.json` `llm.lore_cards`, `:71-72` vanilla / `null` tbc+wotlk `:132-133,:193-194`).
- **Work items:**
  1. **Per-stage retirement (RA/RB runbook):** at each stage exit, migrate that stage's allowlist entries into their owning tables and let the stale-entry self-check (`leak_sweep.py:36-40`) confirm deletion — S4 DB routing, S5 identity constants, S7 datasets/UX copy, S8 auth frames, exactly per the D18a accounting.
  2. **S9: retire D9.** Thread the flavor parameter through the eight listed `.single()` sites, module by module with tests after each (the S0 threading-audit method, plan §S0.4); flavor resolves from the launch/session spec, not the registry singleton. `single_qualified_lane` (D14) retires with it.
  3. **S9: fold duplicated constants** — `characters_per_account` and `lore_cards` read from a `ClientFlavor` projection (extend the registry data class); delete the Kotlin constants or derive them at init; per D15 items 5-6.
  4. **S10 DoD assertion:** extend `tools/check_flavor.py` (which `tools/check_repo.py` already gates, D12) with an `--owning-tables-only` mode: fail if any ALLOWLIST entry's `retires` does not start with `"never"` — this also catches the one comparator-gated entry (`:150`), which retires with item 2's D9 work; wire it into the S10 End State checklist. Negative test: temporarily re-adding an "Sx retires" entry fails the gate.
  5. Keep the D18a exemptions that are permanent by design (docs prose, owning-table tests, vendored `native/vanilla-tweaks/`) — the sweep stays a tripwire, production gates stay code-level (D13).
- **Upstream dependency:** none.
- **Verification:** run `python tools/leak_sweep.py` (clean) and `python tools/check_flavor.py --owning-tables-only` at S10 — pass; the negative test in item 4 demonstrably fails while any "Sx retires" entry exists; full `check_repo` chain green in CI; grep of the eight D9 sites returns no `qualified().single()` outside the registry module.
- **Effort:** M (spread across stage exits; the S9 threading is the bulk)
- **Depends on:** F3-3/H2-5 (ClientFlavor threading — owned by §2.5: `out/fix-plan/performance-persistence-import.md` `### F3-3/H2-5`; prerequisite for the D9 retirement in item 2), D9/D14/D15 retirement plan, each stage's own exit gate
- **Owner stage:** S9/S10

---

### J1-1 — Guild formation for the player (see H3R2-9)

> **DP9 owner.** This section owns decision point 9's mechanism of record (option set A/B/C below — no new fork patch or `PocketRealm.*` conf surface is required; `allowGuildBots` already gates the player-guild chain). `bots-llm.md` J1-1 is a cross-reference carrying the S3-side duties only (per-flavor key verification on the tbc/wotlk trees, H3R2-1 matrix registration of whichever lever ships, H3R2-9 keep-off fence).

- **Goal:** Decision point 9 (register §4) is recorded and implemented: the player can form a guild through a sanctioned bot-signature lane, a conf lever, or an honest out-of-scope copy — with the bot self-organization keys (form/count) still proven off per flavor.
- **Current state:** The app forces the whole guild-growth family off: `BotProfiles.kt:159` `GuildOrderAlwaysActive = 0`, `:185` `AllowGuildBots = 0`, `:191-192` `RandomBotFormGuild = 0` / `RandomBotGuildCount = 0` — against upstream defaults `true` / `true` / `20` (`native/playerbots/playerbot/PlayerbotAIConfig.cpp:620/:503/:191`), and the emitted block's doc pins "guild growth stays disabled by design" (`BotProfiles.kt:135-137`). `allowGuildBots` specifically gates bots sitting in the *master's* guild (`native/playerbots/playerbot/PlayerbotMgr.cpp:540`). Core facts: a charter needs **9 signatures** — `native/cmangos/src/game/Entities/PetitionsHandler.cpp:266-267` sends `m_minSignatures = 9; m_maxSignatures = 9;`, and turn-in checks `CONFIG_UINT32_MIN_PETITION_SIGNS` (`PetitionsHandler.cpp:558`) defined as `setConfigMinMax(CONFIG_UINT32_MIN_PETITION_SIGNS, "MinPetitionSigns", 9, 0, 9)` (`native/cmangos/src/game/World/World.cpp:589`) — i.e. a solo-friendly `MinPetitionSigns=0` lane already exists as a conf key. The pinned playerbots tree ships the full player-guild chain: `PetitionSignAction.cpp:1-40` (signs petitions; the petition-type query is `#ifndef MANGOSBOT_ZERO`), `GuildCreateActions.cpp` (`BuyPetitionAction` `:75`, `PetitionTurnInAction` `:298`, `BuyTabardAction` `:349`), charter item 5863 checked in `GuildValues.h:274`. The in-tree legacy module has its own duplicate guild actions (`native/cmangos/src/modules/PlayerBots/.../GuildCreateActions.cpp:320`) — the C4/H1-15 in-tree-module disable is what keeps this from forking behavior.
- **Work items:**
  1. **Record the decision** (decision point 9): **A** — sanctioned bot signatures: set `AllowGuildBots = 1` so playerbots may sign the player's charter while `RandomBotFormGuild`/`RandomBotGuildCount` stay 0 (bots never self-organize); **B** — conf lever: emit `MinPetitionSigns = 0` in the flavor's `mangosd.conf` so a solo charter turns in without signatures (simplest, least blizzlike); **C** — honest out-of-scope copy ("guild charters need 9 players; not available in this offline realm").
  2. **If A:** add a `guildSignatures` knob to `BotProfiles` (default per Decision Log) that flips only `AllowGuildBots`, per flavor. **Rate limit (the constraint roadmap DP9 references):** signings are player-initiated only — a bot signs when the *player* shows it the petition (`CMSG_PETITION_SHOW_SIGNATURES` → `PetitionSignAction`), never autonomously; if on-device testing shows nine near-simultaneous signings bursting (teleport/instance-creation storm), add a per-bot signing cooldown (≥2 s stagger) in the pinned bots tree rather than a new conf key — and if even the player-initiated flow proves disruptive, that is the recorded trigger to fall back to option B. Audit the rest of the guild-key family against the flavor's playerbots conf dists (H3R2-9's keep-off audit: `CreateRandomGuilds`, `GuildBank`, `BuyGuildBankTab`); the economy side (bank-tab gold) stays inside H1R2-7's gold-wall tuning. Verify the key spellings on the tbc/wotlk playerbots trees at S3 — only the classic tree was grepped here.
  3. **If B:** emit `MinPetitionSigns = 0` from `ServerRuntimeFiles.worldConfig` for opted-in flavors with the G3-7 golden-byte rule for vanilla; verify the key exists in mangos-tbc/wotlk `World.cpp` at S2 (register expects it; do not assume).
  4. **If C:** AddonsScreen/BotsScreen/guild-flow copy only; no conf change.
  5. All options: the fail-loud conf check (plan §S3.6) guards unknown keys; guild-bank *schema* is H1R2-6's gap, not this one — this gap only wires formation.
- **Upstream dependency:** pinned playerbots `puzzled-pancake/playerbots@7e2cd2fb` (`PetitionSignAction.cpp`, `GuildCreateActions.cpp`, `PlayerbotAIConfig.cpp:191,503,620`); cmangos `MinPetitionSigns` config (`native/cmangos/src/game/World/World.cpp:589`) — per-branch existence to be confirmed at S2.
- **Verification:** per chosen option on device: **A** — player buys [Guild Charter] (item 5863), nine bots sign via `PetitionSignAction`, `PetitionTurnInAction` succeeds, guild exists in the characters DB, and the keep-off audit still shows `RandomBotFormGuild = 0`/`RandomBotGuildCount = 0` in the staged conf; the signing log shows player-initiated signings only (no autonomous signing), with the pacing acceptable or the item-2 cooldown applied; **B** — solo charter turns in with `MinPetitionSigns = 0` staged; **C** — R6 copy review. Plus: golden conf test (vanilla bytes unchanged unless the decision changes vanilla too).
- **Effort:** S (options B/C) / M (option A)
- **Depends on:** H3-1 (bots pin/build), H3R2-9 (per-flavor keep-off conf audit), H1-15/C4 (in-tree PlayerBot disabled so the playerbots submodule's actions are the only live path)
- **Owner stage:** S3 (decision point 9; new:S3 work item)

---

### C3 — App-feature per-flavor stories (umbrella)

- **Goal:** Every app-level feature the R7 app-feature walk flagged gets an explicit per-flavor story — implemented or recorded as deliberately flavor-independent with rationale: Vulkan driver catalog/quarantine per graphics lane, LLM model registry/prompt-pack/endpoint scoping, LAN join flavor routing, per-flavor first-run replay, and battery-gated chatter under per-flavor run dirs. **Plan of record for the C3 umbrella:** §2.4's `### C3-walk` (ux-addons-controls.md) is this gap's S7 UX/device-surface execution half — its Vulkan/LLM/LAN/first-run items consume work items 1-4 below by reference, and the Decision Log entries in items 1-2 are the single home for the sub-story decisions (roadmap §0 ID-collision conventions, C3/C3-walk bullet).
- **Current state (all verified this session):** **Vulkan** — driver state is global: `UserVulkanDriverRegistry.kt:73-77` (`<filesDir>/drivers/registry.json`) and quarantine is a single driver flag (`UserVulkanCrashGuard.kt:20`, "quarantined after 2 early crashes"), so a driver that crashed the vanilla *OpenGL* lane is quarantined for the TBC/WotLK *DXVK d3d9* lane too — evidence that says nothing about the other lane; catalogs are `schemas/vulkan-driver-catalog.json` + `schemas/community-vulkan-drivers.json`. **LLM** — `LlmModelRegistry.kt` is one global registry with per-tier `LlmTierProfile`s; `flavor.json` already carries per-flavor `llm.lore_cards` (vanilla `"lore_cards_v112.jsonl"` `:71-72`, tbc/wotlk `null` `:132-133,:193-194`) but `ServerRuntimeFiles.kt:327-328` duplicates the filename (D15 item 5); the external endpoint is user-global (`LlmRuntimePolicy.normalizeExternalEndpoint`, `LlmRuntimePolicy.kt:111`); there is no NPU code path at all (repo grep "NPU" matches nothing relevant). **LAN** — host/join works (`LanScreen.kt:61`+; `RealmService.kt:180-205`), but join is hard-wired to `AndroidRuntimeBackend.INTEGRATED_PROFILE` (`RealmService.kt:194`) with no flavor selection/validation, and the realmlist projection is flavor-blind (`ClientRealmEndpointProjection.kt:10`). **First-run** — one seal for the whole app (`FirstRunTutorial.kt:120` `tutorialSealWrite(setupComplete, dismissed)`); importing flavor #2 replays nothing. **Chatter** — the battery dim exists (`ChatterPowerMonitor.kt:28-30`, `LOW_BATTERY_PCT = 15`) and writes `chatter-power.txt` into the single `noBackupFilesDir/server/run` dir (`ChatterPowerMonitor.kt:56-59`), which S9's per-flavor namespaces will split.
- **Work items:**
  1. **(S7) Vulkan quarantine per lane:** key quarantine state by (driverId, graphics route) — an optional field on the driver record (compatible registry migration, absence = current behavior), or a per-lane quarantine map; a quarantine recorded under `opengl`/`virgl` must not block the `dxvk` route (D3 makes DXVK the only route for tbc/wotlk, so this matters most at the RA/RB boundary). Update `docs/wiki/Choosing-a-Vulkan-Driver.md` per flavor (S10 doc sweep already names it).
  2. **(S3/S9) LLM story:** bind `llm.lore_cards` from the manifest (retires D15 item 5), per-flavor prompt-pack identity (`LlmPromptPack.kt:235`, rides the S3 corpus work F3-9/H3-7), and record in the Decision Log that the *model registry stays flavor-independent by design* (bot brains serve all flavors; tier profiles are device-derived) and the external endpoint stays user-global — with `LlmScreen` showing the active flavor's corpus/prompt identity. NPU: record "no NPU path exists; out of scope" rather than inventing one.
  3. **(S7/S9) LAN join per flavor:** the host session's flavor is exposed with the endpoint; `LanScreen`'s join pane shows "host runs WotLK — launch your wow-3.3.5a-12340 client"; join launch resolves a flavor-matching managed client via `ClientFlavors.isManagedClientId` (`ClientFlavor.kt:159-160`) and refuses a mismatch with actionable copy *before* any Wine start. Realmd build refusal (S8.4) remains the protocol-level backstop.
  4. **(S9) First-run replay:** the seal becomes a per-flavor map — vanilla keeps the existing key semantics byte-compatible (G3-1/G3-7); each newly qualified flavor gets its own first-run cards (plan §S9.6 "per-flavor 'what you need' text").
  5. **(S9) Chatter:** `ChatterPowerMonitor.powerFile` (`ChatterPowerMonitor.kt:56-59`) resolves through the active flavor's run namespace; the `refreshOnce` call at world start (`ServerRuntimeFiles.kt:238`) already re-stages per session, so only the path needs to follow the namespace split.
- **Upstream dependency:** none (app-side throughout).
- **Verification:** androidTest per sub-story: quarantine lane matrix (crash recorded on opengl → dxvk route unaffected, and vice versa); join-mismatch refusal before Wine start; first-run seal per flavor (vanilla record untouched); power-file path per flavor namespace. Device: import a 2nd flavor → per-flavor first-run card shows; TBC LAN host + vanilla joiner refused cleanly at the UI, matching-flavor joiner reaches char select (pairs with S8's auth matrix).
- **Effort:** L (five sub-stories, individually S-M)
- **Depends on:** plan §S9.2 (per-flavor namespaces), H4R2-8 (flavor-switch card), S8.4 (flavor advertisement at realmlist/auth), D15 items 5-6, F3-8 (owning-table discipline for the new manifest fields)
- **Owner stage:** S7/S9

---

### W2-1 — Device-time-as-realm-time DST policy + event-schedule test

- **Goal:** A recorded policy pins what realm time is relative to the device clock, the world/realmd processes run under that pinned time zone so DST transitions cannot shift event windows, and an automated test proves a seasonal event fires at the policy's stated time across a device time-zone change.
- **Current state:** Realm time *is* the device wall clock: `m_gameTime = time(nullptr)` in the world update path (`native/cmangos/src/game/World/World.cpp:125, 1342, 2054`), with no time-zone pinning anywhere in the runtime — greps for `TZ`/`setenv`/timezone over `native/realm-runtime/src/*` and `AndroidRuntimeClock.kt` return nothing — and no time-related key in the generated confs (`ServerRuntimeFiles.realmdConfig`/`worldConfig`, `ServerRuntimeFiles.kt:53-70,120+`). Consequences on a device-local-TZ clock: the DST "fall back"/"spring forward" hour moves realm time twice a year, shifting `game_event` schedule boundaries, uptime bookkeeping, and (once WotLK lands, H1-11) the calendar UI. The classic `mangosd.conf.dist.in` has no TZ key either (grep over `native/cmangos/src/mangosd/mangosd.conf.dist.in` — only Save/uptime-related "time" hits).
- **Work items:**
  1. **Record the policy** in `docs/DECISIONS-PORT.md`: recommended — world and realmd processes run in **UTC** (stable event windows, blizzlike reset math, immune to DST), while the *client* keeps device-local time for its own clock display; user-facing copy states "the realm runs on server time (UTC)".
  2. **Implement the pin** in one place: `setenv("TZ", "UTC", 1)` at native process init in `native/realm-runtime/src/server_common.cpp` (before the mangosd/realmd entry paths run) — identical for all flavors; if Android's TZ cache interferes, add `tzset()` after the setenv.
  3. **Event-schedule test:** seed a test `game_event` row with a near-future UTC start; host/device test asserts the event activates inside the expected window while the device time zone is changed mid-wait (Android broadcasts a TZ change; the pinned world process must not shift ±1 h). Reuse the H1R2-2 event-activation fixtures (S4) as the seed source.
  4. **Docs:** `docs/wiki/Local-Server-World-and-Bots.md` (or `Settings.md`) gains a short "Realm time" section: what it is, what DST does (nothing, once pinned), and where it shows up (calendar, event announcements).
- **Upstream dependency:** none (cmangos uses libc `time()` in all branches; confirm the tbc/wotlk branches carry no TZ config at S2 — only classic was checked here).
- **Verification:** the TZ-change test in item 3 passes (event window stable within one tick across the change); `world.log`/`realmd.log` timestamps render in UTC after the pin (log-line attestation); unit test for the init-time `setenv` on the host lane.
- **Effort:** S
- **Depends on:** H1R2-2 (event/seasonal activation matrix, S4 — supplies the fixtures the test seeds)
- **Owner stage:** S9

---

### W2-2 — Client-trust/cheating acceptance recorded (single-player: own world)

- **Goal:** A written, user-visible acceptance statement exists: the realm is the user's own offline world — client modifications, GM/console commands, and byte patches are accepted, no anti-cheat or Warden enforcement ships, and LAN trust is the host's decision.
- **Current state:** Nothing records it: `grep -rn "cheat"` over `docs/wiki/*.md` and `README.md` returns zero matches. The underlying posture exists only as scattered engineering facts — Warden ships with lazy null-module handling and a packaging verdict pending (G2-1, register §1), the anticheat writes `Enable = 0` for the offline posture (G2-3, register §2.1), and vanilla-tweaks are byte patches applied to the user's own client (`ClientTweaks.kt`). None of it is stated as a *product acceptance* the user can read. The register assigns this row stage "docs".
- **Work items:**
  1. New "Trust & modifications" section in `docs/wiki/Local-Server-World-and-Bots.md` stating: (a) the world is single-player and yours — console/GM commands, client-side mods, and the shipped vanilla-tweaks patches are supported and accepted; (b) Warden and the anticheat heuristics ship **disabled by default** and why (an offline kick/ban heuristic would only punish the sole user — G2-3), and they stay off unless a future release says otherwise; (c) LAN guests join at the host's invitation — the app does not police guests, and the host owns who they play with (cross-link `docs/wiki/Accounts-Security-and-Networking.md`).
  2. One Decision Log entry recording the stance, referencing the G2-1 Warden packaging verdict and G2-3 anticheat posture so the docs claim and the shipped conf can never drift apart silently.
  3. Cross-link from the LAN screen footer copy and the Diagnostics flavor line documentation (D11's diagnostics line is where users will ask "is this allowed?").
  4. Per-flavor truthfulness: the statement must hold for all three flavors (the tbc/wotlk branches ship their own warden modules — S8.3 notes this; the docs sentence should say "anti-cheat components are present in the game sources and disabled", not "do not exist").
- **Upstream dependency:** none (documentation only; factual inputs are the in-repo conf posture and the S2 Warden packaging verdict).
- **Verification:** docs review under the R6 reviewer charter (wiki truthful, links resolve); grep check that the section exists and references G2-1/G2-3; consistency check at S10 that the wiki claim matches the shipped `.conf` defaults (the S10 docs sweep already walks all pages).
- **Effort:** S
- **Depends on:** G2-1 (Warden packaging verdict, S2 — the docs must match whatever is decided) and G2-3 (anticheat offline posture, S2)
- **Owner stage:** S10 (docs deliverable; may land any time after S2's posture is decided — pinned to the S10 wiki sweep so it cannot ship stale)

---

### W2-3 — Cross-device backup restore: state support + fresh-device restore test

- **Goal:** A realm archive exported on device A restores and boots on a fresh device B, the archive records its flavor, a cross-flavor restore is refused with an actionable code, and the fresh-device restore path is a named step in the S10 device matrix.
- **Current state:** The archive format exists and is single-flavor-shaped: `RealmDataArchive.kt:14-20` — ZIP kind `pocket-realm-characters` holding a stopped-state engine snapshot, `account.json` credentials, and a `realm-archive.json` meta at **schema 1** with only `kind/snapshotId/createdAtMs/appVersionName/abi` (`RealmDataArchive.kt:33`); readers hard-check `meta.optInt("schema") == 1` (`:71,:106`). The snapshot manifest is **schema 2** with per-file sha256 and a `compatibility` object (`DatabaseSnapshotStore.kt:39-46`); restore validates `compatibility.generationUuid` against the *active local* generation (`DatabaseEngine.kt:2493-2510`, `requireCompatibleSnapshot`). No field anywhere names the flavor. The untested path is exactly the W2-3 path: on a fresh device there is **no active generation**, so `requireCompatibleSnapshot`'s semantics for a foreign/absent generation are undefined-by-test, and the UI sequence "restore before any client import exists" is unowned. `abi` is recorded (`RealmDataArchive.kt:30`) but nothing states whether cross-ABI restore (arm64↔x86_64 MariaDB datadir) is supported.
- **Work items:**
  1. **Flavor into the archive without schema bumps:** ride the snapshot's existing `compatibility` object (schema 2 stays schema 2 — G3-9 discipline, matching P2-2's design) and add an optional `flavor` key to the archive meta (readers use `optString(..., "vanilla")`; schema stays 1).
  2. **Define fresh-device restore semantics:** restore with no local generation = accept the snapshot into the named flavor's DB lane and *require* re-prep/import of that flavor's client before boot; restore with a mismatched flavor = deny with a VAL code naming both flavors; restore with mismatched generation on the *same* device keeps today's behavior (no regression).
  3. **Record the ABI verdict:** verify MariaDB datadir portability arm64↔x86_64 (little-endian both) with one actual round-trip on the host lane; if portable, say so in the meta note; if not, gate restore on matching abi with actionable copy.
  4. **UI sequence:** the Settings import path (`SettingsScreen.kt`, the `RealmDataArchive` consumer) gains the "this archive needs your WotLK client — import it first" flow when the flavor's client is absent, per H4R2-6's per-flavor copy.
  5. **Fresh-device restore test in the S10 matrix** (plan §S10.1): export on RP6 → wipe app data (or second device) → import the flavor's client → prep → restore archive → launch → character present, `account.json` credentials provisioned into the per-flavor account store (H5R2-1), auto-login lands on char select.
  6. **Wiki:** `docs/wiki/Backups-and-Diagnostics.md` gains the cross-device procedure and the flavor/ABI caveats.
- **Upstream dependency:** none (app-side; the snapshot machinery is in-repo).
- **Verification:** the item-5 matrix step green in both directions (RP6↔AVD or two devices); negative test — WotLK archive into a vanilla-only app denied with the named-flavor code; same-flavor archive restored on a fresh device boots and the character logs in; schema-compat test — a pre-change archive (no `flavor` key) still imports as vanilla (G3-2 reader-tolerance pattern).
- **Effort:** M
- **Depends on:** P2-2 (snapshot flavor validation + manifest fields, S4/S9), F3-10 (backup/restore × session-drain interlock, S9), H5R2-1 (per-flavor account store the restored credentials land in), G3-9 (snapshot-manifest schema discipline)
- **Owner stage:** S10

---

### W2-4 — N≥2 humans on LAN: population math + 2-client soak before LAN exit

- **Goal:** The two-human LAN configuration is explicitly supported: the host's bot population derates for the remote player session by a measured factor, and one ≥2-client soak passes and is archived before LAN mode exits S10.
- **Current state:** LAN host/join works mechanically — `LanScreen.kt:61`+ host/join panes, `RealmService.kt:180-205` (`ACTION_HOST_LAN` → `RuntimeLaunchSpec.lanHost(...)`, `ACTION_JOIN_LAN` → `lanJoin(...)`), host interface-loss supervision with a grace window (`RealmService.kt:475-494`), and client-failure isolation in the supervisor (`DurableRuntimeSupervisor.kt:505-507` treats the LAN client separately). What does not exist is any notion of a second human in the resource math: `BotAdmissionController.observe` consumes device-local samples only (`BotAdmissionController.kt:16-24` `BotResourceSample(elapsedMs, onlineBots, worldP99Ms, freeMemoryMiB, freeStorageMiB, thermal, hardStall…)`) and `BotPopulationPolicy.kt` presets (`MAX_BUILT_IN_TARGET = 600`, `:41`) assume the single-client reality; nothing records a population ceiling or derate for a realm serving 2+ humans. The register's P1-2/P1-3 memory-envelope work (RP6: client 1.4-1.6 GB + bots guidance ≥8 GB on a 12 GB shared device) is the measurement base this gap extends.
- **Work items:**
  1. **Population math:** define the LAN-host budget as entities = bots + human sessions, and derate the default/adapted bot target when `RuntimeMode.LAN_HOST` — either an admission-preset adjustment (a `lanHostReserve` input to the preset that lowers `selectedTarget`/`maximumOnline` before `BotAdmissionController` starts) or a documented guidance number on the host pane. Record the chosen factor and rationale in the Decision Log.
  2. **Measure, don't guess (RP6 milestone):** with the P1-2/P1-3 harness, sample mangosd `worldP99Ms`/RSS with 1 client + N bots vs. 2 clients + N bots (remote client on the second device or the x86/AVD lane); set the item-1 derate from the measured delta and the P1-2 admission floors.
  3. **2-client soak before LAN exit:** ≥2 h with two connected clients on one host — second client crosses Save & Exit and relaunch while the first keeps playing; host survives (client-isolation path), no admission oscillation from the remote player's zone churn, and the host's `worldP99Ms` stays within the P1-2 floor. Archive per plan §4.5.
  4. **Copy:** `LanScreen` host pane states the supported shape honestly ("2+ players; bot population scales down to make room") and the per-flavor join requirement from C3's LAN story.
- **Upstream dependency:** none (app-side policy + measurement; the server already multiplexes human sessions natively).
- **Verification:** the item-3 soak archived with memory/thermal samples; admission logs show the derate applied on LAN_HOST and not on single-player mode; a negative check that the derate never pushes the target below `minimumOnline` (`BotAdmissionController.kt:52` floor logic); R4 reviewer sign-off on the measured factor.
- **Effort:** M
- **Depends on:** P1-2/P1-3 (memory envelope measurement, S2/S6 — supplies floors and the harness), S8.4 (LAN flavor advertising — the joiner must match flavor), C3 (per-flavor LAN join story)
- **Owner stage:** S10

---

## Coverage ledger

- [x] H5R2-1
- [x] H5R2-2
- [x] F3-5
- [x] F3-7
- [x] F3-8
- [x] J1-1
- [x] C3
- [x] W2-1
- [x] W2-2
- [x] W2-3
- [x] W2-4

Not assigned to this section, resolved here for readers arriving from the `Depends on` fields above: **F3-3/H2-5** (register §1 P0, roadmap CP-5) is owned by §2.5 — `out/fix-plan/performance-persistence-import.md` `### F3-3/H2-5`, ticked in that file's ledger — because its `qualified().single()` consumers live in the import/planner/prep lanes that section covers.

§2.7 closes the register walk with the ten guards that make execution of everything above safe for existing vanilla installs: the G3 quarantine hazards (strict schema checks, golden-byte pins, pointer pruning, verbatim surfaces) that would otherwise strand the one user group the port can silently destroy. Its rules are stage invariants re-run at every gate — which is why the roadmap schedules the fixture and golden halves in wave 0, before any parameterization touches a protected surface.

---

# §2.7 Vanilla-regression guards

## 2.7 Vanilla-regression guards (R5: G3 — apply at every stage)

This area protects the one group of users the port can silently destroy: existing vanilla installs whose durable on-device state — account records, client/prep manifests, supervisor journals, migration seals, generation pointers, addon registries, generated conf files, lore staging, snapshots, and settings catalogs — is validated by strict schema and byte checks today. The ten gaps group into four themes: **(a) optional-field shims** — every durable record that must learn a `flavor` dimension (G3-1 UserAccountStore, G3-2 manifests, G3-4 journal codec) gains it as an optional field defaulting `"vanilla"` via `optString`, with readers accepting both old and new encodings and quarantine/refusal reserved for genuinely invalid input, never for absence of the new key; **(b) byte-stability pins** — artifacts whose identity is a digest (G3-3 migration-ledger seal, G3-6 addon catalogs/validator policies, G3-7 generated conf bytes, G3-8 lore asset name) stay byte-identical for vanilla across the S1/S3/S7 renames and parameterizations, proven by golden-SHA tests; **(c) scoped destructive maintenance** — generation-pointer pruning (G3-5) and snapshot-manifest validation (G3-9) must never consume another flavor's data; and **(d) verbatim surfaces** — the vanilla settings/binding catalogs, bot-population policy, and WTF layout (G3-10) are frozen as captured. Every guard here is a stage invariant (port plan §2.1: "vanilla stays shippable"): its tests are written against current-release record fixtures *before* the corresponding stage touches the surface, and re-run at every gate. The `ClientFlavors` registry (`android/app/src/main/java/com/pocketrealm/client/ClientFlavor.kt:57-165`) and `schemas/flavor.json` (schema 2, only `vanilla` qualified) already exist from S0 groundwork; these gaps harden the durability layer that registry is about to flow through.

---

### G3-1 — UserAccountStore flavor field lands as optional, never as a schema bump

- **Goal:** the stored user-account record (`user-account/account.json`) carries the flavor as an optional `optString`-defaulted field at the existing `schema == 2`, and `loadOrQuarantine()` quarantines only records that are malformed — never records that merely predate the field.

- **Current state:** `android/app/src/main/java/com/pocketrealm/supervisor/UserAccountStore.kt:65` hard-fails with `require(value.getInt("schema") == SCHEMA)` (`SCHEMA = 2`, line 129), and `loadOrQuarantine()` (lines 48-53) responds to any `readLocked()` throw by renaming the record to `account.invalid.<ts>.json` (`quarantineLocked()`, lines 98-109) — i.e. the user's typed username, password, accountId and gmLevel are moved aside on first launch after an update. The record already uses tolerant reads for later-added fields (`optLong("accountId", 0)` / `optInt("gmLevel", -1)`, lines 70-73), which is exactly the pattern the flavor field must follow. If S8's expansion work ("UserAccountStore schema gains a flavor field", port plan S8.1) lands as `SCHEMA = 3`, every existing vanilla device quarantines its account on first boot — the register's S0-class finding. The desktop JVM twin `desktop/src/main/kotlin/com/pocketrealm/supervisor/UserAccountStore.kt` has the identical check (lines 55, 134) and must move in lockstep.

- **Work items:**
  1. In both `UserAccountStore.kt` files (Android + desktop twin), add `val flavor = value.optString("flavor", ClientFlavors.VANILLA.flavorId)` to `readLocked()` and `require(flavor in ClientFlavors.ALL.map { it.flavorId }) { "invalid stored user-account flavor" }` — reject only unknown *values*, never absent keys. Keep `SCHEMA = 2` in `writeLocked()`; write the field only when it is non-vanilla (`if (account.flavor != "vanilla") builder.put("flavor", …)`) so bytes for existing vanilla users are unchanged.
  2. Extend `UserAccount` (Android lines 118-126) with `val flavor: String = "vanilla"`; `save()` gains `flavor: String = ClientFlavors.VANILLA.flavorId`. S8's `account set addon` relay (F1-10) then writes `expansion` from this field without any further store change.
  3. Deliberately do **not** touch `SinglePlayerCredentialStore.kt:67` (its schema check stays; the single-player identity is flavor-agnostic) — record that decision in `docs/DECISIONS-PORT.md`.
  4. Embed a current-release record fixture (the exact JSON bytes the shipping build writes: `{"schema":2,"username":…,"password":…,"accountId":…,"gmLevel":…}`) as a test resource and assert `loadProvisioned()` round-trips it unchanged.

- **Upstream dependency:** none.

- **Verification:** `cd android && gradlew :app:testDebugUnitTest --tests "com.pocketrealm.supervisor.UserAccountStoreValidationTest"` (file exists at `android/app/src/test/java/com/pocketrealm/supervisor/UserAccountStoreValidationTest.kt`) extended with: fixture record (no flavor key) loads as vanilla; record with `"flavor":"tbc"` loads; record with `"flavor":"cata"` quarantines; byte-compare of `writeLocked` output for a vanilla save against the committed fixture. Instrumented proof on device: `UserAccountStoreInstrumentedTest.kt` (exists under `android/app/src/androidTest/java/com/pocketrealm/supervisor/`) writes a schema-2 record with the *old* build's code path (fixture bytes), upgrades nothing, and asserts `loadOrQuarantine()` returns the account and no `account.invalid.*` file appears.

- **Effort:** S (<1 day).

- **Depends on:** none.

- **Owner stage:** S0 (shim + fixtures land with flavor foundation; S8 consumes the field without touching the schema constant).

---

### G3-2 — Manifest readers accept both schemas; flavor rides as an optional field

- **Goal:** every durable manifest (client-manifest, data-manifest, active pointers) keeps its current schema number, gains `flavor` only as an optional `optString("flavor","vanilla")` key, and every reader validates the *content* (clientId/build/family) rather than the presence of new keys.

- **Current state:** the managed-client generation manifest is written at `ClientGenerationStore.kt:100-118` with `"schema": 2` and a stamped `clientId`, and read at `ClientGenerationStore.kt:190-202` with `check(manifest.getInt("schema") == 2 && manifest.getBoolean("complete"))` plus `ClientFlavors.isManagedClientId(...)` (line 193). `ManagedClientStore.kt:86` is already the tolerant model: `check(manifest.optInt("schema") in 1..2 && manifest.optBoolean("complete"))` — new code must follow that shape, not the strict-equality shape. The o11 data manifest is written at `DataPreparationStore.kt:269` (`schema 1`, `mode NORMAL`, `clientBuild`, `cmangosFamily`) and read at `PreparedDataStore.kt:40-42` with strict `schema == 1` plus `clientBuild`/`cmangosFamily` equality. Active pointers: `ClientGenerationStore.kt:142` and `DataPreparationStore.kt:331` write `schema 1` pointer objects; `PreparedDataStore.kt:22` checks pointer `schema == 1 && mode == "NORMAL"`.

- **Work items:**
  1. Adopt a written rule in `docs/DECISIONS-PORT.md`: **a manifest schema number never increments to introduce an optional field.** Flavor enters `client-manifest.json` (at S5) and `data-manifest.json` (at S6) as `manifest.put("flavor", clientId-derived-flavor)` added *after* the schema-2/1 shape readers know; readers use `optString("flavor", /* derived from clientId/clientBuild */ "vanilla")`.
  2. `ClientGenerationStore.validateManifest` (line 192): keep `schema == 2`; add a consistency check `optString("flavor", "vanilla") == ClientFlavors.byClientId(getString("clientId"))!!.flavorId` so a doctored flavor value is rejected (fail-closed, invariant 2) while absent values are fine.
  3. `PreparedDataStore.kt:40-42` / `DataPreparationStore` reader at lines 299-307: same treatment — `cmangosFamily` already carries the flavor dimension (`classic`/`tbc`/`wotlk` per `ClientFlavor.serverFamily`), so the `flavor` key is an additive alias validated against it, never a schema bump.
  4. Fixture tests: commit the exact `client-manifest.json` and `data-manifest.json` bytes emitted by the current release (captured from a real import on device or from `SyntheticClientArchives`-driven publish) and assert all three readers (`ClientGenerationStore`, `ManagedClientStore`, `PreparedDataStore`) accept them verbatim on the new build.
  5. Negative tests: flavor value disagreeing with `clientId`/`clientBuild`/`cmangosFamily` must be refused with the existing error strings (no silent coercion).

- **Upstream dependency:** none.

- **Verification:** unit tests in `android/app/src/test/java/com/pocketrealm/importer/` (alongside the existing `DataPreparationStoreTest.kt`): fixture manifests from the current release load with zero edits; `optString` default resolution equals `ClientFlavors.VANILLA`; adversarial flavor/clientId mismatch throws. Run `cd android && gradlew :app:testDebugUnitTest --tests "com.pocketrealm.importer.*"`.

- **Effort:** M (1-3 days).

- **Depends on:** none.

- **Owner stage:** S0 (rule + fixtures land before S5/S6 stamp flavor into manifests).

---

### G3-3 — Vanilla migration-ledger bytes stay byte-identical across per-flavor renames; golden SHA pinned

- **Goal:** when S1/S4 split `schemas/database-migrations.json` into per-flavor ledgers, the vanilla ledger's bytes (and therefore its APK-asset SHA-256) do not change, so every deployed vanilla datadir's migration seal still verifies; a golden SHA gate in CI enforces this forever.

- **Current state:** `DatabaseEngine.kt:79-84` reads the packaged asset `database/migrations/manifest.json` (`MIGRATION_MANIFEST`, line 3260) and computes `expectedMigrationManifestSha256 = sha256Text(...)`; `DatabaseEngine.kt:2062-2068` folds it into the SQLite provider identity and `DatabaseMigrationSeal.current()` (`android/app/src/main/java/com/pocketrealm/database/DatabaseMigrationSeal.kt:43-49`) requires `marker.getString("manifestSha256") == expectedManifestSha256` (and `marker.length() == 6`, line 43) against the seal written into the datadir at apply time (`DatabaseEngine.kt:620, 904, 957, 1426, 1532`). `tools/stage_database_migrations.py:217-218` writes the identical encoded text to `schemas/database-migrations.json` and the staging `manifest.json` that `android/app/build.gradle.kts:1893-1894` packages. Measured this session: the file is **208,495 bytes, sha256 `3771fc35b4ceddbadd6f786d76b10902db319d07c7d6372156417e4e3d81dc68`**, 414 entries, schema 1, `expected_revisions` `required_z2820_01_realmd…` etc. Any re-key, reorder, whitespace or rename that changes those bytes breaks every existing vanilla device's seal → forced re-apply or `DB-REVISION` fail at next boot.

- **Work items:**
  1. New tool `tools/check_ledger_golden.py`: asserts `sha256(schemas/database-migrations-vanilla.json) == 3771fc35…dc68` and byte-equality with the staged `assets/database/migrations/manifest.json` input; wire it into `tools/check_repo.py` (which port plan invariant §2.3 keeps authoritative) so CI fails on any vanilla-ledger byte change that is not an explicit golden-sha rotation with a Decision Log entry.
  2. S1 work item 7's rename is executed as **additive, not mutating**: create `schemas/database-migrations-vanilla.json` as a byte copy of the current file; keep `schemas/database-migrations.json` as the alias that must remain byte-identical during the deprecation window (port plan S1 item 8); only `tbc`/`wotlk` ledgers are new files. **The window is temporary and closes at roadmap wave S9** (owned there inside F3-8's allowlist-retirement part): once every consumer reads the `-vanilla` canonical name — wave S1's "no consumer reads unqualified names" exit is the migration precondition — the alias *file* is deleted, `tools/check_ledger_golden.py` re-points to `database-migrations-vanilla.json` (same golden sha `3771fc35…dc68`: the datadir seal binds the packaged asset *bytes* `database/migrations/manifest.json`, never the schema filename, so deployed vanilla devices are unaffected), the `tools/leak_sweep.py:609` allowlist entry retires with the file, and a Decision Log entry records the rotation. An indefinitely-live alias is exactly what the sweep's retirement model (`retires` field on every allowlist entry) forbids.
  3. `tools/leak_sweep.py:609` already pins `schemas/database-migrations.json` as the vanilla-retiring path — extend the sweep's per-flavor section so the vanilla ledger path set {canonical, alias} is asserted byte-identical to each other, not just literal-scanned.
  4. If a vanilla-ledger change is ever unavoidable (e.g. a genuine vanilla migration fix), the rotation procedure is: recompute seal-compatible diff → ship a one-release compatibility window where `DatabaseMigrationSeal.current()` also accepts the previous sha via an explicit `LEGACY_MANIFEST_SHA256` list in `DatabaseEngine` — never a silent accept-anything.

- **Upstream dependency:** none.

- **Verification:** `python tools/check_ledger_golden.py` green; `python tools/check_repo.py` green; adversarial: hand-edit one byte of the vanilla ledger on a branch → both gates red with a message naming the golden sha. Device proof: a current-release build's datadir (seal written by today's APK) boots on the post-rename build with no `DB-REVISION`/re-apply, asserted by the existing `DatabaseStartPreparationTest`-style instrumented harness.

- **Effort:** M (1-3 days).

- **Depends on:** none (must land **before** S1 item 7's ledger renames).

- **Owner stage:** S1.

---

### G3-4 — Supervisor journal codec: flavor as optional field, decode tolerant of pre-flavor journals

- **Goal:** `RuntimeSnapshotJournalCodec` round-trips a `flavor` field without incrementing `JOURNAL_SCHEMA`, and every journal written by today's release still decodes byte-for-byte on the multi-flavor build (no quarantine, no recovery false-positive after app update).

- **Current state:** `android/app/src/main/java/com/pocketrealm/supervisor/RuntimeSnapshotJournalCodec.kt:39-41` requires `storedSchema == LEGACY_SCHEMA (2) || storedSchema == RuntimeSnapshot.JOURNAL_SCHEMA (3)` (`RuntimeModel.kt:49`), and `AtomicSupervisorJournal.kt:34` re-asserts `snapshot.schema == JOURNAL_SCHEMA` on every append. The codec is the shared JVM file so the Windows desktop lane compiles the same tree (file docstring, lines 5-11). S9.3 ("supervisor journal entries carry flavor") and F3-6/P2-12 (mid-switch kill resumes the journaled flavor) are the consumers that will tempt an implementer into a schema bump — a bump to 4 with a strict decode would strand any device killed mid-session just before the update.

- **Work items:**
  1. `RuntimeSnapshot` (`RuntimeModel.kt:31-52`) gains `val flavor: String = ClientFlavors.VANILLA.flavorId` with a default, so every existing constructor call site compiles unchanged.
  2. `encode()` (codec lines 13-37): append `.put("flavor", value.flavor)` **only when non-vanilla**, keeping today's vanilla journal bytes identical (org.json has no field order guarantee, but omitting the key entirely for vanilla keeps the golden-fixture comparison honest).
  3. `decode()`: `val flavor = value.optString("flavor", ClientFlavors.VANILLA.flavorId)` plus `require(flavor in …)` for unknown values; keep `LEGACY_SCHEMA = 2` and `JOURNAL_SCHEMA = 3` exactly as they are.
  4. Extend `RuntimeSnapshotJournalCodecTest.kt` (exists under `android/app/src/test/java/com/pocketrealm/supervisor/`) with a golden fixture of a current-release journal JSON blob and asserts: decodes to `flavor == "vanilla"`; re-encode omits the flavor key; a `"flavor":"wotlk"` journal decodes; `"flavor":"galaxy"` throws `unsupported`.
  5. Desktop lane: no separate twin exists (the codec is shared) — verify the desktop suite still compiles/passes via `cd desktop && gradlew test` (convention documented in `docs/WINDOWS_QUALIFICATION.md:12`).

- **Upstream dependency:** none.

- **Verification:** `cd android && gradlew :app:testDebugUnitTest --tests "com.pocketrealm.supervisor.RuntimeSnapshotJournalCodecTest"`; fixture decode/re-encode equality for the pre-flavor journal; a supervisor-level test that a journaled `flavor:"tbc"` snapshot survives `AtomicSupervisorJournal` append/reload (schema 3 asserted throughout — no 4 anywhere in the diff, enforced by a grep in the stage's leak sweep).

- **Effort:** S (<1 day).

- **Depends on:** none.

- **Owner stage:** S0 (codec rule + tests land up front; S9/F3-6 write non-vanilla values).

---

### G3-5 — Generation-pointer pruning scoped per flavor; legacy `active.json` seeds the vanilla namespace

- **Goal:** activating a TBC/WotLK client generation or data-generation never deletes a vanilla generation (or vice versa); the pre-existing un-namespaced `active.json` files are adopted as the vanilla namespace on first multi-flavor boot, with zero data loss.

- **Current state:** `ClientGenerationStore.kt:17-18` keeps a single `active.json` / `previous.json` pair, and `activateLocked()` (lines 135-151) writes a schema-1 pointer (line 142) then prunes: `keep = setOfNotNull(generation, priorGeneration)` and every other non-staging directory under `generations/` is `deleteRecursively()`'d (lines 146-149). `DataPreparationStore.activate()` (`DataPreparationStore.kt:323-338`) repeats the identical pattern for o11 data generations (keep-set lines 335-337), and `PreparedDataStore.kt:22-42` authenticates the pointer plus manifest. Consequence: the moment a second flavor is qualified (S5/S6) and a TBC generation activates, the **vanilla managed client — and worse, the vanilla prepared-data generation representing hours of on-device mmaps extraction — is silently destroyed** by the prune. Conversely, `recoverPublished` (lines 34-47) and pointer readers assume one active lane.

- **Work items:**
  1. Introduce per-flavor pointer files: `active-vanilla.json` / `previous-vanilla.json` (and `-tbc` / `-wotlk`) in the same directories, with the identical schema-1 body plus `"flavor"` key. Keep the un-suffixed `active.json` reading as a **legacy seed**: on first read, if `active-vanilla.json` is absent and legacy `active.json` exists, atomically rename it to `active-vanilla.json` (client store) / copy-then-adopt (prep store), then continue. Never leave both readable as authoritative.
  2. Scope pruning to the activating flavor: `keep = setOfNotNull(generation, priorGeneration)` plus "every generation whose `client-manifest.json`/`data-manifest.json` resolves to a *different* flavor" — read each candidate generation's manifest `clientId`/`cmangosFamily` (already stamped at `ClientGenerationStore.kt:100-104` and `DataPreparationStore.kt:269`); if the manifest is unreadable, treat the generation as vanilla-foreign and **keep it** (fail-safe, not fail-destructive), logging to the diagnostics bundle.
  3. `PreparedDataStore.authenticateActive`/`resolveActive` gains a `ClientFlavor` parameter (threaded from `ServerRuntimeFiles`/`DataPreparationStore` callers) so the vanilla lane can never activate onto a TBC generation even if a pointer is hand-mangled (defense-in-depth behind the manifest gates at `PreparedDataStore.kt:40-42`).
  4. Migration fixture: commit byte fixtures of the current single-pointer layout; the adoption path is unit-tested (legacy `active.json` present → adopted, generations intact, `previous.json` preserved), and the adversarial path (vanilla active + TBC activation → both generation directories still present afterwards) is an androidTest.
  5. Update `OrphanSelfHealPolicy`/recovery paths that glob pointer files so they enumerate per-flavor pointers, not a fixed name (check `android/app/src/main/java/com/pocketrealm/supervisor/OrphanSelfHealPolicy.kt` for `active.json` references during implementation).

- **Upstream dependency:** none.

- **Verification:** unit tests under `android/app/src/test/java/com/pocketrealm/importer/`: (a) legacy-seed adoption preserves generation dirs and pointer digests; (b) publish of a synthetic TBC generation over an existing vanilla generation leaves both directories with valid manifests (today's code fails this test — assert the fix flips it); (c) prune of the *same* flavor still retires the third-oldest generation (old behavior preserved within one flavor). Device: import vanilla → capture `generations/` listing → simulate TBC publish via `SyntheticClientArchives` → re-list and diff. This test is also R2's standing adversarial scenario for every later stage gate.

- **Effort:** L (1-2 weeks).

- **Depends on:** G3-2 (manifests must carry/derive flavor for keep-set scoping).

- **Owner stage:** S5 (first stage that can activate a second flavor's generation; S9 namespaces build on it).

---

### G3-6 — Addon registry ids and vanilla validator policies pinned byte-for-byte (incl. VOICEOVER_DATA)

- **Goal:** installed-addon registry ids, the vanilla catalog file, and the `AddonArchiveValidator` vanilla policies (`VANILLA`, `VOICEOVER_PLAYER`, `VOICEOVER_DATA`) are frozen with golden hashes so per-flavor catalogs (H4-3) and per-flavor interfaces (S7) never re-key or re-validate what existing vanilla users have installed.

- **Current state:** `android/app/src/main/java/com/pocketrealm/addons/AddonCatalog.kt` loads `addons/catalog-v1.json` (line 102) and hard-pins its shape: `matrixId` regex `\d{3}` (line 150), `require(addons.size == 154)` (line 158), `require(pairs.size == 266)` (line 178), recommended ids `{"151","002","059"}` (lines 103-110). The installed registry is `registry.json` / `registry.previous.json` / `registry.transaction.json` (`AddonRepository.kt:39-41`), written as `{"schema":1,"installed":[…]}` (`AddonRepository.kt:759-760`, strict read at 793-796; transaction journal `JOURNAL_SCHEMA = 1` at `AddonRegistryPublisher.kt:94`), where `InstalledAddon.id` is the GitHub-ref id or builtinId (`AddonModels.kt:5-13`) — the key `remove()`, update-diff and rollback match on. `AddonArchiveValidator.kt:24-38` defines `Policy.VANILLA` (`acceptedInterfaces = {11200}`), `VOICEOVER_PLAYER`, and `VOICEOVER_DATA` (`AI_VoiceOverData_Vanilla`, interfaces `{11200, 100000}`, `OptionalDeps` pin, 1536 MiB caps at lines 255-256). Hazards: renumbering vanilla `matrixId`s, prefixing ids with a flavor namespace, retuning the vanilla caps, or swapping `catalog-v1.json` for a multi-flavor file all break update checks, compatibility warnings, and `addonForInstalled` resolution for existing installs.

- **Work items:**
  1. Freeze the vanilla catalog: `addons/catalog-v1.json` is never edited again; compute and pin its sha256 in `tools/check_repo.py` (like the ledger golden in G3-3). Per-flavor catalogs are new assets (`addons/catalog-tbc-v1.json`, `catalog-wotlk-v1.json`) loaded by a per-flavor resolver; `AddonCatalog.load` keeps returning the vanilla file for the vanilla flavor verbatim.
  2. `matrixId` stays the cross-flavor identity key (ids are already stable three-digit strings; new-flavor entries continue the numbering in *their own files*, never by renumbering vanilla rows). Record the no-renumber rule in `docs/DECISIONS-PORT.md`.
  3. Registry ids: `InstalledAddon.id` remains the un-prefixed install id on all flavors; flavor scoping of *installed sets* happens at the projection/rollback layer (F3-5), not by rewriting ids. Add a migration test proving a pre-change `registry.json` fixture loads and resolves its catalog entries (`addonForInstalled`) identically before/after the multi-flavor work.
  4. Pin the vanilla validator policies: give `Policy.VANILLA`/`VOICEOVER_PLAYER`/`VOICEOVER_DATA` an explicit "pinned for vanilla" contract — new `Policy` instances for TBC/WotLK (`acceptedInterfaces = {20400}/{30300}`, H4-3's VOICEOVER data variants) are *new named constants*; the vanilla constants' constructor arguments are asserted by a test that reflects over the data-class values against a committed literal list (so an accidental edit of `maxArchiveBytes`/`expectedFolders` fails CI).
  5. `VoiceOverReleaseResolver` (`INSTALL_ID`, referenced at `AddonRepository.kt:161,318`): its vanilla install id and repo mapping stay fixed; per-expansion artifacts (H4-13) extend the resolver with new ids instead.

- **Upstream dependency:** none.

- **Verification:** `cd android && gradlew :app:testDebugUnitTest --tests "com.pocketrealm.addons.*"` with new tests: catalog golden-sha (fails on any byte change), registry fixture round-trip, policy-literal pin; `python tools/check_repo.py` extended with the `catalog-v1.json` digest gate. Negative: a branch that edits one vanilla catalog row → CI red naming the changed digest.

- **Effort:** M (1-3 days).

- **Depends on:** none (must land before H4-3's per-flavor catalogs — ux-addons-controls.md H4-3 names this guard; F3-5's rollback scoping consumes it).

- **Owner stage:** S7 (the golden/pin work items 1-5 are host-side and land in roadmap wave 0 beside G3-7's fixture capture — the catalog sha pin and validator-policy literals need no flavor runtime, satisfying CP-1's guards-before-surfaces ordering and wave S7's entry criterion; S7 itself executes the per-flavor catalog variants against those pins and keeps them green).

---

### G3-7 — Golden byte-tests for vanilla conf output; per-flavor projections keep vanilla byte-identical

- **Goal:** every app-generated server conf and client config projection — `realmd.conf`, `mangosd.conf`, `aiplayerbot-*.conf`, the publish-time `WTF/Config.wtf` (SAFE_CONFIG), and `ManagedConfigPolicy` enforced lines — emits **byte-identical output for the vanilla lane** after per-flavor parameterization, proven by committed golden fixtures re-checked in CI.

- **Current state:** `android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt` writes `realmd.conf` (line 55), per-profile `aiplayerbot-<id>.conf` or `aiplayerbot-disabled.conf` (lines 123-129), and `mangosd.conf` (line 135) via `secureWrite`; database names come from `serverDatabases()` (lines 404-418), which today resolves `ClientFlavors.qualified().single()` and *requires* exactly one qualified vanilla flavor — the projection already reads the flavor registry, so S2's realmlist/`gamebuild` divergence work will parameterize these templates directly on the vanilla path. Client-side, `ClientGenerationStore.kt:153-156` writes the `SAFE_CONFIG` byte block (lines 240-262) at publish, and `ManagedConfigPolicy.enforcedKeys(conditions)` (`android/app/src/main/java/com/pocketrealm/ingame/ManagedConfigPolicy.kt:36`) produces the enforced `Config.wtf` lines through `ConfigWtfCodec`. There is currently **no test anywhere asserting these outputs byte-for-byte**; the first parameterization pass can silently reformat a template (a stray newline, a reordered key) and change what existing vanilla devices run.

- **Work items:**
  1. Capture golden fixtures now, before any S2/S3 parameterization: run the generators under the current build (JVM unit test can call `ServerRuntimeFiles` internals via the existing test plumbing; `SAFE_CONFIG` is a constant) and commit the exact bytes of `realmd.conf`, `mangosd.conf`, `aiplayerbot-disabled.conf`, one `aiplayerbot-<id>.conf`, `WTF/Config.wtf`, and the enforced-lines serialization as test resources.
  2. Add `VanillaConfGoldenTest`: for each surface, invoke the generator with the vanilla `ClientFlavor` and byte-compare (not string-compare after normalization — raw `ByteArray` equality) against the fixture; failures print a unified diff. This test is the acceptance gate every later conf-touching stage must keep green (ties to invariant §2.1 and S1's "byte-identical staging" proof pattern).
  3. When S2/S3 parameterize `ServerRuntimeFiles`/`BotProfiles` conf emission (S3.6's fail-loud unknown-key check), route *all* keys through the flavor table with vanilla defaults equal to today's literals — the fixture diff is the proof. `serverDatabases()` (lines 404-418) is reworked then to take the session flavor (that rework is F3-3/H2-5's — owned by its section in `out/fix-plan/performance-persistence-import.md` `### F3-3/H2-5`; the golden tests must already exist when it happens).
  4. Same treatment for the Wine-side projections: pin `ClientRealmEndpointProjection.text()` output (`set realmlist 127.0.0.1\r\n`) and the `writeManagedConfiguration` pair (`realmlist.wtf` + `Config.wtf`, `ClientGenerationStore.kt:153-156`) as fixtures; H2-1's later `Data\<locale>\` copies extend *around* the root file, which stays byte-identical.
  5. Donor-machine topology keys (`processAffinityMask`/`coresDetected`/`hwDetect`): **no seed of these keys exists in app code today** (repo-wide grep returns only the settings-catalog row `WowVanillaSettingsCatalog.kt:122` and docs hits), so item 1's fixtures already cover the complete vanilla generated-conf surface. The single program posture (review-round-1 resolution; owned by `client-data-runtime.md` W1-10 item 2, adopted by `performance-persistence-import.md` P2-a, which withdrew its original vanilla delta-golden): scrub donor machine keys at import on **every** flavor — a no-op on genuine vanilla trees, so published vanilla bytes are unchanged — and seed the full-core mask/`hwDetect "0"` into the **tbc/wotlk seed templates only**. Vanilla `SAFE_CONFIG` never gains topology keys; the golden tests here enforce that absence byte-for-byte. If vanilla seeding is ever wanted, it requires a Decision Log entry amending this section *and* the roadmap wave-0 `--flavor vanilla` byte-identity exit criterion in the same commit — not planned.

- **Upstream dependency:** none.

- **Verification:** `cd android && gradlew :app:testDebugUnitTest --tests "com.pocketrealm.server.VanillaConfGoldenTest"` (and the client-side twin) green on the unparameterized tree first (proves the fixtures match today's output), then green after every S2/S3/S7 conf change; R2's stage-gate check re-runs it. Device spot-check: one vanilla boot post-parameterization, `adb` pull of `server/run/*.conf` byte-compared to the fixtures.

- **Effort:** L (1-2 weeks — many surfaces, fixture plumbing across JVM/instrumented lanes).

- **Depends on:** none (must land before S2 item 4's realmd conf divergence work).

- **Owner stage:** S2 (extends through S3's aiplayerbot conf and S7's Config.wtf work).

---

### G3-8 — Vanilla lore asset name kept; per-flavor corpora are new files

- **Goal:** `lore/lore_cards_v112.jsonl` keeps its asset path, file name, and staging name for the vanilla lane forever; TBC/WotLK corpora (`v204`/`v303`) are added as new assets selected by flavor, so no vanilla update changes what the running vanilla world stages.

- **Current state:** `android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt:476-477` pins `LORE_CARDS_FILE_NAME = "lore_cards_v112.jsonl"`, staged from `assets/lore/lore_cards_v112.jsonl` (file exists in the repo tree) into `server/run/` by `stageLoreCards()` (lines 318-335), which re-stages when the staged copy's size stops matching the asset. The staging is deliberately best-effort (lines 194-210: a lore problem must never fail a world start). S3.7 introduces per-flavor corpora ("`lore_cards_v112.jsonl` → `v204`/`v303` variants or shared corpus + era lint — decision in-stage") and H3-7/F1-9 own provenance and packaging; the regression hazard is "cleanup": renaming the vanilla asset or the staged file, or replacing `LORE_CARDS_FILE_NAME` with a flavor-computed name that formats differently, silently changing the staged path the native retrieval loop reads.

- **Work items:**
  1. Keep `LORE_CARDS_FILE_NAME` as the vanilla constant exactly as is. Introduce the flavor dimension as a new lookup (e.g. `private fun loreCardsFileName(flavor: ClientFlavor): String = when (flavor.flavorId) { "vanilla" -> LORE_CARDS_FILE_NAME; … }`) where the vanilla arm returns the untouched constant — a per-flavor *function*, not a per-flavor rewrite of the constant.
  2. Add per-flavor assets `android/app/src/main/assets/lore/lore_cards_v204.jsonl` / `lore_cards_v303.jsonl` (H3-7 content) without touching the `v112` file; a golden sha256 of `lore_cards_v112.jsonl` goes into `tools/check_repo.py` so provenance work cannot silently revise vanilla cards (revision of vanilla cards must be a Decision Log entry + sha rotation).
  3. `stageLoreCards()` gains the flavor parameter with the vanilla path behaviorally identical (same target name in `run/`, same size-match re-stage logic, same fail-open contract); assert in a unit test that the vanilla staged file name is still exactly `lore_cards_v112.jsonl`.
  4. Era-lint work (H3-8) reads the same files — record that the vanilla corpus is *frozen content, stable name*, with lint changes living in the lint layer, not the asset.

- **Upstream dependency:** none.

- **Verification:** unit test asserting `stageLoreCards()` target path for vanilla == `…/server/run/lore_cards_v112.jsonl` and the asset size-match path is exercised; `python tools/check_repo.py` digest gate green; adversarial: change one byte of `lore_cards_v112.jsonl` on a branch → CI red naming the golden digest.

- **Effort:** S (<1 day).

- **Depends on:** none (H3-7/F1-9 are consumers, not prerequisites).

- **Owner stage:** S3.

---

### G3-9 — Snapshot manifests: never add a top-level key to schema 2 (or relax `length()` to `>=`)

- **Goal:** DB snapshot manifests created by today's release still validate on the multi-flavor build; the flavor dimension enters through the existing `compatibility` object, and the strict `manifest.length() == 5` / `record.length() == 3` checks are either left untouched or deliberately relaxed — never silently widened by a writer/reader mismatch.

- **Current state:** `android/app/src/main/java/com/pocketrealm/database/DatabaseSnapshotStore.kt:43-45` writes `{"schema":2, "snapshotId", "createdAt", "files", "compatibility"}` plus a `manifest.sha256` sidecar (lines 48-49), and `validatePublished()` enforces, at line 155, `check(manifest.length() == 5 && manifest.getInt("schema") == 2 && …)` and at line 162 `check(record.length() == 3)` per file record; the digest chain (lines 146-153) pins the manifest bytes themselves. P2-2 ("snapshot/restore flavor validation — manifest gains flavor/db-set/table inventory; cross-flavor reject tests") will want to add keys. If flavor lands as a sixth top-level key, **every snapshot taken by the current release fails `validatePublished`** on the updated build → backup/restore and `retainNewest` paths (`DatabaseSnapshotStore.kt:120-126`) reject existing user snapshots — silent loss of the rollback path for vanilla users.

- **Work items:**
  1. Rule (Decision Log): **flavor goes inside `compatibility`** — it is already a free-form `JSONObject` the writer accepts as a parameter (`create(..., compatibility: JSONObject = JSONObject())`, line 22) and the reader only type-checks (`manifest.get("compatibility") is JSONObject`, line 157). P2-2's db-set/table inventory rides there too. `manifest.length()` stays 5; file records stay length 3.
  2. Alternative if P2-2 needs top-level keys: relax line 155 to `manifest.length() >= 5` **and** keep every named check; never relax line 162 (file records are per-file integrity rows, and extra keys there have no legitimate use). Record which option shipped.
  3. Fixture test: commit the manifest bytes of a snapshot created by the current release (produced by `DatabaseSnapshotStoreTest`-style harness — the test file exists at `android/app/src/test/java/com/pocketrealm/database/DatabaseSnapshotStoreTest.kt`) and assert `load()`/`restore()`/`verify()` accept it verbatim post-change, including the `manifest.sha256` sidecar chain.
  4. Cross-flavor reject (P2-2's actual goal, fail-closed): restore of a snapshot whose `compatibility.flavor` disagrees with the active session flavor refuses with a `DB-SNAPSHOT:` error naming both flavors — while the no-flavor (vanilla) compatibility object continues to restore on the vanilla lane.

- **Upstream dependency:** none.

- **Verification:** `cd android && gradlew :app:testDebugUnitTest --tests "com.pocketrealm.database.DatabaseSnapshotStoreTest"` extended: current-release manifest fixture loads; `compatibility`-carried flavor round-trips; vanilla restore accepted; tbc-manifest × vanilla-lane restore refused. Byte-level: alter one byte inside `compatibility` → digest mismatch error (proves the seal chain still guards).

- **Effort:** S (<1 day).

- **Depends on:** none (P2-2 consumes the rule).

- **Owner stage:** S4.

---

### G3-10 — Vanilla catalogs, bot-population policy, and WTF layout stay verbatim

- **Goal:** the vanilla settings catalog (109 entries), binding catalog, `BotPopulationPolicy` constants, and the build-5875 WTF file layout are frozen as captured; TBC/WotLK variants are new data/code paths selected by flavor — the vanilla paths are never "generalized" in place.

- **Current state:** `android/app/src/main/java/com/pocketrealm/ingame/WowVanillaSettingsCatalog.kt` declares `CATALOG_VERSION = 2` (line 25, re-verified this session; the symbol name is the stable anchor) over a fixed 109-entry list provenance-pinned to the 5875 FrameXML/WTF capture (doc lines 4-20); `android/app/src/main/java/com/pocketrealm/client/WowVanillaBindingCatalog.kt` enumerates the 5875 binding sections/semantics; `android/app/src/main/java/com/pocketrealm/ingame/InGameSettingsFiles.kt:10-31` pins the WTF layout — account `SavedVariables.lua`, account `bindings-cache.wtf`, per-character `bindings-cache.wtf` — with the docstring "pinned to the build-5875 layout verified on device". `BotPopulationPolicy.kt:24-33` hard-codes the classic lane's bounds (`CHARACTERS_PER_BOT_ACCOUNT = 9`, targets). Port plan §7 lists all of these as pinned artifacts the gauntlet watches. S7.3 (per-flavor catalogs + WotLK per-realm/per-char WTF), H4-5/H4-6 (3-level WTF writes/snapshots), H4R2-4 (per-flavor settings catalogs), and H3R2-1 (per-flavor bot conf) will rework exactly these files; the hazard is in-place mutation — e.g. changing `InGameSettingsFiles` path helpers for WotLK scopes and breaking vanilla repair/snapshot paths, or editing catalog entries to "shared" wording.

- **Work items:**
  1. Freeze the catalogs as data: extract `WowVanillaSettingsCatalog.definitions` and the binding catalog tables into hash-pinned sources — either keep them in code with a committed digest of the rendered entries, or move to versioned assets `settings/catalog-vanilla-v2.json` whose sha256 is pinned in `tools/check_repo.py` (same mechanism as G3-6's catalog gate). `CATALOG_VERSION` never changes for vanilla; a content fix = new version + Decision Log entry.
  2. `InGameSettingsFiles`: refactor by *addition* — introduce a `WtfLayout` strategy (vanilla = today's paths verbatim; wotlk = per-realm/per-char routes from H4-5) selected by `ClientFlavor`, with the vanilla strategy's path functions byte-for-byte identical to today's outputs (unit-test: for a canonical client root + account/scope set, the file lists produced before and after the refactor are equal).
  3. `BotPopulationPolicy`: keep the classic constants as the vanilla values (`CHARACTERS_PER_BOT_ACCOUNT = 9` — the 9-char/account invariant comes from the pinned `RandomPlayerbotFactory`); per-flavor values (H3R2-1) enter as a `ClientFlavor`-keyed table whose vanilla row *is* today's constants. Golden test asserts the vanilla row equals the literal constants.
  4. `WowVanillaBindingCatalog` categories (`WowBindingCategory`) are never re-ordered or renamed — H4-2's WotLK vehicle/override categories and H4-11's per-flavor capture add new entries in per-flavor catalogs. A reflection test pins the enum ordinals/names the controller profile persists.
  5. UI copy surfaces fed by these catalogs (`InGameSettingsScreens`, `ControlsScreen`) keep vanilla strings until S7.7's flavor-aware sweep — the golden catalog tests double as the copy-freeze proof for the vanilla lane.

- **Upstream dependency:** none.

- **Verification:** `cd android && gradlew :app:testDebugUnitTest --tests "com.pocketrealm.ingame.*" --tests "com.pocketrealm.client.WowVanilla*"`: catalog digest pins green; `InGameSettingsFiles` before/after path-list equality for vanilla fixtures; `BotPopulationPolicy` vanilla-row equality. Device: vanilla WTF snapshot/restore round-trip (existing H4-5 repair journal tests) still passes untouched. `python tools/check_repo.py` carries the new digest gates.

- **Effort:** L (1-2 weeks).

- **Depends on:** none (H4-5/H4-6/H4R2-4/H3R2-1 are consumers).

- **Owner stage:** S7 (the golden-freeze work items 1-4 — catalog digest pins, `WtfLayout` vanilla-strategy path-equality tests, `BotPopulationPolicy` vanilla-row pin, binding-enum ordinal pins — are host-side and land in roadmap wave 0 beside G3-7's fixture capture, before the catalog/WTF rework they fence; item 5's copy sweep rides S7, which keeps all pins green).

---

## Coverage ledger

- [x] G3-1 — UserAccountStore flavor field lands as optional, never as a schema bump
- [x] G3-2 — Manifest readers accept both schemas; flavor rides as an optional field
- [x] G3-3 — Vanilla migration-ledger bytes stay byte-identical across per-flavor renames; golden SHA pinned
- [x] G3-4 — Supervisor journal codec: flavor as optional field, decode tolerant of pre-flavor journals
- [x] G3-5 — Generation-pointer pruning scoped per flavor; legacy `active.json` seeds the vanilla namespace
- [x] G3-6 — Addon registry ids and vanilla validator policies pinned byte-for-byte (incl. VOICEOVER_DATA)
- [x] G3-7 — Golden byte-tests for vanilla conf output; per-flavor projections keep vanilla byte-identical
- [x] G3-8 — Vanilla lore asset name kept; per-flavor corpora are new files
- [x] G3-9 — Snapshot manifests: never add a top-level key to schema 2 (or relax `length()` to `>=`)
- [x] G3-10 — Vanilla catalogs, bot-population policy, and WTF layout stay verbatim
