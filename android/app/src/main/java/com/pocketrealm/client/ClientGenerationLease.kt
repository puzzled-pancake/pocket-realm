package com.pocketrealm.client

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-process publication fence for immutable imported client generations.
 * Runtime users share the lock; activation/pruning takes it exclusively.
 */
internal class ClientGenerationLease private constructor(
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isHeld: Boolean get() = !closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }

    companion object {
        private data class SharedHolder(
            val file: RandomAccessFile,
            val channel: FileChannel,
            val lock: FileLock,
            var references: Int,
        )

        private val monitor = Any()
        private val sharedByPath = mutableMapOf<String, SharedHolder>()

        fun acquireRuntime(clientRoot: File): ClientGenerationLease {
            val lockFile = lockFile(clientRoot, ".generation-publication.lock")
            val key = lockFile.canonicalPath
            synchronized(monitor) {
                sharedByPath[key]?.let { holder ->
                    holder.references++
                    return ClientGenerationLease { releaseShared(key) }
                }
                val file = RandomAccessFile(lockFile, "rw")
                try {
                    val channel = file.channel
                    val lock = channel.lock(0L, Long.MAX_VALUE, true)
                    sharedByPath[key] = SharedHolder(file, channel, lock, 1)
                    return ClientGenerationLease { releaseShared(key) }
                } catch (error: Throwable) {
                    file.close()
                    throw error
                }
            }
        }

        fun acquirePublication(clientRoot: File): ClientGenerationLease =
            acquireExclusive(clientRoot, ".generation-publication.lock", blocking = true)
                ?: error("client generation publication lease is busy")

        fun tryAcquirePublication(clientRoot: File): ClientGenerationLease? =
            acquireExclusive(clientRoot, ".generation-publication.lock", blocking = false)

        fun acquireImportOperation(clientRoot: File): ClientGenerationLease =
            acquireExclusive(clientRoot, ".import-operation.lock", blocking = true)
                ?: error("client import operation lease is busy")

        private fun acquireExclusive(
            clientRoot: File,
            name: String,
            blocking: Boolean,
        ): ClientGenerationLease? {
            val lockFile = lockFile(clientRoot, name)
            val key = lockFile.canonicalPath
            synchronized(monitor) {
                if (sharedByPath.containsKey(key)) {
                    if (!blocking) return null
                    error("client generation is in use in this process")
                }
            }
            val file = RandomAccessFile(lockFile, "rw")
            try {
                val channel = file.channel
                val lock = try {
                    if (blocking) channel.lock() else channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) {
                    file.close()
                    return null
                }
                return ClientGenerationLease {
                    runCatching { lock.release() }
                    runCatching { channel.close() }
                    runCatching { file.close() }
                }
            } catch (error: Throwable) {
                file.close()
                throw error
            }
        }

        private fun releaseShared(key: String) = synchronized(monitor) {
            val holder = checkNotNull(sharedByPath[key])
            holder.references--
            if (holder.references == 0) {
                sharedByPath.remove(key)
                runCatching { holder.lock.release() }
                runCatching { holder.channel.close() }
                runCatching { holder.file.close() }
            }
        }

        private fun lockFile(clientRoot: File, name: String): File {
            val root = clientRoot.canonicalFile.apply { mkdirs() }
            check(root.isDirectory) { "managed client root is unavailable" }
            return File(root, name).also { file ->
                if (!file.exists()) file.createNewFile()
                file.setReadable(false, false)
                file.setWritable(false, false)
                check(file.setReadable(true, true) && file.setWritable(true, true)) {
                    "managed client lease permissions could not be restricted"
                }
            }
        }
    }
}
