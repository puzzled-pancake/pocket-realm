# PLAN-LOG — User-Imported Vulkan (Turnip) Drivers autonomous run

Plan: `docs/plans/user-vulkan-drivers-autonomous.md`. Branch
`feature/user-vulkan-drivers` off `main @ f397b7a` (0.100.2-alpha), started
2026-08-21. Machine: Windows, Git Bash, no device access.

---

## Phase 0 — Baseline lock

**Outcome: complete, green, committed.**

- `git status` clean (only the untracked plan doc under `docs/plans/`); branch
  `feature/user-vulkan-drivers` created off `f397b7a`.
- Baseline full suite:
  `./gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 -PpocketLane=full` →
  BUILD SUCCESSFUL in 1m 6s (only pre-existing warnings in unrelated test
  files). `tools/check_sources.py` and `tools/check_repo.py` both OK.
- Characterization net added:
  - Extracted the ARM Box64 session driver/renderer env construction from
    `ClientRuntimeService.runArmBox64Session` into the new pure JVM object
    `client/ArmSessionEnvironment.kt`. Emitted values are byte-identical
    (verified field-by-field by the reviewer against the HEAD blob). This is a
    *deviation in mechanism only* — the plan explicitly authorized "pull the env
    construction into a testable pure function".
  - New `ArmSessionEnvironmentTest` (10 tests): exact env lists for
    (SYSTEM, TURNIP) × (dxvk, virgl, opengl), the RP6 `TU_DEBUG` trim +
    case-insensitive variant, `VK_DRIVER_FILES` never emitted, fail-closed
    unknown-driver exception + exact message.
  - New `VulkanDriverResolutionCharacterizationTest` (3 tests): resolveId
    table (auto/null/catalog/unknown × vendor), exact fail-closed reason
    strings, full `resolvePersistedSelection` migration table for schema 0-4.
- Suite re-run with the new tests: BUILD SUCCESSFUL; both new classes 0
  failures (10 + 3 tests).
- Reviewer gate (Appendix A prompt): **no BLOCKER/MAJOR/MINOR findings.**
  Process note honored: this log entry written before the Phase 0 commit.
- `git diff --stat native/ schemas/` empty throughout.

**Deviation (logged per §1.5):** the pre-commit hook's Python contract
`tests/test_virgl_renderer_contract.py::test_arm_only_build_and_runtime_have_no_renderer_fallback`
greps `ClientRuntimeService.kt` for the virgl env literals. The authorized
Phase-0 extraction moved them to `ArmSessionEnvironment.kt`. The contract was
updated to grep the env-construction file for the same four literals AND to
additionally assert the service still wires
`ArmSessionEnvironment.driverEnv(`/`rendererEnv(` into the Box64 session —
equal-or-stronger, not weakened (I11 respected).

---

## Phase B — Model, registry, validator

**Outcome: complete, green, committed.**

- New `client/UserVulkanDriverRegistry.kt`: `UserVulkanDriver` model (ids in
  the `user-` namespace, quarantined/reason invariants), app-private registry
  (`registry.json` schema 1, temp + atomic rename), staged imports into
  `.incoming-<uuid>` renamed into `<slug>/{driver.so,icd.json}` only after
  validation (failed/cancelled imports leave no partial entry), zip + bare
  `.so` unpacking (exactly one `.so`, at most one ICD JSON, unsafe entry
  paths rejected, per-entry size caps), synthetic ICD when the archive has
  none, `remove`/`update` for the picker and the crash guard.
- New `client/UserVulkanDriverValidator.kt`: ELF64/aarch64/16 KB PT_LOAD
  validation (bounded 4 MiB prefix read, overflow-safe bounds checks), ICD
  JSON sanity (`ICD.library_path` non-empty), warn-only Vulkan < 1.3 note,
  256 MiB default cap, one owner for the exact size-cap string.
- Tests: 26 new (12 validator + 14 registry) — synthetic ELF fixtures
  (truncated / wrong magic / x86_64 machine / 4 KB / mixed aligns /
  overflowing e_phoff), registry round-trip + restart, failed-import
  cleanliness, slug sanitization incl. the trailing-hyphen edge, duplicate
  labels, remove/update, quarantine model invariants, unknown-schema
  fail-closed.
