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

    // F1-10 (lanes item 12): the account-expansion verb — the core's
    // `account set addon <name> <0|1|2>` console op (0 classic / 1 tbc /
    // 2 wotlk), typed like setAccountGmLevelNative. The account-creation
    // wirings chain it after a successful create so a flavor's account
    // row carries its expansion BEFORE first login.
    external fun setAccountAddonNative(username: String, expansion: Int, timeoutMs: Long): Int
    external fun accountInfoNative(username: String): LongArray
    external fun characterPersistenceNative(username: String, characterName: String): String

    // Remote friend accounts (design-remote-accounts §3/§8/§9/§11): the
    // verifier provisioning op (no password ever crosses the host), the
    // roster read, per-friend characters, and the ban/unban verbs. All
    // require the world READY; JSON answers follow the
    // characterPersistence pattern. createAccountVerifierNative returns
    // [resultCode, accountId].
    external fun createAccountVerifierNative(username: String, sHex: String, vHex: String): LongArray
    external fun accountListNative(): String
    external fun accountCharactersNative(username: String): String
    external fun banAccountNative(username: String, duration: String, reason: String, timeoutMs: Long): String
    external fun unbanAccountNative(username: String, timeoutMs: Long): String
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
