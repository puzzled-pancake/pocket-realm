# First-Boot Fixes Plan (v2 — revised after 7-agent review)

Review outcomes folded in: F1 auto-retry premise corrected (relaunch is
manual-only in code); Gladio re-pin list extended to the runtime attestation
constants; F3d schema-writer fix (Settings.kt:387 hardcodes 1 — would make
the migration re-fire and clobber user toggles) and value-changes gated on
never-configured state; F2 watchdog restart-URI mismatch fix; F4 input-profile
preservation, gxMultisample as CHOICE, per-renderer locks for GL lanes,
delete-only-if-stale-value cleanup trigger; F5 seed placement pinned; F6
storage/data-cost/permission-grant specifics.

Branch: `devibe/cleanup` (all work lands here; `main` untouched).
Date: 2026-08-17. Inputs: user first-boot report (16 items), seven research
reports (2026-08-17), device evidence in `.tmp/devibe/rp6_firstboot_crash/`
(logcat 09:25–10:14, dropbox tombstones, two WoW `Crash.txt` dumps, Wine
`last-session.json`).

## Hard constraints (every phase)

1. **Data preservation.** Nothing may require uninstalling or wiping
   `com.pocketrealm`. Updates install in place (`adb install -r` /
   PackageInstaller) with signature continuity. All persisted state
   (DataStore schemas, `registry.json`, client generations, MariaDB datadir,
   Wine prefixes, import journal) must survive an app update.
2. **Fail-closed boundaries stay fail-closed.** Vulkan/DXVK "auto" exists
   only in the DataStore/UI layer and resolves to an exact catalog id before
   any request. Prepare/launch identity checks (tweaks JSON equality, display
   identity, Config.wtf attestation) must keep passing — new derivations
   happen once, at the single decision point before `preparePrefix`.
3. **Repo hygiene gates.** `tools/check_repo.py`, `tools/check_sources.py`,
   detekt, and the documented pytest deselects must stay green. No PII, no
   binaries >1MB in-tree, no proprietary client data.
4. **No invented facts in the audit trail.** Every claim in docs must trace
   to code, logs, or user statement. (Added after the false "DXVK page-fault /
   re-select Legacy Gladio" entry in PROGRESS.md — see F0.)
5. Verification protocol: after each phase, 3 review agents check the diff
   against this plan; fix and re-verify until clean before the next phase.

---

## F0 — Audit correction (doc-only)

**Change.** PROGRESS.md:46-48 ("on the RP6 `wow.exe` page-faults under DXVK
2.4.1 and 1.10.3 … the pre-wipe qualified Legacy Gladio renderer must be
re-selected manually") is a user-verified false claim (an agent error that was
logged, then cited by another agent as a solution). Replace with a truthful
note: DXVK 2.4.1 + Turnip is healthy per Wine `last-session.json` (43 state
cache entries, working 1920×1080 swapchain, clean session); the actual
first-boot crash is the WoW.exe null-vcall ERROR #132 tracked in F1.

**Tests.** None (docs). Gate: check_repo clean.

---

## F1 — Stability: world-entry crash + adjacent hardening

### F1a Root-cause the ERROR #132 (evidence-driven)

Facts: both crashes are byte-identical — EIP `0x0070211C`, instruction
`FF 53 08` (`call [ebx+8]` with `EBX=0`: null-object virtual call) inside
WoW.exe build 5875, during first world entry after a fresh realm start
(T+92 s and T+129 s after `:world` start); the entry at T+4 min succeeded.
Stack residue: `Interface\AddOns\And…`, `DBG:AndroidPortEvents`,
`WTF\Account\<account>…`. Not LMK, not renderer-default related (H1
withdrawn). (v2: also "byte-identical" refined — same fault signature:
identical EIP/instruction/EBX; timestamps and heap addresses differ.)

Work items (host-side only; the user runs all device tests):
1. **Bisection tooling + procedure**: add a temporary, UI-reachable "disable
   addon modules" debug control (Settings → Diagnostics, or addon-side slash
   flags `/ap off bags`, `/ap off mover`, …) so the user can bisect
   Bags/FrameMover/ActionBars/Hud/Radial from the device without new builds.
   Write the exact test procedure (realm restart → immediate world entry,
   per configuration) into the device test checklist (F7).
2. **Symbolize**: map EIP `0x702111C` (RVA `0x30111C`) against a 1.12.1.5875
   address table derived from the pinned FrameXML/known-vanilla layout (no
   binary downloads into the repo; research notes go in `.tmp/`).
3. **Instrument**: ensure the client stderr tail + `Errors/` listing are
   surfaced in Diagnostics for any `:client` failure (they exist in
   `last-session.json`/session dirs; add a Diagnostics pointer if absent).

### F1b Supervisor/UX mitigations (regardless of root cause)

1. **Retry guidance, not auto-retry** (v2 correction: there is no auto-retry
   in the code today — `relaunchClient()` is reached only from the manual
   HomeScreen retry button and the RealmService binder endpoint; the plan
   must not "add backoff" to a mechanism that does not exist). Keep manual
   retry immediate. Add: when `CLIENT_FAILED` occurs while the realm was
   initialized recently (same app session, i.e. first-boot window), the
   retry affordance surfaces guidance copy — "The world was still preparing;
   wait a minute or two before entering, then retry." (Aligns with F3a;
   single string reused.) No new retry loop, no supervisor state, no test
   churn beyond the guidance string.
2. Home/Client UX: same first-boot hint logic as F3a drives a "world is
   preparing" hint on the Home screen while `RealmState.Starting` during a
   first-boot window.
3. **TZ propagation**: the ARM Wine env block
   (`ClientRuntimeService.kt:543-667`) does not set `TZ`; WoW crash files and
   `Errors/` filenames are stamped in UTC (12 h off NZST). Use
   `java.util.TimeZone.getDefault().id` and pass a **POSIX TZ string**
   (e.g. `NZST-12NZDT,M9.5.0,M4.1.0/3`) rather than an IANA name — the
   box64 rootfs has no verified `zoneinfo` tree, and glibc silently ignores
   unresolved IANA names (v2: reviewer C3). The env blob passes through the
   glibc loader verbatim (no allowlist rejects new vars).