- Reviewer gate round 1: 2 MAJOR (overflowing `e_phoff` could throw
  ArrayIndexOutOfBounds instead of rejecting; `slugify` could emit a
  trailing hyphen that fails the id regex out of `import()`), 3 MINOR.
  Both MAJORs fixed with regression tests + reviewer re-verification pass:
  **no open BLOCKER/MAJOR; gate passed.** Residual nits also addressed
  post-gate (exact-bytes size message; 64 KiB staged-ICD read cap).
- Full suite green; `git diff --stat native/ schemas/` empty.

---

## Phase C — Runtime integration behind the opt-in toggle

**Outcome: complete, green, committed (after one reviewer round).**

- New `client/UserVulkanDriverResolution.kt` — the single seam: catalog ids
  delegate to the unchanged fail-closed catalog gates; `user-` ids resolve
  through the app-private registry only behind the toggle, with Adreno and
  quarantine gates and exact failure strings; `kindOf` for display/readiness;
  deterministic `icdForRootfs` rewrite; rootfs layout helpers.
- Env: `ArmSessionEnvironment.driverEnv` gained the user lane —
  `VK_ICD_FILENAMES` + `VK_DRIVER_FILES` alias (user lane only) + the Turnip
  MESA/TU_DEBUG set; packaged lanes byte-identical (Phase-0 pins green).
- Wiring: `ArmRendererAuto` passes user ids through; `ClientRuntimeContract`
  armRendererBuildId + kind-based DXVK log attestation;
  `ClientRuntimeService.preparePrefix` (toggle flag from the request JSON,
  seam gate, env, readiness) ; `WineRuntimeStore` (user-aware prepare/paths,
  generation identity fields, `installUserArmGraphics` staging into the
  shared rootfs with packaged-file retirement + digest verification, attest
  branch); `DurableFiles.atomicCopy`; `Settings.allowUserVulkanDrivers`
  (default false) with the disabled-lane reset-to-Auto rule in both
  `update()` and `toSnapshot` + visible notice; supervisor preflight/launch
  gates + request flag; display host/integrated display registry gate.
- **Reviewer round 1: 6 BLOCKERs + 1 MAJOR** — all in ARM-only paths the JVM
  suite cannot reach (null driver id reaching `armRendererBuildId`/manifest
  blocks; `userRootfsIcdText` missing the `/rootfs` segment so identity and
  install digests could never match; `startClient`'s catalog lookup after
  prepare; a false "unknown driver" Settings notice for valid user
  selections). All fixed and re-verified by the same reviewer: **no open
  defects; gate passed.** Added `UserVulkanGenerationIdentityTest` (3)
  pinning the identity + guest-path/ICD-rewrite consistency (the B5 class).
- Tests: 19 new JVM tests (12 resolution + 4 settings rule + 3 identity);
  full suite green; Python 94 passed (8 documented deselects only);
  `git diff --stat native/ schemas/` empty.
- **Deviation (mechanism, goal intact):** the plan sketched per-launch
  staging into a session tmp + cleanup on stop. Repo reality: the packaged
  lane installs driver files into the *shared rootfs* at prepare time under
  the generation lease, replacing/retiring whatever driver was resident —
  so the user lane mirrors that proven mechanism (staging at prepare, stale
  retirement both directions). Interrupted-stop safety rides the existing
  0.100.2 drain/recovery paths unchanged; nothing new is cleaned at stop
  because nothing user-lane-specific outlives the next prepare. Guest-path
  on-device proof deferred to the checklist (§11.2).
- **Deviation (minor):** "disable the picker while the realm runs" — no
  other restart-required setting in this codebase disables itself while
  running; Phase D matches the existing presentation (static
  applies-on-next-launch note) instead of inventing new machinery.

---

## Phase D — UI

**Outcome: complete, green, committed (after one reviewer round).**

