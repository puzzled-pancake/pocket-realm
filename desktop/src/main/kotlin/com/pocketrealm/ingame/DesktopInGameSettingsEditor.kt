package com.pocketrealm.ingame

import com.pocketrealm.client.WowVanillaBindingCatalog
import com.pocketrealm.desktop.DesktopLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * DESK-12/22/41/59/85/96: the desktop twin of the Android in-game settings
 * editor — same package, same contract, one process.
 *
 * The WTF tree is the configured client folder's `WTF/` directory and the
 * codecs are the shared ones (ConfigWtfCodec / SavedVariablesCodec /
 * BindingsFileCodec). While the client is stopped, an edit applies directly
 * to the files (the Android editor's direct lane); while the client runs,
 * the same edit stages into the persisted queue instead, and the queue is
 * drained by the app model right after a clean stop (the Android
 * apply-on-next-prepare lane, desktop flavor: "apply when the session
 * drains"). The cross-process edit lock degenerates to an in-process
 * reentrant mutex on the desktop (single JVM) but is kept for contract
 * parity — acquisition order and hold discipline match the Android editor.
 *
 * The DataStore legs collapse into one JSON file (`queueFile`) carrying the
 * queue (the shared WowGameSettingsConfig codec), a never-resetting revision
 * counter, the direct-edit journal (so the master-sound transition rule
 * stays sound), and the delivered-override record the Android editor keeps
 * in managed-safe-profile.json.
 */
class DesktopInGameSettingsEditor(
    /**
     * The configured client folder (the WTF tree's root), re-resolved at
     * every operation: the app model's client selection resolves the
     * active managed generation lazily, so the folder can first appear
     * (a fresh install configures its client) or move (a re-import
     * retires the old generation) long after this editor was built — the
     * same seam [launchConditions] re-resolves at drain time. Resolving
     * once per operation also keeps the lock, the reads and the writes on
     * one consistent root within that operation. Null = unavailable.
     */
    private val clientRoot: () -> File?,
    /** Durable queue/journal/delivery record (under the app's runtime root). */
    private val queueFile: File,
    /** Probe of the client's liveness (the supervisor's CLIENT observation). */
    private val clientRunning: () -> Boolean,
    /**
     * The next launch's DESK-50 conditions, resolved at drain time so the
     * drain blocks queued edits to keys the launch overlay owns (the launch
     * path re-enforces them through [DesktopConfigWtf.enforceAtLaunch]
     * right before every spawn — a delivered edit there would be silently
     * stomped one launch later).
     */
    private val launchConditions: () -> DesktopConfigWtf.DesktopLaunchConditions,
) {

    init {
        queueFile.parentFile?.mkdirs()
    }

    /** How a user edit landed. The desktop routes between these two lanes on
     * the live client state — the Android app's UI decides up front. */
    enum class EditLane { APPLIED_DIRECT, QUEUED_FOR_CLEAN_STOP }

    sealed class EditOutcome {
        data class Landed(val lane: EditLane) : EditOutcome()
        data class Refused(val reason: String) : EditOutcome()
    }

    /** One user edit, the editor decides its lane. Exactly one family is set. */
    sealed class Edit {
        data class CvarSetting(
            val definition: WowSettingDefinition,
            val storedValue: String?,
        ) : Edit()

        data class UvarSetting(
            val definition: WowSettingDefinition,
            val account: String,
            val storedValue: String?,
        ) : Edit()

        data class Binding(
            val commandId: String,
            val scope: String,
            val primary: String?,
            val secondary: String?,
        ) : Edit()
    }

    // ------------------------------------------------------------- reads

    fun readConfigValues(): Map<String, String>? = readLocked { root ->
        val file = InGameSettingsFiles.configFile(root)
        if (file.isFile) ConfigWtfCodec.parse(file.readText(Charsets.UTF_8)) else emptyMap()
    }

    fun readUvarValues(account: String): Map<String, SavedVariablesCodec.Value>? = readLocked { root ->
        val file = InGameSettingsFiles.accountSavedVariables(root, account)
        if (file.isFile) SavedVariablesCodec.parse(file.readText(Charsets.UTF_8)) else emptyMap()
    }

    fun readBindings(scope: String): Map<String, String>? = readLocked { root ->
        val file = InGameSettingsFiles.bindingsForScope(root, scope)
        if (file.isFile) BindingsFileCodec.parse(file.readText(Charsets.UTF_8)) else emptyMap()
    }

    fun uvarAccounts(): List<String> =
        readLocked(InGameSettingsFiles::accountsWithSavedVariables).orEmpty()

    /** `(scope, scopeKind)` pairs — Account rows first, then Character rows. */
    fun bindingScopes(): List<Pair<String, String>> = readLocked { root ->
        InGameSettingsFiles.accountsWithBindings(root).map { it to "Account" } +
            InGameSettingsFiles.characterScopesWithBindings(root).map { it to "Character" }
    }.orEmpty()

    // ------------------------------------------------------------- edits

    /**
     * Apply one user edit: direct file edit while the client is stopped
     * (stopped state re-checked INSIDE the edit lock, then the queued entry
     * for the same setting dropped — newest explicit edit wins), or a queue
     * staging while it runs. Refusals are honest and named.
     */
    fun apply(edit: Edit): EditOutcome {
        val root = clientRoot() ?: return EditOutcome.Refused("no client folder is configured")
        InGameSettingsEditLock.acquire(root).use {
            val running = clientRunning()
            if (!running) {
                // Direct lane (Android directEdit): drop the queued entry only
                // after the stopped re-check, then write the files atomically.
                val failure = applyDirect(root, edit)
                if (failure != null) return EditOutcome.Refused(failure)
                mutateRecord { record ->
                    record.copy(queue = record.queue.without(edit), journal = record.journal)
                }
                journalDirectEdit(edit)
                return EditOutcome.Landed(EditLane.APPLIED_DIRECT)
            }
            // Queue lane (Android stageOverride/stageBindingOverride).
            val failure = checkStageable(edit)
            if (failure != null) return EditOutcome.Refused(failure)
            var bumped = 0L
            mutateRecord { record ->
                bumped = record.revision + 1
                record.copy(
                    queue = record.queue.with(edit, bumped),
                    revision = bumped,
                )
            }
            return EditOutcome.Landed(EditLane.QUEUED_FOR_CLEAN_STOP)
        }
    }

    /** Staged edits still awaiting the next clean stop. */
    fun queue(): WowGameSettingsConfig = readRecord().queue

    fun queuedCount(): Int = queue().totalQueued

    fun discardQueue() {
        mutateRecord { record -> record.copy(queue = WowGameSettingsConfig(), revision = record.revision + 1) }
    }

    /**
     * The clean-stop drain: plan the queue through the shared
     * apply-once planner against the live files, write the planned
     * cvar/uvar/binding legs atomically, record deliveries (so a later queue
     * visit can classify applied-vs-superseded), and carry the delivery
     * record forward. Returns a human summary for the app log/status line.
     */
    fun applyQueuedOnCleanStop(): String {
        val root = clientRoot() ?: return "no client folder configured; queue kept"
        if (clientRunning()) return "client running; queue kept for the next clean stop"
        val record = readRecord()
        if (record.queue.totalQueued == 0) return "no queued in-game settings"
        InGameSettingsEditLock.acquire(root).use {
            if (clientRunning()) return "client running; queue kept for the next clean stop"
            val uvarExists: (String) -> Boolean = { scope ->
                InGameSettingsFiles.accountSavedVariables(root, scope).isFile
            }
            val bindingExists: (String) -> Boolean = {
                InGameSettingsFiles.bindingsForScope(root, it).isFile
            }
            val conditions = launchConditions()
            val plan = GameSettingsDeliveryPlanner.plan(
                config = record.queue,
                // The DESK-50 overlay the next launch re-enforces over the
                // user's Config.wtf: a queued edit to one of these keys
                // (maxFPS, gxResolution, MasterSoundEffects under an
                // audio-off launch, ...) would be delivered here and then
                // silently stomped by DesktopConfigWtf.enforceAtLaunch
                // before the next spawn, so the planner blocks it instead.
                enforcedCvarKeys = enforcedKeysFor(conditions).map { it.key }.toSet(),
                uvarScopeExists = uvarExists,
                bindingScopeExists = bindingExists,
                previousDelivered = record.delivered,
            )
            plan.cvarWrites.forEach { writes ->
                val file = InGameSettingsFiles.configFile(root)
                val base = if (file.isFile) file.readText(Charsets.UTF_8) else null
                InGameSettingsFiles.writeAtomic(
                    file,
                    ConfigWtfCodec.merge(base, emptyList(), listOf(writes)).text,
                )
            }
            plan.uvarWrites.forEach { (scope, writes) ->
                val file = InGameSettingsFiles.accountSavedVariables(root, scope)
                if (file.isFile) {
                    var text = file.readText(Charsets.UTF_8)
                    writes.forEach { (name, value) ->
                        val numberForm = uvarNumberForm(record.queue, name)
                        text = SavedVariablesCodec.assign(text, name, value, numberForm = numberForm)
                            ?: return@forEach
                    }
                    InGameSettingsFiles.writeAtomic(file, text)
                }
            }
            plan.bindingWrites.forEach { (scope, assignments) ->
                val file = InGameSettingsFiles.bindingsForScope(root, scope)
                if (file.isFile) {
                    var text = file.readText(Charsets.UTF_8)
                    assignments.forEach { assignment ->
                        BindingsFileCodec.keysForCommand(text, assignment.command).forEach { old ->
                            text = BindingsFileCodec.assign(text, old, null)
                        }
                        listOfNotNull(assignment.primary, assignment.secondary).forEach { key ->
                            text = BindingsFileCodec.assign(text, key, assignment.command)
                        }
                    }
                    InGameSettingsFiles.writeAtomic(file, text)
                }
            }
            mutateRecord { next ->
                next.copy(
                    queue = WowGameSettingsConfig(),
                    revision = next.revision + 1,
                    delivered = GameSettingsDeliveryPlanner.carryForward(
                        next.delivered,
                        plan.delivered,
                        next.queue,
                    ),
                )
            }
            return "applied " + plan.delivered.size + " queued in-game change(s)" +
                plan.blockedKeys.takeIf { it.isNotEmpty() }?.let { "; blocked: " + it.sorted() } .orEmpty() +
                plan.missingScopeKeys.takeIf { it.isNotEmpty() }
                    ?.let { "; missing scope: " + it.sorted() }.orEmpty()
        }
    }

    /**
     * Enforced Config keys for the given desktop launch conditions — the
     * DESK-50 overlay the next launch writes
     * ([DesktopConfigWtf.enforcedKeys]: the launch path runs the same
     * decision through [DesktopConfigWtf.enforceAtLaunch] before every
     * spawn), so the queue-blocked classification and the overlay stay one
     * decision. This is what [applyQueuedOnCleanStop] blocks queued edits
     * against.
     */
    fun enforcedKeysFor(conditions: DesktopConfigWtf.DesktopLaunchConditions): List<ConfigWtfCodec.EnforcedLine> =
        DesktopConfigWtf.enforcedKeys(conditions)

    /** Queued cvar entries whose backing key [enforcedKeysFor] owns. */
    fun blockedQueueKeys(conditions: DesktopConfigWtf.DesktopLaunchConditions): Set<String> {
        val enforced = enforcedKeysFor(conditions).map { it.key }.toSet()
        return queue().cvar.keys.filter { id ->
            WowVanillaSettingsCatalog.byId(id)?.key in enforced
        }.toSet()
    }

    // ---------------------------------------------------------- internals

    private fun <T> readLocked(block: (File) -> T): T? {
        val root = clientRoot() ?: return null
        InGameSettingsEditLock.acquire(root).use { return block(root) }
    }

    /** The direct lane's write legs; null on success, else the refusal. */
    @Suppress("ReturnCount", "NestedBlockDepth") // one verdict per family leg
    private fun applyDirect(root: File, edit: Edit): String? = try {
        when (edit) {
            is Edit.CvarSetting -> {
                val definition = edit.definition
                val file = InGameSettingsFiles.configFile(root)
                val base = if (file.isFile) file.readText(Charsets.UTF_8) else null
                val writes = buildList {
                    add(ConfigWtfCodec.UserOverride(definition.key, edit.storedValue))
                    edit.storedValue?.toFloatOrNull()?.let { numeric ->
                        definition.pairedWrites.forEach { (companion, factor) ->
                            add(ConfigWtfCodec.UserOverride(
                                companion,
                                ConfigWtfCodec.formatValue(numeric * factor),
                            ))
                        }
                    }
                }
                InGameSettingsFiles.writeAtomic(
                    file,
                    ConfigWtfCodec.merge(base, emptyList(), writes).text,
                )
            }
            is Edit.UvarSetting -> {
                val definition = edit.definition
                val file = InGameSettingsFiles.accountSavedVariables(root, edit.account)
                check(file.isFile) { "log in once in game to edit saved variables" }
                val updated = SavedVariablesCodec.assign(
                    file.readText(Charsets.UTF_8),
                    definition.key,
                    edit.storedValue,
                    numberForm = definition.uvarValueForm == WowUvarValueForm.NUMBER,
                ) ?: error("\"${definition.key}\" is not editable outside the game")
                InGameSettingsFiles.writeAtomic(file, updated)
            }
            is Edit.Binding -> {
                val file = InGameSettingsFiles.bindingsForScope(root, edit.scope)
                check(file.isFile) { "log in once in game to edit key bindings" }
                var text = file.readText(Charsets.UTF_8)
                BindingsFileCodec.keysForCommand(text, edit.commandId).forEach { old ->
                    text = BindingsFileCodec.assign(text, old, null)
                }
                listOfNotNull(edit.primary, edit.secondary).forEach { key ->
                    text = BindingsFileCodec.assign(text, key, edit.commandId)
                }
                InGameSettingsFiles.writeAtomic(file, text)
            }
        }
        null
    } catch (failure: IllegalStateException) {
        failure.message
    } catch (failure: IllegalArgumentException) {
        failure.message
    }

    /** Stageability checks for the queue lane (the Android staging guards). */
    private fun checkStageable(edit: Edit): String? = when (edit) {
        is Edit.CvarSetting -> {
            val definition = edit.definition
            if (definition.fixedReason != null || definition.backend == WowSettingBackend.FUNCTION) {
                "\"${definition.label}\" cannot be changed outside the game"
            } else {
                null
            }
        }
        is Edit.UvarSetting -> {
            val exists = readLocked { root ->
                InGameSettingsFiles.accountSavedVariables(root, edit.account).isFile
            } == true
            if (!exists) "log in once in game to edit saved variables" else null
        }
        is Edit.Binding -> {
            if (WowVanillaBindingCatalog.find(edit.commandId) == null) {
                "unknown binding command: ${edit.commandId}"
            } else {
                val exists = readLocked { root ->
                    InGameSettingsFiles.bindingsForScope(root, edit.scope).isFile
                } == true
                if (!exists) "log in once in game to edit that scope's bindings" else null
            }
        }
    }

    private fun journalDirectEdit(edit: Edit) {
        val key = when (edit) {
            is Edit.CvarSetting -> edit.definition.key
            is Edit.UvarSetting -> edit.definition.key
            is Edit.Binding -> edit.commandId
        }
        mutateRecord { record ->
            record.copy(
                journal = record.journal + (key to (record.journal[key] ?: 0L) + 1L),
            )
        }
    }

    private fun uvarNumberForm(queue: WowGameSettingsConfig, uvarName: String): Boolean =
        queue.uvar.keys.any { id ->
            val definition = WowVanillaSettingsCatalog.byId(id)
            definition?.key == uvarName && definition?.uvarValueForm == WowUvarValueForm.NUMBER
        }

    // ------------------------------------------------------- queue record

    data class Record(
        val revision: Long,
        val queue: WowGameSettingsConfig,
        /** CVar/uvar key or command id -> count of direct desktop edits; the
         * desktop twin of the Android direct-edit journal (the master-sound
         * transition discriminator). */
        val journal: Map<String, Long>,
        val delivered: List<GameSettingsDeliveryEntry>,
    )

    private fun readRecord(): Record {
        if (!queueFile.isFile) return Record(0L, WowGameSettingsConfig(), emptyMap(), emptyList())
        // Only the envelope can fail here: the queue section
        // (WowGameSettingsConfig.fromJson) and the delivered record
        // (GameSettingsDeliveryEntry.listFromJson) are lenient codecs, so a
        // hand-edited or partially corrupt section defaults on its own and
        // never takes the others down with it — a lost `delivered` record
        // would make already-applied entries re-deliverable and stomp
        // in-game edits.
        val root = runCatching {
            val parsed = JSONObject(queueFile.readText(Charsets.UTF_8))
            check(parsed.optInt("schema") == SCHEMA) { "unsupported queue schema" }
            parsed
        }.getOrElse { failure ->
            DesktopLog.w(
                LOG_TAG,
                "in-game settings queue ${queueFile.name} unreadable; resetting to empty " +
                    "(${failure.message})",
            )
            return Record(0L, WowGameSettingsConfig(), emptyMap(), emptyList())
        }
        return Record(
            revision = root.optLong("revision"),
            queue = WowGameSettingsConfig.fromJson(root.optString("queue")),
            journal = buildMap {
                root.optJSONObject("directEditJournal")?.let { journal ->
                    journal.keys().forEachRemaining { key -> put(key, journal.optLong(key)) }
                }
            },
            delivered = GameSettingsDeliveryEntry.listFromJson(root.opt("delivered")),
        )
    }

    private fun mutateRecord(transform: (Record) -> Record) {
        synchronized(lock) {
            val next = transform(readRecord())
            val root = JSONObject()
                .put("schema", SCHEMA)
                .put("revision", next.revision)
                .put("queue", next.queue.toJson())
                .put(
                    "directEditJournal",
                    JSONObject().apply { next.journal.forEach { (key, rev) -> put(key, rev) } },
                )
                .put("delivered", GameSettingsDeliveryEntry.listToJson(next.delivered))
            val payload = root.toString()
            check(payload.toByteArray(Charsets.UTF_8).size <= WowGameSettingsConfig.MAX_JSON_BYTES) {
                "the in-game settings queue is full — discard some pending changes first"
            }
            queueFile.parentFile?.mkdirs()
            val temp = File(queueFile.parentFile, "." + queueFile.name + ".tmp-" + System.nanoTime())
            java.io.FileOutputStream(temp).use { stream ->
                stream.write(payload.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            try {
                java.nio.file.Files.move(
                    temp.toPath(), queueFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temp.toPath(), queueFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }

    private val lock = Any()

    private companion object {
        const val SCHEMA = 1
        const val LOG_TAG = "InGameSettings"

        /** The queue-without rule: the newest explicit edit drops the staged
         * entry for the same setting (the Android directEdit contract). */
        fun WowGameSettingsConfig.without(edit: Edit): WowGameSettingsConfig = when (edit) {
            is Edit.CvarSetting -> copy(cvar = cvar - edit.definition.id)
            is Edit.UvarSetting -> copy(uvar = uvar - edit.definition.id)
            is Edit.Binding -> copy(bindings = bindings - edit.commandId)
        }

        fun WowGameSettingsConfig.with(edit: Edit, revision: Long): WowGameSettingsConfig =
            when (edit) {
                is Edit.CvarSetting -> copy(
                    cvar = cvar + (edit.definition.id to QueuedOverride(
                        value = edit.storedValue,
                        revision = revision,
                        scope = WowGameSettingsConfig.SCOPE_CONFIG,
                    )),
                )
                is Edit.UvarSetting -> copy(
                    uvar = uvar + (edit.definition.id to QueuedOverride(
                        value = edit.storedValue,
                        revision = revision,
                        scope = edit.account,
                    )),
                )
                is Edit.Binding -> copy(
                    bindings = bindings + (edit.commandId to BindingOverride(
                        primary = edit.primary,
                        secondary = edit.secondary,
                        revision = revision,
                        scope = edit.scope,
                    )),
                )
            }
    }
}
