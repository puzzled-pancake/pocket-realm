package com.pocketrealm.ingame

import android.content.Context
import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ManagedClientStore
import com.pocketrealm.client.WowBindingDefinition
import com.pocketrealm.client.X86DirectWineRuntime
import com.pocketrealm.storage.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** the [ActiveFlavor] lane suffix naming the editor's lease gate. */
private const val INGAME_EDITOR_LANE =
    "the in-game settings editor resolves the qualified lane"

/**
 * UI-process editor facade. While the client is stopped it
 * direct-edits the WTF files under the shared generation lease and the
 * exclusive edit lock (re-checking the stopped state *inside* the lock so a
 * prepare that just started is detected before any write); while the client
 * runs (or a prepare/launch is in flight) every change is staged into the
 * DataStore queue instead.
 */
internal class InGameSettingsEditor(private val context: Context) {

    /** Client activity as the editor understands it. */
    enum class ClientActivity { STOPPED, RUNNING, LAUNCHING, UNKNOWN }

    data class ReconcileSummary(val applied: Int, val superseded: Int, val blocked: Int)

    private val settings = Settings(context)

    suspend fun clientActivity(runtime: X86DirectWineRuntime?): ClientActivity {
        val raw = withContext(Dispatchers.IO) {
            runCatching { runtime?.statusCurrentJson() }.getOrNull()
        } ?: return ClientActivity.UNKNOWN
        return runCatching {
            val status = JSONObject(raw)
            when {
                status.optBoolean("prepareInFlight") || status.optBoolean("preparedTicket") ->
                    ClientActivity.LAUNCHING
                status.optString("state") == "RUNNING" -> ClientActivity.RUNNING
                else -> {
                    val state = status.optString("state")
                    val drained = status.optBoolean("processTreeDrained", true)
                    val finished = status.optBoolean("runtimeFinished", true)
                    val terminal = state in setOf("EXITED", "FORCE_STOPPED", "FAILED")
                    if (terminal && drained && finished) ClientActivity.STOPPED else ClientActivity.RUNNING
                }
            }
        }.getOrDefault(ClientActivity.UNKNOWN)
    }

    /** Enforced Config keys for the *current* persisted launch conditions. */
    fun enforcedForCurrentConditions(snapshot: Settings.Snapshot): List<ConfigWtfCodec.EnforcedLine> {
        val renderer = snapshot.effectiveRenderer().id.lowercase()
        val displaySelection = snapshot.displaySelection().let { selection ->
            com.pocketrealm.client.ClientDisplayCapabilities
                .physicalLandscapeBounds(context)
                .let { (width, height) -> selection.profile.resolveFor(width, height) }
        }
        return ManagedConfigPolicy.enforcedKeys(
            ManagedConfigPolicy.LaunchConditions(
                renderer = renderer,
                resolution = displaySelection.resolution,
                gameMaximized = snapshot.displaySelection().profile.gameMaximized,
                frameCap = snapshot.clientFrameCap,
                uiScale = snapshot.effectiveClientUiScale(displaySelection.height),
                audioMode = snapshot.audioMode.name.lowercase(),
                realmLoopback = snapshot.runtimeMode == com.pocketrealm.supervisor.RuntimeMode.LOCAL,
                soundChannelsEnabled = snapshot.tweaks.soundChannelsEnabled,
                soundChannels = snapshot.tweaks.soundChannels,
            ),
        )
    }

    /**
     * the ACTIVE flavor's catalog lookups. The editor was wired to
     * the vanilla catalogs at every call site; each now resolves the active
     * flavor's authored catalog (vanilla/tbc/wotlk) through
     * [WowFlavorCatalogs], so a tbc/wotlk lane's queued entries, reconcile
     * passes, and binding stages answer against THAT flavor's catalog.
     */
    private fun settingsById(): (String) -> WowSettingDefinition? =
        WowFlavorCatalogs.settingsById(ActiveFlavor.resolve(INGAME_EDITOR_LANE))

    private fun bindingFind(): (String) -> WowBindingDefinition? =
        WowFlavorCatalogs.bindingFind(ActiveFlavor.resolve(INGAME_EDITOR_LANE))

    /** Queued entries whose backing key the app enforces right now. */
    fun blockedQueueKeys(snapshot: Settings.Snapshot): Set<String> {
        val enforced = enforcedForCurrentConditions(snapshot).map { it.key }.toSet()
        val byId = settingsById()
        return snapshot.gameSettings.cvar.keys.filter { id ->
            byId(id)?.key in enforced
        }.toSet()
    }

    // ---------------------------------------------------------------- reads

