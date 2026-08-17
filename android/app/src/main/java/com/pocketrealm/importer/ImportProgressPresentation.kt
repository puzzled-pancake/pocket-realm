package com.pocketrealm.importer

import org.json.JSONObject
import java.util.Locale

data class ImportStageProgress(
    val id: String,
    val title: String,
    val state: String,
    val processed: Int,
    val total: Int,
    val bytesWritten: Long,
    val checkpoint: String?,
    val updatedAtMs: Long,
    val explanation: String,
    val startedAtMs: Long = 0L,
    val completedAtMs: Long = 0L,
)

data class ImportBenchmarkStageDuration(val stage: String, val durationMs: Long)

data class ImportBenchmarkRun(
    val deviceLabel: String,
    val totalMs: Long,
    val copyMs: Long,
    val dataMs: Long,
    val stages: List<ImportBenchmarkStageDuration>,
    val mmapMaps: Int,
    val mmapThreads: Int,
    val createdAtMs: Long,
)

data class ImportBenchmarkHistoryEntry(val deviceLabel: String, val totalMs: Long, val createdAtMs: Long)

data class ImportDeviceSpec(
    val label: String,
    val soc: String,
    val activelyCooled: Boolean,
    val abi: String,
    val api: Int,
    val cores: Int,
    val ramBytes: Long,
)

