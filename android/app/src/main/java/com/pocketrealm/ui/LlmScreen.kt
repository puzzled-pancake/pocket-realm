package com.pocketrealm.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pocketrealm.llm.ComputeMode
import com.pocketrealm.llm.HexagonProbe
import com.pocketrealm.llm.LlmModelCoordinator
import com.pocketrealm.llm.LlmModelDescriptor
import com.pocketrealm.llm.LlmModelRegistry
import com.pocketrealm.llm.LlmModelDownloadService
import com.pocketrealm.llm.LlmPromptBlock
import com.pocketrealm.llm.LlmPromptPack
import com.pocketrealm.llm.LlmRuntime
import com.pocketrealm.llm.LlmRuntimeService
import com.pocketrealm.server.LlmRuntimePolicy
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI bot LLM submenu: the embedded llama-server runtime in a fault-isolated
 * :llm process, with compute placement (CPU vs Hexagon NPU hybrid) and
 * decode-core selection. Settings persist through the app DataStore; the
 * runtime itself starts here on demand AND automatically right before the
 * realm starts (AndroidRuntimeBackend.ensureLlmRuntime), so the model memory
 * is always claimed before the game container — the measured ordering rule.
 * Compute toggles apply on the next realm start; Start/Stop-now applies them
 * immediately.
 *
 * Two tiers: the simple tier (speech, source, banter, world chatter, model)
 * is always visible; the verbose tier — accelerator, generation limits,
 * connection fields — sits behind the "Advanced engine settings" disclosure
 * (llmAdvanced), mirroring the Auto-login advanced-timing idiom. Both tiers
 * write the same snapshot; the disclosure only changes what is shown.
 */
