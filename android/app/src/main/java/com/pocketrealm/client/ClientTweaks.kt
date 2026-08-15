package com.pocketrealm.client

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Toggleable vanilla-tweaks (upstream `brndd/vanilla-tweaks` v1.6.0, MIT) applied
 * to a pristine managed `WoW.exe` as a root-level `WoW.exe.patched` sibling.
 *
 * The field set and CLI flag identifiers mirror the vendored upstream `main.rs`
 * exactly (commit pinned in `native/vanilla-tweaks/BUILD_PROVENANCE.json`). Each
 * patch defaults to the upstream default (patch applied, upstream-default value);
 * [toFlags] emits the CLI form the patcher consumes. Published builds carry one
 * Pocket-Realm-owned companion patch on top of the upstream output: the
 * nearby-loot acceptance patch (see [ClientTweaksConfig.applyNearbyLootAcceptPatch])
 * that lets the L1 nearby-use feature open loot windows through the vanilla
 * client loot lifecycle.
 *
 * Persisted as a single JSON-string DataStore key by [com.pocketrealm.storage.Settings]
 * (DECISIONS.md: no JSON-string precedent existed in Settings; introduced here for
 * this composite value).
 */
data class ClientTweaksConfig(
    val fovEnabled: Boolean = false,
    val fov: Float = DEFAULT_FOV,
    val farclipEnabled: Boolean = false,
    val farclip: Float = DEFAULT_FARCLIP,
    val frilldistanceEnabled: Boolean = false,
    val frilldistance: Float = DEFAULT_FRILLDISTANCE,
    val soundInBackgroundEnabled: Boolean = false,
    val soundChannelsEnabled: Boolean = false,
    val soundChannels: Int = DEFAULT_SOUND_CHANNELS,
    val quicklootEnabled: Boolean = false,
    val nameplateEnabled: Boolean = false,
    val nameplateDistance: Float = DEFAULT_NAMEPLATE_DISTANCE,
    val largeAddressAwareEnabled: Boolean = false,
    val cameraSkipFixEnabled: Boolean = false,
    // Upstream patch is OFF by default. When enabled, raise the vanilla 50 limit to 100.
    val maxCameraDistanceEnabled: Boolean = false,
    val maxCameraDistance: Float = DEFAULT_CAMERA_DISTANCE_MAX,
) {
    init {
        require(fov.isFinite() && fov in 0.5f..3.0f) { "fov is out of range" }
        require(farclip.isFinite() && farclip in 177f..10_000f) { "farclip is out of range" }
        require(frilldistance.isFinite() && frilldistance in 0f..1_000f) {
            "frill distance is out of range"
        }
        require(soundChannels in 1..128) { "sound channel count is out of range" }
        require(nameplateDistance.isFinite() && nameplateDistance in 20f..100f) {
            "nameplate distance is out of range"
        }
        require(maxCameraDistance.isFinite() && maxCameraDistance in 1f..100f) {
            "maximum camera distance is out of range"
        }
    }

    /**
     * Emit the patcher CLI args (without the input/output operands). Each patch
     * follows its own toggle (upstream default: applied).
     */
    fun toFlags(): List<String> {
        val flags = mutableListOf<String>()
        if (!fovEnabled) flags += "--no-fov"
        else if (fov != DEFAULT_FOV) { flags += "--fov"; flags += fov.toString() }
        if (!farclipEnabled) flags += "--no-farclip"
        else if (farclip != DEFAULT_FARCLIP) { flags += "--farclip"; flags += farclip.toString() }
        if (!frilldistanceEnabled) flags += "--no-frilldistance"
        else if (frilldistance != DEFAULT_FRILLDISTANCE) {
            flags += "--frilldistance"; flags += frilldistance.toString()
        }
        if (!soundInBackgroundEnabled) flags += "--no-sound-in-background"
        if (!soundChannelsEnabled) flags += "--no-soundchannels"
        else if (soundChannels != DEFAULT_SOUND_CHANNELS) {
            flags += "--soundchannels"; flags += soundChannels.toString()
        }
        if (!quicklootEnabled) flags += "--no-quickloot"
        if (!nameplateEnabled) flags += "--no-nameplatedistance"
        else if (nameplateDistance != DEFAULT_NAMEPLATE_DISTANCE) {
            flags += "--nameplatedistance"; flags += nameplateDistance.toString()
        }
        if (!largeAddressAwareEnabled) flags += "--no-largeaddressaware"
        if (!cameraSkipFixEnabled) flags += "--no-cameraskipfix"
        if (maxCameraDistanceEnabled) {
            flags += "--maxcameradistance"; flags += maxCameraDistance.toString()
        }
        return flags
    }

    /** A stable signature input for the patched-exe cache sidecar. */
    fun signatureInput(): String = toFlags().joinToString(" ")

    fun hasAnyPatch(): Boolean = fovEnabled || farclipEnabled || frilldistanceEnabled ||
        soundInBackgroundEnabled || soundChannelsEnabled || quicklootEnabled ||
        nameplateEnabled || largeAddressAwareEnabled || cameraSkipFixEnabled ||
        maxCameraDistanceEnabled

    /**
     * Vanilla launch is hash-agnostic once the importer has proved build 5875.
     * Byte-addressed optional patches remain restricted to their verified image.
     */
    fun acceptsExecutableForLaunch(sha256: String): Boolean =
        !hasAnyPatch() || sha256.equals(AUTHORIZED_CLIENT_SHA256, ignoreCase = true)

    /**
     * Optional byte-addressed patches must never turn a valid imported build-5875
     * client into a boot failure.  Keep the user's requested value in Settings,
     * but select pristine Vanilla for this launch when the executable layout is
     * not the one those offsets were qualified against.
     */
    fun effectiveForExecutable(sha256: String): ClientTweaksConfig =
        if (acceptsExecutableForLaunch(sha256)) this else ClientTweaksConfig()

    fun toJson(): String {
        val o = JSONObject()
        o.put("fovEnabled", fovEnabled); o.put("fov", fov.toDouble())
        o.put("farclipEnabled", farclipEnabled); o.put("farclip", farclip.toDouble())
        o.put("frilldistanceEnabled", frilldistanceEnabled); o.put("frilldistance", frilldistance.toDouble())
        o.put("soundInBackgroundEnabled", soundInBackgroundEnabled)
        o.put("soundChannelsEnabled", soundChannelsEnabled); o.put("soundChannels", soundChannels)
        o.put("quicklootEnabled", quicklootEnabled)
        o.put("nameplateEnabled", nameplateEnabled); o.put("nameplateDistance", nameplateDistance.toDouble())
        o.put("largeAddressAwareEnabled", largeAddressAwareEnabled)
        o.put("cameraSkipFixEnabled", cameraSkipFixEnabled)
        o.put("maxCameraDistanceEnabled", maxCameraDistanceEnabled)
        o.put("maxCameraDistance", maxCameraDistance.toDouble())
        return o.toString()
    }

    companion object {
        // Upstream-default values (vanilla-tweaks 1.6.0 main.rs).
        const val DEFAULT_FOV: Float = 1.925f
        const val DEFAULT_FARCLIP: Float = 10_000f
        const val DEFAULT_FRILLDISTANCE: Float = 300f
        const val DEFAULT_NAMEPLATE_DISTANCE: Float = 41f
        const val DEFAULT_SOUND_CHANNELS: Int = 64
        const val DEFAULT_CAMERA_DISTANCE_MAX: Float = 100f
        private const val LEGACY_NOOP_CAMERA_DISTANCE_MAX: Float = 50f
        const val AUTHORIZED_CLIENT_SHA256: String =
            "b4756d38ef207c02ed651f4952bd89a70b4857b73a33413339e1b285b28d2dc7"

        /** Conservative one-tap set: CPU-heavier sound channels and code injection stay off. */
        fun commonPreset() = ClientTweaksConfig(
            fovEnabled = true,
            farclipEnabled = true,
            frilldistanceEnabled = true,
            soundInBackgroundEnabled = true,
            soundChannelsEnabled = false,
            quicklootEnabled = true,
            nameplateEnabled = true,
            largeAddressAwareEnabled = true,
            cameraSkipFixEnabled = false,
            maxCameraDistanceEnabled = false,
        )

        private val JSON_KEYS = setOf(
            "fovEnabled", "fov", "farclipEnabled", "farclip",
            "frilldistanceEnabled", "frilldistance", "soundInBackgroundEnabled",
            "soundChannelsEnabled", "soundChannels", "quicklootEnabled",
            "nameplateEnabled", "nameplateDistance", "largeAddressAwareEnabled",
            "cameraSkipFixEnabled", "maxCameraDistanceEnabled", "maxCameraDistance",
        )

        fun fromJson(raw: String?): ClientTweaksConfig {
            if (raw.isNullOrBlank()) return ClientTweaksConfig()
            return runCatching {
                val o = JSONObject(raw)
                val storedMaxCameraDistance = o.optDouble(
                    "maxCameraDistance",
                    DEFAULT_CAMERA_DISTANCE_MAX.toDouble(),
                ).toFloat()
                ClientTweaksConfig(
                    fovEnabled = o.optBoolean("fovEnabled", false),
                    fov = o.optDouble("fov", DEFAULT_FOV.toDouble()).toFloat(),
                    farclipEnabled = o.optBoolean("farclipEnabled", false),
                    farclip = o.optDouble("farclip", DEFAULT_FARCLIP.toDouble()).toFloat(),
                    frilldistanceEnabled = o.optBoolean("frilldistanceEnabled", false),
                    frilldistance = o.optDouble("frilldistance", DEFAULT_FRILLDISTANCE.toDouble()).toFloat(),
                    soundInBackgroundEnabled = o.optBoolean("soundInBackgroundEnabled", false),
                    soundChannelsEnabled = o.optBoolean("soundChannelsEnabled", false),
                    soundChannels = o.optInt("soundChannels", DEFAULT_SOUND_CHANNELS),
                    quicklootEnabled = o.optBoolean("quicklootEnabled", false),
                    nameplateEnabled = o.optBoolean("nameplateEnabled", false),
                    nameplateDistance = o.optDouble("nameplateDistance", DEFAULT_NAMEPLATE_DISTANCE.toDouble()).toFloat(),
                    largeAddressAwareEnabled = o.optBoolean("largeAddressAwareEnabled", false),
                    cameraSkipFixEnabled = o.optBoolean("cameraSkipFixEnabled", false),
                    maxCameraDistanceEnabled = o.optBoolean("maxCameraDistanceEnabled", false),
                    // The first UI stored 50 here, making the "raise" switch a no-op.
                    // There was no value editor, so that exact persisted value is legacy state.
                    maxCameraDistance = if (storedMaxCameraDistance == LEGACY_NOOP_CAMERA_DISTANCE_MAX) {
                        DEFAULT_CAMERA_DISTANCE_MAX
                    } else storedMaxCameraDistance,
                )
            }.getOrDefault(ClientTweaksConfig())
        }

        /** Strict parser for the private runtime control boundary. */
        fun fromControlJson(raw: String): ClientTweaksConfig {
            require(raw.toByteArray(Charsets.UTF_8).size <= 8_192) {
                "client tweaks payload is too large"
            }
            val o = JSONObject(raw)
            val actualKeys = buildSet { o.keys().forEachRemaining { add(it) } }
            require(actualKeys == JSON_KEYS) { "client tweaks schema mismatch" }
            return ClientTweaksConfig(
                fovEnabled = o.getBoolean("fovEnabled"),
                fov = o.getDouble("fov").toFloat(),
                farclipEnabled = o.getBoolean("farclipEnabled"),
                farclip = o.getDouble("farclip").toFloat(),
                frilldistanceEnabled = o.getBoolean("frilldistanceEnabled"),
                frilldistance = o.getDouble("frilldistance").toFloat(),
                soundInBackgroundEnabled = o.getBoolean("soundInBackgroundEnabled"),
                soundChannelsEnabled = o.getBoolean("soundChannelsEnabled"),
                soundChannels = o.getInt("soundChannels"),
                quicklootEnabled = o.getBoolean("quicklootEnabled"),
                nameplateEnabled = o.getBoolean("nameplateEnabled"),
                nameplateDistance = o.getDouble("nameplateDistance").toFloat(),
                largeAddressAwareEnabled = o.getBoolean("largeAddressAwareEnabled"),
                cameraSkipFixEnabled = o.getBoolean("cameraSkipFixEnabled"),
                maxCameraDistanceEnabled = o.getBoolean("maxCameraDistanceEnabled"),
                maxCameraDistance = o.getDouble("maxCameraDistance").toFloat(),
            )
        }

        /**
         * Computes the byte sequence the vanilla-tweaks patcher itself may
         * publish for [config]. This mirrors vanilla-tweaks 1.6.0 exactly and
         * lets the runtime reject successful-but-unexpected native output byte
         * for byte. The published `WoW.exe.patched` additionally carries the
         * Pocket Realm nearby-loot companion patch (see
         * [applyNearbyLootAcceptPatch]); use [expectedPublishedPatchedBytes]
         * for the final on-disk image.
         */
        fun expectedPatchedBytes(pristine: ByteArray, config: ClientTweaksConfig): ByteArray {
            require(peMagicOk(pristine)) { "managed client is not a PE image" }
            require(pristine.size > 0x467958 + 4) { "managed client is too small for build 5875" }
            val result = pristine.copyOf()
            fun put(offset: Int, bytes: ByteArray) {
                require(offset >= 0 && offset + bytes.size <= result.size) {
                    "tweak patch range is outside the managed client"
                }
                bytes.copyInto(result, offset)
            }
            fun putFloat(offset: Int, value: Float) = put(
                offset,
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array(),
            )

            if (config.largeAddressAwareEnabled) {
                val characteristics = ByteBuffer.wrap(result, 0x126, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                put(0x126, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((characteristics or 0x20).toShort()).array())
            }
            if (config.farclipEnabled) putFloat(0x40FED8, config.farclip)
            if (config.fovEnabled) putFloat(0x4089B4, config.fov)
            if (config.frilldistanceEnabled) putFloat(0x467958, config.frilldistance)
            if (config.soundInBackgroundEnabled) put(0x3A4869, byteArrayOf(0x27))
            if (config.soundChannelsEnabled) {
                val encoded = "${config.soundChannels}\u0000".toByteArray(Charsets.US_ASCII)
                require(encoded.size <= 4) { "sound channel value does not fit client field" }
                put(0x435D38, encoded)
            }
            if (config.quicklootEnabled) {
                put(0x0C1ECF, byteArrayOf(0x75)); put(0x0C2B25, byteArrayOf(0x75))
            }
            if (config.nameplateEnabled) putFloat(0x40C448, config.nameplateDistance)
            if (config.maxCameraDistanceEnabled) putFloat(0x4089A4, config.maxCameraDistance)
            if (config.cameraSkipFixEnabled) CAMERA_SKIP_PATCHES.forEach { (offset, bytes) ->
                put(offset, bytes)
            }
            return result
        }

        private val CAMERA_SKIP_PATCHES = listOf(
            0x02CCD0 to byteArrayOf(
                0x55,0x8b.toByte(),0x05,0x48,0x4e,0x88.toByte(),0x00,0x8b.toByte(),0x0d,0x44,
                0x4e,0x88.toByte(),0x00,0xe9.toByte(),0x33,0x90.toByte(),0x32,0x00,0x83.toByte(),
                0xc0.toByte(),0x32,0x83.toByte(),0xc1.toByte(),0x32,0x3b,0x0d,0xa8.toByte(),
                0xeb.toByte(),0xc4.toByte(),0x00,0x7e,0x03,0x83.toByte(),0xe9.toByte(),0x01,0x3b,
                0x05,0xac.toByte(),0xeb.toByte(),0xc4.toByte(),0x00,0x7e,0x03,0x83.toByte(),
                0xe8.toByte(),0x01,0x83.toByte(),0xe9.toByte(),0x32,0x83.toByte(),0xe8.toByte(),
                0x32,0x89.toByte(),0x05,0x48,0x4e,0x88.toByte(),0x00,0x89.toByte(),0x0d,0x44,
                0x4e,0x88.toByte(),0x00,0x5d,0xeb.toByte(),0x0d,
            ),
            0x02D326 to byteArrayOf(0xe9.toByte(),0xb1.toByte(),0x8a.toByte(),0x32,0x00),
            0x02D334 to byteArrayOf(0x8b.toByte(),0x35,0x48,0x4e,0x88.toByte(),0x00),
            0x355D15 to byteArrayOf(
                0x83.toByte(),0xf8.toByte(),0x32,0x7d,0x03,0x83.toByte(),0xc0.toByte(),0x01,
                0x83.toByte(),0xf9.toByte(),0x32,0x7d,0x03,0x83.toByte(),0xc1.toByte(),0x01,
                0xe9.toByte(),0xb8.toByte(),0x6f,0xcd.toByte(),0xff.toByte(),
            ),
            0x355DDC to byteArrayOf(
                0x8d.toByte(),0x4d,0xf0.toByte(),0x51,0xff.toByte(),0x35,0x00,0x4e,0x88.toByte(),
                0x00,0xff.toByte(),0x15,0x50,0xf6.toByte(),0x7f,0x00,0x8b.toByte(),0x45,
                0xf0.toByte(),0x8b.toByte(),0x15,0x44,0x4e,0x88.toByte(),0x00,0xe9.toByte(),
                0x35,0x75,0xcd.toByte(),0xff.toByte(),
            ),
        )

        /**
         * Companion patch (Pocket Realm, L1 nearby use): the stock 1.12.1 client
         * only enters its loot lifecycle for an `SMSG_LOOT_RESPONSE` whose GUID
         * matches a loot the client itself requested (or for the spontaneous
         * loot types 2-4, e.g. pickpocketing/fishing). Any other loot response
         * is politely declined: the client sends `CMSG_LOOT_RELEASE` and no
         * `LOOT_OPENED` ever fires. The server-side nearby-use interaction
         * therefore opens the loot session invisibly.
         *
         * The gate lives in the response handler's fallback branch: with no
         * matching pending loot GUID it accepts only loot-type bytes 2, 3 and 4
         * (`cmp al,2/3/4; je open`). This patch rewrites the first test to
         * `cmp al,1; jae open` — two bytes — so every real loot type (corpse
         * loot is type 1; the fork never sends 0) is accepted through the same
         * untouched vanilla open path. Client-initiated loot (the GUID-matching
         * branch) is not modified.
         *
         * Fail-closed: the 24-byte original signature must appear exactly once
         * in the image, so any non-qualified client layout aborts publication
         * instead of patching the wrong code.
         */
        fun applyNearbyLootAcceptPatch(bytes: ByteArray): ByteArray {
            require(peMagicOk(bytes)) { "managed client is not a PE image" }
            require(bytes.size >= NEARBY_LOOT_ACCEPT_OFFSET + NEARBY_LOOT_ACCEPT_SIGNATURE.size) {
                "managed client is too small for the nearby-loot gate"
            }
            var occurrences = 0
            var index = indexOfSignature(bytes, NEARBY_LOOT_ACCEPT_SIGNATURE)
            while (index != -1) {
                occurrences++
                index = indexOfSignature(
                    bytes,
                    NEARBY_LOOT_ACCEPT_SIGNATURE,
                    fromIndex = index + 1,
                )
            }
            require(occurrences == 1) {
                "nearby-loot gate signature matched $occurrences times (expected 1)"
            }
            val result = bytes.copyOf()
            NEARBY_LOOT_ACCEPT_WRITES.forEach { (offset, value) ->
                require(offset in result.indices) {
                    "nearby-loot patch offset is outside the managed client"
                }
                result[offset] = value
            }
            return result
        }

        /** The complete `WoW.exe.patched` image: vanilla-tweaks output plus the companion patch. */
        fun expectedPublishedPatchedBytes(pristine: ByteArray, config: ClientTweaksConfig): ByteArray =
            applyNearbyLootAcceptPatch(expectedPatchedBytes(pristine, config))

        private fun indexOfSignature(
            bytes: ByteArray,
            signature: ByteArray,
            fromIndex: Int = 0,
        ): Int {
            if (signature.isEmpty() || bytes.size - fromIndex < signature.size) return -1
            outer@ for (i in fromIndex..bytes.size - signature.size) {
                for (j in signature.indices) {
                    if (bytes[i + j] != signature[j]) continue@outer
                }
                return i
            }
            return -1
        }

        /** Original bytes at 0x1EB944 (VA 0x5EB944) of the authorized enUS build-5875 image. */
        private val NEARBY_LOOT_ACCEPT_SIGNATURE = byteArrayOf(
            0x0B, 0xC1.toByte(),             // or eax, ecx            (pending GUID nonzero?)
            0x8A.toByte(), 0x45, 0xFE.toByte(), // mov al, [ebp-2]        (client loot type)
            0x75, 0x18,                      // jne release
            0x3C, 0x02,                      // cmp al, 2              <- becomes cmp al, 1
            0x0F, 0x84.toByte(), 0xA1.toByte(), 0x00, 0x00, 0x00, // je open <- becomes jae
            0x3C, 0x03,                      // cmp al, 3
            0x0F, 0x84.toByte(), 0x99.toByte(), 0x00, 0x00, 0x00, // je open
            0x3C, 0x04,                      // cmp al, 4
        )

        private const val NEARBY_LOOT_ACCEPT_OFFSET = 0x1EB944

        private val NEARBY_LOOT_ACCEPT_WRITES = listOf(
            0x1EB94C to 0x01.toByte(),       // cmp al, 2 -> cmp al, 1
            0x1EB94E to 0x83.toByte(),       // je +0xA1   -> jae +0xA1
        )

        /**
         * Runtime authorization is deliberately stronger: it requires the full
         * managed-client SHA-256 and then compares the patcher's complete output
         * with [expectedPatchedBytes].  These bytes remain useful for qualification
         * diagnostics because they identify the principal upstream patch sites.
         */
        @Deprecated("full managed-client SHA-256 authorization replaces sparse byte checks")
        fun expectedOriginalBytes(): Map<Int, Byte> = linkedMapOf(
            0x126 to 0x0f,
            0x127 to 0x01,
            0x40FED8 to 0x00,
            0x40FED9 to 0x40,
            0x40FEDA to 0x42,
            0x40FEDB to 0x44,
            0x4089B4 to 0xdb.toByte(),
            0x4089B5 to 0x0f,
            0x4089B6 to 0xc9.toByte(),
            0x4089B7 to 0x3f,
            0x467958 to 0x00,
            0x467959 to 0x00,
            0x46795A to 0x8c.toByte(),
            0x46795B to 0x42,
            0x3A4869 to 0x14,
            0x435D38 to '1'.code.toByte(),
            0x435D39 to '2'.code.toByte(),
            0x435D3A to 0x00,
            0x0C1ECF to 0x74,
            0x0C2B25 to 0x74,
            0x40C448 to 0x00,
            0x40C449 to 0x00,
            0x40C44A to 0xa0.toByte(),
            0x40C44B to 0x41,
            0x4089A4 to 0x00,
            0x4089A5 to 0x00,
            0x4089A6 to 0x48,
            0x4089A7 to 0x42,
        )

        /**
         * Pure PE sanity check (host-testable): the buffer is at least 0x40
         * bytes and starts with the `MZ` DOS stub magic. Mirrors the file-level
         * `WineRuntimeStore.checkPeMagic` so the patch-step decision logic can
         * be unit tested without the device-only patcher binary.
         */
        fun peMagicOk(bytes: ByteArray): Boolean {
            if (bytes.size < 0x40) return false
            return bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()
        }

        /**
         * Locale guard (pure, host-testable): returns the first offset in
         * [expected] whose byte in [bytes] differs from the known enUS-5875
         * original (or is out of range), or null when every offset matches.
         * Behavior-identical to the inline check previously in
         * `WineRuntimeStore.applyTweaks`, minus its dead elvis-on-non-null.
         */
        fun firstLocaleMismatch(bytes: ByteArray, expected: Map<Int, Byte>): Int? =
            expected.entries.firstOrNull { (off, exp) ->
                val actual = if (off in bytes.indices) bytes[off] else (-1).toByte()
                actual != exp
            }?.key
    }
}
