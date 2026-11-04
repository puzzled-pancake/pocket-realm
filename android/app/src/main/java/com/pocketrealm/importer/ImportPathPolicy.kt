package com.pocketrealm.importer

import java.text.Normalizer
import java.util.Locale

/** Canonical NFKC policy implementing report Appendix B.7. */
class ImportPathPolicy(private val limits: ImportLimits = ImportLimits()) {
    fun normalize(rawRelative: String): String {
        if (rawRelative.any { it == '\u0000' || Character.isISOControl(it) }) {
            throw ImportRejected("control character in source path")
        }
        val separated = rawRelative.replace('\\', '/')
        if (separated.startsWith('/') || DRIVE.matches(separated)) {
            throw ImportRejected("absolute source path")
        }
        val rawParts = separated.split('/')
        if (rawParts.isEmpty() || rawParts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw ImportRejected("empty or traversal source path")
        }
        if (rawParts.size > limits.maxDepth) throw ImportRejected("A source path is nested too deeply")
        val normalized = rawParts.map { part ->
            val value = Normalizer.normalize(part, Normalizer.Form.NFKC)
            if (value.isBlank() || value.length > limits.maxComponentChars ||
                value.any { it == '\u0000' || Character.isISOControl(it) || it == '/' || it == '\\' }) {
                throw ImportRejected("unsafe source path component")
            }
            if (value == "." || value == "..") throw ImportRejected("normalized traversal component")
            value
        }
        return normalized.joinToString("/")
    }

    /** Archive-entry normalization: [normalize] plus rejection of
     *  Windows-illegal trailing dot/space components — they survive NFKC
     *  and NTFS aliases "x." onto "x", evading the importer's
     *  root-executable exclusion and the fold-collision duplicate gate. */
    fun normalizeEntry(rawRelative: String): String {
        val normalized = normalize(rawRelative)
        normalized.split('/').forEach { component ->
            if (component.length > 1 && (component.last() == '.' || component.last() == ' ')) {
                throw ImportRejected("trailing dot or space in component: '$component'")
            }
        }
        return normalized
    }

    fun providerChild(parent: String?, displayName: String): String {
        if (displayName.contains('/') || displayName.contains('\\')) {
            throw ImportRejected("provider name contains a path separator")
        }
        return normalize(if (parent.isNullOrEmpty()) displayName else "$parent/$displayName")
    }

    fun caseFoldKey(canonicalRelative: String): String =
        Normalizer.normalize(canonicalRelative, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    companion object { private val DRIVE = Regex("^[A-Za-z]:($|/).*") }
}
