package com.pocketrealm.client

/**
 * Explicit virtual-desktop profiles for the dedicated gameplay surface.
 *
 * The current x86 provider remains pinned to [BALANCED]. [QUALITY] is a hook
 * for a separately qualified runtime/profile selection; declaring it here does
 * not silently opt an existing provider into a more expensive resolution.
 */
enum class ClientDisplayProfile(
    val id: String,
    val virtualWidth: Int,
    val virtualHeight: Int,
    val initialFrameCap: Int,
    val gameMaximized: Boolean,
) {
    BALANCED("balanced", 1280, 720, 30, gameMaximized = false),
    QUALITY("quality", 1920, 1080, 30, gameMaximized = true),
    ;

    init {
        require(virtualWidth * 9 == virtualHeight * 16) { "client display profile must be 16:9" }
        require(initialFrameCap in setOf(30, 45, 60)) { "unsupported client frame cap" }
    }

    val resolution: String
        get() = "${virtualWidth}x$virtualHeight"

    /** Exact uniform scale to an actual laid-out 16:9 physical surface. */
    fun exactScaleTo(physicalWidth: Int, physicalHeight: Int): Float {
        require(physicalWidth > 0 && physicalHeight > 0) { "physical surface must be non-empty" }
        require(physicalWidth * virtualHeight == physicalHeight * virtualWidth) {
            "physical surface must match the virtual desktop aspect"
        }
        return physicalWidth.toFloat() / virtualWidth
    }

    companion object {
        private const val RETROID_POCKET_6 = "Retroid Pocket 6"

        fun requireId(value: String): ClientDisplayProfile =
            entries.firstOrNull { it.id == value }
                ?: throw IllegalArgumentException("unknown client display profile: $value")

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
