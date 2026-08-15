# CONTEXT COMPRESSION NOTES — Gladio/WoW 1.12.1 Android engagement
Written 2026-08-15 after Phase-2 agent return. Everything needed to continue without
re-deriving. Companion files live in this folder (`GL calls testing/`).

## 0. One-paragraph state

Goal: make the Gladio GL→GLES bridge render WoW 1.12.1 (build 5875) correctly on a
Retroid Pocket 6 with low overhead. Selectability + launch crashes are FIXED; the lane
reaches in-game. TWO open defects: (A) hard stall ~1 minute after entering the world
(deterministic-ish, both runs' final heartbeat window ended at request 352 =
glLoadMatrixf); (B) graphical glitches (water/terrain/lighting) unchanged by texgen
fix. Web agent's Phase-2 return (`phase2_*` files) found a VERIFIED ring-buffer
commit-skip-on-uint32-zero bug that is timing-compatible with the stall and delivered
a validated production patch set (T01/T02/R01/R02) + two diagnostic patches (D1/D2).
NEXT ACTION: translate + apply the production patch set via the repo pipelines,
re-apply the lost tail-ring instrumentation, rebuild, deploy, and run acceptance
(10 min / ≥18,000 frames, no starvation).

## 1. Architecture (verified)

- CLIENT: fake desktop `libGL.so.1`, glibc ARM64, loaded by Wine/WoW under Box64.
  Forwards every GL call over a 64MB shm ring (6-byte header: int16 code + int32
  size). ~95% fire-and-forget; sync round trips for glGetError/glGetString/
  glFinish/glReadPixels/buffer map. ONE global recursive mutex `gl_call_mutex`
  serializes all client GL calls (per-context lockCount makes recursion safe).
  Source: `native/.build-arm64/gladio-client/source/` — REGENERATED on every build
  from upstream git (brunodev85/gladio @ eaa2a8d, cloned at
  `native/.providers-extracted/gladio-source`) + anchored patches inside
  `tools/build_gladio_client.py` (X11 socket env var, GL 3.0/GLSL 1.30 profile,
  pixel-store spans, POCKET_DRAW_ATTR framing, BGRA offsets) + patch-file chain
  `tools/patches/gladio-phase2-gl_calls.patch` (sha-locked 7e1f99d3…) and
  `tools/patches/gladio-wow-texgen-gl_calls.patch` (sha-locked 614693d1… =
  deployed v5 gl_calls.c). Built in Docker image ghcr.io/termux/package-builder-cgct.
  Direct edits to the source tree are WIPED on rebuild — all client changes must go
  through the build script's patch chain.
