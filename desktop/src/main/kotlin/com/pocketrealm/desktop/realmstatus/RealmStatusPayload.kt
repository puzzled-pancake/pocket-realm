package com.pocketrealm.desktop.realmstatus

import org.json.JSONObject

/**
 * Realm-status browser (design realm-status-browser.md): the
 * `GET /api/status.json` payload contract, schema 3.
 *
 * The builder is a PURE function of one captured input bundle — the
 * caller snapshots the supervisor state ONCE at request entry (the
 * one-snapshot rule) and hands the values in, so a torn render can never
 * mix pre-stop and post-stop facts by construction.
 *
 * The fail-closed rules the contract pins (each asserted by the suite):
 *  - PUBLIC trim: `supervisor.phase/mode/clean`, `world.generation`,
 *    `players.botTarget`, `uptime.startedAtWallMs` are null and the
 * timestamps go minute-coarse (the "null in PUBLIC" set);
 *  - degraded status: `supervisor.realmOnline` is null (an unreadable
 * upstream is not evidence of offline), uptime 0, and the
 * players block fail-closes to nulls — never a 0-split ;
 *  - world down: the zeros are STRUCTURAL (`players.source` =
 * "world-down") — no world means no players by construction;
 *  - the split publishes non-null numbers only when `players.source` is
 *    "native" (a clean bot-status lane AND a passing cross-check);
 * `players.botsLabeled` is always true (convergence Tier 3);
 * the advertised address follows the TWO-case rule : the FRIENDS
 *    tunnel lane only, null in plain-LAN FRIENDS and always null in PUBLIC.
 */
object RealmStatusPayload {

    /** The round-3 contract version. */
    const val SCHEMA = 3

    /** The join hint copied verbatim into every PUBLIC payload. */
    const val PUBLIC_JOIN_HINT: String =
        "This page is read-only. Joining is by friend invitation from the host only."

    /** The FRIENDS join pointer — address-block-only family, address-free
     * in the payload ( the LAN address appears only in the page's own
     * join section, never here). */
    const val FRIENDS_JOIN_HINT: String =
        "Joining is by friend invitation from the host: ask the host for an " +
            "invite — the app's invite block carries everything except your login."

    /** The MOTD clamp: control characters stripped, hard cap at 280
     * characters. Runs at the settings WRITE path and re-runs here (and at
     * the render) as defense in depth — the payload never trusts a stored
     * value it did not clamp itself. */
    const val MOTD_MAX_LENGTH = 280

    fun clampDescription(raw: String): String {
        val cleaned = raw.filterNot { it.isISOControl() }
        return if (cleaned.length <= MOTD_MAX_LENGTH) cleaned else cleaned.take(MOTD_MAX_LENGTH)
    }

    /** `players.source`: the fail-closed lane label. */
    enum class PlayerSource(val wire: String) {
        NATIVE("native"),
        DEGRADED("degraded"),
        CROSS_CHECK_FAILED("cross-check-failed"),
        WORLD_DOWN("world-down"),
    }

    /** `supervisor.statusSource`. */
    enum class StatusSource(val wire: String) { LIVE("live"), DEGRADED("degraded") }

    /** `public.tunnelState`. */
    enum class TunnelState(val wire: String) {
        LIVE("live"),
        STARTING("starting"),
        DOWN("down"),
        BINARY_MISSING("binary-missing"),
    }

    /** `join.kind` — keyed on the ADVERTISEMENT axis only. */
    enum class JoinKind(val wire: String) {
        TUNNEL_INVITE("tunnel-invite"),
        LAN_INVITE("lan-invite"),
        NONE("none"),
    }

    /** The world-data counts block ( `PreparedDataStore`'s manifest counts). */
    data class WorldCounts(
        val dbc: Int,
        val maps: Int,
        val vmapTrees: Int,
        val mmapMaps: Int,
        val mmapTiles: Int,
    )

    /** The rates block. */
    data class Rates(val xpKill: Double?, val xpQuest: Double?, val xpHonor: Double?, val source: String) {
        companion object {
            /** The app writes no Rate.* keys → engine defaults. */
            fun engineDefault() =
                Rates(xpKill = 1.0, xpQuest = 1.0, xpHonor = 1.0, source = "engine-default")

            /** The world is down: nulls + the honest "not-running" label. */
            fun notRunning() = Rates(xpKill = null, xpQuest = null, xpHonor = null, source = "not-running")
        }
    }

    /** The players block's inputs. [humansOnline]/[botsOnline]/
     * [totalOnline] are null unless [source] is NATIVE; [botTarget] is the
     * conf-resolved launch target (null in PUBLIC); [characterNames] the
     * consent-gated roster (empty = absent from the payload). */
    data class Players(
        val source: PlayerSource,
        val crossCheckOk: Boolean,
        val humansOnline: Int? = null,
        val botsOnline: Int? = null,
        val totalOnline: Int? = null,
        val botTarget: Int? = null,
        val characterNames: List<String> = emptyList(),
    )

