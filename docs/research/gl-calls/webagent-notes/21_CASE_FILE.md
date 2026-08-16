# CASE FILE — Gladio GL→GLES bridge: WoW 1.12.1 in-game stall + graphical glitches (Retroid Pocket 6)

(Companion to the source files in this folder. Everything here is also reproducible
from 22_STALL_LOG_EXCERPTS.txt and the screenshots 23–25.)

You are continuing a debugging engagement. You have NO repo/device access. Analyze the
evidence below and answer the questions in the final section. Label conclusions
VERIFIED (follows from evidence) vs HYPOTHESIS. Cite sources for external claims
(WoW 1.12 GL behavior, gl4es-class bugs, Qualcomm Adreno quirks, Wine/box64).

## 1. System under test

- Device: Retroid Pocket 6, Snapdragon 8 Gen 2 (kalama), Adreno (driver 0676.53,
  "OpenGL ES 3.2 V@0676.53" dated 12/27/23), Android 13 (API 33), 11.5GB RAM, no root.
- App runs full WoW vanilla 1.12.1 build 5875 on-device: cmangos-class realm+world+
  MariaDB in app processes; game client under Wine (glibc rootfs) via Box64, hosted by
  a supervisor app.
- Graphics lane under test: "Gladio" GL→GLES bridge. CLIENT = fake desktop libGL.so.1
  (glibc ARM64, loaded inside Wine/WoW under Box64) forwarding every GL call over a
  64MB shared-memory ring to a SERVER (libgladiorenderer.so, Android NDK, in the app's
  X-server process) that replays on a real EGL/GLES 3 context (system Adreno driver).
  Fire-and-forget for ~95% of calls; sync round trips only for calls returning data
  (glGetError/glGetString/glFinish/glReadPixels/buffer map). ONE global mutex
  (gl_call_mutex, recursive per-context) serializes all client GL calls across threads.
- Server consumes requests on a single handler thread + 4-thread pool; fixed-function
  pipeline emulated via generated shaders ("material" system keyed on lighting/fog/
  alpha-test/texGen/texture-count state).

## 2. Fixes already applied and VERIFIED working (do not re-litigate)

1. Renderer selectable in UI + 3s watchdog on the EGL capability probe (probe could
   block forever in-app on this device; identical EGL sequence passes from a clean
   process — confirmed with a compiled on-device EGL test binary).
2. `java.nio.file.Path.of()` → `Paths.get()` in the payload installers (Path.of is
   API 35; device is API 33 → NoSuchMethodError crashed client launch). After this
   fix both lanes LAUNCH and the Gladio lane reaches in-game.
3. Phase-2 research corrections in client gl_calls.c (integer color/normal
   normalization per GL 2.1 table 2.9, client-active-texture fix for
   glMultiTexCoordPointerEXT, indexed client-state enables, clamped info-log copies,
   glAreTexturesResident output). Client pinned v5.
4. glTexGen{d,f,i}v and glLightModel{f,i}v implemented on BOTH ends (were stubs;
   WoW calls glTexGenfv and glLightModelfv continuously — recovered stderr showed
   per-frame spam of "gladio: unimplemented call glTexGenfv" / glLightModelfv).
5. Spam guard: remaining unimplemented client calls log once per function name
   (prevents stderr-pipe backpressure).

## 3. The open defects

### Defect A — hard stall shortly after entering the world (blocks everything)

Three observed stalls (runs 1–3). Run 3 is the cleanest evidence (all fixes in,
stderr clean). Consistent signature across ALL runs:

- Server heartbeat instrumentation (logs every 4096 consumed requests) shows a
  sustained stream of ~100–160 THOUSAND requests/second, then the stream STOPS. In
  runs 2 and 3 the final heartbeat window ENDED with last consumed request code 352
  (= REQUEST_CODE_GL_LOAD_MATRIXF, i.e. glLoadMatrixf — but heartbeat granularity
  is 4096, so the true final request lies within [352, 352+4095]). Identical in both
  runs. Run 2: 3,948,544 requests consumed at death. Run 3: 2,084,864 consumed,
  1,723 frames presented.
- No crash evidence AT ALL in run 3: no SIGSEGV/Fatal signal in logcat, no wine
  "Unhandled exception" in stderr, no box64 crash output, no ANR, no lowmemorykiller
  activity at the stall moment (checked), no memory pressure (5.6GB free).
- Run 1 (pre-guard): while the display froze, wine audio kept streaming for minutes
  (ALSA server in the app process logged 10M+ accepted frames, zero underruns AFTER
  the freeze) → at least in run 1 the game/box64 process tree was alive with non-render
  threads running.
- Thread state at run-1 stall (app/X-server process): server thread pool (4 threads)
  futex-IDLE; X connectors epoll-IDLE; one native thread in nanosleep loop; one
  "GLThread"-named thread flipping between userspace-running and futex at ~15% CPU.
  I.e., the SERVER is healthy and starved: it is receiving no more requests.
