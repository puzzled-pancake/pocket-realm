package com.pocketrealm.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * R4-1 (desktop-twin round 1, serious): the update-apply path must land
 * the honest outcome on EVERY failure class — an IO failure mid-apply
 * (truncated/garbage package, disk-full, locked target) previously
 * escaped the confirm click's unguarded coroutine and killed the app.
 * The contract under test: `applyDownloadedUpdate` THROWS NOTHING and
 * folds every thrown failure into [UpdateApply.ApplyOutcome.Failed];
 * the Settings confirm click carries the same guard posture as the
 * download click (source pin at the bottom).
 */
class UpdateApplyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun currentImage(): File {
        val image = tmp.newFolder("app")
        // the jpackage image root the walk/swap logic keys on
        File(image, UpdateApply.LAUNCHER_NAME).writeText("launcher")
        return image
    }

    private fun garbageZip(): File {
        val staged = File(tmp.newFolder("staging"), "pocket-realm-app-image.zip")
        // NOT a zip: the ZipInputStream read throws mid-apply
        staged.writeBytes("this is not an archive".toByteArray())
        return staged
    }

    private fun truncatedZip(): File {
        val staged = File(tmp.newFolder("staging2"), "pocket-realm-app-image.zip")
        ZipOutputStream(staged.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("PocketRealm/app/PocketRealm.exe"))
            output.write("partial".toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("PocketRealm/app/heavy.bin"))
            output.write(ByteArray(64))
            output.closeEntry()
        }
        // truncate: a real disk-full/corrupt-read signature
        val full = staged.readBytes()
        staged.writeBytes(full.copyOf(full.size / 2))
        return staged
    }

    @Test
    fun aGarbagePackageAnswersHonestAndThrowsNothing() {
        // a non-zip byte stream is tolerated as an empty archive by
        // ZipInputStream, so the honest answer is the REFUSAL naming the
        // missing launcher (nothing changed) — either honest outcome is
        // contract-correct; the invariant under test is: THROWS NOTHING
        val outcome = UpdateApply.applyDownloadedUpdate(
            stagedPackage = garbageZip(),
            currentImage = currentImage(),
            consent = true,
        )
        assertTrue(
            "a garbage package must answer an honest outcome, got $outcome",
            outcome is UpdateApply.ApplyOutcome.Refused ||
                outcome is UpdateApply.ApplyOutcome.Failed,
        )
    }

    @Test
    fun aThrownIOFailureMidApplyFoldsIntoFailedAndKeepsTheRunningImage() {
        // THE crash class fixed: an extraction that THROWS (here the
        // zip-slip guard's IOException — the same propagation path a
        // disk-full FileOutputStream or a corrupt-read ZipException rides)
        // previously escaped the unguarded confirm-click coroutine and
        // killed the app. The fold must land the honest Failed outcome
        // with the running image untouched.
        val image = currentImage()
        val staged = File(tmp.newFolder("staging-slip"), "pocket-realm-app-image.zip")
        ZipOutputStream(staged.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("PocketRealm/../../../escaped.txt"))
            output.write("slip".toByteArray())
            output.closeEntry()
        }
        val outcome = UpdateApply.applyDownloadedUpdate(staged, image, consent = true)
        assertTrue(
            "a thrown IO failure must fold into the honest Failed outcome, got $outcome",
            outcome is UpdateApply.ApplyOutcome.Failed,
        )
        assertTrue(
            "the running image must survive a mid-apply failure",
            image.isDirectory && File(image, UpdateApply.LAUNCHER_NAME).isFile,
        )
        assertTrue(
            "the slip entry must not have landed outside the work dir",
            !File(image.parentFile, "escaped.txt").exists(),
        )
    }

    @Test
    fun aValidAppImageZipSwapsTheImage() {
        val image = currentImage()
        val staged = File(tmp.newFolder("staging3"), "pocket-realm-app-image.zip")
        ZipOutputStream(staged.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("PocketRealm/app/PocketRealm.exe"))
            output.write("launcher v2".toByteArray())
            output.closeEntry()
        }
        val outcome = UpdateApply.applyDownloadedUpdate(staged, image, consent = true)
        assertTrue(
            "the happy path swaps the image, got $outcome",
            outcome is UpdateApply.ApplyOutcome.Swapped,
        )
        assertEquals(
            "launcher v2",
            File(image, UpdateApply.LAUNCHER_NAME).readText(),
        )
    }

    @Test
    fun consentFalseRefusesWithoutTouchingDisk() {
        val image = currentImage()
        val staged = File(tmp.newFolder("staging4"), "pocket-realm-app-image.zip")
        staged.writeBytes("not a zip".toByteArray())
        val outcome = UpdateApply.applyDownloadedUpdate(staged, image, consent = false)
        assertTrue(outcome is UpdateApply.ApplyOutcome.Refused)
        assertTrue(
            "refusal touches nothing",
            File(image, UpdateApply.LAUNCHER_NAME).readText() == "launcher",
        )
    }

    /** layer (b) the Settings confirm click folds escapes into the
     * outcome copy exactly like the download click — pinned as a source
     * contract on the composable. */
    @Test
    fun theConfirmClickCarriesTheSameGuardAsTheDownloadClick() {
        val repoRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .firstOrNull { File(it, "schemas/flavor.json").isFile }
            ?: error("repo root not found")
        val screen = File(
            repoRoot,
            "desktop/src/main/kotlin/com/pocketrealm/desktop/ui/SettingsScreen.kt",
        ).readText()
        val confirm = screen
            .split("InstallConfirmRow(", limit = 2)[1]
            .split("Text(\"Cancel\")", limit = 2)[0]
        assertTrue(
            "the confirm click must wrap the apply in runCatching",
            "runCatching" in confirm,
        )
        assertTrue(
            "an escape must fold into the honest Failed outcome",
            "ApplyOutcome.Failed" in confirm,
        )
    }
    @Test
    fun aTamperedStagedPackageIsRefusedAtApplyTime() {
        // verification must fail closed AT APPLY — the download
        // finalize gate ran long before this call and the staged file is
        // mutable on disk.
        val image = currentImage()
        val staged = File(tmp.newFolder("staging-tamper"), "pocket-realm-app-image.zip")
        ZipOutputStream(staged.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("PocketRealm/app/PocketRealm.exe"))
            output.write("launcher v2".toByteArray())
            output.closeEntry()
        }
        val digestAtStaging = com.pocketrealm.fs.FileDigests.sha256(staged)
        // the consent click lands hours later; the file changed on disk
        staged.appendText("tampered")
        val outcome = UpdateApply.applyDownloadedUpdate(
            stagedPackage = staged,
            currentImage = image,
            consent = true,
            expectedSha256 = digestAtStaging,
        )
        assertTrue(
            "a tampered package must be refused naming the checksum, got $outcome",
            outcome is UpdateApply.ApplyOutcome.Refused &&
                "sha256" in (outcome as UpdateApply.ApplyOutcome.Refused).reason,
        )
        assertTrue(
            "the running image is untouched by the refused apply",
            File(image, UpdateApply.LAUNCHER_NAME).readText() == "launcher",
        )
    }

    @Test
    fun anUnTamperedStagedPackageAppliesUnderTheExpectedDigest() {
        val image = currentImage()
        val staged = File(tmp.newFolder("staging-clean"), "pocket-realm-app-image.zip")
        ZipOutputStream(staged.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("PocketRealm/app/PocketRealm.exe"))
            output.write("launcher v2".toByteArray())
            output.closeEntry()
        }
        val outcome = UpdateApply.applyDownloadedUpdate(
            stagedPackage = staged,
            currentImage = image,
            consent = true,
            expectedSha256 = com.pocketrealm.fs.FileDigests.sha256(staged),
        )
        assertTrue(
            "the matching digest applies, got $outcome",
            outcome is UpdateApply.ApplyOutcome.Swapped,
        )
    }
}
