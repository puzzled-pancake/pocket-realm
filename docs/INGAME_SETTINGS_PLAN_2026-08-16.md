# In-Game Settings — external WoW 1.12.1 settings editor (plan, rev 6, 2026-08-16)

> Rev 2 incorporated the first three-part review round (vanilla semantics,
> runtime integration, UI/phases): binding-default provenance, reserved-key/reset
> interactions with the controller overlay, enforced-line removal semantics, the
> editor/prepare race, queued-override draining, hub phase gating, and density
> arithmetic.
>
> Rev 3 incorporated the second round: master-sound-CVar ownership across audio
> modes (written-when-off / user-owned-when-on with one-time transition cleanup),
> a global never-resetting revision counter making apply-once delivery correct
> for all three queues including re-staging after reconcile, the bindings queue
> gaining revisions, the delivery record carried forward across prepares, edit
> lock placement/ordering pinned to the stable client root, FrameXML provenance
> file list corrected (no `UIOptionsPanels.lua` in 1.12.1), Modified-badge rule
> reworded, fresh-install direct-edit clarification, and the reconcile summary
> surfaced on the hub status card.
>
> Rev 4 incorporates the third round: sound provenance corrected (sound *is*
> FrameXML — `SoundOptionsFrame.lua` pins names/labels/ranges; only defaults are
> capture-pinned; video remains capture-pinned), bindings line grammar corrected
> (quote-optional parse, unquoted emission matching existing writers), the queue
> payload sized to its 211-binding worst case (32 KiB) with the revision counter
> in its own preferences key immune to JSON fallback, per-entry scope on queued
> overrides with key+scope delivery records, direct-edit-supersedes-queued rule
> for stopped-state edits, master-CVar transition cleanup skipped when the user
> edited the key after the audio-off launch, the hub's third status state
> ("Launching"), Phase 2 acceptance gaining the transition-cleanup test, and the
> fresh-install display rule (seeded file values, not catalog defaults).
>
> Rev 5 incorporates the fourth round: every direct file edit (not only
> queue-superseding ones) bumps the revision counter and journals the post-bump
> value, closing the master-CVar transition rule's equality case; the payload
> cap re-sized to 48 KiB from full-catalog worst-case arithmetic (scope strings,
> long chords, ~118 setting entries); `directEditRevisions` added to the Phase 1
> schema/writer deliverables; the delivery map gains a prune rule (bounding
> scope accumulation); stranded overrides whose key is enforced (e.g. master
> sound while audio off) are labeled Blocked and excluded from the
> next-launch count; and the catalog schema gains `defaultProvenance` so the
> per-aspect provenance split is expressible and testable.
>
> Rev 6 (final polish from round 5): prepare-path behavior for blocked entries
> pinned (skip-and-retain — not written, not dropped, not recorded in
> `applied_overrides`), the persistent hub "B blocked" line with a discard path
> for blocked-only queues, Discard-all clearing blocked entries too, the
> three-term reconcile summary pinned in Section 10's host-contract test, and
> screenshot evidence added to Phase 3/4 acceptance.

## 1. Purpose and scope

Add an **In-Game Settings** sub-menu to the Settings screen that exposes WoW 1.12.1's
stock Video/Sound/Interface options and Key Bindings outside the game, mirroring the
game's own menus, and keeps them in two-way sync with the imported client's
configuration files:

- Changes made **out of game** are applied before the client starts (or staged for the
  next launch when the client is running).
- Changes made **in game** are visible in the app the next time the editor is opened.

The initial idea came from an external web-agent analysis of the Blizzard 1.12.1
FrameXML (preserved in the conversation that produced this plan). That analysis is
**treated as a hypothesis, not gospel**: every claim it makes is either verified
against this repository's pinned sources, or gated behind the Phase 0 ground-truth
capture below. Several of its core recommendations conflict with how Pocket Realm
already works and are replaced (Section 3).

### In scope
- A new In-Game Settings hub + category pages in Settings, following existing UI
  conventions, sized for the Retroid Pocket 6 landscape screen.
- A versioned, integrity-hashed catalog of the stock 1.12.1 fixed settings
  (video/sound/interface) — the sibling of the existing `WowVanillaBindingCatalog`.
- Host-side editors/mergers for the client's own persistence files:
  `WTF/Config.wtf` (CVars), saved UI variables (`uvars`), and
  `WTF/**/bindings-cache.wtf` (key bindings).
- Capability/ownership gating so options the port genuinely cannot honor are visible
  but disabled with a reason, and options Pocket Realm already manages are labeled.

### Out of scope (explicit)
- **Live push into a running client.** No app→game channel exists (the Gladio
  transport carries GL/GLX messages only; input injection exists but is not a
  settings channel). All writes happen at the pre-launch boundary or while the
  client is stopped. While the client runs, edits are staged as "applies next
  launch".
- **The web agent's "Mobile UI" section** (stock-UI scaling tweaks). The built-in
  VanillaConsolePort addon already owns the in-game mobile HUD (frame moving,
  radial menu, minimap behavior). Duplicating that externally is a separate future
  feature, recorded here as deferred.
- **Macro editor.** Not a settings page in 1.12.1.
- **Modifying FrameXML/MPQs.** No Blizzard file is patched or redistributed. All
  host-side edits target the WTF tree (client-generated files) or the app-owned
  `WTF/Config.wtf`, both of which live inside the user's imported, app-private
  managed client generation.

## 2. What exists today (load-bearing facts)

1. **The app owns `WTF/Config.wtf` and regenerates it wholesale at every launch.**
   `WineRuntimeStore.managedConfigText` (`client/WineRuntimeStore.kt:1927-1977`)
   renders a fixed template; `enforceManagedSafeMode` (`:1890-1925`) writes it
   atomically after `realmlist.wtf` projection and before
   `AddonRuntimeProjector.project`. `attestForLaunch` (`:1554-1560`) re-reads the
   file at spawn and fails closed unless it byte-equals the recomputed template.
   Consequence today: **every CVar the client persists at exit (volumes, camera,
   interface toggles, …) is silently reset to client defaults on the next launch.**
2. **The import seeds a safe Config** (`SAFE_CONFIG` in
   `importer/ClientGenerationStore.kt:204-223`), and the manifest lists
   `realmlist.wtf` + `WTF/Config.wtf` as `appOwnedFiles` (`:99`); other WTF files
   (account/character trees) are client-generated and untouched by the manifest.
3. **A pinned binding catalog already exists**: `client/WowVanillaBindingCatalog.kt`
   — 211 user-facing binding IDs + 3 hidden pointer gestures, sourced from the
   MOUZU mirror of Blizzard's 1.12.1 `FrameXML/Bindings.xml` with commit pin +
   SHA-256 integrity hashes. Today it only feeds the read-only action reference in
   `ui/ControlsScreen.kt`. It has **no default-key data**, and cannot get it from
   `Bindings.xml`: that file contains only Lua handler bodies — stock default keys
   live in the client binary (see Phase 0).
