package com.pocketrealm.importer

import java.text.Normalizer
import java.util.Locale

/** Canonical NFKC policy implementing report Appendix B.7. */
class ImportPathPolicy(private val limits: ImportLimits = ImportLimits()) {
    fun normalize(rawRelative: String): String {
        if (rawRelative.any { it == '\u0000' || Character.isISOControl(it) }) {
            throw ImportRejected("VAL-07: control character in source path")
        }
        val separated = rawRelative.replace('\\', '/')
        if (separated.startsWith('/') || DRIVE.matches(separated)) {
            throw ImportRejected("VAL-07: absolute source path")
        }
        val rawParts = separated.split('/')
        if (rawParts.isEmpty() || rawParts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw ImportRejected("VAL-07: empty or traversal source path")
        }
        if (rawParts.size > limits.maxDepth) throw ImportRejected("VAL-08: source depth exceeds ${limits.maxDepth}")
        val normalized = rawParts.map { part ->
            val value = Normalizer.normalize(part, Normalizer.Form.NFKC)
            if (value.isBlank() || value.length > limits.maxComponentChars ||
                value.any { it == '\u0000' || Character.isISOControl(it) || it == '/' || it == '\\' }) {
                throw ImportRejected("VAL-07: unsafe source path component")
            }
            if (value == "." || value == "..") throw ImportRejected("VAL-07: normalized traversal component")
            value
        }
        return normalized.joinToString("/")
    }

    fun providerChild(parent: String?, displayName: String): String {
        if (displayName.contains('/') || displayName.contains('\\')) {
            throw ImportRejected("VAL-07: provider name contains a path separator")
        }
        return normalize(if (parent.isNullOrEmpty()) displayName else "$parent/$displayName")
    }

    fun caseFoldKey(canonicalRelative: String): String =
        Normalizer.normalize(canonicalRelative, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    companion object { private val DRIVE = Regex("^[A-Za-z]:($|/).*") }
}
