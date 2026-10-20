package com.pocketrealm.importer

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TwoQualifiedFlavors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * (desktop-twin serious): the desktop
 * DataPreparationStore.publish stamped the data-manifest flavor from the
 * LIVE ActiveFlavor machine while the desktop verifiedSourceFlavor helper
 * existed with ZERO call sites — the D48/R2-4 mid-prep-switch quarantine
 * (the android twin's publish calls it) had never landed, and the file's
 * own comment claimed the opposite of what the code did. A Home switch
 * during the hours-long prep published the generation + the per-flavor
 * active pointer under the switched lane while the bytes were the source
 * flavor's — the exact cross-flavor data flow recorded as fixed.
 *
 * The quarantine tests drive the SAME helper publish now stamps through
 * (the android agree/refuse/unresolvable/bare-root matrix), plus the
 * source pin that publish routes through it.
 */
class DataPreparationSourceQuarantineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun clientRootWith(clientId: String?): File {
        val root = tmp.newFolder()
        if (clientId != null) {
            File(root, "client-manifest.json")
                .writeText("""{"clientId":"$clientId"}""")
        }
        return root
    }

    @Test
    fun anAgreeingGenerationPublishesUnderItsVerifiedFlavor() {
        TwoQualifiedFlavors.install(selection = "tbc")
        try {
            val flavor = verifiedSourceFlavor(
                clientRootWith(ClientFlavors.TBC.clientId),
            )
            assertEquals("tbc", flavor.flavorId)
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun aVanillaGenerationUnderASwitchedLaneRefusesLoud() {
        TwoQualifiedFlavors.install(selection = "tbc")
        try {
            val thrown = runCatching {
                verifiedSourceFlavor(clientRootWith(ClientFlavors.VANILLA.clientId))
            }.exceptionOrNull()
            assertNotNull("a mid-prep switch must not relabel cross-flavor bytes", thrown)
            assertTrue(
                "the refusal names the identities and the switch",
                thrown?.message?.contains("mid-prep flavor switch") == true &&
                    thrown?.message?.contains("wow-1.12.1-5875") == true,
            )
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun anUnresolvableSourceIdentityRefusesLoud() {
        TwoQualifiedFlavors.install(selection = "tbc")
        try {
            val thrown = runCatching {
                verifiedSourceFlavor(clientRootWith("not-a-registered-lane"))
            }.exceptionOrNull()
            assertNotNull("an unknown clientId must refuse", thrown)
            assertTrue(
                thrown?.message?.contains("resolves to no registered flavor") == true,
            )
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun aBareRootDegradesToTheActiveLane() {
        TwoQualifiedFlavors.install(selection = "tbc")
        try {
            // no client-manifest.json (a legacy source dir): the active
            // lane answers — the helper's documented degrade
            assertEquals("tbc", verifiedSourceFlavor(clientRootWith(null)).flavorId)
        } finally {
            ActiveFlavor.reset()
        }
    }

    @Test
    fun publishStampsTheVerifiedSourceIdentityNotTheLiveMachine() {
        val repoRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "schemas/flavor.json").isFile }
            ?: error("repo root not found")
        val source = File(
            repoRoot,
            "desktop/src/main/kotlin/com/pocketrealm/importer/DataPreparationStore.kt",
        ).readText()
        val publish = source
            .split("private fun publish(", limit = 2)[1]
            .split("/**", limit = 2)[0]
        assertTrue(
            "publish must receive the client root (the verified identity's source)",
            "clientRoot: File" in publish,
        )
        assertTrue(
            "publish must stamp through verifiedSourceFlavor(clientRoot)",
            "verifiedSourceFlavor(clientRoot)" in source,
        )
        assertTrue(
            "the stamp site must not resolve the live machine",
            "val flavor = ActiveFlavor.resolve(SERVER_DATA_PREPARATION_LANE)" !in publish,
        )
        assertTrue(
            "the resume path carries the same quarantine",
            "verifiedSourceFlavor(clientRoot)" in source.split("private fun recover(", limit = 2)[1],
        )
    }
}