- IMPORTANT GAP: the WoW/box64 process threads were NEVER inspected at a stall.
  The stall is client-side: WoW's render thread either (a) blocks acquiring the
  client library's global gl_call_mutex held by a sibling thread that died/was
  terminated (Windows TerminateThread does not release pthread mutexes),
  (b) blocks in a sync GL call whose reply handling wedges, (c) sits in an infinite
  loop inside the client library, or (d) the game process exits silently. The
  evidence (audio continued, no exit traces) leans (a)/(b)/(c) with the process alive.
- Session supervisor limitation: at stall the app shows the frozen frame indefinitely;
  the blocking native launcher only returns when the whole wine tree drains, and
  orphaned wine services never exit. Killing them post-hoc releases the tracker and
  flushes the game's stdout/stderr tails into last-session.json (how run 1's spam and
  run 3's clean stderr were recovered).
- Instrumentation NOW IN THE SOURCE (NOT yet built/deployed): client
  `gladioTraceSend` logs the last 32 sent request codes once per second to stderr;
  server heartbeat prints the trailing 64 consumed codes. Next stall will show the
  exact call sequence at the wedge from both ends.

### Defect B — graphical glitches in-game (water/terrain/lighting look wrong)

- Present identically in all runs, INCLUDING after implementing texgen + light-model
  forwarding — so texgen forwarding alone did not visibly change rendering
  (possibilities: server material path not selected for those draws, WoW water uses a
  different mechanism, or the visual issue is dominated by other untranslated state).
- Gladio server advertises GL 3.0 / GLSL 1.30; WoW 1.12 GL usage: fixed-function +
  client-side vertex arrays (no VBOs), multitexture terrain blending, DXT/palette BLP
  textures, dynamic lights. Still-stubbed functions exist (once-per-name logging now
  in place; full list not yet captured from a run).

### Defect C (parked) — Mesa VirGL lane instant-OOMs the whole device

- 5 seconds after client launch with renderer=virgl, kernel lowmemorykiller hits
  "critical pressure" and mass-kills nearly every process on the phone. virpipe
  (Mesa 23.1.9 guest) + virglrenderer server consumes gigabytes instantly on this
  device. Not investigated further; Gladio is the priority.

## 4. Key log excerpts

See 22_STALL_LOG_EXCERPTS.txt (verbatim). Server heartbeat tail, run 3 death:
requests=2084864 last=352 then silence. Run-1 stderr spam vs run-3 clean stderr.
VirGL kill-storm sample. Session record numbers.

## 5. Request-code reference

The CLIENT copy is 07_client_request_codes.h; the SERVER copy is
20_server_request_codes.h — THE TWO DIFFER (the server's numbering is what pairs
with the server dispatch table; decode heartbeat codes against the SERVER copy).
352 = glLoadMatrixf (both copies). Codes seen in final heartbeat windows: 101, 119,
132, 149, 183, 185, 205, 223, 226, 242, 366, 374, 421, 437, 526, 527.

## 6. Questions to answer

1. Given "GL request stream stops mid-frame at a deterministic-ish point, process
   likely alive, audio continues, zero crash output, client library has one global
   recursive mutex": rank the four stall hypotheses and design the cheapest
   discriminating experiment for each.
2. Does WoW 1.12.1 (build 5875) terminate worker/render threads with TerminateThread
   (mutex-poisoning risk), or is its render path single-threaded? Cite community
   knowledge (apitrace traces, wine hacks, gl4es WoW threads).
3. The sustained 100–160k GL requests/sec (~5,000/frame at 30fps) — plausible for
   WoW 1.12 fixed-function rendering, or a symptom of a pathological redraw loop?
   What would distinguish these?
4. With texgen+light-model now forwarded but visuals unchanged, what are the most
   likely remaining causes of "water/terrain/lighting glitches" in a fixed-function
   GL→GLES shader-emulation bridge for THIS game (rank by likelihood, name the exact
   GL state to check next on-device)?
5. Known gl4es/Gladio-class bugs matching "game freezes silently after N minutes /
   M frames; GL bridge starves; no crash"? (ring-buffer overflow when the client
   writes faster than the server drains, sync-call deadlocks where the client waits
   for a reply while the server waits for ring space, glBegin/glEnd command-buffer
   overflow.) For each: expected log signature vs. what we observed.
6. For the VirGL instant-OOM: known Mesa 23.x virpipe/virglrenderer memory explosions
   on Adreno/Android 13? One-line root-cause candidates only; lane is parked.
7. Recommend the single highest-value next diagnostic given the pending tail-ring
   traces (client last-32-sent, server last-64-consumed), and what patterns in those
   tails would identify each hypothesis in (1).

## 7. Constraints for recommendations

- Client changes must be expressible as anchored source patches (the build regenerates
  the source from a pinned upstream clone + signature-locked patches).
- Any protocol change must land on both ends together (pinned, hash-verified pair).
- No root (no debuggerd dumps, no simpleperf — SELinux denies). Debuggable app:
  run-as works; /proc/<pid>/task inspection of app-uid processes (including
  wine/box64 children) IS possible at a stall.
- Prefer fixes safe for a 30fps-capped 800x600 target.
