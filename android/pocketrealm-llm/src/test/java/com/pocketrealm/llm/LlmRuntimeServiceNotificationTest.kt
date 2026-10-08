package com.pocketrealm.llm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification source contract (the LlmRuntimeServiceWarmUpTest /
 * AndroidPortAssetTest pattern): buildNotification is a private service
 * method returning a real framework Notification - not unit-drivable - so
 * the load-bearing behavior is pinned against the source. The specialUse
 * FGS notification must ship the module's OWN status icon: the debt-era
 * android system placeholder (stat_sys_data_bluetooth) rendered a
 * Bluetooth glyph that said nothing about the LLM runtime. This library
 * cannot reach the app module's launcher drawable, hence the dedicated
 * ic_stat_llm resource.
 */
class LlmRuntimeServiceNotificationTest {

    private fun moduleFile(rel: String): File =
        sequenceOf(
            File("src/main/$rel"),
            File("pocketrealm-llm/src/main/$rel"),
        ).firstOrNull { it.isFile }
            ?: error("$rel not found from test working dir")

    private val source: String by lazy {
        moduleFile("java/com/pocketrealm/llm/LlmRuntimeService.kt").readText()
    }

    @Test fun `fgs notification ships the module's own status icon`() {
        assertTrue(".setSmallIcon(R.drawable.ic_stat_llm)" in source)
        // the system-placeholder glyph and its debt marker must be gone
        assertTrue("stat_sys_data_bluetooth" !in source)
        assertTrue("// replace with app icon" !in source)
    }

    @Test fun `the status icon ships in this module as an alpha-only vector`() {
        // the service reference only compiles when the resource exists;
        // this pins the resource itself. Small icons are rendered from the
        // alpha channel only, so the fill must stay flat white.
        val icon = moduleFile("res/drawable/ic_stat_llm.xml").readText()
        assertTrue("<vector" in icon)
        assertTrue("android:fillColor=\"#FFFFFFFF\"" in icon)
    }
}
