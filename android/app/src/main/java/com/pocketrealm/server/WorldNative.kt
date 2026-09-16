package com.pocketrealm.server

internal object WorldNative {
    init { System.loadLibrary("pocket_world_runtime") }
    external fun startNative(configPath: String): Int
    external fun setBotTargetNative(target: Int): Int
    external fun beginAdmissionBotTargetGenerationNative(generation: Long): Int
    external fun setAdmissionBotTargetNative(target: Int, generation: Long): Int
    external fun retireAdmissionBotTargetGenerationNative(generation: Long): Int
    external fun botStatusNative(): LongArray
    external fun performanceStatusNative(): LongArray
    external fun createAccountNative(username: String, password: String, timeoutMs: Long): Int
    external fun verifyAccountPasswordNative(username: String, password: String): Boolean
    external fun setAccountGmLevelNative(username: String, level: Int, timeoutMs: Long): Int
    external fun accountInfoNative(username: String): LongArray
    external fun characterPersistenceNative(username: String, characterName: String): String
    external fun realmInfoNative(): String
    // Test-support smoke rail: inject a chat line through the real chat
    // opcode handler, clear LLM assertion memory, read LLM memory state.
    external fun worldChatNative(
        characterName: String, channel: String, target: String, text: String,
        timeoutMs: Long): String
    external fun resetStateNative(player: String): String
    external fun llmMemoryStateNative(player: String): String
    external fun saveNative(timeoutMs: Long): Int
    external fun stopNative(timeoutMs: Long): Int
    external fun statusNative(): LongArray
    external fun onlinePlayersNative(): Int
    external fun detailNative(): String

    // Companion mode: world pause primitive + LLM profile switch
    external fun pauseWorldNative(paused: Int): Int
    external fun isWorldPausedNative(): Int
    external fun setCompanionModeNative(enabled: Int): Int
}
