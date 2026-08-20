# Pocket Realm — Community Turnip Driver List: Implementation Plan

Written 2026-08-21 against `feature/user-vulkan-drivers @ 7d7b73a` (0.101.0-alpha,
unmerged). This feature builds directly on the USER driver lane shipped on that
branch (registry, validator, crash guard, picker UI).

**Pre-existing dirt (recorded, never staged):** at branch creation the tree
carried uncommitted work that is *not* part of this run — modified
`native/realm-runtime/CMakeLists.txt` and `tools/build_o09_realm_runtime.py`,
plus untracked `native/llm/` and `native/patches/playerbots/` (an in-process
llama.cpp playerbot backend). Every commit in this run stages explicit paths
only. The C1 gate below is measured against this Phase-0 snapshot, not against
an empty diff.

Provenance: derived from a same-day research pass (community driver ecosystem
audit, artifacts in `tmp/turnip-audit/` — untracked scratch). Its findings are
binding; its implementation sketches are not. Log every deviation in
`PLAN-LOG.md`.

---

## 0. Mission (fixed)

Give users a **curated, in-app list of known community Mesa Turnip builds** —
the Eden/Winlator ecosystem's packs — downloadable with **pinned digests** and
imported through the **existing USER lane gates unchanged**. Three tiers:

1. **meta.json support** — AdrenoTools-format zips (one `.so` + `meta.json`,
   the format Eden/K11MCH1/Vita3K distribute) import cleanly; today the
   metadata file is mistaken for an ICD manifest and rejected.
2. **Community list** — a reviewed, APK-bundled manifest of pinned entries;
   Settings offers "Community drivers…" → download (digest-verified, capped,
   allowlisted hosts) → `UserVulkanDriverRegistry.import()` → ordinary
   user-lane chip with crash guard and quarantine.
3. **Docs** — wiki "known-good community builds" table + on-device
   qualification checklist rows.

Non-goals: no runtime list fetching (list refresh = app update), no new
packaged/bundled drivers, no catalog changes, no Qualcomm-extracted blobs, no
`.wcp`/`.tzst` containers, no on-device automation in this run (the RP6 smoke
is a human checklist item).

### Critical grounding (verified 2026-08-21)

- The USER lane exists end-to-end: `client/UserVulkanDriverRegistry.kt`,
  `client/UserVulkanDriverValidator.kt`, `client/UserVulkanDriverResolution.kt`,
  `client/UserVulkanCrashGuard.kt`, picker UI, diagnostics, crash guard — 7
  commits `c290eb2..7d7b73a` on the parent branch.
- Empirical audit (`tmp/turnip-audit/audit.py` replicates our gates):
  K11MCH1 `Turnip_v26.0.0_R8.zip` = `meta.json` (291 B) +
  `vulkan.ad07xx.so` (17,159,433 B) — ELF64 aarch64, all PT_LOAD ≥ 0x4000;
  **rejected today only by the ICD check on `meta.json`**. K11MCH1
  `libvulkan_freedreno.so` v25.1 R2 (10,593,080 B, sha256 `fe222ea204d5ac312eae2955da4a7b78c087009f28403edceec73e4ed1ae64da`)
  — **passes the validator as-is**. Winlator official `turnip-26.0.3.tzst`:
  correct inner layout (`usr/lib/libvulkan_freedreno.so` + icd json), wrong
  container — excluded.
