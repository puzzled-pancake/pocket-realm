package com.pocketrealm.server

internal object WorldNative {
    init { System.loadLibrary("pocket_world_runtime") }
    external fun startNative(configPath: String): Int
    external fun createAccountNative(username: String, password: String, timeoutMs: Long): Int
    external fun setAccountGmLevelNative(username: String, level: Int, timeoutMs: Long): Int
    external fun accountInfoNative(username: String): LongArray
    external fun realmInfoNative(): String
    external fun saveNative(timeoutMs: Long): Int
    external fun stopNative(timeoutMs: Long): Int
    external fun statusNative(): LongArray
    external fun onlinePlayersNative(): Int
    external fun detailNative(): String
}
