# Vendored llama.cpp runtime (kai build, arm64-v8a only)

Prebuilt llama.cpp shared libraries for the in-process playerbot LLM backend
(`PlayerbotLlamaRuntime`), linked into `libpocket_world_runtime` by the
realm-runtime build on arm64-v8a only. x86_64 builds compile the backend out
and fall back to the HTTP/deterministic path.

- Source: llama.cpp master @ `6d05498314db1b57f81c271080018aa2d0b89be9`
  (2026-08-19), built with Android NDK r28c / clang, arm64-v8a,
  `-DGGML_KLEIDIAV=ON` + armv8.2-a+dotprod+fp16+i8mm (KleidiAI-accelerated).
- The NPU, Vulkan, and OpenCL accelerator paths are closed (unused) in
  this build; the backend runs on CPU with KleidiAI.
- `include/` carries the matching llama/ggml public headers so the
  playerbots static target can compile against the ABI without a full
  llama.cpp checkout.
- `libllama-server-impl.so` and `libmtmd.so` from the deploy bundle are
  intentionally NOT vendored: in-process backend only, no server binary,
  no multimodal.

SHA-256 of each staged .so is pinned in `native/llm/lockfile-arm64-v8a.json`
and enforced by the Gradle native-closure validator.
