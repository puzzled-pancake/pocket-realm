package com.pocketrealm

/**
 * Desktop twin of the AGP-generated Android BuildConfig fields consumed by
 * the shared server contract. On Android these are baked from the reviewed
 * realm-runtime lane lockfile at build time; here they are pinned to
 * schemas/realm-runtime-lockfile-mariadb-win.json — the MARIADB lane the
 * desktop image actually ships (NativeRuntimeFence pins the same lockfile
 * Over the staged pocket_*.dll at package time). The tbc/wotlk
 * lanes pin their OWN suffixed siblings
 * (realm-runtime-lockfile-mariadb-win-tbc.json / -wotlk.json). The SQLITE
 * win lane stays a dev/compat selection whose lockfile is pinned by its
 * own tests/test_win_lockfile.py rows.
 *
 * W4 flip (retirement): one id PER QUALIFIED LANE. The vanilla id keeps
 * its historical name ([NATIVE_RUNTIME_BUILD_ID] — the tests'
 * telltale-lane constant); tbc/wotlk add their own lane lockfile ids
 * ([NATIVE_RUNTIME_BUILD_ID_TBC] / [_WOTLK], pinned by the same test's
 * per-lane rows). The per-flavor DLL roots ride the Gradle rpHost lane
 * routing (-PrpFlavor), not these constants.
 *
 * tests/test_win_lockfile.py recomputes every id from its lane lockfile
 * with the SAME formula the Android build uses (prefix + backend + commit
 * prefixes + the world DLL digest prefix) and fails on drift — an unpinned
 * id must fail the shared staleness telltale rather than silently pass.
 */
object BuildConfig {
    const val NATIVE_RUNTIME_BUILD_ID: String =
        "win-x86_64-mariadb-cmangos-ce83805d-playerbots-7e2cd2fb-6851c2394459"

 /** Flip: the tbc mariadb win lane (realm-runtime-lockfile-mariadb-win-tbc.json). */
    const val NATIVE_RUNTIME_BUILD_ID_TBC: String =
        "win-x86_64-mariadb-cmangos-ffbf9767-playerbots-7e2cd2fb-18ae206fed52"

 /** Flip: the wotlk mariadb win lane (realm-runtime-lockfile-mariadb-win-wotlk.json). */
    const val NATIVE_RUNTIME_BUILD_ID_WOTLK: String =
        "win-x86_64-mariadb-cmangos-c3a8d86f-playerbots-7e2cd2fb-56a0884d3a31"

    const val NATIVE_CMANGOS_COMMIT: String =
        "ce83805d48f9c98b2af617096be5347acb8a1f17"

    const val NATIVE_PLAYERBOTS_COMMIT: String =
        "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"

    /** The lane's telltale id (support bundles, the settings line): the
     * ACTIVE lane's own build id, never a vanilla constant on a tbc/wotlk
     * boot. Unknown lanes fall back to the vanilla id (the shipped lane). */
    fun nativeRuntimeBuildIdFor(flavorId: String): String = when (flavorId) {
        "tbc" -> NATIVE_RUNTIME_BUILD_ID_TBC
        "wotlk" -> NATIVE_RUNTIME_BUILD_ID_WOTLK
        else -> NATIVE_RUNTIME_BUILD_ID
    }

    /** The ACTIVE lane's own cmangos source commit (status JSON, support
     * bundles) — the per-lane ids above are recomputed from these same
     * commits by tests/test_win_lockfile.py, so a drift fails the pin. */
    fun nativeCmangosCommitFor(flavorId: String): String = when (flavorId) {
        "tbc" -> NATIVE_CMANGOS_COMMIT_TBC
        "wotlk" -> NATIVE_CMANGOS_COMMIT_WOTLK
        else -> NATIVE_CMANGOS_COMMIT
    }

    /** tbc lane core (realm-runtime-lockfile-mariadb-win-tbc.json). */
    const val NATIVE_CMANGOS_COMMIT_TBC: String =
        "ffbf97675dc31f845587778f5bb993b232f1b6a2"

    /** wotlk lane core (realm-runtime-lockfile-mariadb-win-wotlk.json). */
    const val NATIVE_CMANGOS_COMMIT_WOTLK: String =
        "c3a8d86fe4e61c3373eff424eb12cd7e7e8282b5"
}
