---
name: qualify-runtime
description: Qualify a Box64, FEX, Wine, DXVK, WineD3D, driver, or client runtime tuple on Android. Use for runtime-provider changes or support claims.
context: fork
---

Treat the tuple—not a component name—as the tested unit. Record provider/build, Wine architecture/build, prefix generation, renderer/driver, audio/input/display, client build/locale, addon/visual generation, device firmware, and settings hash.

Required sequence:
1. Verify signed/pinned components and isolate prefix/rootfs/caches from every other tuple.
2. Run cold and warm launch, login, character selection, world entry, twenty map transitions, city/outdoor scenes, audio/controller changes, background/foreground, client crash, Save & Exit, and dirty recovery.
3. Run a two-hour test before candidate status and an eight-hour test before supported status.
4. Capture frame-time percentiles, server diff, PSS, cache growth, battery power, thermal state, audio underruns, exceptions, renderer resets, and recovery outcomes.
5. Test DXVK and WineD3D separately. Report fallback; never hide it.
6. Compare against the current accepted tuple. Promote only when correctness is equal and the intended profile improves or fills a compatibility need.
7. Mark FEX laboratory-only until every non-root Android, rootfs, Wine, graphics, lifecycle, page-size, and sustained test passes.
