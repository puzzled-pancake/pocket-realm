package com.pocketrealm.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Atomic-write contract for the archive import's credentials leg: the
 * imported account bytes must reach the live account.json only through
 * the UserAccountStore.writeLocked discipline, byte-form (temp + fd.sync
 * + chmod + rename + directory fsync) — the desktop twin's
 * DesktopAppModel.writeImportedAccount. A direct writeBytes over the
 * live record would leave a torn file that the next loadOrQuarantine
 * quarantines, silently discarding the previously saved auto-login
 * account.
 *
 * The lane itself needs a live Android context and android.system.Os
 * (the file-I/O path the store pins only in androidTest,
 * UserAccountStoreInstrumentedTest), so the write discipline is pinned
 * as a source-contract test over the shipped source — the repo's pattern
 * for host-untestable glue (LanTunnelWiringContractTest). The bytes'
 * validation gate stays pinned executable in
 * RealmDataArchiveTest.accountEntryValidationMirrorsTheStoreDecode.
 */
class RealmImportAccountWriteContractTest {

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

    @Test
    fun theImportedAccountWriteIsTheStoreAtomicDisciplineByteForm() {
        val settings = mainSource("ui/SettingsScreen.kt")
        // Nothing lands on disk before the store's own decode accepts the bytes.
        assertTrue(
            "the import must validate through the store decode before writing",
            "if (!RealmDataArchive.isValidAccountRecord(bytes)) return@withContext false" in settings,
        )
        // temp + fd.sync: a crash mid-write must never tear the live record.
        assertTrue(
            "the bytes must be written to a temp file that is fd.sync()ed",
            "java.io.FileOutputStream(temp)" in settings && "stream.fd.sync()" in settings,
        )
        assertTrue(
            "the temp file must keep the store's .user-account.<pid>.tmp naming",
            ".user-account." in settings && ".tmp" in settings,
        )
        assertTrue(
            "the temp must be chmod owner-only BEFORE it becomes the record",
            "android.system.Os.chmod(temp.absolutePath" in settings,
        )
        assertTrue(
            "the directory must carry the store's owner-only mode (the S_IXUSR leg)",
            "android.system.OsConstants.S_IXUSR" in settings,
        )
        // Atomic publish + the directory fsync the store's writeLocked carries.
        assertTrue(
            "the publish must be the store's Os.rename over the record",
            "android.system.Os.rename(temp.absolutePath, accountFile.absolutePath)" in settings,
        )
        assertTrue(
            "the directory must be fsynced after the rename (writeLocked parity)",
            "android.system.Os.fsync(descriptor)" in settings,
        )
        // Store layout parity: the same user-account/account.json the store owns.
        assertTrue(
            "the write must target the store's user-account/account.json",
            "java.io.File(context.noBackupFilesDir, \"user-account\")" in settings &&
                "java.io.File(directory, \"account.json\")" in settings,
        )
    }

    @Test
    fun noNonAtomicWriteOverTheLiveRecordRemains() {
        val settings = mainSource("ui/SettingsScreen.kt")
        assertFalse(
            "the import must not writeBytes over the live account.json",
            "writeBytes(bytes)" in settings,
        )
        assertTrue(
            "the import lane must route through writeImportedAccount",
            "writeImportedAccount(bytes)" in settings,
        )
    }

    /** Round-5: the credentials write's own IO (the FileOutputStream and the
     * Os.chmod/Os.rename/dir-fsync legs — all ErrnoException-capable) can
     * fail AFTER supervisor.restoreBackup has already committed — the
     * committed import must still set its success status with the failure
     * disclosed in the note, never throw to the whole-body catch as an
     * "Import failed" for characters that were in fact restored (the desktop
     * twin's importedAccountNote fold, pinned executable by
     * DesktopRealmArchiveTest.importDisclosesAnUnwritableAccountLegWithout-
     * FailingTheCommittedRestore). */
    @Test
    fun theAccountWriteFailureIsFoldedIntoTheNoteNeverAnImportFailure() {
        val settings = mainSource("ui/SettingsScreen.kt")
        assertTrue(
            "the account leg must fold its own failure at the call site, not throw it",
            "runCatching { writeImportedAccount(bytes) }.fold(" in settings,
        )
        assertTrue(
            "an unwritable saved login must be disclosed, never fail the committed restore",
            "\" Characters restored, but the saved login could not be written: \"" in settings,
        )
        assertTrue(
            "the committed import's success status must still be set after the account leg",
            "realmDataStatus = \"Realm data imported.\$accountNote\"" in settings,
        )
    }
}
