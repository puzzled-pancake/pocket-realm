---
paths:
  - "android/**/*.kt"
  - "android/**/*.kts"
  - "android/**/AndroidManifest.xml"
---

# Android rules

- Use Kotlin, Compose, structured coroutines, and explicit dispatcher ownership. No `GlobalScope` or blocking work on the main thread.
- Realm/client hosting runs in a user-visible foreground service. Activity or service destruction callbacks are not durability guarantees.
- Keep mutable realm data on internal app storage by default; use SAF for user imports/exports.
- Package native executable code through the signed Android build. Do not download and execute replacement ARM binaries from writable app storage.
- UI state reflects the supervisor state machine; never report Playing or Safe before health/checkpoint conditions hold.
- Basic screens stay uncluttered. Advanced values are bounded presets and generation-managed.
- Handle controller hot-plug, audio route changes, background/foreground, low storage, process recreation, and thermal state explicitly.

## Repository hygiene (de-vibe sustainment)
Screenshots, UI dumps, and logcat evidence belong in `tests/**/evidence/` or
local `tmp/` — never the repo root. Never commit binaries >1 MB without a
DECISIONS.md entry; build outputs are always untracked. The `.githooks/`
pre-commit gate enforces this after `git config core.hooksPath .githooks`.
