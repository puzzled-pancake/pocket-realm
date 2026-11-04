package com.pocketrealm.desktop

/**
 * Hand-rolled navigation for the desktop shell. androidx.navigation has no
 * desktop artifact, and the desktop app has a flat, small destination set,
 * so a sealed-type route plus a single mutable holder is the whole router.
 */
sealed class Route(val label: String) {
    data object Home : Route("Home")

    /** DESK-1/97: the client import lane (folder → managed generation). */
    data object Import : Route("Import")

    /** Opened from Home and Settings; not in the sidebar. */
    data object Bots : Route("Bots")

    data object Settings : Route("Settings")

    /** DESK-12/22/41/59/85/86/96: the stock in-game settings editor
     * (graphics/sound/interface/advanced/bindings) over the WTF tree. */
    data object InGameSettings : Route("Game settings")

    /** DESK-4: the gamepad controls editor (scheme picker, per-button map,
     * dead zones and trigger tuning) + the gamepad bridge toggle. */
    data object Controls : Route("Controls")

    /** DESK-10/17/39/54/82: the add-on manager (recommended/my add-ons,
     * browse catalog, GitHub-commit install, updates, rollback). */
    data object Addons : Route("Add-ons")

    /** design-remote-accounts §7: the full host management surface — open
     * invites with copyable codes, pending requests with Approve/Reject,
     * and the friends table (roster, ban/unban, gmlevel). */
    data object Players : Route("Players")

    /** The Multiplayer screen: Find a game / Host / Direct join — the
     * realm browser's surfaces (LAN realms over the shared LanJoinProbe
     * seam, the hosted realm-status page link, the W6-N1 Public realms
     * section via Nostr discovery) live under its tabs. The route object
     * keeps its historical name (Realms); the label is Multiplayer. */
    data object Realms : Route("Multiplayer")

    data object Diagnostics : Route("Diagnostics")
}

val DESKTOP_ROUTES: List<Route> = listOf(
    Route.Home,
    Route.Import,
    Route.Players,
    Route.Realms,
    Route.Settings,
    Route.InGameSettings,
    Route.Controls,
    Route.Addons,
    Route.Diagnostics,
)

/** Router state; navigation is synchronous and single-threaded (UI thread). */
class DesktopRouter(initial: Route = Route.Home) {
    var current: Route = initial
        private set

    /** the tab Home asked for; consumed by the Multiplayer screen once */
    var pendingRealmsTab: String? = null

    fun navigate(route: Route) {
        current = route
    }
}
