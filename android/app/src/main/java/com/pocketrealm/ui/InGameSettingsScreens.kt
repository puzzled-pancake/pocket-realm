package com.pocketrealm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketrealm.client.X86DirectWineRuntime
import com.pocketrealm.ingame.ConfigWtfCodec
import com.pocketrealm.ingame.InGameSettingsEditor
import com.pocketrealm.ingame.SavedVariablesCodec
import com.pocketrealm.ingame.WowSettingBackend
import com.pocketrealm.ingame.WowSettingControl
import com.pocketrealm.ingame.WowSettingDefinition
import com.pocketrealm.ingame.WowSettingSection
import com.pocketrealm.ingame.WowVanillaSettingsCatalog
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Nested-graph routes for the in-game settings sub-menu (AddonRoutes pattern). */
internal object InGameRoutes {
    const val GRAPH = "settings/ingame"
    const val HUB = "settings/ingame/hub"
    const val GRAPHICS = "settings/ingame/graphics"
    const val SOUND = "settings/ingame/sound"
    const val INTERFACE = "settings/ingame/interface"
    const val INTERFACE_ADVANCED = "settings/ingame/interface-advanced"
    const val BINDINGS = "settings/ingame/bindings"

    fun routeFor(section: WowSettingSection): String = when (section) {
        WowSettingSection.GRAPHICS -> GRAPHICS
        WowSettingSection.SOUND -> SOUND
        WowSettingSection.INTERFACE -> INTERFACE
        WowSettingSection.INTERFACE_ADVANCED -> INTERFACE_ADVANCED
    }

    fun titleFor(route: String): String = when (route) {
        HUB -> "In-Game Settings"
        GRAPHICS -> "Graphics"
        SOUND -> "Sound"
        INTERFACE -> "Interface"
        INTERFACE_ADVANCED -> "Advanced Interface"
        BINDINGS -> "Key Bindings"
        else -> "In-Game Settings"
    }
}

/** Pure presentation for the hub status card (unit-tested host contract). */
internal object InGameSettingsPresenter {
    fun statusLine(activity: InGameSettingsEditor.ClientActivity): String = when (activity) {
        InGameSettingsEditor.ClientActivity.STOPPED -> "Changes apply immediately"
        InGameSettingsEditor.ClientActivity.RUNNING -> "Client running"
        InGameSettingsEditor.ClientActivity.LAUNCHING -> "Launching"
        InGameSettingsEditor.ClientActivity.UNKNOWN -> "Checking client state…"
    }

    fun queuedLine(deliverable: Int): String? =
        if (deliverable > 0) {
            if (deliverable == 1) "1 queued change applies next launch"
            else "$deliverable queued changes apply next launch"
        } else null

    fun blockedLine(blocked: Int): String? =
        if (blocked > 0) "$blocked blocked (audio is off)" else null

    fun summaryLine(applied: Int, superseded: Int, blocked: Int): String =
        "$applied applied · $superseded superseded · $blocked blocked"
}

@Composable
internal fun rememberInGameRuntime(): X86DirectWineRuntime {
    val context = LocalContext.current
    val runtime = remember { X86DirectWineRuntime(context) }
    DisposableEffect(Unit) {
        onDispose { runCatching { runtime.close() } }
    }
    return runtime
}

/**
 * Shared editor state: client activity polling, loaded file values, and the
 * direct-edit/stage dispatch (§5.1) with a surfaced error line.
 */
