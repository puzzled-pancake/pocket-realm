package com.pocketrealm.server

internal object RealmNative {
    init { System.loadLibrary("pocket_realmd_runtime") }
    external fun startNative(configPath: String): Int
    external fun stopNative(timeoutMs: Long): Int
    external fun statusNative(): LongArray
    external fun detailNative(): String
}
