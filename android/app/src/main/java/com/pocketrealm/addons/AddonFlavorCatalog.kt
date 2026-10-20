package com.pocketrealm.addons

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors
import java.util.Locale

/**
 * The add-on catalog projected onto the ACTIVE flavor (the multi-flavor
 * lift of the add-ons screens): the screens list ONLY the active flavor's
 * addon set, resolved from the catalog's own research data.
 *
 * A catalog entry's researched client compatibility is its free-text
 * `clientTarget` field ("1.12.1", "1.12.1 + 2.4.3", "1.12 + 2.4.3 +
 * 3.3.5", ...). Each flavor's researched version tokens are matched
 * against that text — a flavor's set is exactly the entries whose research
 * names that client line. The vanilla token matches every researched entry
 * (the catalog is researched from the 1.12 line), so the vanilla lane's
 * list is unchanged from the pre-multi-flavor screens; the TBC and WotLK
 * lanes list only entries whose research covers those clients, and an
 * honestly short (even empty) list beats a mislabeled one.
 *
 * Install-time `## Interface:` acceptance is the separate
 * [com.pocketrealm.client.ClientFlavor.addonInterfaces] seam (DESK-54) on
 * [com.pocketrealm.addons.AddonArchiveValidator.Policy]; this file is the
 * LIST projection only.
 */
object AddonFlavorCatalog {

    /**
     * The client-line tokens a flavor's researched add-ons declare in
     * `clientTarget`. Vanilla matches the whole researched 1.12 line.
     */
    fun versionTokens(flavor: ClientFlavor): List<String> = when (flavor.flavorId) {
        ClientFlavors.VANILLA.flavorId -> listOf("1.12")
        ClientFlavors.TBC.flavorId -> listOf("2.4.3")
        ClientFlavors.WOTLK.flavorId -> listOf("3.3.5")
        else -> emptyList()
    }