    // Lease acquisition, file reads/writes, and fsync run off the main
    // dispatcher; every editor entry point funnels through this helper.
    private suspend fun <T> underLease(block: suspend (File) -> T): T? {
        val leased = withContext(Dispatchers.IO) {
            runCatching {
                // this UI-process facade resolves
                // the ACTIVE lane through the [ActiveFlavor] machine — the
                // same in-memory selection the Home switcher seeds — so a
                // second qualified flavor routes the lease to the selected
                // lane's runtime instead of `qualified().single()` throwing
                // (the runCatching boundary keeps an ambiguous unselected
                // registry state a fail-soft "no lease", never a crash).
                ManagedClientStore(context).acquireRuntime(
                    ActiveFlavor.resolve(INGAME_EDITOR_LANE).clientId,
                )
            }.getOrNull()
        } ?: return null
        return leased.use { held ->
            withContext(Dispatchers.IO) { block(held.client.root) }
        }
    }

    suspend fun readConfigValues(): Map<String, String>? = underLease { root ->
        val file = InGameSettingsFiles.configFile(root)
        if (file.isFile) ConfigWtfCodec.parse(file.readText(Charsets.UTF_8)) else emptyMap()
    }

    suspend fun readUvarValues(account: String): Map<String, SavedVariablesCodec.Value>? = underLease { root ->
        // a non-vanilla layout has NO single-file uvar route — the
        // read degrades to empty instead of reaching the fail-loud backstop.
        if (!InGameSettingsFiles.activeLayout().supportsUvars) return@underLease emptyMap()
        val file = InGameSettingsFiles.accountSavedVariables(root, account)
        if (file.isFile) SavedVariablesCodec.parse(file.readText(Charsets.UTF_8)) else emptyMap()
    }

    suspend fun readBindings(scope: String): Map<String, String>? = underLease { root ->
        val file = InGameSettingsFiles.bindingsForScope(root, scope)
        if (file.isFile) BindingsFileCodec.parse(file.readText(Charsets.UTF_8)) else emptyMap()
    }

    suspend fun uvarAccounts(): List<String> =
        underLease { root -> InGameSettingsFiles.accountsWithSavedVariables(root) }.orEmpty()

    suspend fun bindingScopes(): List<Pair<String, String>> = underLease { root ->
        InGameSettingsFiles.accountsWithBindings(root).map { it to "Account" } +
            InGameSettingsFiles.characterScopesWithBindings(root).map { it to "Character" }
    }.orEmpty()

    // ---------------------------------------------------------- direct edits

    /**
     * Apply file edits while the client is stopped. The stopped state is
     * re-checked *inside* the edit lock; only then is the queued entry for
     * the same setting dropped (newest explicit edit wins) and the
     * files written atomically - a failed re-check aborts with the queue
     * untouched instead of silently discarding the user's edit. Each applied
     * edit is then journaled so the master-sound transition rule stays sound.
     */
    suspend fun directEdit(
        runtime: X86DirectWineRuntime?,
        edits: List<DirectEdit>,
    ) {
        require(edits.isNotEmpty()) { "no edits to apply" }
        underLease { root ->
            InGameSettingsEditLock.acquire(stableClientRoot()).use {
                val activity = clientActivity(runtime)
                check(activity == ClientActivity.STOPPED) {
                    "the client is no longer stopped; change applies next launch instead"
                }
                settings.mutateGameSettings { queue, _ ->
                    var next = queue
                    edits.forEach { edit -> next = next.without(edit.queueRemovalId, edit.family) }
                    next
                }
                edits.forEach { edit -> edit.applyTo(root) }
            }
        } ?: error("managed client is unavailable")
        val keys = edits.map { it.journalKey }.distinct()
        keys.forEach { key -> settings.journalGameSettingsDirectEdit(key) }
    }

