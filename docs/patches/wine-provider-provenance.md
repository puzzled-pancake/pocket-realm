# O06 Wine Runtime and X-Server Provider Provenance

This document records the Wine 16 KB adaptation plus the source correspondence,
license obligation, trim list, and O07 Gladio adaptations for the in-app X
server and WineD3D bridge.

**Status as of this revision:** the Java X11 wire-protocol sources are vendored
and compile (143 `.java` files). The native transport `libwinlator.so` is
vendored, built, and packaged. O07 additionally vendors and packages the
source-matched `libgladiorenderer.so` GLX/OpenGL bridge. Both are NDK-built and
16 KB aligned. S-3 passes end-to-end on the Modern 4 KB and 16 KB lanes. No Java
reimplementation of the native epoll/SCM_RIGHTS layer was written.

## Wine 11.14 paired 16 KB adaptation

- **Source:** `wine-mirror/wine` commit
  `1012f3d99507b80d4869eabf0853567660a7ecbb` (Wine 11.14), matching the
  Kron4ek 11.14 vanilla WoW64 provider recorded in `schemas/sources.json`.
- **Patch:** `native/wine-spike/patches/wine-11.14-x86_64-16k.patch`.
- **Reproduction:** `tools/build_wine_16k_ntdll.py` builds in the pinned Termux
  CGCT image digest. Reviewed output hashes/toolchain data are checked in at
  `native/wine-spike/patches/wine-11.14-x86_64-16k.provenance.json`; each local
  rebuild also emits its generated record at
  `native/.build-x86_64/wine-ntdll-16k-multiarch/BUILD_PROVENANCE.json`.
- **Paired outputs:** Unix `ntdll.so`, x86_64 PE `ntdll.dll`, and x86_64 PE
  `win32u.dll`. These must always be staged as one source-matched set.

Wine's generated x86_64 syscall stubs normally call a process-local dispatcher
pointer at `0x7ffe1000`. On a 16 KB Android host this address occupies the same
host page as the shared `KUSER_SHARED_DATA` mapping at `0x7ffe0000`. Multiple
Wine processes can therefore overwrite one another's ASLR-relative dispatcher
pointer and jump into an invalid address. The patch moves the dispatcher to a
private host page at `0x7ffe4000`, maps/protects using the detected host page
size, and regenerates all affected x86_64 PE syscall stubs.

The same source patch also constrains Wine's low-DOS-memory fallback below
4 GiB. Android can reject the traditional fixed low mapping; Wine's otherwise
unbounded 64-bit fallback may then return an address that a 32-bit WoW64 guest
truncates. The replacement uses Wine's normal `map_view` allocator with a
`limit_4g - 1` ceiling. It does not reserve a project-specific fixed address.

Separately, the APK-side seccomp shim deliberately refuses to `MAP_FIXED`
executable permissions over an existing writable WoW64 stack. It returns
`EACCES`, allowing Wine to retry its writable-only path, instead of destroying
guest stack contents to satisfy an execmod request.

The build verifier scans the provider's complete x86_64 PE directory and proves
that only `ntdll.dll` and `win32u.dll` contain the old dispatcher encoding. It
rejects any old stubs in the rebuilt pair, requires the expected new stubs, and
checks the Unix ELF for 16 KB page compatibility. This closes the source-
reproduction and mixed-runtime risks rather than treating a manually edited
binary as a distributable fix.

Final paired-runtime qualification is recorded in:

- `tests/avd/AVD-Modern-x86_64-v1/evidence/pkgExperiment-wine_spike-all-20260802-185902.PASS.log`
- `tests/avd/AVD-16K-x86_64-v1/evidence/pkgExperiment-wine_spike-all-20260802-185645.PASS.log`

## Source pin

- **Upstream:** https://github.com/brunodev85/winlator-app
- **Commit:** `ca3d735a60d653a787daf16d14fafef28d9c2c23`
- **Fetched to:** `native/.providers-extracted/winlator-app-ca3d735/` (source
  archive, not committed)
