# Merged Plan v2 (fixed) — (A) Toggleable vanilla-tweaks + sound × (B) User-chosen realm account + auto-login

Two independent features merged into one coordinated plan. Both ride on the current uncommitted ARM working tree.
This v2 applies the full code-verified review: 5 must-fix errors corrected, 6 citation corrections, 6 gaps closed. Every line reference below was verified against the tree on `agent/glm-o14-continuation-2026-08-05`.

---

## Step 0 — Checkpoint (first action, approved)

On `agent/glm-o14-continuation-2026-08-05` (no new branch): stage the working tree and create checkpoint commit `checkpoint: pre-O23 working tree before vanilla-tweaks + auto-login`. **No commits after this unless asked.**

---

## 0. Merge review (verified against code)

Fully orthogonal at the runtime layer; three shared files take purely additive edits:

| Shared file | Feature A edit | Feature B edit |
|---|---|---|
| `storage/Settings.kt` | adds `Snapshot.tweaks`, `audioMode` | adds `autoLoginOnLaunch`, `autoLoginAdvanced`, `autoLoginTimings` |
| `ui/SettingsScreen.kt` | adds "Client tweaks" + "Sound" cards | adds "Auto-login" card |
| `supervisor/AndroidRuntimeBackend.kt` `startClient` | rewrites `"audioMode"` literals `:295` & `:308` | rewrites `singlePlayerAutoLogin` computation `:273-274` (already passed as 3rd `prepare` arg `:302`; AIDL unchanged) |

`audioMode`/`singlePlayerAutoLogin` fully decoupled. Feature A runtime = `ClientRuntimeService.kt` + `WineRuntimeStore.kt` + `X86DirectWineRuntime.kt`; Feature B runtime = `IntegratedClientDisplay.kt` + `ClientDisplayHost.kt` + `InputContract.kt` + `SinglePlayerAutoLogin.kt`.

**Out-of-box defaults after the change:** `autoLoginOnLaunch = true`, `audioMode = OFF` (until Milestone 3 qualifies), all client tweaks = upstream defaults (on).

---

# Part A — Toggleable vanilla-tweaks + end-to-end sound

Patched exe is a **root-level `WoW.exe.patched` sibling** (no MPQ risk, no manifest rewrite, no `ManagedClientStore` change), **byte-signature pre-verification** (locale guard), **`audioMode` default OFF** until the audio backend qualifies, reuse of the repo's extracted Winlator ALSA assets.

### A.1 Vendor + build the patcher
- `native/vanilla-tweaks/` (upstream `brndd/vanilla-tweaks` `src/main.rs` master, v1.6.0 pin, `Cargo.toml`, `LICENSE.txt` — **MIT**, provenance pin with commit hash).
- **Prerequisites (NEW — repo has zero Rust tooling today; `build_o11_extractors.py` is CMake/Ninja, not cargo):**
  1. `rustup target add aarch64-linux-android x86_64-linux-android` (host cargo 1.96.0 confirmed present).
  2. NDK clang/`ar` linker config via `.cargo/config.toml` in the vendored dir (or env in the build script): `[target.aarch64-linux-android] linker = "<NDK>/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android<api>-clang"`, same for `x86_64-linux-android`.
  3. Expected DT_NEEDED: bionic `libc.so`/`libdl.so`/`libm.so` only (Rust std statically linked) — fits the o11 allowlist convention.
- `tools/build_vanilla_tweaks.py`: `cargo build --release --target {aarch64,x86_64}-linux-android`, strip, rename `libpocket_vanilla_tweaks.so`, stage to a staging dir added to the `required` native-closure list in `android/app/build.gradle.kts:107-175`, 16 KiB LOAD-alignment check, `BUILD_PROVENANCE.json` + lockfile — conventions mirrored from `tools/build_o11_extractors.py:137-174`.

### A.2 `ClientTweaksConfig` + DataStore *(shared: `Settings.kt`)*
- New `client/ClientTweaks.kt`: one Boolean per patch + numeric overrides, defaults = upstream (all on). `toFlags(): List<String>` emits `--no-<patch>`/`--<param> <value>`. `expectedOriginalBytes(): Map<Int,Byte>` — offset/expected-byte table extracted from the pinned upstream source (provenance tied to the vendored commit).
- `Settings.kt`: add to `Snapshot`:
  - `tweaks: ClientTweaksConfig` — **single JSON-string key** (decided; new pattern for this file — no JSON-string precedent exists today; org.json is used app-wide. Recorded in DECISIONS.md).
  - `audioMode: AudioMode` enum (`OFF` default) — **enum-name string key following the `Renderer` pattern** (enum declared like `:29`; read-back like `:145-151` with `runCatching { AudioMode.valueOf(...) }.getOrDefault(AudioMode.OFF)`), NOT JSON.
