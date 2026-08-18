package com.pocketrealm.importer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportProgressPresentationTest {
    @Test fun presentsLiveMmapWorkAndPartialFile() {
        val value = JSONObject()
            .put("schema", 2)
            .put("phase", ImportPhase.PREPARING_DATA.name)
            .put("filesProcessed", 150)
            .put("filesTotal", 150)
            .put("bytesCopied", 5_000L)
            .put("bytesTotal", 5_000L)
            .put("updatedAtMs", 9_000L)
            .put("activeFile", JSONObject()
                .put("relativePath", "Data/large.MPQ")
                .put("copiedBytes", 256L)
                .put("expectedBytes", 1_024L))
            .put("dataStages", JSONArray().put(JSONObject()
                .put("stage", DataStage.MMAPS.name)
                .put("state", DataStageState.RUNNING.name)
                .put("processed", 1)
                .put("total", 22)
                .put("bytesWritten", 585_816_544L)
                .put("checkpoint", "0")
                .put("updatedAtMs", 8_500L)))
            .put("sourceUri", "content://import/tree/primary")
            .put("worker", JSONObject()
                .put("present", true)
                .put("state", "working")
                .put("rssBytes", 128L * 1024L * 1024L)
                .put("threadCount", 12)
                .put("processCount", 2))

        val progress = ImportProgressPresentation.fromJson(value)

        assertEquals("AI navigation maps", progress.activeStage?.title)
        assertTrue(progress.explanation.contains("counter may stay unchanged"))
        assertEquals("Data/large.MPQ", progress.currentPath)
        assertEquals(0.25f, progress.currentFileFraction, 0.0001f)
        assertEquals(2, progress.processCount)
        assertEquals("content://import/tree/primary", progress.sourceUri)
        assertEquals(1L, progress.updatedAgeSeconds(10_500L))
    }

    @Test fun handlesOldOrIncompleteStatusWithoutInventingActivity() {
        val progress = ImportProgressPresentation.fromJson(JSONObject().put("phase", "PAUSED"))

        assertEquals("Import paused", progress.phaseTitle)
        assertFalse(progress.workerPresent)
        assertNull(progress.sourceUri)
        assertTrue(progress.stages.isEmpty())
        assertEquals(0f, progress.byteFraction, 0f)
    }

    @Test fun formatsBoundedHumanReadableValues() {
        assertEquals("1.5 GiB", formatImportBytes(1_610_612_736L))
        assertEquals("25.0%", formatImportPercent(0.25f))
        assertEquals("100.0%", formatImportPercent(2f))
        assertEquals("Working", importWorkerLabel("working", present = true))
        assertEquals("Worker not running", importWorkerLabel("working", present = false))
    }

    @Test fun busyPhasesCoverOnlyExecutingImports() {
        listOf("IDLE", "PAUSED", "COMPLETE", "CANCELLED", "FAILED").forEach {
            assertFalse("phase $it must not count as busy", importPhaseBusy(it))
        }
        listOf("DISCOVERING", "PREFLIGHT", "COPYING", "VERIFYING", "PUBLISHING", "PREPARING_DATA").forEach {
            assertTrue("phase $it must count as busy", importPhaseBusy(it))
        }
        assertFalse(importPhaseBusy("UNKNOWN_PHASE"))
    }

    @Test fun formatsDurationsInHumanUnits() {
        assertEquals("0s", formatImportDuration(0L))
        assertEquals("1s", formatImportDuration(1_200L))
        assertEquals("59s", formatImportDuration(59_499L))
        assertEquals("1m 0s", formatImportDuration(59_500L))
        assertEquals("10m 30s", formatImportDuration(630_000L))
        assertEquals("2h 4m 7s", formatImportDuration((2 * 3600 + 4 * 60 + 7) * 1000L))
        assertEquals("0s", formatImportDuration(-5_000L))
    }

    @Test fun presentsBenchmarkAndDeviceSpec() {
        val value = JSONObject()
            .put("phase", ImportPhase.COMPLETE.name)
            .put("updatedAtMs", 9_000L)
            .put("dataStages", JSONArray())
            .put("device", JSONObject()
                .put("label", "Retroid Pocket 6")
                .put("soc", "Snapdragon 8 Gen 2")
                .put("activelyCooled", true)
                .put("abi", "arm64-v8a")
                .put("api", 33)
                .put("cores", 8)
                .put("ramBytes", 8L * 1024 * 1024 * 1024))
            .put("benchmark", JSONObject()
                .put("latest", JSONObject()
                    .put("deviceLabel", "Retroid Pocket 6")
                    .put("totalMs", 7_412_000L)
                    .put("copyMs", 2_100_000L)
                    .put("dataMs", 5_312_000L)
                    .put("stageDurations", JSONObject()
                        .put("MMAPS", 3_200_000L)
                        .put("DBC_MAPS", 240_000L))
                    .put("mmapMaps", 43)
                    .put("mmapThreads", 6)
                    .put("createdAtMs", 9_000L))
                .put("history", JSONArray()
                    .put(JSONObject().put("deviceLabel", "Retroid Pocket 6")
                        .put("totalMs", 7_412_000L).put("createdAtMs", 9_000L))))
        val presentation = ImportProgressPresentation.fromJson(value)
        val benchmark = presentation.benchmark
        assertEquals("Retroid Pocket 6", benchmark?.deviceLabel)
        assertEquals(7_412_000L, benchmark?.totalMs)
        assertEquals(listOf("DBC_MAPS" to 240_000L, "MMAPS" to 3_200_000L),
            benchmark?.stages?.map { it.stage to it.durationMs })
        assertEquals(43, benchmark?.mmapMaps)
        assertEquals(1, presentation.benchmarkHistory.size)
        val device = presentation.device
        assertEquals("Retroid Pocket 6", device?.label)
        assertEquals("Snapdragon 8 Gen 2", device?.soc)
        assertTrue(device?.activelyCooled == true)
        assertNull(presentation.error)
    }

    // F8 B: the watchdog words its notices from the OS-recorded death reason,
    // so the parsed model must carry it through (and stay null when absent).
    @Test fun parsesWorkerExitReasonForWatchdogWording() {
        fun progress(exitReason: Any?) = ImportProgressPresentation.fromJson(
            JSONObject().put("phase", ImportPhase.COPYING.name)
                .put("worker", JSONObject()
                    .put("present", false)
                    .put("lastExitReason", exitReason)
                    .put("lastExitAgeMs", 4_000L)))
        assertEquals("LOW_MEMORY", progress("LOW_MEMORY").workerExitReason)
        assertEquals(4_000L, progress("LOW_MEMORY").workerExitAgeMs)
        assertNull(progress(JSONObject.NULL).workerExitReason)
    }

    // F8 C: during data preparation lastRelativePath holds the raw stage
    // checkpoint ("MMAPS:map 169 (20/43) …"); the stage pane already renders
    // it, so the copy card must not show it as a file path.
    @Test fun dataPreparationCheckpointIsNotShownAsCurrentFilePath() {
        val progress = ImportProgressPresentation.fromJson(
            JSONObject().put("phase", ImportPhase.PREPARING_DATA.name)
                .put("lastRelativePath", "MMAPS:map 169 (20/43) tiles 50 gen-cpu 0s")
                .put("dataStages", JSONArray().put(JSONObject()
                    .put("stage", DataStage.MMAPS.name)
                    .put("state", DataStageState.RUNNING.name)
                    .put("processed", 19)
                    .put("total", 43)
                    .put("checkpoint", "map 169 (20/43) tiles 50 gen-cpu 0s"))))
        assertNull(progress.currentPath)
        assertEquals("map 169 (20/43) tiles 50 gen-cpu 0s", progress.activeStage?.checkpoint)
    }

    // F8 C: verify/publish ticks write "(n/total) path" into lastRelativePath;
    // that phase has no active copy file, so the text must surface as the
    // current path.
    @Test fun verifyingTickSurfacesAsCurrentPath() {
        val progress = ImportProgressPresentation.fromJson(
            JSONObject().put("phase", ImportPhase.VERIFYING.name)
                .put("lastRelativePath", "(87/150) Data/terrain.MPQ"))
        assertEquals("(87/150) Data/terrain.MPQ", progress.currentPath)
    }

    // F8 C: finite data stages now journal total 0 (unknown length) while
    // running; the parsed model keeps that so the UI shows an indeterminate
    // bar instead of a frozen "0/1" checkpoint.
    @Test fun runningFiniteStageKeepsUnknownTotal() {
        val progress = ImportProgressPresentation.fromJson(
            JSONObject().put("phase", ImportPhase.PREPARING_DATA.name)
                .put("dataStages", JSONArray().put(JSONObject()
                    .put("stage", DataStage.VMAP_EXTRACT.name)
                    .put("state", DataStageState.RUNNING.name)
                    .put("processed", 0)
                    .put("total", 0))))
        assertEquals(0, progress.activeStage?.total)
        assertEquals(DataStage.VMAP_EXTRACT.name, progress.activeStage?.id)
    }

    // F8 C round 2: a FAILED data stage leaves the raw stage checkpoint in
    // lastRelativePath; it must not render as a file path, while a FAILED copy
    // keeps showing the real file that failed.
    @Test fun failedStageResidueIsSuppressedButFailedCopyPathShows() {
        val failedStage = ImportProgressPresentation.fromJson(
            JSONObject().put("phase", ImportPhase.FAILED.name)
                .put("lastError", "DATA_MMAPS: generator exited 1")
                .put("lastRelativePath", "MMAPS:map 169 (20/43) tiles 50 gen-cpu 0s"))
        assertNull(failedStage.currentPath)

        val failedCopy = ImportProgressPresentation.fromJson(
            JSONObject().put("phase", ImportPhase.FAILED.name)
                .put("lastError", "SOURCE_CHANGED: Data/terrain.MPQ")
                .put("lastRelativePath", "Data/terrain.MPQ"))
        assertEquals("Data/terrain.MPQ", failedCopy.currentPath)
    }
}
