---
paths:
  - "native/**/*.{c,cc,cpp,cxx,h,hpp}"
  - "native/**/CMakeLists.txt"
  - "runtime/**/*.{c,cc,cpp,cxx,h,hpp}"
---

# Native C++ rules

- Use C++20, RAII, explicit ownership, and bounded containers. New owning raw pointers are not allowed.
- Native components expose a narrow versioned C ABI or app-private control socket with opaque handles/tokens and stable error codes. No C++ exception, STL type, callback with ambiguous lifetime, or thread-affine object crosses it.
- Replace process termination, console-only control, and signal-only shutdown with supervised lifecycle/control calls. Keep database, auth, world, and client failure domains separate unless the G0 packaging evidence explicitly selects a library-backed component.
- Build the same pinned server patch set for x86_64 development and portable `arm64-v8a` release; device scheduling is runtime policy.
- Playerbots stay disabled until the MariaDB-backed zero-bot world passes persistence and forced-recovery gates.
- Treat packet, archive, pathfinding, log, queue, retry, and cache input as bounded.
- Persistence and item/economy changes require transaction and abrupt-stop tests.
- Do not change upstream code blindly; isolate patches and document why upstream behavior is insufficient.
