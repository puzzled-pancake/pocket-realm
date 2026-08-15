package com.pocketrealm.addons

import java.io.File
import java.nio.file.Files

/** Removes extraction directories that can survive an app-process death before publication. */
internal fun cleanupStaleAddonStaging(packages: File): Int {
    if (!packages.isDirectory) return 0
    val packageRoot = packages.canonicalFile
    var removed = 0
    packages.listFiles().orEmpty()
        .filter { it.name.startsWith(".staging-") }
        .forEach { stale ->
            val parent = checkNotNull(stale.absoluteFile.parentFile) {
                "Add-on staging path has no parent"
            }
            require(parent.canonicalFile == packageRoot) {
                "Unsafe add-on staging path"
            }
            val deleted = if (Files.isSymbolicLink(stale.toPath())) stale.delete() else stale.deleteRecursively()
            check(deleted || !stale.exists()) { "Stale add-on staging directory could not be removed" }
            removed++
        }
    return removed
}
