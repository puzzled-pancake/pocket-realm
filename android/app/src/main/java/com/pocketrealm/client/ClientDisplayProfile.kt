package com.pocketrealm.client

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Concrete virtual-desktop geometry for one profile on one physical panel.
 * The presentation renderer scales this uniformly onto the Android surface.
 */
data class ClientVirtualDisplay(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "virtual desktop must be non-empty" }
    }

    val resolution: String
        get() = "${width}x$height"

    /** Uniform presentation scale onto a same-aspect physical surface. */
    fun uniformScaleTo(physicalWidth: Int, physicalHeight: Int): Float {
        require(physicalWidth > 0 && physicalHeight > 0) { "physical surface must be non-empty" }
        return physicalHeight.toFloat() / height
    }
}

/**
 * Explicit virtual-desktop profiles for the dedicated gameplay surface.
 *
 * A profile fixes the virtual desktop's height class (and its 16:9 reference
 * geometry); [resolveFor] adopts the physical panel's landscape aspect for the
 * width so a uniformly scaled desktop always fills the screen. On an exactly
 * 16:9 panel such as the RP6's 1920x1080 the resolved desktop is exactly the
 * reference geometry (1280x720 / 1920x1080).
 *
 * The current x86 provider remains pinned to [BALANCED]. [QUALITY] is a hook
 * for a separately qualified runtime/profile selection; declaring it here does
 * not silently opt an existing provider into a more expensive resolution.
 */
enum class ClientDisplayProfile(
    val id: String,
    /** 16:9 reference width; the runtime width comes from [resolveFor]. */
    val virtualWidth: Int,
    /** 16:9 reference height; this is the profile's fixed height class. */
    val virtualHeight: Int,
    val gameMaximized: Boolean,
) {
    BALANCED("balanced", 1280, 720, gameMaximized = true),
    QUALITY("quality", 1920, 1080, gameMaximized = true),
    ;

    init {
        require(virtualWidth * 9 == virtualHeight * 16) { "client display profile must be 16:9" }
    }

    /** 16:9 reference geometry, for labels and panel-query failure fallbacks. */
    val resolution: String
        get() = "${virtualWidth}x$virtualHeight"

    /** 16:9 reference geometry as a [ClientVirtualDisplay]. */
    fun nominalDisplay(): ClientVirtualDisplay = ClientVirtualDisplay(virtualWidth, virtualHeight)

    /**
     * Resolve this profile onto a physical landscape panel. The height class
     * is kept; the width adopts the panel's aspect (even-rounded down) so
     * uniform presentation scaling fills the panel without stretch or crop.
     * The profile's height class must fit the panel.
     */
    fun resolveFor(landscapeWidth: Int, landscapeHeight: Int): ClientVirtualDisplay {
        require(landscapeWidth > 0 && landscapeHeight > 0) {
            "physical display bounds must be non-empty"
        }
        require(virtualHeight <= landscapeHeight) {
            "client display profile $id exceeds the physical display"
        }
        val adapted = (landscapeWidth.toLong() * virtualHeight / landscapeHeight).toInt()
        val width = if (adapted % 2 == 0) adapted else adapted - 1
        return ClientVirtualDisplay(width, virtualHeight)
    }

    companion object {
        private const val RETROID_POCKET_6 = "Retroid Pocket 6"

        fun requireId(value: String): ClientDisplayProfile =
            entries.firstOrNull { it.id == value }
                ?: throw IllegalArgumentException("unknown client display profile: $value")

        fun availableForPhysical(
            landscapeWidth: Int,
            landscapeHeight: Int,
        ): List<ClientDisplayProfile> {
            require(landscapeWidth > 0 && landscapeHeight > 0) {
                "physical display bounds must be non-empty"
            }
            val width = maxOf(landscapeWidth, landscapeHeight)
            val height = minOf(landscapeWidth, landscapeHeight)
            // Widths adapt to the panel aspect in [resolveFor], so only the
            // fixed height class has to fit the panel.
            return entries.filter { it.virtualHeight <= height }
        }

        fun requireForPhysical(
            id: String,
            landscapeWidth: Int,
            landscapeHeight: Int,
        ): ClientDisplayProfile {
            val selected = requireId(id)
            require(selected in availableForPhysical(landscapeWidth, landscapeHeight)) {
                "client display profile $id exceeds the physical display"
            }
            return selected
        }

        /**
         * Qualification APKs are single-ABI. Keep x86_64 on its retained
         * Balanced lane and select native-panel Quality for the ARM64 lane.
         * x86 wins for a development universal ABI list, matching runtime
         * provider selection and preventing an accidental x86 behavior change.
         */
        fun forSupportedAbis(supportedAbis: Iterable<String>): ClientDisplayProfile {
            val abis = supportedAbis.toSet()
            return when {
                "x86_64" in abis -> BALANCED
                "arm64-v8a" in abis -> BALANCED
                else -> throw IllegalArgumentException(
                    "no client display profile for ABI set: ${abis.sorted().joinToString()}",
                )
            }
        }

        /**
         * Native-panel Quality is intentionally qualified only for the measured
         * RP6 target. Other ARM64 devices retain the conservative Balanced
         * profile until they receive their own capability/performance record.
         */
        fun forDevice(supportedAbis: Iterable<String>, model: String): ClientDisplayProfile {
            val abis = supportedAbis.toSet()
            if ("x86_64" in abis) return BALANCED
            if ("arm64-v8a" !in abis) return forSupportedAbis(abis)
            return if (model.trim().equals(RETROID_POCKET_6, ignoreCase = true)) QUALITY else BALANCED
        }
    }
}

