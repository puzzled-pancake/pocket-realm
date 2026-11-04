package com.pocketrealm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** What the editor is currently pointed at. */
private sealed interface EditorTarget {
    data class BuiltIn(val profileId: String) : EditorTarget
    data class Saved(val presetId: String) : EditorTarget
    data object NewDraft : EditorTarget
}

/**
 * Editor section tabs. Accounts and adaptation settings live behind
 * Population's per-tab "Advanced tuning" disclosure.
 */
private enum class EditorSection(val label: String) {
    BASICS("Basics"),
    POPULATION("Population"),
    BEHAVIOUR("Behaviour"),
}

private val EditorSectionSaver = Saver<EditorSection, String>(
    save = { it.name },
    restore = { runCatching { EditorSection.valueOf(it) }.getOrDefault(EditorSection.BASICS) },
)

/**
 * The bot panel shown in Settings: the three built-in presets, the user's
 * saved presets, and a collapsible editor. It does not scroll on its own;
 * the host page scrolls. The editor tabs are views over one working
 * configuration, so switching tabs never changes values.
 */
@Composable
fun BotsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember(context) { Settings(context) }
    val snapshotState: Settings.Snapshot? by settings.flow.collectAsState(initial = null)
    val snapshot = snapshotState ?: Settings.Snapshot()
    // Installed from a side effect, not during composition:
    // install() does a blocking cold load that must stay off the main
    // thread and out of remember{}.
    val botsDir = remember(context) { File(context.filesDir, "bots") }
    val store = remember(context) {
        BotCustomPresets.store() ?: BotPresetStore(botsDir)
    }
    LaunchedEffect(context) {
        if (BotCustomPresets.store() == null) {
            withContext(Dispatchers.IO) { BotCustomPresets.install(botsDir) }
        }
    }
    val presets by store.presets.collectAsState()
    val scope = rememberCoroutineScope()
    val supervisorClient = remember(context) { RuntimeSupervisorClient(context) }
    val realmState by remember(supervisorClient) {
        supervisorClient.observeRealmState()
    }.collectAsState(initial = RealmState.Idle())

    // One-time migration of a legacy advanced setup into a named preset.
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
    var nameRequest by remember { mutableStateOf<NameRequest?>(null) }
    var deleteRequest by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable(stateSaver = EditorSectionSaver) {
        mutableStateOf(EditorSection.BASICS)
    }
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
        section = EditorSection.BASICS
    }
    fun selectSaved(preset: BotPresetStore.SavedPreset) {
        target = EditorTarget.Saved(preset.id)
        working = preset.configuration
        section = EditorSection.BASICS
    }
    fun startNewDraft() {
        target = EditorTarget.NewDraft
        working = BotCustomConfiguration.fromProfile(BotProfiles.defaultProfile)
        presetNameDraft = ""
        section = EditorSection.BASICS
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
                        // Same failure handling as the store writes: a
                        // DataStore I/O error must surface as a dialog,
                        // not an uncaught exception in the scope.
                        runCatching {
                            settings.update {
                                it.copy(
                                    botProfileId = profile.id,
                                    botPopulationTarget = profile.selectedTarget,
                                    botSavedPresetId = null,
                                    botAdvancedEnabled = false,
                                    botAdvanced = BotAdvancedSettings.fromProfile(profile),
                                )
                            }
                        }.onFailure {
                            actionError = it.message ?: "The selection could not be applied"
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
                    runCatching {
                        settings.update {
                            it.copy(botSavedPresetId = t.presetId, botAdvancedEnabled = false)
                        }
                    }.onFailure {
                        actionError = it.message ?: "The selection could not be applied"
                    }
                }
                EditorTarget.NewDraft -> nameRequest = NameRequest(
                    title = "New preset name",
                    initial = presetNameDraft,
                ) { name -> presetNameDraft = name; saveWorkingAsPreset(name) }
                null -> Unit
            }
        }
    }

    val editorPane: @Composable (Modifier) -> Unit = { modifier ->
        val applied = when (val t = target) {
            is EditorTarget.Saved -> snapshot.botSavedPresetId == t.presetId
            is EditorTarget.BuiltIn ->
                snapshot.botSavedPresetId == null && snapshot.botProfileId == t.profileId
            EditorTarget.NewDraft, null -> false
        }
        EditorPane(
            target = target,
            working = working,
            dirty = dirty,
            section = section,
            onSection = { section = it },
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
            onWorking = { working = it },
            modifier = modifier,
        )
    }

    val appliedBuiltinId = snapshot.botProfileId.takeIf { snapshot.botSavedPresetId == null }
    val inUseName = currentSavedPreset?.name
        ?: BotProfiles.find(snapshot.botProfileId)?.displayName
        ?: BotProfiles.defaultProfile.displayName
    var customizing by rememberSaveable { mutableStateOf(false) }

    fun useBuiltIn(profile: com.pocketrealm.bots.BotProfile) {
        selectBuiltIn(profile)
        scope.launch {
            runCatching {
                settings.update {
                    it.copy(
                        botProfileId = profile.id,
                        botPopulationTarget = profile.selectedTarget,
                        botSavedPresetId = null,
                        botAdvancedEnabled = false,
                        botAdvanced = BotAdvancedSettings.fromProfile(profile),
                    )
                }
            }.onFailure { actionError = it.message ?: "The selection could not be applied" }
        }
    }
    fun useSaved(preset: BotPresetStore.SavedPreset) {
        selectSaved(preset)
        scope.launch {
            runCatching {
                settings.update { it.copy(botSavedPresetId = preset.id, botAdvancedEnabled = false) }
            }.onFailure { actionError = it.message ?: "The selection could not be applied" }
        }
    }

    Column(
        modifier.fillMaxWidth().testTag("bots-panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BotProfiles.experiencePresets.forEach { profile ->
                PresetCard(
                    profile = profile,
                    recommended = profile == BotProfiles.defaultProfile,
                    inUse = appliedBuiltinId == profile.id,
                    onClick = { useBuiltIn(profile) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        Text(
            if (running) "In use: $inUseName · changes apply when the realm next starts"
            else "In use: $inUseName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("bots-in-use"),
        )
        if (presets.isNotEmpty()) {
            Text("My presets", style = MaterialTheme.typography.labelLarge)
            presets.sortedWith(
                compareByDescending<BotPresetStore.SavedPreset> { it.favorite }
                    .thenBy { it.name.lowercase() },
            ).forEach { preset ->
                SavedPresetRow(
                    preset = preset,
                    selected = snapshot.botSavedPresetId == preset.id,
                    onSelect = { useSaved(preset) },
                    onToggleFavorite = { favorite ->
                        scope.launch {
                            runCatching { store.setFavorite(preset.id, favorite) }
                                .onFailure { actionError = it.message ?: "Could not update the preset" }
                        }
                    },
                    onRename = {
                        nameRequest = NameRequest("Rename preset", preset.name) { name ->
                            scope.launch {
                                runCatching { store.rename(preset.id, name.trim()) }
                                    .onFailure { actionError = it.message ?: "Could not rename the preset" }
                            }
                        }
                    },
                    onDuplicate = {
                        nameRequest = NameRequest("Duplicate “${preset.name}”", "${preset.name} copy") { name ->
                            scope.launch {
                                runCatching { store.duplicate(preset.id, name.trim()) }
                                    .onFailure { actionError = it.message ?: "Could not duplicate the preset" }
                            }
                        }
                    },
                    onExport = {
                        pendingExportId = preset.id
                        exportLauncher.launch("${preset.name}.botpreset.json")
                    },
                    onDelete = { deleteRequest = preset.id },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { customizing = !customizing },
                modifier = Modifier.testTag("bots-customize"),
            ) { Text(if (customizing) "Hide settings" else "Customize") }
            OutlinedButton(
                onClick = { startNewDraft(); customizing = true },
                modifier = Modifier.testTag("preset-new"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(" New preset")
            }
            TextButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                modifier = Modifier.testTag("preset-import"),
            ) { Text("Import") }
        }
        if (customizing) editorPane(Modifier.fillMaxWidth())
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
                            runCatching { settings.update { it.copy(botSavedPresetId = null) } }
                        .onFailure { failure ->
                            android.util.Log.w("BotsScreen", "preset-id clear failed", failure)
                        }
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("preset-saved-${preset.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onToggleFavorite(!preset.favorite) },
                modifier = Modifier.testTag("preset-favorite-${preset.id}"),
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
                        "${preset.configuration.activeBotPercent}% active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("preset-menu-${preset.id}")) {
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
private fun PresetCard(
    profile: com.pocketrealm.bots.BotProfile,
    recommended: Boolean,
    inUse: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (inUse) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.testTag("preset-builtin-${profile.id}"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                profile.displayName.substringBefore(" · "),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${profile.selectedTarget} bots",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                profile.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inUse || recommended) {
                Text(
                    if (inUse) "In use" else "Recommended",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Editor: header, section tabs, one section at a time, pinned actions
// ---------------------------------------------------------------------

@Composable
private fun EditorPane(
    target: EditorTarget?,
    working: BotCustomConfiguration,
    dirty: Boolean,
    section: EditorSection,
    onSection: (EditorSection) -> Unit,
    running: Boolean,
    savedPreset: BotPresetStore.SavedPreset?,
    applied: Boolean,
    onApply: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onWorking: (BotCustomConfiguration) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.testTag("bots-config-pane")) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
            SectionTabs(section, onSection)
            // key(section) disposes the previous tab's scroll state so each
            // section starts at the top and its scroll position is saved.
            key(section) {
                SectionContent(
                    section = section,
                    target = target,
                    working = working,
                    running = running,
                    savedPreset = savedPreset,
                    applied = applied,
                    onWorking = onWorking,
                )
            }
            EditorActionBar(target, dirty, onApply, onSave, onReset)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionTabs(selected: EditorSection, onSelect: (EditorSection) -> Unit) {
    ScrollableTabRow(
        selectedTabIndex = selected.ordinal,
        edgePadding = 4.dp,
        modifier = Modifier.fillMaxWidth().testTag("bots-section-tabs"),
    ) {
        EditorSection.entries.forEach { section ->
            Tab(
                selected = section == selected,
                onClick = { onSelect(section) },
                text = { Text(section.label) },
                modifier = Modifier.testTag("bots-section-tab-" + section.name.lowercase()),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionContent(
    section: EditorSection,
    target: EditorTarget?,
    working: BotCustomConfiguration,
    running: Boolean,
    savedPreset: BotPresetStore.SavedPreset?,
    applied: Boolean,
    onWorking: (BotCustomConfiguration) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (section) {
            EditorSection.BASICS -> BasicsContent(
                target = target,
                working = working,
                running = running,
                savedPreset = savedPreset,
                applied = applied,
                onWorking = onWorking,
            )
            EditorSection.POPULATION -> {
                PopulationSection(working, onWorking)
                HorizontalDivider()
                NearbySection(working, onWorking)
                AdvancedDisclosure(
                    title = "Advanced tuning",
                    support = "Startup pacing, teleport limits, accounts and load limits.",
                    tag = "bots-population-advanced",
                ) {
                    PopulationAdvancedSection(working, onWorking)
                }
            }
            EditorSection.BEHAVIOUR -> {
                SchedulingSection(working, onWorking)
                HorizontalDivider()
                TemperamentSection(working, onWorking)
                HorizontalDivider()
                LevelingSection(working, onWorking)
                AdvancedDisclosure(
                    title = "Advanced tuning",
                    support = "Login and re-roll timing for distant bots.",
                    tag = "bots-behaviour-advanced",
                ) {
                    BehaviourAdvancedSection(working, onWorking)
                }
            }
        }
    }
}

@Composable
private fun EditorActionBar(
    target: EditorTarget?,
    dirty: Boolean,
    onApply: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Column {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onReset,
                enabled = dirty,
                modifier = Modifier.weight(1f).testTag("result-reset"),
            ) { Text("Reset") }
            Button(
                onClick = onSave,
                enabled = target is EditorTarget.Saved || dirty || target is EditorTarget.NewDraft,
                modifier = Modifier.weight(1f).testTag("result-save"),
            ) { Text(if (target is EditorTarget.Saved) "Save" else "Save As") }
            Button(
                onClick = onApply,
                enabled = target != null,
                modifier = Modifier.weight(1f).testTag("result-apply"),
            ) {
                // Modified presets are saved by Apply itself, so the
                // primary action is never greyed out mid-edit.
                Text(if (dirty && target is EditorTarget.Saved) "Save & apply" else "Apply")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BasicsContent(
    target: EditorTarget?,
    working: BotCustomConfiguration,
    running: Boolean,
    savedPreset: BotPresetStore.SavedPreset?,
    applied: Boolean,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    Text(
        "${working.selectedTarget} bots",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("result-target"),
    )
    if (target != null && !applied) {
        Text(
            "Not in use yet. Press Apply to use it.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.testTag("result-not-applied"),
        )
    }
    Text(
        "About ${working.estimatedActiveBots()} active at a time; bots near you always are",
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
    savedPreset?.let {
        Text(
            "Selected preset: ${it.name} · revision ${it.revision.revision}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("result-selected-preset"),
        )
    }
    HorizontalDivider()
    SectionHeader("WORLD SIZE")
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

    SectionHeader("ACTIVITY")
    Text(
        "How often the bot AI thinks. Bots near you or in your group are always fully active.",
        style = MaterialTheme.typography.bodySmall,
    )
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

    SectionHeader("PLAYSTYLE")
    Text(
        "How bots behave around you: questing, grouping, wandering and inviting. " +
            "The Behaviour tab has a switch for each.",
        style = MaterialTheme.typography.bodySmall,
    )
    // Exclusive selection: the first matching non-custom preset wins, so
    // two presets with identical values can never light two chips at once.
    val selectedPlaystyle = BotPlaystylePreset.entries
        .firstOrNull { it != BotPlaystylePreset.CUSTOM && it.matches(working) }
    BotPlaystylePreset.entries.filter { it != BotPlaystylePreset.CUSTOM }.forEach { playstyle ->
        FilterChip(
            selected = playstyle == selectedPlaystyle,
            onClick = { onWorking(playstyle.applyTo(working)) },
            label = { Text(playstyle.label) },
            modifier = Modifier.fillMaxWidth()
                .testTag("playstyle-${playstyle.name.lowercase()}"),
        )
        Text(
            playstyle.summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (selectedPlaystyle == null) {
        FilterChip(
            selected = true,
            onClick = {},
            label = { Text("Custom") },
            modifier = Modifier.fillMaxWidth().testTag("playstyle-custom"),
        )
    }
}

@Composable
private fun PopulationSection(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader("POPULATION - AMOUNT OF BOTS")
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
    }
}

/** The Population tab's Advanced disclosure: pacing, teleport limits, accounts, adaptation. */
@Composable
private fun PopulationAdvancedSection(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        AccountsSection(working, onWorking)
        HorizontalDivider()
        AdaptationSection(working, onWorking)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NearbySection(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader("NEARBY SPAWNS - CLOSE TO PLAYERS")
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
        Text(
            "Fine-grained min/max cadence limits live behind this tab's Advanced tuning switch.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SchedulingSection(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader("AI & SCHEDULING")
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
    }
}

@Composable
private fun TemperamentSection(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader("TEMPERAMENT")
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
        SwitchRow("Bots may invite the player", working.allowPlayerInvites, "bots-invites") { value ->
            onWorking(working.copy(allowPlayerInvites = value))
        }
        SwitchRow("Limit background combat work", working.limitCombatActivity, "bots-limit-combat") { value ->
            onWorking(working.copy(limitCombatActivity = value))
        }
        SwitchRow("Bots speak on their own", working.allowBotChat, "bots-chat") { value ->
            onWorking(working.copy(allowBotChat = value))
        }
        Text(
            "Speaking on their own lets bots chat without being spoken to.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The Behaviour tab's Advanced disclosure: login lifecycle + rerandomize cadence. */
@Composable
private fun BehaviourAdvancedSection(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader("LOGIN LIFECYCLE")
        SwitchRow("Bots log in at realm start", working.loginAtStartup, "bots-login-startup") { value ->
            onWorking(working.copy(loginAtStartup = value))
        }
        SwitchRow("Bot joins with its player", working.loginWithPlayer, "bots-login-with-player") { value ->
            onWorking(working.copy(loginWithPlayer = value))
        }
        SectionHeader("RERANDOMIZE CADENCE")
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
    }
}

@Composable
private fun LevelingSection(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader("LEVELING")
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
    }
}

@Composable
private fun AccountsSection(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader("LOGIN & ACCOUNTS")
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
        Stepper("Generation batch", working.generationBatchSize, 1..10) { value ->
            onWorking(working.copy(generationBatchSize = value))
        }
        Stepper("Generation yield (ms)", working.generationYieldMs.toInt(), 0..5_000, step = 50) { value ->
            onWorking(working.copy(generationYieldMs = value.toLong()))
        }
    }
}

@Composable
private fun AdaptationSection(
    working: BotCustomConfiguration,
    onWorking: (BotCustomConfiguration) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader("ADAPTATION & PERFORMANCE")
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
}

private fun titleFor(target: EditorTarget?, working: BotCustomConfiguration): String = when (target) {
    is EditorTarget.BuiltIn -> BotProfiles.find(target.profileId)?.displayName ?: "Bots"
    is EditorTarget.Saved -> "Custom preset"
    EditorTarget.NewDraft -> "New preset"
    null -> "Bots"
}

/** Direct numeric population entry: not a %25 ladder, no 600 cap. */
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
                    error ?: "${BotPopulationPolicy.MIN_CUSTOM_TARGET} to " +
                        "${BotPopulationPolicy.MAX_SUPPORTED_TARGET} bots",
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

// ---------------------------------------------------------------------
// Row primitives: full-width rows, 48dp minimum touch targets
// ---------------------------------------------------------------------

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall)
}

/**
 * The per-tab verbose-tier disclosure (the Auto-login "Advanced timing"
 * idiom): a divider, a whole-row toggle, a support line, then the content
 * only while expanded. Hiding never changes a value.
 */
@Composable
private fun AdvancedDisclosure(
    title: String,
    support: String,
    tag: String,
    content: @Composable () -> Unit,
) {
    // Keyed on the caller-supplied tag: Population and Behaviour tabs reuse
    // the same composition slot via SectionContent's when(), so an unkeyed
    // rememberSaveable would leak one tab's expanded state into the other.
    var expanded by rememberSaveable(tag) { mutableStateOf(false) }
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp)
            .toggleable(value = expanded, onValueChange = { expanded = it }, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = expanded, onCheckedChange = null, modifier = Modifier.testTag(tag))
    }
    Text(support, style = MaterialTheme.typography.bodySmall)
    if (expanded) {
        content()
    }
}

@Composable
private fun SettingRow(
    label: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    SettingRow(label) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (value - step >= range.first) onChange(value - step) },
                enabled = value - step >= range.first,
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease $label")
            }
            Text(
                "$value",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(56.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(
                onClick = { if (value + step <= range.last) onChange(value + step) },
                enabled = value + step <= range.last,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase $label")
            }
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
    // The whole row toggles; the switch itself is display-only.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
