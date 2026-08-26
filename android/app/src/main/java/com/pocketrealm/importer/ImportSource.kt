package com.pocketrealm.importer

import java.io.InputStream

/**
 * Unified import source entry. The folder lane maps SAF document rows onto
 * this type; archive lanes map archive entries (key = stable entry identity,
 * lastModified = 0 because container timestamps are not stable across
 * re-open). Everything downstream — journal rows, copy loop, verification,
 * publish — consumes only this shape.
 */
data class ImportSourceEntry(
    val key: String,
    val relativePath: String,
    val directory: Boolean,
    val size: Long,
    val lastModified: Long,
    val attributes: String,
)

/**
 * A bounded, read-only view of client files to import: an inventory (already
 * policy-normalized, wrapper-rebased, capped by [ImportLimits]) plus
 * per-entry stream access. [SafTreeSource] is the folder implementation;
 * archive lanes implement this over a staged archive file.
 */
interface ImportSource : AutoCloseable {
    fun inventory(): SourceInventory

    /** Stream for one non-directory entry from [SourceInventory.entries]. */
    fun open(entry: ImportSourceEntry): InputStream

    override fun close() {}
}
