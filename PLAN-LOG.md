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
