# Pocket Realm Vulkan Turnip Drivers — Round-Robin Review & Research Plan

Standing runbook for the **user-imported / community Mesa Turnip driver swap**
feature, in the same two-loop format as the LLM runbook
(`native/llm/REVIEW_AND_RESEARCH_PLAN.md`):

1. **The round-robin review loop** — repeat multi-agent review of the driver
   lane until a full round returns **zero findings** ("clean as a whistle"),
   then keep it converged as the feature evolves.
2. **The research & ideas loop** — every review round also *adds* to this
   document: each reviewer agent contributes a few new ideas (not defects)
   toward driver **performance, compatibility, robustness, and UX**, feeding
   the research tracks in Part 3.

> ## ⛔ DIRECTIVE — READ THIS FIRST, BEFORE STARTING ANY ROUND ⛔
>
> **Scope = the Vulkan/Turnip driver lane only** (the files in Part 1).
> Two neighboring efforts are owned by **other agents** — do not open,
> review, continue, or "help" either of them, and do not touch their files:
>
> - the **LLM companion loop** (`native/llm/REVIEW_AND_RESEARCH_PLAN.md`
>   and everything under `native/patches/playerbots/`, `native/llm/`,
>   the PB_*/CORE_* overlays in `tools/build_o09_realm_runtime.py`); and
> - the **MariaDB study** (`docs/plans/mariadb-replacement-research-plan.md`).
>
> If a finding concerns those, skip it — at most note it in the round log as
> "external: owned elsewhere". Scope drift is the failure mode this banner
> exists to prevent.

Everything below is written so a fresh session (or a new reviewer) can run
the loop without any other context.

---

## Part 0 — Status snapshot (update every round)

| Round | Reviewers | Findings | Fixed | Builds/tests after fixes |
|-------|-----------|----------|-------|-------------------------|
| 0 (pre-plan) | internal pass | zip-bomb cap, registry lock, copy early-out, guard races | all (commit 7d7b73a) | green |
| 1 | 6 agents | 10 reported → 9 distinct (critical 1, major 3, minor 5) | all 9 (this round, uncommitted) | green: compile 0 errors, unit tests 0 failures, both `--check` gates exit 0 |
| 2 | 6 agents | 6 (major 2, minor 4); reviewers 1/3/4 fully CLEAN | all 6 (this round, uncommitted) | green: compile 0 errors, unit tests 0 failures, both `--check` gates exit 0 |
| 3 | 6 agents | 9 (important 4, minor/low 5); reviewer 2 fully CLEAN | all 9 (this round, uncommitted) | green: compile 0 errors, unit tests 0 failures, both `--check` gates exit 0 |
| 4 | 6 agents | 5 distinct (all important); reviewers 1/2/3 fully CLEAN | all 5 (this round, uncommitted) | green: compile 0 errors, unit tests 0 failures, both `--check` gates exit 0 |
| 5 | 6 agents | 5 (all important); reviewers 2/3/4 fully CLEAN | all 5 (this round, uncommitted) | green: compile 0 errors, unit tests 0 failures, both `--check` gates exit 0 |
| 6 | 6 agents | 1 (important); reviewers 1/2/3/5/6 fully CLEAN | fixed (this round, uncommitted) | green: compile 0 errors, unit tests 0 failures, both `--check` gates exit 0 |
| 7 | 6 agents | 3 (critical 1, important 2); reviewers 2/3/5/6 fully CLEAN | all 3 (this round, uncommitted) | green: compile 0 errors, unit tests 0 failures, both `--check` gates exit 0 |
| 8 | 6 agents | 2 (important); reviewers 2/3/5/6 fully CLEAN | all 2 (this round, uncommitted) | green: compile 0 errors, unit tests 0 failures, both `--check` gates exit 0 |
| 9 | 6 agents | **CLEAN — 0 findings, 6/6 reviewers** | — | green (no changes this round) |
| 10 | 6 agents | **CLEAN — 0 findings, 6/6 reviewers** | — | green (no changes this round) |

Convergence rule: a round is **clean** when all six reviewers return "clean"
in every category and contribute nothing outside the **Accepted Caveats
Registry** (Part 2c). Rounds continue until **two consecutive clean rounds**
— that is the "clean as a whistle" bar.

> **STATUS: CONVERGED (2026-08-22).** Rounds 9 and 10 were both fully
> clean — the two-consecutive-clean bar is met across 40 fixed defects
> over ten rounds. The loop now switches to its standing mode: **re-run
> one round (Part 2) after any change to the lane's files**, treating the
> Round 1–8 fix set as the permanent regression list. The next change's
> "verify these recent fixes" list starts from the Round 8 set (the
> null-coercion guards + catalog generator literal checks — Rounds 9–10
> made no changes).

---

## Part 1 — What is being reviewed (architecture map)

Pocket Realm's game client renders through Vulkan on Android. On Adreno
GPUs the packaged Mesa **Turnip** ICD is the default; every other vendor
uses the system **Vortek** bridge (see `ArmRendererAuto.kt`). On top of
that, this feature lets the user **import their own Turnip driver build**
(or pick a **pinned community build** in Settings) and run the client with
it — behind an opt-in toggle, with an offline validation gate, a
fail-closed resolution seam, and a crash guard that quarantines bad
drivers.

Landed in phases (commit order): B registry/model/validator → C runtime
integration behind the toggle → D picker/import/delete/home-chip UI →
E crash guard/quarantine/diagnostics → post-review hardening →
AdrenoTools `meta.json` import support → community manifest + generator +
Gradle gate → pinned downloader + Settings dialog. Original phase plan:
`docs/plans/user-vulkan-drivers-autonomous.md`; community list design:
`docs/plans/community-turnip-list.md`.

Hard constraints every reviewer must know:

- This lane is **pure app-side Kotlin** — no native submodules, no
  overlays. The pinned-submodule rules that govern the LLM loop do not
  apply here, but its files ARE out of scope (see the top directive).
- **Fail-closed catalog semantics (I1)**: catalog ids (`VulkanDriverCatalog`)
  keep their closed behavior untouched; the user lane is purely additive
  behind its toggle.
- **Exact reasons (I8)**: every rejection/resolution failure carries an
  exact human-readable reason surfaced verbatim in UI and diagnostics —
  nothing is silently accepted, substituted, or swallowed.
- **Same gates for community imports (C3)**: a community-list download is
  just a pre-filled import — it enters the `user-` namespace and passes the
  identical validation, guard, and quarantine machinery.
- The community manifest is **compile-time pinned** (generated code +
  `schemas/community-vulkan-drivers.json`), enforced by a Gradle gate that
  re-runs the generator in `--check` mode (`android/app/build.gradle.kts`
  ~line 1054, `tools/generate_community_vulkan_drivers.py`).

Key files:

| Area | Files |
|------|-------|
| model + registry | `android/app/src/main/java/com/pocketrealm/client/UserVulkanDriverRegistry.kt` (478 ln — `UserVulkanDriver` model with constructor invariants, JSON + file persistence under app-private storage, zip extraction with bomb cap, locks) |
| import gate | `.../client/UserVulkanDriverValidator.kt` (214 ln — size cap 256 MB, ELF64 + `EM_AARCH64` identity, 16 KB `PT_LOAD` alignment rule, ICD-JSON and AdrenoTools `meta.json` outcomes, sha256) |
| resolution seam | `.../client/UserVulkanDriverResolution.kt` (137 ln — the single seam where `user-` ids meet the launch chain; Adreno-only reason; quarantined-driver reason; `SessionDriver` sealed type) |
| crash guard | `.../client/UserVulkanCrashGuard.kt` (89 ln — 2 consecutive early deaths < 10 s ⇒ quarantine; clean exit resets; forced stops never count; SYSTEM/PACKAGED exempt) |
| community lane | `.../client/CommunityVulkanDrivers.kt`, `GeneratedCommunityVulkanDrivers.kt`, `CommunityVulkanDriverDownload.kt` (pinned GitHub-release assets, size+sha256, optional inner-`.so` sha for "already imported" matching) |
| catalog + probe | `.../client/VulkanDriverCatalog.kt` (290 ln, closed SYSTEM/PACKAGED lanes), `GeneratedVulkanDriverCatalog.kt`, `AndroidSystemVulkanProbe.kt` |
| launch wiring | `.../client/ClientRuntimeService.kt`, `ClientRuntime.kt`, `ArmRendererAuto.kt`, `WineRuntimeStore.kt`, `IntegratedClientDisplay.kt`, `ClientDisplayHost.kt` |
| UI | `.../ui/UserVulkanDriverPresentation.kt`, Settings dialog + home chip (`SettingsScreen.kt`, `HomeScreen.kt`) |
| generators + gate | `tools/generate_community_vulkan_drivers.py`, `tools/generate_vulkan_driver_catalog.py`, `schemas/community-vulkan-drivers.json`, Gradle `--check` gate |
| tests | 9 files, **102 `@Test` methods** under `android/app/src/test/java/com/pocketrealm/client/` (registry 41, catalog 15, download 11, resolution 12, crash guard 7, community 5, bridge readiness 5, generation identity 3, characterization 3) |

