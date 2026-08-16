package com.pocketrealm.ingame

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pending, not-yet-delivered edits staged while the client runs
 * (docs/INGAME_SETTINGS_PLAN_2026-08-16.md §4.2). This is a queue, not a
 * mirror of game state. The global `revisionSequence` counter lives in its
 * own DataStore Long key (see Settings) and never resets, which is what
 * makes apply-once delivery correct for all three queues.
 */
data class QueuedOverride(
    /** null stages a removal — the setting resolves to the client default. */
    val value: String?,
    val revision: Long,
    val scope: String,
)

data class BindingOverride(
    val primary: String?,
    val secondary: String?,
    val revision: Long,
    val scope: String,
)

data class WowGameSettingsConfig(
    val cvar: Map<String, QueuedOverride> = emptyMap(),
    val uvar: Map<String, QueuedOverride> = emptyMap(),
    val bindings: Map<String, BindingOverride> = emptyMap(),
) {
    val totalQueued: Int get() = cvar.size + uvar.size + bindings.size

    fun toJson(): String {
        val root = JSONObject().put("schema", 1)
        root.put("cvar", JSONObject().apply {
            cvar.forEach { (id, entry) -> put(id, JSONObject()
                .put("value", entry.value ?: JSONObject.NULL)
                .put("revision", entry.revision).put("scope", entry.scope)) }
        })
        root.put("uvar", JSONObject().apply {
            uvar.forEach { (id, entry) -> put(id, JSONObject()
                .put("value", entry.value ?: JSONObject.NULL)
                .put("revision", entry.revision).put("scope", entry.scope)) }
        })
        root.put("bindings", JSONObject().apply {
            bindings.forEach { (id, entry) ->
                put(id, JSONObject()
                    .put("primary", entry.primary ?: JSONObject.NULL)
                    .put("secondary", entry.secondary ?: JSONObject.NULL)
                    .put("revision", entry.revision).put("scope", entry.scope))
            }
        })
        return root.toString()
    }

    companion object {
        /** Sized from the full-catalog worst case (§4.2): 211 bindings ≈ 31 KiB
         *  plus ~118 settings ≈ 8 KiB, with margin under the 64 KiB control cap. */
        const val MAX_JSON_BYTES: Int = 48 * 1024
        const val SCOPE_CONFIG: String = "config"

        /** Lenient: any malformed payload reads as an empty queue, never throws. */
        fun fromJson(raw: String?): WowGameSettingsConfig {
            if (raw.isNullOrBlank()) return WowGameSettingsConfig()
            return runCatching {
                val root = JSONObject(raw)
                check(root.getInt("schema") == 1) { "unsupported game-settings schema" }
                fun readOverrides(name: String): Map<String, QueuedOverride> {
                    val section = root.optJSONObject(name) ?: return emptyMap()
                    val out = linkedMapOf<String, QueuedOverride>()
                    section.keys().forEachRemaining { id ->
                        val entry = section.getJSONObject(id)
                        out[id] = QueuedOverride(
                            value = if (entry.isNull("value")) null
                            else entry.getString("value"),
                            revision = entry.getLong("revision"),
                            scope = entry.getString("scope"),
                        )
                    }
                    return out
                }
                val bindings = linkedMapOf<String, BindingOverride>()
                root.optJSONObject("bindings")?.let { section ->
                    section.keys().forEachRemaining { id ->
                        val entry = section.getJSONObject(id)
                        bindings[id] = BindingOverride(
                            primary = if (entry.isNull("primary")) null
                            else entry.optString("primary").takeIf { it.isNotEmpty() },
                            secondary = if (entry.isNull("secondary")) null
                            else entry.optString("secondary").takeIf { it.isNotEmpty() },
                            revision = entry.getLong("revision"),
                            scope = entry.getString("scope"),
                        )
                    }
                }
                WowGameSettingsConfig(
                    cvar = readOverrides("cvar"),
                    uvar = readOverrides("uvar"),
                    bindings = bindings,
                )
            }.getOrDefault(WowGameSettingsConfig())
        }
    }
}

/**
 * The latest delivery per setting/command id + scope, persisted inside
 * `managed-safe-profile.json` and carried forward across prepares (pruned
 * against the oldest revision still queued, §5.2).
 */
data class GameSettingsDeliveryEntry(
    val key: String,
    val scope: String,
    val value: String,
    val revision: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("key", key).put("scope", scope)
        .put("value", value).put("revision", revision)

    companion object {
        fun fromJson(raw: JSONObject): GameSettingsDeliveryEntry = GameSettingsDeliveryEntry(
            key = raw.getString("key"),
            scope = raw.getString("scope"),
            value = raw.getString("value"),
            revision = raw.getLong("revision"),
        )

        fun listToJson(entries: List<GameSettingsDeliveryEntry>): JSONArray =
            JSONArray().apply { entries.forEach { put(it.toJson()) } }

        fun listFromJson(raw: Any?): List<GameSettingsDeliveryEntry> {
            if (raw !is JSONArray) return emptyList()
            return (0 until raw.length()).mapNotNull { index ->
                runCatching { fromJson(raw.getJSONObject(index)) }.getOrNull()
            }
        }
    }
}

/** One binding delivery: the exact two-slot state to enforce for a command. */
data class BindingAssignment(val command: String, val primary: String?, val secondary: String?)

