package com.pocketrealm.client

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 (lanes item 10): the multi-qualified routing WIRING pin for the eight
 * former `qualified().single()` sites. Five of the six shipping classes are
 * Context-bound (no Robolectric on this lane — the module's unit tests are
 * pure-JVM by design), so the cross-lane wiring is pinned as a source
 * contract over the shipped source — the same pattern as
 * RealmBuildsProjectionContractTest for the realmd-builds lane. The
 * resolution BEHAVIOR under two qualified flavors is pinned behaviorally in
 * MultiQualifiedLaneResolutionTest (every lane's exact resolve shape) and
 * DatabaseSnapshotMultiQualifiedLaneTest (the one pure-JVM site end to
 * end).
 *
 * Each site must route through the [ActiveFlavor] machine with its own
 * lane-suffix constant — the persisted-pick shape
 * (`ActiveFlavorState(<settings pick>).resolve(ActiveFlavor.qualifiedFlavors, LANE)`)
 * for the cross-process sites, the holder shape
 * (`ActiveFlavor.resolve(LANE)`) for the UI-process sites — and no
 * non-comment `qualified().single()` may remain in any of the six files.
 */
class MultiQualifiedRoutingContractTest {

    private fun mainSource(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/pocketrealm/$relative"),
            File("app/src/main/java/com/pocketrealm/$relative"),
            File("android/app/src/main/java/com/pocketrealm/$relative"),
            File("../android/app/src/main/java/com/pocketrealm/$relative"),
        )
        val source = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("$relative not found from ${File(".").absolutePath}")
        return source.readText(Charsets.UTF_8)
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    /** Code lines only — strips // and /* */ comment lines the docs carry. */
    private fun codeLines(source: String): List<String> = source.lines()
        .map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("/*") || it.startsWith("*") }

    private val sixSiteFiles = listOf(
        "client/WineRuntimeStore.kt",
        "database/DatabaseSnapshotStore.kt",
        "importer/ClientGenerationStore.kt",
        "importer/DataPreparationStore.kt",
        "ingame/InGameSettingsEditor.kt",
        "supervisor/AndroidRuntimeBackend.kt",
    )

    @Test
    fun noSiteShipsAQualifiedSingleLandmineInCode() {
        for (relative in sixSiteFiles) {
            val offending = codeLines(mainSource(relative))
                .filter { "qualified().single()" in it }
            assertEquals(
                "$relative: the D9 landmine must stay retired in code (comments " +
                    "documenting the former crash are fine)",
                emptyList<String>(),
                offending,
            )
        }
    }

    @Test
    fun wineRuntimeStoreArmAuthorizationResolvesThePersistedPick() {
        val source = mainSource("client/WineRuntimeStore.kt")
        assertEquals(
            "exactly one ARM runtime authorization resolve, through the machine",
            1,
            occurrences(source, ".resolve(ActiveFlavor.qualifiedFlavors, ARM_RUNTIME_AUTHORIZATION_LANE)"),
        )
        assertTrue(
            "the resolve seeds from the PERSISTED settings pick (:client never sees " +
                "the UI process's in-memory selection)",
            "runBlocking { com.pocketrealm.storage.Settings(context).flow.first().selectedFlavorOrNull() }" in source,
        )
        assertTrue(
            "the refusal names the resolved lane's build",
            "build-\${lane.build} client" in source,
        )
    }

    @Test
    fun databaseSnapshotCompatibilityGateResolvesThePersistedPick() {
        val source = mainSource("database/DatabaseSnapshotStore.kt")
        assertEquals(
            "exactly one restore compatibility resolve, through the machine",
            1,
            occurrences(source, ".resolve(ActiveFlavor.qualifiedFlavors, SNAPSHOT_COMPATIBILITY_LANE).flavorId"),
        )
        assertTrue(
            "the gate seeds from the injected persisted-pick seam",
            "ActiveFlavorState(persistedActiveFlavorId())" in source,
        )
    }

    @Test
    fun clientGenerationActiveDefaultResolvesThroughTheHolder() {
        val source = mainSource("importer/ClientGenerationStore.kt")
        assertEquals(
            "the activeGeneration default arg resolves through the machine exactly once",
            1,
            occurrences(source, "ActiveFlavor.resolve(ACTIVE_GENERATION_LANE)"),
        )
        assertTrue(
            "the no-arg default is the multi-qualified-safe resolve (the desktop " +
                "twin's exact shape, desktop ClientGenerationStore.kt:81)",
            "fun activeGeneration(flavor: ClientFlavor = ActiveFlavor.resolve(ACTIVE_GENERATION_LANE)): String?" in source,
        )
    }

    @Test
    fun dataPreparationPublishAndRecoverBothResolveThroughTheHolder() {
        val source = mainSource("importer/DataPreparationStore.kt")
        assertEquals(
            "publish AND recover each resolve the active lane (the desktop twin's " +
                "pair, desktop DataPreparationStore.kt:381/:418)",
            2,
            occurrences(source, "ActiveFlavor.resolve(SERVER_DATA_PREPARATION_LANE)"),
        )
    }

    @Test
    fun inGameEditorLeaseGateResolvesThroughTheHolderInsideTheFailSoftBoundary() {
        val source = mainSource("ingame/InGameSettingsEditor.kt")
        assertEquals(
            "exactly one lease-gate resolve, through the machine",
            1,
            occurrences(source, "ActiveFlavor.resolve(INGAME_EDITOR_LANE).clientId"),
        )
        assertTrue(
            "the resolve sits inside the runCatching boundary — an ambiguous " +
                "unselected registry state is a fail-soft no-lease, never a crash",
            "runCatching {" in source && "}.getOrNull()" in source,
        )
    }

    @Test
    fun androidRuntimeBackendLaunchAndProjectionResolveThePersistedPick() {
        val source = mainSource("supervisor/AndroidRuntimeBackend.kt")
        assertEquals(
            "the launch lane resolves through the machine exactly once (the " +
                "launchLaneFlavor definition both the probe path and startClient call)",
            1,
            occurrences(source, ".resolve(ActiveFlavor.qualifiedFlavors, CLIENT_BUILD_LANE)"),
        )
        assertTrue(
            "the launch resolve seeds from the PERSISTED runtime-settings pick",
            "ActiveFlavorState(runtimeSettings.selectedFlavorOrNull())" in source,
        )
        assertEquals(
            "launchLaneFlavor is declared AND consumed by both launch paths",
            3,
            occurrences(source, "launchLaneFlavor("),
        )
        assertEquals(
            "the realmd-builds projection resolves through the machine (the " +
                "REALMD_BUILDS_LANE pin RealmBuildsProjectionContractTest also carries)",
            1,
            occurrences(source, ".resolve(ActiveFlavor.qualifiedFlavors, REALMD_BUILDS_LANE).realmdAcceptedBuilds"),
        )
        assertEquals(
            ":supervisor must never resolve the in-memory holder — its selection " +
                "is UI-process-local state that never crosses the process boundary",
            0,
            occurrences(source, "ActiveFlavor.resolve("),
        )
        assertTrue(
            "the former class-load CLIENT_BUILD_ID landmine stays retired in code " +
                "(the KDoc naming the retired crash is fine)",
            codeLines(source).none { "CLIENT_BUILD_ID" in it },
        )
    }
}