- Reference patterns: `inputSafeMode` is stored as **int 1/0** (decl `:44`, write `:77`, read `:161`); `botAdvanced` nested class = flat `intPreferencesKey`s (`:89-98`, read-back `:106-126`).

### A.3 Advanced UI *(shared: `SettingsScreen.kt`)*
New "Client tweaks" `SettingCard` (one `Switch` per boolean + `LabeledSlider`/`OutlinedTextField` for numerics) + a "Sound" row bound to `audioMode`, mirroring the input-safe-mode card (`:229-246`). Existing private helpers: `SettingCard` (`:432-440`), `LabeledSlider` (`:403-424`), and the `AdvancedBotControls` conditional-section pattern (`:167-226`, `:273-398`). No Switch-row helper exists — the `Row + Switch + Text` pattern is inlined each time.

### A.4 Runtime assembly *(shared: `AndroidRuntimeBackend.kt`)*
- `startClient`: source `tweaks` + `audioMode` from the existing `runtimeSettings` read (`:266`); replace the `"off"` literals (`:295` prepare / `:308` launch).
- `ClientRuntime.kt`: **add only `tweaks`** to `PrefixRequest` (`:25-31`) and `LaunchRequest` (`:39-45`) — **`audioMode: String = "off"` already exists at `:28` and `:42`**.
- Plumb: `X86DirectWineRuntime` JSON (`:72` prepare / `:88` launch) → `ClientRuntimeService.preparePrefix` (`:106`, read at `:116-121` via `optString`) / `launch` (`:129`) → `WineRuntimeStore.prepare` (`:73`) / `prepareArm` (`:243`).

### A.5 Patched exe — root-level sibling + byte-signature guard
New step in `WineRuntimeStore.prepare()`/`prepareArm()`, inserted **after** `enforceManagedSafeMode` (`:128` x86 / `:313` ARM) and before the prefix-manifest write:
1. Signature = `sha256(toFlags() + vanillaTweaksVersion + sha256(pristine managed.executable))` (sha256 helper exists at `:682-693`).
2. Target = `File(managed.root, "WoW.exe.patched")` (root-level sibling). If exists + `.signature` sidecar matches → reuse. Else: **pre-verify** each `(offset, expectedByte)` vs pristine → on mismatch (non-enUS locale) **abort**, log `VAL-LOCALE-TWEAK-MISMATCH`, fall back to pristine exe; run patcher → **rename upstream `WoW_tweaked.exe` output → `WoW.exe.patched`** (upstream writes `<name>_tweaked.exe` next to the input per its README; confirm/rename during vendoring); PE sanity re-check; on failure fall back to pristine + warn.
3. **Runner: `WineRuntimeStore.runCheckedProcess` (`:558-577`)** — non-suspend, already used for `libpocket_zstd_exec.so` (`:379-387`). (`DataPreparationStore.runTool` `:155-187` is `private` + import-scoped — NOT usable.)
4. **Executable override: `return p.copy(executable = patchedFile)`** from the patch step. **Do NOT change `:67`/`:234`** — those run inside `paths()`/`armPaths()` *before* the patch step, and the existence checks at `:127` (x86) / `:307` (ARM) would fail on first run. `Prepared` is a data class (`:18-32`) so `.copy` is clean. `workingDir = managed.root` unchanged. Manifest writer (`:130-172` / `:315-352`) records the patched path + signature.
5. Validated design facts: `ManagedClientStore.load` never walks the directory (extras tolerated) and hash-pins only pristine `WoW.exe` (`:64`); `enforceManagedSafeMode` already mutates the managed root post-validation (precedent); MPQ discovery co-located with `Data/` (confirm in the Milestone 3 spike).
6. When `audioMode=OFF`, force `soundInBackground=false` + `soundChannels=false` in `toFlags()`.

