package com.pocketrealm.client

/**
 * The desktop runtime twins of the Android `WineRuntimeStore`'s top-level
 * tweak helpers (same package, same names, same signatures — the shared
 * `ClientTweaksConfigTest` compiles against these on the JVM). The Wine
 * machinery around them does not exist here: the desktop launch boundary
 * (DesktopVanillaTweaksPatcher / DesktopConfigWtf) consumes them instead.
 */

/** Resolve byte-addressed tweaks only after hashing the pristine executable. */
internal data class EffectiveClientTweaks(
    val config: ClientTweaksConfig,
    val fallback: Boolean,
)

internal fun resolveEffectiveClientTweaks(
    requested: ClientTweaksConfig,
    pristineSha256: String,
): EffectiveClientTweaks {
    val effective = requested.effectiveForExecutable(pristineSha256)
    return EffectiveClientTweaks(effective, fallback = effective != requested)
}

/** Pure Config.wtf projection for the optional executable sound-channel patch. */
internal fun managedSoundChannelsConfigLine(
    audioMode: String,
    effectiveTweaks: ClientTweaksConfig,
): String = if (audioMode == "on" && effectiveTweaks.soundChannelsEnabled) {
    "SET SoundSoftwareChannels \"${effectiveTweaks.soundChannels}\"\n"
} else ""

/**
 * the world conf's PocketRealm.NearbyInteract knob verdict — the
 * AUTHORIZED-layout EFFECTIVE resolution of the requested tweaks over the
 * user's actual client executable, never the raw requested consent. A
 * requested patch set on a non-build-5875 layout resolves to pristine (the
 * launch runs unpatched), so the server must not promise nearby-use the
 * unpatched client cannot complete — the original DESK-58 failure shape. A
 * null/unknown executable hash (no resolvable client at conf time) fails
 * closed to 0.
 */
internal fun nearbyUseEnabledForExecutable(
    requested: ClientTweaksConfig,
    pristineSha256: String?,
): Boolean =
    pristineSha256 != null &&
        resolveEffectiveClientTweaks(requested, pristineSha256).config.hasAnyPatch()
