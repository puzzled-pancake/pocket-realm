package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.llm.LlmPromptBlock
import com.pocketrealm.llm.LlmPromptPack
import com.pocketrealm.server.LlmRuntimePolicy
import kotlinx.coroutines.launch

/**
 * The LLM screen, the Android LlmScreen's desktop twin with the Source
 * toggle back (DESK-48/80/103): on-device = the LOCAL llama-server
 * sidecar (beta) over a staged registry GGUF; external = any
 * OpenAI-compatible endpoint (the desktop default). Same settings fields,
 * same conf semantics (applies at the next realm start), same spend
 * disclosure for the cloud conversation toggle — and the honest lane
 * copy: NPU/compute placement and battery-aware pacing are ANDROID-ONLY
 * (the desktop sidecar is a plain CPU/GPU child process).
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber") // cards mirror the Android screen's sections
fun LlmScreen(model: DesktopAppModel) {
    val settings by model.settings.collectAsState()
    var urlEdit by remember { mutableStateOf<String?>(null) }
    var modelEdit by remember { mutableStateOf<String?>(null) }
    var apiKeyEdit by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var downloadNote by remember { mutableStateOf("") }

    val url = urlEdit ?: settings.llmExternalUrl
    val modelName = modelEdit ?: settings.llmExternalModel
    val apiKey = apiKeyEdit ?: settings.llmExternalApiKey

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Runtime", style = MaterialTheme.typography.titleMedium)
                SwitchRow(
                    label = "AI bot LLM speech",
                    support = "Playerbots speak through a language model (written into the bot conf at " +
                            "realm start). Off keeps the reviewed LLMEnabled = 0 conf.",
                    checked = settings.llmEnabled,
                    onChecked = { value -> model.updateSettings { it.copy(llmEnabled = value) } },
                )
                // The Source toggle (the Android LlmScreen's choice row,
                // DESK-103's "on-device/external source toggle"): chips
                // instead of the phone dropdown.
                Text("Source", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !settings.llmExternalMode,
                        onClick = { model.updateSettings { it.copy(llmExternalMode = false) } },
                        label = { Text("Local sidecar (beta)") },
                    )
                    FilterChip(
                        selected = settings.llmExternalMode,
                        onClick = { model.updateSettings { it.copy(llmExternalMode = true) } },
                        label = { Text("External server") },
                    )
                }
                Text(
                    if (settings.llmExternalMode) {
                        "External server: the realm talks to any OpenAI-compatible " +
                            "/v1/chat/completions API (OpenAI, OpenRouter, LM Studio, ollama, a " +
                            "PC llama-server). Settings apply at the next realm start."
                    } else {
                        "Local sidecar (beta): a bundled llama-server.exe serves a staged model " +
                            "on loopback; the realm's conf points at it and applies at the next " +
                            "realm start. CPU/GPU only - NPU compute placement and battery-aware " +
                            "pacing are Android-only. Untuned staged models cost quality; the " +
                            "tuned checkpoints are staged by hand (no download source)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                SwitchRow(
                    label = "Authored banter",
                    support = "The authored, zero-model-cost layer: bots speak first when a " +
                        "remembered player returns (greet-first), nudge about unsettled debts " +
                        "and past goals, cheer level-ups, quip on kills, remark when idle - and " +
                        "the street reacts: nearby bots emote at ambient chatter, and two bots " +
                        "may trade a line when you walk up. Minutes between lines per bot; off " +
                        "keeps bots reply-only.",
                    checked = settings.llmBanter,
                    onChecked = { value -> model.updateSettings { it.copy(llmBanter = value) } },
                )
                SwitchRow(
                    label = "World chatter (beta)",
                    support = "Ambient bot life beyond direct replies. Costs generation budget; " +
                        "applies at the next realm start. The External server fields double as " +
                        "the cloud-composer setup for richer party banter.",
                    checked = settings.llmAmbience,
                    onChecked = { value -> model.updateSettings { it.copy(llmAmbience = value) } },
                )
                SwitchRow(
                    label = "Cloud conversation",
                    support = "Widens what your external provider is asked for: bots also answer " +
                        "party lines you did not address, react to street talk, and chat with " +
                        "each other. Bot chat, including your messages, is sent to your " +
                        "configured external provider - expect roughly 1-1.5M tokens per active " +
                        "evening (almost all of it is prompt context, not replies). Applies on " +
                        "the next realm start; daily quotas are per-session and reset when the " +
                        "realm restarts.",
                    checked = settings.llmCloudChatter,
                    onChecked = { value -> model.updateSettings { it.copy(llmCloudChatter = value) } },
                )
                Text(
                    "How to talk: say a bot's name in chat (or whisper them) and they answer " +
                        "back. Strangers keep it short - bots you spend time with open up. A " +
                        "fresh realm also starts quiet: its population grows from the first few " +
                        "bots to the full target over the first minutes.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (!settings.llmExternalMode) {
            // The model card (the Android model manager's desktop twin,
            // DESK-48/103): registry entries small-first with their staged
            // state, the selection, and the download lane for the pinned
            // upstream model. localOnly entries are staged by hand.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("On-device model", style = MaterialTheme.typography.titleMedium)
                    com.pocketrealm.llm.LlmModelRegistry.all
                        .sortedBy { it.size }
                        .forEach { descriptor ->
                            // The PRESENCE check (file + pinned size) — never
                            // the full-digest stagedModel() inside composition:
                            // that proof is multi-GB sha256 work and runs at
                            // the sidecar-launch gate, not on the UI thread.
                            val staged = model.llmModelManager.stagedFilePresent(descriptor.id)
                            val selected =
                                com.pocketrealm.llm.LlmModelRegistry.byId(settings.llmModelId).id ==
                                    descriptor.id
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        model.updateSettings { it.copy(llmModelId = descriptor.id) }
                                    },
                                    label = { Text(descriptor.id) },
                                )
                                Text(
                                    when {
                                        staged != null ->
                                            "staged (%.1f GB; digest proved at launch)"
                                                .format(staged.length() / 1e9)
                                        descriptor.localOnly ->
                                            "not staged - tuned checkpoint, staged by hand (no download source)"
                                        else -> "not staged - ${"%.1f".format(descriptor.size / 1e9)} GB download"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!descriptor.localOnly && staged == null && selected) {
                                OutlinedButton(
                                    onClick = {
                                        downloadNote = "downloading ${descriptor.fileName}…"
                                        scope.launch {
                                            val note = kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.IO,
                                            ) {
                                                runCatching {
                                                    model.llmModelManager.downloadModel(descriptor.id)
                                                    "staged ${descriptor.fileName}"
                                                }.getOrElse {
                                                    "download failed: ${it.message ?: it.javaClass.simpleName}"
                                                }
                                            }
                                            downloadNote = note
                                        }
                                    },
                                ) {
                                    Text("Download and verify (sha256-pinned)")
                                }
                            }
                        }
                    if (downloadNote.isNotBlank()) {
                        Text(downloadNote, style = MaterialTheme.typography.bodySmall)
                    }
                    val sidecar = model.llmModelManager.state(settings.llmModelId)
                    Text(
                        "Sidecar: ${sidecar.detail}" +
                            (sidecar.endpoint?.let { " - $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connection", style = MaterialTheme.typography.titleMedium)
                val normalizedEndpoint = LlmRuntimePolicy.normalizeExternalEndpoint(url)
                // http + key would send the bearer key in cleartext: flag
                // it here, not only as the fail-closed no-block at realm
                // start (isCleartextKeyedEndpoint).
                val cleartextKey =
                    LlmRuntimePolicy.isCleartextKeyedEndpoint(normalizedEndpoint, apiKey)
                OutlinedTextField(
                    value = url,
                    onValueChange = { value ->
                        urlEdit = value.take(512)
                        if (LlmRuntimePolicy.normalizeExternalEndpoint(value) != null ||
                            value.isBlank()
                        ) {
                            model.updateSettings { it.copy(llmExternalUrl = value.trim()) }
                        }
                    },
                    label = { Text("Endpoint URL") },
                    isError = url.isNotBlank() &&
                        (normalizedEndpoint == null || cleartextKey),
                    supportingText = {
                        when {
                            url.isNotBlank() && normalizedEndpoint == null ->
                                Text("Use a full http(s) URL, e.g. https://api.openai.com")
                            cleartextKey ->
                                Text(
                                    "http:// would send the API key in cleartext; " +
                                        "use https:// or clear the key",
                                )
                            normalizedEndpoint != null ->
                                Text("Written as AiPlayerbot.LLMApiEndpoint = $normalizedEndpoint")
                            else -> Text("Any OpenAI-compatible /v1/chat/completions service")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val normalizedModel = LlmRuntimePolicy.normalizeExternalModel(modelName)
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { value ->
                        modelEdit = value.take(128)
                        if (LlmRuntimePolicy.normalizeExternalModel(value) != null || value.isBlank()) {
                            model.updateSettings { it.copy(llmExternalModel = value.trim()) }
                        }
                    },
                    label = { Text("Model name") },
                    isError = modelName.isNotBlank() && normalizedModel == null,
                    supportingText = {
                        Text(
                            if (modelName.isNotBlank() && normalizedModel == null) {
                                "No quotes, backslashes, or spaces"
                            } else {
                                "Sent in the request body (empty = \"local\")"
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val normalizedKey = LlmRuntimePolicy.normalizeExternalApiKey(apiKey)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { value ->
                        apiKeyEdit = value.take(512)
                        if (LlmRuntimePolicy.normalizeExternalApiKey(value) != null || value.isBlank()) {
                            model.updateSettings { it.copy(llmExternalApiKey = value.trim()) }
                        }
                    },
                    label = { Text("API key (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = apiKey.isNotBlank() && normalizedKey == null,
                    supportingText = {
                        Text(
                            if (apiKey.isNotBlank() && normalizedKey == null) {
                                "No quotes, backslashes, or spaces"
                            } else if (apiKey.isBlank()) {
                                "Empty sends no Authorization header"
                            } else {
                                "Sent as an Authorization: Bearer header; stays on this device"
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The realm conf points bot chat at this endpoint at realm start. Leave it " +
                        "blank or invalid and no LLM conf is written.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Generation", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Overrides apply to the external server; the default choice restores the " +
                        "measured external profile.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Reply length", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 48, 96, 200, 400, 600).forEach { tokens ->
                        FilterChip(
                            selected = LlmRuntimePolicy.normalizeMaxNewTokensOverride(
                                settings.llmMaxNewTokens,
                            ) == tokens,
                            onClick = {
                                model.updateSettings { it.copy(llmMaxNewTokens = tokens) }
                            },
                            label = { Text(if (tokens == 0) "Model default" else tokens.toString()) },
                        )
                    }
                }
                Text("Generation timeout", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 30, 60, 120, 240, 300).forEach { seconds ->
                        FilterChip(
                            selected = LlmRuntimePolicy.normalizeGenerationTimeoutOverride(
                                settings.llmGenerationTimeout,
                            ) == seconds,
                            onClick = {
                                model.updateSettings { it.copy(llmGenerationTimeout = seconds) }
                            },
                            label = { Text(if (seconds == 0) "Tier default" else seconds.toString()) },
                        )
                    }
                }
            }
        }

        // The DESK-9 advanced-tier gate + engine knobs (decode threads /
        // offload layers) for the on-device sidecar; compute mode and decode
        // cores stay Android-only (DESK-28).
        if (!settings.llmExternalMode) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(
                            checked = settings.llmAdvanced,
                            onCheckedChange = { on ->
                                model.updateSettings { it.copy(llmAdvanced = on) }
                            },
                        )
                        Text("Advanced engine settings", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "Decode threads and GPU offload for the local llama-server. Defaults " +
                            "are the measured CPU posture; touch these only to experiment.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (settings.llmAdvanced) {
                        Text("Decode threads", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (threads in LlmRuntimePolicy.MIN_THREADS..LlmRuntimePolicy.MAX_THREADS) {
                                FilterChip(
                                    selected =
                                    LlmRuntimePolicy.normalizeThreads(settings.llmThreads) == threads,
                                    onClick = {
                                        model.updateSettings {
                                            it.copy(llmThreads = LlmRuntimePolicy.normalizeThreads(threads))
                                        }
                                    },
                                    label = { Text(threads.toString()) },
                                )
                            }
                        }
                        Text("GPU offload layers (-ngl)", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 8, 16, 32, LlmRuntimePolicy.MAX_OFFLOAD_LAYERS).forEach { layers ->
                                FilterChip(
                                    selected = LlmRuntimePolicy.normalizeOffloadLayers(
                                        settings.llmOffloadLayers,
                                    ) == layers,
                                    onClick = {
                                        model.updateSettings {
                                            it.copy(
                                                llmOffloadLayers =
                                                LlmRuntimePolicy.normalizeOffloadLayers(layers),
                                            )
                                        }
                                    },
                                    label = {
                                        Text(
                                            when (layers) {
                                                0 -> "CPU only"
                                                LlmRuntimePolicy.MAX_OFFLOAD_LAYERS -> "All layers"
                                                else -> layers.toString()
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        Text(
                            "CPU only keeps every layer on the CPU (the bundled lane is a " +
                                "CPU-only llama-server); a higher count has effect only with a " +
                                "GPU-capable sidecar binary. Applies at the next sidecar start.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // DESK-26: the player-editable prompt blocks (the Android
        // PromptPackCard's desktop port over the shared LlmPromptPack).
        PromptPackCard(
            packJson = settings.llmPromptPackJson,
            replyTokens = LlmRuntimePolicy.normalizeMaxNewTokensOverride(settings.llmMaxNewTokens)
                .takeIf { it > 0 } ?: LlmRuntimePolicy.EXTERNAL_PROFILE.maxTokens,
            contextLength = LlmRuntimePolicy.EXTERNAL_TIER.contextLength,
            update = { transform -> model.updateSettings(transform) },
        )
    }
}

@Composable
private fun SwitchRow(label: String, support: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = onChecked)
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(support, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The player-editable prompt pack (DESK-26; the Android LlmScreen's
 * PromptPackCard port over the shared LlmPromptPack): block toggles,
 * wording edits with inline transport validation, seasoning reorder,
 * reset, and import/export. Edits persist verbatim into
 * `llmPromptPackJson` (the mid-typing rule); transport validation runs at
 * edit time as inline errors and again at resolve/stage time (fail-open to
 * the default body). Applies at the next realm start like every llm*
 * setting.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber") // the Android card's shape, ported
private fun PromptPackCard(
    packJson: String,
    replyTokens: Int,
    contextLength: Int,
    update: ((com.pocketrealm.storage.Settings.Snapshot) -> com.pocketrealm.storage.Settings.Snapshot) -> Unit,
) {
    val pack = remember(packJson) { LlmPromptPack.resolve(packJson) }
    // The meter reads the SEASONING tail only (the trained blocks render
    // natively; their editor copies are placeholders) — the Android card's
    // meter arithmetic, verbatim.
    val seasoningTok = pack.seasoningTokenEstimate()
    val seasoningBytes = pack.seasoningByteEstimate()
    val contextTokens = contextLength / LlmPromptBlock.CHARS_PER_TOKEN
    val replyRoom = contextTokens - seasoningTok - replyTokens - 8
    val firstSeasoning = pack.blocks.indexOfFirst { it.id in LlmPromptPack.SEASONING_IDS }
        .let { if (it < 0) pack.blocks.size else it }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }
    var importNote by remember { mutableStateOf<String?>(null) }

    fun commit(next: LlmPromptPack) {
        update { it.copy(llmPromptPackJson = next.serialize()) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Prompt pack (advanced)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every block below feeds the bot's system prompt, in order. Toggle seasoning " +
                    "rules on to deepen roleplay; edit wording to taste. Trained blocks " +
                    "(identity, tools, voice bible) render natively in a frozen order — their " +
                    "rows here are read-only, and only the seasoning tail reorders. Applies " +
                    "at the next realm start.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Seasoning ~$seasoningTok tok enabled · $seasoningBytes / " +
                    "${LlmPromptPack.NATIVE_SEASONING_MAX_BYTES} B native cap · reply cap " +
                    "$replyTokens · " +
                    if (replyRoom >= 0) {
                        "reply room +$replyRoom tok OK (context ≈ $contextTokens tok)"
                    } else {
                        "reply room $replyRoom tok OVER — disable blocks or shorten replies"
                    },
                style = MaterialTheme.typography.labelMedium,
            )
            if (seasoningBytes >= LlmPromptPack.NATIVE_SEASONING_MAX_BYTES) {
                Text(
                    "Over the native ${LlmPromptPack.NATIVE_SEASONING_MAX_BYTES}-byte cap the " +
                        "renderer silently cuts the TAIL of the joined seasoning (blocks " +
                        "ordered last go first). Reorder or shorten.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            pack.blocks.forEachIndexed { index, block ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Switch(
                            checked = block.enabledByDefault,
                            // Trained blocks render natively regardless of the
                            // flag; the switch is read-only instead of lying.
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
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${block.title} (~${block.estimatedTokens()} tok)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                block.help.ifBlank { block.body.take(120) },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (index > firstSeasoning) {
                                    val reordered = pack.blocks.toMutableList().also {
                                        it[index] = it[index - 1].also { prev -> it[index - 1] = it[index] }
                                    }
                                    commit(pack.copy(blocks = reordered))
                                }
                            },
                            enabled = index > firstSeasoning,
                        ) { Text("↑") }
                        OutlinedButton(
                            onClick = {
                                if (index < pack.blocks.size - 1 && index >= firstSeasoning) {
                                    val reordered = pack.blocks.toMutableList().also {
                                        it[index] = it[index + 1].also { nxt -> it[index + 1] = it[index] }
                                    }
                                    commit(pack.copy(blocks = reordered))
                                }
                            },
                            enabled = index < pack.blocks.size - 1 && index >= firstSeasoning,
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
                                    Text(
                                        editError
                                            ?: "${editText.length} chars · ~${editText.length / 4} tokens",
                                    )
                                },
                                isError = editError != null,
                                modifier = Modifier.weight(1f),
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
                            ) { Text("Save") }
                            OutlinedButton(onClick = { editingId = null }) { Text("Cancel") }
                        } else {
                            if (block.id in LlmPromptPack.TRAINED_IDS) {
                                Text(
                                    "Rendered natively — read-only",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                OutlinedButton(onClick = {
                                    editingId = block.id; editText = block.body; editError = null
                                }) { Text("Edit text") }
                                OutlinedButton(onClick = {
                                    val def = LlmPromptPack().blocks.firstOrNull { it.id == block.id }
                                    if (def != null) {
                                        commit(
                                            pack.copy(
                                                blocks = pack.blocks.map {
                                                    if (it.id == block.id) def else it
                                                },
                                            ),
                                        )
                                    }
                                }) { Text("Reset") }
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    commit(LlmPromptPack()); editingId = null
                }) { Text("Reset all") }
                if (importText == null) {
                    OutlinedButton(onClick = { importText = "" }) { Text("Import") }
                    OutlinedButton(onClick = { importText = pack.serialize() }) { Text("Export") }
                } else {
                    OutlinedTextField(
                        value = importText ?: "",
                        onValueChange = { importText = it },
                        label = { Text("Pack JSON") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            val raw = importText ?: ""
                            val parsed = LlmPromptPack.parse(raw)
                            if (parsed != null) {
                                val resolved = LlmPromptPack.resolve(raw)
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
                    ) { Text("Apply") }
                    OutlinedButton(onClick = { importText = null; importNote = null }) {
                        Text("Close")
                    }
                }
            }
            importNote?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Edits save immediately and apply at the next realm start. Import validates " +
                    "the pack shape; corrupt JSON cannot apply.",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
