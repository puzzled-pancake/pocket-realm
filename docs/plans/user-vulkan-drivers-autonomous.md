# Pocket Realm — User-Imported Vulkan (Turnip) Drivers: Autonomous Overnight Plan

Written 2026-08-21 against `main @ f397b7a` (0.100.2-alpha).
Derived from an external model's implementation plan: **its goals are binding, its
implementation sketches are not.** Where a sketch conflicts with repo reality, repo
reality + the goals win. Log every deviation in `PLAN-LOG.md`.

This document is the complete brief for an autonomous overnight run with **no device
access**. Read it fully before starting. Do not ask anyone for input — follow the stop
conditions in §4 instead.

---

## 0. Mission (fixed)

Let users import and run their own Mesa Turnip Vulkan driver builds — the **USER**
lane — alongside the existing SYSTEM (Vortek bridge) and PACKAGED (Turnip 26.1.0)
options, with:

- hard import validation (ELF aarch64, **16 KB page alignment**, ICD JSON sanity),
- a crash guard that quarantines a bad user driver after 2 early deaths and
  auto-reverts to the safe default,
- honest UI and diagnostics (exact rejection/quarantine reasons, never a silent
  fallback),
- **zero behavior change for anyone who does not opt in** — the closed, pinned,
  fail-closed packaged catalog stays byte-identical.

Non-goals tonight: no new bundled drivers, no changes to VirGL/Gladio lanes, no
catalog/packaging changes, no on-device qualification (that becomes a checklist
artifact, §11).

### Critical re-grounding (read first)

The source plan assumed the picker and driver plumbing do not exist. They do:

- `schemas/vulkan-driver-catalog.json` — closed catalog, schema 2,
  `selection_policy: exact-request-fail-closed`, exactly two drivers
  (`system-vulkan-vortek-2.1`, `turnip-26.1.0`).
- `tools/generate_vulkan_driver_catalog.py` → generates
  `android/app/src/main/java/com/pocketrealm/client/GeneratedVulkanDriverCatalog.kt`
  and **hard-enforces the closed 2-id set**.
- `VulkanDriverCatalog.kt` — runtime model, availability/compatibility gates,
  fail-closed request gates, selection-schema migrations.
- `ClientRuntimeService.kt:604-623` — env injection for the `dxvk` renderer route
  via **`VK_ICD_FILENAMES`** (the source plan's `VK_DRIVER_FILES` is a loader-era
  name; the repo truth is `VK_ICD_FILENAMES` — use it).
- `SettingsScreen.kt:361-410` — the Settings → "Vulkan driver" picker already
  exists (Auto / System / Turnip chips).
- `HomeScreen.kt:641` — the home card already shows a driver chip.

So the real remaining work is the **USER lane** (registry, import, validation,
staging, injection, guard, UI, diagnostics) layered *around* the closed catalog
without loosening it. The source plan's SYSTEM/PACKAGED items are already shipped.

---

## 1. Operating doctrine

1. **Goals fixed, implementation adaptive.** Every phase below separates
   *Goal (fixed)* from *Sketch (adaptive)*. If a sketch fights the codebase,
   re-derive the mechanism — keep the goal and the invariants (§3).
2. **No device access.** No adb, no emulator, no `tools/capture_rp6.py`,
   `tools/capture_avd.py`, `tools/run_realm_test.py`, or anything device-bound.
   Verification = JVM unit tests, Gradle builds, Python repo checks, git, and
   code-reviewer agents.
3. **Reviewer gates are mandatory.** No phase is done until its verification is
   green AND a code-reviewer subagent pass (Appendix A prompt) returns no open
   BLOCKERs. Fix, re-verify, re-commit.
4. **Never on red.** Every commit builds and the full unit suite passes at that
   commit. Never delete/weaken an existing test to get green — that is a blocker
   to report, not a fix (invariant I11).
