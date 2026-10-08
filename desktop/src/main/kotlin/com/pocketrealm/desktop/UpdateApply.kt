package com.pocketrealm.desktop

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

/**
 * The desktop updater's CONSENTED apply (DESK-14/32/45/91/104): swap the
 * jpackage app image (atomic rename; %LOCALAPPDATA% state is untouched —
 * data lives outside the image, so preservation is structural) or launch a
 * release installer. Nothing ever applies without [UpdateApply.applyDownloadedUpdate]'s
 * explicit consent flag, which the Settings card only sets after the user's
 * confirmation click. The honest unsigned copy rides every outcome: the
 * package is verified only against the feed's own sha256, never a code
 * signature, and the copy must keep saying so.
 */
object UpdateApply {

    /**
     * Applies a verified staged package. [consent] may only be true after an
     * explicit user action in the UI; false refuses without touching disk
     * (never silent, never automatic).
     */
    fun applyDownloadedUpdate(
        stagedPackage: File,
        currentImage: File?,
        consent: Boolean,
    ): ApplyOutcome {
        val refusal = refusalReason(stagedPackage, currentImage, consent)
        if (refusal != null) return ApplyOutcome.Refused(refusal)
        return when {
            stagedPackage.name.endsWith(APP_IMAGE_SUFFIX, ignoreCase = true) ->
                swapAppImage(stagedPackage, currentImage!!)
            stagedPackage.name.endsWith(INSTALLER_SUFFIX, ignoreCase = true) ->
                launchInstaller(stagedPackage)
            else -> ApplyOutcome.Refused(
                "The staged package is neither an app-image .zip nor an installer .exe; " +
                    "nothing was changed.",
            )
        }
    }

    /** The ordered refusal contract: consent first, then stage, then image. */
    private fun refusalReason(stagedPackage: File, currentImage: File?, consent: Boolean): String? =
        when {
            !consent ->
                "Install cancelled - nothing was changed. (The build stays unsigned; an " +
                    "install only ever happens after this explicit confirmation.)"
            !stagedPackage.isFile -> "The staged package is missing; download it again."
            currentImage == null ->
                "No jpackage app image detected (this looks like a dev run) - replace the " +
                    "image manually with the staged package at " +
                    "${stagedPackage.absolutePath}. Nothing was changed."
            else -> null
        }

    /** Locates the running jpackage image from this class's code source; null in dev runs. */
    fun currentAppImageDir(): File? = runCatching {
        val location = UpdateApply::class.java.protectionDomain.codeSource?.location
        val jar = location?.let { File(it.toURI()) }
        // The fat jar lives at <image>/app/<name>.jar inside a jpackage image.
        val image = jar?.parentFile?.parentFile
        image?.takeIf { File(it, LAUNCHER_NAME).isFile }
    }.getOrNull()

    /** Starts the swapped image's launcher and ends this process (explicit user action). */
    fun relaunchAndExit(imageDir: File): Nothing {
        val launcher = File(imageDir, LAUNCHER_NAME)
        check(launcher.isFile) { "no $LAUNCHER_NAME under $imageDir to relaunch" }
        // The same relaunch handoff the spent-world relauncher stamps: this
        // process's drain runs in the JVM shutdown hook (bounded by the
        // save control timeout + the component stop budgets), so the fresh
        // instance must WAIT for the single-instance lock instead of dying
        // in the "already running" refusal dialog (RelaunchHandoff).
        RelaunchHandoff.stamp(ProcessBuilder(launcher.absolutePath)).start()
        exitProcess(0)
    }

    /**
     * App-image swap: unpack the verified zip, locate the image root, then
     * rename the running image aside and rename the staged image into its
     * place (atomic on one volume; a copy fallback covers a staging dir on a
     * different drive).
     */
    private fun swapAppImage(stagedPackage: File, currentImage: File): ApplyOutcome {
        val work = File(stagedPackage.parentFile, "apply-${System.currentTimeMillis()}")
        try {
            work.mkdirs()
            unzip(stagedPackage, work)
            // Breadth-limited search for the unpacked image root (the dir
            // that directly holds the launcher).
            val stagedImage = work.walkTopDown()
                .maxDepth(APPLY_WALK_DEPTH)
                .firstOrNull { it.isFile && it.name == LAUNCHER_NAME }
                ?.parentFile
            if (stagedImage == null) {
                return ApplyOutcome.Refused(
                    "The app-image zip carries no $LAUNCHER_NAME; nothing was changed.",
                )
            }
            return installStagedImage(stagedImage, currentImage)
        } finally {
            work.deleteRecursively()
        }
    }

