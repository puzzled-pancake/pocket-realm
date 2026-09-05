# Deep research: adding LiteRT as an LLM engine option (with simple + advanced AI settings)

Research date: 2026-09-02; **updated 2026-09-03** after the LLM submenu,
model registry, and Hexagon NPU support shipped, and after the simple +
advanced settings tiers landed. Ecosystem facts (§§2–4) are unchanged —
re-verified 2026-09-03 against Google Maven (`litertlm-android` latest:
0.16.1 stable, 0.17.0-alpha1 2026-08-29). Items that could not be confirmed
on a primary source are marked **UNVERIFIED**.

Goal: evaluate adding Google's LiteRT stack as a second on-device LLM engine
alongside the embedded llama-server — as the acceleration option for
non-Snapdragon devices and as an opt-in experiment on Snapdragon devices —
and design the settings surface (a simple tier plus a verbose advanced tier;
the tier split itself has now shipped, see §6).

---

## 1. What Pocket Realm has today (codebase findings, 2026-09-03)

The LLM feature is fully app-visible and runs in production configuration:

- **Runtime**: a fault-isolated `:llm` Android process
  (`android/pocketrealm-llm`, `LlmRuntimeService`, foreground `dataSync`)
  fork/execs a vendored **llama-server**, binding
  `http://127.0.0.1:8080/v1/chat/completions`. Compute placement is
  `ComputeMode { AUTO, CPU, NPU }`: the NPU hybrid is the **GenieX Hexagon
  backend** (`libggmlhex.so` + per-SoC DSP skels v73/75/79, gated by
  `HexagonProbe` with a FastRPC/remoteproc/memory check, 2-death block with
  persisted `npuBlocked` and CPU fallback). Decode cores/threads are pinned
  app-side (`llmCoresMask 0x38`, `llmThreads 3`).
- **Playerbot side**: `AiPlayerbot.LLMBackend = 0` (HTTP → the loopback
  server; the native client builds the trained prompt format natively,
  `LLMPromptFormat = 1`). A second backend, `LLM_BACKEND_LLAMA = 1`
  (in-process `PlayerbotLlamaRuntime`), exists only behind the debug-build
  override in `ServerRuntimeFiles.llmOverrides` for the adb workflow.
- **Conf chain**: `Settings` (DataStore) → `LlmRuntimePolicy.confBlock` /
  `confBlockExternal` → appended after `BotProfile.playerbotConfig()` in
  `aiplayerbot-<id>.conf` (last-wins parse wins over the base
  `LLMEnabled = 0`).
- **Model registry**: `LlmModelRegistry` — descriptors
  `{id, fileName, url, size, sha256, sampling profile, tier profile}` for
  the tuned Gemma-4-E2B (default), a tuned Qwen-0.8B, and the untuned
  Gemma-4-E2B base (HF-pinned sha256). `LlmModelCoordinator` verifies
  sha256 before a model is "present"; `LlmModelDownloadService` does
  resumable, checksum-verified downloads.
- **Settings surface (shipped 2026-09-03)**: the Settings "AI bot LLM"
  card carries the simple tier (master switch); `LlmScreen` splits into the
  always-visible simple tier (speech, source embedded/external, banter,
  world chatter, model picker) and an "Advanced engine settings" disclosure
  (`llmAdvanced`) gating the Accelerator (compute mode, decode cores,
  threads, NPU offload), Generation (reply-length + timeout overrides,
  `llmMaxNewTokens`/`llmGenerationTimeout`, 0 = model/tier default), and
  Connection (external endpoint/URL/key) cards.

So "adding LiteRT" now means: a second ENGINE beside llama-server behind the
existing loopback HTTP contract plus registry/UI extensions — the whole
app-side settings/model-management surface this research said was missing
already exists for the llama engine and is reusable as-is.

---

## 2. The LiteRT landscape in 2026 — three generations, one current path

The name "LiteRT" covers several distinct things; picking the wrong one is the
main trap:

| Generation | Artifact | Format | Status (2026-09) |
|---|---|---|---|
| MediaPipe LLM Inference API | `com.google.mediapipe:tasks-genai` (0.10.35, 2026-04) | `.task` | **Maintenance-only**; Google recommends migrating off it |
| Classic LiteRT (core runtime + delegates) | `com.google.ai.edge.litert:litert*` (2.2.0) | `.tflite` | Active, but not the LLM path |
| **LiteRT-LM** (current) | **`com.google.ai.edge.litertlm:litertlm-android`** (0.16.1, 2026-08-18; 0.17.0-alpha1) | **`.litertlm`** | The recommended LLM engine; pre-1.0, fast-moving |

