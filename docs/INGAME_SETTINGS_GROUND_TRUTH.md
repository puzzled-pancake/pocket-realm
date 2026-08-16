# In-Game Settings — Phase 0 ground truth (2026-08-16)

Sources: the live managed client generation on the Retroid Pocket 6
(generation `fcda3cb3-2b23-4e42-a524-2b68b8b4fdd2`, account `HI`, character
`char-1` on `MaNGOS`, pulled read-only via `run-as` over wireless ADB), and the
pinned MOUZU mirror of Blizzard's 1.12.1 FrameXML at commit
`776d64ecf708540969e34df9680ffdacb3e8b555` (`UIOptionsFrame.lua`,
`SoundOptionsFrame.lua`, `MultiActionBars.lua`, `UIParent.lua`,
`GlobalStrings.lua`, `Bindings.xml`; preserved under `.tmp/framexml/` during
implementation). Every catalog claim is pinned to one of these two sources;
nothing from the external web-agent analysis entered the catalog unverified.

## WTF layout (build 5875 on-device evidence)

- `WTF/Config.wtf` — app-generated at prepare, then **rewritten by the client
  at clean exit**. The observed file is the app template's lines (in template
  order) followed by client-echoed CVars (`hwDetect`, `gxColorBits`,
  `gxDepthBits`, `gxRefresh`, `gxFixLag`, `fullAlpha`, `lodDist`, `SmallCull`,
  `DistCull`, `trilinear`, `frillDensity`, `specular`, `pixelShaders`,
  `particleDensity`, `unitDrawDist`, `realmList`, `gameTip`).
- `WTF/Account/<account>/SavedVariables.lua` — **account-level** Blizzard
  saved UI variables. This corrects the plan's hypothesis of character-scoped
  uvars: in 1.12.1 every `RegisterForSave` uvar persists to the account file,
  not the character folder. Observed value forms: strings (`"1"`/`"0"`), bare
  numbers (`AUTO_QUEST_WATCH = 1`, `PARTYBACKGROUND_OPACITY = 0.5`), and
  `nil`. The catalog stores uvar scope as `account`.
- `WTF/Account/<account>/bindings-cache.wtf` — **the effective binding table**
  (163 `bind KEY COMMAND` lines, unquoted, CRLF/CR mix tolerated). The
  character-level `bindings-cache.wtf` existed but was empty: per-character
  overrides are layered over the account table, and an empty character file
  means "no per-character overrides". The editor edits account scope and
  per-character override files with the same grammar.
- Per-character folders hold addon `SavedVariables/` only (VCP, pfQuest,
  AI_VoiceOver) plus `chat-cache.txt`, `layout-cache.txt`,
  `camera-settings.txt` (camera *position*, not camera CVars — the camera
  style/speed settings are CVars in Config.wtf).

## Config.wtf round-trip behavior

- The client **preserves lines it does not know** (`Sound_Enable*` seed lines
  survived a full play session verbatim) and **drops two known-inert lines**:
  `gxVSync` and `gxMultisample` disappeared from the post-exit file while
  `gxMultisampleQuality` survived. Both stay app-enforced (re-added each
  prepare) — the merge engine's enforced set is what makes them durable.
- The client **overwrote `farclip`** from the template's `177` to
  `500.000000` (the 1.12.1 default) at exit — confirming that today's
  template silently resets the user's in-game view distance every launch,
  exactly the behavior this feature removes.
- `DistCull` tracks `farclip` (both `500.000000`); `camera-settings.txt` is
  unrelated to the camera CVars.
- Float CVars are echoed with six decimals (`500.000000`); the codecs format
  float writes the same way.

## Sound panel (FRAMEXML_PIN, `SoundOptionsFrame.lua`)

7 toggles — `MasterSoundEffects` (master), `EnableMusic`, `EnableAmbience`,
`EnableErrorSpeech`, `SoundListenerAtCharacter`, `EmoteSounds`,
`SoundZoneMusicNoDelay` (label "Loop Music"); 4 sliders 0–1 step 0.1 —
`MasterVolume`, `SoundVolume`, `MusicVolume`, `AmbienceVolume`. Labels from
`GlobalStrings.lua` (e.g. `MasterSoundEffects` → "Enable All Sound"). The
current template's `Sound_Enable*` names are **confirmed inert** (preserved
verbatim by the client, never consumed). Defaults are not stated by FrameXML
and were not present in the capture → `defaultValue = null`
(defaultProvenance null); the UI marks absent lines as "Default".

## Interface panels (FRAMEXML_PIN, `UIOptionsFrame.lua` + `GlobalStrings.lua`)

- Check buttons: every stock control with exact label, backend (`cvar`/`uvar`
  with name, or function-backed), inversion (`deselectOnClick`,
  `spamFilter` — checked = CVar "0"), paired write
  (`PetMeleeDamage` → also sets `PetSpellDamage`), and uvar defaults
  (`default = "x"` in the table or the `UIOptionsFrame_Init` literals;
  `AUTO_QUEST_WATCH` is a *number* 1, `PARTYBACKGROUND_OPACITY` numeric 0.5).
- Sliders: `mousespeed` 0.5–1.5×0.05, `cameraYawSmoothSpeed` 90–270×10
  (paired `cameraPitchSmoothSpeed = value/4`), `cameraYawMoveSpeed` 90–270×10
  (paired `cameraPitchMoveSpeed = value/2`), `cameraDistanceMaxFactor` 1–2×0.1.
