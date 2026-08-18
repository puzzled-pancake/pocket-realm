package com.pocketrealm.bots

import java.io.File

/**
 * Process-local bridge between [BotProfiles.find] and the saved-preset
 * store. Installed once per process (UI, supervisor, world services) from
 * each component's create path; `BotProfiles.find` resolves `usr5`
 * identities through it before falling back to legacy adv decoders.
 */
object BotCustomPresets {

    @Volatile
    private var installed: BotPresetStore? = null

    fun install(store: BotPresetStore) {
        installed = store
        // Synchronous cold load: the document is small and every later
        // lookup then stays a pure memory read, including on the main thread.
        runCatching { kotlinx.coroutines.runBlocking { store.reload() } }
    }

    fun install(directory: File) = install(BotPresetStore(directory))

    fun isInstalled(): Boolean = installed != null

    fun store(): BotPresetStore? = installed

    /** Flush any pending disk state, then resolve a usr5 identity. */
    fun lookupProfile(id: String): BotProfile? {
        val store = installed ?: return null
        val parsed = BotPresetIdentities.parse(id) ?: return null
        return store.resolveIdentity(parsed.presetId, parsed.revision, parsed.digest)
    }
}