    /** One direct file edit, with everything the apply step needs. */
    data class DirectEdit(
        val family: Family,
        /** The setting id (cvar/uvar) or binding command id to drop from the queue. */
        val queueRemovalId: String,
        /** The journal key (CVar/uvar name or command id). */
        val journalKey: String,
        /** uvar account scope, uvar family only. */
        val account: String? = null,
        val setting: WowSettingDefinition? = null,
        val storedValue: String? = null,
        val bindingScope: String? = null,
        val command: String? = null,
        val primary: String? = null,
        val secondary: String? = null,
    ) {
        enum class Family { CVAR, UVAR, BINDING }

        fun applyTo(root: File) {
            when (family) {
                Family.CVAR -> {
                    val definition = checkNotNull(setting)
                    val file = InGameSettingsFiles.configFile(root)
                    val base = if (file.isFile) file.readText(Charsets.UTF_8) else null
                    val writes = buildList {
                        add(ConfigWtfCodec.UserOverride(definition.key, storedValue))
                        definition.pairedWrites.forEach { (companion, factor) ->
                            storedValue?.toFloatOrNull()?.times(factor)?.let { multiplied ->
                                add(ConfigWtfCodec.UserOverride(
                                    companion, ConfigWtfCodec.formatValue(multiplied),
                                ))
                            }
                        }
                    }
                    InGameSettingsFiles.writeAtomic(
                        file, ConfigWtfCodec.merge(base, emptyList(), writes).text,
                    )
                }
                Family.UVAR -> {
                    val definition = checkNotNull(setting)
                    val file = InGameSettingsFiles.accountSavedVariables(
                        root, checkNotNull(account),
                    )
                    check(file.isFile) { "log in once in game to edit saved variables" }
                    val updated = SavedVariablesCodec.assign(
                        file.readText(Charsets.UTF_8), definition.key, storedValue,
                        numberForm = definition.uvarValueForm == WowUvarValueForm.NUMBER,
                    ) ?: error("\"${definition.key}\" is not editable outside the game")
                    InGameSettingsFiles.writeAtomic(file, updated)
                }
                Family.BINDING -> {
                    val commandId = checkNotNull(command)
                    val file = InGameSettingsFiles.bindingsForScope(
                        root, checkNotNull(bindingScope),
                    )
                    check(file.isFile) { "log in once in game to edit key bindings" }
                    var text = file.readText(Charsets.UTF_8)
                    BindingsFileCodec.keysForCommand(text, commandId).forEach { old ->
                        text = BindingsFileCodec.assign(text, old, null)
                    }
                    listOfNotNull(primary, secondary).forEach { key ->
                        text = BindingsFileCodec.assign(text, key, commandId)
                    }
                    InGameSettingsFiles.writeAtomic(file, text)
                }
            }
        }
    }

    // -------------------------------------------------------------- staging

    /** Stage a setting change for the next launch (client running/launching). */
    suspend fun stageOverride(
        definition: WowSettingDefinition,
        storedValue: String?,
        scope: String,
        snapshot: Settings.Snapshot,
    ) {
        check(definition.fixedReason == null && definition.backend != WowSettingBackend.FUNCTION) {
            "\"${definition.label}\" cannot be changed outside the game"
        }
        if (definition.backend == WowSettingBackend.CVAR) {
            val enforced = enforcedForCurrentConditions(snapshot).map { it.key }.toSet()
            check(definition.key !in enforced) {
                "\"${definition.label}\" is currently managed by Pocket Realm"
            }
        }
        if (definition.backend == WowSettingBackend.UVAR) {
            // the single-file route is vanilla-only — refuse honestly
            // before the layout backstop can throw mid-stage.
            check(InGameSettingsFiles.activeLayout().supportsUvars) {
                "\"${definition.label}\" is a vanilla-lane saved variable — the " +
                    "active flavor's client writes per-addon SavedVariables and " +
                    "has no in-game editor for them"
            }
            val scopeFileExists = underLease { root ->
                InGameSettingsFiles.activeLayout()
                    .accountHasUvars(root, scope)
            } == true
            check(scopeFileExists) { "log in once in game to edit saved variables" }
        }
        settings.mutateGameSettings { queue, revision ->
            val entry = storedValue?.let { QueuedOverride(it, revision, scope) }
            when (definition.backend) {
                WowSettingBackend.CVAR -> queue.copy(
                    cvar = mutateMap(queue.cvar, definition.id, entry),
                )
                WowSettingBackend.UVAR -> queue.copy(
                    uvar = mutateMap(queue.uvar, definition.id, entry),
                )
                WowSettingBackend.FUNCTION -> queue
            }
        }
    }

    /** Stage a binding change for the next launch. */
    suspend fun stageBindingOverride(
        command: String,
        primary: String?,
        secondary: String?,
        scope: String,
    ) {
        check(bindingFind()(command) != null) { "unknown binding command: $command" }
        val scopeFileExists = underLease { root ->
            InGameSettingsFiles.bindingsForScope(root, scope).isFile
        } == true
        check(scopeFileExists) { "log in once in game to edit that scope's bindings" }
        settings.mutateGameSettings { queue, revision ->
            queue.copy(bindings = queue.bindings + (
                command to BindingOverride(primary, secondary, revision, scope)
                ))
        }
    }

    suspend fun discardAll() {
        settings.mutateGameSettings { _, _ -> WowGameSettingsConfig() }
    }

    // ------------------------------------------------------------ reconcile

