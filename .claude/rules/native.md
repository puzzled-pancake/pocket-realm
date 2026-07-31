---
paths:
  - "native/**/*.{c,cc,cpp,cxx,h,hpp}"
  - "native/**/CMakeLists.txt"
  - "runtime/**/*.{c,cc,cpp,cxx,h,hpp}"
---

# Native C++ rules

- Use C++20, RAII, explicit ownership, and bounded containers. New owning raw pointers are not allowed.
- The embedded runtime exposes a versioned C ABI with opaque handles and error codes. No C++ exception, STL type, callback with ambiguous lifetime, or thread-affine object crosses it.
- Replace process termination, console-only control, and signal-only shutdown with lifecycle calls and callbacks.
- Native server code remains portable `arm64-v8a`; device scheduling is runtime policy.
- Treat packet, archive, pathfinding, log, queue, retry, and cache input as bounded.
- Persistence and item/economy changes require transaction and abrupt-stop tests.
- Do not change upstream code blindly; isolate patches and document why upstream behavior is insufficient.
