# L1 "Nearby Use" Investigation — Full Report

**Date:** 2026-08-14 / 2026-08-15 (evening session, ~22:00 – 00:45 device time)
**Feature:** Gamepad L1 = "choose the nearest eligible corpse, chest, or ordinary usable object and open it as one realm action" (Vanilla 1.12.1 profile)
**Device:** Retroid Pocket 6 (kalima), wireless ADB `adb-REDACTED-DEVICE._adb-tls-connect._tcp`, package `com.pocketrealm`, character `char-1`, account `HI`
**Status (follow-up session, 2026-08-14):** Root cause #2 is now **found and fixed in the client binary** — the exact gate was reverse-engineered in `WoW.exe` (build 5875 enUS) and neutralized with a 2-byte companion patch shipped in the `WoW.exe.patched` pipeline. See §7. On-device verification was pending at writing time (device off-network); the patch is deterministic, unit-tested, and fail-closed.

---

## 1. TL;DR

1. **Root cause #1 (found and fixed):** the addon sent its trigger as `SendAddonMessage("PR6I", "1 INTERACT", "PARTY")`. A vanilla 1.12.1 client **refuses to transmit PARTY-channel addon messages while not in a group** (client-side "You are not in a party" check). Playing solo — the entire point of this product — the packet never left the client. Fixed by switching the trigger to a plain self-whisper (`SendChatMessage("PR6I:1 INTERACT", "WHISPER", nil, UnitName("player"))`) and moving the server-side intercept to the `CHAT_MSG_WHISPER` case.
2. **Root cause #2 (found and fixed — client binary patch):** after the transport fix, L1 presses demonstrably reach the server and the server-side interact **succeeds** (`Pocket Realm nearby interact result: OK_LOOT` in `world.log`, 19 times across tests) — but no loot window appears. Reverse engineering of `WoW.exe` (build 5875) showed the 1.12 client does not *silently* ignore a server-initiated `SMSG_LOOT_RESPONSE`: its handler (`0x5EB900`) compares the response's GUID against the "pending loot GUID" the client records **only when it sends `CMSG_LOOT` itself** (`0x5DF1E0`/`0x5DF3A0` store it at `player+0x1D28`). With no match — and with the corpse loot type byte = 1 not in the spontaneous-loot set {2,3,4} the fallback branch accepts — the client **builds and sends `CMSG_LOOT_RELEASE` for that corpse and drops the response**: no `LOOT_OPENED`, no window, loot session closed. That is why the server kept reporting `OK_LOOT` while nothing appeared. Fixed by widening the fallback branch's loot-type acceptance from {2,3,4} to "any real type" with a 2-byte patch (`cmp al,2`→`cmp al,1`, `je`→`jae` at file offsets `0x1EB94C`/`0x1EB94E`), so the server-opened corpse enters the *same untouched vanilla parse/open path* (`0x5EB9F4`→`0x5EBAFC`: gold, items, `LOOT_OPENED`). Target selection stays client-authoritative by design: L1 needs the corpse as *interaction subject*, not as visible target.
3. **Fix shipped as:** a Pocket-Realm-owned companion patch in the existing `WoW.exe.patched` pipeline (`ClientTweaks.applyNearbyLootAcceptPatch` + `expectedPublishedPatchedBytes`; applied in `WineRuntimeStore.applyTweaks` after the upstream vanilla-tweaks patcher runs, before byte-for-byte verification). Deterministic: unique 24-byte original-code signature must match exactly once or publication aborts (fail-closed); manifest records `nearbyLootAccept: true`. The earlier "server-side auto-loot" idea from the previous session was **rejected** in favor of this minimal client-side acceptance (per the task decision: Pocket Realm selects and opens; the client enters its normal loot lifecycle; Vanilla Fixes auto-loots).

---

## 2. How the whole system works

### 2.0 The one-paragraph chain

Each link below was traced in source and (where marked ✅) verified live on the device:

| Step | Component | Evidence |
|---|---|---|
| 1 | RP6 shoulder L1 arrives as `KEYCODE_BUTTON_L1` (102), classified gamepad, layout `RETROID_POCKET_6_XBOX` | ✅ logcat `PR/ClientInput` lines at 22:57 and 00:18 |
| 2 | `ClientInputBridge.dispatchKey` gates (system keys, device classification, layout, focus repair) pass | `ClientDisplayHost.kt:1113-1175`; ✅ per-press `isImeFixed` system lines |
| 3 | Active profile `profile_v11`: scheme `VANILLA_CONSOLE_PORT`, `"L1":"NEARBY_USE"`, aspect `16:9` | ✅ on-device `shared_prefs/pocket_input_profile.xml` |
| 4 | `NEARBY_USE` → `GamepadBinding.NearbyUsePulse` → one synthetic F7 key pair injected into the in-process Winlator XServer (now with a 50 ms paced release) | `InputContract.kt:1339` (pulse), `1141-1148` (`injectAndroidKey`) |
| 5 | F7 → X11 → Wine (`winex11.drv`) → WoW 1.12.1 client | ✅ proven transitively (step 8 fires) |
| 6 | Managed addon binds F7 → `VCP_NEARBY_INTERACT`; handler sends the trigger message | `Bindings.xml:44`, `Core.lua:429-439` (claim), `Core.lua:351-357` (handler); ✅ `bind F7 VCP_NEARBY_INTERACT` in on-device `bindings-cache.wtf:163` |
| 7 | cmangos overlay `PocketRealmInteraction.cpp` intercepts the trigger on the authenticated session (exact message + self-name match), applies the cooldown, selects the best candidate (lootable corpse / chest via Opening-spell eligibility / supported gameobject) within ~5 yd + line of sight, then executes through core handlers | ✅ verified by live `OK_LOOT` / `NO_TARGET` results in `world.log` |
| 8 | Execution: corpse → synthesized `CMSG_LOOT` → `HandleLootOpcode`; chest → real Opening spell (3365); other GO → synthesized `CMSG_GAMEOBJ_USE` | `PocketRealmInteraction.cpp` (interact function); `LootHandler.cpp:90-155`; `LootMgr.cpp:1459-1498` |