### A.6 Unblock audio (default OFF) — complete site list (verified)
- Guards → `audioMode in {ON, OFF}`: `ClientRuntimeService.kt:154` (`"O06 requires audio-off"`), `WineRuntimeStore.kt:94`, `:259`.
- Launch env:
  - `ClientRuntimeService.kt:279` — `POCKET_AUDIO_MODE=off` (read by `pocket_selftest.c:78`) → mode value.
  - `:280` — x86, **comma separator**: `WINEDLLOVERRIDES=winealsa.drv=d,winepulse.drv=d`.
  - `:344` — Box64, semicolons.
  - **`:482-489` — FEX has TWO branches: `:483` (dxvk) and `:488` (opengl)** — both carry the literal.
  - When ON: drop the `winealsa.drv=d;winepulse.drv=d` entries, add `ANDROID_ALSA_SERVER=<socket>` + `ANDROID_ASERVER_USE_SHM=1` (neither exists today; `ANDROID_SYSVSHM_SERVER` precedent at `:338`/`:471`).
- `WineRuntimeStore.kt:892`/`:898` — `managed-safe-profile.json` `.put("audio", "off")` → mode value.
- `WineRuntimeStore.kt:115`/`:135`/`:345` — `audio_mode` manifest entries → mode value.
- `enforceManagedSafeMode` (`:875-878`) + `ClientGenerationStore` (`:195-198`): when ON write `Sound_EnableAllSound "1"`, Music/SFX/Ambience `"1"`, `SoundSoftwareChannels "64"`. **Keep `SAFE_CONFIG` byte-identical** (sound-on write is a separate conditional path) — `ClientBuild5875LoginTest.kt:114` pins the current sound-off content.
- FMOD warning at import if `fmod.dll` absent (fmod.dll is classification-only in `STANDARD_DLLS` — `ManagedClientImporter.kt:173-175`, `SafClientScanner.kt:190` — so the warning is additive, never blocking).

### A.7 Wire up existing Winlator ALSA stack
**Corrected source map (verified):**
- `native/.providers-extracted/winlator-app-ca3d735/`: `ALSAClient.java`, `ALSARequestHandler.java` (+`ALSAClientConnectionHandler`, `RequestCodes`), `ALSAServerComponent.java` (+`SysVSharedMemoryComponent`), `sysvshared_memory.c` (+header). (AudioTrack-based.)
- `native/.providers-extracted/winlator-ludashi-source/`: `audio_plugin/module_pcm_android_aserver.c` + `alsa.conf` + `android_asound.conf` hook pair + `CMakeLists.txt`; `app/src/main/cpp/winlator/alsa_client.c` — **the only file with `#include <aaudio/AAudio.h>` (line 1)**; plus a second alsaserver Java tree at `com/winlator/cmod/alsaserver/`.
- `native/.glibc-build/glibc-packages/packages/alsa-lib/build.sh:18` (verbatim): *"pcm interface uses sysv semaphore which is broken on Android 14+"* — avoid SysV semaphores. Same comment at `alsa-plugins/build.sh:21`.
- Shipped x86_64 `native/.build-x86_64/wine-staging/jniLibs/libwine_unix_winealsa.so` is a real driver, DT_NEEDED includes `libasound.so.2` — but **`libasound.so.2` is staged nowhere today**.

- **3.1 Spike (gate):** build alsa-lib/alsa-plugins (CGCT container); stage `libasound.so.2` + `libasound_module_pcm_android_aserver.so` into jniLibs **and add both to the `build.gradle.kts` required-closure list (`:107-175`)**; generate `$HOME/.asoundrc` (`pcm.!default type android_aserver`) adapted from the ludashi `alsa.conf`/`android_aserver.conf` pair (no `asound.conf` exists in the repo) → HOME = `rootfs/home/xuser` (ARM), runtime-root (x86). Confirm the shipped `winealsa.so` opens the plugin. **Lane scope: x86-direct + Box64 only** (no arm64 `winealsa` for FEX/ARM64EC).
- **3.2:** port `ALSAClient`/`ALSARequestHandler` (+ sysvshm deps) into `com.pocketrealm.audio/` — **lineage (ca3d735 vs ludashi `cmod`) decided during the spike** (decided: defer). Lifecycle hooks (ClientRuntimeService has no sidecar mechanism today): env construction at `:276-287` (x86) / `:334-375` (Box64) / `:467-519` (FEX); a tracked component field sibling to `armProcess` (`:27`); teardown in `cancelActiveRuntime()` (`:586-595`).
- **3.3:** replace the AudioTrack stage with **AAudio** (adapt ludashi `alsa_client.c`) or Oboe over AAudio — `Usage::Game`, low latency, `SharingMode::Exclusive`→MMAP when granted, native 48 kHz, lock-free SPSC, audio thread on a little core. MMAP-exclusive = measured best case; shared-mode fallback.
- **3.4:** on-device qualify (frame timing, audio-thread CPU%, latency, thermal); if acceptable flip `audioMode` default ON; graduate `libasound.so.2` (from `RUNTIME_OPTIONAL`, `check_wine_dtneeded.py:56-61`) and `winealsa.so` (from `OPTIONAL_WINE_MODULES`, `:178-189`) to required.