### F1c Gladio GLX teardown crash (real, separate bug)

Aug 15 tombstones (old APK, Gladio lane): guest exit → X client kill →
`destroyGLXContext` → `SparseArray_free` frees a tag-truncated (corrupted)
pointer → MTE aborts the whole main app process. Frames:
`native/xserver-winlator/cpp/src/arrays.c:460-473` (`SparseArray_free`),
`cpp/gladiorenderer/src/gl_context.c:664-673`. Fix (v2, per reviewer
ownership audit):
1. **Root cause is share-group lifetime**: `GLClientState_destroy`
   (gl_client_state.h:79-112) frees the owner context's vertex/textures/
   buffers arrays while child contexts in the share list still alias them
   (gl_client_state.h:52-63); destroying the owner first leaves children
   with dangling pointers, and the later `destroyAllGLXContexts` on a child
   reads freed memory → corrupt `entries` → tag-truncated free. Fix the
   ownership (defer freeing shared arrays until the last sharer is
   destroyed — refcount or last-owner-frees).
2. Also fix the `GLXContext` struct leak: `destroyGLXContext` never frees
   the context struct itself (contrast the GLContext path at gl_context.c:577).
3. `SparseArray_free` null-safety is already idempotent for the entries
   array — treat hardening there as defense-in-depth only, not the fix.
4. Host test: wire a lifecycle test into `cpp/tests/CMakeLists.txt` (only
   `thread_pool_lifecycle_test` is registered today) exercising
   create-share-destroy orderings (host glibc cannot reproduce MTE tagging
   but can catch double-free/UAF under ASan if available).
5. **Re-pin checklist (v2 — three places, not one)**: rebuild via
   `tools/build_xserver_winlator.py --abi arm64-v8a`, then update in the
   SAME commit: (a) gradle digest pin `android/app/build.gradle.kts:382-384`
   (size/sha of `libgladiorenderer.so`); (b) `ArmClientRendererCatalog.kt:79-80`
   `GLADIO_SERVER_SHA256` — a runtime fail-closed attestation checked at
   prepare (`WineRuntimeStore.kt:666-678`) that would otherwise refuse every
   Gladio launch; (c) `ArmClientRendererCatalog.kt:78` `GLADIO_SERVER_BUILD_ID`
   (embeds the digest prefix), re-checked post-prepare against the generation
   manifest (`WineRuntimeStore.kt:1674-1685`) — existing prepared Gladio
   generations will fail the identity check until re-prepared (fail-closed
   by design; document in the release note). Update
   `WineRuntimeGenerationIdentityTest` pins accordingly.
6. Gate list (v2): the python source-contract tests
   `tests/test_gladio_presentation_dimensions.py` and
   `tests/test_gladio_fixed_function.py` read the edited C sources and run
   in pre-commit + CI — keep them passing.

### F1d `:client` foreground-service promotion

`ClientRuntimeService` is bound-only today (`onBind` at
`ClientRuntimeService.kt:460`, no `onStartCommand`), bound from the
`:supervisor` process with `BIND_AUTO_CREATE` (`AndroidRuntimeBackend.kt:1055`)
— so the process hosting box64+Wine+WoW.exe is cache-classified whenever the
UI is backgrounded (v2: the observed `adj 905` deaths are consistent with
this; phrase as inference, the provided logcat shows the classification only
at death). Work items:
1. Convert to also being a **started** service while a Wine session is live:
   RealmService (itself a `specialUse` FGS, manifest :50-58, with
   `FOREGROUND_SERVICE_SPECIAL_USE` already declared at :11) calls
   `startForegroundService` for `:client`; add `onStartCommand` →
   `startForeground` with `specialUse` (targetSdk 27 keeps requirements
   minimal; the device already defers-then-shows FGS notifications for this
   legacy-target app, per logcat). Working precedent:
   `ImportWorkerService.kt:51-57,116-117,162`.
2. **Stop on clean exit** (v2): the service currently self-kills only on
   owner loss (`ClientRuntimeService.kt:76-77, :449-450`); an FGS promotion
   needs an explicit `stopForeground/stopSelf` on clean client exit or a
   stale notification lingers.
3. This does not change process composition, only oom classification; stop
   paths that kill the process remove the FGS with it.

**F1 tests/gates**: supervisor retry tests (existing `DurableRuntimeSupervisor`
test patterns), detekt, unit suite; device: crash-bisection log captured;
`adb install -r` update of the RP6 build (data preserved).

**F1 verification round 1 (2026-08-17) outcome**: agents 1/2 CLEAN, agent 3
found one blocker — `/ap off hud` left Hud's file-scope event frame
active at world entry (chat restyle + XP bar), which would falsely
exonerate Hud in the bisection. Fixed by gating Hud.lua's OnEvent/OnUpdate
on `IsModuleEnabled("hud")`, and likewise `Radial:Toggle` (F12 could
lazily re-initialize a disabled radial). Also hardened:
`requestForegroundStop` now uses `stopService` (a stop-intent could
resurrect a freshly killed :client process). Deferred/remaining: (a) F1b.2
Home-screen "world is preparing" Starting-state hint lands with F3a (same
first-boot window logic); (b) the gladio GUEST-side `libGL.so.1` builds
from a separately pinned source tree with the same owner-frees-while-
children-alias pattern — not implicated by current tombstones, recorded as
remaining work; (c) docs/handoffs/graphics-selection.md:115 retains the
pre-fix build id as a historical handoff record (superseded by the
DECISIONS entry).

---

## F2 — Import UX

### F2a Auto-continue watchdog (no manual "resume")

Root cause of the perceived pause: the `:import` process is LMK-killed under
memory pressure; ActivityManager restarts it only after 32–290 s backoff, and
the journal stays in a busy phase showing a stale "worker not running" card.
Work items:
1. In `ClientScreen.kt` poller (`LaunchedEffect(importEpoch)`, :97-121): when
   `importPhaseBusy(phase) && !progress.workerPresent` and
   `progress.updatedAtMs` is stale >25 s, call `ImportWorkerService.start(
   context, journalSourceUri)` — rate-limited to ≥60 s between attempts, max 4
   attempts per epoch (in-Compose-state counter; resets if the UI process is
   itself killed, which the ≥60 s rate limit bounds — accepted), then fall
   back to the existing manual Resume affordance with an explanatory notice
   ("Worker was stopped by the system — tap Resume").