    /** Everything one render needs, captured ONCE (one-snapshot rule). */
    data class Inputs(
        val visibility: RealmStatusVisibility,
        val generatedAtWallMs: Long,
        val realmName: String?,
        val flavor: String,
        val timeframeStageId: String,
        val timeframeTitle: String,
        val timeframeIsFinal: Boolean,
        val description: String,
        val worldRunning: Boolean,
        val worldStartedAtWallMs: Long?,
        val recordSeconds: Long,
        val rates: Rates,
        val players: Players,
        val worldPrepared: Boolean,
        val worldGeneration: String?,
        val worldCounts: WorldCounts?,
        val statusSource: StatusSource,
        val phase: String?,
        val mode: String?,
        val clean: Boolean?,
        val advertisedAddress: String?,
        val joinKind: JoinKind,
        val publicUrl: String?,
        val tunnelState: TunnelState,
        val characterNamesInPublic: Boolean,
    )

    /** Builds the schema-3 payload JSON from one captured input bundle. */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "MaxLineLength")
    // one reviewed contract literal over the field table — the exact
    // ServerRuntimeFiles.worldConfig reviewed-literal precedent (golden-pinned)
    fun build(inputs: Inputs): JSONObject {
        val publicMode = inputs.visibility == RealmStatusVisibility.PUBLIC
        val degraded = inputs.statusSource == StatusSource.DEGRADED
        // uptime rides the world component's start; minute-coarse in
        // PUBLIC; 0 when down or degraded (the never-a-stale-snapshot).
        val uptimeSeconds = when {
            degraded || !inputs.worldRunning || inputs.worldStartedAtWallMs == null -> 0L
            else -> ((inputs.generatedAtWallMs - inputs.worldStartedAtWallMs) / MS_PER_SECOND)
                .coerceAtLeast(0L)
        }
        val coarseUptime =
            if (publicMode) uptimeSeconds - (uptimeSeconds % SECONDS_PER_MINUTE) else uptimeSeconds

        // The liveness signal : null when degraded — no false offline
        // claim of either polarity; down is a REAL offline (phase carries it).
        val realmOnline: Boolean? = when {
            degraded -> null
            else -> inputs.worldRunning
        }

        return JSONObject()
            .put("schema", SCHEMA)
            .put("visibility", inputs.visibility.wireName())
            .put(
                "generatedAtWallMs",
                if (publicMode) {
                    minuteCoarse(inputs.generatedAtWallMs)
                } else {
                    inputs.generatedAtWallMs
                },
            )
            .put(
                "realm",
                JSONObject()
                    .put("name", inputs.realmName?.takeIf { it.isNotBlank() } ?: DEFAULT_REALM_NAME)
                    .put("flavor", inputs.flavor)
                    .put("timeframeStageId", inputs.timeframeStageId)
                    .put("timeframeTitle", inputs.timeframeTitle)
                    .put("timeframeIsFinal", inputs.timeframeIsFinal)
                    .put("description", clampDescription(inputs.description)),
            )
            .put(
                "uptime",
                JSONObject()
                    .put("seconds", coarseUptime)
                    .put(
                        "startedAtWallMs",
                        if (publicMode) JSONObject.NULL else (inputs.worldStartedAtWallMs ?: JSONObject.NULL),
                    )
                    .put("recordSeconds", inputs.recordSeconds),
            )
            .put(
                "rates",
                JSONObject()
                    .put("xpKill", inputs.rates.xpKill ?: JSONObject.NULL)
                    .put("xpQuest", inputs.rates.xpQuest ?: JSONObject.NULL)
                    .put("xpHonor", inputs.rates.xpHonor ?: JSONObject.NULL)
                    .put("source", inputs.rates.source),
            )
            .put("players", playersJson(inputs.players, publicMode))
            .put(
                "world",
                JSONObject()
                    .put("prepared", inputs.worldPrepared)
                    .put("generation", if (publicMode) JSONObject.NULL else (inputs.worldGeneration ?: JSONObject.NULL))
                    .put("counts", countsJson(inputs.worldCounts)),
            )
            .put(
                "supervisor",
                JSONObject()
                    .put("phase", if (publicMode) JSONObject.NULL else (inputs.phase ?: JSONObject.NULL))
                    .put("mode", if (publicMode) JSONObject.NULL else (inputs.mode ?: JSONObject.NULL))
                    .put("clean", if (publicMode) JSONObject.NULL else (inputs.clean ?: JSONObject.NULL))
                    .put("realmOnline", realmOnline ?: JSONObject.NULL)
                    .put("statusSource", inputs.statusSource.wire)
                    // 's two-case rule: the FRIENDS tunnel lane only; the
                    // plain-LAN FRIENDS lane and PUBLIC are null, always.
                    .put(
                        "advertisedAddress",
                        advertisedOrNull(inputs, publicMode) ?: JSONObject.NULL,
                    ),
            )
            .put(
                "join",
                JSONObject()
                    .put("kind", inputs.joinKind.wire)
                    .put(
                        "hint",
                        if (inputs.visibility == RealmStatusVisibility.PUBLIC) PUBLIC_JOIN_HINT else FRIENDS_JOIN_HINT,
                    ),
            )
            .put(
                "public",
                JSONObject()
                    // the URL rides a LIVE tunnel only — never a stale one.
                    .put(
                        "url",
                        if (inputs.tunnelState == TunnelState.LIVE) {
                            inputs.publicUrl ?: JSONObject.NULL
                        } else {
                            JSONObject.NULL
                        },
                    )
                    .put("tunnelState", inputs.tunnelState.wire)
                    .put("readOnly", true),
            )
            .put("privacy", JSONObject().put("characterNamesInPublic", inputs.characterNamesInPublic))
    }

    /** The trycloudflare share-URL shape gate. The SAME rule gates
     * the payload, the page's copy button, and the tunnel URL parse — a
     * parse deviation renders the failure block, never the attribute. */
    val TRYCLOUDFLARE_SHAPE: Regex = Regex("^https://[a-z0-9-]+\\.trycloudflare\\.com$")

    fun isShapeValidPublicUrl(raw: String?): Boolean =
        raw != null && TRYCLOUDFLARE_SHAPE.matches(raw)

    private fun advertisedOrNull(inputs: Inputs, publicMode: Boolean): String? =
        when {
            // 's two-case rule: PUBLIC is null always; only the FRIENDS
            // tunnel lane carries the frozen advertisement.
            publicMode || inputs.visibility != RealmStatusVisibility.FRIENDS -> null
            else -> inputs.advertisedAddress?.takeIf { it.isNotBlank() }
        }

    /** The players block: numbers only for a clean NATIVE split
     * or the STRUCTURAL world-down zeros (no world means no players by
     * construction — labels those `world-down`, never a fake "native");
     * every degraded/failed lane publishes NULLS, never a 0-split. Bots
     * are always labeled; the roster rides only when consented + provided. */
    @Suppress("CyclomaticComplexMethod")
    // the publish ladder IS the contract: native split | structural
    // world-down zeros | nulls for every degraded/failed lane
    private fun playersJson(players: Players, publicMode: Boolean): JSONObject {
        val splitLive = players.source == PlayerSource.NATIVE &&
            players.humansOnline != null && players.botsOnline != null &&
            players.totalOnline != null
        val structuralZeros = players.source == PlayerSource.WORLD_DOWN &&
            players.humansOnline != null && players.botsOnline != null &&
            players.totalOnline != null
        val publish = splitLive || structuralZeros
        return JSONObject()
            .put("humansOnline", if (publish) players.humansOnline else JSONObject.NULL)
            .put("botsOnline", if (publish) players.botsOnline else JSONObject.NULL)
            .put("totalOnline", if (publish) players.totalOnline else JSONObject.NULL)
            .put("source", players.source.wire)
            .put("splitAvailable", splitLive)
            .put("crossCheckOk", players.crossCheckOk)
            .put("botsLabeled", true)
            .put("botTarget", if (publicMode) JSONObject.NULL else (players.botTarget ?: JSONObject.NULL))
            // /: the roster is ABSENT unless the consent toggle rides
            // — no key at all in the default payload, never a null member.
            .also { payload ->
                if (players.characterNames.isNotEmpty()) {
                    payload.put("characterNames", org.json.JSONArray(players.characterNames))
                }
            }
    }

    private fun countsJson(counts: WorldCounts?): Any {
        if (counts == null) return JSONObject.NULL
        return JSONObject()
            .put("dbc", counts.dbc)
            .put("maps", counts.maps)
            .put("vmapTrees", counts.vmapTrees)
            .put("mmapMaps", counts.mmapMaps)
            .put("mmapTiles", counts.mmapTiles)
    }

    /** PUBLIC timestamps are minute-coarse. */
    fun minuteCoarse(wallMs: Long): Long = wallMs - (wallMs % MS_PER_MINUTE)

    private fun RealmStatusVisibility.wireName(): String = name.lowercase()

    private const val DEFAULT_REALM_NAME = "my realm"

    private const val MS_PER_SECOND = 1_000L
    private const val MS_PER_MINUTE = 60_000L
    private const val SECONDS_PER_MINUTE = 60L
}
