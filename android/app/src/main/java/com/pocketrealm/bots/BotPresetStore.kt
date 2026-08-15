package com.pocketrealm.bots

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Dedicated repository for unlimited named custom bot presets (brief §39).
 *
 * Storage: one versioned JSON document per app, written atomically
 * (temp file + atomic move) under an app-private directory. Revisions keep an
 * immutable per-save history so `usr5` identities already handed to a running
 * realm stay resolvable after the preset is edited again (launch snapshots,
 * §40). No preset-count cap is enforced beyond available storage (§36).
 */
class BotPresetStore(private val directory: File) {

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "bot_presets.json"
        const val MAX_REVISIONS_PER_PRESET = 32
        const val EXPORT_KIND = "pocketrealm.bot-preset"
        const val EXPORT_SCHEMA = 1

        /** Highest revision retained per preset; bounds file growth. */
        private val ALLOWED_NAME = Regex("[\\p{L}\\p{N}\\p{P}\\p{Zs}'’-]{1,48}")
    }

    data class Revision(
        val revision: Int,
        val updatedAt: Long,
        val configuration: BotCustomConfiguration,
    )

    data class SavedPreset(
        val id: String,
        val schemaVersion: Int,
        val name: String,
        val basePresetId: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val favorite: Boolean,
        val revisions: List<Revision>,
    ) {
        val revision: Revision get() = revisions.last()
        val configuration: BotCustomConfiguration get() = revision.configuration

        /** Identity of the current revision. */
        fun identity(): String =
            BotPresetIdentities.mint(id, revision.revision, configuration)

        fun identityForRevision(target: Int): String? = revisions
            .lastOrNull { it.revision == target }
            ?.let { BotPresetIdentities.mint(id, it.revision, it.configuration) }
    }

    private val file = File(directory, FILE_NAME)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<SavedPreset>>(emptyList())
    val presets: StateFlow<List<SavedPreset>> = state

    suspend fun reload(): List<SavedPreset> = mutex.withLock { loadFromDisk() }

    suspend fun refresh(): List<SavedPreset> {
        // Pick up changes written by another process without clobbering ours.
        return mutex.withLock {
            val diskModified = runCatching { file.lastModified() }.getOrDefault(0L)
            val loaded = loadedAtMillis
            if (diskModified > loaded) loadFromDisk() else state.value
        }
    }

    private var loadedAtMillis: Long = 0

    suspend fun create(
        name: String,
        base: BotProfile?,
        favorite: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): SavedPreset = mutate { current ->
        require(name.matches(ALLOWED_NAME)) { "invalid preset name" }
        val configuration = base?.let(BotCustomConfiguration::fromBasePreset)
            ?: BotCustomConfiguration.fromBasePreset(BotProfiles.defaultProfile)
        val preset = SavedPreset(
            id = BotPresetIdentities.newPresetId(),
            schemaVersion = SCHEMA_VERSION,
            name = name,
            basePresetId = base?.id,
            createdAt = now,
            updatedAt = now,
            favorite = favorite,
            revisions = listOf(Revision(1, now, configuration)),
        )
        preset.identity() // construction validates the configuration eagerly
        (current + preset) to preset
    }

    /** Save configuration changes; bumps the revision only on real changes. */
    suspend fun save(id: String, configuration: BotCustomConfiguration): SavedPreset =
        mutate { current ->
            val index = current.indexOfFirst { it.id == id }
            require(index >= 0) { "unknown preset" }
            val existing = current[index]
            val now = System.currentTimeMillis()
            val revised = if (existing.revisions.last().configuration == configuration) {
                existing
            } else {
                existing.copy(
                    updatedAt = now,
                    revisions = (existing.revisions + Revision(
                        existing.revision.revision + 1, now, configuration,
                    )).takeLast(MAX_REVISIONS_PER_PRESET),
                )
            }
            revised.identity()
            current.toMutableList().apply { set(index, revised) }.toList() to revised
        }

    suspend fun rename(id: String, name: String): SavedPreset = mutate { current ->
        require(name.matches(ALLOWED_NAME)) { "invalid preset name" }
        val index = current.indexOfFirst { it.id == id }
        require(index >= 0) { "unknown preset" }
        val updated = current[index].copy(
            name = name,
            updatedAt = System.currentTimeMillis(),
        )
        current.toMutableList().apply { set(index, updated) }.toList() to updated
    }

    suspend fun setFavorite(id: String, favorite: Boolean): SavedPreset = mutate { current ->
        val index = current.indexOfFirst { it.id == id }
        require(index >= 0) { "unknown preset" }
        val updated = current[index].copy(favorite = favorite)
        current.toMutableList().apply { set(index, updated) }.toList() to updated
    }

    suspend fun duplicate(id: String, newName: String): SavedPreset = mutate { current ->
        require(newName.matches(ALLOWED_NAME)) { "invalid preset name" }
        val source = current.firstOrNull { it.id == id } ?: error("unknown preset")
        val now = System.currentTimeMillis()
        val copy = SavedPreset(
            id = BotPresetIdentities.newPresetId(),
            schemaVersion = SCHEMA_VERSION,
            name = newName,
            basePresetId = source.basePresetId,
            createdAt = now,
            updatedAt = now,
            favorite = false,
            revisions = listOf(Revision(1, now, source.configuration)),
        )
        copy.identity()
        current + copy to copy
    }

    suspend fun delete(id: String): Unit = mutate { current ->
        require(current.any { it.id == id }) { "unknown preset" }
        current.filterNot { it.id == id } to Unit
    }

    /**
     * Resolve a usr5 identity against the loaded (and, on miss, reloaded)
     * state. Digest mismatch or missing revision yields null — the caller
     * treats that as an unresolvable profile.
     */
    fun resolveIdentity(presetId: String, revision: Int, digest: String): BotProfile? {
        fun resolve(): BotProfile? {
            val preset = state.value.firstOrNull { it.id == presetId } ?: return null
            val record = preset.revisions.lastOrNull { it.revision == revision } ?: return null
            val identity = BotPresetIdentities.mint(preset.id, record.revision, record.configuration)
            if (!identity.endsWith("-$digest")) return null
            return record.configuration.resolve(identity, preset.name, preset.basePresetId)
        }
        resolve()?.let { return it }
        // The file may have been written by another process after we loaded.
        return runCatching {
            kotlinx.coroutines.runBlocking { reload() }
        }.getOrNull()?.let { resolve() }
    }

    // -----------------------------------------------------------------
    // Interchange (export/import) — §37 optional actions, consumer-ready.
    // -----------------------------------------------------------------

    /** Shareable preset document. The checksum covers the canonical configuration. */
    fun exportJson(preset: SavedPreset): String {
        val envelope = JSONObject()
            .put("kind", EXPORT_KIND)
            .put("schema", EXPORT_SCHEMA)
            .put("name", preset.name)
            .put("basePresetId", preset.basePresetId ?: JSONObject.NULL)
            .put("favorite", preset.favorite)
            .put("configuration", writeConfiguration(preset.configuration))
            .put("checksum", configurationChecksum(writeConfiguration(preset.configuration)))
        return envelope.toString(2)
    }

    /**
     * Import a preset document. The configuration is re-validated through
     * [BotCustomConfiguration] construction and the checksum is recomputed
     * from the canonical form, so edited values, unknown keys and tampered
     * checksums are rejected.
     */
    suspend fun importJson(raw: String): SavedPreset {
        val trimmed = raw.trim()
        require(trimmed.toByteArray(StandardCharsets.UTF_8).size <= 256 * 1_024) {
            "preset file is too large"
        }
        val document = JSONObject(trimmed)
        require(document.optString("kind") == EXPORT_KIND) { "not a bot preset file" }
        val schema = document.getInt("schema")
        require(schema in 1..EXPORT_SCHEMA) { "unsupported preset file schema $schema" }
        val configurationJson = document.getJSONObject("configuration")
        val canonical = writeConfiguration(readConfiguration(configurationJson))
        require(configurationChecksum(canonical) == document.getString("checksum")) {
            "preset file failed its integrity check"
        }
        val name = document.getString("name")
        require(name.matches(ALLOWED_NAME)) { "invalid preset name" }
        val preset = SavedPreset(
            id = BotPresetIdentities.newPresetId(),
            schemaVersion = SCHEMA_VERSION,
            name = name,
            basePresetId = if (document.isNull("basePresetId")) null
                else document.getString("basePresetId"),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            favorite = document.optBoolean("favorite", false),
            revisions = listOf(Revision(1, System.currentTimeMillis(), readConfiguration(canonical))),
        )
        preset.identity() // validates eagerly
        return mutate { current -> (current + preset) to preset }
    }

    private fun configurationChecksum(configuration: JSONObject): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(configuration.toString().toByteArray(StandardCharsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private suspend fun <T> mutate(transform: (List<SavedPreset>) -> Pair<List<SavedPreset>, T>): T =
        mutex.withLock {
            if (loadedAtMillis == 0L) loadFromDisk()
            val (next, result) = transform(state.value)
            persist(next)
            state.value = next
            result
        }

    private suspend fun loadFromDisk(): List<SavedPreset> {
        val loaded = runCatching { readPresets(file) }
        loaded.exceptionOrNull()?.let {
            // Preserve the corrupt document for diagnosis instead of crashing.
            runCatching { file.copyTo(File(file.path + ".corrupt"), overwrite = true) }
        }
        val presets = loaded.getOrDefault(emptyList())
        state.value = presets
        loadedAtMillis = runCatching { file.lastModified() }.getOrDefault(System.currentTimeMillis())
        return presets
    }

    private suspend fun persist(presets: List<SavedPreset>) {
        directory.mkdirs()
        require(directory.isDirectory) { "preset directory unavailable" }
        val document = JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("presets", JSONArray(presets.map(::writePreset)))
        val bytes = document.toString().toByteArray(StandardCharsets.UTF_8)
        val tmp: Path = File(directory, "$FILE_NAME.tmp").toPath()
        val target: Path = file.toPath()
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        loadedAtMillis = runCatching { file.lastModified() }.getOrDefault(System.currentTimeMillis())
    }

    private fun readPresets(source: File): List<SavedPreset> {
        if (!source.isFile) return emptyList()
        val text = source.readText(StandardCharsets.UTF_8)
        if (text.isBlank()) return emptyList()
        val document = JSONObject(text)
        val schema = document.getInt("schema")
        require(schema in 1..SCHEMA_VERSION) { "unsupported preset schema $schema" }
        val array = document.getJSONArray("presets")
        return buildList {
            for (i in 0 until array.length()) add(readPreset(array.getJSONObject(i)))
        }
    }

    private fun writePreset(preset: SavedPreset): JSONObject = JSONObject()
        .put("id", preset.id)
        .put("schemaVersion", preset.schemaVersion)
        .put("name", preset.name)
        .put("basePresetId", preset.basePresetId ?: JSONObject.NULL)
        .put("createdAt", preset.createdAt)
        .put("updatedAt", preset.updatedAt)
        .put("favorite", preset.favorite)
        .put("revisions", JSONArray(preset.revisions.map { revision ->
            JSONObject()
                .put("revision", revision.revision)
                .put("updatedAt", revision.updatedAt)
                .put("configuration", writeConfiguration(revision.configuration))
        }))

    private fun readPreset(json: JSONObject): SavedPreset {
        val revisionsArray = json.getJSONArray("revisions")
        require(revisionsArray.length() >= 1)
        val revisions = buildList {
            for (i in 0 until revisionsArray.length()) {
                val revision = revisionsArray.getJSONObject(i)
                add(Revision(
                    revision = revision.getInt("revision"),
                    updatedAt = revision.getLong("updatedAt"),
                    configuration = readConfiguration(revision.getJSONObject("configuration")),
                ))
            }
        }
        require(revisions.map { it.revision } == revisions.map { it.revision }.sorted())
        return SavedPreset(
            id = json.getString("id"),
            schemaVersion = json.getInt("schemaVersion"),
            name = json.getString("name"),
            basePresetId = if (json.isNull("basePresetId")) null else json.getString("basePresetId"),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            favorite = json.getBoolean("favorite"),
            revisions = revisions,
        )
    }

    private fun writeConfiguration(configuration: BotCustomConfiguration): JSONObject {
        val admission = configuration.admission
        return JSONObject()
            .put("selectedTarget", configuration.selectedTarget)
            .put("minimumOnline", configuration.minimumOnline)
            .put("maximumOnline", configuration.maximumOnline)
            .put("initialTarget", configuration.initialTarget)
            .put("startupIncreaseStep", configuration.startupIncreaseStep)
            .put("startupRampIntervalMs", configuration.startupRampIntervalMs)
            .put("activationBatchSize", configuration.activationBatchSize)
            .put("maximumAltBots", configuration.maximumAltBots)
            .put("generationBatchSize", configuration.generationBatchSize)
            .put("generationYieldMs", configuration.generationYieldMs)
            .put("accountPrefix", configuration.accountPrefix)
            .put("accountCount", configuration.accountCount)
            .put("loginBatchSize", configuration.loginBatchSize)
            .put("maintenanceBatchSize", configuration.maintenanceBatchSize)
            .put("randomBotUpdateIntervalMs", configuration.randomBotUpdateIntervalMs)
            .put("iterationsPerTick", configuration.iterationsPerTick)
            .put("loginAtStartup", configuration.loginAtStartup)
            .put("loginWithPlayer", configuration.loginWithPlayer)
            .put("forceActiveWhenNearPlayer", configuration.forceActiveWhenNearPlayer)
            .put("nearPlayerTeleportMaxAmount", configuration.nearPlayerTeleportMaxAmount)
            .put("nearPlayerTeleportRadius", configuration.nearPlayerTeleportRadius)
            .put("teleportMinIntervalSeconds", configuration.teleportMinIntervalSeconds)
            .put("teleportMaxIntervalSeconds", configuration.teleportMaxIntervalSeconds)
            .put("syncLevelWithPlayers", configuration.syncLevelWithPlayers)
            .put("syncLevelMaxAbove", configuration.syncLevelMaxAbove)
            .put("syncLevelNoPlayer", configuration.syncLevelNoPlayer)
            .put("randomBotMaxLevelChance", configuration.randomBotMaxLevelChance.toDouble())
            .put("randomizeMinIntervalSeconds", configuration.randomizeMinIntervalSeconds)
            .put("randomizeMaxIntervalSeconds", configuration.randomizeMaxIntervalSeconds)
            .put("limitCombatActivity", configuration.limitCombatActivity)
            .put("activeBotPercent", configuration.activeBotPercent)
            .put("autoDoQuests", configuration.autoDoQuests)
            .put("allowBotChat", configuration.allowBotChat)
            .put("allowPlayerInvites", configuration.allowPlayerInvites)
            .put("groupNearby", configuration.groupNearby)
            .put("wanderWhenIdle", configuration.wanderWhenIdle)
            .put("enableOffSpecStrategies", configuration.enableOffSpecStrategies)
            .put(
                "admission", JSONObject()
                    .put("maxWorldP99Ms", admission.maxWorldP99Ms)
                    .put("minFreeMemoryMiB", admission.minFreeMemoryMiB)
                    .put("minFreeStorageMiB", admission.minFreeStorageMiB)
                    .put("performanceWarmupMs", admission.performanceWarmupMs)
                    .put("reduceStep", admission.reduceStep)
                    .put("increaseStep", admission.increaseStep)
                    .put("healthyRampMs", admission.healthyRampMs)
                    .put("changeCooldownMs", admission.changeCooldownMs),
            )
    }

    private fun readConfiguration(json: JSONObject): BotCustomConfiguration {
        val admission = json.getJSONObject("admission")
        return BotCustomConfiguration(
            selectedTarget = json.getInt("selectedTarget"),
            minimumOnline = json.getInt("minimumOnline"),
            maximumOnline = json.getInt("maximumOnline"),
            initialTarget = json.getInt("initialTarget"),
            startupIncreaseStep = json.getInt("startupIncreaseStep"),
            startupRampIntervalMs = json.getLong("startupRampIntervalMs"),
            activationBatchSize = json.getInt("activationBatchSize"),
            maximumAltBots = json.getInt("maximumAltBots"),
            generationBatchSize = json.getInt("generationBatchSize"),
            generationYieldMs = json.getLong("generationYieldMs"),
            accountPrefix = json.getString("accountPrefix"),
            accountCount = json.getInt("accountCount"),
            loginBatchSize = json.getInt("loginBatchSize"),
            maintenanceBatchSize = json.getInt("maintenanceBatchSize"),
            randomBotUpdateIntervalMs = json.getInt("randomBotUpdateIntervalMs"),
            iterationsPerTick = json.getInt("iterationsPerTick"),
            loginAtStartup = json.getBoolean("loginAtStartup"),
            loginWithPlayer = json.getBoolean("loginWithPlayer"),
            forceActiveWhenNearPlayer = json.getBoolean("forceActiveWhenNearPlayer"),
            nearPlayerTeleportMaxAmount = json.getInt("nearPlayerTeleportMaxAmount"),
            nearPlayerTeleportRadius = json.getInt("nearPlayerTeleportRadius"),
            teleportMinIntervalSeconds = json.getInt("teleportMinIntervalSeconds"),
            teleportMaxIntervalSeconds = json.getInt("teleportMaxIntervalSeconds"),
            syncLevelWithPlayers = json.getBoolean("syncLevelWithPlayers"),
            syncLevelMaxAbove = json.getInt("syncLevelMaxAbove"),
            syncLevelNoPlayer = json.getInt("syncLevelNoPlayer"),
            randomBotMaxLevelChance = json.getDouble("randomBotMaxLevelChance").toFloat(),
            randomizeMinIntervalSeconds = json.getInt("randomizeMinIntervalSeconds"),
            randomizeMaxIntervalSeconds = json.getInt("randomizeMaxIntervalSeconds"),
            limitCombatActivity = json.getBoolean("limitCombatActivity"),
            activeBotPercent = json.getInt("activeBotPercent"),
            autoDoQuests = json.getBoolean("autoDoQuests"),
            allowBotChat = json.getBoolean("allowBotChat"),
            allowPlayerInvites = json.getBoolean("allowPlayerInvites"),
            groupNearby = json.getBoolean("groupNearby"),
            wanderWhenIdle = json.getBoolean("wanderWhenIdle"),
            enableOffSpecStrategies = json.getBoolean("enableOffSpecStrategies"),
            admission = BotAdmissionLimits(
                maxWorldP99Ms = admission.getInt("maxWorldP99Ms"),
                minFreeMemoryMiB = admission.getLong("minFreeMemoryMiB"),
                minFreeStorageMiB = admission.getLong("minFreeStorageMiB"),
                performanceWarmupMs = admission.getLong("performanceWarmupMs"),
                reduceStep = admission.getInt("reduceStep"),
                increaseStep = admission.getInt("increaseStep"),
                healthyRampMs = admission.getLong("healthyRampMs"),
                changeCooldownMs = admission.getLong("changeCooldownMs"),
            ),
        )
    }
}