- Dropdowns: `cameraSmoothTrackingStyle` 1=Smart/2=Locked/0=Never;
  `cameraSmoothStyle` 1=Smart/2=Always/0=Never (selecting Never disables the
  auto-follow slider, mirrored as a `requires` dependency);
  `SHOW_TARGET_OF_TARGET_STATE` uvar 1=Raid/2=Party/3=Solo/4=Raid and
  Party/5=Always; `COMBAT_TEXT_FLOAT_MODE` uvar 1=Scroll Up/2=Scroll Down/3=Arc.
- Function-backed (no file persistence observed; per the Section 5.5 ladder
  they ship visible-but-disabled): `SHOW_TUTORIALS` (Reset/ClearTutorials),
  `AUTO_JOIN_GUILD_CHANNEL` (SetGuildRecruitmentMode), `SHOW_HELM`/`SHOW_CLOAK`
  (ShowHelm/ShowCloak), multibar visibility toggles (`SetActionBarToggles`/
  `GetActionBarToggles`; only `ALWAYS_SHOW_MULTIBARS` is a persisted uvar).

## Video CVars (DEVICE_CAPTURE from the post-exit Config.wtf)

Captured values are the client's own echoes (defaults unless the player
changed them): `hwDetect 0`, `gxColorBits 24`, `gxDepthBits 24`, `gxRefresh
60`, `gxFixLag 0`, `fullAlpha 1`, `lodDist 100.000000`, `SmallCull 0.040000`,
`DistCull 500.000000`, `trilinear 1`, `frillDensity 24`, `specular 1`,
`pixelShaders 1`, `particleDensity 1.000000`, `unitDrawDist 300.000000`,
`farclip 500.000000`. Panel labels for these are native (client binary), so
catalog labels are descriptive English with `DEVICE_CAPTURE` provenance; the
native panel's resolution/window/UI-scale rows are represented disabled as
"Managed by Pocket Realm display settings" or "Not supported by the current
renderer" (conservative `WowRendererCapabilities` per the plan). Only
`farclip` (177–777, capture default 500) is user-editable in this drop —
the same flip the plan schedules for Phase 2. `gamma`/`uiscale`/`useUiScale`
had no line in the capture and no FrameXML source → **excluded from the
catalog** rather than shipped on hypothesis.

## Bindings (DEVICE_CAPTURE, `WTF/Account/HI/bindings-cache.wtf`)

- Grammar confirmed: unquoted `bind KEY COMMAND` with `SHIFT-`/`CTRL-`/
  `CTRL-SHIFT-`/`ALT-` prefixes (`bind ALT-1 SELFACTIONBUTTON1` is live
  data), mouse `BUTTON1..4`, `MOUSEWHEELUP/DOWN` chords, `NUMPAD*` tokens.
- The capture is the full effective table including stock defaults
  (`W MOVEFORWARD`, `SPACE JUMP` + `NUMPAD0 JUMP` — two slots are two keys
  bound to one command, ordered by appearance). VCP claims filter cleanly by
  target (`VCP_ACTION_n`, `VCP_TOGGLE_RADIAL`, `VCP_MOVE_UI`,
  `VCP_NEARBY_INTERACT`); legacy surrogates by the exact key+command pairs
  F6/TARGETNEARESTENEMY and F9/TOGGLEAUTORUN.
- `ACTIONBUTTON1..8`'s stock digit defaults were *not* in the file (VCP
  claimed the digits). They are reconstructed from the visible pattern
  (`9`/`0`/`-`/`=` → ACTIONBUTTON9/10/11/12 continue the row) and the
  pre-existing `LegacyControllerBindingRepair.stockBindings` map, which
  already pins `1..8 → ACTIONBUTTON1..8`. Catalog v2 marks those eight as
  reconstructed in the header comment.
- Caveat recorded: the capture is one live device snapshot, not an in-game
  "Reset To Defaults" capture — if the player ever remapped a keyboard key
  the default would be wrong for that command. The controller-first play
  style (VCP owns the digits; everything else matches the known stock table)
  makes this low-risk; the Modified badge may mislabel such a row, nothing
  more. The full-catalog capture is preserved at
  `.tmp/rp6-wtf/account-bindings.wtf`.

## Normal-stop flush (the two-way promise gate)

The pulled tree is itself the evidence: the user played and stopped via the
app's normal stop path, and the client had rewritten Config.wtf, all three
binding/saved-variable layers, `chat-cache.txt`, and `camera-settings.txt`
by the time the files were read. The normal stop path flushes. (The stale
`Sound_Enable*` seed surviving verbatim is preservation, not proof of
consumption — the exit-echo set proves the write side.)

## Fixed-settings count (web-agent 114-vs-~118 discrepancy)

The honest catalog count after verification: sound 11, graphics 24, interface
37, advanced interface 36 = **108 fixed settings**, of which 8 are
function-backed rows (helm/cloak, tutorials, guild recruitment, four multibar
toggles) that render disabled, plus the 211-command binding catalog. The web
agent's larger tables included rows this verification could not pin (Mobile
UI section, per-account binding profiles, dynamic capability probing,
gamma/uiscale) — excluded per the provenance rules rather than guessed.

## Plan amendments recorded by this capture

1. **uvar scope is account, not character** (§4.2/§5.4): the uvar editor
   targets `WTF/Account/<name>/SavedVariables.lua`; the character picker is
   unnecessary for uvars (bindings keep both scopes).
2. **Bindings defaults provenance** is a live-device capture filtered for
   overlay claims (§7), not an in-game reset session — acceptable per the
   risk note above, with the eight digit-bar defaults reconstructed and
   flagged.
3. **`Sound_Enable*` inertness confirmed**; Phase 2's lockstep rename
   (template + `SAFE_CONFIG` + `ClientBuild5875LoginTest`) proceeds as
   planned.
4. **Video catalog is capture-evidenced CVars only**; gamma/uiscale excluded
   (absent from capture, no FrameXML source).
