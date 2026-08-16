package com.pocketrealm.client

import android.content.Context
import android.system.Os
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/** Publishes the pinned Winlator rootfs on clean install without an ADB staging step. */
class ArmRootfsProvisioner(private val context: Context) {
    fun ensure(root: File): File {
        val canonicalRoot = root.canonicalFile
        canonicalRoot.parentFile!!.mkdirs()
        val lockFile = File(canonicalRoot.parentFile, ".${canonicalRoot.name}.provision.lock")
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use {
                val target = File(canonicalRoot, "rootfs")
                if (isReady(target)) return target
                canonicalRoot.mkdirs()
                val staging = File(
                    canonicalRoot,
                    ".rootfs.staging.${android.os.Process.myPid()}.${System.nanoTime()}",
                )
                checkContained(canonicalRoot, staging)
                check(staging.mkdirs()) { "rootfs staging directory could not be created" }
                try {
                    extractArchive(ROOTFS_ASSET, staging)
                    extractArchive(PATCHES_ASSET, staging)
                    // Winlator ships the reusable Wine prefix separately from
                    // the Linux rootfs.  Merge it at the location expected by
                    // WineRuntimeStore instead of treating the partial .wine
                    // directory in rootfs.tzst as a complete prefix.
                    extractArchive(CONTAINER_PATTERN_ASSET, File(staging, "home/xuser"))
                    stripUnscopedOpenGlClient(staging)
                    requireExpectedPayload(staging)
                    writeReadyMarker(staging)
                    publish(canonicalRoot, staging, target)
                    check(isReady(target)) { "published ARM rootfs failed attestation" }
                    return target
                } catch (error: Throwable) {
                    if (staging.exists()) staging.deleteRecursively()
                    throw error
                }
            }
        }
    }

    private fun extractArchive(assetPath: String, destination: File) {
        context.assets.open(assetPath).use { compressed ->
            ZstdInputStream(compressed).use { zstd ->
                TarArchiveInputStream(zstd).use { archive ->
                    var count = 0
                    var totalBytes = 0L
                    while (true) {
                        val entry = archive.nextEntry as? TarArchiveEntry ?: break
                        if (isArchiveRootDirectory(entry.name, entry.isDirectory)) continue
                        if (isRetiredContainerRootLink(assetPath, entry)) continue
                        count++
                        check(count <= MAX_ENTRIES) { "ARM rootfs archive contains too many entries" }
                        val target = safeTarget(destination, entry)
                        when {
                            entry.isDirectory -> {
                                check(target.mkdirs() || target.isDirectory) {
                                    "rootfs directory could not be created"
                                }
                            }
                            entry.isSymbolicLink -> installSymlink(destination, target, entry.linkName)
                            entry.isFile -> {
                                check(entry.size in 0..MAX_ENTRY_BYTES) { "invalid rootfs entry size" }
                                totalBytes = Math.addExact(totalBytes, entry.size)
                                check(totalBytes <= MAX_TOTAL_BYTES) { "ARM rootfs exceeds extraction quota" }
                                target.parentFile!!.mkdirs()
                                check(!Files.isSymbolicLink(target.toPath())) {
                                    "rootfs regular file would overwrite a symlink"
                                }
                                FileOutputStream(target, false).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var remaining = entry.size
                                    while (remaining > 0) {
                                        val read = archive.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                        check(read > 0) { "truncated ARM rootfs archive" }
                                        output.write(buffer, 0, read)
                                        remaining -= read
                                    }
                                }
                                Os.chmod(target.absolutePath, entry.mode and 0x1ff)
                            }
                            else -> error("unsupported ARM rootfs archive entry")
                        }
                    }
                }
            }
        }
    }

    private fun safeTarget(root: File, entry: TarArchiveEntry): File {
        val name = entry.name.removePrefix("./")
        require(name.isNotEmpty() && !name.startsWith('/') && '\u0000' !in name) {
            "unsafe ARM rootfs archive path"
        }
        val normalized = root.toPath().resolve(name).normalize()
        check(normalized.startsWith(root.canonicalFile.toPath())) {
            "ARM rootfs archive escaped its staging root"
        }
        return normalized.toFile()
    }

    private fun installSymlink(root: File, target: File, linkName: String) {
        require(linkName.isNotEmpty() && !Paths.get(linkName).isAbsolute && '\u0000' !in linkName) {
            "unsafe ARM rootfs symlink"
        }
        val parent = checkNotNull(target.parentFile) { "rootfs symlink has no parent" }
        val resolved = parent.toPath().resolve(linkName).normalize()
        check(resolved.startsWith(root.canonicalFile.toPath())) {
            "ARM rootfs symlink escaped its staging root"
        }
        parent.mkdirs()
        Files.deleteIfExists(target.toPath())
        Files.createSymbolicLink(target.toPath(), Paths.get(linkName))
    }

    private fun publish(root: File, staging: File, target: File) {
        val old = File(root, ".rootfs.replaced.${System.nanoTime()}")
        checkContained(root, old)
        if (target.exists()) Os.rename(target.absolutePath, old.absolutePath)
        try {
            Os.rename(staging.absolutePath, target.absolutePath)
            syncDirectory(root)
        } catch (error: Throwable) {
            if (!target.exists() && old.exists()) Os.rename(old.absolutePath, target.absolutePath)
            throw error
        }
        if (old.exists()) check(old.deleteRecursively()) { "retired ARM rootfs could not be removed" }
        syncDirectory(root)
    }

    private fun requireExpectedPayload(rootfs: File) {
        val required = listOf(
            "opt/wine/bin/wine",
            "opt/wine/bin/wineserver",
            "home/xuser/.wine/system.reg",
            "home/xuser/.wine/user.reg",
            "home/xuser/.wine/userdef.reg",
            "home/xuser/.wine/.update-timestamp",
            "usr/lib/libasound.so.2.0.0",
            "usr/lib/alsa-lib/libasound_module_pcm_android_aserver.so",
            "opt/wine/lib/wine/x86_64-unix/winealsa.so",
            "usr/share/alsa/alsa.conf",
            "etc/alsa/conf.d/android_aserver.conf",
        )
        required.forEach { relative ->
            val file = File(rootfs, relative)
            check(file.isFile && !Files.isSymbolicLink(file.toPath())) {
                "pinned ARM rootfs is missing $relative"
            }
        }
        check(File(rootfs, "home/xuser/.wine/dosdevices").isDirectory) {
            "pinned ARM rootfs is missing home/xuser/.wine/dosdevices"
        }
        check(isExpectedAudioPlugin(
            File(rootfs, "usr/lib/alsa-lib/libasound_module_pcm_android_aserver.so"),
        )) {
            "pinned ARM rootfs has the wrong android_aserver plug-in ABI or content"
        }
        check(hasNoUnscopedOpenGlClient(rootfs)) {
            "pinned ARM rootfs retained an unscoped OpenGL client"
        }
    }

    /**
     * Remove the provider's generic libGL names only while constructing a new,
     * unpublished rootfs. Renderer clients are installed later in attested,
     * generation-local directories; active/shared roots are never mutated by
     * renderer selection or lease preparation.
     */
    private fun writeReadyMarker(rootfs: File) {
        val marker = File(rootfs, READY_MARKER)
        val value = JSONObject()
            .put("schema", SCHEMA)
            .put("rootfsAssetSha256", ROOTFS_SHA256)
            .put("patchesAssetSha256", PATCHES_SHA256)
            .put("containerPatternAssetSha256", CONTAINER_PATTERN_SHA256)
            .toString()
        FileOutputStream(marker).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        syncDirectory(rootfs)
    }

    private fun isReady(rootfs: File): Boolean = runCatching {
        val marker = JSONObject(File(rootfs, READY_MARKER).readText(Charsets.UTF_8))
        marker.getInt("schema") == SCHEMA &&
            marker.getString("rootfsAssetSha256") == ROOTFS_SHA256 &&
            marker.getString("patchesAssetSha256") == PATCHES_SHA256 &&
            marker.getString("containerPatternAssetSha256") == CONTAINER_PATTERN_SHA256 &&
            File(rootfs, "opt/wine/bin/wine").isFile &&
            File(rootfs, "home/xuser/.wine/system.reg").isFile &&
            File(rootfs, "home/xuser/.wine/user.reg").isFile &&
            File(rootfs, "home/xuser/.wine/userdef.reg").isFile &&
            File(rootfs, "home/xuser/.wine/.update-timestamp").isFile &&
            hasNoUnscopedOpenGlClient(rootfs) &&
            isExpectedAudioPlugin(
                File(rootfs, "usr/lib/alsa-lib/libasound_module_pcm_android_aserver.so"),
            )
    }.getOrDefault(false)

    private fun checkContained(root: File, target: File) {
        check(target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            "ARM rootfs target escaped its generation root"
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    companion object {
        internal fun isArchiveRootDirectory(name: String, directory: Boolean): Boolean =
            directory && (name == "." || name == "./")

        internal fun isRetiredContainerRootLink(
            assetPath: String,
            entry: TarArchiveEntry,
        ): Boolean =
            assetPath == CONTAINER_PATTERN_ASSET &&
                entry.isSymbolicLink &&
                entry.name.removePrefix("./") == ".wine/dosdevices/z:" &&
                entry.linkName == "/data/data/com.winlator/files/rootfs"

        // Schema 4 also republishes any prior rootfs that retained provider
        // libGL aliases outside the renderer-generation identity.
        private const val SCHEMA = 4
        private const val READY_MARKER = ".pocket-rootfs-ready"
        private const val ROOTFS_ASSET = "arm-translated-wine/rootfs.tzst"
        private const val PATCHES_ASSET = "arm-translated-wine/rootfs_patches.tzst"
        private const val CONTAINER_PATTERN_ASSET =
            "arm-translated-wine/container_pattern.tzst"
        private const val ROOTFS_SHA256 =
            "8b5110f248e84f2aee4df37dab8bac4c4bf2bdc7b400c0643a0778ca8e7e40c2"
        private const val PATCHES_SHA256 =
            "44b73e37587ea827a12a34753632feb6e2a9c127089e342774167dd91aba8210"
        private const val CONTAINER_PATTERN_SHA256 =
            "8ae3a4fee33e86da26826395650bb07c6f49ce94629ea4b9442bc633b6b8ca33"
        private const val AUDIO_PLUGIN_SIZE = 73_216L
        private const val AUDIO_PLUGIN_SHA256 =
            "209927b86066863fbe4f3607273577d4af1534036d3b5b59f87b882b15f3346c"
        private const val ELF_MACHINE_AARCH64 = 0x00b7
        private const val MAX_ENTRIES = 100_000
        private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
        internal fun isUnscopedOpenGlClientName(name: String): Boolean =
            name == "libGL.so" || name.startsWith("libGL.so.")

        internal fun hasNoUnscopedOpenGlClient(rootfs: File): Boolean {
            val libraryDirectory = File(rootfs, "usr/lib")
            if (!libraryDirectory.exists()) return true
            if (!libraryDirectory.isDirectory || Files.isSymbolicLink(libraryDirectory.toPath())) {
                return false
            }
            val entries = libraryDirectory.listFiles() ?: return false
            return entries.none { isUnscopedOpenGlClientName(it.name) }
        }

        internal fun stripUnscopedOpenGlClient(rootfs: File) {
            val canonicalRoot = rootfs.canonicalFile.toPath()
            val libraryDirectory = File(rootfs, "usr/lib")
            check(libraryDirectory.isDirectory &&
                !Files.isSymbolicLink(libraryDirectory.toPath()) &&
                libraryDirectory.canonicalFile.toPath().startsWith(canonicalRoot)) {
                "ARM rootfs library directory escaped its staging root"
            }
            val targets = checkNotNull(libraryDirectory.listFiles()) {
                "ARM rootfs library directory could not be enumerated"
            }.filter { isUnscopedOpenGlClientName(it.name) }
            for (target in targets) {
                // Delete the directory entry itself. Never canonicalize/follow
                // a selected symlink: an outside target must remain untouched.
                if (Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    check(target.delete()) {
                        "unscoped ARM OpenGL client could not be removed: ${target.name}"
                    }
                }
            }
            check(hasNoUnscopedOpenGlClient(rootfs)) {
                "unscoped ARM OpenGL client is still present"
            }
        }

        internal fun isExpectedAudioPlugin(file: File): Boolean = runCatching {
            file.isFile &&
                !Files.isSymbolicLink(file.toPath()) &&
                isExpectedAudioPluginIdentity(
                    file.length(), sha256(file), elfMachine(file),
                )
        }.getOrDefault(false)

        internal fun isExpectedAudioPluginIdentity(
            size: Long,
            sha256: String,
            elfMachine: Int,
        ): Boolean = size == AUDIO_PLUGIN_SIZE &&
            sha256 == AUDIO_PLUGIN_SHA256 && elfMachine == ELF_MACHINE_AARCH64

        private fun sha256(file: File): String =
            com.pocketrealm.fs.FileDigests.sha256(file)

        private fun elfMachine(file: File): Int {
            val header = ByteArray(20)
            file.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    check(read > 0) { "truncated ELF header" }
                    offset += read
                }
            }
            check(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[4] == 2.toByte() && header[5] == 1.toByte()) {
                "unsupported ELF header"
            }
            return ByteBuffer.wrap(header, 18, 2).order(ByteOrder.LITTLE_ENDIAN)
                .short.toInt() and 0xffff
        }
    }
}