- **Java vendored to:** `runtime/xserver-winlator/com/winlator/`
- **Native source location:** `native/xserver-winlator/cpp/`, vendored from
  `native/.providers-extracted/winlator-app-ca3d735/app/src/main/cpp/winlator/`
  (`src/{xconnector_epoll.c, xinput_stream.c, xoutput_stream.c, arrays.c,
  ring_buffer.c, drawable.c, ...}` + `include/*.h`)
- **GLX server location:** `native/xserver-winlator/cpp/gladiorenderer/`, derived
  from the pinned `app/src/main/cpp/gladiorenderer/` source set plus
  `vortekrenderer/include/bc_decoder.h`; Pocket Realm's protocol/profile/shader
  adaptations are retained as normal in-tree source changes.
- **GLX client source:** `https://github.com/brunodev85/gladio` commit
  `eaa2a8d6eda3a1a6af755370ea9fac6cf7792ac3`; built for native x86_64 glibc by
  `tools/build_gladio_client.py` as a 544456-byte `libGL.so.1` (SHA-256
  `7b60dafa5e071e11187c0936840201920e141160f0897609ce530cb6f69b60b6`).
- **License:** LGPL-2.1 (Winlator is LGPL-2.1; source retained in-tree for the
  source-offer obligation)
- **schemas/sources.json id:** `xserver-winlator-ca3d735`

## Vendored Java layer

159 `.java` files under `runtime/xserver-winlator/com/winlator/` (refreshed
2026-08-16 during the de-vibe doc pass; earlier revisions said 143):

| Package | Files | Role |
|---|---|---|
| `com.winlator.xserver` | 37 | X11 core: window/pixmap/GC/atom/visual/colormap/cursor managers, request handlers, errors, events, extensions |
| `com.winlator.xserver.errors` | 18 | X11 error events |
| `com.winlator.xserver.events` | 27 | X11 event types |
| `com.winlator.xserver.extensions` | 9 | BigReq, DRI3, GLX, MIT-SHM, Present, Sync, XComposite (+ base `Extension`) |
| `com.winlator.xserver.requests` | 11 | X11 core request handlers (CreateWindow, MapWindow, PolyFillRect, etc.) |
| `com.winlator.xconnector` | 8 | Unix-socket connection layer. `XConnectorEpoll` + `XInputStream` + `XOutputStream` use the packaged native transport. |
| `com.winlator.renderer` | 9 | GLES compositor (`GLRenderer`, `Texture`, `RenderableWindow`) |
| `com.winlator.renderer.effects` | 4 | Post effects (cursor, etc.) |
| `com.winlator.renderer.material` | 4 | GLES materials/shaders |
| `com.winlator.core` | 10 | Utility subset reachable from the X-server |
| `com.winlator.math` | 2 | `Mathf`, `XForm` |
| `com.winlator.widget` | 1 | `XServerView` |
| `com.winlator.XServerDisplayActivity` | 1 | stub (Activity host; spike runs from instrumented test) |
| `com.winlator.contentdialog` | 1 | stub (`DebugDialog`) |
| `com.winlator.winhandler` | 2 | stub (`WinHandler`, `MouseEventFlags`) |
| `com.winlator.inputcontrols` | 1 | stub (`ExternalController`) |
| `com.winlator.sysvshm` | 4 | implemented: source-matched JNI shared-memory bridge used by DRI3 dma-buf transport (was a 1-file stub at vendoring time) |
| `com.winlator.alsaserver` | 5 | Pocket Realm addition: ALSA audio bridge (not upstream) |
| `com.winlator.xenvironment.components` | 5 | Pocket Realm addition: Vortek/VirGL renderer components, context registry, window authority (not upstream) |

## What is vendored + built