    /**
     * Editor-reopened-while-stopped reconcile: drop delivered
     * entries, classify applied vs superseded against the live files, keep
     * everything else (including blocked entries, which stay visibly
     * queued). Returns the three-term summary the hub renders.
     */
    suspend fun reconcile(snapshot: Settings.Snapshot): ReconcileSummary {
        val record = underLease { root ->
            runCatching {
                JSONObject(File(root, "managed-safe-profile.json").readText(Charsets.UTF_8))
            }.getOrNull()
        }
        val delivered = GameSettingsDeliveryEntry.listFromJson(record?.opt("applied_overrides"))
            .associateBy { "${it.key}\u0000${it.scope}" }
        if (delivered.isEmpty()) {
            return ReconcileSummary(0, 0, blockedQueueKeys(snapshot).size)
        }
        val configValues = readConfigValues().orEmpty()
        var applied = 0
        var superseded = 0
        val droppedCvar = mutableSetOf<String>()
        val droppedUvar = mutableSetOf<String>()
        val droppedBindings = mutableSetOf<String>()

        fun classify(key: String, scope: String, revision: Long, currentValue: String?): Boolean {
            val prior = delivered["$key\u0000$scope"] ?: return false
            if (revision <= prior.revision) {
                // A delivered removal ("") matches an absent line (null).
                if ((currentValue ?: "") != prior.value) superseded++ else applied++
                return true
            }
            return false
        }

        val cvarById = settingsById()
        snapshot.gameSettings.cvar.forEach { (id, entry) ->
            val definition = cvarById(id) ?: run {
                droppedCvar += id; return@forEach
            }
            val current = configValues[definition.key]
            if (classify(id, entry.scope, entry.revision, current)) droppedCvar += id
        }
        // a carried vanilla uvar queue degrades CLEANLY on a
        // non-vanilla lane — every queued uvar drops (the lane has no
        // single-file uvar route) instead of the layout backstop throwing
        // mid-reconcile.
        val uvarsSupported = InGameSettingsFiles.activeLayout().supportsUvars
        val uvarValuesByScope = if (!uvarsSupported) emptyMap()
        else snapshot.gameSettings.uvar.values.map { it.scope }.distinct()
            .mapNotNull { scope -> readUvarValues(scope)?.let { scope to it } }.toMap()
        snapshot.gameSettings.uvar.forEach { (id, entry) ->
            if (!uvarsSupported) {
                droppedUvar += id; return@forEach
            }
            val definition = cvarById(id) ?: run {
                droppedUvar += id; return@forEach
            }
            val current = uvarValuesByScope[entry.scope]?.get(definition.key)?.rendered()
            if (classify(id, entry.scope, entry.revision, current)) droppedUvar += id
        }
        val bindingValuesByScope = snapshot.gameSettings.bindings.values.map { it.scope }.distinct()
            .mapNotNull { scope -> readBindings(scope)?.let { scope to it } }.toMap()
        snapshot.gameSettings.bindings.forEach { (command, entry) ->
            val current = bindingValuesByScope[entry.scope]
                ?.filterValues { it == command }
                ?.keys?.joinToString(",")
            if (classify(command, entry.scope, entry.revision, current)) droppedBindings += command
        }
        if (droppedCvar.isNotEmpty() || droppedUvar.isNotEmpty() || droppedBindings.isNotEmpty()) {
            settings.mutateGameSettings { queue, _ ->
                queue.copy(
                    cvar = queue.cvar - droppedCvar,
                    uvar = queue.uvar - droppedUvar,
                    bindings = queue.bindings - droppedBindings,
                )
            }
        }
        return ReconcileSummary(applied, superseded, blockedQueueKeys(snapshot).size)
    }

    private fun SavedVariablesCodec.Value.rendered(): String = when (this) {
        is SavedVariablesCodec.Value.Str -> raw
        is SavedVariablesCodec.Value.Num -> raw
        is SavedVariablesCodec.Value.Bool -> if (raw) "1" else "0"
        SavedVariablesCodec.Value.Nil -> "nil"
    }

    private fun stableClientRoot(): File = com.pocketrealm.storage.StorageRoots.get(context).client

    private fun <V> mutateMap(
        map: Map<String, V>,
        id: String,
        entry: V?,
    ): Map<String, V> = if (entry == null) map - id else map + (id to entry)
}

private fun WowGameSettingsConfig.without(id: String, family: InGameSettingsEditor.DirectEdit.Family) =
    when (family) {
        InGameSettingsEditor.DirectEdit.Family.CVAR -> copy(cvar = cvar - id)
        InGameSettingsEditor.DirectEdit.Family.UVAR -> copy(uvar = uvar - id)
        InGameSettingsEditor.DirectEdit.Family.BINDING -> copy(bindings = bindings - id)
    }