4. **A proven WTF-file rewrite pattern exists**, plus two live consumers of the
   binding files at the launch boundary:
   - `addons/VanillaConsolePortBindingRepair.kt` parses/rewrites
     `bind` lines in every `bindings-cache.wtf` (quote-optional on parse;
     existing host-side writers emit the **unquoted** `bind KEY COMMAND` form,
     which the client demonstrably loads — WTF walk, depth 8,
     ≤128 files, ≤1 MiB each), with atomic temp+fsync+rename writes and a durable
     journal. Its `captureBeforeLaunch` runs at every launch but only **reads**
     bindings and writes the journal — it never rewrites binding files;
     `restoreAfterRemoval` (the rewriting path) runs only on addon removal. In
     game, the VCP addon claims keys itself via `SetBinding`/`SaveBindings`,
     adopting a key only if it is unbound or already addon-owned. VCP owns
     digits `1`–`0` (+`SHIFT-`/`CTRL-`/`CTRL-SHIFT-` chords → `VCP_ACTION_n`),
     `F12`, `F8`, `F7`.
   - `addons/LegacyControllerBindingRepair.repair` runs **unconditionally at every
     launch** and **appends** `bind F6 TARGETNEARESTENEMY` / `bind F9
     TOGGLEAUTORUN` whenever F6/F9 are unbound (controller surrogates).
   Net effect for this feature: the every-launch binding rewriters are the legacy
   repair (append-only for F6/F9) and VCP's journal capture (read-only); user
   bindings otherwise survive every launch untouched.
5. **Settings persistence pattern**: user settings live in the multi-process
   DataStore (`storage/Settings.kt`). Nested config objects travel as one JSON
   string key + schema int key (`tweaks: ClientTweaksConfig`, per DECISIONS O23).
   The UI process is the only writer; the **supervisor process already reads
   `Settings.flow`** at launch decisions (`supervisor/AndroidRuntimeBackend.kt`) —
   the precedent a read-only `:client` reader follows. (The display process
   receives its settings as Binder JSON, not DataStore.)
6. **Safe write window**: files under the managed client generation may only be
   written when `ClientRuntimeService.checkNoActiveSession()` would pass (no
   session, or terminal with `runtimeFinished && processTreeDrained`), under the
   `ClientGenerationLease` (shared runtime lease vs. exclusive publication —
   `client/ClientGenerationLease.kt:35-65`). Caveat the editor must handle:
   `statusCurrent()` reports only the `session`; a prepared-but-not-launched
   ticket and `processTreeDrained` are not currently observable from the UI
   process (addressed in Section 5).
7. **UI conventions**: Settings screen uses `SettingCard` sections, `TweakSwitch`/
   `LabeledSlider`, full-width `FilterChip` selectors with disabled-with-reason
   text, `OutlinedButton` sub-page rows, and a NavHost with `navigatePush` +
   nested-graph precedent (`AddonRoutes` in `ui/PocketRealmApp.kt`). BotsScreen is
   the model for tabbed, grouped, dense-but-48dp settings pages
   (`SettingRow`/`SwitchRow`/`SteppedSlider`, `ScrollableTabRow`), ControlsScreen
   for searchable lists and `ChoiceRow` dropdowns. These row primitives are
   `private` to their files today; reusing them requires hoisting into a shared
   UI file (Phase 2 deliverable). Landscape brief
   (`docs/UI_LANDSCAPE_REFACTOR_BRIEF_2026-08-15.md`): 48 dp touch targets,
   12–16 dp card padding, two-column/two-pane where sensible.
8. **Renderer lanes**: DXVK (default) and Gladio OpenGL (experimental), VirGL.
   The current template already pins `ffxGlow "0"`, `ffxDeath "0"`,
   `farclip "177"` (perf), and forces `M2UseShaders "0"` on the Gladio lane only.
9. **In-flight work**: the working tree carries the renderer/Vulkan-driver
   "Auto" selection feature (confined to the "ARM client runtime" card and
   related catalogs). This plan builds on the current working tree and touches
   none of those files' regions.

## 3. Deltas from the web-agent analysis (decisions, with reasons)