Core mechanics reviewers must understand:

- Import chain: pick/extract archive → size cap → ELF identity + 16 KB
  alignment → ICD JSON (or AdrenoTools `meta.json`) → sha256 → registry
  entry (`user-` id, fixed storage name) → selectable in the picker.
- Resolution: request id → catalog id (closed semantics) or `user-` id
  (only when the lane toggle is on) → `SessionDriver` for the session;
  Adreno-only enforcement with the exact ADRENO_ONLY_REASON; quarantined
  drivers refuse with their recorded reason.
- Session lifecycle: launch with the resolved ICD → early-death window
  (10 s) → crash guard streak → quarantine at 2 → user sees the reason;
  clean exits reset; forced stops are never crashes.
- Community lane: pinned manifest → download (resumable, size+sha256
  verified) → imported through the SAME validator/registry (`user-`
  namespace) → `librarySha256` marks "already imported".

---

## Part 2 — The round-robin review protocol

### 2a. Roster (six reviewers, launched in parallel, fresh agents each round)

Each reviewer gets a self-contained prompt: the Part 1 context block, its
angle below, the Accepted Caveats Registry, and the round's "verify these
recent fixes" list. Launch all six in one batch:

1. **import validator & archive security** — attack the import gate:
   zip path traversal (`ZipInputStream` entry names, `../`, absolute
   paths, symlinks), zip-bomb cap correctness (compressed vs uncompressed
   accounting), ELF parse robustness (truncated/malformed headers,
   `RandomAccessFile` bounds, endianness, `EM_AARCH64`/class/PT_LOAD
   checks against real malformed files), sha256 computation, ICD-JSON and
   `meta.json` parsing (malformed JSON, missing keys, absurd values),
   error-path resource cleanup.
2. **registry & persistence** — `UserVulkanDriverRegistry.kt`: lock
   coverage vs the `ConcurrentHashMap`, atomicity of JSON+file writes
   (torn state on crash mid-import/delete?), corrupt-JSON recovery,
   storage invariants (fixed library file name, id/sha256 validation),
   copy/delete early-outs, unbounded growth (orphaned files?), concurrent
   import + delete + enumerate races.
3. **resolution seam & launch integration** — I1 fail-closed semantics
   really preserved; `user-` ids resolve ONLY when the toggle is on;
   Adreno gating correctness; the seam is genuinely the single place the
   user lane touches the launch chain (`ClientRuntimeService` /
   `ArmRendererAuto` / `ClientRuntime` wiring); crash-guard invocation
   points (every user-lane session start/end, forced-stop vs FAILED
   classification, uptime source); quarantine actually blocks relaunch
   with the exact reason.
4. **community lane** — manifest pinning integrity (generated code vs
   `schemas/community-vulkan-drivers.json` vs generator output — run the
   `--check` gate), downloader correctness (redirects, resume, size cap,
   sha256 verify, cancellation, partial-file handling), `librarySha256`
   "already imported" matching, C3 unification (no bypass of the import
   gate anywhere), provenance/license fields accurate.
5. **player-facing UX audit** (the "clean as a whistle" surface) — Settings
   dialog, import/delete flows, home chip, and every reason string the
   player can see: verbatim I8 reasons (never generic "failed"),
   quarantine messaging that tells the player WHAT happened and WHAT to do
   next, no internal jargon leaking (ICD/ELF/sha unless helpful), loading/
   progress states, error recovery paths that always leave the player with
   a working driver (never stranded with none selected).
6. **tests & build integrity** — do the 102 tests actually pin the
   behavior described in Part 1 (hunt for tests that assert the wrong
   invariant or miss the nasty branches: traversal, torn writes, guard
   races)? characterization tests still match reality; generators are
   idempotent; the Gradle gate fails when the manifest drifts; no
   regressions to the SYSTEM/PACKAGED lanes; Kotlin compiles clean.

### 2b. Per-round workflow

0. **Scope check (mandatory first step):** this loop reviews the
   Vulkan/Turnip driver lane only (Part 1 files). The LLM companion loop
   and the MariaDB study are out of scope and owned by other agents (see
   the top directive). Findings in those areas get one line in the round
   log: "external: owned elsewhere".
1. **Launch** the six reviewers in parallel with self-contained prompts.
2. **Verify** every reported finding yourself in source before fixing —
   reviewers are high-quality but not infallible; confirm each claim by
   reading the code (and running the relevant test) before changing it.
3. **Fix** confirmed findings directly in the Kotlin/Python files with
   exact-byte edits; keep the I1/I8/C3 invariants and the constructor-
   validation style of the model intact; add or repair a unit test for
   every behavioral fix.
4. **Rebuild + verify green**:
   ```bash
   cd android && ./gradlew :app:compileDebugKotlin -q -PpocketAbi=arm64-v8a
   cd android && ./gradlew :app:testDebugUnitTest -PpocketAbi=arm64-v8a
   python tools/generate_community_vulkan_drivers.py --check
   ```
   (zero compile errors, zero test failures, generator check exits 0).
5. **Log the round** in Part 0 / Part 5 and append the round's new ideas to
   the Ideas Ledger (Part 4) — every reviewer is required to contribute
   2–3 ideas per round.
6. **Repeat** with fresh agents until two consecutive clean rounds.

### 2c. Accepted Caveats Registry (reviewers must NOT report these)

Seed list — **extend it** when a round surfaces a deliberate trade-off
(then it becomes permanent); never use it to wave away a real defect:

1. The user/community driver lane is opt-in and defaults OFF.
2. Imported Turnip drivers are Adreno-only by design; non-Adreno devices
   get the exact ADRENO_ONLY_REASON and the system Vortek bridge.
3. SYSTEM/PACKAGED lanes are exempt from the crash guard by design (the
   guard is only invoked for user-lane sessions).
4. Quarantine requires 2 early deaths inside the 10 s window — a driver
   that crashes only later is not quarantined (telemetry research in
   Track C may revisit).
5. Community list is compile-time pinned; refreshing it means regenerating
   the manifest + code and passing the Gradle gate (no OTA updates by
   design).
6. Import size cap is 256 MB (`DEFAULT_MAX_IMPORT_BYTES`).
7. The two dead-ish helper APIs noted in past passes (if still present)
   are harmless; flag for removal only in a cleanup round.
8. Deleting a quarantined driver and re-importing the same bytes yields a
   clean entry (explicit user action; the crash guard re-arms and
   re-quarantines after 2 more early deaths). Only the *accidental*
   byte-identical re-import is rejected (Round 1). Track C3's
   un-quarantine affordance may revisit.
9. For flood archives (> 64 entries) the entry-count reason fires before
   the unsafe-path reason — deliberate precedence, both exact.
10. `unitTests.isReturnDefaultValues = true` stubs `android.system.Os` in
    JVM tests; DurableFiles' durability is trusted infrastructure used
    elsewhere (session record).
11. No downloader resume by design — each attempt is a fresh, fully
    verified download (restart-on-failure accepted; resume is a tracked
    idea).
12. Two already-running staging copies (SAF + community) are serialized at
    the registry mutation lock; the UI prevents new concurrent starts.
13. The import rollback's published→keep direction (failure after the
    rename) is not JVM-injectable; the reclaim direction is test-pinned.
14. The crash-guard find→update window can transplant a streak across a
    same-slug remove+reimport (microsecond window, runCatching-wrapped) —
    tracked CAS-update idea.
15. Entry-level malformed JSON in registry.json (hand-edited storage)
    escapes the exact-reason wrapper — unreachable without external
    tampering; tracked hardening idea.
16. Cosmetic, accepted: signed-Byte rendering in the ELF-class reason;
    corrupt-archive ZipExceptions surface via the "Import failed:"
    wrapper (exact-reasons idea tracked); quoted entry names in rejection
    reasons can reach 64 KiB each (bounded-count idea tracked).
17. Lone surrogates / Unicode non-characters in a manifest kill the
    generator with a raw UnicodeEncodeError rather than a reject() —
    tracked manifest-hardening idea.
18. `reset()` sweeps `.incoming-` staging without the 1 h grace — reset is
    only reachable when the registry is unreadable (no live staging can
    exist); defensive parity is a tracked idea.
19. Leaving Settings entirely (tab navigation) mid-import/download still
    drops the final notice and re-enables buttons while the op runs —
    screen-scoped state; the in-Settings boundaries (renderer switch,
    lane toggle) are covered. ViewModel ownership is the tracked fix.

### 2d. Post-edit validation (run after any change)

```bash
cd android && ./gradlew :app:testDebugUnitTest -PpocketAbi=arm64-v8a
python tools/generate_community_vulkan_drivers.py --check   # exit 0 required
python tools/generate_vulkan_driver_catalog.py --check      # exit 0 required
git status --porcelain -- android/app/src tools schemas     # intended files only
```

---

## Part 3 — Research plan: drivers, compatibility, robustness, UX

These tracks are not defect work; they consume Ideas Ledger entries. Every
experiment needs a before/after measurement and must not weaken the
I1/I8/C3 invariants.

### Track A — Driver performance & compatibility matrix

