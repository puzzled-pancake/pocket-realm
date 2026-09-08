package com.pocketrealm.desktop

/**
 * Hand-rolled navigation for the desktop shell. androidx.navigation has no
 * desktop artifact, and the desktop app has a flat, small destination set,
 * so a sealed-type route plus a single mutable holder is the whole router.
 */
sealed class Route(val label: String) {
    data object Home : Route("Home")

    data object Bots : Route("Bots")

    data object Llm : Route("LLM")

    data object Settings : Route("Settings")

    data object Diagnostics : Route("Diagnostics")
}

val DESKTOP_ROUTES: List<Route> = listOf(
    Route.Home,
    Route.Bots,
    Route.Llm,
    Route.Settings,
    Route.Diagnostics,
)

/** Router state; navigation is synchronous and single-threaded (UI thread). */
class DesktopRouter(initial: Route = Route.Home) {
    var current: Route = initial
        private set

    fun navigate(route: Route) {
        current = route
    }
}
