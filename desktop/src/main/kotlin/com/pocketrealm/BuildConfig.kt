package com.pocketrealm

/**
 * Desktop twin of the AGP-generated Android BuildConfig fields consumed by
 * the shared server contract. On Android these are baked from the reviewed
 * realm-runtime lane lockfile at build time; here they are pinned to
 * schemas/realm-runtime-lockfile-mariadb-win.json — the MARIADB lane the
 * desktop image actually ships (NativeRuntimeFence pins the same lockfile
 * over the staged pocket_*.dll at package time). The SQLITE win lane stays
 * a dev/compat selection whose lockfile is pinned by its own
 * tests/test_win_lockfile.py rows.
 *
 * That test also recomputes this id from the mariadb-win lockfile with the
 * SAME formula the Android build uses (prefix + backend + commit prefixes
 * + the world DLL digest prefix) and fails on drift — an unpinned id must
 * fail the shared staleness telltale rather than silently pass.
 */
object BuildConfig {
    const val NATIVE_RUNTIME_BUILD_ID: String =
        "win-x86_64-mariadb-cmangos-ce83805d-playerbots-7e2cd2fb-784c94131fbf"

    const val NATIVE_CMANGOS_COMMIT: String =
        "ce83805d48f9c98b2af617096be5347acb8a1f17"

    const val NATIVE_PLAYERBOTS_COMMIT: String =
        "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"
}
