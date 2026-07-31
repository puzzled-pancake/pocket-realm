---
paths:
  - "runtime/**"
  - "**/*box64*"
  - "**/*fex*"
  - "**/*wine*"
  - "**/*dxvk*"
---

# Client runtime rules

- Qualify the complete tuple: translator, Wine, prefix/rootfs, renderer/driver, audio/input/display, client build/locale, addons, visual overlay, device firmware, and settings hash.
- Isolate prefixes, root filesystems, dynarec caches, and shader caches by tuple. Never share writable state between Box64 and FEX.
- Box64 plus pinned Wine WoW64 is the first production candidate. FEX stays visibly Laboratory until non-root Android and physical-device tests pass.
- Automatic mode selects only accepted tuples. Never hide renderer/provider fallback or call a launch-only result supported.
- Imported x86 client files are guest data, not permission to execute arbitrary Android-native binaries or inject arbitrary DLLs.
- Support claims require RP6 cold/warm launch, zoning, lifecycle, crash/recovery, sustained performance, battery, and thermal evidence.