5. **Budgets.** ≤ 3 attempts per blocking step, then record the blocker in
   `PLAN-LOG.md`, apply the phase's stated fallback, and move on if the next
   phase is independent.
6. **Log as you go.** Append to `PLAN-LOG.md` at repo root after every phase:
   what was done, deviations from sketches + why, reviewer verdicts, blockers.

---

## 2. Ground truth (verified 2026-08-21 — cheap to re-verify, do not re-derive)

| Fact | Anchor |
|---|---|
| Closed catalog JSON, schema 2, fail-closed policy, 2 drivers | `schemas/vulkan-driver-catalog.json` |
| Catalog codegen, enforces closed id set | `tools/generate_vulkan_driver_catalog.py`, `client/GeneratedVulkanDriverCatalog.kt` |
| Driver staging into APK assets | `tools/stage_renderer_packages.py` → `native/.build-arm64/wine-staging/assets/arm-translated/vulkan-drivers/<id>/` |
| Runtime model, kinds `SYSTEM`/`TURNIP`, fail-closed gates, selection schema 4 | `client/VulkanDriverCatalog.kt` (migrations precedent at :163-186, request gates :189-203) |
| Env injection, `dxvk` route only; TURNIP adds `MESA_*` + `TU_DEBUG` (RP6 variant) | `client/ClientRuntimeService.kt:604-623` (RP6 TU_DEBUG at :616-620) |
| Rootfs ICD provisioning + attestation (`usr/share/vulkan/icd.d/`) | `client/WineRuntimeStore.kt:1224-1242, 1631` |
| Settings persistence + selection-schema call sites | `storage/Settings.kt:151, 678` |
| Vulkan driver picker UI (FilterChips, testTags `vulkan-driver-*`); experimental-toggle Switch precedent | `ui/SettingsScreen.kt:361-410`, Switch pattern :314-321 |
| Home card driver chip | `ui/HomeScreen.kt:641` |
| Self-test marker `POCKET_SELFTEST_OK`; selfTest prepared flag | `client/ClientRuntimeService.kt:560`, `tools/build_selftest_pe.py` |
| Session completion / state transitions (x86 path shown; find ARM analog) | `client/ClientRuntimeService.kt:546-568`, `runArmBox64Session` :579+ |
| Unit test suite location (rich, JVM-only) | `android/app/src/test/java/com/pocketrealm/**` |
| Dev lane command | `README.md:116` → from `android/`: `./gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 -PpocketLane=full` (gradlew lives at `android/gradlew`) |
| Lanes/ABIs; ELF machine per ABI already a build concept | `android/app/build.gradle.kts:681-728` |
| Version | `android/app/build.gradle.kts:752-753` (`versionCode 5`, `versionName "0.100.2-alpha"`) |
| Repo/licensing checks | `tools/check_sources.py`, `tools/check_repo.py` |
| Diagnostics entry to trace to the bundle builder | `ui/SettingsScreen.kt:787-789` (`onDiagnostics`), `diagnostics/SecretRedactor.kt` |
| Catalog records `elf_machine: 183` (EM_AARCH64) for driver libs | `schemas/vulkan-driver-catalog.json` files[].elf_machine |
| Pinned upstream source for staged drivers | winlator app assets `ca3d735…` (see `tools/stage_renderer_packages.py`) |

---

## 3. Global invariants — the reviewer checks these every phase

- **I1 Fail-closed preserved.** Unknown/unavailable driver id ⇒ launch refusal with
  the exact reason, never substitution. User-lane lookup *extends* the resolution
  chain, only behind the opt-in toggle. `requireForRequest` semantics for catalog
  ids are unchanged.
- **I2 No silent fallback.** Every availability/quarantine/migration outcome has a
  precise human-readable string surfaced in UI or diagnostics (VirGL/Gladio
  precedent).
