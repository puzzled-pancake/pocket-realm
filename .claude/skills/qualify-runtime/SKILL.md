---
name: qualify-runtime
description: Qualify the fixed Box64, Wine, DXVK, and driver client runtime tuple on Android. Use for runtime-provider changes or support claims.
context: fork
---

Treat the tuple—not a component name—as the tested unit. Record provider/build, Wine architecture/build, prefix generation, renderer/driver, audio/input/display, client build/locale, addon/visual generation, device firmware, and settings hash.

Required sequence:
1. Verify signed/pinned components and isolate prefix/rootfs/caches from every other tuple.
2. Run cold and warm launch, login, character selection, world entry, twenty map transitions, city/outdoor scenes, audio/controller changes, background/foreground, client crash, Save & Exit, and dirty recovery.
3. Run a two-hour test before candidate status and an eight-hour test before supported status.
4. Capture frame-time percentiles, server diff, PSS, cache growth, battery power, thermal state, audio underruns, exceptions, renderer resets, and recovery outcomes.
5. Verify the exact DXVK package identity and prove that package failure cannot fall back to WineD3D or client OpenGL.
6. Compare against the current accepted tuple. Promote only when correctness is equal and the intended profile improves or fills a compatibility need.
7. Reject any FEX or client-OpenGL artifact, setting, control request, or cache identity in the ARM production closure.