internal class InGameSettingsState(
    val context: android.content.Context,
    val runtime: X86DirectWineRuntime,
    val editor: InGameSettingsEditor,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var activity by mutableStateOf(InGameSettingsEditor.ClientActivity.STOPPED)
        private set
    var configValues by mutableStateOf<Map<String, String>?>(null)
        private set
    var uvarAccounts by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedAccount by mutableStateOf<String?>(null)
    var uvarValues by mutableStateOf<Map<String, SavedVariablesCodec.Value>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val stopped: Boolean get() = activity == InGameSettingsEditor.ClientActivity.STOPPED

    fun refresh() {
        scope.launch {
            activity = runCatching { editor.clientActivity(runtime) }.getOrDefault(
                InGameSettingsEditor.ClientActivity.UNKNOWN,
            )
            configValues = editor.readConfigValues()
            uvarAccounts = editor.uvarAccounts()
            if (selectedAccount == null) selectedAccount = uvarAccounts.firstOrNull()
            uvarValues = selectedAccount?.let { editor.readUvarValues(it) }
        }
    }

    fun pollWhileVisible() {
        scope.launch {
            while (isActive) {
                activity = runCatching { editor.clientActivity(runtime) }.getOrDefault(
                    InGameSettingsEditor.ClientActivity.UNKNOWN,
                )
                delay(1_000)
            }
        }
    }

    fun applySetting(definition: WowSettingDefinition, storedValue: String?) {
        val settings = Settings(context)
        scope.launch {
            runCatching {
                val snapshot = settings.flow.first()
                if (stopped) {
                    editor.directEdit(
                        runtime,
                        listOf(
                            InGameSettingsEditor.DirectEdit(
                                family = when (definition.backend) {
                                    WowSettingBackend.CVAR ->
                                        InGameSettingsEditor.DirectEdit.Family.CVAR
                                    WowSettingBackend.UVAR ->
                                        InGameSettingsEditor.DirectEdit.Family.UVAR
                                    WowSettingBackend.FUNCTION ->
                                        error("\"${definition.label}\" is not file-backed")
                                },
                                queueRemovalId = definition.id,
                                journalKey = definition.key,
                                account = if (definition.backend == WowSettingBackend.UVAR) {
                                    selectedAccount
                                } else null,
                                setting = definition,
                                storedValue = storedValue,
                            ),
                        ),
                    )
                } else {
                    val scopeId = when (definition.backend) {
                        WowSettingBackend.CVAR -> "config"
                        else -> checkNotNull(selectedAccount) {
                            "log in once in game to edit saved variables"
                        }
                    }
                    editor.stageOverride(definition, storedValue, scopeId, snapshot)
                }
            }.onFailure { error = it.message ?: it.javaClass.simpleName }
            refresh()
        }
    }

    fun clearError() {
        error = null
    }
}

@Composable
internal fun rememberInGameSettingsState(): InGameSettingsState {
    val context = LocalContext.current
    val runtime = rememberInGameRuntime()
    val scope = rememberCoroutineScope()
    return remember {
        InGameSettingsState(
            context = context.applicationContext,
            runtime = runtime,
            editor = InGameSettingsEditor(context.applicationContext),
            scope = scope,
        )
    }.also { state ->
        LaunchedEffect(Unit) {
            state.refresh()
            state.pollWhileVisible()
        }
    }
}

// ------------------------------------------------------------------ hub

@Composable
internal fun InGameSettingsHubScreen(
    onOpenRoute: (String) -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val snap by settings.flow.collectAsState(initial = Settings.Snapshot())
    val state = rememberInGameSettingsState()
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<InGameSettingsEditor.ReconcileSummary?>(null) }

    LaunchedEffect(state.activity, snap.gameSettings.totalQueued) {
        if (state.activity == InGameSettingsEditor.ClientActivity.STOPPED &&
            snap.gameSettings.totalQueued > 0
        ) {
            // The reconcile itself drops delivered entries, which re-triggers
            // this effect; accumulate into the running summary so a later
            // blocked-only pass (no new information - the blocked line
            // renders separately) cannot erase the applied/superseded terms.
            val result = runCatching { state.editor.reconcile(snap) }.getOrNull()
                ?: return@LaunchedEffect
            summary = summary?.let { prior ->
                InGameSettingsEditor.ReconcileSummary(
                    applied = prior.applied + result.applied,
                    superseded = prior.superseded + result.superseded,
                    blocked = result.blocked,
                )
            } ?: result
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HubStatusCard(
            activity = state.activity,
            queued = snap.gameSettings.totalQueued,
            blocked = state.editor.blockedQueueKeys(snap).size,
            summary = summary,
            onDiscardAll = {
                scope.launch { runCatching { state.editor.discardAll() } }
            },
        )
        listOf(
            WowSettingSection.GRAPHICS,
            WowSettingSection.SOUND,
            WowSettingSection.INTERFACE,
            WowSettingSection.INTERFACE_ADVANCED,
        ).forEach { section ->
            HubCategoryRow(
                title = section.hubLabel,
                description = section.hubDescription,
                count = WowVanillaSettingsCatalog.forSection(section).size,
                tag = "ingame-hub-" + section.name.lowercase(),
                onClick = { onOpenRoute(InGameRoutes.routeFor(section)) },
            )
        }
        HubCategoryRow(
            title = "Key Bindings",
            description = "Stock commands, slots, and controller-overlay keys",
            count = com.pocketrealm.client.WowVanillaBindingCatalog.userFacing.size,
            tag = "ingame-hub-bindings",
            onClick = { onOpenRoute(InGameRoutes.BINDINGS) },
        )
    }
}

