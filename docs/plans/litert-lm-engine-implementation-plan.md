# LiteRT-LM Engine — Implementation Plan (second on-device LLM engine)

Standing runbook for adding **Google LiteRT-LM** (`com.google.ai.edge.litertlm`,
pinned `0.16.1`; `.litertlm` model format) as a **second engine beside the
embedded llama-server**: an opt-in experiment on Snapdragon devices and the
LiteRT CPU path for non-Snapdragon devices. Phase 1 shape: the engine runs
**inside the existing fault-isolated `:llm` process** behind the same loopback
OpenAI-compatible contract, so the native playerbot client, the conf chain,
and the merge-order guarantees are untouched.

**Owner-set compute policy (2026-09-03, from `G:\NPU LLM\docs\findings.md`):
LiteRT-LM may use the NPU or the CPU — never the GPU.** The findings log
bans GPU inference while the game runs on three independent grounds:
(1) OpenCL weight upload pins ~4.3 GB of non-reclaimable unified memory for
a 1.26 GB model → PSI critical → the lmkd cascade kills `:world` then
`:client` (root-caused 2026-08-20 midnight, reproduced in BOTH load
orders); (2) the Adreno 740 proprietary Vulkan driver silently miscompiles
llama-class compute shaders; (3) even where GPU is fastest it costs
0.544 J/token at +8.14 W — "faster and still disqualified" (ppgrid2,
2026-08-28). The same log shows the working NPU story is the **hybrid HTP
path** (GenieX, already integrated as `ComputeMode.NPU`): prefill
170-190 in-tok/J vs ~35 for CPU, HTP-attributable decode power +0.95 W
@3×mid / −2.75 W @1×mid.

Device nuance for expectations: LiteRT QNN model builds exist for
SM8750/qcs8275-class SoCs — **not** the RP6's SM8550 — so on the RP6 the
LiteRT engine is the CPU "test it out" path (the RP6's NPU acceleration is
the already-shipped GenieX track); LiteRT-LM's NPU delegate matters on
newer Snapdragon and Tensor G5/G6 devices.

Basis: `docs/research/LiteRT-LLM-Integration-Research.md` (ecosystem facts
re-verified 2026-09-03 against Google Maven; latest stable 0.16.1,
0.17.0-alpha1 2026-08-29 — stay off alphas).

**This plan is NOT executed by writing it.** A fresh session picks a phase,
implements it, satisfies the phase gate, and updates Part 0.

---

## Part 0 — Status snapshot (update every phase)

| Phase | State | Gate |
|-------|-------|------|
| P0 Engine abstraction in `:llm` | not started | byte-identical llama path, module tests green both copies |
| P1 LiteRT-LM engine + probe | not started | shim envelope + probe-fallback tests; conf chain untouched |
| P2 Registry + model distribution | not started | per-engine model presence + download tests |
| P3 Settings + UI (advanced tier) | not started | write-set round-trip; AUTO matrix pure test |
| P4 Docs + release checklist | not started | THIRD_PARTY notices, wiki, submenu doc |
| P5 Device validation (user-gated) | not started | RP6 contention/thermal numbers; AUTO-matrix verdict (DEC-2) |

## Non-goals

- No changes to the llama.cpp / GenieX Hexagon track (it stays the Snapdragon path).
- No native in-process C-API backend (`LLM_BACKEND_LITERT = 2`) — optional Phase 6, deferred (DEC-3).
- No model redistribution in-APK; models download (or side-load) exactly like the GGUFs today.
- No sampler UI — the registry sampling profiles remain the single source.

## Contract invariants (must hold through every phase)

1. `AiPlayerbot.LLMBackend = 0` and `LlmRuntimePolicy.confBlock()` /
   `confBlockExternal()` emission are unchanged; `LlmConfMergeOrderTest`
   passes **unmodified** (the base conf's `LLMEnabled = 0` + last-wins
   append is the safety contract).
2. `AndroidRuntimeBackend.ensureLlmRuntime` keeps its ordering rule: the
   engine's model memory is claimed **before** the world process starts.
3. The `:llm` process remains the crash boundary — an engine crash (LiteRT
   JNI included) kills the service, never the realm; the supervisor's
   restart/backoff path governs both engines identically.