The rest of §2 explains each layer in detail.

### 2.1 The big picture: one app, six processes

Pocket Realm is a single Android app split into cooperating processes: the main UI process, `:supervisor` (orchestrates the rest), `:database` (embedded MariaDB holding the realm's three databases: world/characters/logs), `:realm` (realmd, the login server), `:world` (the cmangos world server, loaded from the prebuilt `libpocket_world_runtime.so`), and `:client` (the Wine + Box64/Winlator stack running the patched 1.12.1 client `WoW.exe.patched`). The client connects to the world server over loopback exactly like a real player over the internet — same TCP session, same opcode protocol. That is also why the L1 feature must ride *game-legal* channels: there is no back door between app and server other than the config file and the game socket itself.

The app writes `mangosd.conf` fresh at every world start (`ServerRuntimeFiles.kt`): `PocketRealm.NearbyInteract = 1` enables the handler, `PocketRealm.NearbyInteractCooldownMs` (normalized 100–2000 ms, user-adjustable in Settings, applied at *next* world start) paces it, and all administrative channels are deliberately closed (`Console.Enable = 0`, `Ra.Enable = 0`, `SOAP.Enabled = 0`) — the only command surface is the authenticated game session.

### 2.2 Android input: from shoulder button to game key

The RP6's controller exposes itself as one Android `InputDevice` with keyboard+gamepad+joystick source bits. Every hardware key lands in `ClientInputBridge.dispatchKey` (`ClientDisplayHost.kt:1113-1175`), which runs a gate sequence: Android system keys are dropped; the device is classified (`controllerDeviceMode`) by name/descriptor/vendor-product ID into `RP6_RETRO` ("Retroid Pocket Controller", 0x2022:0x3001), `RP6_XBOX` (the "Xbox Wireless Controller" identity this device actually uses), `OTHER_CONTROLLER`, or `NONE`; keys from an RP6-named device that did not classify as gamepad are suppressed (protects against the handheld's keyboard-emulation mode); finally the bridge repairs X keyboard focus (`ensureKeyboardFocus`) and forwards to the contract stamped with a **generation token** — a monotonic lifecycle counter so stale events from a recycled surface can never reach a new game window (mismatched generation = silently rejected).

### 2.3 The input contract: mapping, pulses, pacing, persistence

`InputContract` is the single authority translating controller state into game input. Its active **profile** maps every physical control (`Rp6Control`) to a semantic `ControllerAction`; the *scheme* selects a preset — `CLASSIC_CAMERA` (built-in layout; L1/L2 are face-button *layers* there), `VANILLA_CONSOLE_PORT` (the addon-aware preset: L1=`NEARBY_USE`, R1=`TARGET_PULSE`, L3=`POINTER_RIGHT`, R3=`POINTER_LEFT`, Select opens the radial, etc.), or `CUSTOM` (any user edit).

Some actions are **semantic pulses**: one balanced down+up pair per physical press. `semanticPulseLocked` fires only on the first DOWN per owner (Android key-repeat is deduped), silently refuses while the soft keyboard is open, and retires the owner on UP. The F7 pulse is now *paced*: the DOWN is injected immediately and the UP is scheduled 50 ms later through the display's main-loop scheduler (skipped only if the generation died mid-dwell) — instantaneous pairs are a documented swallowed class on this emulated lane because the game polls input per frame (§2.4). Select is special: while held (or within a 300 ms tap grace) it intercepts certain buttons as chords — e.g. Select+L1 becomes the precise right-click-at-pointer fallback instead of the nearby use.

Profiles persist in `pocket_input_profile.xml` under versioned keys (`profile_v11` newest; `profile_v10`…`profile_v2` legacy; first valid JSON wins). On load, `fromJson` compares the stored map against **frozen literal legacy maps** — exact snapshots of every uncustomized preset this codebase ever shipped. A stored map that matches one is upgraded to the current preset (this is how the L3 change propagates: the pre-change preset is frozen as `v11VanillaConsolePortNearbyUseBindingNames`); any map that matches none is a genuine player customization and is preserved verbatim, deliberately, forever. This preserving rule is why the original `profile_v7` CUSTOM map on this device survived every upgrade untouched until `profile_v11` was found ahead of it.

### 2.4 Getting keys into WoW: the embedded X server and the Wine lane