- **I3 Packaged surface byte-identical.** `git diff --stat native/ schemas/` stays
  empty all night. `generate_vulkan_driver_catalog.py`,
  `GeneratedVulkanDriverCatalog.kt`, and the packaged assets/ICDs are never edited
  or mutated at runtime — staging copies, never writes in place.
- **I4 User drivers are user data.** Never in APK assets,
  `schemas/sources.json`, or `THIRD_PARTY_NOTICES.md`. `check_sources.py` green
  with zero changes to licensing files.
- **I5 16 KB rule.** Every PT_LOAD `p_align < 0x4000` is rejected at import with
  the exact remediation message. This is the check most Turnip CI builds fail.
- **I6 Import safety.** SAF content copied to app-private storage immediately (no
  URI retention across reboot), atomic rename into place, slug regex forbids path
  traversal, sane size cap, cancelled/failed imports leave no partial registry
  entries.
- **I7 Lane orthogonality.** User drivers affect only the `dxvk` renderer route;
  `virgl`/`gladio`/`opengl` env maps unchanged (pinned by characterization tests).
- **I8 Exact reasons.** Every validator rejection, quarantine, and availability
  miss carries its specific string — reachable in UI copy.
- **I9 No device access** (§1.2).
- **I10 Tested behavior only.** No new runtime behavior without a JVM unit test on
  the x86_64 lane; full suite green before each commit.
- **I11 Don't game the gates.** Never weaken tests, widen the closed catalog, or
  hardcode values to force green.

---

## 4. Overnight protocol

- **Branch:** `feature/user-vulkan-drivers` off `main`. Work in
  `C:\pocket_realm_complete` (Windows, Git Bash).
- **Per phase:** implement → run phase verification → self-check §3 invariants →
  spawn code-reviewer subagent (Appendix A) → fix BLOCKERs → re-verify → commit
  (`Vulkan user drivers: <summary>` style) → append `PLAN-LOG.md` entry.
- **Stop-the-night conditions** (write `BLOCKERS.md`, stop cleanly, leave tree
  green): baseline suite red before any change; Gradle/JDK environment broken;
  pre-existing dirt under `native/` or `schemas/` you did not cause.
- **Deferred-by-design:** anything requiring a device goes to
  `DEVICE_QUALIFICATION_CHECKLIST.md` (§11), not into tonight's code paths.

---

## 5. Phase 0 — Baseline lock (~30 min)

**Goal (fixed):** prove the night starts green and pin today's behavior so the
end-of-night zero-change claim is mechanical, not aspirational.

