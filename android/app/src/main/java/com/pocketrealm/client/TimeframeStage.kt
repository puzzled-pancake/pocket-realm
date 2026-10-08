package com.pocketrealm.client

/**
 * The per-flavor content-patch timeframe registry — the shared core of the
 * "World timeframe" control (design §1–§5 of
 * docs/DESKTOP-MULTIPLAYER-AND-TIMEFRAME-DESIGN.md).
 *
 * The control adds a STAGE dimension under the flavor: one content patch of
 * that flavor's timeline. The client patch is FIXED per flavor — picking an
 * earlier stage never changes what binary, DBCs, maps, or UI the client runs;
 * it only gates world CONTENT availability (design §0's one plain sentence).
 *
 * Default = the flavor's FINAL patch (vanilla-1.12.1 / tbc-2.4.3 /
 * wotlk-3.3.5a): the world as the pinned DB already ships it. Every stage
 * above the floor is MORE restrictive than the seed, never inventive — the
 * control can only close content earlier or re-open it at its own point in
 * the timeline; it cannot resurrect pre-nerf data the seed never had.
 *
 * The registry mirrors schemas/timeframes/<flavor>.json (schema 1) the same
 * way [ClientFlavors] mirrors schemas/flavor.json; tools/check_timeframes.py
 * is the sync gate (ids + orders here == the manifests; the vanilla
 * final-stage era baseline == the native patch lists). Per-stage measured
 * availability id sets land with the per-(flavor, stage) DB pin (design
 * §9.2); until then every set is empty and `measured=false`, and the gating
 * predicates pass the seed's own availability straight through — existing
 * worlds are unaffected by construction.
 */
data class TimeframeStage(
    /** Flavor slug matching ClientFlavor.flavorId ("vanilla", "tbc", "wotlk"). */
    val flavorId: String,
    /** Canonical flat stage id, e.g. "vanilla-1.12.1" — everything keys off this string. */
    val id: String,
    /** 0-based position on the flavor's timeline (earlier patch = lower order). */
    val order: Int,
    /** Short selection label ("Patch 1.12.1 — current release content"). */
    val title: String,
    /** One honest line of per-stage copy for the settings surfaces. */
    val copy: String,
) {
    /**
     * Per-stage event-availability block (design §3, the game_event leg).
     * The stage overlay MOVES `game_event` windows (never event content):
     * [openEventIds] forces listed events active at this stage, [closeEventIds]
     * forces them inactive. EMPTY + [measured]=false until the S4 DB pin
     * curates the id sets against the pinned seed — the honest v1 posture:
     * the mechanism is live, the curated data is not, so every stage passes
     * the seed's own windows through (existing worlds unaffected).
     */
    data class EventGating(
        val openEventIds: Set<Int>,
        val closeEventIds: Set<Int>,
        val measured: Boolean,
    )

    /** One later-era sense phrase: (lowercase needle, negation-escapes). */
    data class EraSensePhrase(val needle: String, val negationEscapes: Boolean)

    /**
     * The chat era-policy term classes for one stage (design §5): the world
     * resolves its class set from the same stage id the conf carries. The
     * never-ban discipline rides the lists themselves — context-allow words
     * (northrend, outland, dalaran, lich king, naxxramas, wrath, legion…)
     * collide with earlier legitimate vocabulary and NEVER appear; only full
     * later-era senses are banned.
     */
    data class EraTermClass(
        val alwaysBanTerms: List<String>,
        val sensePhrases: List<EraSensePhrase>,
    )

    val isFinal: Boolean get() = id == TimeframeStages.defaultStageId(flavorId)
}

// The registry is one reviewed data table (the ClientFlavors twin): stage
// timelines, the settings-layer resolution, the gating predicates, and the
// era-policy resolution — twelve small members over three data lists.
@Suppress("TooManyFunctions")
object TimeframeStages {

    /**
     * The one conf key both ServerRuntimeFiles twins emit at world start
     * (design §4.2): `PocketRealm.TimeframeStage = "<stage-or-default>"`,
     * read at the world's next start — never mid-session.
     */
    const val CONF_KEY: String = "PocketRealm.TimeframeStage"

    /** Provenance string carried by every un-measured availability set. */
    const val PENDING_MEASUREMENT_PROVENANCE: String =
        "pending-s4-pin: measured id sets land with the per-(flavor, stage) DB pin"

    // -- The stage timelines (mirroring schemas/timeframes/<flavor>.json) --
    // Orders derive from list position; the last patch of each flavor is its
    // final/default stage. The patch strings are the ask's shipped stages:
    // vanilla 1.1→1.12.1, TBC 2.0.1→2.4.3, WotLK 3.0.2→3.3.5a.

    private val VANILLA_PATCHES = listOf(
        "1.1", "1.2", "1.3", "1.4", "1.5", "1.6",
        "1.7", "1.8", "1.9", "1.10", "1.11", "1.12.1",
    )
    private val TBC_PATCHES = listOf("2.0.1", "2.1", "2.2", "2.3", "2.4.3")
    private val WOTLK_PATCHES = listOf("3.0.2", "3.1", "3.2", "3.3", "3.3.5a")

