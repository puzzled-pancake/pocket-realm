package com.pocketrealm.server

/**
 * Fail-loud conf staging: every key the server
 * runtime stages into an `aiplayerbot-*.conf` must be KNOWN for the
 * flavor's bots build — an unknown key used to be silently dropped by the
 * core's config parser, shipping a bot world that quietly ignored the app's
 * policy values. The known-key set is the flavor's conf matrix
 * ([GeneratedBotConfKeyCatalog], generated from the pinned .conf.dist
 * surfaces) plus the catalog's code-read sets (the patch-injected
 * PocketRealm and LLM keys the built core reads — ENUMERATED, never a
 * family-level accept: a typo'd overlay key is refused like any other
 * unknown key). Excluded families (correction: the wotlk VoiceChat
 * inert stub) are honored by the consumer — a key in an excluded family is
 * refused with its OWN naming, never the generic unknown-key error.
 */
internal object BotConfStaging {

    private val KEY_LINE = Regex("""^\s*#?\s*([A-Za-z][A-Za-z0-9_.-]*)\s*=""")
    private val EXPECTED_KEY = Regex("""^[A-Za-z][A-Za-z0-9_.-]*$""")

    /** The verdict for one staged conf body. */
    data class Verdict(
        val unknownKeys: List<String>,
        val excludedKeys: List<String>,
    ) {
        val isClean: Boolean get() = unknownKeys.isEmpty() && excludedKeys.isEmpty()
    }

    /** Validate [confText] for [flavorId]: every key line's key must be
     * known (matrix ∪ core-read) and outside the flavor's excluded
     * families. Comments (`#`-prefixed keys) validate identically — a
     * commented unknown key is the same drift. */
    fun validate(flavorId: String, confText: String): Verdict {
        val matrixKeys = GeneratedBotConfKeyCatalog.keysByFlavor[flavorId]
            ?: error("no conf-key catalog registered for flavor \"$flavorId\"")
        val codeRead = GeneratedBotConfKeyCatalog.codeReadKeysByFlavor[flavorId].orEmpty()
        val known = matrixKeys + codeRead
        val excluded = GeneratedBotConfKeyCatalog.excludedFamiliesByFlavor[flavorId]
            ?: emptySet()
        val unknown = linkedSetOf<String>()
        val excludedHits = linkedSetOf<String>()
        confText.lineSequence().forEach { line ->
            val match = KEY_LINE.find(line) ?: return@forEach
            val key = match.groupValues[1]
            if (!EXPECTED_KEY.matches(key)) return@forEach
            val family = key.substringBefore('.', missingDelimiterValue = key)
            when {
                family in excluded -> excludedHits += key
                // NO family-level accept — the shipped PocketRealm.*
                // emission keys are ENUMERATED in the code-read sets, so
                // `key in known` covers them; a blanket startsWith accept
                // would wave through a typo'd or drifted overlay key (the
                // exact silent-drop class this gate exists to kill).
                key in known -> Unit
                else -> unknown += key
            }
        }
        return Verdict(unknown.sorted(), excludedHits.sorted())
    }

    /**
     * The staging gate: throws with the actionable per-class message when
     * [confText] is not staging-safe for [flavorId]. The message names the
     * profile, the offending keys, and the regeneration path — a silent
     * drop must be impossible to ship.
     */
    fun requireStagingSafe(flavorId: String, profileId: String, confText: String) {
        val verdict = validate(flavorId, confText)
        check(verdict.excludedKeys.isEmpty()) {
            "aiplayerbot-${profileId}.conf carries key(s) ${verdict.excludedKeys} from " +
                "an EXCLUDED family for flavor $flavorId — the family is recorded " +
                "under schemas/flavor.json conf_matrix.excluded_keys (correction C9: " +
                "the core's implementation is an inert stub with zero conf keys); " +
                "remove the keys from the emission"
        }
        check(verdict.unknownKeys.isEmpty()) {
            "aiplayerbot-${profileId}.conf carries key(s) ${verdict.unknownKeys} that " +
                "the flavor $flavorId bots build does not define — the core would " +
                "silently drop them (fail-loud conf staging, W3.4). Known keys: " +
                "the committed schemas/conf-matrix-$flavorId.json for the tbc/wotlk " +
                "lanes (vanilla is the G3-7 CI-run lane and commits no matrix — its " +
                "known set rides the generated catalog); regenerate the Kotlin " +
                "catalog: python tools/generate_conf_matrix.py --kotlin-catalog; " +
                "fix the emission or register the key with the flavor's dist"
        }
    }
}
