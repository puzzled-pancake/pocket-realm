package com.pocketrealm.client

/**
 * The DESKTOP TWIN of the android `client/ClientRuntime.kt` contract
 * object (R6-w3-1, code-red round 6): the android file carries
 * Android-only surface and is deliberately NOT in the desktop
 * shared-sources manifest, so the JVM lane carries this same-package
 * twin with the pieces the desktop UI consumes. Keep the copied
 * members byte-identical to the android originals — the desktop pin
 * test reads BOTH sources and fails on drift (the twin-of-a-contract
 * rule; a wording drift would render different honest copy per lane).
 */
object ClientRuntimeContract {
    /** The per-flavor vanilla-tweaks copy: null when the tweaks are
     * available, the honest "not available for this flavor" line otherwise
     * (the tweak set is validated against the 1.12.1 client only). */
    fun flavorTweaksCopy(flavor: com.pocketrealm.client.ClientFlavor): String? =
        if (flavor.vanillaTweaksEnabled) null else
            "Vanilla tweaks are not available for ${flavor.expansion} — " +
                "the tweak set is validated against the vanilla 1.12.1 client only."
}