The app embeds a Winlator X server in-process. The contract's production sink (`XServerInputSink`) hands each synthetic Android key to the X server's keyboard layer, which maps Android keycodes to X keycodes/keysyms (F7 → keysym 65476) and injects press/release into the focused window — silently dropped if no window has focus or the window is disabled, which is why the bridge repairs focus on every event (1.12 WoW's top-level windows are not classified as application windows by the desktop helper, so the repair prefers windows named "wow"/"world of warcraft"). From there the event crosses the X socket into Wine's `winex11.drv`, becomes `WM_KEYDOWN`/`WM_KEYUP`, and is consumed by the game's input polling (the 1.12 client uses DirectInput fed from the window message stream). Pointer buttons travel the same lane as mouse clicks — this is the path your working L3 right-click takes.

### 2.5 The managed addon: install, projection, and key bindings

The Vanilla ConsolePort addon is a first-party, built-in addon (id `builtin__vanillaconsoleport`) living in APK assets. `AddonRepository` tracks installs in `no_backup/addons/registry.json` with a digest of the asset tree; on app upgrade, if the APK's asset digest changed, it republishes the package once (no loops). Separately, **every game launch** `AddonRuntimeProjector` copies the built-in tree fresh from APK assets into `<client>/Interface/AddOns/VanillaConsolePort` (filename/extension allowlist, size caps, hard `## Interface: 11200` check, atomic swap with rollback, ownership marker `.pocketrealm-managed.json`) — so an app upgrade can never race the projection. Two hygiene helpers run around it: `LegacyControllerBindingRepair` reserves the F6/F9 surrogate bindings the addon's older versions used, and `VanillaConsolePortBindingRepair` journals F7 ownership so a clean uninstall restores whatever the player had before.

In-game, the addon registers its commands via `Bindings.xml` — discovered by the client through that reserved filename alone (deliberately not listed in the `.toc`). At `PLAYER_ENTERING_WORLD` the addon claims its keys: each key only if currently unbound or already addon-owned (`mayClaimF7` etc.), then `SetBinding` + `SaveBindings`, persisted by the client into `WTF/Account/<account>/bindings-cache.wtf` and stamped with a schema number (`BINDING_SCHEMA = 5`) so later logins skip re-claiming. That is why `bind F7 VCP_NEARBY_INTERACT` survives app reinstalls — it lives in the WoW account data, not the APK.

### 2.6 The trigger transport: why a self-whisper

1.12 addons cannot open or interact with objects (no `InteractUnit`, no `INTERACTTARGET` binding, `CameraOrSelectOrMove` protected since 1.10), so the feature needs a server-side actor — and the only addon→server text channel is the chat system. The original design used `SendAddonMessage(..., "PARTY")`, which the client blocks while solo (root cause #1). The fix uses the plain chat API: `SendChatMessage("PR6I:1 INTERACT", "WHISPER", nil, UnitName("player"))` sends an ordinary whisper to yourself — legal solo, and it travels as a normal `CMSG_MESSAGECHAT` packet (whisper type, the character's racial language, recipient name + text). The delimiter is a printable `:` because tab-byte survival in 1.12 chat serialization is unproven; both endpoints change together, so any exact-match token works. The self-directed recipient is the security boundary: the server only ever acts on a whisper whose recipient is the sender's own name.

### 2.7 The server overlay: how the patch gets into the prebuilt .so

The world server is not built from a forked repo — it is **pinned upstream cmangos + a generated patch**. `tools/build_o09_realm_runtime.py` checks out the recorded submodule commits, verifies the tree is content-clean, then applies byte-exact **anchor overlays**: it finds a known pristine snippet in a source file and splices in the Pocket Realm code (the whisper-case hook in `ChatHandler.cpp`, the enum/declaration/member injections in `WorldSession.h`, the `PocketRealm.NearbyInteract*` config registration in `World.h/.cpp`, and the copied `PocketRealmInteraction.cpp`). It then cross-compiles with the Android NDK, strips and validates the ELF, stages the `.so` files, and writes both `BUILD_PROVENANCE.json` and `schemas/realm-runtime-lockfile-arm64-v8a.json` (sizes + sha256). Gradle's `validateRealmRuntime` task later fails the build unless the staged artifact, the provenance, and the lockfile hashes all agree — the chain that let us prove the running binary contained the fix. After building, the script reverses every anchor, leaving the submodule pristine for the next run.

### 2.8 The nearby-interact handler: eligibility, selection, execution — and the window gate

On a matching trigger, the handler first applies the cooldown (`PocketRealm.NearbyInteractCooldownMs`, default 250 ms — a result of `THROTTLED` is consumed silently). Then it searches the grid around the player within `INTERACTION_DISTANCE` (~5 yd) with line of sight for: dead creatures whose loot the player may take, and spawned gameobjects that pass per-type rules — a **chest** is admitted only if the core's own Opening-spell cast validator accepts spell 3365 on it (locks/profession chests excluded); other objects only if unlocked and of a supported type (door, button, questgiver, text, goober, camera, mailbox). Candidates are ranked: lootable corpse/chest first, then quest/use objects, then the rest; ties by combat-reach distance, then type, then GUID — nearest eligible wins deterministically. Execution reuses the core's own opcode handlers: corpses synthesize a `CMSG_LOOT` (real loot path, `HandleLootOpcode`), chests cast the genuine Opening spell, everything else synthesizes `CMSG_GAMEOBJ_USE`.

The **window gate** is where it currently stops: `HandleLootOpcode` ends in `Loot::ShowContentTo`, which *does* unconditionally send `SMSG_LOOT_RESPONSE` — but the 1.12 client's C-side only raises `LOOT_OPENED` (the event that makes `LootFrame.lua` show the window) for loot **it requested itself**. Identical packet, different initiator, no window — proven by the L3/L1 comparison in §3 Phase 7. Target selection has the same property in reverse: the target frame is client-authoritative, so no server packet can "select the container" either.

### 2.9 The controls as they stand on the latest installed build

- **L1** — nearby use/open (works server-side; invisible until the §7 pivot lands).
- **L3** — right mouse button at the pointer (manual interact/open; window + auto-loot verified working).
- **R3** — left mouse button (select at pointer).
- **R1** — nearest-enemy target pulse (F6); **Select chords** — radial menu, last-hostile (G), use-at-pointer, camera/pointer mode.
- **Auto-run** — touch overlay only (removed from L3 by request; the classic layout keeps its own L3 auto-run).

---

## 3. Investigation timeline and tests performed

### Phase 0 — Environment and build verification (pre-diagnosis)

- Dirty-tree arm64 APK built at 22:27 and upgrade-installed at 22:28:09 (`adb install -r`, data preserved — no WoW file revalidation). Verified inside the APK: arm64-v8a only, `assets/addons/vanilla-console-port/` present.
- Server processes all up (`:supervisor :client :world :realm :database`), `ClientActivity` focused, `WoW.exe.patched` running, `Sessions online: 1` (solo) in `world.log`.

### Phase 1 — Input/profile/addon/server state audit (22:30 – 23:00)

- **Input side ✅:** logcat showed every L1 press (`code=102`, `gamepad=true`, layout `RETROID_POCKET_6_XBOX`) at 22:57:39-45, including Android auto-repeat bursts.
- **Profile ✅ (after one correction):** first dump of `pocket_input_profile.xml` was head-truncated and showed only a legacy `profile_v7` (CUSTOM, `L1=KEY_0`) — briefly misdiagnosed as the root cause. Full key list revealed `profile_v11` with `L1=NEARBY_USE`; the store reads `profile_v11` first (`InputProfileStore.storedProfile`, `InputProfile.kt:938-943`). Lesson recorded below in §6.
- **Addon ✅:** registry `no_backup/addons/registry.json` contains `builtin__vanillaconsoleport`; projection at every launch (`AddonRuntimeProjector`, from `WineRuntimeStore.kt:1895`) — on-device folder listing complete (6 files including `Bindings.xml`, `## Interface: 11200`).
- **Binding ✅:** on-device `WTF/Account/HI/bindings-cache.wtf:163` = `bind F7 VCP_NEARBY_INTERACT`.
- **Server ✅:** `mangosd.conf` values correct; installed `libpocket_world_runtime.so` sha256 `19781dbc…59bc54` **exactly matched** the pin in `schemas/realm-runtime-lockfile-arm64-v8a.json` (the overlay is in the running binary).
- **Smoking gun #1:** `world.log` contained **zero** interaction results and zero chat packets around the L1 presses — the trigger never reached the server.

### Phase 2 — Root cause #1: solo PARTY addon message (23:00 – 23:40)

- Web research (era-correct sources: vanilla API archives, WoWInterface threads incl. Party Ability Bars' known "not in a party" spam, SendAddonMessage channel-behavior thread) confirmed: **the 1.12 client applies the same membership check as `SendChatMessage(..., "PARTY")` and silently blocks the send while solo.**
- The patch's own header comment had assumed the server-side intercept made solo PARTY traffic work — never true, and never wire-tested (tests pinned only the Lua string and conf keys).

### Phase 3 — Fix design verification (23:40 – 00:00)

Independent agent verification + an external adversarial review both checked the replacement transport against the actual 1.12.1 client:

| Claim | Verdict | Key evidence |
|---|---|---|
| `SendChatMessage(msg, "WHISPER", lang, target)` exists in 1.12, 4th arg = recipient | TRUE | Blizzard's own 1.12.1 `FrameXML/ChatFrame.lua:718,1950` uses the exact form |
| No hardware-event restriction on programmatic chat in 1.12 | TRUE | No secure/protected system anywhere in 1.12 UI; chat protection only arrived in 8.2.5 (2019) and never covered WHISPER. (Correction to an early framing: it did **not** arrive in 2.0 — 2.0 protected spells/targeting.) |
| Self-whisper (target = own name) transmits | TRUE (93–98%) | No self-check on any 1.12 send path; "No player named…" is a **server** response (`GlobalStrings.lua:1534`), proving no client-side name validation; era usage ("whisper yourself" tricks). No 2006 packet capture exists — flagged, then confirmed empirically on-device in Phase 6 |
| Tab `\t` survives `SendChatMessage` end-to-end | UNCERTAIN | Valid-chat-char tables (3.x-era test) permit bytes 1–9, but no 1.12-specific proof → **design changed to printable `:` delimiter to delete the risk** (functional no-op; both sides change together) |
| Outgoing whisper line renders only from server echo | TRUE | 1.12 `ChatEdit_SendText` has no local echo; display driven by `CHAT_MSG_WHISPER_INFORM` from the server → an intercepted whisper is invisible |

### Phase 4 — Fix implementation (v1 transport) and deployment (00:00 – 00:15)

Edits (all pinned by updated tests):

- `Core.lua:351-357` — handler → `SendChatMessage("PR6I:1 INTERACT", "WHISPER", nil, UnitName("player"))`.
- `PocketRealmInteraction.cpp` — renamed to `HandlePocketRealmChatTrigger(to, message)`: exact `kRequest` match; case-insensitive self-name check (`to` vs `GetPlayer()->GetName()`); non-matching whispers fall through untouched (visible = self-diagnosing). Interaction internals untouched.
- `tools/build_o09_realm_runtime.py` — `WorldSession.h` declaration updated; chat hook anchor moved from the `CHAT_MSG_PARTY` case to `CHAT_MSG_WHISPER` (`ChatHandler.cpp:228-235`), inserted before the empty-guard and before `ParseCommands`/`CheckChatMessage`/`Whisper()` so a consumed trigger suppresses **both** the recipient `CHAT_MSG_WHISPER` and the sender `CHAT_MSG_WHISPER_INFORM`. Deliberately **no** `lang == LANG_ADDON` condition (the trigger arrives with a real language; the old condition would silently kill it). Anchor uniqueness pre-verified (exactly 1 occurrence).
- `ServerRuntimeFiles.kt` — `LogFileLevel 1 → 2` (later found insufficient; see Phase 5).
- Tests updated: `VanillaConsolePortAssetTest.kt` (pins new Lua, forbids any `SendAddonMessage`), `tests/test_pocket_realm_nearby_interact.py` (pins whisper anchor + hook, forbids the old name and the LANG_ADDON gating regression, still forbids `GetPlayer()->Whisper` in the patch).
- Native rebuild `--abi arm64-v8a` (first launch attempt failed from a wrong working directory; relaunched from repo root — completed cleanly, submodule restored pristine). New staged hashes: world `4f042ba9…7f9b19` (was `19781dbc…59bc54`), realmd `738d9715…ae76ed`; lockfile rewritten by the build script and verified to match.
- APK rebuilt, **verified by content**: new Lua line present in APK assets; APK-embedded `libpocket_world_runtime.so` sha256 == new lockfile hash. Upgrade-installed (`install -r`) at ~00:12; processes restarted 00:12:46–00:13:54.

Test results: pytest 8/8 passed; `VanillaConsolePortAssetTest` passed.

### Phase 5 — Second failure and the observability gap (00:15 – 00:35)

- User tested: L1 still appeared dead. Logcat confirmed presses arrived (00:18:39).
- `world.log` showed zero `CHAT: packet received` lines — **but this was not evidence**: bot *Detail*-level lines were appearing while the chat trace (`DEBUG_LOG`) was not. Reading the fork's `Log.h:30-36` showed `LOG_LVL_DEBUG = 3`; `LogFileLevel = 2` only enables Detail. **Both** the chat-packet trace and the patch's own result line are `DEBUG_LOG` → level 2 was one level short (my miss; recorded in §6).
- Direct experiments while observability was still broken: two adb F7 keystroke injections (`input keyevent 141`) bypassing the gamepad path; screenshot analysis (image model) confirmed the game running in Northshire and **no** `To [char-1]: PR6I…` line in chat and no error text — consistent with either "addon never fired" or "fired and silently consumed" (undecidable at level 2).

### Phase 6 — Diagnostic build (v2) and decisive evidence (00:33 – 00:45)

Bundled changes (Kotlin only — no native rebuild needed):

- `LogFileLevel 2 → 3` (DEBUG now lands in `world.log`).
- **F7 pulse dwell:** `NearbyUsePulse` now injects F7 down immediately and schedules the up after `imeKeyDwellMs` (50 ms) through the contract's scheduler, guarded by generation — mirroring the qualified IME paced-pulse pattern (instantaneous down+up pairs are a documented swallowed class on this lane; see "paced input qualification boundary" in the O14 docs).
- **L3 change (user request):** vanilla preset `L3: AUTO_RUN → POINTER_RIGHT` ("replace auto run with the other mouse button"), with the migration machinery done properly: the previously *derived* legacy map `v11VanillaConsolePortDirectBindingNames` frozen as a literal (it would have silently drifted), a new frozen legacy map added for the pre-change preset (`v11VanillaConsolePortNearbyUseBindingNames`) so the on-device stored profile upgrades automatically, and the `v11VanillaConsolePortDefault` match extended. Auto-run remains available via the touch overlay; the classic layout and all Select chords are unaffected. `InputContractTest` updated (test renamed; L3 now asserts pointer-right pairs) and docs updated.
- APK verified by content (`LogFileLevel = 3` present in dex, no stale literal), upgrade-installed, world restarted 00:35:55 with the new conf.

**Decisive evidence collected:**

```
2026-08-15 00:38:54 CHAT: packet received. type 6, lang 7          ← the self-whisper ARRIVES (whisper, Common)
2026-08-15 00:38:54 Pocket Realm nearby interact result: OK_LOOT   ← server found a corpse and executed the loot
```

Result distribution across the user's real L1 presses and two adb-injected F7 presses:

| Result | Count | Meaning |
|---|---|---|
| `NO_TARGET` | 10 | Nothing eligible in range (early presses, empty areas, one adb-injected press) |
| `OK_LOOT` | 19 | Corpse found, `HandleLootOpcode` executed, `SMSG_LOOT_RESPONSE` sent |
| `THROTTLED` / `DISABLED` / `BLOCKED` | 0 | — |

User's functional report: **L3 right-click works** (window opens, auto-loots — a genuine client-initiated cycle `CMSG_LOOT → CMSG_LOOT_MONEY → CMSG_LOOT_RELEASE` confirmed in `world.log` at 00:45:29); **L1 produces no visible window** despite server-side `OK_LOOT`.

### Phase 7 — Root cause #2: the client ignores server-initiated loot windows (00:45 – 01:00)

Proof by elimination plus client source:

1. The working L3 flow and the invisible L1 flow run the **identical server code path**: `HandleLootOpcode` (`LootHandler.cpp:90-155`) → `sLootMgr.GetLoot` → `Loot::ShowContentTo` (`LootMgr.cpp:1459`) → builds `SMSG_LOOT_RESPONSE` and calls `plr->SendDirectMessage(data)` **unconditionally** (lines 1489-1498). So the packet *is* being sent for L1.
2. The only difference is initiator: L3's client sent `CMSG_LOOT` itself; L1's was synthesized server-side.
3. The 1.12 client's own UI opens the window only on the `LOOT_OPENED` client event (`FrameXML/LootFrame.lua:5-20`, `ShowUIPanel`), which the client's C-side fires for loot responses it requested. An unsolicited response is silently dropped — matching 19 invisible `OK_LOOT`s.
4. "Select the container" is additionally impossible from the server: 1.12 target selection is client-authoritative (`CMSG_SET_SELECTION`); no server packet sets the target frame.

The same reasoning predicts the **chest path (Opening spell) also cannot show a window** — untested on a real chest this session, flagged in §5.

---

## 4. Current state (what works / what doesn't)

**Works (verified on-device):**
- L1 → F7 → addon → self-whisper transport, fully silent (no chat trace), solo or grouped.
- Server-side intercept, cooldown, eligibility (range/LOS/loot rights), candidate priority.
- Server-side *opening* of the loot session: the player is registered as looting, loot content is computed and sent (`SMSG_LOOT_RESPONSE`), and the state stays consistent — repeated presses re-open cleanly (12 consecutive `OK_LOOT` on the same corpse), and a subsequent real client loot cycle (`CMSG_LOOT → MONEY → RELEASE`) still works normally afterward. Nothing is collected and the corpse is **not** emptied — the items wait for a client request that never comes, which is exactly the visible-window gap.
- L3 = right-click at pointer (window + auto-loot), R3 = left-click select; auto-run still on the touch overlay; classic layout and Select chords untouched.
- Full build chain: native overlay rebuild → lockfile → APK → upgrade install, all hash-verified.

**Broken:** the visible outcome of L1 — no target selection (impossible via protocol) and no loot window (client-gated).

**Known-not-tested:** chest path (`OK_USE` via Opening spell) — never pressed near a real chest; expected to have the same window problem by the same mechanism.

---

## 5. Open questions / honest unknowns

1. **Was the 50 ms F7 dwell necessary?** All successful tests ran with the dwell installed. No test isolated the whisper transport *without* it (the pre-dwell attempts were unobservable due to the log-level gap). Not worth re-litigating unless pulses misbehave.
2. **Chest path** untested live (see above).
3. **x86_64 lockfile intentionally stale** (arm-only decision): `schemas/realm-runtime-lockfile.json` still pins the pre-whisper build; nothing validates cross-ABI, so nothing fails, but an emulator build would run the old PARTY-based code until rebuilt.
4. The on-device `profile_v11` auto-upgraded to the L3-pointer-right preset on first load of the 00:33 build via the new frozen legacy map — verified by unit test *and* confirmed on-device (the user's L3 acts as right-click on that build; had the migration failed it would still be auto-run).
5. **Unexplained, probably unrelated:** at the exact second of the first adb F7 injection (00:25:00), `world.log` shows a playerbot (`Mesarut`) accepting a group invitation. Most likely the bot system processing a queued/stale invite from earlier party-based testing; no bearing on the L1 chain (that injection produced no observable packet at the then-current log level).

---

## 6. Process corrections (recorded to prevent repeats)

- **Truncated evidence:** the initial prefs dump was head-truncated, producing a wrong-but-confident root cause (`CUSTOM`/`KEY_0`). Corrected by listing *all* keys before concluding. Rule: enumerate the full key space of any prefs/registry before diagnosing.
- **Log-level semantics assumed, not read:** `LogFileLevel = 2` was assumed to enable `DEBUG_LOG`. The fork's `LogLevel` enum (1=Basic, 2=Detail, 3=Debug) means DEBUG needs 3. One silent-observability level cost a full test round. Rule: read the log macro/enum, not the doc memory.
- **Piped exit codes:** a background build "succeeded" (exit 0) because the pipe masked the real failure (wrong working directory). Rule: never pipe long-running build commands whose exit code matters.

---

## 7. Resolution — client loot-acceptance companion patch (2026-08-14)

The earlier recommendation (server-driven auto-loot) was superseded: the approved direction was to fix the **client** so a legitimate server-opened loot response enters the normal loot lifecycle, keeping Pocket Realm's responsibility at *select + open* and leaving auto-loot to Vanilla Fixes.

### 7.1 What the client actually does (reverse-engineered from the authorized image)

Analysis target: `.tmp/wow_re/WoW.exe`, sha256 `b4756d38ef207c02ed651f4952bd89a70b4857b73a33413339e1b285b28d2dc7` (identical to the device's managed pristine client). PE32, image base `0x400000`, file offset = VA − `0x400000` throughout `.text`.

1. **Dispatch:** a switch on the opcode (`lea eax,[esi-0x160]; cmp eax,5; jmp [eax*4+0x5E6110]`) maps `SMSG_LOOT_RESPONSE` (0x160) to case `0x5E605B` → handler **`0x5EB900`** (packet-reader calls `0x4190B0` ReadGuid, `0x418CB0` ReadByte, `0x418EB0`/`0x418E30` ReadDWORD).
2. **The gate (`0x5EB924-0x5EB944`):** the handler reads the packet GUID + loot-type byte, then loads the *pending loot GUID* from `this+0x1D28/0x1D2C`. That field is written in exactly one client situation: when the client itself sends `CMSG_LOOT` (right-click) — the senders at `0x5DF1E0`/`0x5DF3A0` store the object GUID there (and play the interact sound `0x32`) alongside building the 0x15D packet. It is cleared again by the release-response handler (`0x5EC090`), the open path, and the release path.
   - pending GUID == packet GUID → normal open path (`0x5EB9F1`).
   - else, at `0x5EB944`: if pending GUID was nonzero → straight to release; if zero → the type byte is tested against {2,3,4} (pickpocket/fishing-style *spontaneous* loots, which arrive with no client request) and those open too.
   - everything else → `0x5EB963`: the client builds **`CMSG_LOOT_RELEASE`** (0x15F) carrying the corpse GUID, sends it, zeroes the pending GUID, returns. No window. This is the previously-invisible "silent ignore": the client actively declined, and the server dutifully closed the loot session — matching every observed symptom (server `OK_LOOT`, client nothing, loot re-openable on the next press).
3. **Why L3 works and L1 didn't:** identical server code path (`HandleLootOpcode` → `ShowContentTo` → identical `SMSG_LOOT_RESPONSE` bytes); the only difference is whether the client had pre-stored the GUID, i.e. the *initiator*.
4. **The open path** (`0x5EB9F4` → `0x5EBAFC`): reads gold + item count (clamped 16) + items into the client loot table (`0xC4D4E8`, 24 bytes/slot), looks up the object by GUID, stores the packet GUID as the new pending/current-loot GUID, sets gold/type on the object, and continues into the vanilla UI/event flow that ends in `LOOT_OPENED` → `LootFrame` (FrameXML `LootFrame.lua:5-20`). Downstream of the gate there is no other client-initiated-state dependency — proven by the fact that spontaneous type-2/3/4 loots already flow through this same path with no prior client request.

### 7.2 The patch (2 bytes)

In the fallback branch, widen acceptance from {2,3,4} to "any nonzero type" (this fork's `ClientLootType`: corpse=1, pickpocket=2, fishing=3; 0 is never sent):

| File offset | VA | Original | Patched | Meaning |
|---|---|---|---|---|
| `0x1EB94C` | `0x5EB94C` | `02` | `01` | `cmp al,2` → `cmp al,1` |
| `0x1EB94E` | `0x5EB94E` | `84` | `83` | `je +0xA1` → `jae +0xA1` (same target `0x5EB9F4`) |

The 24 original bytes at `0x1EB944` (`0B C1 8A 45 FE 75 18 3C 02 0F 84 A1 00 00 00 3C 03 0F 84 99 00 00 00 3C`) occur **exactly once** in the 4.7 MB image. Effects:

- Client-initiated loot (the GUID-match branch) is byte-for-byte untouched.
- Server-initiated loot (L1) with type ≥ 1 now enters the vanilla open path; the auto-release branch remains reachable only for type 0 (never sent) and stays intact.
- If a response arrives while a *different* pending loot exists (mashed L3+L1), the response now opens instead of auto-releasing — the desired behavior.
- Loot-type values above 4, if ever introduced, are accepted too (safe: same open path).

### 7.3 Where it lives

- `ClientTweaks.kt` — `applyNearbyLootAcceptPatch` (signature-verified, occurrence-count-verified, fail-closed) and `expectedPublishedPatchedBytes` (vanilla-tweaks model + companion patch = the only authorized published image).
- `WineRuntimeStore.applyTweaks` — runs the upstream patcher, applies the companion patch to its output, fsyncs, then byte-compares against the model; manifest gains `nearbyLootAccept: true`. The patched-exe cache self-invalidates because the model bytes changed.
- Tests: `ClientTweaksNearbyLootAcceptTest` (7 cases: exact-two-byte diff, surrounding bytes untouched, composition with the vanilla-tweaks model, and three fail-closed paths). Full unit suite green (517 tests).
- Semantics note: the companion patch applies whenever a `WoW.exe.patched` is built (any tweak enabled, e.g. the common preset). With *all* tweaks disabled the app deliberately launches the pristine exe — and then L1's window cannot appear (documented pristine-mode trade-off).

### 7.4 Verification status

- ✅ Static: patched-image disassembly shows `cmp al,1; jae 0x5EB9F4`; signature unique; size unchanged; PE checks pass; reference patched image (loot patch only) sha256 `53ab5d310753997b7fb6b7dde1decbc53d974a1e7c9d9279661529c1ce5b745f`.
- ✅ Host: unit tests green; APK assembled (arm64-v8a).
- ⏳ On-device (blocked at writing time — device off-network): `adb install -r`, relaunch (expect the patched exe to rebuild + attestation to pass), then the matrix: L1 on lootable corpse → window + Vanilla Fixes auto-loot, `world.log` `OK_LOOT` with **no** `CMSG_LOOT_RELEASE` immediately after (the pre-fix log signature showed the release); combat-target-held test; nearest-of-two-corpse test; negative tests (`NO_TARGET`, out-of-range, no-LOS ignored, cooldown); chest live test (predicted working via same gate; to be observed, not assumed); regressions L3/R3/R1/manual loot.

---

## Appendix A — Key file references

| File | Role |
|---|---|
| `android/app/src/main/java/com/pocketrealm/client/InputContract.kt:1339` | L1 → F7 pulse (now paced) |
| `android/app/src/main/java/com/pocketrealm/client/InputProfile.kt:360-466` | vanilla preset + frozen legacy maps (L1=NEARBY_USE, L3=POINTER_RIGHT) |
| `android/app/src/main/java/com/pocketrealm/client/ClientDisplayHost.kt:1113-1175` | gamepad bridge + gates |
| `android/app/src/main/assets/addons/vanilla-console-port/VanillaConsolePort/Core.lua:351-357` | trigger handler (self-whisper) |
| `android/app/src/main/assets/addons/vanilla-console-port/VanillaConsolePort/Bindings.xml:44` | F7 binding declaration |
| `android/app/src/main/java/com/pocketrealm/addons/AddonRuntimeProjector.kt` | per-launch projection |
| `android/app/src/main/java/com/pocketrealm/server/ServerRuntimeFiles.kt` | mangosd.conf (`LogFileLevel = 3`, `PocketRealm.NearbyInteract = 1`) |
| `native/patches/cmangos/PocketRealmInteraction.cpp` | trigger intercept + candidate selection + execution |
| `tools/build_o09_realm_runtime.py:103-164` | overlay anchors (WorldSession.h decls, WHISPER-case hook) |
| `native/cmangos/src/game/Chat/ChatHandler.cpp:228-235` | hook site (pristine submodule) |
| `native/cmangos/src/game/Loot/LootHandler.cpp:90-155` | `HandleLootOpcode` |
| `native/cmangos/src/game/Loot/LootMgr.cpp:1459-1498` | `ShowContentTo` → `SMSG_LOOT_RESPONSE` |
| `.tmp/Blizzard-WoW-Interface/1.12.1/FrameXML/LootFrame.lua:5-20` | client loot-window event logic |
| `native/cmangos/src/shared/Log/Log.h:30-36` | log-level enum (DEBUG=3) |
| `android/app/src/main/java/com/pocketrealm/client/ClientTweaks.kt` | loot-accept companion patch (signature + model) |
| `android/app/src/main/java/com/pocketrealm/client/WineRuntimeStore.kt:1396` | `applyTweaks` — patcher → companion patch → byte verification |
| `android/app/src/test/java/com/pocketrealm/client/ClientTweaksNearbyLootAcceptTest.kt` | companion-patch contract tests |
| `tests/test_pocket_realm_nearby_interact.py`, `VanillaConsolePortAssetTest.kt`, `InputContractTest.kt` | contract pins |

## Appendix B — Build/deploy identity chain

| Artifact | Value |
|---|---|
| Pre-fix world lib sha256 | `19781dbce316a00eb1a79d977fc0cb4f02024274e1139441613811616595bc54` |
| Post-fix world lib sha256 | `4f042ba98c47342a27c62e1a2a518c181ef2959daa94aecab569f759217f9b19` |
| Post-fix realmd lib sha256 | `738d971580b257a3098806fe0695de2095f1a78c172ba4aa97bd301f65ae76ed` |
| Client root (device) | `/data/data/com.pocketrealm/no_backup/client/generations/fcda3cb3-2b23-4e42-a524-2b68b8b4fdd2` |
| Server run dir (device) | `/data/data/com.pocketrealm/no_backup/server/run` (conf) · `…/logs/world.log` |
| Installs | 22:28:09 (initial dirty-tree), ~00:12 (whisper build), ~00:33 (diagnostic build) — all `adb install -r`, data preserved |