data class ImportProgressPresentation(
    val phase: String,
    val phaseTitle: String,
    val explanation: String,
    val filesProcessed: Int,
    val filesTotal: Int,
    val bytesCopied: Long,
    val bytesTotal: Long,
    val currentPath: String?,
    val currentFileCopied: Long,
    val currentFileTotal: Long,
    val activeStage: ImportStageProgress?,
    val stages: List<ImportStageProgress>,
    val workerPresent: Boolean,
    val workerState: String,
    val rssBytes: Long,
    val threadCount: Int,
    val processCount: Int,
    val sourceUri: String?,
    val updatedAtMs: Long,
    val error: String?,
    val benchmark: ImportBenchmarkRun? = null,
    val benchmarkHistory: List<ImportBenchmarkHistoryEntry> = emptyList(),
    val device: ImportDeviceSpec? = null,
) {
    val fileFraction: Float get() = fraction(filesProcessed.toLong(), filesTotal.toLong())
    val byteFraction: Float get() = when {
        phase == ImportPhase.COMPLETE.name -> 1f
        else -> fraction(bytesCopied + currentFileCopied, bytesTotal)
    }
    val currentFileFraction: Float get() = fraction(currentFileCopied, currentFileTotal)

    fun updatedAgeSeconds(nowMs: Long): Long =
        ((nowMs - updatedAtMs).coerceAtLeast(0L) / 1_000L)

    companion object {
        fun idle(): ImportProgressPresentation =
            fromJson(JSONObject().put("phase", ImportPhase.IDLE.name))

        fun fromJson(value: JSONObject): ImportProgressPresentation {
            val phase = value.optString("phase", ImportPhase.IDLE.name)
            val activeFile = value.optJSONObject("activeFile")
            val stages = buildList {
                val array = value.optJSONArray("dataStages") ?: return@buildList
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                val id = item.optString("stage")
                add(ImportStageProgress(
                    id = id,
                    title = stageTitle(id),
                    state = item.optString("state", DataStageState.PENDING.name),
                    processed = item.optInt("processed"),
                    total = item.optInt("total"),
                    bytesWritten = item.optLong("bytesWritten"),
                    checkpoint = item.optionalString("checkpoint"),
                    updatedAtMs = item.optLong("updatedAtMs"),
                    explanation = stageExplanation(id),
                    startedAtMs = item.optLong("startedAtMs"),
                    completedAtMs = item.optLong("completedAtMs"),
                ))
                }
            }
            val worker = value.optJSONObject("worker") ?: JSONObject()
            val activeStage = stages.firstOrNull { it.state == DataStageState.RUNNING.name }
                ?: stages.firstOrNull { it.state == DataStageState.FAILED.name }
            val benchmarkObject = value.optJSONObject("benchmark")
            val historyArray = benchmarkObject?.optJSONArray("history")
            return ImportProgressPresentation(
                phase = phase,
                phaseTitle = phaseTitle(phase),
                explanation = phaseExplanation(phase, activeStage?.id),
                filesProcessed = value.optInt("filesProcessed"),
                filesTotal = value.optInt("filesTotal"),
                bytesCopied = value.optLong("bytesCopied"),
                bytesTotal = value.optLong("bytesTotal"),
                currentPath = activeFile?.optionalString("relativePath")
                    ?: value.optionalString("lastRelativePath"),
                currentFileCopied = activeFile?.optLong("copiedBytes") ?: 0L,
                currentFileTotal = activeFile?.optLong("expectedBytes") ?: 0L,
                activeStage = activeStage,
                stages = stages,
                workerPresent = worker.optBoolean("present"),
                workerState = worker.optString("state", "absent"),
                rssBytes = worker.optLong("rssBytes"),
                threadCount = worker.optInt("threadCount"),
                processCount = worker.optInt("processCount"),
                sourceUri = if (value.isNull("sourceUri")) null
                else value.optionalString("sourceUri"),
                updatedAtMs = value.optLong("updatedAtMs"),
                error = value.optionalString("lastError"),
                benchmark = benchmarkObject?.optJSONObject("latest")?.let { latest ->
                    val durations = latest.optJSONObject("stageDurations") ?: JSONObject()
                    ImportBenchmarkRun(
                        deviceLabel = latest.optString("deviceLabel"),
                        totalMs = latest.optLong("totalMs"),
                        copyMs = latest.optLong("copyMs"),
                        dataMs = latest.optLong("dataMs"),
                        stages = durations.keys().asSequence().map { stage ->
                            ImportBenchmarkStageDuration(stage, durations.optLong(stage))
                        }.sortedBy { it.stage }.toList(),
                        mmapMaps = latest.optInt("mmapMaps"),
                        mmapThreads = latest.optInt("mmapThreads"),
                        createdAtMs = latest.optLong("createdAtMs"),
                    )
                },
                benchmarkHistory = if (historyArray != null) buildList {
                    for (index in 0 until historyArray.length()) {
                        val entry = historyArray.optJSONObject(index) ?: continue
                        add(ImportBenchmarkHistoryEntry(
                            deviceLabel = entry.optString("deviceLabel"),
                            totalMs = entry.optLong("totalMs"),
                            createdAtMs = entry.optLong("createdAtMs"),
                        ))
                    }
                } else emptyList(),
                device = value.optJSONObject("device")?.let { device ->
                    ImportDeviceSpec(
                        label = device.optString("label"),
                        soc = device.optString("soc"),
                        activelyCooled = device.optBoolean("activelyCooled"),
                        abi = device.optString("abi"),
                        api = device.optInt("api"),
                        cores = device.optInt("cores"),
                        ramBytes = device.optLong("ramBytes"),
                    )
                },
            )
        }
    }
}

fun formatImportBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = safe
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}"
    else String.format(Locale.US, "%.1f %s", value, units[unit])
}

fun formatImportDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) + 500) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

/**
 * True only while an import is actually executing. Terminal phases (COMPLETE,
 * CANCELLED, FAILED) must count as not busy: re-picking the client folder after
 * a completed import starts a fresh import, and FAILED remains resumable —
 * refusing either silently drops the picker result.
 */
fun importPhaseBusy(phase: String): Boolean = phase in ACTIVE_IMPORT_PHASES

private val ACTIVE_IMPORT_PHASES = setOf(
    ImportPhase.DISCOVERING.name,
    ImportPhase.PREFLIGHT.name,
    ImportPhase.COPYING.name,
    ImportPhase.VERIFYING.name,
    ImportPhase.PUBLISHING.name,
    ImportPhase.PREPARING_DATA.name,
)