### A.8 Tests / docs / telemetry
- JVM: synthetic PE32 stub (template: `tools/build_selftest_pe.py` — already a 32-bit PE linking winmm) — each `--no-*` leaves its offset original, on-state flips it, LAA bit sets; pre-verify rejects a stub with mismatched bytes.
- Instrumented: toggle vector → `WoW.exe.patched` exists + sidecar matches; pristine `WoW.exe` sha256 unchanged.
- Docs: `DECISIONS.md` (incl. the tweaks-JSON-key decision + 3-process DataStore note), `FEATURES.json` +`O23` (verified: max today is O22 — additive), MIT attribution (vanilla-tweaks) + LGPL-2.1 attribution (Winlator audio components); G5 telemetry captures `audioMode`, MMAP availability, audio-thread CPU%, tweaks signature.

---

# Part B — User-chosen realm account + auto-login, with tunable timing

### B.1 DXVK (verified, no change)
`Snapshot.renderer = Renderer.DXVK` (`storage/Settings.kt:34`) → ARM resolver `"dxvk"` (`AndroidRuntimeBackend.kt:285-289`); x86 hard-pinned `wined3d` (`WineRuntimeStore.kt:93`). **No edits.**

### B.2 User-chosen account → auto-login
- **NEW `supervisor/UserAccountStore.kt`** — mirrors `supervisor/SinglePlayerCredentialStore.kt` (verified pattern: JSON, `SCHEMA = 1` enforced on read, temp file + `fd.sync()` + `Os.rename` + directory fsync at `:78-98`, `Os.chmod` 0600 file / 0700 dir at `:80`/`:94`, redacted `toString` at `:117-119`). API: `save(username, password, accountId)`, `loadProvisioned(): UserAccount?`, `clear()`. Validation reuses realm rules (`DurableRuntimeSupervisor.provisionAccount:137-145`: 1..16 ASCII alnum user/pass, gm 0..3). Save only on `ACCOUNT_CREATED` + `accountId>0`; never on `ACCOUNT_EXISTS`.
- **MODIFY `ui/HomeScreen.kt`** — capture `savedUsername`/`savedPassword` **before** `createAccount(...)` (`:165`; existing `password=""` at `:167` would wipe it). Branch on the result code (verified codes: `ACCOUNT_CREATED`/`ACCOUNT_EXISTS` from `AndroidRuntimeBackend.kt:185-209`); on `ACCOUNT_CREATED` + `accountId>0`: `withContext(Dispatchers.IO){ UserAccountStore(context).save(...) }`. Stored-account line + "Clear" button. Re-create overwrites; `ACCOUNT_EXISTS` warns, doesn't store.
- **MODIFY `supervisor/AndroidRuntimeBackend.kt` `startClient`** *(shared)* — `:273-274`:
  ```
  val userAccountProvisioned = UserAccountStore(appContext).loadProvisioned() != null
  val isBotProfile = BotProfiles.find(profileId) != null
  val singlePlayerAutoLogin = runtimeSettings.autoLoginOnLaunch && (isBotProfile || userAccountProvisioned)
  if (singlePlayerAutoLogin && !userAccountProvisioned) ensureSinglePlayerAccount(owner)
  ```
  Extract pure `resolveAutoLogin(profileId, autoLoginOnLaunch, userAccountProvisioned)` into its own dependency-free file (`supervisor/AutoLoginPolicy.kt`) so the host-JVM test doesn't load Android classes. **Reuse existing 3rd `prepare` arg** (`IClientDisplayControl.aidl:8`, passed `:302`) → no AIDL change.
- **MODIFY `client/IntegratedClientDisplay.kt` `prepare()`** — **DELETE** `requireNotNull(...)` (`:77-82`; note `guarded` `:178-186` already converts the throw to `{"ok":false}` — today it fails soft); replace with null-safe precedence: user account → single-player store → null (log + skip, never throw). `ClientDisplayHost` already accepts null `autoLoginCredentials` (`:40`, consumed `:212`). Read timings: `runBlocking(Dispatchers.IO){ Settings(applicationContext).flow.first().autoLoginTimings }` — runBlocking-in-Binder-stub pattern per `service/RealmService.kt:111`; the Settings-read pattern per `AndroidRuntimeBackend.kt:266`. (Note: `RealmService.kt` is in `service/`, not `realm/`.)

