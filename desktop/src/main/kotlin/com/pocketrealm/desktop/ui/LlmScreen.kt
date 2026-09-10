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
import com.pocketrealm.server.LlmRuntimePolicy

/**
 * The LLM screen, external-endpoint edition: the Android LlmScreen minus
 * every embedded-model surface (the on-device llama-server, the model
 * manager, NPU) — cloud is the only lane on Windows. Same settings fields,
 * same conf semantics (applies at the next realm start), same spend
 * disclosure copy for the cloud conversation toggle.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber") // cards mirror the Android screen's sections
fun LlmScreen(model: DesktopAppModel) {
    val settings by model.settings.collectAsState()
    var urlEdit by remember { mutableStateOf<String?>(null) }
    var modelEdit by remember { mutableStateOf<String?>(null) }
    var apiKeyEdit by remember { mutableStateOf<String?>(null) }

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
                Text(
                    "Source: external server - the realm talks to any OpenAI-compatible " +
                        "/v1/chat/completions API (OpenAI, OpenRouter, LM Studio, ollama, a " +
                        "PC llama-server). Settings apply at the next realm start.",
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connection", style = MaterialTheme.typography.titleMedium)
                val normalizedEndpoint = LlmRuntimePolicy.normalizeExternalEndpoint(url)
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
                    isError = url.isNotBlank() && normalizedEndpoint == null,
                    supportingText = {
                        when {
                            url.isNotBlank() && normalizedEndpoint == null ->
                                Text("Use a full http(s) URL, e.g. https://api.openai.com")
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
                    listOf(0, 30, 60, 120, 240).forEach { seconds ->
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
