package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The desktop storage-root taxonomy: the scratch/exports
 * and database run/snapshots splits exist, ensureDirectories creates
 * them, scratch is purged on clean start (Windows never clears caches
 * under pressure — the app owns it), and verify():Report mirrors the
 * Android diagnostics/wizard report. Pure path policy — no natives.
 */
class DesktopStorageRootsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun rootTaxonomyMirrorsTheAndroidSplit() {
        val roots = DesktopStorageRoots(folder.newFolder("base"))
        assertEquals(File(roots.root, "realm"), roots.realm)
        assertEquals(File(roots.root, "database"), roots.database)
        assertEquals(
            File(roots.database, DatabaseSqliteControlPlane.SQLITE_DATADIR_NAME),
            roots.sqliteDatadir,
        )
        // the database root splits into run + snapshots (the
        // snapshot-store lane's roots) alongside the datadir.
        assertEquals(File(roots.database, "run"), roots.databaseRun)
        assertEquals(File(roots.database, "snapshots"), roots.databaseSnapshots)
        // the MariaDB engine lane's datadir is a SIBLING of
        // the sqlite datadir (the Android dual-datadir layout).
        assertEquals(File(roots.database, "mariadb-datadir"), roots.mariadbDatadir)
        // scratch is the transient staging root under the
        // runtime root (the app-owned cache analog); exports is the ONLY
        // user-facing output root.
        assertEquals(File(roots.runtime, "scratch"), roots.scratch)
        assertEquals(File(roots.root, "exports"), roots.exports)
    }

    @Test
    fun ensureDirectoriesCreatesEveryRootAndPurgesScratch() {
        val roots = DesktopStorageRoots(folder.newFolder("base"))
        roots.ensureDirectories()
        listOf(
            roots.root, roots.realm, roots.database, roots.content, roots.runtime,
            roots.settings, roots.supervisorJournalDir, roots.logs,
            roots.scratch, roots.exports, roots.databaseRun, roots.databaseSnapshots,
            roots.mariadbDatadir,
        ).forEach { dir -> assertTrue("missing root: $dir", dir.isDirectory) }
        // Scratch staging leftovers from a previous session never
        // outlive a clean start.
        val leftover = File(roots.scratch, "staging/leftover.zip")
        leftover.parentFile.mkdirs()
        leftover.writeText("partial")
        roots.ensureDirectories()
        assertFalse("scratch must purge on clean start", leftover.exists())
        assertTrue("the scratch root itself survives the purge", roots.scratch.isDirectory)
        // Durable roots are never touched by the purge.
        val durable = File(roots.database, "classicrealmd.sqlite3")
        durable.writeBytes(byteArrayOf(1))
        roots.ensureDirectories()
        assertTrue(durable.isFile)
    }

    @Test
    fun verifyReportsEveryRootWithUsableBytes() {
        val roots = DesktopStorageRoots(folder.newFolder("base"))
        roots.ensureDirectories()
        val report = roots.verify()
        assertTrue(report.ok)
        assertTrue(report.rootsUnderBase)
        val names = report.roots.map { it.name }
        listOf(
            "realm", "database", "database/datadir", "database/mariadb-datadir",
            "database/run", "database/snapshots",
            "content", "runtime", "runtime/scratch", "settings", "exports",
        ).forEach { name -> assertTrue("verify must report $name", name in names) }
        report.roots.forEach { status ->
            assertTrue("${status.name} must exist", status.exists)
            assertTrue("${status.name} usableBytes", status.usableBytes >= 0)
            assertTrue(
                "${status.path} must live under the base dir",
                File(status.path).startsWith(roots.root),
            )
        }
    }

    @Test
    fun verifyFlagsAMissingRoot() {
        val roots = DesktopStorageRoots(folder.newFolder("base"))
        roots.ensureDirectories()
        assertTrue(roots.databaseSnapshots.deleteRecursively())
        val report = roots.verify()
        assertFalse(report.ok)
        val snapshots = report.roots.first { it.name == "database/snapshots" }
        assertFalse(snapshots.exists)
        assertTrue(report.roots.filter { it.name != "database/snapshots" }.all { it.exists })
    }

    // the additive data-root override ----

    @Test
    fun `the POCKET_REALM_DATA_ROOT override relocates the default root`() {
        System.setProperty(DesktopStorageRoots.ENV_DATA_ROOT, "E:" + java.io.File.separator + "pocket_realm_data")
        try {
            val root = DesktopStorageRoots.defaultRoot()
            assertEquals("E:" + java.io.File.separator + "pocket_realm_data", root.path)
        } finally {
            System.clearProperty(DesktopStorageRoots.ENV_DATA_ROOT)
        }
    }

    @Test
    fun `an unset override keeps the legacy LOCALAPPDATA default`() {
        // The shipped default is unchanged when the override is absent
        // (byte-identical behavior for existing installs).
        System.clearProperty(DesktopStorageRoots.ENV_DATA_ROOT)
        DesktopStorageRoots.hermeticEnvLeg = ""
        try {
            val expectedBase = System.getenv("LOCALAPPDATA")
                ?: System.getProperty("user.home")
            // The env leg is INJECTED as absent (the hermetic seam) a
            // runner whose process carries the user-scope var (every
            // shell started after the relocation) must not flip this
            // default-arm assertion.
            assertEquals(
                java.io.File(expectedBase, "PocketRealm").path,
                DesktopStorageRoots.defaultRoot().path,
            )
        } finally {
            DesktopStorageRoots.hermeticEnvLeg = null
        }
    }
    // R2-1: the override arm fails loud ------

    @Test
    fun `an override pointing at a regular file fails loud naming the override`() {
        val impostor = folder.newFile("pocket-realm-root-impostor")
        System.setProperty(DesktopStorageRoots.ENV_DATA_ROOT, impostor.absolutePath)
        try {
            val roots = DesktopStorageRoots()
            val thrown = runCatching { roots.ensureDirectories() }.exceptionOrNull()
            assertTrue(
                "the misconfigured override must surface ONE actionable error, got $thrown",
                thrown is IllegalStateException,
            )
            assertTrue(
                "the error must name the override variable and its value",
                thrown?.message?.contains("POCKET_REALM_DATA_ROOT") == true &&
                    thrown?.message?.contains(impostor.absolutePath) == true,
            )
            assertFalse(
                "the impostor file must never become a root",
                roots.root.isDirectory,
            )
        } finally {
            System.clearProperty(DesktopStorageRoots.ENV_DATA_ROOT)
        }
    }

    @Test
    fun `a blank override still falls through to the shipped default`() {
        System.setProperty(DesktopStorageRoots.ENV_DATA_ROOT, "")
        DesktopStorageRoots.hermeticEnvLeg = ""
        try {
            val roots = DesktopStorageRoots()
            assertEquals(
                "the blank override falls through (the D50 default arm)",
                java.io.File(
                    System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
                    "PocketRealm",
                ).absolutePath,
                roots.root.absolutePath,
            )
        } finally {
            System.clearProperty(DesktopStorageRoots.ENV_DATA_ROOT)
            DesktopStorageRoots.hermeticEnvLeg = null
        }
    }
    @Test
    fun `a relative override is rejected as an absolute-path requirement`() {
 // (desktop-twin ): the fail-open arm of the fix —
        // a relative override resolves per-process CWD (the rpHost harness,
        // a jpackage install and a dev run would each land the durable tree
        // somewhere else, and with a CWD on C: the whole tree lands on C:).
        System.setProperty(DesktopStorageRoots.ENV_DATA_ROOT, "breaker-relative-root")
        try {
            val thrown = runCatching { DesktopStorageRoots() }.exceptionOrNull()
            assertTrue(
                "a relative override must be rejected at construction, got $thrown",
                thrown is IllegalStateException,
            )
            assertTrue(
                "the refusal names the variable and the absolute requirement",
                thrown?.message?.contains("POCKET_REALM_DATA_ROOT") == true &&
                    thrown?.message?.contains("ABSOLUTE") == true,
            )
        } finally {
            System.clearProperty(DesktopStorageRoots.ENV_DATA_ROOT)
        }
    }
}
