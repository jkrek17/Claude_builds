package com.compositioncoach.vision.merge

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Tone adjustments for the burst merge, as one 256-entry gain table on luma so a 12 MP strip costs a lookup
 * and a multiply per pixel and colour ratios (hue and saturation) are preserved.
 *
 * - **Exposure lift** ([lut] `exposureStops` > 0): the burst is captured a third of a stop under so highlights
 *   survive; the merge lifts it back in linear light with a soft shoulder above [SHOULDER_KNEE], so the
 *   highlights the under-exposure saved are rolled off instead of clipped again.
 * - **Shadow lift** ([lut] `shadowLift` > 0): Night's gentle lift, `l + s * l * (1 - l)^2` on the encoded
 *   luma: nothing at black or white, the most at about a third of the way up the scale, so shadows come up
 *   and highlights are held. The gain is capped at [MAX_GAIN] so near-black noise is not amplified without
 *   limit.
 *
 * Pure functions over ARGB pixels.
 */
object ToneLift {
    /** Gamma used to go between encoded and linear light (close enough to sRGB for a tone curve). */
    private const val GAMMA = 2.2f

    /** Linear level above which highlights are rolled off when the exposure is lifted. */
    private const val SHOULDER_KNEE = 0.7f

    /** The most any luma level is multiplied by. */
    const val MAX_GAIN = 2f

    private const val LEVELS = 256

    /** Default Night shadow lift ("gentle"): about +0.15 in encoded luma at a third of the scale. */
    const val NIGHT_SHADOW_LIFT = 0.6f

    /** Encoded luma in 0..1 after lifting exposure by [exposureStops] and shadows by [shadowLift]. */
    fun curve(luma: Float, exposureStops: Float, shadowLift: Float): Float {
        var v = luma.coerceIn(0f, 1f)
        if (exposureStops != 0f) {
            var linear = v.pow(GAMMA) * 2f.pow(exposureStops)
            if (linear > SHOULDER_KNEE) {
                val room = 1f - SHOULDER_KNEE
                linear = SHOULDER_KNEE + room * (1f - exp(-(linear - SHOULDER_KNEE) / room))
            }
            v = min(linear, 1f).pow(1f / GAMMA)
        }
        if (shadowLift != 0f) v += shadowLift * v * (1f - v) * (1f - v)
        return v.coerceIn(0f, 1f)
    }

    /** The gain for each of the 256 luma levels (level 0 shares level 1's gain; black stays black either way). */
    fun lut(exposureStops: Float, shadowLift: Float): FloatArray {
        val gains = FloatArray(LEVELS)
        for (i in 1 until LEVELS) {
            val l = i / 255f
            gains[i] = (curve(l, exposureStops, shadowLift) / l).coerceIn(1f / MAX_GAIN, MAX_GAIN)
        }
        gains[0] = gains[1]
        return gains
    }

    /** True when every gain is 1 (nothing to apply). */
    fun isIdentity(lut: FloatArray): Boolean = lut.all { it == 1f }

    /** Multiplies the first [count] pixels of [argb] by the gain of their luma, in place. */
    fun apply(argb: IntArray, count: Int, lut: FloatArray) {
        for (i in 0 until count) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val gain = lut[(77 * r + 150 * g + 29 * b) shr 8]
            if (gain == 1f) continue
            argb[i] = (p and 0xFF000000.toInt()) or
                (scale(r, gain) shl 16) or (scale(g, gain) shl 8) or scale(b, gain)
        }
    }

    private fun scale(channel: Int, gain: Float): Int = (channel * gain).roundToInt().coerceIn(0, 255)
}
