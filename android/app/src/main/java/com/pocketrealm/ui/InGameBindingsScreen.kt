package com.pocketrealm.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketrealm.client.WowBindingCategory
import com.pocketrealm.client.WowVanillaBindingCatalog
import com.pocketrealm.ingame.InGameSettingsEditor
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.launch

/** Editing intent for one binding row (which slot, which command). */
private data class BindingEdit(val commandId: String, val slot: Int)

@Composable
internal fun InGameBindingsScreen() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val snap by settings.flow.collectAsState(initial = Settings.Snapshot())
    val runtime = rememberInGameRuntime()
    val scope = rememberCoroutineScope()
    val editor = remember { InGameSettingsEditor(context.applicationContext) }

    var activity by remember {
        mutableStateOf(InGameSettingsEditor.ClientActivity.STOPPED)
    }
    var scopes by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedScope by remember { mutableStateOf<String?>(null) }
    var bindings by remember { mutableStateOf<Map<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var selectedCategory by remember {
        mutableStateOf<WowBindingCategory>(WowBindingCategory.MOVEMENT)
    }
    var edit by remember { mutableStateOf<BindingEdit?>(null) }
    // (command, key, slot) awaiting the user's conflict confirmation.
    var conflict by remember { mutableStateOf<Triple<String, String, Int>?>(null) }

    fun refresh() {
        scope.launch {
            activity = runCatching { editor.clientActivity(runtime) }.getOrDefault(
                InGameSettingsEditor.ClientActivity.UNKNOWN,
            )
            scopes = editor.bindingScopes()
            if (selectedScope == null) selectedScope = scopes.firstOrNull()?.first
            bindings = selectedScope?.let { editor.readBindings(it) }
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            kotlinx.coroutines.delay(1_000)
            activity = runCatching { editor.clientActivity(runtime) }.getOrDefault(
                InGameSettingsEditor.ClientActivity.UNKNOWN,
            )
        }
    }
    LaunchedEffect(selectedScope) { bindings = selectedScope?.let { editor.readBindings(it) } }

    fun applyBinding(commandId: String, primary: String?, secondary: String?) {
        scope.launch {
            runCatching {
                val target = checkNotNull(selectedScope) { "no binding scope selected" }
                if (activity == InGameSettingsEditor.ClientActivity.STOPPED) {
                    editor.directEdit(
                        runtime,
                        listOf(
                            InGameSettingsEditor.DirectEdit(
                                family = InGameSettingsEditor.DirectEdit.Family.BINDING,
                                queueRemovalId = commandId,
                                journalKey = commandId,
                                bindingScope = target,
                                command = commandId,
                                primary = primary,
                                secondary = secondary,
                            ),
                        ),
                    )
                } else {
                    editor.stageBindingOverride(commandId, primary, secondary, target)
                }
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
            refresh()
        }
    }

    /** Assign [key] to [commandId]'s [slot]; a non-reserved conflict asks first. */
    fun requestAssign(commandId: String, slot: Int, key: String?) {
        if (key == null) {
            // Explicit unbind of one slot: rebuild from the surviving slot.
            val queued = snap.gameSettings.bindings[commandId]
            val currentKeys = bindings?.filterValues { it == commandId }?.keys?.toList()
            val base = listOfNotNull(
                queued?.primary ?: currentKeys?.getOrNull(0),
                queued?.secondary ?: currentKeys?.getOrNull(1),
            )
            val surviving = base.filterIndexed { index, _ -> index != slot }
            applyBinding(commandId, surviving.getOrNull(0), surviving.getOrNull(1))
            return
        }
        check(key !in WowVanillaBindingCatalog.reservedKeys) {
            "$key is used by the controller overlay"
        }
        val displaced = bindings?.entries
            ?.firstOrNull { it.key == key && it.value != commandId }
        if (displaced != null) {
            conflict = Triple(commandId, key, slot)
            return
        }
        val queued = snap.gameSettings.bindings[commandId]
        val currentKeys = bindings?.filterValues { it == commandId }?.keys?.toList()
        val primary = queued?.primary ?: currentKeys?.getOrNull(0)
        val secondary = queued?.secondary ?: currentKeys?.getOrNull(1)
        val next = if (slot == 0) key to secondary else primary to key
        applyBinding(commandId, next.first, next.second)
    }

    /**
     * Category reset (plan SS7): restore capture defaults, but skip reserved
     * keys outright - commands whose stock default is a reserved key keep
     * their current binding. No conflict prompts; displaced assignments
     * resolve silently, exactly like the in-game reset.
     */
    fun resetCategory() {
        val targets = WowVanillaBindingCatalog.inCategory(selectedCategory)
            .mapNotNull { binding ->
                WowVanillaBindingCatalog.stockDefaultKeys(binding.id)
                    ?.slots
                    ?.filter { it !in WowVanillaBindingCatalog.reservedKeys }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { binding.id to it }
            }
        if (targets.isEmpty()) return
        scope.launch {
            runCatching {
                val target = checkNotNull(selectedScope) { "no binding scope selected" }
                if (activity == InGameSettingsEditor.ClientActivity.STOPPED) {
                    // One batched read-modify-write under a single lock hold:
                    // per-binding coroutines would race the same file.
                    editor.directEdit(
                        runtime,
                        targets.map { (commandId, slots) ->
                            InGameSettingsEditor.DirectEdit(
                                family = InGameSettingsEditor.DirectEdit.Family.BINDING,
                                queueRemovalId = commandId,
                                journalKey = commandId,
                                bindingScope = target,
                                command = commandId,
                                primary = slots.getOrNull(0),
                                secondary = slots.getOrNull(1),
                            )
                        },
                    )
                } else {
                    targets.forEach { (commandId, slots) ->
                        editor.stageBindingOverride(
                            commandId, slots.getOrNull(0), slots.getOrNull(1), target,
                        )
                    }
                }
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
            refresh()
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceRow(
            label = "Scope",
            selectedId = selectedScope,
            choices = scopes.map { (id, kind) -> id to "$id ($kind)" },
            tag = "ingame-bindings-scope",
            onSelect = { selectedScope = it },
        )
        if (activity != InGameSettingsEditor.ClientActivity.STOPPED) {
            val queuedCount = snap.gameSettings.totalQueued
            val status = when (activity) {
                InGameSettingsEditor.ClientActivity.LAUNCHING -> "Launching"
                InGameSettingsEditor.ClientActivity.UNKNOWN -> "Checking client state"
                else -> "Client running"
            }
            val queued = when {
                queuedCount == 1 -> " — 1 change applies next launch"
                queuedCount > 1 -> " — $queuedCount changes apply next launch"
                else -> ""
            }
            Text(
                status + queued,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ingame-bindings-queued-banner"),
            )
        }
        if (selectedScope == null && scopes.isEmpty()) {
            Text(
                "Log in once in game to edit key bindings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("ingame-bindings-absent"),
            )
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("ingame-error"),
            )
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search commands") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("ingame-bindings-search"),
        )

        val matches = remember(search, selectedCategory) {
            WowVanillaBindingCatalog.search(
                search,
                categories = if (search.isBlank()) setOf(selectedCategory) else emptySet(),
            )
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CategoryRail(
                        selected = selectedCategory,
                        onSelect = {
                            selectedCategory = it
                            search = ""
                        },
                        modifier = Modifier.weight(0.9f).fillMaxHeight(),
                    )
                    BindingList(
                        matches = matches,
                        bindings = bindings,
                        queued = snap.gameSettings.bindings,
                        onEdit = { commandId, slot -> edit = BindingEdit(commandId, slot) },
                        onUnbind = { commandId, slot -> requestAssign(commandId, slot, null) },
                        onResetCategory = {
                            resetCategory()
                        },
                        modifier = Modifier.weight(1.7f).fillMaxHeight(),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        WowBindingCategory.entries.forEach { category ->
                            FilterChip(
                                selected = category == selectedCategory,
                                onClick = {
                                    selectedCategory = category
                                    search = ""
                                },
                                label = { Text(category.displayName) },
                                modifier = Modifier.padding(end = 8.dp)
                                    .testTag("ingame-bindings-cat-" + category.name.lowercase()),
                            )
                        }
                    }
                    BindingList(
                        matches = matches,
                        bindings = bindings,
                        queued = snap.gameSettings.bindings,
                        onEdit = { commandId, slot -> edit = BindingEdit(commandId, slot) },
                        onUnbind = { commandId, slot -> requestAssign(commandId, slot, null) },
                        onResetCategory = {
                            resetCategory()
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }

    edit?.let { target ->
        KeyPickerDialog(
            commandId = target.commandId,
            bindings = bindings,
            onDismiss = { edit = null },
            onPick = { key ->
                edit = null
                // null is the dialog's explicit Unbind for this slot.
                requestAssign(target.commandId, target.slot, key)
            },
        )
    }
    conflict?.let { (commandId, key, slot) ->
        val displacedLabel = bindings?.get(key)?.let { id ->
            WowVanillaBindingCatalog.find(id)?.label ?: id
        } ?: "another action"
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Reassign $key?") },
            text = {
                Text(
                    "$key is bound to $displacedLabel. " +
                        "Reassigning it here unbinds it there.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val queued = snap.gameSettings.bindings[commandId]
                    val currentKeys = bindings?.filterValues { it == commandId }?.keys?.toList()
                    val primary = queued?.primary ?: currentKeys?.getOrNull(0)
                    val secondary = queued?.secondary ?: currentKeys?.getOrNull(1)
                    val next = if (slot == 0) key to secondary else primary to key
                    conflict = null
                    applyBinding(commandId, next.first, next.second)
                }) { Text("Reassign") }
            },
            dismissButton = {
                TextButton(onClick = { conflict = null }) { Text("Cancel") }
            },
        )
    }
}


@Composable
private fun CategoryRail(
    selected: WowBindingCategory,
    onSelect: (WowBindingCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.testTag("ingame-bindings-rail")) {
        LazyColumn(Modifier.padding(4.dp)) {
            items(WowBindingCategory.entries) { category ->
                TextButton(
                    onClick = { onSelect(category) },
                    modifier = Modifier.fillMaxWidth()
                        .testTag("ingame-bindings-cat-" + category.name.lowercase()),
                ) {
                    Text(
                        category.displayName,
                        style = if (category == selected) {
                            MaterialTheme.typography.bodyMedium
                        } else MaterialTheme.typography.bodyMedium,
                        color = if (category == selected) {
                            MaterialTheme.colorScheme.primary
                        } else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun BindingList(
    matches: List<com.pocketrealm.client.WowBindingDefinition>,
    bindings: Map<String, String>?,
    queued: Map<String, com.pocketrealm.ingame.BindingOverride>,
    onEdit: (String, Int) -> Unit,
    onUnbind: (String, Int) -> Unit,
    onResetCategory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                onClick = onResetCategory,
                modifier = Modifier.testTag("ingame-bindings-reset"),
            ) { Text("Reset to defaults") }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(matches, key = { it.id }) { binding ->
                BindingRow(
                    binding = binding,
                    bindings = bindings,
                    queued = queued[binding.id],
                    onEdit = onEdit,
                    onUnbind = onUnbind,
                )
            }
        }
    }
}

@Composable
private fun BindingRow(
    binding: com.pocketrealm.client.WowBindingDefinition,
    bindings: Map<String, String>?,
    queued: com.pocketrealm.ingame.BindingOverride?,
    onEdit: (String, Int) -> Unit,
    onUnbind: (String, Int) -> Unit,
) {
    val defaults = WowVanillaBindingCatalog.stockDefaultKeys(binding.id)
    val defaultKeys = defaults?.slots.orEmpty()
    // A reserved default key is never a valid modification baseline: the
    // controller overlay owns whatever the stock default was (§7).
    val reservedDefault = defaultKeys.any { it in WowVanillaBindingCatalog.reservedKeys }
    val liveKeys = bindings?.filterValues { it == binding.id }?.keys?.toList()
    val primary = queued?.primary ?: liveKeys?.getOrNull(0)
    val secondary = queued?.secondary ?: liveKeys?.getOrNull(1)
    val modified = !reservedDefault &&
        (primary != defaultKeys.getOrNull(0) || secondary != defaultKeys.getOrNull(1))

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(binding.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                binding.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (reservedDefault) {
                Text(
                    "controller overlay",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("ingame-binding-overlay-${binding.id}"),
                )
            } else if (modified) {
                Text(
                    "Modified",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("ingame-binding-modified-${binding.id}"),
                )
            }
            if (queued != null) {
                Text(
                    "Queued — applies next launch",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column {
            (0..1).forEach { slot ->
                val key = if (slot == 0) primary else secondary
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { onEdit(binding.id, slot) },
                        modifier = Modifier
                            .width(150.dp)
                            .heightIn(min = 48.dp)
                            .testTag("ingame-binding-${binding.id}-slot$slot"),
                    ) { Text(key ?: "Unbound") }
                    if (key != null && key !in WowVanillaBindingCatalog.reservedKeys) {
                        TextButton(onClick = { onUnbind(binding.id, slot) }) { Text("✕") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------- key picker

private val KEY_GROUPS: List<Pair<String, List<String>>> = listOf(
    "Letters" to ('A'..'Z').map { it.toString() },
    "Digits" to listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    "Function" to (1..12).map { "F$it" },
    "Navigation" to listOf(
        "UP", "DOWN", "LEFT", "RIGHT", "SPACE", "ENTER", "ESCAPE", "TAB",
        "BACKSPACE", "INSERT", "DELETE", "HOME", "END", "PAGEUP", "PAGEDOWN",
    ),
    "Numpad" to listOf(
        "NUMPAD0", "NUMPAD1", "NUMPAD2", "NUMPAD3", "NUMPAD4", "NUMPAD5",
        "NUMPAD6", "NUMPAD7", "NUMPAD8", "NUMPAD9", "NUMPADPLUS", "NUMPADMINUS",
        "NUMPADMULTIPLY", "NUMPADDIVIDE", "NUMPADDECIMAL", "NUMLOCK",
    ),
    "Punctuation" to listOf("`", "-", "=", "[", "]", ";", "'", ",", ".", "/", "\\"),
    "Mouse & wheel" to listOf(
        "BUTTON1", "BUTTON2", "BUTTON3", "BUTTON4", "MOUSEWHEELUP", "MOUSEWHEELDOWN",
    ),
    "Other" to listOf("PRINTSCREEN"),
)

private val MODIFIERS = listOf("", "SHIFT-", "CTRL-", "ALT-", "CTRL-SHIFT-")

@Composable
private fun KeyPickerDialog(
    commandId: String,
    bindings: Map<String, String>?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    var modifier by remember { mutableStateOf("") }
    val label = WowVanillaBindingCatalog.find(commandId)?.label ?: commandId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign key — $label") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Modifier",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    MODIFIERS.forEach { candidate ->
                        FilterChip(
                            selected = modifier == candidate,
                            onClick = { modifier = candidate },
                            label = { Text(if (candidate.isEmpty()) "None" else candidate.trimEnd('-')) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                KEY_GROUPS.forEach { (group, keys) ->
                    Text(
                        group,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // Flow rows of keys would need FlowRow; a simple wrapped
                    // LazyColumn-free grid via rows of chips is enough here.
                    keys.chunked(6).forEach { rowKeys ->
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            rowKeys.forEach { key ->
                                val chord = modifier + key
                                val reserved = chord in WowVanillaBindingCatalog.reservedKeys
                                FilterChip(
                                    selected = false,
                                    enabled = !reserved,
                                    onClick = { onPick(chord) },
                                    label = { Text(key) },
                                    modifier = Modifier
                                        .padding(end = 6.dp, top = 4.dp)
                                        .heightIn(min = 48.dp)
                                        .testTag("ingame-key-$chord"),
                                )
                            }
                        }
                    }
                }
                Text(
                    "Reserved keys are used by the controller overlay.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(null) }) { Text("Unbind") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
