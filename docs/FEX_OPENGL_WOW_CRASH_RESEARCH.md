
# FEXCore + OpenGL/Wine/World of Warcraft 1.12.1 crash research

**Date:** 2026-08-10  
**Device lane:** physical Retroid Pocket 6, Android 13/API 33, `arm64-v8a`, 4 KiB pages  
**Build under investigation:** `realmRuntime` debug APK, FEXCore/ARM64EC Wine, Gladio OpenGL path  
**Status:** diagnosis/remediation record; the RP6 OpenGL login/relaunch visual gate now passes, while full O11/O14 gameplay acceptance remains open

## Executive conclusion

The current evidence does **not** support one single FEX-induced crash explanation. There are five separate problems at different layers:

1. The first direct Wine launch was invalid for this Android display topology. It produced `nodrv_CreateWindow`, WineD3D initialization failure, and WoW Error #132. Launching through Wine Explorer's virtual desktop fixed that specific failure.
2. The first ARM auto-login matcher treated Wine's 1920x1080 Explorer desktop as the game window. It could therefore type before the real WoW child existed. The matcher now distinguishes the desktop container from the non-desktop game drawable.
3. Gladio had an architecture-sensitive protocol bug: `GL_MAX_VIEWPORT_DIMS` returns two integers, but the bridge previously allocated/sent one. On Adreno this could overwrite the temporary buffer and return a zero height, after which WineD3D could set a permanent `0x0` viewport. The two-integer fix is present, and the clean RP6 run now renders a full-colour 1920x1080 login scene on both first launch and relaunch.
4. A second architecture-sensitive defect was in ARB vertex-program constants. WoW declares `PARAM c0 = { 255.002, 3, 0, 1 }`; the bridge wrapped that vector as `vec4(vec4(...))`, and the old parser failed to classify the nested form, uploading `[0,0,0,0]`. The explicit four-scalar parser now unwraps the nested vector and uploads the declared value. The clean RP6 screenshot changed from an all-magenta diagnostic/black 3-D scene to the expected animated login environment.
5. The latest physical-device process is not visibly crashing. WoW is sleeping with four threads, roughly 278 MiB RSS, and PPID 1, while the supervisor journal says the client timed out. This is an orphaned-child/state-reconciliation defect: the Android client service can report failure while Wine/wineserver/WoW remain alive.
6. The 700-bot telemetry is from an earlier run, not the current 10:52 run. It shows the admission controller shedding the effective target to 25 after repeated hard stalls while 656 bots remained online. The fresh CPU capture shows the database at roughly 100% and the world service around 25%, so server/database contention must be isolated before judging the renderer.

The correct product position is therefore: **FEXCore + Wine + Gladio OpenGL is a valid experimental architecture, but it is not yet the production renderer.** Keep DXVK selectable/default on the Adreno lane, keep OpenGL available for diagnosis, and make the launch supervisor and renderer capability contract deterministic before comparing performance.

## What is actually being translated

The stack is layered; OpenGL on Android is not a direct conversion of WoW's calls into Android Java APIs:

```text
WoW.exe (32-bit Windows PE)
        |
        | FEXCore ARM64EC/WoW64 execution
        v
ARM64EC Wine (Bionic-native)
        |
        | winex11.drv + Wine opengl32/WGL
        v
Gladio libGL.so.1 (Wine desktop-GL compatibility client)
        |
        | private X11/GLX transport
        v
Android Gladio server + EGL/GLES context
        |
        v
Adreno GLES/Turnip driver
```

FEX's official project describes itself as an ARM64 user-mode emulator that can run x86/x86-64 applications with Wine/Proton and forward OpenGL/Vulkan calls to host libraries ([FEX README](https://github.com/FEX-Emu/FEX)). The pinned Android implementation here is not the ordinary Linux FEX executable; it is ARM64EC Wine loading the pinned FEXCore Windows DLLs. That distinction matters when comparing logs or environment variables with desktop FEX.