    /** A NEGATION-clause cue followed (within a short window) by a client
     * line token: research text like "explicitly NOT for 2.4.3" or "broken
     * since 3.3.5" MENTIONS a client line in a negative context. R2-1
     * (android-ui-tests review round 1): bare substring matching read such
     * mentions as SUPPORT, listing the add-on on the wrong-flavor lane. */
    internal val NEGATION_CUES: Regex = Regex(
        "\\b(?:not|never|no|broken|incompatible|unsupported|untested|unsafe|unusable|" +
            "abandoned|crashes|crashed|fails|failed|dropped|stops?|stopped|dead|" +
            "doesn't|didn't|wouldn't|shouldn't|couldn't|isn't|aren't|wasn't|weren't|" +
            "hasn't|haven't|hadn't|" +
            "don't|does not|do not|did not|will not|won't|would not|" +
            "cannot|can't|could not|couldn't|should not|shouldn't|shall not|" +
            "is not|are not|was not|were not|has not|have not|had not|" +
            "except|other than|excluding|excluded|aside from)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** R2-1/R6-r2-1 (android-ui-tests review rounds 1-2): load-time guard
     * for the catalog parse — a `clientTarget` that names a client line
     * inside a negation clause is rejected instead of being silently
     * matched as SUPPORT by the flavor projection. */
    internal fun requirePositiveClientTarget(clientTarget: String, addonName: String) {
        val lower = clientTarget.lowercase(Locale.ROOT)
        for (lane in listOf("1.12", "2.4.3", "3.3.5")) {
            check(!negationCovers(lower, lane)) {
                "addon \"$addonName\": clientTarget negation-covers the $lane client " +
                    "line (\"$clientTarget\") - research text must POSITIVELY list the " +
                    "supported client lines (the flavor projection matches version tokens " +
                    "by substring and would list this add-on on the wrong-flavor lane)"
            }
        }
    }

    /** True when the entry's researched targets cover [flavor]'s client
     * line: a version token matches at a version boundary (never inside a
     * longer version like 2.4.35) and NEVER inside a negation clause. */
    fun supportsFlavor(addon: CatalogAddon, flavor: ClientFlavor): Boolean =
        versionTokens(flavor).any { token ->
            versionTokenMatches(addon.clientTarget, token) &&
                !negationCovers(addon.clientTarget, token)
        }

    /** Boundary-bounded token match (regression guard): the match's
     * neighbours must not be a DIGIT (so 2.4.3 never matches inside
     * 2.4.35, nor 1.12 inside 11.2); a following DOT is the same client
     * line family (the researched "1.12" line covers 1.12.1). */
    internal fun versionTokenMatches(text: String, token: String): Boolean {
        var at = text.indexOf(token)
        while (at >= 0) {
            val beforeOk = at == 0 || !text[at - 1].isDigit()
            val end = at + token.length
            val afterOk = end >= text.length || !text[end].isDigit()
            if (beforeOk && afterOk) return true
            at = text.indexOf(token, at + 1)
        }
        return false
    }

    /** R2UI-2/R6-r2-2/R2-1 (android-ui-tests review round 2): the negation
     * model — a WIDE cue set evaluated in a WINDOW AROUND each
     * boundary-valid, suffix-tolerant token occurrence. The round-1
     * cue-to-token regex missed the common phrasings ("incompatible with
     * X", "unsupported on X", "won't load on X", "crashes on X", "unusable
     * on X"), cue-AFTER-token negations ("2.4.3 not supported") and the
     * canonical suffixed wotlk spelling ("3.3.5a" — no word boundary after
     * the bare token). Cues match case-insensitively in a window crossing
     * sentence periods; an occurrence with ANY cue in its window is
     * negation-covered. Conservative direction: exclusion from a flavor's
     * list under-reports rather than wrong-lane lists. The live
     * catalog-v1.json verified clean under the widened model. */
    internal fun negationCovers(text: String, token: String): Boolean {
        if (text.isBlank()) return false
        // R3-R2-1 (android-ui-tests review round 3): the WHOLE clientTarget
        // is scanned — no distance window. The 80/40-char window let
        // a cue beyond the window ("compatible with every release from the
        // 1.12 line except 2.4.3" with the cue 132 chars before) accept a
        // wrong-lane listing; a clientTarget that negation-covers ANY lane
        // is research text that must be rewritten positively, so the
        // conservative whole-text scan is the correct direction.
        val lower = text.lowercase(Locale.ROOT)
        // suffix-tolerant variants: the canonical letter-suffixed spellings
        // ("3.3.5a") name the same client line.
        val variants = listOf(token, "${token}a", "${token}b", "${token}c")
        return variants.any { variant -> negationCoversVariant(lower, variant) }
    }

    private fun negationCoversVariant(lowerText: String, variant: String): Boolean {
        var at = lowerText.indexOf(variant)
        while (at >= 0) {
            val end = at + variant.length
            // digit neighbours are a DIFFERENT version (2.4.35 / 12.4.3)
            val beforeOk = at == 0 || !lowerText[at - 1].isDigit()
            val afterIsDigit = end < lowerText.length && lowerText[end].isDigit()
            if (beforeOk && !afterIsDigit &&
                NEGATION_CUES.containsMatchIn(lowerText)
            ) {
                return true
            }
            at = lowerText.indexOf(variant, end)
        }
        return false
    }

    /** The active flavor's addon set, catalog order preserved. */
    fun forFlavor(addons: List<CatalogAddon>, flavor: ClientFlavor): List<CatalogAddon> =
        addons.filter { supportsFlavor(it, flavor) }

    /**
     * The query filter of [com.pocketrealm.addons.AddonCatalog.filter]
     * (same fields, same ordering) over an already flavor-projected list —
     * the Browse screens filter the ACTIVE flavor's set with it.
     */
    fun filter(addons: List<CatalogAddon>, query: String): List<CatalogAddon> {
        val needle = query.trim().lowercase(Locale.ROOT)
        return addons.asSequence()
            .filter { addon ->
                needle.isBlank() || listOf(
                    addon.name, addon.category, addon.description, addon.compatibilityRole,
                    addon.compatibilityNotes, addon.handheldVerdict,
                ).any { needle in it.lowercase(Locale.ROOT) }
            }
            .sortedWith(compareByDescending<CatalogAddon> { it.installable }
                .thenByDescending { it.handheldScore }
                .thenBy { it.name.lowercase(Locale.ROOT) })
            .toList()
    }
}
