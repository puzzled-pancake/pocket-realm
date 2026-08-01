# O06 X-Server Provider Provenance (Winlator ca3d735)

This document records the source correspondence, license obligation, and trim
list for the in-app X-server vendored from Winlator for the O06 Wine feasibility
spike (S-3: X11/GDI window).

## Source pin

- **Upstream:** https://github.com/brunodev85/winlator-app
- **Commit:** `ca3d735a60d653a787daf16d14fafef28d9c2c23`
- **Fetched to:** `native/.providers-extracted/winlator-app-ca3d735/` (not committed; source archive)
- **Vendored to:** `runtime/xserver-winlator/com/winlator/`
- **License:** LGPL-2.1 (Winlator is LGPL-2.1; source retained in-tree for the
  source-offer obligation)
- **schemas/sources.json id:** `xserver-winlator-ca3d735`

## Packages vendored (188 .java files)

| Package | Files | Role |
|---|---|---|
| `com.winlator.xserver` | 99 | X11 wire protocol: window/pixmap/GC/atom/visual managers, request handlers, errors, events, extensions (MIT-SHM, DRI3, Present, GLX, etc.) |
| `com.winlator.xconnector` | 8 | Unix-socket connection layer (`XConnectorEpell` + JNI). **Native epoll layer reimplemented in Java for the spike** (see below). |
| `com.winlator.renderer` | 17 | GLES compositor (`GLRenderer`, `Texture`, `RenderableWindow`, materials, effects) |
| `com.winlator.core` | 34 | Utility classes (only the subset reachable from the X-server is used) |
| `com.winlator.math` | 2 | `Mathf`, `XForm` |
| `com.winlator.widget` | 16 | `XServerView` + other widgets (only `XServerView` is used by the spike) |
| `com.winlator.xenvironment` | 12 | `XServerComponent` bootstrap (wires connector + handlers) |

## Trim list (couplings to Winlator's app shell — replaced/stubbed)

The Winlator X-server has couplings to its app shell that Pocket Realm does not
have. These are stubbed/replaced for the spike:

1. **`com.winlator.XServerDisplayActivity`** — the Android Activity host. The
   spike runs the X-server from an instrumented test, not an Activity. Replaced
   with a minimal host interface / null where the constructor demands it.
2. **`com.winlator.contentdialog.DebugDialog`** — reached only via
   `activity.getDebugDialog()`. Stubbed to no-op (`debugPrint` discards).
3. **`com.winlator.winhandler.WinHandler`** — bridge to Wine's window management
   (focus/foreground/dynamic-resolution). Pass `null` for the spike.
4. **`com.winlator.inputcontrols.ExternalController`** — input is fully stubbed
   for S-3 (window create+map+GLES render does not require input events). The
   `inject*` methods are never called.
5. **`com.winlator.sysvshm.SysVSharedMemory`** — System V shared memory. Stubbed
   (the spike does not exercise the SysV IPC path; MIT-SHM uses ashmem instead).
6. **`com.winlator.R`** — resource references. No XML resources are required by
   the X-server core; GLSL shaders are inline strings in the material classes.
7. **Native epoll layer (`libwinlator.so` JNI in `XConnectorEpoll`)** — the 6
   JNI methods (`nativeAllocate`, `startEpollThread`, etc.) are reimplemented in
   pure Java (`android.net.LocalSocket`) for the spike to avoid building a second
   native module. This is the only behavioral substitution (transport only; the
   X11 protocol layer is byte-for-byte the upstream implementation).

## Acceptance mapping (S-3)

- **Listen on socket:** `XServerComponent.start()` → `XConnectorEpoll` binds
  `/tmp/.X11-unix/X0` (display :0). Wine's `winex11.drv` connects via `DISPLAY=:0`.
- **Accept winex11.drv:** `XClientConnectionHandler` + `XClientRequestHandler`
  do the X11 auth handshake + opcode dispatch (unchanged upstream code).
- **Create + map window:** opcodes CREATE_WINDOW/MAP_WINDOW → `WindowManager`.
  The mapped window's content lives in a `Drawable` (BGRA `ByteBuffer`).
- **GLES render proof:** `Texture.updateFromDrawable()` uploads the `Drawable`'s
  `ByteBuffer` to a GLES2 texture; the spike asserts the texture is valid.

## Source-offer obligation (LGPL-2.1)

The complete corresponding source of the LGPL-2.1 Winlator X-server is retained
in-tree at `runtime/xserver-winlator/` (the vendored copy at the pinned commit,
minus the app-shell couplings listed above which Pocket Realm does not use). The
upstream commit hash above identifies the exact source version. This satisfies
the LGPL-2.1 source-availability obligation for the adapted library.