2. **Restart with the journal's own source URI** (v2 — reviewer finding):
   the current `persistedTree` derivation (`firstOrNull` over
   `persistedUriPermissions`, ClientScreen.kt:68-71) can pick a different
   tree than the journal's import when several persistable grants exist;
   `beginOrResume` then takes the mismatch branch and **fails the running
   import** (ImportJournal.kt:31-39) instead of resuming. Fix: expose the
   journal's `source_uri` in `readStatus` JSON and restart with exactly
   that; fall back to the manual notice on mismatch/unavailability.
3. Gate on `dataPreparationEnabled` (database lane never prepares data).
4. Never auto-restart from FAILED/CANCELLED; COMPLETE needs no action
   (inherent: `importPhaseBusy` excludes terminal phases).
5. Resume idempotence is already guaranteed (`ImportJournal.beginOrResume`,
   verified-file skip, published-generation fast path) — no importer changes.
   Double-start with an ActivityManager restart is harmless
   (ImportWorkerService.kt:52 busy gate + START_REDELIVER_INTENT).

### F2b Remove CPU % from the import card

Render: `ClientScreen.kt:324-329` (`CPU … • Memory …`) → keep Memory only;
in the second line (:330-334) remove only the `• N ms sample` fragment —
keep the process/thread counts (v2 scope clarification). Remove the
`formatImportCpu` import (:42). Full cleanup: drop `cpuPercent`/
`cpuSampleWindowMs` from `ImportProgressPresentation` (:60-61, :129-130)
**and the now-orphaned private `JSONObject.optionalDouble` helper**
(:294-295 — used only by the cpuPercent parse; leaving it is a guaranteed
detekt UnusedPrivateMember failure), worker JSON
(`ImportWorkerService.kt:204-205`), formatter (:222-223), and update
`ImportProgressPresentationTest` (:36-37 — both fixture lines, :48, :58,
:61). Keep the sampler's internal working/waiting state
(`ImportProcessMetrics.kt:63-66`) — that drives the "Working / Waiting"
label, not a percentage display. The movemapgen checkpoint line
("Checkpoint: … gen-cpu Ns", `DataPreparationStore.kt:176-185`) reports
CPU-seconds of the map generator, not device CPU%; keep it (it explains a
long-running stage).

### F2c Device label

Answer (user question): curated by design. `DeviceProfile.kt:26-29` maps
`Build.MODEL == "Retroid Pocket 6"` → "Snapdragon 8 Gen 2", actively cooled;
the dynamic fallback is `Build.SOC_MANUFACTURER + SOC_MODEL` → "Qualcomm
Kalama" (no Android API yields marketing names or cooling). Change: render
`Build.MODEL` alongside the SoC on the import screen
(`ClientScreen.kt:403-413`): "Retroid Pocket 6 · Snapdragon 8 Gen 2 · actively
cooled" — states the actual device as requested. Update
`ImportProgressPresentationTest` passthrough expectations.

### F2d Pre-import confirmation dialog

