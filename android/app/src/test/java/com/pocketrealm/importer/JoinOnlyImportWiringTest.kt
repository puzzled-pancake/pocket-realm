package com.pocketrealm.importer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join-only import copies the game files and skips the server-data
 * build, and the build can run later on its own. The repo has no
 * Robolectric lane, so these pins hold the source shape of the service and
 * importer wiring.
 */
class JoinOnlyImportWiringTest {
    private fun source(relative: String): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "schemas/flavor.json").isFile }
            ?: error("repo root not found")
        return File(root, "android/app/src/main/java/com/pocketrealm/$relative").readText()
    }

    @Test
    fun joinOnlySkipsTheServerBuildAndItsSpace() {
        val service = source("importer/ImportWorkerService.kt")
        val joinOnlyArm = Regex(
            """else if \(joinOnly\) ManagedClientImporter\((.*?)\) else ManagedClientImporter""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(service)?.groupValues?.get(1)
        assertTrue("the join-only importer construction exists", joinOnlyArm != null)
        assertTrue("join only never prepares server data", "prepareData = false" in joinOnlyArm!!)
        assertTrue("join only reserves no server-data space", "preparedEstimate = 0" in joinOnlyArm)
    }

    @Test
    fun bothImportArmsReadTheRememberedChoice() {
        val service = source("importer/ImportWorkerService.kt")
        val reads = Regex("""buildImporter\(testProfile, joinOnly\(applicationContext\)\)""")
            .findAll(service).count()
        assertTrue("the folder and archive arms both honor join only (found $reads)", reads == 2)
    }

    @Test
    fun theServerBuildRunsForTheActiveClientAndClearsJoinOnly() {
        val service = source("importer/ImportWorkerService.kt")
        assertTrue(
            "the build action is routed",
            "ACTION_PREPARE_SERVER_DATA) return startServerDataBuild(startId)" in service,
        )
        assertTrue(
            "a finished build clears the join-only marker",
            Regex(
                """prepareActiveServerData\(.*?\)\s*setJoinOnly\(applicationContext, false\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(service),
        )
        val importer = source("importer/ManagedClientImporter.kt")
        assertTrue(
            "the build prepares the active generation's own files",
            "dataStore.prepare(active, generations.generation(active), onDataStageTick)" in importer,
        )
    }

    @Test
    fun theStatusSaysWhetherServerDataIsReady() {
        val service = source("importer/ImportWorkerService.kt")
        assertTrue(""".put("serverDataReady", serverDataReady(context))""" in service)
        val screen = source("ui/ClientScreen.kt")
        assertTrue(
            "the screen offers the build once the game files are in",
            "needsServerData = ui.importComplete && !ui.serverDataReady" in screen,
        )
    }
}