A1. **Bench harness**: scripted session per driver (system Vortek,
    packaged Turnip, each imported build) logging fps, frame-time
    percentiles, load time, and session stability — one command, one
    table out.
A2. **Adreno generation matrix**: map community builds × Adreno 6xx/7xx/
    8xx (and Turnip fork lineage) into a compatibility table so Settings
    can order the list by the user's actual SoC.
A3. **Regression tracking**: re-run the harness per manifest refresh to
    catch driver-release regressions before users do; annotate the
    community manifest with per-SoC notes.

### Track B — Upstream & community pipeline

B1. **Refresh workflow**: semi-automated manifest update (fetch releases,
    pre-fill size/sha256, human approve) that keeps the compile-time
    pinning model intact.
B2. **Provenance & licensing surface**: expose license/upstream/repo per
    entry in the Settings detail sheet (data already in the model).
B3. **Fork evaluation**: criteria + scoring for which Turnip forks earn
    manifest slots (maintained, signed releases, per-SoC testing).

### Track C — Robustness & recovery

C1. **Import-gate fuzzing**: a host-side fuzz corpus (malformed zips,
    truncated ELFs, hostile `meta.json`) run against the validator in
    unit tests — milliseconds per run, no device needed.
C2. **Crash telemetry**: opt-in local diagnostics of early-death stacks
    per driver build → feeds quarantine tuning and the community notes.
C3. **Post-update un-quarantine**: when an updated build with a different
    sha256 is imported over a quarantined label, auto-clear quarantine
    (new artifact, new chance) with the history kept.
C4. **A/B session fallback**: on first launch of a newly imported driver,
    offer a one-tap "revert to previous driver" if the session dies early
    — smoother than quarantine alone.

### Track D — UX & player trust

D1. **Recommended-driver chip**: rank community entries for the detected
    SoC (A2 data) and badge one "Recommended for your device".
D2. **Driver diff notes**: per-entry "what changed vs your current
    driver" one-liner in the picker.
D3. **Import flow polish**: share-sheet/file-picker entry points,
    progress with byte counts, and a post-import validation summary the
    player can read (surfacing the existing I8 reasons verbatim).

---

## Part 4 — Ideas Ledger (every reviewer contributes, every round)

**Rule for review agents:** after the defect report, each reviewer MUST
append 2–3 new ideas (not defects — enhancements, experiments, integration
opportunities) to its round's ledger entry, tagged
`[performance]`, `[compatibility]`, `[robustness]`, `[ux]`, or
`[pipeline]`. The main agent triages: dedupe, rank by
(impact × feasibility), promote the best into Tracks A–D. Never delete
entries — mark them `[done]`, `[promoted]`, or `[rejected: reason]`.

### Seed entries

- `[robustness]` Host-side fuzz corpus for the validator — feeds Track C1.
- `[ux]` "Recommended for your device" badge from the Adreno matrix —
  feeds Tracks A2/D1.
- `[robustness]` Auto-clear quarantine on updated-artifact import —
  feeds Track C3.
- `[pipeline]` Semi-automated manifest refresh PR helper — feeds Track B1.
- `[performance]` Persist the last-session fps next to each driver in the
  picker so players see real-world cost, not just version strings.
- `[ux]` One-tap revert after an early death — feeds Track C4.

### Round 1 (2026-08-22 — 17 ideas from 6 reviewers)

Reviewer 1 (validator):
- `[robustness]` Bound name collection + short-circuit prescan on shape
  violations — **[done]** (the Round 1 entry-cap + sampling fix).
- `[pipeline]` Compute the sha256 during the SAF staging copy (digest while
  streaming) so a 256 MB import is read once, not twice — feeds Track A/B tooling.
- `[ux]` One-tap "View exact rules" on size/junk rejections linking the
  existing wiki page — feeds Track D3.

Reviewer 2 (registry):
- `[robustness]` `list()` verifies `libraryFile(id).isFile` (or badges dead
  entries) so Settings never offers a selectable driver whose payload was
  lost to a torn remove.
- `[robustness]` Change `update(driver)` to `update(id) { fold }` executed
  under the mutation lock so a second fold site can never lose an increment.
- `[pipeline]` Include orphan-dir count + reclaimed bytes from the reconcile
  sweep in the diagnostics session record — feeds Track C2.

Reviewer 3 (resolution):
- `[robustness]` Exclude launcher-level throws before `processTreeStarted`
  from the crash-guard fold (infrastructure failures ≠ driver crashes).
- `[ux]` Home "Active setup" chip shows the exact quarantine/seam reason
  when the selected driver is quarantined or missing.
- `[robustness]` Persist a pending-session marker before the process tree
  starts so an app-process death mid-session still folds an outcome.

