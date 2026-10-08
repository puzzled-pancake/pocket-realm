package com.pocketrealm.desktop.ui

import com.pocketrealm.supervisor.RuntimeOperation
import com.pocketrealm.supervisor.RuntimeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Review R3 (the desktop realm-archive import lane): the import card's
 * status runs through [realmArchiveImportStatus] — the export lane's
 * runCatching fold — so a throwable that ESCAPES
 * DesktopAppModel.importRealmArchive after the character restore already
 * committed (the credentials write's raw FileOutputStream + Files.move)
 * becomes a REPORTED failure status instead of killing the coroutine and
 * wedging the card at "Importing…" with busy=true forever. The desktop
 * harness is JVM-unit only (no compose UI-test dependency), so the mapping
 * is pinned through the pure seam the composable calls; the composition
 * wiring itself is review-pinned (the TimeframeCardFlavorTest pattern).
 */
class RealmArchiveImportStatusTest {

    @Test
    fun aReturnedOperationReportsItsOwnDetail() = runBlocking {
        assertEquals(
            "Realm data imported (snapshot 19700101-000000-imported, from app 1.2.3).",
            realmArchiveImportStatus {
                RuntimeOperation(
                    true,
                    RuntimeSnapshot(),
                    "Realm data imported (snapshot 19700101-000000-imported, from app 1.2.3).",
                )
            },
        )
        // A refusal is reported the same way — the model's own detail.
        assertEquals(
            "Snapshot 19700101-000000-imported already exists on this machine.",
            realmArchiveImportStatus {
                RuntimeOperation(
                    false,
                    RuntimeSnapshot(),
                    "Snapshot 19700101-000000-imported already exists on this machine.",
                )
            },
        )
    }

    @Test
    fun anEscapingThrowableWithAMessageBecomesAFailureStatus() = runBlocking {
        assertEquals(
            "Import failed: disk full",
            realmArchiveImportStatus { throw java.io.IOException("disk full") },
        )
    }

    @Test
    fun anEscapingThrowableWithoutAMessageFallsBackToTheClassName() = runBlocking {
        assertEquals(
            "Import failed: NullPointerException",
            realmArchiveImportStatus { throw NullPointerException() },
        )
    }
}