/**
 * Pure apply-once planner (§5.1/§5.2). A queued entry delivers only when its
 * revision outranks the revision last delivered for the same id+scope; a
 * delivered override is never re-applied, so later in-game edits survive and
 * are reported as superseded at the next editor visit. Entries stranded on a
 * key the app currently enforces (e.g. staged master sound while audio is
 * off) and entries whose backing scope file is absent are skipped and
 * retained: not written, not dropped, not recorded as delivered.
 */
object GameSettingsDeliveryPlanner {

    data class Plan(
        val cvarWrites: List<ConfigWtfCodec.UserOverride>,
        /** uvar scope -> (uvar name, value or null-to-remove) */
        val uvarWrites: Map<String, List<Pair<String, String?>>>,
        val bindingWrites: Map<String, List<BindingAssignment>>,
        val delivered: List<GameSettingsDeliveryEntry>,
        val blockedKeys: Set<String>,
        val missingScopeKeys: Set<String>,
    ) {
        val anyBlocked: Boolean get() = blockedKeys.isNotEmpty() || missingScopeKeys.isNotEmpty()
    }

    fun plan(
        config: WowGameSettingsConfig,
        enforcedCvarKeys: Set<String>,
        uvarScopeExists: (scope: String) -> Boolean,
        bindingScopeExists: (scope: String) -> Boolean,
        previousDelivered: List<GameSettingsDeliveryEntry>,
    ): Plan {
        val deliveredIndex = previousDelivered.associateBy { "${it.key}\u0000${it.scope}" }
        val cvarWrites = mutableListOf<ConfigWtfCodec.UserOverride>()
        val uvarWrites = linkedMapOf<String, MutableList<Pair<String, String?>>>()
        val bindingWrites = linkedMapOf<String, MutableList<BindingAssignment>>()
        val delivered = mutableListOf<GameSettingsDeliveryEntry>()
        val blocked = mutableSetOf<String>()
        val missingScope = mutableSetOf<String>()

        fun outranks(id: String, scope: String, revision: Long): Boolean {
            val prior = deliveredIndex["$id\u0000$scope"] ?: return true
            return revision > prior.revision
        }

        config.cvar.forEach { (id, entry) ->
            val definition = WowVanillaSettingsCatalog.byId(id)
            val cvarKey = definition?.key ?: id
            if (definition == null || definition.backend != WowSettingBackend.CVAR) {
                blocked += id
                return@forEach
            }
            if (cvarKey in enforcedCvarKeys) {
                blocked += id
                return@forEach
            }
            if (!outranks(id, entry.scope, entry.revision)) return@forEach
            cvarWrites += ConfigWtfCodec.UserOverride(cvarKey, entry.value)
            entry.value?.toFloatOrNull()?.let { numeric ->
                definition.pairedWrites.forEach { (companion, factor) ->
                    cvarWrites += ConfigWtfCodec.UserOverride(
                        companion,
                        ConfigWtfCodec.formatValue(numeric * factor),
                    )
                }
            }
            delivered += GameSettingsDeliveryEntry(id, entry.scope, entry.value ?: "", entry.revision)
        }
        config.uvar.forEach { (id, entry) ->
            val definition = WowVanillaSettingsCatalog.byId(id)
            if (definition == null || definition.backend != WowSettingBackend.UVAR) {
                blocked += id
                return@forEach
            }
            if (!uvarScopeExists(entry.scope)) {
                missingScope += id
                return@forEach
            }
            if (!outranks(id, entry.scope, entry.revision)) return@forEach
            uvarWrites.getOrPut(entry.scope) { mutableListOf() } += definition.key to entry.value
            delivered += GameSettingsDeliveryEntry(id, entry.scope, entry.value ?: "", entry.revision)
        }
        config.bindings.forEach { (commandId, entry) ->
            if (!bindingScopeExists(entry.scope)) {
                missingScope += commandId
                return@forEach
            }
            if (!outranks(commandId, entry.scope, entry.revision)) return@forEach
            bindingWrites.getOrPut(entry.scope) { mutableListOf() } +=
                BindingAssignment(commandId, entry.primary, entry.secondary)
            delivered += GameSettingsDeliveryEntry(
                commandId,
                entry.scope,
                listOfNotNull(entry.primary, entry.secondary).joinToString(","),
                entry.revision,
            )
        }
        return Plan(cvarWrites, uvarWrites, bindingWrites, delivered, blocked, missingScope)
    }

    /**
     * Carry forward + prune rule (§5.2): merge new deliveries over the
     * previous map, then drop carried entries older than the oldest revision
     * still queued in any queue — future stagings always carry higher
     * revisions, so a pruned entry can never matter again.
     */
    fun carryForward(
        previous: List<GameSettingsDeliveryEntry>,
        freshlyDelivered: List<GameSettingsDeliveryEntry>,
        config: WowGameSettingsConfig,
    ): List<GameSettingsDeliveryEntry> {
        val merged = previous.associateBy { "${it.key}\u0000${it.scope}" }.toMutableMap()
        freshlyDelivered.forEach {
            merged["${it.key}\u0000${it.scope}"] = it
        }
        val oldestQueued = (config.cvar.values.asSequence() + config.uvar.values.asSequence())
            .map { it.revision } + config.bindings.values.asSequence().map { it.revision }
        val floor = oldestQueued.minOrNull()
        return merged.values.filter { entry -> floor == null || entry.revision >= floor }
            .sortedWith(compareBy({ it.key }, { it.scope }))
    }
}
