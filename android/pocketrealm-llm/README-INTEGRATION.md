# pocketrealm-llm — embedded LLM runtime module

Gradle module that hosts a persistent, OpenAI-compatible llama.cpp server
inside the pocketrealm APK, in its own `:llm` process, for cmangos playerbots
AI chat (`AiPlayerbot.LLM*`). Integrated and managed by the app's
"AI bot LLM" settings submenu — see `docs/llm-runtime-submenu.md` for the
full design; this file documents the module itself.

## What's inside

| Piece | Purpose |
|---|---|
| `src/main/cpp/llmexec.c` | JNI fork/exec wrapper: applies CPU affinity + nice **before** exec |
| `LlmRuntimeService.kt` | Foreground service (`:llm` process): compute-mode resolution, start/health/restart/backoff, PSI pressure guard, NPU crash-block |
| `NpuDeathAttribution.kt` | Pure death-attribution table for never-healthy NPU children (waitpid status + log-tail markers + grace window; unit-tested) |
| `LlmRuntime.kt` | Public API (`start`/`stop`/`resetNpuBlock`/`endpoint`) + `LlmRuntimeConfig` with `ComputeMode{AUTO,CPU,NPU}` |
| `LlmRuntimeService.kt` warm-up probe | one tiny chat completion once `/health` passes (pays the measured first-request penalty) and, when the model's own template burns the budget on a thinking preamble, restarts the child once with `--chat-template <staged file's CONTENT>` (the vendored 6d05498 binary predates `--chat-template-file`; a post-healthy kill is never attributed as an NPU load death — attribution is pre-healthy only) |
| `HexagonProbe.kt` | NPU pre-flight: FastRPC/remoteproc/skel detection + MemAvailable load gate (kernel-panic protection) |
| `build.gradle.kts` staging tasks | Stage the prebuilt runtime into jniLibs (`libllamaserver.so`) + Hexagon backend (`libggmlhex.so`) + DSP skels as assets |
| `prebuilt/` | Vendored llama-server runtime (see `prebuilt/README.md` for provenance, the SIGTERM-during-load patch, and the revision coupling) |

## Compute modes

- **CPU** — llama.cpp on the pinned cores (KleidiAI + dotprod build).
- **NPU (Hexagon hybrid)** — `--device HTP0 -ngl N`: prefill on the Hexagon
  (core-invariant ~430-540 tok/s on the RP6), decode on the pinned cores.
  Loaded via `GGML_BACKEND_PATH` only after `HexagonProbe` reports READY;
  two consecutive load failures block NPU (persisted) until
  `LlmRuntime.resetNpuBlock`. MTP is forced off in this mode.
- **AUTO** — NPU when detection passes, CPU otherwise.

Defaults: threads 3, CPU mask `0x38` (mid cores 3-5), nice 10, MTP **off**
(a measured net loss on pinned cores — do not re-enable without a benchmark).

## Embedding / starting

The game app's supervisor starts the runtime before the world server claims
memory (`AndroidRuntimeBackend.ensureLlmRuntime`). Manual start looks like:

```kotlin
val model = File(context.filesDir, "models/gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf")
val config = LlmRuntimeConfig.Builder(model.absolutePath).apply {
    bindLoopbackOnly = true   // cmangos inside pocketrealm; false => LAN (set apiKey!)
    computeMode = ComputeMode.AUTO
    threads = 3
    cpuMaskHex = 0x38L
    extraArgs = listOf("--jinja", "--load-mode", "none")
    // Staged non-thinking template (gemma dialect). When non-null AND
    // the warm-up probe detects a thinking template, the service restarts
    // once with --chat-template <the staged file's content> (the vendored
    // 6d05498 binary has no --chat-template-file). The app stages the
    // packaged asset via LlmRuntimePolicy.stageChatTemplate(context).
    // serialVersionUID is pinned (sticky-restart deserialization).
    chatTemplateFile = stagedTemplatePath
}.build()
LlmRuntime.start(context, config)
```

Model delivery reuses the app's `LlmModelCoordinator` (resumable HuggingFace
download into `filesDir/models`, sha256-verified). The memory gate refuses an
NPU load below `file × 1.1 + 0.7 GB` of MemAvailable — an unsatisfiable DSP
DMA allocation reboots this device.

## cmangos conf (what the app emits when enabled)

```ini
AiPlayerbot.LLMEnabled = 2
AiPlayerbot.LLMBackend = 0
AiPlayerbot.LLMApiEndpoint = http://127.0.0.1:8080/v1/chat/completions
AiPlayerbot.LLMApiJson = {"model":"local","messages":[{"role":"system","content":"<pre prompt> <context>"},{"role":"user","content":"<prompt> <post prompt>"}],"max_tokens":210,"temperature":1,"top_p":0.95,"top_k":64,"repeat_penalty":1,"cache_prompt":true,"stream":false}
AiPlayerbot.LLMResponseStartPattern =
AiPlayerbot.LLMResponseEndPattern =
AiPlayerbot.LLMContextLength = 12288
AiPlayerbot.LLMBanterEnabled = 1
```

The response pattern keys are emitted EMPTY: the native client parses
the chat-completions envelope as JSON and decodes
`choices[0].message.content` directly — the old regex patterns are dead on
every endpoint this block configures, and the keys must be written empty so
the native defaults (which never match decoded prose) do not
re-arm. See docs/llm-runtime-submenu.md for the current sample and the
per-profile sampling fields.

## Resource rules (validated on-device, QCS8550 / Retroid Pocket 6)

- Decode-core profiles (measured): 3×mid balanced · 2×mid low-draw ·
  1×mid lowest power per token. NPU prefill ignores cores entirely.
- The service polls `/proc/pressure/memory`; sustained PSI (some>70 / full>25)
  SIGTERMs the server (never SIGKILL — a hard kill mid-load leaks the DSP
  session until reboot) rather than letting anything else die. It restarts
  with backoff once pressure clears.
- NPU practical ceilings: all-layer offload safe for files ≤ ~2.9 GB; the
  Hexagon session address window is ~4 GB (partial `-ngl` beyond); context
  ≤ 32k on the hybrid path.

## Build provenance

llama.cpp master @ `6d05498` (2026-08-19), NDK r28c, arm64-v8a, min Android
8.1 (API 28) for the prebuilt runtime (the module's minSdk is 26 to match the
app; the runtime is only exercised on API 28+ devices). One local patch on
top: the server signal handler installs before model load (see
`prebuilt/README.md`). Rebuild: `ninja -C build-droid-kai llama-server`, then
llvm-strip the two artifacts.

## External endpoints + authored banter

- The submenu's Source choice can point the realm conf at ANY
  OpenAI-compatible `/v1/chat/completions` endpoint (settings: URL, optional
  Bearer key — the pristine C++ client already sends `LLMApiKey` — and model
  name). In that mode this module's runtime never starts. See
  `docs/llm-runtime-submenu.md` §"External endpoint mode".
- `AiPlayerbot.LLMBanterEnabled` (default 1, emitted in every LLM conf
  block) gates the authored banter layer: trait seasoning segments in
  `BuildPromptContext` (`POCKETREALM_LLAMA_PROMPT_FORMAT_VERSION` now 4),
  rare kill quips via the new `Unit::Kill` hook, tier greetings on
  long-absence hellos, idle/mood lines, and the POOL_BUSY busy placeholder.
  Persona cells are 12 lines deep. All of it is authored text — no model
  calls, no governor budget.
