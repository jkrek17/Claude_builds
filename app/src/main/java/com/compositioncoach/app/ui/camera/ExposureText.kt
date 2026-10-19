package com.compositioncoach.app.ui.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arithmetic and wording behind the exposure slider: index <-> slider fraction, and the "+0.7" EV text.
 * Pure, unit-tested in `ExposureTextTest`.
 */
object ExposureText {

    /** Whether the bound camera offers exposure compensation at all (`0..0` means it does not). */
    fun isAdjustable(range: IntRange): Boolean = range.first < range.last

    /** "+0.7", "-1.3", "0.0": one decimal, always signed except at zero. */
    fun format(index: Int, stepEv: Float): String {
        val ev = index * stepEv
        val rounded = (ev * 10f).roundToInt() / 10f
        if (rounded == 0f) return "0.0"
        val sign = if (rounded > 0f) "+" else "-"
        return sign + String.format(Locale.US, "%.1f", abs(rounded))
    }

    /** TalkBack's reading: "Exposure plus 0.7". */
    fun description(index: Int, stepEv: Float): String {
        val text = format(index, stepEv)
        return when {
            text.startsWith("+") -> "Exposure plus ${text.drop(1)}"
            text.startsWith("-") -> "Exposure minus ${text.drop(1)}"
            else -> "Exposure normal"
        }
    }

    /**
     * The EV size of one index step when the camera's own `ExposureState.exposureCompensationStep` could
     * not be read. Typical sensors report ±2 EV as -6..6 (1/3 EV), -12..12 (1/6 EV) or -20..20 (1/10 EV),
     * which this reproduces; anything else assumes ±2 EV over the range.
     */
    fun fallbackStepEv(range: IntRange): Float {
        val span = maxOf(abs(range.first), abs(range.last))
        return when {
            span <= 0 -> 0f
            span <= 6 -> 1f / 3f
            span % 10 == 0 -> 1f / 10f
            span <= 12 -> 1f / 6f
            else -> 2f / span
        }
    }

    /** Slider position for [index]: 0 at the bottom of the range, 1 at the top. */
    fun fractionFor(index: Int, range: IntRange): Float {
        if (!isAdjustable(range)) return 0.5f
        return (index.coerceIn(range) - range.first).toFloat() / (range.last - range.first)
    }

    /** The index a slider [fraction] (0 bottom .. 1 top) lands on, rounded to the nearest step. */
    fun indexFor(fraction: Float, range: IntRange): Int {
        if (!isAdjustable(range)) return 0
        val raw = range.first + fraction.coerceIn(0f, 1f) * (range.last - range.first)
        return raw.roundToInt().coerceIn(range)
    }
}