**Steps:**
1. `git status` clean; record HEAD.
2. Full suite: `cd android && ./gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 -PpocketLane=full`.
3. `python tools/check_sources.py && python tools/check_repo.py`.
4. **Characterization tests (the night's safety net):** add JVM tests pinning —
   - the exact env var list emitted for `(SYSTEM, TURNIP) × (dxvk, virgl, gladio)`
     routes (pull the env construction into a testable pure function if it is not
     already; do not change emitted values),
   - resolution results for `auto` / each catalog id / an unknown id (unknown ⇒
     exact failure),
   - `resolvePersistedSelection` migration table (schema 0-4).

**Verify:** suite green including the new tests.
**Reviewer gate:** light — tests pin observable behavior (env maps, resolution
outcomes), not implementation details.
**Fallback:** none needed; red here = stop-the-night.

## 6. Phase B — Model, registry, validator (JVM-only)

**Goal (fixed):** a persisted user-driver registry and an import validator with
the exact rejection semantics of the source plan (P1.1-P1.4), fully unit-tested
offline.

**Sketch (adaptive):**
- New `client/UserVulkanDriverRegistry.kt` (+ model): 
  `UserVulkanDriver(id = "user-<slug>", label, libraryFileName, sha256,
  vulkanApiVersion?, addedAt, earlyCrashStreak = 0, quarantined = false,
  quarantineReason?)`. Storage `<filesDir>/drivers/<slug>/{driver.so, icd.json}`
  + `registry.json` (versioned schema, write-temp + atomic rename). Ids live in
  the `user-` namespace so they can never collide with catalog ids.
- New `client/UserVulkanDriverValidator.kt`:
  - reject: not ELF64 / `e_machine != 183` (match the catalog's `elf_machine`),
  - reject: any PT_LOAD `p_align < 0x4000`, message per the source plan
    ("…the RP6 kernel uses 16 KB pages and this build will crash on load. Use a
    build made with `-Wl,-z,max-page-size=0x4000`."),
  - reject: archive not containing exactly one `.so` (+ optional one ICD JSON);
    ICD JSON must parse with non-empty `ICD.library_path`,
  - warn-only: Vulkan API < 1.3 (DXVK 2.4.1 needs 1.3; 1.1 pairs with
    `box64-dxvk-1.10.3` — mirror the catalog's `minimum_vulkan_by_renderer`),
  - size cap (suggest 256 MiB) and an exact reason string for every rejection.

**Tests (fixed):** in-test synthetic ELF byte fixtures — truncated, wrong magic,
x86_64 machine, 4 KB-aligned PT_LOAD, valid 16 KB; registry round-trip +
restart; failed import leaves no partial entry; slug sanitization.

**Verify:** full suite green.
**Reviewer focus:** I5, I6, I8; malformed headers must produce the validator's
rejection, never an unhandled exception.

## 7. Phase C — Runtime integration (behind the opt-in toggle)

**Goal (fixed):** toggle ON ⇒ selected user driver is staged per-launch and
injected through the guest ICD mechanism; toggle OFF ⇒ byte-identical Phase-0
behavior (proved by the untouched characterization tests).

**Sketch (adaptive within guardrails):**
- Settings: `allowUserVulkanDrivers: Boolean = false`; follow the existing
  settings-schema/migration patterns in `storage/Settings.kt`.
- Resolution chain: catalog `find()` first (unchanged, closed); if the id is in
  the `user-` namespace and the toggle is ON → registry lookup; quarantined or
  missing ⇒ exact failure. Toggle OFF with a persisted user id ⇒ settings-level
  migration to `auto` **with a visible notice** (precedent:
  `resolvePersistedSelection`, `VulkanDriverCatalog.kt:163-186`) — never a
  launch-time silent swap (I1).
- Staging: copy `.so` + ICD into the session-writable area per launch (candidate:
  `<rootfs>/usr/share/vulkan/icd.d/user/<slug>/` or the session tmp already mapped
  into the guest), rewrite the ICD `library_path` to the staged absolute path,
  clean up on stop — reuse the 0.100.2 interrupted-stop recovery path. Never
  mutate packaged ICDs (I3).
- Env: emit `VK_ICD_FILENAMES=<staged icd>` (match `ClientRuntimeService.kt:610`);
  optionally also `VK_DRIVER_FILES` as an alias **for the USER lane only** —
  packaged lanes stay byte-identical.
- Optional `TU_DEBUG` passthrough for user drivers, default off (source P2.4).

**Tests:** staging into temp dirs (library_path rewrite, cleanup on stop,
interrupted-stop); env map for a user driver; toggle-off parity with Phase-0
goldens; virgl/gladio env unchanged; fail-closed matrix (unknown user id,
quarantined driver, registry file missing).

**Verify:** suite green; `git diff --stat native/ schemas/` empty.
**Reviewer focus:** I1, I3, I7; staging-vs-stop concurrency; guest path handling.
**Fallback:** if the resolution-chain integration exceeds budget, land registry +
validator + staging as internally-unused-but-tested code behind the (off) toggle
and record the integration point as a blocker.

## 8. Phase D — UI

**Goal (fixed):** honest picker, import, delete, home chip (source P3).

**Sketch:**
- "Vulkan driver" section (`SettingsScreen.kt:361+`): an opt-in switch ("Allow
  imported drivers", default off — the experimental-renderers Switch at :314-321
  is the pattern); when on, user-driver FilterChips (`testTag
  vulkan-driver-user-<slug>`) with label + Vulkan version + import date;
  availability text uses the exact validator/quarantine strings; disabled with
  reason on non-Adreno GPUs (mirror :383-409). Disable the picker while the realm
  runs (restart-required note), matching how other restart-required settings
  present this.
- Import: "Import driver (.so / .zip)" → `OpenDocument`; copy immediately;
  progress; success card shows the parsed Vulkan version; **every** Phase-B
  rejection string reachable in UI copy.
- Delete: confirm dialog; deleting the active driver resets selection (to `auto`
  or the packaged default — pick per settings semantics) and says so.
- Home chip (`HomeScreen.kt:641`): reflect the active user driver label.
- Honest capability gating (P3.5): Vulkan-less device ⇒ informative note, not a
  dead picker.

**Tests:** keep Compose thin; JVM-test list ordering, deletion-reset rule, label
formatting, quarantine copy (project style: logic in plain classes, testTags on
chips).
**Reviewer focus:** I2, I8; validator remains the single gate (no UI-only checks).

## 9. Phase E — Crash guard + diagnostics

**Goal (fixed):** two consecutive early deaths (<10 s uptime, FAILED) of a
user-driver session ⇒ quarantine that driver, persist the reason, reset the
selection, and explain ("quarantined after 2 early crashes"). SYSTEM/PACKAGED are
exempt (qualified lanes). Ships in the same release as the picker (source P4.1).

**Sketch:**
- Hook session completion in `ClientRuntimeService` (ARM-path analog of
  :554-568; `transition()` to FAILED/EXITED); uptime from session timestamps;
  streak persisted in `registry.json`; reset on clean exit or uptime ≥ 10 s.
- Implement the guard as a pure state machine + unit tests (crash,crash ⇒
  quarantined; crash,ok ⇒ reset; quarantined driver unselectable with reason).
- Smoke test (P4.3, offline form): if the selfTest PE path is already reachable
  for a driver switch, wire "self-test first" as an option; do not invent device
  flows tonight.
- Diagnostics (P4.2): trace `onDiagnostics` (`SettingsScreen.kt:787`) to the
  bundle builder; add registry.json (sanitized), active driver sha256, the
  emitted `VK_*` env recorded in the session record, last quarantine event. No
  paths outside app-private dirs (SecretRedactor precedent).

**Verify:** suite green.
**Reviewer focus:** quarantine can never brick launch (always recoverable to the
packaged default); registry writes atomic.

## 10. Phase F — Docs, licensing, version, final regression

- `docs/wiki/Choosing-a-Vulkan-Driver.md` (source P5.2): where Turnip builds come
  from (mesa GitLab CI artifacts), Adreno-only applicability, the 16 KB
  requirement, version guidance, how to report results. Link from the Settings
  section copy.
- Licensing posture (P5.1): user drivers are user data — no
  `schemas/sources.json` / `THIRD_PARTY_NOTICES.md` changes; `check_sources.py`
  green.
- Version (P5.3, first release): `versionName "0.101.0-alpha"`, `versionCode 6`
  (`build.gradle.kts:752`). Toggle default OFF = the feature lands dark; the GA
  flip is human/device-deferred.
- Final regression (P5.4, offline form): full unit suite;
  `:app:assembleDebug -PpocketAbi=x86_64 -PpocketLane=full`; both Python checks;
  `git diff --stat native/ schemas/` empty; Phase-0 characterization tests
  untouched and green.
- Write `DEVICE_QUALIFICATION_CHECKLIST.md` (§11) and the final report
  (§12).

---

## 11. Device-deferred qualification checklist (generate as an artifact)

Convert the source plan's device steps into a human checklist — the overnight run
writes the file, humans execute it later:

1. RP6 baseline: boot realm+game stock, capture the driver name/version from the
   DXVK log (source P0.4).
2. Guest env proof: with a user driver active, verify `VK_ICD_FILENAMES` in the
   guest `/proc/<wine-pid>/environ` points at the staged ICD (P2.1).
3. Packaging immutability on device: packaged driver SHA unchanged after N
   launches; staging dir empty after stop, including interrupted stops (P2.2).
4. Crash-guard field test: import an intentionally broken (but
   validation-passing) driver; confirm auto-revert after attempt 2 and the UI
   explanation (P4.1 done-when).
5. Driver-switch smoke: run the self-test PE or a short client boot after a
   switch before the real session (P4.3).
6. Community matrix: Mali / other-Snapshot / untested-device reports (source QA
   matrix rows — User column stays N/A for non-Adreno).
7. GA decision input for 0.102.0-alpha (P5.3).

## 12. Final deliverables (what the morning report contains)

- Branch `feature/user-vulkan-drivers` with per-phase commits, tree green.
- `PLAN-LOG.md` — per-phase record, deviations + reasons, reviewer verdicts.
- `DEVICE_QUALIFICATION_CHECKLIST.md`.
- `docs/wiki/Choosing-a-Vulkan-Driver.md`.
- Version at 0.101.0-alpha, toggle off, packaged surface untouched.
- Final message: phase status table, blocker list (if any), and the exact
  commands a human should run on-device next.

## 13. Risk register (carried from the source plan, updated for tonight)

- **R1** 4 KB-aligned `.so` ⇒ SIGBUS on the 16 KB kernel — import-time rejection
  (I5). *Mitigated in Phase B.*
- **R2** bad driver bricks sessions before the user can revert — crash guard +
  quarantine + auto-revert, same release as the picker. *Phase E.*
- **R3** user drivers erode the pinned/reproducible invariant — I3/I4 +
  characterization tests + reviewer greps. *All phases.*
- **R4** ICD `library_path` drift after staging — rewrite at every staging, tested.
  *Phase C.*
- **R5** DXVK needs Vulkan 1.3 (1.1 + compat package otherwise) — warn-only API
  check; packaged route stays the safe path. *Phase B.*
- **R6** SAF URI permission lapse after reboot — copy on import, never hold the
  URI (I6). *Phase B/D.*
- **R7** stale staging after interrupted launch — per-launch staging + stop
  cleanup riding the 0.100.2 interrupted-stop recovery. *Phase C.*
- **R8 (new)** autonomous drift overnight — budgets, never-on-red commits,
  PLAN-LOG, mandatory reviewer gates. *§1, §4.*
- **R9 (new)** accidentally loosening the closed catalog — generator script and
  closed set untouched (I3); reviewer verifies. *§3.*

---

## Appendix A — Reviewer-agent prompt template (per phase)

> Review the diff `<commit range>` on branch `feature/user-vulkan-drivers` in
> `C:\pocket_realm_complete` against (1) the phase goals in
> `docs/plans/user-vulkan-drivers-autonomous.md` §<phase>, and (2) the global
> invariants I1-I11 in its §3. Phase focus: <phase-specific list>. Report only
> concrete defects with file:line and severity BLOCKER / MAJOR / MINOR.
> Explicitly verify: fail-closed lookup is unchanged for non-user ids; no edits
> under `native/` or `schemas/`; no SAF URI retention; validator rejections carry
> exact strings; tests assert behavior, not implementation; no existing test was
> weakened or deleted.

## Appendix B — Verification command card

```
cd C:/pocket_realm_complete/android && ./gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 -PpocketLane=full
cd C:/pocket_realm_complete && python tools/check_sources.py && python tools/check_repo.py
git -C C:/pocket_realm_complete diff --stat native/ schemas/   # must stay empty
git -C C:/pocket_realm_complete status                          # clean per commit
```