There is no `litert-llm` artifact (verified absent from Google Maven and Maven
Central). `.task` files are **not** consumed by LiteRT-LM — do not build on the
MediaPipe path for new code.

### Runtime facts that matter for this repo

- **Kotlin API** (`com.google.ai.edge.litertlm`): `Engine(EngineConfig(modelPath, backend, maxNumTokens, cacheDir))` →
  `engine.createConversation(ConversationConfig(systemInstruction, samplerConfig, maxOutputToken, …))` →
  `conversation.sendMessageAsync(text): Flow<String>`. Backends:
  `Backend.CPU(threadCount)`, `Backend.GPU()` (OpenCL on Android),
  `Backend.NPU(nativeLibraryDir)`, `Backend.GOOGLE_TENSOR()`.
  A low-level `Session` API (`runPrefill`/`runDecode`/`generateContentStream`)
  exists that behaves like llama.cpp's raw completion path — useful because
  the playerbots llama backend already sends raw completion prompts.
- **C API with official prebuilts since v0.16.0 (2026-08-11)**:
  `litert_lm_c_api-0.1.0.zip` ships `engine.h`/`conversation.h` and a
  prebuilt `liblitert-lm.so` for android arm64 (39 MB) and x86_64, with no
  JVM dependency (it loads `libOpenCL.so` itself). This makes **pure-native
  embedding inside the realm process possible**, exactly like llama.cpp.
- **Sampler parity is good**: topK, topP, temperature, seed, repetition /
  presence / frequency penalty (`RepetitionPenaltyConfig`),
  no-repeat-ngram, token suppression, even regex/JSON-schema constrained
  decoding. **Not supported: `min_p`, stop sequences, logit bias.** The
  playerbots delete/split patterns already handle stop-like trimming
  server-side, and `LLMRepeatPenalty` maps 1:1 — so the existing config
  surface transfers cleanly.