- New `ui/UserVulkanDriverPresentation.kt` (pure, tested): picker rows
  (newest-first, label + parsed Vulkan version, imported-today/date,
  selected/enabled, exact status strings), import-result notices (exact
  validator rejection strings verbatim), deletion notices (active-driver
  reset explanation), section copy. Canonical strings are owned by the
  seam/validator (`ADRENO_ONLY_REASON`, `quarantinedDriverReason`,
  `apiVersionWarning`) and delegated — no UI-side duplicates.
- `SettingsScreen`: "Allow imported drivers" Switch (default off), section
  note, non-Adreno informative note (picker stays visible, rows carry the
  exact Adreno-only reason), "Import driver (.so / .zip)" SAF import
  (OpenDocument; copy-immediate with a bounded 64 KiB-chunk 256 MiB cap;
  URI never retained; cache file deleted in finally; any import throw
  becomes a status line, never a crash), user FilterChips
  (`vulkan-driver-user-<slug>`), per-row Delete with confirm dialog that
  resets the selection to Auto when the active driver is deleted and says
  so, empty state, registry-read-failure status line.
- `HomeScreen` setup card: the dxvk chip resolves the user-driver label
  through a produceState/IO registry lookup behind the catalog lookup.
- Tests: `UserVulkanDriverPresentationTest` (8) + the delegation keeps the
  Phase-B exact-string pins meaningful. Full suite green; packaged surface
  clean; Python 94 passed (documented deselects only).
- **Reviewer round 1: no BLOCKERs; 1 MAJOR (import-path throws could crash
  the app instead of surfacing a line) + 4 MINORs (uncapped SAF staging,
  silent registry-read failures, canonical-string duplication, main-thread
  HomeScreen I/O).** All five fixed and verified by a fresh reviewer pass:
  **no open defects; gate passed.**
- Logged deviations: "disable the picker while the realm runs" is presented
  as the applies-on-next-launch section note (matches how every other
  restart-required setting in this codebase presents; no new machinery);
  the Vulkan-less-device honesty requirement is covered by the non-Adreno
  note (imported Turnip ICDs are Adreno-only and bring their own Vulkan, so
  no further Vulkan-less subcase exists for this lane).

---

## Phase E — Crash guard + diagnostics

**Outcome: complete, green, committed (single reviewer round, no BLOCKERs).**

- New `client/UserVulkanCrashGuard.kt` (pure): `isEarlyDeath` (<10 s FAILED,
  forced excluded), the outcome fold (streak reset only on a clean exit or
  surviving past the window; quarantine at 2 consecutive early deaths with
  the exact reason; quarantine sticky), full-facts overload where a forced
  stop inside the window is streak-NEUTRAL (hang-then-kill must not wipe an
  ongoing streak), and `sessionRecordJson` (driver identity/sha/version,
  renderer, emitted Vulkan env as NAMES only — never absolute paths, uptime,
  outcome, quarantine state).
- `ClientRuntimeService`: SessionRecord gains the monotonic launch timestamp
  + the emitted env names; both ARM terminal paths call
  `recordUserVulkanSessionOutcome` outside the session lock; the hook no-ops
  for non-user ids (SYSTEM/PACKAGED exempt), folds via the guard, atomically
  writes `drivers/session-record.json`, and on a fresh quarantine resets the
  persisted selection to Auto via a guarded runBlocking multi-process
  DataStore write — quarantine can never brick launch (seam still explains
  if a stale attempt is made).
- `SupportBundleExporter`: `drivers/registry.json` +
  `drivers/session-record.json` entries (path-bounded, redacted,
  verify-safe).
- Tests: `UserVulkanCrashGuardTest` (7). Reviewer round: no BLOCKER/MAJOR;
  1 MINOR (forced-stop-under-10s reset the streak) — fixed with the neutral
  semantics + matrix test, closure confirmed by the same reviewer.
- **Deviation (logged):** P4.3 self-test-first is not wired — the ARM lane
  authorizes only the build-5875 client, so the self-test PE route is
  unreachable for driver switches; deferred to checklist §11.5.

---
