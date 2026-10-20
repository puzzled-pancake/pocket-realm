package com.pocketrealm.addons

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File

/**
 * 7 negative interface matrix: every client-line mismatch is refused
 * fail-closed with the ACTIVE lane's own accepted-interface copy — never a
 * silent install, never another lane's copy. The three accepts are positive
 * controls (a flavor's own interface passes its lane).
 *
 * Every interface number resolves through the OWNING registry
 * ([ClientFlavors] — the manifest's `addon_interfaces` data per
 * [com.pocketrealm.client.ClientFlavor.addonInterfaces]); the bare numbers
 * live only in their owning table, and the matrix tracks a future
 * interface change automatically.
 */
class AddonInterfaceMatrixTest {

    private val vanillaIfaces: List<Int> = ClientFlavors.VANILLA.addonInterfaces.toList()
    private val tbcIfaces: List<Int> = ClientFlavors.TBC.addonInterfaces.toList()
    private val wotlkIfaces: List<Int> = ClientFlavors.WOTLK.addonInterfaces.toList()
    private val allIfaces: List<Int> = (vanillaIfaces + tbcIfaces + wotlkIfaces).distinct().sorted()

    private fun ifacesFor(flavorId: String): List<Int> = when (flavorId) {
        "vanilla" -> vanillaIfaces
        "tbc" -> tbcIfaces
        "wotlk" -> wotlkIfaces
        else -> error("unknown lane $flavorId")
    }

    /** All interfaces of the OTHER lanes — the foreign set a refusal must
     * never offer as accepted. */
    private fun foreignIfaces(flavorId: String): List<Int> =
        allIfaces.filter { it !in ifacesFor(flavorId) }

    /** All three flavors qualified (the flip shape) through the test
     * seam; the selection is the active lane under test. */
    private fun installAllQualified(selection: String) {
        ActiveFlavor.reset()
        val lanes = listOf(
            ClientFlavors.VANILLA,
            ClientFlavors.TBC.copy(qualified = true),
            ClientFlavors.WOTLK.copy(qualified = true),
        )
        ActiveFlavor.qualifiedSource = { lanes }
        ActiveFlavor.select(selection)
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("addon-matrix", ".zip")
        ZipArchiveOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                val entry = org.apache.commons.compress.archivers.zip.ZipArchiveEntry(name)
                out.putArchiveEntry(entry)
                out.write(text.toByteArray())
                out.closeArchiveEntry()
            }
        }
        return file
    }

    private fun archiveFor(interfaceLine: Int): File = zip(
        "owner-addon-abc/Example.toc" to "## Interface: $interfaceLine\nExample.lua\n",
        "owner-addon-abc/Example.lua" to "print('ok')\n",
    )

    @Test fun `every interface-lane mismatch refuses with the lane's own copy`() {
        // (archive interface = a FOREIGN lane's interface, active lane, the
        // lane's accepted interface) — every cross-lane pair, registry-derived.
        val mismatches = buildList {
            listOf("vanilla", "tbc", "wotlk").forEach { lane ->
                val accepted = ifacesFor(lane).first()
                foreignIfaces(lane).forEach { foreign ->
                    add(Triple(foreign, lane, accepted))
                }
            }
        }
        assertTrue("the mismatch matrix must not be empty", mismatches.isNotEmpty())
        mismatches.forEach { (interfaceLine, lane, accepted) ->
            installAllQualified(lane)
            try {
                // an EXPLICIT policy pins the accepted set to the lane
                // under test, so the companion's frozen init state cannot
                // leak across tests sharing this JVM.
                val policy = AddonArchiveValidator.Policy(
                    acceptedInterfaces = setOf(accepted),
                )
                val failure = assertThrows(
                    "a $interfaceLine archive must refuse on the $lane lane",
                    IllegalArgumentException::class.java,
                ) {
                    AddonArchiveValidator().validate(archiveFor(interfaceLine), "OtherName", policy = policy)
                }
                val message = failure.message ?: ""
                assertTrue(
                    "$lane: the refusal must name the lane's accepted interface $accepted: $message",
                    accepted.toString() in message,
                )
                // Per-flavor copy: the refusal names the ACTIVE lane's set,
                // never a foreign lane's interface as the accepted one.
                foreignIfaces(lane).forEach { foreign ->
                    assertTrue(
                        "$lane: the refusal must not offer $foreign as accepted: $message",
                        !Regex("accepts: [^)]*\\b$foreign\\b").containsMatchIn(message),
                    )
                }
            } finally {
                ActiveFlavor.reset()
            }
        }
    }

    @Test fun `each lane accepts its own interface - positive controls`() {
        listOf("vanilla", "tbc", "wotlk").forEach { lane ->
            val own = ifacesFor(lane).first()
            installAllQualified(lane)
            try {
                val result = AddonArchiveValidator().validate(
                    archiveFor(own), "DifferentName",
                )
                assertTrue(
                    "$lane: its own $own interface must validate",
                    result.addonFolders == listOf("Example"),
                )
            } finally {
                ActiveFlavor.reset()
            }
        }
    }

    @Test fun `multi-root-TOC refusal is lane-neutral on a tbc lane - R6-r3-1`() {
        // the multi-root-TOC refusal must not hard-code a vanilla
        // lane name — the accepted-interface set is the ACTIVE flavor's.
        val tbcInterface = ClientFlavors.TBC.addonInterfaces.first()
        installAllQualified("tbc")
        try {
            val archive = zip(
                "owner-addon-abc/First.toc" to "## Interface: $tbcInterface\nFirst.lua\n",
                "owner-addon-abc/First.lua" to "-- first\n",
                "owner-addon-abc/Second.toc" to "## Interface: $tbcInterface\nSecond.lua\n",
                "owner-addon-abc/Second.lua" to "-- second\n",
            )
            val failure = assertThrows(
                IllegalArgumentException::class.java,
            ) {
                AddonArchiveValidator().validate(archive, "Repository")
            }
            val message = failure.message ?: ""
            assertTrue(
                "the refusal must not hard-code a vanilla-era lane name: $message",
                "Vanilla 1.12" !in message,
            )
            assertTrue(
                "the refusal must name the lane's accepted interface ($tbcInterface): $message",
                "$tbcInterface" in message,
            )
        } finally {
            ActiveFlavor.reset()
        }
    }
}
