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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 */
// LongMethod/Complex: the screen is one column of four small cards; the
// card composables below already split it at the natural seams.
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
                    "Battery-aware: on-device lines pause when power or thermals " +
                    "run low. Costs generation budget; the switch takes effect " +
                    "within about a minute, in both directions. The External " +
                    "server fields double as the cloud-composer setup for " +
                    "richer party banter.",
                onChange = { enabled -> update { it.copy(llmAmbience = enabled) } },
            )
            if (snap.llmExternalMode) {
                Text(
                    "External endpoint — the embedded runtime stays stopped; " +
                        "settings apply at the next realm start.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("llm-external-note"),
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

        if (!snap.llmExternalMode) {
            LlmCard("Model") {
                val selected = LlmModelRegistry.byId(snap.llmModelId)
                val model = LlmModelCoordinator.modelPathFor(context, snap.llmModelId)
                // Small-first ordering with trade-off copy — the 501 MB
                // efficiency model is the "try it first" download, the 3.36 GB
                // E2B is the upgrade. All three stay selectable; local-only
                // entries stage by hand (no download distribution yet).
                ChoiceRow(
                    label = "Bot brain model",
                    selectedId = selected.id,
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
                            modelError
                                ?: "Sent in the request body (empty = \"local\")",
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

/** Snapshot of the staged model + NPU readiness, polled on a 2 s tick. */
private const val MODEL_POLL_MS = 2_000L

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
