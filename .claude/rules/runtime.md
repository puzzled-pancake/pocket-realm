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
- Prove `x86DirectWine` on the fixed x86/x86_64 AVD before any ARM translator work. Box64 plus pinned 64-bit Wine WoW64 is the first ARM production candidate.
- FEX is outside the release critical path and stays visibly Laboratory until a separate feature proves non-root Android and physical-device tests.
- Automatic mode selects only accepted tuples. Never hide renderer/provider fallback or call a launch-only result supported.
- Imported x86 client files are guest data, not permission to execute arbitrary Android-native binaries or inject arbitrary DLLs.
- Start client proof in deterministic WineD3D safe mode; qualify DXVK/Turnip and higher profiles only after the safe path works.
- Support claims require RP6 cold/warm launch, zoning, lifecycle, crash/recovery, sustained performance, battery, and thermal evidence.
