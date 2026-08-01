# O06 X-Server Provider Provenance (Winlator ca3d735)

This document records the source correspondence, license obligation, and trim
list for the in-app X-server vendored from Winlator for the O06 Wine feasibility
spike (S-3: X11/GDI window).

**Status as of this revision:** only the Java X11 wire-protocol sources are
vendored and they compile. The **native transport (`libwinlator.so`) is NOT yet
built or committed.** No Java reimplementation of the native epoll/SCM_RIGHTS
layer exists. S-3 is not yet runnable. This document describes what is actually
present; counts below are accurate to the tree.

## Source pin

- **Upstream:** https://github.com/brunodev85/winlator-app
- **Commit:** `ca3d735a60d653a787daf16d14fafef28d9c2c23`
- **Fetched to:** `native/.providers-extracted/winlator-app-ca3d735/` (source
  archive, not committed)
- **Java vendored to:** `runtime/xserver-winlator/com/winlator/`
- **Native source location (pinned, NOT yet vendored/built):**
  `native/.providers-extracted/winlator-app-ca3d735/app/src/main/cpp/winlator/`
  (`src/{xconnector_epoll.c, xinput_stream.c, xoutput_stream.c, arrays.c,
  ring_buffer.c, drawable.c, ...}` + `include/*.h`)
- **License:** LGPL-2.1 (Winlator is LGPL-2.1; source retained in-tree for the
  source-offer obligation)
- **schemas/sources.json id:** `xserver-winlator-ca3d735`

## What is actually vendored today (Java only; compiles)

143 `.java` files under `runtime/xserver-winlator/com/winlator/`:

| Package | Files | Role |
|---|---|---|
| `com.winlator.xserver` | 36 | X11 core: window/pixmap/GC/atom/visual/colormap/cursor managers, request handlers, errors, events, extensions |
| `com.winlator.xserver.errors` | 18 | X11 error events |
| `com.winlator.xserver.events` | 25 | X11 event types |
| `com.winlator.xserver.extensions` | 9 | BigReq, DRI3, GLX, MIT-SHM, Present, Sync, XComposite (+ base `Extension`) |
| `com.winlator.xserver.requests` | 11 | X11 core request handlers (CreateWindow, MapWindow, PolyFillRect, etc.) |
| `com.winlator.xconnector` | 8 | Unix-socket connection layer. **`XConnectorEpoll` + `XInputStream` + `XOutputStream` declare JNI methods backed by the native transport — which is NOT yet built.** These classes therefore link but fail at runtime until `libwinlator.so` is added. |
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
| `com.winlator.sysvshm` | 1 | stub (`SysVSharedMemory`) |

## What is NOT yet done (S-3 pending)

1. **Native transport `libwinlator.so`** — the spike will vendor the minimum
   source-matched subset from the pinned commit: `src/xconnector_epoll.c`,
   `src/xinput_stream.c`, `src/xoutput_stream.c`, `src/arrays.c`,
   `src/ring_buffer.c`, plus required headers and the drawable/native functions
   actually reached by the GDI path. Packaged as an NDK-built, 16 KB-aligned JNI
   library. **The pinned native code correctly handles filesystem-domain Unix
   sockets, epoll, SCM_RIGHTS fd-passing, buffered X11 input/output, and JNI
   callbacks — no Java rewrite is planned.** (Android supplies `LocalServerSocket`
   but its simple constructor targets the abstract namespace, not Wine's
   `/tmp/.X11-unix/X0` filesystem socket; the native implementation is the
   correct match.)
2. **GDI-only extension set** — the current `XServer.setupExtensions()`
   constructs GLX, DRI3, MIT-SHM, Present, and Sync in addition to BigReq. GLX
   loads `libgladiorenderer.so` (absent); DRI3/MIT-SHM/Present need native support
   not built for this spike. These must be removed from the advertisement list so
   no no-op extension is advertised to Wine. Only BigReq remains for GDI/X11.
3. **S-3 harness** — `XServerComponent` wiring, `<appTmp>/.X11-unix/X0` creation,
   self-test PE launch via the same PRoot/prefix/cache path, screenshot/PixelCopy
   of non-background pixels, `POCKET_SELFTEST_OK` + exit zero.

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
   (the SysV IPC path is not exercised; MIT-SHM will be removed from the
   advertisement list for GDI-only).
6. **`com.winlator.R`** — resource references. No XML resources are required by
   the X-server core; GLSL shaders are inline strings in the material classes.
   `GLRenderer.createRootCursorDrawable` uses a resource-name lookup with a 1x1
   fallback.

## Acceptance mapping (S-3, pending)

- **Create + map window:** opcodes CREATE_WINDOW/MAP_WINDOW → `WindowManager`.
  The mapped window's content lives in a `Drawable` (BGRA `ByteBuffer`).
- **Listen on socket:** `XConnectorEpoll` (native `xconnector_epoll.c`) binds
  `<appTmp>/.X11-unix/X0`; under PRoot the `<appTmp>:/tmp` bind makes it visible
  as `/tmp/.X11-unix/X0`. Wine's `winex11.drv` connects via `DISPLAY=:0`.
- **GLES render proof:** `Texture.updateFromDrawable()` uploads the `Drawable`'s
  `ByteBuffer` to a GLES2 texture; the harness asserts a valid texture and
  non-background pixels via screenshot/PixelCopy.

## Source-offer obligation (LGPL-2.1)

The complete corresponding source of the LGPL-2.1 Winlator X-server Java layer is
retained in-tree at `runtime/xserver-winlator/` (the vendored copy at the pinned
commit, minus the app-shell couplings listed above which Pocket Realm does not
use). When the native transport is built, its pinned C sources will be vendored
from the same commit into the tree and recorded here. The upstream commit hash
identifies the exact source version. This satisfies the LGPL-2.1
source-availability obligation for the adapted library.
