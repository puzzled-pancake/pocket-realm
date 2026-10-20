package com.pocketrealm.importer

import com.pocketrealm.client.ClientFlavors
import org.json.JSONObject
import java.io.File

/**
 * shared plumbing for the durable generation pointers
 * (`ClientGenerationStore` managed-client generations and
 * `DataPreparationStore` o11 data generations).
 *
 * The single-namespace `active.json` / `previous.json` pair becomes per-flavor
 * (`active-vanilla.json`, `active-tbc.json`, …) so activating one flavor's
 * generation can never delete another flavor's data. The un-suffixed files
 * remain readable exactly once, as a LEGACY SEED: on first read, when the
 * canonical per-flavor pointer is absent and the legacy pointer exists, the
 * store adopts it (atomic rename for the client store, copy-then-adopt for
 * the data store, whose fail-closed reader may hold the source open) and
 * continues under the canonical name — zero data loss on the update boot, and
 * never both names readable as authoritative (the canonical name wins the
 * moment it exists).
 *
 * The pure pieces (file names, the scoped prune keep-set, manifest→flavor
 * resolution) live here so they are JVM-unit-testable; the store classes own
 * the filesystem mechanics (Os.rename / atomic copy) exactly as before.
 */
internal object GenerationPointers {

    /** The pre-flavor un-namespaced pointer names, readable only as a seed. */
    const val LEGACY_ACTIVE = "active.json"
    const val LEGACY_PREVIOUS = "previous.json"

    /** Canonical per-flavor pointer names, e.g. `active-vanilla.json`. */
    fun activePointerName(flavorId: String): String = "active-$flavorId.json"
    fun previousPointerName(flavorId: String): String = "previous-$flavorId.json"

    /**
     * The prune keep-set for one activation (G3-5 scoped destructive
     * maintenance). The activating generation and its prior predecessor stay
     * (today's invariant); every other generation whose manifest resolves to
     * a DIFFERENT flavor stays too — activating a TBC generation must never
     * consume the vanilla generation (hours of on-device mmaps work), and
     * vice versa. A generation whose manifest is unreadable is treated as
     * vanilla-foreign and KEPT: fail-safe, never fail-destructive. Within a
     * single flavor the old behavior is preserved exactly: older same-flavor
     * generations are retired.
     *
     * [generationFlavor] resolves a generation directory's manifest to its
     * flavor id, or null when the manifest is absent/unreadable/unresolvable.
     */
    fun scopedKeepSet(
        activatingGeneration: String,
        priorGeneration: String?,
        generations: List<File>,
        activatingFlavorId: String,
        generationFlavor: (File) -> String?,
    ): Set<String> {
        val keep = buildSet {
            add(activatingGeneration)
            priorGeneration?.let(::add)
            generations.forEach { directory ->
                if (directory.name in this) return@forEach
                val flavor = generationFlavor(directory)
                if (flavor == null || flavor != activatingFlavorId) add(directory.name)
            }
        }
        return keep
    }

    /**
     * A client generation's flavor, resolved from its stamped `clientId`
     * (written at publish from the verified PE identity). Null when the
     * manifest is absent/unreadable or the clientId is unresolvable — the
     * caller keeps the generation (fail-safe).
     */
    fun clientGenerationFlavor(generationDirectory: File): String? = runCatching {
        val manifest = JSONObject(File(generationDirectory, "client-manifest.json").readText())
        ClientFlavors.byClientId(manifest.getString("clientId"))?.flavorId
    }.getOrNull()

    /**
     * A data generation's flavor, resolved from its `cmangosFamily` (the
     * flavor dimension the o11 manifest has carried since schema 1). Null when
     * the manifest is absent/unreadable or the family is unresolvable.
     */
    fun dataGenerationFlavor(generationDirectory: File): String? = runCatching {
        val manifest = JSONObject(File(generationDirectory, "data-manifest.json").readText())
        ClientFlavors.byServerFamily(manifest.getString("cmangosFamily"))?.flavorId
    }.getOrNull()
}
