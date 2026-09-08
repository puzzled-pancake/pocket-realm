package com.pocketrealm

/**
 * Desktop twin of the AGP-generated Android BuildConfig fields consumed by
 * the shared server contract. On Android these are baked from the reviewed
 * realm-runtime lane lockfile at build time; the Windows lane does not have a
 * lockfile yet (Phase 2 of the Windows port creates
 * schemas/realm-runtime-lockfile-sqlite-win.json and wires real values in).
 *
 * The values are deliberately, loudly unpinned: ServerRuntimeContract's
 * staleness telltale compares this id against the derivable-current one, and
 * an unpinned id must fail that check rather than silently pass.
 */
object BuildConfig {
    const val NATIVE_RUNTIME_BUILD_ID: String = "windows-lane-unpinned"

    const val NATIVE_CMANGOS_COMMIT: String = "unpinned"

    const val NATIVE_PLAYERBOTS_COMMIT: String = "unpinned"
}
