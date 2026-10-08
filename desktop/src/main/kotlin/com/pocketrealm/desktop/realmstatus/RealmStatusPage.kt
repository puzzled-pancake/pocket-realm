package com.pocketrealm.desktop.realmstatus

import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.Inputs
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.PlayerSource
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.StatusSource
import com.pocketrealm.desktop.realmstatus.RealmStatusPayload.TunnelState
import org.json.JSONObject

/**
 * Realm-status browser (design realm-status-browser.md §8): the static,
 * server-rendered showcase page. No JS, mobile-tolerable, every dynamic
 * value HTML-escaped, and the public-URL interpolation SHAPE-GATED (§5.5):
 * a URL that fails [RealmStatusPayload.isShapeValidPublicUrl] renders the
 * failure block and never reaches a `data-copy` attribute.
 *
 * The one-snapshot rule (§4) holds here too: the page renders from the
 * exact payload JSON the request captured, never a second state read.
 */
object RealmStatusPage {

    /** The mandatory bots clause (§8.4; convergence §5 Tier 3): the
     * parenthesized "(N of them are bots)" copy is required verbatim. */

    /** Renders the full page for one captured payload. [hostView] enables
     * the HOST-side blocks (§8.7: the public-link/copy/posture card) that
     * only the host's own loopback view renders. [pageLanAddress] is the
     * FRIENDS plain-LAN lane's PAGE-ONLY join line (§8.5) — sourced OUTSIDE
     * the payload; it renders only where the LAN can already reach the
     * page, and never in PUBLIC (the caller passes null there). */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    // the page body is one reviewed server-rendered literal (the §8 blocks
    // in order); every dynamic value funnels through the single escape sink
    fun render(payload: JSONObject, hostView: Boolean, pageLanAddress: String?): String {
        val realm = payload.optJSONObject("realm") ?: JSONObject()
        val players = payload.optJSONObject("players") ?: JSONObject()
        val supervisor = payload.optJSONObject("supervisor") ?: JSONObject()
        val uptime = payload.optJSONObject("uptime") ?: JSONObject()
        val rates = payload.optJSONObject("rates") ?: JSONObject()
        val world = payload.optJSONObject("world") ?: JSONObject()
        val join = payload.optJSONObject("join") ?: JSONObject()
        val public = payload.optJSONObject("public") ?: JSONObject()

        val realmOnline = supervisor.optBoolean("realmOnline", false)
        val degradedStatus =
            supervisor.optString("statusSource") == StatusSource.DEGRADED.wire
        val down = !degradedStatus && !realmOnline
        val tunnelState = TunnelState.entries.firstOrNull {
            it.wire == public.optString("tunnelState")
        } ?: TunnelState.DOWN

        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<meta name=\"referrer\" content=\"no-referrer\">")
            append("<title>")
            append(escape(realm.optString("name", "my realm")))
            append(" — realm status</title>")
            append("<style>body{font-family:sans-serif;margin:2rem auto;max-width:42rem;padding:0 1rem}")
            append("h1{font-size:1.4rem}.badge{display:inline-block;border:1px solid #999;border-radius:")
            append("4px;padding:0 .4rem;margin-left:.4rem;font-size:.8rem}.down{color:#a33}")
            append(".muted{color:#666}.card{border:1px solid #ddd;border-radius:6px;padding:1rem;")
            append("margin:1rem 0}</style></head><body>")
            // §8.1 header: name + flavor badge + start-frozen timeframe badge.
            append("<h1>")
            append(escape(realm.optString("name", "my realm")))
            append("<span class=\"badge\">").append(escape(realm.optString("flavor", "")))
            append("</span>")
            val stageTitle = realm.optString("timeframeTitle", "")
            if (stageTitle.isNotEmpty()) {
                append("<span class=\"badge\">").append(escape(stageTitle))
                if (!realm.optBoolean("timeframeIsFinal", true)) append(" (timeframe)")
                append("</span>")
            }
            append("</h1>")
            // §8.2 the host-set MOTD, escaped at render.
            val description = realm.optString("description", "")
            if (description.isNotEmpty()) {
                append("<p>").append(escape(description)).append("</p>")
            }
            // §8.3 the status block.
            append("<div class=\"card\"><p>")
            when {
                degradedStatus -> append("Status unavailable.")
                down -> append("<span class=\"down\">The realm is offline right now.</span>")
                else -> {
                    append("The realm is online.")
                    append(" <span class=\"muted\">")
                    append(escape(uptimeLine(uptime)))
                    append("</span>")
                }
            }
            append("</p>")
            append("<p class=\"muted\">").append(escape(ratesLine(rates))).append("</p>")
            append("<p class=\"muted\">").append(escape(worldLine(world))).append("</p></div>")
            // §8.4 the players block.
            append("<div class=\"card\">").append(playersLine(players)).append("</div>")
            // §8.5 the join section.
            append("<div class=\"card\"><h2>Joining</h2>")
            if (pageLanAddress != null) {
                // The ONE surface where the LAN address may appear (§7): the
                // FRIENDS plain-LAN page, LAN-reachable by construction.
                append("<p><code>set realmlist ").append(escape(pageLanAddress))
                append("</code> <span class=\"muted\">(on the host's local network)</span></p>")
            } else {
                append("<p>").append(escape(join.optString("hint", ""))).append("</p>")
            }
            append("</div>")
            // §8.6 the graceful-down lifecycle copy: the public link REMAINS
            // LIVE through realm stops while the visibility session is on.
            if (down) {
                append(
                    "<div class=\"card\"><p>The public link stays up while the host keeps " +
                        "this page on — realm stops do not take it down. A page restart " +
                        "(the app reopening) always needs the host to re-enable it.</p></div>",
                )
            }
            // §8.7 the host's loopback-only public-link block.
            if (hostView) {
                append(hostLinkBlock(public, tunnelState))
            }
            append("</body></html>")
        }
    }

    /** §8.7: shape-gated URL + copy button + posture copy + failure blocks
     * for `tunnelState != "live"` and the cloudflared-missing case. */
    private fun hostLinkBlock(public: JSONObject, tunnelState: TunnelState): String {
        val url = public.optString("url", "")
        val live = tunnelState == TunnelState.LIVE &&
            RealmStatusPayload.isShapeValidPublicUrl(url)
        val block = StringBuilder("<div class=\"card\"><h2>Public link</h2>")
        if (live) {
            block.append("<p><a href=\"").append(escape(url)).append("\">")
                .append(escape(url)).append("</a></p>")
            block.append("<p><button data-copy=\"").append(escape(url))
                .append("\">Copy link</button></p>")
        } else {
            block.append("<p class=\"down\">").append(
                when (tunnelState) {
                    TunnelState.BINARY_MISSING ->
                        "The cloudflared tool was not found, so the public link could not " +
                            "start. Install cloudflared or set its path in Settings, then " +
                            "enable the Public showcase again."
                    TunnelState.STARTING ->
                        "The public tunnel is starting — the link appears here when it is live."
                    else ->
                        "The public tunnel is down right now. The host can retry it from " +
                            "the app's hosting card."
                },
            ).append("</p>")
        }
        block.append(
            "<p class=\"muted\">Anyone with this link can view this page until you turn " +
                "the Public showcase off or the app stops hosting it. It changes each time. " +
                "The link is unguessable, not secret — share it onward freely. Cloudflare " +
                "terminates TLS for this address.</p>",
        )
        block.append(
            "<p class=\"muted\">Moderation posture: kick is unavailable — bans apply at " +
                "the next logon, so a guest online at ban time stays in the world.</p>",
        )
        block.append("</div>")
        return block.toString()
    }

    @Suppress("ReturnCount") // the three §8.4 render branches (degraded / down / live) are the contract
    private fun playersLine(players: JSONObject): String {
        // Degraded lane: the §8.4 copy, never a 0-split (§3.3).
        if (players.optString("source") != PlayerSource.NATIVE.wire) {
            val downStructural = players.optString("source") == PlayerSource.WORLD_DOWN.wire
            if (downStructural) {
                val total = players.optInt("totalOnline", 0)
                return "<p>0 humans online · 0 bots online · $total in world " +
                    "<span class=\"muted\">(the realm is offline)</span></p>"
            }
            return "<p>Player counts unavailable right now (status degraded).</p>"
        }
        val humans = players.optInt("humansOnline", 0)
        val bots = players.optInt("botsOnline", 0)
        val total = players.optInt("totalOnline", humans + bots)
        return "<p>$humans humans online · $bots bots online · $total in world " +
            "<strong>($bots of them are bots)</strong></p>"
    }

    private fun uptimeLine(uptime: JSONObject): String {
        val seconds = uptime.optLong("seconds", 0L)
        val record = uptime.optLong("recordSeconds", 0L)
        return "online ${formatDuration(seconds)} · longest session ${formatDuration(record)}"
    }

    private fun ratesLine(rates: JSONObject): String = "Rates (source: " +
        escape(rates.optString("source", "engine-default")) + "): " +
        "XP kill ${rateValue(rates, "xpKill")}, " +
        "XP quest ${rateValue(rates, "xpQuest")}, " +
        "honor ${rateValue(rates, "xpHonor")}"

    /** The not-running lane's null rates render n/a — never NaN. */
    private fun rateValue(rates: JSONObject, key: String): String =
        if (rates.isNull(key)) "n/a" else rates.optDouble(key, Double.NaN).toString()

    private fun worldLine(world: JSONObject): String = if (world.optBoolean("prepared", false)) {
        val counts = world.optJSONObject("counts")
        val generation = world.optString("generation", "")
        "World data: prepared" +
            (if (generation.isNotEmpty()) " (generation $generation)" else "") +
            (counts?.let { c ->
                " — ${c.optInt("dbc", 0)} DBC, ${c.optInt("maps", 0)} maps, " +
                    "${c.optInt("mmapTiles", 0)} mmaps"
            } ?: "")
    } else {
        "World data: not prepared yet."
    }

    @Suppress("MagicNumber") // the 3600/60 duration units are the format
    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3_600L
        val minutes = (seconds % 3_600L) / 60L
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    /** The single HTML sink: every dynamic value passes through here. */
    fun escape(raw: String): String = buildString {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}