### B.3 Full timing set behind "Advanced timing" toggle *(shared: `Settings.kt`, `SettingsScreen.kt`)*
- **`storage/Settings.kt`** — `data class AutoLoginTimings(...)` (9 `Long` + `requiredStablePolls: Int`), defaults = current constants. Add `Snapshot.autoLoginOnLaunch=true`, `autoLoginAdvanced=false` (int 1/0 keys à la `inputSafeMode`), `autoLoginTimings=AutoLoginTimings()` (flat int keys à la `botAdvanced` `:89-98`, Int↔Long `.toInt()`/`.toLong()`, each `?: default`). Defaults/ranges: `pollIntervalMs 250 (100–1000)`, `requiredStablePolls 4 (1–12)`, `loginUiSettleMs 8000 (1k–30k)`, `sessionTimeoutMs 300000 (60k–900k)`, `drainPollMs 50 (25–200)`, `inputDrainTimeoutMs 5000 (1k–30k)`, `imeKeyDwellMs 50 (20–200)`, `imeKeyGapMs 10 (0–100)`, `fieldSettleMs 300 (50–2000)`, `pointerDwellMs 80 (20–500)`.
- **`client/SinglePlayerAutoLogin.kt`** — append trailing-optional `timings: AutoLoginTimings = AutoLoginTimings()` (defaults = companion constants `:294-301`, **kept** — `SinglePlayerAutoLoginTest` pins 8000/300000 behaviorally); replace the **8 reference sites** (6 distinct constants — `POLL_MS` ×3 at `:206/:216/:224`, `REQUIRED_STABLE_POLLS` `:214`, `LOGIN_UI_SETTLE_MS` `:215`, `SESSION_TIMEOUT_MS` `:143`, `INPUT_DRAIN_TIMEOUT_MS` `:242`, `DRAIN_POLL_MS` `:248`) with `timings.*`.
- **`client/InputContract.kt`** — add 4 trailing-optional instance fields (`imeKeyDwellMs`, `imeKeyGapMs`, `fieldSettleMs`, `pointerDwellMs`) defaulting from the **unchanged** companion constants (`:941-944`: 50/10/300/80). Constructor is `(sink, scheduler)` (`:97-100`) — appending is source-compatible. Then: `:700` key dwell → `imeKeyDwellMs`; `:678` pointer dwell → `pointerDwellMs`; key-pulse `ImePulse(...)` at `:544/:564/:575/:636` → pass **`gapAfterMs = imeKeyGapMs` explicitly** (required — `ImePulse.gapAfterMs` default at `:162` references the companion and cannot see instance state); click-pulse `:624-629` → `gapAfterMs = fieldSettleMs` (**keep distinct — swapping breaks login**). `InputContractTest` pins companion names + `50L`/`10L` (`:613-628`, `:659`) → all hold.
- **`client/ClientDisplayHost.kt`** — add `timings: AutoLoginTimings = AutoLoginTimings()` **immediately before** the trailing-lambda `onWindowVisible` (`:41`); default keeps all **8** call sites compiling unchanged (`ClientScreen.kt:129` + **`IntegratedClientDisplay.kt:83`** + 6 androidTest: `O14TouchOverlayAcceptanceTest:83`, `O14InputContractTest:83`, `O14InputContractRelaunchTest:219`, `O14ImeTest:85`, `ClientRuntimeLifecycleTest:67`, `ClientBuild5875LoginTest:193`). Forward `timings` → controller (`:218`) + InputContract (`:126-131`).
- **`ui/SettingsScreen.kt`** *(shared)* — "Auto-login" `SettingCard` after "Input safe mode": master `Switch`→`autoLoginOnLaunch`; stored-account line + "Clear"; "Advanced timing" `Switch`→`autoLoginAdvanced`; when on, 10 `LabeledSlider`s + "Reset to defaults". Mirrors "Advanced bot tuning" (`:167-226`).

### B.4 Tests
NEW `UserAccountStoreTest` (host JVM): save/load/clear, redaction, schema, validation, perms, atomic write. NEW pure-decision `resolveAutoLogin(...)` test (in `supervisor/AutoLoginPolicy.kt`). NEW assertion: non-default `imeKeyGapMs` → `ImePulse.gapAfterMs == <override>`. Existing `InputContractTest`/`SinglePlayerAutoLoginTest` pass unchanged; no `androidTest` pins the gate → instrumentation unaffected.

