package com.pocketrealm.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pocketrealm.client.WowVanillaBindingCatalog
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.ingame.ConfigWtfCodec
import com.pocketrealm.ingame.DesktopInGameSettingsEditor
import com.pocketrealm.ingame.SavedVariablesCodec
import com.pocketrealm.ingame.WowSettingBackend
import com.pocketrealm.ingame.WowSettingControl
import com.pocketrealm.ingame.WowSettingDefinition
import com.pocketrealm.ingame.WowSettingSection
import com.pocketrealm.ingame.WowVanillaSettingsCatalog
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DESK-12/22/41/59/85/86/96: the stock 1.12.1 settings panels over the
 * client's WTF tree — graphics / sound / interface / advanced-interface
 * sections from the shared, integrity-hashed [WowVanillaSettingsCatalog],
 * plus the bindings editor over the shared 211-entry
 * [WowVanillaBindingCatalog]. Every edit routes through
 * [DesktopInGameSettingsEditor.apply]: while the client is stopped the edit
 * lands in the WTF files directly; while it runs the change queues and the
 * app model delivers it at the next clean stop.
 *
 * Renderer-capability rows (pixel shaders / glow effects) stay editable on
 * the desktop — the native client negotiates its own renderer, unlike the
 * Android Wine lanes the capture rows were pinned under.
 */
