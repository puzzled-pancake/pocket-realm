package com.pocketrealm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketrealm.bots.BotActivityPreset
import com.pocketrealm.bots.BotAdvancedSettings
import com.pocketrealm.bots.BotCustomConfiguration
import com.pocketrealm.bots.BotPlaystylePreset
import com.pocketrealm.bots.BotPopulationPolicy
import com.pocketrealm.bots.BotPresetStore
import com.pocketrealm.bots.BotProfiles
import com.pocketrealm.desktop.DesktopAppModel
import java.io.File
import kotlinx.coroutines.launch

/** What the Bots editor is editing. */
private const val NAME_MAX_CHARS = 48

private sealed interface EditorTarget {
    data class BuiltIn(val profileId: String) : EditorTarget
    data class Saved(val presetId: String) : EditorTarget
    data object NewDraft : EditorTarget
}

/**
 * The Bots screen: population profiles, custom presets and per-preset AI
 * speech — the Android BotsScreen ported to desktop chrome (a preset rail
 * instead of a bottom sheet, the same shared BotPresetStore and the same
 * Apply semantics: the selection rides the settings snapshot into the next
 * world start).
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // the editor wiring: target + working + apply in one scope
fun BotsScreen(model: DesktopAppModel) {
    val snapshot by model.state.collectAsState()
    val settings by model.settings.collectAsState()
    val presets by model.presets.presets.collectAsState()
    val scope = rememberCoroutineScope()

    val running = snapshot.phase.let {
        it == com.pocketrealm.supervisor.RuntimePhase.WORLD_READY ||
            it == com.pocketrealm.supervisor.RuntimePhase.RUNNING ||
            it == com.pocketrealm.supervisor.RuntimePhase.PAUSED
    }

    var target by remember { mutableStateOf<EditorTarget?>(null) }
    var working by remember { mutableStateOf<BotCustomConfiguration?>(null) }
    var tab by remember { mutableStateOf(0) }
    var nameDialog by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    var confirmDelete by remember { mutableStateOf<BotPresetStore.SavedPreset?>(null) }

    fun selectBuiltIn(profileId: String) {
        target = EditorTarget.BuiltIn(profileId)
        working = BotCustomConfiguration.fromProfile(BotProfiles.require(profileId))
        tab = 0
    }

    fun selectSaved(presetId: String) {
        val preset = presets.firstOrNull { it.id == presetId } ?: return
        target = EditorTarget.Saved(presetId)
        working = preset.configuration
        tab = 0
    }

    fun base(): BotCustomConfiguration {
        if (working == null) selectBuiltIn(BotProfiles.defaultProfile.id)
        return working!!
    }

    fun edit(transform: (BotCustomConfiguration) -> BotCustomConfiguration) {
        working = transform(base())
    }

    fun dirty(): Boolean = when (val current = target) {
        is EditorTarget.BuiltIn ->
            working != BotCustomConfiguration.fromProfile(BotProfiles.require(current.profileId))
        is EditorTarget.Saved ->
            presets.firstOrNull { it.id == current.presetId }?.configuration != working
        else -> working != null
    }

    fun apply() {
        val current = target ?: return
        when (current) {
            is EditorTarget.BuiltIn -> {
                if (dirty()) {
                    nameDialog = "Save modified preset as" to { name ->
                        scope.launch {
                            val created = model.presets.create(name, base = null)
                            working?.let { model.presets.save(created.id, it) }
                            model.updateSettings {
                                it.copy(
                                    botSavedPresetId = created.id,
                                    botProfileId = "",
                                    botPopulationTarget = created.configuration.selectedTarget,
                                )
                            }
                            selectSaved(created.id)
                        }
                    }
                } else {
                    val profile = BotProfiles.require(current.profileId)
                    model.updateSettings {
                        it.copy(
                            botProfileId = profile.id,
                            botPopulationTarget = profile.selectedTarget,
                            botSavedPresetId = null,
                        )
                    }
                }
            }
            is EditorTarget.Saved -> {
                if (dirty()) scope.launch { model.presets.save(current.presetId, base()) }
                model.updateSettings {
                    it.copy(botSavedPresetId = current.presetId, botProfileId = "")
                }
            }
            EditorTarget.NewDraft -> {
                nameDialog = "New preset name" to { name ->
                    scope.launch {
                        val created = model.presets.create(name, base = null)
                        working?.let { model.presets.save(created.id, it) }
                        model.updateSettings { it.copy(botSavedPresetId = created.id, botProfileId = "") }
                        selectSaved(created.id)
                    }
                }
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PresetRail(
            modifier = Modifier.width(300.dp).fillMaxHeight(),
            presets = presets,
            selectedSavedId = (target as? EditorTarget.Saved)?.presetId,
            selectedBuiltInId = (target as? EditorTarget.BuiltIn)?.profileId,
            appliedSavedId = settings.botSavedPresetId,
            appliedBuiltInId = settings.botProfileId,
            onSelectBuiltIn = ::selectBuiltIn,
            onSelectSaved = ::selectSaved,
            onNew = {
                target = EditorTarget.NewDraft
                working = BotCustomConfiguration.fromProfile(BotProfiles.defaultProfile)
                tab = 0
            },
            onImport = {
                importPresetFile(model) { selectSaved(it) }
            },
            onExport = { preset ->
                exportPresetFile(model, preset)
            },
            onDelete = { confirmDelete = it },
            onRename = { preset ->
                nameDialog = "Rename preset" to { name ->
                    scope.launch { model.presets.rename(preset.id, name) }
                }
            },
            onDuplicate = { preset ->
                nameDialog = "Duplicate \"${preset.name}\"" to { name ->
                    scope.launch {
                        val copy = model.presets.duplicate(preset.id, name)
                        selectSaved(copy.id)
                    }
                }
            },
            onFavorite = { preset, favorite ->
                scope.launch { model.presets.setFavorite(preset.id, favorite) }
            },
        )
        Card(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val configuration = base()
                TabRow(selectedTabIndex = tab) {
                    listOf("Basics", "Population", "Behaviour", "AI").forEachIndexed { index, label ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (tab) {
                        0 -> BasicsTab(configuration, running, ::edit)
                        1 -> PopulationTab(configuration, ::edit)
                        2 -> BehaviourTab(configuration, ::edit)
                        else -> AiTab(configuration, ::edit)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = dirty(),
                        onClick = {
                            when (val current = target) {
                                is EditorTarget.BuiltIn -> selectBuiltIn(current.profileId)
                                is EditorTarget.Saved -> selectSaved(current.presetId)
                                else -> {}
                            }
                        },
                    ) { Text("Reset") }
                    OutlinedButton(onClick = {
                        nameDialog = (if (target is EditorTarget.Saved) "Save" else "Save As") to { name ->
                            scope.launch {
                                val existing = (target as? EditorTarget.Saved)?.presetId
                                    ?: model.presets.create(name, base = null).id
                                model.presets.save(existing, base())
                                selectSaved(existing)
                            }
                        }
                    }) { Text(if (target is EditorTarget.Saved) "Save" else "Save As") }
                    Button(enabled = target != null, onClick = ::apply) {
                        Text(if (dirty() && target is EditorTarget.Saved) "Save & apply" else "Apply")
                    }
                }
            }
        }
    }

    nameDialog?.let { (title, onConfirm) ->
        var name by remember { mutableStateOf("") } // 48-char cap mirrors the shared store rule
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { value -> name = value.take(NAME_MAX_CHARS) },
                    label = { Text("Preset name") },
                    supportingText = { Text("Use up to 48 letters, numbers, or spaces") },
                    isError = name.isNotBlank() && !BotPresetStore.isValidName(name),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = BotPresetStore.isValidName(name),
                    onClick = {
                        nameDialog = null
                        onConfirm(name.trim())
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${preset.name}\"?") },
            text = {
                Text(
                    "Realms already launched with this preset stay unchanged; new launches " +
                        "fall back to the built-in selection.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        model.presets.delete(preset.id)
                        if (settings.botSavedPresetId == preset.id) {
                            model.updateSettings { it.copy(botSavedPresetId = null) }
                        }
                        if ((target as? EditorTarget.Saved)?.presetId == preset.id) {
                            target = null
                            working = null
                        }
                    }
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun importPresetFile(model: DesktopAppModel, onImported: (String) -> Unit) {
    Thread {
        val frame = java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame } as? java.awt.Frame
        val dialog = java.awt.FileDialog(frame, "Import bot preset", java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".botpreset.json") }
        dialog.isVisible = true
        val file = dialog.directory?.let { dir -> dialog.file?.let { name -> File(dir, name) } }
        if (file != null && file.isFile) {
            val raw = file.readText(Charsets.UTF_8)
            kotlinx.coroutines.runBlocking {
                runCatching { model.presets.importJson(raw) }
                    .onSuccess { onImported(it.id) }
            }
        }
    }.apply { isDaemon = true }.start()
}

private fun exportPresetFile(model: DesktopAppModel, preset: com.pocketrealm.bots.BotPresetStore.SavedPreset) {
    Thread {
        val frame = java.awt.Window.getWindows().firstOrNull { it is java.awt.Frame } as? java.awt.Frame
        val dialog = java.awt.FileDialog(frame, "Export bot preset", java.awt.FileDialog.SAVE)
        dialog.file = "${preset.name}.botpreset.json"
        dialog.isVisible = true
        val target = dialog.directory?.let { dir -> dialog.file?.let { name -> File(dir, name) } }
        if (target != null) {
            target.writeText(model.presets.exportJson(preset), Charsets.UTF_8)
        }
    }.apply { isDaemon = true }.start()
}

