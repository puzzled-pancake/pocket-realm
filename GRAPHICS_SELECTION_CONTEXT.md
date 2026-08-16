# Graphics selection context — 2026-08-16 session compression

This file is the complete handoff for the renderer/GPU-selection work. Read it
instead of re-deriving from the session transcript. Companion artifacts:
`docs/research/gl-calls/CONTEXT_NOTES.md` (gladio transport history) and
`C:\pocket_realm_graphics_backup\` (source backup of all graphics work).

## 1. What happened, in order

1. **Phase-4 transport patches (gladio v6/v7)**: the external research agent's
   production patch set was translated into the repo pipelines (client as
   `tools/patches/gladio-phase4-transport.patch` chained in
   `tools/build_gladio_client.py`; server applied directly in
   `native/xserver-winlator/cpp/{src/ring_buffer.c,include/ring_buffer.h,`
   `gladiorenderer/*}`). v6 pins deployed. On-device it ran but was
   **super slow on both devices** (~4k GL req/s vs ~36k on v5).
2. **Root cause of the slowness**: the phase-2 ring hardening replaced the old
   constant ~100 µs consumer poll with a power-saving ladder (64 yields, then
   sleeps 125 µs→2 ms). Its own comment says "500 timed wakes/second" — that
   ceiling is the throttle. **Fixed** by restoring the 100 µs cadence
   (`RING_WAIT_INITIAL_SLEEP_US/RING_WAIT_MAX_SLEEP_US = 100`) on both ends:
   server `native/xserver-winlator/cpp/src/ring_buffer.c` (rides in
   **libwinlator.so**, which gladiorenderer links dynamically — the
   gladiorenderer .so hash is unchanged by design) and the client patch.
   Client rebuilt as **v7**: 498656 B,
   sha256 `c02fb7275463bebcc3aa3fcf3e8e6de668bd2e6f39bda57052d3352801636d08`.
3. **Pixel 6a bring-up (new test device)**: wireless adb, fresh install,
   original client imported from `C:\Vanilla wow 1.12.1` through the app's own
   importer. World first-boot is slow (~20 min) and was repeatedly killed by
   (a) a stop-press during load (30 s control timeout → self-kill) and (b) RAM
   pressure storms on the 6 GB device. **Game crashed at launch** until
   root-caused: SELinux `avc denied { execmod }` on wine's PE ntdll.dll —
   Android 17's untrusted_app_34 policy denies wine's map-relocate-execmark
   pattern. **Fixed by targetSdk 35→27** (legacy SELinux domain grants
   execmod) in `android/app/build.gradle.kts` (overridable via
   `-PpocketTargetSdk`). After that WoW.exe ran and gladio streamed (~4.1k
   req/s — see the throttle above).
4. **Winlator-style auto-selection rework (this branch's main change)**:
   replaced the RP6-device-model qualification with wow-mobile/Winlator
   behaviour. Files: `VulkanDriverCatalog.kt` (schema 4),
   `ArmClientRendererCatalog.kt` (renderer schema 3), new `ArmRendererAuto.kt`,
   `Settings.kt` (migrations + snapshot helpers), `AndroidRuntimeBackend.kt`,
   `HomeScreen/LanScreen/SettingsScreen`, tests. AUTO default: Adreno
   (`ro.hardware.egl` reflection) → DXVK+Turnip; other GPUs → DXVK+system
   Vortek **if our capability probe passes**, else LEGACY_GLADIO. Manual
   selections exact/fail-closed. Three reviewer rounds: 5 findings fixed,
   final round NO FINDINGS. Unit tests green; APK (targetSdk 27 + v7 client +
   ring fix + auto-selection) installed on the Pixel 2026-08-16 10:06.
5. **The crash on the Pixel after install**: the session record
   (`no_backup/wine/last-session.json`) shows `renderer:"opengl"`,
   `vulkanDriverId:null`, 78 frames, then supervisor teardown. **The auto
   resolver fell back to Gladio.** Verified mechanism: Pixel `ro.hardware.egl`
   = `mali` → non-Adreno → the Vulkan probe itself SUCCEEDS, but
   `VulkanDriverCatalog.compatibility()` (VulkanDriverCatalog.kt:277) requires
   `nativeTextureCompressionBC == true` and **Mali-G78 reports VK_FALSE**
   (mobile GPUs do ETC2/ASTC; BC is an optional Vulkan feature). So auto →
   Gladio → the crash/lag the user saw.

## 2. The key divergence from wow-mobile

Our system-Vortek lane carries OUR OWN hardening gate —
`VORTEK_REQUIRED_DEVICE_EXTENSIONS` (8 extensions) plus the **BC texture
compression requirement** — from the earlier system-Vortek qualification work.
**wow-mobile/Winlator's Vortek bridge has no such gate**: their default
non-Adreno path runs Vortek on Mali as-is and their games work (README shows
60 fps on Pixel 8, same GPU family). Our gate is the only reason auto lands on
Gladio on the Pixel.

## 3. TARGET DESIGN (user directive, 2026-08-16) — IMPLEMENTED 2026-08-16

- **Default = wow-mobile's GPU handling, verbatim semantics:**
  - Adreno → DXVK + packaged Turnip (`turnip-26.1.0`).
  - Every other GPU (Mali, PowerVR, Xclipse…) → DXVK + system Vortek
    (`system-vulkan-vortek-2.1`). **No BC requirement, no extension gate, no
    device-model gate on the AUTO path.** The Vulkan API-version floor for the
    selected DXVK package remains the only sanity check (DXVK 2.4.1 needs
    Vulkan 1.3; if the device reports less, auto should fall back to the
    legacy DXVK 1.10.3 package first — winlator-style graceful degradation —
    and only then consider the OpenGL lane).
  - Keep wow-mobile's semantics: unknown/broken selections degrade to
    defaults with a notice, never hard-fail the default path.
- **DXVK independently changeable**: the DXVK package picker
  (`RendererPackageCatalog`, box64-dxvk-2.4.1 / 1.10.3) stays a separate
  manual setting, exactly as now (SettingsScreen "DXVK version").
- **OpenGL (Legacy Gladio) demoted to SUPER-optional**: not part of auto
  resolution at all. Hide it behind an "Advanced/experimental" disclosure in
  Settings (pattern exists: bots Advanced toggle), label it very experimental,
  keep it a manual exact selection. VirGL likewise stays manual/experimental.
- **What gets removed/changed concretely:**
  - `VulkanDriverCatalog.compatibility()` (VulkanDriverCatalog.kt:260-292):
    delete the `nativeTextureCompressionBC` check and the
    `VORTEK_REQUIRED_DEVICE_EXTENSIONS` hard requirement for AUTO; keep an
    API-version floor. If a hard capability profile is still wanted anywhere,
    keep it only for MANUAL system-Vortek selection (or delete entirely to
    match wow-mobile).
  - `ArmRendererAuto.vortekCompatibleWithDxvk()`: probe only the API floor;
    on floor failure with the default 2.4.1 package, retry with the legacy
    1.10.3 package before ever choosing LEGACY_GLADIO.
  - `ArmRendererAuto.resolve()`: the Gladio fallback leg should effectively
    never trigger (only if Vulkan is entirely absent/broken).
  - SettingsScreen: move LEGACY_GLADIO + MESA_VIRGL chips under an
    "Experimental renderers" disclosure; AUTO + DXVK remain the visible
    defaults.
  - Update `VulkanDriverCatalogTest` capability tests to the new policy.
  - Re-run the reviewer loop (fresh reviewer after each fix round until zero
    findings) — established workflow this session.

## 4. Current pins / build state (do not re-derive)

- Gladio client **v7**: `gladio-eaa2a8d-arm64-glibc-gles-v7`, 498656 B,
  sha256 `c02fb7275463bebcc3aa3fcf3e8e6de668bd2e6f39bda57052d3352801636d08`
  (pinned in `build_gladio_client.py`, `ArmClientRendererCatalog.kt`,
  `build.gradle.kts`).
- Gladio server: build id `gladio-eaa2a8d-android-gles-server-f6f6a5db`
  (libgladiorenderer.so unchanged); **the 100 µs ring fix is inside
  libwinlator.so** (448520 B, sha256
  `7adf1c9af0144f6c36dfc8ed6e7dd99de1426f355bcbe2fc3fd8a2034354a2f7`) —
  gladiorenderer links it dynamically; gradle does NOT content-pin
  libwinlator.so.
- targetSdk = 27 (required for wine execmod on Android 17; RP6/Android 13
  worked at 35 but 27 is harmless there).
- Selection schemas: VulkanDriverCatalog **4**, ArmClientRendererCatalog **3**.
- Backup of all graphics work: `C:\pocket_realm_graphics_backup\`
  (`graphics-work-2026-08-16.tar.gz` + `graphics-work-extra.tar.gz`).
- Devices: Pixel 6a `adb-REDACTED-DEVICE._adb-tls-connect._tcp` (Mali,
  Android 17, 6 GB, app installed 10:06 with everything above);
  Retroid Pocket 6 `adb-REDACTED-DEVICE._adb-tls-connect._tcp` (Adreno 740,
  Android 13, still running the older v6 targetSdk-35 build unless updated).
- Build: `cd android && ./gradlew :app:assembleDebug -PpocketAbi=arm64-v8a`;
  install: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`.

## 5. Evidence pointers

- Crash session record: Pixel `no_backup/wine/last-session.json`
  (renderer "opengl", stderr "Error initializing native libEGL.so.1",
  "MIT-SHM missing").
- BC denial chain: `AndroidSystemVulkanProbe.kt` → `GPUHelper.probeSystemVulkan`
  → `native/xserver-winlator/cpp/src/gpu_helper.c:98`
  (`features.textureCompressionBC`) → `VulkanDriverCatalog.kt:277` gate.
- wow-mobile reference clone: `native/.providers-extracted/wow-mobile/`
  (see `app/src/main/java/com/winlator/container/GraphicsDrivers.java` —
  `getDefaultDriver`, no capability gates; `Provisioner.java` for their launch
  tuning; `GraphicsDriverPicker.java` for their two-axis manual UI).
- Reviewer loop: 3 rounds, findings fixed in
  `ArmRendererAuto.kt` / `AndroidRuntimeBackend.kt` / `HomeScreen.kt`;
  final round NO FINDINGS (round-3 agent id agent_c8636de3).
- Ring throttle evidence: RP6/Pixel heartbeats ~4.1k req/s; fixed constants
  in `ring_buffer.c:38-40` and the client patch.

## 6. Implementation status (2026-08-16, later session)

§3 is implemented and reviewed: `compatibility()` gates only on the DXVK
package's Vulkan API floor (BC + extension gates and
`VORTEK_REQUIRED_DEVICE_EXTENSIONS` deleted); `ArmRendererAuto.resolve()` is
always DXVK (no Gladio leg — a broken probe keeps the selection so launch
fails closed on the real error); `ArmRendererAuto.resolveAutoDxvkPackageId()`
degrades 2.4.1→1.10.3 on the auto path only (manual stays exact), used by
both preflight and launch in `AndroidRuntimeBackend.kt`; Gladio/VirGL sit
behind the "Experimental renderers" disclosure in SettingsScreen. Unit suite
green (new `ArmRendererAutoTest`), APK installed on the Pixel 6a. Fresh
code-reviewer pass: NO FINDINGS. Details in DECISIONS.md ("wow-mobile
default without capability gates; OpenGL manual-only").