// LongMethod/Complex: the screen is one column of small cards; the card
// composables below already split it at the natural seams.
@Suppress("LongMethod", "CyclomaticComplexMethod", "FunctionNaming")
@Composable
internal fun LlmScreen(contentPadding: PaddingValues = PaddingValues()) {
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }
    val snap by settings.flow.collectAsState(initial = Settings.Snapshot())
    val scope = rememberCoroutineScope()
    val runtime = rememberLlmServiceState()
    // 1 Hz tick so the service-state freshness gate (below) re-evaluates even
    // when no broadcast arrives: if the :llm process dies without a final
    // ACTION_STATS (LMK SIGKILL), the last broadcast must go stale instead of
    // showing "running" forever.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(SERVICE_STATE_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    // Model staging + NPU probe, refreshed on a tick while the screen is
    // visible: the download service has no completion broadcast, so polling
    // is the honest way to notice a finished multi-GB transfer (or any model
    // file change) without stat()-ing the filesystem during composition.
    val modelState by produceState(LlmModelState(), snap.llmCoresMask, snap.llmModelId) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                val model = LlmModelCoordinator.modelPathFor(context, snap.llmModelId)
                LlmModelState(
                    present = model.isFile,
                    sizeBytes = if (model.isFile) model.length() else LlmModelState().sizeBytes,
                    probe = HexagonProbe.probe(context, model.takeIf { it.isFile }),
                    npuBlocked = LlmRuntime.isNpuBlocked(context),
                )
            }
            kotlinx.coroutines.delay(MODEL_POLL_MS)
        }
    }

    fun update(transform: (Settings.Snapshot) -> Settings.Snapshot) {
        scope.launch { settings.update(transform) }
    }

    fun startNow() {
        val model = LlmModelCoordinator.modelPathFor(context, snap.llmModelId)
        if (!model.isFile) return
        scope.launch(Dispatchers.IO) {
            // The staged non-thinking override arms the warm-up
            // probe's template retry (single-sourced with the supervisor's
            // pre-world launch). Staged off the main thread - flash IO,
            // however small.
            LlmRuntime.start(
                context,
                LlmRuntimePolicy.runtimeConfig(
                    snap,
                    model.absolutePath,
                    LlmRuntimePolicy.stageChatTemplate(context),
                ),
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LlmCard("Runtime") {
            SwitchRow(
                label = "AI bot LLM speech",
                checked = snap.llmEnabled,
                tag = "llm-enabled",
                support = "Playerbots speak through a language model (written into the " +
                    "bot conf at realm start). Off keeps the reviewed LLMEnabled = 0 conf.",
                onChange = { enabled -> update { it.copy(llmEnabled = enabled) } },
            )
            HorizontalDivider()
            ChoiceRow(
                label = "Source",
                selectedId = if (snap.llmExternalMode) LLM_SOURCE_EXTERNAL else LLM_SOURCE_EMBEDDED,
                choices = listOf(
                    LLM_SOURCE_EMBEDDED to "On-device (embedded llama-server)",
                    LLM_SOURCE_EXTERNAL to "External server (any OpenAI-compatible API)",
                ),
                tag = "llm-source",
                support = "External talks to a remote endpoint (OpenAI, OpenRouter, LM " +
                    "Studio, ollama, a PC llama-server); the embedded runtime stays off.",
                onSelect = { id ->
                    update { it.copy(llmExternalMode = id == LLM_SOURCE_EXTERNAL) }
                    // switching to external strands a running embedded server
                    // (its Stop button leaves with the hidden card, and the
                    // supervisor only stops it at the NEXT realm start) -
                    // reclaim the multi-GB process at the flip instead
                    if (id == LLM_SOURCE_EXTERNAL) LlmRuntime.stop(context)
                },
            )
            SwitchRow(
                label = "Authored banter",
                checked = snap.llmBanter,
                tag = "llm-banter",
                support = "The authored, zero-model-cost layer: bots speak first " +
                    "when a remembered player returns (greet-first), nudge about " +
                    "unsettled debts and past goals, cheer level-ups, quip on " +
                    "kills, remark when idle - and the street reacts: nearby bots " +
                    "emote at ambient chatter, and two bots may trade a line when " +
                    "you walk up. Minutes between lines per bot; off keeps bots " +
                    "reply-only.",
                onChange = { enabled -> update { it.copy(llmBanter = enabled) } },
            )
            HorizontalDivider()
            SwitchRow(
                label = "World chatter (beta)",
                checked = snap.llmAmbience,
                tag = "llm-ambience",
                support = "Bots talk among themselves: party companions banter " +
                    "while you quest, townsfolk murmur nearby, and rare news " +
                    "reaches General chat. Every line is grounded in something " +
                    "that really happened - silence is the default, and each " +
                    "story is told a limited number of times before it retires. " +
                    "The switch is the control: on means a loud world, with " +
                    "only a low-battery courtesy slowdown below 15% off the " +
                    "charger. Costs generation budget; applies at the next " +
                    "realm start. The External server fields double as the " +
                    "cloud-composer setup for richer party banter.",
                onChange = { enabled -> update { it.copy(llmAmbience = enabled) } },
            )
            // F3: the lane-neutral "how to talk" hint. Every claim is
            // code-supported on BOTH lanes: name-addressing is the hard
            // trigger for /say (a whisper always works), greeting lines
            // come from tier pools (strangers draw the short lines), and
            // the realm's population ramps from its initial count toward
            // the target over the first minutes.
            Text(
                LLM_SPEECH_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("llm-speech-hint"),
            )
            if (snap.llmExternalMode) {
                Text(
                    "External endpoint — the embedded runtime stays stopped; " +
                        "settings apply at the next realm start.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("llm-external-note"),
                )
                HorizontalDivider()
                // §0.a UI row: the Cloud conversation toggle (AiPlayerbot.
                // LLMCloudChatter). Visible only when an external provider
                // is configured — the key masters cloud-lane widenings and
                // means nothing on the embedded lane.
                SwitchRow(
                    label = "Cloud conversation",
                    checked = snap.llmCloudChatter,
                    tag = "llm-cloud-chatter",
                    support = LLM_CLOUD_CHATTER_SUPPORT,
                    onChange = { enabled -> update { it.copy(llmCloudChatter = enabled) } },
                )
            } else {
                // Stats broadcasts arrive at >=1 Hz while the service lives; two
                // missed ticks mean the process is gone (no final broadcast on a
                // SIGKILL) and the last state must not keep showing "running".
                val runtimeFresh = runtime.at != 0L && nowMs - runtime.at < SERVICE_STATE_FRESH_MS
                val stateLine = when {
                    runtimeFresh && runtime.running && runtime.healthy -> "running (${runtime.mode})"
                    runtimeFresh && runtime.running -> "loading model… (${runtime.mode})"
                    else -> "stopped"
                }
                Text(
                    "Runtime: $stateLine",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("llm-runtime-state"),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { startNow() },
                        enabled = modelState.present,
                        modifier = Modifier.testTag("llm-start-now"),
                    ) { Text("Start now") }
                    OutlinedButton(
                        onClick = { LlmRuntime.stop(context) },
                        enabled = runtimeFresh && runtime.running,
                        modifier = Modifier.testTag("llm-stop-now"),
                    ) { Text("Stop") }
                }
                val blocked = runtime.npuBlocked || modelState.npuBlocked
                if (blocked) {
                    Text(
                        "NPU was blocked after repeated load failures; CPU is in use. " +
                            runtime.npuDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = { LlmRuntime.resetNpuBlock(context) },
                        modifier = Modifier.testTag("llm-reset-npu"),
                    ) { Text("Reset NPU") }
                }
            }
        }

        if (!snap.llmExternalMode) {
            LlmCard("Model") {
                val selected = LlmModelRegistry.byId(snap.llmModelId)
                val model = LlmModelCoordinator.modelPathFor(context, snap.llmModelId)
                // Small-first ordering with trade-off copy — the 501 MB
                // efficiency model is the "try it first" download, the 3.36 GB
                // E2B is the upgrade. All three stay selectable; local-only
                // entries stage by hand (no download distribution yet).
                // selectedId passes the raw persisted id through: byId()
                // falls back to a known descriptor for unknown ids, and the
                // picker must show the actual stored value, not the fallback.
                ChoiceRow(
                    label = "Bot brain model",
                    selectedId = snap.llmModelId,
                    choices = LlmModelRegistry.all
                        .sortedBy { it.size }
                        .map { desc -> desc.id to modelPickerLabel(desc) },
                    tag = "llm-model-picker",
                    support = modelPickerSupport(selected),
                    onSelect = { id ->
                        if (LlmModelRegistry.byId(id).id == id) {
                            update { it.copy(llmModelId = id) }
                        }
                    },
                )
                Text(
                    if (modelState.present) {
                        val sizeGb =
                            "%.2f".format(java.util.Locale.US, modelState.sizeBytes / BYTES_PER_GB)
                        "Staged: ${model.name} ($sizeGb GB)"
                    } else {
                        "Not staged: ${model.name}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("llm-model-state"),
                )
                Text(
                    model.absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Model choice is written into the realm conf at the NEXT realm " +
                        "start (a running server keeps its loaded model); use Start/Stop " +
                        "now to apply it immediately.",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("llm-model-restart-note"),
                )
                if (!modelState.present) {
                    if (selected.localOnly) {
                        // registry models without a URL are staged by hand;
                        // they have no download source
                        Text(
                            "This model is staged by hand (sideload/adb): ${model.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedButton(
                            onClick = { LlmModelDownloadService.start(context, selected.id) },
                            modifier = Modifier.testTag("llm-model-download"),
                        ) { Text("Download model (resumable)") }
                    }
                }
            }
        }

        // The verbose-tier gate (Settings keeps the simple tier): below this
        // line every control is a measured-default tuning knob. The simple
        // tier above — speech, source, banter, world chatter, model — is all
        // most players ever need; the advanced defaults are already applied.
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = snap.llmAdvanced,
                onCheckedChange = { on -> update { it.copy(llmAdvanced = on) } },
                modifier = Modifier.testTag("llm-advanced"),
            )
            Text("  Advanced engine settings", style = MaterialTheme.typography.titleSmall)
        }
        Text(
            "Compute placement, decode cores, NPU offload, generation limits and " +
                "endpoint details. Defaults are measured for this device class; " +
                "touch these only to experiment.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (snap.llmAdvanced && !snap.llmExternalMode) {
            LlmCard("Accelerator") {
                ChoiceRow(
                    label = "Compute mode",
                    selectedId = snap.llmComputeMode.name,
                    choices = listOf(
                        ComputeMode.AUTO.name to "Auto (NPU when detected)",
                        ComputeMode.CPU.name to "CPU only",
                        ComputeMode.NPU.name to "NPU (Hexagon hybrid)",
                    ),
                    tag = "llm-compute-mode",
                    support = "NPU hybrid: prefill on the Hexagon (~430-540 tok/s, core-free), " +
                        "decode on the pinned cores.",
                    onSelect = { id ->
                        val mode = runCatching { ComputeMode.valueOf(id) }
                            .getOrDefault(LlmRuntimePolicy.DEFAULT_COMPUTE_MODE)
                        update { it.copy(llmComputeMode = mode) }
                    },
                )
                val p = modelState.probe
                val ready = p?.state == HexagonProbe.State.READY && !modelState.npuBlocked
                Text(
                    when {
                        p == null -> "Probing Hexagon NPU…"
                        ready -> "Hexagon NPU ready: ${p.detail}"
                        p.state == HexagonProbe.State.READY ->
                            "Hexagon NPU: ${p.detail} — blocked, next start uses CPU"
                        else -> "NPU unavailable — ${p.detail}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        ready -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                HorizontalDivider()
                ChoiceRow(
                    label = "Decode cores",
                    selectedId = "0x%02x".format(snap.llmCoresMask),
                    choices = CORE_PROFILES.map { it.first to it.second },
                    tag = "llm-cores",
                    support = "NPU prefill ignores cores; these carry decode (and all of CPU mode).",
                    onSelect = { id ->
                        val mask = id.removePrefix("0x").toLongOrNull(HEX_RADIX)
                            ?: LlmRuntimePolicy.DEFAULT_CORES_MASK
                        update { it.copy(llmCoresMask = mask) }
                    },
                )
                ChoiceRow(
                    label = "Decode threads",
                    selectedId = snap.llmThreads.toString(),
                    choices = (LlmRuntimePolicy.MIN_THREADS..LlmRuntimePolicy.MAX_THREADS)
                        .map { it.toString() to "$it threads" },
                    tag = "llm-threads",
                    onSelect = { id ->
                        update { it.copy(llmThreads = LlmRuntimePolicy.normalizeThreads(id.toIntOrNull()
                            ?: LlmRuntimePolicy.DEFAULT_THREADS)) }
                    },
                )
                ChoiceRow(
                    label = "NPU offload layers (-ngl)",
                    selectedId = snap.llmOffloadLayers.toString(),
                    choices = listOf(
                        LlmRuntimePolicy.DEFAULT_OFFLOAD_LAYERS.toString() to
                            "All layers (files ≤ 2.9 GB)",
                        "24" to "75% of layers (large files)",
                        "16" to "50% of layers",
                    ),
                    tag = "llm-ngl",
                    support = "The Hexagon session has a ~4 GB address window; files above " +
                        "2.9 GB need partial offload.",
                    onSelect = { id ->
                        update { it.copy(llmOffloadLayers =
                            LlmRuntimePolicy.normalizeOffloadLayers(id.toIntOrNull()
                                ?: LlmRuntimePolicy.DEFAULT_OFFLOAD_LAYERS)) }
                    },
                )
                Text(
                    "Applies on the next realm start (or use Start now).",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        if (snap.llmAdvanced) {
            LlmCard("Generation") {
                ChoiceRow(
                    label = "Reply length",
                    selectedId = snap.llmMaxNewTokens.toString(),
                    choices = REPLY_LENGTH_CHOICES,
                    tag = "llm-max-new-tokens",
                    support = "Caps every bot reply (LLMMaxNewTokens). The model default is " +
                        "tuned per model; shorter replies keep chat snappy, longer ones " +
                        "suit storytelling.",
                    onSelect = { id ->
                        update {
                            it.copy(llmMaxNewTokens = LlmRuntimePolicy.normalizeMaxNewTokensOverride(
                                id.toIntOrNull() ?: 0))
                        }
                    },
                )
                ChoiceRow(
                    label = "Generation timeout",
                    selectedId = snap.llmGenerationTimeout.toString(),
                    choices = GENERATION_TIMEOUT_CHOICES,
                    tag = "llm-gen-timeout",
                    support = "Queue-inclusive wait for one reply before a bot falls back to a " +
                        "stock line. Raise it on slow devices or busy external servers; the " +
                        "tier default is measured per model size.",
                    onSelect = { id ->
                        update {
                            it.copy(llmGenerationTimeout =
                                LlmRuntimePolicy.normalizeGenerationTimeoutOverride(
                                    id.toIntOrNull() ?: 0))
                        }
                    },
                )
                Text(
                    "Overrides apply to embedded and external servers alike; the default " +
                        "choice restores the measured per-model profile.",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            PromptPackCard(
                packJson = snap.llmPromptPackJson,
                replyTokens = when {
                    snap.llmMaxNewTokens > 0 -> snap.llmMaxNewTokens
                    // the meter must reflect the tier that will actually
                    // run: external mode serves 600-token replies out of a
                    // 128k context, not the on-device model's numbers
                    snap.llmExternalMode -> LlmRuntimePolicy.EXTERNAL_PROFILE.maxTokens
                    else -> LlmModelRegistry.byId(snap.llmModelId).profile.maxTokens
                },
                contextLength = if (snap.llmExternalMode)
                    LlmRuntimePolicy.EXTERNAL_TIER.contextLength
                else LlmModelRegistry.byId(snap.llmModelId).tierProfile.contextLength,
                update = { transform -> update(transform) },
            )
        }

        if (snap.llmAdvanced || snap.llmExternalMode) {
            LlmCard("Connection") {
                if (snap.llmExternalMode) {
                    // Local field state: null until the user types, then the local
                    // value wins. Persisting through settings.update() on every
                    // keystroke re-emits the flow, and keying the field on the
                    // persisted value would reset it (cursor jumps, IME restarts,
                    // a trim in the write path deleting trailing spaces under the
                    // cursor) - so the fields track the snapshot only until
                    // touched, and rememberSaveable keeps the override across
                    // configuration changes.
                    var urlEdit by rememberSaveable { mutableStateOf<String?>(null) }
                    var modelEdit by rememberSaveable { mutableStateOf<String?>(null) }
                    var apiKeyEdit by rememberSaveable { mutableStateOf<String?>(null) }
                    // Clear a local override once the persisted snapshot
                    // catches up: otherwise the first keystroke wins forever
                    // and later external changes (or the write-path trim)
                    // never surface in the field.
                    LaunchedEffect(snap.llmExternalUrl) {
                        if (urlEdit != null && urlEdit == snap.llmExternalUrl) urlEdit = null
                    }
                    LaunchedEffect(snap.llmExternalModel) {
                        if (modelEdit != null && modelEdit == snap.llmExternalModel) modelEdit = null
                    }
                    LaunchedEffect(snap.llmExternalApiKey) {
                        if (apiKeyEdit != null && apiKeyEdit == snap.llmExternalApiKey) apiKeyEdit = null
                    }
                    val url = urlEdit ?: snap.llmExternalUrl
                    val model = modelEdit ?: snap.llmExternalModel
                    val apiKey = apiKeyEdit ?: snap.llmExternalApiKey
                    val urlError = if (url.isBlank() ||
                        LlmRuntimePolicy.normalizeExternalEndpoint(url) != null
                    ) null else "Use a full http(s) URL, e.g. https://api.openai.com"
                    val modelError = if (model.isBlank() ||
                        LlmRuntimePolicy.normalizeExternalModel(model) != null
                    ) null else "No quotes, backslashes, or spaces"
                    val endpoint = LlmRuntimePolicy.normalizeExternalEndpoint(url)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { value ->
                            urlEdit = value
                            update { it.copy(llmExternalUrl = value) }
                        },
                        label = { Text("Endpoint URL") },
                        supportingText = {
                            Text(
                                urlError
                                    ?: endpoint?.let {
                                        "Written as AiPlayerbot.LLMApiEndpoint = $it"
                                    }
                                    ?: "Any OpenAI-compatible /v1/chat/completions service",
                            )
                        },
                        isError = urlError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("llm-external-url"),
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { value ->
                            modelEdit = value
                            update { it.copy(llmExternalModel = value) }
                        },
                        label = { Text("Model name") },
                        supportingText = {
                            Text(
                                modelError ?: "Sent in the request body (empty = \"local\")",
                            )
                        },
                        isError = modelError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("llm-external-model"),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { value ->
                            apiKeyEdit = value
                            update { it.copy(llmExternalApiKey = value) }
                        },
                        label = { Text("API key (optional)") },
                        supportingText = {
                            Text(
                                when {
                                    LlmRuntimePolicy.normalizeExternalApiKey(apiKey) == null ->
                                        "No quotes, backslashes, or spaces"
                                    // empty normalizes to "": no Authorization header is sent
                                    apiKey.isBlank() -> "Empty sends no Authorization header"
                                    else ->
                                        "Sent as an Authorization: Bearer header; stays on this device"
                                },
                            )
                        },
                        isError = LlmRuntimePolicy.normalizeExternalApiKey(apiKey) == null &&
                            apiKey.isNotBlank(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("llm-external-api-key"),
                    )
                    Text(
                        "The realm conf points bot chat at this endpoint at realm start. " +
                            "Leave it blank or invalid and no LLM conf is written.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "The realm talks to the runtime over loopback:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "AiPlayerbot.LLMBackend = 0\n" +
                            "AiPlayerbot.LLMApiEndpoint = http://127.0.0.1:" +
                            "${LlmRuntimePolicy.DEFAULT_PORT}/v1/chat/completions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Written into the bot profile conf at realm start when the runtime is enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Snapshot of the staged model + NPU readiness, polled on a 2 s tick. */
private const val MODEL_POLL_MS = 2_000L

/**
 * F3: the lane-neutral "how to talk to bots" hint shown in the Runtime
 * card. Pinned by the UI copy contract test — every clause must stay
 * code-supported on both lanes: name-addressing is the both-lane hard
 * trigger for /say (whispers always work); greeting length follows the
 * relationship tier pools (strangers keep it short); the admission ramp
 * grows the population from its initial count over the first minutes.
 */
internal const val LLM_SPEECH_HINT: String =
    "How to talk: say a bot's name in chat (or whisper them) and they " +
        "answer back. Strangers keep it short — bots you spend time with " +
        "open up. A fresh realm also starts quiet: its population grows " +
        "from the first few bots to the full target over the first minutes."

/**
 * §0.c.4 spend disclosure for the Cloud conversation toggle: what leaves
 * the device, what it roughly costs (prompt-dominated), and when it
 * applies. Pinned by the UI copy contract test.
 */
internal const val LLM_CLOUD_CHATTER_SUPPORT: String =
    "Widens what your external provider is asked for: bots also answer " +
        "party lines you did not address, react to street talk, and chat " +
        "with each other. Bot chat, including your messages, is sent to " +
        "your configured external provider — expect roughly 1–1.5M tokens " +
        "per active evening (almost all of it is prompt context, not " +
        "replies). Applies on the next realm start; daily quotas are " +
        "per-session and reset when the realm restarts."

/** E5: the picker's per-model trade-off line (small-first ordering). */
private fun modelPickerLabel(desc: LlmModelDescriptor): String {
    val gb = "%.2f".format(java.util.Locale.US, desc.size / 1e9)
    return when (desc.id) {
        LlmModelRegistry.TUNED_Q08.id -> "$gb GB - try first (0.8B tuned)"
        LlmModelRegistry.BASE_E2B.id -> "$gb GB - fallback (untuned base)"
        LlmModelRegistry.TUNED_E2B.id -> "$gb GB - the full experience (2B tuned)"
        else -> "$gb GB - ${desc.id}"
    }
}

/** E5: the support line under the picker, describing the SELECTED model. */
private fun modelPickerSupport(desc: LlmModelDescriptor): String = when (desc.id) {
    LlmModelRegistry.TUNED_Q08.id ->
        "Smallest and fastest. Shorter memory, simpler speech, no bot-to-bot " +
            "banter - the quickest way to hear the realm talk."
    LlmModelRegistry.BASE_E2B.id ->
        "Untuned: understands chat but not the trained tool protocol - expect " +
            "plain talk without memory tools. The non-thinking template override " +
            "(needed for this model to speak at all) is applied automatically."
    LlmModelRegistry.TUNED_E2B.id ->
        "The default: richest voice, full memory tools, secrets and nicknames, " +
            "bot-to-bot banter. Needs the headroom this device has."
    else -> desc.id
}

/** Source-choice ids for the runtime card's dropdown. */
private const val LLM_SOURCE_EMBEDDED = "EMBEDDED"
private const val LLM_SOURCE_EXTERNAL = "EXTERNAL"

/** 1 Hz re-evaluation tick for the service-state freshness gate. */
private const val SERVICE_STATE_TICK_MS = 1_000L

/** Two missed 1 Hz stats broadcasts ⇒ the service state is stale, not running. */
private const val SERVICE_STATE_FRESH_MS = 4_000L
private const val BYTES_PER_GB = 1e9
private const val HEX_RADIX = 16

private data class LlmModelState(
    val present: Boolean = false,
    val sizeBytes: Long = 0L,
    val probe: HexagonProbe.Result? = null,
    val npuBlocked: Boolean = false,
)

/** Measured decode-core profiles (findings: ppgrid2, q4bfinal3, 2026-08-26 (c)). */
private val CORE_PROFILES = listOf(
    "0x38" to "Balanced · mids 3-5",
    "0x30" to "Low draw · mids 4-5",
    "0x10" to "Min power · mid 4",
    "0x78" to "Wide · mids 3-6",
    "0x07" to "Littles 0-2 (ultra-low power)",
)

/** Reply-length override choices; "0" is the model's own tuned profile. */
private val REPLY_LENGTH_CHOICES = listOf(
    "0" to "Model default",
    "48" to "48 tokens",
    "96" to "96 tokens",
    "150" to "150 tokens",
    "200" to "200 tokens",
    "300" to "300 tokens",
    "400" to "400 tokens",
    "600" to "600 tokens",
)

/** Generation-timeout override choices; "0" is the model tier's measured budget. */
private val GENERATION_TIMEOUT_CHOICES = listOf(
    "0" to "Tier default",
    "30" to "30 seconds",
    "60" to "60 seconds",
    "90" to "90 seconds",
    "120" to "120 seconds",
    "240" to "240 seconds",
)

private data class LlmServiceState(
    val running: Boolean,
    val healthy: Boolean,
    val mode: String,
    val npuBlocked: Boolean,
    val npuDetail: String,
    /** When this broadcast arrived; 0 before any. Feeds the freshness gate. */
    val at: Long = 0L,
)

@Composable
private fun rememberLlmServiceState(): LlmServiceState {
    val context = LocalContext.current
    val state = remember {
        mutableStateOf(LlmServiceState(false, false, "", false, ""))
    }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent) {
                state.value = LlmServiceState(
                    running = intent.getBooleanExtra(LlmRuntimeService.EXTRA_RUNNING, false),
                    healthy = intent.getBooleanExtra(LlmRuntimeService.EXTRA_HEALTHY, false),
                    mode = intent.getStringExtra(LlmRuntimeService.EXTRA_MODE) ?: "",
                    npuBlocked = intent.getBooleanExtra(LlmRuntimeService.EXTRA_NPU_BLOCKED, false),
                    npuDetail = intent.getStringExtra(LlmRuntimeService.EXTRA_NPU_DETAIL) ?: "",
                    at = System.currentTimeMillis(),
                )
            }
        }
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(LlmRuntimeService.ACTION_STATS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state.value
}

@Composable
@Suppress("FunctionNaming")  // Compose convention: composables read as nouns
private fun LlmCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/**
 * Phase-2 Advanced prompt manager (SillyTavern-style): the ordered prompt
 * blocks with enable toggles, tap-to-edit bodies, reset-to-default per
 * block, reorder (up/down), per-block token estimates + pack total +
 * reply-room meter, and import/export of the pack JSON.
 *
 * Edits persist verbatim into `llmPromptPackJson` (mid-typing rule — no
 * trim/validation in the write path); transport validation
 * ([LlmPromptBlock.validateBody]) runs at edit time as inline errors and
 * again at resolve/stage time (fail-open to the default body). Applies on
 * the next realm start like every llm* setting.
 */
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
private fun PromptPackCard(
    packJson: String,
    replyTokens: Int,
    contextLength: Int,
    update: ((Settings.Snapshot) -> Settings.Snapshot) -> Unit,
) {
    val pack = remember(packJson) { LlmPromptPack.resolve(packJson) }
    // the meter reads the SEASONING tail only: the trained blocks are
    // rendered natively (their editor copies are placeholders), so counting
    // them measured fiction. The native context window is a CHARACTER
    // window (LLMContextLength is compared against prompt chars), so the
    // token room uses the same chars/4 heuristic as the meter itself.
    val seasoningTok = pack.seasoningTokenEstimate()
    val seasoningBytes = pack.seasoningByteEstimate()
    val contextTokens = contextLength / LlmPromptBlock.CHARS_PER_TOKEN
    val replyRoom = contextTokens - seasoningTok - replyTokens - 8
    // trained blocks render natively in a frozen order (the wording lock);
    // only the seasoning tail is player-reorderable
    val firstSeasoning = pack.blocks.indexOfFirst { it.id in LlmPromptPack.SEASONING_IDS }
        .let { if (it < 0) pack.blocks.size else it }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editText by rememberSaveable { mutableStateOf("") }
    var editError by rememberSaveable { mutableStateOf<String?>(null) }
    var importText by rememberSaveable { mutableStateOf<String?>(null) }
    var importNote by rememberSaveable { mutableStateOf<String?>(null) }

    fun commit(next: LlmPromptPack) {
        update { it.copy(llmPromptPackJson = next.serialize()) }
    }

    LlmCard("Prompt pack (advanced)") {
        Text(
            "Every block below feeds the bot's system prompt, in order. " +
                "Toggle seasoning rules on to deepen roleplay; edit wording " +
                "to taste. Trained blocks (identity, tools, voice bible) " +
                "render natively in a frozen order — their rows here are " +
                "read-only, and only the seasoning tail reorders. " +
                "Applies at the next realm start.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Seasoning ~$seasoningTok tok enabled · $seasoningBytes / " +
                "${LlmPromptPack.NATIVE_SEASONING_MAX_BYTES} B native cap · " +
                "reply cap $replyTokens · " +
                if (replyRoom >= 0) "reply room +$replyRoom tok OK (context ≈ $contextTokens tok)"
                else "reply room $replyRoom tok OVER — disable blocks or shorten replies",
            style = MaterialTheme.typography.labelMedium,
            color = when {
                replyRoom < 0 -> MaterialTheme.colorScheme.error
                seasoningBytes > LlmPromptPack.NATIVE_SEASONING_MAX_BYTES -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag("llm-pack-meter"),
        )
        if (seasoningBytes >= LlmPromptPack.NATIVE_SEASONING_MAX_BYTES) {
            Text(
                "Over the native ${LlmPromptPack.NATIVE_SEASONING_MAX_BYTES}-byte cap the " +
                    "renderer silently cuts the TAIL of the joined seasoning " +
                    "(blocks ordered last go first). Reorder or shorten.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        pack.blocks.forEachIndexed { index, block ->
            Column(
                Modifier.fillMaxWidth().testTag("llm-pack-block-${block.id}"),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = block.enabledByDefault,
                        // trained blocks render natively regardless of this
                        // flag (the native renderer skips their ids), so the
                        // switch is presented read-only instead of lying
                        onCheckedChange = if (block.id in LlmPromptPack.TRAINED_IDS) {
                            null
                        } else {
                            { on ->
                                val next = pack.blocks.map {
                                    if (it.id == block.id) it.copy(enabledByDefault = on) else it
                                }
                                commit(pack.copy(blocks = next))
                            }
                        },
                        enabled = block.id !in LlmPromptPack.TRAINED_IDS,
                        modifier = Modifier.testTag("llm-pack-toggle-${block.id}"),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${block.title} (~${block.estimatedTokens()} tok)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            block.help.ifBlank { block.body.take(120) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val from = if (index > firstSeasoning) {
                                pack.blocks.toMutableList().also {
                                    it[index] = it[index - 1].also { prev -> it[index - 1] = it[index] }
                                }
                            } else null
                            if (from != null) commit(pack.copy(blocks = from))
                        },
                        enabled = index > firstSeasoning,
                        modifier = Modifier.testTag("llm-pack-up-${block.id}"),
                    ) { Text("↑") }
                    OutlinedButton(
                        onClick = {
                            val to = if (index < pack.blocks.size - 1) {
                                pack.blocks.toMutableList().also {
                                    it[index] = it[index + 1].also { nxt -> it[index + 1] = it[index] }
                                }
                            } else null
                            if (to != null) commit(pack.copy(blocks = to))
                        },
                        enabled = index < pack.blocks.size - 1 && index >= firstSeasoning,
                        modifier = Modifier.testTag("llm-pack-down-${block.id}"),
                    ) { Text("↓") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingId == block.id) {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { value ->
                                editText = value
                                editError = LlmPromptBlock.validateBody(value)
                            },
                            label = { Text("Prompt text") },
                            supportingText = {
                                Text(editError ?: "${editText.length} chars · ~${editText.length / 4} tokens")
                            },
                            isError = editError != null,
                            modifier = Modifier.weight(1f).testTag("llm-pack-edit-${block.id}"),
                        )
                        OutlinedButton(
                            onClick = {
                                if (editError == null) {
                                    val next = pack.blocks.map {
                                        if (it.id == block.id) it.copy(body = editText) else it
                                    }
                                    commit(pack.copy(blocks = next))
                                    editingId = null
                                }
                            },
                            enabled = editError == null,
                            modifier = Modifier.testTag("llm-pack-save-${block.id}"),
                        ) { Text("Save") }
                        TextButton(
                            onClick = { editingId = null },
                            modifier = Modifier.testTag("llm-pack-cancel-${block.id}"),
                        ) { Text("Cancel") }
                    } else {
                        val trained = block.id in LlmPromptPack.TRAINED_IDS
                        if (trained) {
                            Text(
                                "Rendered natively — read-only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        } else {
                        TextButton(
                            onClick = { editingId = block.id; editText = block.body; editError = null },
                            modifier = Modifier.testTag("llm-pack-show-${block.id}"),
                        ) { Text("Edit text") }
                        TextButton(
                            onClick = {
                                val def = LlmPromptPack().blocks.firstOrNull { it.id == block.id }
                                if (def != null) {
                                    commit(pack.copy(blocks = pack.blocks.map {
                                        if (it.id == block.id) def else it
                                    }))
                                }
                            },
                            modifier = Modifier.testTag("llm-pack-reset-${block.id}"),
                        ) { Text("Reset") }
                        }
                    }
                }
            }
            HorizontalDivider()
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val def = LlmPromptPack()
                    commit(def)
                    editingId = null
                },
                modifier = Modifier.testTag("llm-pack-reset-all"),
            ) { Text("Reset all") }
            if (importText == null) {
                OutlinedButton(
                    onClick = { importText = "" },
                    modifier = Modifier.testTag("llm-pack-import-show"),
                ) { Text("Import") }
                OutlinedButton(
                    onClick = { importText = pack.serialize() },
                    modifier = Modifier.testTag("llm-pack-export-show"),
                ) { Text("Export") }
            } else {
                OutlinedTextField(
                    value = importText ?: "",
                    onValueChange = { importText = it },
                    label = { Text("Pack JSON") },
                    modifier = Modifier.weight(1f).testTag("llm-pack-import-field"),
                )
                OutlinedButton(
                    onClick = {
                        val parsed = importText?.let { LlmPromptPack.parse(it) }
                        if (parsed != null) {
                            val resolved = LlmPromptPack.resolve(importText!!)
                            val reverted = resolved.countBodiesRevertedFrom(parsed)
                            commit(resolved)
                            importNote = if (reverted > 0) {
                                "$reverted edited bod${if (reverted == 1) "y fell" else "ies fell"} " +
                                    "back to the default at resolve (over-length or invalid)."
                            } else {
                                null
                            }
                            importText = null
                        }
                    },
                    enabled = importText?.let { LlmPromptPack.parse(it) } != null,
                    modifier = Modifier.testTag("llm-pack-import-apply"),
                ) { Text("Apply") }
                TextButton(
                    onClick = { importText = null; importNote = null },
                    modifier = Modifier.testTag("llm-pack-import-cancel"),
                ) { Text("Close") }
            }
        }
        if (importNote != null) {
            Text(
                importNote!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("llm-pack-import-note"),
            )
        }
        Text(
            "Edits save immediately and apply at the next realm start. " +
                "Import validates the pack shape; corrupt JSON cannot apply.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
