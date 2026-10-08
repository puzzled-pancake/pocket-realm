# LIVE-VERIFICATION-PLAN — spec for the future live bug-checker workflow

Assembled 2026-10-02 by the plan-assembler step of the live-verification authoring run
(`.zcode/workflow-runs/dwfrun-a109a3e5-49a6-43a2-8c54-d6b801bd357a.mjs`, ask#15);
**revised 2026-10-02 after the final coverage audit** (`python tmp/final-coverage-audit.py`
had reported 144/169 deliverable IDs cited on ≥1 embedded test row — the repair authored
the launch-boundary section file, the onboarding/supervisor-journal rows, the DESK-55
citation, the DEDICATED-SLOT disposition rule (§3.1) and the server-JVM heap pin (§7.2),
then re-assembled with recomputed hashes). This document is the complete spec a **future,
separate workflow** will execute: purpose and constraints (§1), the chosen environment
(§2), the user-artifact requirements manifest and the staging split (§3), the full CLI
test matrix — 281 test rows across 8 section files, embedded verbatim (§4) — the
realm-status browser build-then-test work item (§5), the bug-report format and
round-robin convergence rules (§6), and the resource bounds (§7). The companion copy for
the user is `docs/LIVE-VERIFICATION-REQUIREMENTS.md` (byte-identical to
`out/live-verification/requirements.md`).

**Relocation 2026-10-08 (W0):** the genuine clients moved from C:\Wow clients\ to E:\Wow clients\ (exe sha256s re-verified against the capture-manifest pins). Procedure rows below now stage from E:. Historical machine-state snapshots and recorded evidence keep their capture-time C: paths verbatim.

## Assembly provenance

Sections §2, §3, §4 and §5 embed these files **verbatim** (byte-for-byte; hashes computed
at assembly time). They come from `out/live-verification/` on the authoring run's working
tree (`C:/pocket_realm_complete`) — the only tree that holds them: the sibling
`E:/pocket_realm_complete/out/live-verification/` directory contains `campaign-inventory.md`
and execution logs but **none** of these eleven section files (`ls` + `cmp` run during the
first assembly; all eleven `cmp` calls report target-missing on E:).

| Plan § | Source file | Bytes | sha256 |
|---|---|---|---|
| §2 | `out/live-verification/environment.md` | 10,439 | `f9b828a23828bd627982536f986ff8920e6a17415ec5fb157b494e1c22d43bd3` |
| §3 | `out/live-verification/requirements.md` | 20,204 | `4a2be3f92ebc9d9c4fc70ccfcaf18d2940e478cdb9f5952a109c5f7f4f133c9a` |
| §4.1 | `out/live-verification/matrix-flavor-import.md` | 51,669 | `b059efd228d99e50369c9b0f2cb39a604dceaf3b04649498fd8ba048c58155b3` |
| §4.2 | `out/live-verification/matrix-server-db.md` | 72,042 | `658af978602561b243923e470cb2b7d4a0e341617c52fe20ee1d48845d1a8796` |
| §4.3 | `out/live-verification/matrix-bots-llm.md` | 62,566 | `cf2ff3853d5f28515be85ec0877764141c18089dad9436aad6e72ab9e497b49f` |
| §4.4 | `out/live-verification/matrix-multiplayer.md` | 64,151 | `9a32881a64b0140356700c00840bf627cef8800bf3781f93119a6786c7557c88` |
| §4.5 | `out/live-verification/matrix-timeframe-era.md` | 69,987 | `059404744405cbc4c01d032436601d06bd4827ff3b9caf69939fb4ad5c3cd235` |
| §4.6 | `out/live-verification/matrix-desktop-app.md` | 86,803 | `ffffbe658fa46463648942c3e694b69d08b235723a02eff3e1df4425c2372e0c` |
| §4.7 | `out/live-verification/matrix-bot-actors.md` | 79,699 | `b968ec6fa81925dbc21139835eca54b1175ff8744a1b85ee83018dd939977dc9` |
| §4.8 | `out/live-verification/matrix-launch-boundary.md` | 13,483 | `0e84386d0cf2ef314d5f92103acf78b37e00400a6a335d4c6e801a170b370724` |
| §5 | `out/live-verification/realm-status-browser.md` | 65,795 | `32664c49344112990d3b0f0bdbb04f4452040756b46a2091039b7860d5b2e1bd` |

Hashes recomputed after the final-audit repair: §4.1 (DESK-55 citation added to
LV-flavor-import-14), §4.6 (+rows 38–45/EX-111), §4.7 (rows 33/34/38 amended + round-12
section) and the new §4.8 differ from the pre-repair assembly; §4.2–§4.5, §2, §3 and §5
are byte-identical to their pre-repair selves. Recomputed AGAIN after the final-read
repair (2026-10-02, same day): §4.2 (LV-server-db-33 AVD clarification), §4.4
(MP:MOBILE-PARITY row citations), §4.6 (row-count line + table-break fix), §4.7 (DESK-61
row citations, round-list renumbering, final-audit section renumbered to §14) — the table
above carries the post-repair hashes; §4.1/§4.3/§4.5/§4.8, §2, §3 and §5 untouched by
this pass.

Authored fresh for this plan (grounded in the sources cited inline): §1, §3.1, the §4
framing, the §5 framing, §6 and §7. Everything else is the section files' own text,
including their evidence-class markers (**[R]** read / **[X]** executed / **[A]** ask-supplied
/ **[RAN]**), their per-round execution records, and their honesty notes. Each embedded file
keeps its own `#` H1 title line; the `## 4.N` headings above them are this plan's.

## How to read this plan

- §4's row tables are the executable contract: one row = one CLI-driven live test
  (`testId | feature | flavor(s) | exact CLI mechanism | required artifacts | pass
  condition | severity`). The bug-checker executes rows, it does not redesign them.
- Each matrix file also records what its design rounds already executed at design time
  (with quoted outputs). Those records are history, not substitutes: the bug-checker
  re-runs every row it executes and trusts only its own run's output as a result.
- Three row dispositions exist and the coverage ledger must keep them distinct
  (matrix-multiplayer.md header): artifact-free now-tests, ARTIFACT rows
  (require an R1–R7 artifact), and blocked-tracked rows (require a named repo/harness
  prerequisite — e.g. P-3 — and are unblocked by code, never by artifacts).

---

## 1. Purpose and constraint set

### 1.1 Purpose

The live bug-checker is a future, separate workflow (not this authoring run). It boots the
real stack — the headless Android emulator plus the Windows-host servers — and drives the
runnable rows of the §4 matrix against the real app and real servers, filing every
discrepancy as a finding in the §6 format, cycling the eight matrix areas round-robin
(launch-boundary joins the rotation after bot-actors — §4 framing, §6.2)
until convergence (§6.2). The design campaign only specified and spot-executed; the
bug-checker executes at scale and re-runs design-time passes as regression rows.

Two first-class outputs:

1. **Findings** — per §6.1, written under `out/live-verification/findings/`.
2. **Coverage ledger** — every campaign deliverable ID in
   `out/live-verification/campaign-inventory.md` (169 shipped deliverables) maps to at
   least one executed row, or to a recorded disposition: ARTIFACT-gated (artifact still
   absent), blocked-tracked (repo/harness prerequisite), or design-gap (unimplemented
   converged architecture; no test invented — matrix-multiplayer.md "Architecture gaps").

### 1.2 Constraint set (hard; every row of §4 was designed under these)

1. **Headless.** The emulator runs `-no-window -no-audio -no-boot-anim -gpu
   swiftshader_indirect` (§2). No window may open, no focus may be taken, nothing may
   render on the host desktop.
2. **Background, hours-long, must not take over the PC.** Guest RAM 2048 MiB default
   (6144 only when a row's recorded lane profile needs it), 4 guest vCPU, software GL so
   the host GPU is untouched, logs to files (`adb logcat -v time > file`, gradle
   `--console=plain`), everything driven by background processes (§2 "Resource profile"
   and "Constraints honored"). Bounded wall-clock per row (§7.3).
3. **CLI-only.** adb + gradlew + python + curl + the section files' scripts
   (`out/live-verification/scripts/{rpdrive.py,srp6_probe.py,ban_toggle.py}`). No
   screenshot driving, no manual GUI steps, no eyes-on verification (the feasibility
   auditor's standing rule during design; matrix files carry no eyes-on rows).
4. **Debug builds only.** `com.pocketrealm` debug build type; every staged variant inits
   from debug; minify disabled; **no release build is needed or built** (§2, citing
   `android/app/build.gradle.kts:1217,1269-1273,1306-1327`). Android unit/instrumented
   rows carry the mandatory `-PpocketAbi=x86_64` on this lane (matrix-timeframe-era.md
   round-2 note, hit live when absent).
5. **Sequential.** One emulator, one server lane, one feature group at a time (§2; §7.4).
6. **No test invention at execution time.** The bug-checker runs §4/§5 rows as written.
   If a row's mechanism proves impossible as written, that is itself a finding (§6.1
   class `row-invalid`); it never silently substitutes a different test.
7. **Honest evidence.** Every executed row's result records the exact command and the
   quoted output that justifies it (the section files' own [X]/[RAN] convention). A row
   without runnable evidence is not a pass.

---

## 2. Environment — the chosen environment, verbatim

The environment decision is embedded verbatim below (`out/live-verification/environment.md`,
hash in the provenance table). It answers the authoring ask's architecture question —
Windows-host servers + emulator app bridged by `adb reverse` — and contains, in its own
text: the **port map** (fixed, schema-pinned: 3724 auth / 8085 world / 3725 friend-access
provisioning / 8080 LLM lane, with the correction that the realm/world status surface has
no HTTP port of its own), the **adb reverse story** (`adb reverse tcp:3724 tcp:3724`,
`tcp:8085 tcp:8085`, plus `tcp:8080`/`tcp:3725` for the LLM and guest-side
provisioning legs), the resource profile, the honored constraints, the rejected
alternatives (headed AVD, physical RP6, desktop-lane-only, 16K/Legacy lanes), and the
operational caveats — notably that at design time **no emulator boot, APK build/install or
server boot had been executed end-to-end**: closing that gap is the bug-checker's first
work item (§7.5 boot gate).

# Live-Verification Environment Decision

Written 2026-10-02 by the environment-selector agent. Evidence class is marked per claim:
**[R]** = read in this session (path:line), **[X]** = command executed in this session
(command + output quoted), **[A]** = material the ask itself supplied.

## Decision

**Hybrid lane: Windows-host-native desktop lane hosts realmd/mangosd in-process; a
headless x86_64 AVD runs the Android debug app; `adb reverse` bridges guest loopback to
host services. Sequential feature tests, debug builds only.**

Concretely, on this host, today:

- **Emulator half** — `PocketDiff_x86_64` (launchable AVD, `emulator -list-avds` [X]) on the
  same system image the repo's recorded lane pins: `system-images\android-35\default\x86_64\`
  (PocketDiff config.ini [R]; identical `image.sysdir.1` + `tag.id=default` as recorded
  AVD-Modern-x86_64-v1 and AVD-Large-x86_64-v1 in tests/avd/*.json [R]). 6144 MiB guest RAM,
  4 vCPU, 40 G data, `hw.gpu.enabled=no`, snapshot fast-boot (`fastboot.forceFastBoot=yes`)
  [R]. Launch headless:
  `emulator -avd PocketDiff_x86_64 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save`
  (or `-memory 2048` to pin the recorded Modern-lane 2 GiB profile for the 25-bot tier).
- **Server half** — the desktop lane's own CLI gates, which boot the servers **in-process**
  in the desktop JVM on host loopback: `cd desktop && gradlew bootRealmd` (evidence:
  "in-process realmd; 127.0.0.1:3724 accepts", docs/WINDOWS_QUALIFICATION.md:20 [R]) and
  `gradlew bootWorld` ("world READY…; 8085 accepts", :22 [R]); `gradlew supervisorStartGate`
  drives the full db+realm+world start path through the app's own supervisor (:24 [R]).
  The realm DLLs are the `tools/build_win_realm_runtime.py` products (:17 [R]).
- **Bridge** — after emulator boot: `adb reverse tcp:3724 tcp:3724` then
  `adb reverse tcp:8085 tcp:8085` (add `tcp:8080` when exercising the LLM lane,
  `tcp:3725` only for guest-side friend-access tests). Port map below.
- **App half** — `com.pocketrealm` debug build only: `applicationId = "com.pocketrealm"`
  (android/app/build.gradle.kts:1217 [R]), debug build type `getByName("debug")`,
  `isMinifyEnabled = false` (:1269-1273 [R]); every staged variant inits from debug
  (:1306-1327 [R]). No release build is needed or built.

## Why this hybrid is VALID (the architecture question)

The chain closes in the repo's own code and docs:

1. The servers bind host loopback on fixed ports: realmd `RealmServerPort = 3724`
   (native/cmangos/src/realmd/realmd.conf.dist.in:132 [R]); world
   `WorldServerPort = 8085`, `BindIP = "127.0.0.1"`
   (android/app/src/test/resources/fixtures/g3/conf/mangosd.conf:7-8 [R]). The desktop lane
   runs them **in-process** and the qualification record proves loopback acceptance on
   3724/8085 (WINDOWS_QUALIFICATION.md:20,22 [R]).
2. The Android app in LOCAL mode targets **loopback only**:
   `LOOPBACK_ADDRESS = "127.0.0.1"` (RuntimeTopology.kt:34 [R]),
   "local mode requires loopback" (:187 [R]), "local launch endpoint must be loopback"
   (:303 [R]).
3. Therefore the guest app dials 127.0.0.1:3724/8085 **inside the emulator**, and
   `adb reverse` forwards exactly those guest loopback ports to the host services. Host-native
   servers + guest app + reverse bridge = one coherent world. No LAN bind, no firewall rule,
   no second machine needed — matching the "single host machine, no second peer" boundary the
   campaign records (out/live-verification/campaign-inventory.md:280 [R]).

### Port map (fixed, schema-pinned)

| Port | Service | Defined at | Hybrid use |
|---|---|---|---|
| TCP 3724 | auth / realmd | `REALM_PORT = 3724` (ServerRuntimeContract.kt:28 [R]; realmd.conf.dist.in:132 [R]) | `adb reverse tcp:3724 tcp:3724` — required for guest logon |
| TCP 8085 | world / mangosd | `WORLD_PORT = 8085` (ServerRuntimeContract.kt:29 [R]; mangosd.conf fixture :7) | `adb reverse tcp:8085 tcp:8085` — required for guest world entry |
| TCP 3725 | friend-access provisioning listener | `PROVISION_PORT = 3725` (ServerRuntimeContract.kt:40 [R]; bound only while a friend-access LAN_HOST session is up — wiki Accounts-Security-and-Networking.md:62,96 [R]) | host-side curl directly; `adb reverse` only for guest-side provisioning tests |
| TCP 8080 (loopback) | llama-server LLM lane | `DEFAULT_PORT = 8080` — "llama-server inside pocketrealm binds loopback on this fixed port" (LlmRuntimePolicy.kt:42-43 [R]) | `adb reverse tcp:8080 tcp:8080` for guest-side LLM tests; its `/health` + built-in web UI is the only HTTP status page in the stack |

**Correction to the ask's "status page port" premise:** the realm/world status surface has
**no HTTP port of its own**. Status telemetry is JNI-in-process (`statusNative`,
native/realm-runtime/src/realmd_runtime.cpp:327 [R]) rendered as JSON by
`ServerStatusJson` (desktop/src/main/kotlin/com/pocketrealm/server/ServerStatusJson.kt [R]);
its `listenPort` fields merely echo 3724/8085. DESK-127 ships that as the Diagnostics
"realm-status surface" (campaign-inventory.md:137 [R]), not as a web endpoint. So for
curl-driven health checks: TCP-connect probes against 3724/8085 (realmd answers a connect
with the SRP6 auth challenge — 0x1EC per campaign-inventory.md:43 [R] — so any byte back on
3724 = listener alive), plus logcat/gradle-gate output for app-side state; 8080 `/health`
only when the LLM lane is up.

## Resource profile (unattended, background, hours-long)

- Host verified live [X]: `emulator -accel-check` → "WHPX(10.0.26200) is installed and
  usable"; emulator 36.6.11.0 — byte-identical to `host.emulator_version` in all four
  tests/avd/*.json [R]; adb 1.0.41 (36.0.0-13206524) at C:\platform-tools; AMD Ryzen 7 7700
  8C/16T; 95 GB RAM, ~52 GB free (PowerShell CIM query).
- Caps that keep the PC usable: guest RAM 2048 MiB (recorded Modern-lane tier,
  `mem_total_kb 2532436` [R]) up to 6144 MiB (PocketDiff default); 4 guest vCPU;
  `-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect` (software GL — the AVD
  already ships `hw.gpu.enabled=no` [R], so nothing touches the host desktop GPU); snapshot
  fast-boot for fast cold starts. Server JVM + in-process servers ride host loopback and can
  be heap-capped; everything is driven by adb/logcat/curl/gradlew in background processes.
  Sequential feature tests only — one emulator, one server lane at a time.
- Storage tiers: PocketDiff's 40 G data partition covers the recorded Large-lane purpose
  ("full normal-play generation and 410-migration bot database" needs 8 GiB RAM + 30 G data —
  tests/avd/AVD-Large-x86_64-v1.json `lane_purpose` [R]). If that exact 8 GiB qualification
  profile is required, recreate `O11-Large-x86_64` (its `.avd` dir on disk is **empty** [X])
  rather than squeezing PocketDiff.

## Constraints honored

- **Background for hours, no PC takeover:** headless emulator + host-loopback servers; no
  window, no focus steal; logs to files (`adb logcat -v time > file`, gradle `--console=plain`).
- **CLI-only:** adb + scripts + curl + gradlew gates (bootRealmd/authGate/bootWorld/
  supervisorStartGate — WINDOWS_QUALIFICATION.md:20-24 [R]).
- **Debug only:** all staged variants init from the debug build type (:1306+ [R]); the vanilla
  staging gate skips un-staged flavors so the vanilla debug build is unchanged
  (campaign-inventory.md FIX:S1.2-8, :44 [R]).

## Alternatives rejected

- **Headed AVD** — zero fidelity gain over headless for CLI-driven checks; it risks stealing
  the desktop (window focus, GPU) on a PC that must stay usable. Same gate constraints either
  way. Rejected for the takeover risk.
- **Physical device** — the RP6 (`serial-redacted`) **is attached right now** [X: `adb devices`],
  but the ask rejects it because it is "not always attached" [A], and the campaign records the
  RP6-dependent waves (P3-6, P1-4, P1, H3-3/H5-9, DP12, device matrix/soaks) as externally
  blocked on absent hardware (campaign-inventory.md:277-278 [R]). Decisive additional fact:
  the repo's own connected-test gate **refuses non-emulator serials** unless the deliberate
  RP6 hardware-qualification path is taken — allowlist file + `-PpocketAbi=arm64-v8a` +
  non-emulator ANDROID_SERIAL (android/app/build.gradle.kts:105-134, task doc :1453 [R]).
  Emulator-only is the sanctioned default: "Physical, wireless, missing, and unrecognised
  serials are refused before deployment" (:107-112 [R]). Keep the RP6 as an occasional
  manual cross-check, never the unattended lane. (Also note inventory line 279: the
  instrumented androidTest lane was never run in any campaign round; the fail-closed emulator
  gate is the mechanism cited there.)
- **Windows-host-native desktop lane alone** — highest server fidelity (it IS the server
  half of the chosen hybrid) but it never exercises the Android app, which is the ask's
  subject. Used as the server half only.
- **16K-page (AVD-16K) and Legacy (API 28) lanes** — secondary rotation targets, not the
  default: 16K is the page-size qualification lane, API 28 the min-support lane (both PASS
  records in tests/avd/ [R]). Their `.avd` dirs on disk are also empty [X].

## Operational caveats (verified, not assumed)

- The four **recorded** lanes' AVDs (AVD-Modern, O11-Large, AVD-16K, AVD-Legacy) exist on
  disk only as empty `.avd` directories [X] and are not in `emulator -list-avds` [X]; the two
  **launchable** AVDs are `PocketDiff_x86_64` (API 35 default x86_64 — the recorded lanes'
  system image) and `PocketRealm_API28` (API 28 default x86_64, 2 G RAM) [X: config.ini
  reads]. Adopt PocketDiff as the Modern-lane stand-in; its RAM/data exceed the recorded
  Modern profile, which is safe for the sequential-test profile.
- "The infra agent's returned lanes": no lane manifest from a sibling infra agent exists on
  disk (sibling session exec dirs under C:\Users\<owner>\.zcode\cli\exec\ for this run are
  empty [X]); the lanes as listed in the ask [A] were verified directly against
  E:/pocket_realm_complete/tests/avd/ (read in full [R]) and the live host state [X].
- Not run (would be the next phase's work, not environment selection): no emulator boot, no
  APK build/install, no server boot was executed in this ask. The bridge recipe above is
  untested end-to-end on this machine as of this writing; its components (loopback binds,
  port constants, gate behavior, WHPX) are each individually verified as cited.

---

## 3. Requirements manifest and the staging split

The manifest is embedded verbatim below (`out/live-verification/requirements.md`, hash in
the provenance table): the seven user-provided artifacts **R1–R7** — genuine TBC 8606 and
WotLK 12340 enUS clients, the vanilla 5875 client, the `puzzled-pancake/mangos-tbc` +
`mangos-wotlk` forks, the MariaDB 11.5.2 Windows distribution, the two tuned GGUF chat
models, and the optional tunnel binaries — with per-artifact staging paths, hash gates,
the group × artifact matrix (G1–G8), and the "already in the repo — do not download" list.

### 3.1 The staging split — artifact-free first, artifact-gated per arrival

The bug-checker sorts §4's 281 rows into three dispositions and runs them in this order:

**Stage 0 — run-tree staging (once, before any row).** All §4 commands run from repo root
`E:\pocket_realm_complete`, but the eleven section files, `environment.md`, `requirements.md`,
`realm-status-browser.md` and `scripts/` exist only on the planning tree
`C:\pocket_realm_complete\out\live-verification\` (provenance table above). Before Stage A, the
workflow copies that directory into `E:\pocket_realm_complete\out\live-verification\` and
verifies every copied file against the provenance sha256 table — a mismatch stops the run
`infra-blocked`, never a warning. From then on the E: tree is the single authoritative run tree:
the rows' relative `python out\live-verification\scripts\…` invocations and the
findings/journal/evidence paths (§6.1, §7.3, §7.6) all resolve there.

**Stage A — artifact-free rows, first and always.** Every row whose required-artifacts
cell is `none` and that is not blocked-tracked. This is the majority of the matrix and it
starts with zero user input — e.g. matrix-bot-actors.md's verdict is "39 rows are runnable
now without user artifacts" of 43, and matrix-desktop-app.md records "every artifact-free
row is now executed except the two rpHost world-boot legs and the R3 artifact row". The
bug-checker derives the Stage-A set mechanically each round from the row tables (artifact
cell + ARTIFACT/LATER markers), never from memory.

**Stage B — artifact-gated rows, per artifact arrival.** A row marked ARTIFACT (or
LATER with a named Rn) becomes runnable only in the first round after its artifact is
**verified present** — using requirements.md's own staging checks, not a looser probe:
R1/R2/R3 = managed import whose `WoW.exe` matches the pristine MD5
(`57c5c030…`/`45892bde…`) or vanilla SHA-256 `b4756d38…`; R4 = both forks exist with
branch `pocket-realm` and their commit pins registered in `schemas/sources.json` +
`schemas/flavor.json`; R5 = `native/.build-win-x86_64/mariadb-provider/` passes
`build_win_realm_runtime.py --database-backend mariadb` fail-loud verification; R6 = both
GGUFs placed under `%LOCALAPPDATA%\PocketRealm\runtime\llm\models\` and accepted after
sha256 verification; R7 = tunnel binary installed **and** a second peer available (R7
alone does not make LV-multiplayer-19 runnable). One artifact unblocks exactly its
group × artifact matrix cells (requirements.md §4) — nothing else.

**Blocked-tracked rows — never artifact-unblockable.** Rows gated on named repo/harness
prerequisites (P-1…P-n) or recorded design-gaps (G-n) stay blocked until the named code
lands, regardless of artifacts (e.g. LV-bot-actors-41's engagement lane on P-3; the
multiplayer Architecture gaps). They are ledgered every round and never attempted.

**DEDICATED-SLOT rows — excluded from the unattended lane at every stage.** Rows marked
DEDICATED-SLOT (real WoW.exe window + Win32 auto-typing: LV-flavor-import-17, the
LV-flavor-import-40 acceptance legs, LV-desktop-app-34, LV-bot-actors-38, LV-desktop-app-42's
live relaunch leg) violate §1.2's headless/no-focus constraint **by design**, so artifact
arrival never promotes them into Stage B and the artifact re-check never queues them. They
run only in a separate, user-informed slot ("must run alone, never alongside the unattended
lane" per the row texts) and carry their own ledger disposition — never counted in the
unattended pass set, never counted for or against convergence (§6.2).

**Round-start artifact re-check.** Because the user may drop artifacts in mid-run, every
round begins by re-running the R1–R7 presence checks; newly verified artifacts pull their
Stage-B rows into that round's queue. The ledger records which artifact unblocked each
row and when.
# Live-Verification Requirements Manifest — user-provided artifacts

Compiled 2026-10-02 by the requirement-analyst step of the live-testing workflow authoring run.
Scope: every artifact **the USER must obtain or create** so a future live-testing workflow can run
**all** groups on this Windows host, plus an explicit list of what is **already in the repo** and
must NOT be re-downloaded.

**Sources read** (all paths E: = `E:/pocket_realm_complete` unless noted):

| Source | What was taken from it |
|---|---|
| `docs/FEATURE-PARITY-GAPS.md` §0/§3 (E:) | corrections C1/C2/C6/C7 (realmbuilds, MPQ sets, pristine exe MD5s, not-LAA); §3 verification record (puzzled-pancake fork pin caveat) |
| `out/live-verification/campaign-inventory.md` (C: working tree) | "Externally blocked (need genuine clients / devices / forks)" — the gap IDs each lane unblocks (:270-280); MP:TUNNEL-LANE (Tailscale recommended, cloudflared documented, :246); mariadb-lane + llm-lane + multiplayer rows |
| `docs/DESKTOP-PARITY-GAPS.md` (C: copy) | register headings: DESK-4 = gamepad (:490), **DESK-5 = MariaDB engine lane** (:545), DESK-9 = embedded LLM lane (:1119) |
| `schemas/sources.json` (E:) | per-flavor pins: cmangos-core-tbc / cmangos-core-wotlk `status:"unresolved"` with fork-creation status_note; playerbots-tbc/wotlk `registered` @ 7e2cd2fb; llama-cpp-6d05498 entry |
| `schemas/flavor.json` (E:) | tbc/wotlk `qualified:false`, `server_core/db_content fork/commit:null`, realmd accepted_builds `8606`/`12340`, C2 MPQ-set notes, vanilla client sha/size |
| `tools/build_win_realm_runtime.py` (E:) | MariaDB provider staging contract (:445-548: "a PHYSICAL artifact the lane cannot synthesize"; `WIN_PROVIDER_ID="mariadb-11.5.2-windows-x86_64"` :464; staging dir :59) |
| `tools/build_win_llama_server.py` + `native/llm/lockfile-win-x86_64.json` (E:) | llama-server.exe is **built in-repo** from pinned source; toolchain + artifact sha256 |
| `android/app/src/main/java/com/pocketrealm/llm/LlmModelRegistry.kt` + `desktop/.../DesktopLlmModelStaging.kt` (E:) | GGUF model pins; the two tuned models have `url=""` → user-supplied |
| `desktop/.../DesktopMariaDbProvider.kt` (E:) | `STAGED_ROOT_REPOSITORY_PATH = "native/.build-win-x86_64/mariadb-provider"` (:259) |

**Checks run during this ask** (existence/pin checks via shell; external availability via live web fetch, 2026-10-02):

- `ls E:/pocket_realm_complete/native/.build-win-x86_64` → **no** `llama-staging/`, **no** `mariadb-provider/` (both lanes unstaged).
- `ls E:/pocket_realm_complete/schemas` → `realm-runtime-lockfile-sqlite-win.json` **exists** (vanilla Windows runtime lane locked); **no** `realm-runtime-lockfile-mariadb-win.json`, **no** `database-provider-lockfile-win.json` (MariaDB lane never staged/built).
- `ls E:/pocket_realm_complete/native/.providers/llama-cpp-6d05498…` → pinned tarball cached (36,865,467 B); source tree present at `native/.deps/src/llama/6d05498…/`.
- `git -C native/cmangos log -1` → `ce83805d48f9c98b2af617096be5347acb8a1f17` (= sources.json pin); `git -C native/playerbots log -1` → `7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d` (= pin).
- `find %LOCALAPPDATA%/PocketRealm/content/client/generations` → **empty**; `ls "C:/Wow clients"` → absent; bounded `find` for `Wow.exe` under `C:/Users/<owner>` (depth 4) and `E:/` (depth 3) → no hits. No WoW client of any flavor is staged on this machine.
- `find … -iname "*.gguf"` over E:/repo/tmp + Downloads/Documents → only llama.cpp vocab-test ggufs; **no production chat model on disk**.
- Live web: `api.github.com/users/puzzled-pancake/repos` → repos include `mangos-classic`, `playerbots`, `pocket-realm`; **`mangos-tbc` and `mangos-wotlk` DO NOT exist**. `anzz1/wow-client-checksums` → 8606 MD5 `57c5c03097103e15f9abe2803aebdc3c`; 12340 MD5 `45892bdedd0ad70aed4ccd22d9fb5984` (identical across locales). `archive.mariadb.org/mariadb-11.5.2/winx64-packages/` → `mariadb-11.5.2-winx64.zip` (87 MB) + `sha256sums.txt` + `.asc`. `cloudflare/cloudflared/releases` → latest 2026.9.3, asset `cloudflared-windows-amd64.exe` (sha256 `f096265ec2fcbe9bb6e2d64268db167ced3fcbb83d894bdb9e2fcdb26f2ea7e2`). `tailscale.com/download` → official Windows client page.
- No builds/tests were executed: this ask is a requirements compile.

---

## 1. Test groups assumed

The ask did not enumerate the workflow's groups; these are derived from the campaign record's
blocked lanes (`campaign-inventory.md:270-280`) and the DESK lanes they exercise:

| # | Group | Live behavior |
|---|---|---|
| G1 | Client qualification & import (w5 lane) | import 8606/12340 folders/archives; VAL gates, MPQ exact-set, exe hash cross-check |
| G2 | TBC server boot & play | qualified TBC client vs forked mangos-tbc boot (S2-lane), realmbuilds 8606 auth |
| G3 | WotLK server boot & play | qualified 12340 client vs forked mangos-wotlk boot, realmbuilds 12340 |
| G4 | Vanilla regression live | 5875 client + in-repo vanilla lane (must not regress; G3-x guards) |
| G5 | MariaDB provider lane (DESK-5/47/78/106) | dual-provider switch, mariadbd.exe lifecycle, migration ledger, ban-op pragma fix (finding 5.34) |
| G6 | Embedded LLM lane (DESK-9/28) | llama-server.exe sidecar + staged GGUF + world chatter |
| G7 | LAN multiplayer | LAN_HOST/join, guest bans, provisioning |
| G8 | Tunnel lane (optional) | Tailscale/cloudflared advertise + friend join (MP:TUNNEL-LANE) |

Groups the artifacts below can **never** unblock on this host (need hardware/people, per
`campaign-inventory.md:277-280`): RP6 physical-device measurements (w6), device-matrix soaks +
`J2-7` conformance soak, Android instrumented lane (`TF-9`, G3 device pins), `W2-4` N≥2-human LAN
soak, real two-machine tunnel join.

> **Scope annotation (added 2026-10-02, audit round 1 — recorded so the ruling travels with the
> manifest):** the "Android instrumented lane" exclusion above refers to the *physical-device*
> instrumented lane (RP6). **Emulator-instrumented rows ARE in scope for the unattended lane**:
> the governing environment decision rules "Emulator-only is the sanctioned default" — the
> fail-closed serial gate (android/app/build.gradle.kts:105-134) refuses non-emulator serials and
> passes emulators, and the headless PocketDiff_x86_64 AVD is the chosen lane
> (environment.md:105-122). The `am instrument` / WorldConsoleRelay rows in the live-verification
> matrices run against that emulator only; RP6 remains excluded. Basis: environment.md's recorded
> decision plus audit round 1's directive; no human owner signed a new ruling beyond those.

---

## 2. REQUIRED artifacts (user must obtain / create)

### R1. Genuine TBC 2.4.3 **enUS** client — build **8606**

- **Why:** the tbc flavor is `qualified:false` and every tbc live group starts from w5
  qualification: pristine-MD5 cross-check + SHA-256 registration (C6), exact-set MPQ
  qualification (C2; unknown `patch-*.mpq` = VAL-04), locale observation, login/char-select
  captures, TOS/EULA run, render/sound QA. Unblocks: **G1, G2** (and every tbc-dependent leg).
  Blocked gap IDs carried by the campaign for this artifact: `G2-5, H2R2-3, H2-8, P1-5, H5R2-8,
  H4R2-1, H4R2-11, H4-10, H4R2-9, G2-4, H3R2-7, H2-13, H2-14, H4-9, J2/J2-7 (capture groundwork)`
  (`campaign-inventory.md:276`).
- **Where to get:** Blizzard no longer distributes 2.4.3; use the user's own install media or a
  community archive of the original enUS 2.4.3 installer. **Provenance does not matter — bytes
  do:** the import is accepted only if `WoW.exe` matches pristine MD5 `57c5c030…`
  (`57c5c03097103e15f9abe2803aebdc3c` per anzz1/wow-client-checksums, verified live 2026-10-02).
- **Form:** unpacked folder, `.zip/.7z/.rar`, or Inno Setup installer (import lanes DESK-2/35/64/65).
  Must be **unmodified**: no LAA/d3d9ex/repack exes (H2R2-3 negative vectors), no
  Repair.exe/Launcher.exe in the managed copy (G2-6). Realmlist target build: `8606`
  (`realmbuilds`, flavor.json). Expected MPQ root set (C2): `common, expansion, patch, patch-2` +
  `Data\enUS\` locale/speech set.
- **Storage:** plan for ~25 GiB class budget (register P3-2 TBC row).

### R2. Genuine WotLK 3.3.5a **enUS** client — build **12340**

- **Why:** same as R1 for the wotlk flavor; additionally the memory-envelope facts (C7: not
  large-address-aware, 2 GB VA) get their live datapoint only from this client. Unblocks: **G1,
  G3**. Same campaign gap-ID list as R1.
- **Where to get:** user's own media / community archive of the original enUS 3.3.5a installer;
  verify `WoW.exe` MD5 `45892bde…` (`45892bdedd0ad70aed4ccd22d9fb5984`; identical across all
  locales per anzz1/wow-client-checksums) — exe ~12.6 MB (C6).
- **Form:** as R1. Expected MPQ root set (C2): `common, common-2, expansion, lichking, patch,
  patch-2, patch-3` (**no patch-4**) + `Data\enUS\` set; tolerated auxiliaries `base-enUS`,
  `backup-enUS`. Realmbuilds: `12340`.
- **Storage:** ~35 GiB class budget (P3-2 WotLK row); extracted tree ~16-17 GB.

### R3. Vanilla 1.12.1 client — build **5875** (likely already owned; NOT currently staged here)

- **Why:** group **G4** (vanilla regression live) and every vanilla control run needs the user's
  1.12.1.5875 client — it is user-supplied by design (`flavor.json`: "user-supplied; PE/build fast
  scan … full managed import") and never bundled (licensing; `sources.json` cmangos-db
  redistribution note: client-derived data comes only from the user's managed import).
- **Status on this machine:** **absent** — `%LOCALAPPDATA%/PocketRealm/content/client/generations`
  is empty, `C:\Wow clients` staging folder doesn't exist, bounded Wow.exe scan negative (checks
  listed in the header). If the user has the archive from earlier vanilla live testing, place it in
  the staging folder; otherwise obtain it as for R1/R2.
- **Gate values:** exe size 4,775,986 B, SHA-256 `b4756d38ef207c02ed651f4952bd89a70b4857b73a33413339e1b285b28d2dc7`
  (`flavor.json` vanilla `wine.known_exe_sha256`; `tools/install_client_windows.ps1`
  `EXPECTED_EXE_BYTES`). Sourcing helper: `tools/install_client_windows.ps1 -StagingRoot "E:\Wow clients"`.
- **Unblocks:** G4 only.

### R4. GitHub forks — `puzzled-pancake/mangos-tbc` + `puzzled-pancake/mangos-wotlk`, branch `pocket-realm`

- **Why:** the tbc/wotlk server cores cannot be fetched, pinned, or built until the forks exist.
  `schemas/sources.json` `cmangos-core-tbc`/`cmangos-core-wotlk`: `status:"unresolved"`, commit
  `null`, status_note — *"the fork (puzzled-pancake/mangos-tbc) and its commit pin land with the
  wave-0 fork registration; tools/check_sources.py reports PENDING (expected-loud, non-fatal) until
  then, and building the tbc lane fails loud naming this entry."* Campaign records the same:
  forks are externally blocked — `C4` (fork-creation half), `H3-1` (registration half)
  (`campaign-inventory.md:275`).
- **Verified 2026-10-02 (live):** `puzzled-pancake` has `mangos-classic` and `playerbots` forks but
  **no `mangos-tbc`, no `mangos-wotlk`**.
- **What the user does:** fork `github.com/cmangos/mangos-tbc` and `github.com/cmangos/mangos-wotlk`
  under `puzzled-pancake`; create branch `pocket-realm` in each; land the port-plan S2 adaptation
  commits; register the resulting commit pins in `schemas/sources.json` + `schemas/flavor.json`
  (wave-0 fork registration). Note: **`tbc-db`/`wotlk-db` are NOT forks** — they are pinned from
  upstream `cmangos/tbc-db` / `cmangos/wotlk-db` at S4 (sources.json `branch:"master"`, commit pin
  is an S4 decision); no user artifact there.
- **Unblocks:** **G2, G3** (and tbc/wotlk qualification legs that need a running server).

### R5. MariaDB 11.5.2 Windows x86_64 distribution (DESK-5/47/78 lane)

> Label correction: the ask called this the "DESK-4 lane"; DESK-4 is gamepad/controller
> (`DESKTOP-PARITY-GAPS.md:490`). The MariaDB engine lane is **DESK-5** (:545), with DESK-47/78/106
> as the provider/dual-provider rows.

- **Why:** the Windows provider distribution is *"a PHYSICAL artifact the lane cannot synthesize"*
  — the build fails loud rather than fabricating a server lane (`tools/build_win_realm_runtime.py`
  :445-466). The desktop engine spawns real `mariadbd.exe`/`mariadb.exe` (`DesktopMariaDbEngine.kt`
  :45-58) and resolves staging at `native/.build-win-x86_64/mariadb-provider/`
  (`DesktopMariaDbProvider.kt:259`). Today that directory **does not exist**, and neither
  `realm-runtime-lockfile-mariadb-win.json` nor `database-provider-lockfile-win.json` exists — the
  lane has never been staged. Unblocks: **G5** (and the MariaDB marker leg of timeframe overlays).
- **Where to get (official, verified live):** `https://archive.mariadb.org/mariadb-11.5.2/winx64-packages/`
  — `mariadb-11.5.2-winx64.zip` (87 MB; `.msi` also available), with `sha256sums.txt` and GPG
  `.asc` signatures. (11.5.2 is the exact provider id the lane pins:
  `WIN_PROVIDER_ID = "mariadb-11.5.2-windows-x86_64"`, `build_win_realm_runtime.py:464`.)
- **What to stage** (per `verify_mariadb_provider_staging`, `build_win_realm_runtime.py:468-548`):
  into `native/.build-win-x86_64/mariadb-provider/` — `bin/mariadbd.exe`, `bin/mariadb.exe`,
  `bootstrap.sql`, `migration-manifest.json` (with an `entries` array), and `runtime-manifest.json`
  (schema 1, `provider:"mariadb-11.5.2-windows-x86_64"`, `abi:"windows-x86_64"`, `bootstrap_sha256`,
  `executables{mariadbd,mariadb}` each with matching `sha256`/`size`/staging-relative `name`). Then
  `build_win_realm_runtime.py --database-backend mariadb` verifies fail-loud, and
  `--write-lockfile` records `schemas/database-provider-lockfile-win.json` +
  `realm-runtime-lockfile-mariadb-win.json`.

### R6. Tuned GGUF chat models ×2 (DESK-9/28 LLM lane)

- **Why:** the registry pins the product's tuned models with **empty URLs** — the app cannot
  download them (`LlmModelRegistry.kt`: `gemma4-E2B-TUNED-q4_0.gguf` `url=""` :80-83, sha256
  `e267e9793bac7db4340103b840bce8b52412ec3545827160b449119379853665`; `qwen35-08b-CLEAN-tuned-q4_0.gguf`
  `url=""` :112-115, sha256 `d87581a7ddd118f3193073226748881ea5fcbf48af40676e50071574b5e1f7aa`).
  No `.gguf` chat model exists on this machine (checked). Unblocks: **G6**.
- **Where to get:** the user's own training outputs (the project's tuned GGUFs — arm1b/epoch-3
  Qwen, tuned Gemma per registry comments). Place each file at
  `%LOCALAPPDATA%\PocketRealm\runtime\llm\models\<fileName>`; the lane accepts a pre-placed copy
  only after sha256 verification (`DesktopLlmModelStaging.kt:73-79`).
- **Not needed:** the third registry entry `gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf` self-downloads from
  `huggingface.co/unsloth/gemma-4-E2B-it-qat-GGUF` (registry :141-147) — needs network on first
  download, no user action.

### R7. Tunnel binaries — OPTIONAL (group G8)

- **Why:** MP:TUNNEL-LANE verifies the advertise/join lane over a real tunnel; the design
  recommends **Tailscale/ZeroTier** and documents **cloudflared** (`campaign-inventory.md:246`).
  The app never spawns these binaries — they appear only as copy/recipes
  (`DesktopRuntimeBackend.kt:514-678`) — so they must be installed at OS level by the user.
- **Where to get (verified live):** Tailscale — `https://tailscale.com/download` (Windows client);
  cloudflared — `https://github.com/cloudflare/cloudflared/releases`, asset
  `cloudflared-windows-amd64.exe` (2026.9.3 sha256 `f096265ec2fcbe9bb6e2d64268db167ced3fcbb83d894bdb9e2fcdb26f2ea7e2`).
  ZeroTier — `https://www.zerotier.com/download/` (alternative; not fetched this session).
- **Caveat:** a *meaningful* tunnel test also needs a second peer (second machine/account) — that
  is a harness constraint (§1), not satisfied by the binary alone. Skip R7 if G8 stays out of scope;
  G7 (plain LAN) needs none of these.

---

## 3. NOT needed — already in the repo (do not download)

| Item | Evidence it is already in-repo |
|---|---|
| **llama.cpp source / "pinned llama.cpp build"** (ask candidate) | Pin is the SOURCE, not a binary: tarball cached `native/.providers/llama-cpp-6d05498314….tar.gz` (36,865,467 B) + staged tree `native/.deps/src/llama/6d05498…/`; `sources.json` entry `llama-cpp-6d05498`. The exe is **built in-repo** by `tools/build_win_llama_server.py` (CPU-only, MSVC or WinLibs MinGW) and sha-pinned in `native/llm/lockfile-win-x86_64.json` (expected `llama-server.exe` 29,898,240 B, sha256 `850697f8…`). The exe is **not yet built** on E: (`llama-staging/` absent) — that is a build action, not a user download. |
| **playerbots** (all flavors, one pin) | `puzzled-pancake/playerbots` fork exists (verified live); submodule `native/playerbots` checked out at exactly `7e2cd2fb…` (git log run this ask); tbc/wotlk entries `registered` in sources.json — only the submodule *checkouts* remain, which land with R4's registration, not a download. |
| **Vanilla core + DB** | `native/cmangos` @ `ce83805d…` (git log run this ask; = sources.json/flavor.json pin), `native/classic-db` @ `be1a5206…` per sources.json. Windows vanilla runtime lane already built/locked: `schemas/realm-runtime-lockfile-sqlite-win.json` exists. |
| **tbc-db / wotlk-db content repos** | Fetched by the build when the S4 commit pins land; `sources.json` `cmangos-db-tbc/wotlk` pin from upstream `cmangos/*-db` — no fork, no user artifact. |
| **Android/glibc MariaDB runtimes** | Staged with lockfiles: `schemas/mariadb-runtime-lockfile.json` (x86_64 glibc 11.5.2), `…arm64-v8a.json` (Termux bionic 12.3.2); trees under `native/.build-arm64/mariadb-arm/`, `tools/stage_mariadb_*.py`. These are for Android, not the Windows desktop lane. |
| **Checksum corpus** | `anzz1/wow-client-checksums` is a read-only reference; values are transcribed into `flavor.json` at qualification — *"no pin required"* (`FEATURE-PARITY-FIX-PLAN.md:1434`). |
| **vanilla-tweaks, extractors, sqlite seam, wine tooling, vcpkg deps** | `schemas/vanilla-tweaks-lockfile-win.json` + `realm-runtime-lockfile-sqlite-win.json` exist; `native/win-deps/vcpkg_installed` present; extractor/o11 lockfiles staged (`PORT-D36`). |
| **The WoW clients' checksum values** | Already recorded as prefixes in `flavor.json` (`57c5c030…`, `45892bde…`) — only the R1/R2 full hashes are computed at qualification. |

---

## 4. Group × artifact matrix

| Group | R1 8606 | R2 12340 | R3 5875 | R4 forks | R5 MariaDB | R6 GGUF | R7 tunnel |
|---|---|---|---|---|---|---|---|
| G1 client qualification | **✔** | **✔** | ✔ | — | — | — | — |
| G2 TBC boot | **✔** | — | — | **✔** | optional¹ | — | — |
| G3 WotLK boot | — | **✔** | — | **✔** | optional¹ | — | — |
| G4 vanilla regression | — | — | **✔** | — | optional¹ | — | — |
| G5 MariaDB lane | — | — | — | — | **✔** | — | — |
| G6 embedded LLM | — | — | — | — | — | **✔** | — |
| G7 LAN multiplayer | — | — | ✔ (any client) | — | optional¹ | — | — |
| G8 tunnel (optional) | — | — | ✔ | — | — | — | **✔** + 2nd peer |

¹ tbc/wotlk/vanilla boots run on the default SQLite backend; the MariaDB backend is its own lane.

---

## 5. Honesty notes

- No build, import, or test gate was executed for this manifest; every "absent/present" claim is an
  `ls`/`find`/`git log` result run this ask (commands in the header), and every external-URL claim
  was fetched live on 2026-10-02.
- The workflow's group list (§1) was **derived** from the campaign record; the ask did not define
  it. If the workflow's groups differ, re-map §4 accordingly — the artifact set itself is pinned by
  the lane code and docs cited per row.
- Client "where to get" for R1/R2/R3 cannot name an official source (Blizzard distributes none of
  these builds); the acceptance gate is the pristine-MD5 cross-check, so any source whose bytes
  match qualifies. The community-checksum reference is `anzz1/wow-client-checksums` (verified live;
  matches register C6 exactly).
- Hardware/people-dependent groups (RP6 device, device matrix, Android instrumented lane, N≥2-human
  soaks, true two-machine tunnel join) are **out of artifact scope** — no download unblocks them
  (`campaign-inventory.md:277-280`).

---

## 4. The full CLI test matrix — all 8 section files, verbatim, in design order

The first seven section files are embedded verbatim below in the canonical order the design
run fixed (`matrixDesignerDefs` in
`.zcode/workflow-runs/dwfrun-a109a3e5-49a6-43a2-8c54-d6b801bd357a.mjs`), which is also the
order requirements.md's groups G1–G8 unfold in. §4.8 (`matrix-launch-boundary.md`) was
authored afterwards by the final-audit repair round (2026-10-02 — the coverage audit's
`desktop-launch-boundary` finding) and is appended last; the seven-file design order is
unchanged and remains the round-robin's area order (§6.2), with launch-boundary joining the
rotation after bot-actors. Row counts below are `LV-*` rows with a full 7-cell table format
per file (counted during this assembly, audit-script rule).

| § | Section file | Area | Table rows |
|---|---|---|---|
| §4.1 | `matrix-flavor-import.md` | flavor foundation, client import, per-flavor data prep (vanilla now; TBC/WotLK artifact-gated) | 44 |
| §4.2 | `matrix-server-db.md` | server runtime, MariaDB/SQLite lanes, migration ledger, snapshots/backups, supervisor journal | 50 |
| §4.3 | `matrix-bots-llm.md` | bot upgrades, LLM chatter lane, guest bans, friend access | 44 |
| §4.4 | `matrix-multiplayer.md` | LAN play, tunneling, remote accounts, join matrix, realm-status surface | 23 |
| §4.5 | `matrix-timeframe-era.md` | content-patch timeframe control, both platforms, era-policy linkage | 23 |
| §4.6 | `matrix-desktop-app.md` | desktop app surfaces: settings editor, add-ons, diagnostics, support bundles, updates (+ final-audit round: onboarding/autologin, single-instance, scanner, foreground, spent-world, save-exit, account-entry, login-detection) | 46 |
| §4.7 | `matrix-bot-actors.md` | **in-game bot-actor testing — playerbots + RpWowClient protocol probes as the PLAYING testers** (leveling, combat, dungeon groups, quests, tradeskills, AH, mail, deaths/corpse runs), asserted via SQL world-state queries and mangosd logs through the console relay (+ DESK-30/136/138 gate legs amended into rows 33/34/38) | 43 |
| §4.8 | `matrix-launch-boundary.md` | **launch-boundary (final-audit coverage group):** tweaks patcher/publishVerified, Config.wtf overlay, realmlist projection/teardown, NearbyInteract knobs, audio mute (DESK-13/27/46/50/51/58/89/132/139) | 8 |

**281 rows total.** The bot-actor section (§4.7) is the capstone: it is where the game is
actually *played* — its two assert channels (SQL world-state queries and console-relayed
mangosd logs) are the ground truth every other area's surface behavior can be cross-checked
against. Its rows 17/18 (LLM batteries) and 38 (real 1.12.1 client) are artifact-gated; row
40 (TBC/WotLK bot play) additionally needs R1/R2 + R4; row 31's engagement lane is
blocked-tracked on P-3 (its §0 verdict has the full split).

Where a section's own text marks rows **ARTIFACT** or blocked-tracked, §3.1's staging rules
apply — and rows marked **DEDICATED-SLOT** stay out of the unattended lane at every stage.
Where a section records design-time executions, those are the design run's history — the
bug-checker's own run is the only result that counts (§1.2 item 7).

### 4.1 — section file `matrix-flavor-import.md` (embedded verbatim)

# Live-Test Matrix — Flavor foundation, client import & per-flavor data prep
Area: `flavor-import` · Round 4 design (round-1 build; audit rounds 1-3 fixes; re-validated each round) · 2026-10-02

Covers the register sections `flavor-foundation` (7), `desktop-flavor-routing` (4),
`desktop-import-generations` (18), `desktop-data-prep` (4) — campaign-inventory.md:40-48, :60-65,
:101-111, :176-191, :228-230. Vanilla tests run NOW (artifact-free); TBC/WotLK rows that need the
user's clients/forks are marked **ARTIFACT** and run in the later stage
(requirements.md R1/R2/R3/R4).

## Legend

- **✅ design-run** — this exact mechanism was executed during this design ask (output cited in
  §Checks-run-below); the live-test round re-runs it and asserts the recorded contract.
- **ARTIFACT Rx** — requires user-provided artifact R1 (TBC 8606 client), R2 (WotLK 12340 client),
  R3 (vanilla 5875 client), R4 (puzzled-pancake/mangos-tbc + mangos-wotlk forks) per
  requirements.md §2. Runs in the later stage.
- **DEDICATED-SLOT** — the row spawns the real WoW.exe window (Win32 focus + optional
  auto-typing), violating the environment's no-takeover contract (environment.md:98-99) while
  other rows run. Scheduled only in a dedicated, user-informed slot; unattended coverage of the
  same launch contracts rides the rpHost proto/wire-client rows (bot-actors-04, multiplayer-06),
  which need no window. Refusal rows that fail at the identity gate BEFORE the spawn
  (DesktopRuntimeBackend.kt:1786-1795) are explicitly window-free and stay in the unattended lane.
- **Budgets** — the two long rows carry wall-clock caps: -12 ≤45 min (parallelism capped to 8
  logical CPUs via `start /affinity 0xFF`, since the tool hardcodes `-j {os.cpu_count()}` =
  16 on this 8C/16T host) and -25 ≤4 h (outer `timeout 14400`, matching the tool's own per-step
  cap). -25 runs in its own slot; its persisted outputs feed -26/-27/-30/-31, never concurrently.
- All commands run from repo root `E:\pocket_realm_complete` (bash: `/e/pocket_realm_complete`);
  desktop gates are `cd desktop && gradlew ...` (docs/WINDOWS_QUALIFICATION.md §1 gate table).
  Python is on PATH (every `python tools/…` below ran in this session).
- **Emulator PATH note (applies to every launch line in this matrix):** bare `emulator`/`adb` are
  NOT on this host's Git-Bash PATH; the executor harness prepends
  `%LOCALAPPDATA%\Android\Sdk\emulator` and `…\Sdk\platform-tools` before launching — the binaries
  exist at `C:\Users\<owner>\AppData\Local\Android\Sdk\emulator\emulator.exe` (verified this ask:
  `"…\emulator.exe" -list-avds` → `PocketDiff_x86_64`, `PocketRealm_API28`) and
  `C:\platform-tools\adb.exe`, matching the SDK-constant resolution in
  tools/run_differential_parity.py:42-43.
- Severity: **P1** = lane-blocking gate; **P2** = core behavior; **P3** = determinism/hardening.

## Test matrix

| testId | feature (deliverable IDs) | flavor(s) | exact CLI mechanism (real commands) | required artifacts | pass condition | severity |
|---|---|---|---|---|---|---|
| LV-flavor-import-01 | Flavor registry & qualified-flag gate (S0:D6-D18a, C1-C9, DEC:D1-D37) | all 3 | `python tools/check_flavor.py` | NONE (artifact-free now-test) ✅design-run | rc=0; stdout `check_flavor: OK (3 flavors: vanilla=qualified, tbc=unqualified, wotlk=unqualified)` | P1 |
| LV-flavor-import-02 | single_qualified_lane loud-fail (D14/D9 manifest half) | vanilla+tbc | `python -c` copy of `schemas/flavor.json` to `tmp/flavor-probe/flavor-two-qualified.json` with `flavors[tbc].qualified=true`, then `python tools/check_flavor.py --manifest C:\pocket_realm_complete\tmp\flavor-probe\flavor-two-qualified.json` | NONE ✅design-run | rc=1; first error names `single_qualified_lane requires exactly one qualified flavor, got 2 (qualified ids: vanilla, tbc)` (exact output recorded in design run) | P1 |
| LV-flavor-import-03 | sources.json PEND-vs-DRIFT contract (FIX:S1.2-8) | all 3 | `python tools/check_sources.py` | NONE ✅design-run | rc=0 (expected-loud, non-fatal); table shows 6 PEND rows: `cmangos-core-tbc`, `cmangos-db-tbc`, `playerbots-tbc`, `cmangos-core-wotlk`, `cmangos-db-wotlk`, `playerbots-wotlk`; footer `6 per-flavor pin(s) pending registration — expected-loud`; vanilla core/db/bots rows OK at `ce83805d…`/`be1a5206…`/`7e2cd2fb…` | P2 |
| LV-flavor-import-04 | Per-flavor seed fail-loud on unresolved pins (FIX:S1.2-8) | tbc | `python tools/seed_realm_db.py --flavor tbc --out C:\pocket_realm_complete\tmp\flavor-probe\tbc-seed` | NONE ✅design-run | rc=2; stderr names BOTH: `flavor 'tbc' server_core pin is unresolved … (port plan S2 item 1)` and `the tbc SQLite seed is port plan S4 item 3 work … refusing to seed classic inputs under a tbc name`; no DB files written to the out dir | P1 |
| LV-flavor-import-05 | Per-flavor migration-ledger fail-loud + zero mutation (FIX:S1.2-8, F3-1) | tbc | `git -C E:/pocket_realm_complete status --porcelain > baseline.txt`; `python tools/stage_database_migrations.py --flavor tbc`; `git status --porcelain \| diff baseline.txt -` | NONE ✅design-run | rc=2; error `flavor 'tbc' server_core pin is unresolved … (port plan S2 item 1)`; git tree byte-unchanged (design run: `GIT-UNCHANGED`) — no partial `schemas/database-migrations-tbc.json` left behind | P1 |
| LV-flavor-import-06 | Vanilla migration ledger determinism + golden seal (F3-1, G3-3) | vanilla | `python tools/stage_database_migrations.py --flavor vanilla`; `git -C E:/pocket_realm_complete status --porcelain -- schemas/` (must be empty); `cmp schemas/database-migrations.json schemas/database-migrations-vanilla.json`; `python tools/check_ledger_golden.py` | NONE; golden half ✅design-run (`check_ledger_golden: OK (golden 3771fc35b4ceddbadd6f786d76b10902db319d07c7d6372156417e4e3d81dc68)` rc=0) | stage rc=0; `git status` on `schemas/` clean (deterministic byte-identical rebuild); `cmp` silent (alias == canonical); golden check rc=0 with the same 3771fc35… digest | P1 |
| LV-flavor-import-07 | Per-flavor SQLite-seam pins refuse before MSVC work (P2-6, D37) | tbc | `python tools/build_win_sqlite_seam.py --flavor tbc` | NONE ✅design-run | rc=1 BEFORE any compiler invocation (instant, no MSVC output): `ERROR: flavor 'tbc' has no registered per-flavor overlay set — re-derive the overlay definitions per branch first (port plan S2 item 3 / CP-2), then register them in PER_FLAVOR_OVERLAY_REGISTRATION` | P1 |
| LV-flavor-import-08 | Vanilla SQLite-seam build (P2-6 happy arm; DLL flavor-agnostic) | vanilla | `python tools/build_win_sqlite_seam.py --flavor vanilla`; then `pytest tests/test_win_sqlite_seam.py` | NONE (needs MSVC toolchain, present per WINDOWS_QUALIFICATION.md §1) | build rc=0; `native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll` exists; JNI export table derived from Kotlin shims verifies; pytest lane green | P2 |
| LV-flavor-import-09 | Generated conf-key matrix per flavor (BOT-CONFMATRIX S1 half, C9 excluded families) | vanilla, tbc | `python tools/generate_conf_matrix.py --flavor vanilla --list-surfaces`; `python tools/generate_conf_matrix.py --flavor tbc --list-surfaces` | NONE ✅design-run (both) | vanilla rc=0 listing 8 dist surfaces — 6 core-tree (mangosd, realmd, mangosd/mods, anticheat, ahbot core, playerbot core) + 2 bots-tree (playerbots/aiplayerbot, playerbots/ahbot); tbc rc=0 listing 7 dist surfaces - 5 core-tree (native/cmangos-tbc: mangosd, realmd, anticheat, ahbot core, playerbot core; the tbc tree ships no mangosd/mods conf, unlike vanilla's 6) + 2 bots-tree (playerbots-tbc/aiplayerbot, playerbots-tbc/ahbot); the pre-W0 rc=2 'conf dist files missing' refusal was the unmaterialized-submodule state (the six flavor submodules were materialized 2026-10-08, W0 item 4) | P3 |
| LV-flavor-import-10 | Authoritative repo gate incl. check_flavor + leak_sweep enforcement, PORT-D36 divergence binding (lockfile ↔ driver pin ↔ app constant ↔ flavor.json) (D12, PORT-D36) | all 3 | `python tools/check_repo.py` | NONE (not run during design — broad gate; live round runs it full-scale) | rc=0; `check_repo: OK (…)` summary line; no divergence errors naming `realm-runtime-lockfile-sqlite-win.json`, `DataPreparationStore.CMANGOS_COMMIT`, or `flavor.json server_core` | P1 |
| LV-flavor-import-11 | Lockfile-pin staleness fence before runtime use (DESK-115) | vanilla | `cd desktop && gradlew validateNativeRuntimePins --console=plain` (also rides every `gradlew test` per desktop/build.gradle.kts:237) | NONE | rc=0; pins-only verdict empty (win sqlite lockfile commits match schemas/sources.json); any drift fails with REMEDY copy, non-zero rc | P2 |
| LV-flavor-import-12 | Extractor staging for data prep (PORT-D36 rebuild leg; precondition of C-rows). **BUDGET: ≤45 min wall clock; parallelism capped to 8 logical CPUs** — the tool hardcodes `-j {os.cpu_count()}` (build_win_realm_runtime.py:649,661 = 16 on this 8C/16T host), so the cap is enforced from outside via CPU affinity; run pre-flight -37 first (finding 5.24) | vanilla | `timeout 2700 cmd /c "start /affinity 0xFF /b /wait python tools\build_win_realm_runtime.py --extractors --write-lockfile"` (0xFF = first 8 logical CPUs; outer 45-min kill; `--write-lockfile` is OPT-IN — its help reads "record the freshly built PE artifacts … into the backend's lockfile" — so it is passed explicitly to produce the asserted artifact, matching LV-server-db-23's pattern); then check `native/.build-win-x86_64/bin/x64_Release/Extractors/{ad.exe,vmap_extractor.exe,vmap_assembler.exe,MoveMapGen.exe}` exist; `pytest tests/test_win_lockfile.py` | NONE (MSVC toolchain) | rc=0 within budget; four extractor exes present; `schemas/realm-runtime-lockfile-sqlite-win.json` refreshed by the explicitly-passed `--write-lockfile`; submodule byte-pristine after run; pytest pins green | P2 |
| LV-flavor-import-13 | Archive/installer rank lane with synthetic (non-Blizzard) fixtures (DESK-2/35/64 rank half) | vanilla lane | `python scripts/smoke_archive_import.py` | NONE ✅design-run | rc=0; `smoke OK: client zip ranks OK, installer zip routes to the in-app installer lane` — client-shaped zip verdict OK, Blizzard-style installer routed to the in-app installer lane | P2 |
| LV-flavor-import-14 | Desktop importer/generation/scanner JVM lane incl. legacy active.json adoption (DESK-1/3/20/34/35/36/55/63/64/65/66/67, G3-5 pointer adoption; DESK-55 = this importer/generation/scanner lane's merged view, campaign-inventory.md:182) | vanilla | `cd desktop && gradlew test --tests "com.pocketrealm.importer.*" --tests "com.pocketrealm.client.DesktopClientScannerTest" --console=plain` | NONE | rc=0, 0 failures across DesktopClientGenerationStoreTest, DesktopImportJournalTest, DesktopImportStoragePlannerTest, DesktopImportWatchdogNoticesTest, ManagedClientImporterTest, ManagedClientImporterArchiveTest, RarArchiveSourceTest, DesktopDataPreparationStoreTest, DesktopClientScannerTest | P2 |
| LV-flavor-import-15 | Genuine-client staging rank verdicts (WoW.exe size + hash gate) (DESK-21 fast-scan half; requirements.md R3 gate values) | vanilla | `powershell -NoProfile -ExecutionPolicy Bypass -File tools/install_client_windows.ps1 -StagingRoot "E:\Wow clients" -RankOnly` | **ARTIFACT R3** (5875 client archive in `E:\Wow clients`) | rc=0 verdict table row for the archive: OK; findings list empty of size errors — `WoW.exe is 4775986 bytes` check passes (install_client_windows.ps1:90,96) | P2 |
| LV-flavor-import-16 | Genuine-client full extraction/import to managed Install root (DESK-63 folder lane twin; requirements.md R3 sourcing helper) | vanilla | same PS1 without `-RankOnly`: `powershell … -File tools/install_client_windows.ps1 -StagingRoot "E:\Wow clients" -InstallRoot "E:\Wow clients\Installed"`; then `dir /s /b "E:\Wow clients\Installed\WoW.exe"` | **ARTIFACT R3** | rc=0; extracted tree contains `WoW.exe` (4,775,986 B) + `Data\*.MPQ` set; source archive untouched | P1 |
| LV-flavor-import-17 | Folder-lane launch identity gate ACCEPTS qualified 5875 client (DESK-21/36/52/68 at launch; DesktopRuntimeBackend.kt:1790-1794) | vanilla | **DEDICATED-SLOT ROW (user-informed desktop takeover):** this row spawns the real WoW.exe window (rpHost `-PrpClient=1` → Win32 spawn + optional auto-typing) and must run alone, never alongside the unattended lane. `cd desktop && gradlew rpHost -PclientDir="E:\Wow clients\Installed\<client>" -PrpClient=1 --console=plain` (Python driver alternative: `tools/rp_harness/desktop_host.py` DesktopHost(client_dir=…); protocol `@@RP@@{…}` lines) | **ARTIFACT R3** | stack boots; client spawn attempt proceeds past the VAL gate (no `client identity refused:` line); scanner Result.supported=true with build=5875. Unattended play coverage of the same launch contracts rides the rpHost proto/wire-client rows (bot-actors-04, multiplayer-06), which need no window | P1 |
| LV-flavor-import-18 | Wrong-build client REFUSED at launch, no client-dir side effects (DESK-68/52; D10 refusal copy; H2R2-3 negative vector). **Window-free:** the identity gate throws BEFORE the spawn (DesktopRuntimeBackend.kt:1786-1795), so no WoW.exe window opens and the row stays in the unattended lane | tbc→vanilla lane | stage R1 client copy; `cd desktop && gradlew rpHost -PclientDir="<8606 folder>" -PrpClient=1 --console=plain`; capture stderr; then `git status`-equivalent: verify client dir entry-set unchanged (compare `dir /s /b` before/after) | **ARTIFACT R1** (8606 client) | boot/launch fails loud, rc≠0; refusal line `client identity refused: VAL-03: …` naming the detection per D10 (`no qualified flavor matches (detected 2.4.3.8606)` — code pinned, not the sentence); zero writes into the refused client dir (realmlist untouched); no client process spawned | P1 |
| LV-flavor-import-19 | Wrong-build refusal, WotLK client into vanilla lane (window-free like -18) | wotlk→vanilla lane | as -18 with the R2 client folder | **ARTIFACT R2** (12340 client) | same VAL-03 loud refusal naming `detected 3.3.5.12340`; no side effects; no client process spawned | P2 |
| LV-flavor-import-20 | VAL-01 fail-closed on a client-less folder (DESK-21). Window-free: refuses at the identity gate before any spawn | vanilla | `mkdir C:\pocket_realm_complete\tmp\flavor-probe\empty-client`; `cd desktop && gradlew rpHost -PclientDir="C:\pocket_realm_complete\tmp\flavor-probe\empty-client" -PrpClient=1 --console=plain` | NONE | launch refused loud, rc≠0; refusal carries the pinned `VAL-01: direct WoW.exe is absent` copy (DesktopClientScanner.kt:12-13,79-80); no client process spawned | P2 |
| LV-flavor-import-21 | Broken MPQ set REFUSED (VAL-04; C2 exact-set contract, negative arm). Window-free: refuses at the identity gate before any spawn | vanilla | copy the R3 managed client; delete `Data\patch-2.MPQ` from the copy; `cd desktop && gradlew rpHost -PclientDir="<copy>" -PrpClient=1 --console=plain` | **ARTIFACT R3** | refusal `client identity refused: VAL-04: missing base MPQ set: patch-2.MPQ` (DesktopClientScanner.kt:107-117); no launch, no side effects; no client process spawned | P1 |
| LV-flavor-import-22 | Managed generation + per-flavor pointer namespace after import (DESK-34/67/97; G3-5; GenerationPointers.kt:34) | vanilla | after a desktop-app import (or -16 + store adoption): `dir "C:\Users\<owner>\AppData\Local\PocketRealm\content\client\generations"`; `type …\generations\active-vanilla.json`; `type …\generations\<uuid>\client-manifest.json` | **ARTIFACT R3** | `active-vanilla.json` exists (canonical name `active-<flavorId>.json`), holds a UUID generation id + manifest hash that matches the generation's `client-manifest.json`; manifest carries `flavor: "vanilla"` (ManagedClientStore.kt:163-165; ClientGenerationStore.kt:394) | P1 |
| LV-flavor-import-23 | LATER-STAGE re-run of the S1 per-flavor gates after fork registration (R4 lands: wave-0 fork registration per requirements.md R4) | tbc, wotlk | after forks + pins registered: `python tools/check_sources.py` (expect tbc/wotlk rows OK); `python tools/build_win_sqlite_seam.py --flavor tbc` (proceeds past overlay refusal); `python tools/stage_database_migrations.py --flavor tbc` (writes `schemas/database-migrations-tbc.json`); repeat for wotlk | **ARTIFACT R4** (both forks, branch pocket-realm, pins in schemas/sources.json + flavor.json) | check_sources rc=0 with zero PEND rows; sqlite-seam tbc/wotlk builds reach cmake (no overlay refusal); per-flavor migration ledgers written with `flavorId` matching; `python tools/check_flavor.py` still rc=0 (tbc/wotlk still unqualified until their qualification waves — pins registered ≠ qualified) | P1 |
| LV-flavor-import-24 | Data-prep flavor honesty: non-qualified flavor refused (DESK-128 identity table; win_prepare_data.py:73-77) | tbc | `python tools/win_prepare_data.py --flavor tbc` | NONE ✅design-run | rc=2; `PREPARE FAILED: flavor 'tbc' has no qualified manifest identity (qualified: vanilla); the extractor lane is vanilla-only today`; nothing written under `%LOCALAPPDATA%\PocketRealm\content\o11-server` | P1 |
| LV-flavor-import-25 | Full vanilla data prep: extractors → verified generation → per-flavor pointer publication (DESK-44/69/102/128 — DESK-102's progress surface rides this same engine path; win_prepare_data.py:8-11,299-321). **BUDGET: ≤4 h wall clock (outer `timeout 14400`; the tool's own per-step cap is STEP_TIMEOUT_S = 4×60×60, win_prepare_data.py:56); runs in its OWN slot** — it is the one multi-hour row; dependents (-26/-27/-30/-31) reuse its persisted outputs and must not run concurrently | vanilla | `timeout 14400 python tools/win_prepare_data.py --client "E:\Wow clients\Installed\<client>" --flavor vanilla --workspace E:\pocket_realm_complete\tmp\lv-data-prep` (needs -12's extractors); then `type %LOCALAPPDATA%\PocketRealm\content\o11-server\active-vanilla.json` and `…\generations\<uuid>\data-manifest.json` | **ARTIFACT R3** (5875 client with Data MPQs) | rc=0 within budget; generation dir holds `dbc,maps,vmaps,mmaps` + `data-manifest.json`; tool's own verify_publication passes: manifest `flavor=="vanilla"`, `clientBuild==5875`, `cmangosFamily=="classic"`, staged sha == published digest; `active-vanilla.json` + legacy `active.json` both written and equal; MoveMapGen per-map coverage complete (every extracted map ≥1 mmtile); client dir byte-clean (entry-set snapshot before/after equal) | P1 |
| LV-flavor-import-26 | Validation-based resumability: partial output wiped, not trusted (DESK-44 checkpoint contract; win_prepare_data.py docstring) | vanilla | start -25, kill mid-vmaps: `timeout 120 python tools/win_prepare_data.py --client … --flavor vanilla --workspace …`; then re-run the same command to completion | **ARTIFACT R3** | second run rc=0 and produces a valid generation; the partial vmaps/mmaps output from the killed run did NOT pass validation (log shows wipe+regenerate, never skip-on-existence); final verify_publication passes | P2 |
| LV-flavor-import-27 | `--publish-only` republish keeps pointer identity (DESK-128 idempotence) | vanilla | after -25: `python tools/win_prepare_data.py --flavor vanilla --publish-only`; `fc %LOCALAPPDATA%\PocketRealm\content\o11-server\active-vanilla.json` against pre-copy | **ARTIFACT R3** (requires the -25 generation on disk) | rc=0; canonical + legacy pointers re-published byte-identical to the generation's manifest sha; no new generation created | P3 |
| LV-flavor-import-28 | Vanilla seed bring-up: four DBs, exact statement counts (WINDOWS_QUALIFICATION.md §1 Seed bring-up row) | vanilla | `cd desktop && gradlew seedRealmData --console=plain`; then `dir "%LOCALAPPDATA%\PocketRealm\database\sqlite-datadir"` | NONE (in-repo dumps + pinned amalgamation harness) | rc=0; four databases (`mangos,characters,realmd,logs` .sqlite); statement counts match the C harness exactly: 43 / 1,110,192 / 12 / 27,886 | P1 |
| LV-flavor-import-29 | Fail-closed world start with prepared data ABSENT (DESK-44/53 refusal codes; current machine state per requirements.md:100 — generations dir empty). **Daemon hygiene:** both gradle invocations use `--no-daemon` and the cleanup leg runs `gradlew --stop`, so the daemon started under the overridden LOCALAPPDATA cannot survive the probe and redirect the app-data roots of later rows (daemons key on JVM/version, not that env var) | vanilla | `cmd /c "set LOCALAPPDATA=C:\Users\<owner>\AppData\Local\Temp\pr-lv-fresh&& mkdir C:\Users\<owner>\AppData\Local\Temp\pr-lv-fresh 2>nul && cd /d E:\pocket_realm_complete\desktop && gradlew.bat --no-daemon seedRealmData"` (seeds only); then same env + `gradlew.bat --no-daemon bootWorld`; CLEANUP: `cd /d E:\pocket_realm_complete\desktop && gradlew.bat --stop` and `rmdir /s /q C:\Users\<owner>\AppData\Local\Temp\pr-lv-fresh` | NONE (artifact-free NOW precisely because no client/prepared data is staged; if prepared data later exists, use -30's pointer-hide form instead) | bootWorld fails loud rc≠0 naming the missing prepared-data input (never a fake READY); with seeds present the refusal is the prepared-data class, distinct from `DB-NOT-SEEDED` (which the seedless variant of the same probe produces — both codes demonstrated per DESK-53); cleanup leaves no daemon alive under the temp root and the temp dir removed | P1 |
| LV-flavor-import-30 | Fail-closed start when the per-flavor pointer is missing/lying (DESK-128; PreparedDataStore.kt:94 canonical pointer) | vanilla | after -25: rename `%LOCALAPPDATA%\PocketRealm\content\o11-server\active-vanilla.json` to `.bak`; `cd desktop && gradlew bootWorld --console=plain` (expect refusal); restore the pointer (`move …bak …active-vanilla.json`); re-run bootWorld | **ARTIFACT R3** (needs the -25 generation) | with pointer hidden: boot refuses loud rc≠0 naming the missing `active-vanilla.json`/prepared data (fail-closed — never falls back silently to legacy `active.json` for a *disagreeing* state); after restore: boot proceeds; restore verified by the second rc | P1 |
| LV-flavor-import-31 | Happy world boot on prepared data (DESK-69 output consumed; WINDOWS_QUALIFICATION.md §1 World boot gate) | vanilla | after -25: `cd desktop && gradlew bootWorld --console=plain` | **ARTIFACT R3** | rc=0; `world READY with vmaps+mmaps`; TCP 8085 accepts (`adb`-free host probe: a socket connect to 127.0.0.1:8085 succeeds); save rc=0; clean stop incl. WAL seal; cycle-2 WRONG_STATE refusal pinned | P1 |
| LV-flavor-import-32 | Flavor-routed auth: SRP6 logon with build from the qualified-flavor registry (DESK-56/93/94; AuthGate.kt:28-47) | vanilla | `cd desktop && gradlew bootRealmd --console=plain` (probe: any byte read from TCP 3724 = SRP6 challenge 0x1EC listener alive); `cd desktop && gradlew authGate --console=plain` | NONE (needs -28's seeds) | bootRealmd rc=0, `127.0.0.1:3724 accepts`, clean stop, no WAL sidecars; authGate rc=0 with all three proofs: M1 accepted, M2 == SHA1(A\|M1\|K), persisted sessionkey == K hex; realmd log `successfully authenticated`; account `AUTHGATE`; verifier row written to the flavor-routed realmd DB (DESK-93 — no hard-coded DB name) | P1 |
| LV-flavor-import-33 | Full start path through the app's own supervisor on the vanilla lane (DESK-62 flavor-routed identity resolution consumer; ties DESK-98) | vanilla | `cd desktop && gradlew supervisorStartGate --console=plain` | NONE (needs -28/-12 inputs) | rc=0; db+realm+world reach WORLD_READY via model.startRealm through DurableRuntimeSupervisor; clean save/stop; journal clean — proving the flavor-routed serverFlavor()/serverDatabases() resolution on the real start path | P1 |
| LV-flavor-import-34 | Android packaging gate SKIPS un-staged flavors; vanilla debug build unchanged (FIX:S1.2-8; android/app/build.gradle.kts:1940-1942) | all 3 | `cd android && gradlew :app:assembleDebug --console=plain` (tbc/wotlk staging trees absent on this host) | NONE | rc=0; no per-flavor staging errors (the gate `continue`s over absent `native/.build-<suffix>-{tbc,wotlk}/mariadb-staging`); produced debug APK identical lane to a pre-gate build (BuildConfig flavor fields unchanged) | P1 |
| LV-flavor-import-35 | Android packaging gate FAILS LOUD on a partial per-flavor staging tree (FIX:S1.2-8 negative arm; build.gradle.kts:1943-1957) | tbc | create partial tree: `mkdir native/.build-x86_64-tbc/mariadb-staging` + only `jniLibs/x86_64/libpocket_mariadbd.so`; `cd android && gradlew :app:assembleDebug --console=plain`; then CLEAN UP: `rm -rf native/.build-x86_64-tbc` and `git -C E:/pocket_realm_complete status --porcelain` back to baseline | NONE (synthetic staging tree; untracked build dir; cleanup mandatory) | rc≠0; check fails naming the missing files list and the remedy `Re-run tools/stage_database_migrations.py --flavor tbc. Missing: …` (build.gradle.kts:1952-1957); after cleanup assembleDebug is green again (-34) | P2 |
| LV-flavor-import-36 | Guest-side interaction over the hybrid bridge (environment.md lane): emulator + adb reverse + world console relay ops. **Prerequisite:** the relay attaches via instrumentation `com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner` (world_console.py:37), which lives in the androidTest APK — build and install the canonical APK pair, not just app-debug.apk | vanilla | `cd android && gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PpocketAbi=x86_64 -PpocketLane=full --console=plain` (canonical build+flags per tools/run_differential_parity.py:115-121); `"%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd PocketDiff_x86_64 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save &` (full path — bare `emulator` is not on PATH, see legend); `adb wait-for-device`; `adb install -r android/app/build/outputs/apk/debug/app-debug.apk && adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`; `adb reverse tcp:3724 tcp:3724 && adb reverse tcp:8085 tcp:8085`; run pre-flight -38 first (finding 5.22); `python tools/world_console.py` → ops `realm-status`, `db-status`, `db-health` (WorldConsoleRelay.kt:141-146) | NONE (uses in-repo APK pair + host lanes; guest data-prep pointer check limited to what relay exposes) | both APKs install; relay attach succeeds (no missing-instrumentation error); relay ops return rc=0 JSON: `realm-status` echoes listenPort 3724/8085 of the vanilla lane (ServerStatusJson twin, DESK-127), `db-status`/`db-health` report the seeded vanilla DBs healthy — the guest app is talking to the host vanilla lane through the reverse bridge | P2 |
| LV-flavor-import-37 | PRE-FLIGHT for every rebuild row: era-stages header staged (campaign finding 5.24, campaign-inventory.md:286) | vanilla, tbc, wotlk | `grep -c "playerbot/PlayerbotLlmEraStages.generated.h" tools/build_o09_realm_runtime.py` (expect ≥1: the staged overlay list, :490); `grep -n "o09.PLAYERBOTS_OVERLAYS" tools/build_win_realm_runtime.py` (the Windows lane reuses the same list, :357); `ls -la native/patches/playerbots/PlayerbotLlmEraStages.generated.h` | NONE ✅design-run (list hits at build_o09_realm_runtime.py:490 and the remedial staging write at :6020-6023; file present, 7,903 B) | header is in the staging overlay list AND the source file exists → -12's rebuild may proceed. If absent → recorded finding 5.24 ("the next realm-runtime rebuild fails until staged"), remedy = the :6020-6023 staging write — NOT a lane failure | P2 |
| LV-flavor-import-38 | PRE-FLIGHT for on-device account/ban rows: the JNI library the app actually LOADS carries the five friend-account exports (campaign finding 5.22, campaign-inventory.md:286). **The loaded library is `libpocket_world_runtime.so`** — WorldNative.kt:4 `System.loadLibrary("pocket_world_runtime")` — and the APK packages it from `native/.build-o09-<abi>/realm-staging-sqlite/jniLibs/<abi>/` on this host's default sqlite-provider lane (build.gradle.kts:330-336 `realmStage = if (isSqliteProvider) "…realm-staging-sqlite…" else "…realm-staging…"`; sqlite is the default here — the MariaDB provider is unstaged per requirements.md §3). `pocket-runtime-build/libpocketrealm.so` (build.gradle.kts:290 staging entry) is VESTIGIAL for this gate: WorldNative never resolves JNI through it | vanilla lane | `"<llvm-nm>" -D --defined-only native/.build-o09-x86_64/realm-staging-sqlite/jniLibs/x86_64/libpocket_world_runtime.so` and `…o09-arm64-v8a/realm-staging-sqlite/jniLibs/arm64-v8a/libpocket_world_runtime.so`, each piped to `grep -e createAccountVerifierNative -e accountListNative -e accountCharactersNative -e banAccountNative -e unbanAccountNative` (llvm-nm verified at the WinLibs path `C:\Users\<owner>\AppData\Local\Microsoft\WinGet\Packages\BrechtSanders.WinLibs…\mingw64\bin\llvm-nm`; NDK 28.2.13676358/30.0.15729638 bundles are alternates) | NONE ✅design-run (re-run in round 2): **5/5 exports in BOTH sqlite-lane trees** — `Java_com_pocketrealm_server_WorldNative_{createAccountVerifierNative, accountListNative, accountCharactersNative, banAccountNative, unbanAccountNative}` (WorldNative.kt:24-28); the non-sqlite `realm-staging` sibling also scans 5/5; the byte-scan caveat stands: `pocket-runtime-build/libpocketrealm.so` scans 0/5 on both ABIs, which is NOT a 5.22 reproduction (never JNI-resolved) | all five exports present in the packaging-lane trees → -36 and any on-device friend-access/op row may run; finding 5.22 **does not reproduce on the loaded library** on the current staging (reconciled verdict with LV-server-db-36 leg (b), which this round was re-pointed to the same trees). If a future scan goes 0/5 HERE → recorded finding 5.22, remedy = rebuild/re-pin — NOT a lane failure | P2 |
| LV-flavor-import-39 | C9 excluded-family refusal of the conf-matrix generator (C9; campaign-inventory.md:267 "refuses excluded key families (C9 VoiceChat) with rc=2") — the negative arm -09 does not cover | wotlk | `python -` heredoc: `sys.path.insert(0,"tools")`; `import generate_conf_matrix as g`; write a synthetic dist `VoiceChat.Enabled = 0` into a temp dir; `g.flavor_surfaces = lambda layout: [<synthetic>]`; `g.generate("wotlk", out=<tmp>/conf-matrix-wotlk.json)`. wotlk is the flavor whose `flavor.json conf_matrix.excluded_keys` carries the VoiceChat family (vanilla and tbc lists are empty — design-verified, so the guard can only fire on wotlk) | NONE ✅design-run | return code 2 + stderr `ERROR: correction C9 violation — dist VoiceChat.conf.dist.in carries key 'VoiceChat.Enabled' whose family is excluded for flavor 'wotlk' (schemas/flavor.json conf_matrix.excluded_keys); the generator must not emit it and staging must not expect it` — text distinct from -09's missing-dists error; no matrix file emitted. Monkeypatch lives in-process only; no production file touched | P2 |
| LV-flavor-import-40 | Modified-client contract at the folder lane: VAL-02 size refusal, tweaked-exe hash posture, VAL-10 unknown-DLL posture (DESK-21/36; C6). VAL-02 leg window-free (refusal before spawn); acceptance legs DEDICATED-SLOT like -17 | vanilla | copy R3 client three ways: (a) append bytes to `WoW.exe` past the fast-scan limit → `cd desktop && gradlew rpHost -PclientDir="<copy-a>" -PrpClient=1 --console=plain`; (b) flip one byte in a same-size `WoW.exe` copy → same command (observes the launch attempt — spawns a window); (c) drop an unrecognized root DLL (e.g. `evil.dll`, 0 bytes) into the copy → same command | **ARTIFACT R3** | (a) rc≠0, refusal `VAL-02: selected executable exceeds fast-scan limit` (DesktopClientScanner.kt:89-90), no spawn, no side effects; (b) RECORDED CONTRACT IS WARN-AND-ACCEPT at build identity: warning `VAL-03: build confirmed; this WoW.exe is accepted for vanilla launch, but optional executable tweaks require the inspected byte layout` (:100-102) — byte-exactness is the qualification/data-prep lanes' contract, NOT a folder-lane refusal (design found no refusal branch for same-size hash mismatch); (c) warning `VAL-10: unrecognized root DLLs: evil.dll` (:124-131), launch proceeds | P2 |
| LV-flavor-import-41 | Adoption-leg integrity: doctored managed manifest refused (DESK-68 refuse-non-qualified + G3-2's client-manifest AND data-manifest alias gates, fail-closed); foreign-MPQ-set posture recorded (C2 exact-set lives at qualification) | vanilla | (a) `cd desktop && gradlew test --tests "com.pocketrealm.importer.DesktopClientGenerationStoreTest" --console=plain` — pins `validateManifestShape`'s flavor-disagreement refusal (ClientGenerationStore.kt:389-399) incl. absent-alias-defaults-to-vanilla; (b) real-artifact probe: build a TBC-shaped `Data` set (TBC `expansion.mpq`/`patch-2.mpq` headers) under the genuine 5875 exe copy → `cd desktop && gradlew rpHost -PclientDir="<copy>" -PrpClient=1 --console=plain`; (c) `cd desktop && gradlew test --tests "com.pocketrealm.importer.DesktopDataPreparationStoreTest" --console=plain` — pins the data-manifest envelope gate `PreparedDataStore.validateDataManifestEnvelope(manifest, flavor)` (DataPreparationStore.kt:410-413: "complete, mode, clientBuild, cmangosFamily, optional flavor alias") | (a) NONE; (b) **ARTIFACT R3**; (c) NONE | (a) suite green including the fail-closed case `stored client flavor "…" disagrees with the manifest clientId "…"`; (b) RECORDED POSTURE: the folder scan checks only MISSING/invalid-header MPQs (DesktopClientScanner.kt:116-123) — extra TBC MPQs under a 5875 exe do NOT refuse; assert supported=true + warnings as the recorded contract (window spawns: DEDICATED-SLOT). The C2 exact-set/unknown-`patch-*.mpq` refusal is enforced at w5 qualification, not the folder lane; (c) suite green incl. the data-manifest alias legs — a disagreeing `flavor` alias fails closed, an absent alias resolves to the qualified (vanilla) lane (G3-2's data-manifest half, adopted from the matrix-server-db.md:164-166 assignment) | P2 |
| LV-flavor-import-42 | Interaction: generation switch × ownership × projection/WTF rooting (DESK-135 switcher + DESK-67 pointer + DESK-40/84 projection + DESK-96 WTF root). (a)/(b) window-free; (c) DEDICATED-SLOT | vanilla | two managed client dirs (R3 import + a copy with a distinctive `Interface\AddOns` marker); `python -` rewrites `…\PocketRealm\content\client\generations\active-vanilla.json` to gen2's uuid+manifest hash; (a) corruption probe: point the pointer at a stale uuid → `cd desktop && gradlew rpHost --console=plain` (NO `-PclientDir` → managed-generation resolution runs, DesktopRuntimeBackend.kt:1743-1744); (b) valid switch: pointer → gen2 → same command; (c) DEDICATED-SLOT launch: assert add-on projection lands on gen2's dir and in-game settings root Config.wtf under gen2 (DESK-96) | **ARTIFACT R3** | (a) rc≠0 loud fail-closed refusal naming the pointer mismatch (`active-pointer manifest hash mismatch`, ManagedClientStore.kt:85) — never silent fallback to the old generation; (b) boot resolves gen2 as the managed root (boot log names the resolved dir); (c) projection/WTF follow the switched generation: gen2's AddOns populated, gen1's dir NOT re-projected, Config.wtf written under gen2. Unattended play coverage of the same contracts rides the rpHost wire-client rows (bot-actors-04, multiplayer-06) | P1 |
| LV-flavor-import-43 | G3-1 UserAccountStore twins: optional flavor at unchanged SCHEMA=2, vanilla byte-stability, unknown-flavor quarantine (G3-1, campaign-inventory.md:51; adopted verbatim from the matrix-server-db.md:156-159 assignment) | vanilla | (a) android unit twin: `cd android && gradlew testDebugUnitTest --tests "com.pocketrealm.supervisor.UserAccountStoreValidationTest" --console=plain` — its G3-1 cases pin: a record without a flavor key loads as vanilla and is never quarantined for its absence (:55-63), a known non-vanilla flavor loads (:66-70), an unknown flavor value throws so `loadOrQuarantine` relocates it (:73+); (b) desktop JVM twin: `cd desktop && gradlew test --tests "com.pocketrealm.desktop.DesktopAutoLoginProofTest" --console=plain` (drives the desktop UserAccountStore twin, supervisor/UserAccountStore.kt:30-37 flavor default + `require(flavor in ClientFlavors.ALL)`); (c) crafted-record live leg: `python -` writes a record with `"flavor":"superunknown"` into an isolated `%LOCALAPPDATA%\PocketRealm\user-account\` root → next load moves it to the forensic quarantine (`account.invalid.*.json`, UserAccountStore.kt:46-114) and the store still loads the remaining records; (d) byte-stability probe: write+read a vanilla account record, `fc` before/after — the flavor key is written only when non-vanilla, so vanilla bytes never change | NONE | (a) unit twin green with all three G3-1 cases; (b) desktop twin green; (c) the unknown-flavor record is quarantined (invalid copy present, original relocated), not adopted and not fatal to the store; (d) vanilla record bytes identical across the round-trip; SCHEMA stays 2 throughout (G3-1's contract — no schema bump for the optional field) | P1 |
| LV-flavor-import-EX-135 | ACCEPTED EXCLUSION — DESK-135 'Change game folder…' / 'Import client…' render affordances (register: "rendered unconditionally in ActiveSetupCard; 'Import client…' as generation switcher"). This is a DISPOSITION row, not a pass/fail test; it owns DESK-135 in the coverage ledger | vanilla | static ownership probe only: `grep -n "data object Import" desktop/src/main/kotlin/com/pocketrealm/desktop/Router.kt` (Route.Import declared at Router.kt:11-12 with the DESK-1/97 comment and registered in the route list at :42; ImportScreen.kt carries the cards). The render contract itself is a Compose draw-time guarantee; the desktop suite has NO Router/navigation test class and no compose-test lane (checked desktop/src/test — no file references Router), and authoring one is a code change outside a test-design round | NONE | DISPOSITION recorded: DESK-135 is excluded from the CLI live lane with this entry as its owner (silent hole closed); the routing half is statically probed above; revisit with a real assertion if a desktop compose-test lane lands | — |

## Coverage notes (honest scope)

- **DESK-135** is owned by the accepted-exclusion entry **LV-flavor-import-EX-135** (a
  disposition row in the matrix, not a silent hole): its routing half is statically probed
  (`Route.Import`, Router.kt:11-12,42); its render contract has no existing desktop test class
  to drive (no Router/navigation test in desktop/src/test; no compose-test lane), and authoring
  one is a code change outside a test-design round.
- **DESK-102** now appears on row **-25** (its data-prep progress surface rides the same
  engine path as DESK-44/69/128); the Home/Settings/Diagnostics progress-CARDS remain UI and
  are covered only insofar as -25's engine states drive them.
- **D11's diagnostics flavor line** is rendered at draw time from `ClientFlavors.qualified()`
  (docs/DECISIONS-PORT.md:24) — no CLI surface emits it, so it is not directly assertable here.
  The D9 `.single()` runtime contract is pinned on the manifest side by -02; its snapshot-create
  emission (DesktopRuntimeBackend.kt:2153, `compatibility.flavor = qualified().single().flavorId`)
  has no CLI driver today (snapshot create is an app-model verb) and is left to the desktop JVM
  suite rather than claimed as a live row.
- **TBC/WotLK happy-path import + data prep** (extract from 8606/12340, per-flavor generations)
  cannot be designed as runnable rows yet: the S2/S4 lanes themselves are unexecuted
  (campaign-inventory.md:287). -23 pins the observable S1-gate transition when R4 lands; the
  actual tbc/wotlk qualification + prep rows belong to the later-stage round that also consumes
  R1/R2.
- **G3-1/G3-2 adoption (cross-matrix assignment from matrix-server-db.md:152-171, adopted this
  round):** G3-1 is owned by row **-43** (UserAccountStore twins: optional flavor at SCHEMA=2,
  vanilla byte-stability, unknown-flavor quarantine); G3-2 is owned by **-41(a)+(c)** — the
  client-manifest disagreement leg (DesktopClientGenerationStoreTest) plus the data-manifest
  envelope legs absent-alias→vanilla / disagreeing→fail-closed (DesktopDataPreparationStoreTest
  over `PreparedDataStore.validateDataManifestEnvelope`, DataPreparationStore.kt:410-413). The
  other vanilla-regression shims (G3-3..G3-10) remain referenced here only where rows lean on
  them (e.g. G3-3 in -06, G3-5 in -14/-22) — their owning lane is not this matrix.
- **Recorded build-breaker premises:** the campaign's open findings 5.24 (staging list omits
  `PlayerbotLlmEraStages.generated.h` — "the next realm-runtime rebuild fails until staged") and
  5.22 (staged Android .so lacks the five friend-account JNI ops) are pre-flighted by rows
  **-37/-38** before -12's rebuild and -36's on-device lane respectively. Round-2 evidence: 5.24's
  header is staged (design-run); **5.22 does not reproduce on the library the JVM loads** —
  `libpocket_world_runtime.so` (WorldNative.kt:4) scans 5/5 in both `realm-staging-sqlite` trees;
  the 0/5 hits live in `pocket-runtime-build/libpocketrealm.so`, a vestigial staging entry
  (build.gradle.kts:290) WorldNative never resolves JNI through. Reconciled verdict across
  matrices: on-device friend-access/op rows are NOT blocked by 5.22 on the current staging.
- All "artifact-free" rows are executable today on this host: every non-✅ NONE-artifact row was
  grounded by reading the named source in this session; the ✅ rows were additionally executed
  (see below). Non-✅ NONE rows NOT yet executed: -08, -10, -11, -12, -14, -20, -28, -29, -33,
  -34, -35, -36. Rows -37/-38/-39 are design-run.

## Re-validation log

### Round 4 (2026-10-02, fourth pass)

Working state still byte-identical to round 2's snapshot (315 entries, `git status --porcelain`
diff → empty; probes left it unchanged, `GIT-UNCHANGED-R4`). All design-run gates re-executed,
results unchanged: check_flavor 0; two-qualified refusal 1 (single_qualified_lane line present);
check_sources 0 (6 PEND expected-loud); `seed_realm_db --flavor tbc` 2;
`build_win_sqlite_seam --flavor tbc` 1; `stage_database_migrations --flavor tbc` 2;
`check_ledger_golden` 0 (3771fc35…); conf matrix vanilla 0 / tbc 2; `win_prepare_data
--flavor tbc` 2; `smoke_archive_import` 0 (`smoke OK`); C9 wotlk drill 2 (identical text); 5.22
pre-flight 5/5 on both sqlite-lane `libpocket_world_runtime.so` trees; era-stages header 7,903 B.
Structural QA: 44 rows = 43 tests + EX-135, 0 duplicate IDs, row IDs 01-43 in order.
No row changed this round.

### Round 3 (2026-10-02, third pass)

The E:-tree working state is byte-identical to round 2 (`git status --porcelain` snapshot diff →
empty, 315 entries both passes), and every design-run gate was re-executed with unchanged
results — check_flavor 0; two-qualified manifest refusal 1 (single_qualified_lane line present);
check_sources 0 (6 PEND expected-loud); `seed_realm_db --flavor tbc` 2;
`build_win_sqlite_seam --flavor tbc` 1; `stage_database_migrations --flavor tbc` 2 with working
tree unchanged (`GIT-UNCHANGED-R3`); `check_ledger_golden` 0 (3771fc35…); conf matrix vanilla 0 /
tbc 2 (missing dists); `win_prepare_data --flavor tbc` 2 (vanilla-only refusal);
`smoke_archive_import` 0 (`smoke OK`); C9 wotlk drill 2 (identical refusal text); 5.22 pre-flight
5/5 on BOTH `realm-staging-sqlite` `libpocket_world_runtime.so` trees; era-stages header 7,903 B.
No row changed this round.

### Round 2 (2026-10-02, second pass)

Every ✅design-run mechanism was re-executed this round against the current tree; **all results are
identical to the round-1 record** (same stdout contracts, same exit codes). True tool exit codes
this pass (captured via `PIPESTATUS[0]`, not the pipe tail): check_flavor 0; check_flavor
`--manifest` two-qualified copy **1** (the `single_qualified_lane requires exactly one qualified
flavor, got 2 (qualified ids: vanilla, tbc)` error now at output line 7 of 8 ERROR lines — the
first lines are the temp-copy path-resolution errors, unchanged substance); check_sources 0
(6 PEND expected-loud); `seed_realm_db --flavor tbc` **2**; `stage_database_migrations
--flavor tbc` **2** with `git status --porcelain | diff` vs the round-2 baseline → unchanged
(`GIT-UNCHANGED-R2`); `check_ledger_golden` 0 (same golden 3771fc35…); `generate_conf_matrix
--flavor vanilla --list-surfaces` 0 / `--flavor tbc` **2** (missing dists); `win_prepare_data
--flavor tbc` **2** (vanilla-only refusal); `scripts/smoke_archive_import.py` 0 (`smoke OK`);
C9 wotlk drill **2** (identical refusal text); 5.22 pre-flight `llvm-nm -D …libpocket_world_runtime.so |
grep -e … -e …` → 5 exports; era-stages header still 7,903 B. The E:-tree baseline moved from 313
to 315 modified/untracked entries between passes (sibling agents' concurrent writes — two
docs/ research files); none of this area's probes changed tracked state.

## Checks run during this design ask (mechanism evidence)

Host: win32, Git-Bash tool shell; repo `E:/pocket_realm_complete`. Baseline git status snapshotted
to `C:\pocket_realm_complete\tmp\git-status-baseline.txt` (313 pre-existing modified files — the
E: tree was already dirty; all probes below left it unchanged).

1. `python tools/check_flavor.py` → `check_flavor: OK (3 flavors: vanilla=qualified, tbc=unqualified, wotlk=unqualified)`, rc=0.
2. Temp copy with tbc `qualified=true` → `python tools/check_flavor.py --manifest …flavor-two-qualified.json` → rc=1, `ERROR single_qualified_lane requires exactly one qualified flavor, got 2 (qualified ids: vanilla, tbc)` (+ sources.json cross-check errors because the copy sits outside schemas/).
3. `python tools/check_sources.py` → rc=0; 6 PEND rows exactly as in -03; footer `All active pins verified (6 per-flavor pin(s) pending registration — expected-loud).`
4. `python tools/seed_realm_db.py --flavor tbc --out …/tmp/flavor-probe/tbc-seed` → rc=2, both ERROR lines quoted in -04.
5. `python tools/stage_database_migrations.py --flavor tbc` → rc=2, S2-item-1 error; `git status --porcelain | diff baseline -` → `GIT-UNCHANGED`.
6. `python tools/check_ledger_golden.py` → rc=0, `check_ledger_golden: OK (golden 3771fc35b4ceddbadd6f786d76b10902db319d07c7d6372156417e4e3d81dc68)`.
7. `python tools/build_win_sqlite_seam.py --flavor tbc` → rc=1, overlay-registration ERROR quoted in -07 (returned instantly, pre-MSVC).
8. `python tools/win_prepare_data.py --flavor tbc` → rc=2, `PREPARE FAILED: flavor 'tbc' has no qualified manifest identity (qualified: vanilla); the extractor lane is vanilla-only today`.
9. `python scripts/smoke_archive_import.py` → rc=0, `smoke OK: client zip ranks OK, installer zip routes to the in-app installer lane`.
10. `python tools/generate_conf_matrix.py --flavor tbc --list-surfaces` → rc=2 missing-dists ERROR; `--flavor vanilla --list-surfaces` → rc=0, six dist surfaces listed.
11. `python tools/win_prepare_data.py --help` / `seed_realm_db.py --help` / `stage_database_migrations.py --help` / `build_win_sqlite_seam.py --help` / `generate_conf_matrix.py --help` / `world_console.py --help` — flag surfaces as cited.
12. C9 guard drill for -39: heredoc importing `tools/generate_conf_matrix`, `flavor_surfaces` pointed at a synthetic `VoiceChat.Enabled = 0` dist — `generate("wotlk", …)` → rc=2 with `ERROR: correction C9 violation — dist VoiceChat.conf.dist.in carries key 'VoiceChat.Enabled' whose family is excluded for flavor 'wotlk' …`; the same drill against vanilla and tbc returned rc=0 because those flavors' `conf_matrix.excluded_keys` are `[]` (read from schemas/flavor.json: wotlk carries `{'key_family': 'VoiceChat', … 'enforced_by': 'tools/generate_conf_matrix.py …'}`) — which is exactly why the C9 negative must target wotlk.
13. 5.22 pre-flight for -38 ran for real, round-2 form (five artifacts, one command shape `llvm-nm -D --defined-only <lib> | grep -c -e createAccountVerifierNative -e accountListNative -e accountCharactersNative -e banAccountNative -e unbanAccountNative`): `native/.build-x86_64/pocket-runtime-build/libpocketrealm.so` → **0**; `native/.build-arm64/pocket-runtime-build/libpocketrealm.so` → **0**; `native/.build-o09-x86_64/realm-staging-sqlite/jniLibs/x86_64/libpocket_world_runtime.so` → **5**; `native/.build-o09-arm64-v8a/realm-staging-sqlite/jniLibs/arm64-v8a/libpocket_world_runtime.so` → **5**; `native/.build-o09-arm64-v8a/realm-staging/jniLibs/arm64-v8a/libpocket_world_runtime.so` → **5** (round-1 form scanned only the last tree). Exports are `Java_com_pocketrealm_server_WorldNative_{createAccountVerifierNative, accountListNative, accountCharactersNative, banAccountNative, unbanAccountNative}`; load evidence WorldNative.kt:4 `System.loadLibrary("pocket_world_runtime")` — the 0/5 monolith is vestigial staging (build.gradle.kts:290), not a 5.22 reproduction.
14. 5.24 pre-flight for -37 ran as: `grep -n "PlayerbotLlmEraStages" tools/build_o09_realm_runtime.py` → staged-list hit at :490 (`"playerbot/PlayerbotLlmEraStages.generated.h",`) plus the remedial staging write at :6020-6023; `ls -la native/patches/playerbots/PlayerbotLlmEraStages.generated.h` → present, 7,903 B; `grep -n "o09.PLAYERBOTS_OVERLAYS" tools/build_win_realm_runtime.py` → :357 (win lane reuses the list).
15. APK-pair facts for -36 read at tools/run_differential_parity.py:115-121 (`:app:assembleDebug :app:assembleDebugAndroidTest -PpocketAbi=x86_64 -PpocketLane=full`; outputs `app-debug.apk` + `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`) and tools/world_console.py:37 (`INSTRUMENT = "com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner"`).
16. VAL-code census for -40/-41 ran as `grep -rn "VAL-05\|VAL-10\|VAL-0" … | grep -o … | sort | uniq -c` → refusals are VAL-01/02/03/04 (+archive VAL-06/07/08); VAL-05 (`FLAT_ENGLISH_LOCALE_INFERRED`) and VAL-10 (`unrecognized root DLLs`) are `warnings +=` legs (DesktopClientScanner.kt:124,131), and no folder-lane branch refuses a same-size hash mismatch — the warn-and-accept contract cited in -40(b).
17. Source reads: tools/win_prepare_data.py:51-52,299-322 (DATADIR/pointers/verify_publication); desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt:1762-1804 (launch identity gate, refusal-before-side-effects), :2153 (D9 `.single()` flavor emission); desktop/.../client/DesktopClientScanner.kt:12-13,68-117,124-131 (VAL-01/02/03/04 + VAL-05/10 warnings); desktop/.../client/DesktopStorageRoots.kt:26-145 (%LOCALAPPDATA% roots); desktop/.../client/ManagedClientStore.kt:163-165 + android/.../importer/GenerationPointers.kt:34 + android/.../server/PreparedDataStore.kt:94 (active-<flavorId>.json); desktop/build.gradle.kts:168-202,246-252,289-353 (fence + gate tasks); android/app/build.gradle.kts:290 (vestigial libpocketrealm.so staging entry), :309-336 (llamaRealmRoot/realmStage sqlite-vs-mariadb packaging selection); android/app/src/main/java/com/pocketrealm/server/WorldNative.kt:4 (loadLibrary pocket_world_runtime), :24-28 (five friend-account externals); desktop/.../supervisor/UserAccountStore.kt:30-37,46-114 (flavor default/require + forensic quarantine) + android/app/src/test/java/com/pocketrealm/supervisor/UserAccountStoreValidationTest.kt:55-73 (the three G3-1 codec cases); desktop/.../importer/DataPreparationStore.kt:410-413 (validateDataManifestEnvelope: optional flavor alias); tools/build_win_realm_runtime.py `--help` (--write-lockfile opt-in); tools/install_client_windows.ps1:15-19,90-96 (params + 4,775,986-byte gate); tools/rp_harness/desktop_host.py (DesktopHost driver); android/.../database/WorldConsoleRelay.kt:141-146 (relay ops); docs/WINDOWS_QUALIFICATION.md §1 (gate table + seed counts); docs/DECISIONS-PORT.md:14-24 (D9/D10/D11/D12/D14); matrix-server-db.md:152-171 (G3-1/G3-2 cross-matrix assignment adopted as -43/-41(c)).

### 4.2 — section file `matrix-server-db.md` (embedded verbatim)

# Live-Verification Matrix — server-db (round 1 design)

Area: **server runtime, MariaDB/SQLite lanes, migration ledger, snapshots/backups, timeframe overlays**.
Written 2026-10-02. Inputs: `campaign-inventory.md` (deliverable IDs for this area), `environment.md`
(hybrid lane: host-desktop in-process servers + headless AVD + `adb reverse`), `requirements.md`
(user artifacts R1–R7). Repo: `E:/pocket_realm_complete` (all paths below relative to it unless
`%LOCALAPPDATA%` = `C:/Users/<owner>/AppData/Local`).

## Area boundaries

- **In:** DESK-5/47/78/106 (MariaDB lane), DESK-108/129/131 (storage durability), DESK-19/53/79/98
  (migration-ledger + prepare-for-start rows of the supervisor-journal group), DESK-6/18/37/100 and
  DESK-7/33/38/60/101 (snapshots/backups, realm archive), DESK-127 (realm-status telemetry surface),
  G3-3 + F3-1 (ledger golden seal), TF-1..TF-9 (timeframe overlays), plus the **server half** of
  MP:GUEST-BANS (ban enforcement at logon).
- **Out (other areas):** SRP6 routing/VAL gates/client import (desktop-flavor-routing /
  desktop-import-generations), supervisor journal *format* and save-exit UI (desktop-supervisor-journal),
  provisioning transport, tunnel/LAN join, UI copy, LLM lane, updates.

## Verified premises (every check below RAN during this ask, on this host)

| # | Command (exact) | Result |
|---|---|---|
| P1 | `python tools/check_ledger_golden.py` | `check_ledger_golden: OK (golden 3771fc35b4ceddbadd6f786d76b10902db319d07c7d6372156417e4e3d81dc68)`, rc=0 |
| P2 | `python tools/check_timeframes.py` | `check_timeframes: OK — vanilla: 12 stages, tbc: 5 stages, wotlk: 5 stages (registry, kotlin/native era sync, and overlay pins verified)`, rc=0 |
| P3 | `python tools/stage_database_migrations.py --flavor tbc` | rc=2, `ERROR: flavor 'tbc' server_core pin is unresolved — …`; no `schemas/database-migrations-tbc.json` created (verified by `ls`) |
| P4 | `python tools/seed_realm_db.py --flavor tbc --out C:/pocket_realm_complete/tmp/seed-tbc` | rc=2, ERROR naming the unresolved pin + `the tbc SQLite seed is port plan S4 item 3 work … refusing to seed classic inputs under a tbc name` |
| P5 | `python -m pytest tests/test_check_ledger_golden.py tests/test_check_timeframes.py tests/test_mariadb_lockfile_pins.py tests/test_seed_realm_db_fail_loud.py -q` | **28 passed** in 4.98s |
| P6 | `python -m pytest tests/test_sqlite_hardening.py tests/test_sqlite_dialect.py tests/test_db_async_null_guard.py tests/test_sqlite_seeding.py tests/test_s1_flavor_parameterization.py -q` | **103 passed** in 415.64s |
| P7 | `cd desktop && ./gradlew test --tests com.pocketrealm.database.DesktopSqliteSealsTest --tests com.pocketrealm.database.DesktopTimeframeOverlayTest --tests com.pocketrealm.database.DesktopMariaDbEngineTest --tests com.pocketrealm.server.ServerStatusJsonTest --tests com.pocketrealm.server.DesktopServerRuntimeFilesTest --tests com.pocketrealm.desktop.DesktopStorageRootsTest --console=plain --no-daemon` | BUILD SUCCESSFUL; JUnit XML (13:02): 73 tests, 0 failures, 0 errors across the 6 classes (10+8+11+6+34+4) |
| P8 | `cd desktop && ./gradlew test --tests com.pocketrealm.desktop.DesktopGuestBansTest --console=plain --no-daemon` | 8 tests, 0 failures (13:06) |
| P9 | `cd desktop && ./gradlew test --tests com.pocketrealm.desktop.DesktopSnapshotsRestoreTest --tests com.pocketrealm.desktop.DesktopRealmArchiveTest --console=plain --no-daemon` | 6+3 tests, 0 failures (13:11) |
| P10 | `cd desktop && ./gradlew test --tests com.pocketrealm.desktop.DesktopRuntimeBackendTest --console=plain --no-daemon` | 40 tests, 0 failures, BUILD SUCCESSFUL in 48s |
| P11 | `python tools/build_win_realm_runtime.py --database-backend mariadb` | rc=1, loud refusal listing the absent staging files (`mariadb-provider/bootstrap.sql`, `migration-manifest.json`, `bin/mariadbd.exe`, `bin/mariadb.exe`) — the DESK-78 unstaged posture, live |
| P12 | on-disk reads (ls / python-sqlite3) | `%LOCALAPPDATA%/PocketRealm/database/sqlite-datadir/` holds the 4 realm DBs + `pocketrealm_meta.sqlite3`; markers `.pocketrealm-migrations.json`, `.pocketrealm-clean-stop.json`, `.pocketrealm-generation.json` present; `pocketrealm_meta.timeframe_stage_state` = `(1,'vanilla-1.12.1','default-seed',…)`; `content/o11-server/active-vanilla.json` → generation `7ceb6c39-…` with `dbc/maps/vmaps/mmaps` present; `native/.build-o09-server-data/` baseline pack present (BUILD_PROVENANCE: client `wow-1.12.1-5875`, exe sha256 `b4756d38…`); realm DLLs + `pocket_sqlite.dll` staged under `native/.build-win-x86_64/` |
| P13 | byte-identity read of both ledger files | `schemas/database-migrations.json` and `schemas/database-migrations-vanilla.json` both 208,495 B, sha256 prefix `3771fc35b4ce` (alias==canonical, per G3-3) |
| P14 | rode every P7–P10 run | `Native runtime freshness fence PASSED for desktop pins-only (sqlite-win) (3 lockfile pin(s)…)` — `validateNativeRuntimePins` (desktop/build.gradle.kts:213-218) |
| P15 | byte-scan over the five friend-account JNI names (`createAccountVerifierNative`/`createAccountNative`/`banAccountNative`/`unbanAccountNative`/`accountListNative`) in five staged artifacts (audit round 2 re-run; the round-1 scan covered only the two `libpocketrealm.so` files and mis-scoped this gate) | `pocket-runtime-build/libpocketrealm.so` (x86_64 481 MB, arm64 497 MB): **0/5** — but that library is VESTIGIAL for JNI: `WorldNative.kt:4 = System.loadLibrary("pocket_world_runtime")`, and `libpocketrealm.so` is merely a staging-list entry (android/app/build.gradle.kts:290) the JVM never resolves JNI through. The libraries that matter scan **5/5 distinct names** each: `native/.build-o09-x86_64/realm-staging-sqlite/jniLibs/x86_64/libpocket_world_runtime.so`, `native/.build-o09-arm64-v8a/realm-staging-sqlite/jniLibs/arm64-v8a/libpocket_world_runtime.so` (the trees the APK packages, build.gradle.kts:330-337), and Windows `pocket_world_runtime.dll`. **Finding 5.22 does NOT reproduce on the loaded library** on the current staging |
| P16 | `grep -n "PlayerbotLlmEraStages.generated.h" tools/build_o09_realm_runtime.py` | staging list contains the header at :490 (finding 5.24's remedy appears applied at HEAD; the compile-dies-without-it comment is at :6020-6023); `find native/.build-win-x86_64 native/.build-arm64 -name "PlayerbotLlmEraStages.generated.h"` → no staged copy currently on disk (build artifacts cleaned — the check re-runs inside/after any rebuild row) |
| P17 | python read of `schemas/mariadb-runtime-lockfile.json` | abi `x86_64`, provider `mariadb-11.5.2-termux-glibc`, mariadb executables ride the APK as `libpocket_mariadbd.so` / `libpocket_mariadb_client.so` — this is the lockfile for the x86_64-AVD Android MariaDB lane; `native/.build-o09-x86_64/mariadb-connector` present on disk |
| P18 | code reads (audit round 1) | snapshot create verb is hard-wired to `roots.sqliteDatadir` (DesktopRuntimeBackend.kt:2155-2160) — the named-backup lane has no MariaDB-datadir arm; corrupt/truncated journal materializes an ERROR/dirty snapshot, not a throw (`DesktopSupervisorJournalTest.corruptFileMaterializesErrorDirtySnapshotNotAThrow`, :58-62; journal file `journal.json`, DesktopSupervisorJournal.kt:30); instrumentation driver `com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner` at world_console.py:40; canonical APK build+install pair at run_differential_parity.py:109-124 (build) and :160-167 (`adb install -r` of both APKs after uninstall) |
| P19 | `python -m pytest tests/test_differential_lane.py tests/test_sqlite_user_state_bridge.py tests/test_world_runtime_relay_contract.py -q` (round 2) | **44 passed** in 0.68s |
| P20 | `python tools/generate_timeframe_era.py check` (round 2) | `generate_timeframe_era check: OK (era header + overlays match their pins)`, rc=0 — the chain pins `native/patches/playerbots/PlayerbotLlmEraStages.generated.h` (the finding-5.24 artifact) against `schemas/timeframes`; also `ls tools/validate_timeframes.py` → **No such file or directory** (inventory TF-8 lists this tool; it is absent on E: — recorded discrepancy) |
| P21 | `--help` reads (round 2) | `run_differential_parity.py --help`: profiles `quick|standard|massive`, `--skip-build`; Server A = default MariaDB provider, Server B = `-PdifferentialTestLane -PsqliteProvider` (x86_64); dedicated AVD with enforced cold boots; verdicts PASS(0) / PASS-GATED(2 — world legs skipped for lack of prepared game data) / FAIL(1); `parity-report.json` emitted always; comparison today ROW-COUNT-ONLY over Server A's user-state export slice. `verify_seed_sqlite_floor.py --help`: `--apk/--apks/--floors` — APK statics + `assets/seed/<db>.sqlz` sha256 vs `schemas/sqlite-seed-baseline.json` + floor replay on pinned old sqlite3 shells; `checkpoint_dbs.py`: `PRAGMA wal_checkpoint(TRUNCATE)` + `quick_check` over the 4 realm DBs under the production datadir |
| P22 | seal-chain reads (round 3) | sha256 of `native/.build-x86_64/mariadb-staging/assets/database/migrations/manifest.json` (the staged asset tree: 414 `.sqlz` + `manifest.json`) == **`3771fc35b4ceddba…` = the G3-3 golden, TRUE** — the schemas→staging seal chain closes on this machine; datadir marker `.pocketrealm-migrations.json` well-formed (schema 1, `provider: "sqlite-3.46.1-in-tree"`, `migrationCount: 4`); honest open item: the marker's own `manifestSha256` (`d3a25daf…`) matches neither the golden nor `schemas/sqlite-seed-baseline.json` (`e57e21b3…`) — it binds the seeder's staged manifest via `SeedRealmData -PstagingRoot`, source file not identified this ask |
| P23 | `cd desktop && ./gradlew test --tests com.pocketrealm.database.DesktopSqliteSeederTest --tests com.pocketrealm.database.DesktopSqliteSeamTest --tests com.pocketrealm.database.DesktopDatabaseServiceTest --console=plain --no-daemon` (round 3) | BUILD SUCCESSFUL; JUnit XML (15:38): **19 tests, 0 failures** (Seeder 5, Seam 7, DatabaseService 7) |
| P24 | `python -m pytest tests/test_win_lockfile.py -q` (round 3) | **10 passed** in 0.21s |
| P25 | code/artifact reads (round 3) | `openMeta` is create-if-absent + DDL (`DesktopTimeframeOverlay.kt:165-173` — a missing/empty meta is rebuilt, `recordApplied` re-records the stage; a non-sqlite garbage file makes `exec` throw → the `start-failed-overlay` guard at DesktopRuntimeBackend.kt:2419-2433 fires: "ANY overlay failure refuses the start loudly"); `tools/check_repo.py` wires the G3-3 gate (`check_ledger_golden` at :75-82, invoked :326) and the §4.6 leak sweep (:61-72); `native/.build-x86_64/pocket-runtime-build/pocket_lifecycle_test` present alongside `libpocketrealm.so` (the run_realm_test.py inputs); re-ran P1/P2 this round — both still OK, nothing drifted |
| P26 | round-4 runs | `./gradlew test --tests DesktopSupervisorJournalTest --tests FriendAccountNativeOpsTest` → **4/0 and 1/0** (JUnit XML 16:17; FriendAccountNativeOps boots the machine's real stack and drives the native ban/unban/roster relay ops for real). `./gradlew seedRealmData --console=plain` over the production datadir → **rc=1, `DB-SEED: datadir already holds seeded databases: …sqlite-datadir`** — the fail-closed re-seed refusal, live (DesktopSqliteSeeder.kt:105) |
| P27 | `python tools/seed_sqlite_from_manifest.py --out C:/pocket_realm_complete/tmp/seed-manifest-out` (round 4) | **rc=0, `SEED OK`** — 414 entries, report `manifest_sha256 = 3771fc35b4ceddba…` (THE G3-3 GOLDEN), per-DB statement counts exactly the pinned harness numbers: classicrealmd 43/43, classiccharacters 1,110,192/1,110,192, classiclogs 12/12, classicmangos 27,886/27,886 — **0 errors**; four `.sqlite` files written to the scratch dir (spot-check: `account` table populated) |
| P28 | `cd desktop && ./gradlew whisperGate --console=plain` (round 4) | **`BRIDGE GATE PASSED`**, rc=0 — offline whisper refused with `sender-not-online (the documented posture)`; world booted for the injection-surface contract (docs/WINDOWS_QUALIFICATION.md:23) |

Consequently the host server lane is runnable **without any user artifact**: prepared data, seeds,
natives and the SQLite provider are all staged. Windows MariaDB engine lanes are the only R5-gated
rows; wrong-client auth legs are the only R1–R4-gated rows.

## Test matrix

Legend: **Artifacts** = `none` (artifact-free now-test) or the `requirements.md` artifact ID gating a
later-stage row. **Sev** = P1 (blocker for the area) / P2 (must-pass, lower blast radius).
Host gates run as `cd E:/pocket_realm_complete/desktop && ./gradlew <task> --console=plain`.
**Android gradle operational notes (audit round 2):** every `android` gradle invocation MUST carry
`-PpocketAbi=x86_64` — android/app/build.gradle.kts:948-951 throws `Missing required -PpocketAbi=<abi>`
unconditionally otherwise (verified live by matrix-timeframe-era's spot-proof: the same command FAILED
at configuration without it); `-PpocketLane=full` is spelled on APK-pair builds for explicitness (it
defaults to `full`, :961). From Git-Bash invoke as `cmd //c "gradlew.bat …"`. The emulator binary is
NOT on PATH: use `"%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe"` (verified:
`-list-avds` → PocketDiff_x86_64, PocketRealm_API28) or prepend
`%LOCALAPPDATA%\Android\Sdk\emulator` + `platform-tools` to PATH (the repo's own lane resolves it
from the SDK constant, tools/run_differential_parity.py:42-43).

| testId | feature | flavor(s) | exact CLI mechanism | artifacts | pass condition | sev |
|---|---|---|---|---|---|---|
| LV-server-db-01 | Realm DB seeding — 4 DBs into production datadir (DESK-19 seeder half) | vanilla | `cd desktop && ./gradlew seedRealmData --console=plain`; then `python -c "import sqlite3,glob;[print(f,len(sqlite3.connect(f).execute('select name from sqlite_master').fetchall())) for f in sorted(glob.glob(r'%LOCALAPPDATA%/PocketRealm/database/sqlite-datadir/*.sqlite3'))]"` | none | gate rc=0; four `classic*.sqlite3` + `pocketrealm_meta.sqlite3` under `%LOCALAPPDATA%/PocketRealm/database/sqlite-datadir`; gate's statement counts equal the pinned harness counts 43 / 1,110,192 / 12 / 27,886 (docs/WINDOWS_QUALIFICATION.md:19); no `-wal`/`-shm` sidecars left | P1 |
| LV-server-db-02 | realmd listener happy path | vanilla | `cd desktop && ./gradlew bootRealmd --console=plain` | none | rc=0; in-process realmd; `127.0.0.1:3724 accepts`; clean stop; no WAL sidecars (docs/WINDOWS_QUALIFICATION.md:20; task def desktop/build.gradle.kts:302) | P1 |
| LV-server-db-03 | Listener liveness + `adb reverse` bridge for the guest | vanilla | `cd desktop && ./gradlew realmdHold --console=plain` (background, holds 60 s); host probe: `python -c "import socket;s=socket.create_connection(('127.0.0.1',3724),5);print('challenge bytes:',len(s.recv(64)));s.close()"`; `adb reverse tcp:3724 tcp:3724 && adb reverse tcp:8085 tcp:8085 && adb reverse --list` | none (emulator for the reverse half) | probe reads ≥1 challenge byte on 3724 within 5 s (any byte back = listener alive, per environment.md port map); `adb reverse --list` shows both forwards; hold exits cleanly at 60 s | P2 |
| LV-server-db-04 | Full SRP6 protocol auth, session persisted server-side | vanilla | `cd desktop && ./gradlew authGate --console=plain` | none | `M1 accepted, M2 verified, session key persisted server-side`; realmd logs `successfully authenticated`; verifier row seeded into `classicrealmd.sqlite` (docs/WINDOWS_QUALIFICATION.md:21; AuthGate.kt:48,73) | P1 |
| LV-server-db-05 | World boot READY + save rc=0 + WAL seal + one-lifetime refusal | vanilla | `cd desktop && ./gradlew bootWorld --console=plain` | none (o11 prepared data `7ceb6c39-…` verified present, P12) | world READY with vmaps+mmaps; 8085 accepts; save rc=0; clean stops incl. WAL seal; cycle-2 start refuses WRONG_STATE (one lifetime per process) (docs/WINDOWS_QUALIFICATION.md:22; BootWorld task desktop/build.gradle.kts:330) | P1 |
| LV-server-db-06 | Supervisor-path start through the app's own start machine (DESK-98 happy leg, DESK-108 INITIALIZE evaluate) | vanilla | `cd desktop && ./gradlew supervisorStartGate --console=plain` | none | `SUPERVISOR START GATE PASSED`: start ok → WORLD_READY → saveAndExit ok → `DesktopSupervisorJournal(...).read().clean == true` and phase STOPPED (SupervisorStartGate.kt:25-56; gate drives `model.startRealm`, the app's real path) | P1 |
| LV-server-db-07 | Save-exit shutdown-hook drain (DESK-109; journal + lifecycle + sidecars) | vanilla | `cd desktop && ./gradlew saveExitGate --console=plain` (boot leg exits mid-session; verify leg reads durable state back) | none | verify leg: journal clean at STOPPED, clean world lifecycle record, no WAL sidecars; missing inputs fail loudly, never fake-pass (desktop/build.gradle.kts:261-287) | P1 |
| LV-server-db-08 | Kill matrix + SQLite WAL recovery | vanilla | `python tools/win_kill_matrix.py --scenario early-boot` then `--scenario mid-world-run` then `--scenario mid-save` (add `--soak-min 5` for the soak variant) | none | every kill (taskkill /F at the named leg) followed by a full recovery boot: READY, save rc=0, stop with WAL seal, no live sidecars left (docs/WINDOWS_QUALIFICATION.md:25) | P2 |
| LV-server-db-09 | Realm/world status telemetry surface (DESK-127) | vanilla | PREREQ (attach needs the instrumentation APK): `cd android && gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PpocketAbi=x86_64 -PpocketLane=full --console=plain` (canonical pair, tools/run_differential_parity.py:109-124); `adb install -r android/app/build/outputs/apk/debug/app-debug.apk && adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` (the run lane's install-after-uninstall pair, tools/run_differential_parity.py:160-167) — the relay is driven through `com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner` (tools/world_console.py:40), which lives in the androidTest APK. Then `python tools/world_console.py`; at the REPL run `stack-up-bot` (or `world-start`), then `realm-status`, `db-status`, `db-health`, `world-status` | none (emulator) | `realm-status` echoes the ServerStatusJson field map (listenPort 3724/8085, heartbeat/tick/session fields per ServerStatusJson.kt:23-46); `db-health` ok=true; `db-status` reports the active provider + datadir. Desktop twin pinned by ServerStatusJsonTest 6/6 (P7) | P2 |
| LV-server-db-10 | Storage-roots taxonomy + verify() report (DESK-129/131) | vanilla | `cd desktop && ./gradlew test --tests com.pocketrealm.desktop.DesktopStorageRootsTest --console=plain`; then `python -c "import os; base=os.path.expandvars(r'%LOCALAPPDATA%/PocketRealm'); names=['content','database','database/sqlite-datadir','database/snapshots','database/run','exports','realm','runtime','settings']; print(all(os.path.isdir(os.path.join(base,n)) for n in names))"` | none | test class 4/4 green (P7); existence walk prints `True` (every DesktopStorageRoots.kt:26-83 root present under the single base dir = `verify():Report.ok` semantics: roots exist AND rootsUnderBase) | P2 |
| LV-server-db-11 | Migration-ledger golden seal + S1 alias identity (G3-3, F3-1) | vanilla | `python tools/check_ledger_golden.py`; `python -c "import hashlib;[print(p,hashlib.sha256(open(p,'rb').read()).hexdigest()) for p in ['schemas/database-migrations.json','schemas/database-migrations-vanilla.json']]"; python -m pytest tests/test_check_ledger_golden.py -q` | none | OK at golden `3771fc35…`; both files byte-identical (P13); pytest green. **RAN this ask: all three green (P1, P13, P5)** | P1 |
| LV-server-db-12 | Staged-ledger byte-identity leg (build lane) | vanilla | `python tools/stage_database_migrations.py --flavor vanilla`; then `python tools/check_ledger_golden.py --staged native/.build-x86_64/mariadb-staging/assets/database/migrations/manifest.json` | none | `--staged` rc=0: the staged `manifest.json` is byte-identical to the pinned ledger (check_ledger_golden.py:36-40 contract). NOT run this ask (regenerates the staging tree); the default-mode leg was run instead (P1) | P2 |
| LV-server-db-13 | Per-flavor ledger/seeder fail-loud (S1 parameterization) | tbc, wotlk | `python tools/stage_database_migrations.py --flavor tbc` (and `--flavor wotlk`); `python tools/seed_realm_db.py --flavor tbc --out C:/pocket_realm_complete/tmp/seed-tbc` (and wotlk); `python -m pytest tests/test_s1_flavor_parameterization.py -q` | none | each command rc=2 naming the flavor's unresolved server_core pin; no `schemas/database-migrations-{flavor}.json` created; pytest green. **RAN for tbc (P3, P4) + pytest (P6); wotlk not run this ask, same mechanism** | P1 |
| LV-server-db-14 | DESK-53 distinct refusal codes at start + supervisor-journal decode honesty (G3-4; fail-closed tamper matrix) | vanilla | python script: (1) copy the 3 marker files + 4 DBs of `%LOCALAPPDATA%/PocketRealm/database/sqlite-datadir` AND `%LOCALAPPDATA%/PocketRealm/realm/runtime-supervisor/journal.json` to a timestamped backup dir; (2) rename `.pocketrealm-migrations.json` → `.bak`; (3) `cd desktop && ./gradlew supervisorStartGate --console=plain` → capture refusal; (4) restore marker, rename `.pocketrealm-generation.json` → `.bak`, re-run gate; (5) restore, move `classicrealmd.sqlite3` out, re-run gate; (6) restore, then truncate the journal: `python -c "open(r'%LOCALAPPDATA%/PocketRealm/realm/runtime-supervisor/journal.json','w').write('{\"schema\": 2, \"components\": ')"`, re-run gate; (7) restore journal, then write a PRE-FLAVOR (flavor-less) journal.json (a pre-G3-4 snapshot JSON with no flavor key), re-run gate; (8) restore everything, re-run gate green | none | legs 3/4/5 refuse carrying **DB-MIGRATION-PENDING** / the dirty-generation family (**DB-DIRTY-GENERATION**) / **DB-NOT-SEEDED** (distinct codes, DesktopDatabasePreparation.kt:12-13, DesktopRuntimeBackend.kt:421-422) — never the generic code; leg 6: the corrupt/truncated journal materializes the honest ERROR/dirty snapshot and the gate refuses or recovers loudly — never a throw and never a silent history reset (contract pinned by `DesktopSupervisorJournalTest.corruptFileMaterializesErrorDirtySnapshotNotAThrow`, DesktopSupervisorJournalTest.kt:58-62; file name `journal.json`, DesktopSupervisorJournal.kt:30); leg 7: the pre-flavor journal decodes tolerantly, flavor defaulted to vanilla per G3-4 (inventory:54 — codec takes flavor as an optional field without a JOURNAL_SCHEMA bump); leg 8 green with the two SEAL markers byte-identical to backup (`.pocketrealm-migrations.json` migration seal + `.pocketrealm-generation.json` generation marker, DesktopSqliteSeals.kt:40-42) — and byte-identity is asserted on THOSE two only: journal.json and `.pocketrealm-clean-stop.json` are rewritten by design on every gate cycle (fresh sessionId per run — eece1cd6→ae384db8 observed; clean-stop seal stamps a fresh `stoppedAt`), so they assert rewrite-sanity, not byte-identity (LVF-server-db-007's recorded leg-8 facet). Codec legs now also RAN at class level: DesktopSupervisorJournalTest 4/4 (P26) | P1 |
| LV-server-db-15 | Migration-seal / generation-seal re-proof before the integrity gate (DESK-79/98) | vanilla | after LV-server-db-14 leg 6: `python -c "import json;print(open(r'%LOCALAPPDATA%/PocketRealm/database/sqlite-datadir/.pocketrealm-migrations.json').read())"`; `cd desktop && ./gradlew test --tests com.pocketrealm.database.DesktopSqliteSealsTest --tests com.pocketrealm.desktop.DesktopRuntimeBackendTest --console=plain` | none | marker binds the ledger sha + completedAt and re-proofs green after the tamper/restore cycle; seals classes green (DesktopSqliteSealsTest 8/8 in P7, DesktopRuntimeBackendTest 40/40 in P10 — RAN) | P1 |
| LV-server-db-16 | Named DB snapshots: create/verify/list/retainNewest + transactional restore (DESK-6/18/37/100) | vanilla | `cd desktop && ./gradlew test --tests com.pocketrealm.desktop.DesktopSnapshotsRestoreTest --console=plain` | none | create refuses a live datadir (`DB-SNAPSHOT: refusing to copy a live datadir`, DatabaseSnapshotStore.kt:23); manifest schema 2 + per-file sha256; BEGIN-hash-restore / COMMIT-or-ROLLBACK on the world-ready proof; retainNewest prunes; **RAN: 6/6 green (P9)** | P1 |
| LV-server-db-17 | Realm data archive export/inspect/restore, refuse-unless-at-rest (DESK-7/33/38/60/101) | vanilla | `cd desktop && ./gradlew test --tests com.pocketrealm.desktop.DesktopRealmArchiveTest --console=plain` | none | single-file ZIP export; inspect without restore; restore refuses unless runtimes at rest; **RAN: 3/3 green (P9)**. Archive production-root live leg (exports root under `%LOCALAPPDATA%/PocketRealm/exports`, present per P12) rides the same verbs — no CLI main exists for the archive verbs, so the live half is recorded as **not CLI-drivable today** (honest gap, see notes) | P2 |
| LV-server-db-18 | Production-root disaster-recovery drill (snapshots lane against the REAL datadir) | vanilla | python script: (1) assert stopped (no `-wal`/`-shm`; `.pocketrealm-clean-stop.json` present); (2) copy `sqlite-datadir` → `%LOCALAPPDATA%/PocketRealm/database/snapshots/lv-drill-<ts>/` with the manifest+sha256 layout of DatabaseSnapshotStore (manifest.json listing path/size/sha256 + `manifest.sha256`); (3) move the live datadir aside; (4) restore from the snapshot copy into the datadir path; (5) `cd desktop && ./gradlew supervisorStartGate --console=plain`; (6) `python -c` sha256-compare every restored file vs the backup listing | none | gate green (READY + clean stop, journal clean); every file's sha256 equals the manifest digest; `timeframe_stage_state` row survives the restore | P2 |
| LV-server-db-19 | Snapshot flavor-lane pin (G3-9) | vanilla | covered by `cd android && gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.database.DatabaseSnapshotFlavorShimTest --tests com.pocketrealm.database.DatabaseSnapshotStoreTest --console=plain` — the `checkSnapshotFlavorMatchesLane` legs (DatabaseSnapshotStore.kt:152,61-67) | none | restore refuses a snapshot whose `compatibility.flavor` disagrees with the active lane; a no-flavor snapshot restores unchanged; manifest lengths (5 / per-file 3) never relaxed (G3-9 contract). **RAN: green — android DatabaseSnapshotFlavorShimTest 4/4 (incl. `tbc flavored snapshot refuses the vanilla lane naming both flavors`) + DatabaseSnapshotStoreTest 3/3 (LVF-server-db-008)**; the previously-cited desktop DesktopSnapshotsRestoreTest carries ZERO flavor references (grep: 0 hits) — not a coverage location for this row; the cross-flavor live leg additionally needs a second qualified lane (S2+) — recorded, not designable now | P2 |
| LV-server-db-20 | MariaDB lane fail-closed when the provider is not staged (DESK-78 posture) | vanilla (host) | `python tools/build_win_realm_runtime.py --database-backend mariadb`; then `cd desktop && ./gradlew test --tests com.pocketrealm.desktop.DesktopRuntimeBackendTest --tests com.pocketrealm.database.DesktopMariaDbEngineTest --console=plain` | none | build rc≠0 with the loud missing-staging list (**RAN: rc=1, P11**); backend preflight refuses with the **DB-LINK** copy and never touches the datadir, and the SQLite lane never surfaces DB-LINK (DesktopRuntimeBackendTest.kt:156-210, DesktopMariaDbEngineTest.kt:25,254); **classes RAN: 40/40 + 10/10 (P10, P7)** | P1 |
| LV-server-db-21 | MariaDB provider-closure verification + DB-REVISION legs (DESK-5/47) | vanilla (synthetic staging) | `cd desktop && ./gradlew test --tests com.pocketrealm.database.DesktopMariaDbEngineTest --console=plain` | none | synthetic staged distribution verifies end-to-end (manifest sha, per-exe size+sha256, bootstrap/migration digests); applyPinnedMigrations refuses on ledger drift / unsafe ledger identity / digest-mismatch negative accepted (`DB-REVISION: …`, DesktopMariaDbEngine.kt:388,558,605-666); **RAN: 10/10 (P7)** | P1 |
| LV-server-db-22 | On-device dual-provider baseline world sanity — SQLite AND MariaDB lanes live (DESK-5/47/106 Android leg). **BUDGET: ≤~2.5 h wall clock for both legs** (per-server gradle build capped at 1800 s + each instrument capped at 3600 s, tools/run_baseline_world_sanity.py:88 and run_differential_parity.py:49's 3600 s quick tier; `--only b` / `--only a` halves it); own emulator slot — environment.md's sequential one-emulator rule, never concurrent with LV-38's dedicated AVD | vanilla | headless AVD per environment.md (`"%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd PocketDiff_x86_64 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save` — full path; bare `emulator` is not on PATH, see legend); wait `adb wait-for-device` + `adb shell getprop sys.boot_completed` = 1; then `python tools/run_baseline_world_sanity.py --skip-build` (or `--only b` / `--only a` per server) — stages the in-repo baseline pack (`native/.build-o09-server-data`, provenance P12) via `adb push` + `run-as com.pocketrealm`, drives SQLite (B) then MariaDB (A); the MariaDB leg rides the in-repo **x86_64 Android MariaDB lane** — `schemas/mariadb-runtime-lockfile.json` (abi `x86_64`, provider `mariadb-11.5.2-termux-glibc`, mariadb executables shipped as `libpocket_mariadbd.so`/`libpocket_mariadb_client.so` — lockfile read this ask) built from `native/.build-o09-x86_64/mariadb-connector` (present on disk, verified this ask). The arm64 lockfile (`mariadb-runtime-lockfile-arm64-v8a.json`) is the wrong pin for this x86_64-AVD lane | none (x86_64 Android MariaDB runtime + baseline pack are in-repo, NOT R5) | both legs: `initialize` ok with `providerReady`, world boots, console account writes, `saveall`, on-disk proof (run-as `cat` of the account row), dirty-kill recovery with sentinel survival; MariaDB leg honors least-privilege probes (DatabaseLifecycleTest.kt:41-53 pins the same contract). **NOT run this ask (needs a booted emulator) — artifact-free now-test** | P1 |
| LV-server-db-23 | [later-stage] Windows MariaDB engine lane live — staged provider lifecycle (DESK-5/78) | vanilla | (1) obtain R5 `mariadb-11.5.2-winx64.zip` from `archive.mariadb.org` (sha256sums.txt verified live by requirements.md); (2) stage `bin/mariadbd.exe`, `bin/mariadb.exe`, `bootstrap.sql`, `migration-manifest.json`, `runtime-manifest.json` (schema 1, provider `mariadb-11.5.2-windows-x86_64`) into `native/.build-win-x86_64/mariadb-provider/` per `verify_mariadb_provider_staging` (tools/build_win_realm_runtime.py:468-548); (3) clear LV-server-db-36 leg (a) (finding 5.24), then `python tools/build_win_realm_runtime.py --database-backend mariadb --write-lockfile`; (4) point settings provider at MariaDB; (5) `cd desktop && ./gradlew supervisorStartGate --console=plain`; (6) `python -c "import json;print(open(r'%LOCALAPPDATA%/PocketRealm/database/active-provider.json').read())"` | **R5** | build verifies fail-loud→passes and writes `schemas/database-provider-lockfile-win.json` + `realm-runtime-lockfile-mariadb-win.json`; supervisor cycle green on the MariaDB provider; `active-provider.json` written (DatabaseDurableState.kt:517) with mode MARIADB; `mariadb-datadir/` populated; clean stop kills the daemon tree (`killTree`, DesktopMariaDbEngine.kt:79) leaving no orphan mariadbd (`tasklist /FI "IMAGENAME eq mariadbd.exe"` empty) | P1 |
| LV-server-db-24 | [later-stage] MariaDB migration-ledger live tamper → DB-REVISION (DESK-5 durability & ledger) | vanilla | after LV-server-db-23: `python` edit of one byte in the staged `migration-manifest.json` (or restore a prior generation's ledger over the current one); re-run the start gate | **R5** | refusal carries **DB-REVISION** naming the drifted ledger id (`DB-REVISION: ledger drift for <id>`, DesktopMariaDbEngine.kt:638) — never a silent re-apply; restoring the staged bytes → gate green | P1 |
| LV-server-db-25 | Timeframe overlay × MariaDB lane refusal (TF-2 MARIADB guard) | vanilla | `cd desktop && ./gradlew test --tests com.pocketrealm.database.DesktopTimeframeOverlayTest --console=plain` (requireStagingLane leg, DesktopTimeframeOverlay.kt:92-108); live leg after LV-server-db-23: settings `timeframeStage` = a non-default registered stage with the MariaDB marker present → start gate | none for the JVM leg (RAN: 11/11, P7); **R5** for the live leg | JVM: a MARIADB active-provider marker refuses any non-default stage loudly ("the MARIADB provider lane has no overlay leg in v1"); live: world start refused with stage=<id> in the refusal; DEFAULT stage still boots on MariaDB | P2 |
| LV-server-db-26 | Guest-ban store on the SQLite lane + provider-blind probe (MP:GUEST-BANS server half; finding 5.34) | vanilla | `cd desktop && ./gradlew test --tests com.pocketrealm.desktop.DesktopGuestBansTest --tests com.pocketrealm.desktop.FriendAccountNativeOpsTest --console=plain` — GuestBans pins ban/suspend/unban/list + BEGIN IMMEDIATE/COMMIT wraps (DesktopGuestBans.kt:76-176); FriendAccountNativeOpsTest boots the REAL stack and drives native `ban`/`unban`/`account_list` ops + roster ban-state read (FriendAccountNativeOpsTest.kt:23-33) | none | GuestBans 8/8 (**RAN**, P8); FriendAccountNativeOps **RAN: 1/1 green against the live stack (P26)** — verifier provisioning, duplicate-name refusal, roster read (ban state / failed-logins), ban + unban verbs, gmlevel, per-friend characters, then the backend verb. **MariaDB-lane ban persistence is NOT CLI-drivable today** (no gate/console verb drives DesktopGuestBans over the MariaDB provider) — recorded as the open probe for finding 5.34 (inventory:286), to be re-designed after LV-server-db-23 lands | P2 |
| LV-server-db-27 | Refused-ban logon enforcement (ban expiry predicate at realmd) | vanilla | after LV-server-db-26's FriendAccountNativeOps ban leg, on the same live stack: `cd desktop && ./gradlew test --tests com.pocketrealm.desktop.FriendAccountNativeOpsTest --console=plain` (roster read leg proves ban state reaches the native account layer — **RAN 1/1 green, P26**); on-device complement (same APK-pair prerequisite as LV-server-db-09 — the console's relay rides the androidTest APK, world_console.py:40): `python tools/world_console.py` REPL → `world-account <banned-user> <pw>` then `world-verify <banned-user> <pw>` | none | banned account's logon is refused by the engine's logon expiry predicate (ban row honored); `world-verify` fails for the banned account and succeeds after `unban`; no ban row → login succeeds. Desktop half RAN; console half designed (needs emulator) | P1 |
| LV-server-db-28 | Stage registry + three-way manifest↔Kotlin↔native sync (TF-1, TF-6, TF-8) | all three | `python tools/check_timeframes.py`; `python -m pytest tests/test_check_timeframes.py -q`; `python tools/generate_timeframe_era.py check` | none | 22 stages verified: vanilla 12 / tbc 5 / wotlk 5, registry + kotlin/native era sync + overlay pins (**RAN: OK, P2**); pytest green (**RAN: in P5's 28**); era chain check green (**RAN: `OK (era header + overlays match their pins)`, P20** — this also hash-pins `PlayerbotLlmEraStages.generated.h`, the finding-5.24 artifact LV-server-db-36 gates on). Recorded discrepancy: inventory TF-8 credits `tools/validate_timeframes.py`, which is **absent on E:** (**RAN: `ls` → No such file**, P20) — `check_timeframes.py` + `generate_timeframe_era.py check` are the shipped gates | P1 |
| LV-server-db-29 | Desktop overlay applier contract: apply+record, missing world DB, meta WAL check (TF-2) | vanilla | `cd desktop && ./gradlew test --tests com.pocketrealm.database.DesktopTimeframeOverlayTest --console=plain`; live premise: `python -c "import sqlite3;con=sqlite3.connect(r'%LOCALAPPDATA%/PocketRealm/database/sqlite-datadir/pocketrealm_meta.sqlite3');print(con.execute('select * from timeframe_stage_state').fetchall())"` | none | applied stage + overlay sha recorded in `pocketrealm_meta.timeframe_stage_state`; missing world DB refused loudly; meta write leaves no WAL sidecar; **RAN: 11/11 (P7)**; live read shows `(1,'vanilla-1.12.1','default-seed',…)` (**RAN, P12**) | P1 |
| LV-server-db-30 | World-start wiring live: settings → conf → overlay → native boot (TF-3) | vanilla | (1) `python -c "import json;print([s['id'] for s in json.load(open('schemas/timeframes/vanilla.json'))['stages']])"` → pick a non-final registered id (e.g. `vanilla-1.1`); (2) backup `%LOCALAPPDATA%/PocketRealm/settings/settings.json`; (3) python-edit `"timeframeStage": "<stage>"` (field pinned: Settings.kt:365,608; read via `resolveStored` :473-474); (4) `cd desktop && ./gradlew supervisorStartGate --console=plain`; (5) grep the generated world conf for `PocketRealm.TimeframeStage` (emitted by ServerRuntimeFiles.kt:237 under the run dir); (6) re-read `timeframe_stage_state` (LV-29 command); (7) restore settings to `""`, re-run gate, confirm final-patch default (`vanilla-1.12.1`) | none | gate green both ways; non-default leg: conf carries `PocketRealm.TimeframeStage = "<stage>"` AND meta row shows the applied stage with a non-default source; overlay applies INSERT-OR-IGNORE-only inert scaffolding, zero SQL at DEFAULT (TF-7); restore leg returns `vanilla-1.12.1`. **Designed; not run this ask** (mutates user settings.json — backup/restore scripted) | P1 |
| LV-server-db-31 | Impossible/cross-flavor stage degradation at the settings layer (TF-5) | vanilla | (1) `python` write `"timeframeStage": "wotlk-3.3.5a"` into settings.json; (2) `cd desktop && ./gradlew supervisorStartGate --console=plain`; (3) grep generated conf → expect the resolved default, never `wotlk-3.3.5a`; (4) restore settings; (5) `cd android && cmd //c "gradlew.bat testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.storage.TimeframeStageSettingsTest --tests com.pocketrealm.client.TimeframeStageTest --console=plain"` | none | unregistered/cross-flavor value degrades to `""` at read AND write (never reaches the persisted encoding or the conf — TimeframeStageSettingsTest.kt:76 leg); gate boots on the default stage. **Unit legs designed, not run this ask**; live legs designed | P2 |
| LV-server-db-32 | Golden conf pins incl. the timeframe line (TF-4) | vanilla | `cd android && cmd //c "gradlew.bat testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.server.VanillaConfGoldenTest --console=plain"`; desktop half already green: `DesktopServerRuntimeFilesTest` (34/34, P7) | none | `fixtures/g3/conf/mangosd.conf:48` pin holds: `PocketRealm.TimeframeStage = "vanilla-1.12.1"`; 8 conf surfaces byte-pinned incl. topology-key-absence (G3-7). **Android unit leg not run this ask** | P2 |
| LV-server-db-33 | On-device SQLite overlay leg — TF-9's first execution | vanilla | emulator up (LV-server-db-22 boot line); `cd android && ANDROID_SERIAL=emulator-5554 gradlew connectedDebugAndroidTest -PpocketAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.class=com.pocketrealm.database.DatabaseLifecycleTest --console=plain` (audit round 2: `-PpocketAbi` is mandatory — build.gradle.kts:948-951 throws at configuration without it) (emulator-only allowlist enforced at android/app/build.gradle.kts:107-112); then `adb -s emulator-5554 exec-out run-as com.pocketrealm cat files/database/sqlite-datadir/pocketrealm_meta.sqlite3 > C:/pocket_realm_complete/tmp/meta-device.db` (path as printed by the runner's on-disk proof) and host-read `timeframe_stage_state` | none | `initialize` ok / providerReady on-device; after a world start the on-device meta row records the applied stage (the SQLiteDatabase leg that was never run — inventory:279); boot on API 28 AVD (`PocketRealm_API28`) as the min-support rotation — CLARIFIED (final read): this row boots `PocketDiff_x86_64` (API 35) only; the API 28 (`PocketRealm_API28`) min-support rotation is a separate later-stage row, not part of this row's pass condition | P1 |
| LV-server-db-34 | [later-stage] Wrong-client auth refusal — realmbuilds mismatch (C1) | tbc, wotlk (+vanilla control) | after S2 fork registration (R4) and a qualified client (R1/R2): import the client, project realmlist at 127.0.0.1, launch the **wrong-build** client against the flavor's realmd (e.g. 5875 client vs tbc lane whose `realmbuilds` = 8606), then the correct-build client → logon succeeds. Vanilla control: 5875 client vs the host lane via `realmdHold` + `launchClient`. RE-STATED at the correct layer (multiplayer round-4 executed refutation, matrix-multiplayer.md annex): realmd does NOT refuse wrong builds at AUTH_LOGON_CHALLENGE — `srp6_probe.py --challenge-only --build 8606/5467/5875` all received full challenge answers, because core-side `ok_build` (AuthSocket.cpp:920/986) only shapes the realmlist ADVERTISEMENT from `realmlist.realmbuilds` (live datum `'5875 6005 6141 '`). The wrong-build negative is therefore the CLIENT declining the advertisement (this row's artifact legs, R1/R2); the artifact-free server half is the advertisement-datum check: `python -c` read-only `SELECT realmbuilds FROM realmlist WHERE id=1` asserting the flavor's accepted-build set, and a probe challenge for a wrong build recorded as ANSWERED (never asserted as a refusal) | **R1, R2, R3 (+R4 for tbc/wotlk lanes)** for the client-declines-advertisement leg; the server/advertisement half is artifact-free | wrong-build client declines the realm whose advertised `realmbuilds` set excludes its build (client-side; capture auth/realmlist transcripts) and never reaches world entry; the challenge itself is SERVED for any build (pinned executed behavior, not a failure); correct build completes SRP6; advertisement datum matches the flavor's accepted-build set; vanilla control unaffected | P1 |
| LV-server-db-35 | [later-stage] Windows MariaDB datadir survival across provider switching (DESK-106 dual-provider window) | vanilla | after LV-server-db-23: (1) stop cleanly; (2) python-write a tampered `active-provider.json` claiming SQLITE over the mariadb datadir; (3) start gate → expect the provider-mismatch refusal (DB-PROVIDER-MISMATCH family, DesktopRuntimeBackend.kt:421-422); (4) restore marker; (5) switch provider to SQLite in settings with the MariaDB marker present → start refused or honest re-init per the dual-provider window contract (DatabaseProviderCard routing) | **R5** | cross-provider claims refuse loudly (never boot the wrong engine over a foreign datadir); marker restore → green | P1 |
| LV-server-db-36 | Build pre-flight: recorded build-breakers 5.24/5.22 gated BEFORE any rebuild or on-device op row (now-test) | all (build inputs, not flavor-bound) | (a) finding 5.24 (inventory:286 — native staging list omits PlayerbotLlmEraStages.generated.h): `grep -n "PlayerbotLlmEraStages.generated.h" tools/build_o09_realm_runtime.py` (must hit the staging list — VERIFIED this ask: present at :490; the build "dies at compile" without it, build_o09_realm_runtime.py:6020-6023) + `python tools/generate_timeframe_era.py check` (VERIFIED: `OK (era header + overlays match their pins)`, P20 — the source-of-record hash pin over the same header); after any realm-runtime rebuild: `find native/.build-win-x86_64 native/.build-arm64 -name "PlayerbotLlmEraStages.generated.h"` must be non-empty in the staged source tree; (b) finding 5.22 (inventory:286 — staged Android .so lack the five friend-account JNI ops): grep the library the JVM actually LOADS — `android/app/src/main/java/com/pocketrealm/server/WorldNative.kt:4` = `System.loadLibrary("pocket_world_runtime")` — i.e. `llvm-nm -D --defined-only` over `native/.build-o09-x86_64/realm-staging-sqlite/jniLibs/x86_64/libpocket_world_runtime.so` and `native/.build-o09-arm64-v8a/realm-staging-sqlite/jniLibs/arm64-v8a/libpocket_world_runtime.so` (the trees the APK packages per android/app/build.gradle.kts:330-336 `realmStage = if (isSqliteProvider) "…realm-staging-sqlite…" else "…realm-staging…"`), counting the five exports `createAccountVerifierNative/accountListNative/accountCharactersNative/banAccountNative/unbanAccountNative`. Byte-scan honesty caveat retained: `pocket-runtime-build/libpocketrealm.so` (build.gradle.kts:290 staging entry) scans 0/5 on both ABIs, but it is VESTIGIAL for this gate — WorldNative never resolves JNI through it — so its 0/5 is NOT a finding-5.22 reproduction | none | (a) grep hits the staging-list entry AND the era-chain check is green, and post-rebuild the staged header exists — clearing LV-server-db-23 step 3 and every other realm-runtime rebuild row; (b) grep = 5/5 in BOTH sqlite-lane `libpocket_world_runtime.so` trees — RECONCILED VERDICT (audit round 2, matching LV-flavor-import-38 and timeframe-era's 5.22 leg): finding 5.22 **does not reproduce on the loaded library** on the current staging; the earlier **FAILING (0/0)** status was an artifact of scanning the vestigial `libpocketrealm.so` monolith and is withdrawn — on-device friend-access/op rows are NOT blocked by 5.22. If a future scan of the `realm-staging[-sqlite]` world-runtime trees goes below 5/5, THEN map to finding 5.22's recorded remedy (rebuild/re-pin), never misdiagnose it as a lane failure | P1 |
| LV-server-db-37 | [later-stage] MariaDB provider × snapshots/backup lane — explicit provider boundary + mariadb-datadir DR drill (DESK-6/18/37/100 on the second provider) | vanilla | after LV-server-db-23: (1) with the MariaDB provider active, run the app's backup verbs — the shipped create verb snapshots `roots.sqliteDatadir` unconditionally (DesktopRuntimeBackend.kt:2155-2160), so the CLI leg is the same settings+gate drive as LV-23 plus `listBackups`/`verifyBackup`/`retainNewestBackups` observation via the Diagnostics lane's persisted state under `%LOCALAPPDATA%/PocketRealm/database/snapshots/`; record which arm the build takes: (a) an honest provider-boundary refusal for backup-on-MariaDB, or (b) the cycle runs but covers only `sqlite-datadir`; (2) file-level DR drill over the REAL MariaDB datadir (LV-server-db-18's form against `%LOCALAPPDATA%/PocketRealm/database/mariadb-datadir/` + `active-provider.json`): stop cleanly → copy → wipe → restore → `cd desktop && ./gradlew supervisorStartGate --console=plain` | **R5** | the boundary is EXPLICIT, not silent: whichever arm (a)/(b) the build under test takes is recorded in the run notes; the mariadb-datadir DR drill ends green (supervisor cycle on the MariaDB provider, restored datadir byte-identical to the copy, `active-provider.json` intact, no orphan mariadbd). If the build ships arm (b) with no boundary code, the gap "named-backup lane is SQLite-only" is recorded against DESK-6/18/37/100 as a finding, not left implicit | P1 |
| LV-server-db-38 | Differential parity lane — identical barrage over the MariaDB (A) and SQLite (B) providers on-device. **BUDGET: ≤~2 h wall clock at `--profile quick`** (two x86_64 APK builds capped at 1800 s each, run_differential_parity.py:117,126; the instrument leg is capped at INSTRUMENT_TIMEOUT_S `quick` = 3600 s, run_differential_parity.py:49; `standard` = 7200 s / `massive` = 10800 s — do not schedule massive in a default slot); own dedicated AVD slot — environment.md's sequential one-emulator rule | vanilla | `python tools/run_differential_parity.py --profile quick` (builds both x86_64 server APKs itself: A = default MariaDB provider, B = `-PdifferentialTestLane -PsqliteProvider`, run_differential_parity.py:109-124; dedicated AVD, enforced cold boots; drives `DifferentialBarrageRunner` on both via `am instrument`; pulls evidence bundles; parity oracle vs the append-only KNOWN-DIFFERENCE LEDGER; writes `build/differential/<run-id>/parity-report.json` always); `--skip-build` for reruns on captured APKs | none (both providers in-repo: x86_64 MariaDB lane per P17, SQLite default) | exit 0 (PASS); exit 2 = PASS-GATED is a recorded outcome (every executed check passed but world legs skipped for lack of prepared game data — stage `native/.build-o09-server-data` staging or accept the gate, never read exit 2 as a failure of the comparison); exit 1 FAIL must name the diverging check in parity-report.json. **NOT run this ask (needs emulator + two APK builds) — artifact-free now-test** | P1 |
| LV-server-db-39 | WAL checkpoint + integrity after a hard kill (SQLite recovery tooling) | vanilla | immediately after LV-server-db-08's `mid-world-run` kill leg: `python tools/checkpoint_dbs.py`; then `cd desktop && ./gradlew supervisorStartGate --console=plain` | none | per-DB line `checkpoint mode=<mode> wal=<log> -> <ckpt> integrity=<result>` for all four realm DBs (classicrealmd/classicmangos/classiccharacters/classiclogs — tool hardcodes the production datadir, checkpoint_dbs.py:7-9) with `integrity=ok`; the next boot then starts clean and the supervisor gate is green | P2 |
| LV-server-db-40 | User-state bridge — MariaDB datadir → SQLite provider translation (dual-provider first-boot window) | vanilla | NOW leg (**RAN**: `pytest tests/test_sqlite_user_state_bridge.py tests/test_differential_lane.py tests/test_world_runtime_relay_contract.py -q` → **44 passed**, P19 — covers the TSV codec, OUTFILE escaping incl. the `tests/p5_outfile_wire_fixture*.txt` wire fixtures, and transactional INSERT OR REPLACE import); LIVE leg after LV-server-db-38's Server A: export the user-state slice via the module's `INTO OUTFILE` queries on the running MariaDB, feed the TSV through the bridge into a seeded SQLite datadir, then `run-as com.pocketrealm cat` the account row in both datadirs and compare | none (core is host-testable; live leg rides LV-38's in-repo MariaDB APK) | 44/44 pytest green (**RAN**); live leg: exported account rows survive translation byte-faithfully (blobs via HEX→X'' round-trip, UTC-pinned timestamps per the module's recorded residuals, sqlite_user_state_bridge.py:1-30); a failed export refuses fail-closed (the old provider must still boot — the mandatory-window posture) | P1 |
| LV-server-db-41 | Seed-floor preflight — seed APK integrity + framework-SQLite floor replay | vanilla | `cd android && cmd //c "gradlew.bat :app:assembleDebug -PpocketAbi=x86_64 -PpocketLane=full -PsqliteProvider --console=plain"` — `-PsqliteProvider` is the mode where "the seed transcripts ship as compressed assets" (android/app/build.gradle.kts:972-975), which is what this preflight consumes; then `python tools/verify_seed_sqlite_floor.py --apk android/app/build/outputs/apk/debug/app-debug.apk` (add `--floors` to pin extra device classes) | none (pinned old sqlite3 shells + baseline are in-repo: `schemas/sqlite-seed-baseline.json`) | (1) APK statics ok (package, minSdk, expected ABI dir); (2) every `assets/seed/<db>.sqlz` gunzips to a sha256 matching BOTH the append-only baseline AND the sqlite realm lockfile pin for the APK's ABI (hash chain to the shipped artifact); (3) every transcript replays start-to-finish on the pinned OLD sqlite3 shells, exit 0, empty stderr. If the built APK lacks the prefix, the tool fails loud naming `assets/seed/` (verify_seed_sqlite_floor.py:71) and the run records it — never a silent skip | P2 |
| LV-server-db-42 | On-device one-lifetime + kill/restart cycle (on-device twin of LV-05's cycle-2 refusal) | vanilla | with LV-server-db-09's APK-pair prerequisite installed: `python tools/world_console.py` REPL → `world-start`; then `world-start` again → second must refuse honestly; then `world-kill` → `world-start` → READY again; `world-save` → rc=0; `db-health` between legs | none (emulator) | second `world-start` refuses with the WRONG_STATE/one-lifetime family (never a double boot); after `world-kill` the restart reaches READY (recovery boot); `world-save` rc=0 with clean stop markers; `db-health` ok throughout | P2 |
| LV-server-db-43 | Server-runtime supply-chain fence — win-lane lockfile pins over the bytes the server boots | vanilla | `cd desktop && ./gradlew validateNativeRuntimePins --console=plain` (also rides every `test` run — P14); `python -m pytest tests/test_win_lockfile.py -q` (**RAN: 10 passed, P24**) | none | fence prints PASSED for desktop pins-only (sqlite-win, 3 lockfile pins); pytest green across its ten pins: per-flavor lockfile naming registered, the win seam refuses unregistered flavors before MSVC, source pins mirror the android sqlite lane, PE import tables pinned (no dynamic OpenSSL), BuildConfig telltale recomputed from the lockfile, seed pins == baseline, sqlite amalgamation pin matches sources, vanilla-tweaks host lockfile PE-pinned | P1 |
| LV-server-db-44 | On-device native realm lifecycle — the real C ABI over real seeded SQLite, in the emulator | vanilla | emulator up (LV-server-db-22 boot line); `python tools/run_realm_test.py --abi x86_64 --serial emulator-5554` — builds the self-contained run-root (loopback realm configs + DBs seeded by `tools/seed_realm_db.py`), pushes it + `libpocketrealm.so` + `pocket_lifecycle_test` (VERIFIED present, P25) to `/data/local/tmp`, runs the native test, reports PASS/FAIL | none (pocket_lifecycle_test + seeds are build-lane products in-repo; NOT the JNI gate — LV-36 leg (b) covers exports) | native test exit 0 (PASS); the tool's honesty contract: client-data health conditions may report BLOCKED_ON_CLIENT_DATA — recorded, never faked (tools/run_realm_test.py:1-15); rc≠0 on any real failure or missing prerequisite | P1 |
| LV-server-db-45 | Ledger seal chain at rest — staged asset manifest == G3-3 golden; datadir migration marker well-formed | vanilla | `python -c "import hashlib;h=hashlib.sha256(open('native/.build-x86_64/mariadb-staging/assets/database/migrations/manifest.json','rb').read()).hexdigest();print(h);print(h=='3771fc35b4ceddbadd6f786d76b10902db319d07c7d6372156417e4e3d81dc68')"`; `python -c "import json;print(json.dumps(json.load(open(r'%LOCALAPPDATA%/PocketRealm/database/sqlite-datadir/.pocketrealm-migrations.json')), indent=1))"` | none | staged manifest sha == the G3-3 golden (**RAN: TRUE, P22** — the exact bytes the build lane ships match the schema pin); marker well-formed: schema 1, `provider "sqlite-3.46.1-in-tree"`, `migrationCount 4` (one per realm DB). Recorded open item (P22): the marker's `manifestSha256` (`d3a25daf…`) matches neither the golden nor `schemas/sqlite-seed-baseline.json` — it binds the seeder's `-PstagingRoot` staged manifest, whose source file was not identified this ask; asserting that hash's provenance stays open for the executor | P2 |
| LV-server-db-46 | Remaining desktop DB-lane JVM classes: seeder, sqlite seam, database service | vanilla | `cd desktop && ./gradlew test --tests com.pocketrealm.database.DesktopSqliteSeederTest --tests com.pocketrealm.database.DesktopSqliteSeamTest --tests com.pocketrealm.database.DesktopDatabaseServiceTest --console=plain` | none | all three green: **RAN 19 tests, 0 failures (P23)** — Seeder 5 (seed pipeline), Seam 7 (production define set / UTF-16 boundary / shim-derived JNI gate), DatabaseService 7 (service-level DB ops) | P2 |
| LV-server-db-47 | Meta-ledger damage: reconstruction vs fail-closed overlay guard (TF-3) | vanilla | python script: (1) copy `pocketrealm_meta.sqlite3` aside; (2) truncate it to 0 bytes; (3) `cd desktop && ./gradlew supervisorStartGate --console=plain` → meta is REBUILT (openMeta create-if-absent + DDL, DesktopTimeframeOverlay.kt:165-173) and the stage re-recorded (`recordApplied`); (4) restore; (5) write garbage bytes (non-sqlite) into `pocketrealm_meta.sqlite3`; (6) re-run gate → expect refusal carrying `world start refused: timeframe overlay failed` + lifecycle record `start-failed-overlay` (guard at DesktopRuntimeBackend.kt:2419-2433); (7) restore, re-run gate green; `python -c` confirm the restored meta's `timeframe_stage_state` row is unchanged from the backup | none | leg 3: gate GREEN with the meta reconstructed and the applied stage re-recorded — the booted world's conf and meta state agree (no contradiction ever boots); leg 6: refuse-before-boot with the overlay-failure copy and the `start-failed-overlay` lifecycle record — never a throw-induced crash and never a boot over an unreadable ledger; leg 7 green, meta byte-identical to backup | P1 |
| LV-server-db-48 | Re-seed fail-closed: the seeder refuses an already-seeded datadir (DESK-19 seeder negative half) | vanilla | NOW leg (**RAN this round, P26**): `cd desktop && ./gradlew seedRealmData --console=plain` over the production datadir → rc=1 `DB-SEED: datadir already holds seeded databases`; FRESH-ROOT leg (designed; daemon hygiene per matrix-flavor-import.md's -29 pattern): `cmd /c "set LOCALAPPDATA=<temp-pr-fresh> && cd /d E:\pocket_realm_complete\desktop && gradlew.bat --no-daemon seedRealmData"` → green with the pinned counts, then `gradlew.bat --stop` + temp dir removed | none | now leg: rc=1 with the DB-SEED copy (never a silent reseed over live data — DesktopSqliteSeeder.kt:105,142 also refuses any single existing DB and any WAL/shm debris on the partial); fresh-root leg: rc=0, four DBs, pinned statement counts, no sidecars; cleanup leaves no daemon under the temp root | P1 |
| LV-server-db-49 | Manifest-driven seeder end-to-end: the 414-entry golden ledger → four SQLite DBs, hash-verified per entry | vanilla | `python tools/seed_sqlite_from_manifest.py --out <scratch-dir>` (add `--write-baseline` to re-emit `schemas/sqlite-seed-baseline.json` form; `--transcripts <dir>` for the pinned-amalgamation replay transcripts; `--max-errors` defaults to 0 = fail-loud) | none (source SQL from the pinned in-repo trees; manifest is the golden) | **RAN this round (P27): rc=0 `SEED OK`; report manifest_sha256 == the G3-3 golden `3771fc35…`; per-DB applied == statements with 0 errors: 43 / 1,110,192 / 12 / 27,886 (exactly the pinned harness counts); four .sqlite files produced.** Re-runs must reproduce the same report deterministically; any sql_sha256 mismatch or statement error aborts with zero tolerated errors | P1 |
| LV-server-db-50 | World chat-injection bridge contract (server half of the whisper surface) | vanilla | `cd desktop && ./gradlew whisperGate --console=plain` | none | **RAN this round (P28): `BRIDGE GATE PASSED`** — boots database+realm+world and proves the injection surface: offline whisper refused honest `sender-not-online`, unknown-channel refused honestly, `onlinePlayers` honest, memory-state JSON emitted (docs/WINDOWS_QUALIFICATION.md:23). Re-runs must keep all refusals honest (never a fake delivery) | P2 |

## Coverage map (deliverable ID → test IDs)

| Deliverable (inventory area) | Tests |
|---|---|
| DESK-5, DESK-47, DESK-78 (mariadb-lane) | LV-server-db-20, -21, -22, -23, -24, -25, -35, -37, -38, -40 |
| DESK-106 (provider lane + dual-provider window) | LV-server-db-22, -23, -35, -37, -38, -40, -09 (db-status) |
| DESK-108 (in-app DB initialization) | LV-server-db-06, -33 |
| DESK-129, DESK-131 (storage roots & durability) | LV-server-db-10, -18 |
| DESK-19 (ledger & seals into seeder + recovery) | LV-server-db-01, -14, -15, -48 (re-seed refusal), -49 (golden-manifest seeder) |
| DESK-53 (distinct refusal codes) | LV-server-db-14 |
| DESK-109 (save-exit shutdown-hook drain) | LV-server-db-07 |
| DESK-79, DESK-98 (re-proof in recovery; prepare-for-start) | LV-server-db-06, -14, -15 |
| G3-4 (journal codec flavor-optional, pre-flavor tolerant decode) | LV-server-db-14 (legs 6-7), -15 |
| DESK-6, DESK-18, DESK-37, DESK-100 (snapshots & restore) | LV-server-db-16, -18, -19, -37 (provider boundary) |
| DESK-7, DESK-33, DESK-38, DESK-60, DESK-101 (realm archive) | LV-server-db-17 |
| DESK-127 (realm-status telemetry) | LV-server-db-09 |
| G3-3, F3-1 (ledger golden seal, per-flavor naming) | LV-server-db-11, -12, -13, -45 (staged-asset chain at rest) |
| TF-1, TF-6, TF-8 (registry, era sync, tooling) | LV-server-db-28 |
| TF-2 (overlay appliers both platforms) | LV-server-db-29, -33, -25 |
| TF-3 (world-start wiring) | LV-server-db-30, -47 (meta damage: reconstruction vs fail-closed guard) |
| Realm-runtime native ABI (server half, on-device) + runtime-byte supply chain | LV-server-db-44 (pocket_lifecycle_test), -43 (win-lane lockfile fence; DESK-115's register home stays with the updates area — this row covers the server-byte legs only) |
| TF-4 (default + golden conf pins) | LV-server-db-30, -32 |
| TF-5 (impossible-stage rejection) | LV-server-db-31 |
| TF-7 (v1 gating data / inert scaffolding) | LV-server-db-30 (zero-SQL-at-default leg) |
| TF-9 (on-device applier leg — never run) | LV-server-db-33 |
| MP:GUEST-BANS (server half; finding 5.34) | LV-server-db-26, -27 |
| C1 realmbuilds (the ADVERTISEMENT datum — the build gate is CLIENT-side: core `ok_build` shapes only the realmlist advertisement, executed refutation of a challenge-level refusal in multiplayer round 4) | LV-server-db-34 (artifact client-declines leg + artifact-free advertisement check), matrix-multiplayer.md LV-multiplayer-18 (wrong-client negative, client-side), matrix-flavor-import.md LV-flavor-import-18/19 (app-layer VAL-03 identity gate — a different, still-valid layer) |
| G3-1, G3-2 (vanilla-regression shims — adopted cross-matrix, audit round 2) | **LV-flavor-import-43** (G3-1 UserAccountStore twins) and **LV-flavor-import-41(a)+(c)** (G3-2 client- + data-manifest alias gates) in matrix-flavor-import.md — adopted from this file's round-1 assignment; no server-db row (out of area, see boundaries) |

## Scope ruling — emulator-instrumented rows (recorded at audit round 1; owner decision carried verbatim)

**Ruling:** the emulator-instrumented rows in these matrices — `connectedDebugAndroidTest` rows
(LV-server-db-33), the `WorldConsoleRelay` / `tools/world_console.py` rows (LV-server-db-09, -27) and
the on-device sanity row (LV-server-db-22) — are **in-scope for the unattended headless lane** on
`PocketDiff_x86_64`. The RP6 physical device **remains excluded** (android/app/build.gradle.kts:107-112
refuses non-emulator serials; the deliberate hardware path stays untouched).

Basis (verified): the AVD exists and is launchable headlessly (environment.md); the x86_64 APK build
path is the lane's own (`-PpocketAbi=x86_64`, tools/run_differential_parity.py:109); the instrumentation
driver is present (world_console.py:40); `requirements.md` §1's "Android instrumented lane" line lists
the device-matrix/RP6-era blockers and predates the emulator-lane decision.

**Manifest annotation to apply (owner action — this file's author is restricted to writing
matrix-server-db.md only, so requirements.md §1 was NOT edited):** append to the §1 paragraph after the
"cannot unblock" list —

> *Audit-round-1 ruling (2026-10-02): "Android instrumented lane" here means the RP6/device-matrix era
> blockers. The headless-emulator instrumented lane (PocketDiff_x86_64, x86_64 debug + androidTest
> APKs) is explicitly IN-SCOPE for the unattended live lane; connectedDebugAndroidTest and
> WorldConsoleRelay rows in the live-verification matrices run under this ruling. RP6 remains excluded.*

Until that annotation lands, this section is the governing record for the executor.

## Cross-matrix ownership assignments (audit round 1)

The vanilla-regression shims **G3-1** and **G3-2** are un-owned by any matrix; the audit assigns them
to **matrix-flavor-import.md** (near LV-flavor-import-22). This file's author cannot write that
section file, so the assigned rows are recorded here verbatim for that matrix's owner to adopt.
**ADOPTED (audit round 2):** matrix-flavor-import.md now owns both — G3-1 as **LV-flavor-import-43**
(UserAccountStore twins: android unit twin `UserAccountStoreValidationTest` + desktop JVM twin +
crafted-record quarantine + vanilla byte-stability legs) and G3-2 as **LV-flavor-import-41(a)+(c)**
(client-manifest disagreement via DesktopClientGenerationStoreTest + data-manifest envelope legs
absent-alias→vanilla / disagreeing→fail-closed via DesktopDataPreparationStoreTest over
`PreparedDataStore.validateDataManifestEnvelope`). The rows below are kept as the original
assignment record:

- **G3-1 (UserAccountStore twins — optional flavor at SCHEMA=2):** store-level row over isolated
  roots (desktop twin root exists: `%LOCALAPPDATA%/PocketRealm/user-account`, observed P12; Android
  unit twin): (a) a vanilla account record is byte-stable across a write/read round-trip (flavor key
  written only when non-vanilla); (b) an unknown flavor VALUE (e.g. `"flavor": "superunknown"`) is
  quarantined, never adopted; (c) SCHEMA stays 2. Suggested mechanism: `gradlew testDebugUnitTest
  --tests <UserAccountStore unit twin>` + JVM desktop-twin class, plus a crafted-record live leg.
- **G3-2 (manifest flavor alias — disagreeing values fail closed):** negative row: edit a managed
  generation's `client-manifest.json`/`data-manifest.json` flavor alias to a value disagreeing with
  the stamped content → the reader must fail closed (refuse the generation), never silently re-read
  as vanilla; absent alias defaults to vanilla. Suggested mechanism: python edit of the manifest under
  `%LOCALAPPDATA%/PocketRealm/content/client/generations/<gen>/` (or the JVM twin) + the manifest
  reader gate.

The flavor-optional journal shim **G3-4** is owned HERE: LV-server-db-14 legs 6–7 (corrupt journal
honesty + pre-flavor tolerant decode) and LV-server-db-15.

## Honesty notes

1. **What was actually executed this ask** is exactly premises P1–P18 plus the six targeted desktop
   JVM classes (73+8+9+40 = 130 desktop tests, 0 failures) and the two pytest batches (131 tests).
   Every other row is a **design** whose mechanism was verified to exist as cited (task definitions,
   script CLIs, test classes, file layout) but whose live execution is the next stage's work — the
   bring-up gates (LV-01..-08), the emulator rows (LV-09, -22, -27, -33), the tamper/DR drills
   (LV-14, -15, -18, -30, -31) and all R5/R1–R4 rows were **not run**. The LV-server-db-36
   pre-flight scans WERE run (P15/P16); audit-round-2 correction: finding 5.22 does **not**
   reproduce on the loaded library (`libpocket_world_runtime.so`, 5/5) — the round-1 "FAILING"
   verdict scanned the vestigial `libpocketrealm.so` and is withdrawn (see LV-36 leg (b)).
2. `tools/win_kill_matrix.py` (LV-08) exists at `tools/win_kill_matrix.py` with `--scenario
   {early-boot,mid-world-run,mid-save}` + `--soak-min` (read this ask); not executed.
3. No CLI surface exists for the snapshot/archive/guest-ban **app verbs** against production roots
   (they live behind `DesktopRuntimeBackend`/app model; grep-verified — only the JVM gates drive
   them). LV-18 therefore re-implements the store's on-disk layout from outside for one DR drill;
   LV-17/26 record the gap instead of inventing commands.
4. The timeframe live rows (LV-30/-31) mutate `%LOCALAPPDATA%/PocketRealm/settings/settings.json`;
   each mechanism includes an explicit backup/restore step, and the settings field name was pinned
   to `timeframeStage` (Settings.kt:365,608) and the conf key to `PocketRealm.TimeframeStage`
   (ServerRuntimeFiles.kt:237, TimeframeStage.kt:85).
5. Gradle test runs used `--no-daemon --console=plain`; the second/third runs cleaned
   `desktop/build/test-results/test/` (only the current run's XML survives), which is why premise
   P7's per-class counts were transcribed from the 13:02 XMLs before the later runs.
6. Standing disclosure carried: the repo's 8 pre-existing python-lane failures (gladio ×2, vortek ×6,
   inventory:289) are outside this area; none of the 131 tests run here touched those files.

## Round-2 additions (2026-10-02, same ask series)

- **New rows LV-server-db-38..-42** (differential parity lane, WAL checkpoint post-kill, user-state
  bridge, seed-floor preflight, on-device one-lifetime/kill-restart cycle) — grounded on the round-2
  checks P19–P21. LV-38/-40 are the two big cross-provider interaction tests round 1 lacked.
- **New checks run in round 2:** `pytest tests/test_differential_lane.py
  tests/test_sqlite_user_state_bridge.py tests/test_world_runtime_relay_contract.py -q` → **44
  passed** (P19); `python tools/generate_timeframe_era.py check` → **OK** (P20); `ls
  tools/validate_timeframes.py` → absent (P20, inventory TF-8 discrepancy recorded in LV-28).
- **LV-28 strengthened** with the era-chain check; **LV-36 leg (a) strengthened** with the same chain
  as a second, hash-based gate over the finding-5.24 header.
- Premise md5 of the round-1 file for diffing: `589bd21bf8223648b63ded91ff8a4530` (37 rows); this
  round ends at 42 rows.

## Round-3 additions (audit round 2 fixes, 2026-10-02)

- **Finding-5.22 verdict reconciled (high):** LV-server-db-36 leg (b) and premise P15 re-pointed to
  the library the JVM actually loads — `WorldNative.kt:4 = System.loadLibrary("pocket_world_runtime")`,
  packaged from `native/.build-o09-<abi>/realm-staging-sqlite/jniLibs/<abi>/` (build.gradle.kts:330-337).
  Re-verified by byte-scan this round: 5/5 distinct friend-account JNI export names in BOTH
  sqlite-lane trees and the Windows DLL; 0/5 only in `pocket-runtime-build/libpocketrealm.so`
  (build.gradle.kts:290), now annotated VESTIGIAL for this gate. The round-1 "FAILING (0/0)" status
  is withdrawn; reconciled with LV-flavor-import-38 (same correction, adopted by its owner) and
  timeframe-era's 5.22 leg. The honesty caveat (byte-scan, not llvm-nm) is retained.
- **`-PpocketAbi` made explicit on every android gradle invocation (high):** legend note added
  (build.gradle.kts:948-951 throws without it; `-PpocketLane` defaults to `full`, :961, but is
  spelled on APK-pair builds); LV-33, LV-31, LV-32, LV-41 now carry it; Git-Bash
  `cmd //c "gradlew.bat …"` convention noted per the timeframe-era operational note.
- **Emulator PATH (low):** legend note added — bare `emulator` is not on PATH; full binary path
  verified (`-list-avds` → PocketDiff_x86_64, PocketRealm_API28); LV-22's launch line uses it.
- **Budgets (low):** LV-38 ≤~2 h at `--profile quick` (INSTRUMENT_TIMEOUT_S, run_differential_parity.py:49)
  and LV-22 ≤~2.5 h for both legs (run_baseline_world_sanity.py:88 caps) added; exit-2 PASS-GATED
  recorded as a valid LV-38 outcome; sequential one-emulator rule restated.
- **G3-1/G3-2 adoption closed (high):** matrix-flavor-import.md now owns both — G3-1 as
  LV-flavor-import-43, G3-2 as LV-flavor-import-41(a)+(c) (its owner amended the file this round;
  no edit made from this file's side). Coverage map records the adoption; this file's ownership
  section is updated to ADOPTED with the original assignment text preserved as the record.
- No test rows were added or removed this round: still **42 rows** (LV-server-db-01..-42);
  idsCovered and needsArtifacts unchanged.

## Round-3 additions (2026-10-02)

- **New rows LV-server-db-43..-47:** runtime-byte supply-chain fence (lockfile pins + win-lockfile
  pytest), on-device native realm lifecycle (`tools/run_realm_test.py`, `pocket_lifecycle_test`
  verified present), ledger seal chain at rest (staged asset manifest == G3-3 golden — RAN TRUE,
  P22), the three remaining desktop DB-lane JVM classes (**RAN 19/0, P23**), and the meta-ledger
  damage pair (reconstruction contract vs `start-failed-overlay` guard, grounded in
  DesktopTimeframeOverlay.kt:165-173 + DesktopRuntimeBackend.kt:2419-2433).
- **New checks run in round 3:** `pytest tests/test_win_lockfile.py -q` → **10 passed** (P24); the
  three-class desktop gradle run → **19 tests, 0 failures** (P23); the staged-asset manifest sha ==
  golden (**TRUE**, P22); `check_ledger_golden.py` + `check_timeframes.py` re-run → both still OK
  (P25); `check_repo.py` wiring read — `check_ledger_golden` at :75-82 invoked :326, leak sweep
  :61-72 (G3-3's "wired into check_repo" verified; the full check_repo run belongs to the
  repo-hygiene lane, not re-run here).
- **Recorded open item (P22):** the datadir marker's `manifestSha256` (`d3a25daf…`) matches neither
  the golden nor `schemas/sqlite-seed-baseline.json` — it binds the seeder's `-PstagingRoot` staged
  manifest; source not identified, provenance assertion left open (LV-45 records it).
- Row count now **47** (LV-server-db-01..-47). idsCovered and needsArtifacts unchanged.

## Round-4 additions (2026-10-02)

- **New rows LV-server-db-48..-50:** the fail-closed re-seed refusal (refusal half **RAN live**:
  `seedRealmData` rc=1 `DB-SEED: datadir already holds seeded databases`, DesktopSqliteSeeder.kt:105),
  the manifest-driven seeder end-to-end (**RAN**: `SEED OK`, 414 entries, report manifest sha == the
  G3-3 golden, per-DB counts 43 / 1,110,192 / 12 / 27,886 all applied with 0 errors — exactly the
  pinned harness numbers), and `whisperGate` (**RAN: `BRIDGE GATE PASSED`**, honest
  sender-not-online refusal) — the last bring-up gate that had no matrix row.
- **Cited-but-unexecuted classes now RAN:** DesktopSupervisorJournalTest 4/4 (grounds LV-14's G3-4
  journal legs) and FriendAccountNativeOpsTest 1/1 against the live stack (grounds LV-26/-27's
  ban/roster legs) — P26.
- **New checks run in round 4:** the P26–P28 runs above. Row count now **50**
  (LV-server-db-01..-50). idsCovered and needsArtifacts unchanged.

### 4.3 — section file `matrix-bots-llm.md` (embedded verbatim)

# Live-Verification Matrix — bots & LLM (bot upgrades, LLM chatter lane, guest bans, friend access)

Written 2026-10-02 by the matrix-designer step (area `bots-llm`, round 1; **round 2 extension** below
the original rows). One row per CLI-driven live test. Deliverable IDs covered
(campaign-inventory.md:32,160-163,203-216,221-239,247-253,264-268):
desktop-bots DESK-8/23/43/99/116/117/118/122/123/124/77/134, bot-upgrades BOT-PIN / BOT-CONFMATRIX /
BOT-POLICY, llm-lane DESK-9/26/28/48/80/81/103, multiplayer friend-access rows MP:RA-VERIFIER-PATH /
MP:RA-INVITE-TOKENS / MP:RA-RATE-LIMITS / MP:GUEST-BANS / MP:HARDENING-FIXES (ban-pragma legs), plus
the implied cross-area interaction TF-6 (chat era-policy × bot chatter).

**Round 4 additions (LV-bots-llm-43…44):** the vanilla conf golden byte-pins (G3-7 — the emitted
bot conf surfaces rows 09/15/18 observe, pinned as bytes incl. the aiplayerbot disabled-conf text and
the topology-key-absence assert) and the friend-access client-side unit trio (Srp6AccountMath §3
on-device (s,v) derivation; FriendAccessHub provision-refusal + moderation verdicts). Both are
android unit rows carrying the mandatory `-PpocketAbi=x86_64`. Searched but NOT designed this round:
the "bot voices are offline" transport-failure notice (llm-settings-guide.md §replies) — its emission
point was not located in the pinned sources (grep over PlayerbotLlmBridge.cpp/Memory.cpp hits only
comments), so no row can pin an observable for it yet; and live riding/flight bot behavior, which
needs a game client (the campaign records the bot-flight datapoint as externally blocked,
campaign-inventory.md:277) — the conf-emission half is row 11.

**Round 3 additions (LV-bots-llm-39…42):** the LLM settings mid-session inertness contract, the
python chatter-lane battery (EXECUTED this round: `python -m pytest tests/test_llm_security_refusal.py -q`
→ `7 passed in 0.02s`; the 7-file battery → `112 passed, 2 skipped in 9.19s`), the battery-forced
CONSTRAINED chatter-pacing leg on the emulator (gated on the ambience toggle — a UI-only setting
today, Android settings are a DataStore binary, Settings.kt:146-147, so no CLI pre-stage exists),
and the ban-while-realm-stopped leg (DesktopGuestBans.kt:25-28).

**Round 2 additions (LV-bots-llm-33…38):** the auth/realmd liveness surfaces that rows 27-29 depend
on but never drove directly (`authGate`, `realmdHold` + raw TCP probes), the Android relay's
account-lifecycle/persistence verbs, the live corrupt-staged-model fail-closed leg, and the
ambience-yields-to-interactive chatter interaction (LATER, R6). New grounding this round:
the moderation-audit sink resolves to `%LOCALAPPDATA%\PocketRealm\realm\friend-access\
friend-access-audit.jsonl` (DesktopAppModel.kt:388,1057-1067,1638; FriendInviteStore.kt:55,597)
and is pinned at unit level by FriendInviteStoreTest:65-66,213 (audit rows carry actions, not
secrets; the create-refused audit) — folded into row 30.

## Lanes and driving surfaces (all verified by reading E:/pocket_realm_complete this ask)

| Surface | What it is | Grounding |
|---|---|---|
| `gradlew rpHost [-PrpBots=N]` | Desktop stdio JSON-line world console: boots db+realm+world, one JSON op per stdin line → one `@@RP@@`-prefixed JSON per stdout line; EOF/`stop` drains with a save. Ops: `hello, account, chat, memory, reset, online, bots, botstatus, save, detail, autologin, stop, proto-login, proto-world, proto-create, proto-chars, proto-pick, proto-chat, proto-close`. `proto-*` is a pure-Kotlin 1.12 protocol client (SRP6 vs live realmd, world auth, char create/pick, chat capture) — real protocol logon with **no game client**. | desktop/build.gradle.kts:365-381; desktop/src/main/kotlin/com/pocketrealm/desktop/RpHost.kt:15-32,102-263,284-301 |
| Gradle gates (desktop, `cd E:\pocket_realm_complete\desktop`) | `bootRealmd` (:302), `realmdHold` (:311), `authGate` (:321, full 1.12 SRP6 logon, account `AUTHGATE`), `bootWorld` (:330), `whisperGate` (:353, chat-injection bridge contract incl. honest sender-not-online/unknown-channel), `supervisorStartGate` (:246), `test` (forkEvery=1, one live-world budget per class). | desktop/build.gradle.kts:246-381; docs/WINDOWS_QUALIFICATION.md §1 gate table |
| Android relay console | Long-lived instrumentation `com.pocketrealm.database.WorldConsoleRelay` in the androidTest APK; ops via `adb push filesDir/console-in.json` → poll `console-out.json`. Ops include `stack-up-bot [profile], world-start-bot <profile>, world-bots <n>, world-status, world-bot-status, world-save, world-pause <0|1>, world-kill, world-account/-verify/-gm/-account-status/-persistence, world-chat, reset-state, llm-memory-state, realm-status, db-status, db-health, ping, stop`. Repo drivers: `tools/world_console.py` (REPL, stdin-pipable) and `tools/rp_harness/run_suite.py --suite smoke` (batch + A8 world-log post-pass). | tools/world_console.py:14-42,120-125; android/app/src/androidTest/java/com/pocketrealm/database/WorldConsoleRelay.kt:33-200; tools/rp_harness/run_suite.py:1-14 |
| Staged-conf observation | The world's confs land at `%LOCALAPPDATA%\PocketRealm\runtime\server\run\` (`realmd.conf`, `mangosd.conf`, `aiplayerbot-<profileId>.conf`, `aiplayerbot-disabled.conf`, `llm_prompt_pack.json`, `chatter-power.conf`); the admission record at `runtime/server/bot-admission.json`. | desktop/src/main/kotlin/com/pocketrealm/server/ServerRuntimeFiles.kt:57,129-189,375,594-595; DesktopRuntimeBackend.kt:342 |

## Verified NOW-state of this host (checks I ran this ask)

- Prepared data + seeds are staged: `%LOCALAPPDATA%/PocketRealm/content/o11-server/active-vanilla.json`
  → generation `7ceb6c39…` containing `dbc/ maps/ vmaps/ mmaps/` + `data-manifest.json` (`ls` run);
  `database/sqlite-datadir` holds the four seeded DBs + meta (149 MB, `du -sh`). So every world-boot
  leg below is **artifact-free on this host** — R3 (the 5875 client) is needed only to *regenerate*
  prepared data (tools/win_prepare_data.py), not to run these tests.
- Native runtime DLLs staged: `native/.build-win-x86_64/pocket-runtime-build/{pocket_world_runtime.dll,
  pocket_realmd_runtime.dll}` exist (`ls` run).
- **`cd E:/pocket_realm_complete/desktop && ./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopGuestBansTest" --console=plain`**
  → BUILD SUCCESSFUL; result XML `desktop/build/test-results/test/TEST-…DesktopGuestBansTest.xml`:
  **tests=8 failures=0 errors=0 skipped=0**. (Bash note: `gradlew` must be invoked as `./gradlew.bat`.)
- **`python tools/check_sources.py`** (in E:/pocket_realm_complete) → `OK playerbots 7e2cd2fbbbb4`;
  `PEND playerbots-tbc/wotlk: pin 7e2cd2fbbbb4 registered, checkout pending … expected until its wave
  registers` — the BOT-PIN contract, live.
- **`python tools/generate_conf_matrix.py --list-surfaces`** → rc 0, 8 dist surfaces listed incl.
  `native/playerbots/playerbot/aiplayerbot.conf.dist.in` (+ `.tbc`/`.wotlk` variants on disk).
- `git -C native/playerbots log -1` → `7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d`; `git -C native/cmangos log -1`
  → `ce83805d48f9…` (= pins).
- Host tooling: Python 3.11.9, pytest 9.1.1, sqlite3 CLI present.
- **Not run this ask** (design-only): any emulator boot, APK build/install, rpHost/whisperGate/bootWorld
  execution, FriendAccountNativeOpsTest/ProvisioningListenerTest execution. Their commands below are
  grounded in the cited sources, not in a run.

## Artifact register (requirements.md §2) mapping

| Artifact | Tests gated on it |
|---|---|
| None — **NOW-test** (artifact-free) | LV-bots-llm-01…12, 13 (NOW leg: tbc/wotlk conf-matrix missing-dist refusal), 14…19, 22, 24, 25, 27…37, 39…44 (41 gated: ambience toggle on, else BLOCKED-recorded) |
| **R6** — the two pinned GGUFs with `url=""` (requirements.md:153-166); §R6 additionally documents the third registry entry as the self-downloading, no-user-action alternative (LlmModelRegistry.kt:141-147). **No external-endpoint/credential artifact exists in the manifest** — LV-bots-llm-21's endpoint is the in-repo-built llama-server standalone, which serves an R6 GGUF, so R6 is required either way | LV-bots-llm-20, 21, 23 (genuine generation legs) |
| **R4** forks `puzzled-pancake/mangos-tbc|-wotlk` | LV-bots-llm-13 (tbc/wotlk flavor legs; vanilla pin leg is artifact-free) |
| **R5** MariaDB 11.5.2 Windows provider | LV-bots-llm-26 (documents open finding 5.34) |

Every row marked `NOW` runs without user artifacts on this host. Rows marked `LATER` name their artifact.

## Scope ruling (audit round 1, recorded so it travels with the manifest)

**Emulator-instrumented rows are in-scope for the unattended lane; RP6/physical remains excluded.**
requirements.md §1 lists the "Android instrumented lane (TF-9, G3 device pins)" among the
hardware/people-dependent groups no artifact can unblock — that phrasing targeted the *physical-device*
lane and predates the environment decision. The governing environment decision (environment.md:105-122)
explicitly rules "Emulator-only is the sanctioned default": the fail-closed serial gate
(android/app/build.gradle.kts:105-134) refuses *non-emulator* serials and passes emulators, and the
headless PocketDiff_x86_64 AVD is the chosen lane. All `am instrument` / WorldConsoleRelay rows in this
matrix (and the sibling matrices) run against that emulator only. This ruling was recorded per audit
round 1 on 2026-10-02; requirements.md §1 carries the matching annotation.


- **Audit round 2 (bot-actors section edit):** LV-bots-llm-04's step 2 now carries the mandatory `-PpocketAbi=x86_64 -PpocketLane=full` (android/app/build.gradle.kts:948-951 throws at configuration otherwise; matrix-timeframe-era.md spot-proof 8 failed this live). Rows 08/22 inherit steps 1-5 of row 04 and need no separate line. LV-bots-llm-03's begin:2 contract (P-5) was already pinned in this file's earlier round and is unchanged.

## Shared conventions

- `%REPO%` = `E:\pocket_realm_complete`. `%RUN%` = `%LOCALAPPDATA%\PocketRealm\runtime\server\run`.
- **Android gradle invocations MUST carry `-PpocketAbi=x86_64`** — the property is mandatory,
  `android/app/build.gradle.kts:948-951` throws `GradleException("Missing required -PpocketAbi=<abi>…")`
  at configuration otherwise (verified live by matrix-timeframe-era.md spot-proof 8, :148). APK-pair
  builds add `-PpocketLane=full`; canonical form `gradlew :app:assembleDebug :app:assembleDebugAndroidTest
  -PpocketAbi=x86_64 -PpocketLane=full --console=plain` (matrix-flavor-import.md:71, per
  tools/run_differential_parity.py:115-121). From Git-Bash invoke gradle as `cmd //c "gradlew.bat …"`
  inside the project dir (matrix-timeframe-era.md:54).
- **The `emulator` binary is NOT on Git-Bash PATH** — the executor harness prepends
  `%LOCALAPPDATA%\Android\Sdk\emulator` (and `…\platform-tools`) to PATH, or launch lines spell the full
  binary (verified this round: `"C:/Users/<owner>/AppData/Local/Android/Sdk/emulator/emulator.exe"
  -list-avds` → `PocketDiff_x86_64`, `PocketRealm_API28`; the repo's lane resolves the same constant,
  tools/run_differential_parity.py:42-43).
- **The desktop `bots` retarget op is PROFILE-OWNED in the rpHost harness lane**: the world's app-layer
  admission monitor owns the target and the op answers `ok:false reason:"begin:2"` — EXECUTED
  observation, matrix-multiplayer.md:272 (`bots:5` → `begin:2`, `effectiveTarget=25` from its profile).
  Desktop rows drive admission through the owning profile and PIN the refusal as the contract; live
  retarget semantics run on the EMU lane through the app's own verbs (`world-start-bot`/`world-bots`,
  rows 05/36). Never assert `effectiveTarget` following a desktop `bots` op without this posture.
- **settings.json backup/restore (mandatory for any row that touches
  `%LOCALAPPDATA%\PocketRealm\settings\settings.json`)** — the LV-server-db-30 pattern
  (matrix-server-db.md:80,127): step 0 of the first mutating row copies the live file to a timestamped
  backup (`python -c "import shutil,time;shutil.copy2(src,src+'.lvbak-'+time.strftime('%Y%m%d-%H%M%S'))"`);
  every mutating row ends with a cleanup leg that restores from that backup and byte-compares
  (`python -c "import filecmp,sys;sys.exit(0 if filecmp.cmp(a,b,shallow=False) else 1)"` must exit 0)
  before the next row starts — no state leaks across the sequential run, and the user's live LLM config
  survives the session.

## Matrix

| testId | feature (deliverable IDs) | flavor(s) | exact CLI mechanism | required artifacts | pass condition | severity |
|---|---|---|---|---|---|---|
| LV-bots-llm-01 | Bot admission happy path: boot under the owning profile, population ramp, telemetry (DESK-8/23/43, BOT-POLICY vanilla row live) | vanilla | `cd /d E:\pocket_realm_complete\desktop`, then a stdlib-only `python -c` driver that (a) spawns `gradlew.bat rpHost --console=plain` with piped stdin/stdout — NO `-PrpBots`: the target is owned by the launch profile (Shared conventions; matrix-multiplayer.md:272 observed `effectiveTarget=25` from the profile in this lane), (b) writes `{"op":"hello"}`, then `{"op":"botstatus"}` every 15 s ×10, then `{"op":"stop"}`, (c) captures the `@@RP@@`-prefixed stdout lines to `build\lv-01.out`; finally a `python -c` json scan of `build\lv-01.out` (strip `@@RP@@` per line) | None (natives+seeds+prepared data staged on this host — see header) | `{"event":"ready","world":"READY"}` appears; `botstatus` reports the profile-owned `effectiveTarget` (non-zero, = the settings profile's initial target — 25 in the recorded observation) with `onlineBots` rising >0 toward it across the polls; `dbProbeDelayMs` present and not `UINT32_MAX` after ramp. Do NOT use the `bots` op in this lane — it refuses `begin:2` (Shared conventions) | High |
| LV-bots-llm-02 | Bot admission negative, two lanes: (a) desktop — the `bots` op is owned by the profile's admission monitor and refuses cleanly; (b) EMU — target 0 really closes the gate (DESK-8 refusal legs; RpHost.kt:83-85) | vanilla | (a) Same `python -c` stdlib driver as LV-bots-llm-01 (`gradlew.bat rpHost`, no `-PrpBots`), ops: `{"op":"botstatus"}` ×2, `{"op":"bots","target":0}`, `{"op":"botstatus"}` ×2, `{"op":"stop"}` → `build\lv-02a.out`. (b) In the LV-bots-llm-04 relay session: `(echo world-bots 0 & echo world-bot-status & echo quit) \| python tools\world_console.py` — `world-bots` is the app's OWN verb (WorldConsoleRelay.kt:151 → `worldApi().setBotTarget`), i.e. the owning path the desktop console op lacks | None | (a) the `bots` op answers `ok:false reason:"begin:2"` (the EXECUTED contract, matrix-multiplayer.md:272) and the following `botstatus` still shows the profile-owned `effectiveTarget` unchanged — a clean refusal, no partial application; (b) `world-bots 0` `ok:true`, `world-bot-status` shows `effectiveTarget=0` and no new bot logins across the window | High |
| LV-bots-llm-03 | Target ownership contract on a RUNNING world: desktop console op refused (pinned), live retarget proven on the owning lane (DESK-116/123) | vanilla | (a) Driver as LV-bots-llm-01 (`gradlew.bat rpHost`), mid-session ops: `{"op":"bots","target":2}`, `{"op":"botstatus"}` ×2, `{"op":"bots","target":7}`, `{"op":"botstatus"}` ×2, `{"op":"stop"}` → `build\lv-03.out`. (b) The honored retarget is LV-bots-llm-05's EMU sequence (`world-start-bot` + `world-bots 8` through `worldApi()`), which drives the same begin→set→retire generation handshake FROM the monitor's owner (DesktopBotAdmission.retarget, DesktopBotAdmission.kt:183-202) | None | (a) both desktop `bots` ops answer `ok:false reason:"begin:2"` (RpHost.kt:284-292 response shape; EXECUTED observation matrix-multiplayer.md:272) and `effectiveTarget` never leaves the profile-owned value — no stale queued target, no partial set; (b) on the EMU lane `world-start-bot`/`world-bots 8` return ok and `world-bot-status.effectiveTarget` follows the new target (row 05's condition) | High |
| LV-bots-llm-04 | Android admission lane: stack-up on the bot profile, raise target, read telemetry (DESK-8/99 Android service leg) | vanilla | 1. `emulator -avd PocketDiff_x86_64 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save` (environment.md launch line)<br>2. `cd /d E:\pocket_realm_complete\android && gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PpocketAbi=x86_64 -PpocketLane=full --console=plain` (audit round 2, bot-actors section: `-PpocketAbi` is MANDATORY — build.gradle.kts:948-951 throws at configuration without it; APK-pair canonical form per matrix-flavor-import.md:71; rows 08/22 inherit these steps)<br>3. `adb install -r app\build\outputs\apk\debug\app-debug.apk` + `adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`<br>4. Stage the prepared o11 generation (already host-staged at `build\o11-bot-data\o11-server`): `adb root` → `adb shell rm -rf /data/data/com.pocketrealm/files/content/o11-server && mkdir -p …` → `adb push build\o11-bot-data\o11-server\. /data/data/com.pocketrealm/files/content/o11-server/` → `adb shell chown -R $(adb shell stat -c %u /data/data/com.pocketrealm) …` + `restorecon -R` (tools/world_console.py:126-149 mechanics)<br>5. `adb shell am instrument -e class com.pocketrealm.database.WorldConsoleRelay com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner` (non-blocking; world_console.py:64-73)<br>6. `(echo stack-up-bot mobile-balanced-b100-v1 & echo world-bots 10 & echo world-status & echo world-bot-status & echo quit) \| python tools\world_console.py` (REPL is stdin-driven, world_console.py:120-158) | None (o11 generation staged host-side; verified `build/o11-bot-data/o11-server/active.json` present) | `stack-up-bot` op `ok:true`; `world-bots 10` `ok:true`; `world-status`/`world-bot-status` return tick percentiles + `botsOnline` rising toward 10; no `reconnect` transcript event (a reconnect FAILS per tools/rp_harness/README.md) | High |
| LV-bots-llm-05 | Live profile application while the world runs (DESK-116/123 Android `startBotProfile` leg) | vanilla | Continue LV-bots-llm-04's relay session (or re-run steps 1-5): `(echo world-start-bot mobile-typical-b50-v1 & echo world-bot-status & echo world-bots 8 & echo world-bot-status & echo quit) \| python tools\world_console.py` (relay op `world-start-bot -> worldApi().startBotProfile(arg1)`, WorldConsoleRelay.kt:149; profile id verified registered: BotProfiles.kt:562 `mobile-typical-b50-v1` — `mobile-balanced-b50-v1` has no match in that file and would fail the profile lookup) | None | `world-start-bot` `ok:true` with the new profile id; `world-bot-status` shows the new profile's effective target; subsequent `world-bots 8` retarget applies under the new monitor generation | High |
| LV-bots-llm-06 | Pause/resume + companion-mode interplay: admission withholds adaptation while paused (DESK-122/117 + DesktopBotAdmission.kt:84-87 worldPausedProbe contract) | vanilla | In the LV-bots-llm-04 relay session: `(echo world-pause 1 & echo world-status & echo world-pause 0 & echo world-status & echo quit) \| python tools\world_console.py`; desktop twin: `cd /d E:\pocket_realm_complete\desktop && gradlew.bat test --tests "com.pocketrealm.desktop.DesktopAppModelCompanionModeTest" --console=plain` | None | `world-pause 1` `ok:true`; `world-status` during pause reports frozen/no-advance tick sampling; `world-pause 0` resumes and ticks advance; companion-mode unit leg green | Medium |
| LV-bots-llm-07 | Admission sampler + decision-cycle legs and the persisted record conf feed (DESK-99, DesktopBotAdmission.kt:377-428) | vanilla | `cd /d E:\pocket_realm_complete\desktop && gradlew.bat test --tests "com.pocketrealm.desktop.DesktopBotAdmissionTest" --tests "com.pocketrealm.desktop.DesktopBotResourceSamplerTest" --console=plain`; then after any LV-bots-llm-01/03 run: `python -c "import json;p=r'%LOCALAPPDATA%\PocketRealm\runtime\server\bot-admission.json';import os;d=json.load(open(p)) if os.path.isfile(p) else None;print(d)"` | None | both test classes green (recorder-driven decision cycle: shed/adapt/refuse legs, sampler metrics); `bot-admission.json`, when present, is `{schema:1, profileId, effectiveTarget>0, selectedTarget, reason, at}` and `effectiveTarget ≤ selectedTarget` (the next boot's `PocketRealm.BotTarget` conf feed clamp) | Medium |
| LV-bots-llm-08 | Bot pressure benchmark: admission/load protection under stepped populations (DESK-8/23/43 heavy leg; MariaDB-vs-SQLite A/B is the mariadb lane's) | vanilla | 1. Steps 1-5 of LV-bots-llm-04 (APKs + o11 staged)<br>2. `python tools\run_bot_pressure_benchmark.py --waves 25:120,50:180 --sample-ms 5000` (tools/run_bot_pressure_benchmark.py:20-24; host-side preflight gate :46-62 runs first) | None (heavy: 30-60 min emulator soak) | benchmark report: each wave boots via `startBotProfile`, `setBotTarget` waves apply, telemetry samples (tick p50/p95, hard stalls, db probe delay, RSS) recorded; admission adapts target down under measured pressure (verdict `adapted:true` with a shed reason) and the stack survives with a client-acked saveall rc=0 per wave | Medium |
| LV-bots-llm-09 | Shipped defaults: default bot profile + conf emission shape (DESK-134) | vanilla | 0. settings.json backup per Shared conventions. 1. `python -c "import json,os;p=os.path.expandvars(r'%LOCALAPPDATA%\PocketRealm\settings\settings.json');s=json.load(open(p)) if os.path.isfile(p) else {};json.dump({k:v for k,v in s.items() if k!='botProfileId'},open(p,'w'))"` (fresh-read posture), then LV-bots-llm-01's rpHost run; then `python -c "import glob;print(glob.glob(os.path.expandvars(r'%LOCALAPPDATA%\PocketRealm\runtime\server\run\aiplayerbot-*.conf')))"` and print the file. 2. Cleanup leg: restore settings.json from the backup + byte-compare (Shared conventions) | None | staged `aiplayerbot-<default-profile>.conf` exists and carries the emitted pin set: `AiPlayerbot.Enabled=1`, `RandomBotAutologin=1`, `RandomBotAutoCreate=1`, `RandomBotMaps=0,1`, `MinRandomBots`/`MaxRandomBots` = the vanilla policy row window, `PocketRealm.GenerationBatchSize/-YieldMs/ActivationBatchSize` (BotProfiles.kt:136-156 emission source); restore byte-compare exits 0 | High |
| LV-bots-llm-10 | Population policy: vanilla row = classic constants, non-vanilla rows fail-loud (BOT-POLICY, D31) | vanilla (+ tbc/wotlk keys as *data*, not boots) | `cd /d E:\pocket_realm_complete\android && gradlew.bat test -PpocketAbi=x86_64 --tests "com.pocketrealm.bots.BotPopulationPolicyVanillaRowTest" --tests "com.pocketrealm.bots.BotPolicyTest" --console=plain` | None | both green: vanilla row pins 9/account, 10..10 000 max, 600/2000 budgets; a non-vanilla key lookup fails loud (no silent vanilla fallback) | Medium |
| LV-bots-llm-11 | Strategy/playstyle presets emit distinct real conf (DESK-118 presets; BotCustomConfiguration.kt:1-55 "no pretend settings"); launcher-level advanced-override resolution branch (DESK-124, DesktopRuntimeBackendTest.kt:1297-1322); riding/flight/strategy automation keys present in the generated matrix | vanilla | 1. `cd /d E:\pocket_realm_complete\android && gradlew.bat test -PpocketAbi=x86_64 --tests "com.pocketrealm.bots.BotPlaystyleDistinctnessTest" --tests "com.pocketrealm.bots.BotExperiencePresetTest" --tests "com.pocketrealm.bots.BotPassiveDelayTest" --tests "com.pocketrealm.bots.BotPresetStoreTest" --tests "com.pocketrealm.bots.BotProfilesCatalogTest" --console=plain`<br>2. `cd /d E:\pocket_realm_complete\desktop && gradlew.bat test --tests "com.pocketrealm.desktop.DesktopRuntimeBackendTest.launchProfileResolvesTheAdvancedBranchThroughThePersistedOverride" --console=plain`<br>3. `python tools\generate_conf_matrix.py --flavor vanilla --out tmp\conf-matrix-vanilla.json`<br>4. `python -c` scan of `tmp\conf-matrix-vanilla.json` for the automation keys: `AiPlayerbot.TransportTeleportType`, `AiPlayerbot.RndBotCheats`, `AiPlayerbot.RandomBotCombatStrategies`, `AiPlayerbot.RandomBotNonCombatStrategies`, `AiPlayerbot.UseWanderAsDefaultFollowStrategy` | None | preset tests green (distinct playstyles produce distinct emitted conf values); the desktop advanced-branch method green (`BotAdvancedSettings.fromProfile` override wins over the profile default through the persisted settings, `activeBotProfileId()` reflects it); matrix json contains all five automation keys with their dist defaults (dist ground truth: aiplayerbot.conf.dist.in:862-868 strategies, :165 transport/ride style, :1001-1003 `RndBotCheats = taxi,item,breath` taxi/flight automation; emission twin BotProfiles.kt:205) | Medium |
| LV-bots-llm-12 | BOT-PIN: single multi-expansion playerbots pin @ 7e2cd2fb, vanilla active, tbc/wotlk checkout pending expected-loud (D34/C4) | vanilla + tbc/wotlk (pin state only) | `git -C E:\pocket_realm_complete\native\playerbots log -1 --format=%H` then `cd /d E:\pocket_realm_complete && python tools\check_sources.py` (exit code + full output) | None (RAN this ask: pin = `7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d`; `OK playerbots 7e2cd2fbbbb4`; `PEND playerbots-tbc/-wotlk … registered, checkout pending`; final line `All active pins verified (6 per-flavor pin(s) pending registration — expected-loud)`) | rc 0; `OK playerbots 7e2cd2fbbbb4`; exactly the 6 documented PEND lines (cmangos-core-tbc/-wotlk, cmangos-db-tbc/-wotlk unregistered; playerbots-tbc/-wotlk checkout pending) and nothing else PEND | High |
| LV-bots-llm-13 | BOT-PIN negative + tbc/wotlk boots: unregistered fork pins must fail loud, never fabricate (C4/H3-1) — and the conf-matrix missing-dist refusal (BOT-CONFMATRIX) | tbc, wotlk | 1. `cd /d E:\pocket_realm_complete && python tools\generate_conf_matrix.py --flavor tbc --out tmp\conf-matrix-tbc.json ; echo rc=%ERRORLEVEL%` (cmangos-tbc core absent → fail-loud on missing dist, per inventory :267 and the tool's contract; also run `--flavor wotlk`)<br>2. **LATER (R4):** after the forks land and S2/S3 pins register: `python tools\build_win_realm_runtime.py --flavor tbc` then the tbc flavor boot leg (`gradlew` bring-up with the tbc lane staged) | NOW leg: none. LATER leg: **R4** | NOW: rc≠0 for `--flavor tbc`/`wotlk` with a message naming the missing pinned dist (no silent partial matrix). LATER: tbc/wotlk lanes build+boot only once their pins are registered; until then every tbc/wotlk request fails loud naming `cmangos-core-tbc`/`cmangos-core-wotlk` | Medium |
| LV-bots-llm-14 | LLM chatter injection bridge, honest-failure contract (DESK-77 `world-chat`; whisperGate) | vanilla | `cd /d E:\pocket_realm_complete\desktop && gradlew.bat whisperGate --console=plain` (boots db+realm+world; gates are the only callers needed — docs/WINDOWS_QUALIFICATION.md §1 "honest failures without a client") | None | gate exit 0: injection surface answers with the honest refusal reasons (`sender-not-online`, `unknown-channel`, `world-not-ready` posture) and `onlinePlayers` honest; no fabricated success without a live sender | High |
| LV-bots-llm-15 | LLM master switch OFF = scripted bots, no `:llm` block (llm-settings-guide.md §1 "When OFF the base conf's LLMEnabled = 0 wins") | vanilla | LV-bots-llm-01's rpHost run with default `%LOCALAPPDATA%\PocketRealm\settings\settings.json` (`llmEnabled:false`, Settings.kt:279); then `python -c "import os;d=open(os.path.expandvars(r'%LOCALAPPDATA%\PocketRealm\runtime\server\run\mangosd.conf')).read();print('LLMEnabled=2' in d, 'LLMApiJson' in d)"` plus a `python -c` scan of `aiplayerbot-disabled.conf` for `LLMEnabled=0` | None | world READY; `mangosd.conf` staged set contains **no** `AiPlayerbot.LLMEnabled=2` and no LLMApiJson block; the disabled profile conf carries `LLMEnabled=0`; no llama-server child process spawned (`tasklist /FI "IMAGENAME eq llama-server.exe"` empty) | High |
| LV-bots-llm-16 | LLM fail-closed negative: unusable external endpoint → NO LLM conf block at all (LlmRuntimePolicy.kt:69-97 `normalizeExternalEndpoint` returns null = "fail-closed: no LLM block is then emitted") | vanilla | 0. settings.json backup per Shared conventions. 1. `python -c` merge into `%LOCALAPPDATA%\PocketRealm\settings\settings.json`: `"llmEnabled":true, "llmExternalMode":true, "llmExternalUrl":"ftp://bad host:99999", "llmExternalModel":""`<br>2. rpHost driver as LV-bots-llm-01 (no `-PrpBots`), ops `{"op":"hello"}`, `{"op":"stop"}`<br>3. `python -c` scan of staged `run\mangosd.conf` for `LLMEnabled=2` / `LLMApiJson`<br>4. Cleanup leg: restore settings.json from the backup + byte-compare (Shared conventions) | None | world still READY (the LLM lane failing closed must not take the world down); staged conf contains **no** LLM block despite `llmEnabled:true` (the invalid URL was refused, not sanitized into something usable); restore byte-compare exits 0 | High |
| LV-bots-llm-17 | Prompt-pack editor fail-closed legs: control characters illegal, 1 200-byte seasoning-tail cap cuts the END (DESK-26; llm-settings-guide.md §external table; ServerRuntimeFiles.kt:375,595) | vanilla | 0. settings.json backup per Shared conventions. 1. `python -c` write into `settings.json`: `"llmPromptPackJson"` = a pack JSON whose seasoning rows include a `\x01` control char and whose joined seasoning tail exceeds 1 200 bytes<br>2. rpHost boot (ops `{"op":"hello"}`, `{"op":"stop"}`)<br>3. `python -c` print staged `run\llm_prompt_pack.json` and assert: control-char body fell back to the trained default; the over-cap tail is truncated at the END (the last-ordered blocks dropped), total ≤ 1 200<br>4. Cleanup leg: restore settings.json from the backup + byte-compare (Shared conventions) | None | world READY; staged pack reflects both degradations exactly (fallback + end-cut); no crash, no silent acceptance of the control-char row; restore byte-compare exits 0 | Medium |
| LV-bots-llm-18 | Battery-aware world-chatter power pacing (DESK-81): staged chatter-power file contract; AC host = NORMAL; unreadable read never dims | vanilla | 0. settings.json backup per Shared conventions. 1. `python -c` set `"llmAmbience":true` in `settings.json`<br>2. rpHost boot + `{"op":"stop"}`<br>3. `python -c "import os;d=open(os.path.expandvars(r'%LOCALAPPDATA%\PocketRealm\runtime\server\run\chatter-power.conf')).read();print(d)"`<br>4. `cd /d E:\pocket_realm_complete && python -m pytest tests\test_playerbot_mobile_pacing.py -q`<br>5. Cleanup leg: restore settings.json from the backup + byte-compare (Shared conventions) | None | `chatter-power.conf` exists with flat `enabled`/`dim`/`rung`/`at` lines and `rung` = NORMAL (4) on this AC-powered host (DesktopChatterPowerMonitor.kt:14-28 states; `computeRung` null/AC→NORMAL); pacing pytest green (CONSTRAINED ≤15 % off-charger pinned at unit level — the desktop has no CLI way to force a low battery); restore byte-compare exits 0 | Medium |
| LV-bots-llm-19 | Desktop LLM model-manager + sidecar fail-closed pins (DESK-9/28/48/80/103 unit legs: staging sha256 gate, sidecar args, absent-model refusal) | vanilla | `cd /d E:\pocket_realm_complete\desktop && gradlew.bat test --tests "com.pocketrealm.desktop.DesktopLlmModelManagerTest" --tests "com.pocketrealm.desktop.DesktopLlmSidecarArgsTest" --console=plain` | None | green: a pre-placed model is accepted only on a matching 64-hex sha256 (DesktopLlmModelStaging.kt:75-79), mismatches discard, downloads are host-allowlisted with redirect refusal (:167,236), and the sidecar arg set matches the pinned lane | Medium |
| LV-bots-llm-20 | **Genuine on-device chatter happy path**: embedded llama-server sidecar + tuned model + whisper → reply (DESK-9/28; WINDOWS_QUALIFICATION.md §2 step 4) — **LATER** | vanilla | 0. settings.json backup per Shared conventions. 1. `python tools\build_win_llama_server.py` (in-repo build action; source tarball cached per requirements.md §3 → `llama-staging` + lockfile `native\llm\lockfile-win-x86_64.json`)<br>2. Place R6 model: `copy <user-gguf> %LOCALAPPDATA%\PocketRealm\runtime\llm\models\gemma4-E2B-TUNED-q4_0.gguf` (sha256-verified on use; DesktopLlmModelStaging)<br>3. `python -c` settings: `"llmEnabled":true,"llmExternalMode":false,"llmModelId":"<tuned id>"`<br>4. rpHost (no `-PrpBots`) with ops: `{"op":"account","user":"RPTEST","pass":"rptestpass"}`, `{"op":"proto-login","user":"rptest","pass":"rptestpass"}`, `{"op":"proto-world","user":"rptest"}`, `{"op":"proto-chars"}`, `{"op":"proto-create","name":"Rpprobe"}`, `{"op":"proto-pick","guid":<guid from proto-chars>}`, `{"op":"proto-chat","kind":"whisper","target":"<online bot name>","text":"hail friend","drainMs":8000}`, `{"op":"memory","player":"Rpprobe"}`, `{"op":"stop"}`<br>5. `curl -s -o NUL -w "%{http_code}" http://127.0.0.1:8080/health` while up<br>6. Cleanup leg: restore settings.json from the backup + byte-compare (Shared conventions) | **R6** (tuned GGUF; sha must match LlmModelRegistry.kt:80-83) + the in-repo llama-server build (MSVC toolchain) | `/health` → 200; `proto-chat` returns captured bot reply lines (≥1 line, ≥3 words per assertions.py `bot_reply`); `memory` delta shows new relationship/fact rows; world.log shows the A8 per-turn shape `BotLLM: dispatch req=N` → `begin` → exactly one `end … class=ok`; restore byte-compare exits 0 | High |
| LV-bots-llm-21 | Genuine **external-endpoint** chatter: the same request path at an OpenAI-compatible URL (llm-settings-guide.md §external) — **LATER** | vanilla | 0. settings.json backup per Shared conventions. 1. As LV-bots-llm-20 but: run the built llama-server standalone as the endpoint (`<staging>\llama-server.exe --port 8090 -m <R6 model>`; it is OpenAI-compatible — the in-repo endpoint keeps this row R6-gated; requirements.md documents no external-credential artifact); `python -c` settings: `"llmEnabled":true,"llmExternalMode":true,"llmExternalUrl":"http://127.0.0.1:8090","llmExternalModel":"local"`; same rpHost proto-chat capture; additionally `curl -s http://127.0.0.1:8090/v1/chat/completions`-reachability pre-check<br>2. Cleanup leg: restore settings.json from the backup + byte-compare (Shared conventions) | **R6** (the standalone llama-server endpoint serves an R6 GGUF — no separate artifact exists) | staged `mangosd.conf` carries the external `LLMApiJson` block with the normalized URL (`EXTERNAL_CHAT_COMPLETIONS_PATH` appended for bare origins, LlmRuntimePolicy.kt:47-49); whisper produces a reply whose `BotLLM end class=ok` proves the request path; key never appears in logs (redaction); restore byte-compare exits 0 | Medium |
| LV-bots-llm-22 | Android chatter smoke suite (relay-min): whisper → reply/memory evidence → memory sanity (DESK-28 Android lane) — NOW form | vanilla | Steps 1-5 of LV-bots-llm-04, then: `python tools\rp_harness\run_suite.py --suite smoke --out build\lv-rp-smoke.json --device <emulator-serial>` (exit codes 0/1/2 per tools/rp_harness/run_suite.py:9-14) | None | exit 0; every suite step `passed` (health, stack-up-bot, world-account rptest, reset-state cleared, world-chat sentinel whisper, bot-reply step **pass-or-skipped** — text unobservable without the protocol transport, memory evidence accepted; neither = fail) | High |
| LV-bots-llm-23 | Android chatter with real generations + A8 invariants — **LATER** | vanilla | LV-bots-llm-22 plus the Android model lane staged (R6 GGUF pushed to `filesDir/models/` per llm-settings-guide §model; requirements.md §R6 also documents the third registry entry as the self-downloading, no-user-action alternative) and: `python tools\rp_harness\run_suite.py --suite smoke --out build\lv-rp-smoke.json --world-log build\world.log --pull-world-log --require-a8` | **R6** (both alternatives are the ones requirements.md §R6 records: the two pinned GGUFs, or the self-downloading HF entry) | exit 0 WITH `--require-a8`: world.log contains per-req exactly one `BotLLM: dispatch`, ≥1 `begin` (zero only for busy-class), exactly one `end`; `bot-reply` step = observed text pass; latency percentiles recorded | High |
| LV-bots-llm-24 | Cross-area interaction: chat era-policy (TF-6) — stage-resolved LLM filters, vanilla byte-equivalence | vanilla | `cd /d E:\pocket_realm_complete && python tools\check_timeframes.py` then `python -m pytest tests\test_llm_truth.py -q -k era` and `python -m pytest tests\test_check_timeframes.py -q` | None | check_timeframes green over all 22 stages (three-way manifest↔Kotlin↔native sync that feeds PlayerbotLlmFilters.cpp); `test_llm_truth -k era` → **5 passed, 10 deselected** (the demonstrated behavior, matrix-timeframe-era.md:51 — the other 10 tests of the file take a `truth_binary` fixture and are NOT part of this NOW row; running them needs the in-repo truth-binary build first, as matrix-timeframe-era.md:85 records); test_check_timeframes → 13 passed; vanilla-1.12.1 stage resolves to the pre-control chatter lists. Annotate: the only full-loop stage→chat-era proof (generation + instruction line + token bias in flight) is **LV-timeframe-era-19** (matrix-timeframe-era.md:99), R6/LATER-gated — this row plus LV-bots-llm-32 cover everything observable without it | Medium |
| LV-bots-llm-25 | Guest bans store battery incl. every negative (MP:GUEST-BANS, MP:HARDENING-FIXES pragma/transaction legs) — **RAN this ask** | vanilla | `cd /d E:\pocket_realm_complete\desktop && gradlew.bat test --tests "com.pocketrealm.desktop.DesktopGuestBansTest" --console=plain` (result XML: desktop/build/test-results/test/TEST-…DesktopGuestBansTest.xml) | None | **Verified 2026-10-02: tests=8 failures=0 errors=0 skipped=0** — ban→logon-refusal predicate, unban re-allows, unknown-account ban refuses loud ("no account named …"), second ban idempotent (1 active row), token-rule names (`has space`, `mindé`, ``) refuse, lifted rows drop from list, ban succeeds while another writer holds the DB (BEGIN IMMEDIATE + busy_timeout), missing-DB refuses honestly | High |
| LV-bots-llm-26 | Guest bans on the MariaDB provider lane — provider-blindness regression probe (open cross-ledger finding 5.34, campaign-inventory.md:286) — **LATER** | vanilla (mariadb provider) | 1. Stage R5 per requirements.md §R5 (verify_mariadb_provider_staging + `build_win_realm_runtime.py --database-backend mariadb --write-lockfile`)<br>2. Boot the stack with provider=mariadb (Settings DatabaseProviderCard leg / supervisor gate with the mariadb lockfile)<br>3. Repeat LV-bots-llm-25's ban legs against the mariadb-hosted datadir | **R5** | today's expected result records the finding: DesktopGuestBans still writes the SQLite realmd lane (provider-blind) — the test FAILS to find ban rows in the mariadb `account_banned`, documenting 5.34; when the fix lands, the same commands must show the row in the active provider's realmd DB and the logon refusal holding | Medium |
| LV-bots-llm-27 | Guest-ban live enforcement chain, protocol-level: create guest → logon OK → wrong-password refused → permanent ban row → logon refused → unban → logon OK (MP:GUEST-BANS engine predicate; rpHost.kt:113-124,178-194) | vanilla | Driver as LV-bots-llm-01, `gradlew.bat rpHost` (no bots), ops:<br>`{"op":"account","user":"LVGUEST1","pass":"Guest-Pass-1"}` → `{"op":"proto-login","user":"LVGUEST1","pass":"WRONG-pass"}` (negative) → `{"op":"proto-login","user":"LVGUEST1","pass":"Guest-Pass-1"}` → `python -c` ban insert (`INSERT INTO account_banned (account_id,banned_at,expires_at,banned_by,reason,active) SELECT id, strftime('%s','now'), strftime('%s','now'), 'LV', 'matrix', 1 FROM account WHERE username='LVGUEST1';` via stdlib sqlite3 against `%LOCALAPPDATA%\PocketRealm\database\sqlite-datadir\classicrealmd.sqlite3` — byte-identical to the DesktopGuestBans permanent form, DesktopGuestBans.kt:88-104). **Time-form contract (resolved by running `PRAGMA table_info(account_banned)` against the live datadir this ask): `banned_at`/`expires_at` are INTEGER epoch columns** — only the `strftime('%s',…)` epoch form matches the engine predicate `expires_at > _UNIXTIME_ OR expires_at = banned_at` (AuthSocket.cpp:466-467); the same epoch semantics are the reference in LV-multiplayer-14's `ban_toggle.py` (matrix-multiplayer.md:136, epoch `now`→`now+3600`). LV-bot-actors-29's `datetime('now')` form was standardized to this epoch form per audit round 1) → `{"op":"proto-login","user":"LVGUEST1","pass":"Guest-Pass-1"}` → `python -c` `DELETE FROM account_banned WHERE account_id=(SELECT id FROM account WHERE username='LVGUEST1');` → `{"op":"proto-login",…}` → `{"op":"stop"}` | None | first wrong-password proto-login → `ok:false`; correct-password proto-login → `ok:true`; after the permanent ban insert → proto-login `ok:false` (AUTH_LOGON_FAILED_BANNED at the challenge, AuthSocket.cpp:466-477 per DesktopGuestBans.kt:16-18); after the delete → `ok:true` again | High |
| LV-bots-llm-28 | Friend-access native ops live world: verifier provisioning, duplicate refusal, roster, gmlevel, ban/unban verbs, suspension lapse, characters, backend verb, case normalization (MP:RA-VERIFIER-PATH + MP:GUEST-BANS native ops) | vanilla | `cd /d E:\pocket_realm_complete\desktop && gradlew.bat test --tests "com.pocketrealm.desktop.FriendAccountNativeOpsTest" --console=plain` (boots the machine's real stack; per-class fresh JVM, desktop/build.gradle.kts:222-231; test accounts wiped in `finally`) | None | green: `createAccountVerifierNative` rc=0 + accountId>0; duplicate = ACCOUNT_EXISTS (10); roster row gmlevel 0 / not banned / offline / zero counters; `setAccountGmLevelNative` reflected; `banAccountNative … "1h"` lands the `account_banned` row (read back live) and roster flips banned; `unbanAccountNative` clears; a 2-second suspension renders banned then NOT-banned after lapse while the row stays active=1 (expiry predicate, not removal); `accountCharactersNative` ok; backend `provisionAccountVerifier` ok; mixed-case provision lands uppercase and its uppercase twin refuses ACCOUNT_EXISTS | High |
| LV-bots-llm-29 | Provisioning listener end-to-end on loopback: framing, byte-identical refusal oracle, SUBMITTED→poll→PROVISIONED, rate-limit lockout, lifecycle state (MP:RA-VERIFIER-PATH/RA-RATE-LIMITS; FriendProvisionProtocol 4-byte-length-prefixed JSON, allowlisted keys, 64-hex token/sHex/vHex rules) | vanilla | `cd /d E:\pocket_realm_complete\desktop && gradlew.bat test --tests "com.pocketrealm.desktop.ProvisioningListenerTest" --console=plain` (real `ProvisioningListener` over real sockets; requests built by the real shared `FriendAccessClient`) | None | green: unknown token / expired window / burned token / rate-locked source all answer the SAME byte-identical refusal blob (FriendProvisionProtocol.kt:37); a valid `{schema:1,inviteId,token,username,sHex,vHex}` request arcs SUBMITTED → poll → PROVISIONED with accountId; lockout engages after the limiter's burst window; `currentState()` Listening on 127.0.0.1 | High |
| LV-bots-llm-30 | Invite tokens + rate limiter + the no-secret audit trail (shared domain): single-use ≥128-bit tokens, 15-minute window, OPEN→SUBMITTED→APPROVED→PROVISIONED, invite quarantine (MP:RA-INVITE-TOKENS; ProvisionRateLimiterTest 5/5 per inventory :250) | vanilla | `cd /d E:\pocket_realm_complete\android && gradlew.bat test -PpocketAbi=x86_64 --tests "com.pocketrealm.supervisor.FriendInviteStoreTest" --tests "com.pocketrealm.supervisor.ProvisionRateLimiterTest" --console=plain` | None | green: window = SUBMISSION_WINDOW_MS = 15×60×1000 with expiry quarantining; consumed token cannot replay; rate limiter's three layers engage in order and recover; invite JSON carries no credentials; **the audit trail legs green** — `auditRowsCarryActionsNotSecrets` (FriendInviteStoreTest.kt:213) and the create-refused audit record (FriendInviteStoreTest.kt:65-66), i.e. `friend-access-audit.jsonl` rows carry action/username/accountId, never secrets (the same sink the moderation verbs write, DesktopAppModel.kt:1062-1065) | Medium |
| LV-bots-llm-31 | DESK-77 app verbs — `llm-memory-state` / `reset-state` (+ `online`/`detail`/`save`) desktop console form + memory JSON contract (the desktop `world-chat` verb is driven by rows 14/20; **`characterPersistence` has no desktop console op** — its EMU relay legs are LV-bots-llm-36 and LV-bot-actors-39) | vanilla | Driver as LV-bots-llm-01, `gradlew.bat rpHost` (no `-PrpBots` — the population comes from the owning profile, Shared conventions); ops: `{"op":"online"}`, `{"op":"memory"}` (world summary), `{"op":"memory","player":"<bot>"}`, `{"op":"reset"}`, `{"op":"memory"}`, `{"op":"detail"}`, `{"op":"save"}`, `{"op":"stop"}` | None | `memory` world summary returns `botsOnline/onlineBots/totalFacts/totalRelationships`; `reset` reports `factsCleared/relationshipsCleared` counts and the following `memory` shows zeros; `save` ok:true; `detail` returns native detail without error | Medium |
| LV-bots-llm-32 | Cross-area interaction, LIVE and artifact-free: timeframe stage change ⇒ observable era behavior in the running bot surface (TF-6 × bots) — the now-stage half of what LV-timeframe-era-19 completes after R6 | vanilla | 0. settings.json backup per Shared conventions. 1. `python -c "import json,os;p=os.path.expandvars(r'%LOCALAPPDATA%\PocketRealm\settings\settings.json');d=json.load(open(p));d['timeframeStage']='vanilla-1.10';json.dump(d,open(p,'w'))"` (registered non-final stage — the same choice as LV-timeframe-era-10, matrix-timeframe-era.md:90)<br>2. `cd /d E:\pocket_realm_complete\desktop && gradlew.bat supervisorStartGate --console=plain` (boots db+realm+world through the production supervisor and stages the confs — the LV-server-db-30 mechanism)<br>3. Assert the era input the native LLM consumer reads: `findstr /c:"PocketRealm.TimeframeStage" "%LOCALAPPDATA%\PocketRealm\runtime\server\run\mangosd.conf"` — this is the exact key PlayerbotLlmFilters.cpp:41-53 reads via `sConfig.GetStringDefault("PocketRealm.TimeframeStage", "")` into the `TimeframeStageProviderSlot`<br>4. Assert the stage is a live selector branch: `python -c` grep of `native/cmangos/src/modules/PlayerBots/playerbot/PlayerbotLlmEraStages.generated.h` for `vanilla-1.10` (present at :35 stage list, :79 era mapping, :162 selector branch) + `python tools\generate_timeframe_era.py check` (era header + overlays match their pins; matrix-timeframe-era.md:46-47 shows the command's recorded output)<br>5. Boot a second time back at default (`timeframeStage:''`) and re-assert the conf now carries `vanilla-1.12.1` (the stage knob demonstrably drives the bot-era input in BOTH directions)<br>6. Cleanup leg: restore settings.json from the backup + byte-compare (Shared conventions) | None | supervisorStartGate PASSED both boots; conf carries `PocketRealm.TimeframeStage = "vanilla-1.10"` after step 1-2 and `= "vanilla-1.12.1"` after step 5; the generated header carries the `vanilla-1.10` selector branch; generate_timeframe_era check RC=0; restore byte-compare exits 0. **Explicit annotation:** the conversational half of this interaction (era instruction line + token bias actually applied to a live whisper) is only provable with a generating LLM lane — that is LV-timeframe-era-19 (matrix-timeframe-era.md:99), the single full-loop proof, R6/LATER-gated | High |
| LV-bots-llm-33 | Realmd protocol-auth gate: full 1.12 SRP6 logon against the live realmd (the logon predicate rows 27-29 depend on, driven directly) | vanilla | `cd /d E:\pocket_realm_complete\desktop && gradlew.bat authGate --console=plain` (boots realmd in-process, verifier row seeded directly into classicrealmd.sqlite, account `AUTHGATE` / `AuthGate-Password-1`; gate registered at desktop/build.gradle.kts:321-329, contract docs/WINDOWS_QUALIFICATION.md §1) | None (natives staged — see header) | gate exit 0: M1 accepted, M2 verified, session key persisted server-side; realmd logs "successfully authenticated" | High |
| LV-bots-llm-34 | Realmd liveness probe, raw TCP: the listener answers a connect with the SRP6 auth challenge byte stream | vanilla | 1. `cd /d E:\pocket_realm_complete\desktop && start /b gradlew.bat realmdHold --console=plain > build\lv-hold.log 2>&1` (60 s hold lane, desktop/build.gradle.kts:311-319)<br>2. `python -c` stdlib socket probe: connect `127.0.0.1:3724`, `recv(64)` with a 10 s timeout — any ≥1 byte back = listener alive with the auth challenge (environment.md:72-74 "realmd answers a connect with the SRP6 auth challenge — 0x1EC"; campaign-inventory.md:43 one shared 0x1EC opcode, C3)<br>3. after the hold exits: `python -c` re-probe → connection refused (clean stop, no lingering listener) | None | probe during the hold: ≥1 byte received on 3724 within 10 s; probe after the hold: ConnectionRefused (the gate's "clean stop" contract) | High |
| LV-bots-llm-35 | Realmd idle-close negative: a pre-auth peer that connects and sends nothing must be closed by the one-shot 30 s AuthSocket timer | vanilla | 1. realmdHold up as LV-bots-llm-34 step 1<br>2. `python -c` stdlib socket: connect `127.0.0.1:3724`, send NOTHING, block on recv with a 45 s timeout, record elapsed-until-close (or timeout)<br>3. repeat once to rule out a one-off | None | server closes the silent pre-auth socket within ~40 s (AuthSocket.cpp OnOpen one-shot 30 s timer, cancelled at first packet — the documented contract, tests/test_g1_realmd_liveness.py:20-24 "idle-close … must be closed by the one-shot 30 s AuthSocket timer"). **Honesty note:** that file marks this expectation "Device-gated expectations (documented only; no protocol client on host)" — this row is its first live host-side check; a socket surviving 40 s FAILS the row and reopens the fd/liveness investigation with the transcript as evidence | Medium |
| LV-bots-llm-36 | Android relay account lifecycle + persistence verbs (DESK-77 `characterPersistence` + account ops on the EMU lane; the world_console.py HELP surface) | vanilla | In the LV-bots-llm-04 relay session: `(echo world-account LVU1 Lv-Pass-1 & echo world-verify LVU1 Lv-Pass-1 & echo world-verify LVU1 wrong-pass & echo world-gm LVU1 3 & echo world-account-status LVU1 & echo world-persistence LVU1 LVChar & echo quit) \| python tools\world_console.py` (relay ops at WorldConsoleRelay.kt:154-158 → worldApi().createAccount/verifyAccountPassword/setAccountGmLevel/accountStatus/characterPersistence) | None (LV-bots-llm-04's staged emulator session) | `world-account` ok (created-or-exists); `world-verify` correct creds → ok:true, wrong creds → honest false; `world-gm` ok and `world-account-status` reflects gmlevel 3; `world-persistence` answers ok for the (account, char) pair; no fabricated success on the wrong-password leg | Medium |
| LV-bots-llm-37 | Corrupt staged model fail-closed, LIVE: a sha-mismatched GGUF at the models path is never accepted (DESK-9/28/48/80/103 live form of row 19's unit pins) | vanilla | 0. settings.json backup per Shared conventions. 1. `python -c` write 1 024 arbitrary bytes to `%LOCALAPPDATA%\PocketRealm\runtime\llm\models\gemma4-E2B-TUNED-q4_0.gguf` (name of the pinned tuned model, LlmModelRegistry.kt:80-83, sha `e267e979…` — the bytes deliberately do NOT match)<br>2. `python -c` settings: `"llmEnabled":true,"llmExternalMode":false,"llmModelId":"<tuned id>"`<br>3. rpHost boot (ops `{"op":"hello"}`, `{"op":"stop"}`)<br>4. while up: `curl -s -o NUL -w "%{http_code}" http://127.0.0.1:8080/health` → expect failure/no-200; `tasklist /FI "IMAGENAME eq llama-server.exe"` → empty<br>5. `python -c` scan `%LOCALAPPDATA%\PocketRealm\runtime\logs\pocket-realm.log` (DesktopLog.attachFile target, RpHost.kt:36-37) for the verification/discard line<br>6. Cleanup leg: delete the planted file, restore settings.json from the backup + byte-compare (Shared conventions) | None | world still READY (fail-closed must not take the world down); the corrupt model is NOT accepted: no llama-server child process, 8080 never answers 200; the log names the sha256 verification failure/discard (DesktopLlmModelStaging.kt:75-79 `if (!target.isFile \|\| descriptor.sha256.length != 64) return null` / `sha256File(target).equals(descriptor.sha256…)` — mismatch → discarded); restore byte-compare exits 0 | High |
| LV-bots-llm-38 | Chatter interaction: ambient world-chatter always yields the interactive lane (llm-settings-guide.md §replies "Ambient work always yields the interactive lane (it never queues behind your whisper)") — **LATER** | vanilla | Needs LV-bots-llm-20's lane (R6 + built llama-server). 0. settings backup. 1. settings: R6 model + `"llmEnabled":true,"llmExternalMode":false,"llmAmbience":true`<br>2. rpHost with the launch profile's own population (NO `-PrpBots` — the `bots` boot op is profile-owned, Shared conventions/matrix-multiplayer.md:272; the profile-owned pool keeps the ambient scheduler busy) + the LV-bots-llm-20 proto-chat whisper sequence fired DURING ambient chatter<br>3. capture world.log and run the A8 pass (`python tools\rp_harness\run_suite.py` post-pass shape, run_suite.py:25-47: `NO_BEGIN_CLASSES == {busy}`)<br>4. Cleanup leg: settings restore + byte-compare | **R6** | the interactive whisper's turn carries a full `dispatch → begin → end … class=ok` arc (never busy-class); concurrently-sampled ambient turns show busy/end classes WITHOUT begin (the busy posture) — i.e. ambient load manifested as busy denials, never as the whisper queuing behind it; whisper reply text arrives within the reply window | Medium |
| LV-bots-llm-39 | LLM settings are mid-session INERT: flipping `llmEnabled` while the world runs changes nothing until the next realm start (llm-settings-guide.md §1 "Nothing applies mid-session — flip, then (re)start the realm") | vanilla | 0. settings.json backup per Shared conventions. 1. `python -c` ensure `"llmEnabled":false` in settings.json<br>2. rpHost boot (ops `{"op":"hello"}`, `{"op":"stop"}`); `python -c` scan staged `%RUN%\mangosd.conf` → posture A recorded (no `AiPlayerbot.LLMEnabled=2`)<br>3. WITHOUT restarting anything: `python -c` flip `"llmEnabled":true, "llmExternalMode":true, "llmExternalUrl":"http://127.0.0.1:8090"` in settings.json<br>4. second `{"op":"hello"}`-only rpHost boot; re-scan the staged conf → UNCHANGED vs posture A (the flip did not apply mid-flight)<br>5. full stop, third boot; re-scan → the external `LLMApiJson` block IS now emitted (settings re-read at world start; LlmRuntimePolicy emission)<br>6. Cleanup leg: restore settings.json + byte-compare (Shared conventions) | None | posture A at boot 1; boot 2's staged conf byte-identical in its LLM keys to boot 1's (mid-session flip inert); boot 3's conf carries the LLM block (applies at start); restore byte-compare exits 0 | Medium |
| LV-bots-llm-40 | Python chatter-lane battery: trigger gates, security refusal (prompt-injection negative), persona lock, recall, banter pools, prompt format, player keywords surface — **EXECUTED this round** | vanilla | two commands, executed separately this round [X]: (1) `cd /d E:/pocket_realm_complete && python -m pytest tests/test_llm_security_refusal.py -q` → `7 passed in 0.02s`; (2) `python -m pytest tests/test_llm_banter.py tests/test_llm_gates.py tests/test_llm_chatter.py tests/test_llm_prompt_format.py tests/test_llm_recall.py tests/test_llm_persona_lock_scope.py tests/test_llm_player_surface.py -q` → `112 passed, 2 skipped in 9.19s`. The 8-file union as ONE invocation has NOT been executed — transcribe the two commands as run | None (the battery pins the native C++ chatter sources in-repo; pre-compiled objs under tools/) | 0 failures; skips are recorded as skips (never silent passes); the security-refusal file green means injection-shaped whisper payloads are refused by the pinned gates, and persona-lock/recall pins hold | High |
| LV-bots-llm-41 | Battery-forced CONSTRAINED chatter pacing, LIVE on the emulator (DESK-81 Android arm — the arm the desktop cannot force; LV-bots-llm-18 holds the desktop/NORMAL half) | vanilla | 0. relay session up per LV-bots-llm-04 steps 1-6 with ambience ON. GATE STEP: `adb shell run-as com.pocketrealm cat no_backup/server/run/chatter-power.txt` (file name/path pinned: ChatterPowerMonitor.kt:51-55) — must exist with `enabled` on; if not, the row records **BLOCKED-on-setting** (the toggle is UI-only today: Android settings are a DataStore binary, Settings.kt:146-147, and the relay has no settings op — never a fake pass)<br>1. `adb shell dumpsys battery unplug && adb shell dumpsys battery set level 10` (forced ≤15 % off-charger)<br>2. wait one refresh cadence (periodic refresh + battery receiver, ChatterPowerMonitor.kt:159-166; atomic tmp write :114-116); re-`cat` the file<br>3. `adb shell dumpsys battery reset && adb shell dumpsys battery plug`<br>4. re-`cat` → back to NORMAL | None | gate step passes; under forced battery the file's `rung` reads CONSTRAINED (3, DesktopChatterPowerMonitor.kt:14-28 twin states; computeRung ChatterPowerMonitor.kt:62); after `reset`+`plug` it reads NORMAL (4); both transitions land within one refresh cadence | Medium |
| LV-bots-llm-42 | Guest ban written while the realm is STOPPED bites at the next boot (DesktopGuestBans.kt:25-28 "it works … while it is stopped" — the WAL second-writer contract's other half; row 27 covers ban-while-RUNNING) | vanilla | 1. stack-down proof: no rpHost/gradle holds the datadir (`tasklist /FI "IMAGENAME eq java.exe"` empty after the previous row's `stop`; no `classicrealmd.sqlite3-wal` growth)<br>2. one short rpHost boot: `{"op":"account","user":"LVGUEST2","pass":"Guest-Pass-2"}`, `{"op":"stop"}` (account exists, world down again)<br>3. while STOPPED: `python -c` stdlib sqlite3 `INSERT INTO account_banned (account_id,banned_at,expires_at,banned_by,reason,active) SELECT id, strftime('%s','now'), strftime('%s','now'), 'LV', 'stopped-ban', 1 FROM account WHERE username='LVGUEST2';` against `classicrealmd.sqlite3` (epoch form per LV-bots-llm-27's resolved contract)<br>4. boot rpHost again; `{"op":"proto-login","user":"LVGUEST2","pass":"Guest-Pass-2"}`, `{"op":"stop"}`<br>5. cleanup: `python -c` delete the ban row + the LVGUEST2 account rows | None | step 3's insert succeeds on the stopped DB (no lock refusal); after the boot the proto-login answers `ok:false` (the stop-time row is enforced at the first logon challenge — AuthSocket.cpp:466-477); cleanup removes both rows | Medium |
| LV-bots-llm-43 | Vanilla conf golden byte-pins for the emitted bot surfaces (G3-7: 8 conf surfaces byte-pinned incl. `aiplayerbotDisabledConfText`; topology-key-absence assert — campaign-inventory.md:57) — the emission bytes rows 09/15/18 read at runtime, pinned as bytes | vanilla | `cd /d E:/pocket_realm_complete/android && cmd //c "gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.server.VanillaConfGoldenTest --console=plain"` (test class verified on disk: android/app/src/test/java/com/pocketrealm/server/VanillaConfGoldenTest.kt) | None (pins are committed golden fixtures; no client) | class green: the emitted realmd/mangosd/aiplayerbot-disabled conf texts reproduce the pinned bytes exactly and the bot conf carries no topology keys; XML report 0 failures. Designed; not executed this ask (android gradle lane untested here for time — flags per Shared conventions) | Medium |
| LV-bots-llm-44 | Friend-access client-side unit trio: the friend-side SRP6 (s,v) derivation and the access-hub refusal/verdict surface (MP:RA-VERIFIER-PATH §3 client half — the counterpart of rows 28/29's host side) | vanilla | `cd /d E:/pocket_realm_complete/android && cmd //c "gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.server.Srp6AccountMathTest --tests com.pocketrealm.supervisor.FriendAccessHubTest --console=plain"` (both classes verified on disk; hub legs read: provisionFriendAccount refusal `ACCOUNT_EXISTS` at FriendAccessHubTest.kt:131-133, moderation verdicts banned/unbanned/refused at :86-90) | None | both classes green: Srp6AccountMath derives (s,v) matching the SRP6 vectors the host side accepts (the verifier hex form FriendAccountNativeOpsTest provisions with), and the hub refuses a colliding identity with `ACCOUNT_EXISTS` (never verify-and-adopt) and returns honest moderation verdicts; XML report 0 failures. Designed; not executed this ask | Medium |

### Deliberately NOT designed as rows (honest gaps)

- **Guest-side friend-access over `adb reverse tcp:3725`** (environment.md:63 reserves the port for
  exactly this): the *listener* side only binds while a friend-access LAN_HOST session is up
  (campaign-inventory.md:248), and **no CLI surface starts that session** — `rpHost` is `RuntimeMode.LOCAL`
  only (RpHost.kt:51-56), `Main.kt` takes no args (Main.kt:39), and the relay has no host-access op
  (WorldConsoleRelay.kt:141-200). Driving it would require a manual UI step, which this campaign
  forbids. The listener is instead fully covered CLI-side by LV-bots-llm-29 (real listener, real
  sockets, real protocol). If a later stage adds a CLI host-session verb, a `curl --data-binary @frame`
  row (4-byte big-endian length prefix + JSON, FriendProvisionProtocol.kt:101-115) slots in here.
- **KICK of an online guest**: recorded deferred by the campaign itself — no JNI export in the pinned
  runtime (DesktopGuestBans.kt:20-24). Not testable by any CLI lane today.
- **Moderation-audit sink via the app's own ban path, live**: the Guests-card store is the only caller
  of `DesktopGuestBans` with the real audit sink wired (DesktopAppModel.kt:1057-1067, UI-lazy), and no
  relay/rpHost op exposes ban verbs — so the moderation-side audit rows
  (`auditModeration` → `friend-access-audit.jsonl`) have no CLI trigger today. Covered as far as CLI
  reaches: the store battery (row 25) exercises the write path with the sink defaulted to no-op, and
  the sink itself is pinned by row 30's `FriendInviteStoreTest` audit legs (invite-flow rows in the
  same jsonl). A live moderation-audit row slots in here if a future lane grows a ban console op.
- **Desktop CONSTRAINED (<15 % battery) chatter dimming**: needs a battery state the host CLI cannot
  force; pinned at unit level inside LV-bots-llm-18's pytest leg (the `computeRung` seam).

## Severity legend

High = core contract or fail-closed gate for the area; Medium = auxiliary lane, heavy soak, or
cross-area pin; all High rows must be green before the area is called live-verified. NOW rows are
executable on this host today (staged natives, seeds, prepared data, o11 generation verified in the
header); LATER rows name their requirement-manifest artifact and must not be attempted before it lands.

### 4.4 — section file `matrix-multiplayer.md` (embedded verbatim)

# Live-verification matrix — multiplayer (LAN play, tunneling, remote accounts, join matrix, realm-status surface)

Design round 1 (audit round 1 revisions applied 2026-10-02). Area: `multiplayer`. Every
mechanism below is CLI-driven (adb / gradlew / python / curl); no screenshot driving, no manual
GUI steps. Rows marked **ARTIFACT** require user-provided artifacts from
`out/live-verification/requirements.md` and run in the later stage; rows marked
**OUT-OF-SCOPE-FOR-ROUND-1 / blocked-tracked** can never run until a named repo/harness
prerequisite lands ("Tracked prerequisites" below) — the coverage ledger must distinguish those
from runnable rows; everything else is an artifact-free now-test.

## Grounding (what this design is built on — all read this session)

| Source | What was taken |
|---|---|
| `docs/MULTIPLAYER-TRANSPORT-CONVERGENCE.md` (owner-approved synthesis) | Seams to test: dual-port loopback gateway (3724+8085, §1.3), invite tokens (§1/§2), reconnect resilience (§1.4, §2 "Deep reconnect"), privacy payload rules (§1.5, §5 Tier 3: bots labeled, no IPs/secrets), Tailscale = dev baseline only (§4 conflicts), quick tunnels = HTTP showcase page only (§4). Anti-cheat/admission/settlement OUT of scope per the owner's ruling (:11). |
| `docs/RESEARCH-WOWMESH-P2P-2026-07.md` §2-§4 | Dual TCP semantics login/world (:25-28), loopback gateway listeners 127.0.0.1:3724/8085 (:127-147), buffering/deadlines (:228-241), invite payload rules — 15-min default, single-use, never passwords (:274-301), heartbeat/suspicion/failover timing (:302-317). §5+ (anti-cheat/settlement) not used. |
| `out/live-verification/environment.md` | Hybrid lane: headless AVD `PocketDiff_x86_64` + desktop JVM servers on host loopback + `adb reverse tcp:3724/8085` (+3725 guest-side); port map table; **correction recorded there: the realm-status surface has NO HTTP port** — it is JNI `statusNative` rendered by `ServerStatusJson`, DESK-127's Diagnostics surface. |
| `out/live-verification/campaign-inventory.md:240-253` | The 12 multiplayer deliverable IDs (MP:*) this section covers. Externally blocked context: "Two-machine connectivity: live LAN/tunnel join verification" (:280). |
| `out/live-verification/requirements.md` | G7 (LAN multiplayer) needs only R3 (vanilla 5875 client, **not staged on this machine**); G8 (tunnel) needs R7 + a second peer; R1/R2 for wrong-client negatives. |

## Implemented seams each test drives (E:/pocket_realm_complete, read this session)

- `android/app/src/main/java/com/pocketrealm/supervisor/RuntimeTopology.kt` — `RuntimeMode`
  LOCAL/LAN_JOIN/LAN_HOST (:8); `RealmEndpoint.parseLan` (:48, strict private/link-local) vs
  `parseJoinTarget` (:64, + CGNAT 100.64/10 — `isCgnat` :80); `AdvertisedAddress.parse` (:143,
  loopback/private/link-local/CGNAT numeric only, hostnames+public refused);
  `RuntimeLaunchSpec` schema 2 (:164-324: advertise only on LAN_HOST :182, LOCAL forbids
  allowLanPlayers :188, LAN_JOIN client-only :191, LAN_HOST requires allowLanPlayers :199);
  `LanInterfacePolicy` (:327-357); `TunnelInterfacePolicy` (name-prefix allowlist
  tailscale/zerotier, :368-403); `InterfaceWatch.forSession` (:417-440: None=loopback
  advertisement, Lan=plain, Tunnel=advertised).
- `android/app/src/main/java/com/pocketrealm/server/ServerRuntimeContract.kt` — REALM_PORT 3724
  (:28), WORLD_PORT 8085 (:29), PROVISION_PORT 3725 (:40), `resolveBindAddress` accepts exactly
  one extra literal `0.0.0.0` (:88-93), `wrongPassLockoutConfLines` emitted **only on
  non-loopback binds** (:109-116).
- `android/app/src/main/java/com/pocketrealm/server/FriendProvisionProtocol.kt` — length-prefixed
  UTF-8 JSON (4-byte BE prefix :102-114), 4 KiB request cap (:31), byte-identical
  `REFUSED_RESPONSE` for unknown/expired/burned/malformed/rate-locked (:40, refusal-oracle rule).
- `android/app/src/main/java/com/pocketrealm/supervisor/FriendInviteStore.kt` —
  OPEN→SUBMITTED→APPROVED→PROVISIONED state machine (:18-25); token 24 bytes (192-bit ≥128-bit,
  :602) stored hashed only (:27-29); 15-minute decision window (:599); single-use (poll reveal
  burns the token, :243-252); max 8 open invites (:598); s/v never persisted (:57-58).
- `android/app/src/main/java/com/pocketrealm/supervisor/ProvisionRateLimiter.kt` —
  MAX_FAILURES=5 (:88), LOCKOUT_MS=10 min (:89), MAX_CONCURRENT_CONNECTIONS=10 (:90).
- `android/app/src/main/java/com/pocketrealm/supervisor/ProvisioningListener.kt` — binds the
  host's LAN address (never 0.0.0.0, :25-28) on 3725 only while a friend-access LAN_HOST session
  is WORLD_READY+ (`FriendAccessController.onRuntimeChanged`, FriendAccessController.kt:89-115);
  5 s read deadline (:233).
- `android/app/src/main/java/com/pocketrealm/service/RealmService.kt` — CLI intents
  ACTION_HOST_LAN (:228, `EXTRA_ADVERTISED_ADDRESS` :239) / ACTION_JOIN_LAN (:248,
  `EXTRA_LAN_ADDRESS`) / ACTION_SAVE_EXIT (:274) / ACTION_STOP (:275), extras (:815-818); the
  1 s monitor loop (:602) with 10 s grace (`LAN_HOST_INTERFACE_LOSS_GRACE_MS = 10_000L`, :821)
  ending in `supervisor.stop(GRACEFUL, reason = LAN_HOST_INTERFACE_LOST | TUNNEL_HOST_INTERFACE_LOST)`
  (:665, :685). Services are `exported="false"` (AndroidManifest.xml:55-57) → intents require
  `adb root` on the emulator.
- `android/app/src/main/java/com/pocketrealm/supervisor/RuntimeFailureCopy.kt` — humanized copy
  for LAN_HOST_INTERFACE_LOST (:33), TUNNEL_HOST_INTERFACE_LOST (:37), LAN-PROBE-UNREACHABLE
  (:52), AUTH_LOGON_FAILED_BANNED.
- `android/app/src/main/java/com/pocketrealm/ui/LanJoinProbe.kt` — the pre-join probe is a one-shot
  TCP connect to 3724, 2 s timeout (:27-33, :51-70).
- `desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopRuntimeBackend.kt` —
  `lanHostLivenessRefusal` (:516, mode-keyed preflight), `bindAddressFor` → wildcard bind iff
  `tunnelAdvertising` (:652-658), `checkLanHostInterfaceAtStart` (:666-684),
  `observeWorldPresence` separates realPlayers (botStatusNative) from onlinePlayers (:873-882),
  status JSON via `ServerStatusJson` (:1717, :1722).
- `desktop/src/main/kotlin/com/pocketrealm/server/ServerStatusJson.kt` — realm/world/operation
  payload fields (no secrets; `listenAddress` defaults 127.0.0.1 :28/:36).
- `desktop/src/main/kotlin/com/pocketrealm/desktop/RpHost.kt` — the desktop world console:
  stdio JSON-line host, `@@RP@@`-prefixed protocol lines (:294-301), ops `hello | account | chat |
  memory | reset | online | bots | botstatus | save | detail | autologin | stop |
  proto-login | proto-world | proto-create | proto-chars | proto-pick | proto-chat | proto-close`
  (:25-31 doc, :111-262 switch). `proto-login` = full SRP6 logon against 3724 (:178-194),
  `proto-world` = world auth on 8085 (:195-211). Boots **LOCAL mode only** (:51-56).
- `desktop/src/main/kotlin/com/pocketrealm/desktop/AuthGate.kt` — seeds a verifier row directly
  into `classicrealmd.sqlite` (:80-93, the direct-write precedent) and proves the 1.12 SRP6
  handshake end-to-end on 3724.
- `desktop/src/test/kotlin/com/pocketrealm/desktop/FriendAccountNativeOpsTest.kt` — live-world
  leg for the five friend-account relay ops (`createAccountVerifierNative`,
  `accountListNative`, `banAccountNative`, `unbanAccountNative`, `accountCharactersNative` —
  WorldNative.kt:24-28), ban rows in `account_banned` + the engine's logon expiry predicate
  (AuthSocket.cpp:466-467, cited at :187-196); skips loudly (assumeTrue) if staged inputs are
  missing. Staged inputs verified present this session (below).
- `desktop/src/test/kotlin/com/pocketrealm/desktop/ProvisioningListenerTest.kt` — drives the real
  listener over real loopback sockets (test :52 binds and connects 127.0.0.1).
- Conf/journal paths: desktop conf `%LOCALAPPDATA%/PocketRealm/runtime/server/run/realmd.conf`
  (desktop ServerRuntimeFiles.kt:55-58: root = `roots.runtime/server`, `run` under it), supervisor
  journal `%LOCALAPPDATA%/PocketRealm/realm/runtime-supervisor/journal.json`
  (DesktopStorageRoots.kt:63, DesktopSupervisorJournal.kt:30). Android conf under the app's
  `run/` dir (android ServerRuntimeFiles.kt:31, realmd.conf :57).

## Checks run during this design ask (all commands executed here)

- `cmd /c gradlew.bat :tasks --console=plain` in `E:/pocket_realm_complete/desktop` → rc=0;
  bring-up gates listed at /tmp/tasks.txt:14-25: `authGate`, `bootRealmd`, `bootWorld`,
  `realmdHold`, `rpHost`, `supervisorStartGate`. (Task names/descriptions also read in
  desktop/build.gradle.kts:246-378.)
- `ls native/.build-win-x86_64/pocket-runtime-build/` → `pocket_realmd_runtime.dll` and
  `pocket_world_runtime.dll` staged.
- `ls %LOCALAPPDATA%/PocketRealm/database/sqlite-datadir` → the five sqlite DBs
  (classicrealmd.sqlite3, classicmangos.sqlite3, classiccharacters.sqlite3, classiclogs.sqlite3,
  pocketrealm_meta.sqlite3) — datadir seeded.
- `cat %LOCALAPPDATA%/PocketRealm/content/o11-server/active-vanilla.json` → active prepared-data
  pointer (schema 1, generation 7ceb6c39…). → **FriendAccountNativeOpsTest / rpHost / bootWorld
  preconditions are satisfied on this host today.**
- `adb version` → 1.0.41 (36.0.1-13811061); `adb devices` → RP6 `serial-redacted` attached, **no
  emulator running** (the AVD half of rows 02/07-10 runs in the execution stage).
- Live conf probe on the current on-disk desktop lane:
  `grep -n "BindIP" %LOCALAPPDATA%/PocketRealm/runtime/server/run/realmd.conf` →
  `3:BindIP = "127.0.0.1"`; `grep -c WrongPass <same file>` → `0` — the D11 loopback-absence
  leg of row LV-multiplayer-11 verified against the real staged conf bytes today.
- NOT run (reported as such): no emulator boot, no APK install, no gradle test execution, no
  server boot — this ask designs and grounds the matrix; it does not execute it. `adb root`
  behavior on the default-image emulator is expected (userdebug image) but **unverified until an
  emulator boots** — row 02 includes the verification step.

## Test matrix

Conventions: `PW` = `cd /d E:\pocket_realm_complete\desktop`; `PA` = `cd /d
E:\pocket_realm_complete\android`. Emulator = headless `PocketDiff_x86_64` per environment.md.
`$LOCALAPPDATA` = the host user's. Every gradle run uses `--console=plain` and a bounded
`--tests` filter. Severity: P0 = converged-core seam, P1 = shipped-deliverable behavior,
P2 = hardening/copy.

Audit-round-2 conventions (executor harness MUST honor):
- **SDK tool paths are not on this host's PATH** (`which emulator` → not found, verified). The
  harness prepends `%LOCALAPPDATA%` + `\Android\Sdk\emulator` and `...\Android\Sdk\platform-tools`
  to PATH before any adb/emulator line, or cites the full binary — the repo's own lane resolves
  `SDK / "emulator" / "emulator.exe"` the same way (tools/run_differential_parity.py:42-43;
  verified this round: that full path with `-list-avds` → PocketDiff_x86_64, PocketRealm_API28).
- **All `--tests` filters are FULLY-QUALIFIED class names.** The quoted-wildcard form
  (`--tests "*Name"`) fails under this shell's cmd quoting with "No tests found for given
  includes" (observed in round 2's first execution attempt); every executed result in the annex
  used FQCNs.
- **The android unit lane requires `-PpocketAbi=x86_64`** (android/app/build.gradle.kts:950 fails
  loud without it; verified in round 2).

| testId | feature | flavor(s) | exact CLI mechanism | required artifacts | pass condition | severity |
|---|---|---|---|---|---|---|
| LV-multiplayer-01 | Join matrix — typed endpoint grammar (admitted/refused classes; MP:MOBILE-PARITY android half — LanTunnelWiringContractTest) | vanilla (flavor-independent JVM) | `PW` then `gradlew.bat test --tests com.pocketrealm.supervisor.RuntimeTopologyTest --tests com.pocketrealm.ui.TunnelInviteTest --console=plain` (both classes compile into the desktop test set — verified in build/classes/kotlin/test); `PA` then `gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.ui.LanTunnelWiringContractTest --console=plain` | none | Both suites green (0 failures, tests not skipped): pins that loopback+private parse as bind/advertise, CGNAT 100.64/10 accepted by `parseJoinTarget`/`AdvertisedAddress` but refused by `parseLan`/bind, public IPv4 + hostnames + non-canonical octets refused everywhere, advertise only on LAN_HOST, LOCAL+allowLanPlayers refused, LAN_JOIN client-only (RuntimeTopology.kt:48-71,143-158,182-200; 7 wiring tests) | P0 |
| LV-multiplayer-02 | Join matrix — live service-intent refusals on the guest (MP:MOBILE-PARITY — the Android service surface) | vanilla | boot emulator per environment.md; install debug APK; `adb root && adb shell id -u` (expect `0` — REQUIRED since services are exported=false, AndroidManifest.xml:55); then `adb shell am start-foreground-service -n com.pocketrealm/.service.RealmService -a com.pocketrealm.action.JOIN_LAN --es com.pocketrealm.extra.LAN_ADDRESS "host.example.com"` → `adb logcat -d \| grep -i "invalid LAN endpoint"`; repeat with `203.0.113.10` (public, refused), `127.0.0.1` (refused: join target grammar), `100.64.1.5` (CGNAT — accepted past the grammar, launches client-only per RealmService.kt:248-258) | none | Hostname/public/loopback JOIN_LAN each refused with the parser's message and no client launch; the CGNAT target passes the grammar (no "invalid LAN endpoint"); supervisor journal/logcat shows no realm bind in any refusal case | P0 |
| LV-multiplayer-03 | LAN join probe — live reachability leg (MP:LAN-JOIN-PROBE) | vanilla | Terminal A: `PW && gradlew.bat realmdHold` (holds realmd on 127.0.0.1:3724 for 60 s, RealmdHold.kt:27). Terminal B, while it holds: `python -c "import socket,time; t=time.time(); s=socket.create_connection(('127.0.0.1',3724),2); d=s.recv(64); print('REACHABLE',len(d),'%.2fs'%(time.time()-t))"`; after the hold expires (or `taskkill` the java process): same probe → expect `ConnectionRefusedError`/timeout | none | Probe against the held realmd returns ≥1 byte (realmd answers a connect with the SRP6 auth challenge, 0x1EC family) within the 2 s budget = `LanJoinProbe.Reachable`; with no listener the connect fails within 2 s = `Unreachable` (LanJoinProbe.kt:51-70) | P1 |
| LV-multiplayer-04 | Dual-port handoff — login port 3724 protocol proof | vanilla | `PW && gradlew.bat authGate` (seeds a fresh SRP6 verifier row into classicrealmd.sqlite, boots realmd in-process, runs the full 1.12 logon handshake, AuthGate.kt:56-120) | none | Exit code 0; stdout shows the seeded row line and a successful session-key exchange; realmd listener up on 3724 during the run and cleanly stopped after (gate's own EXIT_LISTEN/EXIT_HANDSHAKE codes absent) | P0 |
| LV-multiplayer-05 | Dual-port handoff — world port 8085 + full stack | vanilla | `PW && gradlew.bat bootWorld` (db+realm+world in-process; verifies READY, the 8085 listener, save/stop — desktop/build.gradle.kts:330-334) | none | Exit code 0: world reaches READY, 8085 accepts, clean save/stop (no EXIT_WORLD=3) | P0 |
| LV-multiplayer-06 | Dual-port handoff — 3724→8085 through ONE path, protocol-level (the convergence §1.3 seam) | vanilla | `PW && gradlew.bat rpHost` (background); `python out\live-verification\scripts\rpdrive.py --steps hello,account:DUALPORT1:DualPortPass1,proto-login,proto-world,proto-create:Dualport,proto-chars,proto-pick,proto-chat:"handoff ok",stop` (driver script specified below the table; speaks one JSON line per op into rpHost's stdin, reads only `@@RP@@` lines, RpHost.kt:294-301) | none | `proto-login` ok (3724 SRP6 logon, session key stored), `proto-world` ok with success authResult (8085 world auth with the SAME session key — RpHost.kt:178-211), char create/enum/pick ok, chat capture returns the line — i.e. login and world both reachable through the single LOCAL path with no port juggling | P0 |
| LV-multiplayer-07 | Dual-port handoff — both ports through the adb-reverse guest bridge (environment.md hybrid) | vanilla | Host: `PW && gradlew.bat rpHost` (background; binds 3724+8085 on host loopback). `adb reverse tcp:3724 tcp:3724 && adb reverse tcp:8085 tcp:8085 && adb reverse --list` (both lines present). Guest: `adb shell "printf 'x' \| nc -w 2 127.0.0.1 3724 \| head -c 16 \| od -An -tx1"` and the same for 8085. If the image's toybox lacks `nc`, record the substitution and defer the guest-side leg to the artifact stage — the host-side probes of rows 03-05 already prove the listeners | none | Guest-side connect on BOTH forwarded ports returns bytes (3724: SRP6 challenge; 8085: world listener accept behavior — bytes or clean accept) — proving one bridge path carries both conversations; `adb reverse --list` shows exactly the two game ports | P1 |
| LV-multiplayer-08 | LAN_HOST bring-up on the guest + exposure invariant (3724/8085 up; 3725 closed while friend access off) | vanilla | PRECONDITION (tracked, see "Tracked prerequisites" P-3): the stored `allowLanPlayers` DataStore toggle must be ON — AndroidRuntimeBackend.kt:180-186 refuses with "Allow LAN players is disabled in Settings" otherwise, and the toggle lives in the protobuf DataStore (Settings.kt:65), UI-only today. CLI route to verify on first boot: `adb root` (or `run-as com.pocketrealm` — the debug build is debuggable) + patch/inspect `files/datastore/*.preferences_pb`; if the DataStore patch proves unreliable the guest legs of this row are BLOCKED, TRACKED with LV-20. Then: `adb shell am start-foreground-service -n com.pocketrealm/.service.RealmService -a com.pocketrealm.action.HOST_LAN` (RealmService picks `LanInterfacePolicy.selectForHosting()` = emulator eth0 10.0.2.15, :228-246); wait for READY via `adb logcat`; read the live conf: `adb shell find /data/data/com.pocketrealm -name realmd.conf -exec cat {} \;` (bind + lockout bytes); from host: `adb forward tcp:13724 tcp:3724 && adb forward tcp:18085 tcp:8085` then python connects to 127.0.0.1:13724 and 127.0.0.1:18085; friend-access-off negative: `adb forward tcp:13725 tcp:3725` + python connect → refused; in-guest listener inventory via `adb shell cat /proc/net/tcp` (parse for ports 0E8C/1F95/0E8D hex = 3724/8085/3725 — toybox has no `ss`) | none | Host-lan start reaches READY; staged realmd.conf carries `BindIP = "10.0.2.15"`; both game ports answer through the forward; 3725 refuses (listener bound ONLY while friend access is enabled — FriendAccessController.kt:89-115) — the wiki's "only 3724 and 8085" exposure invariant | P0 |
| LV-multiplayer-09 | Host network interruption ⇒ graceful save+stop, then manual re-host (the implemented reconnect semantics; MP:LAN-LIVENESS-LOSS-COPY) | vanilla | Continue LV-multiplayer-08's session. STEP 0 (audit fix — pre-verify the trigger on the FIRST emulator boot; the AVD uplink is eth0 over host NAT and the wifi/mobile-data services may not carry it): issue `adb shell svc wifi disable && adb shell svc data disable`, then check `adb shell cat /proc/net/tcp` + `adb logcat -d \| grep -i "interface.*absent"` — if the monitor did NOT flag the interface loss within one poll (10 s), the trigger is substituted with `adb shell cmd connectivity airplane-mode enable` (removes the AVD's connectivity stack-wide) and RECORDED in the run log; re-enable with the matching `… disable`/`enable` inverse. Watch `adb logcat -v time` either way: the 1 s monitor logs the 10 s grace window then the stop (RealmService.kt:639-667, GRACE_MS :821). Verify stop reason + save: `adb logcat -d \| grep -E "LAN_HOST_INTERFACE_LOST\|generation will save and stop"`; `adb shell find /data/data/com.pocketrealm -name "journal.json" -exec cat {} \;` shows the GRACEFUL terminal snapshot. Reconnect leg: undo the trigger (airplane-mode disable / svc enable — whichever was used), re-issue the HOST_LAN intent, re-probe via the LV-08 forwards | none | Within ~10 s grace + one poll the session saves and stops with reason `LAN_HOST_INTERFACE_LOST` (never a hang, never a silent dead listener) — with WHICHEVER trigger actually removed connectivity, recorded; RuntimeFailureCopy renders the humanized "Your network connection changed…" entry (:33-36); after undoing the trigger the same HOST_LAN intent re-hosts successfully and 3724 answers again — manual reconnect works; automatic client-session continuity across the interruption does NOT exist (→ Architecture gaps G-2) | P0 |
| LV-multiplayer-10 | Tunnel-advertised start fail-closed without a tunnel adapter (MP:TUNNEL-LANE negative) | vanilla | Same LV-08 precondition (allowLanPlayers ON — the toggle check at AndroidRuntimeBackend.kt:181 fires BEFORE the tunnel check and would mask the expected refusal copy otherwise); then `adb shell am start-foreground-service -n com.pocketrealm/.service.RealmService -a com.pocketrealm.action.HOST_LAN --es com.pocketrealm.extra.ADVERTISED_ADDRESS "100.100.1.5"`; `adb logcat -d \| grep -i tunnel`; in-guest listener inventory via `adb shell cat /proc/net/tcp` — parse for 0E8C/1F95 (3724/8085) absence (toybox has no `ss`, same mechanism as row 08) | none | Start refused honestly — "the tunnel adapter carrying 100.100.1.5 is not up; join the tunnel network before hosting over the internet" (DesktopRuntimeBackend.kt:536-545 Android twin) — and nothing binds: /proc/net/tcp shows NO listener on 0E8C/1F95 after the refused start; InterfaceWatch=Tunnel without an adapter must fail closed, never half-host | P1 |
| LV-multiplayer-11 | Conditional brute-force lockout — conf emission keyed on bind class (MP:CONDITIONAL-LOCKOUT) | vanilla | Loopback leg: `PW && gradlew.bat bootRealmd` then `grep -c WrongPass %LOCALAPPDATA%/PocketRealm/runtime/server/run/realmd.conf` (expect 0 matches — already verified against the current staged conf in this design ask). Non-loopback leg: during LV-multiplayer-08's session, `adb shell find /data/data/com.pocketrealm -name realmd.conf -exec grep -A2 WrongPass {} \;` | none | LOCAL conf carries NO `WrongPass.*` keys (loopback self-lockout rule); the 10.0.2.15-bound conf carries exactly `WrongPass.MaxCount = 10` / `WrongPass.BanTime = 900` / `WrongPass.BanType = 0` (ServerRuntimeContract.kt:109-116; golden byte pins in VanillaConfGoldenTest) | P1 |
| LV-multiplayer-12 | Provisioning transport + invite state machine + rate limits over real sockets (MP:RA-INVITE-TOKENS, MP:RA-RATE-LIMITS refusal paths) | vanilla (JVM) | `PW && gradlew.bat test --tests com.pocketrealm.desktop.ProvisioningListenerTest --tests com.pocketrealm.supervisor.FriendInviteStoreTest --tests com.pocketrealm.supervisor.ProvisionRateLimiterTest --console=plain` (all three compile into the desktop test set — verified classes present; the listener under test binds real 127.0.0.1 sockets, ProvisioningListenerTest.kt:52) | none | All green: one refusal blob byte-identical for unknown/expired/burned/malformed/rate-locked (`REFUSED_RESPONSE`, FriendProvisionProtocol.kt:40); 4 KiB frame cap; 5 failures → 10-min per-IP lock (even a VALID token then refused); 10-connection cap; 8-open-invite cap; single-use token burn at the PROVISIONED reveal; 15-min decision window boundaries; s/v never written to `friend-access-invites.json` (token hash only) | P0 |
| LV-multiplayer-13 | Remote account creation — verifier path against a LIVE world (MP:RA-VERIFIER-PATH) | vanilla | `PW && gradlew.bat test --tests com.pocketrealm.desktop.FriendAccountNativeOpsTest --console=plain` (boots the real staged stack: DLLs + prepared o11 data + seeded datadir all verified present this session; wipes its test accounts after the drain) | none | Green, with the transcript proving: `createAccountVerifierNative` provisions {username,s,v} with NO password anywhere; duplicate-username refused (ACCOUNT_EXISTS class); roster read carries ban state/failed-logins/last-logon/online; `banAccountNative` lands the `account_banned` row the roster reads and `unbanAccountNative` clears it; a TIMED suspension that lapses renders not-banned (engine predicate AuthSocket.cpp:466-467); gmlevel + per-friend characters ops answer | P0 |
| LV-multiplayer-14 | Guest-ban logon refusal, live (MP:GUEST-BANS — the ban bites at next login) | vanilla | 1) `PW && gradlew.bat rpHost` (background); `rpdrive.py --steps account:BANGUEST1:BanGuestPass1,proto-login,proto-chat:"baseline",stop` → proto-login ok (baseline). 2) `python out\live-verification\scripts\ban_toggle.py ban BANGUEST1` (script specified below the table: inserts the engine's own account_banned row, epoch `now`→`now+3600`, active=1, while the stack is down — the AuthGate.kt:80-93 direct-write precedent; the app's own ban verb is exercised live in LV-13). 3) restart rpHost; `rpdrive.py --steps proto-login:BANGUEST1:BanGuestPass1,stop` → refused. 4) `ban_toggle.py unban BANGUEST1`; restart; proto-login → ok. Negative: `ban_toggle.py expire BANGUEST1` (row already lapsed) must NOT refuse | none | Banned+active row ⇒ `proto-login` answers ok:false (realmd logon refusal; the AUTH_LOGON_FAILED_BANNED failure class exists in RuntimeFailureCopy); lapsed/active=0 ⇒ ok:true again. Unattended-honesty note: an ONLINE guest is disconnected only at next login / session end (wiki "Revocation today" — recorded behavior, not a bug) | P1 |
| LV-multiplayer-15 | Realm-status payload — honest telemetry + privacy negatives on the surface that EXISTS (DESK-127; convergence Tier-3 privacy rules applied to it) | vanilla | `PW && gradlew.bat rpHost -PrpBots=5` (background); ops: `{"op":"hello"}` → `{"op":"online"}` → `{"op":"botstatus"}` → `{"op":"detail"}` → `{"op":"save"}` → `{"op":"stop"}`, transcript to a file. Then: `python - <<` asserts on the transcript + `%LOCALAPPDATA%/PocketRealm/realm/runtime-supervisor/journal.json` + `%LOCALAPPDATA%/PocketRealm/runtime/logs/*.log`: (a) bot counts present under the bot lane (`onlineBots`/`effectiveTarget`, RpHost.kt:141-154) DISTINCT from `online` players (DESK-29/DESK-127 "bots labeled" — WorldPresenceSample splits realPlayers from onlinePlayers, DesktopRuntimeBackend.kt:873-882); (b) grep sweeps for the password `DualPortPass1`, `BanGuestPass1`, `sHex`, `vHex`, any `token` field → 0 hits in journal/logs/status (secrets deliberately kept out per FriendInviteStore.kt:26-34, DesktopSupportBundle.kt redaction canaries :124-150); (c) status `listenAddress` is 127.0.0.1 only in LOCAL (ServerStatusJson.kt:28,36) | none | (a) bots are a labeled, separate field — never folded into player counts; (b) zero secret material (passwords/verifiers/invite tokens) in journal, logs, or status transcripts; (c) the LOCAL surface carries no LAN/host address. NOTE: the PUBLIC realm-status browser page with a privacy payload does not exist (→ gap G-3) — this row tests the shipped surface against the same rules | P1 |
| LV-multiplayer-16 | Host listener kill/restart while peers probe (transport reconnect mechanics — the ask's "kill and restart the host listener" simulation) | vanilla | Terminal A: `PW && gradlew.bat realmdHold` (background; note the java PID). Terminal B: python probe → REACHABLE (challenge bytes). Kill: `taskkill /PID <pid> /F` → probe immediately fails (ConnectionRefused). Restart: re-run `gradlew.bat realmdHold` → probe REACHABLE again within the hold window. Repeat kill/restart ×3 to shake out listener reuse (`reuseAddress`, ProvisioningListener.kt:83) | none | Every restart restores acceptance on 3724 with fresh challenge bytes within the probe timeout; no port-in-use residue (realmdHold's second boot must not EXIT_LISTEN). This measures the transport layer only — the app-level equivalent is LV-09 (graceful stop + re-host); automatic session continuity for a logged-in client does not exist (→ gap G-2) | P1 |
| LV-multiplayer-17 | Stop-with-closure — clean drain closes every listener (MP:STOP-WITH-GUESTS mechanics) | vanilla | Continue a live rpHost session (from LV-06/15); `{"op":"save"}` → `{"op":"stop"}`; after the drain: python probes on 3724 and 8085 → both refused; `type %LOCALAPPDATA%/PocketRealm/realm/runtime-supervisor/journal.json` → clean terminal state; gradle daemon log shows save ok:true (RpHost.kt:270-273) | none | `save` ok; `stop` drains world→realm→database (RpHost.kt:317-326) and both ports stop accepting within the drain window; journal decodes clean (no dirty-stop record). The D8 consent dialog naming players in the world is UI-surface and pinned by tests in LV-01's lane, not CLI-drivable | P1 |
| LV-multiplayer-18 | **ARTIFACT** — true two-peer LAN join with the real 5875 client + wrong-client refusal | vanilla (+ wrong-client negatives need R1/R2) | Preconditions (artifact stage): R3 client imported into the host lane; desktop app hosting started (UI — out of CLI scope, so the host leg uses `rpHost`-equivalent LOCAL stack where possible). `adb reverse tcp:3724 tcp:3724 && adb reverse tcp:8085 tcp:8085`; guest client (in-app) realmlist 127.0.0.1 → login → char-select → enter world. Wrong-client negatives: import the R1 8606 (or R2 12340) client and point it at the vanilla-only lane → the realmbuilds gate (C1) bites CLIENT-SIDE — ROUND-4 EXECUTED FINDING: realmd does NOT refuse wrong builds at the logon challenge (probe with `--build 8606`/`--build 5467` got full challenge answers, identical to 5875), because core-side `ok_build` (AuthSocket.cpp:920/986) only shapes the realmlist ADVERTISEMENT from `realmlist.realmbuilds` (“5875 6005 6141” in the live DB) which the stock client consumes when choosing realms; the server-side pairing checks live in the app-driven client (AuthGate.kt proofResponseBytes/build pairing, DESK-94). The refusal datapoint therefore needs the real wrong-build client | **R3** (5875 client), **R1 or R2** (wrong-build client) | 5875 guest completes logon+world entry through the one reverse bridge; the wrong-build client never reaches world entry (the realmlist advertisement's realmbuilds set excludes its build, so the stock client declines the realm; verify server-side via the auth/realmlist transcripts), never a half-login; `proto-chars`-equivalent roster shows the guest's character in the host's realm DB | P0 (stage 2) |
| LV-multiplayer-19 | **ARTIFACT** — tunnel lane live: advertise over a real tunnel adapter, join via CGNAT target, tunnel-adapter loss ⇒ TUNNEL_HOST_INTERFACE_LOST, and the tunnel×moderation interactions | vanilla | Preconditions (artifact stage): R7 (Tailscale or ZeroTier installed on host AND guest/second peer; Tailscale is the design's recommended option but the converged doc scopes it "dev/benchmark baseline only", §4) + the LV-08 precondition verb for the allowLanPlayers/friend-access toggles. Host: advertise field = the tunnel adapter's 100.64/10 address (HOST_LAN `EXTRA_ADVERTISED_ADDRESS`, or desktop Advertise card). Guest: JOIN_LAN with the host's CGNAT literal (`parseJoinTarget` widening). Then drop the host's tunnel (`tailscale down` / ZeroTier leave) while the session is up → the mode-keyed monitor must fire TUNNEL_HOST_INTERFACE_LOST (RealmService.kt:668-688), NOT the LAN leg; physical-LAN churn must NOT kill a tunnel-advertised session (InterfaceWatch table, RuntimeTopology.kt:417-440). Moderation interactions (audit round 1): with the wildcard-bound session up — (a) `adb shell find /data/data/com.pocketrealm -name realmd.conf -exec grep -A2 WrongPass {} \;`; (b) ban a guest (`ban_toggle.py` on the guest's pulled realmd DB or the app verb) and drive the ban-logon refusal through the tunnel path with `srp6_probe.py --host <tunnel-address> --port 3724`; (c) with friend access enabled, read the provisioning listener's bind from the guest's `friend-access` logs/`/proc/net/tcp` 0E8D entry | **R7** + a second peer/tailnet | Tunnel-advertised session comes up (wildcard bind `0.0.0.0` in the live conf + the ONE realmlist row carries the CGNAT address — ServerRuntimeFiles.kt:470, `bindAddressFor` :652-658); guest joins via the CGNAT target; tunnel-adapter loss ⇒ graceful TUNNEL_HOST_INTERFACE_LOST stop + save; LAN churn during tunnel hosting ⇒ session survives; AND (a) the wildcard-bind conf carries `WrongPass.MaxCount = 10` / `BanTime = 900` / `BanType = 0` (MP:CONDITIONAL-LOCKOUT "incl. tunnel wildcard" — ServerRuntimeContract.kt:85-87); (b) the banned guest's logon through the tunnel path is refused (probe: proof-refused / challenge-refused); (c) the 3725 provisioning listener's bind address is the LAN/tunnel endpoint, NEVER 0.0.0.0 (ProvisioningListener.kt:25-28 exposure invariant) | P0 (stage 2) |
| LV-multiplayer-20 | **OUT-OF-SCOPE-FOR-ROUND-1 (blocked, tracked — prerequisite P-1)** — friend-app end-to-end provisioning over a live hosted session (submit → approve → poll reveal) | vanilla | NOT CLI-drivable today and NOT scheduled in round 1: both ends need app UI — friend access lives in the protobuf DataStore (Settings.kt:65,397,836) toggled only in the LAN settings UI, and the friend submit rides FriendRequestFlow (UI). Unblock path tracked under "Tracked prerequisites" P-1 (a UI-driving harness, or a provisioning CLI verb added to the repo); revisit only after one of those lands. Negative legs already covered artifact-free in LV-12; the 3725-bind-address and ban-through-tunnel deltas are covered by LV-19(a-c)/LV-21 at their respective layers | none (apps under test) | (unchanged from the original design — recorded so the coverage ledger counts this as blocked-tracked, not runnable:) friend SUBMITTED → host approval → PROVISIONED + accountId; token burn after the one reveal; audit rows carry token hash only | P1 (blocked) |
| LV-multiplayer-21 | Tunnel×moderation conf/projection interaction — the wildcard-bind form at the JVM layer (audit round 1: MP:CONDITIONAL-LOCKOUT "incl. tunnel wildcard" + MP:TUNNEL-INVITE projection, tested nowhere before) | vanilla (JVM) | `PW && gradlew.bat test --tests com.pocketrealm.desktop.RuntimeFailureCopyTunnelTest --tests com.pocketrealm.desktop.LanInterfaceLossMonitorTest --tests com.pocketrealm.desktop.DesktopTunnelBackendTest --console=plain`; AND `PA && gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.server.ServerRuntimeFilesTunnelBindTest --console=plain` — ServerRuntimeFilesTunnelBindTest is ANDROID-lane: no compiled class exists in the desktop test tree and shared-sources.json does not list it; it compiled and ran 3/3 in the android unit lane (audit-round-2 finding, verified by class-tree scan) — the pins that matter for this row: a wildcard bind accepts remote logons so the D11 lockout MUST ride it (ServerRuntimeFilesTunnelBindTest:26-31), the loopback lane emits none (:47), the ADVERTISED tunnel address lands in the one realmlist row even when it differs from the bind (DesktopTunnelBackendTest:90-99), hostname/public/oversize advertisements refuse without touching the row (:118-146), and the copy/monitor entries key on TUNNEL_HOST_INTERFACE_LOST | none | All green: the tunnel/wildcard conf form CARRIES `WrongPass.*`; the realmlist row carries the ADVERTISED value (never the bind IP); refused advertisements leave the row untouched; the tunnel-loss copy resolves. This is the byte/pin layer of LV-19(a); the LIVE wildcard session legs are LV-22 | P1 |
| LV-multiplayer-22 | Brute-force lockout ENGAGEMENT — the engine actually locks the source, not just names it in conf (audit round 1: the security behavior was verified at byte level only) | vanilla | Non-loopback-bound realmd = LV-multiplayer-08's emulator session (10.0.2.15 bind) reached via `adb forward tcp:13724 tcp:3724`, OR the G-4 desktop verb when it lands. `python out\live-verification\scripts\srp6_probe.py --host 127.0.0.1 --port 13724 --account LOCKPROBE1 --password LockProbePass1` once (correct logon must be ACCEPTED — M2 verified; seed the verifier row the AuthGate way or create the account on the guest lane). Then wrong-password × 11 (WrongPass.MaxCount+1): `python srp6_probe.py --host … --account LOCKPROBE1 --password LockProbePass1 --wrong-password --attempts 11`. Then one more CORRECT attempt → expect refusal; evidence: probe verdict (`challenge-refused` = ip_banned gate, or `proof-refused`) + `adb shell … realmd.log \| grep -iE "wrongpass\|banned"` + the ip_banned row (`ip_banned` columns ip/banned_at/expires_at — schema verified this session). CONTROL (loopback never engages): against the LOCAL rpHost realmd (127.0.0.1, no WrongPass keys — LV-11): same 11 wrong attempts then a correct attempt must STILL succeed and the ip_banned count in classicrealmd.sqlite3 unchanged (python sqlite3 read) | none (srp6_probe.py authored + golden-verified; blocked only on LV-08's session precondition P-3) | 11 wrong proofs ⇒ the NEXT correct logon is refused with lockout evidence in realmd.log/ip_banned carrying the probe's source; before the 11th wrong attempt the same correct logon was accepted; the loopback control lane never engages (correct logon still accepted, no ip_banned row added) | P1 (blocked-tracked on P-3) |
| LV-multiplayer-23 | Desktop-lan legs — DESK-15 parse/authorization + DESK-42 persisted form & realmlist projection (audit round 1: claimed by no matrix); DESK-24 = UI, excluded | vanilla (desktop JVM) | `PW && gradlew.bat test --tests com.pocketrealm.desktop.DesktopAppModelLanLaunchTest --tests com.pocketrealm.desktop.DesktopAppModelTunnelLaunchTest --tests com.pocketrealm.desktop.DesktopLanInterfacesTest --tests com.pocketrealm.desktop.DesktopTunnelBackendTest --console=plain`; then the persisted-form leg: the desktop settings are a plain JSON file (CLI-editable, unlike the Android DataStore) — after ANY desktop LAN launch spec is built (behind G-4's verb), `python -c "import json;print(json.load(open(r'%LOCALAPPDATA%/PocketRealm/settings/settings.json')))"` asserts the `runtimeMode`/`lanAddress`/`allowLanPlayers`/`advertisedAddress` fields match the launch spec (DESK-42's recorded lanAddress+runtimeMode persistence form); the realmlist projection bytes for a LAN_HOST launch spec are pinned by DesktopTunnelBackendTest (advertised → realmlist row :90-99; `realmlist -> 127.0.0.1` default :114) | none | All green: parseLan validation + allowLanPlayers authorization gate (DESK-15's desktop semantics), the persisted settings form round-trips, virtual/hidden Windows adapters excluded (DesktopLanInterfacesTest), advertisement projects to the realmlist row and refuses honestly. DESK-24 (LAN section in SettingsScreen) is UI surface — recorded UI-excluded like DESK-135, covered indirectly by DesktopAppModelLanLaunchTest's model-level pins; the LIVE desktop-hosted LAN session stays behind G-4's rpHost mode verb | P1 |

### Driver scripts (AUTHORED this audit round — on disk under out/live-verification/scripts/, stdlib-only python, no repo changes; smoke-tested here)

Authored as the execution stage's first step is DONE — no row 06/14/15/22 can fail for a missing
script. What was run in this audit round (all commands executed here):

- `python -m py_compile rpdrive.py ban_toggle.py srp6_probe.py` → OK for all three.
- `python srp6_probe.py --selftest` → rc=0, "**SELFTEST PASSED (x, v, K, M1, M2, challenge body
  all match golden)**" — the python SRP6 math cross-checked against AuthGateTest.kt's INDEPENDENT
  golden fixtures (AuthGateTest.kt:15-23 provenance: derived from cmangos SRP6.cpp, not from the
  Kotlin). The wire driver itself (challenge/proof over a socket) has not touched a live realmd
  yet — that is the executing stage's first socket run.
- `python rpdrive.py --selftest` → rc=0, "SELFTEST PASSED" — step parser, quote splitting,
  @@RP@@ filter, and a fake-child pipe round-trip. (Two real bugs found and fixed during this
  selftest: a json-`true`-as-Python-literal fixture bug, and stdin-iteration buffering.)
- `ban_toggle.py` round-trip against a SCRATCH COPY of classicrealmd.sqlite3 (never the live
  datadir; copy removed after): ban → `effective_banned=True`; expire → `effective_banned=False`;
  unban → lifts; all rc=0. (One real bug found and fixed: the status SELECT named a non-existent
  `banreason` column — the real schema is `reason`, verified read-only via
  `PRAGMA table_info(account_banned)` = id, account_id, banned_at, expires_at, banned_by,
  unbanned_at, unbanned_by, reason, active.)

Scripts:

- `out/live-verification/scripts/rpdrive.py` — spawns `gradlew.bat rpHost` with cwd=desktop (or
  attaches to an already-running rpHost via two pipes), writes one JSON line per `--steps` item
  (`hello` → `{"op":"hello"}`; `account:U:P` → `{"op":"account","user":U,"pass":P}`;
  `proto-login`/`proto-login:U:P`; `proto-world:U`; `proto-create:NAME`; `proto-chars`;
  `proto-pick:<guid from the preceding proto-chars reply>`; `proto-chat:"TEXT"`;
  `bots:N`; `botstatus`; `online`; `detail`; `save`; `stop`), reads stdout, keeps only
  `@@RP@@`-prefixed lines (RpHost.kt:298-301), asserts the expected `ok` per step, exits non-zero
  on the first failing assertion or on rpHost's own exit codes 2/3 (preflight/world refusal,
  RpHost.kt:328-329). All stderr/diagnostics go to a transcript file.
- `out/live-verification/scripts/ban_toggle.py` — `ban|unban|expire <username>` over
  `%LOCALAPPDATA%/PocketRealm/database/sqlite-datadir/classicrealmd.sqlite3` via python `sqlite3`:
  resolves `account.id`, then inserts/updates `account_banned` with the engine's row semantics the
  live test pins (active=1; `banned_at`=epoch now; `expires_at`=now+3600 for `ban`, an
  already-lapsed `expires_at` for `expire`; `unban` sets active=0 — the predicate cited at
  FriendAccountNativeOpsTest.kt:187-196 = AuthSocket.cpp:466-467). MUST be run only while the
  stack is down (rpHost stopped), mirroring the AuthGate precedent's at-rest writes.
- `out/live-verification/scripts/srp6_probe.py` — the 1.12 SRP6 wire probe (challenge → proof →
  M2 verify), mirroring VsrpMath/VsrpClient (AuthGate.kt:181-478) and Srp6AccountMath
  (N = 0x894B…9BB7, g=7; x over the salt's little-endian bytes, both identities uppercased).
  Modes: one correct logon (`--host --port --account --password`), N wrong-password attempts for
  the lockout-engagement leg (`--wrong-password --attempts 11`), challenge-only reachability
  (`--challenge-only` — also the banned-at-challenge probe), `--selftest` golden cross-check.
  Used by LV-multiplayer-06 (alternative success leg), LV-22 (engagement), LV-19(b).

### Tracked prerequisites (blocked rows — the coverage ledger distinguishes "blocked, tracked" from "runnable")

| id | blocker | unblocks | owner | tracked where |
|---|---|---|---|---|
| P-1 | A UI-driving harness for the two apps, OR a provisioning/friend-access CLI verb added to the repo (Android intents exist but the toggles live in the protobuf DataStore, Settings.kt:65; desktop has no CLI verb at all). NOT unblockable by any requirements.md artifact. | LV-multiplayer-20 (explicitly OUT-OF-SCOPE-FOR-ROUND-1) | workflow owner / repo | this file; revisit only after the verb/harness lands |
| P-2 | G-4's desktop CLI bring-up verb for a LAN_HOST / wildcard-bind (tunnel-advertised) session — e.g. an `rpHost -PrpMode=…` property. Repo change, no artifact unblocks it. | LV-multiplayer-22's desktop variant; the live desktop-hosted legs of LV-multiplayer-19/23; DESK-42's live desktop side | repo (harness verb) | Architecture gaps G-4 (below), now SCHEDULED via rows 22/23 rather than open-ended |
| P-3 | A CLI verb to set the guest's `allowLanPlayers` toggle (adb-root or `run-as` DataStore patch; field layout to be read from the actual `files/datastore/*.preferences_pb` on first boot). | LV-multiplayer-08/09/10/22's guest legs (all HOST_LAN starts refuse without the toggle, AndroidRuntimeBackend.kt:180-186) | execution stage, verified on first emulator boot | LV-08 precondition; if the patch proves unreliable these rows re-block onto P-1 |
| P-4 | (cross-area, recorded here for the ledger) LV-timeframe-era-17's blocker is a repo instrumented-test addition — same "blocked, tracked" class as P-1/P-2, no artifact unblocks it. | that area's row | timeframe area's matrix owner | matrix-timeframe-era section |
| P-5 | (cross-area, audit round 2) The `{"op":"bots","target":N}` console op does NOT control a world whose app-layer admission monitor owns the target: the begin→set→retire generation handshake refuses a second begin with rc=2/WRONG_STATE while a monitor generation is live (DesktopBotAdmission.kt:55-78 + the Android twin WorldRuntimeService.kt:498-673; DESK-134's default profile means a monitor is ALWAYS live on a fresh install). **Executed observation backing this: LV-multiplayer-15's round-2 run — `bots:5` against a live rpHost world answered `{"reason":"begin:2","ok":false}` while botstatus showed `effectiveTarget=25` from the profile** (matrix-multiplayer.md annex). | **RESOLVED in audit round 3** — both rows were amended (option (b) adopted): matrix-bots-llm.md:122 LV-bots-llm-03 now pins the executed `begin:2` refusal as the DESKTOP contract and routes live retarget through the owning lane; matrix-bot-actors.md:233 LV-bot-actors-23 moved to the relay's `world-bots` owning verb (WorldConsoleRelay.kt:151) with the refusal recorded. No rows are blocked on this item; the entry stands for the MECHANICAL FACT (a second `begin` on a live admission generation returns WRONG_STATE — DesktopBotAdmission.kt:55-78, executed observation in LV-multiplayer-15) and for any FUTURE row that reaches for the console `bots` op: on a world with a live profile-owned monitor, expect `begin:2`, never a retarget | both sibling rows are runnable as pinned — a coverage ledger must NOT count them as blocked | this file (multiplayer owns the executed observation) |

Note: LV-multiplayer-19's "second peer" is NOT in this table — it is a documented artifact
caveat (requirements.md §2 R7, :178-180), so it stays a normal stage-2 artifact row.

### Row → deliverable-ID coverage

- MP:LAN-JOIN-PROBE → 01, 02, 03. MP:LAN-LIVENESS-LOSS-COPY → 08, 09, 10. MP:TUNNEL-LANE →
  01, 02, 10, 19, 21 (+ gap G-2/G-3). MP:TUNNEL-INVITE → 01 (TunnelInviteTest), 20, 21 (realmlist
  projection). MP:CONDITIONAL-LOCKOUT → 11, 21 (wildcard-bind conf), 22 (behavioral engagement).
  MP:RA-VERIFIER-PATH → 13, 20. MP:RA-INVITE-TOKENS → 12, 20. MP:RA-RATE-LIMITS → 12.
  MP:GUEST-BANS → 13, 14, 19(b). MP:STOP-WITH-GUESTS → 17. MP:MOBILE-PARITY → 01
  (LanTunnelWiringContractTest), 02, 08-10 (Android service intents). MP:HARDENING-FIXES → 13
  (ban transaction/pragma legs run live), 12. DESK-127 (realm-status surface) → 15.
  DESK-15/42 (desktop-lan) → 23; DESK-24 recorded UI-excluded in 23; DESK-42's LIVE desktop side
  stays behind G-4/P-2. Dual-port handoff (convergence §1.3) → 04, 05, 06, 07, 18.

## Architecture gaps (severity: design-gap — recorded, NOT tested; no tests invented for unimplemented behavior)

- **G-1 — Tier-1 LAN advertisement/discovery is absent.** The converged architecture's Tier 1
  (convergence §5: mDNS/NSD `_wowrealm._tcp` advertisement; research §4.9 invite-derived
  discovery) has no implementation: no `NsdManager`/mDNS code exists in either app tree (grep for
  `NsdManager|_wowrealm|mdns` over android/app/src/main + desktop/src/main → 0 files). LAN
  "discovery" today is the typed numeric address literal + `LanJoinProbe` (wiki: "There is no
  automatic public discovery"; the invitation is the address). Consequence: mDNS-failure and
  advertisement-rotation behaviors are untestable — nothing to test.
- **G-2 — Tier-2 embedded tunnel/relay + reconnect resilience is absent (the converged main
  feature).** No iroh/wowmesh/QUIC transport code exists (grep over native/, desktop, android → 0
  files). There is no loopback→QUIC gateway, no endpoint-identity (NodeId) that survives host
  address changes, no relay fallback, no RELAY-ONLY privacy mode, and no epochs/standby automatic
  host failover (research §4.10). The implemented interruption semantics are deliberately the
  opposite of failover: interface loss ⇒ save + graceful stop (LV-09/10), and a restarted host
  requires a manual re-host (LV-09's reconnect leg). "Tunneling" as shipped = OS-level
  Tailscale/ZeroTier + numeric advertise + wildcard bind (ServerRuntimeContract.kt:88-93) — which
  LV-19 tests. Per the ask, no tests are designed here for automatic failover or session continuity.
- **G-3 — Tier-3 realm-status browser page is absent.** The convergence's "now" Tier-3 item — a
  realm-status web page via cloudflared quick tunnel with a privacy payload (bots labeled,
  character names hidden, no IPs/secrets) — is not implemented: no HTTP server code exists in
  either tree (grep `httpserver|HttpServer|Ktor|NanoHTTPD` → 0 files), and environment.md records
  the correction that the realm-status surface has no port at all (JNI `statusNative` →
  `ServerStatusJson` → Diagnostics, DESK-127). LV-15 applies the converged privacy rules to the
  surface that exists; the public page itself remains untestable until built.
- **G-4 (harness, not converged architecture) — no CLI bring-up for LAN_HOST on desktop, and TWO
  UI-only settings gates.** Every desktop CLI gate hardcodes `RuntimeMode.LOCAL`
  (BootRealmd.kt:31-35, BootWorld.kt:40-44, AuthGate.kt:55-59, RealmdHold.kt:20-24, RpHost.kt:51-56),
  so a desktop-hosted LAN/tunnel session can only be started through the app GUI today. Audit
  round 1 additionally found that a guest HOST_LAN start refuses unless the STORED
  `allowLanPlayers` DataStore toggle is on — AndroidRuntimeBackend.kt:180-186 ("Allow LAN players
  is disabled in Settings") — and that toggle is protobuf DataStore (Settings.kt:65), UI-only
  (this re-classifies the guest legs of rows 08/09/10 onto tracked prerequisite P-3). Remediation
  is now SCHEDULED, not open-ended: P-2's rpHost mode verb unblocks the desktop legs (rows 22/23),
  and P-3's toggle setter is verified on first emulator boot. Until then the tunnel/wildcard and
  desktop-lan behavior is pinned at the JVM layer by rows 21 and 23 (runnable now).

## Round 2 execution results (2026-10-02, this ask — the no-emulator now-rows were RUN)

All commands executed this round; every result below is this session's output, not the records'.

| Row | Command (exact) | Result |
|---|---|---|
| LV-multiplayer-01 (desktop half) | `cd desktop && gradlew.bat test --tests com.pocketrealm.supervisor.RuntimeTopologyTest --console=plain` | **21 tests, 0 failures, 0 skipped** (build/test-results/test/TEST-…RuntimeTopologyTest.xml). NOTE: the `--tests "*Name"` wildcard form fails under this shell's cmd quoting ("No tests found for given includes") — use fully-qualified class names |
| LV-multiplayer-01 (android half) + LV-12 android + LV-21 android | `cd android && gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.ui.LanTunnelWiringContractTest --tests com.pocketrealm.ui.TunnelInviteTest --tests com.pocketrealm.server.ServerRuntimeFilesTunnelBindTest --tests com.pocketrealm.supervisor.FriendInviteStoreTest --tests com.pocketrealm.supervisor.ProvisionRateLimiterTest --console=plain` (rc=0; `-PpocketAbi` is REQUIRED — build.gradle.kts:950 fails without it) | **7 + 6 + 3 + 16 + 5 = 37 tests, 0 failures, 0 skipped** — incl. the wildcard-bind `WrongPass.*` pins (ServerRuntimeFilesTunnelBindTest 3/3) and the 7-test LAN/tunnel wiring contract |
| LV-multiplayer-04 | `cd desktop && gradlew.bat authGate --console=plain` | **rc=0, "logon proof accepted; M2 verified; account=AUTHGATE" / "PROTOCOL-AUTH GATE PASSED"** — the live 3724 SRP6 handshake works |
| LV-multiplayer-05 | `cd desktop && gradlew.bat bootWorld --console=plain` | **rc=0, "cycle 1 world READY … 127.0.0.1:8085 accepted a connection … save: ok=true … stopped (sidecars sealed) … WORLD BOOT GATE PASSED (cycles=1)"** |
| LV-multiplayer-06 | `python out\live-verification\scripts\rpdrive.py --steps hello,account:DUALPORT1:DualPortPass1,proto-login,proto-world:DUALPORT1,proto-create:Dualport,proto-chars,proto-pick,proto-chat:"handoff ok",stop` | **rc=0 END-TO-END: proto-login ok:true (3724 SRP6) → proto-world authResult=12 (8085 world AUTH_OK) → character created (guid 55, listed by proto-chars) → proto-pick ok → proto-chat ok → stop ok** — the dual-port handoff is PROVEN live through one path. Transcript: server replies carry no password fields |
| LV-multiplayer-12 | `gradlew.bat test --tests com.pocketrealm.desktop.ProvisioningListenerTest …` (desktop) + the android FriendInviteStore/ProvisionRateLimiter legs above | **9/9 listener (real loopback sockets) + 16/16 invites + 5/5 rate-limiter, 0 failures** |
| LV-multiplayer-13 | `cd desktop && gradlew.bat test --tests com.pocketrealm.desktop.FriendAccountNativeOpsTest --console=plain` | **1 test, 0 failures, 0 skipped** — the live-world leg (verifier provisioning, duplicate refusal, roster ban state, ban/unban → account_banned, lapse predicate, gmlevel, characters) ran against the real staged stack |
| LV-multiplayer-14 | `rpdrive.py --steps account:BANGUEST1:BanGuestPass1,proto-login,save,stop` → `ban_toggle.py ban BANGUEST1` → `rpdrive.py --steps proto-login:BANGUEST1:BanGuestPass1,stop` → `ban_toggle.py unban` → proto-login → `ban_toggle.py expire` → proto-login | **ALL FOUR LEGS LIVE: baseline proto-login ok:true; banned → proto-login answers `{"ok":false,"reason":"challenge rejected with result 12"}` (realmd refuses the banned account AT THE CHALLENGE); unban → ok:true; lapsed suspension → ok:true (the expiry predicate honored)** |
| LV-multiplayer-15 | `rpdrive.py --steps bots:5,botstatus,online,detail,save,stop` + python sweep over journal + runtime logs + server-side transcripts | **bots lane is live-labeled separately from players** (`botstatus` → `onlineBots/effectiveTarget/available/accounts/dbProbeDelayMs` vs `online` players); honest observation recorded: `bots:5` refuses with `begin:2` in the harness lane (the world's app-layer admission monitor owns the target; effectiveTarget=25 from its profile) — this EXECUTED observation is now the pinned contract for every `bots`-op row: see tracked prerequisite P-5 (matrix-bots-llm-03 / bot-actors-23 must adopt the fresh-read precondition or assert the refusal). Sweep: **0 secret hits (passwords/sHex/vHex/token patterns) across 17 files** (journal.json decodes clean); LOCAL status surface carries 127.0.0.1 only (ServerStatusJson.kt:28,36) |
| LV-multiplayer-21 | `gradlew.bat test --tests com.pocketrealm.desktop.RuntimeFailureCopyTunnelTest --tests com.pocketrealm.desktop.LanInterfaceLossMonitorTest --tests com.pocketrealm.desktop.DesktopTunnelBackendTest --console=plain` (+ the android ServerRuntimeFilesTunnelBindTest leg above) | **2 + 7 + 7 = 16 desktop tests, 0 failures** + 3/3 android wildcard-bind pins — the tunnel×lockout conf/projection interaction holds at the byte layer |
| LV-multiplayer-22 (CONTROL leg only) | inline orchestrator: `gradlew.bat rpHost` → `{"op":"account","user":"LOCKPROBE1","pass":"LockProbePass1"}` → `srp6_probe.py --wrong-password --attempts 11` → `srp6_probe.py` (correct) → ip_banned count before/after | **CONTROL PASSED: 11 wrong-password attempts on the loopback lane (no WrongPass keys) go SILENT (no verdict packet — recorded server posture with MaxCount disabled), the NEXT correct logon is still `accepted; M2 verified`, and `ip_banned` rows 0 → 0.** The python probe's math is golden-verified AND now wire-proven (full successful logon against live realmd). The non-loopback ENGAGEMENT leg remains gated on P-3 (emulator LAN session) |

**Round-2 discoveries folded into the rows above:** (1) engine account policy rejects non-alnum
passwords — `created=1` (INVALID_ARGUMENT) observed live for `DualPort-Pass-1`; all example
credentials now alnum-conformant; (2) banned logons refuse at the CHALLENGE (result 12), refining
LV-14's oracle; (3) rpdrive gained event-line skipping, pre-send auto-guid resolution, and
process-tree teardown during these runs (selftest still green); (4) `--tests` wildcards must be
fully-qualified class names under this shell; the android unit lane needs `-PpocketAbi=x86_64`.

Not executed (unchanged): LV-multiplayer-02/03/07/08/09/10/11 (non-loopback leg)/16/17 — emulator
boots required (P-3 for the HOST_LAN rows); LV-multiplayer-18/19 — artifacts (R1-R3/R7);
LV-multiplayer-20 — P-1; LV-multiplayer-22's engagement leg — P-3.

## Round 3 execution results (2026-10-02 — the host-only probe rows RUN; still zero emulator boots)

| Row | Command (exact) | Result |
|---|---|---|
| LV-multiplayer-03 | python orchestrator: `gradlew.bat realmdHold` (desktop/) + `python srp6_probe.py --host 127.0.0.1 --port 3724 --account DUALPORT1 --password DualPortPass1 --challenge-only` + the same with `--account NOSUCHACCT9` | **Positive: challenge ANSWERED for the existing account** (`stage='challenge', "answered (N/g/salt verified, g=7)"` — realmd served a full challenge); **negative discriminator: unknown account → `challenge-refused result=4`** (observed live code semantics: 4 = unknown account, 12 = banned — LV-14's banned leg hit 12; srp6_probe's labels updated to match). Listener came up in 2.0 s on every cycle |
| LV-multiplayer-16 | python orchestrator ×3 cycles: `gradlew.bat realmdHold` → wait 3724 accept → `taskkill /PID <pid> /T /F` → re-probe → relaunch | **3/3 cycles: listener up in 2.0 s, killed, port REFUSED after kill, restarted clean — no port-in-use residue** (a second realmdHold boot never took EXIT_LISTEN) |
| LV-multiplayer-17 | `rpdrive.py --steps hello,save,stop` then python post-probes on 3724/8085 + journal read | **rpdrive rc=0; BOTH ports refuse after the drain (`3724:refused`, `8085:refused`); journal decodes with `phase=STOPPED`, `lastDurableAction=clean-stop-committed`** — clean stop-with-closure proven live |
| LV-multiplayer-11 (loopback leg, re-verified on the freshest conf) | `grep -n "BindIP" %LOCALAPPDATA%/PocketRealm/runtime/server/run/realmd.conf` and `grep -c WrongPass <same>` after this round's boots | **`3:BindIP = "127.0.0.1"` and 0 WrongPass matches (grep rc=1)** — the loopback-absence contract holds on the live conf written by this round's boots |

Round-3 notes: (1) realmdHold's docstring cites `tools/realmd_srp_probe.py` — that file does NOT
exist in tools/ (checked: only fix_probe_nuls.py + srp6_reference_harness.cpp); the harness lane's
`srp6_probe.py` fills exactly that gap and its math is golden-verified. (2) srp6_probe's challenge
result-code labels were corrected against live observations (4 = unknown account, 12 = banned).
Still not executed: every emulator row (02/07/08/09/10/22-engagement) and artifact rows (18/19).

## Round 4 execution results (2026-10-02 — a wrong-client assumption TESTED and REFUTED at protocol level)

| Row / finding | Command (exact) | Result |
|---|---|---|
| Wrong-client gate location (feeds LV-multiplayer-18; srp6_probe gained `--build`/`--version`) | Gate data first: python sqlite3 read-only `SELECT * FROM realmlist` on classicrealmd.sqlite3 → `realmbuilds = '5875 6005 6141 '` (C1's varchar(64) column, live). Then a `realmdHold` session + `srp6_probe.py --challenge-only --account DUALPORT1 --build 5875 --version 1.12.1` / `--build 8606 --version 2.4.3` / `--build 5467 --version 1.10.2` | **REFUTED the challenge-level-gate assumption: ALL THREE builds got a full challenge answer** (`stage='challenge', "answered (N/g/salt verified, g=7)"` each) — realmd does not refuse builds at the logon challenge. Mechanism read from the core: `ok_build` (AuthSocket.cpp:920/986) only shapes the realmlist ADVERTISEMENT built from `realmlist.realmbuilds`; a stock client consumes that advertisement and declines realms that exclude its build. Consequence recorded in LV-multiplayer-18: the wrong-client refusal negative stays artifact-stage (R1/R2 real clients) — and is now precisely located client-side, with the server-side pairing checks living in the app-driven client (AuthGate.kt DESK-94). No row asserts a server refusal that the executed evidence contradicts |

Round-4 notes: srp6_probe.py gained `--build`/`--version` (selftest still golden-green); the
live `realmlist.realmbuilds` value is now on record as the client-side gate datum.

Propagation status (verified this ask, audit round 4): the executed refutation is cited where it
bites — matrix-bot-actors.md:270 LV-bot-actors-30 INVERTED (flip `realmbuilds`→'12340', assert
proto-login still COMPLETES, restore; cross-refs this annex + LV-multiplayer-18 +
matrix-flavor-import.md LV-flavor-import-18/19), matrix-server-db.md:107 LV-server-db-34
RE-STATED at the advertisement layer (client-declines-advertisement artifact leg kept; the
server half is the read-only `realmbuilds` datum check, wrong-build challenge recorded as
ANSWERED, never as a refusal). Verified by grep: the stale texts ("capture the
AUTH_LOGON_CHALLENGE refusal", "wrong-client server half") return 0 hits across all nine
live-verification matrices, and ok_build/client-side/advertisement now appear in both sibling
files. No stale C1 coverage line remains anywhere.

## Honesty notes (updated in audit round 1)

- Round-4 update: fifteen rows executed green through round 3 (see the round-2/3 annexes), plus
  round 4's EXECUTED REFUTATION: the wrong-client build gate does NOT exist at the realmd
  challenge — three wrong builds got full challenge answers — so the negative stays where the
  evidence puts it (LV-18, artifact stage, client-side realmbuilds advertisement). A row was NOT
  invented for a server refusal the execution disproved. srp6_probe gained `--build`/`--version`
  (selftest golden-green).
- The three harness scripts now EXIST on disk (audit fix); `srp6_probe.py`'s math is golden-
  verified but its socket half has not met a live realmd yet — the executing stage runs that first.
- `adb root` on the default-image emulator is the linchpin of rows 02/07/08/09/10 (services are
  exported=false); expected on the pinned `default/x86_64` userdebug image but unverifiable
  without booting — row 02 begins with the explicit verification step. The debug build also
  admits `run-as com.pocketrealm` as a fallback file-access route (P-3).
- Row 09's interruption trigger is pre-verified on first boot per the audit; `svc wifi/data` may
  not carry the AVD's eth0 NAT uplink — the recorded substitute is
  `cmd connectivity airplane-mode enable/disable`, and the run log names whichever fired.
- Row 07 depends on `nc` existing in the emulator's toybox; a documented fallback defers the
  guest-side leg rather than faking it.
- Row 14 bans via direct SQL using the engine's documented row semantics (the repo's own AuthGate
  direct-write precedent, and the column names verified against the live schema this round); the
  app's own ban verb is exercised live in row 13 — together they cover "ban written" and "ban
  bites" without inventing UI steps.
- Rows 08/09/10/22 discovered to need the guest allowLanPlayers toggle (AndroidRuntimeBackend.kt:
  180-186, found in audit round 1) — their precondition is tracked as P-3, not silently assumed.
- File hygiene (audit round 2): the stray CR byte at this file's line 268 (`scripts` + 0x0D +
  `pdrive.py`) is eliminated — a full byte scan now returns **0 CR bytes** in
  matrix-multiplayer.md. NOTE for the sibling owner: matrix-bot-actors.md line 209 has the same
  class of byte (CR-not-followed-by-LF at byte 24935, found by the same scan) — fix it there
  before an executor transcribes that command.
- Row counts (arithmetic corrected in audit round 2): 23 rows. 19 in the artifact-free
  enumeration (01-17, 21, 23); of those, 08/09/10 additionally require the P-3 guest-toggle
  precondition, row 11's non-loopback leg and row 22's engagement leg ride LV-08's session, and
  row 23's settings-form leg needs G-4's verb (its JVM half ran green). Then 2 artifact-gated
  stage-2 rows (18, 19), 1 blocked row (20 → P-1), and row 22 (control leg EXECUTED green;
  engagement leg → P-3). The ledger should count 19 as the runnable-now enumeration, minus the
  P-3-gated legs noted above.

### 4.5 — section file `matrix-timeframe-era.md` (embedded verbatim)

# Live-Test Matrix — timeframe-era (content-patch timeframe control on both platforms + era-policy linkage)

Written 2026-10-02 by the matrix-designer step (round 1). **Revised 2026-10-02 after audit round 1**
(owner ruling recorded; row 17 tracked-blocked; rows 20/21/22 added; cross-matrix notes appended).
**Round 2 (2026-10-02):** rows 06/07/08 were EXECUTED this ask at class scale and passed (desktop
11+34+18, android 25 — all 0 failures); row 08's mechanism corrected with the mandatory
`-PpocketAbi` property (its absence fails configuration — hit live); row 16's on-device meta path
pinned to a literal run-as path; row 18's success/failure outputs and exit codes grounded; row 21's
staging-artifact names and selector check written as literal commands; new row 23 (stage persistence
+ idempotent short-circuit across restarts); operational gradle notes added.
**Audit round 2 fixes:** emulator launch lines pinned to the full SDK binary path (bare `emulator`
is not on PATH — verified [X]); row 22 leg B re-pinned to the ONE library the friend-account ops
bind to (`System.loadLibrary("pocket_world_runtime")`, WorldNative.kt:4) across all four staged
copies, with `pocket-runtime-build/libpocketrealm.so` annotated as a vestigial staging entry that
must not be scanned (0 hits expected) — cross-matrix note N4 records the reconciled CLEAN 5.22
verdict against server-db-36(b)'s false FAILING.
**Round 3 (2026-10-02) — the desktop live lane was EXECUTED:** rows 09/10/23/21/18/11/12/13/14 all
ran for real through `supervisorStartGate`/`whisperGate` on this host and passed, with two honest
layering discoveries (rows 13/14: the db-start `DB-NOT-SEEDED` and realm `DB-LINK` provider gates
fire BEFORE the overlay guards — still fail-closed, refusals recorded verbatim); row 16 step 1
(emulator boot) validated live (24 s cold boot, `boot_completed=1`, API 35); row 20's aggregate
EXECUTED (`check_repo: OK (1307 tracked files, 0 errors, 0 warnings)`, RC=0). See "Live executions
(round 3)" below and the per-row updates.
**Round 4 (2026-10-02):** row 03's second poison variant CORRECTED and executed — a well-formed
stage-id rename escapes the `--repo-root` structural gate (RC=0 observed!), only a MALFORMED id
trips it (`bad stage id`, RC=1); row 16's real driver discovered and cited
(`tools/run_baseline_world_sanity.py` — the repo's own boot+stage+instrument lane) and its
`--skip-build --only b` form ATTEMPTED live: every stage worked except the instrument itself —
ALL prior APK captures predate the runner class (`ClassNotFoundException`, 0 dex hits in both
August tests APKs), so row 16's steps 2-4 need a fresh build (a repo build action, recorded as
precondition N5 — not a user artifact). Evidence classes:
**[R]** = file read this ask (path:line cited), **[X]** = command executed this ask (command +
output quoted), **[D]** = designed here, execution deferred to the live stage.

Area scope: campaign-inventory **timeframe (8)** deliverables TF-1…TF-8 (campaign-inventory.md:254-263)
plus the era-policy linkage they imply (TF-6 chat era-policy; the BOT-POLICY flavor-keyed seam at
campaign-inventory.md:268 as a cross-area interaction note). All TF-1…TF-8 rows are covered below;
TF-9 (device-only `TimeframeOverlayApplier` SQLiteDatabase leg, campaign-inventory.md:279) gets an
honest dedicated row — **no instrumented test class for it exists in the repo** (verified, row 17).

Environment: the hybrid lane of out/live-verification/environment.md — Windows-host desktop JVM
hosts the servers in-process (`cd desktop && gradlew bootRealmd/bootWorld/supervisorStartGate`);
headless AVD `PocketDiff_x86_64` + `adb reverse` bridges the Android half; debug builds only.
Repo root for every command below: `E:\pocket_realm_complete` (call it `%REPO%`). Python must be
invoked with Windows-style paths (Windows python does not understand Git-Bash `/e/...` paths —
hit live in round 1: `python open('/e/...')` → FileNotFoundError while `ls` saw the file).

## Owner ruling (2026-10-02) — emulator-instrumented rows are IN-SCOPE

Recorded verbatim from the run owner in response to audit round 1 (requirements.md §1 listed the
"Android instrumented lane" as hardware/people-dependent; environment.md sanctions the headless
emulator; the two could otherwise void each other at execution time):

> RULING: YES — emulator-instrumented rows are IN-SCOPE for the unattended live lane;
> requirements.md §1's exclusion is hereby interpreted as PHYSICAL-DEVICE-ONLY.
> 1. IN-SCOPE: connectedDebugAndroidTest rows on the headless emulator lane only — headless
>    PocketDiff_x86_64 AVD (API 35), ANDROID_SERIAL=emulator-5554 (or the lane's actual emulator
>    serial), -PpocketAbi=x86_64, debug builds only.
> 2. REMAINS OUT OF SCOPE: the RP6 / physical-device lane, unchanged. No download unblocks it.
> 3. The requirements.md §1 lines were written under the assumption that instrumented tests need a
>    physical device — that assumption is void for the emulator lane chosen by environment.md.
>    Do NOT edit requirements.md (out of the section writer's scope); this note travels with the
>    section files and the plan-assembler carries it into the assembled plan.
> 4. Constraints that still bind every such row: headless-safe, bounded runtime, artifact-free.
>    Any row that cannot meet those stays marked as needing the physical lane and remains out of scope.

Grounding for the ruling's premise (verified this ask): the connected-test gate approves emulator
serials explicitly — `"Connected-test safety: approved emulator target $serial"`
(android/app/build.gradle.kts:107-112 [R]); the x86_64 staging tree exists
(`native/.build-o09-x86_64/` [X]); and the staged x86_64 `libpocket_world_runtime.so` carries the
five friend-account JNI exports (row 22's live scan [X]).

## Operational notes for the gradle/emulator legs (learned by running them this ask)

- **`emulator` is NOT on this host's Git-Bash PATH.** Launch it by full path —
  `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe` (verified this ask:
  `"C:/Users/<owner>/AppData/Local/Android/Sdk/emulator/emulator.exe" -list-avds` →
  `PocketDiff_x86_64`, `PocketRealm_API28`, RC=0 [X]) — or have the executor harness prepend
  `%LOCALAPPDATA%\Android\Sdk\emulator` (and the adb dir, `C:\platform-tools` per
  environment.md:82) to PATH. The repo's own lane resolves the same way from the SDK constant:
  `EMULATOR = SDK / "emulator" / "emulator.exe"`, with `SDK = ANDROID_SDK_ROOT or ANDROID_HOME or
  ~/AppData/Local/Android/Sdk` (tools/run_differential_parity.py:40-43 [R]). Every `emulator …`
  launch line below means that full path.

- From Git Bash, invoke gradle as `cmd //c "gradlew.bat …"` inside the project dir. `cmd /c` is
  MSYS path-mangled into an INTERACTIVE cmd session that runs nothing (hit live: the task stdout
  showed only the cmd banner [X]); bare `gradlew` is not on PATH (RC=127 [X]).
- The android project REQUIRES `-PpocketAbi=<abi>` on EVERY invocation:
  `Missing required -PpocketAbi=<abi>; supported values: arm64-v8a, x86_64` at
  android/app/build.gradle.kts:948-951 — hit live, BUILD FAILED in 1s without it [X].
- A stale lock on `desktop\build\test-results\test\binary\output.bin` fails `:test` with
  `Unable to delete directory …` — cleared live with `gradlew.bat --stop` (daemon shutdown), after
  which the same command passed [X]. The live stage should expect this after any killed run.
- A dependency task of `:desktop:test` is the native-runtime freshness fence; it printed
  `Native runtime freshness fence PASSED for desktop pins-only (sqlite-win) (3 lockfile pin(s),
  staged bytes: not required)` during the run [X].
- **Round 3 additions (all hit live):**
  - The RP6 physical device (`serial-redacted`) was ATTACHED during the round-3 session; with two devices
    up, EVERY `adb` call must carry `ANDROID_SERIAL=emulator-5554` (a bare `adb shell` silently
    returns nothing — hit live: `boot_completed` polled empty for 5 min until the serial was added).
  - The emulator's first launch may fail snapshot load (`Failed to load snapshot 'default_boot' …
    starting from scratch. Reason: different renderer configured`) and then cold-boots fine —
    `Boot completed in 24205 ms` observed; budget one restart.
  - `findstr` with a forward-slash path silently matches nothing on this host (`findstr /c:"…"
    "$LOCALAPPDATA/PocketRealm/…"` → empty); use bash `grep -n` or backslash paths.
  - `mangosd.conf` SURVIVES the gate's clean stop, and `runtime\server\lifecycle\world.json` holds
    only the LAST operation — after `supervisorStartGate` it is the `stop` record, so the conf
    (line 48) is the durable post-run stage evidence, not the lifecycle start record.
  - The datadir on this host was already seeded (`classicmangos.sqlite3` etc. present), so gate
    runs take 20-35 s; a fresh-machine first run still owes the seeding lane (rows 09-14 budget).

## Paths the pass conditions read (all verified in code this ask)

| Surface | Path | Grounding |
|---|---|---|
| Desktop settings | `%LOCALAPPDATA%\PocketRealm\settings\settings.json` | DesktopStorageRoots.kt:42,61 [R] |
| Desktop world conf | `%LOCALAPPDATA%\PocketRealm\runtime\server\run\mangosd.conf` | ServerRuntimeFiles.kt:56-57,189 [R] |
| Desktop lifecycle record | `%LOCALAPPDATA%\PocketRealm\runtime\server\lifecycle\world.json` | ServerRuntimeFiles.kt:59,423-428 [R] |
| Desktop SQLite datadir | `%LOCALAPPDATA%\PocketRealm\database\sqlite-datadir\` | DesktopStorageRoots.kt:69; DatabaseSqliteControlPlane.kt:90 [R] |
| Stage-state meta DB (desktop) | `<datadir>\pocketrealm_meta.sqlite3`, table `timeframe_stage_state` (single row id=1: applied_stage, overlay_sha256, applied_at) | DatabaseSqliteControlPlane.kt:93-97; TimeframeOverlayPlan.kt:25-45 [R] |
| Stage-state meta DB (Android) | `<noBackupFilesDir>/database/sqlite-datadir/pocketrealm_meta.sqlite3` — the applier receives `File(databaseRoot, "sqlite-datadir")` with `databaseRoot = noBackupFilesDir/database` | StorageRoots.kt:31 [R]; WorldRuntimeService.kt:470-473 [R]; DatabaseSqliteControlPlane.kt:90,97 [R] |
| World DB (qualified lane) | `<datadir>\classicmangos.sqlite3` | DatabaseSqliteControlPlane.kt:93-94; ClientFlavor.kt:102 [R] |
| Active-provider marker | `%LOCALAPPDATA%\PocketRealm\database\active-provider.json` | DatabaseDurableState.kt:517; DesktopTimeframeOverlay.kt:95-105 reads it from `sqliteDatadir.parentFile` [R] |
| Marker JSON (producer form) | `{"schema":1,"mode":"MARIADB","provider":"mariadb-…","generationUuid":"<uuid>"}` (4 keys, schema 1) | DatabaseDurableState.kt:519-533,537-550 [R] |
| Overlay SQL pins | `schemas/timeframes/overlay-pins.json` — 12 vanilla stages; e.g. vanilla-1.10 sha256 `bc1eaf06…`; stage marker rows land at `game_event`/`conditions` entry `8000001+order` (vanilla-1.10 → 8000010, vanilla-1.12.1 → 8000012) | overlay-pins.json read [R]; vanilla-1.12.1.sql read [R]; TimeframeStage.kt:96-126 (order = list position) [R] |
| Native era consumer read path | `sConfig.GetStringDefault("PocketRealm.TimeframeStage", "")` installed at static init via `TimeframeStageProviderSlot` | native/patches/playerbots/PlayerbotLlmFilters.cpp:35-52 [R] |
| Generated era selector | `AlwaysBanTermsFor(stage)` / `SensePhrasesFor(stage)` — every registered stage branches to the single v1 set `kEraSet0_AlwaysBan()` / `kEraSet0_Senses()`, unregistered falls open to the same default | PlayerbotLlmEraStages.generated.h:151-203 [R] |
| World LLM staging artifacts (run/) | `llm_prompt_pack.json`, `llm_character_card.json`, `aiplayerbot-<id>.conf`, `aiplayerbot-disabled.conf` | ServerRuntimeFiles.kt:173,177,595-596 [R] |

Shared mechanism facts the rows lean on: the conf key is `PocketRealm.TimeframeStage` written by
both ServerRuntimeFiles twins (desktop ServerRuntimeFiles.kt:237 [R]; android ServerRuntimeFiles.kt:561 [R]);
the settings layer degrades any non-registered/unqualified value to `""` at read AND write
(desktop Settings.kt:473,608 [R]; android Settings.kt:840,1123 [R]); `confValue` always resolves to a
concrete stage (TimeframeStage.kt:223-227 [R]); all three Android start legs apply the overlay
between conf-write and native boot (WorldRuntimeService.kt:151-235,446-478 [R]); the desktop start
leg does the same via `DesktopRuntimeBackend` (DesktopRuntimeBackend.kt:2377-2441 [R]); refusals
write lifecycle op `start-failed-overlay` and throw `world start refused: timeframe overlay failed: …`
(WorldRuntimeService.kt:464-476 [R]; same copy on desktop DesktopRuntimeBackend.kt:2435 [R]).

## Checks already executed in this ask (design-time spot proofs)

| # | Command (run from `%REPO%` unless noted) | Result |
|---|---|---|
| 1 | `python tools/check_timeframes.py` | `check_timeframes: OK — vanilla: 12 stages, tbc: 5 stages, wotlk: 5 stages (registry, kotlin/native era sync, and overlay pins verified)`, RC=0 [X] |
| 2 | `python tools/generate_timeframe_era.py check` | `generate_timeframe_era check: OK (era header + overlays match their pins)`, RC=0 [X] |
| 3 | `python -m pytest tests/test_check_timeframes.py -q` | `13 passed in 0.10s`, RC=0 [X] |
| 4 | `python -m pytest tests/test_llm_truth.py -q -k era` | `5 passed, 10 deselected in 0.08s`, RC=0 [X]. **Demonstrated behavior for the cross-matrix note:** the full file without `-k era` needs the `truth_binary` fixture for 10 of its 15 tests — a bare `python -m pytest tests/test_llm_truth.py -q` would NOT reproduce the inventory's "15 passed" (campaign-inventory.md:263) without a truth-binary build step (cross-matrix note N2 below) |
| 5 | Negative harness: temp copy of `schemas/timeframes/*.json` + the Kotlin registry with `default_stage` poisoned to `vanilla-1.11`, then `python tools/check_timeframes.py --repo-root C:/pocket_realm_complete/tmp/tf-neg` | `ERROR vanilla: default_stage 'vanilla-1.11' must be the FINAL stage (highest order: 'vanilla-1.12.1')` + `check_timeframes: 1 error(s)`, RC=1; repo untouched (temp dir deleted after) [X] |
| 6 | Row 06 EXECUTED (round 2): `cd /e/pocket_realm_complete/desktop && cmd //c "gradlew.bat test --tests com.pocketrealm.database.DesktopTimeframeOverlayTest --console=plain"` | `BUILD SUCCESSFUL`; `TEST-com.pocketrealm.database.DesktopTimeframeOverlayTest.xml` → `tests="11" skipped="0" failures="0" errors="0"` [X]. (First two attempts failed for environmental reasons recorded under Operational notes: MSYS `cmd /c` mangling, then the stale `output.bin` lock cleared by `gradlew.bat --stop`.) Class-scale subset of `:desktop:test` — the full 73-test desktop suite was NOT run |
| 7 | Row 07 EXECUTED (round 2): `cmd //c "gradlew.bat test --tests com.pocketrealm.server.DesktopServerRuntimeFilesTest --tests com.pocketrealm.storage.SettingsSnapshotCodecTest --console=plain"` | `BUILD SUCCESSFUL`; DesktopServerRuntimeFilesTest `tests="34" … failures="0"`, SettingsSnapshotCodecTest `tests="18" … failures="0"` [X] |
| 8 | Row 08 EXECUTED (round 2): `cd /e/pocket_realm_complete/android && cmd //c "gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.client.TimeframeStageTest --tests com.pocketrealm.database.TimeframeOverlayPlanTest --tests com.pocketrealm.storage.TimeframeStageSettingsTest --tests com.pocketrealm.server.TimeframeOverlayWiringTest --console=plain"` | `BUILD SUCCESSFUL`; TimeframeStageTest `tests="12"`, TimeframeOverlayPlanTest `tests="6"`, TimeframeStageSettingsTest `tests="4"`, TimeframeOverlayWiringTest `tests="3"` — all `failures="0" errors="0"` (25 android JVM tests, 0 failures) [X]. Without `-PpocketAbi=x86_64` the same command FAILED at configuration (`Missing required -PpocketAbi=<abi>`, build.gradle.kts:950) [X]. Class-scale subset of the android JVM suite (1349 tests per the ledger) — NOT the full suite |
| 9 | Finding-5.22 pre-flight scan (row 22's leg B, run early to size the risk): `python` byte-presence scan of the five friend-account JNI symbol names in both staged `libpocket_world_runtime.so` files (exact command in row 22) | ALL FIVE symbols present in BOTH `native/.build-o09-x86_64/realm-staging-sqlite/jniLibs/x86_64/` and `native/.build-o09-arm64-v8a/realm-staging-sqlite/jniLibs/arm64-v8a/` [X] — finding 5.22's recorded landmine does not reproduce on the current staging on E: |
| 10 | Finding-5.24 pre-flight read (row 22's leg A): `sed -n 482,496p` and `sed -n 6012,6028p tools/build_o09_realm_runtime.py` | The staging list carries `"playerbot/PlayerbotLlmEraStages.generated.h"` (:490) and the staged-copy fix whose comment names the exact recorded failure signature (`fatal error: PlayerbotLlmEraStages.generated.h: No such file or directory`, :6018-6023) [R] — finding 5.24 is remediated on the current E: tree |
| 11 | Standing-failure capture for row 20's allowlist: `python -m pytest tests/test_gladio_client_unpack_transport.py tests/test_gladio_fixed_function.py tests/test_gladio_presentation_dimensions.py tests/test_vortek_lifecycle_hardening.py tests/test_vortek_request_handler_hardener.py tests/test_vortek_serializer_hardener.py tests/test_vortek_winlator_baseline.py -q` | `8 failed, 44 passed in 1.08s` — the 8 verbatim failing node ids are pinned in row 20's allowlist [X]. This is a bounded SUBSET of the python lane (the 7 gladio/vortek files), run to pin the allowlist; the full lane runs only in row 20 |
| 12 | Row 21's selector-check logic EXECUTED (round 2, stage hardcoded `vanilla-1.10` in place of the conf read — no live boot yet): the `python -c` command as written in row 21's mechanism step 2 | First draft CRASHED (`AttributeError: 'NoneType' object has no attribute 'group'` — `[^ ]+` cannot span the space in `SensePhrasesFor`'s return type `std::pair<std::string, bool>`); corrected to `.+? const&` → RC=0, `era-selector OK for booted stage vanilla-1.10` [X]. The truth-core consumer grep also hit (count=1) [X] |
| 13 | Audit round 2: the decisive 5.22 library split, reproduced first-hand — `python -c` byte-count of `createAccountVerifierNative` over six staged artifacts: `native/.build-{arm64,x86_64}/pocket-runtime-build/libpocketrealm.so` (vestigial) vs all four `native/.build-o09-*/realm-staging{,-sqlite}/jniLibs/<abi>/libpocket_world_runtime.so` (the binding library, WorldNative.kt:4 [R]); plus `"C:/Users/<owner>/AppData/Local/Android/Sdk/emulator/emulator.exe" -list-avds` | `libpocketrealm.so`: count=0 on BOTH ABIs (sizes ~497 MB / ~481 MB); `libpocket_world_runtime.so`: count=1 on ALL FOUR copies (sizes ~33-35 MB); `-list-avds` → `PocketDiff_x86_64`, `PocketRealm_API28`, RC=0 [X]. Recorded in row 22 leg B and cross-matrix note N4 |

## Live executions (round 3) — the desktop lane ran for real

Every run used the exact mechanism its row prescribes (settings via the `python` JSON rewrite;
gates as `cmd //c "gradlew.bat … --console=plain"`; logs under `C:\pocket_realm_complete\tmp\`).
Pre-run state snapshot: datadir already seeded; ledger `('vanilla-1.12.1','default-seed',1790869114539)`;
zero marker rows; settings had NO `timeframeStage` key. (The settings file carries a live LLM API
key on this host — never quote it into any artifact.)

| Seq | Row mechanism run | Result (verbatim evidence) |
|---|---|---|
| L1 | Row 09: settings `timeframeStage=''` → `gradlew.bat supervisorStartGate` | `SUPERVISOR START GATE PASSED`, `BUILD SUCCESSFUL in 35s`, RC=0; log: `supervisor start ok=true detail=native realm ready`, `supervisor world state=READY … activeSessions=0`, `supervisor stop ok=true detail=clean stop complete phase=STOPPED`; meta `('vanilla-1.12.1','default-seed',1790869114539)` — applied_at UNCHANGED → the idempotent short-circuit fired on a real start; conf line 48 `PocketRealm.TimeframeStage = "vanilla-1.12.1"`; markers `0`/`0` [X] |
| L2 | Row 10: settings `vanilla-1.10` → gate | `SUPERVISOR START GATE PASSED` (21s); meta `('vanilla-1.10','bc1eaf06625385f3987bec22c5f83fca2e5930320a98937d1517a513cadcc282',1790908912043)` — sha byte-equal to the recomputed `vanilla-1.10.sql` hash; markers `game_event (8000010,'PocketRealm stage marker: vanilla-1.10')` + `conditions (8000010,12,'PocketRealm stage vanilla-1.10 active')`; conf line 48 `… = "vanilla-1.10"` [X] |
| L3 | Row 23: gate again, NO settings change | `SUPERVISOR START GATE PASSED`; meta triple IDENTICAL incl. `applied_at=1790908912043` — no re-execution [X] |
| L4 | Row 21: conf-driven stage read → selector check → truth-core grep → run/ artifacts | conf grep `1`; `era-selector OK for booted stage vanilla-1.10` RC=0; `erastages::AlwaysBanTermsFor(ActiveTimeframeStage())` count=1; `llm_prompt_pack.json` + `llm_character_card.json` + `aiplayerbot-disabled.conf` all present in run/ [X] |
| L5 | Row 18: `gradlew.bat whisperGate` at vanilla-1.10 | `world READY; exercising the bridge surface`, `onlinePlayers: 0 (honest without a client)`, `offline whisper refused: sender-not-online (the documented posture)`, `unknown channel refused: unknown-channel`, `BRIDGE GATE PASSED`; meta/conf stayed `vanilla-1.10` [X] |
| L6 | Row 11: settings `''` → gate | `SUPERVISOR START GATE PASSED`; meta `('vanilla-1.12.1','default-seed',1790909054189)`; the vanilla-1.10 markers PERSIST `(1,) (1,)` — nothing destroyed [X] |
| L7 | Row 12 leg A: settings `tbc-2.4.3` → gate | `SUPERVISOR START GATE PASSED`; conf `… = "vanilla-1.12.1"` (read-time degrade carried the world); meta stayed default; **raw value REMAINED on disk** (`'tbc-2.4.3'`) — the gate only READS settings; the write-time degrade is codec-proven (row 07) and android-JVM-proven (row 08 leg `anImpossibleStageNeverReachesThePersistedEncoding`), not gate-observable [X] |
| L8 | Row 12 leg B: settings `vanilla-9.9.9` → gate → restore `''` | `SUPERVISOR START GATE PASSED`; conf `… = "vanilla-1.12.1"`; meta default; settings restored [X] |
| L9 | Row 13: settings `vanilla-1.10`, RENAME `classicmangos.sqlite3` away → gate → restore | `SUPERVISOR START GATE FAILED (start leg)`, java exit 2 — fail-closed, world never booted; **the refusing layer was the EARLIER db-start gate**: `start FAILED: DB-NOT-SEEDED: partially seeded datadir — classicmangos missing under …\sqlite-datadir; delete … and relaunch to re-seed from the packaged transcripts, or run gradlew seedRealmData from desktop/` (DESK-53's distinct refusal code); the overlay's own missing-DB refusal is unreachable through the supervisor (the JVM proof stands: row 06 `missingWorldDatabaseRefusesLoudly` [X]); restored → next gate PASSED [X] |
| L10 | Row 14: settings `vanilla-1.10` + hand-written `active-provider.json` (MARIADB, uuid) → gate → cleanup | `SUPERVISOR START GATE FAILED (start leg)` — fail-closed; **refusing layer: `start FAILED: REALM: IllegalStateException: DB-LINK: the active-provider marker names MARIADB but the pinned provider distribution is not staged; stage it (or clear the marker)`** — the REALM component's provider-aware gate (DESK-78 posture) fires BEFORE the overlay leg; the overlay's `MARIADB provider lane has no overlay leg in v1` guard is live-reachable only once the R5 distribution is staged (with it, DB-LINK passes and the overlay guard fires); JVM proofs stand (row 06, both MARIADB cases [X]); marker deleted, settings restored → final gate `SUPERVISOR START GATE PASSED`, meta `('vanilla-1.12.1','default-seed',1790909054189)` (short-circuit again) [X] |
| L11 | Row 16 step 1: `emulator.exe -avd PocketDiff_x86_64 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save` → serial-forced probe → `emu kill` | snapshot load failed once (`Failed to load snapshot 'default_boot' … different renderer configured`) → cold boot `Boot completed in 24205 ms`; `ANDROID_SERIAL=emulator-5554 adb shell getprop sys.boot_completed` → `1`; `ro.build.version.sdk` → `35`; `adb emu kill` → `OK: killing emulator` [X]. Steps 2+ (connectedDebugAndroidTest) NOT run in design — that is the live stage's row |
| L12 | Row 20: `python tools/check_repo.py` | `check_repo: OK (1307 tracked files, 0 errors, 0 warnings)`, RC=0; zero `ERROR timeframes:` lines; **zero `FAILED` lines of any kind** — the aggregate's own lane does not run the gladio/vortek pytest files, so the row-20 allowlist comparison applies to a FULL pytest-lane run, not to this aggregate's log (rule refined in the row) [X] |

## Live executions (round 4)

| Seq | Row mechanism run | Result (verbatim evidence) |
|---|---|---|
| L13 | Row 03 variant B, twice: temp-copy manifest with `vanilla-1.10` renamed (a) `vanilla-1.10.5` (well-formed) then (b) `Vanilla_1.10` (malformed), each `python tools/check_timeframes.py --repo-root <neg>` | (a) **RC=0 — the structural gate does NOT catch a well-formed rename** (`check_timeframes: OK …`): it matches STAGE_ID_RE and `--repo-root` mode skips the Kotlin-sync leg; (b) RC=1 with `ERROR vanilla: bad stage id 'Vanilla_1.10' (canonical <flavor>-<patch>)` + `ERROR vanilla: orders must be contiguous 0..11, found [0,1,…,10,11]`; temp dirs deleted, repo untouched [X]. Row 03's variant corrected accordingly |
| L14 | Row 16 driver attempt: `ANDROID_SERIAL=emulator-5554 python tools/run_baseline_world_sanity.py --skip-build --only b` (reusing the newest complete capture `build/differential/20260825-105240-standard/`) | Driver functioned through boot → install → `adb root` staging → instrument launch → log/evidence plumbing → emulator cleanup (`finally`), then FAILED at the evidence read: `run-as … cat files/baseline-world-sanity.json` exit 1 because the instrument reported `Error in initializationError(…BaselineWorldSanityRunner): … ClassNotFoundException: com.pocketrealm.database.BaselineWorldSanityRunner`. Root cause pinned [X]: BOTH August tests APKs carry ZERO `BaselineWorldSanityRunner` hits across all 12 dex files (python zipfile count) — every prior capture predates the runner class. Consequence: `--skip-build` is DEAD for this runner; row 16 needs the driver's build path (precondition N5). The RP6 was untouched throughout (ANDROID_SERIAL scoping) and the emulator was cleanly killed |

**Document-vs-repo discrepancy found while grounding (reported, not fixed):** campaign-inventory
TF-8 (campaign-inventory.md:263) names `tools/validate_timeframes.py`, `tests/test_timeframe_manifests.py`,
and `tests/test_timeframe_era_linkage.py`. On E: these do not exist: `ls tools | grep -i timeframe`
→ only `check_timeframes.py`, `generate_timeframe_era.py` [X]; `find -name "test_timeframe_manifests.py" -o
-name "test_timeframe_era_linkage.py"` → no hits [X]. The `validate_timeframes` role is filled by
`tools/check_timeframes.py` (its docstring, tools/check_timeframes.py:2-47 [R]); the manifest/era-linkage
assertions live inside test_check_timeframes.py and the JVM suites. The rows below test what exists.

## Conventions for all rows

- `%REPO%` = `E:\pocket_realm_complete`. Desktop gradle: `cd /d %REPO%\desktop && gradlew …`
  (standalone project `pocketrealm-desktop`, desktop/settings.gradle.kts:16 [R]; matches
  environment.md:24-27). Android gradle: `cd /d %REPO%\android && gradlew …` — **every android
  invocation must carry `-PpocketAbi=x86_64` (emulator lane)** (build.gradle.kts:948-951, hit live [X]).
  From a Git-Bash driver wrap as `cmd //c "gradlew.bat …"` (Operational notes).
- Python DB inspections use the stdlib: `python -c "import sqlite3; …"` (no deps).
- "Gate" = `gradlew supervisorStartGate` — drives the full db+realm+world cycle through the
  production supervisor and exits 0 only on the whole cycle (SupervisorStartGate.kt:16-31,60 [R];
  exit codes 2/3/4/5 name the failed leg).
- Settings pre-staging is a `python` JSON rewrite of `settings.json` (the desktop store is a plain
  JSON file — DesktopSettingsStore.kt:11,40-60 [R]); the app rewrites/degrades it on its next save.
- `NEGTMP` = a scratch dir OUTSIDE the repo (e.g. `%TEMP%\tf-live`); nothing in `%REPO%` is mutated
  except the documented PocketRealm app-data state and the settings file.
- Row 22 is a PRE-FLIGHT: rows 16/17 must not start until it has produced a verdict in the same run.

## Matrix

| testId | feature | flavor(s) | exact CLI mechanism | required artifacts | pass condition | severity |
|---|---|---|---|---|---|---|
| LV-timeframe-era-01 | TF-1/TF-6/TF-8 — three-way stage registry sync gate (manifests ↔ Kotlin registry ↔ native era baseline + overlay pins) | all 3 | `cd /d %REPO% && python tools\check_timeframes.py` — RUN THIS ASK [X], spot-proof 1 | none | RC=0; stdout names `vanilla: 12 stages, tbc: 5 stages, wotlk: 5 stages` and `overlay pins verified`; zero `ERROR` lines | P1 |
| LV-timeframe-era-02 | TF-6/TF-8 — generated-era byte-determinism chain (`PlayerbotLlmEraStages.generated.h` + pin; overlays + catalog pin) | all 3 | `cd /d %REPO% && python tools\generate_timeframe_era.py check` — RUN THIS ASK [X], spot-proof 2 | none | RC=0; `era header + overlays match their pins` (byte-identical re-render, no writes) | P1 |
| LV-timeframe-era-03 | TF-5 — fail-loud negative of the sync gate (poisoned manifest must be rejected, repo untouched) | vanilla | `mkdir %NEGTMP%; xcopy %REPO%\schemas\timeframes %NEGTMP%\schemas\timeframes\ /E/I && xcopy /E/I %REPO%\android\app\src\main\java\com\pocketrealm\client %NEGTMP%\android\app\src\main\java\com\pocketrealm\client\` → python-poison `default_stage` (or an order gap) in `%NEGTMP%\schemas\timeframes\vanilla.json` → `cd /d %REPO% && python tools\check_timeframes.py --repo-root %NEGTMP%` — BOTH variants EXECUTED (round 1: default_stage poison; round 4: stage-id poison) | none | RC=1; `ERROR …` names the poisoned field verbatim. ROUND-4 CORRECTION (executed [X]): a WELL-FORMED rename (`vanilla-1.10` → `vanilla-1.10.5`) ESCAPES this gate (RC=0 — it matches STAGE_ID_RE and the `--repo-root` mode skips the Kotlin-sync leg that would catch it in checkout mode); the structural gate catches only a MALFORMED id — verified `Vanilla_1.10` → RC=1 with `bad stage id 'Vanilla_1.10' (canonical <flavor>-<patch>)` + the derived contiguous-orders error. So the variant to schedule here is a MALFORMED id; well-formed drift is caught by rows 01/02's checkout-mode sync legs; `%REPO%` files byte-unchanged in all cases | P1 |
| LV-timeframe-era-04 | TF-8 — python gate test suite | all 3 | `cd /d %REPO% && python -m pytest tests\test_check_timeframes.py -q` — RUN THIS ASK [X]: `13 passed` | none | 13 passed, 0 failed (matches the ledger-recorded run, campaign-inventory.md:263) | P2 |
| LV-timeframe-era-05 | TF-6 — era-policy pins in the LLM truth suite (§5: generated header matches its pin; stage-era wiring) | vanilla | `cd /d %REPO% && python -m pytest tests\test_llm_truth.py -q -k era` — RUN THIS ASK [X]: `5 passed, 10 deselected`. The FULL-file invocation is a DIFFERENT test with a build precondition: cross-matrix note N2 (the `truth_binary` fixture) — do not schedule it as artifact-free | none (subset) / in-repo truth binary build (full file — precondition of note N2, not of this row) | `-k era`: 5 passed. Full file: all 15 pass incl. `test_generated_era_header_matches_its_pin` (tests/test_llm_truth.py:187 [R]) — only after the N2 precondition lands | P2 |
| LV-timeframe-era-06 | TF-2/TF-5 — desktop applier JVM contract: default records without touching world DB; non-default applies+records; idempotent reapply; backward move destroys nothing; destructive-statement refusal; missing-world-DB refusal; unstaged-stage refusal; MARIADB-marker refusal incl. pre-existing-ledger bypass attempt; meta-quote escape | vanilla | `cd /d %REPO%\desktop && gradlew test --tests "com.pocketrealm.database.DesktopTimeframeOverlayTest"` — **EXECUTED THIS ASK [X]** (spot-proof 6; run verbatim as `cmd //c "gradlew.bat test --tests com.pocketrealm.database.DesktopTimeframeOverlayTest --console=plain"`) | none | **PASSED THIS ASK**: 11 tests, 0 failures, 0 errors (XML `tests="11" skipped="0" failures="0" errors="0"`). Re-run at the live stage must reproduce 11/0/0/0 | P1 |
| LV-timeframe-era-07 | TF-4 — desktop golden conf pin + non-default-stage conf emission + settings degrade codec (test legs read at DesktopServerRuntimeFilesTest.kt:204-208 golden `vanilla-1.12.1`, :381-404 `worldConfigCarriesTheChosenTimeframeStage` incl. `vanilla-1.5`, SettingsSnapshotCodecTest degrade legs) | vanilla | `cd /d %REPO%\desktop && gradlew test --tests "com.pocketrealm.server.DesktopServerRuntimeFilesTest" --tests "com.pocketrealm.storage.SettingsSnapshotCodecTest"` — **EXECUTED THIS ASK [X]** (spot-proof 7) | none | **PASSED THIS ASK**: 34 + 18 tests, 0 failures. Re-run at the live stage must reproduce 34/0 and 18/0 | P2 |
| LV-timeframe-era-08 | TF-1/TF-5 — Android JVM contract: 22-stage registry, requireForFlavor throws for unknown/cross-flavor/malformed, resolveStored degrade, era baseline byte-equivalence, plan statement policy, wiring of all three start legs, settings persistence (`TimeframeStageSettingsTest` legs :28-76 incl. `theFinalPatchPersistsVerbatimAndResolvesIdenticallyToTheEmptyDefault` :45 and `anImpossibleStageNeverReachesThePersistedEncoding` :76 [R]) | all 3 | `cd /d %REPO%\android && gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 --tests "com.pocketrealm.client.TimeframeStageTest" --tests "com.pocketrealm.database.TimeframeOverlayPlanTest" --tests "com.pocketrealm.storage.TimeframeStageSettingsTest" --tests "com.pocketrealm.server.TimeframeOverlayWiringTest"` — **EXECUTED THIS ASK [X]** (spot-proof 8; `-PpocketAbi=x86_64` is REQUIRED — without it configuration fails at build.gradle.kts:950 [X]) | none | **PASSED THIS ASK**: 12+6+4+3 = 25 tests, 0 failures. Re-run at the live stage must reproduce 25/0 | P1 |
| LV-timeframe-era-09 | TF-3/TF-4/TF-7 — desktop live world start at DEFAULT stage: conf emission, zero world-DB SQL, honest ledger | vanilla | 1) ensure default: `python -c "import json,os;p=os.path.expandvars(r'%LOCALAPPDATA%\PocketRealm\settings\settings.json');d=json.load(open(p));d['timeframeStage']='';json.dump(d,open(p,'w'))"` (absent key is also valid — default `""` desktop Settings.kt:365 [R]) 2) `cd /d %REPO%\desktop && gradlew supervisorStartGate --console=plain > %NEGTMP%\gate09.log 2>&1` 3) inspect: `python -c "import sqlite3,os;c=sqlite3.connect(os.path.expandvars(r'%LOCALAPPDATA%\PocketRealm\database\sqlite-datadir\pocketrealm_meta.sqlite3'));print(c.execute('select applied_stage,overlay_sha256 from timeframe_stage_state').fetchall())"`; `grep -n "PocketRealm.TimeframeStage" "%LOCALAPPDATA%\PocketRealm\runtime\server\run\mangosd.conf"`; marker-count probe on `classicmangos.sqlite3`: `select count(*) from game_event where entry>=8000001` — **EXECUTED AND PASSED THIS ASK (round 3, L1)** | none | gate log ends `SUPERVISOR START GATE PASSED` (RC=0) **[X]**; meta row = `('vanilla-1.12.1','default-seed', …)` **[X]**; conf carries `PocketRealm.TimeframeStage = "vanilla-1.12.1"` **[X]**; marker count = 0 **[X]** (DEFAULT executes zero world-DB SQL, TF-7). ROUND-3 REFINEMENTS: (a) the conf survives the gate's clean stop and is the durable stage evidence — `lifecycle\world.json` holds only the LAST op (post-gate it is the `stop` record; the `start … stage=…` detail of DesktopRuntimeBackend.kt:2441 is transient), (b) use bash grep/backslash paths for the conf (forward-slash findstr matches nothing), (c) an applied_at UNCHANGED from the prior run is EXPECTED (short-circuit, row 23) | P1 |
| LV-timeframe-era-10 | TF-2 — desktop live BACKWARD move to a non-default stage: overlay applied from pins, ledger updated, destructive-free | vanilla | 1) `python -c "…;d['timeframeStage']='vanilla-1.10';…"` (same settings rewrite) 2) `gradlew supervisorStartGate … > %NEGTMP%\gate10.log 2>&1` 3) re-query meta — RECORD the full triple `(applied_stage, overlay_sha256, applied_at)` as T1 for row 23; `select entry from game_event where entry>=8000001` and `select condition_entry,type from conditions where condition_entry>=8000001` on `classicmangos.sqlite3` — **EXECUTED AND PASSED THIS ASK (round 3, L2)** | none | gate PASSED **[X]**; meta = `('vanilla-1.10','bc1eaf06625385f3987bec22c5f83fca2e5930320a98937d1517a513cadcc282',T1)` — sha byte-equal to the recomputed overlay-file hash **[X]**; conf names `vanilla-1.10` **[X]**; exactly one marker event `(8000010,'PocketRealm stage marker: vanilla-1.10')` and one marker condition `(8000010, 12, 'PocketRealm stage vanilla-1.10 active')` exist **[X]**; no DELETE/DROP ever ran (seed game_event row count unchanged vs run 09 baseline) | P1 |
| LV-timeframe-era-11 | TF-2 — desktop live FORWARD move back to DEFAULT: re-record with `default-seed`, overlays never destroyed | vanilla | 1) settings `timeframeStage=''` 2) `gradlew supervisorStartGate … > gate11.log` 3) re-query meta + both marker tables — **EXECUTED AND PASSED THIS ASK (round 3, L6)** | none | gate PASSED **[X]**; meta = `('vanilla-1.12.1','default-seed',T2)` (freshly re-recorded) **[X]**; the vanilla-1.10 marker rows from run 10 STILL PRESENT `(1,) (1,)` **[X]** (INSERT-OR-IGNORE-only posture — nothing may be destroyed, DesktopTimeframeOverlay.kt:18-23 [R]) | P1 |
| LV-timeframe-era-12 | TF-5/TF-4 — wrong-lane degrade at the LIVE settings layer (the "wrong client" analog for this area): cross-flavor AND malformed stage values can never reach conf/overlay | tbc, vanilla | for each VALUE in `tbc-2.4.3` (registered but unqualified flavor) and `vanilla-9.9.9` (malformed/unregistered): 1) `python -c "…;d['timeframeStage']=VALUE;…"` 2) `gradlew supervisorStartGate … > gate12-<VALUE>.log` 3) re-read settings.json + conf + meta | none | gate PASSED both times **[X, round 3 L7/L8]**; conf always `PocketRealm.TimeframeStage = "vanilla-1.12.1"` (degrade at read, desktop Settings.kt:473 [R]; confValue fallback TimeframeStage.kt:223-227 [R]) **[X]**; meta stays `vanilla-1.12.1` **[X]**. ROUND-3 REFINEMENT (observed): the raw VALUE REMAINS in settings.json after a gate run — the gate only READS settings, so the degrade-at-write (Settings.kt:608 [R]) is observable only on an app settings save; that half stays codec-proven (row 07's SettingsSnapshotCodecTest 18/0 [X]) and android-JVM-proven (row 08's `anImpossibleStageNeverReachesThePersistedEncoding` [X]) — do not assert on-disk disappearance through the gate | P1 |
| LV-timeframe-era-13 | TF-3 — fail-closed live: missing world DB refuses the start loudly (never boot a world whose conf names a stage the DB never reached) | vanilla | precondition: a non-default stage is legal again — 1) settings `timeframeStage='vanilla-1.10'` 2) `ren "%LOCALAPPDATA%\PocketRealm\database\sqlite-datadir\classicmangos.sqlite3" classicmangos.sqlite3.bak` 3) `gradlew supervisorStartGate … > gate13.log`; capture RC 4) `type …\lifecycle\world.json` 5) restore: `ren …classicmangos.sqlite3.bak classicmangos.sqlite3` | none | gate exits non-zero and the world NEVER boots — **EXECUTED THIS ASK (round 3, L9) with a layering discovery: the refusing layer is the EARLIER db-start gate, verbatim `start FAILED: DB-NOT-SEEDED: partially seeded datadir — classicmangos missing under …\sqlite-datadir; delete … and relaunch to re-seed from the packaged transcripts, or run gradlew seedRealmData from desktop/`** (exit value 2; DESK-53's distinct refusal code, campaign-inventory.md:74 [R]) — the supervisor's DB proof fires BEFORE the world/overlay leg, so the overlay's own missing-DB refusal (`world start refused: timeframe overlay failed: … the world database is missing`, DesktopTimeframeOverlay.kt:122-128 [R]) is supervisor-UNREACHABLE for this condition and stays JVM-proven (row 06 `missingWorldDatabaseRefusesLoudly` [X]). Post-restore a gate run PASSED (no partial state) **[X]** | P1 |
| LV-timeframe-era-14 | TF-2 — fail-closed live: MARIADB active-provider marker refuses ANY non-default stage, and a pre-existing stage ledger cannot bypass the guard | vanilla | 1) `python -c "…;d['timeframeStage']='vanilla-1.10';…"` 2) `python -c "import json,os;open(os.path.expandvars(r'%LOCALAPPDATA%\PocketRealm\database\active-provider.json'),'w').write(json.dumps({'schema':1,'mode':'MARIADB','provider':'mariadb-11.5.2-windows-x86_64','generationUuid':'3f2b8c4a-1d5e-4f6a-9b7c-2e8d1a0b5c3e'}))"` (exact producer form, DatabaseDurableState.kt:519-533 [R]; the guard reads the marker BEFORE any short-circuit, DesktopTimeframeOverlay.kt:67-78,95-105 [R]) 3) `gradlew supervisorStartGate … > gate14.log` 4) refusal-leg B (ledger bypass): with the marker still present, run the gate once at default (records vanilla-1.12.1), flip settings back to `vanilla-1.10` — the ledger already names vanilla-1.10 from row 10's state only if retained; the JVM twin of this leg is DesktopTimeframeOverlayTest `aPreexistingLedgerCannotBypassTheMariadbLaneGuard` (:254 [R], part of the row-06 class PASSED this ask [X]) 5) cleanup: `del …active-provider.json`; restore settings `''` | none (the guard test needs only the marker FILE, not real mariadbd — the marker is the lane's durable cache, DatabaseDurableState.kt:514-517 [R]; a REAL MariaDB-provider world overlay attempt belongs to the mariadb area and needs R5) | gate exits non-zero and the world NEVER boots — **EXECUTED THIS ASK (round 3, L10) with a layering discovery: the refusing layer is the REALM component's provider gate, verbatim `start FAILED: REALM: IllegalStateException: DB-LINK: the active-provider marker names MARIADB but the pinned provider distribution is not staged; stage it (or clear the marker)`** — the DESK-78 dual-provider posture fires BEFORE the world/overlay leg, so the overlay's `timeframe overlay: the MARIADB provider lane has no overlay leg in v1` (DesktopTimeframeOverlay.kt:101-104 [R]) is live-reachable only ONCE the R5 distribution is staged (DB-LINK then passes and the overlay guard is what fires for a non-default stage); the JVM proofs stand (row 06: `aMariadbMarkerRefusesAnyNonDefaultStage` + `aPreexistingLedgerCannotBypassTheMariadbLaneGuard` [X]) — leg B's ledger-bypass remains JVM-side for the same reachability reason. After cleanup (marker deleted, settings `''`) a gate run PASSED with meta `('vanilla-1.12.1','default-seed', …)` — full recovery **[X]** | P1 |
| LV-timeframe-era-15 | TF-5 — unstaged tbc/wotlk overlay refusal (v1 = qualified lane only) | tbc, wotlk | The LIVE settings path is PROVABLY unreachable for tbc/wotlk stages (row 12's degrade is the guard), so the honest mechanism is the JVM seam + a static staging-set proof: 1) row 06's class (EXECUTED [X]) carries `anUnstagedStageRefusesLoudly` (:213) and `missingWorldDatabaseRefusesLoudly` (:198) [R] 2) `python -c "import json;d=json.load(open(r'%REPO%\schemas\timeframes\overlay-pins.json'));ks=sorted(d['overlays']);print(len(ks),ks)"` 3) `findstr /c:"tbc-" %REPO%\android\app\src\main\java\com\pocketrealm\client\GeneratedTimeframeOverlayCatalog.kt` (expect: no match) | none | JVM case green (in the row-06 run this ask [X]); pins list = exactly the 12 `vanilla-*` ids, zero tbc/wotlk entries; catalog carries no tbc stage key; refusal copy names the staged set (`timeframe overlay: no overlay staged for "tbc-2.4.3" (staged stages: …)`, TimeframeOverlayPlan.kt:93-98 [R]) | P2 |
| LV-timeframe-era-16 | TF-9/TF-2/TF-3 — Android DEVICE leg, default stage: real stack boot on the emulator through the production binder services, then on-device meta-ledger proof. **Emulator-lane-only per the owner ruling above; PRE-FLIGHT: row 22 must have passed leg A + leg B in the same run; PRECONDITION N5: a FRESH APK capture (see cross-matrix notes)** | vanilla | **The sanctioned mechanism is the repo's own driver** (round-4 discovery — it supersedes any hand-rolled sequence): `cd /d %REPO% && ANDROID_SERIAL=emulator-5554 python tools\run_baseline_world_sanity.py --only b` — the driver boots its own emulator from the SDK constant (`-no-snapshot -accel on -memory 4096`, run_differential_parity.py:38,43,128-132 [R]; `ANDROID_SERIAL` is REQUIRED while the RP6 is attached — the driver's `adb()` is serial-blind, :86-88 [R]), builds the x86_64 APKs (`-PpocketAbi=x86_64 -PpocketLane=full -PsqliteProvider -PdifferentialTestLane`, :109-124 [R]; ~30-min gradle assemblies — this is precondition N5), stages the in-repo data pack via `adb root` (run_baseline_world_sanity.py:42-72 [R]), installs, instruments `am instrument -w -e class com.pocketrealm.database.BaselineWorldSanityRunner` (:86-88 — the runner binds the REAL services and starts the world via `world!!.api.start()`, :95,180 [R], the binder entry into the start legs that call `applyStageOverlayOrRefuse`, WorldRuntimeService.kt:446-478 [R]), pulls the evidence JSON + component logs (:89-106), kills the emulator (:141-149). THEN the timeframe-specific proof on the still-installed app: `ANDROID_SERIAL=emulator-5554 adb exec-out run-as com.pocketrealm cat no_backup/database/sqlite-datadir/pocketrealm_meta.sqlite3 > %NEGTMP%\meta.sqlite3` (pinned path per StorageRoots.kt:31 + WorldRuntimeService.kt:470-473 + DatabaseSqliteControlPlane.kt:90,97 [R]; discovery fallback: `run-as com.pocketrealm find ./no_backup -name pocketrealm_meta.sqlite3`) → `python -c "import sqlite3;c=sqlite3.connect(r'%NEGTMP%\meta.sqlite3');print(c.execute('select applied_stage,overlay_sha256 from timeframe_stage_state').fetchall())"` — **step-mechanisms VALIDATED: round 3 L11 (boot/probe/kill) and round 4 L14 (driver end-to-end except the instrument itself — stale capture, N5)** | none (data pack + staged x86_64 natives are in-repo; the fresh APK capture is a repo BUILD ACTION per N5, not a user artifact) | the driver exits 0 with `[b] provider=SQLITE dbBootMs=… worldBootMs=… accounts=…` (its `OK (1 test)` instrument check, run_baseline_world_sanity.py:107-108,160-163 [R]) — steps 2-4 NOT YET COMPLETED (round 4 attempt died on the stale capture, L14); then the meta pull = `('vanilla-1.12.1','default-seed')` — the device SQLite lane recorded the default stage with zero world-DB SQL, on the real applier path (the exact leg campaign-inventory.md:279 records as never run) | P1 |
| LV-timeframe-era-17 | TF-9 — Android DEVICE leg, NON-default stage (overlay SQL executed via `android.database.sqlite.SQLiteDatabase`) | vanilla | **OUT-OF-SCOPE-FOR-ROUND-1 — BLOCKED, TRACKED (not artifact-gated; see the tracked pre-condition below).** When the pre-condition lands, the mechanism is: 1) stage the desired stage via the new test class' own arrange step (a `pocket_settings.preferences_pb` DataStore pre-stage via `adb push` + `run-as` is the fragile alternative — binary-protobuf rewrite, not designed here) 2) `ANDROID_SERIAL=emulator-5554 gradlew :app:connectedDebugAndroidTest -PpocketAbi=x86_64 -Pandroid.testInstrumentationRunnerArguments.class=com.pocketrealm.database.TimeframeOverlayApplierInstrumentedTest` (name per the tracked pre-condition) 3) the row-16 meta pull; expect applied_stage=`vanilla-<chosen>` + the overlay's pinned sha256; marker rows `8000001+order` present in the on-device `classicmangos.sqlite3` | none | (deferred — cannot run in round 1 by construction) | P2 |
| LV-timeframe-era-18 | TF-3/TF-6 — interaction: stage resolution under the live world + chat-injection gate (whisperGate boots DATABASE→REALM→WORLD itself through the overlay-bearing start path, then probes the bridge surface) | vanilla | 1) settings `timeframeStage='vanilla-1.10'` 2) `cd /d %REPO%\desktop && gradlew whisperGate --console=plain > %NEGTMP%\wg18.log 2>&1` (boot path: `backend.start(DATABASE/REALM/WORLD)` — the same `DesktopRuntimeBackend` world start that runs conf→overlay→native boot, DesktopRuntimeBackend.kt:2377-2441 [R]; probes: onlinePlayers==0, offline whisper → `sender-not-online`, bogus channel → `unknown-channel`, `llmMemoryState` honest JSON — WhisperGate.kt:88-115 [R]) 3) re-query meta + conf + lifecycle as in row 10 — **EXECUTED AND PASSED THIS ASK (round 3, L5)** | none | exit 0 AND log contains `world READY; exercising the bridge surface`, the three probe lines, and `BRIDGE GATE PASSED` — **ALL OBSERVED VERBATIM [X]** (`onlinePlayers: 0 (honest without a client)`; `offline whisper refused: sender-not-online (the documented posture)`; `unknown channel refused: unknown-channel`); meta = `('vanilla-1.10', <pinned sha>, T3)` and conf name `vanilla-1.10` **[X]**. Failure exits (unexercised): 2=PREFLIGHT FAILED, 3=WORLD START REFUSED / WORLD NEVER REACHED READY, 4=BRIDGE CHECK FAILED, 5+=CLEAN STOP FAILED, WhisperGate.kt:46-85,128-130 [R] | P2 |
| LV-timeframe-era-19 | TF-6 — era-policy LIVE chat linkage: world chat at an early stage uses that stage's era class (instruction line + token bias), never-ban discipline holds. **The ONLY full-loop (settings stage ⇒ observable chat-era behavior) proof — requires R6; every artifact-free observable of the same interaction is row 21** | vanilla | LATER STAGE — needs artifacts: 1) build the in-repo lane: `cd /d %REPO% && python tools\build_win_llama_server.py` (llama-staging absent — a build action, not a user download, requirements.md §3 [R]) 2) place user GGUF at `%LOCALAPPDATA%\PocketRealm\runtime\llm\models\<fileName>` (R6; sha256-verified by DesktopLlmModelStaging.kt:73-79 per requirements.md R6 [R]) 3) `adb reverse tcp:8080 tcp:8080` (guest LLM lane; environment.md:30-31,64 [R]) 4) settings `timeframeStage='vanilla-1.1'` + `gradlew bootWorld` (hold) + drive a whisper turn through the whisperGate surface; capture the llama-server request (prompt seasoning line + `EraBiasJson` terms) from the llama-server log/8080 traffic 5) negative: a later-era sense input (`portals are open`) must be filtered; context words (`northrend`, `outland`) must NEVER be banned (the never-ban lists, TimeframeStage.kt:59-69,239-277 [R]) | **R6** (tuned GGUF ×2, user-supplied, url="" in the registry — requirements.md:153-166 [R]) | llama request payload carries the vanilla-baseline era instruction line for `vanilla-1.1` (= the 1.12.1 baseline lists, the v1 posture TimeframeStage.kt:284-296 [R]); banned-sense input produces no later-era assertion in the reply; context-word inputs are never refused; `curl http://127.0.0.1:8080/health` 200 while the lane is up. CAVEAT carried from WhisperGate.kt:16-19 [R]: the conversational half "needs a real player session — that half is interactive by design"; the injection-surface half is CLI-drivable, the full conversational loop may need the desktop client (launch-boundary area's artifact) | P2 |
| LV-timeframe-era-20 | TF-8 — aggregate repo gate: the timeframe leg must be green; standing non-timeframe failures are allowlisted | all 3 | `cd /d %REPO% && python tools\check_repo.py > %NEGTMP%\checkrepo.log 2>&1`; capture RC (wiring read: tools/check_repo.py:50-57 imports `check_timeframes.validate()` at :329 [R]) — **EXECUTED THIS ASK (round 3, L12): `check_repo: OK (1307 tracked files, 0 errors, 0 warnings)`, RC=0, in minutes.** **Verdict rule (b), fixed per audit round 1: overall RC is explicitly ALLOWED non-zero; the row's pass condition is zero timeframe-attributable failure lines plus zero allowlist drift.** ROUND-3 REFINEMENT (observed): the aggregate's own python lane does NOT run the gladio/vortek pytest files — its log contains zero `FAILED` lines of any kind — so the 8-node allowlist comparison applies to a FULL pytest-lane run (`python -m pytest tests -q`), not to the aggregate's log; when the executor runs only the aggregate, the pass condition reduces to clause (1) plus RC recorded Expected-failure allowlist (verbatim node ids, captured in round 1 [X], spot-proof 11; matches the recorded baseline "gladio ×2, vortek ×6", campaign-inventory.md:289): `tests/test_gladio_client_unpack_transport.py::GladioClientValidationLaneContractTest::test_arm_production_packaging_excludes_gladio`; `tests/test_gladio_client_unpack_transport.py::GladioClientValidationLaneContractTest::test_build_script_is_x86_validation_only`; `tests/test_vortek_lifecycle_hardening.py::test_java_control_plane_rejects_ambiguous_ownership_and_unknown_requests`; `tests/test_vortek_lifecycle_hardening.py::test_production_dispatch_opens_only_in_reviewed_bridge_target`; `tests/test_vortek_lifecycle_hardening.py::test_window_authority_is_generation_instance_and_lifetime_bound`; `tests/test_vortek_lifecycle_hardening.py::test_window_operations_revalidate_and_swapchain_creation_is_transactional`; `tests/test_vortek_winlator_baseline.py::test_source_built_guest_is_pinned_and_reports_receiver_map_errno`; `tests/test_vortek_winlator_baseline.py::test_idle_ring_wait_uses_peer_loss_and_progressive_backoff` | none | (1) zero lines matching `^ERROR timeframes:` or `timeframes:` in the log (tools/check_repo.py prefixes check_timeframes errors with `timeframes:`, :56-57 [R]); (2) the set of `FAILED ` lines in the log equals the 8-node allowlist EXACTLY — any extra, missing, or renamed failure is DRIFT → investigate before proceeding (a gladio/vortek node going green is also drift: it means the standing record is stale); (3) overall RC recorded but NOT part of the verdict | P2 |
| LV-timeframe-era-21 | TF-6 × bots — artifact-free live timeframe×bots era-interaction leg (added after audit round 1): after a non-default-stage boot, re-observe the stage-resolved era surfaces that need no LLM — the booted conf the native consumer reads, and the per-stage era-selector resolution for exactly that booted stage | vanilla | continuation of row 10's state (world booted at `vanilla-1.10`): 1) `findstr /c:"PocketRealm.TimeframeStage" "%LOCALAPPDATA%\PocketRealm\runtime\server\run\mangosd.conf"` — the value the native consumer reads via `sConfig.GetStringDefault("PocketRealm.TimeframeStage", "")` (PlayerbotLlmFilters.cpp:35-45 [R]) 2) resolve the era selector for THAT stage per function — literal one-line command (run from `%REPO%`; ONE shell line): `python -c "import re,os;conf=os.path.expandvars(r'%LOCALAPPDATA%\PocketRealm\runtime\server\run\mangosd.conf');stage=[l.split('\"')[1] for l in open(conf,encoding='utf-8') if 'PocketRealm.TimeframeStage' in l][0];h=open(r'native\patches\playerbots\PlayerbotLlmEraStages.generated.h',encoding='utf-8').read();bodies=[re.search(r'inline .+? const& '+fn+r'\(.*?\n\}',h,re.S).group(0) for fn in ('AlwaysBanTermsFor','SensePhrasesFor')];chk=lambda body,s: re.search(r'if \(stage == \"'+re.escape(s)+r'\"\) return (\w+)\(\);',body).group(1);assert all(chk(b,stage)==chk(b,'vanilla-1.12.1') for b in bodies),(stage,);print('era-selector OK for booted stage',stage)" (selector shape :151-203 [R]; the regex uses non-greedy `.+? const&` because `SensePhrasesFor`'s return type `std::pair<std::string, bool>` contains a space — the round-2 draft with `[^ ]+` crashed on exactly that and was fixed by running it, spot-proof 12 [X]; the selector-resolution logic was EXECUTED this ask against stage `vanilla-1.10` hardcoded — RC=0 `era-selector OK for booted stage vanilla-1.10`; the full row adds only the conf-driven stage read, rows 01-02 already prove the three-way at the repo level) 3) `findstr /c:"erastages::AlwaysBanTermsFor(ActiveTimeframeStage())" %REPO%\native\patches\playerbots\PlayerbotLlmTruthCore.h` — the truth core consumes the generated selector (the same assert tools/check_timeframes.py:325 makes [R]) 4) the booted world's LLM staging artifacts exist in run/: `dir "%LOCALAPPDATA%\PocketRealm\runtime\server\run"` shows `llm_prompt_pack.json`, `llm_character_card.json` (ServerRuntimeFiles.kt:595-596 [R]) and `aiplayerbot-disabled.conf` (:177 [R]) — **EXECUTED AND PASSED THIS ASK (round 3, L4)** | none | conf names `vanilla-1.10` **[X]**; the python step exits 0 (`era-selector OK for booted stage vanilla-1.10`) — both selector branches resolve to the same set as `vanilla-1.12.1` **[X]**; the truth-core grep hits (count=1) **[X]**; the three staging artifacts exist for the booted world **[X]**. **Explicit annotation: rows 09-12/18/21 prove settings-stage ⇒ conf ⇒ era-INPUT resolution live; the settings stage ⇒ era OUTPUT in generated chat text is proven ONLY by row 19 once R6 lands** | P1 |
| LV-timeframe-era-22 | **Build-breaker pre-flight for findings 5.24 + 5.22** (campaign-inventory.md:286) — run BEFORE any rebuild row (LV-server-db-23, LV-flavor-import-12 in their matrices) and BEFORE rows 16/17 here. **Leg B scans ONLY the library the ops actually bind to** — `System.loadLibrary("pocket_world_runtime")` (WorldNative.kt:4 [R]) — never the vestigial monolith | all 3 | Leg A (5.24 — era header on the realm-runtime staging list): `findstr /c:"PlayerbotLlmEraStages.generated.h" %REPO%\tools\build_o09_realm_runtime.py` → expect the staging-list entry (:490 [R]) AND the staged-copy fix (:6018-6023 [R], comment names the recorded failure `fatal error: PlayerbotLlmEraStages.generated.h: No such file or directory`). Leg B (5.22 — friend-account JNI ops in the staged Android `libpocket_world_runtime.so`): `python -c` byte-presence scan for `createAccountVerifierNative, accountListNative, accountCharactersNative, banAccountNative, unbanAccountNative` over ALL FOUR staged copies of the binding library — `native/.build-o09-{x86_64,arm64-v8a}/realm-staging-sqlite/jniLibs/<abi>/libpocket_world_runtime.so` and `native/.build-o09-{x86_64,arm64-v8a}/realm-staging/jniLibs/<abi>/libpocket_world_runtime.so` (ops at world_runtime.cpp:1658-1689 [R]; the five ops of MP:RA-VERIFIER-PATH/GUEST-BANS, campaign-inventory.md:248,251) — RUN THIS ASK [X], spot-proofs 9+13: count=1 for the probe symbol on ALL FOUR copies, and all five present on the two realm-staging-sqlite copies. **DO NOT scan `pocket-runtime-build/libpocketrealm.so` for this gate** — it is a vestigial monolith still in the "full"-lane staging list (android/app/build.gradle.kts:290 [R]) that `WorldNative` never loads; it carries 0 friend-account symbols on both ABIs [X] by design of its age, and a scan of it yields a FALSE failing. Honesty caveat: byte-presence scan of the .so image (exported names appear in .dynstr), not a dynsym parse — it detects the recorded "stale binary" landmine, not symbol-table corruption | none | Leg A: both the staging-list entry and the staged-copy fix present (recorded remedy if absent: re-add the header to the staging list per the :6018 comment — finding 5.24's fix). Leg B: 5/5 symbols on all scanned copies of `libpocket_world_runtime.so` (recorded remedy if absent: rebuild/re-pin the Android native lane per finding 5.22 BEFORE rows 16/17 or any on-device friend-access row). Either leg failing does NOT fail the pre-flight row — it GATES the dependent rows with the recorded reason; a silent skip does. **Reconciled 5.22 verdict for ALL matrices (see note N4): CLEAN on the binding library** | P1 |
| LV-timeframe-era-23 | **NEW (round 2): TF-4 persistence + TF-2 idempotent short-circuit across restarts, live** — a second world start with NO settings change must not re-execute any overlay SQL: the ledger triple is returned from the recorded row (DesktopTimeframeOverlay.kt:69-72 short-circuit [R]; JVM twin `reapplyingTheAppliedStageIsAnIdempotentShortCircuit` DesktopTimeframeOverlayTest.kt:144-151 [R], in the row-06 class PASSED this ask [X]) | vanilla | continuation of row 10's state (meta at `('vanilla-1.10',<sha>,T1)`, settings still `vanilla-1.10`): 1) `gradlew supervisorStartGate … > gate23.log` (no settings edit) 2) re-query meta: `select applied_stage,overlay_sha256,applied_at from timeframe_stage_state` 3) re-run marker counts from row 10 — **EXECUTED AND PASSED THIS ASK (round 3, L3)** | none | gate PASSED **[X]**; the meta triple is IDENTICAL to T1 — `('vanilla-1.10','bc1eaf06…',1790908912043)` with **`applied_at` UNCHANGED** (a re-application would write a new timestamp via `recordApplied`, DesktopTimeframeOverlay.kt:134-144 [R]; the short-circuit returns `recordedState` :146-152 [R]); marker rows unchanged. The same no-re-record behavior was observed at the DEFAULT stage (L1: applied_at 1790869114539 carried through a fresh gate run) **[X]** | P1 |

## Tracked pre-conditions (audit round 1, fix 1 — so the coverage ledger can distinguish "blocked, tracked" from "runnable")

| Blocks | Pre-condition | Owner | Unblock path |
|---|---|---|---|
| LV-timeframe-era-17 (this matrix) | New instrumented test class `TimeframeOverlayApplierInstrumentedTest` (android/app/src/androidTest) that arranges a non-default stage and drives the production world-start legs — a REPO test addition, not an artifact; verified absent in round 1 (`find %REPO%\android\app\src\androidTest -iname "*timeframe*"` → no match; no androidTest file references `TimeframeOverlayApplier` [X]) | workflow owner (Android lane) | Land the test class under the owner ruling's constraints (headless-safe, bounded, artifact-free), then row 17 runs with the row-16 vehicle (`connectedDebugAndroidTest`, emulator serial, `-PpocketAbi=x86_64`) |
| (cross-matrix, recorded for the ledger) LV-multiplayer-20 | UI-driving harness for the consent-dialog flow — outside the unattended CLI lane | workflow owner (multiplayer lane) | Same disposition as audit round 1 fix 1: tracked here because my file is the one the auditor read; the fix belongs in matrix-multiplayer + requirements/workflow plan |

## Cross-matrix reconciliation notes (audit round 1 — I can annotate only this file; the fixes belong to the named matrices' owners)

- **N1 — bots-llm-24 truth-suite expectation:** `python -m pytest tests\test_llm_truth.py -q` (full
  file) cannot pass as a NOW artifact-free row: 10 of its 15 tests take the `truth_binary` fixture —
  demonstrated in round 1: `-k era` → `5 passed, 10 deselected` [X] (spot-proof 4); the inventory's
  "15 passed" (campaign-inventory.md:263) is a full-file run WITH the binary. Fix for that matrix:
  scope the NOW mechanism to `-k era` (my row 05) or add the truth-binary build as an explicit
  precondition step.
- **N2 — truth-binary build precondition (if the full test_llm_truth.py file is ever scheduled):**
  it is a repo build action producing the fixture binary, not a user artifact; it must be declared
  in the scheduling row, never assumed.
- **N3 — findings 5.22/5.24 status on E: (this ask):** both recorded landmines do NOT reproduce on
  the current tree (spot-proofs 9-10 [X/R]); row 22 keeps them pre-flighted so a future re-staging
  regression maps to the recorded remedies instead of being misdiagnosed as a lane failure.
- **N5 — round 4: fresh APK capture required before row 16.** All prior captures under
  `build/differential/` predate `BaselineWorldSanityRunner` (verified: 0 dex hits in both tests
  APKs of the newest capture, L14 [X]), so the driver MUST run its build path
  (`python tools/run_baseline_world_sanity.py --only b`, ~30-min `-PpocketAbi=x86_64
  -PpocketLane=full -PsqliteProvider -PdifferentialTestLane` assemblies inside the driver,
  run_differential_parity.py:109-124 [R]). That is a REPO BUILD ACTION inside the lane's own
  driver — not a user artifact, and not the N2 truth-binary fixture. Budget it explicitly in the
  live stage; `--skip-build` must not be scheduled for this runner until a current capture exists.
- **N4 — audit round 2: the 5.22 verdict was split across matrices because they scanned different
  artifacts; reconciled here.** The ops bind to `System.loadLibrary("pocket_world_runtime")`
  (WorldNative.kt:4 [R]) — the gate's ONLY valid target is `libpocket_world_runtime.so`, which is
  CLEAN on current staging (this ask [X]: `createAccountVerifierNative` count=1 on all four copies —
  `native/.build-o09-{arm64-v8a,x86_64}/realm-staging{,-sqlite}/jniLibs/<abi>/`). The 0-hit artifact,
  `pocket-runtime-build/libpocketrealm.so` (~480 MB monolith, both ABIs [X]), is a vestigial staging
  entry (android/app/build.gradle.kts:290 [R]) that `WorldNative` never loads — 0 hits there is
  EXPECTED, not a failure. **LV-server-db-36 leg (b) as written scans that vestigial library and
  reports a false `FAILING (0/0)` that would BLOCK the on-device friend-access rows (multiplayer
  guest legs, timeframe-era-16/17, bots-llm rows) that timeframe-era-22 leg B and flavor-import-38
  CLEAR.** Reconciled verdict every executor should apply until that row is fixed: 5.22 = CLEAN on
  `libpocket_world_runtime.so`; the fix (re-point server-db-36(b)'s grep per this note) belongs to
  that matrix's owner — I can annotate only this file. **RESOLVED (server-db round-3 revision):
  LV-server-db-36 leg (b) now greps `libpocket_world_runtime.so` per this note and records the
  withdrawn FAILING — N4's interim verdict is superseded by matrix-server-db.md LV-36/P15**
  (verified this ask: matrix-server-db.md:109 leg (b) names `WorldNative.kt:4`
  `System.loadLibrary("pocket_world_runtime")`, the 5/5 verdict, and the withdrawn 0/0; P15 at
  :38 records the re-pointed scan).

## Interaction coverage map (what the ask's "interaction tests" resolve to here)

- **conf ↔ overlay ↔ native boot ordering** — rows 09/10/18 (order proven by the meta ledger +
  conf bytes after a real start; JVM twins rows 06/08).
- **settings ↔ conf ↔ overlay one-snapshot discipline** (no stage split between conf and overlay) —
  row 08's `TimeframeOverlayWiringTest` leg `everyWorldStartLegResolvesTheStageOnceForConfAndOverlay`
  (android .../TimeframeOverlayWiringTest.kt:81 [R], in the row-08 class PASSED this ask [X]) + rows
  09-12 live.
- **settings stage ⇒ observable era behavior in the live world** — artifact-free half: rows 18 + 21
  (booted conf → native read path → per-stage era-selector resolution → truth-core selector
  consumption); full-loop half: row 19 (R6-gated, the ONLY chat-output proof).
- **persistence across restarts** (the stage choice survives; re-application is a no-op) — row 23
  live + JVM twins (row 06's idempotent-reapply leg [X]; row 08's settings-persistence legs [X]).
- **stage ↔ provider lane** (MARIADB guard, ledger-bypass) — row 14 live + row 06 JVM [X].
- **stage ↔ flavor qualification** (cross-flavor/wrong-lane/wrong-client analog, unqualified tbc/wotlk
  can never pin a stage) — rows 12/15 + row 08's requireForFlavor legs [X].
- **stage ↔ era chat policy** (three-way grounded baseline; per-stage selector; never-ban) — rows
  01/02/05 (static chain) and 19/21 (live).
- **backward/forward stage moves as pure flag-sets over `isBoundaryActiveAt`** — rows 10/11 live +
  row 06 JVM (`backwardMoveReappliesTheEarlierOverlayAndDestroysNothing` :155 [R], in the row-06
  class PASSED this ask [X]).
- **rebuild/staging landmines ↔ scheduled rows** — row 22 gates rows 16/17 here and the rebuild
  rows of the server-db/flavor-import matrices.

## Later-stage (artifact-gated) rows summary

| Row | Blocks on |
|---|---|
| LV-timeframe-era-17 | NOT artifact-gated — out-of-scope-for-round-1, BLOCKED-TRACKED on the instrumented-test repo addition (tracked pre-conditions table above) |
| LV-timeframe-era-19 | **R6** tuned GGUF ×2 (+ in-repo llama-server build action); full conversational loop additionally needs a real player session/desktop client |

Every other row is artifact-free and runs NOW on the environment.md lane. No row in this area needs
R1/R2/R3 clients, R4 forks, or R5 MariaDB distribution: the area's wrong-client surface is the
settings/registry degrade (rows 08/12/15), and the MariaDB guard is a marker-file test (row 14).

## Honest limitations of this design pass

1. **Executed live (rounds 3-4):** the full desktop live-lane block (rows 09/10/23/21/18/11/12/13/14
   + recovery, L1-L10), row 16 step 1's emulator boot (L11), row 20's aggregate (L12), row 03's
   both poison variants (L13), and row 16's driver attempt through boot/install/staging/instrument
   (L14 — died on the stale capture, precondition N5). **Executed in round 2 (still standing):**
   the python gates and pytest subsets (rows 01-05), the desktop JVM classes of rows 06/07 (11+34+18
   tests, 0 failures), the android JVM classes of row 08 (25 tests, 0 failures), the 5.22 staged-.so
   scans, the bounded gladio/vortek allowlist run, row 21's selector logic. All JVM runs are
   CLASS-SCALE: the full `:desktop:test` suite (73 per the ledger), the full android JVM suite
   (1349 per the ledger), and the full pytest lane were NOT run — a live-stage decision.
2. **Not yet executed (genuinely deferred):** row 16 steps 2-4's SUCCESS path (blocked only on the
   N5 fresh build — the driver mechanism itself is proven up to the instrument), row 17
   (tracked-blocked on the instrumented-test repo addition), row 19 (needs R6), and row 05's
   full-file mode (needs the N2 truth-binary precondition).
3. Rows 13/14's live executions proved their fail-closed behavior through EARLIER gates in the
   supervisor chain (`DB-NOT-SEEDED`, `DB-LINK`) rather than the overlay-specific refusals, which
   stay JVM-proven (row 06) and live-reachable only under the conditions stated in those rows.
4. The TF-8 ledger names (`validate_timeframes.py`, `test_timeframe_manifests.py`,
   `test_timeframe_era_linkage.py`) do not exist on E: — see the discrepancy note above; rows test
   the implementations that do exist.
5. The 5.22 leg-B scan is a byte-presence check, not an ELF dynsym parse (stated in row 22); a
   symbol-table-level verification remains open if the executor wants it.
6. Row 20's allowlist was captured from a bounded 7-file run, not the full python lane; the
   full-lane `FAILED` set could legitimately contain failures in files outside those seven — any
   such line is drift by the row's rule and must be investigated, which is the intended behavior.
7. Row 16's pinned run-as path (`no_backup/database/sqlite-datadir/pocketrealm_meta.sqlite3`) is
   derived from code (`noBackupFilesDir` ↔ `no_backup`, StorageRoots.kt:31 [R]) but not yet
   exercised on a live emulator; the row keeps a discovery fallback if the literal path misses.

### 4.6 — section file `matrix-desktop-app.md` (embedded verbatim)

# Live-Verification Matrix — Round 4: Desktop app surfaces (settings editor, add-ons, diagnostics, support bundles, updates) on the Windows host

Written 2026-10-02 by the matrix-designer step for area `desktop-app` (round 1 authored the design; rounds 2–3 executed 19 rows; **round 4 executes fifteen more — every artifact-free row is now executed except the two rpHost world-boot legs and the R3 artifact row** — see "Checks run" at the bottom). Inputs: `out/live-verification/campaign-inventory.md`, `out/live-verification/environment.md`, `out/live-verification/requirements.md`, repo `E:/pocket_realm_complete` (unchanged — same three files, re-read this session). Every code claim cites `path:line`; **[RAN]** marks executed with outputs quoted at the bottom.

## Scope and deliverable-ID mapping (campaign-inventory.md rows for this area)

| Group in this matrix | Deliverable IDs (campaign-inventory.md) |
|---|---|
| Settings editor (app settings store + WTF in-game editor + catalogs + controls store) | DESK-4, DESK-12, DESK-22, DESK-41, DESK-59, DESK-85, DESK-86, DESK-96 |
| Add-ons (manager, built-in Android Port, projector, validator) | DESK-10, DESK-11, DESK-17, DESK-39, DESK-40, DESK-54, DESK-82, DESK-83, DESK-84, DESK-95 |
| Diagnostics (crash evidence, log ring, exit record, failure copy, realm status, storage mirror) | DESK-107, DESK-110, DESK-112, DESK-121, DESK-126, DESK-127, DESK-137, DESK-120, DESK-125, DESK-131 (Diagnostics-mirror leg — recorded unverified per campaign-inventory.md note 2) |
| Support bundle | DESK-119 (+ members carrying DESK-107/110/112/126 evidence) |
| Updates & fences | DESK-14, DESK-32, DESK-45, DESK-91, DESK-104, DESK-115, DESK-130 |

Not CLI-live-testable (recorded here, not designed): the GUI *click-through* itself (navigating the NavigationRail, clicking "Change game folder…" in `ActiveSetupCard`) — the routes are source-pinned at `desktop/src/main/kotlin/com/pocketrealm/desktop/Router.kt:22,30` and clicking is a GUI step, out of scope by rule. The **state contract behind DESK-135's affordances IS owned here**: row 35 pins `clientSelectionState()`/`resolveClientDir()` (the pure state `ActiveSetupCard` renders) via the desktop test lane, so DESK-135 carries a test disposition in this matrix, not a silent exclusion. DESK-121's process-tree *drain* mechanics belong to the supervisor area; this matrix covers its crash/exit *evidence* legs only (rows 22/34).

## Environment anchor (from environment.md, unchanged here)

Desktop lane runs **host-native**: `cd desktop && gradlew.bat <gate>` boots realmd/mangosd in-process on host loopback (environment.md:23-27). The desktop app is a Compose app (`Main.kt:39`), so every test below drives the **non-UI seams the app itself calls** — the stdio world console (`rpHost`, `desktop/build.gradle.kts:365-387`), gradle gates, the repo's python harness (`tools/rp_harness/`), and jshell drivers against the built fat jar — never screenshots, never GUI clicks.

**Flavors: every row is `vanilla`.** The desktop lane's only qualified flavor today is vanilla (`schemas/flavor.json`: vanilla qualified=True; tbc/wotlk `server_core`/`db_content` unresolved — campaign-inventory.md:25,287). Flavor-parameterized seams (e.g. DESK-54's `acceptedInterfaces`) are tested against the VANILLA policy (row 13).

## Mechanism legend (exact conventions used in the table)

- **[GATE]** `cd /e/pocket_realm_complete/desktop && ./gradlew.bat <task> --console=plain` (cmd: `gradlew.bat`). Gradle 8.10.2 wrapper; exit code is the verdict. Proof this lane runs on this host: `E:/pocket_realm_complete/out/verify-mobile-parity-r1-desktop-focused.log` shows `> Task :validateNativeRuntimePins` … "fence PASSED … (3 lockfile pin(s)…)" and `GRADLE_EXIT=0` (record evidence, not run this session).
- **[JSH] jshell driver against the desktop fat jar** — the primary mechanism for object-level surfaces on real code:
  1. One-time: `cd /e/pocket_realm_complete/desktop && ./gradlew.bat appJar --console=plain` → `build/libs/pocketrealm-desktop-app-0.103.0-alpha.jar` (**[RAN]** exit 0, 48,467,582 bytes).
  2. Per test: `R="$TEMP/lv-desktop-app/<testId>"; mkdir -p "$R"`; write the probe `"$R/probe.jsh"` (heredoc); run `"C:/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot/bin/jshell.exe" --class-path "E:/pocket_realm_complete/desktop/build/libs/pocketrealm-desktop-app-0.103.0-alpha.jar" "$R/probe.jsh"`. JDK 17 Temurin + jshell verified present **[RAN]**.
  3. jshell↔Kotlin interop rules (each verified **[RAN]** across rounds 1–3): Kotlin `val` properties are getters (`roots.getRoot()`, `snap.getRuntimeMode()`); constructors/functions with default params need the lambda/arg passed explicitly (`new DesktopSettingsStore(f, () -> Boolean.FALSE)`; row 20's `clientCrashRows(dir, 5)`); `object` members via `X.INSTANCE`; **`internal` members are name-mangled but callable**: `UpdateCheck.INSTANCE.getTestAllowedHttpHosts$pocketrealm_desktop()`. Kotlin `$default` bridges are NOT resolvable from jshell for `internal` classes (round 2). **Round-3 additions: (a) jshell script snippets cannot cast to the nested sealed `UpdateCheck.CheckResult.Available` (the compile failure cascades silently) — construct `new UpdateCheck.UpdateManifest(version,url,size,sha)` via its public ctor, or read `checkDetailed`'s toString; (b) `File.readText()` is a Kotlin extension, invisible to jshell — use `new String(Files.readAllBytes(...))`.**
  4. **cwd rule (round 3, executed): [JSH] probes that reach `DesktopAddonAssets`/`AddonCatalog`/`DesktopAddonRepository` run with cwd = the repo root** (`cd /e/pocket_realm_complete` first) — the asset resolver walks up from `user.dir` (`DesktopAddonAssets.kt:42-46`); from any other cwd the add-on seed lands in its honest error state (observed: 60 s poll → `installed=null`, no registry written) instead of seeding.
  6. **addon-op async rule (round 4, executed): repository ops are async — refusals land in `state.errorTitle`/`state.error`, never a caller exception** (`launchOperation`, `DesktopAddonRepository.kt:278-333`); **an op issued while another is active is SILENTLY DROPPED** (`:284` `if (activeJob?.isActive == true) return`) — poll `state.operation==null` between ops. `DesktopAddonRepository.get(roots)` is a process-wide singleton (`:984-989`) — one roots per JVM.
  4. State isolation: every [JSH] test uses `new DesktopStorageRoots(new File(System.getProperty("java.io.tmpdir"), "lv-desktop-app/<testId>"))` — `DesktopStorageRoots(File)` ctor (`DesktopStorageRoots.kt:25-26`), so nothing touches the real `%LOCALAPPDATA%\PocketRealm` unless the row says REAL-ROOTS.
- **[PY] python one-off** — `python - <<'PY' … PY` heredocs (stdlib only) for file/zip/sha assertions and the local HTTP fixture.
- **[RPH] repo python harness** — `python - <<'PY'` with `sys.path.insert(0,'tools/rp_harness'); from desktop_host import DesktopHost` (API: `wait_ready`, `hello`, `send_raw(dict)`, `close` — `tools/rp_harness/desktop_host.py:103-201`); spawns `gradlew.bat rpHost`, speaks `@@RP@@` JSON lines (protocol `RpHost.kt:298-301`; ops `RpHost.kt:111-262`).
- **[TST] repo harness class** — `cd /e/pocket_realm_complete/desktop && ./gradlew.bat test --tests "<fqcn>" --console=plain` (the shared android-tree test sources compile into this lane per `desktop/build.gradle.kts:39-41`; `forkEvery=1`, and `validateNativeRuntimePins` rides every run per `:231-237`).
- **[NET]** row needs internet (api.github.com); **no user artifact**.
- **[EGRESS]** precondition for every [NET] row, run once before scheduling them: `curl -sI -m 10 https://api.github.com | head -1` must report HTTP 200 (HTTP/2 or HTTP/1.1). Egress was NOT verified for this host by environment.md (it verified WHPX/adb/AVDs only), so rows marked **[NET→DEFERRED]** are now-tests ONLY when the egress check passes; if the host is offline they are re-classified **deferred** (run later, when egress exists) and never block the unattended lane. Offline legs of mixed rows still run. **[RAN round 2]: `HTTP/1.1 200 OK`, exit 0 — rows 14/16 stand as now-tests on this host today.**

## Host-state facts used by the rows (all **[RAN]** this session)

- `%LOCALAPPDATA%/PocketRealm` exists with `settings/settings.json` (runtimeMode LOCAL, `clientDir=C:\Wow clients\Installed\WoW_Classic_ENG_1.12.1`), `runtime/{logs,scratch,server}`, `content`, `database`, `exports`, `realm`, `user-account`; **no `addons/` dir yet** (fresh-install addon state; rows 10/14/33 cover the seed and real-root lanes).
- Natives + seeds staged: `native/.build-win-x86_64/pocket-runtime-build/{pocket_realmd_runtime.dll,pocket_world_runtime.dll}`, `sqlite-seam-build/pocket_sqlite.dll`, `native/.build-o09-x86_64/realm-staging-sqlite/assets/seed/{classicrealmd,classicc characters,classiclogs,classicmangos}.sqlz` — the [GATE]/[RPH] rows are runnable now.
- `desktop/build/libs/pocketrealm-desktop-0.103.0-alpha.jar` (plain jar) predates this session; the fat `pocketrealm-desktop-app-0.103.0-alpha.jar` was built this session ([RAN], `GRADLE_EXIT=0`).

## Matrix

| testId | feature (deliverable IDs) | flavor(s) | exact CLI mechanism | required artifacts | pass condition | severity |
|---|---|---|---|---|---|---|
| LV-desktop-app-01 | Settings store: fresh defaults + atomic save round-trip (DESK-41/96 codec lane, DESK-134 defaults) | vanilla | [JSH] probe: `var roots=new DesktopStorageRoots(new File(R)); var s=new DesktopSettingsStore(roots.getSettingsFile(),()->Boolean.FALSE); var f=s.load(); System.out.println(f.getRuntimeMode()+" "+f.getAutoLoginOnLaunch()); var w=com.pocketrealm.storage.Settings.Snapshot.Companion.fromJson("{\"runtimeMode\":\"LAN_HOST\",\"setupComplete\":true}",()->Boolean.FALSE); s.save(w); var b=s.load(); System.out.println("rt="+(b.getRuntimeMode()==com.pocketrealm.supervisor.RuntimeMode.LAN_HOST&&b.getSetupComplete())); System.out.println(java.util.Arrays.stream(roots.getSettings().listFiles()).filter(x->x.getName().endsWith(".tmp")).count());` then `python -c "import json;print(json.load(open(R+'/settings/settings.json'))['runtimeMode'])"` | none | Prints `LOCAL true` (fresh-install defaults, `SettingsSnapshotCodecTest` contract); `rt=true` (fsync+atomic-rename save, `DesktopSettingsStore.kt:43-68`); `0` temp files left; settings.json on disk says `LAN_HOST`. **→ EXECUTED round 3: `R01 fresh=LOCAL/true`; `R01 rt=true tmp=0`** | P1 |
| LV-desktop-app-02 | Settings store: corrupt file fails open to defaults (negative) | vanilla | [JSH] `printf '{garbage truncated' > "$R/settings/settings.json"` (mkdir first), then probe: `var s=new DesktopSettingsStore(new DesktopStorageRoots(new File(R)).getSettingsFile(),()->Boolean.FALSE); System.out.println(s.load().getRuntimeMode());` | none | Loads without throwing and returns `LOCAL` (the documented DataStore-parity posture, `DesktopSettingsStore.kt:24-31` "Missing/corrupt file yields defaults"); process exit 0. **→ EXECUTED round 3: `R02 corruptLoad=LOCAL`, exit 0** | P1 |
| LV-desktop-app-03 | In-game settings queue file: corrupt/foreign schema degrades to empty queue, never crashes (negative; DESK-41/85) | vanilla | [PY] `printf '{"schema":99,"queue":"junk"}' > "$R/runtime/ingame-settings/queue.json"` (mkdirs); [JSH] probe: `var roots=new DesktopStorageRoots(new File(R)); var ed=new com.pocketrealm.ingame.DesktopInGameSettingsEditor(new File(R,"client"), new File(roots.getRuntime(),"ingame-settings/queue.json"),()->Boolean.FALSE); System.out.println(ed.queue().totalQueued+" "+ed.queuedCount());` | none | Prints `0 0` — `readRecord` falls back to the empty record on any decode failure (`DesktopInGameSettingsEditor.kt:370-386`); exit 0. **→ EXECUTED round 2: `R03 queued=0`, exit 0** | P2 |
| LV-desktop-app-04 | In-game (WTF) settings editor, direct lane while client stopped (DESK-12/22/85/96; DESK-59's engine at the desktop path) | vanilla | [JSH] probe: `var roots=new DesktopStorageRoots(new File(R)); var client=new File(R,"client"); new File(client,"WTF/Account/SMOKE").mkdirs(); java.nio.file.Files.write(new File(client,"WTF/Config.wtf").toPath(),"SET gxResolution \"800x600\"\n".getBytes()); var def=com.pocketrealm.ingame.WowVanillaSettingsCatalog.Companion.byId("graphics.resolution"); var ed=new com.pocketrealm.ingame.DesktopInGameSettingsEditor(client,new File(roots.getRuntime(),"ingame-settings/queue.json"),()->Boolean.FALSE); var out=ed.apply(new com.pocketrealm.ingame.DesktopInGameSettingsEditor.Edit.CvarSetting(def,"1024x768")); System.out.println(out); System.out.println(ed.readConfigValues()); System.out.println(ed.queuedCount());` then [PY] print `client/WTF/Config.wtf` | none (fabricated WTF tree is CLI-created, no game client) | `Landed(lane=APPLIED_DIRECT)` (`DesktopInGameSettingsEditor.kt:108-136`); `readConfigValues` shows the merged `gxResolution=1024x768` via `ConfigWtfCodec`; `queuedCount()==0` and the direct-edit journal bumped in queue.json (`:117-121,339-350`); file write atomic (`InGameSettingsFiles.writeAtomic`). **→ EXECUTED round 2: `R04 outcome=Landed(lane=APPLIED_DIRECT) cfg={gxResolution=1024x768} queued=0`** (`WowVanillaSettingsCatalog.INSTANCE.byId("graphics.resolution")` resolves the real entry) | P1 |
| LV-desktop-app-05 | In-game settings: queue-while-running then drain on clean stop (DESK-41 queued-delivery interaction) | vanilla | [JSH] same tree as 04 but probe runs TWO editors with different liveness — **round-4 correction: the staged edit must use a STAGEABLE cvar (`graphics.vsync`/gxVSync; `graphics.resolution` carries a fixedReason and is refused in the queue lane with `"Resolution" cannot be changed outside the game`, executed)**: `var edRun=new …(client,q,()->Boolean.TRUE); System.out.println(edRun.apply(new Edit.CvarSetting(vsync,"1"))); System.out.println(edRun.queuedCount());` then `var edStop=new …(client,q,()->Boolean.FALSE); System.out.println(edStop.applyQueuedOnCleanStop()); System.out.println(edStop.readConfigValues());` | none | Running client → `Landed(lane=QUEUED_FOR_CLEAN_STOP)`, `queuedCount()==1`, revision bumped in queue.json (`:122-135`); after stop → summary `applied 1 queued in-game change(s)` (`:223-226`), `Config.wtf` carries the staged value, queue emptied, delivered record carried forward (`:212-222`). **→ EXECUTED round 4: `R05 running=Landed(lane=QUEUED_FOR_CLEAN_STOP) queued=1`; `R05 drain=applied 1 queued in-game change(s)`; `R05 cfg={gxVSync=1} queuedAfter=0`** | P1 |
| LV-desktop-app-06 | In-game settings refusals are honest and named (negative; DESK-85/96 fail-closed) | vanilla | [JSH] probe with client root lacking SavedVariables/bindings files, in BOTH lanes (round-4 correction: the message depends on the lane — while STOPPED `applyDirect`'s file check fires first, while RUNNING `checkStageable`'s catalog check fires): `edStop.apply(new Edit.Binding("TOTALLY_UNKNOWN_CMD","SMOKE","F1",null))` (stopped); `edRun.apply(new Edit.Binding("TOTALLY_UNKNOWN_CMD","SMOKE","F1",null))`, `edRun.apply(new Edit.Binding("CAMERAORSELECTORMOVE","SMOKE",null,null))`, `edRun.apply(new Edit.UvarSetting(vsync,"NOSUCHACCT","1"))` (running) | none (`CAMERAORSELECTORMOVE` verified a real catalog command this session) | Stopped lane → `log in once in game to edit key bindings` (applyDirect's file check, `:293`); running lane → `unknown binding command: TOTALLY_UNKNOWN_CMD` (`:328-329`), `log in once in game to edit that scope's bindings` (`:332-334`), `log in once in game to edit saved variables` (`:322-325`); WTF tree unmodified. **→ EXECUTED round 4: all four messages exactly as corrected** | P2 |
| LV-desktop-app-07 | Settings/binding catalog golden pins (DESK-86) | vanilla | [TST] `./gradlew.bat test --tests "com.pocketrealm.desktop.CatalogGoldenPinsTest" --console=plain` | none | BUILD SUCCESSFUL; exit 0 — the 211+3-entry digests recomputed against committed literals (`desktop/src/test/kotlin/com/pocketrealm/desktop/CatalogGoldenPinsTest.kt`). **→ EXECUTED round 4 (batched [TST] run): `TST_EXIT=0`, `BUILD SUCCESSFUL in 5s`, CatalogGoldenPinsTest tests=5 fail=0 err=0** | P1 |
| LV-desktop-app-37 | G3-10 vanilla frozen pins: settings catalog v2, binding-category/Semantics ordinals, byte-for-byte VanillaWtfLayout paths (G3-10; added at audit direction — placed next to LV-desktop-app-07 per same [TST] mechanism; mirrored as LV-bot-actors-42 in matrix-bot-actors.md) | vanilla | [TST] (android app module — these pin classes live there; from Git-Bash invoke as `cmd //c "gradlew.bat …"`) `cd android && cmd //c "gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.client.VanillaCatalogPinsTest --tests com.pocketrealm.client.WowVanillaBindingCatalogTest --tests com.pocketrealm.ingame.InGameSettingsFilesLayoutPinTest --console=plain"` — `-PpocketAbi` MANDATORY (audit round 2, bot-actors section; build.gradle.kts:948-951 throws at configuration without it; unit tests need no `-PpocketLane`, which defaults to full, :956) | none | BUILD SUCCESSFUL; 0 failures — `WowVanillaSettingsCatalog.CATALOG_VERSION==2` + `8e1ae853…` rendered-entry digest + binding catalog v2 (`android/app/src/test/java/com/pocketrealm/client/VanillaCatalogPinsTest.kt:47,57,63`); binding ordinals pinned against committed literals (`WowVanillaBindingCatalogTest.kt`); `VanillaWtfLayout` reproduces the WTF paths byte-for-byte (`InGameSettingsFilesLayoutPinTest.kt:11,21,51-57`). **→ EXECUTED round 3 (bot-actors section, same suites): `gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests …` → BUILD SUCCESSFUL, exit 0; VanillaCatalogPinsTest 6 + WowVanillaBindingCatalogTest 7 + InGameSettingsFilesLayoutPinTest 4 = 17 tests, 0 failures, 0 errors, 0 skipped** | P2 |
| LV-desktop-app-08 | Controls/input-profile store + gamepad bridge contract (DESK-4) | vanilla | [JSH] probe: `var roots=new DesktopStorageRoots(new File(R)); var st=new com.pocketrealm.client.InputProfileStore(new File(roots.getSettings(),"input-profile.json")); var d=st.loadStoredOrDefault(); System.out.println(d); st.save(d); System.out.println(st.loadStoredOrDefault());` + [TST] `./gradlew.bat test --tests "com.pocketrealm.desktop.Win32GamepadBridgeTest"` | none | File-backed store round-trips at `settings/input-profile.json` (the app's own path, `DesktopAppModel.kt:431-433`); harness class green (bridge start/stop under the lifecycle lock). **→ EXECUTED round 4 (batched [TST] run): Win32GamepadBridgeTest tests=2 fail=0 err=0; [JSH] store probe re-run with the corrected `st.save(d)` — the row's original `st.saveStored(d)` names a method on no InputProfileStore (the desktop save API is `save(profile)`, InputProfile.kt:1254) — and round-trips byte-equal (LVF-desktop-app-001)** | P2 |
| LV-desktop-app-09 | Add-on catalog asset resolves + built-in identity (DESK-10/95/83) | vanilla | `cd /e/pocket_realm_complete` (DesktopAddonAssets walks up to the checkout's asset tree, `DesktopAddonAssets.kt:42-46`); [JSH] probe: `var c=com.pocketrealm.addons.AddonCatalog.Companion.load(); System.out.println(c.getAddons().size()); var a=c.addon("151"); System.out.println(a.getName()+" "+a.getInstallSource()+" "+a.getInstallId());` | none | Non-empty catalog (parsed from `android/app/src/main/assets/addons/catalog-v1.json` — 151 entries family verified this session); `addon("151")` = `Android Port`, installSource `builtin`, installId `builtin__androidport` (`AndroidPortPackage.kt:11-13`). **→ EXECUTED round 4: `R09 addons=154 a151=Android Port/BUILTIN/builtin__androidport`** | P1 |
| LV-desktop-app-10 | Fresh-install seed: built-in Android Port installs + two-file registry publish (DESK-11/17/40/83/95 — the DESK-17 merged add-on-manager + Android Port + projector lane's built-in/manager leg; its projector leg is row 11) | vanilla | **cwd = repo root** (legend rule 4). [JSH] probe: `var roots=new DesktopStorageRoots(new File(R)); var repo=com.pocketrealm.addons.DesktopAddonRepository.Companion.get(roots); for(int i=0;i<60;i++){var st=repo.getState().getValue(); if(!st.getInstalled().isEmpty()&&st.getOperation()==null){System.out.println(st.getInstalled()); break;} Thread.sleep(500);} System.out.println(new File(roots.getRoot(),"addons/registry.json").isFile());` then [PY] `json.load(addons/registry.json)` → assert schema/installed/folders; `find "$R/addons/packages" -name "AndroidPort.toc"` + grep `## Interface: 11200` | none | registry.json appears (fresh install, `DesktopAddonRepository.kt:112-119`); installed contains id `builtin__androidport` with 40-hex `commitSha`/`archiveSha256`; package tree published at `addons/packages/builtin__androidport/<digest>/AndroidPort/` whose `.toc` declares `## Interface: 11200` (`DesktopAddonRuntimeProjector.kt:280-283`); no `registry.previous.json` yet. **→ EXECUTED round 3: `installed=[InstalledAddon(id=builtin__androidport, repository=builtin:addons/android-port, displayName=Android Port, commitSha=7017eda00a0be73c6d876acb63409e1b54daf0b3, archiveSha256=7017eda0…77d97bad0addf16e9d0fde2f, packagePath=packages/builtin__androidport/7017eda0…, folders=[AndroidPort])]`; `registry=true`; [PY] `schema=1 folders=['AndroidPort'] tocInterface=11200`** | P1 |
| LV-desktop-app-11 | Launch-boundary projection onto a client folder + ownership record (DESK-40/84) | vanilla | [JSH] reusing row-10's roots: `var proj=new com.pocketrealm.addons.DesktopAddonRuntimeProjector(new File(roots.getRoot(),"addons"), null); var applied=proj.project(new File(R,"client"), false); System.out.println(applied);` then [PY] assert `client/Interface/AddOns/AndroidPort/AndroidPort.toc` exists and print `client/Interface/AddOns/.pocketrealm-managed.json` | none | Returns `["AndroidPort"]`; the built-in tree physically lands under `Interface/AddOns/AndroidPort/`; ownership file `{"schema":1,"folders":["AndroidPort"]}` (`DesktopAddonRuntimeProjector.kt:33-157,196-207`). **→ EXECUTED round 3: `R11 applied=[AndroidPort]`; [PY] `ownership={'schema':1,'folders':['AndroidPort']}` + `tocExists=True`** | P1 |
| LV-desktop-app-12 | Projection fail-closed set (negative; DESK-84/95) | vanilla | [JSH]+[PY], six probes (legs d/e must craft a **non-builtin** registry id — the built-in's packagePath is never consulted, the assets tree is copied at every launch, `DesktopAddonRuntimeProjector.kt:47-50,65-75`): (a) `proj.project(client2,true)` after row 11 → `[]`; (b) [PY] `printf '{corrupt' > addons/registry.json` (fresh copy) then project → readInstalled fails; (c) pre-create `client3/Interface/AddOns/AndroidPort/` NOT in ownership, project; (d) [PY] craft registry entry `id="shagu__pfquest", packagePath:"../escape"` and project; (e) [PY] craft `id="shagu__pfquest", packagePath:"packages/shagu__pfquest/deadbeef", folders:["Nope"]` with the package dir present → missing folder; (e2) same but the package dir deleted | none | (a) `[]` (safe mode projects none, `:62-63`); (b) throws (launch refused — corrupt registry read, `:52-56`); (c) throws `conflicts with a client-owned addon` (`:86-91`); (d) throws `Unsafe addon package path` (`:286-294`); (e) throws `Installed addon folder is missing` (`:70-75`); (e2) throws `Addon package is unavailable` (`:290-294`); in no case is a partial tree left (staging/backup cleanup `:127-144`). **→ EXECUTED round 3: `R12a safe=[]`; `R12b REFUSED JSONException: Expected a ':' after a key at 8…` (fail-closed read); `R12c REFUSED Addon 'AndroidPort' conflicts with a client-owned addon; remove or rename it before enabling this package`; `r12d REFUSED Unsafe addon package path`; `r12e REFUSED Installed addon folder is missing: Nope`; `r12e2 REFUSED Addon package is unavailable`** | P1 |
| LV-desktop-app-13 | Add-on archive validator: flavor seam + malformed archives (negative happy; DESK-54, G3-6) | vanilla | [PY] build 5 zips: `good.zip` (`MyAddon/MyAddon.toc` with `## Interface: 11200` + `MyAddon.lua`), `tbc.zip` (same, `## Interface: 20400`), `empty.zip` (one non-toc file), `rootgood.zip`/`roottbc.zip` (`MyAddon.toc` + lua at archive ROOT, Interface 11200/20400); [JSH] probe (round-2 corrected call form — pass the explicit policy; `$default` bridges are not jshell-resolvable for internal classes): `var v=new com.pocketrealm.addons.AddonArchiveValidator(); var pol=com.pocketrealm.addons.AddonArchiveValidator.Policy.Companion.getVANILLA(); System.out.println(v.validate(new File(R,"good.zip"),"t",pol,()->kotlin.Unit.INSTANCE).getAddonFolders()); for(var z:new String[]{"tbc.zip","empty.zip","rootgood.zip","roottbc.zip"}){try{v.validate(new File(R,z),"t",pol,()->kotlin.Unit.INSTANCE); System.out.println(z+"=ACCEPTED");}catch(Throwable e){System.out.println(z+"=REFUSED "+e.getMessage());}}` | none | `good.zip` and `rootgood.zip` → `[MyAddon]` (acceptedInterfaces default `ClientFlavors.VANILLA.addonInterfaces`, `AddonArchiveValidator.kt:37-44`); the 20400 flavor-seam refusal fires in BOTH layouts (non-supported tocs are filtered at `:146-152`/`rootToc` at `:121-131`, then the `addonFolders` non-empty guard at `:159-161`); `empty.zip` → "No Vanilla addon .toc file was found" (`:107`). **→ EXECUTED round 2: `good=[MyAddon]`; `tbc.zip=REFUSED Each addon must contain Folder/Folder.toc or a repository-root .toc`; `empty.zip=REFUSED No Vanilla addon .toc file was found`; `rootgood=[MyAddon]`; `roottbc=REFUSED` (same guard message) — the DESK-54 seam holds; the refusal SURFACES as the folder-guard message, not a dedicated Interface message (the dedicated "does not declare a supported Vanilla interface" text at `:166-172` is reachable only via `validateToc`, i.e. a root toc that already passed the interface gate)** | P1 |
| LV-desktop-app-14 | GitHub install happy path + one-step rollback (DESK-10/39/82) | vanilla | **[EGRESS]** gate first ([RAN round 2]: passed). On a copy of row-10's roots: [JSH] poll seed idle; `repo.install("https://github.com/shagu/pfQuest");` poll `getState()` until `getOperation()==null` (op-async rule, legend 6); [PY] read registry.json; `repo.canRollback()`; `repo.rollback()`; poll until notice/error; [PY] diff registry.json | none (internet; pfQuest = catalog matrixId 002) | Notice `pfQuest is ready for the next game launch.` (`:417`); registry entry has 40-hex commitSha + sha256 digest pin and package at `addons/packages/<id>/<commit>/` (`:376-389`); `canRollback()==true`; rollback restores the previous (built-in-only) set with notice `Previous add-on set restored for the next game launch.` (`:227-255`). **→ EXECUTED round 4 over the live network: `R14 notice=pfQuest is ready for the next game launch.`; registry `commitSha=6c5bff62b35b…` (40-hex, == the resolver's own release-tag ground truth); `canRollback=true`; `R14 rollback=Previous add-on set restored for the next game launch. installed=1`** | P1 |
| LV-desktop-app-15 | Add-on install/rollback refusals (negative; DESK-10/82) | vanilla | [JSH] (op-async rule, legend 6 — refusals surface in state, never a throw; poll `operation==null` after each op). Round-4 corrected legs: (a) fresh roots pre-seed → `canRollback()` false (executed `false`); (b) **the deterministic rollback refusal**: copy a published addons tree, [PY] delete `registry.previous.json`, then `repo.rollback()` → error state `Could not restore add-ons` / `There is no previous add-on set to restore` (`:229`); (c) `repo.install("https://gitlab.com/x/y")` → error state `Could not install add-on` / `Use a public GitHub URL such as https://github.com/owner/addon` (`AddonModels.kt:51-62`) with installed unchanged; (d) [NET] 404-repo leg under the [EGRESS] gate (still designed). Also recorded executed truth: after the fresh-install SEED, `registry.previous.json` exists (possibly empty) and rollback legitimately SUCCEEDS restoring it (executed: notice "Android Port and its matching controls are ready…") — the refusal needs the deleted-previous state, not merely fresh roots | none | (a) `canRollback()==false`; (b) error state `Could not restore add-ons` / `There is no previous add-on set to restore` with installed unchanged; (c) gitlab URL → error state with the public-GitHub-URL message, installed unchanged. **→ EXECUTED round 4: `canRollback=false` (pre-seed); `rollback title=Could not restore add-ons err=There is no previous add-on set to restore installed=1`; `gitlab title=Could not install add-on err=Use a public GitHub URL such as https://github.com/owner/addon installed=1`**; (d) 404 leg remains designed | P2 |
| LV-desktop-app-16 | Add-on update check: verdict pinned to computed ground truth (interaction; DESK-39/82) | vanilla | **[EGRESS]** gate first ([RAN round 2]: passed). After row-14 (registry current). Ground truth FIRST, mirroring the resolver's own precedence (`DesktopAddonRepository.kt:643-660`: latest-release tag if any, else default branch): `INFO=$(curl -s -m 15 https://api.github.com/repos/shagu/pfQuest)`; `TAG=$(…releases/latest … d.get('tag_name') or '')`; `REF=${TAG:-$(… default_branch …)}`; `GROUND=$(curl -s -m 15 https://api.github.com/repos/shagu/pfQuest/commits/$REF \| python -c "import json,sys;print(json.load(sys.stdin)['sha'].lower())")`; [PY] read installed pfQuest `commitSha` from registry.json; [JSH] `repo.checkForUpdates();` poll `getState().getValue()` until `getUpdatesCheckedAtEpochMs()!=null`; [PY] compare notice + `availableUpdates` against the computed equality | none (internet) | EXACTLY ONE of two verdicts, decided by the computed comparison — equal: notice `Installed add-ons are up to date.` AND `availableUpdates` empty; differ: `availableUpdates` maps the pfQuest install id to a 40-hex value EQUAL to `$GROUND` and notice `1 add-on update available.` (`:161-197`). Any other combination (update reported against a different sha, or none reported while commits differ) FAILS the row. **→ EXECUTED round 4 (equal branch, live): ground truth = release 7.0.1's commit `6c5bff62b35b…`; installed matched → `R16 verdictEqual notice=Installed add-ons are up to date. updatesEmpty=true`**; the differ branch's map-value assertion remains designed | P2 |
| LV-desktop-app-17 | Add-on cancellation fence (DESK-95 lane, shared harness) | vanilla | [TST] `./gradlew.bat test --tests "com.pocketrealm.addons.AddonOperationSafetyTest" --console=plain` | none | BUILD SUCCESSFUL — the shared safety test drives the token/cancel fence against a stalled local HTTP server (mockwebserver3, `desktop/build.gradle.kts:140-142`). **→ EXECUTED round 4 (batched [TST] run): AddonOperationSafetyTest tests=3 fail=0 err=0** | P2 |
| LV-desktop-app-18 | Native runtime lockfile-pin fence (DESK-115) + skip-flag ambiguity refusal (negative) | vanilla | [GATE] `./gradlew.bat validateNativeRuntimePins --console=plain` then [GATE, negative] `./gradlew.bat validateNativeRuntimePins -PpocketSkipNativeFreshness=bogus --console=plain; echo EXIT=$?` | none | First: `Native runtime freshness fence PASSED for desktop pins-only (sqlite-win) (3 lockfile pin(s)…)` exit 0; second: nonzero exit with a loud refusal naming the ambiguous skip value — skippable ONLY by the literal `true` (`desktop/build.gradle.kts:160-165`). **→ EXECUTED round 2, both legs: `PINS_EXIT=0` with the exact PASSED line; `BOGUS_EXIT=1` with `IllegalArgumentException: Refusing ambiguous -PpocketSkipNativeFreshness='bogus'; pass exactly 'true' to skip loudly, or drop the property to run the fence`** | P1 |
| LV-desktop-app-19 | Typed app-log ring: 800 cap, exportJson, unbounded file lane (DESK-110) | vanilla | [JSH] probe: `var roots=new DesktopStorageRoots(new File(R)); com.pocketrealm.desktop.DesktopLog.INSTANCE.attachFile(roots.getLogs()); for(int i=0;i<850;i++) com.pocketrealm.desktop.DesktopLog.INSTANCE.i("LV","m"+i); System.out.println(com.pocketrealm.desktop.DesktopLog.INSTANCE.snapshot().size()); java.nio.file.Files.write(new File(R,"ring.json").toPath(), com.pocketrealm.desktop.DesktopLog.INSTANCE.exportJson().getBytes());` then [PY] `json.load(ring.json)`; count lines of `<R>/runtime/logs/pocket-realm.log` (the file lane lives under the runtime/logs root — `DesktopStorageRoots.kt:65`) | none | `snapshot().size==800` (`DesktopLog.kt:29,56-59`); ring.json parses as a JSON array of `{ts,level,kind,msg}` objects, first entry `m50` (oldest evicted); the file lane keeps all 850 lines (append unchanged, `:47-55`). **→ EXECUTED round 2: `R19 ring=800`; json `entries=800 first=m50 shape=['kind','level','msg','ts']`; file `runtime/logs/pocket-realm.log` `lines=850`** | P1 |
| LV-desktop-app-20 | Client crash evidence lanes: Errors/ texts, WER dumps, honest absence (DESK-107/126) | vanilla | [PY] fabricate `<client>/Errors/` with 6 `Errors-*.txt` (distinct mtimes) and a dumps dir with `WoW.exe.100.dmp`, `WoW.exe.101.dmp`, `Other.exe.1.dmp`; [JSH] probe (explicit caps per legend rule 3): `var ev=new com.pocketrealm.desktop.DesktopCrashEvidence(new File(R,"dumps"), n->""); System.out.println(ev.clientCrashRows(new File(R,"client"),5)); System.out.println(ev.werDumpRows(3)); var none=new com.pocketrealm.desktop.DesktopCrashEvidence(new File(R,"nodumps"), n->""); System.out.println(none.werDumpRows(3).size());` | none | crash rows = newest-first `Errors/<name>`, capped at 5 (`DesktopCrashEvidence.kt:29-38,66`); dump rows list only the two `WoW.exe.*.dmp` with KiB sizes (`:41-53`); empty source → empty rows, no throw ("absence of any source means no crash yet", `:17-19`). **→ EXECUTED round 3: `crash=[Errors/Errors-2026-10-06.txt … Errors-2026-10-02.txt]` (6 fabricated, 5 newest kept); `wer=[WoW.exe.100.dmp (4 KiB), WoW.exe.101.dmp (4 KiB)]` (Other.exe filtered); `noneWer=0` on an empty dumps dir, no throw** | P1 |
| LV-desktop-app-21 | Support bundle export: DESK-119 manifest header + bounded tail + redaction + dump metadata-only (DESK-119/110/112/126) | vanilla | [PY] prefill isolated roots: settings.json containing `"llmExternalApiKey":"SK-CANARY-abc123"`, `runtime/server/run/mangosd.conf` containing the same key, `logs/pocket-realm.log` > 300 KiB, `realm/runtime-supervisor/journal.json` stub, client Errors/ file; [JSH] probe: `var roots=new DesktopStorageRoots(new File(R)); System.out.println(new com.pocketrealm.desktop.DesktopSupportBundle(roots,new File(R,"client"),new com.pocketrealm.desktop.DesktopCrashEvidence(new File(R,"dumps"),n->"")).export());` then [PY]: open the zip in `runtime/logs/`, dump `manifest.json`, recompute sha256 of each member, `bytes = f.read()` scan | none | Returns `Created N entries - support-bundle-<stamp>.zip` (`DesktopSupportBundle.kt:117`); manifest has `schema=1`, `redaction=structured-plus-regex-v1`, `appVersion=0.103.0-alpha`, `flavorId=vanilla`, `nativeRuntimeBuildId`, `topology.runtimeMode` (`:67-78`); every member's sha256 matches the manifest (`:165-171`); `SK-CANARY-abc123` appears NOWHERE in the zip (explicit canary lane, `:136-150,276-278`); `logs/pocket-realm.log` member ≤ 256 KiB + one line (`APP_LOG_TAIL_BYTES`, `:263-265,214-228`); `crash/wer-dumps.json` carries names/sizes only and no `*.dmp` entry exists anywhere (`:104-106,174-212`); `logs.json` member parses as JSON. **→ EXECUTED round 3: `Created 8 entries - support-bundle-20261002-154607.zip`; [PY] `members=8 sha_mismatches=[]`; header keys `appVersion/flavorId/nativeRuntimeBuildId/redaction/schema/topology` present; `canary_leaks=[]` (the key sat in BOTH settings.json and mangosd.conf); `log_tail_bytes=262102 ≤262144`; `dmp_entries=[]`; `wer=[{name:WoW.exe.9.dmp, bytes:2048, …}]` metadata-only; `logs.json` parses (0 entries in the fresh probe JVM — ring content proven by row 19)** | P1 |
| LV-desktop-app-22 | Last client exit record: honest absence + decode (DESK-112 read leg) | vanilla | [PY]: (a) assert `<R>/runtime/server/lifecycle/client.json` absent on fresh roots; (b) write the exact producer shape `{"schema":1,"component":"client","pid":123,"exitCode":-1073741819,"clean":false,"operation":"exit","detail":"crash","at":<now>}` there (path verified: `ServerRuntimeFiles.kt:56,451-457`); [TST] `./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopCrashEvidenceTest"` | none | (a) no file → the Diagnostics card shows nothing and `readClientExit()` returns null without throwing (`:451-457`); (b) the record decodes to the ABNORMAL copy path (`DiagnosticsScreen.kt:325-334`); harness green. **→ EXECUTED round 4: [TST] `DesktopCrashEvidenceTest` → `CE_EXIT=0`, `BUILD SUCCESSFUL in 8s`**; the python file legs (a)/(b) remain designed. **Write leg under a real client death is row 34 (artifact).** | P2 |
| LV-desktop-app-23 | Stable failure codes → plain user copy (DESK-137/120/125) | vanilla | [JSH] probe: `var r=com.pocketrealm.supervisor.RuntimeFailureCopy.INSTANCE; for(var d:new String[]{"LAN_HOST_INTERFACE_LOST","UNVERIFIED_ORPHAN","LAN-JOIN-CLIENT-MISSING"}) System.out.println(d+" -> "+r.humanize(d));` + [TST] `./gradlew.bat test --tests "com.pocketrealm.desktop.RuntimeFailureCopyTunnelTest"` | none | Each maps to the plain-language copy (≠ the raw code, multi-word sentences — `RuntimeFailureCopy.kt:16-60` branches read this session); this is what the Diagnostics supervisor card renders at `DiagnosticsScreen.kt:128-137`; harness green. **→ EXECUTED round 3: `LAN_HOST_INTERFACE_LOST -> Your network connection changed, so host…`; `UNVERIFIED_ORPHAN -> A previous realm start left a world runn…`; `LAN-JOIN-CLIENT-MISSING -> Joining doesn't need a local realm, but …`** | P2 |
| LV-desktop-app-24 | Realm-status surface: status JSON twin + live world telemetry (DESK-127) | vanilla | (a) [TST] `./gradlew.bat test --tests "com.pocketrealm.server.ServerStatusJsonTest"`; (b) [RPH] `from desktop_host import DesktopHost` → `h=DesktopHost('.'); h.wait_ready(timeout_s=900); print(h.hello()); print(h.send_raw({'op':'detail'})); h.close()` (world boots in-process; `RpHost.kt:81` ready event) | none (natives+seeds staged — verified this session) | (a) BUILD SUCCESSFUL (the `ServerStatusJson.world/realm` rendering the Diagnostics World-load card consumes, `DesktopRuntimeBackend.kt:1712-1723`, `DiagnosticsScreen.kt:340-360`); (b) `{"event":"ready","world":"READY"}` then `hello` `ok=true` and the `detail` op returns live world detail JSON — the statusNative/detailNative lanes answer on a RUNNING world. **→ EXECUTED round 4 (leg a, batched [TST] run): ServerStatusJsonTest tests=6 fail=0 err=0**; leg (b) remains designed | P1 |
| LV-desktop-app-25 | Diagnostics storage-roots mirror (DESK-131 mirror leg — recorded unverified in the campaign) | vanilla | [JSH] probe A (isolated): `var roots=new DesktopStorageRoots(new File(R)); roots.ensureDirectories(); var rep=roots.verify(); System.out.println(rep.getOk()+" "+rep.getRoots().size()+" "+rep.getRootsUnderBase());` probe B (REAL-ROOTS, read-only): same with `new DesktopStorageRoots(null)` then print each `RootStatus` line | none | A: `true 12 true` — the full 12-root taxonomy (`DesktopStorageRoots.kt:111-132`) with `layout: ok`, closing the "Diagnostics-mirror leg unverified" gap (campaign-inventory.md note 2); B: every real root `exists=true` with usable-bytes ≥ 0; deleting one root in probe A first makes `ok==false` (the MISSING rendering path, `DiagnosticsScreen.kt:100-117`). **→ EXECUTED round 3 (probe A): `R25 ok=true roots=12 under=true`**; probe B (REAL-ROOTS) and the delete-one-root negative remain designed | P1 |
| LV-desktop-app-26 | Update check honest refusals (negative; DESK-14) | vanilla | [JSH] probe on fresh roots: `System.out.println(com.pocketrealm.desktop.UpdateCheck.INSTANCE.checkOnce(roots));` then [PY] `printf 'http://127.0.0.1:8901/feed.json' > settings/update-feed.url` and re-run `checkDetailed(roots)` (NO seam added — production posture); then [PY] `printf 'https://127.0.0.1:9/feed.json' > settings/update-feed.url` and re-run | none | (a) `No update feed configured (place a feed URL in update-feed.url under …)` (`UpdateCheck.kt:52-58`); (b) `The configured update feed must be an https:// URL.` (`:61-65` — cleartext feed refused); (c) `Update check failed: <IOException simple name>` (unreachable https, `:83-85`); all as `CheckResult.Unavailable`, never a throw out of `checkOnce` (`:26-28`). **→ EXECUTED round 2, all three legs: `R26a No update feed configured (place a feed URL in update-feed.url under …Temp…\r26\settings to enable the check)`; `R26b Unavailable(reason=The configured update feed must be an https:// URL.)`; `R26c Unavailable(reason=Update check failed: ConnectException)`** | P1 |
| LV-desktop-app-27 | Update feed parsing: partial download legs + malformed sha256 refused (negative; DESK-14) | vanilla | [PY] start `python -m http.server 8901 --bind 127.0.0.1 --directory "$R/www"` serving `feed.json` = `{"version":"9.9.9","url":"https://x/y.zip"}`; [JSH] probe: `UpdateCheck.INSTANCE.getTestAllowedHttpHosts$pocketrealm_desktop().add("127.0.0.1"); …writeFeedFile… System.out.println(UpdateCheck.INSTANCE.checkDetailed(roots));` ; swap feed body to `"sha256":"nothex"` variant and re-run; finally a display-only body `{"version":"9.9.9"}` | none | Legs-partial → `Unavailable` and malformed-sha → `Unavailable` — **round-4 executed correction: the require MESSAGES are not carried into the display copy; `checkDetailed`'s runCatching reports only the exception class (`Update check failed: IllegalArgumentException`, `:83-85`)**; display-only → `Available` with `getDownloadable()==false` and the `this app does not self-update` copy (`:31-45,92-100`). **→ EXECUTED round 4 (body-cycling local server): `R27a Unavailable(reason=Update check failed: IllegalArgumentException)` (partial legs); `R27b Unavailable(reason=Update check failed: IllegalArgumentException)` (malformed sha); `R27c Available(manifest=UpdateManifest(version=9.9.9, packageUrl=null, size=null, sha256=null))`** | P2 |
| LV-desktop-app-28 | Update check + resumable verified download happy path; staging under settings/downloads (DESK-14/32/45/91) | vanilla | [PY] inline Range-capable server (stdlib `http.server` subclass answering `Range:` with 206 + `ETag`, request log to `$R/server.log`) serving `/pkg.bin` (64 KiB pattern) AND `/feed.json` = `{"version":"9.9.9","url":"…/pkg.bin","size":65536,"sha256":"<real>"}`. **Round-3 correction: the feed FILE `settings/update-feed.url` carries the BARE feed URL (one line, no JSON)** — `checkDetailed` runs `isFetchAllowed` over the whole file text (`UpdateCheck.kt:61-65`), a JSON body fails `URL()` and reports the https refusal (executed this round); the JSON comes from the HTTP GET (exactly `UpdateCheckTest.writeFeedFile:106-110`). [JSH] probe: seam-add host; `checkDetailed(roots)` → read the `Available` VERDICT via toString (do NOT cast to the nested sealed class — legend 3a); construct `var m=new UpdateCheck.UpdateManifest("9.9.9","http://127.0.0.1:<p>/pkg.bin",65536L,"<real>")`; `UpdateDownload.INSTANCE.download(roots,m,l->kotlin.Unit.INSTANCE)`; second `download` + `downloadedIfVerified`; count `pkg.bin` GETs in server.log before/after; [PY] `hashlib.sha256` + size of the staged file | none | Check → `Available` downloadable (`UpdateCheck.kt:35-39,66-79`); staged file at `<roots>/settings/downloads/pocketrealm-update` (`UpdateCheck.kt:119` stagingDir — the DESK-32 recorded staging leg) with exact size+sha256 (length+digest gates, `UpdateDownload.kt:150-170`); the re-download makes **zero** new requests (verified-copy reuse, `:62-65`) and `downloadedIfVerified` returns the same path. **→ EXECUTED round 3: `D4 verdict=Available(manifest=UpdateManifest(version=9.9.9, packageUrl=…, size=65536, sha256=dd85007c…))`; `D9 f1exists=true size=65536` with [PY] staged-file `sha16=dd85007c333da36a size=65536` == expected; `D10 reuse=pocketrealm-update size=65536 verified=true` with server requests `before=9 after=9` (zero new)** | P1 |
| LV-desktop-app-29 | Download resume from .part + checksum-mismatch discard (mixed; DESK-45) | vanilla | [PY] seed `settings/downloads/pocketrealm-update.part` = first 32 KiB of pkg + `.etag` sidecar; run row-28 server+probe with the correct manifest → then repeat with `sha256` = 64×`f` (both on fresh roots AND after a verified base exists — the two sequences behave differently, executed round 3) | none | Resume leg: server.log shows exactly one request with `Range: bytes=32768-` answered 206 (`UpdateDownload.kt:100-116`); final file complete+verified; `.part`/`.etag` consumed on success (`:96,166-167`). Mismatch leg: `IOException: checksum mismatch - download discarded` (`:160-164`) — the tampered BYTES never persist. **Round-3 executed corrections: (a) on a FRESH roots the staging dir is left with the `.etag` sidecar (staging=`[pocketrealm-update.etag]`), not empty — `fetchInto` writes the etag before the sha gate; (b) when a verified base already exists, the fresh attempt's cleanup deletes it first (`:89-95`) before refetching, so a mismatch after a good download leaves only the `.etag`. → EXECUTED round 3: `D6 resumed=true` after `GET /pkg.bin range=bytes=32768-`; `D9 fresh-mismatch REFUSED checksum mismatch - download discarded staging=[pocketrealm-update.etag]`** | P1 |
| LV-desktop-app-30 | Consented apply: refusal contract + image swap mechanics (DESK-104) | vanilla | [PY] build fake current image `<R>/apps/PocketRealm/{PocketRealm.exe,app/app.jar}` and staged zips `pkg.zip` (with `PocketRealm/PocketRealm.exe` + `PocketRealm/app/app.jar`), `bare.zip` (no launcher), `pkg.msi`, `bad.exe`; [JSH] probe: `var ua=com.pocketrealm.desktop.UpdateApply.INSTANCE; System.out.println(ua.applyDownloadedUpdate(pkg,img,false)); System.out.println(ua.applyDownloadedUpdate(pkg,null,true)); System.out.println(ua.applyDownloadedUpdate(pkg,img,true)); System.out.println(new File(img.getParentFile(),"PocketRealm.old").exists()); System.out.println(pkg.exists());` + bare/msi/bad variants; [PY] verify new exe content in the image | none | consent=false → `Refused(Install cancelled - nothing was changed…)` and image byte-untouched (`UpdateApply.kt:47-59`); null image → `Refused(No jpackage app image detected…)`; valid zip → `Swapped(<img>)` with new exe in place, backup `.old` removed, staged zip preserved (`:89-134,186-199`); `bare.zip` → `Refused` (no launcher); `pkg.msi` → `Refused` (unknown shape); `bad.exe` → `Failed(cannot launch the installer…)` (`:141-151`). **→ EXECUTED round 3 (fresh jshell — note `File.readText()` is invisible to jshell, read via `Files.readAllBytes`, legend 3b): `consentFalse=Refused(Install cancell…`; `noImage=Refused(reason=No jpackage app image det…`; `swap=Swapped(newImage=C:\…) newExe=true oldGone=true zipSurvives=true`; `bare=Refused(reason=The app-image z…) exeStillNew=true`; `msi=Refused(reason=The staged pack…`; `badExe=Failed(reason=cannot launch th…` — every refusal left the image's exe byte-identical** | P1 |
| LV-desktop-app-31 | Shared-source manifest drift fence (DESK-130) | vanilla | `cd /e/pocket_realm_complete && python tests/test_desktop_shared_manifest.py; echo EXIT=$?` | none | `PASS <check>` per check then `OK: all N manifest checks passed`, EXIT=0 (direct-run support documented in the file's own header) — no android-coupled file slipped into `desktop/shared-sources.json`, no silent shared-set growth. **→ EXECUTED round 2: `EXIT=0`, `OK: all 7 manifest checks passed`** | P1 |
| LV-desktop-app-32 | Interaction: staged settings drive the world console; console protocol honest-negatives (settings↔world console; DESK-96/41 lane + RpHost) | vanilla | (0) `cp "$LOCALAPPDATA/PocketRealm/settings/settings.json" "$TEMP/lv-desktop-app/settings-backup.json"`; [PY] rewrite the real settings.json with `clientDir` pointing at a CLI-fabricated folder (if rpHost ever preflight-fails on seeds: `cd desktop && ./gradlew.bat seedRealmData --console=plain` first); (1) [RPH] `h=DesktopHost('.'); print(h.wait_ready(timeout_s=900)); print(h.hello()); print(h.send_raw({'op':'nosuchop'})); print(h.online()); h.close()`; (2) [PY] restore backup and assert `filecmp.cmp` against it | none (REAL-ROOTS row; natives+seeds staged) | `{"event":"ready","world":"READY"}` — rpHost resolved `clientDir` from the staged settings (`RpHost.kt:42-45`); `hello` `ok=true`; unknown op answers `ok=false, reason=unknown-op:nosuchop` (`RpHost.kt:262`) instead of dying; EOF drain saves (`:270-273`); settings.json restored byte-identical | P1 |
| LV-desktop-app-33 | Interaction: support bundle over the REAL app state — real members, real-secret redaction (DESK-119/110/112 live; REAL-ROOTS, additive only) | vanilla | [JSH] probe (no writes to app state except the bundle zip itself): `var roots=new DesktopStorageRoots(null); System.out.println(new com.pocketrealm.desktop.DesktopSupportBundle(roots,null,new com.pocketrealm.desktop.DesktopCrashEvidence(new File(System.getenv("LOCALAPPDATA"),"CrashDumps"),n->"")).export());` then [PY]: read the real key value from `%LOCALAPPDATA%/PocketRealm/settings/settings.json` (`llmExternalApiKey`), open the produced zip in `runtime/logs/`, assert members `settings.json`+`logs.json`+`supervisor-journal.json` (if present) exist and the key value appears in **no** member; print manifest.json header | none | Export succeeds against real state (bundle lands next to the logs, `DesktopSupportBundle.kt:60`); manifest header fields present; the real `llmExternalApiKey` value (present in this host's live settings — verified this session) does NOT survive into any zip member (the raw-bytes canary lane, `:136-150`); WER member (if any dumps exist) is metadata-only. **→ EXECUTED round 4: `Created 8 entries - support-bundle-20261002-162458.zip` on the REAL roots; [PY] `members=8`, header keys `appVersion/flavorId/logs.json/nativeRuntimeBuildId/redaction/schema/topology` + members `settings.json/supervisor-journal.json/logs.json/data-active.json`; the real 125-char key in `leaks=[]`; `topology={'lanAddress':'','runtimeMode':'LOCAL'}` — matching the host's real settings** | P1 |
| LV-desktop-app-34 | Client exit journal + crash evidence under a real client death (DESK-112/121/107 write legs) | vanilla | **DEDICATED-SLOT ROW — NOT unattended:** it launches a real WoW.exe window and Win32AutoLogin types into it (`RpHost.kt:94-99`, `Win32AutoLogin.tryLogin`), stealing desktop focus and violating environment.md's "no window, no focus steal" contract (`environment.md:97-99`) while other rows run. Run ONLY in a scheduled slot where host takeover is acceptable, user informed. All read-side contracts are already covered offline by rows 20/21/22/33; window-free launch-adjacent contracts (auth/char-enter over the wire) are covered by the proto-* rows in the other matrices (bot-actors-04, multiplayer-06) — this row owns only the REAL-process death journal. REQUIRES ARTIFACT R3 (vanilla 1.12.1.5875 client, requirements.md §2-R3). Stage clientDir at the R3 folder in settings.json; [RPH] `h=DesktopHost('.', client=True, account='rptest', password='rptestpass'); h.wait_ready(timeout_s=900)`; read the client pid from the `{"event":"client","pid":N}` line; `taskkill /PID N /F`; [PY] assert `%LOCALAPPDATA%/PocketRealm/runtime/server/lifecycle/client.json` now exists with `clean:false` (producer shape `ServerRuntimeFiles.kt:440-449`); re-run row-33's export probe; [PY] assert zip member `crash/last-client-exit.json` with `clean==false` | **R3 — vanilla 5875 client (later stage; dedicated supervised slot)** | client event observed; `client.json` records pid/exitCode with `clean=false`; the next bundle carries `crash/last-client-exit.json` (ABNORMAL) — the Diagnostics "Last client exit: pid … (ABNORMAL)" line's exact data (`DiagnosticsScreen.kt:324-334`) | P1 |
| LV-desktop-app-35 | Client-selection affordances: 'Change game folder…'/'Import client…' as generation switcher (DESK-135 — this matrix OWNS it; the GUI click itself stays out of scope, its state contract is CLI-assertable) | vanilla | [TST] `./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopClientSelectionStateTest" --console=plain` (drives the real `DesktopAppModel.clientSelectionState()`/`resolveClientDir()` through NONE → FOLDER → MANAGED_GENERATION with synthetic clients and a genuinely published generation — `DesktopClientSelectionStateTest.kt:44-104`, using the shared `SyntheticClientArchives` fixtures compiled into this lane per `desktop/build.gradle.kts:86-90`); source pins read this session: the affordance renders from that state (`DesktopAppModel.kt:484-492`), `ActiveSetupCard` renders it (`HomeScreen.kt:319,647`), and the routes exist (`Router.kt:8-38`) | none | BUILD SUCCESSFUL exit 0 — the state machine the two affordances render (NONE = picker-only, FOLDER, MANAGED_GENERATION = generation named) is pinned including the wrong-build-folder-reads-as-None negative (`:103-104`); a GUI-click walkthrough remains out of scope by rule (no screenshots/manual steps) and is so recorded. **→ EXECUTED round 4 (batched [TST] run): DesktopClientSelectionStateTest tests=4 fail=0 err=0** | P2 |
| LV-desktop-app-36 | Cross-feature interaction: active-generation switch × add-on projection × WTF rooting (DESK-67 pointer × DESK-40/84 projection × DESK-96 rooting; the pointer store path mirrors the import matrix's LV-flavor-import-22) | vanilla | Isolated roots. (1) [PY] fabricate two generation dirs `content/client/generations/<uuid1>` and `<uuid2>` (UUID form — `activeGeneration` UUID-validates, `ClientGenerationStore.kt:74-75`), each with `WTF/Config.wtf` (`SET gxResolution "800x600"` vs `"1024x768"`); [PY] atomic-write the per-flavor pointer `content/client/active-vanilla.json` = `{"schema":1,"generation":"<uuid1>","clientId":"vanilla","flavor":"vanilla","manifestSha256":"<64f>","activatedAtMs":<now>}` (exact producer shape `:168-171`; name per shared `GenerationPointers.activePointerName`, `:34`); (2) [JSH] `var st=new com.pocketrealm.importer.ClientGenerationStore(roots); System.out.println(st.activeGeneration());` → uuid1; [JSH] project + editor against `generations/<uuid1>` (rows 11/04 probes); [PY] snapshot dir1's `Interface/AddOns/.pocketrealm-managed.json` + AndroidPort tree; (3) [PY] atomic-move the pointer to uuid2; (4) [JSH] `st.activeGeneration()` → uuid2; re-run projection + editor probes against `generations/<uuid2>`; (5) [PY] diff dir1 against the snapshot | none | Step 2: pointer read returns uuid1; dir1 gets `Interface/AddOns/AndroidPort` + ownership file, and `readConfigValues` reads dir1's WTF (`DesktopAppModel.kt:421-427` roots the editor at the resolved client dir; `DesktopAddonRuntimeProjector.kt:33-157` writes ownership under the root it is handed). Step 4: pointer switch honored (uuid2); projection and WTF reads/writes now target dir2 only. Step 5: dir1 byte-unchanged — its managed tree is NOT silently re-projected or cleaned (projection is per-client-dir; only the handed root is touched). **→ EXECUTED round 4 (probes C/C2, addons tree seeded from row-10's published registry; pointer switched in-probe via atomic move): `active1==uuid1`; `project1=[AndroidPort]` + dir1 ownership `{"schema":1,"folders":["AndroidPort"]}` + toc; `active2==uuid2` after the atomic switch; `project2=[AndroidPort]` + dir2 ownership; `cfg1={gxResolution=800x600}` → `cfg2={gxResolution=1024x768}` (WTF root followed); dir1 kept its 11-file managed tree (toc present) — never re-projected** | P1 |

**Row count: 46 rows** (LV-desktop-app-01 through LV-desktop-app-45 plus the EX-111 disposition row, one row per test; row 37 = the G3-10 frozen-pins row added at audit direction, mirrored as LV-bot-actors-42; rows 38–45 and EX-111 added at final-audit direction). Artifact-free now-tests: 01–13, 15 (offline legs), 17–45 (38–45 artifact-free; EX-111 is a disposition, not an executable). **[NET] now-tests confirmed by the executed [EGRESS] check** ([RAN round 2]: `HTTP/1.1 200 OK`): 14, 16, and row 15's gated 404 leg — they run as now-tests on this host today and fall back to deferred only if a future egress check fails. Artifact-requiring + dedicated supervised slot (later stage): 34.

## Artifact requirements

- Only **R3** (requirements.md §2: vanilla 1.12.1 build 5875 client) is required by this area — row 34. `needsArtifacts = ["R3"]`. Row 34 additionally requires a **dedicated supervised slot** (real WoW.exe window + focus-stealing auto-typing — see its mechanism cell); it is never scheduled inside the unattended lane.
- Rows 14/16 and row 15's 404 leg use **[NET]** (api.github.com + a public add-on repo); no user artifact, but they are gated on the **[EGRESS]** pre-check and re-classified deferred when the host is offline (egress never verified for this host — environment.md verified WHPX/adb/AVDs only).
- Not needed by this area: R1/R2 (tbc/wotlk clients), R4 (forks), R5 (MariaDB), R6 (GGUF), R7 (tunnel).

## Honesty notes

- **Round-4 status: 34 of 37 rows executed.** Newly executed this round: 05, 06, 07, 08 (harness leg), 09, 14, 15, 16 (equal branch), 17, 22 (harness leg), 24 (leg a), 27, 33, 35, 36 — live over the network for 14/16. Remaining designed-only: 24 (leg b, rpHost world boot), 32 (rpHost interaction), 34 (R3 artifact + dedicated slot), plus the differ-branch sub-assertion of 16, row 08's jshell store leg, row 22's python file legs, row 25's probe B/negative leg, and row 15's 404 leg. **Round-4 corrections folded back:** (a) row 05's staged edit must use a stageable cvar — `graphics.resolution` carries a fixedReason and is refused while running (`"Resolution" cannot be changed outside the game`, executed) while remaining direct-editable while stopped (row 04); (b) row 06's refusal messages are lane-dependent — stopped→`log in once in game to edit key bindings` (applyDirect's file check, `:293`), running→`unknown binding command: …` (`:328-329`); (c) addon ops are ASYNC — refusals land in `state.errorTitle`/`state.error`, ops issued while another is active are silently dropped (`:284`), `get()` is a process-wide singleton (`:984-989`) (legend 6); (d) row 15's rollback refusal needs `registry.previous.json` deleted post-publish — after the fresh-install seed the previous set exists (possibly empty) and rollback legitimately SUCCEEDS restoring it (executed); (e) row 27's require messages are not carried into the display copy — only `Update check failed: IllegalArgumentException`; (f) row 33's real-state bundle executed with the real 125-char key absent and real topology in the header.
- **Round-3 status (retained): 19 of 37 rows executed** (01, 02, 03, 04, 10, 11, 12, 13, 18, 19, 20, 21, 23, 25, 26, 28, 29, 30, 31 — plus row 37 executed by the bot-actors section per its own annotation, and the [EGRESS] gate). Round-3 corrections: (a) the [JSH] addon probes need cwd = repo root (`DesktopAddonAssets` walk-up — observed honest error state otherwise); (b) row 12's packagePath legs need a non-builtin registry id (the built-in never consults packagePath); (c) rows 28/29's feed FILE carries the bare URL, not the JSON body (`UpdateCheck.kt:61-65` tests the whole file text — executed: a JSON body reports the https refusal); (d) row 29's mismatch leaves the `.etag` sidecar (staging=`[pocketrealm-update.etag]`), not an empty dir, and a pre-existing verified base is consumed by the next attempt's cleanup (`:89-95`); (e) two jshell interop rules (no casts to nested sealed classes; no `File.readText()`). One probe-side sequencing artifact is recorded honestly: in the first row-30 run a compile-failed println still let its `applyDownloadedUpdate` var-snippet execute, so the swap happened before the consent-false leg was re-run — the final executed copy re-ran consent-false and the three refusal legs against the swapped image and all left it byte-identical; the image-untouched-under-refusal contract is additionally pinned by `UpdateCheckTest.applyWithoutConsentChangesNothing`.
- **Round-2 status (retained): 7 rows executed** (03, 04, 13, 18, 19, 26, 31) + egress gate. Corrections then: `$default` bridges uncallable from jshell; DESK-54's seam refusal surfaces via the folder-guard message; row 19's file lane is `runtime/logs/pocket-realm.log`.
- **Round-1/audit history retained:** round 1 authored the 34-row design with a jshell interop smoke (`SMOKE-OK`); audit round 1 gave DESK-135 an owner (row 35, [TST] lane), added DESK-17 to row 10's feature cell, gated rows 14/16 (+15's 404 leg) on [EGRESS] with deferred re-classification, fenced row 34 to a dedicated supervised slot with the window-free proto-* coverage cross-referenced, pinned row 16's verdict to curl-resolved ground truth, and added row 36 (generation-switch × projection × WTF rooting, grounded in `ClientGenerationStore.kt:74-75,158-185`, `GenerationPointers:30-34`).
- **Round-2 external addition (by the bot-actors section, audit-round-1 direction):** row 37 (G3-10 vanilla frozen pins — `VanillaCatalogPinsTest` + `WowVanillaBindingCatalogTest` + `InGameSettingsFilesLayoutPinTest`, android app module [TST]) inserted next to row 07 per the audit ("same mechanism"). The audit-round-1 fix originally recorded this row only in `matrix-bot-actors.md` (row 42) because this file did not exist yet; the row now lives here and is mirrored in matrix-bot-actors.md §5. ID 37 was free (previous max: 36). NOT executed — designed row. **Audit round 2 (bot-actors section edit):** the invocation now carries the mandatory `-PpocketAbi=x86_64` + the Git-Bash `cmd //c` form (build.gradle.kts:948-951).
- `WowVanillaSettingsCatalog.byId("graphics.resolution")` and binding command `CAMERAORSELECTORMOVE` were verified against the catalog sources this session (`android/app/src/main/java/com/pocketrealm/ingame/WowVanillaSettingsCatalog.kt`, `client/WowVanillaBindingCatalog.kt`); the `Edit.CvarSetting` jshell construction in row 04 follows the same interop rules as the smoke but its catalog-object call was not itself smoke-run.
- `UpdateCheckTest` (desktop test, `UpdateCheckTest.kt:53-71`) adds `server.address.hostName` to `testAllowedHttpHosts` — the production seam this matrix reuses via the mangled getter; from jshell it was verified callable and effective (`seamHosts=[127.0.0.1]` in the smoke).
- The bundle writes into `runtime/logs/` (not the `exports/` root — exports hold realm archives; `DesktopSupportBundle.kt:60` vs `DesktopStorageRoots.kt:57-59`). Rows 21/33 assert the real path.
- https-only update fetches cannot be happy-pathed live without a JVM-trusted cert; the matrix therefore (a) exercises the production https-refusal negatives (rows 26), and (b) happy-paths the download/apply over the code's own documented test seam (rows 27–30), mirroring `UpdateCheckTest`'s local-HTTP fixture. A real-HTTPS feed test would additionally need a CA-imported truststore (`-Djavax.net.ssl.trustStore`) and is out of scope for round 1.
- rpHost world-boot rows (24b — row 24's leg (b), 32, 34) inherit the environment decision's caveat that the full bridge recipe was untested end-to-end as of environment.md:142-145; the desktop half alone (in-process boot) is the part exercised here — no emulator needed for this area.

## Checks run during this ask (commands + outputs)

### Round 4 (this ask)

All [JSH] probes against the round-1 fat jar with cwd = repo root (legend 4); fixtures under `%TEMP%\lv-desktop-app\round4\`.

- **Rows 05/06/09/15** (`probeA.jsh`, first pass): surfaced three executed-truth corrections — `graphics.resolution` refused in the queue lane (`"Resolution" cannot be changed outside the game`); stopped-lane unknown-command returns the file-check message; `rollback()` on a fresh roots did NOT throw (async state + seed had already published a previous set); a gitlab `install()` issued while busy was silently dropped (state stayed `err=null`). `R09 addons=154 a151=Android Port/BUILTIN/builtin__androidport` executed clean.
- **Rows 05/06** (`probeA2.jsh`, corrected): `R05 running=Landed(lane=QUEUED_FOR_CLEAN_STOP) queued=1` → `R05 drain=applied 1 queued in-game change(s)` → `R05 cfg={gxVSync=1} queuedAfter=0`; `R06 stoppedUnknown=Refused(reason=log in once in game to edit key bindings)`; `R06 runningUnknown=Refused(reason=unknown binding command: TOTALLY_UNKNOWN_CMD)`; `R06 runningNoScope=Refused(reason=log in once in game to edit that scope's bindings)`; `R06 runningNoUvar=Refused(reason=log in once in game to edit saved variables)`.
- **Row 15** (`probeA3.jsh` + `probeA4.jsh`): `A3 gitlab title=Could not install add-on err=Use a public GitHub URL such as https://github.com/owner/addon installed=1`; `A4 canRollback=false` → `rollback title=Could not restore add-ons err=There is no previous add-on set to restore installed=1` (previous-registry deleted post-publish). Also executed: post-seed rollback legitimately succeeds restoring the previous (possibly empty) set (`A3 notice=Android Port and its matching controls are ready for the next game launch. installed=1`).
- **[TST] batch** (one gradle invocation, five classes): `TST_EXIT=0`, `BUILD SUCCESSFUL in 5s`; per-class XML: CatalogGoldenPinsTest 5, AddonOperationSafetyTest 3, DesktopClientSelectionStateTest 4, Win32GamepadBridgeTest 2, ServerStatusJsonTest 6 — TOTAL 20, failed 0. Plus `DesktopCrashEvidenceTest` → `CE_EXIT=0` (row 22's harness leg). Log: `out/live-verification/round4-tst.log`, `round4-crashevidence.log`.
- **Row 36** (`probeC.jsh` + `probeC2.jsh`): pointer `active1==uuid1` → projection `[AndroidPort]` + dir1 ownership → atomic pointer switch → `active2==uuid2` → `project2=[AndroidPort]` + dir2 ownership; editor `cfg1={gxResolution=800x600}` → `cfg2={gxResolution=1024x768}` (WTF root followed the switch); dir1 kept its 11-file managed tree, never re-projected. First pass (no addon registry in the fixture) honestly returned `project1=[]` — fixture corrected before the pass.
- **Row 27** (`feedcycle.py` body-cycling server + `probeD.jsh`): `R27a/b Unavailable(reason=Update check failed: IllegalArgumentException)` (partial legs / malformed sha — messages not carried into the display copy); `R27c Available(manifest=UpdateManifest(version=9.9.9, packageUrl=null, size=null, sha256=null))`.
- **Row 33** (`probeB.jsh` on the REAL roots): `Created 8 entries - support-bundle-20261002-162458.zip`; [PY]: `members=8`, header + `settings.json`/`supervisor-journal.json`/`logs.json`/`data-active.json` members present; the REAL 125-char `llmExternalApiKey` in `leaks=[]`; `topology={'lanAddress':'','runtimeMode':'LOCAL'}` (== the host's real settings).
- **Rows 14/16** ([NET], live): ground truth resolved first via the resolver's own precedence (`releases/latest` → tag `7.0.1` → commit `6c5bff62b35b…`); `R14 notice=pfQuest is ready for the next game launch.` with registry `commitSha=6c5bff62b35b…` (40-hex, equals ground); `R16 installedShaEqualsGround=true` → `R16 verdictEqual notice=Installed add-ons are up to date. updatesEmpty=true`; `canRollback=true` → `R14 rollback=Previous add-on set restored for the next game launch. installed=1`.
- **One self-inflicted structural defect, found and fixed within this round:** my rows-14/15 rewrite initially dropped row 16 (the old_string spanned three rows, the replacement contained two). Caught by the row-count check (36 vs 37), restored in place with its executed annotation.

### Round 3 (prior ask, retained)

All probes against the round-1 fat jar (`pocketrealm-desktop-app-0.103.0-alpha.jar`), fixtures under `%TEMP%\lv-desktop-app\round3\` built by python heredocs.

- **Rows 01/02/23/25** [JSH] (`probeA.jsh`): `R01 fresh=LOCAL/true` · `R01 rt=true tmp=0` · `R02 corruptLoad=LOCAL` · `R23` three humanized copies printed (multi-word, ≠ raw codes) · `R25 ok=true roots=12 under=true` · `ROUND3-A-OK`.
- **Row 20** [JSH] (`probeA20.jsh`, after adding explicit caps — the one-arg calls failed on default params, legend 3): `crash=[Errors/Errors-2026-10-06.txt, …, Errors/Errors-2026-10-02.txt]` (6 fabricated → 5 newest), `wer=[WoW.exe.100.dmp (4 KiB), WoW.exe.101.dmp (4 KiB)]` (Other.exe filtered), `noneWer=0` on empty dumps, no throw.
- **Rows 10/11/12a/12c** [JSH] (`probeB1b.jsh`, run with cwd=`/e/pocket_realm_complete` after the cwd diagnosis): seed produced `installed=[InstalledAddon(id=builtin__androidport, … commitSha=7017eda00a0be73c6d876acb63409e1b54daf0b3, archiveSha256=7017eda0…77d97bad0addf16e9d0fde2f, packagePath=packages/builtin__androidport/7017eda0…, folders=[AndroidPort])]`, `registry=true`; [PY] `schema=1 folders=['AndroidPort'] tocInterface=11200`; `R11 applied=[AndroidPort]` + ownership `{'schema':1,'folders':['AndroidPort']}` + toc exists; `R12a safe=[]`; `R12c REFUSED Addon 'AndroidPort' conflicts with a client-owned addon; remove or rename it before enabling this package`. **Negative cwd control (probeB1.jsh from the wrong cwd): 60 s poll → `installed=null`, `registry=false`, projection `[]` — the honest error state the cwd rule prevents.**
- **Rows 12b/d/e/e2** [JSH]+[PY] (`probeB2.jsh`, `probeB2b.jsh`; crafted registries from row-10's real bytes): `R12b REFUSED JSONException: Expected a ':' after a key at 8 [character 9 line 1]`; first craft with the BUILTIN id returned `The bundled Android Port add-on assets are missing` (assets unresolvable from wrong cwd) — re-crafted with `id=shagu__pfquest` → `r12d REFUSED Unsafe addon package path`; `r12e REFUSED Installed addon folder is missing: Nope`; `r12e2 REFUSED Addon package is unavailable`.
- **Row 21** [JSH]+[PY] (`probeC.jsh`): `Created 8 entries - support-bundle-20261002-154607.zip`; [PY] `members=8 sha_mismatches=[]`; header keys `['appVersion','flavorId','logs.json','nativeRuntimeBuildId','redaction','schema','settings.json','supervisor-journal.json','topology']`; `canary_leaks=[]` (`SK-CANARY-abc123` sat in settings.json AND mangosd.conf); `log_tail_bytes=262102` (≤ 262144, line-aligned); `dmp_entries=[]`; `wer=[{'lastModifiedMs':…,'bytes':2048,'name':'WoW.exe.9.dmp'}]`; `logs.json` parses.
- **Row 28** [PY server]+[JSH] (`rangeserver.py`/`feedserver.py` on 127.0.0.1, `probeD4/D6/D9/D10.jsh`): `D4 verdict=Available(manifest=UpdateManifest(version=9.9.9, packageUrl=http://127.0.0.1:8911/pkg.bin, size=65536, sha256=dd85007c…))` (feed file = bare URL after the executed correction); `D9 f1exists=true size=65536`; [PY] staged `pocketrealm-update` `sha16=dd85007c333da36a size=65536` == expected; `D10 reuse=pocketrealm-update size=65536 verified=true` with server requests `before=9 after=9`.
- **Row 29** [JSH] (same probes): `D6 resumed=true` after `GET /pkg.bin range=bytes=32768-` (206); mismatch legs: `D6 mismatch REFUSED checksum mismatch - download discarded` and `D9 fresh-mismatch REFUSED … staging=[pocketrealm-update.etag]` (fresh roots leave the `.etag`; a prior verified base is consumed by the next attempt's cleanup).
- **Row 30** [JSH]+[PY] (`probeD7/D8.jsh`): `consentFalse=Refused(Install cancell…`; `noImage=Refused(reason=No jpackage app image det…`; `swap=Swapped(newImage=C:\…) newExe=true oldGone=true zipSurvives=true`; `bare=Refused(reason=The app-image z…) exeStillNew=true`; `msi=Refused(reason=The staged pack…`; `badExe=Failed(reason=cannot launch th…`. Recorded probe-side artifact: in D7 a compile-failed println still executed its `applyDownloadedUpdate` var-snippet (the swap landed before the consent-false re-run); the re-run proved every refusal leaves the image byte-identical.
- **Execution-driven design corrections folded back**: cwd rule (legend 4 + rows 09–12); non-builtin id for row 12 d/e; bare-URL feed file for rows 28/29; row 29's executed mismatch residue (`.etag` remains); jshell interop rules (legend 3): no casts to nested sealed classes, no `File.readText()`, explicit caps for row 20's default args.

### Round 2 (prior ask, retained)

- **[EGRESS]**: `curl -sI -m 10 https://api.github.com | head -1` → `HTTP/1.1 200 OK`, `CURL_EXIT=0` — rows 14/16 confirmed as now-tests.
- **Row 31** [PY]: `python tests/test_desktop_shared_manifest.py` → `EXIT=0`, tail `PASS test_unshared_allowlist_entries_stay_accurate` / `PASS test_unshared_android_free_files_are_shared_or_allowlisted` / `OK: all 7 manifest checks passed`.
- **Row 18 leg 1** [GATE]: `cd desktop && ./gradlew.bat validateNativeRuntimePins --console=plain` → `PINS_EXIT=0`; `Native runtime freshness fence PASSED for desktop pins-only (sqlite-win) (3 lockfile pin(s), staged bytes: not required).` / `BUILD SUCCESSFUL in 1s` (log: `out/live-verification/round2-pins.log`).
- **Row 18 leg 2** [GATE]: same task with `-PpocketSkipNativeFreshness=bogus` → `BOGUS_EXIT=1`; `Exception in thread "main" java.lang.IllegalArgumentException: Refusing ambiguous -PpocketSkipNativeFreshness='bogus'; pass exactly 'true' to skip loudly, or drop the property to run the fence` (log: `out/live-verification/round2-pins-bogus.log`).
- **Rows 03/04/13/19/26** [JSH]: one combined probe (`%TEMP%\lv-desktop-app\round2\probe.jsh`) against the round-1 fat jar, fixtures built by python (foreign-schema queue.json; fabricated WTF tree; 5 validator zips). Output lines: `R03 queued=0` · `R04 outcome=Landed(lane=APPLIED_DIRECT) cfg={gxResolution=1024x768} queued=0` · R13 (isolated re-run after the arity fix): `good=[MyAddon]`, `tbc.zip=REFUSED Each addon must contain Folder/Folder.toc or a repository-root .toc`, `empty.zip=REFUSED No Vanilla addon .toc file was found`, `rootgood=[MyAddon]`, `roottbc=REFUSED` (same guard message) · `R19 ring=800` + `ring.json entries=800 first=m50 shape=['kind','level','msg','ts']` + `runtime/logs/pocket-realm.log lines=850` · `R26a No update feed configured (…)` · `R26b Unavailable(reason=The configured update feed must be an https:// URL.)` · `R26c Unavailable(reason=Update check failed: ConnectException)` · `ROUND2-PROBE-OK`.
- **Execution-driven design corrections**: jshell rejects `$default` bridges on internal classes (`validate$default` → "cannot find symbol"; fixed by calling `validate` with explicit `Policy.Companion.getVANILLA()`), and row 19's file lane is `runtime/logs/pocket-realm.log`. Both folded back into the rows above.

### Round 1 (prior ask, retained for provenance)

- `cd /e/pocket_realm_complete/desktop && ./gradlew.bat appJar --console=plain` → exit 0; `build/libs/`: `pocketrealm-desktop-app-0.103.0-alpha.jar` (48,467,582 B), `pocketrealm-desktop-0.103.0-alpha.jar` (pre-existing). Log kept at `out/live-verification/appJar-smoke-build.log`.
- jshell smoke (fat jar classpath, isolated `DesktopStorageRoots` under `%TEMP%/lv-desktop-app/smoke2`): printed `roundtrip=true`, `seamHosts=[127.0.0.1]`, `checkOnce=No update feed configured (place a feed URL in update-feed.url under C:\Users\<owner>\AppData\Local\Temp\lv-desktop-app\smoke2\settings to enable the check).`, `cfg={gxResolution=800x600}`, final line `SMOKE-OK`. (One expected probe-side `NoSuchFileException` on my own pre-mkdir — the editor call then succeeded; noted, not a product failure.)
- Host state: `ls $LOCALAPPDATA/PocketRealm` → `content database exports realm runtime settings user-account` (no `addons/`); settings.json keys printed incl. `runtimeMode=LOCAL`, `clientDir=C:\Wow clients\Installed\WoW_Classic_ENG_1.12.1`, `llmExternalApiKey` present.
- Toolchain: `java -version` → Temurin 17.0.20+8; `where jshell` → `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot\bin\jshell.exe`.
- Staging presence: `ls native/.build-win-x86_64/pocket-runtime-build/*.dll sqlite-seam-build/*.dll` → both realm DLLs + `pocket_sqlite.dll`; `ls native/.build-o09-x86_64/realm-staging-sqlite/assets/seed/` → the four `.sqlz` transcripts.
- Catalog asset parse (python): entry `151` → `Android Port builtin installId=None assetPath=addons/android-port`; `GitHubRepoRef.parse` refusal message read at `AddonModels.kt:55`; `RuntimeFailureCopy` branch codes read at `RuntimeFailureCopy.kt:16-60`.
- javap signature checks (fat jar): `DesktopStorageRoots(File)`+getters, `DesktopSettingsStore(File,Function0)`, `UpdateCheck` (`getTestAllowedHttpHosts$pocketrealm_desktop`, `checkDetailed`), `UpdateDownload.download$default`, `UpdateApply.applyDownloadedUpdate(File,File,boolean)`, `DesktopSupportBundle(roots,File,crashEvidence)`+`export()`, `DesktopInGameSettingsEditor(File,File,Function0)`, `DesktopAddonRepository$Companion.get(roots)`, `AddonArchiveValidator()`+`validate$default`, `UpdateCheck$UpdateManifest(String,String,Long,String)`, `DesktopCrashEvidence(File,Function1)`.
- NOT run: any of rows 01–34 end-to-end; any gradle `test` lane; any rpHost boot; any python HTTP fixture; no repo source file was modified (only `out/live-verification/appJar-smoke-build.log` written and build outputs under `desktop/build/`).

## Final-audit coverage round (2026-10-02, plan-repair)

Added after the final coverage audit (`python tmp/final-coverage-audit.py` over the
assembled plan) reported zero embedded test rows for the desktop `onboarding-autologin`
(DESK-16/31/49/92/105/136/138 — campaign-inventory.md:140-148), the `supervisor-journal`
(DESK-30/71/111/113/114/25 — :89-95,227) halves this file owns, and the DESK-25
spent-world restart path. No existing row was edited; rows 38–45 + EX-111 are new.
[CLASS-VERIFIED] = the named test class and its cited test methods were read this ask
(path:line); the gradle legs themselves are execution-round work. [X] = executed this ask
(output quoted).

| testId | feature | flavor(s) | exact CLI mechanism | required artifacts | pass condition | severity |
|---|---|---|---|---|---|---|
| LV-desktop-app-38 | First-run tutorial & setup shortcuts — the compose-free legs (DESK-16/31/49/92/105) | vanilla | **[TST]** `cd /e/pocket_realm_complete/desktop && ./gradlew.bat test --tests "com.pocketrealm.desktop.FirstRunTutorialTest" --console=plain` | none | rc=0; the seven legs green [CLASS-VERIFIED FirstRunTutorialTest.kt:29-94]: guide shows until setup seals (`theGuideShowsUntilSetupSeals` :29), replay overrides the seal (:35), step walk bounded (:43) and walks Import→Prepare→Start (:51), a fresh model shows the guide and finishing seals it PERSISTENTLY (:68), replay from settings re-shows until consumed (:81), prepare step reports the seeded datadir honestly (:94). The rendered-Compose-pixels half has no desktop compose-test lane (same shape as LV-flavor-import-EX-135); the seal/state machine is the behavior with live consequence and it is what this row pins | P1 |
| LV-desktop-app-39 | Single-owner runtime domain — AppInstanceLock tryLock + SecondInstanceRefusalDialog data (DESK-113) | vanilla | **[TST]** `./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopAppModelSingleInstanceTest" --console=plain`; **[JSH]** live two-process leg [X-shaped, per legend interop rules]: process A acquires `AppInstanceLock.acquire(new File(roots.getRealm()))` on an isolated roots dir; process B (second jshell) calls the same → `AppInstanceLockHeld` carrying A's pid | none | gradle rc=0: `firstInstanceStampsItsPidAndReleasesOnClose` (:23) and `secondInstanceRefusesNamingTheHolderPid` (:44) green [CLASS-VERIFIED DesktopAppModelSingleInstanceTest.kt:18-58, AppInstanceLock.kt:46-82 `acquire` throws `AppInstanceLockHeld(holder, file)`]; the live pair reproduces the refusal — second acquire raises `AppInstanceLockHeld` naming the holder pid; release lets the retry succeed | P1 |
| LV-desktop-app-40 | Client observation, orphan adoption, double-launch guard — the scanner seam (DESK-114) | vanilla | **[TST]** `./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopRuntimeBackendTest" --console=plain` (orphan/adoption legs :675-702 inject `ClientProcessScanner { listOf(scanRow(dir, 4242)) }` and assert adoption engages on a RUNNING ownerless client and refuses owned/stopped states); **[JSH]** live seam probe [X-shaped]: `com.pocketrealm.desktop.DesktopRuntimeBackend.ClientProcessScanner.Companion.getSYSTEM().scan()` with a `Wow.exe`-named copy of a system binary spawned in a temp dir → scan lists it (pid + full image path); kill it → scan returns empty | none | gradle rc=0 on the adoption truth-table legs [CLASS-VERIFIED]; live probe: the spawned lookalike IS enumerated with its path (name-based CLIENT_EXE_NAME match, DesktopRuntimeBackend.kt:59-70 [R]), an unattributable/unreadable process yields NO row, and after the kill `scan()` is empty — no stale pid is ever drained | P2 |
| LV-desktop-app-41 | Foreground promotion & world-presence verbs — honest ok=false (DESK-71) — **probe executed this ask** | vanilla | **[TST]** `./gradlew.bat test --tests "com.pocketrealm.desktop.Win32ClientWindowTest" --console=plain`; **[JSH]** live refusal legs [X]: windowless spawned process pid → `Win32ClientWindow.INSTANCE.promote(pid)`; `promote(0)` | none | gradle rc=0 (`promoteRefusesNonPositivePidsWithoutTouchingWindows` :16, `demoteTreatsNonPositivePidsAsNothingToDemote` :22 [CLASS-VERIFIED]); live [X]: `promote(0)` → `"client pid unknown"`; `promote(<windowless pid>)` → `"no visible window for pid <pid>"` — the honest refusal the DESK-71 register entry pins (Win32ClientWindow.kt:49-58 [R]: Win32 foreground restriction reports, never swallows). No window is opened by this row. The ok=true promotion leg belongs to the DEDICATED-SLOT rows that own a real client window (LV-bot-actors-38) | P2 |
| LV-desktop-app-42 | Spent-world restart path: spent detection + app-relaunch + relaunch handoff (DESK-25) | vanilla | **[TST]** `./gradlew.bat test --tests "com.pocketrealm.desktop.DesktopAppModelSpentWorldTest" --tests "com.pocketrealm.desktop.DesktopAppRelauncherTest" --tests "com.pocketrealm.desktop.RelaunchHandoffTest" --console=plain` | none | rc=0 across the three classes [CLASS-VERIFIED]: `freshModelReportsAnUnspentWorldLifetime` + `relaunchRefusalSurfacesHonestlyAndLeavesTheModelAlive` (DesktopAppModelSpentWorldTest.kt:25,31 — the DesktopRuntimeBackend.kt:2451 READY-spends-the-lifetime pin [R]); relauncher resolves/spawns/refuses honestly incl. `devRunWithoutAnImageRefusesWithoutSpawning` (DesktopAppRelauncherTest.kt:33); handoff stamp/wait/release semantics (RelaunchHandoffTest.kt:21-67). The LIVE app-relaunch spawn opens the app window — DEDICATED-SLOT disposition, ledgered like LV-flavor-import-17; this row owns the JVM-contract half | P1 |
| LV-desktop-app-43 | Graceful world save on OS shutdown/logoff — the drain half of the hook (DESK-111) | vanilla | **[TST]** `./gradlew.bat test --tests "com.pocketrealm.desktop.SaveExitDrainTest" --console=plain`; static registration leg: `grep -n "addShutdownHook" desktop/src/main/kotlin/com/pocketrealm/desktop/Main.kt` (hook at Main.kt:137, registered with the save-exit drain per Main.kt:129 comment [R]) | none | gradle rc=0: `windowCloseThenShutdownHookDrainsExactlyOnce` (first requester wins the once-flag, one drain, one close) and `shutdownHookWaitsForAnInFlightWindowCloseDrain` (SaveExitDrainTest.kt:26,43 [CLASS-VERIFIED]) — i.e. the hook logoff/shutdown/restart/taskkill all ride (`Main.kt:129` [R], `SaveExitDrain.kt:14` [R]). The OS-logoff TRIGGER itself is excluded → LV-desktop-app-EX-111 | P1 |
| LV-desktop-app-EX-111 | ACCEPTED EXCLUSION — DESK-111's OS-logoff/shutdown TRIGGER leg (`shutdown /l`, `logoff.exe`, WM_ENDSESSION): firing it ends the operator's Windows session and every process on the host, so it can never run in the unattended lane | vanilla | disposition row (no executable mechanism): the trigger leg is exercised only by the user, manually, if ever; everything mechanically verifiable about DESK-111 without ending the session is owned by LV-desktop-app-43 (drain-once + hook-waits + registration) | none | this row owns the trigger-half disposition in the coverage ledger; DESK-111 is not "uncovered" — it is split: JVM half = row 43, OS-trigger half = this exclusion | P2 |
| LV-desktop-app-44 | Account name/password entry: validate-and-explain, not mangling — desktop lane (DESK-136) | vanilla | **[TST]** `./gradlew.bat test --tests "com.pocketrealm.desktop.ui.AccountEntryValidationTest" --console=plain` | none | rc=0; the pinned café leg green [CLASS-VERIFIED AccountEntryValidationTest.kt:30-43]: `"café"` stays INTACT in the field (16-char cap only, no character-class filter), `UserAccountStore.isValidCredential("café")` = false, Create disabled via the shared gate (`accountCredentialsSubmittable`/`accountCreateEnabled`), and the field shows the shared plain-copy reason `"Use 1–16 letters or numbers — no spaces, symbols, or accents"`. The account-OP ground-truth cross-check rides LV-bot-actors-33's amended legs | P1 |
| LV-desktop-app-45 | Login-window detection & typed-entry targeting — the JVM detection seam (DESK-138) — **probe executed this ask** | vanilla | **[TST]** `./gradlew.bat test --tests "com.pocketrealm.desktop.Win32AutoLoginSignatureTest" --console=plain`; **[JSH]** live detection seam [X, fat-jar classpath]: `Win32AutoLogin.INSTANCE.loginSignatureFor("vanilla")` non-null; `sig.matches(new Win32AutoLogin.WindowDescriptor("WowWindow","World of Warcraft",true,<pid>))`; splash row `descriptor("","World of Warcraft",true,<pid>)`; `pollLoginWindow(<windowless pid>,"nosuchflavor", timings, () -> now, (long m) -> {})` | none | gradle rc=0 on the signature truth table [CLASS-VERIFIED Win32AutoLoginSignatureTest.kt:29-44]; live [X]: `SIG_MATCH=true` for the pinned vanilla frame (class `WowWindow`, title `World of Warcraft`, visible, pid-owned), `SIG_SPLASH=false` for the classless splash (never match), and an unqualified flavor returns `LoginWindowPoll.NeverAppeared` immediately (auto-login refuses rather than approximating — Win32AutoLogin.kt:88-95,114-116 [R]). The LIVE window+typed-entry leg stays LV-bot-actors-38 (DEDICATED-SLOT), which cites DESK-138 in its feature cell | P1 |

### Checks run for this round

- [CLASS-VERIFIED] reads: FirstRunTutorialTest.kt, DesktopAppModelSingleInstanceTest.kt,
  DesktopRuntimeBackendTest.kt:675-702, Win32ClientWindowTest.kt, SaveExitDrainTest.kt,
  AccountEntryValidationTest.kt, Win32AutoLoginSignatureTest.kt (all under
  `desktop/src/test/kotlin/com/pocketrealm/...`), plus the production seams cited per row.
- [X] live jshell probes (fat-jar classpath per the mechanism legend): `promote(0)` →
  `client pid unknown`; `promote(<windowless pid>)` → `no visible window for pid <pid>`;
  `loginSignatureFor("vanilla")` non-null with `SIG_MATCH=true`/`SIG_SPLASH=false`;
  `pollLoginWindow(..., "nosuchflavor", ...)` → `LoginWindowPoll$NeverAppeared`.
  (STALE-JAR note: the pre-existing plain `pocketrealm-desktop-0.103.0-alpha.jar` returns
  `null` from `loginSignatureFor("vanilla")` — use the fat jar, which rows 38–45's [JSH]
  legs already pin.)
- NOT run: every gradle `[TST]` leg above (execution-round work, per this file's honesty
  notes); no repo source file modified.

### 4.7 — section file `matrix-bot-actors.md` (embedded verbatim)

# Live-Verification Matrix — Area: bot-actors (in-game bot-actor testing)

Written 2026-10-02 by the matrix-designer step (area `bot-actors`). Scope per the ask:
**playerbots + RpWowClient protocol probes as the PLAYING testers** (leveling, combat,
dungeon groups, quests, tradeskills, AH, mail, deaths/corpse runs), asserted via **SQL
world-state queries** and **mangosd logs through the console relay**. Evidence classes:
**[R]** = read this session (path:line), **[X]** = command run this session (output quoted),
**[A]** = material the ask gave.

## 0. Verdict up front

**43 test rows (audit round 2 revisions retained). 39 rows are runnable now without user
artifacts — with row 41 contributing ONLY its control lane (the engagement lane is
blocked-tracked on tracked prerequisite P-3, a harness/repo gap rather than a user
artifact). 3 rows require user artifacts** — rows 17/18 (the LLM text/ambience batteries: R6 GGUF
models) and row 38 (real 1.12.1.5875 client, R3; run only in a dedicated interactive slot,
not the unattended lane). **1 is explicitly design-only** — row 40 (TBC/WotLK bot play)
requires the R1/R2 clients + R4 forks and CANNOT run today (the lane structurally refuses a
non-single qualified flavor, BotProfiles.kt:223-231 [R]). Row 31 is demoted to a **harness
self-test** (the attach-time stale-relay refusal it probes exists in no product or harness
code today — see the row and §7). Round-2 changes: the audit-1 G3-10 row is now RE-HOMED —
its canonical copy is **LV-desktop-app-37** in matrix-desktop-app.md (inserted next to
LV-desktop-app-07; that file did not exist at audit time, so row 42 here is the mirrored
duplicate); new row 43 (directed whisper bot-commands: invite/leave/who — the probe
converses with the bot AI itself). Round 3 executed the artifact-free slice that needs no
world boot: rows 03, 12, 13 (conf leg) and 42 PASS with quoted outputs (§2 round-3 block;
§10). Every row's mechanism was checked against code and/or a live command this session;
the two SQL/log assert channels were executed live against the already-staged desktop lane
(§2 checks 3-6).

## 1. Lanes, drivers, assert channels (all verified this session)

### Lane D — desktop console-relay lane (primary; no emulator needed)

- Boot: `cd desktop && gradlew rpHost [-PrpBots=N] [-PrpAccount=u -PrpPassword=p]`
  — task registered at desktop/build.gradle.kts:365 [R]; boots db+realm+world in-process
  exactly like the bring-up gates, then serves **one JSON op per stdin line → one JSON op per
  stdout line**, EOF drains with a save (RpHost.kt:15-31 [R]).
- Op surface (RpHost.kt:111-267 [R]): `hello | account | chat | memory | reset | online |
  bots | botstatus | save | autologin | stop | proto-login | proto-world | proto-chars |
  proto-create | proto-pick | proto-chat | proto-close`. Python driver:
  `tools/rp_harness/desktop_host.py` (`DesktopHost`, JSON-line protocol `@@RP@@…`,
  desktop_host.py:39, 147-202 [R]). The `proto-*` ops are **RpWowClient** — a real 1.12 wire
  client (SRP6 logon, CMSG_AUTH_SESSION, CMSG_CHAR_ENUM/CREATE, CMSG_PLAYER_LOGIN,
  CMSG_MESSAGECHAT say/whisper, SMSG capture; RpWowClient.kt:33-66, 99-297 [R]).
- GM commands ride the probe's own chat: the probe account is created GM (`account` op with
  `gm=3`), and a say/yell line starting with `.` is routed by the server through
  `ParseCommands` — the repo's own battery drives `.gm visible on` and `.appear <bot>` this
  way (desktop_play.py:119-137 [R]). Command table grounded in
  native/cmangos/src/game/Chat/Chat.cpp:944-1005 [R]: `.tele` (SEC_MOD), `.appear <name>`
  (:954), `.summon <name>` (:956), `.die` (:960), `.revive` (:961), `.levelup` (:984),
  `.additem` (:987), `.damage` (:1005), `.send mail|money` subtable (:724-726),
  `.npc add` (:529). **`.die`/`.damage` require a selected unit** (Level3.cpp:3157-3165,
  3197-3206 [R]) so death is driven by spawned hostile mobs instead; **`.revive`,
  `.send mail/money`, `.summon`, `.appear`, `.tele` take name/location args and are fully
  CLI-drivable** [R].
- **SQL channel**: host-side python `sqlite3` over
  `%LOCALAPPDATA%\PocketRealm\database\sqlite-datadir\classic{realmd,mangos,characters,logs}.sqlite3`
  — the repo's own tools do exactly this (tools/reset_davos.py:7-10, tools/checkpoint_dbs.py:7-16 [R]).
  Live-executed this session [X] (see §2): realmd DB has `account`,
  `account_banned(id, account_id, banned_at, expires_at, banned_by, unbanned_at, unbanned_by,
  reason, active)`, `ip_banned`, `realmlist(realmbuilds='5875 6005 6141 ')`; characters DB
  has `characters` (54 rows), `corpse`, `groups`, `group_member`, `group_instance`.
- **Log channel**: `%LOCALAPPDATA%\PocketRealm\runtime\server\logs\world.log` +
  `realmd.log` — live files exist on this host [X]: world.log 16.9 MB / 232,552 lines, tail
  dated 2026-10-02 11:38. Staged confs (the world's actual runtime conf surface) live in
  `%LOCALAPPDATA%\PocketRealm\runtime\server\run\` — `aiplayerbot-preset-alive-realm-b320-v1.conf`,
  `aiplayerbot-bench-autologin-b50-v1.conf`, `aiplayerbot-disabled.conf`, `mangosd.conf`,
  `realmd.conf` all present [X].
- Canonical batteries already in-repo (used as full mechanisms, not re-derived):
  `tools/rp_harness/desktop_boots.py` (boot-race: N boots, login-at-READY, self-echo),
  `desktop_smoke.py` (bot reply from world log), `desktop_play.py` (protocol player battery),
  `desktop_soak.py` (ambience), `desktop_toolprobe.py` (tool lane).

### Lane E — Android in-emulator console-relay lane

- Relay: `adb shell am instrument -e class com.pocketrealm.database.WorldConsoleRelay
  com.pocketrealm.test/androidx.test.runner.AndroidJUnitRunner` (non-blocking resident),
  ops driven by push/poll of `/data/data/com.pocketrealm/files/console-{in,out}.json`
  (tools/world_console.py:40-112 [R]).
- Op surface (WorldConsoleRelay.kt:141-202 [R]): `db-status | db-health | realm-start/stop/
  status | world-start | world-start-bot <profile> | world-status | world-bots <n> |
  world-bot-status | world-save | world-account | world-verify | world-gm |
  world-account-status | world-persistence <u> <char> | world-chat (say|party|whisper|yell)
  | reset-state | llm-memory-state | world-pause <0|1> | world-kill | world-stop | ping |
  stack-up-bot [profile]`. Attach-time `ping` carries `runtimeBuildId`/`nativeCmangosCommit`/
  `nativePlayerbotsCommit` compared against `schemas/realm-runtime-lockfile*.json` before any
  op (WorldConsoleRelay.kt:190-198 [R]).
- SQL channel on device: `adb root` (emulator adbd is rootable — world_console.py:49-52,
  run_baseline_world_sanity.py:42-45 [R]) then `adb shell find
  /data/data/com.pocketrealm -name 'classiccharacters.sqlite3'` → `adb pull` → host-side
  python sqlite3 (the on-device datadir is the engine's `sqlite-datadir` under the app
  database root — DatabaseSqliteControlPlane.kt:90 [R]; exact parent resolved by `find` at
  run time, not guessed here).
- Bot data: `build/o11-bot-data/o11-server` exists in-tree [X] (dbc+maps+vmaps+mmaps
  prepared from the user's client by tools/prepare_o11_bot_data.py —
  run_bot_pressure_benchmark.py:24-26 stages it; preflight refuses to start without it,
  :38-57 [R]). ADB/AVD constants: `%SDK%\platform-tools\adb.exe`, `%SDK%\emulator\emulator.exe`,
  AVD `PocketDiff_x86_64` (run_differential_parity.py:38-43 [R]; matches environment.md §AVD).

### Behavior knobs (what the bots are actually told to do)

`BotProfiles.kt` emits the AiPlayerbot conf per profile (BotProfiles.kt:135-211 [R]):
`AiPlayerbot.MaxRandomBots/MinRandomBots`, `RandomBotAccountPrefix/AccountCount`,
`AutoDoQuests`, `RandomBotGroupNearby`, `RandomBotInvitePlayer`, `SyncLevelWithPlayers`,
`LimitCombatActivity`, `botActiveAlone`, `PassiveDelay` …; **AH automation is deliberately
off** ("Auction-house automation remains disabled; the core BUILD_AHBOT target is excluded",
:132-134; `ShouldQueryAHListingsOutsideOfAH = 0`, `BotCheckAllAuctionListings = 0`,
:194-195) and the base conf carries `AiPlayerbot.LLMEnabled = 0` (the LLM block is appended
separately — live conf shows the `0` then appended `2` [X]). Profile ids used below are
real registry ids (BotProfiles.kt:416, 632, 707, 778, 992, 1223-1292 [R]):
`mobile-quiet-b25-v1`, `mobile-balanced-b100-v1` (AutoDoQuests=1), `preset-alive-realm-b320-v1`
(default, AutoDoQuests=1), `bench-autologin-b50-v1`, `bench-autologin-b100-v1`,
`bench-active-b600-v1` (combat unrestrained, 20% active). Admission control is
`DesktopBotAdmission` (DESK-8/23/43/99; DesktopBotAdmission.kt header [R]) persisting its
verdict to `runtime/server/bot-admission.json`; **the world refuses random-bot logins while
no target is set** (RpHost.kt:83-85 [R]).

## 2. Checks run during THIS ask (artifact-free)

1. `grep -n "rpHost\|authGate\|bootWorld\|bootRealmd\|whisperGate\|supervisorStartGate\|seedRealmData\|launchClient" desktop/build.gradle.kts`
   → all eight JavaExec tasks present, `rpHost` at :365. **PASS**
2. Op surfaces enumerated by reading RpHost.kt / WorldConsoleRelay.kt / RpWowClient.kt (§1 [R]).
3. `python -c` sqlite3 over the live datadir →
   `classicrealmd objects: 28 / accounts: 10`; accounts include seed accounts
   (`ADMINISTRATOR, GAMEMASTER, MODERATOR, PLAYER`) **plus prior-run bot accounts
   `PRT500…PRT505`**; `realmlist → (1,'MaNGOS','5875 6005 6141 ')`; `account_banned` columns
   as quoted in §1; `classiccharacters → 159 objects, characters: 54`, `corpse` table exists,
   group tables `['group_instance','group_member','groups']`; classicmangos 401 objects.
   **PASS — the SQL assert channel works and already contains bot rows**
   (54 bot-class characters on accounts id>4; level spread includes 15 and 60 — organic bot
   progression has happened on this lane).
4. `world.log` live: `exists: True size: 16897778, lines: 232552` (tail = formation-delete
   lines, 2026-10-02 11:38). **PASS — the log-scan channel works.**
5. `grep -E "AutoDoQuests|GroupNearby|MaxRandomBots|…"
   %LOCALAPPDATA%/PocketRealm/runtime/server/run/aiplayerbot-preset-alive-realm-b320-v1.conf`
   → `MaxRandomBots = 320`, `AutoDoQuests = 1`, `RandomBotGroupNearby = 1`,
   `PassiveDelay = 3000`, `LLMEnabled = 0` + appended `LLMEnabled = 2`. **PASS.**
6. `settings.json` live: `botProfileId: "bench-autologin-b50-v1"`, `runtimeMode: "LOCAL"`,
   `clientDir: "C:\Wow clients\Installed\WoW_Classic_ENG_1.12.1"` — note this clientDir is a
   stale pointer; the requirements manifest verified the client folder is ABSENT on this
   machine [A: requirements.md §R3 status], so real-client rows below are marked artifact-required.
7. GM command semantics grounded in source: `.die`/`.damage` need selection
   (Level3.cpp:3157-3165, 3197-3206); `.revive` extracts player from args
   (Level3.cpp:3281-3289); `.send mail/money` SEC_MODERATOR with args (Chat.cpp:724-726).
   **Design consequence**: rows use `.send mail <name>`, `.revive <name>`, `.summon <name>`,
   `.tele <loc>` — none need wire-level targeting.

**Not run this ask**: any world boot (`gradlew rpHost`), any emulator boot, any APK build —
this ask is matrix design; every runnable row is designed to be first executed by the
later live stage. Everything marked [X] above WAS executed.

### Round-2 (this ask) re-checks

1. `ls out/live-verification/` → sibling matrices now exist, incl. `matrix-desktop-app.md`
   (LV-desktop-app-07 found in it) and `matrix-bots-llm.md` — the audit-1 re-home target is
   real; **[X]**.
2. `grep -o "LV-desktop-app-[0-9]*" matrix-desktop-app.md | sort -u | tail -3` → max
   `LV-desktop-app-36`, so the re-homed G3-10 row took `LV-desktop-app-37`, inserted next to
   row 07; **[X]**.
3. `grep -c "rpHost" desktop/build.gradle.kts` → 1 (task still present); **[X]**.
4. Whisper bot-command machinery grounded by reading playerbots source: unsecured command
   set incl. `invite`/`leave`/`who` (PlayerbotAI.cpp:1344-1368), whisper dispatch
   (`HandleCommand`, :1371), action registrations (`ChatActionContext.h:97,164,173`),
   `WhoAction` reply via `ai->TellPlayer` (WhoAction.cpp:60-70) — basis of new row 43; **[R]**.
5. bots-llm matrix re-checked for the audit-1 "external endpoint" wording: its endpoint row
   (LV-bots-llm-21) already gates on R6 via the in-repo llama-server endpoint and states
   "requirements.md documents no external-credential artifact" — self-consistent, no edit
   made to that section's file; **[X]**.

### Round-3 (this ask) re-checks and EXECUTIONS

1. File integrity after the external harmonization + audit-2 edits: `grep -c "^| LV-bot-actors-"` →
   **43 rows**; `grep -cP '\x08|\x0d'` → **0** (the audit-2 control-byte repair held); sections §0-§9 present. **[X]**
2. **Row 03 EXECUTED**: two-step query → `PR% accounts: PRT500-505 (ids 5-10)`, `bot chars: 54`, `level range (1, 60)`. The original single cross-DB subquery FAILED live (`sqlite3.OperationalError: no such table: account` — classiccharacters has no `account` table); the row's mechanism now spells the two-step form. **PASS + design correction [X]**
3. **Row 12 EXECUTED**: `character_skills` columns grounded (`guid, skill, value, max` via PRAGMA table_info); profession-filtered count = **8** (164, 165, 182, 186, 197, 202, 333, 393); 80 distinct skills overall. **PASS [X]**
4. **Row 13 conf leg EXECUTED**: `grep -E "ShouldQueryAHListingsOutsideOfAH|BotCheckAllAuctionListings" …aiplayerbot-preset-alive-realm-b320-v1.conf` → both `= 0`. **PASS [X]** (the auctionhouse SQL leg still needs its soak — not run)
5. **Row 30 EXECUTED (inverted form)**: realmbuilds flipped to `'12340'` → `proto-login` `ok:true` + `worldAuth=12` (AUTH_OK) at build 5875 → restored byte-exact `'5875 6005 6141 '`; no build/refusal lines in realmd.log; clean stop exit 0 (build/rp-desktop/round4b.json). **Confirms multiplayer's executed refutation on this lane: realmd does not gate builds at the challenge — the refusal is client-side.** **[X]**
6. **Row 42 EXECUTED**: `cd android && cmd //c "gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests … --console=plain"` → `BUILD SUCCESSFUL in 2s`, exit 0 — 6 + 7 + 4 = **17 tests, 0 failures/errors/skipped** (TEST-*.xml counts quoted). **PASS [X]** — also proves the mandatory `-PpocketAbi` gate passes with the property set.

### Round-4 (this ask) EXECUTIONS — the world-boot slice

1. **Row 01 EXECUTED**: `gradlew rpHost` (no `-PrpBots`, P-5 posture) via `tools/rp_harness/desktop_host.py` →
   `ready` event at **22.1 s**, `hello` → `{"online":0,"ok":true}`, no `WORLD START REFUSED`. **PASS [X]**
2. **Row 02 EXECUTED — the P-5 posture confirmed live**: with NO `bots` call, `botstatus` polled to
   **`onlineBots=26 ≥ 20`** against **`effectiveTarget=25` profile-owned** (settings profile
   `bench-autologin-b50-v1`). **PASS [X]** (build/rp-desktop/round4.json)
3. **Row 26 EXECUTED (3 of 4 legs)**: `unknown-channel` ✓, `invalid-text` (300-byte text) ✓,
   `sender-not-online` (whisper with probe offline) ✓ — each `ok:false` with the pinned reason, no crash.
   **Design correction**: the unknown-sender leg answered `sender-not-online` — the op validates
   sender-online BEFORE the character name (README reason table is precedence-ordered); the
   `invalid-character-name` leg needs a malformed NAME. **PASS (partial) + correction [X]**
4. **Row 28 EXECUTED — mechanism invalidated by execution**: the wrong-password `proto-login` returned
   `ok:true` — **`proto-login` re-seeds the account's SRP6 verifier from the passed password before
   logging on** (RpHost.kt:178-192: `UPDATE account SET v=…,s=…` then logon with the same secret), so
   the op CANNOT produce a wrong-password attempt. The row's HOST leg now uses the raw wire probe
   (`out/live-verification/scripts/srp6_probe.py --wrong-password`, golden-verified by multiplayer-22).
   Also grounded live: `account` op codes (`created:0`=created/`10`=exists; `gm` = SET rc `0`=success —
   RPTEST row shows `gmlevel 3` in SQL). **Correction [X]** (assertion legs still to run)
5. **Row 04 EXECUTED (PARTIAL)**: `python tools/rp_harness/desktop_boots.py --boots 2 --plain-boots 1` →
   **3/3 in-world, 0 crashes**; every boot `ready ≈21.7 s`, `auth=12` (AUTH_OK 0x0C), `echo=1` say
   self-echo round-trip, `exit=0` — including the bots-DISABLED plain boot (the original crash shape).
   **PASS (partial; the 7-boot run remains the row's bar) [X]** (build/rp-desktop/boots-r4.json)
6. **Row 19 EXECUTED**: `save` → `ok:true`; EOF clean stop `exitCode 0`; post-stop: **zero WAL sidecars**,
   `PRAGMA quick_check=ok` (realmd + characters), `online=1` count **0**, bot rows/levels preserved
   (54 chars, Davos level 1 offline). **PASS [X]**
7. Probe driver: `build/rp-desktop/round4_probe.py` (scratch, written this round); bounded
   (READY 420 s / population 420 s); report `build/rp-desktop/round4.json`. Not side-effect-free by
   design: it created account `RPTEST` (gm 3) and re-verified `Davos` — the batteries' own convention.

## 3. Conventions used in the table

- `PY1 '<stmt>'` = `python -c "<stmt>"` from the repo root (`E:\pocket_realm_complete`).
- `SQ('db','sql')` = `python -c "import sqlite3,os;c=sqlite3.connect(os.path.join(os.environ['LOCALAPPDATA'],'PocketRealm','database','sqlite-datadir','db.sqlite3'));print(c.execute('sql').fetchall())"`.
- `HOST(...)` = `PY1` one-liner driving `tools.rp_harness.desktop_host.DesktopHost`, e.g. the
  canonical population driver:
  `python -c "import sys;sys.path.insert(0,'tools');from rp_harness.desktop_host import DesktopHost;h=DesktopHost('.');h.wait_ready(900);print(h.hello());print(h.botstatus());h.close()"` — NEVER `h.bots(...)` on the desktop lane: the `bots` op refuses `begin:2` while the profile-owned admission monitor is live (tracked prerequisite P-5, matrix-multiplayer.md:218/:286; audit round 3); drive targets via `-PrpBots` at boot (row 01) or the EMU `world-bots` owning verb (rows 23/37).
- `EMU <relay-op> [args]` = the `tools/world_console.py send()` mechanics on the emulator
  (`adb root` → push `console-in.json` → poll `console-out.json`); batched via
  `python tools/world_console.py` REPL or a `PY1` snippet importing `tools.rp_harness.session.RelaySession`.
- `EMU-SQL` = `adb root; adb shell find /data/data/com.pocketrealm -name classiccharacters.sqlite3`
  → `adb pull <path> build/rp-desktop/` → host sqlite3 as above.
- `[SET-BACKUP]` / `[SET-RESTORE]` = the settings-state guard MANDATED by matrix-bots-llm.md's
  Shared conventions for ANY row that writes
  `%LOCALAPPDATA%\PocketRealm\settings\settings.json` (audit round 2 adoption):
  `[SET-BACKUP]` = step 0 `PY1 "import shutil,time;shutil.copy2(r'%LOCALAPPDATA%\PocketRealm\settings\settings.json',r'%LOCALAPPDATA%\PocketRealm\settings\settings.json.bak-'+time.strftime('%Y%m%d-%H%M%S'))"`;
  `[SET-RESTORE]` = cleanup leg `PY1 "import filecmp,shutil,sys;b=<backup>;f=r'%LOCALAPPDATA%\PocketRealm\settings\settings.json';shutil.copy2(b,f);sys.exit(0 if filecmp.cmp(b,f,shallow=False) else 1)"`
  — restore then byte-compare (`shallow=False`), exit 1 on mismatch, BEFORE the next row starts.
  The user's live `botProfileId` (`bench-autologin-b50-v1` [X §2.6]) must never leak across rows.
- Android gradle invocations MUST carry `-PpocketAbi=x86_64` (mandatory —
  android/app/build.gradle.kts:948-951 throws at configuration without it); APK-pair builds add
  `-PpocketLane=full` (canonical form per matrix-flavor-import.md:71); from Git-Bash invoke as
  `cmd //c "gradlew.bat …"` (Shared conventions, matrix-bots-llm.md).
- Severity: **P1** = blocks the area's core claim; **P2** = feature regression; **P3** = hardening/polish.
- "Artifacts" column: `none` = artifact-free now-test; otherwise names the requirement-manifest
  artifact (R1/R2/R3/R4/R6 per requirements.md §2 [A]).

## 4. Test matrix — bot-actors

| testId | feature | flavor(s) | exact CLI mechanism | artifacts | pass condition | severity |
|---|---|---|---|---|---|---|
| LV-bot-actors-01 | World stack boots with the bot lane (console relay up) | vanilla | `cd desktop && gradlew rpHost -PrpBots=25` via `HOST` — the `-PrpBots` registration IS the owning admission generation at boot (RpHost.kt:83-87: the host registers one generation at boot and re-arms it per op; under the P-5 profile-ownership contract the `bots` op answers `begin:2` while a generation is live — matrix-multiplayer.md:218 — so the target is driven ONLY through this boot flag, never a mid-session `bots` call); ready event = world READY after first honest tick (RpHost.kt:76-87) | none | `ready` event received ≤900 s; `hello` op answers `online`; stderr has no `WORLD START REFUSED`. **→ EXECUTED round 4: PASS — ready event at 22.1 s, `hello` → `{"online":0,"ok":true}`, no refusal** (build/rp-desktop/round4.json) | P1 |
| LV-bot-actors-02 | Bot population fills to the applied target (DESK-123/8 admission ops; DESK-29/61 world-presence sampling seam) | vanilla | `HOST`: `gradlew rpHost` with NO `-PrpBots` — the target is owned by the settings profile's admission monitor (P-5 posture: the live settings.json carries `botProfileId=bench-autologin-b50-v1` [X §2.6]; the executed observation matrix-multiplayer.md:272/:286 recorded `bots` → `ok:false reason:"begin:2"` with `effectiveTarget=25` profile-owned; row rewritten in the bots-llm-01 form per audit round 3) — poll `botstatus()` every 15 s (probe_admission.py mechanics); DO NOT call the `bots` op in this lane (it refuses `begin:2` — the clamp leg lives on the EMU owning verb, row 23); `SQ('classiccharacters','SELECT COUNT(*) FROM characters WHERE online=1')` | none | `ready` event; `botstatus` reports the profile-owned `effectiveTarget` (non-zero — 25 in the recorded observation) with `onlineBots ≥ 20` within 15 min; SQL `online=1` count ≈ botstatus count (probe chars excluded). **→ EXECUTED round 4: PASS — P-5 posture confirmed LIVE: no `bots` call, `effectiveTarget=25` (profile-owned), `onlineBots=26 ≥ 20` within the poll window** (build/rp-desktop/round4.json) | P1 |
| LV-bot-actors-03 | Bot accounts/characters minted in world DB | vanilla | Two-step query — sqlite cannot reference the realmd DB from the characters connection (design correction found by EXECUTION round 3: the original single cross-DB subquery fails with `no such table: account`). Step 1 `SQ('classicrealmd','SELECT id FROM account WHERE username LIKE <PR%>')`; step 2 `SQ('classiccharacters','SELECT COUNT(*) FROM characters WHERE account IN (<ids>)')`. EXECUTED round 3: PR% accounts = PRT500-PRT505 (ids 5-10); bot characters = 54; level range 1-60 (prior-run rows already in the live datadir) | none | profile-prefix accounts exist; character count >= initialTarget (account pool >= target per BotProfiles.kt:106-108). EXECUTED round 3: PASS (6 accounts, 54 chars, levels 1-60) | P1 |
| LV-bot-actors-04 | RpWowClient protocol probe: SRP6 → world auth → char enum/login → live say echo | vanilla | `python tools/rp_harness/desktop_boots.py --boots 5 --plain-boots 2 --out build/rp-desktop/boots.json` (proto-login → proto-world → proto-chars → proto-pick → proto-chat say with `includeSelf:true`) | none | report: `inWorld == 7/7`, `crashes == 0` (no 0xC0000409), every `echoLines ≥ 1`, every stop rc=0. **→ EXECUTED round 4 (PARTIAL, 3 of the 7 target boots: `--boots 2 --plain-boots 1`): PASS — 3/3 in-world, 0 crashes; each boot ready ≈21.7 s, `auth=12` (AUTH_OK 0x0C), `echo=1`, exit=0 — including the bots-DISABLED plain boot (the original crash shape)** (build/rp-desktop/boots-r4.json); the full 7-boot run remains the row's bar | P1 |
| LV-bot-actors-05 | Bot leveling (playing testers progress) | vanilla | `[SET-BACKUP]`; host with 25 bots, `preset-lively-b160-v1` behavior (PY1 settings write `botProfileId=preset-lively-b160-v1` — limitCombatActivity=false, activeBotPercent=18 — enough active combat to level), ≥2 h soak with the world CONTINUOUSLY up (no logout ⇒ no `InstantRandomize` re-roll of online bots): snapshot `SQ('classiccharacters','SELECT guid,level FROM characters WHERE account>4')` at t0 and t1; `[SET-RESTORE]` before the next row. Liveness precondition (recorded, NOT a pass arm): online bots' `totaltime` advances between snapshots | none | **PASS only if ≥1 bot guid has `level(t1) > level(t0)`**; if zero bots leveled, the row FAILS even when totaltime advanced (that number only proves the soak ran). No crash in the world.log window; `[SET-RESTORE]` exits 0 | P2 |
| LV-bot-actors-06 | Bot combat engagement (active bots fight) | vanilla | `[SET-BACKUP]`; `HOST` boot `-PrpBots=25` then set settings `botProfileId=preset-lively-b160-v1` + `botPopulationTarget` (PY1 json edit of `%LOCALAPPDATA%\PocketRealm\settings\settings.json`) → reboot; `.appear <bot>` near an active bot (GM say via `proto-chat kind=say text='.appear <bot>'`); hostile-leg: `proto-chat say '.npc add <entry>'` where `<entry>` = first low-level hostile from `SQ('classicmangos','SELECT entry FROM creature_template WHERE rank=1 AND minlevel<=3 ORDER BY entry LIMIT 1')`; drain 120 s; `[SET-RESTORE]` before the next row | none | bot XP/level delta in SQL (per LV-05 query) for ≥1 engaged bot, or ≥1 kill/death line for that bot in the world.log window; world process alive; `[SET-RESTORE]` exits 0 | P2 |
| LV-bot-actors-07 | Bot questing (AutoDoQuests lane) | vanilla | `[SET-BACKUP]`; boot with `botProfileId=mobile-balanced-b100-v1` (PY1 settings write; autoDoQuests=true, BotProfiles.kt:659): `grep AutoDoQuests %LOCALAPPDATA%\PocketRealm\runtime\server\run\aiplayerbot-mobile-balanced-b100-v1.conf` then 60 min soak; `SQ('classiccharacters','SELECT COUNT(DISTINCT guid) FROM character_queststatus q JOIN characters c ON q.guid=c.guid WHERE c.account IN (SELECT id FROM account WHERE username LIKE \'PR%\')')`; `[SET-RESTORE]` before the next row | none | staged conf carries `AutoDoQuests = 1`; ≥1 bot character has ≥1 character_queststatus row after soak; `[SET-RESTORE]` exits 0 | P2 |
| LV-bot-actors-08 | Organic bot grouping (RandomBotGroupNearby) | vanilla | Boot `preset-alive-realm-b320-v1` (GroupNearby=1 [X §2.5]) with player parked among bots (`.gm visible on` via proto-chat; desktop_play.py:119-121 pattern); 60 min soak; `SQ('classiccharacters','SELECT COUNT(*) FROM group_member m JOIN characters c ON m.memberGuid=c.guid WHERE c.account IN (SELECT id FROM account WHERE username LIKE \'PR%\')')` | none | ≥1 group row with ≥2 bot members (or bot+probe) in `groups`/`group_member` | P2 |
| LV-bot-actors-09 | Dungeon party leg (GM-mobilized group in an instance) | vanilla | HOST: account op `gm=3`; probe in world; `proto-chat say '.summon <bot1>'`, `.summon <bot2>` (names from `SQ('classiccharacters','SELECT name FROM characters WHERE account>4 AND online=1 LIMIT 2')`); `proto-chat say '.tele <dungeon-loc>'` where `<dungeon-loc>` = a `.tele` location resolving to an instance map (list via `SQ('classicmangos','SELECT name FROM teleport_target LIMIT 5')` or grounded tele names); drain; `SQ('classiccharacters','SELECT name,map FROM characters WHERE name IN (bot1,bot2,\'Davos\')')` | none | after `.tele`, probe `characters.map` = instance map id and both summoned bots share it; world alive 5 min after | P2 |
| LV-bot-actors-10 | Mail happy path → probe char (GM send) | vanilla | HOST: `proto-chat say '.send mail Davos 10 20 6948'` (syntax per Chat.cpp:724 [R]; item 6948 = hearsay-free pick from `SQ('classicmangos','SELECT entry FROM item_template WHERE bonding=0 LIMIT 1')`); wait 30 s; `SQ('classiccharacters','SELECT id,messageType,subject,itemTextId FROM mail WHERE receiver=\'Davos\'')` | none | ≥1 `mail` row addressed to the probe char; `mail_items` row if item attached; world.log mail line | P2 |
| LV-bot-actors-11 | Mail → bot; bot picks up mail (CheckMailAction) | vanilla | `SQ('classiccharacters','SELECT name FROM characters WHERE account>4 AND online=1 LIMIT 1')` → `<bot>`; `proto-chat say '.send mail <bot> 10 20 6948'`; soak 30 min; re-query `mail WHERE receiver='<bot>'` + `SQ('classiccharacters','SELECT COUNT(*) FROM character_inventory WHERE item_template=6948')` | none | mail row for `<bot>` disappears or mail state flips AND item/money lands in bot inventory (bounded-soak pass: check every 5 min, pass at first observation) | P2 |
| LV-bot-actors-12 | Tradeskills: bots carry professions | vanilla | SQL-only against the live datadir (no boot needed): step 1 resolve bot account ids (row 03 two-step); step 2 `SQ('classiccharacters','SELECT COUNT(DISTINCT s.skill) FROM character_skills s JOIN characters c ON s.guid=c.guid WHERE c.account IN (<ids>) AND s.skill IN (164,165,171,182,186,197,202,333,393,755)')` — columns grounded live this round: `character_skills(guid, skill, value, max)` [X §2 round-3]. **→ EXECUTED round 3: 8 distinct profession skills on bots (164, 165, 182, 186, 197, 202, 333, 393); 80 distinct skills overall** | none | ≥2 profession skill rows across bot characters (PlayerbotFactory-created bots have tradeskills). **EXECUTED round 3: PASS — 8 distinct profession skills on bots (164, 165, 182, 186, 197, 202, 333, 393), 80 distinct skills overall; columns grounded live: character_skills(guid, skill, value, max)** | P3 |
| LV-bot-actors-13 | AH automation OFF by design (absence proof) | vanilla | `grep -E "ShouldQueryAHListingsOutsideOfAH\|BotCheckAllAuctionListings" %LOCALAPPDATA%\PocketRealm\runtime\server\run\aiplayerbot-preset-alive-realm-b320-v1.conf` **→ EXECUTED round 3 (conf leg): both lines read `= 0` live**; after a 60 min soak `SQ('classiccharacters','SELECT COUNT(*) FROM auctionhouse a JOIN characters c ON a.itemowner=c.guid WHERE c.account IN (SELECT id FROM account WHERE username LIKE \'PR%\')')` | none | both conf lines `= 0` (BotProfiles.kt:194-195 [R]); zero bot-owned auction rows after soak | P2 |
| LV-bot-actors-14 | Death + corpse record + revive (probe char is the mortal tester) | vanilla | HOST probe in world (level-1 Davos via `python tools/reset_davos.py` first — resets+respawns at Northshire, reset_davos.py:1-44 [R]); `.npc add <hostile entry>` adjacent (LV-06 source); drain 60 s; `SQ('classiccharacters','SELECT guid,corpseType FROM corpse')` + `SQ('classiccharacters','SELECT health FROM characters WHERE name=\'Davos\'')`; then `proto-chat say '.revive Davos'` (name-arg form, Level3.cpp:3281-3289 [R]); re-query | none | after kill: Davos health=1 AND ≥1 `corpse` row for his guid; after `.revive`: health ≥ 10, corpse row gone/retired | P2 |
| LV-bot-actors-15 | Corpse-run leg (dead movement, then recover) | vanilla | Continue LV-14 pre-revive: `proto-chat say '.tele <far-loc>'` while dead; drain; `SQ('classiccharacters','SELECT map,position_x,position_y FROM characters WHERE name=\'Davos\'')` + corpse query; then `.revive Davos` | none | position changed while corpse row persisted (the corpse-run invariant); post-revive alive at new location | P3 |
| LV-bot-actors-16 | Whisper wire probe → bot reacts (memory evidence; no LLM needed) | vanilla | HOST + probe in world near a bot (`.appear <bot>`); `reset('')`; `host.chat('Davos','whisper','<bot>','Hail <bot>, well met')`; wait 90 s; `host.memory('Davos')` (three-way table per rp_harness/README.md:129-133 [R]) | none | `relationships` or `facts` count increases ≥1 vs pre-whisper `memory()` snapshot (memory evidence = pass; reply TEXT not required here) | P1 |
| LV-bot-actors-17 | Bot reply TEXT over the wire (LLM lane battery) | vanilla | `python tools/rp_harness/desktop_play.py --min-bots 6 --soak-s 600` (requires the R6 lane: staged tuned GGUF + llama-server, per requirements.md §R6 — the manifest records NO external-endpoint alternative, so rows run only under R6) | **R6** | play2.json: ≥4 conversational phases produce replies ≥3 words; zero `think-leak`/`meta:*` audit issues; soak produced ≥1 ambient reply | P2 |
| LV-bot-actors-18 | Ambience composer soak (bot-to-bot life reaches the player) | vanilla | `python tools/rp_harness/desktop_soak.py --soak-s 2400` (requires R6 as row 17) | **R6** | soak.json census: ≥1 `BotLLM: dispatch … lane=cloud` + ≥1 spoken `BotLLM: line` during soak; no quota-exhaust crash | P3 |
| LV-bot-actors-19 | saveall: world state durable through clean stop | vanilla | `HOST`: after bot soak `host.save()`; SQL snapshot (`online` flags, bot levels); `host.close()` (EOF drains with save, RpHost.kt:19-20); `SQ(...)` re-query post-stop | none | save op ok; post-stop SQL preserves bot rows/levels (online flags cleared); `PRAGMA quick_check` = ok; no WAL sidecars left (bootWorld gate contract, WINDOWS_QUALIFICATION.md [R]). **→ EXECUTED round 4: PASS — `save` → `ok:true`; clean stop `exitCode 0`; post-stop: zero WAL sidecars, `quick_check=ok` on realmd+characters DBs, `online=1` count = 0, bot rows/levels preserved (54 chars, Davos level 1 offline)** (build/rp-desktop/round4.json) | P1 |
| LV-bot-actors-20 | Pause/resume the running world | vanilla (EMU) | `EMU world-pause 1` → `EMU world-status` → `EMU world-pause 0` (op at WorldConsoleRelay.kt:169); DesktopBotAdmission withholds adaptation while paused (DesktopBotAdmission.kt:70-74 [R]) | none | paused: world-status reports frozen tick metrics/paused flag; resumed: ticks advance; no bot-count crash across the toggle | P2 |
| LV-bot-actors-21 | Dirty-kill mid-run + WAL recovery (SQL integrity fail-closed) | vanilla | While HOST runs with bots: `taskkill /F /PID <jvm>` (win_kill_matrix.py victim pattern); `python tools/checkpoint_dbs.py` (WAL truncate + quick_check per DB — checkpoint_dbs.py:7-16 [R]); reboot HOST; re-run LV-02 query | none | checkpoint reports `integrity=ok` on all four DBs; reboot reaches READY; bot population refills (LV-02 pass) | P1 |
| LV-bot-actors-22 | Fail-closed: no admission target ⇒ zero random-bot logins | vanilla | `[SET-BACKUP]`; fresh-read precondition (the bots-llm-09 posture named by tracked prerequisite P-5's amendment, matrix-multiplayer.md:218): PY1 remove `botProfileId` from `%LOCALAPPDATA%\PocketRealm\settings\settings.json` — with the key absent no profile-owned admission monitor starts (with the live `botProfileId=bench-autologin-b50-v1` [X §2.6] a monitor WOULD start with a non-zero target and this row's premise would fail for a harness reason, not a product reason); then `gradlew rpHost` WITHOUT `-PrpBots` (RpHost.kt:83-85: world refuses random-bot logins while the target is unset); wait 10 min; `HOST botstatus()` + SQL online count; `[SET-RESTORE]`. EMU cross-check of the same gate through the owning verb (the bots-llm-02(b) form): `world-bots 0` ⇒ `effectiveTarget=0`, no new bot logins | none | `onlineBots == 0` for the whole window (and `botstatus` shows no effective target); SQL `online=1` shows no PR% characters; `[SET-RESTORE]` exits 0; the EMU `world-bots 0` leg reports `effectiveTarget=0` | P1 |
| LV-bot-actors-23 | Admission clamp on over-target demand | vanilla | `EMU`: `world-bots 25` then `world-bots 100000` through the relay (the app's own verb — WorldConsoleRelay.kt:151); read `%LOCALAPPDATA%/PocketRealm/runtime/server/bot-admission.json` + `world-bot-status` (DesktopBotAdmission record; BotPopulationPolicy.MAX_SUPPORTED_TARGET bound, BotProfiles.kt:99-101 [R]). The HOST `bots` op is NOT usable for this row: it answers `ok:false reason:"begin:2"` — the world's app-layer admission monitor owns the target (EXECUTED observation, matrix-multiplayer.md:272; audit round 2 reconciliation) | none | via the owning verb: `world-bots 100000` answers ok but `world-bot-status.effectiveTarget` stays ≤ profile `maximumOnline`; admission record written with `reason` non-empty or the verb answers an explicit refusal (no crash, no silent >max) | P2 |
| LV-bot-actors-24 | Fail-closed: one world lifetime per process (cycle-2 refusal) | vanilla | `cd desktop && gradlew bootWorld` — the gate itself boots the world, then attempts a SECOND in-process start and pins the refusal ("cycle-2 refusal pinned (one lifetime per process)", WINDOWS_QUALIFICATION.md [R]; task at desktop/build.gradle.kts:330). Do NOT run the task twice — two task invocations are two JVMs, each with a fresh lifetime, and cannot reach the refusal | none | gate output shows the second start attempt answered with the WRONG_STATE refusal while the first cycle stayed clean (READY, save rc=0); gate exits green | P2 |
| LV-bot-actors-25 | Whisper-bridge honest refusals (relay-level) | vanilla | `cd desktop && gradlew whisperGate` (desktop/build.gradle.kts:353; contract per WINDOWS_QUALIFICATION.md: whisper lane answers `sender-not-online` / `unknown-channel` honestly; `onlinePlayers` honest; memory JSON) | none | gate passes: honest refusal reasons observed (not silent ok), memory state JSON returned | P2 |
| LV-bot-actors-26 | world-chat negative vectors | vanilla | HOST (or EMU `EMU world-chat`): `chat('NoSuchChar','say','','hi')` → invalid-character-name; `chat('Davos','bogus','','hi')` → unknown-channel; whisper to non-online bot → invalid-target/sender-not-online; text leading `.` sent via `world-chat` (not proto-chat) → invalid-text; >255-byte text → invalid-text (reasons per rp_harness/README.md:44-47 [R]). **DESIGN CORRECTION (execution, round 4): the sender-online check PRECEDES name validation — an unknown sender name answers `sender-not-online`, not `invalid-character-name` (observed live); to hit `invalid-character-name` the leg must send a MALFORMED name (invalid characters), and this row documents the precedence** | none | each variant answers `ok:false` with the named reason; no world crash. **→ EXECUTED round 4 (3 of 4 legs live): PASS — `unknown-channel` ✓, `invalid-text` (300-byte text) ✓, `sender-not-online` (whisper while probe offline) ✓; the `NoSuchChar` leg returned `sender-not-online` (precedence above)** (build/rp-desktop/round4.json) | P1 |
| LV-bot-actors-27 | Fail-closed: ops before world READY | vanilla | Drive `chat`/`memory` immediately after process spawn, before the `ready` event (PY1: DesktopHost without wait_ready); also EMU: `EMU world-chat` before `EMU stack-up-bot` | none | `ok:false reason=world-not-ready` (native-side validation per README op contract) | P2 |
| LV-bot-actors-28 | Wrong password refused; loopback lockout ABSENT (negative-of-negative) | vanilla | **MECHANISM CORRECTED (execution, round 4): the HOST `proto-login` leg CANNOT test wrong-password refusal — `proto-login` RE-SEEDS the account's SRP6 verifier from the passed password before logging on (RpHost.kt:178-192: `UPDATE account SET v=…, s=… WHERE username=…`, then logon with the same secret), so the 'wrong' password always becomes the correct one (observed live: the wrong-pass attempt returned `ok:true`; also grounded live: the `account` op's codes — `created:0` = created / `10` = already-exists, both `ok:true`; `gm` carries the SET rc, `0` = success, not the level). The wrong-password refusal is driven by the RAW WIRE probe instead — `python out/live-verification/scripts/srp6_probe.py --host 127.0.0.1 --port 3724 --account RPTEST --password <real> --wrong-password` (computes M1 from the given password WITHOUT re-seeding; the multiplayer-22 golden-verified probe). Then the original legs: `EMU world-verify RPTEST correctpw` → verified / `EMU world-verify RPTEST badpw` → false; `grep -i "WrongPass" %LOCALAPPDATA%\PocketRealm\runtime\server\run\realmd.conf` | none | wrong-password auth fails; `world-verify` honest on both legs; **no** `WrongPass.MaxCount` lines in the loopback realmd.conf (MP:CONDITIONAL-LOCKOUT: lockout emitted only on non-loopback binds, campaign-inventory.md:245 [A/R]) | P1 |
| LV-bot-actors-29 | Banned account refused (fail-closed, SQL-driven ban) | vanilla | `SQ('classicrealmd','INSERT INTO account_banned(account_id,banned_at,expires_at,banned_by,reason,active) VALUES ((SELECT id FROM account WHERE username=\'RPTEST\'),strftime(\'%s\',\'now\'),strftime(\'%s\',\'now\',\'+1 hour\'),\'livetest\',\'bot-actors ban probe\',1)')` — NO hardcoded primary key: `id` is the INTEGER PRIMARY KEY and auto-assigns (audit round 2: hardcoding id=1 collides with any prior ban-history row); cleanup deletes by the probe's own fingerprint, `SQ(...,'DELETE FROM account_banned WHERE account_id=(SELECT id FROM account WHERE username=\'RPTEST\') AND reason=\'bot-actors ban probe\'')`. EPOCH-SECOND form: both columns are declared `INTEGER` (live `PRAGMA table_info(account_banned)` [X audit round 1]) and the engine predicate compares `expires_at > _UNIXTIME_ OR expires_at = banned_at` (AuthSocket.cpp:466-467 [R]); a `datetime('now')` ISO string would mis-evaluate the INTEGER/TEXT comparison. → HOST `proto-login RPTEST` → non-OK + `Select-String realmd.log "Banned account"` (AuthSocket.cpp:473); then the cleanup DELETE → `proto-login` OK again | none | banned: login refused with the `AUTH_LOGON_FAILED_BANNED` log evidence; unbanned: login succeeds (probe row deleted, DB restored to pre-test state) | P1 |
| LV-bot-actors-30 | realmbuilds reshapes the ADVERTISEMENT only — the auth chain does NOT gate builds at the challenge (assertion INVERTED in audit round 4; multiplayer's round-4 execution refuted the original refusal assumption: `srp6_probe.py --challenge-only --build 8606/5467/5875` all received full challenge answers; mechanism = `ok_build` AuthSocket.cpp:920/986 shaping only the realmlist advertisement from `realmlist.realmbuilds`) | vanilla | `SQ('classicrealmd','UPDATE realmlist SET realmbuilds=\'12340\' WHERE id=1')` (live value today `'5875 6005 6141 '` [X §2.3]) → HOST `proto-login` (RpWowClient sends the flavor's build per DESK-94, campaign-inventory.md:65 [A/R]) → **must still COMPLETE** (challenge served, SRP6 proceeds — pinning that realmd does not gate builds at the challenge; the datum shapes the advertisement); `SELECT-String realmd.log "build\|reject"` (no refusal expected); then `SQ(...,'UPDATE realmlist SET realmbuilds=\'5875 6005 6141 \' WHERE id=1')` → proto-login OK. Cross-references: matrix-multiplayer.md round-4 annex (the executed refutation), matrix-multiplayer.md LV-multiplayer-18 (the true wrong-client negative = client declines the advertisement; artifact stage), matrix-flavor-import.md LV-flavor-import-18/19 (the app-layer VAL-03 identity gate — a different, still-valid layer) | none | with 5875 unlisted, proto-login STILL completes (challenge answered, SRP6 proceeds — non-completion would itself be a regression finding); after restore, LV-04 shape passes again. **→ EXECUTED round 4 (inverted form, build/rp-desktop/round4b.json): PASS — pre-state `realmbuilds='5875 6005 6141 '`; flipped to `'12340'`; `proto-login` → `ok:true`; `proto-world` → `worldAuth=12` (AUTH_OK — the full SRP6+world chain COMPLETED with 5875 excluded from the advertisement datum); `SELECT-String realmd.log "build\|reject"` → no refusal lines; restored byte-exact `'5875 6005 6141 '`; clean stop exit 0** | P1 |
| LV-bot-actors-31 | Relay attach telltale: stale-runtime values carried by the relay (HARNESS SELF-TEST — not product verification) | vanilla (EMU) | EMU: `EMU ping` first op; PY1 compare returned `runtimeBuildId`/`nativeCmangosCommit`/`nativePlayerbotsCommit` against `schemas/realm-runtime-lockfile*.json` | none | **Scope honesty (audit round 1):** the relay does CARRY the three values (WorldConsoleRelay.kt:190-198 [R]) and this row asserts they are present and match the current lockfile. The attach-time REFUSAL the same comment promises is implemented in NEITHER the app NOR the harness today (greps over `tools/*.py` incl. `rp_harness/session.py` and the androidTest runners found no comparison — [X audit round]); the comparison in this row is the matrix's own driver. Demoted to P3 harness self-test; the missing enforcement is recorded as a gap in §6, not verified here | P3 |
| LV-bot-actors-32 | Realm-status surface agrees with world state (DESK-127; DESK-29/61 world-presence sampling seam) | vanilla | HOST: `hello()`/`online()` ops + `SQ('classiccharacters','SELECT COUNT(*) FROM characters WHERE online=1')`; EMU: `EMU realm-status`/`EMU world-status` (ServerStatusJson lane, DESK-127, campaign-inventory.md:137 [A/R]) | none | op-reported online counts == SQL online counts (± the probe char); status JSON includes the 3724/8085 listen echo | P2 |
| LV-bot-actors-33 | Account lifecycle ops → SQL ground truth (+ DESK-136 credential-entry gate: validate-and-explain, never mangle) | vanilla | [JSH] gate legs, fat-jar classpath (**executed** [X]): `UserAccountStore.Companion.isValidCredential("café")` → `false`, `isValidCredential("livetest")` → `true`; HOST `account('livetest','pw',3)`; EMU `EMU world-account livetest pw` / `EMU world-gm livetest 3` / `EMU world-account-status livetest`; `SQ('classicrealmd','SELECT username,gmlevel FROM account WHERE username=\'livetest\'')`; cleanup `SQ(...,'DELETE FROM account WHERE username=\'livetest\'')` | none | ops answer ok; SQL row shows the account with gmlevel 3; world-verify on the new account passes; the café name is refused by the shared gate BEFORE any op — never created, never mangled (the field copy is the shared plain-copy reason, AccountEntryValidationTest.kt:30-43; desktop lane row = LV-desktop-app-44) [X] | P2 |
| LV-bot-actors-34 | Autologin credential seam (+ DESK-30 policy gate; + DESK-136 validate-and-explain cross-check) | vanilla | [JSH] policy gate, fat-jar classpath (**executed** [X]): `AutoLoginPolicy.INSTANCE.resolveAutoLogin("p1", false, true, notBot)` → `Decision(off=true, singlePlayerAutoLogin=false, runBotProvisioning=false)`; `(true, true, notBot)` → `Decision(off=false, singlePlayerAutoLogin=true)`; `(true, false, notBot)` → `Decision(off=true)` — no silent fallback (notBot = anonymous `kotlin.jvm.functions.Function1<String, Boolean>`; Java lambdas do not convert); [TST] twin: `cd android && gradlew testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.supervisor.AutoLoginPolicyTest --console=plain`. HOST `autologin('RPTEST','rptestpass')` op (RpHost.kt:160-172); negative: `autologin('RPTEST','nope')` → honest failure reason (DESK-57/133 lane, campaign-inventory.md:145-146 [A/R]) | none | correct creds accepted (ok:true), wrong creds answer `ok:false` with reason; no silent success; the DESK-30 gate never auto-logs in when `autoLoginOnLaunch=false` or no identity is provisioned [X] | P3 |
| LV-bot-actors-35 | reset-state interaction (test isolation verb) | vanilla | HOST: LV-16 whisper first → `memory('Davos')` shows facts; `host.reset('')` (clears `bot_player_facts` + `bot_player_relationship` per README:49-59 [R]); `host.memory('')` world summary + `SQ('classiccharacters','SELECT COUNT(*) FROM bot_player_facts')` if the table lives in the characters DB (resolve table location via `sqlite_master` at run time — it may sit in classicmangos) | none | reset answers with pre-delete counts; post-reset world summary shows `totalFacts:0`/`totalRelationships:0`; SQL count 0 | P2 |
| LV-bot-actors-36 | Profile switch retargets behavior conf (DESK-116/118/124/134) | vanilla | `[SET-BACKUP]`; PY1: write `botProfileId=mobile-quiet-b25-v1` into `settings.json` → `gradlew rpHost -PrpBots=25` → `grep -E "LimitCombatActivity\|botActiveAlone\|AutoDoQuests" run\aiplayerbot-mobile-quiet-b25-v1.conf` (expect limit on, quests off); switch to `mobile-balanced-b100-v1` → conf flips (AutoDoQuests=1); `world-start-bot <profile>` equivalently on EMU (WorldConsoleRelay.kt:149); `[SET-RESTORE]` before the next row | none | each boot stages the conf matching the registry profile (BotProfiles.kt values per id); world boot accepts both; `[SET-RESTORE]` exits 0 and the live `botProfileId` (`bench-autologin-b50-v1` [X §2.6]) is back | P2 |
| LV-bot-actors-37 | On-device bot world: stack-up-bot + target waves (EMU) | vanilla (EMU) | Boot headless AVD per environment.md (emulator binary not on Git-Bash PATH — use `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe`, run_differential_parity.py:42-43); from Git-Bash: `cd android && cmd //c "gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -PpocketAbi=x86_64 -PpocketLane=full --console=plain"` + install both APKs (audit round 2: `-PpocketAbi` mandatory, build.gradle.kts:948-951; the relay needs the androidTest APK, world_console.py:37); stage `build/o11-bot-data/o11-server` via `python tools/run_bot_pressure_benchmark.py --only b --waves 25:120,50:180` (preflight refuses without the o11 store, run_bot_pressure_benchmark.py:47-57 [R]); then `python tools/world_console.py` attach → `stack-up-bot bench-autologin-b100-v1` | none | benchmark report: waves reached achieved plateaus; `world-status` healthy (tick p99 < profile admission bound 250 ms); saveall per wave rc=0 | P1 |
| LV-bot-actors-38 | Real game client as the playing tester (launchClient + typed login; DESK-138 typed-entry targeting — Win32AutoLogin types into the detected login window; the JVM detection seam is LV-desktop-app-45) — **DEDICATED INTERACTIVE SLOT, NOT the unattended lane** | vanilla | `cd desktop && gradlew launchClient` then login with the gate account (WINDOWS_QUALIFICATION.md §2.2-2.3 [R]); CLI variant: `gradlew rpHost -PrpClient=1 -PrpAccount=RPTEST -PrpPassword=…` (RpHost.kt:89-99 auto-types via Win32AutoLogin). **Schedule constraint (audit round 1):** this opens a real WoW.exe window with synthetic focus-typing — it steals desktop focus and violates the environment's "no window, no focus steal" unattended contract (environment.md §Resource profile [R]). Run only in a dedicated slot with the user informed, never while other campaign rows run | **R3** (1.12.1.5875 client — absent per requirements.md §R3 [A]; staged settings.json still points at the missing path [X §2.6]) | client process live (`event: client pid>0`), `autologin ok:true`, world.log shows the real client's session; SQL shows the player online among bots. The unattended coverage of the same contracts (SRP6 auth, char login, in-world chat) is rows 04/28/30/34 via the RpWowClient wire client, which opens no window | P2 |
| LV-bot-actors-39 | On-device world-chat + memory delta via relay (EMU) | vanilla (EMU) | After LV-37 boot: `EMU world-chat Davos whisper <botname> "Hail, friend"` → `EMU llm-memory-state Davos` before/after; `EMU world-persistence RPTEST Davos` (WorldConsoleRelay.kt:158-168) | none | memory delta >0 post-whisper (three-way table; memory evidence = pass); persistence op answers character JSON; `reset-state` clears (LV-35 shape on device) | P1 |
| LV-bot-actors-40 | TBC/WotLK bot-actor play (forked cores) | tbc+wotlk | Same Lane-D/Lane-E sequences with `--flavor tbc|wotlk` staging once R4 forks land and flavor pins resolve (sources.json tbc/wotlk entries go `registered`; schemas/flavor.json qualified flips — campaign-inventory.md:287 [A/R]); staging gates already take `--flavor` (FIX:S1.2-8, campaign-inventory.md:44 [A/R]) | **R1+R4 / R2+R4** (clients + forks — externally blocked, campaign-inventory.md:275 [A/R]) | **design-only, NOT runnable today** (single-qualified-flavor lane fails loud: `ClientFlavors.qualified().size==1` require, BotProfiles.kt:223-231 [R]); pass condition = same per-system assertions as LV-02..13 under the tbc/wotlk lane | P3 |
| LV-bot-actors-41 | Brute-force lockout ENGAGEMENT (engine enforces WrongPass.*, not just emits it) — **SPLIT DISPOSITION (audit round 2), mirroring LV-multiplayer-22**: control lane (f) = runnable now-test; engagement lane (a)-(e) = **blocked-tracked on P-3** | vanilla | **NOW-RUNNABLE — (f) control lane (loopback bind, RpHost default; the exact path multiplayer-22's round-2 control run EXECUTED and passed, matrix-multiplayer.md annex :288):** fire 10 wrong-password wire logins against `127.0.0.1:3724` (probe: `python out/live-verification/scripts/srp6_probe.py --host 127.0.0.1 --port 3724 --account RPTEST --wrong-password --attempts 10`, golden-verified + wire-proven there); then one CORRECT-password attempt must still be ACCEPTED; assert `SQ('classicrealmd','SELECT failed_logins FROM account WHERE username=\'RPTEST\'')` unchanged (0) and the `ip_banned` row count unchanged before/after (keys absent ⇒ `GetIntDefault("WrongPass.MaxCount",0)`=0 ⇒ the counter block is skipped entirely, AuthSocket.cpp:664-667 [R]). **BLOCKED-TRACKED — (a)-(e) engagement lane** (realmd bound NON-loopback): the only two vehicles are (i) LV-multiplayer-08's guest LAN_HOST session — gated on tracked prerequisite **P-3** (the `allowLanPlayers` DataStore toggle is UI-only; AndroidRuntimeBackend.kt:180-186 refuses without it — matrix-multiplayer.md P-3 entry and LV-multiplayer-22's identical "blocked-tracked on P-3" marker) — or (ii) a desktop LAN verb that does NOT exist (every desktop CLI gate hardcodes RuntimeMode.LOCAL — gap G-4). The engagement mechanism, ready to run once P-3 lands: stage check `grep -E "WrongPass" <staged realmd.conf>` shows `MaxCount = 10 / BanTime = 900 / BanType = 0` (ServerRuntimeContract.kt:109-114, emitted ONLY on non-loopback binds [R]); 10 wrong-password wire logins at `<lan-ip>:3724` (AuthSocket.cpp:663-667 counts); `failed_logins` = 10 + `ip_banned` row with 900 s window (INSERT form :691-694) + realmd.log `IP ... got banned ... failed to authenticate '10' times` (:695-696); 11th CORRECT attempt → refused `AUTH_LOGON_FAILED_FAIL_NOACCESS` + `[AuthChallenge] Banned ip ... tries to login!` (:417-424); cleanup `DELETE FROM ip_banned; UPDATE account SET failed_logins=0` | none (control lane; the engagement lane is blocked on tracked prerequisite P-3, NOT on a user artifact) | control lane (assert today): correct-password logon still accepted after 10 wrong attempts; `failed_logins` unchanged (0); zero new `ip_banned` rows — the loopback lane never engages. Engagement lane (when P-3 lands): counter reaches 10, `ip_banned` row written, both log lines present, correct-password login then refused — either half's absence on its lane = the conf names a lockout the core never enforces → FAIL | P1 (control: P2, runnable) |
| LV-bot-actors-42 | G3-10 pin suites: catalog/binding/WTF-layout frozen vanilla pins (mirrored in **matrix-desktop-app.md as LV-desktop-app-37**, next to LV-desktop-app-07 per audit round 1 — that file now exists; the desktop-app row is canonical, this row is the same-mechanism duplicate kept for this area's coverage) | vanilla | From Git-Bash invoke gradle as `cmd //c "gradlew.bat …"` inside the project dir (Shared convention, matrix-bots-llm.md): `cd android && cmd //c "gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.client.VanillaCatalogPinsTest --tests com.pocketrealm.client.WowVanillaBindingCatalogTest --tests com.pocketrealm.ingame.InGameSettingsFilesLayoutPinTest --console=plain"` — `-PpocketAbi` is MANDATORY on every invocation of the android module (audit round 2; build.gradle.kts:948-951 throws `GradleException("Missing required -PpocketAbi=<abi>…")` at configuration, live-failed by matrix-timeframe-era.md spot-proof 8; no `-PpocketLane` needed here — unit tests produce no APK and the lane defaults to full, build.gradle.kts:956). Classes verified: VanillaCatalogPinsTest.kt:47/:57/:63 — `WowVanillaSettingsCatalog.CATALOG_VERSION==2` + `8e1ae853…` entry digest + binding-catalog v2; WowVanillaBindingCatalogTest.kt — WowBindingCategory/Semantics ordinals; InGameSettingsFilesLayoutPinTest.kt:11,21,51-57 — `VanillaWtfLayout` byte-for-byte path pins | none | all three classes pass (gradle `BUILD SUCCESSFUL`, 0 failures) — any pin drift fails the build loudly; record the test counts in the report. **→ EXECUTED round 3: `gradlew.bat :app:testDebugUnitTest -PpocketAbi=x86_64 --tests …` → `BUILD SUCCESSFUL in 2s`, GRADLE_EXIT=0 — VanillaCatalogPinsTest 6 tests / WowVanillaBindingCatalogTest 7 / InGameSettingsFilesLayoutPinTest 4 = 17 tests, 0 failures, 0 errors, 0 skipped (android/app/build/test-results/testDebugUnitTest/TEST-*.xml)** | P2 |
| LV-bot-actors-43 | Directed whisper bot-commands (playing tester converses with the bot AI: invite / leave / who) | vanilla | HOST probe in world next to an online bot (`<bot>` from `SQ('classiccharacters','SELECT name FROM characters WHERE account>4 AND online=1 LIMIT 1')`): `host.chat('Davos','whisper','<bot>','invite')` — whisper lands in `PlayerbotAI::HandleCommand` (PlayerbotAI.cpp:1371 [R]); `invite` is an unsecured command (PlayerbotAI.cpp:1353 [R]) routed to `InviteToGroupAction` (ChatActionContext.h:164 [R]); poll `SQ('classiccharacters','SELECT m.memberGuid FROM group_member m JOIN characters c ON m.memberGuid=c.guid WHERE c.name IN (\'Davos\',\'<bot>\')')` every 10 s up to 120 s; then `host.chat('Davos','whisper','<bot>','leave')` → `LeaveGroupAction` (ChatActionContext.h:97 [R]); re-query. Optional probe leg: `whisper '<bot>' who` → `WhoAction` replies via `ai->TellPlayer` (WhoAction.cpp:68-70 [R]) — capture via proto-chat drain (reply may ride a plain-whisper SMSG type the RpWowClient parser does not branch on, RpWowClient.kt:328-351 [R], so the SQL legs are the assert) | none | after `invite`: the Davos+`<bot>` pair appears in `group_member` (bot-originated invite on command); after `leave`: the pair is gone and `groups` has no dangling member rows; world alive throughout | P2 |

## 5. Coverage map (deliverable ID → rows)

| Deliverable (campaign-inventory.md) | Rows |
|---|---|
| BOT-PIN (playerbots one pin 7e2cd2fb) | 01-13, 16, 22, 23, 37, 39 (all bot play rides the pinned submodule; attach-time commit telltale row 31) |
| BOT-CONFMATRIX (generated conf keys) | 07, 13, 36 (staged-conf greps are the conf surface made live) |
| BOT-POLICY (BotPopulationPolicy vanilla constants) | 23, 37 (target bounds, admission authority) |
| DESK-8/23/43/99 (admission + load protection) | 22, 23, 37 (fail-closed no-target, clamp, waves) |
| DESK-116 (live profile application / retarget) | 36, 20 |
| DESK-118/124 (advanced tuning / launcher overrides) | 36 |
| DESK-122 (pause/resume) | 20 |
| DESK-123 (startBotProfile/setBotTarget ops on running world) | 01, 02, 23, 37 |
| DESK-77 (characterPersistence/worldChat/resetState/llmMemoryState verbs) | 26, 35, 39 |
| DESK-117 (companion-mode enter/exit — pause leg, recorded narrower) | 20 |
| DESK-127 (realm-status surface) | 32 |
| DESK-134 (shipped defaults) | 36 |
| DESK-29/61 (world-presence sampling seam) | 02, 32 (botstatus/online ops) |
| DESK-94/56 (SRP6 client identity; the per-build check lives CLIENT-side — server serves any build at the challenge) | 04, 28, 30 |
| C1 realmbuilds (the ADVERTISEMENT datum — the build gate is CLIENT-side: core `ok_build` shapes only the realmlist advertisement; challenge-level refusal refuted by multiplayer's round-4 execution, matrix-multiplayer.md annex) | **30 (inverted: flip the datum, auth still completes — EXECUTED round 4)**; the true wrong-client refusal negatives live in LV-multiplayer-18 (client declines the advertisement; artifact stage), LV-flavor-import-18/19 (app-layer VAL-03 identity gate — different, still-valid layer), LV-server-db-34 (artifact client-declines leg + artifact-free advertisement check) |
| DESK-9/28 (embedded LLM lane — world chatter) | 17, 18 (artifact-required), 16/39 (memory-evidence halves run without it) |
| TF-6 (per-stage chat era policy) | 17/18 (era line rides the same BotLLM lanes; vanilla default byte-equivalence pinned by check_timeframes [A/R]) |
| MP:CONDITIONAL-LOCKOUT | 28 (conf emission/absence), **41 (engine ENFORCES the lockout on a non-loopback bind; loopback control never engages)** |
| MP:GUEST-BANS (engine ban predicate) | 29 |
| G3-10 (BotPopulationPolicy vanilla constants leg) | 03, 23 |
| G3-10 (catalog/binding-ordinal/WTF-layout pin legs — VanillaCatalogPinsTest, WowVanillaBindingCatalogTest, InGameSettingsFilesLayoutPinTest) | **42 here; canonical home LV-desktop-app-37 in matrix-desktop-app.md** (re-homed in round 2, next to LV-desktop-app-07 per audit round 1) |
| Directed whisper bot-commands (unsecured commands invite/leave/who — bot AI converses back) | **43** |

## 6. Honesty notes

- Execution status: round 3 executed the no-boot artifact-free slice — rows 03, 12, 13
  (conf leg) and 42 PASS with quoted outputs (§2 round-3 block, §10); row 13's soak leg and
  every world-boot row (01-11, 14-37 boot-dependent parts) remain designed-not-run. Earlier
  rounds executed the assert channels themselves (§2 checks 1-7: live SQL queries over the
  staged datadir; world.log/realmd.log/staged-conf inspections; settings read; GM-command
  semantics greps).
- The Android-side SQL path is stated as `adb shell find …` deliberately: the on-device
  datadir parent under `/data/data/com.pocketrealm` was not executed this session (no
  emulator was booted), so the row resolves the parent at run time instead of naming a
  guessed path (DatabaseSqliteControlPlane.kt:90 pins only the `sqlite-datadir` leaf [R]).
- `.npc add <entry>` row 06/14 selects the creature entry from classicmangos by query at
  run time; the query shape (`rank`/`minlevel` columns) is standard cmangos creature_template
  but the exact column names were not verified this session — resolve via
  `PRAGMA table_info(creature_template)` in the same step.
- LLM rows 17/18 require the R6 GGUF artifacts (requirements.md §R6 [A]) — the manifest
  records no external-endpoint alternative, so those rows are R6-only (see §7 item 2); rows
  16/39 are designed so the memory-evidence half of the same feature verifies artifact-free.
- Rows 39/40 (TBC/WotLK play) are marked design-only: the flavors are `qualified:false`
  with unresolved forks (requirements.md §R4, campaign-inventory.md:287 [A]), and the lane
  structurally refuses to run a non-single qualified flavor (BotProfiles.kt:223-231 [R]).
- The probe character convention (`Davos`/RPTEST + `tools/reset_davos.py`) is the repo's own
  battery convention (desktop_play.py:31-33, desktop_boots.py:37-39 [R]); rows that create
  additional accounts clean up after themselves (row 33 DELETE leg).
- settings.json on this host contains a live external-LLM API key [X §2.6]; it was noted but
  deliberately NOT copied into this file. Treat the settings file as sensitive in the live stage.

## 7. Audit round 1 resolutions (2026-10-02)

1. **G3-10 coverage** — rows 03/23 cover only the BotPopulationPolicy leg. The pins legs now
   have row 42 running `VanillaCatalogPinsTest` + `WowVanillaBindingCatalogTest` +
   `InGameSettingsFilesLayoutPinTest` (class paths and pin contents verified this round:
   VanillaCatalogPinsTest.kt:47,57,63; InGameSettingsFilesLayoutPinTest.kt:51-57). The audit
   directs this row into `matrix-desktop-app.md` next to LV-desktop-app-07; **that file does
   not exist at fix time** (`ls out/live-verification` [X]) so the row is recorded here with
   an explicit re-home marker — the desktop-app section must adopt or mirror it.
2. **"R6 or external LLM creds" wording** — dropped from rows 17/18. requirements.md §R6
   records only the two pinned GGUFs (url="") and the self-downloading HF entry; no
   external-endpoint artifact (URL shape, key handling) exists in the manifest, so the
   fallback path has no manifest entry and the rows now run under R6 only. NOTE for the
   requirements owner: the external OpenAI-compatible lane EXISTS as a product feature
   (`llmExternalModel`/`llmExternalUrl`/`llmExternalApiKey` settings, live on this host
   [X §2.6]) — if later-stage rows should use it, requirements.md R6 needs an R-note first.
3. **Ban time format** — resolved by live check: `account_banned.banned_at/expires_at` are
   declared `INTEGER` and the engine compares them against `_UNIXTIME_`
   (AuthSocket.cpp:466-467). Row 29 standardized on the epoch form
   (`strftime('%s','now')` / `+1 hour`), matching the engine's own autoban write
   (`_UNIXTIME_`, AuthSocket.cpp:683-685) and bots-llm-14's epoch semantics. (My original
   `datetime('now')` ISO-string form was a harness bug — fixed.)
4. **Real-client rows vs the unattended contract** — row 38 now carries an explicit
   DEDICATED INTERACTIVE SLOT marker (WoW.exe window + Win32 typing steals desktop focus,
   violating environment.md's "no window, no focus steal"); it is excluded from the
   unattended lane, and the same contracts are covered unattended via the RpWowClient wire
   rows 04/28/30/34, which open no window.
5. **bootWorld cycle-2 mechanism** — row 24 reworded: run `gradlew bootWorld` ONCE and
   assert the gate's own pinned in-process cycle-2 refusal leg; the "run the task twice"
   phrasing (two JVMs = two fresh lifetimes) is removed.
6. **Lockout engagement test added** — new row 41 drives 10 wrong-password wire logins at a
   non-loopback-bound realmd and asserts the engine's actual enforcement (failed_logins=10,
   ip_banned 900 s row, both BASIC_LOG lines, correct-password login then refused) plus the
   loopback control lane that must never engage (keys absent ⇒ MaxCount=0 ⇒ counter block
   skipped, AuthSocket.cpp:664-667). Grounded against the live conf values
   `MaxCount=10/BanTime=900/BanType=0` (ServerRuntimeContract.kt:112-114) — BanType=0 means
   the IP-ban branch (:691-696), which is what the row asserts.
7. **Telltale row demoted** — row 31's original pass condition asserted only the matrix's
   own comparison (tautology). Verification this round: greps over `tools/*.py` (incl.
   `rp_harness/session.py` — its `ping` at :134-135 only records health) and the androidTest
   runners found NO consumer of `runtimeBuildId`/commits — the refusal WorldConsoleRelay.kt:190-198
   describes is implemented nowhere. The row is now an honest P3 harness self-test (asserts
   the relay CARRIES matching values) and §6/§0 record the missing enforcement as a gap.
8. **Leveling tautology removed** — row 05's pass condition is now strictly "≥1 bot guid
   leveled up in the soak window, else FAIL"; `totaltime` advancing is kept only as a
   recorded liveness precondition, and the soak is conditioned (2 h continuous, lively
   behavior profile, no logouts ⇒ no InstantRandomize re-roll) so a pass is achievable but
   falsifiable.

## 8. Design round 2 (2026-10-02)

The ask repeated (design round 2) over the already-audited matrix; changes:

1. **Audit-1 re-home completed**: `matrix-desktop-app.md` now exists (LV-desktop-app-07 at
   its :53; previous max ID 36 [X §2 round-2 check 2]) — the G3-10 pins row was inserted
   there as **LV-desktop-app-37** (next to row 07, same [TST] mechanism, android-module
   gradle invocation spelled exactly), with a provenance bullet added to that file's honesty
   notes. Row 42 here is now the mirrored duplicate; its "TO BE RE-HOMED" marker is replaced
   by the canonical-home pointer.
2. **New row 43** (directed whisper bot-commands) — the one in-area interaction the round-1
   matrix described only indirectly: the probe whisper-drives the bot AI's OWN command set
   (`invite`/`leave` are unsecured commands — PlayerbotAI.cpp:1344-1368 [R] — routed to
   `InviteToGroupAction`/`LeaveGroupAction`, ChatActionContext.h:164/:97 [R]) with
   SQL-evident outcomes (`group_member` pair appears/disappears). The `who` leg's reply
   transport is documented honestly: `ai->TellPlayer` (WhoAction.cpp:68-70 [R]) sends a
   plain-whisper SMSG type the RpWowClient parser may not branch on (RpWowClient.kt:328-351
   [R]), so the SQL legs carry the assertion.
3. **Re-verification pass**: rpHost task, sibling matrices, and the bots-llm endpoint
   wording (self-consistent, untouched) re-checked — §2 round-2 block; no drift found in the
   mechanisms §1 relies on.
4. Nothing was renumbered: rows 01-40 keep their IDs (audit-1 cross-references in other
   matrices — e.g. multiplayer-06/server-db-05 citations — remain valid).

## 9. Audit round 2 resolutions (2026-10-02)

1. **`-PpocketAbi` mandatory on android gradle invocations** — verified the gate first:
   android/app/build.gradle.kts:948-951 throws `GradleException("Missing required
   -PpocketAbi=<abi>…")` unconditionally (`pocketLane` defaults to "full" when absent,
   :956, so only APK-pair builds conventionally carry it). Row 37 now builds the APK pair
   with `-PpocketAbi=x86_64 -PpocketLane=full`; row 42 (and its desktop-app mirror
   LV-desktop-app-37) carries `-PpocketAbi=x86_64` on the unit-test invocation; all gradle
   lines now show the Git-Bash `cmd //c "gradlew.bat …"` form, and §3 records both rules
   (mirroring matrix-bots-llm.md's Shared conventions). Cross-file: LV-bots-llm-04's step 2
   fixed in matrix-bots-llm.md (rows 08/22 inherit its steps 1-5); LV-server-db-33 was
   ALREADY fixed by its owner (carries `-PpocketAbi=x86_64` with an audit-round-2 note).
2. **settings.json backup/restore legs** — §3 now defines `[SET-BACKUP]`/`[SET-RESTORE]`
   (timestamped copy + `filecmp.cmp(shallow=False)` byte-compare restore, adopted from
   matrix-bots-llm.md's Shared conventions) and rows 05/06/07/36 carry step-0 backup +
   cleanup-restore legs with `exit 0` in their pass conditions. The user's live
   `botProfileId` (`bench-autologin-b50-v1` [X §2.6]) can no longer leak into bots-llm-15's
   default-posture expectations.
3. **Row 41 split** (findings 3/5) — engagement lane (a)-(e) is now marked **blocked-tracked
   on P-3** (the guest `allowLanPlayers` DataStore toggle is UI-only; the desktop LAN verb
   does not exist — gap G-4), mirroring LV-multiplayer-22's "P1 (blocked-tracked on P-3)"
   marker and cross-referencing matrix-multiplayer.md's P-3 entry; the control lane (f) is
   the runnable now-leg (the exact path multiplayer-22's round-2 control run executed and
   passed, matrix-multiplayer.md annex). Artifacts column and the §0 count now state the
   split explicitly; the probe reference switched to `out/live-verification/scripts/srp6_probe.py`
   (authored + golden-verified + wire-proven by the multiplayer section) instead of my
   probe_world_auth.py sketch.
4. **`bots` op ownership (P-5)** — row 23 was already reconciled in the external harmonization
   pass before this fix round: it now drives the EMU owning verb (`world-bots` →
   `worldApi().setBotTarget`) and pins the executed HOST refusal (`ok:false reason:"begin:2"`,
   effectiveTarget profile-owned) citing matrix-multiplayer.md's multiplayer-15 annex;
   LV-bots-llm-03 likewise already pins the begin:2 contract. No further change needed beyond
   repairing the control-byte corruption the harmonization edit introduced in row 23's path
   (`%LOCALAPPDATA%/PocketRealm/runtime/server/bot-admission.json` now reads cleanly; the line
   carried literal 0x08/0x0D bytes [X]).
5. **Ban INSERT primary key** — row 29 no longer hardcodes id=1: the `id` INTEGER PRIMARY KEY
   auto-assigns and cleanup deletes by `account_id` + the probe's unique `reason` fingerprint
   (collision with any prior ban-history row is now impossible; matches the multiplayer
   ban_toggle.py driver's resolve/update semantics).

## 10. Design round 3 (2026-10-02)

The ask repeated over the fully-audited matrix (43 rows, audit rounds 1-2 retained). Round-3 work:

1. **Integrity re-verification** after the audit-2 round's external harmonization and my
   repairs: 43 rows, zero control bytes, §0-§9 coherent [X §2 round-3 check 1].
2. **Executed the no-boot artifact-free slice** (the campaign pattern: sibling matrices
   executed their now-tests in later rounds). Rows 03, 12, 13 (conf leg) and 42 now carry
   `EXECUTED round 3` annotations with exact outputs; row 42's three pin suites ran green
   (17 tests / 0 failures). All four were designed as now-tests and now have live evidence.
3. **Design correction from execution (row 03)**: the original single cross-DB subquery
   cannot work in sqlite (no `account` table in the characters DB) — the mechanism now
   spells the two-step query; found because the ask's own check was executed, not assumed.
4. Rows 01-02, 04-11, 14-37 remain designed-not-run: each needs a live world boot
   (`gradlew rpHost` or the EMU lane) — heavier than this ask's bounds; the harness drivers
   they name (DesktopHost, desktop_boots.py, world_console.py) are unmodified since §1 was
   written. No row's grounding drifted: the §2 round-3 block re-proved the SQL/log/conf
   channels against the live datadir.
5. **Round-3 file recovery disclosure**: during this round a byte-repair script of mine
   corrupted this file (off-by-one deletions while stripping CRLF `\r` bytes); it was
   rebuilt verbatim from this session's captured full read plus every applied edit's exact
   text. Any external edit made between the captured read and the corruption would have been
   lost — none was detected (§2 round-3 check 6).

## 11. Audit round 3 resolutions (2026-10-02)

1. **P-5 posture applied to the remaining desktop `-PrpBots`/`bots` rows** (multiplayer-15's
   executed observation: `bots:5` → `{"ok":false,"reason":"begin:2"}`, `effectiveTarget=25`
   profile-owned — matrix-multiplayer.md:218/:286; bots-llm-01/02/03 and row 23 were already
   reconciled, rows 01/02/22 were not):
   - **Row 01**: keeps `-PrpBots=25` but now states WHY it is the owning surface (the boot
     flag registers the admission generation — RpHost.kt:83-87) and that a mid-session `bots`
     call would refuse `begin:2` under that generation.
   - **Row 02**: rewritten in the bots-llm-01 form — boot with NO `-PrpBots`, poll
     `botstatus()` against the PROFILE-OWNED `effectiveTarget` (the live settings profile is
     `bench-autologin-b50-v1` [X §2.6]); the `bots(25)` call is gone entirely (the clamp leg
     stays on the EMU owning verb in row 23). Pass condition no longer depends on the refused
     op or on the profile's target coincidentally being 25.
   - **Row 22**: the no-target premise is now REAL — fresh-read precondition (the bots-llm-09
     posture named by P-5's amendment) strips `botProfileId` under `[SET-BACKUP]`/`[SET-RESTORE]`
     so no monitor starts; plus the bots-llm-02(b) EMU cross-check (`world-bots 0` ⇒
     `effectiveTarget=0`).
   - **§3 canonical HOST example**: `h.bots(25)` replaced by `h.hello()`/`h.botstatus()` with
     an explicit never-call-`bots` pointer to P-5.
2. No rows added or removed: still 43 rows; IDs unchanged (cross-matrix references stay valid).

## 12. Design round 4 (2026-10-02)

The ask repeated over the fully-audited matrix. Round-4 work — the world-boot slice executed:

1. **Six rows moved from designed to EXECUTED** against the real product stack (§2 round-4 block
   for exact outputs): rows 01, 02, 04 (partial), 19, 26 (3/4 legs) and the row-28 discovery.
   Cumulative executed set: 03, 12, 13 (conf leg), 42 (round 3) + 01, 02, 04 (partial), 19, 26,
   28-discovery (round 4). Remaining designed-not-run: 05-11, 13 (soak leg), 14-18, 20-21, 23-25,
   27, 29-40, 43.
2. **Two design corrections came out of execution** (the reason this campaign executes):
   (a) `proto-login` re-seeds SRP6 verifiers (RpHost.kt:178-192) — any "wrong password through the
   console op" design is void; wrong-password refusal needs the raw wire probe. (b) the chat op's
   sender-online check precedes name validation — the reason table is precedence-ordered.
3. **No grounding drift**: boot 22.1 s against the staged desktop lane; the P-5 profile-ownership
   posture (audit-2/3) is now EXECUTED evidence, not just pinned contract — the population ramped
   to the profile's own target with the `bots` op never called.
4. No rows added or removed: 43 rows; IDs unchanged. The desktop-app matrix's LV-desktop-app-37
   (row 42's canonical twin) was already annotated executed in round 3.

## 13. Audit round 4 resolutions (2026-10-02)

1. **Row 30 re-grounded on the executed refutation** — the row's original server-refusal oracle
   (`UPDATE realmbuilds='12340'` → proto-login refused + realmd.log refusal line) contradicted
   multiplayer's round-4 execution (`srp6_probe.py --challenge-only --build 5875/8606/5467` all
   answered; `ok_build` AuthSocket.cpp:920/986 shapes only the realmlist advertisement). The row is
   now INVERTED (flip the datum → auth chain still completes → restore) with the cross-references the
   audit named: matrix-multiplayer.md round-4 annex (the refutation), LV-multiplayer-18 (the true
   wrong-client negative = client declines the advertisement; artifact stage), LV-flavor-import-18/19
   (app-layer VAL-03 identity gate — different, still-valid layer), LV-server-db-34 (its own re-stated
   artifact/advertisement split).
2. **Executed here once, as the audit directed**: flipped `realmbuilds` to `'12340'` on the live
   datadir, booted the stack, drove the full probe chain — `proto-login ok:true`, `worldAuth=12`
   (AUTH_OK) at build 5875, zero build/refusal lines in realmd.log — then restored the datum
   byte-exact (`'5875 6005 6141 '`) and stopped clean (exit 0). The refusal, where it exists, is
   client-side; this lane's server answers every build.
3. **C1 coverage-map line added** with the corrected layering (advertisement datum, client-side gate)
   and the cross-area negative owners (multiplayer-18 / flavor-import-18/19 / server-db-34);
   the DESK-94/56 line re-worded to the client-side location of the per-build check.
4. LV-server-db-34 required no edit from this section: its owner had already re-stated it at the
   correct layer (verified before fixing — matrix-server-db.md:107 and the :151 C1 line carry the
   executed-refutation language).
5. No rows added or removed: 43 rows; IDs unchanged.


## 14. Final-audit coverage round (2026-10-02, plan-repair)

Added after the final coverage audit (`python tmp/final-coverage-audit.py` over the
assembled plan) reported zero embedded test rows for DESK-30/136/138 (the
`desktop-onboarding-autologin` family, campaign-inventory.md:140-148, whose DEK-57/133
halves were already covered by row 34). Per the repair directive, the existing rows were
**amended in place** (no new rows): row 33 gained the DESK-136 credential-entry gate legs,
row 34 gained the DESK-30 policy-gate legs and the DESK-136 cross-check, row 38's feature
cell now cites DESK-138 (its live typed-entry leg is the Win32AutoLogin half of that
deliverable; the JVM detection seam is LV-desktop-app-45 in matrix-desktop-app.md).

**Executed this ask [X]** (fat-jar classpath `pocketrealm-desktop-app-0.103.0-alpha.jar` +
kotlin-stdlib 2.0.21 + org-json 20240303, jshell 17.0.20):
`resolveAutoLogin("p1", false, true, notBot)` → `Decision(off=true,
singlePlayerAutoLogin=false, runBotProvisioning=false)`;
`resolveAutoLogin("p1", true, true, notBot)` → `Decision(off=false,
singlePlayerAutoLogin=true)`; `resolveAutoLogin("p1", true, false, notBot)` →
`Decision(off=true)`; `UserAccountStore.Companion.isValidCredential("café")` → `false`;
`isValidCredential("livetest")` → `true`. NOT executed: the HOST/EMU op legs of rows
33/34 (unchanged, prior rounds' state stands), the android `AutoLoginPolicyTest` twin.

### 4.8 — section file `matrix-launch-boundary.md` (embedded verbatim)

# Live-Verification Matrix — launch-boundary (final-audit coverage group, added 2026-10-02)

Written 2026-10-02 by the plan-repair step after the final coverage audit
(`python tmp/final-coverage-audit.py` over the assembled plan reported zero embedded test
rows for the nine `desktop-launch-boundary` deliverables — campaign-inventory.md:151-159).
Scope per the repair directive: **DESK-13/27/46/50/51/58/89/132/139**. Every row here is a
host-JVM lane (gradle desktop tests, the Android unit twin with the mandatory
`-PpocketAbi=x86_64`, or jshell probes against the desktop fat jar
`desktop/build/libs/pocketrealm-desktop-0.103.0-alpha.jar` — 1,498 `com/pocketrealm`
entries, classes verified present via `unzip -l` this ask). The real-window spawn legs of
this boundary stay owned by the DEDICATED-SLOT rows that already exist
(LV-flavor-import-17/-40, LV-desktop-app-34, LV-bot-actors-38); nothing here opens a
window. Evidence classes: **[R]** = read this ask (path:line), **[X]** = executed this ask
(output quoted).

**jshell classpath** (used by every [X] probe below; resolved on this host, this ask):

```
CP = desktop\build\libs\pocketrealm-desktop-app-0.103.0-alpha.jar;
     %USERPROFILE%\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib\2.0.21\618b539767b4899b4660a83006e052b63f1db551\kotlin-stdlib-2.0.21.jar;
     %USERPROFILE%\.gradle\caches\modules-2\files-2.1\org.json\json\20240303\ebb88e8fb5122b7506d5cf1d69f1ccdb790d22a\json-20240303.jar;
     %USERPROFILE%\.gradle\caches\modules-2\files-2.1\net.java.dev.jna\jna\5.14.0\67bf3eaea4f0718cb376a181a629e5f88fa1c9dd\jna-5.14.0.jar;
     %USERPROFILE%\.gradle\caches\modules-2\files-2.1\net.java.dev.jna\jna-platform\5.14.0\28934d48aed814f11e4c584da55c49fa7032b31b\jna-platform-5.14.0.jar
```

The **fat** `pocketrealm-desktop-app-0.103.0-alpha.jar` (48,467,582 B, built 2026-10-02
13:05 — the same jar matrix-desktop-app.md's [JSH] convention pins) is the probe target;
the pre-existing plain `pocketrealm-desktop-0.103.0-alpha.jar` (11:05) is STALE — hit
live this ask: its `Win32AutoLogin.loginSignatureFor("vanilla")` returns `null` where the
fat jar returns the pinned signature [X]. Kotlin version 2.0.21 is the desktop build's
own pin (`desktop/build.gradle.kts:23,71` [R]). jshell 17.0.20 [X]. jshell↔Kotlin interop
rules: follow matrix-desktop-app.md's legend (getters, `INSTANCE`, `Companion` getters —
`RealmEndpoint.LOCAL` reads as `RealmEndpoint.Companion.getLOCAL()` [X]; Java lambdas do
NOT convert to `kotlin.jvm.functions.Function0/Function1` — use anonymous classes [X];
fun-interface params DO take Java lambdas [X]).

## Grounding (all read this ask, E:/pocket_realm_complete)

| Seam | Pins |
|---|---|
| vanilla-tweaks patch lane | `desktop/.../client/DesktopVanillaTweaksPatcher.kt:80,90` (`publishVerified`); `ClientTweaksRuntime.kt:20-40` (effective-resolution + Config.wtf sound-line projection); `Settings.kt:58,75` (`TWEAKS_SCHEMA_VERSION = 2`, `clientTweaksDefaultsMigration` — DESK-13) |
| Config.wtf overlay (DESK-50) | `DesktopRuntimeBackend.kt:1818` ("the app-owned Config.wtf overlay, before spawn"); `ingame/DesktopConfigWtfTest.kt:20,47,84,109,123` (golden-pinned merged file, gate-killing overlay, round-trip, master-sound + software-channels legs) |
| realmlist (DESK-51) | `client/DesktopRealmlist.kt:32` (`internal object`, public `INSTANCE` at runtime), `:34-35` (TARGET_NAME/BACKUP_DIR_NAME), `:55` `projectForSession(clientDir, endpoint, roots, session)` (first-projection backup, merge-only rewrite :21, backup under `roots.runtime/realmlist-backup/` :127), `:78` `restoreForSession` (sha re-verified :83-85, honest false when nothing projected) |
| NearbyInteract (DESK-58/132) | `ClientTweaksRuntime.kt:34` + `DesktopRuntimeBackend.kt:1934` (knob verdict = AUTHORIZED-layout effective resolution over the actual exe hash); `SettingsScreen.kt:394-399` (stepper clamp via shared `NearbyInteractPolicy`); conf pins `DesktopServerRuntimeFilesTest.kt:198-202,464-474` |
| audio mute (DESK-139) | `DesktopRuntimeBackend.kt:145,1849,1868` (`AudioMode.OFF` → `Win32AudioMute.applyLaunchMode(pid, mute = true, muter=…)`); `Win32AudioMute.kt:44-70` (`internal object`; `applyLaunchMode` returns null on success / a RESULT string on honest refusal, never a throw); `Win32AudioMuteTest.kt:20-108` |
| Endpoint for probes | `android/.../supervisor/RuntimeTopology.kt:14,38` — `RealmEndpoint` (private ctor; companion `LOCAL` val reads as `RealmEndpoint.Companion.getLOCAL()` from Java, hit live [X]) |

## Test matrix

| testId | feature | flavor(s) | exact CLI mechanism | required artifacts | pass condition | severity |
|---|---|---|---|---|---|---|
| LV-launch-boundary-01 | Client-tweaks schema-v2 defaults migration + Android applier twin (DESK-13, shared with DESK-46) | vanilla | `cd desktop && gradlew test --tests com.pocketrealm.storage.ClientTweaksDefaultsMigrationTest --console=plain`; twin: `cd android && gradlew testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.storage.ClientTweaksDefaultsMigrationTest --console=plain` | none | rc=0 on both lanes; the stored-schema-below-2 fixture migrates once to pristine defaults and stamps `TWEAKS_SCHEMA_VERSION = 2` (Settings.kt:58,75 [R]) — a second load must not re-migrate | P2 |
| LV-launch-boundary-02 | vanilla-tweaks patcher published at the launch boundary: staged exe + lockfile pin + publishVerified pipeline (DESK-27, DESK-89) | vanilla | `cd desktop && gradlew test --tests com.pocketrealm.client.DesktopVanillaTweaksPatcherTest --console=plain` | none | `stagedPatcherIsPresentAndMatchesItsLockfilePin` green (staged exe == `schemas/vanilla-tweaks-lockfile-win.json` pin [R]); `publishPipelineProducesTheModelVerifiedPatchedImage` green — the nearby-loot acceptance patch lands byte-for-byte or the launch refuses (DesktopVanillaTweaksPatcher.kt:90 `publishVerified` [R]) | P1 |
| LV-launch-boundary-03 | Client-tweaks flag config store legs + shared tests (DESK-46; the ClientTweaksCard's persisted half) | vanilla | `cd desktop && gradlew test --tests com.pocketrealm.storage.ClientTweaksDefaultsMigrationTest --tests com.pocketrealm.client.DesktopVanillaTweaksPatcherTest --console=plain`; then [JSH] store probe (**executed this ask** [X]): fresh temp dir → `new com.pocketrealm.desktop.DesktopSettingsStore(new java.io.File(tmp,"settings.json"), () -> Boolean.FALSE)`; print `load().getTweaks()` and `load().getNearbyInteractTriggerGuardMs()`; `save(load())`; reload and compare | none | gradle rc=0; probe prints the pristine default config and round-trips: `TWEAKS_BEFORE=ClientTweaksConfig(fovEnabled=false, fov=1.925, …, maxCameraDistance=100.0)`, `NBI_BEFORE=250`, `ROUNDTRIP_EQUAL=true` [X]. Without org.json on the classpath the save throws `NoClassDefFoundError: org/json/JSONObject` [X] — the classpath above is part of the row | P2 |
| LV-launch-boundary-04 | App-owned Config.wtf overlay: EULA/TOS/intro-movie gate-killing launch-condition subset + managed merge (DESK-50) — **SPLIT DISPOSITION** | vanilla | Unattended leg: `cd desktop && gradlew test --tests com.pocketrealm.ingame.DesktopConfigWtfTest --console=plain`. Overlay-onto-live-spawn leg: rides the DEDICATED-SLOT rows only (LV-flavor-import-17's spawn; the overlay is applied "before spawn", DesktopRuntimeBackend.kt:1818 [R]) and is ledgered there, never run unattended | none | gradle rc=0: fresh-install gate-killing overlay (:47), unknown-lines round-trip + stale app-owned keys die (:84), audio-off master-sound enforcement (:109), channel-patched software-channels line (:123); the live-overlay observation is recorded on the DEDICATED-SLOT row that owns the spawn | P1 |
| LV-launch-boundary-05 | realmlist first-projection backup + sha sidecar, merge-only rewrite, teardown restore (DESK-51) — **probe executed this ask** | vanilla | [JSH] (**executed this ask** [X]): temp dir `c` → write `realmlist.wtf` = `"## comment\r\nset realmlist old\r\nset patchlist old\r\n"` → `roots=new DesktopStorageRoots(c.getParentFile())`; `s=new DesktopRealmlist.Session()`; `DesktopRealmlist.INSTANCE.projectForSession(c, RealmEndpoint.Companion.getLOCAL(), roots, s)`; print the projected file; `restoreForSession(c, roots, s)` twice; print the file again | none | `PROJECTED=[## comment\|set realmlist 127.0.0.1\|set patchlist old\|]` — ONLY the `set realmlist` line rewritten, comment + patchlist preserved (merge-only, :21 [R]); `RESTORE1=true` (backup + sha256 sidecar verified, :83-85 [R]); `RESTORE2=false` (spent session answers honestly); `AFTER_RESTORE=[## comment\|set realmlist old\|set patchlist old\|]` — original bytes back [X]. Every launch-projection failure throws (launch refuses rather than pointing at stale topology, :44-53 [R]) | P1 |
| LV-launch-boundary-06 | NearbyInteract conf knob gated on AUTHORIZED-layout effective patch resolution over the actual exe hash (DESK-58) | vanilla | `cd desktop && gradlew test --tests com.pocketrealm.client.DesktopVanillaTweaksPatcherTest --tests com.pocketrealm.server.DesktopServerRuntimeFilesTest --console=plain`; android twin: `cd android && gradlew testDebugUnitTest -PpocketAbi=x86_64 --tests com.pocketrealm.server.NearbyInteractPolicyTest --console=plain` | none | `nearbyUseKnobFollowsTheEffectiveLayoutResolution` green (DesktopVanillaTweaksPatcherTest.kt:115 [R]) — a requested patch on a non-AUTHORIZED layout resolves to pristine AND the conf knob verdict follows the EFFECTIVE resolution, never the raw consent; the emitted conf carries `PocketRealm.NearbyInteract`/`…CooldownMs` lines pinned at DesktopServerRuntimeFilesTest.kt:198-202 [R] | P1 |
| LV-launch-boundary-07 | nearbyInteractTriggerGuardMs setting → `PocketRealm.NearbyInteractCooldownMs`, clamped via shared policy (DESK-132) | vanilla | `cd desktop && gradlew test --tests com.pocketrealm.server.DesktopServerRuntimeFilesTest --console=plain` (clamp legs :464-474 [R]); then [JSH] live clamp (**executed this ask** [X]): `System.out.println(com.pocketrealm.server.NearbyInteractPolicy.INSTANCE.normalizeTriggerGuardMs(99999));` under the classpath above | none | the conf line equals the normalized setting (default `NBI_BEFORE=250` [X], conf pin :201-202 [R]); the live clamp prints `2000` for the 99999 request [X] — out-of-range steppers cannot reach the conf; android `NearbyInteractPolicyTest` green (row 06's twin leg) | P2 |
| LV-launch-boundary-08 | Game-audio mute toggle — AudioMode OFF/ON + per-session Core Audio mute with honest refusal (DESK-139) — **probe executed this ask** | vanilla | `cd desktop && gradlew test --tests com.pocketrealm.desktop.Win32AudioMuteTest --console=plain`; live honest-refusal probe (**executed this ask** [X], JNA jars on the classpath): `p=new ProcessBuilder("cmd","/c","timeout","/t","30","/nobreak").start()` (windowless, audio-less); `Win32AudioMute.INSTANCE.applyLaunchMode((int)p.pid(), true, Win32AudioMute.INSTANCE.getSYSTEM(), now, nosleep, 2000L, 250L)` where `now`/`nosleep` are anonymous `kotlin.jvm.functions.Function0/Function1` (Java lambdas do NOT convert — `incompatible types: cannot infer functional interface descriptor` [X]); `p.destroy()` | none | gradle rc=0 across the five legs (first-success confirm, poll-until-session, honest refusal when no session ever appears, muter failures reported not thrown, unknown-pid honest refusal — :20-108 [R]); live probe prints a RESULT string, never a throw: with JNA on the classpath the real Core Audio walk answers `MUTE_RESULT=no default audio render device: -2147024809` [X]; even with JNA missing the contract holds — `MUTE_RESULT=audio mute failed: com/sun/jna/platform/win32/Ole32` [X]. (Note: `applyLaunchMode` polls until its deadline — the probe passes a 2000 ms deadline; the production default is `SESSION_WAIT_TIMEOUT_MS = 20_000L`, :219 [R]) | P2 |

## Coverage map

| Deliverable | Rows |
|---|---|
| DESK-13 | LV-launch-boundary-01, -03 |
| DESK-27 | LV-launch-boundary-02 |
| DESK-46 | LV-launch-boundary-03, -01 |
| DESK-50 | LV-launch-boundary-04 |
| DESK-51 | LV-launch-boundary-05 |
| DESK-58 | LV-launch-boundary-06 |
| DESK-89 | LV-launch-boundary-02 |
| DESK-132 | LV-launch-boundary-07 |
| DESK-139 | LV-launch-boundary-08 |

## Honesty notes

- **Executed this ask ([X]):** the four jshell probes (rows 03/05/07/08) ran to the quoted
  outputs on this host, jshell 17.0.20, kotlin-stdlib 2.0.21 classpath. **Not executed:**
  every gradle leg (rows 01/02/03/04/06/07/08 gradle halves) — the audit repair is a
  design round, and gradle suites are the bug-checker's execution work; their pass
  conditions pin the named test classes read this ask.
- `RealmEndpoint.LOCAL` is a companion val: from Java it reads via
  `RealmEndpoint.Companion.getLOCAL()` — bare `RealmEndpoint.LOCAL` fails with
  "LOCAL has private access" [X]. `Win32AudioMute` is an `internal object`: callable at
  runtime via the public `INSTANCE`/getters (Kotlin internal is compile-time visibility).
- The desktop UI render half of DESK-46 (the ClientTweaksCard as drawn pixels) has no
  desktop compose-test lane (same shape as the DESK-135 exclusion, LV-flavor-import-EX-135);
  this matrix claims the card's persisted half (settings store + shared tests + clamp),
  which is the part with launch-boundary behavior.
- DESK-50's onto-a-live-spawn leg and every real-window spawn of this boundary belong to
  the DEDICATED-SLOT rows; they are never in the unattended pass set (plan §3.1).


---

## 5. Realm-status browser — build-then-test work item of the future workflow

> **OWNER DECISION (2026-10-02): build-then-test stays inside the bug-checker** — the owner
> directed the workflow to implement this plan in full, §5 included. The safeguards the
> final read demanded are therefore part of the spec: the §7.6 journal gains a
> `build`/`build-failed` status pair; the BUILD pass carries an independent fresh-eyes
> review gate (a reviewer that has not seen the build reads the code against this spec and
> must report zero blocking findings before the TEST pass); and §6.2's convergence
> predicate counts a build+test item as settled only when its build is reviewed, its
> deterministic test gate is green, and its §9 rows pass with evidence. The rest of this
> section is the spec that work item consumes.

Unlike §4's rows — which test behavior that already ships — the realm-status browser does
**not exist in the app yet**: its own spec says so under "Not run this round (app-side;
**owned by the future build workflow**)" (realm-status-browser.md §10). The design and its
CLI test suite are complete and fixture-verified; the implementation is missing. The
future workflow therefore owes this spec **two passes**, in order:

1. **BUILD pass.** Implement, in the desktop app (Android twin per spec §6.4):
   the visibility state machine + settings fields (spec §4, fail-closed OFF default),
   the two-tunnel interaction contract (§5 — cloudflared quick tunnel for the HTTP page
   only, never for 3724/8085), the minimal HTTP server + endpoint (§6), the
   `GET /api/status.json` payload contract (§7), and the public showcase HTML page (§8).
   The build is ordinary repo work: debug builds only, same constraint set (§1.2).
2. **TEST pass.** Execute spec §9's curl-driven CLI suite verbatim (its fixture-verified
   commands and expected outputs are in the file), then the ten app-side suite items §10
   enumerates as "specified, not run" — bot-lane agreement on the pinned DLL, roster
   cache/quiesce discipline, orphan-sweep + lost-record detection, rate-limit vs world
   tick timings, real MariaDB-lane page behavior, Android-twin failure emission, MOTD
   clamp vectors, `cloudflaredPath` discovery, connection posture, T23's true tear.

The work item joins the same round-robin as its own area key (`realm-status-browser`),
under the same resource bounds (§7) and bug-report format (§6). Until the BUILD pass
lands, its TEST rows are dispositioned `blocked-tracked` on the build item in the coverage
ledger — the same discipline as P-3 rows, not treated as failures.

The spec is embedded verbatim below (`out/live-verification/realm-status-browser.md`,
hash in the provenance table).

# Realm-status browser surface — design + CLI tests (round 4; app implementation is future work)

Date: 2026-10-02. Round 4: incorporates the browser-review round-3 findings
(boot-time visibility semantics, the degraded-lane liveness allowance in the
one-snapshot rule, FRIENDS-gated orphan detection with instance disambiguation,
connection-level limits, down-state player labeling, the page-only LAN-address
boundary, a well-formed refusal response, real torn-render mechanics in the
fixture, and the cloudflaredPath discovery test).
Round 3: single lifecycle rule, payload-axis split (advertisement vs DB provider),
non-vacuous MOTD tests, IPv6/CGNAT limiter keys, wedged-listener discriminator,
orphan-sweep pinning, degraded-status honesty, roster cache.
Round 2: fail-closed player split, child-ownership mechanism, PUBLIC payload trim
and limiter keying, start-frozen timeframe badge, bind/tunnel failure paths,
citation re-pinning. Canonical path: `out/live-verification/realm-status-browser.md`
(mirrored at `write/out/live-verification/realm-status-browser.md`). Nothing in the
production tree was edited.

**Citation scope note:** every relative path below resolves against the
`E:/pocket_realm_complete` tree (the C:/pocket_realm_complete working copy lacks some
android/ sources). Cites name WHICH twin they mean — `android/...` or `desktop/...` —
because the twins' line numbers differ (e.g. `botPopulationTarget` is
`android/.../storage/Settings.kt:363` and `desktop/.../storage/Settings.kt:258`).

The verification transcript in §9 was run **against a contract fixture**
(`tmp/realm-status-verify/status_page_fixture.py`) implementing the payload/page
contract specified here — NOT against the host app, which does not implement this
surface yet. §9's PASSes mean "the specified CLI test mechanics are sound and the
fixture honors the specified, asserted subset of the contract"; they are not evidence
the app ships this. App-side checks are listed in §10 as not run.

## 1. What this is

A read-only realm-status page served by the host app (desktop first), reachable by:

1. **OFF (default, fail-closed)** — no server exists. Nothing listens.
2. **FRIENDS** — reachable through the membership surface the game already uses: the
   host's LAN when LAN-hosting, the host's tunnel network (Tailscale / ZeroTier) when
   tunnel-hosting. The page never widens the game's reach.
3. **PUBLIC (opt-in showcase)** — the app additionally starts a **cloudflared quick
   tunnel for the PAGE only** (HTTP) and shows a shareable
   `https://<random>.trycloudflare.com` URL with a copy button.

**Lifetime — ONE rule, stated once (round 3, boot semantics added round 4):**
the page dies **only** when the visibility switch goes OFF, the app exits, the page
server hits an unrecoverable bind failure (§6.6), or the **supervisor process** dies.
**ANY realm stop leaves the page up** serving the graceful down state (§8.6) — a user
Save & exit, a D9 interface-loss stop, and crash recovery are the same
`supervisor.stop(StopMode.GRACEFUL)` call in the code
(`DesktopAppModel.kt:352-353` and `:805-806`), so they MUST have the same
page-lifecycle outcome; the design deliberately does NOT branch on
`RuntimeSnapshot.stopReason` (`RuntimeModel.kt:79`) for teardown — that field drives
the host UI's "why hosting ended" copy, never the page's lifetime. (§3.7, §5.6 and
T22 all assert this single rule.) Across app restarts the rule is §4's boot
semantics: a FRIENDS session restores (page server returns, no tunnel child), a
PUBLIC session re-arms but never auto-resumes (§4).

## 2. Grounding (what this design is anchored to)

Owner-approved convergence doc `docs/MULTIPLAYER-TRANSPORT-CONVERGENCE.md`:

- **§1.5 Privacy by design**: the home IP is never part of the realm's public identity;
  "Opt-in public visibility, fail-closed default OFF"; RELAY-ONLY exists as the privacy
  mode for the game path itself (this design never changes the game path).
- **§3 (2026 service disqualifications)**: "Cloudflare quick tunnels are HTTP-only,
  ephemeral, ToS-unsafe for game TCP" — therefore acceptable **only** for this
  disposable HTTP page.
- **§4 conflict table, "Quick tunnels" row**: "HTTP showcase page only — never the game
  TCP endpoint (the page is disposable by design; 3724/8085 never touch quick tunnels)".
- **§5 Tier 3 Public showcase**: "opt-in only … the realm-status web page via cloudflared
  quick tunnel (HTTP only, disposable by design) with privacy payload rules (bots
  labeled, character names hidden by default, no IPs/secrets). Later: game ingress
  through the relay pool only" — this design implements the "Now" clause only.
- §1.3/§1.4: stable identity is cryptographic, not an address — which is why the page
  payload can honestly carry **no address at all** in public mode.

Repo state read and re-verified this round (E:/pocket_realm_complete):

- Supervisor state: `DesktopAppModel.state: StateFlow<RuntimeSnapshot>`
  (`desktop/src/main/kotlin/com/pocketrealm/desktop/DesktopAppModel.kt:315`), `settings`
  (`:284`), `botAdmission` (`:877`), `lanPlayersOnline()` (`:1029-1042`).
  `RuntimeSnapshot` carries phase, `runtimeMode`, `advertisedAddress`, flavor,
  per-component `startedAtWallMs`, `clean`, `stopReason` — and **no** timeframe stage
  member (`android/app/src/main/java/com/pocketrealm/supervisor/RuntimeModel.kt:35-86`,
  shared source). The start-frozen advertisement precedent is `advertisedForInvite()`
  (`DesktopAppModel.kt:1163-1179`): the SNAPSHOT's frozen value, never a mid-session
  settings read. The timeframe badge uses the same pattern (§7).
- Stop paths: the D9 interface-loss monitor and the user's Save & exit both call
  `supervisor.stop(StopMode.GRACEFUL)` (`DesktopAppModel.kt:352-353`, `:805-806`);
  `stopReason` (`RuntimeModel.kt:79`) records WHY for UI copy. One operation, one
  page-lifecycle outcome (§1).
- Humans vs bots: `WorldPresenceSample(state, realPlayers, playerbotsEnabled,
  onlinePlayers)` (`ForegroundPromotionPolicy.kt:56-66`), produced by
  `DesktopRuntimeBackend.observeWorldPresence()` (`DesktopRuntimeBackend.kt:873-884`).
  **Fail-open hazard (round-2 fix):** that producer swallows a bot-status-lane failure
  (`runCatching { WorldNative.botStatusNative() }.getOrElse { LongArray(0) }`,
  `realPlayers = if (readable) ... else 0`) while `onlinePlayers` comes from a
  different native — a degraded lane would report every human as a bot (or, if the
  online lane also lost bots, absorb everyone into humans). The payload contract
  therefore carries lane health and publishes NULLS, not a 0-split (§3.3, §7).
  `realPlayers` index: `BOT_STATUS_REAL_PLAYERS = 9` (`DesktopBotAdmission.kt:436-442`).
  Configured population: the conf-resolved value — `PocketRealm.BotTarget` is the
  resolved profile's `initialTarget` after `persistedLaunchProfile` may have lowered it
  (`DesktopBotAdmission.kt:420-429`; `android/.../server/ServerRuntimeFiles.kt:556`,
  desktop twin `ServerRuntimeFiles.kt:232`) — NOT the raw settings field
  (`android/.../storage/Settings.kt:363`, desktop twin `Settings.kt:258`).
- Timeframe stage: the shared `TimeframeStages` registry — stage ids `vanilla-1.12.1`,
  `tbc-2.4.3`, `wotlk-3.3.5a`, per-stage titles, `isFinal`
  (`android/app/src/main/java/com/pocketrealm/client/TimeframeStage.kt:28-135`);
  `PocketRealm.TimeframeStage` is written to the world conf at START and applies
  "never mid-session" (`android/.../server/ServerRuntimeFiles.kt:561`, desktop twin
  `:237`; `TimeframeStage.kt:80-85`). The settings field
  (`android/.../storage/Settings.kt:425`, desktop twin `:365`) is the NEXT-start wish —
  so the badge MUST NOT read it (§7).
- Prepared data / world size: `DataPreparationHost.status()` →
  `WorldDataStatus(ready, detail)` (`desktop/.../desktop/DataPreparationHost.kt:31-46`);
  manifest `counts` validated by `PreparedDataStore.requireActiveEnvelope`
  (`android/.../server/PreparedDataStore.kt:47-52`). **Round-2 cost note:** that call
  re-runs the envelope gate (sha256 over a ~1.5 MB manifest, per-entry canonical-path
  checks) — the page caches the `world.*` block per generation (§6.4).
- Rates: the app writes no `Rate.*` keys (neither twin's conf body nor the staged
  `native/.o4-stage-x86_64/mangosd.conf`), so effective rates are the engine defaults
  `Rate.XP.Kill = 1.0`, `Rate.XP.Quest = 1.0`, `Rate.Honor = 1.0`
  (`native/cmangos/src/game/World/World.cpp:422-423,452`). The payload reports the
  effective values with an honest `source` label (§7).
- Realm name: `backend.projectedRealmName() ?: "my realm"` (`DesktopAppModel.kt:1211`).
  On the MariaDB provider lane those SQLite rows are STALE (the in-tree refusal copy at
  `DesktopAppModel.kt:1080-1092` proves SQLite writes there "silently no-op … or
  false-succeed against a stale pre-switch row") — the payload gates on the active
  provider marker (§7). The provider gate covers ONLY the name and the roster
  (`join.kind` and addressing key off the advertisement axis, §7).
- Roster read discipline: the sibling ban-lane read holds
  `backend.withDatadirQuiesced { ... }` so an open SQLite handle can never overlap an
  at-rest datadir swap (`DesktopAppModel.kt:1095-1103`) — the roster must do the same
  plus a cache (§7).
- No HTTP server exists in the host app today (`ProvisioningListener.kt:64-115` raw
  ServerSocket; `DesktopLlmModelManager.kt:298` port-picking probe only). The page
  server is new (§6).
- Ports 3724/8085 are fixed constants (`android/.../server/ServerRuntimeContract.kt:28-29`);
  "Ports are deliberately not user input in v1" (`RuntimeTopology.kt:11`).
  `PlayerLimit = 10` in the conf (`android/.../server/ServerRuntimeFiles.kt:534`,
  desktop twin `:210`).
- Advertised-address grammar (bounds §3.1): canonical IPv4 in the **loopback, private
  (RFC1918), link-local, or CGNAT (100.64/10)** ranges; hostnames and **public IPv4
  refused** (`RuntimeTopology.kt` — `parseJoinTarget`'s require, `AdvertisedAddress`
  class doc; `TunnelInvite.kt:31-47` classifies loopback as the cloudflared recipe).
- Friends join flow: `TunnelInvite` — invites are ADDRESS BLOCK ONLY, "a working
  username+password never travels inside a pasted invite"
  (`android/.../ui/TunnelInvite.kt:15-23,58-101`); the plain-LAN lane is
  `lanInviteText` (`:113-130`), selected when no tunnel advertisement is set
  (`DesktopAppModel.kt:1213-1215`).
- Guest moderation posture: bans are logon-time only; "A guest who is online at the
  moment of the ban stays in world" — kick deferred (`DesktopAppModel.kt:1105-1112`);
  the ban lane refuses on the MariaDB provider (`:1080-1092`).
- One-lifetime-per-app-process world (session boundary for the uptime record):
  `worldLifetimeSpent()` (`DesktopAppModel.kt:818-825`).
- Character-name roster source: `characters` table `name` varchar(12) / `online` tinyint
  (`native/cmangos/sql/base/characters.sql:640-659`) — with the known crash-stale
  `online` flag caveat (§7). Android twin poll: `RuntimeSupervisorClient.observeRealmState`
  spans `:28-51`; binder-death callbacks null the remote and the poll then silently
  emits nothing (`:34-44`); unknown phase throws at the `valueOf` (`:171`) and is
  swallowed by the poll's `runCatching` (`:42`) — the failure-emission rule is §6.5.
- Environment port map: `out/live-verification/environment.md:64` records llama-server's
  8080 `/health` as "the only HTTP status page in the stack" — true today, stale the day
  this design lands (§6.1).

## 3. Threat model and privacy invariants

Grounded in convergence §1.5/§3/§4/§5 (see §2). The page:

1. **Never carries the home IP or any local interface address beyond the FRIENDS game
   advertisement.** The only address-like string allowed anywhere in the payload is the
   FRIENDS-mode tunnel-lane `supervisor.advertisedAddress` — by construction inside the
   `AdvertisedAddress` grammar: **loopback, RFC1918 private, link-local, or CGNAT
   (100.64/10)**; hostnames and public IPv4 are refused by that grammar
   (`RuntimeTopology.kt`), so no host-routable address can appear. All of these resolve
   only inside the host's own network/tunnel. The FRIENDS plain-LAN lane carries
   **null** (the LAN address appears only in the lanInviteText-family HTML hint, which
   is LAN-reachable by construction). PUBLIC payloads carry **null** for it: the
   quick tunnel must never become a discovery oracle for the game TCP endpoint
   (3724/8085 never touch quick tunnels — convergence §4).
2. **Never carries secrets**: no passwords, no account names, no filesystem paths, no
   database info, no API keys, no pids.
3. **Never pads the player count with bots — and never guesses the split.** The
   humans/bots split rides two independent native lanes (`realPlayers` from the
   bot-status lane; `onlinePlayers` from the online-count lane) whose agreement on this
   pinned DLL is a BUILD-TIME verification item (§10). The payload is fail-closed about
   it:
   - `players.source` = `"native"` when the bot-status lane read clean AND the
     activeSessions cross-check passes; `"degraded"` when the lane is unreadable/stale;
     `"cross-check-failed"` when the cross-check fails.
   - When `source != "native"` (or `players.crossCheckOk` is false), the payload
     publishes `humansOnline: null, botsOnline: null, totalOnline: null,
     splitAvailable: false` — **never zeros**. A zero-split is the exact "all humans
     reported as bots" / "bots absorbed into humans" failure.
   - The page renders "player counts unavailable right now (status degraded)".
   - Cross-check invariant (promoted from build-time check to payload invariant +
     suite assertion): `totalOnline` must agree with the world telemetry's
     `activeSessions` lane (`android/.../server/ServerStatusJson.kt:19`, desktop twin
     `ServerStatusJson.kt:42`; consumed at `DesktopAppModel.kt:1034-1037`) within the
     bot-population delta. PUBLIC must not be **enabled** while the cross-check is
     failing (the transition is refused, §6.6; if it starts failing MID-SESSION with
     PUBLIC already on, the session stays and the counts go null — tested, T3e).
4. **Character names hidden by default in public mode.** Default payload contains no
   name field. The toggle (`showCharacterNames`, default OFF) is explicit consent; when
   ON, payloads may include `players.characterNames` (online characters only, names
   only — no account linkage). Lane rules: never on the MariaDB provider lane (stale
   SQLite rows); never in the down state (§8.6) — a crashed world leaves `online = 1`
   rows behind, so down-state rosters would list dead sessions. Banned-but-online
   characters, if the roster is on, appear with the standard roster treatment: the page
   never exposes ban state or reasons (privacy); the HOST-side card carries the
   enforcement posture (§8.7).
5. **Explicit consent with honest copy — the copy enumerates the host-relevant
   published set verbatim.** Because the PUBLIC payload is TRIMMED (§7), the before-flip
   consent copy lists exactly: realm **name**; your **description/MOTD**; **flavor**;
   **patch-timeframe stage id, title, and final-stage flag**; **minute-coarse render
   timestamp** (`generatedAtWallMs`); **minute-coarse uptime seconds and longest-session
   seconds**; **rates** (values + source label); **player counts** (humans /
   bots-labeled / total; plus character names ONLY if the separate toggle is ON);
   **world prepared-flag and counts summary**; **a coarse realm online/offline flag**
   (null when status is degraded); **the join hint** (read-only notice); and **the
   public link itself**. The REMAINDER of the payload is page posture, not
   host-revelation, and the copy says so in one line: health/status flags
   (`players.source`/`splitAvailable`/`crossCheckOk`, `supervisor.statusSource`),
   tunnel state (`public.tunnelState`/`readOnly`), the toggle mirror
   (`privacy.characterNamesInPublic`), and `schema`/`visibility`. The copy also names
   what is NOT published: no IPs, no game/join address, no supervisor phase/mode or
   topology, no generation prefix, no configured bot target, no wall-clock timestamps,
   no passwords, no character names unless toggled. It states the link is unguessable
   but NOT secret (shareable onward), that Cloudflare terminates TLS for the
   quick-tunnel URL, and the true exposure lifetime (§5.6).
6. **No auth beyond URL unguessability is claimed.** Read-only, GET-only (405
   otherwise), rate-limited with a PUBLIC-aware keying (§6.2), `Cache-Control:
   no-store`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
   `default-src 'none'` CSP. URLs are ephemeral and rotate — the UI copy says so.
7. **Fail-closed lifecycle — the ONE rule of §1.** OFF is the default and the teardown
   state; at boot, a persisted FRIENDS session restores while a persisted PUBLIC
   session re-arms without auto-resuming (§4) — so the page server never comes back
   unbidden with a tunnel child attached. The page dies when the visibility session
   ends (OFF, app exit, unrecoverable bind failure) or the supervisor process dies.
   ANY realm stop (user Save & exit, D9 interface-loss stop, crash recovery — all the
   same graceful-stop call) leaves the visibility session up, serving the graceful
   down state (§8.6); T22 asserts both stop flavors behave identically and only OFF
   tears the listener down. The tunnel child dies with the app process even on hard
   death (§5.2).

## 4. Visibility states and the state machine

```
                    ┌────────── explicit consent copy + switch ──────────┐
                    ▼                                                    │
  OFF ──(enable FRIENDS)──► FRIENDS ──(enable PUBLIC)──► PUBLIC-ON         │
   ▲                          │                            │             │
   └──────── (turn off) ──────┴─────────(turn off)──────────┴─────────────┘
```

- **OFF**: no page server, no tunnel child. Post-teardown state.
- **Boot semantics (round 4 — the persisted `realmStatusVisibility` meets the
  fail-closed default):** at app launch a persisted **FRIENDS** value RESTORES —
  the page server restarts (no tunnel child; no new consent needed, reachability is
  unchanged). A persisted **PUBLIC** value restores **ARMED-BUT-OFF**: the hosting
  card shows "your Public showcase was on — enable again" and the §3.5 consent flip
  is required before any tunnel child spawns; **the app never auto-starts
  cloudflared at launch** (auto-resurfacing a public tunnel without consent is
  exactly the surprise the fail-closed default exists to prevent). The boot state is
  therefore OFF unless FRIENDS restores.
- **FRIENDS**: page server binds per the game's own lane — wildcard `0.0.0.0:18080`
  (plain LAN hosting, or tunnel hosting behind the same `TunnelInterfacePolicy` proof
  the game start leg uses, `DesktopRuntimeBackend.kt:678-680`; the tunnel start is
  refused with the same honest copy when the tunnel adapter is not up). LOCAL/solo →
  FRIENDS not offered.
- **PUBLIC**: page server binds **loopback only** (`127.0.0.1:18080`) + the quick-tunnel
  child forwards to it. The public URL is parsed from cloudflared stdout
  (shape-gated, §5.5), rendered with a copy button.
- **Transitions** are serialized through the supervisor's owner model. **Bind failure**
  (port taken — second app instance, leftover, unrelated app): refuse the transition,
  leave visibility at its PRIOR state, surface copy naming the port and the conflict —
  no pid, no path (§3.2); never half-start the tunnel while the server is down (§6.6).
- **Rebind ordering (round 2):** stop old server → drain bounded → bind new → flip the
  served state. Every render snapshots the visibility state at request entry and is
  tombstone-checked before its first byte is written: a render straddling a flip to OFF
  is closed with NO response, so no post-OFF payload ever carries `public.*` or
  `advertisedAddress` (tested, §9 T19).
- **Render consistency (round 3, corrected round 4):** each render builds its payload
  from ONE `RuntimeSnapshot` value captured at request entry (`state.value` once).
  The enforced property — the one T23 asserts — bans exactly two mixes: **(a) an
  offline claim against live counts** and **(b) liveness against
  `statusSource == "degraded"`**. It explicitly ALLOWS `realmOnline: true` with a
  lane-degraded NULL split: the bot-status lane can die independently of the
  supervisor status while the world runs, and that payload is the REQUIRED §3.3
  behavior, not a defect. Honest mechanics note: presence comes from live native
  lanes (`observeWorldPresence` samples `WorldNative`, not the snapshot), so a stop
  landing between the state capture and the native sample can still pair pre-stop
  liveness with post-stop nulls — the strong "same instant for uptime AND presence"
  property is the app-side implementation duty; the fixture (single-STATE
  `build_payload`) cannot produce a mixed payload by construction, so its T23 PASS
  documents intent and covers the coherent pre-stop/post-stop branches, while the
  true tear is constructed by the app-side suite (§10). Transient staleness beyond
  that is bounded by the next poll and accepted.
- **Realm lifecycle is orthogonal (the §1 rule):** FRIENDS/PUBLIC hosting stays on
  across ANY realm stop/failed phase — the page then renders the graceful down state
  (§8.6).

## 5. The two tunnels (interaction contract)

| | Game TCP lane (existing) | Page HTTP lane (this design) |
|---|---|---|
| Traffic | WoW logon/world, TCP 3724/8085 | read-only status page, HTTP |
| FRIENDS carriage | Tailscale / ZeroTier network (or the documented cloudflared `access tcp` recipe) | same tunnel network, page port 18080 |
| PUBLIC carriage | **none** — friends-only or relay-only (later) | its **own** cloudflared quick-tunnel invocation: `cloudflared tunnel --url http://127.0.0.1:18080` |
| Quick tunnels | never (convergence §3/§4) | only here, HTTP-only, disposable |

Lifecycle rules (round-3 hardened):

1. Starting PUBLIC hosting spawns **one** child `cloudflared` process for the page,
   separate from any game-lane invocation.
2. **OS-level child ownership — mechanism, not aspiration.** Windows: the child is
   placed in a **Job Object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`** held by the app
   process, so a parent kill or power loss cannot strand it (a plain spawned process on
   Windows survives its parent's death). POSIX: child in its own **process group with
   `PR_SET_PDEATHSIG`** (or equivalent), killed by group on teardown.
3. **Orphan sweep (round-3 pinning — kill rules are strict, detection is layered):**
   - **Killing is OWN-RECORDS-ONLY, always.** The app keeps a durable record per child
     it spawned: `{pid, full command line, process start-time, spawn wall-clock}`. A
     leftover is killed only when **pid AND full command line AND process start-time
     all match** the record at kill time. Rationale the build workflow must not
     simplify away: after a reboot, a recorded pid can name an unrelated process
     (pid reuse) — start-time + command-line matching is what makes the kill safe. No
     bare process-name scan and no command-line-only scan of the process table ever
     kills anything.
   - **Detection for the lost-record case** (state directory wiped, reinstall, or the
     record lost in the very crash that orphaned the child): the sweep ALSO scans for
     processes whose **exact full command line** equals the page-tunnel invocation
     (`cloudflared tunnel --url http://127.0.0.1:18080`) and are older than this app
     instance. Disambiguation order (round 4): the durable record store is consulted
     FIRST — it is shared per install, so a process matching a LIVE record from any
     running instance is NOT an orphan and is skipped (a second app instance's sweep
     must never announce the first instance's healthy child as "not started by this
     app"); only record-less matches surface the notice, naming the owning instance
     when one is live. Those matches are **never auto-killed** (the same command line
     is what a user debugging this very page would legitimately run); they surface as
     a loud, actionable notice on the hosting card ("orphaned page tunnel detected —
     pid N, not started by this app; stop it or adopt it"), with adopt = take into
     the records under the same kill rules.
   - **The scan gates EVERY visibility transition that binds 18080 (round 4) —
     FRIENDS included, not just PUBLIC spawn.** The resurrect scenario runs through
     FRIENDS: PUBLIC → crash → restart (notice shown, host misses it) → host enables
     FRIENDS → wildcard bind → the un-adopted orphaned quick tunnel immediately
     serves the FRIENDS page (join hint + tunnel advertisedAddress) to the internet
     via the old public URL. While a lost-record match is live, the transition is
     refused until the host explicitly acknowledges or adopts it — the same
     refuse-with-honest-copy pattern §4 uses for bind failure and §3.3 for the
     cross-check gate. Sweeps also run on every app start and before every PUBLIC
     spawn. Verification folds into the §11.6 jpackage crash test.
4. **Child watch → payload expression.** A cloudflared exit flips
   `public.tunnelState` (`live` | `starting` | `down` | `binary-missing`); when not
   `live`, `public.url` is **null** — the page never shows a stale URL as if it worked.
   Retry is bounded: 3 re-spawn attempts with backoff, then terminal
   `tunnelState = "down"` with an honest retry affordance. An already-rendered visitor
   tab only learns of death on refresh (static page, no push) — accepted and stated.
5. **The URL is displayed** with a **Copy link** button (`data-copy` attribute,
   shape-gated through the same `^https://[a-z0-9-]+\.trycloudflare\.com$` rule as the
   payload — a parse deviation renders the failure block, never the attribute).
6. **Exposure lifetime copy (honest, aligned with the §1 rule):** "Anyone with this
   link can view this page **until you turn the Public showcase off or the app stops
   hosting it**. It changes each time." The link survives ANY realm stop — including a
   user Save & exit — by design; the §8.6 down page states this.
7. `cloudflared` discovery: PATH lookup + settings path field; failure copy names the
   exact command the app tried; **`cloudflaredPath` pointing at a missing/non-executable
   binary refuses the PUBLIC transition** (visibility stays at its prior state) OR lands
   in PUBLIC with `tunnelState = "binary-missing"`, `url = null`, and the failure block
   rendered — the state-machine consequence is pinned (§9 T15). The app does not
   silently download binaries in v1.

## 6. HTTP endpoint and the minimal server

### 6.1 Port story

**PAGE_PORT = 18080** — the tunnel-lane convention series (the friends' world
forwarder convention is 18085, design-tunnel §4.3). Fixed, not user input
(`RuntimeTopology.kt:11`). Loopback view `http://127.0.0.1:18080`; friends view
`http://<tunnel-advertised-address>:18080`; bind per §4 (loopback in PUBLIC, wildcard
in FRIENDS; scoped Windows Firewall note mirrors design-tunnel §4.4.5).
**Environment cross-reference:** the stack's port map
(`out/live-verification/environment.md:63-64`) currently lists only
3724/8085/8080/3725 and calls 8080's `/health` "the only HTTP status page in the
stack" — on adoption this design **adds a 18080 row** (page server; loopback in
PUBLIC / wildcard in FRIENDS; never adb-reversed in the desktop-first round) and that
"only" clause goes stale. 18080 was verified free and unclaimed in the repo
(`netstat -ano | grep 18080` → empty; repo grep hits only a llama.cpp byte-string and
design-tunnel's 18085). The fixture-only control channel for tests (18081,
loopback-bound) is not an app surface and stays out of the port map.

### 6.2 Implementation (new, minimal)

JDK-builtin `com.sun.net.httpserver.HttpServer` (module `jdk.httpserver`, zero new
dependencies) in the desktop app process:

- `GET /` → server-rendered HTML (escaped, §8); `GET /api/status.json` → §7 payload.
  Everything else 404; non-GET 405. No cookies, no JS requirement.
- Rendered from the supervisor `StateFlow`s at request time (Android twin polls at
  250 ms — `RuntimeSupervisorClient.kt:28-51`; the desktop StateFlows are the
  equivalent source). Renders are serialized against visibility transitions (§4) and
  one-snapshot-consistent (§4).
- **Rate limiting (round-2 keying, round-3 address families):** the limiter MUST NOT
  key on the socket peer in PUBLIC mode — every tunnel visitor arrives with peer
  `127.0.0.1`, so a per-IP key is one shared bucket a single visitor can exhaust for
  everyone. In PUBLIC mode:
  - validated **`CF-Connecting-IP`** header → per-visitor bucket. Valid =
    IPv4 global-unicast **plus CGNAT 100.64/10** (carrier NAT mobile ISPs legitimately
    present CGNAT client IPs at the Cloudflare edge; rejecting them would merge every
    mobile visitor into one global bucket — a direct loopback caller could forge a
    global-unicast IP anyway, so CGNAT adds no new exposure), **plus IPv6
    global-unicast `2000::/3`** (Cloudflare sets an IPv6 header for v6 visitors;
    rejecting v6 would collapse all v6 visitors into the shared bucket). Rejected
    (forged-from-the-edge-impossible): loopback, RFC1918, link-local v4/v6, multicast,
    unspecified — these route to the global bucket.
  - absent/rejected header from a non-host peer → a **global bucket** (this is the
    documented exposure: direct loopback callers share it, and a local process can
    forge the header — acceptable because the server binds loopback in PUBLIC).
  - the host's own loopback view (no header, peer 127.0.0.1) draws from a **reserved
    host budget** a visitor flood can never exhaust.
  - The bucket map is **bounded** (max entries with expired-then-oldest eviction) so
    forged-header floods cannot grow memory without limit.
  Defaults: 60/min per visitor, 300/min global, 120/min host. 429 + `Retry-After`.
  FRIENDS mode keys per-IP normally (peers are real tunnel/LAN addresses). The limit
  is exposed as a settings/test hook so the app-side suite can trip it with a small
  value (§9 T11) instead of 61 requests.
- **`world.*` cache (round 2):** the prepared-data block is computed ONCE per
  generation (keyed by generation id + manifestSha256, invalidated on
  DataPreparationHost events), because `requireActiveEnvelope` re-runs a ~1.5 MB
  manifest parse + per-entry path checks (`PreparedDataStore.kt:42-64`) — per-GET
  revalidation would put 100 ms+ of disk/CPU into the game process behind an
  unauthenticated URL. An abuse test pins that sustained page views at the rate limit
  do not move world tick timings (§10, app-side).
- **`characterNames` roster (round 3):** served from the same cache discipline
  (re-read at most once per supervisor poll, never per render) and taken under the
  same `withDatadirQuiesced` discipline the ban lane uses
  (`DesktopAppModel.kt:1095-1103`) so roster reads can never extend a backup/datadir
  swap window (§10).
- **Connection posture (round 4):** request rate limits do not help against a peer
  that never finishes a request. The server pins: a **bounded request executor**
  (max concurrent exchanges + bounded queue — never an unbounded thread-per-connection
  pool), **per-connection read/idle timeouts** (a peer that stalls mid-request is
  closed, defeating slowloris-style thread pinning — the risk is real in FRIENDS
  mode, a direct unauthenticated wildcard-bound listener; PUBLIC mode is fronted by
  Cloudflare's edge timeouts but keeps the same posture), and a **request-header size
  cap** (reject oversized headers with 431/400). App-side suite pins the defaults and
  the abuse test: N stalled connections + M valid requests → the valid requests are
  still served (§10).
- Security headers on every response; all text HTML-escaped; the public-URL
  interpolation is shape-gated (§5.5); the MOTD is clamped at the settings write and
  escaped at render (§6.3).

### 6.3 New settings (all host-owned, stored in `Settings`)

| Field | Default | Validation point |
|---|---|---|
| `realmStatusVisibility` | `OFF` | the three-state switch; fail-closed |
| `realmDescription` | `""` | **clamped at the settings WRITE path** (like `AdvertisedAddress.parse` gates the tunnel address at launchSpec, `DesktopAppModel.kt:790-792`): ≤280 chars, control characters stripped — the payload and render additionally escape whatever they receive (defense in depth; tested with a hostile vector, §9 T13d; the app-side suite must pin the write-path clamp with the same vectors) |
| `showCharacterNames` | `false` | explicit toggle gating `players.characterNames` (§3.4) |
| `cloudflaredPath` | `""` | optional explicit binary path; missing/non-executable → §5.7 behavior |
| `pageRateLimitPerMinute` (test hook) | 60 | lets the app-side suite trip the limiter (§9 T11) |

### 6.4 Degraded supervisor status

`supervisor.statusSource` = `"live"` | `"degraded"`. Degraded = the upstream status is
unreadable, stale past 2 poll intervals, or undecodable (e.g. an unknown phase enum
from a newer supervisor). **Degraded does NOT mean offline** — an unknown phase enum
from a newer supervisor occurs precisely while the world may still be RUNNING, so
publishing `realmOnline: false` would be a false offline claim on the public surface.
Degraded payloads: `realmOnline: null`, `uptime.seconds: 0`, `players` fail-closed per
§3.3 — never a stale snapshot, never a false liveness claim of either polarity. The
page renders "status unavailable", not an online/offline statement. On the Android twin
this requires REAL failure emission: binder death (the poll silently emits nothing
today, `RuntimeSupervisorClient.kt:34-44`) and repeated decode failure (unknown-phase
throw swallowed at `:42` → `valueOf` at `:171`) must emit an explicit
supervisor-unreachable state within one poll interval, which tears the tunnel child
down (§5.4) — not silence.

### 6.5 Stale-binding and torn-render rules

- **Visibility flip (OFF):** snapshot-at-entry + tombstone — after OFF, no response in
  flight may carry `public.*` or `supervisor.advertisedAddress` (§4; T19).
- **Realm stop mid-render:** the one-snapshot rule (§4) bounds the payload to pre-stop
  or post-stop, never mixed (T23).

### 6.6 Failure paths (all specified; fixture-tested where a fixture can reach)

| Failure | Behavior | Test |
|---|---|---|
| Port 18080 already bound | refuse transition; visibility stays prior; copy names the port, no pid/path (§4) | T17 |
| cloudflared binary missing | refuse OR land PUBLIC with `tunnelState = "binary-missing"`, `url = null`, failure block (§5.7) | T15 |
| Tunnel child dies | `tunnelState = "down"`, `url = null`, bounded retry then terminal (§5.4) | T15c |
| Supervisor status degraded | `statusSource = "degraded"`, `realmOnline = null`, fail-closed counts, "status unavailable" (§6.4) | T16 |
| Bot-status lane unreadable | NULL split, never 0-split | T3b |
| activeSessions cross-check failing | NULL split; PUBLIC enable refused (409-analog); mid-session failure → session stays, counts null | T3c/T3d/T3e |
| Render straddling OFF | connection closed with no response | T19 |
| Realm stop mid-render | one-snapshot rule: pre-stop or post-stop payload, never mixed | T23 |

## 7. Payload contract — `GET /api/status.json`

```json
{
  "schema": 3,
  "visibility": "public",
  "generatedAtWallMs": 1791997200000,
  "realm": {
    "name": "Host's Realm",
    "flavor": "vanilla",
    "timeframeStageId": "vanilla-1.12.1",
    "timeframeTitle": "Patch 1.12.1 — current release content",
    "timeframeIsFinal": true,
    "description": "Chill vanilla levelling realm. Be kind; the bots are too."
  },
  "uptime": { "seconds": 3600, "startedAtWallMs": null, "recordSeconds": 7320 },
  "rates": { "xpKill": 1.0, "xpQuest": 1.0, "xpHonor": 1.0, "source": "engine-default" },
  "players": { "humansOnline": null, "botsOnline": null, "totalOnline": null,
               "source": "degraded", "splitAvailable": false, "crossCheckOk": true,
               "botsLabeled": true, "botTarget": null },
  "world": { "prepared": true, "generation": null,
             "counts": { "dbc": 212, "maps": 173, "vmapTrees": 42,
                         "mmapMaps": 173, "mmapTiles": 4198 } },
  "supervisor": { "phase": null, "mode": null, "clean": null,
                  "realmOnline": true, "statusSource": "live",
                  "advertisedAddress": null },
  "join": { "kind": "none",
            "hint": "This page is read-only. Joining is by friend invitation from the host only." },
  "public": { "url": "https://warm-underscore-example.trycloudflare.com",
              "tunnelState": "live", "readOnly": true },
  "privacy": { "characterNamesInPublic": false }
}
```

(This example is the PUBLIC shape with a RUNNING world — note the nulls, and note
the pairing rule: `realmOnline: true` requires `statusSource: "live"`; realmOnline
is null ONLY when the supervisor status is degraded (§6.4). `players.source:
"degraded"` alongside it is the INDEPENDENT bot-status-lane axis (§3.3) — the two
flags degrade separately and the combination shown is legal and required. FRIENDS
fills phase/mode/clean, `startedAtWallMs`, exact timestamps, `world.generation`,
`botTarget`, and the join block; with a healthy lane, `players` carries the real
split.)

Field-by-field (source / rules; **Snapshot** names which store each field reads):

| Field | Source / rule |
|---|---|
| `schema` | `3` (round-3 contract) |
| `visibility` | `"friends"` \| `"public"` (served page is never `"off"` — off = no server) |
| `generatedAtWallMs` | server wall clock at render; **minute-coarse in PUBLIC** |
| `realm.name` | `projectedRealmName() ?: "my realm"` (`DesktopAppModel.kt:1211`, **RuntimeSnapshot**+backend); MariaDB provider lane → the generic `"my realm"` fallback (stale-SQLite guard, `DesktopAppModel.kt:1080-1092` posture) |
| `realm.flavor` | `RuntimeSnapshot.flavor` (`RuntimeModel.kt:68`) |
| `realm.timeframeStageId` | **START-FROZEN**: the `PocketRealm.TimeframeStage` value of the ACTIVE run's staged `mangosd.conf` — the same file §7 rates parse — or the resolved stage carried in `RuntimeLaunchSpec`/`RuntimeSnapshot` at start. NEVER `Settings.Snapshot.timeframeStage` (that is the NEXT-start wish; the conf applies "at the world's next start — never mid-session", `ServerRuntimeFiles.kt:561`). This mirrors `advertisedForInvite()`'s frozen-advertisement rule (`DesktopAppModel.kt:1163-1179`). Test: edit the timeframe control mid-session → the badge stays on the running stage (§9 T5). |
| `realm.timeframeTitle` / `timeframeIsFinal` | from the same stage via the `TimeframeStages` registry (`TimeframeStage.kt:36,71,121`) |
| `realm.description` | host-set MOTD (`Settings.Snapshot`, §6.3); clamped at the write, escaped at render |
| `uptime.seconds` | `now − components[WORLD].startedAtWallMs` (`RuntimeModel.kt:28-33`, **RuntimeSnapshot**) when running, else 0 — monotonic across polls; minute-coarse in PUBLIC |
| `uptime.startedAtWallMs` | the world component's start wall-clock; **null in PUBLIC** (fingerprinting) |
| `uptime.recordSeconds` | longest session so far, host-persisted. Producer (round 2 — none exists in the tree today): a small record store updated on BOTH graceful and unclean stop, duration computed from the pinned `startedAtWallMs` (crash recovery reads the supervisor journal's last snapshot); "session" = one world lifetime in one app process (DESK-25 boundary, `DesktopAppModel.kt:818-825`). Unclean stops MUST count, or crash-prone hosts keep the record at 0 forever. |
| `rates.*` | parsed from the ACTIVE run's `mangosd.conf` when running (`Rate.XP.Kill/Quest`, `Rate.Honor`); the app writes none today → engine defaults 1.0/1.0/1.0 (`World.cpp:422-423,452`), `source: "engine-default"`; `"not-running"` + nulls when down |
| `players.humansOnline` | `WorldPresenceSample.realPlayers` (`ForegroundPromotionPolicy.kt:56-66`) — **null unless `source == "native"`** (§3.3) |
| `players.botsOnline` | `(onlinePlayers − realPlayers).coerceAtLeast(0)`; same nullability |
| `players.totalOnline` | `humans + bots`; same nullability; cross-checked against the world telemetry `activeSessions` lane (`android/.../server/ServerStatusJson.kt:19`) |
| `players.source` | `"native"` \| `"degraded"` \| `"cross-check-failed"` \| `"world-down"`. **Down state (round 4):** the realm-down zeros are STRUCTURAL — no world means no players by construction, and no bot-status lane is read (the producer short-circuits to EMPTY) — so the down state labels `source: "world-down"`, never `"native"` (which claims a lane-verified split). |
| `players.splitAvailable` / `crossCheckOk` | the fail-closed flags (§3.3); `crossCheckOk: false` blocks PUBLIC enable |
| `players.botsLabeled` | always `true` — the page must label bots (convergence §5 Tier 3) |
| `players.botTarget` | the conf-resolved launch target (the value `PocketRealm.BotTarget` carries, i.e. the profile's `initialTarget` AFTER `persistedLaunchProfile`'s admission lowering — `DesktopBotAdmission.kt:420-429`, `ServerRuntimeFiles.kt:556`), NOT the raw `Settings.Snapshot.botPopulationTarget` (which can be higher). **null in PUBLIC.** |
| `players.characterNames` | ABSENT unless the consent toggle is ON AND the SQLite provider lane is active AND the world is RUNNING (never in the down state — crash-stale `online` flags, §3.4). Source: online rows of the pinned `characters` DB (`characters.sql:640-659`), cached + quiesced per §6.2. Banned-but-online characters appear with the standard treatment; ban state never appears. |
| `world.prepared` / `counts` | `DataPreparationHost.status()` + manifest counts (`DataPreparationHost.kt:31-46`, `PreparedDataStore.kt:47-52`), **cached per generation** (§6.2). No paths. Unchanged in the down state (prepared data exists while the realm is down). |
| `world.generation` | 8-char prefix; **null in PUBLIC** |
| `supervisor.phase` / `mode` / `clean` | **RuntimeSnapshot** fields; **null in PUBLIC** (topology disclosure with zero showcase value); FRIENDS renders them |
| `supervisor.realmOnline` | coarse boolean — **null when `statusSource != "live"`** (an unreadable upstream is not evidence of offline, §6.4); the PUBLIC-visible liveness signal; the §8.6 down copy rides it |
| `supervisor.statusSource` | `"live"` \| `"degraded"` (§6.4) |
| `supervisor.advertisedAddress` | TWO cases, and only two: **(a) FRIENDS tunnel lane** — the frozen `RuntimeSnapshot.advertisedAddress` (grammar-bounded per §3.1); **(b) FRIENDS plain-LAN lane — on either DB provider — null** (the LAN address appears only in the lanInviteText-family HTML hint). PUBLIC: null, always. Never a public address in either case. The DB provider is irrelevant here. |
| `join.kind` | keyed on the ADVERTISEMENT axis only — `"tunnel-invite"` when the session carries a tunnel advertisement, `"lan-invite"` when it does not (plain-LAN; the `lanInviteText` family, `TunnelInvite.kt:113-130`), `"none"` in PUBLIC. The MariaDB PROVIDER axis never touches `join.kind` — it gates only `realm.name` and `characterNames`. |
| `join.hint` | friends: pointer to the invite flow, address-block-only (`TunnelInvite.kt:15-23`) — **address-free in the payload**; public: the read-only notice. The FRIENDS plain-LAN page renders the LAN address as a PAGE-ONLY line in its join section (`set realmlist <lan-address>`), sourced outside the payload — that page is LAN-reachable by construction, and this is the ONE surface where the LAN address may appear (payload: never, PUBLIC: never — T18/T18c). |
| `public.url` | trycloudflare-shaped URL when `tunnelState == "live"`, else **null** — never a stale URL |
| `public.tunnelState` | `"live"` \| `"starting"` \| `"down"` \| `"binary-missing"` (§5.4) |
| `public.readOnly` | always `true` in PUBLIC |
| `privacy.characterNamesInPublic` | mirrors the toggle |

**Absent by design, everywhere:** host/user/account names, character names (unless
toggled), passwords, tokens, paths, local IPs, the home IP, pids, database info, log
tails, LLM endpoint/keys; **null in PUBLIC:** phase/mode/clean, generation prefix,
botTarget, wall-clock timestamps; **null when degraded:** `realmOnline`.

## 8. The HTML page (public showcase surface)

One static, server-rendered page (§6.2), mobile-tolerable, no JS required:

1. **Header**: realm name + flavor badge + **patch-timeframe badge** (start-frozen
   stage title, §7; non-final stages get a "(timeframe)" suffix).
2. **Description/MOTD**: host-set text; edited in the app's hosting card (writes
   `realmDescription`, clamped at the settings write, §6.3); escaped at render.
3. **Status block**: supervisor phase (FRIENDS) or coarse online/offline (PUBLIC;
   "status unavailable" when degraded — never an online/offline guess), uptime
   ("online 1h 1m · longest session 2h 2m"), rates row with source label, world-data
   row (prepared + generation prefix in FRIENDS + counts).
4. **Players block**: "3 humans online · 47 bots online · 50 in world **(47 of them are
   bots)**" — the parenthesized bots clause is MANDATORY copy (convergence §5 Tier 3);
   degraded lanes render "player counts unavailable right now (status degraded)".
5. **Join section**: FRIENDS tunnel → the invite-flow pointer (address-block-only
   family); FRIENDS plain-LAN → the `lanInviteText` family (this hint may name the LAN
   address — it renders only where the LAN can already reach the page); PUBLIC → "This
   page is read-only. Joining is by friend invitation from the host only." — no
   address, no join path.
6. **Graceful down state**: visibility on + realm stopped/failed (ANY stop flavor,
   §1) → HTTP 200, "The realm is offline right now.", **uptime 0, players 0 labeled
   `world-down` (structural zeros, §7); the world-data block is unchanged (prepared
   data exists while the realm is down)**. The page states that the public link
   REMAINS LIVE by design while the visibility session is on — through realm stops,
   and across app restarts for FRIENDS (which restores); PUBLIC re-arms rather than
   auto-resuming, so a restart always requires the host's consent flip before a fresh
   link exists (§4).
7. **Public link block** (host's loopback view): shape-gated URL + copy button +
   posture copy (§5.6) + the toggle state + the live **moderation posture**: kick
   unavailable (bans are logon-time only, online guests stay), and the ban lane's
   availability (refused on the MariaDB provider) — honest about what "invitation
   only" enforces right now. Failure blocks for `tunnelState != "live"` and the
   cloudflared-missing case (§5.7).
8. **Adopted showcase items**: MOTD editing (§6.3) · uptime record (`recordSeconds`,
   §7) · patch-timeframe badge (§8.1) · join-as-friend section (§8.5) · view rate
   limit (§6.2) · graceful down page (§8.6). Deferred: Nostr-style ephemeral public
   listing (convergence §3/§5 "Later"), visitor hit counts, theming.

## 9. CLI test suite (curl assertions) — specified commands + fixture-verified run

Base: `BASE=http://127.0.0.1:18080`, control `CTRL=http://127.0.0.1:18081` (fixture
state machine; fixture-only, loopback-bound, never an app surface). **Every assertion
curl carries `--max-time 5`** — a half-dead server can never hang an unattended run
(T1 uses `--max-time 3`). No jq on this host (verified) — JSON assertions use python.
Suite: `tmp/realm-status-verify/run_tests.sh`; fixture:
`tmp/realm-status-verify/status_page_fixture.py` (synthetic values implementing §7;
fixture-level PASS ⇒ "the asserted subset of the contract holds", not more).

**T1/T12/T14/T17 pass condition (round 2, tightened round 3):** "curl fails (rc ≠ 0)
with NO HTTP response received within the bounded window" **AND the positive
no-listener check — `netstat -ano` shows no LISTENING socket on :18080 (any bind
address)** — because on this Windows/loopback host the identical no-listener state
nondeterministically yields curl exit 7 (refused) or 28 (timed-out), and curl alone
cannot distinguish "nothing listens" from a wedged listener swallowing connects (full
accept backlog). The exact exit code is logged, never branched on; instant refusal is
NOT implied (a refused loopback connect consumes the whole window here). T14's doc
bound and its loop agree: 4 s (10 × 0.4 s).

| # | Command (shape) | Expect |
|---|---|---|
| T1 | `curl -sS --max-time 3 -o /dev/null -w '%{http_code}' $BASE/api/status.json` (OFF) | rc ≠ 0, no 200, no listener on :18080 |
| T2 | `curl -fsS --max-time 5 $BASE/api/status.json` + python field presence | all §7 fields |
| T3 | python over `players` (native lane): arithmetic, caps (`humans ≤ 10` = `PlayerLimit`, `bots ≤ botTarget` == conf value), `botsLabeled`/`crossCheckOk` | hold |
| T3b | degraded-lane variant | `humans/bots/total == null`, `source == "degraded"` — never a 0-split |
| T3c | `--cross-check-fail` variant | `source == "cross-check-failed"`, null split |
| T3d | control `POST /enable-public` with the cross-check failing | refused with ONE well-formed 409 whose body names `CROSS_CHECK_FAILING`; visibility stays |
| T3e | PUBLIC on, control `POST /cross-check-break` mid-session | session stays, counts go null |
| T4 | two polls ≥2 s apart | `uptime.seconds` non-decreasing; `startedAtWallMs` identical |
| T5 | `--conf-stage vanilla-1.10 --settings-stage vanilla-1.9` | badge == conf stage (start-frozen), flavor/`isFinal` consistent |
| T6 | `curl -sS -D - -o /dev/null --max-time 5` | no-store / nosniff / CSP / no-referrer |
| T7a | FRIENDS tunnel `advertisedAddress` regex: loopback \| 10/8 \| 192.168/16 \| 172.16-31 \| 169.254/16 \| CGNAT 100.64-127 | inside grammar; any public IPv4 fails |
| T7b | PUBLIC body scan | phase/mode/clean/generation/botTarget/startedAt null; timestamps minute-coarse; every IPv4 in the non-public ranges; no Windows-path pattern; `join.kind == "none"` |
| T8 | PUBLIC payload + `curl $BASE/` | `public.url` matches `^https://[a-z0-9-]+\.trycloudflare\.com$`, `tunnelState=="live"`, `readOnly`; page has shape-gated `data-copy` button |
| T9/T9b/T9c | names default-hidden; toggle-only; MariaDB provider lane suppresses roster + generic name (tunnel advertisement unaffected) | hold |
| T10 | PUBLIC + realm stopped | HTTP 200, `realmOnline false`, uptime 0, players 0 labeled `source: "world-down"` (structural, §7), `world.prepared` unchanged |
| T11 | PUBLIC limiter, limit 3: visitor A (CF-Connecting-IP 203.0.113.7) 4 requests; visitor B (198.51.100.9); host loopback throughout | A 429s, B unaffected, host budget never 429s |
| T11b | malformed header (`not-an-ip`), global-limit 4 | 429s from the global bucket; host budget intact |
| T11c | forged PRIVATE header (`10.0.0.1`), global-limit 3 | global bucket (never a private per-visitor bucket); host intact |
| T11d | CGNAT header (`100.64.1.2`) and IPv6 header (`2606:4700:4700::1111`), per-visitor limit 2, global-limit 1 | each gets its OWN per-visitor bucket (2 OK then 429 — not the 1-req global bucket) |
| T12 | after stop | unreachable (any rc ≠ 0) AND no listener on :18080 |
| T13 | `POST` → 405; unknown path → 404; hostile `--public-url 'https://evil.example.com/x" onmouseover="alert(1)'` | no unescaped sink; copy attribute absent/failure block |
| T13d | `--hostile-description` (script tag + >280 chars + control chars, through the modeled settings write) | payload: clamped ≤280, control-char-free; HTML: escaped (`&lt;script&gt;`), never a raw sink |
| T14 | `POST $CTRL/off` mid-run, then poll 10 × 0.4 s | listener stops within the 4 s bound AND no listener remains |
| T15 | `--tunnel-state binary-missing` | page 200, `tunnelState=="binary-missing"`, `url` null, failure copy; T15c: `POST $CTRL/tunnel-down` → `down`, `url` null, visibility unchanged |
| T16 | `--status-source degraded` | `statusSource=="degraded"`, `realmOnline == null` (no false offline), uptime 0; page "status unavailable" |
| T17 | pre-bind 18080 (occupier), run `--bind-or-fail` | exit 3 + honest port copy; after occupier exits nothing listens (curl + netstat) |
| T18 | FRIENDS `--lan-lane` (plain-LAN, SQLite provider) + `--lan-address` | `join.kind=="lan-invite"`, `advertisedAddress == null`, the LAN address NEVER in the payload, rendered ONLY in the page's join section, real name kept |
| T18b | FRIENDS `--mariadb-lane` WITH tunnel advertisement | `join.kind=="tunnel-invite"` + tunnel address (provider orthogonal), name fallback still applies |
| T18c | PUBLIC with `--lan-address` set | the LAN address appears NOWHERE — not the page HTML, not the payload (the leak boundary, §7) |
| T19 | slow render (`--slow-ms 1500`) + OFF flip mid-flight | no post-OFF response, or one without `public.*`/`advertisedAddress` |
| T20 | FRIENDS `--bind 0.0.0.0`, curl the host's own non-loopback IPv4 | 200 (single-host wildcard-bind check; a scoped firewall rule may be required, §6.1). **First-run note:** on a virgin host the wildcard bind may surface a one-time Windows Firewall consent prompt for python.exe — the only eyes-on dependency in the suite; pre-granting python.exe on the private profile or the scoped 18080 rule (open item 2) removes it. Cross-HOST reachability stays a manual/second-peer item — out of the unattended suite's scope by the single-host boundary. |
| T21 | `--crash-session` | `recordSeconds ≥` the crash-terminated session's duration |
| T22 | PUBLIC on → control `POST /realm-stop` (user Save&exit analog), then `POST /d9-stop` (D9 analog), then `POST /off` | both stop flavors: page stays 200, `realmOnline false`, `public.url` still live; only OFF tears the listener down — with the same positive no-LISTENING-socket netstat check T14 uses (the §1 single rule) |
| T23 | slow render (`--slow-ms`) + `POST /realm-stop` mid-flight | the fixture captures the payload snapshot at request entry, THEN models the slow render, so the coherent PRE-stop delivery branch is genuinely reachable; the delivered payload is one-snapshot-consistent — pre-stop OR post-stop, never the two banned mixes (§4) |

**App-side PUBLIC variants (specified now so the future suite cannot silently diverge):**

- **(a) no-cloudflared default (offline CI):** page serves HTTP 200 on loopback,
  `public.url == null`, `tunnelState == "binary-missing"`, failure copy present,
  copy-button block in failure state. T8 as written only holds when cloudflared is
  present AND egress works (verified absent on this host: `which cloudflared` → not
  found).
- **(b) live-tunnel (optional, online-only, NOT in the default unattended suite):**
  poll `/api/status.json` with `--max-time 5` per request until `public.url` matches
  the regex, hard deadline 60 s; only then run T8 and the parent-hard-kill test —
  kill the app process and assert the trycloudflare URL stops resolving AND no
  cloudflared process survives (the Job-Object/pdeathsig + orphan-sweep checks, §5.2-5.3).

**Verification run (2026-10-02, round 4, this session) — against the contract fixture:**

```
$ cd /c/pocket_realm_complete/tmp/realm-status-verify && bash run_tests.sh
PASS  T1 OFF: unreachable with no HTTP response (curl exit 7) AND no listener on :18080
PASS  T2 payload fields all present
PASS  T3 bot/human counts vs configured population (native lane)
PASS  T3b degraded bot-status lane -> NULL counts, never humans-as-bots
PASS  T3c cross-check failure -> labeled null split
PASS  T3d PUBLIC enable refused: well-formed 409 + reason in body, visibility stays friends
PASS  T3e mid-session cross-check failure: session stays, counts go null
PASS  T4 uptime monotonic across polls (3600s → 3602s, startedAtWallMs stable)
PASS  T5 flavor/timeframe consistent; badge is the start-frozen conf stage
PASS  T6a no-store
PASS  T6b nosniff
PASS  T6c CSP present
PASS  T6d no-referrer
PASS  T7a friends-tunnel: advertised address within loopback/RFC1918/link-local/CGNAT grammar
PASS  T7b public: trimmed topology/timestamps; no address/IP/path leak
PASS  T8 public: shareable URL (shape-gated) in payload, tunnelState live
PASS  T8b page renders copy button
PASS  T9 names hidden by default
PASS  T9b names only with explicit toggle
PASS  T9c MariaDB lane: roster suppressed, generic name, no address
PASS  T10 graceful down state (HTTP 200, uptime 0, players 0 as world-down, world block intact)
PASS  T11 public limiter: visitor A 429s [200 200 200 429 ], visitor B unaffected [200 200 200 ], host loopback still 200
PASS  T11b unvalidated header -> global bucket (429s in [200 200 200 200 429 429 ]); host budget still 200
PASS  T11c forged private header -> global bucket (429s in [200 200 200 429 ]); host budget still 200
PASS  T11d CGNAT [200 200 429 ] and IPv6 [200 200 429 ] visitors: own per-visitor buckets (2 OK then 429, not the 1-req global bucket)
PASS  T12 teardown: unreachable (curl exit 28) AND no listener on :18080
PASS  T13a non-GET → 405
PASS  T13b unknown path → 404
PASS  T13c copy-button URL is shape-gated; hostile URL never reaches the attribute
PASS  T13d hostile MOTD: clamped ≤280 + control chars stripped at the write, escaped at render
PASS  T14 visibility OFF mid-run: listener stops within the 4 s bound AND no listener remains
PASS  T15 no-cloudflared: page serves, tunnelState binary-missing, url null
PASS  T15b failure copy rendered
PASS  T15c tunnel death: tunnelState down, url null, visibility unchanged
PASS  T16 degraded status → realmOnline null (no false offline), uptime 0
PASS  T16b page renders 'status unavailable'
PASS  T17a bind failure refused with honest port copy (exit 3)
PASS  T17b after the occupier exits, nothing listens (curl exit 28, no LISTENING socket)
PASS  T18 plain-LAN FRIENDS (SQLite) → lan-invite, payload address-free, LAN address only in the HTML hint
PASS  T18b MariaDB+tunnel → tunnel-invite with tunnel address (axes orthogonal)
PASS  T18c LAN address absent from the PUBLIC page and payload
PASS  T19 in-flight render during OFF flip: no response or no public fields (code '000')
PASS  T20 FRIENDS wildcard bind reachable at http://192.168.1.217:18080 (firewall consent possible on first run, §9)
PASS  T21 crash-terminated session updates the uptime record
PASS  T22 lifecycle: realm stops (user + D9) leave the page up; OFF tears it down (no listener remains)
PASS  T23 torn-render consistency: delivered snapshot coherent (pre- or post-stop, never mixed)
----------------------------------------
TOTAL: 46 passed, 0 failed, 0 skipped
```

Post-run: `netstat -ano` shows no LISTENING socket on 18080/18081. Round-3 audit
record (reviewer-verified, kept for the log): the full suite ran headless and
unattended at the stated scale (45 tests then, 0 failed, real ~41 s, byte-matching
the transcript of that round including the T1-exit-7 / T12-exit-28
nondeterminism, never branched on); all curls bounded; T11c forged-private, any-bind
sweep, T20 firewall note, T14 loop/doc agreement, T11d's corrected v6 validator,
T3d's gate, T13d's modeled clamp, T18/T18b axis orthogonality, T22's single
lifecycle rule, and the bounded limiter map were all confirmed; environment.md
unchanged with the 18080 amendment staged as the §11.3 adoption step; the
fixture-only loopback-bound control port 18081 stays out of the app port map;
PUBLIC-mode tests remain browser-free and cloudflared-free; post-run netstat clean.

Post-run: `netstat -ano` shows no LISTENING socket on 18080/18081. Round-2 audit
record (reviewer-verified, kept for the log): all eight round-1 findings confirmed
resolved at the full-suite scale — T1's no-branch pass condition (exit 7 and 28 both
observed across runs, both PASS), `--max-time 5` on every assertion curl, §6.1 port-map
cross-reference + §11.3 adoption step, no-cloudflared app variants with the 60 s
deadline (cloudflared absent on this host, re-verified), T20 wildcard-bind check,
owner-checked sweep, canonical publication path, and the `pageRateLimitPerMinute` test
hook.

Tooling reality checks this round: curl 8.12.1; jq absent (python assertions); MSYS
`/tmp` invisible to Windows-native python (Windows-style temp paths); owner-checked
pre-flight sweep across ANY bind address (127.0.0.1 / 0.0.0.0 / [::]) — only the
fixture's python.exe listeners are swept; an unexpected owner aborts the suite with
"port 18080 owned by unexpected process" instead of a silent kill. Suite/fixture
defects found and fixed across rounds (recorded so the build workflow does not repeat
them): (1, round 3) the v6 validator shifted by 61 bits instead of 125, silently
dumping all IPv6 visitors into the global bucket — exactly the failure T11d now pins;
(2, round 3) a literal apostrophe inside a python string embedded in the bash test
script closed bash's outer double-quoted string and silently skipped three tests — a
caution for any future bash-embedded python in this suite; (3, round 4) the
PUBLIC-enable refusal emitted a malformed wire response (two status lines, no
Content-Length) — fixed to one well-formed 409 carrying the machine-readable reason,
and T3d now asserts the body, not just the status code; (4, round 4) the fixture's
first lan-address modeling put the address into `join.hint` — inside the payload —
and T18's own assertion caught the contradiction, which is the page-only boundary
working as designed.

## 10. Not run this round (app-side; owned by the future build workflow)

- The desktop app does **not** implement the page server, visibility switch, new
  settings fields, or cloudflared child management — §6 does not exist in the tree, so
  no app-side curl test was possible. §9's PASSes are fixture-scoped to the asserted
  subset; the fixture does not prove, e.g., untested prose claims.
- `gradle` desktop tests / app build: not run (no production code touched).
- **App-side suite items specified, not run:** (1) bot-lane agreement on the pinned
  DLL — does `onlinePlayers` include bots? (the payload's fail-closed gating makes a
  wrong answer safe, §3.3, but the cross-check constants must be pinned against the
  real lanes); (2) the `characters`-DB roster query against the pinned DB, **plus the
  roster's cache/quiesce discipline: roster reads must never extend backup/datadir
  swap windows** (§6.2, `DesktopAppModel.kt:1095-1103` discipline); (3)
  parent-hard-kill → trycloudflare URL stops resolving + orphan-sweep effectiveness
  including the lost-record detection path with the FRIENDS-transition gate (live
  cloudflared; not run here — binary absent, `which cloudflared` → not found; folds
  into the jpackage crash test, §11.6); (4) sustained page views at the rate limit
  must not move world tick timings (world-cache abuse test, §6.2); (5) real
  MariaDB-lane page behavior, including the four (provider × lane) cells end-to-end —
  the fixture models them (T9c, T18, T18b) but the real lane is untested; (6)
  Android-twin failure emission (§6.4) — negative tests for unknown phase enum and
  binder death mid-poll are specified there, not run; (7) the settings-write MOTD
  clamp with the T13d vectors against the real `Settings` write path; (8)
  **cloudflaredPath discovery**: set `cloudflaredPath` to a nonexistent file and to a
  non-executable file, enable PUBLIC, and assert the §5.7 outcome (refusal, or
  `tunnelState == "binary-missing"` with `url == null` and failure copy naming the
  exact command tried); (9) **connection posture** (§6.2): N stalled connections + M
  valid requests → the valid requests are still served (bounded executor +
  read/idle timeouts + header caps, pinning the JDK HttpServer defaults relied on);
  (10) **T23's true tear**: stop the realm between the app's `state.value` capture
  and its native presence sample and assert the delivered payload is still
  coherent — the fixture cannot produce that interleaving by construction (single
  -STATE `build_payload`); its run documents intent and the coherent pre/post-stop
  branches only.
- The round-3 trim/consent enumeration (§3.5) must be re-checked verbatim against the
  final payload when the app implements it — the copy names the exact host-relevant
  field set.

## 11. Open items

1. Pin the `activeSessions` vs `onlinePlayers` counting semantics on the pinned DLL
   (§10 item 1) and turn the cross-check into the PUBLIC-enablement gate.
2. Scoped firewall rule for 18080 on the FRIENDS wildcard lane (design-tunnel §4.4.5
   coordination clause: invariant + rule + listener lifecycle together); also removes
   T20's first-run firewall prompt.
3. Amend `out/live-verification/environment.md`'s port map with the 18080 row and
   retire its "only HTTP status page" clause when this design lands (§6.1).
4. Android twin: same payload contract from `RuntimeSupervisorClient` state + the §6.4
   failure-emission work (binder death, unknown phase) — later round.
5. Public roster (names toggle) abuse review against the "treat public realms as
   public" posture (convergence §3) before names-in-public is ever defaulted on.
6. Windows Job Object code path needs a real crash test on the jpackage image,
   including the lost-record orphan-detection path (§5.3) — the fixture models state,
   not process ownership.

---

## 6. Bug-report format and round-robin convergence rules

### 6.1 Finding schema

Every discrepancy the bug-checker observes becomes one JSON object, appended to
`out/live-verification/findings/findings.jsonl` (machine-readable, append-only) and
rendered per round into `out/live-verification/findings/round-<N>/<area>.md`
(human-readable). Mandatory fields first — the five the ask pins — then the operational
fields the workflow needs to close them:

```json
{
  "id": "LVF-multiplayer-003",
  "feature": "DESK-127 / MP:RA-INVITE-TOKENS (rows LV-multiplayer-15, LV-multiplayer-19)",
  "severity": "P0 | P1 | P2 | design-gap | row-invalid",
  "repro": [
    "cd desktop && gradlew.bat rpHost -PrpBots=5 (background)",
    "python out\\live-verification\\scripts\\srp6_probe.py --host 127.0.0.1 --port 3724 --account RPTEST --wrong-password --attempts 10",
    "SQ('classicrealmd','SELECT failed_logins FROM account WHERE username=\\'RPTEST\\'')"
  ],
  "expected": "pass condition quoted from the row",
  "observed": "what actually happened",
  "evidence": {
    "commands": ["exact command strings, copy-paste runnable"],
    "outputs": ["quoted stdout/stderr excerpts that justify the finding"],
    "artifacts": ["out/live-verification/evidence/LV-multiplayer-15/transcript.json", "path:line for any code cite"]
  },
  "class": "product-fail | row-invalid | coverage-gap | feasibility | interaction",
  "area": "multiplayer",
  "round": 3,
  "status": "open | confirmed | fixed-verified | not-reproducing | waived",
  "title": "one line"
}
```

- **`id`** — `LVF-<area>-<seq>`; area keys are the eight §4 slugs (including
  `launch-boundary`) plus `realm-status-browser`.
- **`feature`** — the deliverable IDs (campaign-inventory.md) and matrix row IDs affected;
  never a bare row ID without its deliverable IDs.
- **`severity`** — the matrix's own scale, filed exactly as the row's area documents it
  (no remapping invented at execution time): **P0** = converged-core seam,
  **P1** = shipped-deliverable behavior, **P2** = hardening/copy
  (matrix-multiplayer.md "Audit-round-2 conventions"); the other areas' documented scales
  are used verbatim — **P3** (flavor-import / bot-actors: determinism/hardening) and
  **High / Medium** (bots-llm); **design-gap** = converged
  architecture the code does not implement yet (recorded, NOT a product bug —
  matrix-multiplayer.md "Architecture gaps" precedent); **row-invalid** = the row's
  stated mechanism cannot run or cannot assert what it claims (the finding replaces the
  test; the row is re-designed before it may run again).
- **`repro`** — the exact command sequence, in order, copy-paste runnable on this host
  under the §7 bounds. A repro that needs eyes-on or an unbounded wait is itself a
  `feasibility` finding (the design run's feasibility-auditor rule).
- **`evidence`** — quoted command output, file paths (logs, transcripts, SQL query
  results), and `path:line` code cites. The section files' own convention applies: a
  finding without runnable evidence is not filed.

Finding **classes** mirror the three audit lenses the design run used every round
(coverage / feasibility / adversarial, `.zcode/workflow-runs/dwfrun-a109a3e5….mjs`
matrix phase): `product-fail` (row ran; pass condition unmet), `row-invalid`,
`coverage-gap` (a campaign deliverable ID maps to no executable row — ledger duty, §1.1),
`feasibility` (mechanism not headless/unattended/bounded), `interaction`
(cross-feature breakage: timeframe × bots era-policy, tunnel × guest bans, timeframe ×
payload badge — the interaction tests the matrices already pin).

### 6.2 Round-robin execution and convergence rules

**A round** = one pass over the areas in §4's canonical order (flavor-import → server-db
→ bots-llm → multiplayer → timeframe-era → desktop-app → bot-actors → launch-boundary,
plus realm-status-browser once its build item lands). Within a round the workflow:

1. **Artifact re-check** (§3.1) — re-run the R1–R7 presence checks; newly verified
   artifacts pull their Stage-B rows into this round.
2. **Re-verify open findings** — re-run each open finding's `repro` sequence exactly.
3. **Execute rows** — each area's next runnable rows in row-ID order, honoring §7's
   sequential/bounded execution; evidence and journal written per row (§7.6).
4. **Ledger update** — coverage ledger, findings files, resume journal.

**Convergence predicate** (the design run's own loop shape, applied to execution: its
matrix phase ran `round 1..4` and declared "Matrix design converged" only when every
auditor reported complete AND findings were zero): the workflow **converges** at the
first round that is *clean* — zero new findings, zero open findings changing state, and
every row executed in the round passing — **after at least one full pass over all eight
areas**. A round with the §5 build+test item still open (un-reviewed build, red test
gate, or failing §9 rows) does not converge. A round that files findings or fixes nothing is followed by another round.

**Hard cap and extension.** Default cap **4 rounds** (the design run's matrix-round cap).
The cap extends in +2-round increments only while the previous round still made monotone
progress (open findings strictly decreased, or artifact arrivals newly unblocked Stage-B
rows). Convergence, not round count, is the exit criterion — the campaign's own precedent
is a phase that "exited converged (11 rounds)" (campaign-inventory.md:42); the cap exists
to bound host resources, not to declare victory early. When the cap hits without
convergence, the run ends `unconverged` with the ledger + open findings as the
deliverable — never a fabricated green.

**Finding lifecycle.** `open` → `confirmed` (reproduced by a later round's re-verify) →
`fixed-verified` (the repro sequence now passes end-to-end) | `waived` (owner decision,
reason recorded) | `not-reproducing` (two consecutive clean re-runs; kept in the ledger,
not closed, re-tried if the area's code changes). Only `fixed-verified`, `waived`, and
rows passing with evidence reduce a round's open-findings count.

**Chronic-row rule.** A row failing with an identical signature (same assertion, same
output modulo timestamps) for 3 consecutive rounds is marked `chronic` in the journal and
skipped for the rest of the run — its finding stays open. The workflow must not burn
unbounded hours re-proving a frozen failure.

**Blocked rows never count against convergence.** Stage-B rows whose artifact is absent,
blocked-tracked/design-gap rows, and DEDICATED-SLOT rows (§3.1) are ledger dispositions,
not failures, and are never counted in the unattended pass set (§3.1, §5).

---

## 7. Resource bounds

### 7.1 Headless flags (exact launch lines)

- Emulator (from §2, environment.md): the full SDK binary path — **bare `emulator` is not
  on this host's PATH** (verified at timeframe-era audit round 2) — then:
  `emulator -avd PocketDiff_x86_64 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save`
  with `-memory 2048` to pin the recorded Modern-lane 2 GiB profile for the 25-bot tier
  (§2). `-no-snapshot-save` keeps cold starts stateless; snapshot fast-boot stays on in
  the AVD config.
- The harness prepends `%LOCALAPPDATA%\Android\Sdk\emulator` and
  `%LOCALAPPDATA%\Android\Sdk\platform-tools` to PATH (matrix-multiplayer.md
  audit-round-2 conventions: "SDK tool paths are not on this host's PATH").
- Bridge after boot (§2 port map): `adb reverse tcp:3724 tcp:3724`,
  `adb reverse tcp:8085 tcp:8085`, plus `tcp:8080` (LLM lane) and `tcp:3725` only for the
  rows that name them.
- Servers stay host-native and in-process (`gradlew bootRealmd` / `bootWorld` /
  `supervisorStartGate`, §2); they bind host loopback only.

### 7.2 Session caps

| Resource | Cap | Source |
|---|---|---|
| Guest RAM | 2048 MiB default; 6144 MiB only for rows needing the Large-lane data profile | §2 resource profile (recorded Modern tier `mem_total_kb 2532436`; PocketDiff default 6144) |
| Guest vCPU | 4 | §2 |
| GPU | software GL only (`hw.gpu.enabled=no` + swiftshader) — the host desktop GPU is never touched | §2 |
| Instances | ONE emulator + ONE server lane at a time | §2 "Sequential feature tests only" |
| Server JVM | **`-Xmx2g` default, pinned before the run starts** (this plan's pin — the sources say only "can be heap-capped", §2). **Mechanism (corrected by the final read): the server-hosting gates (`supervisorStartGate`/`bootRealmd`/`bootWorld`/`rpHost`) are forked `JavaExec` child JVMs of the Gradle daemon (desktop/build.gradle.kts `tasks.register<JavaExec>`), so `GRADLE_OPTS -Dorg.gradle.jvmargs` configures ONLY the daemon and must not be relied on.** The harness exports `JAVA_TOOL_OPTIONS="-Xmx2g …"` instead (alternatively `_JAVA_OPTIONS`): the variable is inherited by every forked JVM, and each JVM prints `Picked up JAVA_TOOL_OPTIONS: -Xmx2g` on stderr at startup. A row needing more heap declares its own `-Xmx` in its row text and the harness prepends that value for that row alone (a JVM honors the LAST `-Xmx` it sees — verify per invocation in the captured output). The §7.5 boot gate asserts the cap REACHED THE SERVER CHILD: bring-up fails unless the gate's captured output contains the child JVM's `Picked up JAVA_TOOL_OPTIONS: -Xmx2g` line; a daemon-side `Runtime.getRuntime().maxMemory()` print is NOT acceptable evidence (it measures the wrong JVM — the false-green this correction removes) | §2 + this plan's pin |
| Host | verified live at decision time: WHPX usable, emulator 36.6.11.0, adb 1.0.41, Ryzen 7 7700 8C/16T, 95 GB RAM / ~52 GB free | §2 |
| Storage | PocketDiff's 40 G data partition covers the recorded Large-lane purpose; recreate `O11-Large-x86_64` only if the exact 8 GiB qualification profile is required (its `.avd` dir is empty) | §2 |

### 7.3 Wall-clock bounds (no unbounded waits)

- Every row declares a wall-clock bound. Where the row text pins one, it governs; where it
  does not, the workflow default is **30 min per row** and **10 min per poll loop with an
  explicit deadline** (authored policy here — the sources demand boundedness, e.g. the
  browser design's own CLI suite bounds uptime/poll tests, but fix no per-row minutes).
  A row that overruns its bound is **failed with a `feasibility` finding** (§6.1) — it is
  never left hanging.
- All output goes to files: `adb logcat -v time > file`, gradle `--console=plain`,
  per-row evidence dirs under `out/live-verification/evidence/<rowId>/` (§2 "logs to
  files").
- Gradle runs use a bounded `--tests` filter (matrix-multiplayer.md conventions); Android
  rows carry `-PpocketAbi=x86_64` where the row requires it.

### 7.4 Sequential groups

Order of execution is: boot gate (7.5) → Stage A rows area-by-area in §4 order → Stage B
rows as artifacts arrive (round-start re-check, §3.1). Groups G1–G8 never overlap; two
server lanes never run concurrently; the Android instrumented rows run on the emulator
only (requirements.md §1 scope annotation: RP6 excluded). **DEDICATED-SLOT rows are outside
this schedule entirely** — excluded from the unattended lane at every stage, user-informed
slot only, own ledger disposition (§3.1). If any row would exceed a §7.2 cap, the row
aborts with a `feasibility` finding — the caps are not negotiable per-row.

### 7.5 Boot gate (first work item of the run)

§2's operational caveat is explicit: at design time the bridge recipe was **untested
end-to-end** (no emulator boot, no APK build/install, no server boot happened during
environment selection). The run therefore opens with a bounded bring-up gate before any
matrix row: boot `PocketDiff_x86_64` headless → wait for `sys.boot_completed` (deadline) →
install the debug APKs → `adb reverse` both ports → `bootRealmd` + `bootWorld` → drive one
smoke logon through the bridge (e.g. the §4.4 dual-port handoff row's mechanism). Failure
times out the gate after 3 attempts and ends the run `infra-blocked` with the quoted
outputs — matrix rows are not attempted against an unproven bridge.

### 7.6 Resume journal

Durable state of the run: `out/live-verification/resume-journal.json` (schema below) plus
the append-only `findings.jsonl` (§6.1) and per-row evidence dirs. Everything else is
recomputable.

```json
{
  "schema": 1,
  "runStartedUtc": "2026-10-02T00:00:00Z",
  "round": { "completed": 2, "current": 3 },
  "artifacts": { "R1": "absent", "R2": "absent", "R3": "verified@round2", "R4": "absent",
                  "R5": "absent", "R6": "absent", "R7": "absent" },
  "rows": {
    "LV-server-db-06": { "status": "pass", "round": 2,
                         "evidence": "out/live-verification/evidence/LV-server-db-06/",
                         "findingIds": [] },
    "LV-bot-actors-41": { "status": "pass", "round": 2,
                         "evidence": "out/live-verification/evidence/LV-bot-actors-41/",
                         "note": "SPLIT disposition: control lane (f) executed+passed; engagement lane (a)-(e) stays blocked-tracked on P-3 (vehicle = LV-multiplayer-08's guest session)", "findingIds": [] }
  },
  "findings": { "LVF-multiplayer-001": { "status": "confirmed", "round": 3 } }
}
```

Row `status` values: `pending | running | pass | fail | chronic | blocked-artifact |
blocked-tracked | design-gap | build | build-failed` (the last two are the §5 build
item's states; `build` re-queues on resume like `running`). Discipline (modeled on the app's own supervisor journal —
`journal.json`, `DesktopSupervisorJournal.kt:30`, with the corrupt-journal-must-not-throw
contract of `DesktopSupervisorJournalTest`, matrix-server-db.md P18/P26):

- **Write-before-run:** a row is marked `running` before its first command.
- **Atomic rewrite** after every row (write temp, rename): a killed run never leaves a
  half-written journal; on start the workflow reads it, skips terminal statuses
  (`pass | fail | chronic | blocked-* | design-gap`), and re-queues `running` rows.
- A journal that fails to parse is rebuilt from the evidence dirs and findings file, and
  the rebuild is logged loudly — never silently reset (the app's own journal contract).

### 7.7 Run-level stop rules

The run ends itself (never wedges for hours) when any of: boot gate fails 3× (§7.5);
host free disk < 10 GB; the resume journal or findings writes start failing; or a full
round executes zero rows and files zero findings two times in a row (nothing left to do —
converged or fully blocked; the ledger says which).