    /** Vanilla 1.1 → 1.12.1, the ask's twelve shipped stages. */
    val VANILLA: List<TimeframeStage> = stages("vanilla", VANILLA_PATCHES)

    /** TBC 2.0.1 → 2.4.3 (declared lane, unqualified — ClientFlavors.TBC). */
    val TBC: List<TimeframeStage> = stages("tbc", TBC_PATCHES)

    /** WotLK 3.0.2 → 3.3.5a (declared lane, unqualified — ClientFlavors.WOTLK). */
    val WOTLK: List<TimeframeStage> = stages("wotlk", WOTLK_PATCHES)

    val ALL: List<TimeframeStage> = VANILLA + TBC + WOTLK

    private fun stages(flavorId: String, patches: List<String>): List<TimeframeStage> =
        patches.mapIndexed { order, patch ->
            val final = order == patches.lastIndex
            TimeframeStage(
                flavorId = flavorId,
                id = "$flavorId-$patch",
                order = order,
                title = if (final) "Patch $patch — current release content" else "Patch $patch",
                copy = "World content availability as of patch $patch." +
                    if (final) "" else " Later-patch content stays closed; characters keep " +
                        "items, levels, and spells they already have.",
            )
        }

    /** The ordered stages of one flavor; unknown flavor ids yield an empty list. */
    fun stagesFor(flavorId: String): List<TimeframeStage> =
        ALL.filter { it.flavorId == flavorId }

    /** The flavor's DEFAULT stage: the final patch (design §1). */
    fun defaultStageId(flavorId: String): String =
        stagesFor(flavorId).maxByOrNull { it.order }?.id ?: ""

    /** Exact lookup within one flavor; null for an impossible stage id. */
    fun byId(flavorId: String, stageId: String): TimeframeStage? =
        stagesFor(flavorId).firstOrNull { it.id == stageId }

    /**
     * Impossible-stage rejection at the strict layer: throws for any id that
     * is not a registered stage of [flavorId]. The settings/UI layers never
     * call this on user input (they degrade through [resolveStored]); the
     * emission/acceptance paths use it to fail loud instead of silently
     * serving the default for a stage the caller believes it selected.
     */
    fun requireForFlavor(flavorId: String, stageId: String): TimeframeStage =
        byId(flavorId, stageId) ?: throw IllegalArgumentException(
            "unknown $flavorId timeframe stage \"$stageId\" — registered stages: " +
                stagesFor(flavorId).joinToString { it.id },
        )

    // -- Gating model (design §2/§3) --------------------------------------

    /**
     * The stage-marker predicate (design §2): marker content boundary
     * [boundaryStageId] is active exactly when the world's stage is at or
     * after that boundary on the same flavor's timeline. Backward/forward
     * moves are pure flag-set decisions over this predicate.
     */
    fun isBoundaryActiveAt(stage: TimeframeStage, boundaryStageId: String): Boolean {
        val boundary = requireForFlavor(stage.flavorId, boundaryStageId)
        return stage.order >= boundary.order
    }

    /**
     * The event-activation block for one stage. v1 honesty: every stage
     * carries the empty, un-measured block (see [TimeframeStage.EventGating]);
     * the S4 pin's measured sets land here as the per-stage data.
     */
    fun eventGating(stage: TimeframeStage): TimeframeStage.EventGating {
        requireForFlavor(stage.flavorId, stage.id)
        return TimeframeStage.EventGating(
            openEventIds = emptySet(),
            closeEventIds = emptySet(),
            measured = false,
        )
    }

    /**
     * The game_event active-window verdict for one event at one stage.
     * [seededActive] is the seed's own truth (the final-patch window state):
     * the DEFAULT stage passes it through byte-for-byte, so existing worlds
     * are unaffected. A non-final stage can only close an event the seed has
     * open (close-list) or open one the seed has closed (open-list) — never
     * invent either direction. With the empty v1 blocks every stage equals
     * the seed.
     */
    fun isEventActiveAt(stage: TimeframeStage, eventId: Int, seededActive: Boolean): Boolean {
        if (stage.isFinal) return seededActive
        val gating = eventGating(stage)
        return when {
            seededActive -> eventId !in gating.closeEventIds
            else -> eventId in gating.openEventIds
        }
    }

    // -- Settings-layer resolution (design §4.1) ---------------------------

    /**
     * The degrade-per-field read of a persisted stage id (the desktop twin's
     * snapshot decode posture): "" stays "" (the default), a stage registered
     * for the QUALIFIED lane survives, and anything else — a stale stage of a
     * flavor that is not qualified, a typo, a retired id — degrades to ""
     * instead of silently pinning a world the lane cannot run.
     */
    fun resolveStored(stored: String): String =
        resolveStoredIn(stored, ClientFlavors.qualified().map { it.flavorId })

    /** The pure form of [resolveStored] over an explicit qualified set (tests). */
    fun resolveStoredIn(stored: String, qualifiedFlavorIds: Collection<String>): String {
        val stage = ALL.firstOrNull { it.id == stored } ?: return ""
        val qualified = stage.flavorId in qualifiedFlavorIds
        return if (qualified) stored else ""
    }

