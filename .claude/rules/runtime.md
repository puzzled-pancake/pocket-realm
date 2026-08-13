---
paths:
  - "runtime/**"
  - "**/*box64*"
  - "**/*wine*"
  - "**/*dxvk*"
---

# Client runtime rules

- Qualify the complete tuple: translator, Wine, prefix/rootfs, renderer/driver, audio/input/display, client build/locale, addons, visual overlay, device firmware, and settings hash.
- Isolate prefixes and shader caches by the complete Box64/Wine/DXVK/driver identity.
- Prove `x86DirectWine` on the fixed x86/x86_64 AVD before ARM work. ARM production uses only Box64 plus pinned 64-bit Wine WoW64 and an exact DXVK/Turnip package.
- Reject missing or mismatched ARM runtime/renderer identities; never fall back to FEX, client OpenGL, or WineD3D.
- Imported x86 client files are guest data, not permission to execute arbitrary Android-native binaries or inject arbitrary DLLs.
- Retain WineD3D/Gladio only for the existing x86 validation lane; qualify ARM on Box64 + DXVK/Turnip.
- Support claims require RP6 cold/warm launch, zoning, lifecycle, crash/recovery, sustained performance, battery, and thermal evidence.