FEX's official release notes also show that ARM64EC/Wine is still an active compatibility area, including controller-related crash fixes, WOW64/ARM64EC compiler work, and fixes involving simultaneous OpenGL/Vulkan thunking ([FEX releases](https://github.com/FEX-Emu/FEX/releases)). The local FEXCore pin must therefore be tested against a newer compatible pin as a controlled experiment, not assumed to be interchangeable with ordinary Linux FEX.

Wine implements Windows APIs on Unix and uses Unix/X11 equivalents ([Wine mirror](https://github.com/wine-mirror/wine)). WoW's `-opengl` switch therefore reaches Wine's WGL/GLX path. The local launcher deliberately follows the Winlator pattern:

```text
wine explorer /desktop=shell,1920x1080 Z:\...\WoW.exe -opengl
```

Winlator documents Windows applications through Wine and its current releases describe Gladio as an experimental OpenGL wrapper through GLES ([Winlator README](https://github.com/brunodev85/winlator/blob/main/README.md), [Winlator releases](https://github.com/brunodev85/winlator/releases)).

Android OpenGL ES is an embedded-device API exposed through EGL, not desktop OpenGL. Android's documentation requires an EGL display/config/context/surface and explicitly recommends checking context creation and EGL errors ([Android OpenGL ES guide](https://developer.android.com/develop/ui/views/graphics/opengl/about-opengl), [Android EGL/NDK APIs](https://developer.android.com/ndk/guides/stable_apis)). Khronos maintains separate desktop OpenGL and OpenGL ES registries and documents the API differences ([Khronos OpenGL ES registry](https://registry.khronos.org/OpenGL/index_es.php), [OpenGL ES differences](https://wikis.khronos.org/opengl/OpenGL_ES)). Gladio must consequently emulate the desktop feature/extension surface WineD3D expects; FEX alone cannot solve a missing GLX/WGL/GLES feature.

### Is OpenGL the right renderer for 1.12.1?

Yes, as a compatibility option. Vanilla 1.12.1 is a legacy Windows OpenGL/WGL application, so `WoW.exe -opengl` is semantically correct and useful for a Vulkan-less fallback. It does **not** mean that WoW is being rewritten to call Android GLES. The compatibility boundary is Wine's WGL/GLX implementation plus Gladio's desktop-GL-to-GLES bridge. That bridge must implement the old game's required context profiles, pixel formats, pbuffers, FBO behavior, extensions, and swap semantics. A device can have a healthy GLES 3.x driver and still fail this path if one desktop contract is missing. This is why OpenGL should remain selectable and diagnostic, while DXVK/Turnip remains the production candidate until the matrix below passes.

## Evidence collected

### Local artifacts

| Artifact | Meaning |
|---|---|
| `tmp/fex-explorer-command-probe.log` | 2.17 MiB source-matched Explorer launch with WGL/GLX/D3D tracing |
| `tmp/fex-wow-debug.log` | Earlier direct-launch failure path |
| `tmp/fex-qualified-wait.png` | Physical-device screenshot showing the Wine desktop/overlay while the game target was not yet safely qualified |
| `tmp/android-perf-20260810/perf.data` | 15-second `simpleperf` CPU-cycle recording; 20,878 samples, zero lost |
| `tmp/android-perf-20260810/simpleperf-report.txt` | Device-side report from that recording |
| `tmp/android-perf-20260810/cpuinfo.txt` | CPU snapshot during the live run |
| `tmp/android-perf-20260810/meminfo.txt` | App PSS/RSS/native/graphics snapshot |
| `tmp/android-perf-20260810/ps.txt` | Process ownership, elapsed time, RSS, and command lines |
| `tmp/android-perf-20260810/live-boot.json` | Earlier 700-bot telemetry; timestamp is preserved and explicitly treated as stale |
| `tmp/android-perf-20260810/last-session.json` | Current client-session state, showing the supervision/state mismatch |
| `tmp/android-perf-20260810/journal.json` | Durable supervisor record of the client timeout |
| `tmp/rp6-clean-parser-first2.png` / `tmp/rp6-clean-parser-relaunch2.png` | Fresh physical RP6 OpenGL first/relaunch captures after the ARB constant-parser fix |

No password, username, or other credentials are included in these artifacts.

### Direct launch versus Explorer launch

The direct launch trace contains the expected invalid-display failure:

```text
nodrv_CreateWindow Application tried to create a window, but no driver could be loaded
wined3d_caps_gl_ctx_create Failed to create a window
Failed to initialize wined3d
WoW Error #132 / 0xc0000005
```

The source-matched Explorer launch instead proves that the display plumbing is alive:

```text
GL version             : 3.0
GL renderer            : Gladio
GLX version            : 1.4
Direct rendering enabled: True
WGL_ARB_create_context
WGL_ARB_pbuffer
```

Wine first requests an OpenGL 4.4 context, Gladio rejects that request, and WineD3D falls back to a 3.2 context. The launch then reaches adapter capability initialization and remains alive until the bounded probe timeout (`POCKET_WOW_RC=124`). The timeout was intentional; it is not a WoW crash. The later `Failed to delete old context, last error 0x578` line is important but not independently fatal: context creation and `makeCurrent` had already succeeded. It needs a focused context-ownership test rather than being treated as proof of the crash.

### Live physical-device process evidence

At approximately 10:52 NZST, the device snapshot was:

| Process | CPU snapshot | RSS / state | Interpretation |
|---|---:|---:|---|
| `com.pocketrealm:database` | ~100% (19% user, 80% kernel) | ~110 MiB | dominant current CPU consumer |
| `com.pocketrealm:world` | ~25% (22% user, 2.5% kernel) | ~1.0 GiB | active server workload |
| main `com.pocketrealm` | ~6.9% | ~437 MiB | UI/supervisor process |
| `:supervisor` | ~0.8% | ~188 MiB | orchestration |
| `:realm` | ~0.2% | ~110 MiB | realm service |
| `mariadbd` | ~0.3% | ~179 MiB | native MariaDB child; Java database process owns most sampled work |
| WoW.exe | ~0.1% (0% user/kernel in one sample) | ~278 MiB, sleeping, 4 threads | not GPU/CPU busy; PPID 1 |

The CPU-cycle profile agrees with the snapshot: the database PID dominates the package-scoped samples, including `wait4`/kernel work; the WoW process is not a package-owned process and contributes no meaningful app-owned hotspot. Symbols are incomplete for the stripped native world libraries and kernel addresses are restricted, so this profile ranks consumers but does not identify a single database function.

The app's PSS snapshot was about 229 MiB with about 446 MiB RSS, 16.5 MiB native heap, 28.5 MiB Dalvik heap, 98 MiB Ashmem PSS, and 4.7 MiB graphics PSS. There is no OOM signature in this capture.

The current WoW status was:

```text
Name: WoW.exe
State: S (sleeping)
PPid: 1
VmRSS: 277988 kB
Threads: 4
Seccomp: 0
```

The current session file still says `STARTING`, `windowVisible=false`, while the durable supervisor journal records `CLIENT_FAILED` with `Timed out waiting for 90000 ms`. The descendant's PPID 1 proves that the Wine child outlived the process that was supposed to own it. This is a real lifecycle bug, not evidence of a rendering crash.

### 700-bot telemetry is a different run

The checked file reports:

```json
{
  "profile": "mobile-lively-b700-v1",
  "botsAvailable": 720,
  "botsOnline": 656,
  "effectiveBotTarget": 25,
  "selectedBotTarget": 700,
  "botTargetAdapted": true,
  "botAdmissionReason": "repeated-hard-stall;cooldown-or-floor",
  "worldTickP99Ms": 91,
  "worldPssMiB": 2904,
  "freeMemoryMiB": 4772,
  "thermalLevel": "none",
  "rendererReady": true,
  "windowVisible": true
}
```

Its `verifiedAtMs` converts to 2026-08-10 01:35:59 NZST, roughly nine hours before this research snapshot. It is valuable evidence about the admission controller, but it must not be used as the current renderer result. It shows that 700 is not currently sustainable as an *effective* target: the policy shed to 25 after repeated stalls, while 656 already-online bots remained. The next stress-test revision must distinguish selected target, effective target, online count, and bot-removal convergence.

## Confirmed defects and fixes

### 1. Invalid launch topology  fixed in the launcher

Directly invoking Wine with WoW gave Wine no usable X11 drawable. The fix is the virtual desktop launch, not a random delay or a renderer flag:

```text
wine explorer /desktop=shell,1920x1080 Z:\...\WoW.exe -opengl
```

This creates the desktop/display surface before the executable starts, matching the pinned Winlator launch shape. It is now implemented in `ClientRuntime.armFexClientArguments()` and used by `ClientRuntimeService`.

### 2. Desktop mistaken for game  fixed in the readiness gate

Wine Explorer creates a 1920x1080 desktop window. The game is a separate non-desktop child. The old matcher accepted the desktop as the game and could inject credentials too early. The current `AutoLoginWindow` snapshot carries a redacted `desktop` flag; the ARM matcher requires the exact non-desktop game target and accepts the Explorer desktop only as its container. `ClientDisplayHost` no longer reports the desktop alone as a visible game window.

### 3. `GL_MAX_VIEWPORT_DIMS` truncation  fixed and visually re-qualified

`GL_MAX_VIEWPORT_DIMS` has two `GLint` outputs. The old Gladio `GLRenderer_getParamsv()` path defaulted to one value. The request handler then allocated a four-byte temporary buffer, while GLES wrote eight bytes. The client received only the first integer. A zero second dimension explains the traced `glViewport(..., 0, 0)` and the black/no-swap behavior seen on the Adreno path.

The current tree adds:

```c
case GL_MAX_VIEWPORT_DIMS:
    paramSize = 2 * sizeof(GLint);
    break;
```

This is an ABI-neutral correctness fix, not an Adreno-specific clamp. The fresh physical run completed `ClientBuild5875LoginTest#loginWindowSurvivesCleanRelaunch` with the original strict test threshold and captured two 1920x1080 frames. The first frame had 1,929,795 non-black pixels, 1,208,595 chromatic pixels, and 197,964 unique RGB colours; the relaunch frame had 1,929,793 non-black pixels, 1,208,583 chromatic pixels, and 197,771 unique colours. This rules out the earlier grayscale false-pass and proves the login scene is rendering through the OpenGL bridge. It does not yet prove character/world gameplay.

### 4. ARB constant-vector parsing  fixed on the physical RP6

The relevant WoW vertex program is source-matched in the device log:

```text
PARAM c0 = { 255.002, 3, 0, 1 };
...
MOV R0.w, c0.w;
```

The old path generated `vec4(vec4(255.002, 3.0, 0.0, 1.0))` but only recognized a single `vec4(...)` wrapper. Its fallback left `ARBUniform.type` and `index` uninitialized and the GLES uniform remained zero. The parser now consumes the optional inner wrapper, parses four scalars with `strtof`, and only then falls back to `program.local`/`program.env` references. Device evidence after the fix reports `c0=[255.002 3 0 1]` and the clean screenshots show the complete animated login environment.

### 5. FEX/Wine child ownership  not fixed yet

The ARM path uses `ProcessBuilder` and stores only the top-level `wine` process in `armProcess`. `cancelActiveRuntime()` destroys that process, but does not recursively kill Explorer, wineserver, WoW, or other descendants. The current PPID 1 WoW/wineserver and the 90-second supervisor timeout are direct evidence.

The correct fix is a dedicated process-group/descendant owner for the FEX path, matching the already stronger x86 launcher contract:

* start the ARM Wine tree through a native supervisor that records the root PID and process-group/session identity;
* enumerate descendants on timeout/close and kill children before the root, then wait for wineserver and WoW to disappear;
* persist a terminal `FAILED`/`FORCE_STOPPED` session record with the actual exit/timeout reason;
* reject a new launch while an old tree remains, or perform an explicit owned-tree cleanup first;
* add an instrumentation test that forces the 90-second path and asserts no Wine descendants remain.

Until this is fixed, a black screen after the client failed can simply be an orphaned old Wine tree rather than a new renderer instance.

## Ranked remaining hypotheses

| Rank | Hypothesis | Evidence | Confidence |
|---:|---|---|---|
| 1 | FEX client supervision/state is losing the child tree | PPID 1 descendants, journal timeout, stale `STARTING` session | **Confirmed lifecycle defect** |
| 2 | Server/database load delays client/world progress | fresh DB ~100% CPU, world ~25%; 700 run shed to 25 after hard stalls | **Strong for the current stall; not a graphics proof** |
| 3 | Gladio/WineD3D capability mismatch beyond login | GL 4.4 request fails, 3.2 fallback works; pbuffer/context paths need coverage | **Plausible; gameplay still unqualified** |
| 4 | Missing desktop GL extension/feature in Gladio | Android path intentionally lacks some desktop features; MIT-SHM warning is present | **Possible; no causal error in login run** |
| 5 | FEX JIT/ARM64EC crash | no SIGILL/SIGSEGV/WoW Error in the current FEX stderr; process sleeps | **Low for this run** |
| 6 | Thermal or memory exhaustion | current thermal is not reported severe in the 700 artifact; free memory was 4.7 GiB; no OOM | **Low for this run** |

## Remediation plan

### Phase A  make every run attributable

1. Add a run UUID to client, server, and FEX session logs and write it into `last-session.json`, `live-boot.json`, and the supervisor journal.
2. Make `windowVisible` mean the accepted non-desktop WoW child, not any X window.
3. On timeout, kill the complete owned Wine tree and wait for zero descendants before marking the component terminal.
4. Make `last-session.json` transition atomically to `FAILED`, `EXITED`, or `FORCE_STOPPED`; never leave `STARTING` after the launch coroutine has returned.

### Phase B  prove the OpenGL bridge contract

1. Keep the `GL_MAX_VIEWPORT_DIMS` two-`GLint` fix and add a bounded diagnostic query for `GL_VIEWPORT`, `GL_MAX_VIEWPORT_DIMS`, `GL_DRAW_FRAMEBUFFER_BINDING`, `GL_READ_FRAMEBUFFER_BINDING`, `GL_FRAMEBUFFER_STATUS`, and `glGetError`.
2. Record the first successful WGL context version, current drawable size, FBO completeness, shader compile/link result, draw/clear counts, and first swap. Do not rely on a screenshot alone.
3. Verify all EGL context creation, `eglMakeCurrent`, `eglDestroyContext`, and native global-share registration calls. Android documents context creation failure and EGL error checking as explicit runtime conditions ([Android EGL guide](https://developer.android.com/games/agdk/configure-graphics), [GLSurfaceView reference](https://developer.android.com/reference/android/opengl/GLSurfaceView.html)).
4. Treat the 4.4-to-3.2 fallback as an expected capability negotiation only if the 3.2 context advertises the exact extensions used by WineD3D. If not, lower WineD3D's requested profile or extend Gladio; do not silently claim 4.4.
5. Test the pbuffer and mapped-window paths separately. A synthetic pbuffer must never be mistaken for the presentation drawable.

### Phase C  separate server contention from graphics

Run the same client matrix with the server in three states:

| Server state | Purpose |
|---|---|
| Database/realm only, zero bots | pure client/renderer baseline |
| LOW_25, target 25 | supported mobile baseline |
| LIVELY_700, target 700 | stress/admission experiment |

For each run capture CPU, PSS, free memory/storage, thermal state, world tick p50/p95/p99/max, hard stalls, selected/effective/online bots, and client first-frame time. A stress run is not a renderer qualification run. The admission controller must either actively reduce online bots or report that reduction is pending; lowering `effectiveBotTarget` alone while 656 bots remain online is not convergence.

### Phase D  renderer policy

* Keep OpenGL/Gladio as an explicit experimental choice.
* Keep DXVK/Turnip as the default candidate on the Adreno lane until the OpenGL matrix passes.
* On OpenGL probe failure, surface a bounded diagnostic and fall back to DXVK only after the old Wine tree has been cleaned up.
* Never attempt to make WoW call Android GLES directly. WoW must continue to use Wine WGL; Gladio is the compatibility boundary.

Valve's Proton documentation likewise treats WineD3D/OpenGL as a fallback renderer when Vulkan/DXVK is unavailable ([Proton changelog](https://github.com/ValveSoftware/Proton/wiki/Changelog)). That is consistent with making this path selectable rather than silently replacing the Vulkan path.

### Phase E  acceptance matrix

Acceptance requires all of the following on the physical RP6, with fresh run UUIDs:

1. FEX + OpenGL: launch, non-desktop WoW window, login screen, non-black first frame, character/world transition.
2. FEX + DXVK: same sequence and a renderer comparison.
3. Repeat each with zero bots and LOW_25; run LIVELY_700 separately as stress only.
4. Home/resume and USB keyboard attach/detach preserve the EGL share generation and do not recreate a black context.
5. A timed-out client leaves no Wine descendants and a subsequent launch starts cleanly.
6. Auto-login injects only after the accepted game topology has remained stable for the configured settle interval.

## Validation already completed

### 2026-08-10 standalone ARM OpenGL result

The first physical Retroid Pocket 6 run of the original 1.12.1 client reached
the WineD3D/Gladio path but failed in two bridge areas: the Adreno compressed
texture upload path and the ARB constant-vector parser. The former now uploads
software-decoded blocks as GLES-safe `GL_RGBA8`/`GL_RGBA` with checked DXT
dimensions/payloads and isolated pixel-store state. The latter now unwraps
the generated `vec4(vec4(...))` form and parses all four scalars with
`strtof`, so WoW's `PARAM c0 = { 255.002, 3, 0, 1 }` is not silently replaced
by zero.

On the physical RP6 (arm64-v8a, Android 13, 1920x1080 landscape), the clean
client-only OpenGL run now passes both first launch and clean relaunch with
the strict chromatic/diversity gate:

```text
ClientBuild5875LoginTest#loginWindowSurvivesCleanRelaunch
renderer=opengl
first/relaunch window: wow.exe 1920x1080
first:   non-black=1,929,795  chromatic=1,208,595  unique-RGB=197,964
relaunch: non-black=1,929,793 chromatic=1,208,583 unique-RGB=197,771
result: visual login/relaunch PASS on the physical RP6
```

The earlier 72-colour/zero-chromatic captures are retained as historical
failure evidence only; they are not acceptance evidence. This result proves
the standalone OpenGL client can reach and render the login scene. It does
not yet prove character creation, world entry, input/IME behavior, DXVK parity,
FEX process-tree cleanup, or any realm/bot workload; those remain separate
acceptance lanes.

### FEX + OpenGL selector run (2026-08-10)

The standalone test now accepts `-e pocketTranslator fex` and passes that
choice through probe, prefix preparation, and launch. The FEX lane exposed a
separate launch issue before graphics qualification: after the X socket-root
correction (`rootfs/usr/tmp`, matching the pinned ARM64EC image), Wine created
the 1920x1080 Explorer desktop and small helper windows, but no renderable
WoW child appeared during the 180-second bounded wait. The session was then
force-stopped; its captured stdout contained only normal Wine startup lines
and `X connection to :0 broken` during teardown, with no WoW crash signature.
The bounded stop also left two `winedevice.exe` children under PPID 1 until
test cleanup explicitly killed them, confirming that FEX process-tree ownership
is still incomplete.

Therefore the current evidence is:

* Box64 + OpenGL: physical RP6 login/relaunch visual PASS.
* FEX + OpenGL: provider selection and socket wiring exercised, but the game
  launch/topology gate is still unresolved; no FEX visual acceptance claim.

This distinction is intentional: a working Gladio framebuffer on the Box64
provider does not prove that the ARM64EC/FEX Wine launcher starts the same
32-bit client. The next FEX step is a bounded launcher/process trace (or a
source-matched FEX launch command) before changing renderer code.

The current tree has passed the targeted Kotlin/Android validation used for this investigation:

```text
./gradlew -PpocketAbi=arm64-v8a \
  :app:testDebugUnitTest \
  --tests com.pocketrealm.client.SinglePlayerAutoLoginTest \
  --tests com.pocketrealm.client.ClientRuntimeProviderTest \
  :app:compileDebugAndroidTestKotlin \
  :app:assembleRealmRuntime
```

The Gladio query fix is present in `native/xserver-winlator/cpp/gladiorenderer/src/gl_renderer.c`; the virtual-desktop launcher and topology matcher are in `android/app/src/main/java/com/pocketrealm/client/ClientRuntime.kt`, `ClientRuntimeService.kt`, `SinglePlayerAutoLogin.kt`, and `ClientDisplayHost.kt`. These are build/test results, not a claim that the physical OpenGL acceptance matrix is complete.

## Bottom line

The client-only OpenGL login/relaunch visual gate now passes on the physical
RP6. The next gate is character/world qualification using the same corrected
renderer, followed by FEX launch ownership, DXVK comparison, and realm/bot
stress as separate experiments. None of those later experiments may be used
to overstate the client-only login result.

## New external evidence: character-model shader fallback

The current RP6 symptom (complete login UI/background, but no character
preview) has a useful historical analogue. Multiple Wine/vanilla-WoW
troubleshooting references recommend setting `SET M2UseShaders "0"` when
OpenGL renders no character/object models or produces dark/incorrect model
passes. This is a diagnostic fallback, not a renderer proof: it asks WoW to
avoid its M2 shader path and can change visual quality. It should therefore be
tested as a separate Config.wtf variant, with the original file backed up and
the result compared against the same character-list screenshot gate. Sources:

* [Warcraft Wiki Wine troubleshooting — “No Object Textures/Models”](https://warcraft.wiki.gg/wiki/Wine_troubleshooting)
* [Vanilla WoW in Wine — M2UseShaders workaround](https://www.vanade.com/~blc/html/winewow/)
* [WINE + Vanilla WoW — OpenGL fixes](https://www.schotty.com/Games_And_Wine/Wine_Plus_Vanilla_WoW/)

The bridge-side investigation also now has a specification-backed invariant:
`program.env[]` is shared by every ARB vertex program in a GL context, while
`program.local[]` is object state. The Khronos specification states this
explicitly, and its attribute table aliases `vertex.weight` to generic
attribute 1. Any remaining translator implementation must preserve both rules;
snapshotting environment vectors into only the program that existed when the
write occurred, or mapping `vertex.weight` to a constant zero, is not conformant.

* [Khronos ARB_vertex_program specification — environment state](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_vertex_program.txt#L1924)
* [Khronos ARB_vertex_program specification — attribute aliases](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_vertex_program.txt#L2537)

The local character trace has not yet shown `v17` being consumed by the
candidate VP31–35 programs, so the `vertex.weight` alias remains a correctness
fix to prove, not a claimed root cause. The next device-only discriminator is
the bounded VP31–35 fixed-fragment magenta probe: a silhouette implicates the
fixed fragment/material path; no silhouette keeps the investigation in the
character vertex-input/state path. The RP6 was offline when that probe was
ready, so no result is being recorded.

The managed OpenGL profile now includes `SET M2UseShaders "0"` as this
reversible diagnostic fallback. The D3D/DXVK profile does not receive the
setting. The Android instrumentation config check compiles successfully; a
physical RP6 character-list result is still required before calling the
fallback effective.