    /**
     * The conf value (design §4.2: "<stage-or-default>"), resolved for the
     * flavor of the world actually booting: the stored stage survives only
     * when it belongs to the ACTIVE flavor; anything else — unset, degraded,
     * or a stored stage of a DIFFERENT (even qualified) flavor — degrades to
     * the ACTIVE flavor's final patch. The conf always names a CONCRETE
     * stage of the lane the world actually runs, so the native consumer
     * never parses an empty value and never reads a foreign lane's stage
     * (R5-2: the old flavor-blind form hard-fell to the vanilla default
     * the moment a second flavor qualified).
     */
    fun confValue(stored: String): String =
        confValueFor(stored, ActiveFlavor.resolve().flavorId)

    /** The pure form of [confValue] over an explicit active flavor (tests):
     *  the stored stage when it is the ACTIVE flavor's own, else that
     *  flavor's final-patch default. */
    fun confValueFor(stored: String, activeFlavorId: String): String {
        val stage = ALL.firstOrNull { it.id == stored }
        if (stage != null && stage.flavorId == activeFlavorId) return stored
        return defaultStageId(activeFlavorId)
    }

    // -- Era-policy linkage (design §5) ------------------------------------

    /**
     * The GROUNDED baseline: the native 1.12.1 verdict lists, pinned verbatim
     * from native/patches/playerbots/PlayerbotLlmTruthCore.h
     * (EraAlwaysBanTerms / EraSensePhrases). `vanilla-1.12.1` is this list,
     * unchanged — the design's byte-equivalence requirement. tools/
     * check_timeframes.py re-proves the three-way sync (header ↔ manifest ↔
     * this registry).
     */
    val VANILLA_FINAL_ERA_BASELINE: TimeframeStage.EraTermClass = TimeframeStage.EraTermClass(
        alwaysBanTerms = listOf("shattrath", "acherus"),
        sensePhrases = listOf(
            TimeframeStage.EraSensePhrase("flying mount", false),
            TimeframeStage.EraSensePhrase("flying mounts", false),
            TimeframeStage.EraSensePhrase("portal is open", true),
            TimeframeStage.EraSensePhrase("portals are open", true),
            TimeframeStage.EraSensePhrase("portal has opened", true),
            TimeframeStage.EraSensePhrase("portals have opened", true),
            TimeframeStage.EraSensePhrase("gilneas opened", true),
            TimeframeStage.EraSensePhrase("gilneas is open", true),
            TimeframeStage.EraSensePhrase("gilneas has opened", true),
            TimeframeStage.EraSensePhrase("playable worgen", false),
            TimeframeStage.EraSensePhrase("dalaran floats", false),
            TimeframeStage.EraSensePhrase("sewers of dalaran", false),
            TimeframeStage.EraSensePhrase("dalaran's sewers", false),
            TimeframeStage.EraSensePhrase("ebon blade", false),
            TimeframeStage.EraSensePhrase("wrath of the lich king", false),
            TimeframeStage.EraSensePhrase("mists of pandaria", false),
            TimeframeStage.EraSensePhrase("warlords of draenor", false),
            TimeframeStage.EraSensePhrase("zereth mortis", false),
            TimeframeStage.EraSensePhrase("korthia", false),
            TimeframeStage.EraSensePhrase("revendreth", false),
            TimeframeStage.EraSensePhrase("maldraxxus", false),
            TimeframeStage.EraSensePhrase("ardenweald", false),
            TimeframeStage.EraSensePhrase("nazjatar", false),
            TimeframeStage.EraSensePhrase("hellfire peninsula", false),
            TimeframeStage.EraSensePhrase("zangarmarsh", false),
            TimeframeStage.EraSensePhrase("terokkar forest", false),
            TimeframeStage.EraSensePhrase("blade's edge", false),
            TimeframeStage.EraSensePhrase("netherstorm", false),
            TimeframeStage.EraSensePhrase("shadowmoon valley", false),
        ),
    )

    /**
     * The era term classes the world resolves for one stage (design §5: the
     * chat era-policy follows the chosen patch, not just the flavor — the
     * same stage id the conf carries drives the class set).
     *
     * STAGED REMAINDER (stated here, not hidden): the only GROUNDED policy in
     * the repo is the vanilla 1.12.1 baseline, so every registered stage
     * resolves to it today. Intra-flavor deltas (an earlier vanilla stage
     * banning later-vanilla senses) and the TBC / WotLK legitimization moves
     * are curated at each DB pin (design §5: "illustrative here, not asserted
     * as data") and land as per-stage overrides here + in schemas/timeframes
     * — the resolution point is this one function, so the curation is a data
     * change, not a code sweep.
     */
    fun eraClassFor(stage: TimeframeStage): TimeframeStage.EraTermClass {
        requireForFlavor(stage.flavorId, stage.id)
        return VANILLA_FINAL_ERA_BASELINE
    }
}