    /** rename-aside + rename-in, with a copy fallback for a cross-drive staging dir. */
    private fun installStagedImage(stagedImage: File, currentImage: File): ApplyOutcome {
        val backup = File(currentImage.parentFile, currentImage.name + BACKUP_SUFFIX)
        backup.deleteRecursively()
        val movedAside = currentImage.renameTo(backup)
        val installed = movedAside &&
            (stagedImage.renameTo(currentImage) || copyIntoPlace(stagedImage, currentImage))
        val outcome = when {
            !movedAside -> ApplyOutcome.Failed(
                "cannot move the running image $currentImage aside - close PocketRealm.exe " +
                    "and retry; nothing was changed",
            )
            !installed -> {
                runCatching { backup.renameTo(currentImage) }
                ApplyOutcome.Failed(
                    "cannot move the staged image into place (is it on a different drive?); " +
                        "the old image was restored",
                )
            }
            else -> ApplyOutcome.Swapped(currentImage)
        }
        if (installed) backup.deleteRecursively()
        return outcome
    }

    /** The cross-drive fallback for [installStagedImage]; the rename path is preferred. */
    private fun copyIntoPlace(stagedImage: File, currentImage: File): Boolean =
        runCatching { stagedImage.copyRecursively(currentImage, overwrite = true) }.isSuccess

    /** Installer path: the release .exe carries its own (user-visible) flow. */
    private fun launchInstaller(stagedPackage: File): ApplyOutcome {
        val launched = runCatching { ProcessBuilder(stagedPackage.absolutePath).start() }
        return if (launched.isSuccess) {
            ApplyOutcome.InstallerLaunched
        } else {
            ApplyOutcome.Failed(
                "cannot launch the installer: " +
                    "${launched.exceptionOrNull()?.message}; nothing was changed",
            )
        }
    }

    /** Zip extraction with a zip-slip guard; the archive is sha256-verified upstream. */
    private fun unzip(zip: File, target: File) {
        val canonicalTarget = target.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                extractEntry(input, entry, target, canonicalTarget)
                input.closeEntry()
            }
        }
    }

    private fun extractEntry(
        input: ZipInputStream,
        entry: java.util.zip.ZipEntry,
        target: File,
        canonicalTarget: File,
    ) {
        val out = File(target, entry.name)
        val canonical = out.canonicalFile
        if (canonical != canonicalTarget &&
            !canonical.path.startsWith(canonicalTarget.path + File.separator)
        ) {
            throw IOException("refusing zip-slip entry: ${entry.name}")
        }
        if (entry.isDirectory) {
            out.mkdirs()
        } else {
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
    }

    sealed class ApplyOutcome {
        /** The image swap succeeded; [newImage] is the live image directory. */
        data class Swapped(val newImage: File) : ApplyOutcome() {
            val message: String
                get() = "App image swapped into $newImage. The Windows port is UNSIGNED - " +
                    "the package was verified only against the feed's sha256, not a " +
                    "code signature. Restart PocketRealm to run the new version."
        }

        /** The release installer was started; it carries its own flow. */
        data object InstallerLaunched : ApplyOutcome() {
            val message: String
                get() = "Installer started (the build stays UNSIGNED - review what you " +
                    "run). Finish it, then start PocketRealm again."
        }

        data class Refused(val reason: String) : ApplyOutcome()
        data class Failed(val reason: String) : ApplyOutcome()
    }

    /** The jpackage launcher exe name — shared with the DESK-25 spent-world
     * app relaunch (DesktopAppRelauncher respawns the same image's launcher). */
    internal const val LAUNCHER_NAME = "PocketRealm.exe"
    private const val APP_IMAGE_SUFFIX = ".zip"
    private const val INSTALLER_SUFFIX = ".exe"
    private const val BACKUP_SUFFIX = ".old"
    private const val APPLY_WALK_DEPTH = 3
}