enum class ClientFrameCap(val fps: Int) {
    FPS_30(30),
    FPS_40(40),
    FPS_45(45),
    FPS_60(60),
    ;

    companion object {
        fun requireFps(value: Int): ClientFrameCap =
            entries.firstOrNull { it.fps == value }
                ?: throw IllegalArgumentException("unsupported client frame cap: $value")
    }
}

data class ClientDisplaySelection(
    val profile: ClientDisplayProfile,
    val frameCap: ClientFrameCap,
    /** Panel-resolved desktop; launch paths must resolve, never assume 16:9. */
    val virtual: ClientVirtualDisplay,
) {
    val resolution: String get() = virtual.resolution
    val virtualWidth: Int get() = virtual.width
    val virtualHeight: Int get() = virtual.height

    companion object {
        fun defaultForDevice(supportedAbis: Iterable<String>, model: String) =
            ClientDisplaySelection(
                ClientDisplayProfile.forDevice(supportedAbis, model),
                ClientFrameCap.FPS_30,
                ClientDisplayProfile.forDevice(supportedAbis, model).nominalDisplay(),
            )

        /**
         * Context-free selection carrying only the 16:9 reference geometry.
         * Used for display labels; runtime launch resolves the panel through
         * [ClientDisplayCapabilities.requireSelection] instead.
         */
        fun nominal(profile: ClientDisplayProfile, frameCap: ClientFrameCap) =
            ClientDisplaySelection(profile, frameCap, profile.nominalDisplay())

        fun forPhysical(
            profile: ClientDisplayProfile,
            frameCap: ClientFrameCap,
            landscapeWidth: Int,
            landscapeHeight: Int,
        ) = ClientDisplaySelection(
            profile,
            frameCap,
            profile.resolveFor(landscapeWidth, landscapeHeight),
        )
    }
}

object ClientDisplayCapabilities {
    data class NormalizedProfile(
        val profile: ClientDisplayProfile,
        val changed: Boolean,
    )

    /** Stable physical panel bounds, independent of transient system-bar insets. */
    fun physicalLandscapeBounds(context: Context): Pair<Int, Int> {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("Android default display is unavailable")
        val best = display.supportedModes.maxByOrNull { mode ->
            mode.physicalWidth.toLong() * mode.physicalHeight.toLong()
        } ?: display.mode
        return maxOf(best.physicalWidth, best.physicalHeight) to
            minOf(best.physicalWidth, best.physicalHeight)
    }

    fun requireSelection(
        context: Context,
        profileId: String,
        frameCap: Int,
    ): ClientDisplaySelection {
        val (width, height) = physicalLandscapeBounds(context)
        return ClientDisplaySelection.forPhysical(
            ClientDisplayProfile.requireForPhysical(profileId, width, height),
            ClientFrameCap.requireFps(frameCap),
            width,
            height,
        )
    }

    /**
     * Downgrade a restored preference deterministically when it no longer fits
     * the physical panel (for example after moving app data to another device).
     * New user writes still use [requireSelection] and fail closed instead of
     * silently changing what was selected.
     */
    fun normalizeProfileForPhysical(
        requested: ClientDisplayProfile,
        fallback: ClientDisplayProfile,
        landscapeWidth: Int,
        landscapeHeight: Int,
    ): NormalizedProfile {
        val available = ClientDisplayProfile.availableForPhysical(
            landscapeWidth, landscapeHeight,
        )
        if (requested in available) return NormalizedProfile(requested, changed = false)
        val replacement = fallback.takeIf { it in available }
            ?: available.maxByOrNull { it.virtualWidth.toLong() * it.virtualHeight }
            ?: fallback
        return NormalizedProfile(replacement, changed = replacement != requested)
    }
}
