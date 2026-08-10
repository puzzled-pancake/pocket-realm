package com.pocketrealm.addons

import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileOutputStream

/** Extracts only the immutable allow-list produced by [AddonArchiveValidator]. */
internal class AddonArchiveExtractor {
    fun extract(
        archive: File,
        validated: AddonArchiveValidator.Validated,
        destination: File,
        checkpoint: () -> Unit = {},
    ) {
        val allowed = validated.entries.associateBy { it.archiveName }
        val destinationRoot = destination.canonicalPath + File.separator
        ZipFile.builder().setFile(archive).get().use { zip ->
            zip.entries.asSequence().forEach { entry ->
                checkpoint()
                val record = allowed[entry.name] ?: return@forEach
                val target = File(destination, record.relativeName)
                require(target.canonicalPath.startsWith(destinationRoot)) {
                    "Add-on extraction escaped its staging directory"
                }
                target.parentFile!!.mkdirs()
                var copied = 0L
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            checkpoint()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= record.size && copied <= MAX_FILE_BYTES) {
                                "Add-on entry expanded past its declared size"
                            }
                            output.write(buffer, 0, count)
                        }
                        checkpoint()
                        output.fd.sync()
                    }
                }
                require(copied == record.size) { "Add-on entry size changed during extraction" }
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 32 * 1024
        const val MAX_FILE_BYTES = 128L * 1024 * 1024
    }
}
