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
    val cpuPercent: Double?,
    val cpuSampleWindowMs: Long,
    val rssBytes: Long,
    val threadCount: Int,
    val processCount: Int,
    val updatedAtMs: Long,
    val error: String?,
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
                    ))
                }
            }
            val worker = value.optJSONObject("worker") ?: JSONObject()
            val activeStage = stages.firstOrNull { it.state == DataStageState.RUNNING.name }
                ?: stages.firstOrNull { it.state == DataStageState.FAILED.name }
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
                cpuPercent = worker.optionalDouble("cpuPercent"),
                cpuSampleWindowMs = worker.optLong("sampleWindowMs"),
                rssBytes = worker.optLong("rssBytes"),
                threadCount = worker.optInt("threadCount"),
                processCount = worker.optInt("processCount"),
                updatedAtMs = value.optLong("updatedAtMs"),
                error = value.optionalString("lastError"),
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

fun formatImportPercent(fraction: Float): String =
    String.format(Locale.US, "%.1f%%", fraction.coerceIn(0f, 1f) * 100f)

fun formatImportCpu(percent: Double?): String =
    percent?.let { String.format(Locale.US, "%.1f%%", it.coerceAtLeast(0.0)) } ?: "Sampling…"

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

private fun JSONObject.optionalDouble(name: String): Double? =
    takeIf { has(name) && !isNull(name) }?.optDouble(name)?.takeIf(Double::isFinite)
