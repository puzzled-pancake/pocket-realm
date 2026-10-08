package com.pocketrealm.desktop

import java.io.File

/**
 * The DESK-25 short-term leg: respawning the running jpackage app image.
 * The embedded cmangos lane has ONE world lifetime per process — the native
 * refuses a second start with the WRONG_STATE in-process-restart error ("a
 * world that reached READY once needs a fresh process for its next
 * lifetime", world_runtime.cpp) — so after Save & exit the realm's next
 * lifetime needs a fresh app process. The fresh process re-derives the SAME
 * %LOCALAPPDATA%\PocketRealm storage roots from the inherited environment
 * (data lives outside the image — the updater's structural-preservation
 * argument, UpdateApply's header), so "same roots" needs nothing forwarded.
 *
 * Image resolution reuses [UpdateApply.currentAppImageDir] (the code-source
 * probe; null in a dev run — the honest refusal, mirroring the updater's
 * "this looks like a dev run" copy). The probe/spawn seams exist for the
 * JVM tests; production never passes them.
 */
class DesktopAppRelauncher(
    private val imageDir: () -> File? = UpdateApply::currentAppImageDir,
    private val spawn: (File) -> Long = { file ->
        // The handoff marker (see RelaunchHandoff): this predecessor is
        // STILL draining (world save + component stops) while the fresh
        // JVM boots, so the fresh instance would otherwise lose the
        // single-instance lock race and die in the "already running"
        // refusal dialog. The marker tells Main's entry to wait a bounded
        // window for THIS pid to release the lock instead.
        RelaunchHandoff.stamp(ProcessBuilder(file.absolutePath)).start().pid()
    },
) {
    /**
     * Spawn the fresh app process; the CALLER drains this session only on
     * success (a refusal must leave the app alive with the honest detail).
     */
    fun relaunch(): RelaunchResult {
        val launcher = imageDir()?.let { image -> File(image, UpdateApply.LAUNCHER_NAME) }
        if (launcher == null || !launcher.isFile) {
            return RelaunchResult(ok = false, detail = DEV_RUN_REFUSAL)
        }
        return try {
            val pid = spawn(launcher)
            RelaunchResult(
                ok = true,
                detail = "relaunched ${launcher.name} (pid=$pid); this session drains and exits",
            )
        } catch (failure: Exception) {
            RelaunchResult(
                ok = false,
                detail = "app relaunch failed: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }

    data class RelaunchResult(val ok: Boolean, val detail: String)

    companion object {
        const val DEV_RUN_REFUSAL =
            "app relaunch needs the jpackage app image (this looks like a dev run — " +
                "stop and rerun the gradlew task); the app stays open"
    }
}