- Current-generation community Turnip builds are 16 KB-aligned (both sampled
  builds pass I5's rule).

---

## 1. Operating doctrine

1. **Goals fixed, implementation adaptive.** Keep the goal and the invariants
   (§3); re-derive mechanisms when sketches fight the codebase.
2. **Reviewer gates are mandatory.** No phase is done until verification is
   green AND a code-reviewer subagent pass (Appendix A) returns no open
   BLOCKERs. Fix, re-verify, re-commit.
3. **Never on red.** Every commit builds; the full unit suite passes at that
   commit. Never weaken/delete a test to get green (C9).
4. **Budgets.** ≤ 3 attempts per blocking step, then record the blocker in
   `PLAN-LOG.md`, apply the phase fallback, move on if independent.
5. **Log as you go.** Append to `PLAN-LOG.md` after every phase.
6. **Stage explicit paths only.** The pre-existing llama dirt never enters a
   commit of this run.

---

## 2. Ground truth (verified — cheap to re-verify, do not re-derive)

| Fact | Anchor |
|---|---|
| Import contract: bare `.so` or zip, exactly one `.so` + ≤1 json + nothing else | `client/UserVulkanDriverRegistry.kt:102-172` (`import`), `:211-274` (`unpackZip`) |
| Validator: ELF64/aarch64/16 KB rule, ICD sanity, `MAX_ICD_BYTES = 64 KiB`, 256 MiB cap | `client/UserVulkanDriverValidator.kt:13-15, 58-108, 111-128` |
| Registry entry stores the *library* sha256 (`sha256Of(library)`) | `client/UserVulkanDriverValidator.kt:174-185`, used at `UserVulkanDriverRegistry.kt:120` |
| Settings Vulkan section; everything user-lane gated on the toggle | `ui/SettingsScreen.kt:362-587` (toggle :415-427, `if (snap.allowUserVulkanDrivers)` :432) |
| SAF import: cacheDir temp, cap-during-copy, displayName = SAF name minus extension | `ui/SettingsScreen.kt:1365-1413` |
| Presentation strings live in a pure object, asserted in JVM tests | `ui/UserVulkanDriverPresentation.kt`, `ui/UserVulkanDriverPresentationTest.kt` |
| Self-updater: OkHttp, GitHub host allowlist, digest discipline, filesDir staging | `update/AppUpdateCoordinator.kt:39-68` (allowlist :46-53), `:244-250` (`isVerifiedUpdate`) |
| Addon download pattern: Content-Length preflight, cap-during-copy, progress | `addons/AddonRepository.kt:647-760` (`download` :693) |
| Shared digest utility | `fs/FileDigests.kt:16` |
| Determinate progress bar pattern | `ui/AddonsScreen.kt:646-664` |
| Catalog codegen + Gradle verify-task wiring to compile | `tools/generate_vulkan_driver_catalog.py`, `android/app/build.gradle.kts:995-1018` |
| `check_repo` blob allowlist covers `schemas/`; `check_sources` unrelated to a new schema | `tools/check_repo.py:53`, `tools/check_sources.py` |
| Only pytest contract touching files near this run greps `ClientRuntimeService.kt` / `ArmSessionEnvironment.kt` — **not modified here** | `tests/test_virgl_renderer_contract.py:60-78` |
| INTERNET permission held; HTTPS-only network security config | `AndroidManifest.xml:7`, `res/xml/network_security_config.xml` |
| Registry tests: temp-folder fixtures, `elf64(align)`, `zipOf`, restart re-instantiation | `client/UserVulkanDriverRegistryTest.kt:183+` |
| Version at branch point | `android/app/build.gradle.kts:752-753` (`versionCode 6`, `0.101.0-alpha`) |
| Dev lane command | from `android/`: `./gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 -PpocketLane=full` |
| Seed entry digests (audit-verified) | R8 zip 3,478,359 B; v25.1 R2 `.so` sha256 `fe222ea2…` (full value §6) |

---

## 3. Global invariants — the reviewer checks these every phase

- **C1 Packaged catalog untouched.** This run causes no diff under `native/`,
  in `schemas/vulkan-driver-catalog.json`, or in
  `client/GeneratedVulkanDriverCatalog.kt` beyond the Phase-0 snapshot
  recorded above. The new `schemas/community-vulkan-drivers.json` + its
  generated file are *additions*, never edits of the closed catalog.
- **C2 Pinned digests only.** Every downloadable entry carries reviewed
  `size` + `sha256`; both are verified before import; any mismatch, 404, or
  vanished asset ⇒ exact human reason, temp deleted, nothing imported, no
  fallback.
- **C3 Same gates.** Downloaded payloads traverse the identical
  `UserVulkanDriverRegistry.import()` path — zip caps, ELF aarch64, 16 KB
  rule, ICD sanity, registry lock. No download-specific bypass or relaxation.
- **C4 No third-party list at runtime.** The app never downloads a *list*;
  the manifest is compiled in and changes only through repo review.
- **C5 Allowlisted hosts, HTTPS only.** Downloads resolve only through the
  self-updater's GitHub release hosts, checked per redirect hop.
- **C6 meta.json is untrusted display metadata.** Length-capped strings only
  (label ≤ 64 chars); never paths, URLs, or commands; malformed ⇒ exact
  rejection.
- **C7 Mesa/MIT provenance only.** No Qualcomm-extracted blobs, no `.wcp`/
  `.tzst`; attribution (repo, release, license) in manifest, UI, and wiki.
- **C8 Opt-in unchanged.** Everything stays behind `allowUserVulkanDrivers`
  (default false); zero behavior change for non-opt-in users.
- **C9 Honest, tested strings.** Every new user-visible string is a pure
  function/const asserted in JVM tests; pytest contracts stay green; never
  weaken tests to force green (I11 carries over).

---

## 4. Protocol

- **Branch:** `feature/community-turnip-list` off
  `feature/user-vulkan-drivers @ 7d7b73a`. Work in `C:\pocket_realm_complete`
  (Windows, Git Bash).
- **Per phase:** implement → verify (Appendix B) → self-check §3 → code-reviewer
  subagent (Appendix A) → fix BLOCKERs → re-verify → commit
  (`Community Turnip list: <summary>` style, explicit paths) → `PLAN-LOG.md`.
- **Stop conditions** (write `BLOCKERS.md`, leave tree green): baseline suite
  red before any change; Gradle/JDK environment broken; a phase's reviewer
  gate unpassable after 3 fix rounds.

---

## 5. Phase 0 — Baseline lock

**Goal (fixed):** prove the run starts green and pin the dirt snapshot so the
end-of-run C1 claim is mechanical.

**Steps:**
1. Record HEAD, branch, and the pre-existing dirt snapshot (§ preamble).
2. Full suite green; `check_sources.py` + `check_repo.py` green.
3. Commit this plan document + `PLAN-LOG.md` run header.

**Verify:** Appendix B green. **Fallback:** red baseline = stop.

---

## 6. Phase B — meta.json import support (tier 1 of the mission)

**Goal (fixed):** AdrenoTools zips import cleanly; every outcome carries an
exact string; imports without `meta.json` behave byte-identically to today.

**Sketch (adaptive):**
- `UserVulkanDriverRegistry.unpackZip`: split the json bucket — basename
  `meta.json` (ASCII, case-insensitive) is *metadata*; any other single `.json`
  remains the ICD candidate. New layout rule: exactly one `.so`, ≤1 ICD json,
  ≤1 `meta.json`, nothing else. Old two-json rejection string stays for two
  ICD-shaped jsons.
- Meta parsing next to the other pure checks (single string owner, I8 style):
  reject unparseable JSON ("The AdrenoTools meta.json could not be parsed: …")
  and files > `MAX_ICD_BYTES`; harvest `name` (trim, take 64 — the import
  label; wins over the caller's displayName when non-blank) and
  `driverVersion` (strip one literal `"Vulkan "` prefix → the api_version
  string feeding the existing warn-only floor). `libraryName`/`minApi` are
  read but ignored (note in code comment why: name-agnostic validation).
- No ICD json in the zip ⇒ synthetic ICD (existing behavior, unchanged).

**Tests (fixed):** meta-only zip (exact label/slug from `name`, api version
harvested); meta + ICD zip; meta absent (existing tests untouched and green);
malformed meta; oversized meta; `name` > 64 chars; `libraryName` mismatch
ignored; still exactly-one-`.so`; `driverVersion` without the `Vulkan `
prefix. Empirical pin: re-run the audit logic against a fixture built like
the real R8 zip (meta.json + 16 KB synthetic ELF).

**Verify:** Appendix B green. **Reviewer focus:** C1, C3, C6, C9 — the zip
rule widened only by the meta.json carve-out; no ELF/ICD gate touched.

---

## 7. Phase C — Manifest, generator, runtime object (tier 2 core)

**Goal (fixed):** a reviewed `schemas/community-vulkan-drivers.json` whose
Kotlin projection is build-verified, seeded with the two audit-verified
entries.

**Sketch (adaptive):**
- Schema 1 entries:
  `{id, label, version, source{repo, release, url, size, sha256, librarySha256?, license, upstream}, display{summary, note}}`.
  The pinned `sha256` is the *download artifact* digest; `librarySha256`
  (optional, the inner `.so`) marks "already imported" in the UI and
  cross-checks the imported registry entry.
- `tools/generate_community_vulkan_drivers.py` mirrors
  `generate_vulkan_driver_catalog.py`: hard-fails on non-HTTPS or non-host-
  allowlisted `url`, bad hex digests, `size` > 64 MiB or ≤ 0, label > 64
  chars, duplicate ids, missing fields; emits
  `client/GeneratedCommunityVulkanDrivers.kt` including the manifest's own
  sha256; `--check` mode fails on drift.
- `client/CommunityVulkanDrivers.kt`: small runtime object (data class +
  validation `init` + `find`) around the generated table.
- `android/app/build.gradle.kts`: register `verifyGeneratedCommunityVulkanDrivers`
  (Exec, `python … --check`) and wire it to compile tasks exactly like
  :995-1018.
- Seed entries: `turnip-26.0.0-r8` (K11MCH1 AdrenoTools zip, 3,478,359 B) and
  `turnip-25.1.0-r2` (K11MCH1 bare `libvulkan_freedreno.so`, 10,593,080 B,
  sha256 `fe222ea204d5ac312eae2955da4a7b78c087009f28403edceec73e4ed1ae64da`);
  the R8 asset sha256 is computed from the already-downloaded audit artifact
  and recorded here in the PLAN-LOG when seeded.

**Tests:** schema-validation rejections (each rule), generated-object
round-trip (`find`, all(), constants), `--check` catches a doctored
generated file (python-level test in the tool or a JVM test asserting the
gradle task exists — keep to the existing pattern for the catalog, whatever
it is; if the catalog has no such test, a python `--check` self-test is
enough).

**Verify:** Appendix B green **plus** a debug compile of the app (the Gradle
task must not break compilation). **Reviewer focus:** C1, C2, C4, C7.

---

## 8. Phase D — Downloader + Settings UI (tier 2 UX)

**Goal (fixed):** one tap in a curated dialog ⇒ digest-verified download ⇒
the same import path as a manual SAF import; honest per-step status strings.

**Sketch (adaptive):**
- `client/CommunityVulkanDriverDownload.kt` (JVM-pure, OkHttp): entries
  download from `source.url` with the self-updater's GitHub host allowlist
  (shared constant — not a copy), manual per-hop redirect checks, HTTPS-only,
  `Content-Length` preflight vs `source.size`, cap-during-copy (entry size
  and a 64 MiB absolute ceiling), progress callback, stream to a `cacheDir`
  temp, then `length == size && FileDigests.sha256 == sha256`; any failure ⇒
  exact string, temp deleted, nothing imported. Test seams mirror
  `AppUpdateCoordinatorTest` (injectable hosts/client).
- `ui/SettingsScreen.kt` inside the toggle-gated block: "Community drivers…"
  `OutlinedButton` (testTag `community-vulkan-drivers`) after the import
  button (:492) → `AlertDialog` (delete-dialog pattern :532-585) listing
  entries (label, version, size MiB, source repo, license, "not qualified by
  Pocket Realm" note, "Imported" mark via `librarySha256` match) → tap =
  download with determinate `LinearProgressIndicator` on `Dispatchers.IO`,
  busy-flag re-entry guard (updates-card pattern) → import → existing
  `userVulkanStatus` line + `userDriverRefresh++`. Downloaded drivers are
  ordinary user chips (`vulkan-driver-user-<slug>`).
- New strings as pure members of `UserVulkanDriverPresentation`
  (`communityDriverRows`, download/import status strings, attribution and
  not-qualified notes) with exact-string JVM tests.

**Tests:** downloader — digest mismatch, size lie (header vs cap), mid-copy
cap, 404, redirect to non-allowlisted host, success (content + temp cleanup);
presentation — every new string; no Compose logic beyond wiring.

**Verify:** Appendix B green. **Reviewer focus:** C2, C3, C5, C8, C9.

---

## 9. Phase E — Docs + qualification (tier 3)

- `docs/wiki/Choosing-a-Vulkan-Driver.md`: new "Known-good community builds"
  section after "Where Turnip builds come from" — table (build, source,
  format, sha256, notes), the meta.json note (supported since this release),
  exclusions (Qualcomm blobs, `.wcp`/`.tzst`), MIT attribution, and a pointer
  to the in-app list.
- `DEVICE_QUALIFICATION_CHECKLIST.md`: new section — on-device RP6 pass:
  toggle on → Community drivers… → import `turnip-26.0.0-r8` → select →
  launch → verify chip/label + crash-guard expectations; record results.
- Release-notes line for 0.102.0-alpha (wherever the parent branch records
  them — check `docs/` for the 0.101.0 precedent).

**Verify:** Appendix B green; wiki README index still lists the page.

---

## 10. Phase F — Final reviewer pass, version, APK

- Full regression: Appendix B, plus
  `:app:assembleDebug -PpocketAbi=x86_64 -PpocketLane=full`.
- Fresh whole-feature reviewer round (Appendix A, all invariants).
- Version: `versionCode 7`, `versionName "0.102.0-alpha"`.
- Build the arm64 release APK (`-x lintVitalAnalyzeRelease -x lintVitalRelease`
  — known JDK-17 lint workaround) and hand the on-device smoke to the user
  via the Phase E checklist.
- `PLAN-LOG.md` run totals; final report.

---

## 11. Deliverables

- Branch `feature/community-turnip-list`, per-phase commits, tree green at
  each.
- meta.json import support; manifest + generator + Gradle gate; downloader +
  Community drivers UI; wiki + checklist updates; 0.102.0-alpha (7) APK.
- `PLAN-LOG.md` per-phase record with reviewer verdicts and deviations.

## 12. Risk register

- **R1** Upstream release asset vanishes or is replaced after review ⇒ C2
  fail-closed exact error; entry removed by a reviewed manifest edit. *Phase C/D.*
- **R2** Host-allowlist duplication drifts from the updater's ⇒ share the
  constant; equality test. *Phase D.*
- **R3** Re-importing an entry duplicates a driver ⇒ `librarySha256`
  "Imported" mark + registry id-suffix dedupe already prevents id collisions.
  *Phase D.*
- **R4** Gradle verify-task wiring breaks compilation ⇒ mirror :995-1018
  exactly; debug compile is a Phase C gate. *Phase C.*
- **R5** The meta.json carve-out loosens import safety ⇒ still exactly-one-
  `.so`; ELF/16 KB/ICD gates untouched; Phase B reviewer focus. *Phase B.*
- **R6** OkHttp in JVM tests ⇒ follow `AppUpdateCoordinatorTest` seams. *Phase D.*
- **R7** Autogenous drift ⇒ budgets, never-on-red, reviewer gates, PLAN-LOG. *§1.*

---

## Appendix A — Reviewer-agent prompt template (per phase)

> Review the diff `<commit range>` on branch `feature/community-turnip-list`
> in `C:\pocket_realm_complete` against (1) the phase goals in
> `docs/plans/community-turnip-list.md` §<phase>, and (2) the global
> invariants C1-C9 in its §3. Phase focus: <phase-specific list>. Report only
> concrete defects with file:line and severity BLOCKER / MAJOR / MINOR.
> Explicitly verify: no edits under `native/`, `schemas/vulkan-driver-catalog.json`,
> or `GeneratedVulkanDriverCatalog.kt`; no staging of the pre-existing llama
> dirt; downloaded payloads pass through `UserVulkanDriverRegistry.import()`
> with no bypass; digests verified before import; every new user-visible
> string is asserted in a JVM test; no existing test weakened or deleted.

## Appendix B — Verification command card

```
cd C:/pocket_realm_complete/android && ./gradlew :app:testDebugUnitTest -PpocketAbi=x86_64 -PpocketLane=full
cd C:/pocket_realm_complete && python tools/check_sources.py && python tools/check_repo.py
python tools/generate_community_vulkan_drivers.py --check     # from Phase C on
git -C C:/pocket_realm_complete diff --stat native/           # must equal the Phase-0 snapshot (llama dirt only)
git -C C:/pocket_realm_complete diff schemas/vulkan-driver-catalog.json   # must be empty
```
