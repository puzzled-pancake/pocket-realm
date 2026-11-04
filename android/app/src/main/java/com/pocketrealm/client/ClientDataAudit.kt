package com.pocketrealm.client

/**
 * The client-data half of the import identity decision, shared by every
 * lane (Android folder + archive, desktop folder + archive) so the four
 * scanners judge a client by one rule set.
 *
 * The rules are deliberately tolerant of ordinary client differences:
 *  - the localized MPQ keys are matched in ANY locale (`Data/deDE/`,
 *    `Data/enGB/`, ...), not only enUS;
 *  - extra patch MPQs (custom or HD patches) are kept with a warning;
 *  - an optional MPQ with an unreadable header is a warning — only a
 *    required MPQ the game cannot load refuses the import;
 *  - a WoW.exe with no version resource is identified from its Data set.
 *
 * What still refuses: a client of another version, a missing required
 * MPQ, a required MPQ that is not an MPQ.
 */
internal object ClientDataAudit {

    data class Verdict(
        val flavor: ClientFlavor?,
        val failures: List<String>,
        val warnings: List<String>,
    )

    /**
     * Resolves the flavor for [identity] and audits the Data MPQ set.
     * [mpqRelPaths] are the MPQ paths relative to `Data/` (any case);
     * [readHeader] returns the first bytes of one of them, or null when it
     * cannot be read. [expectedVersions] is the expected-versions text.
     */
    fun audit(
        identity: ClientPeIdentity.Identity?,
        mpqRelPaths: Collection<String>,
        expectedVersions: String,
        dataPresent: Boolean = true,
        readHeader: (String) -> ByteArray?,
    ): Verdict {
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val paths = mpqRelPaths.map { it.lowercase() }.distinct()
        var flavor = ClientPeIdentity.qualifiedFlavor(identity)
        if (identity != null && flavor == null) {
            val inferred = if (identity.version == null) inferFlavor(paths) else null
            if (inferred != null) {
                flavor = inferred
                warnings += "WoW.exe has no version info — identified as version " +
                    "${inferred.versionResource} from its Data files"
            } else {
                failures += "Unsupported game version — expected $expectedVersions, found ${ClientPeIdentity.detected(identity)}"
            }
        }
        val detected = identity?.detectedVersion
        if (flavor != null && detected != null && detected != flavor.versionResource) {
            warnings += "Build $detected accepted — the realm also accepts builds " +
                "${flavor.realmdAcceptedBuilds}"
        }
        if (flavor == null || !dataPresent) return Verdict(flavor, failures.distinct(), warnings.distinct())

        val locale = bestLocale(flavor, paths)
        val required = requiredPaths(flavor, locale)
        val missing = missingFor(flavor, paths, locale)
        if (missing.isNotEmpty()) failures += "Missing game data files: ${missing.joinToString()}"
        if (locale != DEFAULT_LOCALE && locale in localesIn(paths)) {
            warnings += "Client locale ${locale.take(2)}${locale.drop(2).uppercase()}"
        }
        for (rel in paths) {
            val isRequired = rel in required.exact || rel.substringAfterLast('/') in required.bare
            val base = rel.substringAfterLast('/')
            if (!isRequired && base.startsWith("patch") && base.endsWith(".mpq")) {
                warnings += "Extra patch file kept: Data/$rel"
            }
            if (!isMpqHeader(readHeader(rel))) {
                if (isRequired) {
                    failures += "Required data file is not a valid MPQ: Data/$rel"
                } else {
                    warnings += "Data/$rel may not be a valid MPQ — the game may skip it"
                }
            }
        }
        return Verdict(flavor, failures.distinct(), warnings.distinct())
    }

    /** "MPQ\u001a" (archive header) or "MPQ\u001b" (the user-data block
     * some patch MPQs lead with). */
    fun isMpqHeader(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size >= MPQ_MAGIC_LEN &&
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'Q'.code.toByte() &&
            (bytes[3] == MPQ_ARCHIVE_TAG || bytes[3] == MPQ_USER_DATA_TAG)

    /** Locale directories present under Data (lowercase), e.g. "dede": a
     * four-letter directory holding that locale's own locale-<xx>.mpq. */
    fun localesIn(paths: Collection<String>): Set<String> = paths.mapNotNull { rel ->
        val parts = rel.lowercase().split('/')
        val dir = parts.getOrNull(parts.size - 2) ?: return@mapNotNull null
        dir.takeIf { LOCALE_DIR.matches(it) && parts.last().startsWith("locale-$it") }
    }.toSet()

    /** Flavor from the Data set alone (only for an exe with no version
     * resource): the qualified flavor whose whole locale-free MPQ set is
     * present, the largest such set winning (a later expansion carries
     * its predecessor's archives too). */
    fun inferFlavor(paths: Collection<String>): ClientFlavor? {
        val bases = paths.map { it.lowercase().substringAfterLast('/') }.toSet()
        return ClientFlavors.qualified()
            .map { flavor -> flavor to flavor.requiredMpqs.map { it.lowercase() }.filterNot { '/' in it } }
            .filter { (_, bare) -> bare.isNotEmpty() && bases.containsAll(bare) }
            .maxByOrNull { (_, bare) -> bare.size }
            ?.first
    }

    private class Required(val exact: Set<String>, val bare: Set<String>)

    private fun requiredPaths(flavor: ClientFlavor, locale: String): Required {
        val keys = flavor.requiredMpqs.map { it.lowercase() }
        return Required(
            exact = keys.filter { '/' in it }.map { it.replace(DEFAULT_LOCALE, locale) }.toSet(),
            bare = keys.filterNot { '/' in it }.toSet(),
        )
    }

    private fun missingFor(flavor: ClientFlavor, paths: List<String>, locale: String): List<String> {
        val present = paths.toSet()
        val bases = paths.map { it.substringAfterLast('/') }.toSet()
        val required = requiredPaths(flavor, locale)
        return required.exact.filterNot { it in present } + required.bare.filterNot { it in bases }
    }

    /** The locale whose localized keys this client satisfies best; enUS
     * when the client carries no locale directory at all (vanilla). */
    private fun bestLocale(flavor: ClientFlavor, paths: List<String>): String =
        (listOf(DEFAULT_LOCALE) + localesIn(paths).sorted())
            .minBy { missingFor(flavor, paths, it).size }

    private const val DEFAULT_LOCALE = "enus"
    private val LOCALE_DIR = Regex("^[a-z]{4}$")
    private const val MPQ_MAGIC_LEN = 4
    private const val MPQ_ARCHIVE_TAG: Byte = 0x1a
    private const val MPQ_USER_DATA_TAG: Byte = 0x1b
}