- **Distribution details**: AAR contains arm64-v8a + x86_64 only (good: this
  repo's x86_64 test lane works), minSdk 24, ~21.5 MB `.so` per ABI
  (~9 MB compressed), 16 KB-page aligned, Apache-2.0 (GPL-3.0-compatible,
  one-way). **No R8 consumer rules ship** — release builds crash
  (`SamplerConfig.getTopK` `NoSuchMethodError`) without your own
  `-keep class com.google.ai.edge.litertlm.** { *; }`. GPU requires
  `<uses-native-library android:name="libOpenCL.so" android:required="false"/>`
  (plus `libvndksupport.so`) in the manifest.
- **Release cadence** is ~2–4 weeks with breaking changes between minors —
  pin an exact version, don't use `latest.release`.
- Since v0.13.0 the companion CLI has an **OpenAI-compatible server mode**,
  and since v0.14.0 the CLI/Python run on-device (aarch64/x86_64) — evidence
  that hosting LiteRT-LM behind a loopback HTTP API is a supported pattern.

Reference implementation: **Google AI Edge Gallery** (Apache-2.0,
github.com/google-ai-edge/gallery) is fully migrated to `litertlm-android`
and is the best code to crib engine lifecycle, model download, and
conversation-reset handling from.

---

## 3. Hardware reality: where LiteRT actually accelerates

Android GPU acceleration in LiteRT-LM is **OpenCL-first (ML Drift), with an
OpenGL fallback; there is no Vulkan backend on Android.** Consequences:

- **Adreno (Qualcomm — incl. the Retroid Pocket 6's 8 Gen 2 / Adreno 740)**:
  the best case. Officially benchmarked numbers show GPU's big win is
  **prefill/TTFT (5–7×)** — e.g. Gemma-4 E2B on a Galaxy S26 Ultra: CPU
  557 tok/s prefill / 46.9 tok/s decode / 1.8 s TTFT / 1733 MB RAM vs GPU
  3808 / 52.1 / 0.3 s / **676 MB**. Decode gains are modest (~1.1–1.3×,
  bandwidth-bound), but RAM on Android GPU was *lower* than CPU (weight
  sharing + mmap). Gemma 3 1B reached 2585 tok/s prefill (int4, 529 MB).
- **Mali (MediaTek / Exynos)**: works on flagships, but there are recent
  structured-eval reports of **fp16 numerical corruption on Mali GPUs**
  (garbled numbers/tokenization) acknowledged by Google and unfixed for weeks,
  plus the historical op-coverage/partial-graph-splitting slowness. Treat Mali
  GPU as experimental, default to CPU there, and log which backend actually
  initialized.
- **Google Tensor (Pixel)**: **Tensor G3/G4 do not expose OpenCL** →
  `Backend.GPU()` constructs but crashes at inference (LiteRT-LM issue
  #1860). NPU support exists only for Tensor G5/G6. On Pixels: CPU only.
- **NPU on Snapdragon**: real but narrow — LiteRT-LM's QNN path officially
  supports SM8550/8650/8750 for **Gemma-3-1B only, 4-bit, fixed 1280-token
  context**, per-SoC model builds, QAIRT runtime libs, community reports of
  SIGKILL/SIGSEGV during NPU init ("treat as experimental"). The practical
  NPU path for LLMs on Snapdragon in 2026 remains **llama.cpp's
  Hexagon/QNN backend** (e.g. Llama-3.2-1B Q4_0 on an 8 Gen 2-class HTP:
  ~169 tok/s prefill / ~51.5 tok/s decode) — which Pocket Realm's llama
  backend could adopt later as its own track. LiteRT NPU is not worth
  building on yet.
- **NNAPI is irrelevant**: deprecated (Android 15), absent from the modern
  stacks; everything here is XNNPACK (CPU) / OpenCL (GPU) / vendor NPU
  dispatch.

### The contention question (specific to Pocket Realm)

While playing, the WoW client draws through DXVK over Vulkan on the same GPU
an OpenCL LiteRT engine would use. GPU compute and graphics time-slice, and
LLM decode is memory-bandwidth-bound, so expect mutual degradation; there are
also thermal reports of co-locating NPU+GPU LLM work on phones
(UNVERIFIED severity). Bot chat is bursty and short (~120 tokens), so this
may be acceptable — but it must be measured on the RP6 with the game
running before GPU becomes any device's default. A per-SoC capability probe
(try GPU, catch init/inference failure, fall back to CPU, record what
actually ran) is mandatory given the Tensor/Mali/Xclipse failure modes.

### Verdict on "LiteRT for non-SD devices and SD testers"

**Project decision (2026-09-03, owner directive from
`G:\NPU LLM\docs\findings.md`): LiteRT-LM uses NPU or CPU only — GPU is
banned.** The findings log root-caused GPU-while-gaming as fatal (OpenCL
pins ~4.3 GB non-reclaimable for a 1.26 GB model → PSI critical → lmkd
kills `:world`/`:client`, in both load orders), shows the Adreno Vulkan
driver silently miscompiles compute shaders, and measures GPU as
energy-hostile (0.544 J/tok, +8.14 W). The ecosystem facts below are
retained as research; the operative plan is
`docs/plans/litert-lm-engine-implementation-plan.md`.

- **Snapdragon today** (llama.cpp prebuilts are CPU-only): the working NPU
  acceleration is the **GenieX Hexagon hybrid already integrated**
  (`ComputeMode.NPU`; findings 08-27/28: 537 t/s prefill, HTP decode
  power +0.95 W @3×mid / −2.75 W @1×mid). LiteRT on an SM8550 is the
  CPU-only "test it out" option (no LiteRT QNN model builds exist for
  SM8550); LiteRT's NPU delegate matters on SM8750+/Tensor G5/G6.
- **Mali flagships**: LiteRT CPU is the path; there is no NPU story.
- **Tensor devices**: LiteRT CPU (G3/G4); NPU only on G5/G6.

---

## 4. Models and the Unsloth pipeline

- **Gemma 4 (Apache-2.0, ungated, shipped 2026-04) is the headline for this
  project**: `litert-community/gemma-4-E2B-it-litert-lm` — GPU build
  ~1.87 GB download, ~0.8 GB resident text weights (PLE embeddings mmap'd);
  officially benchmarked on Android GPUs. Apache-2.0 means the GPL-3.0 app
  can auto-download and even redistribute without a gate — the licensing
  headache of Gemma 3/3n (Gemma ToU, gated) and Llama 3.2 (community
  license, gated, 700M-MAU clause) disappears.
- Smaller/alternative Apache options: Qwen3-0.6B (344–498 MB), Qwen3-4B
  (2.48 GB), Phi-4-mini (MIT), SmolLM3. For a 6 GB device tier, Gemma-3-1B
  int4 (529 MB, ~0.7–1.2 GB runtime) or Qwen3-0.6B is the realistic fit.
- **Unsloth has no native LiteRT/`.task` export** (its phone path is
  ExecuTorch `.pte`; verified against the docs sitemap). The
  Google-endorsed bridge is: **fine-tune in Unsloth → merge LoRA → push an
  ordinary HF checkpoint → `litert-torch export_hf` → `.litertlm`**
  (`litert-torch` is the renamed `ai-edge-torch`; supports Gemma 3/3n/4,
  Llama, Mistral, Qwen 2/2.5/3, SmolLM3). Google's own tutorial uses
  Unsloth for the fine-tune step, so this is the intended workflow. Test on
  desktop with `litert-lm run`, then ship the file.
- **No runtime LoRA in LiteRT-LM** (open feature request #1188; the legacy
  MediaPipe path had GPU-only LoRA for Gemma-2/Phi-2 only). Merge before
  conversion; per-bot personality must come from system prompts /
  pre-prompt templates, not adapters.
- RAM rule of thumb for the 6 GB tier (realm + game + LLM): stay ≤ ~1.5 GB
  LLM footprint → Gemma-3-1B/Qwen3-0.6B/0.6–1.7B class. 12 GB (RP6) can
  take Gemma-4 E2B comfortably.

---

## 5. Integration blueprint (updated for the shipped architecture)

The 09-02 draft proposed building a `:ai` sidecar from nothing; the `:llm`
sidecar pattern now exists, runs before the world claims memory
(`AndroidRuntimeBackend.ensureLlmRuntime`), and the playerbots talk to it
over the OpenAI-compatible loopback contract with **zero native
involvement**. That collapses the LiteRT integration into engine-shaped
work inside the existing Kotlin stack:

1. **Phase 1 — LiteRT-LM engine mode inside the `:llm` service.** Add an
   engine dimension to the runtime: `llmEngine ∈ {LLAMA, LITERT}` (settings
   field, advanced-tier override; simple tier stays "Auto"). When
   `LITERT` (or AUTO picks it for a non-Snapdragon/qualifying device):
   instead of fork/exec'ing llama-server, `LlmRuntimeService` hosts
   `litertlm-android` **in-process within the isolated `:llm` process** —
   an `Engine` per loaded model, a `Conversation` per warm bot slot
   (standing in for llama-server's slots), a serializer queue, and a tiny
   loopback HTTP shim serving the same `/v1/chat/completions` envelope the
   native client already parses (or the low-level `Session` raw-completion
   API if we keep the exact llama prompt byte-contract). Crash isolation is
   preserved (the process dies, not the realm); `LLMBackend = 0` and
   `LlmRuntimePolicy.confBlock` are reused **verbatim**.
2. **Registry**: add `engine` to `LlmModelDescriptor`; register `.litertlm`
   entries (Gemma-4-E2B litert-lm build as the default LiteRT model,
   Gemma-3-1B int4 / Qwen3-0.6B for small tiers). `url/size/sha256` +
   `LlmModelCoordinator` verification and the download service work
   unchanged — the descriptors only need upstream URL/sha pins per file
   (pending; HF `litert-community` hosts them).
3. **Probe + fallback** (the `HexagonProbe` analog): an `OpenClProbe` —
   construct `Backend.GPU()`, run one tiny prefill/decode; on
   `LiteRtLmJniException`/init-or-first-inference failure fall back to
   `Backend.CPU(threads)`, persist the verdict, and surface "backend
   actually used" through the existing `ACTION_STATS` broadcast extras
   (`EXTRA_MODE` already carries a mode string to `LlmScreen`).
4. **Phase 2 (optional) — in-process C API** (`LLM_BACKEND_LITERT = 2`
   beside the llama backend in `PlayerbotLlamaRuntime`'s shape, prebuilt
   `liblitert-lm.so` vendored under `native/llm/` with a lockfile): removes
   the HTTP hop but drags in a native build lane; defer until Phase 1
   proves the engine.

### Concrete wiring checklist (Phase 1)

- `android/pocketrealm-llm/build.gradle.kts`: pin
  `implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")`
  (exact pin; 2–4-week breaking cadence). The AAR ships arm64-v8a +
  x86_64, minSdk 24, ~21.5 MB `.so` per ABI (~9 MB compressed), 16 KB-page
  aligned.
- `AndroidManifest.xml` (`:llm` service already exists):
  `<uses-native-library android:name="libOpenCL.so" android:required="false"/>`
  + `libvndksupport.so` (GPU path).
- `proguard-rules.pro`: `-keep class com.google.ai.edge.litertlm.** { *; }`
  — **no consumer rules ship**; release builds crash
  (`SamplerConfig.getTopK` `NoSuchMethodError`) without it.
- Settings: `llmEngine` (advanced tier) + engine readback line in the
  Runtime card; `llmComputeMode` gains a LiteRT meaning (AUTO/CPU/GPU) when
  the LiteRT engine is selected.
- Governor: keep the app-side duty-cycle semantics — LiteRT Conversations
  are serialized per Engine (UNVERIFIED for concurrent decode), which maps
  1:1 onto the existing governor/generation-lane behavior.
- Battery ladder: unchanged — `ChatterPowerMonitor` gates the chatter
  layers app-side regardless of which engine decodes.
- Release checklist: LiteRT-LM + its ~2 MB THIRD_PARTY_NOTICE set into
  `THIRD_PARTY_NOTICES.md` (Apache-2.0 §4; the GenieX attribution commit is
  the precedent).

---

## 6. Settings design (shipped 2026-09-03 — this section documents what exists)

The AutoLoginTimings idiom was followed at tier level: the Settings
"AI bot LLM" card holds the simple tier (master `llmEnabled` switch +
pointer card), and `LlmScreen` gates its verbose cards behind an
`llmAdvanced` disclosure, exactly like "Advanced timing". Simple and
advanced write the same Snapshot; `normalized()`-style clamps run on read
and write (`LlmRuntimePolicy.normalize*`, `writeSnapshotWrites` pinned by
`SettingsUpdateWriteSetTest`).

### Simple tier (always visible)

| Control | Maps to |
|---|---|
| **Let bots talk with an AI** (Settings card; default off) | `llmEnabled` |
| **Source**: on-device / external server | `llmExternalMode` |
| **Authored banter** (default on — zero model cost) | `llmBanter` |
| **World chatter (beta)** (default off; battery-aware copy) | `llmAmbience` |
| **Model picker** (small-first, staged/download state) | `llmModelId` |
| Runtime status + Start/Stop now | `ACTION_STATS` readback |

### Advanced tier (behind "Advanced engine settings")

- **Accelerator**: compute mode AUTO/CPU/NPU(Hexagon) with live probe
  status + blocked-state readback; decode-core profiles; decode threads;
  NPU offload layers.
- **Generation**: reply length (`llmMaxNewTokens`, 0 = model's tuned
  profile; 48–600 clamped) and generation timeout
  (`llmGenerationTimeout`, 0 = tier default; 15–240 s) — emitted as
  `LLMMaxNewTokens`/`LLMGenerationTimeout` overrides through
  `LlmRuntimePolicy.confLines` so embedded and external paths stay
  byte-identical.
- **Connection**: external endpoint/model/API key (fail-closed
  normalizers) or the loopback readback.
- Deliberately **not** exposed: per-key sampler overrides
  (temp/topP/topK/repeat-penalty) — the tuned models are validated with
  their registry sampling profiles; hand-tuning them from the UI invites
  degraded speech for no measured gain. The conf keys remain available to
  adb users.

LiteRT-specific additions land in these existing cards when Phase 1
happens: an engine row (Auto/llama/LiteRT) in Accelerator, an
OpenCL-probe status + "backend actually used" readback line, and
`.litertlm` entries flowing into the existing model picker.

---

## 7. Risks and open questions

1. **Pre-1.0 churn**: breaking changes every few weeks; pin versions and
   keep the engine behind one thin wrapper (`LlmEnginePort`), like the
   Gallery's `LlmModelHelper`.
2. **GPU failure modes**: Tensor G3/G4 crash (no OpenCL), Mali fp16
   corruption reports, Samsung Xclipse issues, mid-range SoCs silently
   lacking OpenCL. Mitigation: probe + CPU fallback + readback (above).
3. **GPU contention with the game** on shared SoC memory bandwidth — measure
   on RP6 with the client running before defaulting anyone to GPU; consider
   "prefer CPU/NPU while playing" guidance.
4. **Sampler gaps**: no `min_p`, no stop sequences (delete/split patterns
   already substitute), no runtime LoRA (merge fine-tunes).
5. **App size**: ~9 MB compressed per ABI (both existing lanes covered);
   models are 0.5–2.5 GB downloads — never bundle, always download/side-load.
6. **Licensing**: Gemma-4/Qwen3 Apache-2.0 = clean; Gemma 3/3n and Llama 3.2
   are gated with flow-down terms — prefer Apache models for auto-download,
   gated ones only via explicit license flow.
7. **UNVERIFIED**: concurrent decode across multiple Conversations on one
   Engine (assume serialized; the queue-based governor design already fits);
   `litert-torch` int4 export recipes beyond the named defaults; exact
   on-device LiteRT-vs-llama.cpp CPU comparisons on an 8 Gen 2 (Google
   claims LiteRT wins CPU and GPU for Gemma-3-1B on S25 Ultra, chart-only).

---

## 8. Phasing proposal (aligned with the shipped LLM milestone language)

- **Phase 0 — spike (1–2 days)**: on an RP6-class device, a throwaway harness
  hosting `litertlm-android` in the `:llm` process shape: Gemma-4-E2B
  litert-lm GPU build, GPU vs CPU vs llama-server-CPU/NPU tok/s + TTFT,
  alone and with the game client running (GPU contention is the open
  question); thermals; probe/fallback behavior.
- **Phase 1 — ship the engine option**: engine mode in `:llm` +
  `llmEngine` setting in the (now-shipped) advanced tier, `.litertlm`
  registry entries with pinned URL/sha, OpenCL probe + CPU fallback +
  `ACTION_STATS` readback, R8 keep rules + OpenCL manifest entries,
  THIRD_PARTY notice.
- **Phase 2 — depth**: Mali/Tensor device-report-driven tuning, optional
  in-process C API backend (`LLM_BACKEND_LITERT`), side-load-a-`.litertlm`
  (SAF) for power users.
- **Parallel track (separate effort)**: llama.cpp QNN/Hexagon builds beyond
  the GenieX hybrid for the NPU-first path on Snapdragon — complementary,
  not competing, with LiteRT.

---

## Sources (primary unless noted)

- LiteRT-LM docs: developers.google.com/edge/litert-lm (overview, /android,
  /cpp, /file_builder, /tutorials/convert-and-run); NPU:
  developers.google.com/edge/litert/next/litert_lm_npu and
  /edge/litert/android/npu/qualcomm (updated 2026-05/09)
- Artifacts verified via dl.google.com group-index/maven-metadata
  (`com.google.ai.edge.litertlm`, `com.google.mediapipe`), plus direct AAR/POM
  inspection (litertlm-android 0.16.1, tasks-genai 0.10.35) and the
  `litert_lm_c_api-0.1.0.zip` release asset (github.com/google-ai-edge/LiteRT-LM
  releases v0.16.0/v0.16.1)
- Google blogs: "Blazing fast on-device GenAI with LiteRT-LM" (2026-05-19);
  "Unlocking Peak Performance on Qualcomm NPU with LiteRT" (2025-11-24);
  "LiteRT: The Universal Framework for On-Device AI" (2026-01-28); "Gemma 3 on
  mobile and web with Google AI Edge" (2025-03-12)
- Models: huggingface.co/litert-community (gemma-4-E2B/E4B-it-litert-lm,
  Gemma3-1B-IT, Llama-3.2-1B/3B, Qwen3-*), google/gemma-3n-*-litert-lm;
  HF blog "Gemma 4" (2026-04)
- Conversion: developers.google.com/edge/litert/conversion/pytorch/genai
  (`litert-torch export_hf`); pypi.org/project/ai-edge-torch (deprecation
  note); Unsloth docs sitemap + phone-deployment page (ExecuTorch-only)
- Issues: LiteRT-LM #1860 (Tensor G3 OpenCL), #2114/#2611 (Xclipse), #2292
  (S24 Ultra GPU init), #1188 (LoRA request), #1850; TensorFlow #43073
  (compute-vs-graphics concurrency); llama.cpp snapdragon backend docs +
  discussion #8273; discuss.ai.google.dev Mali structured-eval thread (2026)
- Reference app: github.com/google-ai-edge/gallery (LiteRT-LM migration,
  `runtime/LlmModelHelper.kt`, `libs.versions.toml`)
- Licensing: apache.org/licenses/GPL-compatibility.html; ai.google.dev/gemma/terms
