package com.pocketrealm.client

/**
 * The DESKTOP TWIN of the android `client/ClientRuntime.kt` contract
 * object: the android file carries Android-only surface and is
 * deliberately NOT in the desktop shared-sources manifest, so the
 * desktop carries this same-package twin with the pieces the desktop
 * UI consumes. Keep the copied members byte-identical to the android
 * originals — the desktop pin test reads BOTH sources and fails on
 * drift (a wording drift would render different copy per platform).
 */
object ClientRuntimeContract {
    /** The per-game-version vanilla-tweaks copy: null when the tweaks are
     * available, the "not available for this game version" line otherwise
     * (the tweak set is validated against the 1.12.1 client only). */
    fun flavorTweaksCopy(flavor: com.pocketrealm.client.ClientFlavor): String? =
        if (flavor.vanillaTweaksEnabled) null else
            "Vanilla tweaks are not available for ${flavor.expansion}. " +
                "They only work with the vanilla 1.12.1 client."
}