1. **Native transport `libwinlator.so`** — the complete pinned native transport
   source set (9 `.c` + 17 headers, vendored into
   `native/xserver-winlator/cpp/` from `app/src/main/cpp/winlator/`). It handles filesystem-domain
   Unix sockets, epoll, SCM_RIGHTS fd-passing, buffered X11 input/output, and JNI
   callbacks. The JNI method names match the vendored Java classes' package paths
   exactly (`Java_com_winlator_xconnector_{XConnectorEpoll,XInputStream,
   XOutputStream}_*` + `Java_com_winlator_xserver_Drawable_*`), so it is a
   drop-in for `System.loadLibrary("winlator")`. NO Java rewrite was written.
   Build: `tools/build_xserver_winlator.py` (NDK, 16 KB-aligned). A force-include
   compatibility header (`include/pocket_ndk_compat.h`) supplies
   `<stdlib.h>`/`<string.h>`/`<time.h>` the upstream Android Studio build
   provides transitively. O07 adds the missing `IntArray_indexOf`
   declaration/body that Gladio at the same pinned commit calls but the
   provider's arrays source omits. The EGL/GLES + jnigraphics deps are retained (the renderer
   needs them; they are part of the same library).
2. **O07 GLX extension** — `XServer.setupExtensions()` now advertises BigReq,
   Sync, XComposite, and GLX. GLX is backed by the complete pinned
   `libgladiorenderer.so` source set; DRI3, MIT-SHM, and Present remain omitted
   because their full native paths are outside the qualified O07 WineD3D path.
   Original Winlator wire opcodes are preserved even though optional extensions
   are omitted; extension lookup is by opcode instead of compact array index.
3. **O07 Gladio client/server pair** — the x86_64 glibc client replaces the
   hard-coded Winlator socket with the absolute app-private
   `POCKET_GLADIO_X11_SOCKET`. Client and server use a private, explicit
   `(attribute index, kind, byte count, bytes)` record format, bounds-check every
   record, preserve BGRA VBO offsets, and upload transient client arrays through
   per-attribute GLES VBOs. Signature-locked GLX pbuffer calls use bounded,
   unmapped X drawables so WineD3D can complete its final shared-context probe.
   Shaders target the emulator's GLES 3.1 ceiling
   (`#version 310 es`). The server advertises the qualified OpenGL 3.0 / GLSL
   1.30 subset: internal-format queries remain for WineD3D backbuffer format
   classification, while unsupported modern instancing/base-vertex, sampler,
   UBO, compute, and tessellation paths are withheld.

   The pbuffer adaptation does not close the current O14 real-client renderer
   regression. On the strict O12 visual gate, context 7 is created and made
   current successfully, but the guest emits no `SWAP_DISPLAY_BUFFERS` request
   and the captured display remains unchanged. This is an unresolved FAIL, not
   new O07 acceptance evidence; the historical O07 qualification remains as
   recorded.
4. **S-3 harness** — `WineSpikeRunner.runS3`: creates `<appTmp>/.X11-unix/X0`,
   starts the X-server (XConnectorEpoll + XClientConnectionHandler +
   XClientRequestHandler, headless for the spike), launches the project-owned
   32-bit self-test PE with DISPLAY=:0 via the qualified direct glibc adapter,
   requires
   `POCKET_SELFTEST_WINDOW` + `POCKET_SELFTEST_OK` + exit zero, proves a mapped
   client window via `WindowManager.getMappedClientWindows()`, and cleanly
   shuts down wineserver + the X-server. A `getMappedClientWindows()` accessor
   was added to WindowManager (clearly marked as a Pocket Realm spike addition).

## Experimental ARM64 OpenGL renderer pairs

DXVK remains the default ARM renderer. The following routes are separately
selected, capability-gated, generation-local, and fail without fallback.

- **Legacy OpenGL (Gladio):** recovered from Pocket Realm commit
  `22410a53db50561a9784a23c715e4fb6855db6a8`. The AArch64 glibc client is built
  from `brunodev85/gladio@eaa2a8d6eda3a1a6af755370ea9fac6cf7792ac3` by
  `tools/build_gladio_client.py`. The reviewed client is 530400 bytes, SHA-256
  `1d9663bb23ffe6083cf94925e6ffde4523888d52051c9bf934c87aad4bae4680`.
  It launches WoW with `-opengl` and is considered running only after a live
  transport context, live GLX context, and a validated presented frame.

