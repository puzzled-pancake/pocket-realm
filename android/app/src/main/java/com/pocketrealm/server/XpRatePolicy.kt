package com.pocketrealm.server

import kotlin.math.round

/**
 * Shared bounds for the configurable XP rates staged into the generated
 * mangosd.conf (Rate.XP.Kill / Rate.XP.Quest / Rate.XP.Explore /
 * Rate.Pet.XP.Kill). The conf is rewritten at every realm start, so a new
 * rate always applies on the next start and never mid-session. 1x is the
 * default and keeps authentic vanilla pacing; the band exists so an
 * exported/restored preference store can never stage a conf a realm
 * operator would call broken.
 *
 * Rates are carried as whole tenths so normalized values compare exactly
 * against the presets (binary 0.1 stepping in doubles does not).
 */
// The literals here are the domain itself: tenths arithmetic and the preset
// rate table.
@Suppress("MagicNumber")
internal object XpRatePolicy {
    const val DEFAULT_RATE = 1.0
    const val MIN_RATE = 0.1
    const val MAX_RATE = 10.0
    const val RATE_STEP = 0.1

    internal const val MIN_TENTHS = 1
    internal const val MAX_TENTHS = 100

    /** One-click presets; per-rate tuning rides on the same normalization. */
    val PRESET_RATES = listOf(1.0, 2.0, 3.0, 5.0)

    /** Whole tenths within the supported band: the exact stored/conf unit. */
    fun normalizeTenths(rate: Double): Int {
        // A non-finite value from a corrupted store recovers to the default.
        if (!rate.isFinite()) return normalizeTenths(DEFAULT_RATE)
        return round(rate / RATE_STEP).toInt().coerceIn(MIN_TENTHS, MAX_TENTHS)
    }

    fun tenthsToRate(tenths: Int): Double = tenths / 10.0

    /** Clamp to the supported band and snap to a tenth so the conf stays readable. */
    fun normalize(rate: Double): Double = tenthsToRate(normalizeTenths(rate))

    /** Conf formatting: integral rates print without a decimal (Rate.XP.Kill = 2). */
    fun format(rate: Double): String {
        val tenths = normalizeTenths(rate)
        return if (tenths % 10 == 0) (tenths / 10).toString() else (tenths / 10.0).toString()
    }
}
