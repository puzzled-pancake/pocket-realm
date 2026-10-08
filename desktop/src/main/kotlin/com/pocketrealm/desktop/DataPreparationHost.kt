package com.pocketrealm.desktop

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DESK-44/69/102: the world-data preparation HOST — the named desktop
 * counterpart of Android's DataPreparationStore wiring. It resolves the
 * active managed client generation, opens the [com.pocketrealm.importer.ImportJournal]
 * twin, drives the checkpointed extractor pipeline
 * ([com.pocketrealm.importer.DataPreparationStore], the DESK-69 twin), and
 * words progress through the shared ImportProgressPresentation stage
 * titles. The UI cards (Home, Settings, Diagnostics — see
 * [com.pocketrealm.desktop.ui.DataPreparationCard]) reach this host through
 * the app model; the host is where the lane's composition lives, so every
 * placement renders the same lane, journal, and copy.
 */
internal class DataPreparationHost(
    private val roots: DesktopStorageRoots,
    /** The active managed client generation, or null when nothing usable is
     * published — the host refuses before any stage runs in that state. */
    private val activeGeneration: () -> DesktopAppModel.ClientGenerationStatus?,
) {
    /**
     * The prepared-data lane's fail-soft status: ready when the shared
     * PreparedDataStore envelope (pointer + manifest identity) resolves;
     * otherwise not ready with the envelope's refusal (the same copy the
     * world start path refuses with).
     */
    fun status(): WorldDataStatus {
        val store = com.pocketrealm.server.PreparedDataStore(
            File(roots.content, "o11-server"),
        )
        return try {
            val active = store.requireActiveEnvelope()
            WorldDataStatus(ready = true, detail = "generation ${active.generation.take(8)}…")
        } catch (failure: Throwable) {
            WorldDataStatus(
                ready = false,
                detail = failure.message ?: failure.javaClass.simpleName,
            )
        }
    }

    data class WorldDataStatus(val ready: Boolean, val detail: String?)

    /**
     * Prepare (or resume) the server world data from the active managed
     * client generation: checkpointed DBC_MAPS → VMAP_EXTRACT →
     * VMAP_ASSEMBLE → MMAPS → MANIFEST over the staged extractor EXEs,
     * publishing the immutable generation + per-flavor active pointer.
     * [onPhase] receives coarse wording keyed on the journaled data stages
     * (the shared ImportProgressPresentation stage titles).
     */
    suspend fun prepare(
        onPhase: (String) -> Unit = {},
    ): com.pocketrealm.importer.DataPreparationStore.PublishedData = withContext(Dispatchers.IO) {
        val generation = activeGeneration()
        val clientRoot = generation?.root
            ?.takeIf { it.isDirectory && File(it, "WoW.exe").isFile }
            ?: throw com.pocketrealm.importer.ImportRejected(
                "Import the game client first — world data is generated from the managed client.",
            )
        val journal = com.pocketrealm.importer.ImportJournal(roots)
        try {
            val store = com.pocketrealm.importer.DataPreparationStore(roots, journal)
            store.prepare(
                generation.generation,
                clientRoot,
                onTick = {
                    onPhase(phaseWording(journal, generation.generation))
                },
            )
        } finally {
            journal.close()
        }
    }

    /** Human wording for the running data stage ("preparing" between stages). */
    private fun phaseWording(
        journal: com.pocketrealm.importer.ImportJournal,
        importId: String,
    ): String {
        val running = journal.dataStages(importId)
            .lastOrNull { it.state == com.pocketrealm.importer.DataStageState.RUNNING }
            ?: return "preparing"
        return com.pocketrealm.importer.stageTitle(running.stage.name).replaceFirstChar(Char::lowercase)
    }
}
