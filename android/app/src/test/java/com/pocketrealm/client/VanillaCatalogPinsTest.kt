package com.pocketrealm.client

import com.pocketrealm.ingame.WowVanillaSettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * G3-10 wave-0 pins over the frozen vanilla catalogs:
 *
 *  - the `WowBindingCategory` / `WowBindingSemantics` enums are never
 *    re-ordered or renamed — the controller profile persists enum ordinals
 * H4-2's WotLK vehicle/override categories and H4-11's per-flavor capture
 *    add NEW entries in per-flavor catalogs, appended at the end);
 *  - the `WowVanillaSettingsCatalog` stays CATALOG_VERSION 2 over its pinned
 *    109-entry capture, and the rendered entry digest matches the committed
 *    literal — any in-place content edit ("shared" wording, retuned rows)
 *    fails here; a genuine content fix is a new CATALOG_VERSION plus a
 * Decision Log entry (G3-10 item 1).
 */
class VanillaCatalogPinsTest {

    @Test fun `binding category enum ordinals and names are pinned in order`() {
        val pinned = listOf(
            "MOVEMENT", "CHAT", "MAIN_ACTION_BAR", "SELF_ACTION_BAR", "SHAPESHIFT_BAR",
            "BONUS_ACTION_BAR", "ACTION_PAGES", "TARGETING", "CHARACTER_AND_UI", "BAGS",
            "MISCELLANEOUS", "CAMERA", "MULTI_ACTION_BARS", "RAID_MARKERS", "INTERNAL_POINTER",
        )
        // G3-10 freezes the vanilla 15 IN ORDER (ordinals 0..14 — the
        // controller profile persists ordinals); per-flavor categories
        // (H4-2 totem/vehicle, H4-11 captures) may only APPEND after them,
        // never insert before or within. take(n) equality catches an
        // insert-before, a rename, a reorder, and a removal alike.
        assertEquals(pinned, WowBindingCategory.entries.take(pinned.size).map { it.name })
        assertEquals(
            pinned.indices.toList(),
            WowBindingCategory.entries.take(pinned.size).map { it.ordinal },
        )
    }

    @Test fun `binding category display names are pinned`() {
        val pinned = listOf(
            "Movement", "Chat", "Main action bar", "Self-cast action bar", "Shapeshift bar",
            "Bonus action bar", "Action pages", "Targeting", "Character and interface", "Bags",
            "Miscellaneous", "Camera", "Additional action bars", "Raid markers",
            "Internal pointer actions",
        )
        assertEquals(pinned, WowBindingCategory.entries.take(pinned.size).map { it.displayName })
    }

    @Test fun `binding semantics enum ordinals are pinned`() {
        assertEquals(listOf("KEY_LIKE", "INTERNAL_POINTER_HOLD"), WowBindingSemantics.entries.map { it.name })
    }

    @Test fun `settings catalog stays version 2 over the pinned 109-entry capture`() {
        assertEquals(2, WowVanillaSettingsCatalog.CATALOG_VERSION)
        assertEquals(109, WowVanillaSettingsCatalog.all.size)
    }

    @Test fun `settings catalog rendered entries match the committed digest`() {
        val rendering = WowVanillaSettingsCatalog.all.joinToString("\n") { it.toString() }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(rendering.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "8e1ae853b7fa53ec87a7e4bfd54df086aeaef46d7e1b6f824ea1e296b4d53080",
            digest,
        )
    }

    @Test fun `binding catalog version and provenance pins stay fixed`() {
        assertEquals(2, WowVanillaBindingCatalog.CATALOG_VERSION)
        assertEquals("1.12.1", WowVanillaBindingCatalog.WOW_CLIENT_VERSION)
        assertEquals(5875, WowVanillaBindingCatalog.WOW_CLIENT_BUILD)
        assertEquals(
            "776d64ecf708540969e34df9680ffdacb3e8b555",
            WowVanillaBindingCatalog.SOURCE_COMMIT,
        )
    }
}