@Composable
fun InGameSettingsScreen(model: DesktopAppModel) {
    val editor = model.inGameSettings
    val ioScope = rememberCoroutineScope()

    var section by remember { mutableStateOf(WowSettingSection.GRAPHICS) }
    var bindingsTab by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<String?>(null) }
    var bindingScope by remember { mutableStateOf<Pair<String, String>?>(null) }
    var configValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var uvarValues by remember { mutableStateOf<Map<String, SavedVariablesCodec.Value>>(emptyMap()) }
    var liveBindings by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var accounts by remember { mutableStateOf<List<String>>(emptyList()) }
    var scopes by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var queued by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }

    fun refresh() {
        ioScope.launch(Dispatchers.IO) {
            accounts = editor.uvarAccounts()
            scopes = editor.bindingScopes()
            configValues = editor.readConfigValues().orEmpty()
            val selectedAccount = account ?: accounts.firstOrNull().also { account = it }
            uvarValues = selectedAccount?.let { editor.readUvarValues(it).orEmpty() } ?: emptyMap()
            val selectedScope = bindingScope ?: scopes.firstOrNull().also { bindingScope = it }
            liveBindings = selectedScope?.let { editor.readBindings(it.first).orEmpty() } ?: emptyMap()
            queued = editor.queuedCount()
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun liveValue(definition: WowSettingDefinition): String? = when (definition.backend) {
        WowSettingBackend.UVAR -> uvarValues[definition.key]?.let { value ->
            when (value) {
                is SavedVariablesCodec.Value.Str -> value.raw
                is SavedVariablesCodec.Value.Num -> value.raw
                is SavedVariablesCodec.Value.Bool -> if (value.raw) "1" else "0"
                SavedVariablesCodec.Value.Nil -> null
            }
        }
        else -> configValues[definition.key]
    }

    fun applyEdit(edit: DesktopInGameSettingsEditor.Edit) {
        ioScope.launch(Dispatchers.IO) {
            status = when (val outcome = editor.apply(edit)) {
                is DesktopInGameSettingsEditor.EditOutcome.Landed -> when (outcome.lane) {
                    DesktopInGameSettingsEditor.EditLane.APPLIED_DIRECT ->
                        "written to the WTF tree"
                    DesktopInGameSettingsEditor.EditLane.QUEUED_FOR_CLEAN_STOP ->
                        "queued — applies at the next clean stop"
                }
                is DesktopInGameSettingsEditor.EditOutcome.Refused -> outcome.reason
            }
            refresh()
        }
    }

    fun requirementsMet(definition: WowSettingDefinition): Boolean = definition.requires.all { gate ->
        val gateDefinition = WowVanillaSettingsCatalog.byId(gate.id) ?: return@all true
        val current = liveValue(gateDefinition) ?: return@all true
        current != gate.notValue
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("In-game settings", style = MaterialTheme.typography.titleLarge)
                Text(
                    "The stock 1.12.1 settings panels, editing the client's WTF files " +
                        "through the shared, integrity-pinned stock catalogs. While the " +
                        "client runs, changes queue and deliver at the next clean stop.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    status.ifBlank {
                        when {
                            model.resolveClientDir() == null ->
                                "no client folder configured — pick one on Home first"
                            model.backend.clientAlive() -> "client RUNNING — changes queue"
                            else -> "client stopped — changes write through"
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (queued > 0) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$queued change(s) queued for the next clean stop",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = {
                            ioScope.launch(Dispatchers.IO) {
                                editor.discardQueue()
                                refresh()
                            }
                        }) { Text("Discard queue") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WowSettingSection.entries.forEach { candidate ->
                        Row(
                            modifier = Modifier.clickable {
                                section = candidate
                                bindingsTab = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = !bindingsTab && section == candidate,
                                onClick = {
                                    section = candidate
                                    bindingsTab = false
                                },
                            )
                            Text(sectionLabel(candidate), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(
                        modifier = Modifier.clickable { bindingsTab = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = bindingsTab, onClick = { bindingsTab = true })
                        Text("Bindings", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (bindingsTab) {
            BindingsEditorCard(
                editor = editor,
                scopes = scopes,
                selectedScope = bindingScope,
                onScopePicked = { picked ->
                    bindingScope = picked
                    ioScope.launch(Dispatchers.IO) {
                        liveBindings = editor.readBindings(picked.first).orEmpty()
                    }
                },
                liveBindings = liveBindings,
                search = search,
                onSearch = { search = it },
                onAssign = { commandId, primary, secondary ->
                    val scope = bindingScope ?: return@BindingsEditorCard
                    applyEdit(
                        DesktopInGameSettingsEditor.Edit.Binding(
                            commandId = commandId,
                            scope = scope.first,
                            primary = primary,
                            secondary = secondary,
                        ),
                    )
                },
            )
        } else {
            SectionCard(
                section = section,
                account = account,
                accounts = accounts,
                onAccountPicked = { picked ->
                    account = picked
                    ioScope.launch(Dispatchers.IO) {
                        uvarValues = editor.readUvarValues(picked).orEmpty()
                    }
                },
                liveValue = ::liveValue,
                requirementsMet = ::requirementsMet,
                onEdit = ::applyEdit,
            )
        }
    }
}

private fun sectionLabel(section: WowSettingSection): String = when (section) {
    WowSettingSection.GRAPHICS -> "Graphics"
    WowSettingSection.SOUND -> "Sound"
    WowSettingSection.INTERFACE -> "Interface"
    WowSettingSection.INTERFACE_ADVANCED -> "Advanced"
}

@Composable
private fun SectionCard(
    section: WowSettingSection,
    account: String?,
    accounts: List<String>,
    onAccountPicked: (String) -> Unit,
    liveValue: (WowSettingDefinition) -> String?,
    requirementsMet: (WowSettingDefinition) -> Boolean,
    onEdit: (DesktopInGameSettingsEditor.Edit) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (accounts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Account:", style = MaterialTheme.typography.bodyMedium)
                    accounts.forEach { candidate ->
                        Row(
                            modifier = Modifier.clickable { onAccountPicked(candidate) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = candidate == account,
                                onClick = { onAccountPicked(candidate) },
                            )
                            Text(candidate, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            WowVanillaSettingsCatalog.groups(section).forEach { group ->
                Text(group, style = MaterialTheme.typography.titleMedium)
                WowVanillaSettingsCatalog.forSection(section)
                    .filter { it.group == group }
                    .forEach { definition ->
                        SettingRow(
                            definition = definition,
                            account = account,
                            liveValue = liveValue,
                            requirementsMet = requirementsMet,
                            onEdit = onEdit,
                        )
                    }
            }
        }
    }
}

@Composable
private fun SettingRow(
    definition: WowSettingDefinition,
    account: String?,
    liveValue: (WowSettingDefinition) -> String?,
    requirementsMet: (WowSettingDefinition) -> Boolean,
    onEdit: (DesktopInGameSettingsEditor.Edit) -> Unit,
) {
    val current = liveValue(definition)
    val gatesFailed = !requirementsMet(definition)
    val disabled = definition.fixedReason != null ||
        definition.backend == WowSettingBackend.FUNCTION ||
        gatesFailed
    val disabledCopy = definition.fixedReason
        ?: if (gatesFailed) "needs one of its requirement settings on" else null

    fun storedForm(checked: Boolean): String =
        ((checked xor definition.inverse)).let { if (it) "1" else "0" }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                definition.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(240.dp),
            )
            when (definition.control) {
                WowSettingControl.TOGGLE -> {
                    Switch(
                        checked = (current == "1") xor definition.inverse,
                        enabled = !disabled,
                        onCheckedChange = { checked ->
                            val stored = storedForm(checked)
                            onEdit(
                                if (definition.backend == WowSettingBackend.UVAR) {
                                    DesktopInGameSettingsEditor.Edit.UvarSetting(
                                        definition, account ?: "", stored,
                                    )
                                } else {
                                    DesktopInGameSettingsEditor.Edit.CvarSetting(definition, stored)
                                },
                            )
                        },
                    )
                    HintText(definition, current, disabled, disabledCopy)
                }
                WowSettingControl.SLIDER -> {
                    val min = definition.min ?: 0f
                    val max = definition.max ?: 1f
                    val step = definition.step ?: 1f
                    var value by remember(definition.id, current) {
                        mutableStateOf(current?.toFloatOrNull() ?: min)
                    }
                    Slider(
                        value = value,
                        onValueChange = { value = it },
                        valueRange = min..max,
                        steps = (((max - min) / step).toInt() - 1).coerceAtLeast(0),
                        enabled = !disabled,
                        onValueChangeFinished = {
                            onEdit(
                                DesktopInGameSettingsEditor.Edit.CvarSetting(
                                    definition,
                                    ConfigWtfCodec.formatValue(value),
                                ),
                            )
                        },
                    )
                    Text(
                        ConfigWtfCodec.formatValue(value),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (disabled) Text(
                        disabledCopy ?: "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                WowSettingControl.CHOICE -> {
                    definition.choices?.forEach { choice ->
                        Row(
                            modifier = Modifier.clickable(enabled = !disabled) {
                                onEdit(
                                    if (definition.backend == WowSettingBackend.UVAR) {
                                        DesktopInGameSettingsEditor.Edit.UvarSetting(
                                            definition, account ?: "", choice.stored,
                                        )
                                    } else {
                                        DesktopInGameSettingsEditor.Edit.CvarSetting(
                                            definition, choice.stored,
                                        )
                                    },
                                )
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = current == choice.stored,
                                onClick = null,
                                enabled = !disabled,
                            )
                            Text(
                                choice.label +
                                    if (choice.stored == definition.defaultValue) " (stock default)" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (disabled) Text(
                        disabledCopy ?: "",
                        style = MaterialTheme.typography.bodySmall,
                    ) else if (current == null && definition.choices == null) {
                        Text("default (unset in WTF)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun HintText(
    definition: WowSettingDefinition,
    current: String?,
    disabled: Boolean,
    disabledCopy: String?,
) {
    if (disabled) {
        Text(disabledCopy ?: "", style = MaterialTheme.typography.bodySmall)
    } else if (current == null) {
        Text("default (unset in WTF)", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BindingsEditorCard(
    editor: DesktopInGameSettingsEditor,
    scopes: List<Pair<String, String>>,
    selectedScope: Pair<String, String>?,
    onScopePicked: (Pair<String, String>) -> Unit,
    liveBindings: Map<String, String>,
    search: String,
    onSearch: (String) -> Unit,
    onAssign: (commandId: String, primary: String?, secondary: String?) -> Unit,
) {
    var capturing by remember { mutableStateOf<String?>(null) }
    var captureNote by remember { mutableStateOf<String?>(null) }
    val captureFocus = remember { FocusRequester() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Key bindings", style = MaterialTheme.typography.titleMedium)
            Text(
                "The stock 1.12.1 binding catalog (" + WowVanillaBindingCatalog.userFacing.size +
                    " action IDs, integrity-pinned). Assignments land in the selected " +
                    "scope's bindings-cache.wtf when the client is stopped, or queue for " +
                    "the next clean stop while it runs.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (scopes.isEmpty()) {
                Text(
                    "no binding files yet — log in once in game to create them",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            scopes.forEach { candidate ->
                Row(
                    modifier = Modifier.clickable { onScopePicked(candidate) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = candidate == selectedScope,
                        onClick = { onScopePicked(candidate) },
                    )
                    Text(
                        candidate.second + ": " + candidate.first,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            OutlinedTextField(
                value = search,
                onValueChange = onSearch,
                label = { Text("Search the binding catalog") },
                modifier = Modifier.fillMaxWidth(),
            )
            WowVanillaBindingCatalog.search(search, includeAdvanced = true)
                .forEach { binding ->
                    val currentKeys = liveBindings.filterValues { it == binding.id }.keys.sorted()
                    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                binding.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(240.dp),
                            )
                            Text(
                                currentKeys.joinToString(", ").ifEmpty { "(unbound)" },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(200.dp),
                            )
                            OutlinedButton(onClick = { capturing = binding.id }) {
                                Text("Bind key…")
                            }
                            OutlinedButton(onClick = { onAssign(binding.id, null, null) }) {
                                Text("Clear")
                            }
                        }
                    }
                }
        }
    }

    val command = capturing
    if (command != null) {
        AlertDialog(
            onDismissRequest = { capturing = null },
            title = { Text("Press a key for " + command) },
            text = {
                Box(
                    modifier = Modifier
                        .focusRequester(captureFocus)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val native = event.nativeKeyEvent as? AwtKeyEvent
                                ?: return@onPreviewKeyEvent false
                            val vk = native.keyCode
                            if (vk in WowKeyTokens.MODIFIER_VKS) return@onPreviewKeyEvent true
                            val token = WowKeyTokens.chordToken(
                                ctrl = native.isControlDown,
                                shift = native.isShiftDown,
                                alt = native.isAltDown,
                                vk = vk,
                            )
                            when {
                                token == null -> captureNote = "that key has no 1.12.1 binding token"
                                token in WowVanillaBindingCatalog.reservedKeys ->
                                    captureNote = "the controller bridge owns that key"
                                else -> {
                                    val secondary = liveBindings.filterValues { it == command }.keys
                                        .filterNot { it == token }
                                        .sorted()
                                        .firstOrNull()
                                    onAssign(command, token, secondary)
                                }
                            }
                            capturing = null
                            true
                        },
                ) {
                    LaunchedEffect(Unit) { captureFocus.requestFocus() }
                    Text(
                        "Press any key to bind \"" + command + "\". Escape closes without " +
                            "binding; bridge-owned keys are refused." +
                            (captureNote?.let { " Last refusal: " + it } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {},
        )
    }}
