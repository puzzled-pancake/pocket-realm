package com.pocketrealm.importer.inno

/**
 * Inno Setup data-version handling for the installer-payload lane. The reader
 * supports the non-ambiguous ANSI and Unicode variants from 5.0.0 through
 * 5.5.6 — the era of classic-WoW-era installers (the real
 * WoW-1.12.1_install.rar payload is 5.3.5 ANSI). Older versions, ISX builds,
 * 16-bit builds and the ambiguous 5.4.2(u)/5.5.0(u)/5.5.7 signatures are
 * rejected: the upstream extractor resolves those by trial-parsing whole
 * headers, a retry loop not worth porting for payloads that predate every
 * client we can use.
 */
class InnoVersion private constructor(val value: Long, val unicode: Boolean) : Comparable<InnoVersion> {

    val a: Int get() = ((value shr 16) and 0xff).toInt()
    val b: Int get() = ((value shr 8) and 0xff).toInt()
    val c: Int get() = (value and 0xff).toInt()

    override fun compareTo(other: InnoVersion): Int = value.compareTo(other.value)

    override fun toString(): String = "$a.$b.$c" + if (unicode) " (unicode)" else ""

    operator fun compareTo(other: Long): Int = value.compareTo(other)

    companion object {
        private fun v(a: Int, b: Int, c: Int): Long = (a.toLong() shl 16) or (b.toLong() shl 8) or c.toLong()

        val V5_0_0 = v(5, 0, 0)
        val V5_0_3 = v(5, 0, 3)
        val V5_1_0 = v(5, 1, 0)
        val V5_1_2 = v(5, 1, 2)
        val V5_1_7 = v(5, 1, 7)
        val V5_1_10 = v(5, 1, 10)
        val V5_1_13 = v(5, 1, 13)
        val V5_2_0 = v(5, 2, 0)
        val V5_2_1 = v(5, 2, 1)
        val V5_2_3 = v(5, 2, 3)
        val V5_2_5 = v(5, 2, 5)
        val V5_3_0 = v(5, 3, 0)
        val V5_3_3 = v(5, 3, 3)
        val V5_3_5 = v(5, 3, 5)
        val V5_3_6 = v(5, 3, 6)
        val V5_3_7 = v(5, 3, 7)
        val V5_3_8 = v(5, 3, 8)
        val V5_3_9 = v(5, 3, 9)
        val V5_3_10 = v(5, 3, 10)
        val V5_4_2 = v(5, 4, 2)
        val V5_5_0 = v(5, 5, 0)
        val V5_5_6 = v(5, 5, 6)
        val V5_5_7 = v(5, 5, 7)
        val V5_5_9 = v(5, 5, 9)
        val V5_6_0 = v(5, 6, 0)
        val V6_0_0 = v(6, 0, 0)

        /**
         * Known data signatures in the supported window. Entries outside this
         * map are either unsupported versions or not Inno at all.
         */
        private val signatures: Map<String, Long> = buildMap {
            // (version, has-unicode-variant): every version has an ANSI
            // signature; Unicode ("(u)") variants exist from 5.2.5 on.
            for ((ver, value, hasUnicode) in listOf(
                Triple("5.0.0", v(5, 0, 0), false), Triple("5.0.1", v(5, 0, 1), false),
                Triple("5.0.3", v(5, 0, 3), false), Triple("5.0.4", v(5, 0, 4), false),
                Triple("5.1.0", v(5, 1, 0), false), Triple("5.1.2", v(5, 1, 2), false),
                Triple("5.1.7", v(5, 1, 7), false), Triple("5.1.10", v(5, 1, 10), false),
                Triple("5.1.13", v(5, 1, 13), false), Triple("5.2.0", v(5, 2, 0), false),
                Triple("5.2.1", v(5, 2, 1), false), Triple("5.2.3", v(5, 2, 3), false),
                Triple("5.2.5", v(5, 2, 5), true), Triple("5.3.0", v(5, 3, 0), true),
                Triple("5.3.3", v(5, 3, 3), true), Triple("5.3.5", v(5, 3, 5), true),
                Triple("5.3.6", v(5, 3, 6), true), Triple("5.3.7", v(5, 3, 7), true),
                Triple("5.3.8", v(5, 3, 8), true), Triple("5.3.9", v(5, 3, 9), true),
                Triple("5.3.10", v(5, 3, 10), true), Triple("5.4.2", v(5, 4, 2), false),
                Triple("5.5.0", v(5, 5, 0), false), Triple("5.5.6", v(5, 5, 6), true),
            )) {
                put("Inno Setup Setup Data ($ver)", value)
                if (hasUnicode) put("Inno Setup Setup Data ($ver) (u)", value)
            }
        }

        /**
         * Parses the 64-byte signature stored at the header offset.
         * @throws InnoFormatException when the payload is not a supportable
         * Inno installer (the caller maps this to a VAL-13 rejection).
         */
        fun parse(signature: ByteArray): InnoVersion {
            require(signature.size == 64) { "signature must be 64 bytes" }
            val text = String(signature, Charsets.ISO_8859_1).substringBefore('\u0000')
            if (signature[0] == 'i'.code.toByte()) {
                throw InnoFormatException("legacy (pre-2.0) Inno Setup data — unsupported")
            }
            if (!text.contains("Inno Setup")) {
                throw InnoFormatException("not an Inno Setup installer payload")
            }
            val value = signatures[text]
                ?: throw InnoFormatException(
                    "Inno Setup data version '$text' is outside the supported 5.0.0–5.5.6 window",
                )
            return InnoVersion(value, text.endsWith("(u)"))
        }
    }
}

/** Internal parse failure; the archive lane maps it to ImportRejected VAL-13. */
class InnoFormatException(message: String) : Exception(message)