`ClientScreen.kt:73-92`: picker callback currently starts the import
immediately. Change: stash the picked uri in `pendingImport`; show a Material3
AlertDialog (precedent `SettingsScreen.kt:805-824`, v2 line correction) —
"This copies and verifies the game files, then builds maps, collision and
navmesh data for the server. Depending on the device this can take over 30
minutes. Keep the device plugged in and awake." Confirm performs: busy-check
(`importPhaseBusy` at confirm time), `takePersistableUriPermission`, start,
`importEpoch += 1`. Cancel/dismiss discards without taking the permission.
Shown only when `dataPreparationEnabled` (the database lane has no long
data-prep) and while no benchmark record exists yet (v2 wording: benchmarks
are a global list without a device model field — gate on "no benchmark
recorded yet", not "this device"). Same dialog path for
re-import-after-COMPLETE via the shared button. Process death between pick
and confirm surfaces as the existing `runCatching` failure notice (add a
code comment).

**F2 tests/gates**: importer/UI unit tests updated, detekt, manual device pass.

**F2 verification round 1 (2026-08-17) outcome**: agents found one blocker —
the ImportUiState state-holder refactor dropped the per-epoch reset of
`observedActiveRun`, reintroducing the de-vibe round-2 bug (the relaunched
poller's first polls observe the previous run's terminal phase and exit,
freezing the card for the new run). Fixed with `beginNewPollEpoch()` which
bumps the epoch AND resets `observedActiveRun` + the watchdog budget. Also
fixed: the stopped-worker notice now clears when the worker is observed
alive again; the plan-required process-death comment added. Accepted
simplification recorded: the F2d dialog is shown UNCONDITIONALLY rather
than gated on `dataPreparationEnabled` + "no benchmark recorded yet" — the
two plan gates conflicted with each other (a benchmarked device could never
re-confirm a re-import), and the dialog copy errs toward safety; on the
database lane the copy overstates what that lane builds (noted for the F7
release notes).

---

## F3 — First-run messaging and defaults

### F3a First-realm-start warning

Signal: `File(StorageRoots.get(context).databaseRoot, "initialized.json")`
absent (written only by `DatabaseEngine` after a fully verified bootstrap,
`DatabaseEngine.kt:59,196`). Hook: `HomeScreen.kt:186-198` — both
`startRealmOnly` and `startRealmAndGame` set a pending-start flag instead of
starting; AlertDialog (single "Start" + cancel) with truthful copy: "First
start creates the realm database and prepares the world (schema migrations,
bot characters, map data). This can take several minutes and happens only
once — later starts are much faster." Marker check at tap time on
`Dispatchers.IO`; LAN-join paths untouched. Instrumented tests that drive
`realm-primary-action`/`realm-start-all` get the dialog tagged and a
test-flag bypass (or dismiss step) documented in the test.

### F3b Setup description fix

`SettingsScreen.kt:755` replace with: "Setup covers three tools: importing
the WoW 1.12.1 game files and generating the server's world data, a report of
what this device's hardware can run, and diagnostics with logs for
investigating problems. Start here on a new install. Character backups and
restores live in the Realm data section below." (Single-string edit.)

### F3c Vulkan driver fresh-install default = auto

`VulkanDriverCatalog.resolvePersistedSelection`: the `requestedId == null`
branch currently materializes the concrete default (`turnip-26.1.0`); change
it to return `AUTO_ID` (keep the legacy schema migration and explicit-id
pass-through). `effectiveVulkanDriverId()` already resolves auto → vendor
default for every consumer, so display/launch behavior on the RP6 is
unchanged; new installs simply persist "auto" and follow the catalog if the
default or device set changes. Update the schema-migration tests that pin the
null path. DXVK stays a concrete persisted default (`box64-dxvk-2.4.1`) with
the existing auto-step-down at launch (already active whenever renderer =
auto, which is the default); relabel its UI group to explain the stepping.

### F3d Auto-enable client tweaks (widescreen)

One-time DataMigration in `PocketSettingsStore.create` (Settings.kt:142-149
pattern):
1. **Gate the value changes on never-configured state** (v2 — reviewer
   finding): enable `fovEnabled`, `quicklootEnabled`, `cameraSkipFixEnabled`,
   `maxCameraDistanceEnabled` (value stays the default 100) only when
   `client_tweaks_schema < 1` (no explicit user choices recorded yet); always
   bump `client_tweaks_schema` to 2 unconditionally so the migration never
   re-runs. This preserves the existing schema-1 installs' explicit choices
   (including this RP6's curated set) while fresh installs get the defaults.
2. **Mandatory writer fix (v2 — blocking finding)**: `Settings.update()`
   hardcodes `prefs[Keys.TWEAKS_SCHEMA] = 1` (Settings.kt:387). Introduce a
   `TWEAKS_SCHEMA_VERSION = 2` constant (pattern: the vulkan/renderer
   SELECTION_SCHEMA constants written at Settings.kt:335/:338) and write it
   in `update()`; otherwise every settings write regresses the stamp to 1
   and the migration re-fires, overriding explicit user OFF choices.
3. Widescreen test computed in-migration (v2 — no forward dependency on
   F4a): compose `ClientDisplayCapabilities.physicalLandscapeBounds(context)`
   + `ClientDisplayProfile.resolveFor(w, h)` (both public;
   `resolveVirtualDisplay` is private to WineRuntimeStore) and test the
   resolved virtual display aspect == 16:9. Thread the applicationContext
   into the migrations list (existing migrations are context-free; the
   factory receives context at `PocketSettingsStore.create(context)`).
   DisplayManager access is fine on the store's Dispatchers.IO scope.
4. `commonPreset()` stays as-is (manual button); HomeScreen's
   "Client tweaks on/Vanilla" label reads the persisted config — consistent
   automatically; tweak flags reach the patcher unchanged via `toFlags()`.

### F3e In-game first-launch defaults

Mechanism (researched, high confidence). **Scope (v2): applies to fresh
imports / fresh profiles — an already-imported install keeps its existing
Config.wtf and account SavedVariables (those keys are user-owned there and
are settable via the In-Game Settings screen); state this in the release
note.**
1. **Cvars → Config.wtf seed at import finalize**: extend `SAFE_CONFIG`
   (`ClientGenerationStore.kt:192-208`, mirrored in
   `tools/stage_o07_client.py:37-56`): add `autoSelfCast "1"`,
   `statusBarText "1"` (numeric status text — closest 1.12 equivalent),
   `UnitNameNPC "1"`, `UnitNamePlayerGuild "0"`, `UnitNamePlayerPVPTitle "0"`
   (guild titles / player titles off), `cameraSmoothStyle "0"` (Never), and
   change `MasterSoundEffects` from `"0"` to `"1"` (all sounds on). Align the
   Python mirror exactly (v2: it has drifted — it writes inert WotLK-era
   `Sound_Enable*` "0" lines at :48-51 that the Kotlin side never writes;
   drop them and match SAFE_CONFIG key-for-key).
2. **Uvars → one-time SavedVariables seed at first prepare**: in
   `WineRuntimeStore.enforceManagedSafeMode` next to the uvar-delivery block
   (:1992-2023, already inside `InGameSettingsEditLock`), when the account's
   SavedVariables file is absent, resolve the account from
   `UserAccountStore(context).loadProvisioned()` (the sole auto-login
   identity; skip the seed when absent — the file-absent guard retries at
   the next prepare) and write via `SavedVariablesCodec.assign`:
   `QUEST_FADING_DISABLE = "1"` (instant quest text),
   `SHOW_TARGET_OF_TARGET = "1"`, `NAMEPLATES_ON = 1` (enemy nameplates;
   number form), `SHOW_COMBAT_TEXT = "1"` (floating combat text),
   `SHOW_NEWBIE_TIPS = "0"` (detailed tooltips off), `AUTO_QUEST_WATCH = 1`
   (automatic quest tracking, number form).
3. **NAMEPLATES_ON provenance (v2)**: it is absent from
   `WowVanillaSettingsCatalog.kt`, so record its evidence base in an
   addendum to `docs/INGAME_SETTINGS_GROUND_TRUTH.md` (1.12.1
   Bindings.xml:514-524 sets `NAMEPLATES_ON = 1` for the nameplate keybind;
   `UIOptionsFrame.lua` registers it for save; number form per the binding
   handler; the pinned mirror lives in local `.tmp`, so cite the captured
   device SavedVariables instead — `NAMEPLATES_ON` persisted by the client
   on the RP6). Do not add a catalog row (avoids ID_ORDER_SHA256 churn).
4. Documented N/A: "Rotate Minimap" does not exist in 1.12.1 (TBC+ cvar) —
   no action; "Friendly Nameplates OFF" is already the stock behavior every
   session (1.12 client never persists `FRIENDNAMEPLATES_ON`).
5. Lockstep: `ClientBuild5875LoginTest` (audio-on must not contain
   `SET MasterSoundEffects "0"` — the SAFE_CONFIG flip to "1" satisfies it;
   :134-138), `WowGameSettingsDeliveryTest` (enforced counts unaffected),
   stage_o07 mirror, ground-truth doc. Round-trip safety: seeded keys are
   outside `ManagedConfigPolicy.enforcedKeys` (verified full list), so they
   become user-owned base lines the client rewrites with player values at
   exit (round-trip proven in-repo by the ground-truth capture).

**F3 tests/gates**: settings migration tests, catalog/policy unit tests,
mock-config tests, detekt.

**F3 verification round 1 (2026-08-17) outcome**: no blockers; three
concerns, all fixed. (1) The Kotlin SAFE_CONFIG write-site
`.replace("\\r\\n", "\r\n")` was a historical NO-OP (the raw string holds
backslash-r + newline, never backslash-r-backslash-n), so the app-written
Config.wtf carried junk trailing `\r` text on every line — replaced with a
correct `normalizeRawConfigLineEndings()` so fresh generations get true
CRLF, byte-matching the Python mirror. (2) The ground-truth addendum
misstated the NAMEPLATES_ON capture (nil when off, not a numeric scalar) —
corrected; the number form rests on the Bindings.xml handler alone.
(3) The F1b.2 "world is preparing" Starting-state hint now ships (derived
from the bootstrap seal marker inside the realm card — no signature change,
so the pre-existing detekt baseline entry keeps matching; the dead
firstStartInSession stub removed). Also fixed: dangling KDoc ordering next
to resolveVirtualDisplay. Recorded decisions: the DXVK group's existing
copy already explains auto-stepping (no relabel needed); the F3a dialog
carries no testTag yet (no instrumented test drives the realm buttons
today — the first such test adds it).

---

## F4 — Display modes and graphics unlock

### F4a 4:3 mode (1280×960)

1. `ClientDisplayProfile.kt`: add `CLASSIC_43("classic43", 1280, 960,
   gameMaximized = true)`; relax the 16:9 `require` (:55) to validate against
   an allowed aspect set {16:9, 4:3}; `resolveFor` (:71-81) must return fixed
   1280×960 on a 16:9 panel (opt out of width adaptation — otherwise it
   re-derives 1706×960). Reword the now-false 16:9-only doc comments
   (:32-46, :58-62, :99-100, :164, :180).
2. UI: Display card chip (`SettingsScreen.kt:472-492`) "Classic 4:3 ·
   1280 × 960"; the chip-label `when(profile)` at :481-484 is
   enum-exhaustive and gains a branch (compile error until then). On
   selection write BOTH `displayProfileId` and the tweak update (force
   `fovEnabled = false`) in one `settings.update` — satisfying "selecting 4:3
   turns off the widescreen tweaks". Switching back to a 16:9 profile does
   not silently re-enable (user re-enables deliberately; the F3d migration
   runs once ever). Selection calls `requireSelection` via
   `Settings.update` (Settings.kt:322-328) — guard the chip for panels
   shorter than 960 px (RP6 unaffected; `availableForPhysical` already
   filters by height class).
3. **Input-profile preservation (v2 — reviewer finding: the reset is
   destructive, not a fallback)**: `InputProfileStore` is single-slot and
   keying to a new `aspectIdentity` resets the stored layout
   (InputProfile.kt:250-252, :926-938; the host immediately overwrites the
   stored record, ClientDisplayHost.kt:295-302). Before switching aspects,
   snapshot the current stored profile under the existing prior-profile
   mechanism (`VANILLA_CONSOLE_PRIOR` pattern) and restore it when
   switching back, so a user's customized 16:9 layout survives a 4:3
   excursion. If the mechanism does not generalize during implementation,
   ship the reset behavior with an explicit warning string in the chip's
   selection dialog ("Switching aspect resets customized control layouts")
   — decide at implementation, do not ship silently.
4. Presentation: the winlator X view uniformly scales with `min(aspect)` and
   offsets (ViewTransformation) — a 1280×960 X screen on a 1920×1080 panel
   pillarboxes, never stretches; touch inverse-transform uses the same
   values (verified by reviewer). On-device verification belongs to the
   user's checklist.
5. All identity/attestation flows key off `requireSelection`/resolved virtual
   display — no other wiring.

### F4b Unlock graphics settings (resolution stays fixed)

Two locks must move together — catalog `fixedReason` and
`ManagedConfigPolicy.enforcedKeys`:
1. Unlock renderer-independent world CVars (catalog only): `lodDist`,
   `DistCull`, `SmallCull`, `trilinear`, `fullAlpha`, `frillDensity`,
   `particleDensity`, `unitDrawDist`.
2. **Per-renderer locks (v2)**: `specular`, `pixelShaders` (capability
   PIXEL_SHADERS) and `ffxGlow`/`ffxDeath` (capability GLOW_EFFECTS) stay
   LOCKED when the renderer is the Legacy GL lane (gladio/virgl) — that is
   exactly why they were locked (the opengl lane forces `M2UseShaders=0`,
   ManagedConfigPolicy.kt:66) — and unlock under DXVK. Implement as a
   renderer-aware `fixedReason` at presentation time (the capability
   metadata at WowSettingModel.kt:33/:67 exists but has no consumer; give
   it one).
3. `ffxGlow`/`ffxDeath` under DXVK: unlock catalog + delete enforced lines
   (ManagedConfigPolicy :67-68). Note: enabling glow costs fill rate —
   acceptable, user's choice.
4. `gxVSync`, `gxMultisample`: unlock catalog + remove enforced writes
   (:43-44). `gxMultisample` converts from TOGGLE to CHOICE with values
   0/1/2/4 (v2 — reviewer finding). `gxMultisampleQuality` has NO catalog
   row (v2): enforced-line removal + one-time stale delete only.
5. **One-time stale-value cleanup (v2 trigger)**: for `gxVSync`,
   `gxMultisample`, `gxMultisampleQuality` add null-valued enforced entries
   that apply **only while the current Config.wtf value still equals the
   previously-enforced constant** ("0"/"1"/"0") — delete-only-if-stale is
   idempotent, needs no persisted flag, and cannot fight user choices
   (precedent semantics: write-or-delete model, ManagedConfigPolicy.kt:8-13).
   Note: on the cleanup launch, queued overrides for those keys are skipped
   and delivered the next launch (existing enforced-key retention behavior —
   document, don't fight).
6. Author real choices for single-entry rows: `gxColorBits` 16/24,
   `gxDepthBits` 16/24, `gxFixLag` on/off, `hwDetect` on/off. `gxRefresh`
   stays fixed with an honest reason ("panel is 60 Hz"). `gxResolution`,
   `gxWindowedResolution`, `gxWindow`, `gxMaximize`, `maxFPS` stay
   display-managed (v2: name `gxWindowedResolution` — enforced at
   ManagedConfigPolicy.kt:40, no catalog row).
7. Keep `ID_ORDER_SHA256` unchanged (no ids added/removed); named test-pin
   updates (v2): enforced-list sizes 21/22 → 16/17
   (`WowGameSettingsDeliveryTest.kt:371-384`), farclip-not-enforced and
   renderer-flip pins (:313-328) re-checked, `availableForPhysical`
   1440×1080 list gains CLASSIC_43 (`ClientDisplayProfileTest.kt:143-144`),
   chip `when` branch (SettingsScreen.kt:481-484). Hub counts and
   `userEditable` are not UI-consumed (v2 correction) — no count churn.

**F4 tests/gates**: profile unit tests (new 4:3 cases), catalog/policy tests,
device smoke (4:3 toggle + a graphics change round-trip through
Config.wtf).

**F4 verification round 1 (2026-08-17) outcome**: agents 2 and 3 found one
shared blocker — the delete-only-if-stale unlock cleanup kept
gxVSync/gxMultisample/gxMultisampleQuality permanently inside the enforced
set (the editor would throw on those rows forever, queued overrides would
never deliver, and a user's own equal-value choice would be deleted every
launch). REMOVED entirely: no cleanup entries are needed because the 1.12
client itself drops those lines at clean exit (ground-truth capture) and
the editor can change them once they are outside the enforced set; the
EnforcedLine.deleteOnlyIfValueIs codec extension and its tests were
reverted with it. Enforced counts are therefore 16/17 as the plan
originally predicted. Also fixed: the TOGGLE branch support string now
includes the renderer-conditional reason (the four legacyRendererOnly rows
are toggles and previously showed no reason under GL lanes); five stale
16:9 doc comments reworded; a ground-truth F4 addendum records the unlock
set and the removed-cleanup rationale. Accepted deviations recorded: the
aspect-reset warning ships as a caption under the chips (chips act
immediately; the destructive reset happens at next launch) rather than a
selection dialog; the capability metadata still has no consumer — the
renderer gate uses the new legacyRendererOnly flag (two overlapping
metadata systems, noted as future consolidation); the pre-existing editor/
policy renderer-id mismatch for M2UseShaders (enum id vs runtime string) is
harmless today and left for a follow-up. (Recheck round note: the
ClientDisplayHost.kt "16:9 reference geometry" comment reword was reverted —
touching that file disturbed the detekt baseline match for its pre-existing
LongParameterList finding; the comment inaccuracy is recorded here instead.)

---

## F5 — Addons and bots

### F5a Install Android Port by default

`AddonRepository` init: the seed runs in the **second init block, right
after `refreshInstalledBuiltInIfNeeded`** (AddonRepository.kt:75-101; the
migrator at :63 runs first and never creates `registry.json`, so ordering is
migrate → seed). Gate: `!registry.isFile` (fresh install; deliberate removal
always leaves the file — `remove()` republishes even when empty,
AddonRepository.kt:162-188). Body mirrors `refreshInstalledBuiltInIfNeeded`
(:92-99): `AddonCatalog.load(appContext).addon("151")` with `checkNotNull`,
then `installBuiltInLocked` via the existing `launchOperation` (async on
the repository's IO scope; `installBuiltInLocked` performs its own
`reconcileAndroidPort`).
1. **Construction point (v2)**: `AddonRepository.get(context)` once at app
   start from `MainActivity.onCreate` on a background scope
   (`lifecycleScope.launch(Dispatchers.IO)`, precedent:
   `BotCustomPresets.install` in MainActivity.kt:32) — NOT synchronously on
   the main thread (registry recovery + asset sha256 are I/O) and NOT
   per-screen.
2. **First-launch race acknowledged (v2)**: a user who cold-starts and
   immediately launches can beat the seed job; the projector then projects
   nothing for that session and it self-heals next launch. While the seed
   job runs, a user-triggered install is dropped by the `operationGuard`
   reentrancy check — seconds-scale transients, accepted and documented.
3. Update stale copy (AddonsScreen.kt:49, :79, :174, :189-190) to "Android
   Port is installed by default; everything else stays optional." Safe mode
   still projects nothing. Seed lives in the repository, not the projector,
   so `AddonRuntimeProjectorTest` default-state pins keep passing. Side
   effect is intended: first launch enables the Android Port input profile
   (`reconcileAndroidPort`; its compare-and-restore protection respects
   later user customization).
4. Add a pairwise-distinctness unit test for playstyle presets (v2 — see
   F5c; nothing pins preset identity today).

### F5b Verbose Android Port description

`catalog-v1.json:3244` description (single line, valid JSON; 154-addon /
266-pair pins unaffected) + `compatibilityNotes` (:3254). **Front-load the
summary sentence** (v2: browse/installed rows ellipsize to 2 lines; the full
text shows on the detail screen and powers search). Content: the researched
inventory — twin 8-button clusters (face 1-4, D-pad 5-8) with four modifier
layers (Shift/Ctrl/Ctrl+Shift) = 32 slots, stance/bonus-bar support,
drag-to-assign, cooldown/count/range/mana feedback; radial menu (F12/Select)
with Character/Inventory/Spellbook/Talents/Quest Log/World Map/Social/Move
UI; Move UI (F8/Select+Start) drag handles + wheel/D-pad scaling, persisted
per character, `/ap resetui`; all-in-one bag window (All/per-bag/keyring,
header bag equip/lift, quality colors); Sell Junk (gray items only, never the
keyring, one sale per 0.2 s with merchant re-check, chat summary, press again
to stop); minimal chat + XP/rested strip; F7 nearby interact; F9 auto-run;
`/ap` command list; per-module fail-safes; text entry via the Android
keyboard.

### F5c Bot Activity/Playstyle fix

1. Data: make `INDEPENDENT` distinct from `CLASSIC_WORLD`
   (`BotCustomConfiguration.kt:281-289`) — recommended
   `(autoDoQuests=true, groupNearby=false, wanderWhenIdle=true,
   enableOffSpecStrategies=true, allowBotChat=false, allowPlayerInvites=false,
   syncLevelWithPlayers=false)` ("bots roam and quest alone, no bot-only
   groups").
2. UI: exclusive playstyle selection (`firstOrNull` match), render the
   existing-but-unrendered `playstyle.summary` under each chip, add the
   missing "Custom" fallback chip (parity with Activity).
3. Verbose section copy (drafted in research): Activity = CPU the bot AI
   spends thinking (update interval, work per tick, % background bots
   active; Smart→Light); Playstyle = how bots behave socially (own quests,
   own groups, wandering, off-spec strategies, chat, invites, level sync) —
   not combat roles.
4. Tests: `BotExperiencePresetTest` pins re-checked; combinability test
   unaffected by distinct INDEPENDENT values.

**F5 tests/gates**: addon repo/projector tests, bot preset tests, catalog
parse pins, detekt.

**F5 verification round 1 (2026-08-17) outcome**: agent 1 (seed +
repository) CLEAN. Agents 2/3 found one factual copy concern — the new
description claimed layouts "save per character" while the addon's
SavedVariables are account-wide — plus nits (three-of-four modifier list,
dropped Select-button aliases, the plan-cited Installed-empty-state copy,
import order). All fixed verbatim per the reviewers' wording
("account-wide (SavedVariables)", "none, Shift, Ctrl, Ctrl+Shift",
"F12 / Select" + "F8 / Select+Start", actionable empty-state string,
import block order); JSON re-validated (154 addons / 266 pairs intact) and
the gate re-run green. A formal recheck round was not possible (the two
reviewer agents were no longer resumable); the fixes are mechanical string
edits matching the reviewers' exact suggested text, verified by the patch
script's assertions.

---

## F6 — In-app updates from GitHub (no-breakage requirement)

Requirement (user): the app must upgrade itself for fixes/features without
breaking existing imports or any other on-device state.

Design (two-track):
1. **Track 1 — check + browser-assisted download (ships first, works for any
   repo visibility)**: `AppUpdateChecker` modeled on
   `AddonRepository.resolveGitHub()` (:601-616) against a dedicated public
   **updates repository** (source stays in the private repo; the public repo
   contains only releases: signed APK + `update-manifest.json`). Manifest:
   `{versionCode, versionName, apkUrl, size, sha256, minSupportedVersionCode,
   notes}` served from a host already in the downloader allowlist
   (AddonRepository.kt:941-945 — api.github.com release asset or an added
   host; NOT raw.githubusercontent.com, which is not allowlisted today).
   **Create a version row in Settings** (v2: none exists — the only
   BuildConfig.VERSION_NAME use at SettingsScreen.kt:172 is archive-export
   metadata) and an update card next to it comparing against
   `BuildConfig.VERSION_CODE`; "Update available: X — open download page" via
   ACTION_VIEW. Rate-limit handling copied from AddonRepository (:727-737).
2. **Track 2 — full in-app install (same release)**: resumable downloader
   (Range/ETag — this is a rewrite of the streaming loop, not a small
   extension; persist resume state — partial file + bytes + etag — across
   process death; the artifact is ~420 MB compressed), with (v2, all
   reviewer-required): **storage preflight** reusing the
   `ensureVoiceOverStorage` pattern (AddonRepository.kt:717-725; require
   ~1 GB free for download + session write); download to `cacheDir` with
   cleanup of stale partials; **mobile-data cost warning** sized to the real
   artifact before fetching; sha256 verification with explicit
   checksum-failure UX; then a `PackageInstaller` session install with a
   status receiver (manifest receiver precedent: SaveExitReceiver) and
   install-result feedback. **Permission grant flow (v2)**: declare
   `REQUEST_INSTALL_PACKAGES`; gate install on
   `PackageManager.canRequestPackageInstalls()` and route to
   `ACTION_MANAGE_UNKNOWN_APP_SOURCES` when not granted. New:
   FileProvider + `res/xml/file_paths.xml`. The system installer's signature
   check is the data-preservation guard: same-signature updates install in
   place; a mismatched signature is refused by Android rather than silently
   wiping.
3. **Signing decision (recorded in DECISIONS.md, default = continuity)**:
   the installed RP6 build is debug-signed. To guarantee "no breaking
   upgrade" for the existing install, release builds initially sign with the
   same debug keystore (explicit `signingConfig` in build.gradle.kts
   buildTypes; env-overridable path, debug key fallback). A dedicated
   release keystore is recorded as an open decision: adopting it later
   requires one deliberate transition (Settings realm-data export →
   uninstall → release-signed install → restore + client re-import), never a
   surprise. **No keystore material is committed; the debug keystore is
   machine-local and must be privately backed up** (v2: losing it makes
   in-place updates for the existing install impossible — record in the
   DECISIONS entry).
4. **Versioning**: move `versionCode`/`versionName`
   (build.gradle.kts:743-744) to a bump-on-release discipline starting at
   `versionCode 2 / 0.2.0` for this changeset; surfaced via BuildConfig.
5. **Update-time compatibility invariants** (why imports can't break):
   - persisted DataStore schemas only migrate forward (existing migrations
     intact; this plan adds only additive ones: tweaks schema 2 with the
     F3d writer fix, vulkan auto under the existing schema 4);
   - `registry.json`/import journal/client generations are app-private
     files untouched by APK replacement;
   - built-in addon refresh already handles asset drift
     (`refreshInstalledBuiltInIfNeeded`);
   - `minSupportedVersionCode` in the manifest lets a future breaking
     change refuse to offer an in-place update.
6. **Publishing prerequisites** (execution gated on user go-ahead — public
   repo creation is an outward-facing action): create the public updates
   repo, add LICENSE/NOTICE + GPL source-offer statement (APK embeds
   GPL/LGPL-derived binaries; provenance docs exist), local build + `gh
   release create/upload` (CI cannot build APKs). Track 2 code is fully
   testable beforehand with mockwebserver3.

**F6 tests/gates**: checker/downloader unit tests (mockwebserver3), manifest
parse pins, install-permission flows manually verified on device; no network
in unit tests.

---

## F7 — Hand-off verification and close-out (user runs the device)

All device testing is performed by the user; this phase prepares the hand-off.

1. Full host gate run: `gradlew assembleDebug -PpocketAbi=arm64-v8a
   -PpocketLane=full`, detekt, unit tests, `check_repo`, pytest suite.
2. Produce the fresh APK and a **device test checklist** (docs/devibe/) for
   the user, covering: `adb install -r` in-place update (data preserved);
   first-start warning appears once; import confirmation dialog + watchdog
   auto-continue after a simulated worker stop; Android Port seeded on a
   fresh data state (user may test on an emulator or after a deliberate
   wipe — never wiped by us); 4:3 toggle + widescreen-tweak coupling; a
   graphics setting change round-trips through Config.wtf; bot chips
   single-select; update-check card; crash-bisection procedure (F1a) with
   the addon-module toggles.
3. Record bisection outcomes as the user reports them; a targeted root-cause
   fix lands in a follow-up change if identified.
4. Docs: PROGRESS.md round entry, DECISIONS.md entries (release-keystore
   open decision; updates-repo decision; any re-pinned Gladio digest),
   agent.md unchanged.

## Execution order

F0 → F1 → F2 → F3 → F4 → F5 → F6 → F7. F1c (native Gladio fix) and F1d can
land any time inside F1. Each phase: implement → self-check → 3 review agents
→ fix findings → re-verify clean → commit → next phase.

---

## F8 — Import kill-storm recovery and truthful phase feedback

Trigger (device, fresh install of the F7 APK): after the game files copied,
the screen sat "stuck" for minutes showing "system interrupted — press
resume", then navmesh generation eventually started. Device evidence (logs
in local .tmp only): the kernel lowmemorykiller killed the `:import` worker
13 times in ~3 minutes under critical memory pressure (swap nearly full),
each life 3–8 s; every restart re-opened all 15 MPQs and re-hashed the
entire copied prefix (O(5.4 GB) per life) before resuming, so the storm
made zero forward progress; ActivityManager's crashed-service restart
backoff (up to ~8 min) plus the F2a watchdog's manual-resume fallback
produced the "press resume" loop. Root causes and fixes:

- **A1 (amplifier):** resume re-hashed every VERIFIED file. Now a VERIFIED
  row + fsync marker + unchanged source size/mtime + full-size target is
  trusted; the end-of-copy VERIFYING pass still re-hashes everything once
  before publish.
- **A3 (per-kill cost):** an interrupted `.partial` is appended to (SAF
  skip; prefix bytes are page-cache durable across a process kill) instead
  of re-copying the largest file from zero; the file hash is computed at
  completion from the local file; a kill between rename and journal commit
  is converted via markVerified instead of re-copied (round 2). Residual,
  accepted and recorded: a power loss (not a process kill) can zero-fill an
  unsynced partial tail that hash-at-end then blesses — mitigated (not
  eliminated) by fsyncing the partial at each 64 MiB progress tick; a
  same-size same-mtime source swap was already undetectable before F8 (the
  source fingerprint is metadata-only by design).
- **B (truthful messaging):** the watchdog now reads
  `ApplicationExitInfo.getHistoricalProcessExitReasons` for the `:import`
  process (API 30+, 15-min freshness) and words its notices by reason:
  LOW_MEMORY notices say Android stopped the import to free memory, that
  progress is kept, and that closing other apps lets it finish — instead of
  the generic "tap Resume". Round-1 verification caught a blocker here:
  `getTimestamp()` is epoch-based, so the elapsedRealtime age math silently
  disabled the whole feature; fixed to wall clock.
- **C (phase feedback):** VERIFYING/PUBLISHING journal per-file ticks
  ("(87/150) Data/terrain.MPQ", time-throttled to 2 s — round 2 fixed the
  per-file commit cost being quadratic under synchronous=FULL); finite data
  stages journal unknown totals so the UI shows indeterminate bars instead
  of a frozen "0/1"; the FGS notification follows data-stage progress
  (5 s-gated) instead of freezing on the last copied MPQ; the raw
  "MMAPS:map 169 (20/43)…" journal residue no longer renders as a file
  path (also on FAILED, round 2); the recover-path re-hash ticks and closes
  a stale RUNNING MANIFEST row.
- **D:** 64 MiB journal progress ticks during big copies keep watchdog
  staleness and post-mortem progress truthful.

Verification: 3 review agents (resume correctness, watchdog/UI, stage
ticks/notification) found the clock blocker, two notice-lifecycle
regressions (broadened busy-notice clear wiping the "already running"
notice; restart wording wiped within ~1 s), the quadratic tick cost, the
MANIFEST 0/1 freeze, and the recover-path silence — all fixed; the round-2
recheck verified every fix and found one remaining bypass (startImport
notices not routed through the sticky window), fixed; final verdict CLEAN.
Gates green (compile + unit tests + detekt). Device verification is the
user's: expect memory-pressure wording if the system kills the worker,
VERIFYING/PUBLISHING file ticking, indeterminate DBC/vmap/manifest bars,
per-map MMAPS progress in both card and notification, and no "system
interrupted" loop unless the worker genuinely stops.

Deferred (recorded): supervisor/main-process memory footprint (~376 MB
combined during import) is a separate optimization; notification fallback
can still show a raw stage composite briefly between stages (cosmetic).
