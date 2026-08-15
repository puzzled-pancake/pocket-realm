package com.pocketrealm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketrealm.bots.BotActivityPreset
import com.pocketrealm.bots.BotBehaviorPreset
import com.pocketrealm.bots.BotAdvancedSettings
import com.pocketrealm.bots.BotCustomConfiguration
import com.pocketrealm.bots.BotCustomPresets
import com.pocketrealm.bots.BotPresetStore
import com.pocketrealm.bots.BotPlaystylePreset
import com.pocketrealm.bots.BotPopulationPolicy
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.bots.BotSelection
import com.pocketrealm.realm.RealmState
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RuntimeSupervisorClient
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** What the editor is currently pointed at. */
private sealed interface EditorTarget {
    data class BuiltIn(val profileId: String) : EditorTarget
    data class Saved(val presetId: String) : EditorTarget
    data object NewDraft : EditorTarget
}

/**
 * Dedicated Bots destination (brief §6): presets | configuration | result in
 * landscape, two panes on narrower landscape, one column in portrait. All
 * Playerbots configuration lives here; Settings keeps only general concerns.
 */
@Composable
fun BotsScreen() {
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }
    val snapshotState: Settings.Snapshot? by settings.flow.collectAsState(initial = null)
    val snapshot = snapshotState ?: Settings.Snapshot()
    val store = remember(context) {
        if (BotCustomPresets.store() == null) {
            BotCustomPresets.install(File(context.filesDir, "bots"))
        }
        BotCustomPresets.store() ?: BotPresetStore(File(context.filesDir, "bots"))
    }
    val presets by store.presets.collectAsState()
    val scope = rememberCoroutineScope()
    val supervisorClient = remember(context) { RuntimeSupervisorClient(context) }
    val realmState by remember(supervisorClient) {
        supervisorClient.observeRealmState()
    }.collectAsState(initial = RealmState.Idle)

    // One-time migration of a legacy advanced setup into a named preset (§42).
    // Reuses an existing "Imported Advanced Setup" preset so a half-completed
    // earlier attempt (process death between store write and DataStore write)
    // can never leave duplicates behind.
    LaunchedEffect(snapshotState?.botPresetsImported, snapshotState?.botAdvancedEnabled, presets) {
        val snap = snapshotState ?: return@LaunchedEffect
        if (!snap.botPresetsImported && snap.botAdvancedEnabled) {
            val legacy = BotProfiles.advanced(snap.botPopulationTarget, snap.botAdvanced)
            val imported = presets.firstOrNull { it.name == IMPORTED_PRESET_NAME }
                ?: runCatching {
                    store.create(IMPORTED_PRESET_NAME, base = legacy)
                }.getOrNull()
            if (imported != null) {
                runCatching {
                    settings.update {
                        it.copy(
                            botSavedPresetId = imported.id,
                            botPresetsImported = true,
                            botAdvancedEnabled = false,
                        )
                    }
                }
            }
        }
    }

    // Editor state survives rail navigation and process death: unsaved
    // advanced edits must not vanish because the user checked another tab.
    val targetSaver = Saver<EditorTarget?, String>(
        save = { target ->
            when (target) {
                is EditorTarget.BuiltIn -> "b:${target.profileId}"
                is EditorTarget.Saved -> "s:${target.presetId}"
                EditorTarget.NewDraft -> "n"
                null -> null
            }
        },
        restore = { value ->
            when {
                value == "n" -> EditorTarget.NewDraft
                value.startsWith("b:") -> EditorTarget.BuiltIn(value.removePrefix("b:"))
                value.startsWith("s:") -> EditorTarget.Saved(value.removePrefix("s:"))
                else -> null
            }
        },
    )
    var target by rememberSaveable(stateSaver = targetSaver) { mutableStateOf<EditorTarget?>(null) }
    var working by rememberSaveable(
        stateSaver = Saver(
            save = { BotPresetStore.encodeConfiguration(it) },
            restore = { BotPresetStore.decodeConfiguration(it) },
        ),
    ) { mutableStateOf(BotCustomConfiguration.fromProfile(BotProfiles.defaultProfile)) }
    var presetNameDraft by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var nameRequest by remember { mutableStateOf<NameRequest?>(null) }
    var deleteRequest by remember { mutableStateOf<String?>(null) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    // The preset id, not the object, survives process death while the system
    // file picker is open; the callback re-resolves it against the store.
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }

    // Preset interchange: export writes a checked JSON document through the
    // system file picker; import re-validates it through the store.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val preset = pendingExportId?.let { id -> presets.firstOrNull { it.id == id } }
        pendingExportId = null
        if (uri != null && preset == null) {
            actionError = "The preset to export is no longer available"
        }
        if (uri != null && preset != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(store.exportJson(preset).toByteArray(Charsets.UTF_8))
                    } ?: error("could not open the selected location")
                }.onFailure { actionError = "Export failed: ${it.message}" }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("could not read the selected file")
                    store.importJson(text)
                }.onFailure {
                    actionError = it.message ?: "The preset file could not be imported"
                }
            }
        }
    }

    // Sync editor with the persisted selection when the user has not started
    // editing something else yet (or after apply/reset navigates it).
    LaunchedEffect(snapshotState, presets) {
        val snap = snapshotState ?: return@LaunchedEffect
        if (target == null || targetIsStale(target, presets)) {
            val saved = snap.botSavedPresetId?.let { id -> presets.firstOrNull { it.id == id } }
            if (saved != null) {
                target = EditorTarget.Saved(saved.id)
                working = saved.configuration
            } else {
                val builtin = BotProfiles.find(snap.botProfileId)
                    ?: BotProfiles.defaultProfile
                target = EditorTarget.BuiltIn(builtin.id)
                working = BotCustomConfiguration.fromProfile(builtin)
            }
        }
    }

    val selection = BotSelection.resolve(
        savedPresetId = snapshot.botSavedPresetId,
        advancedEnabled = snapshot.botAdvancedEnabled,
        advancedTarget = snapshot.botPopulationTarget,
        advanced = snapshot.botAdvanced,
        profileId = snapshot.botProfileId,
    )
    val currentSavedPreset = presets.firstOrNull { it.id == snapshot.botSavedPresetId }
    val baseConfiguration = when (val t = target) {
        is EditorTarget.Saved -> presets.firstOrNull { it.id == t.presetId }?.configuration
        is EditorTarget.BuiltIn -> BotProfiles.find(t.profileId)?.let(BotCustomConfiguration::fromProfile)
        EditorTarget.NewDraft, null -> null
    }
    val dirty = baseConfiguration != null && working != baseConfiguration
    val running = realmState is RealmState.Running

    fun selectBuiltIn(profile: com.pocketrealm.bots.BotProfile) {
        target = EditorTarget.BuiltIn(profile.id)
        working = BotCustomConfiguration.fromProfile(profile)
        advancedOpen = false
    }
    fun selectSaved(preset: BotPresetStore.SavedPreset) {
        target = EditorTarget.Saved(preset.id)
        working = preset.configuration
        advancedOpen = false
    }
    fun startNewDraft() {
        target = EditorTarget.NewDraft
        working = BotCustomConfiguration.fromProfile(BotProfiles.defaultProfile)
        presetNameDraft = ""
        advancedOpen = false
    }

    fun saveWorkingAsPreset(name: String) {
        val trimmed = name.trim()
        if (!BotPresetStore.isValidName(trimmed)) return
        scope.launch {
            runCatching {
                val created = store.create(trimmed, base = null)
                store.save(created.id, working)
                settings.update { it.copy(botSavedPresetId = created.id) }
                target = EditorTarget.Saved(created.id)
            }.onFailure { actionError = it.message ?: "The preset could not be saved" }
        }
    }

    // Apply always lands exactly what the editor shows: modified saved
    // presets are saved first, modified built-ins are named once and become
    // a custom preset (the previous version's edit-anything flow).
    fun applySelection() {
        scope.launch {
            when (val t = target) {
                is EditorTarget.BuiltIn -> {
                    val profile = BotProfiles.find(t.profileId) ?: return@launch
                    if (dirty) {
                        nameRequest = NameRequest(
                            title = "Save modified “${profile.displayName}” as preset",
                            initial = "",
                        ) { name -> presetNameDraft = name; saveWorkingAsPreset(name) }
                    } else {
                        settings.update {
                            it.copy(
                                botProfileId = profile.id,
                                botPopulationTarget = profile.selectedTarget,
                                botSavedPresetId = null,
                                botAdvancedEnabled = false,
                                botAdvanced = BotAdvancedSettings.fromProfile(profile),
                            )
                        }
                    }
                }
                is EditorTarget.Saved -> {
                    if (dirty) {
                        val saved = runCatching { store.save(t.presetId, working) }
                        if (saved.isFailure) {
                            actionError = saved.exceptionOrNull()?.message
                                ?: "The preset could not be saved"
                            return@launch
                        }
                    }
                    settings.update { it.copy(botSavedPresetId = t.presetId, botAdvancedEnabled = false) }
                }
                EditorTarget.NewDraft -> nameRequest = NameRequest(
                    title = "New preset name",
                    initial = presetNameDraft,
                ) { name -> presetNameDraft = name; saveWorkingAsPreset(name) }
                null -> Unit
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val threePane = maxWidth >= 840.dp
        val twoPane = maxWidth >= 600.dp && !threePane
        val presetsPane: @Composable (Modifier) -> Unit = { modifier ->
            PresetsPane(
                presets = presets,
                selectedSavedId = (target as? EditorTarget.Saved)?.presetId,
                selectedBuiltinId = (target as? EditorTarget.BuiltIn)?.profileId,
                search = search,
                onSearch = { search = it },
                onSelectBuiltIn = ::selectBuiltIn,
                onSelectSaved = ::selectSaved,
                onNew = ::startNewDraft,
                onImport = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                onExport = { preset ->
                    pendingExportId = preset.id
                    exportLauncher.launch("${preset.name}.botpreset.json")
                },
                onToggleFavorite = { id, favorite ->
                    scope.launch {
                        runCatching { store.setFavorite(id, favorite) }
                            .onFailure { actionError = it.message ?: "Could not update the preset" }
                    }
                },
                onDuplicate = { id ->
                    val source = presets.firstOrNull { it.id == id } ?: return@PresetsPane
                    nameRequest = NameRequest(
                        title = "Duplicate “${source.name}”",
                        initial = "${source.name} copy",
                    ) { name ->
                        scope.launch {
                            runCatching { store.duplicate(id, name.trim()) }
                                .onFailure {
                                    actionError = it.message ?: "Could not duplicate the preset"
                                }
                        }
                    }
                },
                onRename = { id ->
                    val source = presets.firstOrNull { it.id == id } ?: return@PresetsPane
                    nameRequest = NameRequest(
                        title = "Rename preset",
                        initial = source.name,
                    ) { name ->
                        scope.launch {
                            runCatching { store.rename(id, name.trim()) }
                                .onFailure { actionError = it.message ?: "Could not rename the preset" }
                        }
                    }
                },
                onDelete = { id -> deleteRequest = id },
                modifier = modifier,
            )
        }
        val configPane: @Composable (Modifier) -> Unit = { modifier ->
            ConfigPane(
                target = target,
                working = working,
                dirty = dirty,
                advancedOpen = advancedOpen,
                onAdvancedOpen = { advancedOpen = it },
                onWorking = { working = it },
                modifier = modifier,
            )
        }
        val resultPane: @Composable (Modifier) -> Unit = { modifier ->
            val applied = when (val t = target) {
                is EditorTarget.Saved -> snapshot.botSavedPresetId == t.presetId
                is EditorTarget.BuiltIn ->
                    snapshot.botSavedPresetId == null && snapshot.botProfileId == t.profileId
                EditorTarget.NewDraft, null -> false
            }
            ResultPane(
                target = target,
                working = working,
                dirty = dirty,
                running = running,
                savedPreset = currentSavedPreset,
                applied = applied,
                onApply = ::applySelection,
                onSave = {
                    when (val t = target) {
                        is EditorTarget.Saved -> scope.launch {
                            runCatching { store.save(t.presetId, working) }
                                .onFailure {
                                    actionError = it.message ?: "The preset could not be saved"
                                }
                        }
                        EditorTarget.NewDraft -> nameRequest = NameRequest(
                            title = "New preset name",
                            initial = presetNameDraft,
                        ) { name -> presetNameDraft = name; saveWorkingAsPreset(name) }
                        is EditorTarget.BuiltIn -> nameRequest = NameRequest(
                            title = "Save modified built-in as preset",
                            initial = "",
                        ) { name -> saveWorkingAsPreset(name) }
                        null -> Unit
                    }
                },
                onReset = {
                    when (val t = target) {
                        is EditorTarget.Saved ->
                            presets.firstOrNull { it.id == t.presetId }?.let(::selectSaved)
                        is EditorTarget.BuiltIn ->
                            BotProfiles.find(t.profileId)?.let(::selectBuiltIn)
                        EditorTarget.NewDraft -> startNewDraft()
                        null -> Unit
                    }
                },
                modifier = modifier,
            )
        }
        when {
            threePane -> Row(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                presetsPane(Modifier.weight(0.95f).fillMaxHeight())
                configPane(Modifier.weight(1.7f).fillMaxHeight())
                resultPane(Modifier.weight(1.05f).fillMaxHeight())
            }
            twoPane -> Row(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                presetsPane(Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1.6f).fillMaxHeight()) {
                    resultPane(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    configPane(Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(
                // No outer verticalScroll: the panes scroll internally, and a
                // scrollable parent would hand the preset LazyColumn an
                // infinite height constraint (crash on narrow screens).
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                resultPane(Modifier.fillMaxWidth())
                configPane(Modifier.weight(1.3f).fillMaxWidth())
                presetsPane(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    nameRequest?.let { request ->
        var value by remember(request) { mutableStateOf(request.initial) }
        val trimmed = value.trim()
        val nameValid = BotPresetStore.isValidName(trimmed)
        AlertDialog(
            onDismissRequest = { nameRequest = null },
            title = { Text(request.title) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("Preset name") },
                    isError = trimmed.isNotEmpty() && !nameValid,
                    supportingText = {
                        Text(
                            if (trimmed.isNotEmpty() && !nameValid) {
                                "Use up to 48 letters, numbers, or spaces"
                            } else {
                                " "
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("preset-name-field"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nameRequest = null
                        request.onConfirm(trimmed)
                    },
                    enabled = nameValid,
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { nameRequest = null }) { Text("Cancel") }
            },
        )
    }
    deleteRequest?.let { id ->
        val name = presets.firstOrNull { it.id == id }?.name ?: ""
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text("Delete “$name”?") },
            text = { Text("Realms already launched with this preset stay unchanged; new launches fall back to the built-in selection.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteRequest = null
                    scope.launch {
                        runCatching { store.delete(id) }
                            .onFailure { actionError = it.message ?: "Could not delete the preset" }
                        if (snapshot.botSavedPresetId == id) {
                            settings.update { it.copy(botSavedPresetId = null) }
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteRequest = null }) { Text("Cancel") }
            },
        )
    }
    actionError?.let { message ->
        AlertDialog(
            onDismissRequest = { actionError = null },
            title = { Text("Preset transfer") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { actionError = null }) { Text("OK") }
            },
        )
    }
}

private const val IMPORTED_PRESET_NAME = "Imported Advanced Setup"

private data class NameRequest(
    val title: String,
    val initial: String,
    val onConfirm: (String) -> Unit,
)

private fun targetIsStale(
    target: EditorTarget?,
    presets: List<BotPresetStore.SavedPreset>,
): Boolean = when (target) {
    // Only deletion invalidates the editing target. Requiring the persisted
    // selection to match made every store write (favorite/rename/save) snap
    // the editor away and silently discard in-progress edits.
    is EditorTarget.Saved -> presets.none { it.id == target.presetId }
    is EditorTarget.BuiltIn -> false
    EditorTarget.NewDraft, null -> false
}

// ---------------------------------------------------------------------
// Presets pane
// ---------------------------------------------------------------------

@Composable
private fun PresetsPane(
    presets: List<BotPresetStore.SavedPreset>,
    selectedSavedId: String?,
    selectedBuiltinId: String?,
    search: String,
    onSearch: (String) -> Unit,
    onSelectBuiltIn: (com.pocketrealm.bots.BotProfile) -> Unit,
    onSelectSaved: (BotPresetStore.SavedPreset) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onExport: (BotPresetStore.SavedPreset) -> Unit,
    onToggleFavorite: (String, Boolean) -> Unit,
    onDuplicate: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.testTag("bots-presets-pane")) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PRESETS", style = MaterialTheme.typography.labelMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Text("Recommended", style = MaterialTheme.typography.labelSmall)
                    PresetChip(
                        label = "★ ${BotProfiles.ALIVE_REALM_320.displayName}",
                        summary = BotProfiles.ALIVE_REALM_320.summary,
                        selected = selectedBuiltinId == BotProfiles.ALIVE_REALM_320.id,
                        tag = "preset-builtin-320",
                        onClick = { onSelectBuiltIn(BotProfiles.ALIVE_REALM_320) },
                    )
                }
                item {
                    Text("Built-in", style = MaterialTheme.typography.labelSmall)
                }
                items(BotProfiles.experiencePresets.filter { it != BotProfiles.ALIVE_REALM_320 }) { preset ->
                    PresetChip(
                        label = preset.displayName,
                        summary = preset.summary,
                        selected = selectedBuiltinId == preset.id,
                        tag = "preset-builtin-${preset.selectedTarget}",
                        onClick = { onSelectBuiltIn(preset) },
                    )
                }
                items(BotProfiles.legacySelectablePresets) { preset ->
                    PresetChip(
                        label = preset.displayName,
                        summary = "Retained legacy profile; custom presets can exceed 700.",
                        selected = selectedBuiltinId == preset.id,
                        tag = "preset-builtin-${preset.selectedTarget}",
                        onClick = { onSelectBuiltIn(preset) },
                    )
                }
                if (presets.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text("My presets", style = MaterialTheme.typography.labelSmall)
                        if (presets.size > 6) {
                            OutlinedTextField(
                                value = search,
                                onValueChange = onSearch,
                                singleLine = true,
                                label = { Text("Search") },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .testTag("preset-search"),
                            )
                        }
                    }
                }
                val visible = presets
                    .filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
                    .sortedWith(compareByDescending<BotPresetStore.SavedPreset> { it.favorite }.thenBy { it.name.lowercase() })
                items(visible, key = { it.id }) { preset ->
                    SavedPresetRow(
                        preset = preset,
                        selected = selectedSavedId == preset.id,
                        onSelect = { onSelectSaved(preset) },
                        onToggleFavorite = { onToggleFavorite(preset.id, it) },
                        onRename = { onRename(preset.id) },
                        onDuplicate = { onDuplicate(preset.id) },
                        onExport = { onExport(preset) },
                        onDelete = { onDelete(preset.id) },
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onNew,
                            modifier = Modifier.weight(1f).testTag("preset-new"),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("  New")
                        }
                        OutlinedButton(
                            onClick = onImport,
                            modifier = Modifier.weight(1f).testTag("preset-import"),
                        ) { Text("Import") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedPresetRow(
    preset: BotPresetStore.SavedPreset,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("preset-saved"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onToggleFavorite(!preset.favorite) },
                modifier = Modifier.testTag("preset-favorite"),
            ) {
                Icon(
                    if (preset.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (preset.favorite) "Remove favorite" else "Mark favorite",
                )
            }
            Column(
                Modifier.weight(1f).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${preset.configuration.selectedTarget} bots · " +
                        "revision ${preset.revision.revision} · " +
                        "${preset.configuration.activeBotPercent}% active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("preset-menu")) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Preset actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Export to file") },
                        onClick = { menuOpen = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    summary: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

// ---------------------------------------------------------------------
// Configuration pane
// ---------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigPane(
    target: EditorTarget?,
    working: BotCustomConfiguration,
    dirty: Boolean,
    advancedOpen: Boolean,
    onAdvancedOpen: (Boolean) -> Unit,
    onWorking: (BotCustomConfiguration) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.testTag("bots-config-pane")) {
        Column(
            Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    titleFor(target, working),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (dirty) {
                    Text(
                        "Modified",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag("bots-dirty-badge"),
                    )
                }
            }

            Text("WORLD SIZE", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BotProfiles.experiencePresets.map { it.selectedTarget }.forEach { size ->
                    FilterChip(
                        selected = working.selectedTarget == size &&
                            working.maximumOnline == size &&
                            working.minimumOnline <= size,
                        onClick = { onWorking(working.withTarget(size)) },
                        label = { Text("$size") },
                        modifier = Modifier.testTag("world-size-$size"),
                    )
                }
                FilterChip(
                    selected = working.selectedTarget !in
                        BotProfiles.experiencePresets.map { it.selectedTarget } ||
                        working.maximumOnline != working.selectedTarget,
                    onClick = {
                        onWorking(working.withTarget(725))
                    },
                    label = { Text("Custom") },
                    modifier = Modifier.testTag("world-size-custom"),
                )
            }
            if (working.selectedTarget !in BotProfiles.experiencePresets.map { it.selectedTarget } ||
                working.maximumOnline != working.selectedTarget
            ) {
                CustomPopulationEditor(working = working, onWorking = onWorking)
            }

            Text("ACTIVITY", style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    BotActivityPreset.SMART, BotActivityPreset.ACTIVE,
                    BotActivityPreset.BALANCED, BotActivityPreset.LIGHT,
                ).forEach { activity ->
                    FilterChip(
                        selected = activity.matches(working),
                        onClick = { onWorking(activity.applyTo(working)) },
                        label = { Text(activity.label) },
                        modifier = Modifier.testTag("activity-${activity.name.lowercase()}"),
                    )
                }
                if (BotActivityPreset.CUSTOM.matches(working)) {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text("Custom") },
                        modifier = Modifier.testTag("activity-custom"),
                    )
                }
            }
            Text(
                (BotActivityPreset.entries.firstOrNull { it != BotActivityPreset.CUSTOM && it.matches(working) }
                    ?: BotActivityPreset.CUSTOM).summary,
                style = MaterialTheme.typography.bodySmall,
            )

            Text("PLAYSTYLE", style = MaterialTheme.typography.labelSmall)
            BotPlaystylePreset.entries.filter { it != BotPlaystylePreset.CUSTOM }.forEach { playstyle ->
                FilterChip(
                    selected = playstyle.matches(working),
                    onClick = { onWorking(playstyle.applyTo(working)) },
                    label = { Text(playstyle.label) },
                    modifier = Modifier.fillMaxWidth()
                        .testTag("playstyle-${playstyle.name.lowercase()}"),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = advancedOpen,
                    onCheckedChange = onAdvancedOpen,
                    modifier = Modifier.testTag("bots-advanced-toggle"),
                )
                Text("  Advanced configuration", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "Opening Advanced never changes values; every field starts exactly from the selection above.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (advancedOpen) {
                AdvancedSections(working = working, onWorking = onWorking)
            }
        }
    }
}

private fun titleFor(target: EditorTarget?, working: BotCustomConfiguration): String = when (target) {
    is EditorTarget.BuiltIn -> BotProfiles.find(target.profileId)?.displayName ?: "Bots"
    is EditorTarget.Saved -> "Custom preset"
    EditorTarget.NewDraft -> "New preset"
    null -> "Bots"
}

/** Direct numeric population entry (brief §11): not a %25 ladder, no 600 cap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomPopulationEditor(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    var text by remember(working.selectedTarget) { mutableStateOf(working.selectedTarget.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                val parsed = raw.trim().toIntOrNull()
                when {
                    parsed == null -> error = "Enter a number"
                    parsed < BotPopulationPolicy.MIN_CUSTOM_TARGET ->
                        error = "Minimum ${BotPopulationPolicy.MIN_CUSTOM_TARGET} bots"
                    parsed > BotPopulationPolicy.MAX_SUPPORTED_TARGET ->
                        error = "Supported ceiling ${BotPopulationPolicy.MAX_SUPPORTED_TARGET}"
                    else -> {
                        error = null
                        onWorking(working.withTarget(parsed))
                    }
                }
            },
            isError = error != null,
            supportingText = {
                Text(
                    error ?: "Valid range ${BotPopulationPolicy.MIN_CUSTOM_TARGET}.." +
                        "${BotPopulationPolicy.MAX_SUPPORTED_TARGET}; " +
                        "${BotPopulationPolicy.allocatedAccounts(working.selectedTarget)} accounts " +
                        "auto-provisioned",
                )
            },
            singleLine = true,
            label = { Text("Target bots") },
            modifier = Modifier.fillMaxWidth().testTag("custom-target-field"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(80, 160, 240, 320, 400, 500, 600).forEach { size ->
                OutlinedButton(
                    onClick = { onWorking(working.withTarget(size)) },
                    modifier = Modifier.testTag("quick-target-$size"),
                ) { Text("$size") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedSections(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    HorizontalDivider()
    Text("POPULATION - AMOUNT OF BOTS", style = MaterialTheme.typography.labelSmall)
    Text(
        "Starts at ${working.initialTarget} -> ${working.selectedTarget} bots - " +
            "${working.nearPlayerTeleportMaxAmount} favored near players",
        style = MaterialTheme.typography.bodySmall,
    )
    Stepper("Minimum online", working.minimumOnline, 0..working.selectedTarget) { value ->
        onWorking(
            working.copy(
                minimumOnline = value,
                initialTarget = maxOf(working.initialTarget, value),
            ),
        )
    }
    Stepper("Initial bots", working.initialTarget, working.minimumOnline..working.selectedTarget) { value ->
        onWorking(working.copy(initialTarget = value))
    }
    Stepper("Startup increase step", working.startupIncreaseStep, 1..working.selectedTarget) { value ->
        onWorking(working.copy(startupIncreaseStep = value))
    }
    Stepper("Startup ramp interval (s)", (working.startupRampIntervalMs / 1000).toInt(), 0..1_800, step = 30) { value ->
        onWorking(working.copy(startupRampIntervalMs = value * 1_000L))
    }
    Stepper("Activation batch", working.activationBatchSize, 1..64) { value ->
        onWorking(working.copy(activationBatchSize = value))
    }
    Stepper("Maximum configured", working.maximumOnline, working.selectedTarget..BotPopulationPolicy.MAX_SUPPORTED_TARGET) { value ->
        onWorking(
            working.copy(
                maximumOnline = value,
                accountCount = maxOf(
                    working.accountCount,
                    BotPopulationPolicy.allocatedAccounts(value),
                ),
            ),
        )
    }

    HorizontalDivider()
    Text("NEARBY SPAWNS - CLOSE TO PLAYERS", style = MaterialTheme.typography.labelSmall)
    SteppedSlider(
        "Nearby bots per human", working.nearPlayerTeleportMaxAmount, 0..50, 1,
        "bots-nearby-limit",
    ) { value ->
        onWorking(
            working.copy(
                nearPlayerTeleportMaxAmount = value,
                nearPlayerTeleportRadius = if (value == 0) 0
                else working.nearPlayerTeleportRadius.coerceAtLeast(100),
            ),
        )
    }
    if (working.nearPlayerTeleportMaxAmount > 0) {
        SteppedSlider(
            "Nearby radius (yards)", working.nearPlayerTeleportRadius, 100..500, 50,
            "bots-nearby-radius",
        ) { value -> onWorking(working.copy(nearPlayerTeleportRadius = value)) }
    }
    SwitchRow(
        "Fast promotion near players", working.forceActiveWhenNearPlayer, "bots-force-active",
    ) { value -> onWorking(working.copy(forceActiveWhenNearPlayer = value)) }
    Text("Nearby teleport cadence", style = MaterialTheme.typography.labelLarge)
    Text(
        "Controls how often distant bots may be moved near active players.",
        style = MaterialTheme.typography.bodySmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(30 to 120, 60 to 240, 120 to 480, 240 to 720).forEach { (min, max) ->
            FilterChip(
                selected = working.teleportMinIntervalSeconds == min * 60 &&
                    working.teleportMaxIntervalSeconds == max * 60,
                onClick = {
                    onWorking(
                        working.copy(
                            teleportMinIntervalSeconds = min * 60,
                            teleportMaxIntervalSeconds = max * 60,
                        ),
                    )
                },
                label = { Text(cadenceLabel(min, max)) },
                modifier = Modifier.testTag("bots-cadence-$min-$max"),
            )
        }
    }
    Stepper("Teleport min (min)", working.teleportMinIntervalSeconds / 60, 1..2_880, step = 30) { value ->
        val min = value * 60
        onWorking(
            working.copy(
                teleportMinIntervalSeconds = min,
                teleportMaxIntervalSeconds = maxOf(min, working.teleportMaxIntervalSeconds),
            ),
        )
    }
    Stepper("Teleport max (min)", working.teleportMaxIntervalSeconds / 60, 1..2_880, step = 30) { value ->
        onWorking(
            working.copy(
                teleportMaxIntervalSeconds = maxOf(value * 60, working.teleportMinIntervalSeconds),
            ),
        )
    }

    HorizontalDivider()
    Text("AI & SCHEDULING", style = MaterialTheme.typography.labelSmall)
    SteppedSlider(
        "AI update interval", working.randomBotUpdateIntervalMs, 500..5_000, 250,
        "bots-ai-update",
    ) { value -> onWorking(working.copy(randomBotUpdateIntervalMs = value)) }
    SteppedSlider(
        "Bot work per tick (iterations)", working.iterationsPerTick, 1..20, 1,
        "bots-iterations",
    ) { value -> onWorking(working.copy(iterationsPerTick = value)) }
    SteppedSlider(
        "Fully active background bots (%)", working.activeBotPercent, 1..20, 1,
        "bots-active-percent",
    ) { value -> onWorking(working.copy(activeBotPercent = value)) }
    Text(
        "Nearby and human-group bots are promoted regardless of the active percentage.",
        style = MaterialTheme.typography.bodySmall,
    )

    HorizontalDivider()
    Text("TEMPERAMENT", style = MaterialTheme.typography.labelSmall)
    Text(
        "Choose a starting style, then change any individual behaviour below.",
        style = MaterialTheme.typography.bodySmall,
    )
    BotBehaviorPreset.entries.forEach { preset ->
        FilterChip(
            selected = working.matchesBehaviorPreset(preset),
            onClick = { onWorking(working.withBehaviorPreset(preset)) },
            label = { Text(preset.label) },
            modifier = Modifier.fillMaxWidth()
                .testTag("bots-behavior-" + preset.name.lowercase()),
        )
        Text(preset.summary, style = MaterialTheme.typography.bodySmall)
    }
    SwitchRow("Quest and level autonomously", working.autoDoQuests, "bots-quests") { value ->
        onWorking(working.copy(autoDoQuests = value))
    }
    SwitchRow("Form groups with nearby bots", working.groupNearby, "bots-groups") { value ->
        onWorking(working.copy(groupNearby = value))
    }
    SwitchRow("Wander when idle", working.wanderWhenIdle, "bots-wander") { value ->
        onWorking(working.copy(wanderWhenIdle = value))
    }
    SwitchRow("Use off-spec strategies", working.enableOffSpecStrategies, "bots-offspec") { value ->
        onWorking(working.copy(enableOffSpecStrategies = value))
    }
    SwitchRow("Chat without a player master", working.allowBotChat, "bots-chat") { value ->
        onWorking(working.copy(allowBotChat = value))
    }
    SwitchRow("Bots may invite the player", working.allowPlayerInvites, "bots-invites") { value ->
        onWorking(working.copy(allowPlayerInvites = value))
    }
    SwitchRow("Limit background combat work", working.limitCombatActivity, "bots-limit-combat") { value ->
        onWorking(working.copy(limitCombatActivity = value))
    }
    SwitchRow("Bots log in at realm start", working.loginAtStartup, "bots-login-startup") { value ->
        onWorking(working.copy(loginAtStartup = value))
    }
    SwitchRow("Bot joins with its player", working.loginWithPlayer, "bots-login-with-player") { value ->
        onWorking(working.copy(loginWithPlayer = value))
    }

    HorizontalDivider()
    Text("LEVELING", style = MaterialTheme.typography.labelSmall)
    SwitchRow("Match bot levels to players", working.syncLevelWithPlayers, "bots-sync-level") { value ->
        onWorking(working.copy(syncLevelWithPlayers = value))
    }
    if (working.syncLevelWithPlayers) {
        Stepper("Max levels above player", working.syncLevelMaxAbove, 0..10) { value ->
            onWorking(working.copy(syncLevelMaxAbove = value))
        }
    }
    Stepper("Level when no players online", working.syncLevelNoPlayer, 1..60) { value ->
        onWorking(working.copy(syncLevelNoPlayer = value))
    }
    Stepper("Max-level bot chance (%)", (working.randomBotMaxLevelChance * 100).roundToInt(), 0..100, step = 5) { value ->
        onWorking(working.copy(randomBotMaxLevelChance = value / 100f))
    }
    Stepper("Rerandomize min (h)", working.randomizeMinIntervalSeconds / 3600, 1..336) { value ->
        val min = value * 3600
        onWorking(
            working.copy(
                randomizeMinIntervalSeconds = min,
                randomizeMaxIntervalSeconds = maxOf(min, working.randomizeMaxIntervalSeconds),
            ),
        )
    }
    Stepper("Rerandomize max (h)", working.randomizeMaxIntervalSeconds / 3600, 1..336) { value ->
        onWorking(
            working.copy(
                randomizeMaxIntervalSeconds = maxOf(value * 3600, working.randomizeMinIntervalSeconds),
            ),
        )
    }

    HorizontalDivider()
    Text("LOGIN & ACCOUNTS", style = MaterialTheme.typography.labelSmall)
    Text(
        "Account pool: Automatic - prefix " + working.accountPrefix + " - " +
            working.accountCount + " accounts - " +
            "capacity " + working.accountCount * BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT + " characters",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("bots-account-pool"),
    )
    Stepper("Login batch", working.loginBatchSize, 1..10) { value ->
        onWorking(working.copy(loginBatchSize = value))
    }
    Stepper("Maintenance batch", working.maintenanceBatchSize, 1..64) { value ->
        onWorking(working.copy(maintenanceBatchSize = value))
    }
    Stepper("Alternate bots per account", working.maximumAltBots, 0..8) { value ->
        onWorking(working.copy(maximumAltBots = value))
    }
    Stepper("Generation batch", working.generationBatchSize, 1..10) { value ->
        onWorking(working.copy(generationBatchSize = value))
    }
    Stepper("Generation yield (ms)", working.generationYieldMs.toInt(), 0..5_000, step = 50) { value ->
        onWorking(working.copy(generationYieldMs = value.toLong()))
    }

    HorizontalDivider()
    Text("ADAPTATION & PERFORMANCE", style = MaterialTheme.typography.labelSmall)
    SteppedSlider(
        "Reduce above world p99 (ms)", working.admission.maxWorldP99Ms, 100..300, 25,
        "bots-p99",
    ) { value -> onWorking(working.copy(admission = working.admission.copy(maxWorldP99Ms = value))) }
    Stepper("Memory floor (MiB)", working.admission.minFreeMemoryMiB.toInt(), 256..8_192, 256) { value ->
        onWorking(working.copy(admission = working.admission.copy(minFreeMemoryMiB = value.toLong())))
    }
    Stepper("Storage floor (MiB)", working.admission.minFreeStorageMiB.toInt(), 256..8_192, 256) { value ->
        onWorking(working.copy(admission = working.admission.copy(minFreeStorageMiB = value.toLong())))
    }
    Stepper("Warm-up (min)", (working.admission.performanceWarmupMs / 60_000).toInt(), 0..30) { value ->
        onWorking(
            working.copy(admission = working.admission.copy(performanceWarmupMs = value * 60_000L)),
        )
    }
    Stepper("Healthy ramp (min)", (working.admission.healthyRampMs / 60_000).toInt(), 1..120) { value ->
        onWorking(
            working.copy(
                admission = working.admission.copy(
                    healthyRampMs = maxOf(value * 60_000L, working.admission.changeCooldownMs),
                ),
            ),
        )
    }
    Stepper("Change cooldown (s)", (working.admission.changeCooldownMs / 1000).toInt(), 10..300, step = 5) { value ->
        val cooldown = value * 1_000L
        onWorking(
            working.copy(
                admission = working.admission.copy(
                    changeCooldownMs = cooldown,
                    healthyRampMs = maxOf(working.admission.healthyRampMs, cooldown),
                ),
            ),
        )
    }
    Stepper("Reduce step", working.admission.reduceStep, 1..100, step = 5) { value ->
        onWorking(working.copy(admission = working.admission.copy(reduceStep = value)))
    }
    Stepper("Increase step", working.admission.increaseStep, 1..100, step = 5) { value ->
        onWorking(working.copy(admission = working.admission.copy(increaseStep = value)))
    }
}

private fun cadenceLabel(min: Int, max: Int): String =
    (if (min < 60) "0.5" else (min / 60).toString()) + "-" + (max / 60) + " h"

private fun BotCustomConfiguration.withBehaviorPreset(preset: BotBehaviorPreset): BotCustomConfiguration =
    when (preset) {
        BotBehaviorPreset.EFFICIENT -> copy(
            limitCombatActivity = true,
            activeBotPercent = 3,
            autoDoQuests = false,
            allowBotChat = false,
            allowPlayerInvites = false,
            groupNearby = false,
            wanderWhenIdle = false,
            enableOffSpecStrategies = false,
        )
        BotBehaviorPreset.NATURAL -> copy(
            limitCombatActivity = true,
            activeBotPercent = 5,
            autoDoQuests = true,
            allowBotChat = false,
            allowPlayerInvites = false,
            groupNearby = true,
            wanderWhenIdle = true,
            enableOffSpecStrategies = true,
        )
        BotBehaviorPreset.SOCIAL -> copy(
            limitCombatActivity = false,
            activeBotPercent = 8,
            autoDoQuests = true,
            allowBotChat = true,
            allowPlayerInvites = true,
            groupNearby = true,
            wanderWhenIdle = true,
            enableOffSpecStrategies = true,
        )
    }

private fun BotCustomConfiguration.matchesBehaviorPreset(preset: BotBehaviorPreset): Boolean {
    val expected = withBehaviorPreset(preset)
    return limitCombatActivity == expected.limitCombatActivity &&
        activeBotPercent == expected.activeBotPercent &&
        autoDoQuests == expected.autoDoQuests &&
        allowBotChat == expected.allowBotChat &&
        allowPlayerInvites == expected.allowPlayerInvites &&
        groupNearby == expected.groupNearby &&
        wanderWhenIdle == expected.wanderWhenIdle &&
        enableOffSpecStrategies == expected.enableOffSpecStrategies
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { if (value - step >= range.first) onChange(value - step) },
                enabled = value - step >= range.first,
            ) { Text("−") }
            Text(
                "$value",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(48.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            TextButton(
                onClick = { if (value + step <= range.last) onChange(value + step) },
                enabled = value + step <= range.last,
            ) { Text("+") }
        }
    }
}

@Composable
private fun SteppedSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    tag: String,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("$value", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                // coerceIn must bind to the snapped product, not to step;
                // binding to step multiplied every value by range.first.
                val snapped = (((raw + step / 2) / step).toInt() * step)
                    .coerceIn(range.first, range.last)
                if (snapped != value) onChange(snapped)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
        Text("  $label", style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------
// Result pane
// ---------------------------------------------------------------------

@Composable
private fun ResultPane(
    target: EditorTarget?,
    working: BotCustomConfiguration,
    dirty: Boolean,
    running: Boolean,
    savedPreset: BotPresetStore.SavedPreset?,
    applied: Boolean,
    onApply: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.testTag("bots-result-pane")) {
        Column(
            Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("CURRENT RESULT", style = MaterialTheme.typography.labelMedium)
            Text(
                "${working.selectedTarget} bots",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("result-target"),
            )
            if (target != null && !applied) {
                Text(
                    "Preview — press Apply before starting the realm to use this configuration",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.testTag("result-not-applied"),
                )
            }
            Text(
                "~${working.estimatedActiveBots()} normally active · human groups highest priority",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            ResultRow("Ramp", "${working.initialTarget} → ${working.selectedTarget}")
            ResultRow(
                "AI pass",
                "${working.randomBotUpdateIntervalMs / 1000f}s · ${working.iterationsPerTick} it/tick",
            )
            ResultRow(
                "Nearby",
                if (working.nearPlayerTeleportMaxAmount == 0) "Off" else
                    "${working.nearPlayerTeleportMaxAmount} within ${working.nearPlayerTeleportRadius} yd",
            )
            ResultRow(
                "Accounts",
                "Auto · ${working.accountCount} (capacity " +
                    "${working.accountCount * BotPopulationPolicy.CHARACTERS_PER_BOT_ACCOUNT})",
            )
            ResultRow("Adaptive", "On · p99 ${working.admission.maxWorldP99Ms} ms floor")
            if (running) {
                HorizontalDivider()
                Text(
                    "Realm is running — a changed configuration applies on the next start. The running realm keeps its launch snapshot.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("result-running-note"),
                )
            }
            savedPreset?.let {
                Text(
                    "Selected preset: ${it.name} · revision ${it.revision.revision}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("result-selected-preset"),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApply,
                    enabled = target != null,
                    modifier = Modifier.weight(1f).testTag("result-apply"),
                ) {
                    // Modified presets are saved by Apply itself, so the
                    // primary action is never greyed out mid-edit.
                    Text(if (dirty && target is EditorTarget.Saved) "Save & apply" else "Apply")
                }
                Button(
                    onClick = onSave,
                    enabled = target is EditorTarget.Saved || dirty || target is EditorTarget.NewDraft,
                    modifier = Modifier.weight(1f).testTag("result-save"),
                ) { Text(if (target is EditorTarget.Saved) "Save" else "Save As") }
            }
            OutlinedButton(
                onClick = onReset,
                enabled = dirty,
                modifier = Modifier.fillMaxWidth().testTag("result-reset"),
            ) { Text("Reset changes") }
        }
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
