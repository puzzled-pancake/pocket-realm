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
            .put("worker", JSONObject()
                .put("present", true)
                .put("state", "working")
                .put("cpuPercent", 137.5)
                .put("sampleWindowMs", 1_000L)
                .put("rssBytes", 128L * 1024L * 1024L)
                .put("threadCount", 12)
                .put("processCount", 2))

        val progress = ImportProgressPresentation.fromJson(value)

        assertEquals("AI navigation maps", progress.activeStage?.title)
        assertTrue(progress.explanation.contains("counter may stay unchanged"))
        assertEquals("Data/large.MPQ", progress.currentPath)
        assertEquals(0.25f, progress.currentFileFraction, 0.0001f)
        assertEquals(137.5, progress.cpuPercent!!, 0.001)
        assertEquals(2, progress.processCount)
        assertEquals(1L, progress.updatedAgeSeconds(10_500L))
    }

    @Test fun handlesOldOrIncompleteStatusWithoutInventingActivity() {
        val progress = ImportProgressPresentation.fromJson(JSONObject().put("phase", "PAUSED"))

        assertEquals("Import paused", progress.phaseTitle)
        assertFalse(progress.workerPresent)
        assertNull(progress.cpuPercent)
        assertTrue(progress.stages.isEmpty())
        assertEquals(0f, progress.byteFraction, 0f)
        assertEquals("Sampling…", formatImportCpu(null))
    }

    @Test fun formatsBoundedHumanReadableValues() {
        assertEquals("1.5 GiB", formatImportBytes(1_610_612_736L))
        assertEquals("25.0%", formatImportPercent(0.25f))
        assertEquals("100.0%", formatImportPercent(2f))
        assertEquals("Working", importWorkerLabel("working", present = true))
        assertEquals("Worker not running", importWorkerLabel("working", present = false))
    }
}