4. **No GPU backend, ever** (owner policy from findings.md — pinned-RAM
   kill chain + driver miscompile + energy). The LiteRT engine constructs
   `Backend.CPU(threadCount)` or `Backend.NPU(...)` only; a code review
   finding `Backend.GPU()` anywhere is a blocker.
5. **NPU session discipline carries over** (findings 2026-08-28 ops): the
   fastrpc/HTP VA window is 4 GiB cumulative and NOT reclaimed on munmap
   (leaked sessions wedge until reboot) — keep SIGTERM-only stops, fresh
   process per (re)start, and a memory gate before any NPU spawn (the
   GenieX `HexagonProbe` precedent; the LiteRT analog below).
6. LiteRT is **opt-in until P5** says otherwise: `llmEngine` defaults to
   AUTO with AUTO resolving to llama today (see P3 matrix).
7. The chatter power ladder (`ChatterPowerMonitor`) is engine-agnostic and
   unchanged.

---

## P0 — Engine abstraction inside `:llm` (no behavior change)

Goal: a seam to host a second engine without touching the fork/exec path.

- New `LlmEngine` interface in `android/pocketrealm-llm`:
  `start(config)`, `stop()`, `probe()`, `healthEndpoint()`, `stats()` —
  plus a stats-broadcast payload extension (see P1 readback).
- `LlamaServerEngine` implements it by wrapping the **current** code paths
  verbatim: `LlmRuntimeService` argv/env construction (`-t`, `--log-file`
  before `--device`, NPU `--device HTP0 -ngl`, `GGML_BACKEND_PATH`,
  `ADSP_LIBRARY_PATH`), `LlmExec.nativeExec`, the generation-token
  supervision, and the health-ownership gate.
- `LlmRuntimeService` routes start/stop/restart through the interface.

**Gate:** diff of the llama path's argv/env construction is empty
(characterization test holds the exact lists, the pattern of
`ArmSessionEnvironmentTest`); module unit tests green in both copies
(game app + bench app); manual start/stop/start-now smoke.

## P1 — LiteRT-LM engine + OpenCL probe (the core)

- **Dependency pin** in `android/pocketrealm-llm/build.gradle.kts`:
  `implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")`
  (AAR: arm64-v8a + x86_64, minSdk 24, ~21.5 MB `.so`/ABI, ~9 MB compressed).
- **Release-build hardening** (no consumer rules ship upstream):
  - `proguard-rules.pro`: `-keep class com.google.ai.edge.litertlm.** { *; }`
    (absent this, R8 release builds crash at `SamplerConfig.getTopK`).
  - No OpenCL manifest declarations — the GPU path is not shipped at all
    (owner policy; `libOpenCL.so` `uses-native-library` entries would be
    dead weight).