@Composable
private fun HubStatusCard(
    activity: InGameSettingsEditor.ClientActivity,
    queued: Int,
    blocked: Int,
    summary: InGameSettingsEditor.ReconcileSummary?,
    onDiscardAll: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("ingame-hub-status")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                InGameSettingsPresenter.statusLine(activity),
                style = MaterialTheme.typography.titleSmall,
            )
            val deliverable = queued - blocked
            if (deliverable > 0 || blocked > 0) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        InGameSettingsPresenter.queuedLine(deliverable)?.let { line ->
                            Text(
                                line,
                                modifier = Modifier.testTag("ingame-hub-queued"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        InGameSettingsPresenter.blockedLine(blocked)?.let { line ->
                            Text(
                                line,
                                modifier = Modifier.testTag("ingame-hub-blocked"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onDiscardAll,
                        modifier = Modifier.testTag("ingame-hub-discard"),
                    ) { Text("Discard all") }
                }
            }
            summary?.let {
                Text(
                    InGameSettingsPresenter.summaryLine(it.applied, it.superseded, it.blocked),
                    modifier = Modifier.testTag("ingame-hub-summary"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HubCategoryRow(
    title: String,
    description: String,
    count: Int,
    tag: String,
    onClick: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag(tag)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -------------------------------------------------------- category screens

@Composable
internal fun InGameCategoryScreen(section: WowSettingSection) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val snap by settings.flow.collectAsState(initial = Settings.Snapshot())
    val state = rememberInGameSettingsState()
    val definitions = remember { WowVanillaSettingsCatalog.forSection(section) }
    val groups = remember { WowVanillaSettingsCatalog.groups(section) }
    var selectedGroup by remember { mutableStateOf(groups.firstOrNull() ?: "") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (section == WowSettingSection.SOUND && snap.audioMode == Settings.AudioMode.OFF) {
            Text(
                "Pocket Realm audio is off — turn on Sound in Settings to change these. " +
                    "The game stays silent regardless of these switches until then.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp).testTag("ingame-sound-audio-off"),
            )
        }
        if (state.configValues != null &&
            definitions.any { it.backend == WowSettingBackend.UVAR } &&
            state.uvarAccounts.isEmpty()
        ) {
            Text(
                "Log in once in game to create this account's saved settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp).testTag("ingame-absent-backing"),
            )
        }
        if (state.error != null) {
            Text(
                state.error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("ingame-error"),
            )
        }
        if (!state.stopped && snap.gameSettings.totalQueued > 0) {
            Text(
                InGameSettingsPresenter.queuedLine(snap.gameSettings.totalQueued) ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp).testTag("ingame-queued-banner"),
            )
        }
        ScrollableTabRow(
            selectedTabIndex = groups.indexOf(selectedGroup).coerceAtLeast(0),
            edgePadding = 4.dp,
            modifier = Modifier.fillMaxWidth().testTag("ingame-section-tabs"),
        ) {
            groups.forEach { group ->
                Tab(
                    selected = group == selectedGroup,
                    onClick = { selectedGroup = group },
                    text = { Text(group) },
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            definitions.filter { it.group == selectedGroup }.forEach { definition ->
                key(definition.id) {
                    SettingDefinitionRow(
                        definition = definition,
                        snap = snap,
                        state = state,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingDefinitionRow(
    definition: WowSettingDefinition,
    snap: Settings.Snapshot,
    state: InGameSettingsState,
) {
    val queuedEntry = when (definition.backend) {
        WowSettingBackend.CVAR -> snap.gameSettings.cvar[definition.id]
        WowSettingBackend.UVAR -> snap.gameSettings.uvar[definition.id]
        WowSettingBackend.FUNCTION -> null
    }
    val fileValue: String? = when (definition.backend) {
        WowSettingBackend.CVAR -> state.configValues?.get(definition.key)
        WowSettingBackend.UVAR -> state.uvarValues?.get(definition.key)?.let { value ->
            when (value) {
                is SavedVariablesCodec.Value.Str -> value.raw
                is SavedVariablesCodec.Value.Num -> value.raw
                is SavedVariablesCodec.Value.Bool -> if (value.raw) "1" else "0"
                SavedVariablesCodec.Value.Nil -> null
            }
        }
        WowSettingBackend.FUNCTION -> null
    }
    // The queued value is the display truth while staged; otherwise the file
    // is; a row with neither shows its verified default or "Default".
    val stored: String? = queuedEntry?.value ?: fileValue
    val defaulted: Boolean = queuedEntry == null && fileValue == null
    val defaultStored = definition.defaultValue

    val requirementBlocking = definition.requires.firstOrNull { requirement ->
        val gate = WowVanillaSettingsCatalog.byId(requirement.id)
        val gateQueued = gate?.let {
            when (it.backend) {
                WowSettingBackend.CVAR -> snap.gameSettings.cvar[it.id]?.value
                    ?: state.configValues?.get(it.key) ?: it.defaultValue
                WowSettingBackend.UVAR -> snap.gameSettings.uvar[it.id]?.value
                    ?: state.uvarValues?.get(it.key).let { raw -> raw?.rendered() }
                    ?: it.defaultValue
                WowSettingBackend.FUNCTION -> it.defaultValue
            }
        }
        gate == null || gateQueued == requirement.notValue
    }
    val soundGated = definition.section == WowSettingSection.SOUND &&
        snap.audioMode == Settings.AudioMode.OFF
    val uvarGated = definition.backend == WowSettingBackend.UVAR && state.selectedAccount == null
    val enabled = definition.fixedReason == null &&
        definition.backend != WowSettingBackend.FUNCTION &&
        !soundGated && !uvarGated && requirementBlocking == null

    fun commit(nextStored: String?) {
        state.applySetting(definition, nextStored)
    }

    when (definition.control) {
        WowSettingControl.TOGGLE -> {
            val checked = when {
                stored != null -> if (definition.inverse) stored == "0" else stored == "1"
                defaultStored != null ->
                    if (definition.inverse) defaultStored == "0" else defaultStored == "1"
                else -> false
            }
            val support = buildString {
                if (queuedEntry != null) append("Queued — applies next launch")
                if (defaulted && defaultStored == null) {
                    if (isNotEmpty()) append(" · ")
                    append("Default (in-game value)")
                }
                requirementBlocking?.let {
                    if (isNotEmpty()) append(" · ")
                    append("Requires ${it.id.substringAfterLast('.').replaceFirstChar(Char::titlecase)}")
                }
                definition.fixedReason?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
                if (soundGated) {
                    if (isNotEmpty()) append(" · ")
                    append("Pocket Realm audio is off")
                }
                if (uvarGated) {
                    if (isNotEmpty()) append(" · ")
                    append("Log in once in game")
                }
            }
            SwitchRow(
                label = definition.label,
                checked = checked,
                tag = "ingame-${definition.id}",
                support = support.ifEmpty { null },
                enabled = enabled,
                onChange = { next ->
                    val nextStored = if (definition.inverse) {
                        if (next) "0" else "1"
                    } else if (next) "1" else "0"
                    commit(nextStored)
                },
            )
        }
        WowSettingControl.SLIDER -> {
            val min = definition.min ?: 0f
            val max = definition.max ?: 1f
            val step = definition.step ?: 0.1f
            val numeric = stored?.toFloatOrNull() ?: defaultStored?.toFloatOrNull()
            val support = if (queuedEntry != null) "Queued — applies next launch"
            else if (defaulted && defaultStored == null) "Default (in-game value)"
            else null
            FloatSteppedSlider(
                label = definition.label,
                value = numeric,
                valueText = numeric?.let { trimFloat(it) }
                    ?: (defaultStored?.toFloatOrNull()?.let { trimFloat(it) } ?: "Default"),
                range = min..max,
                step = step,
                tag = "ingame-${definition.id}",
                enabled = enabled,
                onCommit = { commit(ConfigWtfCodec.formatValue(it)) },
            )
            if (support != null) {
                Text(
                    support,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (definition.fixedReason != null) {
                Text(
                    definition.fixedReason,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WowSettingControl.CHOICE -> {
            val choices = definition.choices.orEmpty()
            val selectedChoice = choices.firstOrNull { it.stored == stored }
                ?: choices.firstOrNull { it.stored == defaultStored }
            ChoiceRow(
                label = definition.label,
                selectedId = selectedChoice?.id,
                choices = choices.map { it.id to it.label },
                tag = "ingame-${definition.id}",
                support = if (queuedEntry != null) "Queued — applies next launch" else null,
                enabled = enabled,
                onSelect = { id ->
                    choices.firstOrNull { it.id == id }?.let { commit(it.stored) }
                },
            )
            if (definition.fixedReason != null) {
                Text(
                    definition.fixedReason,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun SavedVariablesCodec.Value.rendered(): String = when (this) {
    is SavedVariablesCodec.Value.Str -> raw
    is SavedVariablesCodec.Value.Num -> raw
    is SavedVariablesCodec.Value.Bool -> if (raw) "1" else "0"
    SavedVariablesCodec.Value.Nil -> "nil"
}

private fun trimFloat(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
