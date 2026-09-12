package com.pocketrealm

/**
 * Desktop twin of the AGP-generated Android BuildConfig fields consumed by
 * the shared server contract. On Android these are baked from the reviewed
 * realm-runtime lane lockfile at build time; here they are pinned to
 * schemas/realm-runtime-lockfile-sqlite-win.json (written by
 * `python tools/build_win_realm_runtime.py --write-lockfile` after a lane
 * rebuild).
 *
 * tests/test_win_lockfile.py recomputes the id from the lockfile with the
 * SAME formula the Android build uses (prefix + backend + commit prefixes
 * + the world DLL digest prefix) and fails on drift — an unpinned id must
 * fail the shared staleness telltale rather than silently pass.
 */
object BuildConfig {
    const val NATIVE_RUNTIME_BUILD_ID: String =
        "win-x86_64-sqlite-cmangos-ce83805d-playerbots-7e2cd2fb-633d89dfb70d"

    const val NATIVE_CMANGOS_COMMIT: String =
        "ce83805d48f9c98b2af617096be5347acb8a1f17"

    const val NATIVE_PLAYERBOTS_COMMIT: String =
        "7e2cd2fbbbb4eaa3e1696ee80e9bf8e170b6256d"
}