fun formatImportPercent(fraction: Float): String =
    String.format(Locale.US, "%.1f%%", fraction.coerceIn(0f, 1f) * 100f)

fun importWorkerLabel(state: String, present: Boolean): String = when {
    !present -> "Worker not running"
    state == "working" -> "Working"
    state == "waiting" -> "Waiting between operations"
    else -> "Worker available"
}

internal fun phaseTitle(phase: String): String = when (phase) {
    ImportPhase.IDLE.name -> "No import started"
    ImportPhase.DISCOVERING.name -> "Scanning selected folder"
    ImportPhase.PREFLIGHT.name -> "Checking storage"
    ImportPhase.COPYING.name -> "Copying and verifying client"
    ImportPhase.VERIFYING.name -> "Verifying managed copy"
    ImportPhase.PUBLISHING.name -> "Publishing managed client"
    ImportPhase.PREPARING_DATA.name -> "Preparing server world data"
    ImportPhase.COMPLETE.name -> "Client and server data ready"
    ImportPhase.PAUSED.name -> "Import paused"
    ImportPhase.CANCELLED.name -> "Import cancelled"
    ImportPhase.FAILED.name -> "Import needs attention"
    else -> phase.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
}

internal fun phaseExplanation(phase: String, activeStage: String?): String = when (phase) {
    ImportPhase.COPYING.name ->
        "Each file is copied and hashed before the file counter advances. Large MPQ files can remain on one number for several minutes."
    ImportPhase.VERIFYING.name ->
        "Pocket Realm is checking the completed private copy against the durable import journal before making it active."
    ImportPhase.PUBLISHING.name ->
        "The verified copy is being switched into service atomically; the selected source folder is never modified."
    ImportPhase.PREPARING_DATA.name -> stageExplanation(activeStage)
    ImportPhase.COMPLETE.name ->
        "The managed client and its generated server data passed their integrity checks and are ready to use."
    ImportPhase.PAUSED.name ->
        "Verified files are retained. Resume continues from the last safe checkpoint; a partly copied file restarts from its beginning."
    ImportPhase.FAILED.name ->
        "The source and verified checkpoints are preserved. Review the error below before resuming."
    else -> "Pocket Realm keeps the selected source read-only and publishes only verified app-managed data."
}

internal fun stageTitle(stage: String): String = when (stage) {
    DataStage.DBC_MAPS.name -> "Game tables and terrain maps"
    DataStage.VMAP_EXTRACT.name -> "Collision model extraction"
    DataStage.VMAP_ASSEMBLE.name -> "Collision map assembly"
    DataStage.MMAPS.name -> "AI navigation maps"
    DataStage.MANIFEST.name -> "Final integrity manifest"
    else -> stage.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
}

internal fun stageExplanation(stage: String?): String = when (stage) {
    DataStage.DBC_MAPS.name ->
        "Reads game archives and generates the server's tables and terrain tiles. This is disk and CPU intensive."
    DataStage.VMAP_EXTRACT.name ->
        "Extracts building and terrain collision models so creatures and line-of-sight behave correctly."
    DataStage.VMAP_ASSEMBLE.name ->
        "Combines extracted collision models into the world collision maps used by the server."
    DataStage.MMAPS.name ->
        "Builds pathfinding data for bots and creatures. Maps vary greatly in size, so the map counter may stay unchanged while CPU work continues."
    DataStage.MANIFEST.name ->
        "Hashes all generated data and publishes it atomically so an interrupted build can never look complete."
    else ->
        "Generates the server-side data needed to run the local realm. Progress advances only at verified checkpoints."
}

private fun fraction(value: Long, total: Long): Float =
    if (total <= 0L) 0f else (value.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)

private fun JSONObject.optionalString(name: String): String? =
    takeIf { has(name) && !isNull(name) }?.optString(name)?.takeIf { it.isNotBlank() && it != "null" }