- **`LiteRtEngine`**: `Engine(EngineConfig(modelPath, backend,
  maxNumTokens, cacheDir))` per loaded model; one `Conversation` per warm
  bot slot (the llama-server slot analog); a **serialized** generation
  queue (concurrent decode across Conversations is UNVERIFIED upstream —
  serialize, which also matches the native governor's lane model). Low-level
  `Session` API only if the `Conversation` system-instruction path proves
  insufficient. Backend construction is **CPU or NPU only** (invariant 4).
- **Loopback HTTP shim** on the same fixed port serving:
  - `POST /v1/chat/completions` — response envelope byte-compatible with
    what `PlayerbotLlmJson` parses: `choices[0].message.content`, full
    JSON string semantics, `finish_reason` (`length` triggers the native
    truncation-aware splitter), `stream:false` only.
  - The health endpoint with the same semantics the health-ownership gate
    expects (a 200-answering local listener is the `healthy` signal).
  - `GET /tokenize` — **not provided**: the native era-bias splice
    (`LLMEraBias = 1`) fails open by design (logged once per world) —
    acceptable; era bias is a client-side nicety, not a contract.
- **Sampler mapping** (pure, unit-tested):
  `LlmSamplingProfile → SamplerConfig` — temperature/topP/topK/
  repetitionPenalty map 1:1; `minP`/`presencePenalty` have **no engine
  equivalent** — dropped with a one-time log when a profile sets them
  (Gemma profiles pin repeat-penalty 1.0 and don't set them). No stop
  sequences / no `min_p` — the native delete/split patterns already
  substitute.
- **`LiteRtNpuProbe`** (the `HexagonProbe` analog, NPU-only per the owner
  policy): resolve the device's QNN eligibility the way findings.md
  established — (a) cDSP firmware/remoteproc present (the Aug-19
  firmware-dead class of failure), (b) QAIRT runtime libs loadable,
  (c) the selected `.litertlm` carries an NPU (QNN) build variant for
  this SoC (`_qualcomm_sm8750`-style suffixes; SM8550 has none — the RP6
  answers "CPU only" here and that is correct, not a failure). On any
  miss: `Backend.CPU(threads)`, persisted verdict, "backend actually
  used" readback. **Never probe or construct a GPU backend.**
- **Readback**: extend the `ACTION_STATS` broadcast extras with
  `engine=` and `backendActuallyUsed=`; `LlmScreen` renders it in the
  Runtime card's state line (the existing `EXTRA_MODE` freshness gate
  applies unchanged).

**Gate:** unit tests — shim envelope fixtures (content, finish_reason,
thinking-preamble empty-content passthrough), sampler mapping table (incl.
the minP-drop log), probe verdict table (cDSP-present+model-NPU-build →
NPU; firmware-dead / no-QNN-libs / no-NPU-model-build → CPU with the
reason string); `LlmConfMergeOrderTest` green untouched; detekt clean.

## P2 — Registry + model distribution

- `LlmModelDescriptor` gains `engine: LlmEngineKind { LLAMA_GGUF, LITERT_LM }`
  (default LLAMA_GGUF keeps every existing entry and test stable).
- Registry additions (pending **DEC-1** owner pins of exact files):
  Gemma-4-E2B litert-lm build (the GPU-benchmarked default for the
  LiteRT engine), Gemma-3-1B int4 (529 MB) and Qwen3-0.6B for ≤6 GB
  tiers. `url/size/sha256` feed the existing resumable downloader and the
  `LlmModelCoordinator` sha256-verified "model present" gate unchanged.
- Per-engine presence: the staged-file gate resolves against the selected
  engine's kind — a staged GGUF does not satisfy a LiteRT engine and vice
  versa; the Model card and `ensureLlmRuntime` both read the same gate.
- Apache-2.0 models only for auto-download (Gemma-4/Qwen3); gated models
  (Gemma-3/3n ToU, Llama) only via explicit side-load.

**Gate:** `LlmModelRegistryTest` extensions (kind defaults, byId
fail-safe); coordinator per-engine presence test; download service test
reused as-is.

## P3 — Settings + UI (advanced tier only)

- `Settings.Snapshot`: `llmEngine: LlmEngineChoice { AUTO, LLAMA, LITERT }`
  (default AUTO) — Keys + reader + `writeSnapshotWrites` +
  `SettingsUpdateWriteSetTest` (the closed write-set discipline).
- **AUTO matrix** (pure function over SoC class + staged models, unit-tested;
  the probe is the runtime backstop, not the policy):
  - Snapdragon (Hexagon probe capable) → llama (the GenieX hybrid is the
    measured advantage; findings.md prefill-energy hierarchy HTP ≫ CPU).
  - Everything else → LiteRT if a `.litertlm` model is staged, else llama CPU.
  - The LiteRT engine itself resolves **NPU when `LiteRtNpuProbe` passes,
    CPU otherwise — GPU is never a candidate** (owner policy, invariant 4).
- **LlmScreen advanced tier**: engine ChoiceRow (Auto / llama-server /
  LiteRT); when LiteRT is selected the Accelerator card's compute labels
  read Auto/CPU/NPU (matching the llama engine's semantics — no GPU row);
  probe status + backend-actually-used readback line; Model picker
  filters/annotates by engine kind. Simple tier gains nothing new (AUTO is
  invisible when it does the right thing).
- Supervisor: `ensureLlmRuntime` honors the matrix; llama-only code paths
  (chat-template staging/retry, NPU argv) are skipped, not broken, under
  the LiteRT engine.

**Gate:** `SettingsUpdateWriteSetTest`; matrix pure-function table test;
manual: engine flip applies at next realm start (conf is engine-agnostic —
only the service changes).

## P4 — Docs + release checklist

- `docs/llm-runtime-submenu.md`: engine section (what LiteRT is, when AUTO
  picks it, probe/readback semantics, the `/tokenize` fail-open note).
- `docs/wiki/Settings.md` AI section: engine row in the advanced tier.
- `THIRD_PARTY_NOTICES.md`: LiteRT-LM Apache-2.0 notice set (the GenieX
  commit `01f695e` is the precedent).
- `DEVICE-TEST-CHECKLIST-DUAL-PROVIDER-LLM.md`: engine-A/B row.

**Gate:** repo checks green; detekt baseline refresh recorded as its own
commit (new LiteRT files will trip fresh signatures — fix or baseline
consciously, never blanket-baseline).

## P5 — Device validation (user-gated runbook)

On the RP6 (8 Gen 2 / Adreno 740) and one Mali + one Tensor device:

1. Gemma-4-E2B litert-lm build: LiteRT-CPU vs llama-kai-CPU tok/s / TTFT /
   peak RAM, alone and with the game running (mid-core pinning both) — the
   honest RP6 comparison, since SM8550 has no LiteRT NPU model builds.
2. On an NPU-capable device (SM8750-class or Tensor G5/G6): LiteRT NPU
   vs CPU, same sessions — this is the only acceleration comparison the
   policy allows.
3. 30-minute thermal soak with the game + the engine under test; ladder
   behavior sanity (chatter easing at the toned-down thresholds).
4. Probe/fallback forced-failure drill (NPU block → CPU → Reset), release
   build (R8 rules proven). Plus the DSP-session-leak drill: a killed
   engine start must never wedge the next one (fresh-process discipline).
5. Verdict recorded against **DEC-2**: LiteRT becomes the AUTO default for
   non-Snapdragon (and optionally the opt-in experiment default on
   Snapdragon) — or stays purely opt-in if the CPU numbers disappoint.

---

## Decisions needed from the owner

- **DEC-1 — model pins**: exact `.litertlm` files (HF `litert-community`),
  with URL + sha256 + size for the registry; which tiers to carry. Note:
  NPU (QNN) build variants exist per-SoC — SM8750/qcs8275 yes, SM8550 no
  (findings.md "Model artifacts").
- **DEC-2 — default policy** (after P5): LiteRT as AUTO default on
  non-Snapdragon? (Its backend is NPU-when-probable-else-CPU per the
  owner policy — the old "SD GPU experiment" framing is dead.)
- **DEC-3 — Phase 6 (optional)**: in-process C-API backend
  (`LLM_BACKEND_LITERT = 2` + prebuilt `liblitert-lm.so` vendored under
  `native/llm/` with a lockfile). Defer until the sidecar proves
  the engine.

## Risks

| Risk | Mitigation |
|---|---|
| Pre-1.0 churn (breaking releases every 2–4 weeks) | exact pin; engine behind the one `LlmEngine` seam; upgrade is a deliberate re-pin |
| R8 strips JNI-reached members → release-only crash | keep rules in P1; release-build drill in P5.4 |
| GPU would kill the game (pinned-RAM lmkd cascade) / bad Vulkan driver / energy | **GPU is not implemented at all** — invariant 4; code-review blocker if `Backend.GPU()` appears |
| cDSP firmware-dead devices (the RP6 Aug-19 class) | `LiteRtNpuProbe` firmware/remoteproc check → CPU with reason string |
| Leaked DSP sessions wedge until reboot (fastrpc VA not reclaimed) | fresh process per (re)start, SIGTERM-only stop, memory gate before NPU spawn — the shipped GenieX discipline, applied to the LiteRT NPU path too |
| `min_p` / stop-sequences absent in the engine | registry profiles avoid them; native delete/split patterns substitute |
| LiteRT CPU slower than llama-kai on SD devices | expected (kai is tuned for these cores); that's why AUTO stays llama on Snapdragon — LiteRT there is the explicit "test it out" choice |

## Open questions (UNVERIFIED, resolve during P1)

- Concurrent decode across multiple `Conversation`s on one `Engine`
  (serialize until proven otherwise).
- Exact resident-RAM of the Gemma-4-E2B litert-lm build on-device vs the
  Google-published 676 MB GPU figure (PLE embedding mmap behavior under
  the realm's memory pressure).
- Whether the shim needs `cache_prompt`-style prefix reuse semantics —
  LiteRT manages its own KV/prefill cache; measure whether the native
  client's per-bot slot pattern needs an explicit conversation-reset hook.
