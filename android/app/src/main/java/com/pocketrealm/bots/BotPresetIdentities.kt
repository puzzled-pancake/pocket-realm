package com.pocketrealm.bots

import java.security.MessageDigest

/**
 * Versioned identity for saved custom presets (brief §41):
 * `usr5-<preset-id-32hex>-<revision-base36>-<digest-8hex>`.
 *
 * Unlike adv4, the identity does not embed the resolved values (custom
 * configurations are far larger); the complete resolved data lives in the
 * immutable revision record inside [BotPresetStore], and the digest protects
 * resolved configuration, generated playerbot config and launch-sensitive
 * fields. Revisions keep already-launched identities resolvable after the
 * saved preset is edited again.
 */
object BotPresetIdentities {
    const val PREFIX = "usr5"
    const val SCHEMA = 5

    private val PATTERN = Regex("^$PREFIX-([0-9a-f]{32})-([0-9a-z]+)-([0-9a-f]{8})$")

    data class Parsed(val presetId: String, val revision: Int, val digest: String)

    fun parse(id: String): Parsed? {
        val match = PATTERN.matchEntire(id) ?: return null
        val revision = runCatching { match.groupValues[2].toInt(36) }.getOrNull() ?: return null
        if (revision < 1 || revision > 1_000_000) return null
        return Parsed(
            presetId = match.groupValues[1],
            revision = revision,
            digest = match.groupValues[3],
        )
    }

    fun mint(presetId: String, revision: Int, configuration: BotCustomConfiguration): String {
        require(presetId.matches(Regex("[0-9a-f]{32}"))) { "preset id must be 32 lowercase hex" }
        require(revision in 1..1_000_000)
        val placeholder = "$PREFIX-$presetId-${revision.toString(36)}-00000000"
        val profile = configuration.resolve(placeholder, name = "preset")
        return "$PREFIX-$presetId-${revision.toString(36)}-${digest(presetId, revision, profile)}"
    }

    fun digest(presetId: String, revision: Int, profile: BotProfile): String {
        val canonical = buildString {
            append(PREFIX).append('|')
            append(presetId).append('|')
            append(revision).append('|')
            append(profile.selectedTarget).append('|')
            append(profile.minimumOnline).append('|')
            append(profile.maximumOnline).append('|')
            append(profile.initialTarget).append('|')
            append(profile.startupIncreaseStep).append('|')
            append(profile.startupRampIntervalMs).append('|')
            append(profile.activationBatchSize).append('|')
            append(profile.accountCount).append('|')
            append(profile.playerbotConfig()).append('|')
            append(profile.admission)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Stable new preset identifier (32 lowercase hex, no dashes). */
    fun newPresetId(): String {
        val seed = java.util.UUID.randomUUID().toString().replace("-", "")
        check(seed.length == 32)
        return seed
    }
}