- **Mesa VirGL:** the provider is Winlator
  `ca3d735a60d653a787daf16d14fafef28d9c2c23`, paired with the custom Mesa
  23.1.9 source commit `71c57a2def7db3eb45cde5ee520f112de0fa6ec0`.
  The provider archive SHA-256 is
  `614b1edc8e47c57b2cbb2d96f9c7ab5f5b1a89038de618a58b2faf9c64380e09`;
  its sole `libGL.so.1.7.0` payload is 14379544 bytes, SHA-256
  `531e3dc809281feadcc2120abc6d9f88025d92d567ac32eed9c376bd9e4e04f6`.
  `tools/stage_virgl_renderer.py` verifies that payload, and the matching
  retained native server builds only for ARM64. GLX remains advertised for the
  pinned Mesa Fake-GLX negotiation, while rendering and presentation travel
  through the private `virpipe` V0 socket. Readiness requires an initialized,
  caps-ready connection and a validated non-black flush; Gladio native context
  counters must remain zero.

Both routes require EGL 1.4+, a verified OpenGL ES 3 shared surfaceless context,
and an exact live-root sharing probe after Android publishes its EGL generation.
Compilation and closure checks alone are not claims of broad device support.

## Trim list (couplings to Winlator's app shell — stubbed/replaced)

1. **`com.winlator.XServerDisplayActivity`** — the Android Activity host. The
   spike runs the X-server from an instrumented test, not an Activity. Stubbed.
2. **`com.winlator.contentdialog.DebugDialog`** — reached only via
   `activity.getDebugDialog()`. Stubbed to no-op (`debugPrint` discards).
3. **`com.winlator.winhandler.WinHandler`** / **`MouseEventFlags`** — bridge to
   Wine's window management. Stubbed; pass `null` for the spike.
4. **`com.winlator.inputcontrols.ExternalController`** — input fully stubbed for
   S-3 (window create+map+paint does not require input events).
5. **`com.winlator.sysvshm.SysVSharedMemory`** — System V shared memory. Stubbed
   (the SysV IPC path is not exercised; MIT-SHM is not advertised by the
   qualified GDI/GLX extension set).
6. **`com.winlator.R`** — resource references. No XML resources are required by
   the X-server core; GLSL shaders are inline strings in the material classes.
   `GLRenderer.createRootCursorDrawable` uses a resource-name lookup with a 1x1
   fallback.

## Acceptance mapping (S-3, passed on both lanes)

- **Create + map window:** opcodes CREATE_WINDOW/MAP_WINDOW → `WindowManager`.
  The mapped window's content lives in a `Drawable` (BGRA `ByteBuffer`).
- **Listen on socket:** `XConnectorEpoll` (native `xconnector_epoll.c`) binds
  `<appTmp>/.X11-unix/X0`; the glibc path shim maps Wine's conventional
  `/tmp/.X11-unix/X0` lookup there. Wine's `winex11.drv` connects via
  `DISPLAY=:0`.
- **GLES render proof:** `Texture.updateFromDrawable()` uploads the `Drawable`'s
  `ByteBuffer` to a GLES2 texture; the harness asserts a valid texture and
  non-background pixels via screenshot/PixelCopy.

## O07 WineD3D acceptance

The fixed API-35 x86_64 4 KB lane launches the hash-verified managed build-5875
client twice through this exact client/server pair. `ClientBuild5875LoginTest`
requires a mapped 800x600 `wow.exe` window, samples the `XServerView` framebuffer
on its owning GLES thread, rejects a more-than-99%-black surface, and then repeats
the proof after a clean stop. The accepted samples contain 319,606 and 321,732
non-black pixels. Evidence lives under
`tests/avd/AVD-Modern-x86_64-v1/evidence/o07-login-{first,relaunch}.png` with the
paired JSON record.

## Source-offer obligation (LGPL-2.1)

The complete corresponding source of the LGPL-2.1 Winlator X-server Java layer is
retained in-tree at `runtime/xserver-winlator/` (the vendored copy at the pinned
commit, minus the app-shell couplings listed above which Pocket Realm does not
use). The native C sources are retained at `native/xserver-winlator/cpp/` from
the same pinned commit, with the project compatibility header and build recipe
recorded in-tree. The upstream commit hash identifies the exact source version.
This satisfies the LGPL-2.1 source-availability obligation for the adapted
library.