### B.5 Decisions baked in (override if you disagree)
A1 user account wins everywhere; random identity = bot fallback only. A2 GM accounts may be auto-login identities. A3 re-create silently overwrites. A4 `ACCOUNT_EXISTS` cannot re-arm auto-login. A5 missing credentials at `prepare()` → skip, never throw.

### B.6 Invariants preserved (reworded, honest)
Password is never logged, journaled, placed in status JSON/evidence, or sent across Binder; `:supervisor` performs an existence check only (the in-memory JSON parse during `loadProvisioned()` is unavoidable and not disclosed). Bot-profile random path intact. DXVK default + x86 WineD3D lane untouched. All host-JVM tests pinning constant defaults remain valid. Note: reading Settings in `IntegratedClientDisplay` adds a third DataStore process reader (UI writes; `:supervisor` already reads at `:75`/`:266`) — read-mostly usage, recorded in DECISIONS.md.

---

# Consolidated build & verify

**Milestone 1 — JVM/Kotlin track (both features).** Part A.2–A.4, A.6, A.8(Kotlin tests) + all of Part B. One coherent pass (shared `Settings.kt`/`SettingsScreen.kt`). `:app:assembleDebug -PpocketAbi=x86_64` (single-ABI per APK, `:276`/`:346-348`) → proves compilation of every Kotlin change (incl. the 8 `ClientDisplayHost` call sites). `:app:testDebugUnitTest` → no regression + new `resolveAutoLogin`/`UserAccountStoreTest`/`imeKeyGapMs`/tweaks-stub tests.

**Milestone 2 — Native patcher (A only).** A.1 (incl. the rustup-target + NDK-linker prerequisites) + A.5 + A.8(native tests). Rust cross-compile; patched-exe-sibling + byte-signature guard.

**Milestone 3 — Audio backend (A only, device-gated).** A.7 spike (lineage decision + co-location confirmation) → wire up → AAudio/Oboe → qualify → flip default ON.

**Milestone 4 — Docs/telemetry.** A.8 + B-level doc updates.

**Honest gap (shared):** Milestone 1 does NOT exercise `UserAccountStore` file I/O, HomeScreen create→save, integrated-profile auto-login, custom-timing end-to-end, patched-exe launch, or audio output — all need the ARM Wine client + RP6. Optional on-device step (on request): rebuild ARM lane, reinstall on RP6, create account → confirm 5875 login window with non-default timing; toggle a tweak → confirm `WoW.exe.patched` regenerated + pristine hash unchanged.

# Consolidated risks
- **Merge seams:** verified additive; `SettingsScreen.kt` + `Settings.kt` edited in one coherent pass.
- **Feature A:** WoW MPQ discovery assumed CWD/co-located (confirm in A.7 spike); locale guard rejects non-enUS 5875 by design; upstream writes `WoW_tweaked.exe` (rename step in A.5); Rust-for-Android toolchain is net-new to the repo (A.1 prerequisites); Oboe MMAP-exclusive device-dependent; FEX/ARM64EC lane has no arm64 `winealsa` (audio = x86-direct + Box64 only).
- **Feature B:** pre-existing `createAccountNative` rc→code mapping — O12/O13 provisioning already proven, spot-check only. `IntegratedClientDisplay` reads Settings from a third process (noted).
- **Both:** end-to-end needs real ARM hardware (consolidated, not duplicated).

# Execution order
0. **Checkpoint commit (approved).** → 1. **Milestone 1 (JVM, both features)** → 2. **Milestone 2 (native patcher)** → 3. **Milestone 3 (audio backend)** → 4. **Milestone 4 (docs/telemetry).**

## Sources
- [brndd/vanilla-tweaks](https://github.com/brndd/vanilla-tweaks) (master, v1.6.0 pin, **MIT**)
- Winlator ALSA: `native/.providers-extracted/winlator-app-ca3d735/` (Java server, AudioTrack) + `native/.providers-extracted/winlator-ludashi-source/` (`module_pcm_android_aserver.c`, AAudio `alsa_client.c`, conf pair) — LGPL-2.1
- [1.12.1 FMOD sound-in-background RE (OwnedCore)](https://www.ownedcore.com/forums/world-of-warcraft/world-of-warcraft-bots-programs/wow-memory-editing/971821-1-12-1-sound-background.html)
- [Low-latency audio with Oboe](https://developer.android.com/games/sdk/oboe/low-latency-audio) · [AAudio/MMAP](https://source.android.com/docs/core/audio/aaudio)