| Web-agent proposal | Pocket Realm decision |
| --- | --- |
| "WoW files are the authoritative representation; app is a merge editor that never regenerates" | **Partially adopted.** Config.wtf becomes a merge (base = current file, overlay = app-enforced lines, overlay = queued user overrides), so in-game values survive — but the app remains the writer at the launch boundary and keeps attestation. Pure "file is authority" would break the byte-attestation invariant and the display/audio enforcement guarantees. |
| "Generate a binding manifest from Bindings.xml at build time (234 commands)" | **Already exists.** `WowVanillaBindingCatalog` (211 user-facing + 3 hidden, integrity-hashed). We extend it in place (v2) with default keys instead of generating anything new. |
| "Default keys transcribed from Bindings.xml" | **Corrected provenance.** `Bindings.xml` contains no default keys (handler Lua only); defaults live in the client binary. Catalog v2 defaults come from the Phase 0 on-device capture: in-game Key Bindings → Reset To Defaults → clean exit → parse `bindings-cache.wtf`, with the capture session run so that VCP/legacy claims are absent or filtered. Pinned with capture provenance, not a FrameXML hash. |
| "Small modification to the stock FrameXML (`AndroidSettingsBridge.lua`) applied at `VARIABLES_LOADED`" | **Rejected.** No FrameXML/MPQ patching (product rule + no mechanism). `uvars` are applied host-side by editing the client's own saved-variable files pre-launch, so Blizzard's own load path consumes them exactly as if set in game. A Lua bridge addon remains the recorded fallback if Phase 0 shows a setting cannot be persisted by file. |
| `GetVideoCaps()`-style capability probing with resolution/refresh/multisample lists | **Replaced by static per-lane capability objects.** Resolution/refresh/windowed/maximized are owned by the Pocket Realm display profile; multisampling is pinned by the current template. These rows are shown disabled with "Managed by Pocket Realm display settings" / "Fixed for this renderer" reasons. No dynamic probing is invented. |
| All 30 video settings interactive | Ownership triage: a subset becomes interactive (farclip, weather, spell detail, LOD, anisotropic where the lane supports it, UI scale, gamma where supported, vsync/triple-buffer where the lane supports it); the rest visible-but-disabled with reasons. Exact split recorded in the catalog with `capabilityRequirement`. |
| Sound: 11 CVars (`MasterSoundEffects`, `MasterVolume`, …) | Adopted; names/ranges pre-verified against the pinned `SoundOptionsFrame.lua` (they match the web agent's list exactly). Today's template writes `Sound_EnableAllSound`/`Sound_EnableMusic`/`Sound_EnableSFX`/`Sound_EnableAmbience` — WotLK-era names that 1.12.1 ignores, so today's audio-off "enforcement" is almost certainly inert; Phase 0 cross-checks on device anyway, and Phase 2 fixes template + `SAFE_CONFIG` seed + `ClientBuild5875LoginTest` in lockstep. When audio is OFF, the app enforces only the master sound CVar; per-channel toggles/volumes stay user-owned (inert while master is off). Note: Phase 2 makes audio-off enforcement *effective for the first time* — recorded in DECISIONS.md, with both audio-ON and audio-OFF round trips in the acceptance criteria. The sound page is disabled with a reason while audioMode is OFF. |
| Key bindings: two keys per command, account/character profiles, conflict reassignment | **Adopted**, implemented against `bindings-cache.wtf` with the existing repair's line grammar, plus reserved-key interplay with both VCP and the legacy repair (Section 7). |
| "Reset to defaults" per category | Adopted with a reserved-key carve-out (Section 7): reset = drop queued overrides **and remove the corresponding `SET`/uvar lines** so client defaults resolve (config/uvar-backed), or restore catalog capture-defaults (bindings) — **except reserved keys**, whose current bindings are left untouched. |
| "Mobile UI" page | Deferred (Section 1, out of scope). |

## 4. Data model

### 4.1 `WowVanillaSettingsCatalog` (new, package `com.pocketrealm.ingame`)

A versioned, integrity-hashed manifest of the stock 1.12.1 fixed settings, built the
same way as `WowVanillaBindingCatalog`, with a **split provenance**:

- **Interface settings** (uvars + interface CVars, inversions, paired writes,
  camera-style enums): verified against the pinned MOUZU 1.12.1 FrameXML commit
  (`776d64e…`) — primarily `UIOptionsFrame.lua` + `UIOptionsFrame.xml`, with
  specific entries annotated when their wiring lives in companion files
  (`MultiActionBars.lua`, `UIParent.lua` — e.g. the action-bar toggles, whose
  state flows through `SetActionBarToggles`/`GetActionBarToggles` and is
  therefore classified `FUNCTION`, pending the Phase 0 persistence check).
  Pinned with that source hash.
- **Sound settings**: the stock 1.12.1 sound panel *is* FrameXML — the pinned
  mirror's `SoundOptionsFrame.lua` statically enumerates every sound CVar name
  and slider range (7 toggles: `MasterSoundEffects`, `EnableMusic`,
  `EnableAmbience`, `EnableErrorSpeech`, `SoundListenerAtCharacter`,
  `EmoteSounds`, `SoundZoneMusicNoDelay`; 4 sliders: `MasterVolume`,
  `SoundVolume`, `MusicVolume`, `AmbienceVolume`, all 0–1 step 0.1). Names,
  labels, and ranges are therefore `FRAMEXML_PIN`; only the *defaults* need
  runtime `GetCVarDefault`, so defaults (and anything the capture contradicts)
  are `DEVICE_CAPTURE`.
- **Video settings**: a native engine panel — no `VideoOptionsFrame*` exists in
  FrameXML, so names/ranges/defaults are all pinned from **Phase 0 on-device
  capture evidence** (provenance field records the capture), not a FrameXML
  hash.

One entry per setting:

```kotlin
data class WowSettingDefinition(
    val id: String,                 // "graphics.terrainDistance"
    val section: WowSettingSection, // enum mirroring the sub-page/tabs below
    val label: String,              // exact 1.12.1 English label
    val control: Control,           // TOGGLE, SLIDER, CHOICE, ACTION(button)
    val backend: Backend,           // CVAR, UVAR, FUNCTION
    val key: String,                // CVar name / uvar name / function id
    val min: Float?, val max: Float?, val step: Float?,   // sliders
    val choices: List<Choice>?,     // id + label (+ stored value) for CHOICE
    val inverse: Boolean,           // UI state inverted vs stored value
    val requires: List<String>,     // ids of prerequisite settings
    val pairedWrites: List<Pair<String, Float>>? // e.g. camera pitch = yaw/4
    val capability: Capability?,    // renderer-lane requirement, else always shown
    val defaultValue: String?,      // verified 1.12.1 default; null = client default only
    val provenance: Provenance,     // how the DEFINITION (name/label/range/choices)
                                    // was verified: FRAMEXML_PIN | DEVICE_CAPTURE
    val defaultProvenance: Provenance?, // how the DEFAULT was verified; null when
                                    // defaultValue itself is null. Sound entries
                                    // are FRAMEXML_PIN definitions with
                                    // DEVICE_CAPTURE defaults.
)
```

Counts and ranges from the web-agent analysis are the **starting hypothesis**
(per-section hypothesis counts in Section 8; the web agent's own totals are
internally inconsistent — its tables enumerate ~118 fixed controls, not 114 —
which is itself a Phase 0 line item). Values become catalog data only after
Phase 0 verification; the integrity hash then pins them.

### 4.2 Queued overrides: `WowGameSettingsConfig` (new)

One JSON string key + schema int key in the Settings DataStore, following the
`tweaks` precedent, **sized to its worst case**: a full-catalog staging session
— 211 binding commands × up to ~150 bytes per entry (long chord keys like
`CTRL-SHIFT-NUMPAD8`, character scope ids, multi-digit revisions on a
long-lived install) ≈ 31 KiB, plus ~118 setting entries × ~70 bytes ≈ 8 KiB —
totals ~39 KiB, so the payload cap is **48 KiB** (above the worst case, still
under the 64 KiB control-plane cap). Two hard rules guard the read path,
because — unlike `tweaks`, whose strict 8 KiB parser guards only the Binder
boundary — this config's strict parser sits on the `Settings.flow` read path
in every process:

- `revisionSequence` lives in **its own preferences Long key outside the JSON
  blob**, so no JSON parse failure/fallback can ever regress the counter (a
  regressed counter would make every future stage lose to stale delivered
  revisions — the exact bug apply-once exists to prevent).
- An over-cap or malformed payload is **rejected at write time in the UI** with
  a visible reason; the lenient read fallback restores an empty *queue* but
  never touches the counter key.

```kotlin
data class QueuedOverride(
    val value: String,
    val revision: Long,             // value of revisionSequence when staged
    val scope: String,              // uvar: character scope id (see 5.4);
                                    // cvar: always "config"
)
data class BindingOverride(
    val primary: String?,           // null = unbound
    val secondary: String?,
    val revision: Long,
    val scope: String,              // "account" or a character scope id
)
data class WowGameSettingsConfig(
    // revisionSequence is NOT here: separate preferences Long key, bumped by
    // the UI on every editor write, never reset, so a re-staged entry always
    // outranks the last delivered revision for its key.
    val cvar: Map<String, QueuedOverride> = emptyMap(),    // settingId -> override
    val uvar: Map<String, QueuedOverride> = emptyMap(),    // settingId -> override
    val bindings: Map<String, BindingOverride> = emptyMap(), // commandId -> override
)
```

Every queued entry carries its **scope** (the picker selection at staging
time), and delivery records are keyed by `settingOrCommandId + scope`, so
switching the on-screen scope picker never misdelivers earlier staged edits to
a different `bindings-cache.wtf`/character file.

Semantics: this stores **only pending, not-yet-delivered edits** made while the
client was running (Section 5). It is not a mirror of the game state. The global
`revisionSequence` gives **apply-once delivery semantics for every queue**
(cvar, uvar, and bindings alike — Section 5): a staged entry carries the
counter's value, and delivery compares it against the revision last recorded as
delivered for that key; because the counter never resets, a re-staged entry
after an editor reconcile always outranks the previous delivery and re-delivers,
as intended. Blizzard uvars are **character-scoped only** in 1.12.1 (verified
from the pinned `UIOptionsFrame.lua`: every uvar is `RegisterForSave`, persisted
to the per-character
`WTF/Account/<account>/<server>/<character>/SavedVariables.lua`; the per-addon
`SavedVariables/<Addon>.lua` folders exist only for the TOC
`## SavedVariables:` system addons like VCP use). Account-wide scope applies to
key bindings only. The UI process is the only writer; the revision + per-entry
scope shape is fixed in the Phase 1 schema so bindings need no later migration.

### 4.3 Ownership classes for Config.wtf keys

- **App-enforced** (always resolved at prepare — *written or deleted*, never
  preserved from the base: a conditional line whose condition is false this
  launch is removed from the merged output, so stale lines cannot survive audio
  on→off, loopback→LAN, or Gladio→DXVK flips): `readTOS/readEULA/readScanning/
  movie`, `gxApi`, `gxResolution`, `gxWindowedResolution`, `gxWindow`,
  `gxMaximize`, `gxVSync` (while pinned), `gxMultisample*`, `maxFPS`,
  `scriptMemory`, `realmName` (loopback only), `SoundMixRate/SoundBufferSize`
  (audio on only), `SoundSoftwareChannels` (audio on + sound-channels patch
  only), `M2UseShaders` (Gladio lane only), the master sound CVar **only while
  audioMode is OFF** (Phase 2, verified name; transition rule below),
  `ffxGlow/ffxDeath` (until a lane is proven to support them), `farclip` (until
  Phase 2 flips it).
- **Conditional-with-transition — master sound CVar**: while audioMode is OFF it
  is enforced ("written") so app-level silence is real. While audioMode is ON it
  is **neither enforced nor deleted**: it is an ordinary user-editable setting
  (the sound page's master row), preserved from the base like any user CVar, so
  in-game master-off choices survive relaunches. The only deletion is a one-time
  transition cleanup: when the *previous* launch's record says audio was OFF and
  this launch's is ON, the stale enforced `0` line is removed once, and from
  then on the key is user-owned. Because a user-chosen master-off is
  byte-identical to the stale enforced `0`, the cleanup is **skipped when a user
  edit to the master key is newer than the audio-off launch**: the editor
  journals direct file edits per key (`directEditRevisions` map in the
  game-settings preferences, outside the queue JSON), and each record stores
  `preparedAtRevision` (the counter value at prepare); the cleanup fires only if
  no direct-edit revision for the master key exceeds the previous record's
  `preparedAtRevision`. For this comparison to be sound, **every direct file
  edit bumps the global counter and journals the post-bump value** into
  `directEditRevisions[key]` (5.1) — not only edits that supersede queued
  entries — so any direct edit performed after a prepare strictly exceeds that
  prepare's `preparedAtRevision`, including the common no-queued-entry case.
  (The record already carries `audio` per launch,
  `WineRuntimeStore.kt:1917`.)
- **User-editable**: every catalog `CVAR` setting whose key is not enforced, once
  verified (Phase 2+: `farclip` flips from enforced to user-editable with app
  default 177; gamma, weather, spell detail, LOD, uiscale/useUiScale, sound
  CVars, camera CVars, interface CVars, …).
- **Preserved/unknown**: everything else in the file — never reordered, never
  dropped, byte-preserving round-trip. (One known transient: the legacy
  `Sound_Enable*` seed lines from `SAFE_CONFIG` become inert preserved lines
  after Phase 1 until the client's first clean exit rewrites the file; Phase 2
  updates the seed in the same lockstep as the template.)

## 5. Lifecycle and write model

### 5.1 States and editor behavior

| Client state | Editor behavior |
| --- | --- |
| Stopped (verified per 5.3) | **Direct edit.** Read current values from `WTF/Config.wtf` / uvar files / `bindings-cache.wtf`; on change, write immediately (atomic temp+fsync+rename, `VanillaConsolePortBindingRepair.writeAtomic` discipline), holding the **edit lock** (5.3) and a **shared `ClientGenerationLease`** so a concurrent re-import/publication cannot interleave. **Every direct edit bumps the global counter and journals the post-bump value into `directEditRevisions[key]`** (4.3's master rule depends on this). Rows holding a still-queued entry display the queued value with the Queued badge; a direct edit of such a row **also removes the queued entry**, so the newest explicit edit always wins at the next prepare — an older queued value can never silently overwrite a newer direct edit. |
| Prepare in flight / prepared, not launched | **Stage only**, same as Running (5.3's stopped-check excludes this state); the hub shows a "Launching" status (Section 8). |
| Running | **Stage only.** Controls remain enabled; each change goes to `WowGameSettingsConfig`; rows show a "Queued" state; screens show one banner: "N changes apply next launch". No file is touched. |
| Prepare (next launch) | `:client` process **reads** `Settings.flow` (read-only, supervisor precedent), applies queued overrides per the apply-once rule below, writes Config.wtf, and records delivery in `managed-safe-profile.json` (5.2). |
| Editor re-opened while stopped | Reconcile against the delivery record: entries recorded as delivered are dropped from the queue and reported ("applied" or "superseded by an in-game change"); entries whose backing key is **currently enforced by the app** (e.g. a staged master-sound value while audioMode is OFF) are labeled "Blocked — audio is off", excluded from the "apply next launch" count, and become deliverable again if the condition clears; all other remaining entries stay queued and visibly win at next prepare. The file remains the display truth for non-queued rows. |

**Apply-once delivery.** Each prepare reads the *previous* delivery record before
overwriting it and applies a queued override — in **any** queue (cvar, uvar,
bindings) — only if its `revision` (from the global `revisionSequence`, Section
4.2) is newer than the revision recorded as delivered for that key (or it was
never delivered). A delivered override is never re-applied, so a user's later
in-game change is not silently reverted at every launch — it is reported as
"superseded" at the next editor visit. Re-staging after an editor visit bumps
the global counter, so the new entry always outranks the previous delivery and
re-delivers, as intended. The counter never resets (the UI is its only writer),
which is what makes re-delivery correct even after reconcile has dropped the
earlier queue entry.

**Two-way promise gate.** "In-game changes are visible in the app" presupposes the
client flushes `Config.wtf` / `SavedVariables.lua` / `bindings-cache.wtf` on exit —
which only a clean exit guarantees. Phase 0 must verify this through the app's
**normal stop path** (request-close → teardown → drain), not just a harness-driven
clean exit. If the normal path does not flush, Phase 0 records either a graceful-
exit runtime requirement or an explicit re-scoping of the two-way promise
(e.g. "changes sync after a clean Save & Exit"), and this plan is amended before
Phase 1.

### 5.2 Config.wtf merge engine and launch record

`ConfigWtfCodec` (new, pure Kotlin):

1. Parse base file into an ordered line list (`SET name "value"`, CRLF preserved;
   unknown lines kept verbatim; last-wins for duplicate keys at parse time).
2. Overlay app-enforced lines: replace in place if present, else append in
   deterministic order; **enforced keys whose condition is false this launch are
   deleted** — with the one documented exception of the master sound CVar's
   audio-off→on transition cleanup (Section 4.3), which deletes the stale
   enforced `0` exactly once, on the first audio-on launch after an audio-off
   launch; from then on the key is user-owned and preserved.
3. Overlay queued user overrides (user-editable keys only). A queued override
   whose key is **enforced this launch** (e.g. master sound while audioMode is
   OFF — 5.1's "blocked" case) is **skipped and retained**: not written, not
   dropped from the queue, and not recorded in `applied_overrides`, so it
   delivers when the condition clears. Staging a *new* override for an
   enforced key is rejected at the UI; the skip-and-retain rule exists for
   entries stranded by a later condition change.
4. Serialize with `\r\n` line endings.

`WineRuntimeStore` changes:

- `managedConfigText` becomes `managedConfigMerged(base: String?, …)`. `Prepared`
  (in-process only, never crosses Binder) captures the **exact bytes written**;
  `attestForLaunch` switches from "recompute template and compare" to "compare
  file bytes against the captured bytes" — same fail-closed strength, valid under
  a merge model (recomputing from the live file would always pass and defeat the
  check). Self-test paths that skip enforcement also skip the attest, unchanged.
- `managed-safe-profile.json` (rewritten each prepare; the previous record is
  read first for apply-once) gains: `applied_overrides` — the **latest delivery
  entry per key+scope**, `{key, scope, value, revision}`, covering cvar, uvar,
  and binding queues alike — `preparedAtRevision` (the revision counter value
  at prepare time, used by the master-sound transition rule in 4.3), and
  `config_sha256`, the digest of the prepared Config.wtf text. The delivery map
  is **carried forward across prepares**: each prepare merges its new
  deliveries over the previous record's map and **prunes** — carried-forward
  entries whose revision is older than the oldest revision in any current
  queue entry are dropped (future stagings always carry higher revisions, so a
  pruned entry can never matter again; this also bounds the map against
  unbounded scope accumulation). This keeps a key delivered at prepare 1 and
  still queued at prepare 2 from losing its delivery evidence and getting
  silently re-applied at prepare 3. The digest makes the Section 11 "client
  drops unknown lines" mitigation implementable (re-merge from the last
  prepared text if needed).
- `SAFE_CONFIG` import seed is unchanged in Phase 1 (see 4.3 note). After Phase 1,
  unknown/user lines in the file survive every launch — the intended behavior
  change of this feature (recorded in DECISIONS.md).
- `ClientBuild5875LoginTest` is updated: enforced lines still asserted
  (including negative assertions that remain valid because conditional enforced
  lines are deleted, not preserved), plus a new assertion that a pre-seeded user
  line (e.g. `SET MasterVolume "0.500000"`) survives prepare+launch.

### 5.3 Editor/process coordination (race closure)

UI-level disabling alone cannot close the editor-vs-prepare race:
`statusCurrent()` exposes only the session (a prepared-but-unlaunched ticket is
invisible), `processTreeDrained` is absent from the status payload, and the UI
surface (`X86DirectWineRuntime`) exposes no status call at all. The plan therefore
closes the race **mechanically** and uses observation only for UX:

- **Edit lock (hard guarantee).** A new cross-process `FileLock`
  (`InGameSettingsEditLock`) is taken **exclusively** by the editor around each
  read-modify-write, and **exclusively** by `enforceManagedSafeMode` around its
  Config/addon write phase. This makes editor writes and prepare writes mutually
  exclusive regardless of process, closing the silent-lost-update window for
  the non-attested files (bindings-cache.wtf, uvar files) that the Config
  attestation cannot protect. Lock placement and ordering follow the
  `ClientGenerationLease` precedent: the lock file lives at the **stable client
  root** (`noBackupFilesDir/client/`, alongside `.generation-publication.lock`),
  never inside a generation — generations are deleted on activation retire, and
  a lock file inside one could vanish between acquisition and use, silently
  voiding mutual exclusion. Acquisition order is fixed: **shared
  `ClientGenerationLease` first** (which also resolves the active generation),
  then the edit lock — the same order on both editor and prepare paths, so no
  lock-order cycle exists (exclusive generation-lease holders never take the
  edit lock).
- **Status surface (UX + gating).** `statusCurrent()` and the UI-facing
  control interface are extended to expose: session state,
  `processTreeDrained`, and a prepared-ticket/prepare-in-progress flag. The
  editor treats "stopped" as: no session, no prepared ticket, no prepare in
  flight. The byte-attestation remains the final fail-closed net for Config.wtf.

### 5.4 uvar backend (saved UI variables)

Blizzard uvars are character-scoped (4.2). The editor uses a strict
`SavedVariablesCodec` that:

- parses **top-level scalar assignments only** (`NAME = 1`, `NAME = "x"`,
  `NAME = true/false`) in the selected character's `SavedVariables.lua`,
  preserving every other line byte-for-byte;
- refuses (visible "not editable outside the game" state) if a target variable is
  absent or not a scalar — no structural Lua editing, ever.

Scope handling: a character picker populated by walking the existing WTF tree
(account names → `<server>/<character>` folders). Only characters that have
logged in at least once have files; the uvar section shows "Log in once in game
to configure this character" rather than faking state. (Phase 0 confirms the
exact on-disk layout build 5875 produces; the pinned-source verification above
predetermines character-only scope for Blizzard uvars.)

### 5.5 Function-backed settings (ShowHelm/ShowCloak, tutorials, guild-recruitment
mode, action-bar toggles)

The pinned `UIOptionsFrame_Save` confirms these use `setFunc` functions rather
than CVars — matching the web agent's classification. Phase 0 records where (if
anywhere) build 5875 persists them. Then, in priority order: (1) file persistence
→ catalog `FUNCTION` backend becomes `CVAR`/`UVAR` with the verified key;
(2) no file persistence → the setting ships **visible but disabled** with reason
"Change this in the game's own menus" — honest and complete representation
without a bridge; (3) the Lua-bridge addon (VCP-style, projected by
`AddonRuntimeProjector`, values staged into its own saved-variables file) is the
recorded fallback, deliberately not built unless Phase 0 shows a meaningful set
that file persistence cannot cover.

## 6. Capability gating

A small static `WowRendererCapabilities` object per renderer lane (dxvk, gladio,
virgl) answers the catalog's `capability` requirements (pixel shaders, glow/death
effects, anisotropy, vsync/triple buffer, gamma). Defaults are conservative (what
the current template already pins is "unsupported"); a lane gains capability only
with on-device evidence, recorded in DECISIONS.md. Unsupported rows render
enabled=false with a `labelMedium` reason ("Not supported by the current
renderer"), the same pattern as the renderer/driver availability chips in
SettingsScreen. Rows owned by the app (resolution, windowed, maximize, frame cap)
say "Managed by Pocket Realm display settings" and link narratively to the
existing Display card.

## 7. Key bindings editor

- **Data**: `WowVanillaBindingCatalog` v2 — same IDs, plus each binding's stock
  default key(s) from the **Phase 0 on-device capture** (Section 3 provenance;
  capture session arranged so VCP/legacy claims are absent or filtered out);
  catalog version bump + updated integrity hashes + test with capture provenance
  recorded in the catalog header. (The web agent's 234 count includes
  hidden/debug/Mac entries; this catalog's deliberate 211+3 split stands.)
- **Files**: `bindings-cache.wtf` at account scope
  (`WTF/Account/<name>/bindings-cache.wtf`) and character scope
  (`WTF/Account/<name>/<server>/<char>/bindings-cache.wtf`), parsed/written with
  the line grammar already proven in `VanillaConsolePortBindingRepair`:
  quote-optional on parse (`bind KEY COMMAND` or `bind "KEY" "COMMAND"`),
  **emitted unquoted** to match both existing host-side writers (VCP repair and
  the legacy surrogates), with modifier prefixes `SHIFT-`/`CTRL-`/`ALT-`/
  combinations. Phase 0 confirms the exact modifier token forms (including
  `ALT-`) and the safe emission form, and which scope the client actually loads
  when both exist (active-binding-set detection — a Phase 0 capture line).
- **Reserved keys**: `VCP owned` (digits + chords, F7/F8/F12) **∪ legacy
  surrogates {F6, F9}**. These are never unbound by the editor, never offered as
  assignment targets, and shown as "used by the controller overlay". Rationale:
  the legacy repair re-appends F6/F9 at every launch when unbound, and the VCP
  addon adopts keys only if unbound or addon-owned — a host-side reset that
  rebinds the digits would silently kill the controller layout.
- **Editing model**: two slots (primary/secondary) per command; assigning a key
  bound to another command unbinds it there (vanilla behavior) with a confirm
  dialog naming the displaced action — unless the key is reserved; explicit
  Unbind (reserved keys excluded); per-category "Reset to defaults" uses the v2
  capture-defaults **but skips reserved keys** — commands whose stock default is
  a reserved key keep their current binding and are badged "controller overlay".
- **Modified badge**: comparisons against a **reserved default key** never count:
  commands whose stock default key is reserved never show "Modified" at all —
  they show the "controller overlay" badge instead (their live binding is
  whatever the overlay owns). All other commands badge normally when either
  slot differs from the catalog default.
- **Key picker**: a full-screen dialog with grouped key grids (letters, digits,
  F-keys, navigation, punctuation) plus a modifier-chord builder (SHIFT-, CTRL-,
  ALT-, and their combinations). Existing `ALT-` bindings parse, display, and
  edit correctly from the first Phase 4 release. No free text.
  Controller-friendly (dpad navigation, 48 dp cells).

## 8. UI design

Navigation: a nested graph `settings/ingame/…` in `PocketRealmApp.kt`
(`AddonRoutes` pattern): `hub`, `graphics`, `sound`, `interface`,
`interface-advanced`, `bindings`. Settings screen gains one new `SettingCard`
("In-Game Settings", one sentence of support text + one full-width
`OutlinedButton` row → hub), placed between "Client tweaks" and "Sound".

Hub screen: a status card first with one line per fact: the client state —
"Changes apply immediately" (stopped), "Client running" (running), or
"Launching" (prepare in flight / prepared ticket, per 5.3's stopped-check) —
and, whenever N > 0 regardless of state, "N queued changes apply next launch"
(N counts deliverable entries only) with a **Discard-all action that clears
queued and blocked entries alike** (edits are stage-only in the running and
launching states). Blocked-while-enforced entries (5.1) get their own
**persistent** hub line — "B blocked (audio is off)" — so a queue of only
blocked entries still has a visible discard path. After a reconcile
(Section 5.1), the card adds a transient summary line: "N applied · M
superseded · B blocked". Below it,
category rows (title + one-line description + count badge, full-width cards,
≥64 dp, chevron): **Graphics, Sound, Interface, Advanced Interface, Key
Bindings. No third level of navigation beyond this.**
Hub rows are rendered **only for shipped categories** — Phase 2 lists Graphics
and Sound; Interface rows appear in Phase 3, Bindings in Phase 4 — never dead or
placeholder destinations.

Category screens follow BotsScreen's anatomy: `ScrollableTabRow` sub-sections, a
single vertically scrolled column of shared row primitives (`SwitchRow`,
`SteppedSlider`, `ChoiceRow` — hoisted from BotsScreen/ControlsScreen into a
shared UI file in Phase 2), 48 dp targets, `bodySmall` explanations only where
they add real value (grouped at section top, not per-row), `testTag` on every
control (`ingame-<setting-id>`). Dependencies (e.g. guild/titles names require
player names; smooth shading requires vertex shaders; ToT mode requires ToT)
disable the dependent row with a reason, mirroring in-game behavior. "Rogue /
Druid"-style class annotations render as a `labelMedium` tag, never as hidden
rows.

Hypothesis density (Phase 0 finalizes counts; the web agent's own tables
enumerate ~118 fixed controls, not its claimed 114):

| Screen | Tabs | Rows per tab (hypothesis) |
| --- | --- | --- |
| Graphics | Display · World · Brightness · Shaders · Compatibility (5) | 7 / 8 / 2 / 7 / 6 |
| Sound | Toggles · Volumes (2) | 7 / 4 |
| Interface | Controls · General · Display · Camera · Help (5) | 2 / 6 / 16 / 10 / 4 |
| Advanced Interface | Action Bars · Chat · Raid & Party · Floating Combat Text (4) | 6 / 9 / 6 / 18 |

Largest tabs (~16–18 rows) remain comfortably scrollable single columns; each
phase captures screenshot evidence against the landscape brief.

Bindings screen: wide layout (≥600 dp, the RP6 case) uses BotsScreen's two-pane
pattern — category rail (weight 0.9) + binding list (weight 1.7) with a search
field (`WowVanillaBindingCatalog.search` already exists); compact layout uses a
category dropdown. Scope picker (Account / character) is a `ChoiceRow` at top.
Each binding row: label + description (`bodySmall`) + two key chips; the
"Modified" badge marks rows differing from catalog defaults per Section 7's rule.

Absent-backing degradation (fresh install, never launched), split by backend:

- **Config.wtf-backed rows** (Graphics/Sound/interface CVars): controls enabled,
  showing the **seeded file's values** (app-pinned defaults such as
  `farclip "177"`), falling back to catalog defaults only for keys with no line
  in the seed — the file remains the display truth per 5.1. Because a fresh
  install is a stopped state, edits follow the normal **direct-edit** path
  (5.1) — they write immediately to the seeded Config.wtf (merge base = the
  `SAFE_CONFIG` seed) and take effect at the next launch; nothing queues. A
  single banner explains: "Start the game once to read its current settings."
- **uvar and bindings rows**: controls disabled until the scope files exist
  (first in-game login), with the same banner — the prepare-side writer cannot
  address files whose scope directories don't exist yet, so these never queue
  pre-first-login.

## 9. Phases

Each phase lands independently tested and leaves a coherent commit; PROGRESS.md
updated per repo rules. No phase starts before the previous one's acceptance is
recorded.

**Phase 0 — Ground truth & catalog (no runtime behavior change)**
- On-device capture (RP6, existing androidTest harness + `tools/capture_rp6.py`):
  run the imported client, toggle each stock settings area in game, exit, diff
  the WTF tree. Settle: exact uvar file layout (character `SavedVariables.lua`
  expected); whether the client rewrites Config.wtf at exit and what it
  preserves/echoes; verified 1.12.1 sound CVar names (in-game sound-panel diff or
  `/console cvarlist`) and whether today's `Sound_Enable*` lines are inert;
  camera paired-write behavior; action-bar toggle persistence; function-backed
  settings persistence; bindings grammar edge cases incl. `ALT-` tokens; which
  binding scope is active when both exist; **binding stock defaults** via
  in-game reset-to-defaults with VCP/legacy claims absent or filtered; exact
  fixed-settings count reconciling the web agent's 114-vs-~118 discrepancy.
- **Normal-stop flush gate**: make an in-game change, stop the session via the
  app's normal stop path, verify the WTF diff appears (5.1). Outcome recorded;
  plan amended before Phase 1 if it fails.
- Findings recorded in `docs/INGAME_SETTINGS_GROUND_TRUTH.md`; catalog deltas vs
  the web-agent hypothesis listed there.
- Deliverable: `WowVanillaSettingsCatalog` + integrity test (split provenance per
  4.1); a DECISIONS.md entry covering the ownership model.
- Acceptance: catalog test green; ground-truth doc exists with every open question
  above answered or explicitly marked unverifiable-with-reason.

**Phase 1 — Merge engine + plumbing (first behavior change: user CVars survive)**
- `ConfigWtfCodec` (pure) + unit tests (preservation, ordering, CRLF, precedence,
  enforced-vs-user rejection, **conditional enforced-line deletion on condition
  flips**, duplicate handling).
- `WineRuntimeStore`: merge-based `enforceManagedSafeMode` under the new edit
  lock (stable-root placement, lease-first ordering per 5.3); `Prepared` byte
  capture; attest byte-compare; `applied_overrides` (per key+scope latest
  delivery, carried forward across prepares), `preparedAtRevision`, and
  `config_sha256` in the safe-profile record; `:client` read of
  `WowGameSettingsConfig` with apply-once semantics for all three queues.
- `storage/Settings.kt`: `gameSettings` field + keys + schema 1 — the schema
  ships with the global `revisionSequence` counter key, the
  `directEditRevisions` journal key, and the per-entry scope + `BindingOverride`
  shape even though bindings UI lands in Phase 4, so no later migration is
  needed — plus `SettingsDataStoreTest` coverage (counter monotonicity,
  re-stage-after-reconcile revisions).
- `ingame/InGameSettingsWriter` (UI process): stopped-state check (5.3 status
  surface extension), edit lock + shared lease, atomic writes, and **per-key
  direct-edit journaling into `directEditRevisions` with a counter bump on
  every edit** (4.3/5.1 — Phase 2's master-CVar rule consumes the journal);
  editor-grade read APIs.
- Status surface extension (5.3): `statusCurrent` + UI control interface expose
  session/`processTreeDrained`/prepared-ticket.
- `ClientBuild5875LoginTest` updated + preservation assertion.
- Acceptance: unit + androidTest green; on-device: change `MasterVolume` in game,
  exit, relaunch from app, value persists (recorded as evidence).

**Phase 2 — Graphics & Sound pages**
- `WowRendererCapabilities` (static, conservative); `farclip` flips to
  user-editable (default 177); sound CVar names corrected in template +
  `SAFE_CONFIG` + androidTest, in lockstep; master sound CVar enforced only when
  audioMode is OFF.
- Hoist shared row primitives (`SwitchRow`/`SteppedSlider`/`ChoiceRow`) into a
  shared UI file; hub (**Graphics + Sound rows only**) + Graphics + Sound
  screens, nav wiring, testTags; catalog-driven rendering (no per-setting UI
  code); queued-state visuals + banner; Discard-all.
- Acceptance: unit tests for capability gating + queued-banner state mapping;
  screenshot evidence on RP6; on-device round trips for one graphics + one sound
  setting, **and** audio-ON and audio-OFF round trips (audio-off enforcement
  becomes real this phase); the **master-sound transition-cleanup test**
  (audio off→on deletes the stale enforced `0` exactly once — including the
  skip-when-user-edited cases, both queued-superseded and no-queued-entry —
  then preserves user values), delivered with this phase per Section 10's
  wiring bullet; DECISIONS.md entry noting that
  behavior change; hub shows no dead destinations.

**Phase 3 — Interface pages**
- CVar-backed interface settings; camera paired writes via catalog
  `pairedWrites`; dependency-disabled rows; hub gains Interface rows.
- `SavedVariablesCodec` + character scope picker + uvar-backed settings (per
  Phase 0 truth).
- Acceptance: codec unit tests (strict scalar guard, refusal cases); screenshot
  evidence on RP6 (the densest screens ship here); round trip of one uvar
  (e.g. Instant Quest Text) on device.

**Phase 4 — Key bindings**
- Catalog v2 (capture defaults + provenance + hash bump + tests);
  `BindingsFileCodec` (grammar shared with VCP repair; reserved set = VCP owned
  ∪ {F6, F9}); editor UI (scope, search, two-pane, key picker dialog with ALT-
  chords, conflict confirm, resets with reserved-key skip); Modified-badge rule;
  hub gains Key Bindings row.
- Acceptance: unit tests (reserved-key immutability, reset skip, conflict
  unbind, ALT- round trip); screenshot evidence on RP6 (two-pane bindings
  layout); on-device — rebind Jump to a new key externally,
  verify in game; rebind in game, verify externally after exit; verify F6/F9
  surrogates and VCP keys untouched after a launch.

**Phase 5 — Function-backed leftovers & polish**
- Remaining settings via the Section 5.5 decision ladder; cross-category search
  on the hub; per-category resets everywhere; final evidence capture; DECISIONS /
  PROGRESS finalization.

## 10. Test strategy

- **Pure codecs** (`ConfigWtfCodec`, `SavedVariablesCodec`, `BindingsFileCodec`):
  round-trip byte-fidelity on synthetic files, preservation of unknown content,
  precedence, conditional enforced-line deletion, refusal paths, size guards.
  Pattern: `ClientTweaksValidatorTest` (pure functions over synthetic inputs).
- **Catalog**: integrity-hash tests exactly like `WowVanillaBindingCatalogTest`,
  asserting `provenance`/`defaultProvenance` per the 4.1 split (interface+sound
  definitions FRAMEXML_PIN; video definitions, sound/video/binding defaults
  DEVICE_CAPTURE).
- **DataStore**: `SettingsDataStoreTest` extension for the new key pair,
  revision-counter semantics (monotonicity; the counter surviving a malformed/
  oversized-queue fallback via its separate key), a **full-catalog staging
  case** (all 211 bindings + all settings queued stays under the 48 KiB cap),
  and over-cap write rejection.
- **Wiring**: a unit test asserting `managedConfigMerged` output for a recorded
  prepare equals the bytes captured in `Prepared` (attestation consistency), and
  an apply-once delivery test over successive prepares covering **all three
  queues** (cvar, uvar, bindings) and **scope-keyed delivery** (an entry staged
  at account scope does not deliver to a character scope), including the
  re-stage-after-reconcile, superseded-by-in-game-change, and
  **stopped-direct-edit-over-queued** cases, the **blocked-while-enforced**
  cases (staged master sound while audioMode OFF: skip-and-retain at prepare —
  not written, not dropped, not recorded in `applied_overrides` — plus the
  labeling case), the delivery-map **prune rule**, and the master-sound-CVar
  audio off→on transition cleanup (with the skip-when-user-edited cases, both
  queued-superseded and no-queued-entry).
- **UI host-contract tests** (the `AddonNavigationContractTest` /
  `PocketRealmNavigationTest` pattern): route titles for the six nested-graph
  destinations, hub row gating per phase, queued-banner state mapping,
  **reconcile summary mapping ("N applied · M superseded · B blocked")** and
  the persistent blocked line, disabled-with-reason presentation, capability
  gating, absent-file degradation — all consuming the mandated `testTag`s.
- **On-device**: extended `ClientBuild5875LoginTest` (preserved user line
  survives); Phase-gated round-trip evidence per the acceptance criteria above.
- **Matrix**: the web agent's full per-control validation matrix is realized as
  codec-level property tests (every catalog CVAR/UVAR write → parse-back equality)
  plus the targeted on-device round trips — not a manual 114-row checklist.

## 11. Risks

- **Client may drop unknown Config.wtf lines at exit.** Mitigation: Phase 0
  measures it; if true, only CVars the client itself knows round-trip, which is
  still exactly the set the editor edits; app-added unknown lines are re-merged
  from the last prepared text, retrievable via `config_sha256` in the
  safe-profile record (recorded in Phase 0 before Phase 1 proceeds).
- **Editor/prepare race.** Closed mechanically by the exclusive edit lock (5.3)
  for all WTF writes; the Config byte-attestation remains the final fail-closed
  net; the extended status surface drives UI gating only.
- **Queued overrides re-applying forever** (single-writer DataStore, no
  `:client` writes). Closed by apply-once revision delivery over the global
  counter for **all three queues** (5.1, 4.2), with the carried-forward
  delivery record (5.2) and "applied/superseded" reporting at editor
  reconcile.
- **A newer direct edit losing to an older queued entry.** Closed by the
  stopped-state rule (5.1): a direct edit removes the key's queued entry and
  bumps the counter; covered by the stopped-direct-edit-over-queued wiring
  test (Section 10).
- **Queue payload overflow or corruption regressing delivery.** Closed by
  sizing (48 KiB vs ~39 KiB full-catalog worst case), write-time rejection
  with a visible reason, and the counter living in its own preferences key so
  a JSON fallback can never regress it (4.2); covered by the DataStore tests
  (Section 10).
- **SavedVariables parsing complexity.** Bounded to top-level scalars with hard
  refusal otherwise.
- **Attestation refactor regression.** Byte-capture is equivalent-strength and
  simpler; covered by the Phase 1 wiring test + androidTest.
- **Controller-overlay regression from binding edits.** Reserved set = VCP owned
  ∪ legacy {F6, F9}; reset/badge rules skip reserved slots; Phase 4 on-device
  acceptance verifies a launch leaves VCP/legacy claims intact.
- **UI density.** Four tabbed screens (5/2/5/4 tabs), largest tabs ~16–18 rows;
  verified against the landscape brief with screenshot evidence each phase.
- **Normal-stop flush not guaranteed.** Gated in Phase 0 (5.1) with two recorded
  outcomes (graceful-exit requirement or re-scoped promise) — Phase 1 does not
  start on an unverified premise.

## Appendix A — assumptions inherited from the web-agent analysis (Phase 0 gates)

Setting counts (~118 enumerated vs the claimed 114), slider ranges (farclip
177–777×60, uiscale 0.64–1.0×0.01, gamma ±0.5×0.1, camera yaw 90–270×10,
cameraDistanceMaxFactor 1–2×0.1, mouse sensitivity 0.5–1.5×0.05), enum values
(camera styles 0/1/2, ToT 1–5, combat-text 1–3, weather 0–3, world/spell detail
0–2), inversion list (`deselectOnClick`, `spamFilter`), paired writes
(`pixelShaders`→`ffx`, `PetMeleeDamage`→`PetSpellDamage`, camera pitch
companions), dependency graph, and uvar-vs-CVar classification (Blizzard uvar
character-scope is already verified from the pinned FrameXML; the file layout is
Phase 0). Additionally gated: **binding stock-default provenance** (client-binary
capture, not Bindings.xml), **normal-stop WTF flush**, **active binding scope
detection**, **bindings grammar incl. `ALT-` tokens and the emission form**
(unquoted, per the existing host-side writers), and the **provenance split**
(video = native engine panel, capture-pinned; sound = FrameXML-pinned via
`SoundOptionsFrame.lua` for names/labels/ranges, capture-pinned for defaults).
Each becomes catalog data only after the Phase 0 capture or direct FrameXML
verification; the catalog's integrity hash then pins it.