Reviewer 4 (community):
- `[ux]` HTTP Range-based resume for community downloads (reuse the APK
  lane's resumable machinery) — feeds Track B/D.
- `[robustness]` Unit test for the lying-Content-Length branch
  (`CommunityVulkanDriverDownload` mid-stream cap).
- `[pipeline]` Generator Gradle gates probe `python3` before `python`
  (overridable launcher property).

Reviewer 5 (UX):
- `[ux]` One-time post-quarantine Home notice ("X quarantined after 2 early
  crashes — selection reverted to Auto") — feeds Track C4/D.
- `[robustness]` One shared registry-backed availability seam for picker,
  preflight, and launch gate — **[done for the pair/preflight dimension]**
  (`UserVulkanDriverResolution.availabilityForPairPreflight`, Round 1);
  the registered/quarantined dimensions remain launch-gated by design.
- `[ux]` Pre-mark community entries with Vulkan < 1.3 with the DXVK-floor
  hint before the player downloads ~20 MB — feeds Track A2/D2.

Reviewer 6 (tests):
- `[robustness]` Move the crash-guard fold inside the registry mutation
  lock (read → fold → write as one critical section).
- `[pipeline]` Golden JVM test asserting the generated Kotlin files contain
  no un-escaped `$` (generators now escape; test would pin it).
- `[ux]` Explicit un-quarantine-after-re-import affordance in the Settings
  row (today: delete + re-import) — feeds Track C3.

Triage: 2 marked done, 0 rejected, 15 open (ranked by impact × feasibility:
resume-capable downloads, crash-fold hardening, python3 gate probing, and
the diagnostics reclaim telemetry are the near-term promotes into Tracks
B1/C2/C4/D).

### Round 2 (2026-08-22 — 18 ideas from 6 reviewers)

Reviewer 1 (validator — CLEAN):
- `[performance]` Early-out the prescan once the verdict is fixed
  (duplicate `.so`/junk seen) — reason stays byte-identical, worst-case
  inflate cost drops.
- `[performance]` Fold the .so digest into the prescan drain
  (DigestInputStream) so the sha256 falls out of pass 1 — one full-file
  read instead of two.
- `[robustness]` Typed rejection codes beside the verbatim I8 strings so
  diagnostics can aggregate import failures without string-matching.

Reviewer 2 (registry):
- `[robustness]` Concurrent-soak JVM test (N threads import/remove/list on
  one root) pinning the lock contract's invariants.
- `[robustness]` Reconcile also detects registry entries whose payload is
  missing (ghost detection) and records it in diagnostics — feeds Track C2.
- `[robustness]` Run the orphan sweep when the Settings driver section
  first loads, so users who never import again still reclaim ~256 MB.

Reviewer 3 (resolution — CLEAN):
- `[ux]` Settings picker staleness when the crash guard quarantines a
  driver while Settings is open (registry.json mtime polling).
- `[robustness]` Session-record ring history (last N records) so
  diagnostics show the full streak that led to quarantine.
- `[robustness]` Extend the preflight test to pin the Settings notice
  computation for user ids (no-catalog-notice rule) end to end.

Reviewer 4 (community — CLEAN):
- `[ux]` Demote/disable community rows already imported (prevents
  accidental duplicate `-2` entries).
- `[ux]` Cooperative download cancellation (flag checked in the copy loop
  + Cancel button) — blocking OkHttp IO ignores coroutine cancellation.
- `[ux]` Surface the `upstream` Mesa provenance in the community dialog
  rows (data already carried through generation).

Reviewer 5 (UX):
- `[ux]` Registry-repair affordance as a first-class Settings card —
  **[done]** (Round 2's reset card + `registry.reset()`).
- `[robustness]` One shared `failureNotice(prefix, throwable)` helper
  (`message ?: simpleName`) reused by every failure path, assertable in
  JVM string tests.
- `[ux]` Live byte-count on the import status line ("Importing driver…
  42 MB copied") plus one-decimal MB in the community progress line.

Reviewer 6 (tests):
- `[robustness]` Chain-level community quarantine test (MockWebServer →
  download → import → byte-identical reason verbatim).
- `[robustness]` Exact-fit sampling boundary test (exactly
  MAX_REASON_SAMPLES junk entries ⇒ no overflow suffix).
- `[robustness]` Reconcile co-tenant test (`.registry.lock` +
  `session-record.json` survive the sweep) — partially covered by the new
  reset test's co-tenant assertion; the reconcile-path variant stays open.

Triage: 1 marked done, 0 rejected, 17 open.

### Round 3 (2026-08-22 — 17 ideas from 6 reviewers)

Reviewer 1 (validator):
- `[robustness]` First-class corrupt-archive rejection inside `unpackZip`
  (`ZipException`/CRC/truncation → purpose-built exact reason instead of
  the generic "Import failed:" wrapper).
- `[robustness]` Adversarial ELF header matrix test (phoff/phentsize/
  phnum boundary cells → exact rejection strings).
- `[robustness]` Entry-level registry parse hardening (per-entry
  getString/getLong failures get the reset pointer, not a raw
  JSONException).

Reviewer 2 (registry — CLEAN):
- `[robustness]` Purge stale cacheDir pre-staging (`user-vulkan-import-*`,
  `community-driver-*`) on the next import — mirrors the lane's
  self-healing contract outside the registry root.
- `[robustness]` Crash-guard fold as compare-and-swap
  (`updateIfCurrent(expected)`) so a stale streak can never transplant
  onto a re-imported same-label entry.
- `[robustness]` Widen the temp sweep to all DurableFiles leftovers under
  the registry root (`.session-record.json.<uuid>.tmp` is currently
  unreclaimable).

Reviewer 3 (resolution):
- `[ux]` Durable one-shot quarantine notice key (DataStore) rendered by
  Settings/Home so the crash guard's silent revert-to-Auto is discoverable.
- `[robustness]` Property-based I1 notice test (generated `user-`-shaped
  ids never produce the catalog's unknown-package text).
- `[robustness]` Selection-fallback race regression test (concurrent
  selection during a slow remove/reset survives).

Reviewer 4 (community):
- `[ux]` Quarantine-aware community rows ("Imported — quarantined",
  optionally disabled) so users don't spend a download the import gate
  will reject.
- `[robustness]` Persisted download-liveness marker so Settings can say
  "the last download did not finish" after process death.
- `[pipeline]` Python unit test for `kotlin_string` adversarial inputs
  (`$`, `\"`, `\\`, form feed, U+2028) — the --check gate only
  byte-compares.

Reviewer 5 (UX):
- `[ux]` Severity-aware status coloring (rejections in error color; the
  status slot currently paints everything neutral).
- `[ux]` Quarantine auto-revert notice in the Settings snapshot
  ("selection was reset to Auto because <label> was quarantined").
- `[robustness]` Recreate-resilient import guard (persisted session epoch)
  so a live import survives activity recreation without process death.

Reviewer 6 (tests):
- `[robustness]` Hoist the SAF stall reason + threshold into a testable
  constant (presentation/validator) — currently inline in the UI function.
- `[robustness]` Both-cases meta.json duplicate test (`meta.json` +
  `nested/META.JSON` counted twice, exact reason).
- `[robustness]` Enumerate-all() community invariant test (URL shape,
  UTF-16 label cap, id/digest shape per entry).

Triage: 0 done this round (the `\f` half of R4's python-test idea was
verified manually this round; a committed test remains open), 0 rejected,
17 open.

### Round 4 (2026-08-22 — 17 ideas from 6 reviewers)

Reviewer 1 (validator — CLEAN):
- `[performance]` Single-pass ELF validate+hash (one streaming read of a
  256 MB import instead of prefix read + full-file digest).
- `[robustness]` Bound quoted entry names in rejection reasons (~200 chars
  per sample; counts stay exact) — zip names can legally reach 64 KiB each.
- `[robustness]` Mutation-fuzz corpus test for `import()` (flipped/truncated
  fixture bytes ⇒ always Imported/Rejected/actionable, never a crash).

Reviewer 2 (registry — CLEAN):
- `[robustness]` Import-count quota (e.g. 16 entries) with an exact reason —
  total lane storage is currently user-unbounded.
- `[robustness]` Reset() staging-dir grace parity with reconcile (defensive
  consistency for future reset call sites).
- `[robustness]` Mutation-lock hold-time guard (log when the OS lock is held
  > ~2 s) to catch I/O creeping into the critical section.

Reviewer 3 (resolution — CLEAN):
- `[robustness]` Skip the crash-guard fold when the process tree never
  started (pre-spawn invariant throws are not driver crashes) — refines the
  known pre-processTree-exclusion idea.
- `[robustness]` Re-attest registry state at launch (`registry.find` +
  quarantine re-check inside attestForLaunch) to close the prepare→launch
  TOCTOU.
- `[ux]` Surface DXVK-floor vs imported api_version mismatches in the picker
  rows before a crash streak teaches the player.

Reviewer 4 (community):
- `[robustness]` Per-entry redirect-host subset (GitHub release-CDN hosts
  only) instead of the full updater allowlist.
- `[pipeline]` Manifest hardening with exact reject() reasons for lone
  surrogates / Unicode non-characters (today they kill the gate with a raw
  UnicodeEncodeError).
- `[robustness]` Optional `origin` field on UserVulkanDriver
  ("community:<id>" vs "saf") so provenance stops being inferred.

Reviewer 5 (UX):
- `[ux]` Two-notice status history (import result and download result as
  separate lines until the next operation).
- `[ux]` Auto-renderer lane-off notice parity (the reset-to-Auto notice is
  currently produced only for the DXVK renderer branch).
- `[robustness]` Lane-toggle mid-operation UI contract test (regression net
  for the orphaned-state class fixed this round).

Reviewer 6 (tests):
- `[robustness]` Injectable publication seam (save hook) making torn-write
  orderings JVM-pinnable cross-platform without directory tricks.
- `[robustness]` Chain the community "Imported" mark end-to-end (real import
  digest → communityDriverRows mark).
- `[robustness]` Pin requireSessionDriver precedence (toggle before
  registration/quarantine before Adreno) per combination.

Triage: 0 done, 0 rejected, 17 open.

### Round 5 (2026-08-22 — 18 ideas from 6 reviewers)

Reviewer 1 (validator):
- `[robustness]` Central-directory prescan via `java.util.zip.ZipFile`
  (names/sizes with zero inflation) — structurally eliminates the
  skipped-entry-drain class instead of patching skip sites.
- `[performance]` Single-pass archive handling (classify while writing to
  canonical staging names, discard on violation) — halves inflate work and
  removes prescan/write-pass drift risk.
- `[robustness]` Atomic progress clock for the SAF watchdog
  (AtomicLong/volatile lastProgressNanos).

Reviewer 2 (registry — CLEAN):
- `[robustness]` Pin reset()/remove() save-first ordering via the
  registry.json-as-directory fault (ordering, not final state).
- `[robustness]` Sweep the crash guard's `.session-record.json.*` temps in
  reconcile/reset (currently unreclaimable after a process death mid-write).
- `[robustness]` JVM test for the OS file-lock half of the mutation lock
  (hold it externally, assert a mutation blocks until release).

Reviewer 3 (resolution — CLEAN):
- `[robustness]` Stamp crash-guard uptime at native return, not fold time
  (a ~9.9 s death must not be misclassified by fold-time overhead).
- `[robustness]` Write the session record on pre-spawn forced early-returns
  (keeps diagnostics contiguous across aborts).
- `[compatibility]` Import-time Turnip-kind heuristic (bounded scan for a
  freedreno/DT_SONAME marker) — reject wrong-kind aarch64 libraries with
  an exact reason instead of a 15 s launch failure.

Reviewer 4 (community — CLEAN):
- `[pipeline]` Generator-side bare-so digest invariant
  (`library_sha256 in (None, sha256)`; distinct digest required for
  adrenotools-zip).
- `[robustness]` Structured transient-notice model (sealed
  InProgress/Result status instead of string-prefix sniffing).
- `[robustness]` Sweep orphaned `community-driver-*` cacheDir temps on
  Settings section open (mirror of the registry's staging sweep).

Reviewer 5 (UX):
- `[robustness]` Own the driver-section operation state in a
  screen-scoped ViewModel/StateFlow — survives every disposal boundary
  (subsumes the caveat-19 residual and enables "finished while you were
  away" notices).
- `[ux]` Quarantine-aware community mark ("Imported — quarantined") before
  the player spends a doomed re-download.
- `[ux]` Morph the delete-dialog confirm label to "Close" when the pending
  driver no longer resolves (the button today promises a delete it cannot
  perform).

Reviewer 6 (tests):
- `[robustness]` Reconcile-preserves-diagnostics pin (session-record
  survives every import's reconcile, byte-identical).
- `[robustness]` Reason-hygiene sweep: table-driven test asserting every
  reachable reason string is non-blank, ≤ ~512 chars, and carries its
  anchor token.
- `[robustness]` Same-root two-instance concurrency pin (two registries,
  two threads, distinct labels ⇒ 2 entries, no orphans).

Triage: 0 done, 0 rejected, 18 open.

### Round 6 (2026-08-22 — 18 ideas from 6 reviewers)

Reviewer 1 (validator — CLEAN):
- `[compatibility]` Require ≥ 1 PT_LOAD segment in `elfRejection` (a
  PT_LOAD-less image passes the gate today and burns two crash-guard
  deaths before quarantine).
- `[robustness]` Bound JSON nesting depth before `JSONObject(...)` so
  recursion-bomb manifests stay inside the exact-reason contract.
- `[robustness]` Quarantine-digest memory that survives `reset()` (durable
  sidecar of last N quarantined digests) — feeds Track C3.

Reviewer 2 (registry — CLEAN):
- `[robustness]` Payload fsync before publishing the entry (or digest-
  mismatch auto-quarantine on load) — power-loss hardening beyond the
  registry file itself.
- `[robustness]` Reconcile on list/refresh, not only on import — **[dupe
  of the Round-4 sweep-on-Settings-load idea]**.
- `[robustness]` Shared `classifyEntryBase` helper for prescan + write
  pass (structural parity instead of by-convention).

Reviewer 3 (resolution — CLEAN):
- `[robustness]` Registry entry-count cap with an exact reason — **[dupe
  of the Round-4 import-quota idea]**.
- `[robustness]` Lane-toggle epoch in the prepared launch ticket (closes
  the toggle staleness window mid-start-sequence).
- `[robustness]` Durable pending-outcome marker so a failed crash-guard
  fold (registry hiccup) is not a lost early death.

Reviewer 4 (community):
- `[pipeline]` Manifest→Kotlin parity oracle test (construct the Kotlin
  data classes straight from the JSON in CI; fails visibly when a Kotlin
  invariant lacks a generator check).
- `[robustness]` Pin `Accept-Encoding: identity` on pinned downloads (an
  intermediary content-encoding breaks the byte-exact contract).
- `[robustness]` Storage preflight (`usableSpace >= size * 2`) for the
  community staged file before streaming.

Reviewer 5 (UX — CLEAN):
- `[ux]` First-crash warning in the row status line ("1 early crash so
  far; one more will quarantine it").
- `[ux]` Confirmation dialog for "Reset imported drivers" (single-tap
  wipes the lane today; mirrors the delete dialog).
- `[ux]` Surface final notices near the action buttons (snackbar/anchor)
  instead of the bottom of the tall section.

Reviewer 6 (tests — CLEAN):
- `[robustness]` Redirect-cap success-side test (exactly 3 hops then 200 ⇒
  Downloaded) — guards against over-tightening the hop budget.
- `[robustness]` Downloader body-shape defense branch tests (mid-stream
  over-cap, final-length mismatch) via an interceptor-injected client.
- `[pipeline]` Kotlin-side manifest↔generated content parity test —
  **[overlaps Reviewer 4's parity oracle; one test can serve both]**.

Triage: 0 done, 2 marked as dupes of known ideas, 16 new open (the two
parity-oracle proposals deduped into one).

### Round 7 (2026-08-22 — 18 ideas from 6 reviewers)

Reviewer 1 (validator):
- `[robustness]` Omit-when-null write discipline for registry.json (skip
  absent fields entirely) so both org.json implementations converge.
- `[robustness]` Debug-build registry round-trip canary (re-read + model
  equality right after every save) — catches write/read asymmetry on
  device the JVM suite cannot see.
- `[robustness]` Zip End-of-Central-Directory cross-check against the
  counted entries ("archive is internally inconsistent" exact reason).

Reviewer 2 (registry — CLEAN):
- `[robustness]` Sweep `.session-record.json.*` co-tenant temps in
  reconcile/reset — **[dupe of the Round-5 idea]**.
- `[robustness]` Write the crash-guard session record AFTER a successful
  registry update so diagnostics never describe a quarantine that never
  landed.
- `[robustness]` Bidirectional reconcile (entries whose payload is missing
  get demoted/dropped at import time) — **[dupe of the ghost-detection
  idea]**.

Reviewer 3 (resolution — CLEAN):
- `[robustness]` Kill-time stamping for forced-stop classification (a
  3 s hang that takes 12 s to drain should fold streak-neutral).
- `[robustness]` Fold-by-sha256 attribution guard (skip the fold when the
  id was recycled via delete + re-import).
- `[robustness]` End-to-end reason-chain contract test (one gate reason
  per seam failure pinned across every prefixing boundary).

Reviewer 4 (community):
- `[pipeline]` Pin-freshness CI probe (scheduled HEAD of each manifest
  URL; Content-Length/sha re-verify) — maintainer-visible asset rot.
- `[pipeline]` Lane-independent catalog semantic gates (hoist the
  arm64-full-only closure checks into the generator) — generalizes the
  default-binding fix.
- `[pipeline]` Generator adversarial-fixture self-compile test (render a
  hostile fixture and compile it with kotlinc — tests the real compiler,
  not a re-implemented oracle).

Reviewer 5 (UX — CLEAN):
- `[ux]` Launch-gate reason pass-through in the Home failure mapping (the
  exact seam reason exists in state but renders as the generic
  "wait a minute or two" hint).
- `[ux]` Notice attribution after a failed reset (the list reread
  overwrites "Reset failed:" with the read error).
- `[robustness]` Atomic watchdog progress clock — **[dupe of the Round-5
  idea]**.

Reviewer 6 (tests — CLEAN):
- `[robustness]` Close the last two elfRejection guard pins (EI_DATA
  big-endian, e_phentsize < 56).
- `[robustness]` Same-root concurrent-import lock test — **[dupe of the
  Round-5 idea]**.
- `[pipeline]` Gate-enforce the mirrored 64 MiB download cap (emit the
  constant into the generated Kotlin so divergence fails --check).

Triage: 0 done, 4 marked as dupes, 14 new open.

### Round 8 (2026-08-22 — 18 ideas from 6 reviewers)

Reviewer 1 (validator):
- `[robustness]` org.json platform-behavior shim for the JVM suite
  (reproduce Android's null→"null" coercion in CI, fencing the whole
  divergence class instead of per-site guards).
- `[compatibility]` Plausibility floor (or warning) on the imported
  library size — synthetic 4 KB stubs pass the ELF gate today.
- `[robustness]` Entry-level provenance column
  (`importedVia: "saf" | "community:<id>"`) — **[overlaps the known
  origin-field idea]**.

Reviewer 2 (registry — CLEAN):
- `[robustness]` Generalize the optional-string read guard lane-wide —
  **[done — this Round's fix is exactly that]**.
- `[robustness]` Widen the temp sweep to session-record temps — **[dupe]**.
- `[robustness]` Order the crash-guard record after the registry update —
  **[dupe]**.

Reviewer 3 (resolution — CLEAN):
- `[robustness]` Seal the typed-interface plumbing drift (`PrefixRequest`
  lacks `allowUserVulkanDrivers`; latent, no production constructor).
- `[robustness]` Session record agrees with the registry by construction —
  **[dupe]**.
- `[ux]` Auto-renderer lane-off notice parity — **[dupe]**.

Reviewer 4 (community):
- `[pipeline]` Generator adversarial self-test (`tools/test_generators.py`
  with mutated manifests asserting clean rejects).
- `[pipeline]` `librarySha256` review-typo tripwire (post-import compare
  against the outcome digest).
- `[pipeline]` `library_sha256` uniqueness across entries — **[overlaps the
  bare-so digest-invariant idea]**.

Reviewer 5 (UX — CLEAN):
- `[ux]` Surface the curated per-entry `summary`/`note` in the community
  dialog rows.
- `[ux]` Reflect a selected user driver in Home's setup chip under the
  AUTO renderer (the chip misdescribes the active setup today).
- `[robustness]` Check the stall flag before accepting EOF (watchdog close
  racing the final read attributes the wrong reason).

Reviewer 6 (tests — CLEAN):
- `[robustness]` Pin reconcile-before-stage for an orphan colliding with
  the incoming import's exact slug.
- `[robustness]` Pin the quarantined-on-non-Adreno row status precedence.
- `[robustness]` Pin the `e_phoff == 0` fail-closed edge.

Triage: 1 done, 6 marked dupes/overlaps, 11 new open.

### Round 9 (2026-08-22 — 18 ideas from 6 reviewers; round fully CLEAN)

Reviewer 1 (validator — CLEAN):
- `[robustness]` Local-header vs central-directory agreement check via a
  post-prescan `ZipFile` pass (parser-differential class).
- `[compatibility]` PT_LOAD congruence check (`p_offset ≡ p_vaddr mod
  p_align`) alongside the alignment magnitude gate.
- `[ux]` Healthy-duplicate import notice ("already imported as X") —
  **[overlaps the known duplicate-sibling/demote-rows space]**.

Reviewer 2 (registry — CLEAN):
- `[robustness]` Gate destructive Settings actions (reset/delete) on an
  in-flight import.
- `[robustness]` Previous-generation registry backup for reset-free repair.
- `[robustness]` Bounded acquisition of the mutation lock (tryLock loop +
  exact reason).

Reviewer 3 (resolution — CLEAN):
- `[robustness]` Session-record ring buffer — **[dupe]**.
- `[performance]` Memoize the rootfs ICD digest (recomputed per prepare
  today).
- `[ux]` Healthy-lane informational note at preflight ("Imported Turnip
  driver selected; registry gates apply at launch").

Reviewer 4 (community — CLEAN):
- `[ux]` Pre-flight the quarantine/duplicate match before downloading —
  **[overlaps the quarantine-aware-rows idea]**.
- `[pipeline]` Supersede/retire lifecycle for manifest entries
  (`superseded_by` with an exact row state).
- `[pipeline]` Escape-scanner round-trip corpus test — **[overlaps the
  kotlin_string python-test idea]**.

Reviewer 5 (UX — CLEAN):
- `[robustness]` Lifecycle-aware registry re-read (ON_RESUME) — **[overlaps
  the picker-staleness idea]**.
- `[ux]` Determinate progress for the SAF import (OpenableColumns.SIZE
  drives the same bar the community lane has).
- `[robustness]` Record community provenance on imported entries —
  **[dupe of the origin-field idea]**.

Reviewer 6 (tests — CLEAN):
- `[robustness]` Pin the registry's `driverDirectory` guards.
- `[robustness]` End-to-end short-payload import pin (32-byte junk file).
- `[pipeline]` Format↔digest cross-field invariant in the generator —
  **[overlaps the bare-so digest-invariant idea]**.

Triage: 0 done, 8 marked dupes/overlaps, 10 new open.

### Round 10 (2026-08-22 — 18 ideas from 6 reviewers; round fully CLEAN, loop converged)

Reviewer 1 (validator — CLEAN):
- `[robustness]` Segment-exact traversal matcher (split on `/`/`\`,
  compare segments to ".." instead of the `contains("..")` substring, so
  `driver..fix.so` is never mislabeled).
- `[ux]` Attributed size-cap reasons in the prescan (offending entry name
  + per-entry vs running-total split).
- `[performance]` Single-pass extraction from a recorded prescan —
  **[overlaps the known single-pass idea]**.

Reviewer 2 (registry — CLEAN):
- `[robustness]` Self-verifying registry footer (digest of the canonical
  drivers array; mismatch routes into the corrupt-registry reset path).
- `[robustness]` Cross-reset quarantine memory — **[overlaps the
  quarantine-digest sidecar idea]**.
- `[robustness]` Staging heartbeat (touch `.incoming-` between import
  phases so the 1 h grace survives slow hashing on pathological flash).

Reviewer 3 (resolution — CLEAN):
- `[performance]` Thread the resolved SessionDriver through prepare
  instead of triple validation + triple registry reads per launch.
- `[ux]` Selection-summary quarantine notice in Settings (informational
  line next to the toggle, same verbatim reason).
- `[robustness]` Spawn-time uptime origin for the early-death window
  (measure guest lifetime, not launch latency — distinct from the
  kill-time-sampling idea).

Reviewer 4 (community — CLEAN):
- `[robustness]` Config test pinning the production `sharedClient`'s
  `followRedirects(false)` (every current test injects its own client; a
  one-line regression would silently disable the host policy).
- `[robustness]` Persisted provenance for community imports — **[dupe of
  the origin-field idea]**.
- `[pipeline]` `MAX_DOWNLOAD_BYTES` mirror drift guard — **[dupe]**.

Reviewer 5 (UX — CLEAN):
- `[ux]` Quarantine-aware "Imported" mark — **[dupe]**.
- `[robustness]` Structured transient-notice status type — **[dupe]**.
- `[ux]` "Imported Vulkan drivers" block in the in-app Diagnostics screen
  (last session record + registry entries + quarantine flags).

Reviewer 6 (tests — CLEAN):
- `[robustness]` Cross-layer ordering parity pin (list() vs rows()
  comparator duplication).
- `[robustness]` Crash-guard constants-drift guard
  (QUARANTINE_REASON must quote the real threshold).
- `[ux]` Sub-megabyte progress-line behavior (pin `size >= 1 MiB` or
  format KiB below the threshold).

Triage: 0 done, 6 marked dupes/overlaps, 12 new open. Cumulative ledger:
~120 ideas contributed across ten rounds, ~5 marked done, ~25 deduped,
the rest open and ranked for the Part 3 research tracks.

- _(copy the heading per round)_

---

## Part 5 — Round log template

```
## Round N — <date>
Reviewers: 6/6 returned
Findings: <count> (critical <c>, major <m>, minor <x>) — or CLEAN
Fixes: <list, file:line>
Builds/tests: kotlin=<e> unit-tests=<failures> generator-check=<rc>
Ideas contributed: <count> → <promoted/done/rejected>
Notes: <anything unusual — external-scope sightings, flaky tests>
```

## Round 0 — pre-plan internal pass (2026-08, commit 7d7b73a)

Reviewers: internal (no agent roster)
Findings: zip-bomb cap, registry lock, copy early-out, guard races
Fixes: all, in 7d7b73a "Vulkan user drivers: post-review hardening"
Builds/tests: green at commit time
Notes: baseline for Round 1's "verify these fixes" list.

## Round 1 — 2026-08-22

Reviewers: 6/6 returned (validator/security, registry/persistence,
resolution/launch, community lane, player UX, tests/build)
Findings: 10 reported → 9 distinct after merging one duplicate
(critical 1, major 3, minor 5)
Fixes (all verified in source before fixing; suite re-run green):
- CRITICAL — selecting an imported driver with the DXVK renderer produced a
  false "Unknown Vulkan driver package" that blocked all Home/LAN client
  launch and disabled every DXVK chip in Settings (the catalog cannot know
  `user-` ids). Fix: `UserVulkanDriverResolution.availabilityForPairPreflight`
  (user lane pairs like packaged Turnip; non-Adreno gets the exact
  ADRENO_ONLY_REASON), wired into HomeScreen.kt, LanScreen.kt, and the
  SettingsScreen DXVK section. Test:
  `pairPreflightKeepsUserIdsOffTheCatalogsUnknownPackageNotice`.
- MAJOR — zip prescan accumulated every entry name unbounded and embedded
  them all in the rejection reason (heap/CPU DoS from a header-flood
  archive). Fix: entry-count cap (`MAX_ARCHIVE_ENTRIES = 64`, any valid
  package is ≤3 entries + dir records) with an exact reason, plus
  first-8-names sampling with an exact "and N more" overflow count
  (`UserVulkanDriverRegistry.unpackZip`). Tests:
  `archiveWithTooManyEntriesIsRejectedBeforeNameBookkeepingGrows`,
  `unexpectedEntryNamesAreSampledWithAnExactOverflowCount`.
  Note: for flood archives the count reason now fires before the
  unsafe-path reason would have — both exact, precedence change is
  deliberate.
- MAJOR — interrupted imports (process death mid-extract/move/save)
  stranded `.incoming-*`, orphaned payload dirs, and registry temp files
  forever. Fix: `reconcile()` at import start under the mutation lock;
  staging dirs get a 1 h age grace so a concurrent import's live staging
  is never reclaimed. Test:
  `interruptedImportLeftoversAreReclaimedOnTheNextImport`.
- MAJOR — DXVK chips disabled (same root cause as the critical; counted
  once above as part of the preflight fix).
- MINOR — byte-identical re-import of a quarantined driver resurrected it
  under a fresh id with a clean crash streak. Fix: the import gate rejects
  same-sha256-quarantined bytes with the exact quarantine reason (residual
  delete-then-reimport path recorded as Accepted Caveat 8). Test:
  `reImportingQuarantinedLibraryBytesIsRejectedWithTheExactReason`.
- MINOR — `saveRegistry` was a fsync-less temp+rename copy. Fix:
  `DurableFiles.atomicWrite` (fsync file + parent dir), matching the
  crash-guard session record in the same directory.
- MINOR — SAF file-picker import had no in-progress state or double-tap
  guard (the community lane did). Fix: `userImportInProgress` flag set
  synchronously in the callback, button disabled while running, an
  "Importing driver…" status line, reset in `finally`.
- MINOR — absolute-path zip entries (`/data/...`) had zero test coverage
  for the rejection branch. Fix:
  `absoluteZipEntryPathsAreRejectedAsUnsafe`.
- MINOR — `kotlin_string` in both generators passed `$` through, so a
  manifest edit containing `$` would render uncompilable Kotlin while the
  `--check` gate stayed green. Fix: escape after JSON encoding
  (`json.dumps(...).replace("$", "\\$")`; verified byte-identical output —
  both gates still exit 0). NB: the reviewer's suggested
  replace-before-dumps ordering is wrong (json doubles the backslash) —
  caught by testing the actual output.
Builds/tests: kotlin=0 errors, unit-tests=0 failures (registry 31/31,
resolution 13/13, whole suite green), generator-check=0 (both)
Ideas contributed: 17 → 2 [done], 15 open (ledger above)
Notes: no external-scope sightings reported; Reviewer 4 (community lane)
returned fully CLEAN. Baseline before fixes was also green (tests + both
gates), so all results are attributable to the round's changes only.

## Round 2 — 2026-08-22

Reviewers: 6/6 returned (same six angles, fresh agents)
Findings: 6 (major 2, minor 4) — Reviewers 1 (validator), 3 (resolution),
and 4 (community) fully CLEAN; all seven Round 1 fixes re-verified intact
by every reviewer.
Fixes (all verified in source before fixing; suite re-run green):
- MAJOR — corrupt registry bricked the user lane with an impossible
  instruction: `list()` throws (no rows, no Delete buttons) and `import()`
  hits the same throw at its reconcile prelude, so "delete and re-import
  the driver" was unactionable short of wiping app data. Fix:
  `UserVulkanDriverRegistry.reset()` (locked wipe of registry.json, payload
  dirs, staging/temp leftovers; keeps the crash-guard session record and
  lock file), a "Reset imported drivers" card in Settings shown exactly
  when the list read fails (with selection fallback to Auto), and the
  corrupt-registry reason now points at it. Test:
  `resetClearsAnUnreadableRegistryForAFreshStart`.
- MAJOR — the reconcile test never pinned that REGISTERED drivers' payload
  dirs are spared: dropping the `knownSlugs` check would pass the whole
  suite while deleting every driver per import. Fix:
  `reconcileSparesThePayloadDirectoriesOfRegisteredDrivers`.
- MINOR — `remove()` destroyed the payload before publishing the deletion;
  a torn remove left a never-self-healed ghost entry (and `import()`'s
  rollback could manufacture the same ghost when atomicWrite failed after
  its rename). Fix: remove() now saves the registry first (a torn remove
  leaves at most an orphan dir, which reconcile reclaims), and the import
  rollback only deletes the payload when the entry verifiably never
  landed (`readRegistry()` re-check, unreadable ⇒ keep).
- MINOR — "Delete failed: ${it.message}" could render "Delete failed:
  null". Fix: class-name fallback like every sibling path.
- MINOR — the SAF import had no stall escape: a cloud-provider stream that
  never progresses would keep "Importing driver…" and the disabled button
  forever. Fix: a daemon watchdog closes the input after a progress-free
  minute and the import surfaces the exact
  "import stalled while reading the selected document; pick the file
  again" reason.
- MINOR — the "Importing driver…" status was saveable but the in-progress
  flag was not: after process death mid-import the restored screen showed
  an active-import line next to an enabled button. Fix: the notice became
  `IMPORT_IN_PROGRESS_NOTICE` (presentation constant) and is cleared on
  first composition when no import is running.
Builds/tests: kotlin=0 errors, unit-tests=0 failures (registry 33/33,
resolution 13/13, whole suite green), generator-check=0 (both)
Ideas contributed: 18 → 1 [done], 17 open (ledger above)
Notes: no external-scope sightings. One compile fix during the round
(three-arg java.io.File in a test). Reviewer 2's ghost-entry finding and
Reviewer 5's brick finding were two faces of the same torn-state class —
both fixed with publication-before-destruction ordering.

## Round 3 — 2026-08-22

Reviewers: 6/6 returned (same six angles, fresh agents)
Findings: 9 (important 4, minor/low 5) — Reviewer 2 (registry) fully
CLEAN; all Rounds 1–2 fixes re-verified intact by every reviewer.
Fixes (all verified in source before fixing; suite re-run green):
- IMPORTANT — `reset()` was the one registry mutation that was not
  power-loss durable (plain deletes could leave ghost entries or silently
  undo the reset on a torn sweep). Fix: durably publish the empty registry
  via `saveRegistry(emptyList())` BEFORE destroying payloads; a torn reset
  now leaves at most orphaned directories, which reconcile reclaims.
- IMPORTANT — the delete/reset selection fallbacks guarded on a stale
  snapshot read BEFORE the slow IO, then wrote unconditionally inside the
  `settings.update` transform — a selection made while a 256 MB payload
  deletion was still running got clobbered to Auto. Fix: both fallbacks now
  re-evaluate the guard on the live snapshot inside the transform
  (crash-guard revert pattern).
- IMPORTANT — the stale community "Downloading … N / M MB" status survived
  process death (the R2 restore-clear only covered the SAF notice). Fix:
  `UserVulkanDriverPresentation.isTransientInProgressNotice` generalizes
  the restore-clear to both transient lines; final notices are kept.
- IMPORTANT — the import rollback's publication guard had zero test
  coverage. Fix: `aFailedRegistrySaveAfterThePayloadMoveReclaimsTheStagedPayload`
  (registry.json as a directory forces the save to fail only after the
  payload move; asserts the rollback reclaims the payload).
- MINOR — the reset success notice claimed "the selection is Auto again"
  even when the selection was a packaged driver. Fix:
  `resetNotice(selectionWasUserDriver)` says the fallback line only when it
  applied (test-pinned).
- MINOR — `kotlin_string` passed json's `\f` through, which is not a valid
  Kotlin escape: a form-feed-bearing manifest would pass `--check` and
  break the Kotlin build opaquely. Fix: a left-to-right escape scanner in
  both generators walks json escapes as units (`\f` → `\u000C`, `$` →
  `\$`), keeping literal-backslash sequences intact — a plain replace
  would corrupt a literal `\f` in a value; verified against adversarial
  inputs, both gates still byte-identical.
- MINOR — toggling "Allow imported drivers" off/on mid-download disposed
  the `communityDownloadId` guard state while the screen-scope download
  coroutine kept running, enabling a second concurrent download. Fix: the
  guard state is hoisted outside the lane-toggle conditional.
- LOW — `IMPORT_IN_PROGRESS_NOTICE` was the only unpinned presentation
  constant. Fix: `importInProgressNoticeIsExactAndOnlyTransientLinesMatchTheRestoreClear`.
- LOW — the corrupt-registry test accepted the pre-R2 wording. Fix: it now
  also pins `'Reset imported drivers'`.
Builds/tests: kotlin=0 errors, unit-tests=0 failures (whole suite green),
generator-check=0 (both)
Ideas contributed: 17 → 0 done, 17 open (ledger above)
Notes: no external-scope sightings. Findings continued the
converging pattern (smaller, more peripheral each round).

## Round 4 — 2026-08-22

Reviewers: 6/6 returned (same six angles, fresh agents)
Findings: 6 reported → 5 distinct (all important) — Reviewers 1
(validator), 2 (registry), and 3 (resolution) fully CLEAN; all Rounds 1–3
fixes re-verified intact by every reviewer.
Fixes (all verified in source before fixing; suite re-run green):
- IMPORTANT — the R3 state hoist was incomplete: only the download guard
  moved out of the lane-toggle conditional, so toggling the lane off and
  back on mid-import/mid-download still orphaned the coroutines' writes —
  the final notice (including exact I8 rejection reasons) was silently
  dropped and the import button re-enabled under a running copy. Fix: the
  section's whole operation state (status, refresh counter, delete dialog,
  import-in-progress, unreadable flag, transient-clear effect) now lives
  above the conditional with the download guard.
- IMPORTANT — the SAF import and community download lanes guarded only
  against themselves: run concurrently they shared one status line and the
  last writer hid the other's final notice (an I8 loss). Fix: cross-guarded
  buttons (Import disabled while a download runs and vice versa) plus the
  dialog row's synchronous frame-gap guard checks both lanes.
- IMPORTANT — the escape scanner passed U+0085/U+2028/U+2029 through raw;
  Kotlin counts them as line terminators, so such a manifest string would
  generate an unterminated-string compile error after passing the gate.
  Fix: the scanner emits `\uXXXX` escapes for them (both generators).
- IMPORTANT — generator/Kotlin blankness mismatch: Python `.strip()` accepts
  U+001C–U+001F, which Kotlin's `isBlank` treats as blank — a manifest
  field of only those chars passed generation and the gate, then crashed
  the app at class init. Fix: `kotlin_nonblank` helper mirrors Kotlin's
  whitespace set in both generators (all non-blank checks migrated).
- IMPORTANT — the durable reset's empty-registry publication was unpinned
  (a regression to delete-then-sweep passed the suite). Fix: the reset test
  now asserts registry.json exists with schema + zero drivers after reset.
Builds/tests: kotlin=0 errors, unit-tests=0 failures (whole suite green),
generator-check=0 (both)
Ideas contributed: 17 → 0 done, 17 open (ledger above)
Notes: no external-scope sightings. Two of the five findings were the
residual incompleteness of Round 3's own fix — the hoist class is now
fully closed (all coroutine-captured state survives the lane toggle).

## Round 5 — 2026-08-22

Reviewers: 6/6 returned (same six angles, fresh agents)
Findings: 5 (all important) — Reviewers 2 (registry), 3 (resolution),
and 4 (community) fully CLEAN; all Rounds 1–4 fixes re-verified intact
by every reviewer.
Fixes (all verified in source before fixing; suite re-run green):
- IMPORTANT — directory-marked zip entries skipped the capped drain:
  `isDirectory` is name-based, so a hostile "bomb/"-named entry with a
  huge DEFLATED payload was inflated uncapped by the next `nextEntry()`
  in both passes — the documented zip-bomb defense never triggered for
  it. Fix: classification is skipped for directory records but the drain
  (with the cumulative cap) now runs for every entry. Test:
  `directoryEntriesAreDrainedAgainstTheInflatedCap`.
- IMPORTANT — the Round 4 state hoist stopped one boundary short: the
  renderer conditional (`dxvk || AUTO`) also encloses the section, so
  switching renderer mid-import/download dropped the final notice and
  re-enabled the buttons while the op ran. Fix: the seven-state block +
  restore-clear effect moved above the renderer conditional. The
  leave-Settings variant is recorded as Accepted Caveat 19 (ViewModel
  idea tracked).
- IMPORTANT — the downloader's redirect-loop cap had no test (existing
  redirect tests all fail on the first hop). Fix:
  `redirectLoopsTerminateAtTheCapWithTheExactReason` with a TLS MockWebServer
  (every followed hop must be https, so the cap is unreachable from plain
  HTTP mocks); added test-only `okhttp-tls` (same 5.3.0 version) to the
  catalog.
- IMPORTANT — the ELF prefix fail-closed branch (e_phoff past the 4 MiB
  inspectable prefix) was unpinned. Fix:
  `programHeadersPastTheLoadedPrefixFailClosed`.
- IMPORTANT — the blank display-name fallback (SAF providers returning
  blank DISPLAY_NAME columns) was the only guard for an I8 degradation
  and unpinned. Fix: `blankDisplayNameFallsBackToTheImportedDriverLabel`.
Builds/tests: kotlin=0 errors, unit-tests=0 failures (whole suite green),
generator-check=0 (both)
Ideas contributed: 18 → 0 done, 18 open (ledger above)
Notes: no external-scope sightings. The findings are converging on
edge-of-edge cases (the reviewer's own confidence bars note this); the
caveats registry was extended to 19 entries to formalize the prompt-level
list the rounds have accumulated.

## Round 6 — 2026-08-22

Reviewers: 6/6 returned (same six angles, fresh agents)
Findings: 1 (important) — Reviewers 1 (validator), 2 (registry),
3 (resolution), 5 (UX), and 6 (tests) fully CLEAN; all Rounds 1–5 fixes
re-verified intact by every reviewer.
Fixes:
- IMPORTANT — the catalog generator enforced neither of two Kotlin-side
  invariants: a blank `version` (kotlin_nonblank had been applied only to
  the display fields) or an asset basename outside VulkanDriverPackage's
  `FILE_NAME` shape `[A-Za-z0-9_.-]{3,96}` — such a catalog edit passed
  generation and the gate, then crashed the app at `VulkanDriverCatalog`
  class-init. Fix: `load_catalog` now rejects both with exact messages;
  verified by fault injection (blank version rejected, schema restored,
  gate re-green).
Builds/tests: kotlin=0 errors, unit-tests=0 failures (whole suite green),
generator-check=0 (both)
Ideas contributed: 18 → 0 done, 2 deduped onto known ideas, 16 new open
Notes: no external-scope sightings. First single-finding round; the
convergence bar (two consecutive fully-clean rounds) requires Rounds 7
and 8 if Round 7 is clean.

## Round 7 — 2026-08-22

Reviewers: 6/6 returned (same six angles, fresh agents)
Findings: 3 (critical 1, important 2) — Reviewers 2 (registry),
3 (resolution), 5 (UX), and 6 (tests) fully CLEAN; all Rounds 1–6 fixes
re-verified intact by every reviewer.
Fixes (all verified in source before fixing; suite re-run green):
- CRITICAL — Android's platform org.json coerces a JSON null to the
  literal string "null" in `optString` (the desktop test artifact returns
  "" instead), and `readRegistry` read the two optional fields
  (`vulkanApiVersion`, `quarantineReason`) with a bare
  `optString(...).takeIf { isNotBlank }`. On device, the nulls that
  `saveRegistry` writes for every unquarantined driver came back as the
  string "null", tripping the model invariant — every subsequent read
  threw, bricking the whole opt-in lane (list, import, remove, crash-guard
  update, and the launch seam) while the entire JVM suite stayed green.
  Fix: `optionalRegistryString` reads optional fields through
  has/isNull/"null"-literal guards (the pattern the codebase already uses
  elsewhere for this divergence). Test:
  `androidNullCoercionCannotFakeAQuarantineReason` (both the stored
  JSON-null form and the coerced string form).
- IMPORTANT — the catalog generator allowed `..` substrings in asset
  paths (Kotlin's init rejects `".." !in libraryAsset` as a substring of
  the whole path, but the generator only checked path segments and the
  FILE_NAME shape, which permits consecutive dots) → startup
  ExceptionInInitializerError. Fix: substring check mirrors Kotlin's.
- IMPORTANT — the catalog generator only checked the `default` id's
  membership, not its binding to turnip-26.1.0 that Kotlin's
  `check(DEFAULT_ID == TURNIP_26_1)` hard-requires; flipping the default
  to the system bridge passed generation and crashed at startup on lanes
  without the arm64-full closure validation. Fix: exact binding check.
Builds/tests: kotlin=0 errors, unit-tests=0 failures (registry 37/37,
whole suite green), generator-check=0 (both)
Ideas contributed: 18 → 0 done, 4 deduped, 14 new open
Notes: no external-scope sightings. The critical find validates the
round-robin's premise: six prior rounds of static review missed a
platform-divergence bug invisible to the JVM suite; convergence chase
continues (Rounds 8 and 9 needed if Round 8 is clean).

## Round 8 — 2026-08-22

Reviewers: 6/6 returned (same six angles, fresh agents)
Findings: 2 (important) — Reviewers 2 (registry), 3 (resolution),
5 (UX), and 6 (tests) fully CLEAN; all Rounds 1–7 fixes re-verified
intact by every reviewer.
Fixes (all verified in source before fixing; suite re-run green):
- IMPORTANT — the Round 7 null-coercion class recurred on the validator's
  UNTRUSTED-INPUT side: `validateIcd`/`validateMeta` read pack-supplied
  JSON with raw `optString`, so on device `{"name":null}` produced the
  literal label "null", `{"library_path":null}` flipped the gate verdict
  (rejected on desktop, accepted on device), and a null `api_version` was
  baked as the string "null" into the rootfs ICD by `icdForRootfs` (a
  different generation-identity digest per platform). Fix: a shared
  `UserVulkanDriverValidator.optionalString` guard (has/isNull/"null"
  literal) applied to library_path/api_version/name/driverVersion and the
  rootfs rewrite. Tests:
  `androidNullCoercionCannotAlterValidationVerdicts` (validator) and
  `icdForRootfsOmitsACoercedNullApiVersion` (resolution).
- IMPORTANT — the catalog generator's `PurePosixPath` parent check
  collapsed `//` and `/./` segments while Kotlin's init checks the literal
  `startsWith` prefix — such an asset edit passed generation and crashed
  at app startup. Fix: the raw-prefix check replaces the normalized
  parent comparison (subsumed by the `..` + FILE_NAME checks).
Builds/tests: kotlin=0 errors, unit-tests=0 failures (validator 17/17,
registry 37/37, resolution 14/14, whole suite green), generator-check=0
(both)
Ideas contributed: 18 → 1 done, 6 deduped, 11 new open
Notes: no external-scope sightings. Both findings were residual instances
of classes earlier rounds introduced guards for — the null-coercion fence
now covers every untrusted-JSON read in the lane, and the generator's
path validation is fully literal.

## Round 9 — 2026-08-22

Reviewers: 6/6 returned (same six angles, fresh agents)
Findings: **CLEAN — 0 findings across all six reviewers, every category**
(the first fully-clean round; clean streak 1/2)
Fixes: none required
Builds/tests: unchanged from Round 8's green state (compile 0 errors,
unit tests 0 failures, both `--check` gates exit 0)
Ideas contributed: 18 → 0 done, 8 deduped, 10 new open
Notes: no external-scope sightings. Every reviewer re-verified the full
Rounds 1–8 fix set intact. One more clean round converges the loop.

## Round 10 — 2026-08-22

Reviewers: 6/6 returned (same six angles, fresh agents)
Findings: **CLEAN — 0 findings across all six reviewers, every category**
(second consecutive clean round; **clean streak 2/2 — CONVERGED**)
Fixes: none required
Builds/tests: unchanged from Round 8's green state, re-confirmed this
round (compile 0 errors, unit tests 0 failures, both `--check` gates
exit 0)
Ideas contributed: 18 → 0 done, 6 deduped, 12 new open
Notes: no external-scope sightings. Every reviewer re-verified the full
Rounds 1–8 fix set intact for the second consecutive round.

## Convergence summary (Rounds 1–10)

- **10 rounds, 60 reviewer passes, 40 distinct defects fixed** (1
  critical, ~20 major/important, ~19 minor), each verified in source
  before fixing and each behavioral fix test-pinned.
- The critical find (Round 7) — Android's org.json coercing JSON null to
  the literal string "null", bricking the whole lane on device while the
  JVM suite stayed green — validated the loop's premise; its class was
  then fenced everywhere (registry reads, validator inputs, rootfs ICD
  rewrite).
- Other defect classes closed along the way: a player-facing launch
  lockout with a false reason (R1), zip-bomb/flood/traversal hardening
  (R1/R5), torn-state publication ordering across import/remove/reset
  (R2/R3), UI state orphaning across lane/renderer toggles (R3/R4/R5),
  generator↔Kotlin validation parity including escape correctness
  (R1/R3/R4/R6/R7/R8), and a large body of exact-reason (I8) and test-pin
  gaps.
- Suite grew from 102 to ~150 lane tests; the caveats registry grew from
  7 to 19 accepted trade-offs; the ideas ledger holds ~120 entries with
  the best promoted into the Part 3 research tracks.
- Standing mode from here: re-run one round after any lane change, with
  the Round 1–8 fix set as the permanent regression list.