- SERVER: `libgladiorenderer.so`, Android NDK, hosted in the app's X-server process
  (JNI com.winlator.xserver.extensions.GLXExtension). Source IS the repo tree
  `native/xserver-winlator/cpp/gladiorenderer/` (direct edits, no regeneration).
  Single request-handler thread + 4-thread pool; fixed-function emulated via
  shader "material" system (shader_material.c keyed on lighting/fog/alpha/texGen/
  texture count). Server's ring implementation = SHARED `native/xserver-winlator/
  cpp/src/ring_buffer.c` + `cpp/include/ring_buffer.h` (client has its OWN copy in
  its tree — the two headers differ only CRLF vs LF per agent's normalized diff).
- Pins (must move as a matched pair): `ArmClientRendererCatalog.kt`
  (GLADIO_BUILD_ID / CLIENT_SHA256 / SERVER_BUILD_ID / SERVER_SHA256),
  `android/app/build.gradle.kts` (~line 361 server size+sha, ~line 567 client
  size+sha), `tools/build_gladio_client.py` `select_target` arm64
  TARGET_EXPECTED_SHA256. Client build fails closed on output sha; update flow =
  build once → read new sha from RuntimeError → pin → rebuild green.
- DEPLOYED NOW on device: client v5 = `1a634a5d9259a87188979a29d93b098edf09e8ee
  1639b7fb05e446e31327e865` (497,808 bytes); server = `1ffa75ce4f2dd45b85feb83c
  5f5db5208a496d5a89ef7a434833cfb8a9d76a28` (1,309,312 bytes).
- Renderer selection: Settings chips → `ArmClientRendererCatalog.availability()`
  (ALL renderers selectable on arm64 now; probe informational) → launch gates all
  route through `requireRuntimeRenderer`. WineRuntimeStore installs the payload
  per-generation (`graphics/gladio/libGL.so.1.7.0` + libGL.so/libGL.so.1 symlinks).

## 2. Device/environment facts

- Retroid Pocket 6, kalama/SD8 Gen 2, Adreno driver 0676.53 (12/27/23), Android 13
  (API 33 — Path.of() is API 35, NEVER use it), 11.5GB RAM, debug build, run-as
  works, NO root (debuggerd/simpleperf denied; SELinux).
- In-app EGL capability probe can block forever on this device (clean-process probe
  passes). Workaround deployed: 3s watchdog in `AndroidGladioCapabilityProbe`.
- Wireless adb serial `adb-REDACTED-DEVICE._adb-tls-connect._tcp` DROPS
  periodically; mDNS stops advertising. Recovery: toggle wireless debugging on
  device, or USB (different serial — the `tools/install_rp6_when_available.ps1`
  script matches the WIRELESS serial only). An auto-resuming logcat capture is
  armed in background (waits for device, appends to `launch_capture.log`).
- uiautomator dump fails on this Compose app ("null root node") — use screenshots
  (adb exec-out screencap) + PIL crops instead. The analyze_image web tool is
  flaky with spaces/backslashes in URLs — copy images to a space-free path first.
- WoW session stderr/stdout tails are captured ONLY when the blocking native
  launcher returns: to flush at a stall, kill lingering wine processes
  (`run-as com.pocketrealm kill -9 <pids of *exe/wineserver>`) then read
  `no_backup/wine/last-session.json` stdoutTail/stderrTail.
- `ps -A | grep` output was once head-truncated → false "WoW.exe gone" conclusion.
  Always list app-uid processes fully (`ps -A | grep u0_a135`) before concluding.

## 3. Fixes already deployed (do not redo)

1. Renderer chips selectable + 3s probe watchdog (ArmClientRendererCatalog.kt).
2. Path.of→Paths.get (WineRuntimeStore.kt — 6 sites incl. installers+attestation).
3. Phase-2 research patch (tools/patches/gladio-phase2-gl_calls.patch): GL 2.1
   table 2.9 integer color/normal/secondary normalization, glMultiTexCoordPointerEXT
   client-active-texture fix, indexed client-state enables, clamped info-logs,
   glAreTexturesResident.
4. glTexGen{d,f,i}v + glLightModel{f,i}v implemented BOTH ends (server
   request_handler.c handlers + client forwarding in gladio-wow-texgen patch).
5. Spam guard: client logs unimplemented calls once per name (prevents stderr-pipe
   backpressure freeze).
6. Server instrumentation (deployed): heartbeat every 4096 requests (`PR/Gladio`
   tag, logcat) + swap log every 64 frames.
7. DECISIONS.md entry #49 documents the policy change + pins.

## 4. Evidence log (stall Defect A)

- Run 1 (pre-instrumentation): freeze in-game; wine audio kept streaming minutes
  after (PR/ALSA 10M+ frames, 0 underruns) → non-render threads alive. Recovered
  stderr: thousands of "gladio: unimplemented call glTexGenfv" + glLightModelfv
  (the spam → pipe-backpressure theory; guard deployed since).
- Run 2 (instrumented, client v4): death at 15:23:12, 3,948,544 requests consumed,
  final heartbeat window ended last=352 (glLoadMatrixf). No crash/OOM/ANR.
- Run 3 (client v5 + texgen + guard): death at 15:48:25, 2,084,864 requests,
  1,723 frames presented, last=352 again. stderr CLEAN (no crash words).
  lmkd negative at stall (VirGL's 15:07 kill-storm was different). Thread state at
  run-1 stall: server pool futex-IDLE (starved, healthy), connectors epoll-idle.
  WoW/box64 thread state NEVER captured at a stall (top open evidence gap).
- Request rate ~100–160k/s sustained (~5k/frame @30fps) — unexplained, possibly
  pathological redraw loop.
- Screenshots: `device_screenshots/` (23-25 in webagent-attachments) show glitch
  state around the 15:17-15:22 window.

## 5. Web agent Phase-2 return (2026-08-15, in this folder)

Files: `phase2_engineering_report.md` (full report), `phase2_production_combined.patch`
(T01+T02+R01+R02, 8 files, +376/−87, protocol-UNCHANGED), individual patches,
`phase2_corrected_sources.zip` (post-patch tree, sha-manifested in
`phase2_sha256_manifest.txt`), `phase2_validation_log.txt` (all dry-run/apply rc=0;
client ring passes gcc -Wall -Wextra -Werror + wrap test), diagnostic patches
D1 (skip host glFinish) / D2 (hide GL_ARB_fragment_program) — NOT for production.

HEADLINE FINDING (VERIFIED mechanism, HYPOTHESIS attribution): both ring headers
commit head/tail only when post-op value > 0 — if an operation ends EXACTLY on
uint32 zero (first 4GiB wrap; needs only ~71 MiB/s ≈ 2,060 bytes/request —
plausible since draws inline vertex data), the commit is SKIPPED → desync →
server decodes texture bytes as a request header → silent starvation freeze.
Timing-compatible with run 3 (≥57s, 1,210 req/frame). This is the best stall
candidate so far.

Patch contents:
- P2-T01 atomic client transport: RingBuffer_writeParts (one logical publication),
  checked writes for GL_SEND_TEXIMAGE + glEnd trailing write, RingBuffer_create
  sharedData fix (free/EXIT never worked — mapping pointer never stored!), power-of-
  two capacity validation, unaligned header load/store fix, peer-socket death
  detection, first-send-failure reporting. Client files: gladio.h, ring_buffer.c/.h,
  gl_calls.c, main.c.
- P2-T02 server ring hardening: activates existing peer-fd polling (createGLContext
  never passed clientFd!), explicit boolean commit sentinels replacing zero-checks
  (THE wrap fix), EXIT on wrap-buffer alloc failure. Files: server gl_context.c +
  shared ring_buffer.h.
- P2-R01: glEnableVertexAttribArray now mirrors into client VAO like disable does.
- P2-R02: GL_LUMINANCE_ALPHA → GL_RG + swizzle in server texture_utils.h (matches
  upstream Gladio commit 7044e42; may fix black/alpha-wrong legacy textures).
Acceptance: 10 min / ≥18,000 frames no starvation; zero send-failed/EXIT messages;
client last-sent == server last-consumed modulo pending replies; run D1/D2
SEPARATELY from production (never combine first comparison).
Deferred by agent (needs more source files): A08 per-unit texture binding state,
A10/A11 ARB-program mirror (needs client gl_client_state.h/gl_vao.*).

## 6. How to apply the agent's patches to THIS repo (translation required)

The patches are written against attachment filenames. Translation:
- Client hunks (files 02/03/04/05/06 → gladio.h, ring_buffer.c/.h, gl_calls.c,
  main.c): the repo client tree is REGENERATED — create
  `tools/patches/gladio-phase4-transport.patch` by diffing regenerated-v5 tree vs
  the corrected zip's same files, chain it in `tools/build_gladio_client.py`
  prepare_source after phase-3 (git apply + new sha lock, same pattern).
- Server hunks (09/17/19 → gladiorenderer/src/gl_context.c,
  gladiorenderer/include/texture_utils.h, cpp/include/ring_buffer.h): apply
  directly to the repo tree (verify hunks apply — file 09 in the patch is the
  deployed-reverted copy which matches repo-with-tail-ring-REVERTED; repo currently
  HAS the tail-ring edit — see §7).
- Then: rebuild client (docker, update 3 pins incl. build id v6), rebuild server
  (`python tools/build_xserver_winlator.py --abi arm64-v8a`, update gradle + catalog
  server pins), `cd android && ./gradlew :app:assembleDebug -PpocketAbi=arm64-v8a`,
  install data-preserving (`adb install -r`, or install_rp6_when_available.ps1),
  verify data via run-as sha256sums (account/client/addons records).

## 7. Lost work warning + runbook remnants

- The tail-ring instrumentation (client gladioTraceSend last-32-sent 1/sec stderr;
  server heartbeat last-64-consumed tailCodes) was edited into the tree then LOST
  when gradle re-ran the client build (tree wipe). Exact edit contents are in the
  session transcript (python edit blocks around "Add tail-ring tracing"). Server
  gl_context.c tail-ring edit is STILL in the repo tree — REVERT it before applying
  agent patch 09 (or port the tail ring as phase-5 after).
- Auto-resuming logcat capture armed (background): appends to `launch_capture.log`
  when device returns.
- At next stall: capture WoW/box64 THREAD state (run-as, /proc/<pid>/task wchan+
  syscall+utime 2s delta — the never-collected evidence), then flush stderr tails
  via wine-service kill, then read heartbeat tails.

## 8. Open items ranked

1. Apply production patch set (§6) + rebuild + deploy + acceptance run (10min/18k
   frames). If stall persists WITH tails instrumented, tail sequences identify the
   wedge directly.
2. Re-apply/keep tail-ring instrumentation for the acceptance run.
3. D1/D2 diagnostic A/B runs (separately!) for stall attribution / ARB-fp visuals.
4. Defect B glitches: after R02, capture the still-stubbed call list (once-per-name
   guard output) + compare Northshire/login screenshots; next suspects per agent:
   texture data/primary color/texenv state, ARB fragment program path (D2 tests),
   then A08/A10/A11 (need extra files for the agent: gl_client_state.h, gl_vao.*,
   server gl_texture internals, GLRenderer/GLClientState/GLTexture definitions).
5. VirGL lane parked: instant critical-OOM at launch (lmkd kill storm, 5s after
   client start) — separate investigation.
6. Session supervision gap: WoW death/stall leaves frozen screen; tracker blocks
   until wine tree drains; orphaned wine services never exit. Needs drain timeout
   + process-death detection (app-side robustness, independent of renderer).

## 9. Folder map (`GL calls testing/`)

- launch_capture.log — cumulative device logcat (all runs; stalls at 15:23:12 /
  15:48:25 windows).
- research_plan.md / phase2_corrections.md — round-1 agent paper + patch notes.
- gl_calls.phase2-safe.c / gl_calls.phase3.c — client source evolution snapshots.
- webagent-attachments/ — 27-file self-contained bundle sent to agent (deployed
  sources sha-verified + case file + log excerpts + screenshots + self-check).
- device_screenshots/ — tester's manual screenshots.
- phase2_* / diagnostic_* — round-2 agent return (§5).
- installed.apk, ui/screen dumps, eglprobe.* — earlier diagnostics (eglprobe binary
  + source proves clean-process EGL passes on device).
- CONTEXT_NOTES.md — this file.
