package com.pocketrealm.client

import android.os.Process
import com.pocketrealm.log.AppLog
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One-way retirement for ARM runtime generations that can no longer be selected.
 *
 * The old FEX root can contain hundreds of thousands of files. Removing it recursively on the
 * Binder call that prepares Wine made an otherwise healthy launch look frozen for several
 * minutes. A same-directory rename makes the retired generation unreachable immediately; a
 * single low-priority worker reclaims the tombstone after the launch-critical work has moved on.
 * Tombstones survive process death and are discovered again on the next preparation.
 */
internal object ArmRuntimeRetirement {
    private const val TAG = "ArmRuntimeRetirement"
    private const val FEX_GENERATION = "fexcore-2608"
    private const val TOMBSTONE_PREFIX = ".retired-fexcore-2608-"
    private val tombstoneName = Regex("^\\.retired-fexcore-2608-[0-9a-f]{32}$")
    private val mutationLock = Any()
    private val queued = ConcurrentHashMap.newKeySet<String>()

    private val executor by lazy {
        ScheduledThreadPoolExecutor(1) { work ->
            Thread(work, "pr-retired-runtime").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
    }

    fun retireFexGeneration(armRoot: File) {
        retireFexGeneration(armRoot, ::scheduleDeletion)
    }

    /** Visible to unit tests so retirement can be proved without starting the Android worker. */
    internal fun retireFexGeneration(armRoot: File, enqueue: (File) -> Unit) {
        val pending = synchronized(mutationLock) {
            val root = armRoot.canonicalFile
            check(root.isDirectory || root.mkdirs()) { "ARM runtime root is unavailable: $root" }

            val source = File(root, FEX_GENERATION)
            check(source.parentFile?.canonicalFile == root) {
                "refusing to retire a runtime outside the ARM root: $source"
            }
            if (Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val tombstone = File(
                    root,
                    TOMBSTONE_PREFIX + UUID.randomUUID().toString().replace("-", ""),
                )
                moveOutOfLaunchPath(source, tombstone)
            }

            root.listFiles()
                .orEmpty()
                .filter { isOwnedTombstone(root, it) }
                .sortedBy { it.name }
        }
        pending.forEach(enqueue)
    }

    internal fun deleteTombstone(armRoot: File, candidate: File): Boolean {
        val root = armRoot.canonicalFile
        if (!isOwnedTombstone(root, candidate)) return false
        if (!Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        return if (Files.isSymbolicLink(candidate.toPath())) {
            Files.deleteIfExists(candidate.toPath())
        } else {
            candidate.deleteRecursively()
        }
    }

    private fun moveOutOfLaunchPath(source: File, tombstone: File) {
        try {
            Files.move(source.toPath(), tombstone.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), tombstone.toPath())
        }
        check(!Files.exists(source.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.exists(tombstone.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "retired ARM runtime could not be moved out of the launch path"
        }
    }

    private fun isOwnedTombstone(root: File, candidate: File): Boolean {
        if (!tombstoneName.matches(candidate.name)) return false
        if (candidate.parentFile?.canonicalFile != root) return false
        if (Files.isSymbolicLink(candidate.toPath())) return true
        return runCatching {
            candidate.canonicalFile.toPath().startsWith(root.toPath())
        }.getOrDefault(false)
    }

    private fun scheduleDeletion(target: File) {
        val root = target.parentFile?.canonicalFile ?: return
        val key = target.absoluteFile.normalize().path
        if (!queued.add(key)) return
        executor.schedule({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                if (deleteTombstone(root, target)) {
                    AppLog.i(TAG, "retired FEX runtime storage reclaimed")
                } else {
                    AppLog.w(TAG, "retired FEX runtime tombstone was not eligible for cleanup")
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "retired FEX runtime cleanup will resume on a later launch: ${t.message}")
            } finally {
                queued.remove(key)
            }
        }, 30, TimeUnit.SECONDS)
    }
}
