package com.compositioncoach.app.ui.camera

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Which zoom preset chip is lit and what each one says, Pixel-style: the chip for the range the current
 * ratio falls in is filled and reads the live ratio ("1.4×" while pinched between 1 and 2), the others read
 * their bare preset ("0.6", "2", "5"). Pure, so pinch-highlighting is unit-tested (`ZoomPresetsTest`).
 */
object ZoomPresets {

    /** How close (relative) the live ratio must be to a preset to count as sitting exactly on it. */
    private const val ON_PRESET_TOLERANCE = 0.03f

    /** The row exists only when there is more than one preset to choose between. */
    fun isVisible(presets: List<Float>): Boolean = presets.distinct().size > 1

    /**
     * The index of the chip to fill for [ratio]: the largest preset at or below the ratio (within
     * tolerance), or the first chip when the ratio is below every preset. -1 for an empty list.
     */
    fun activeIndex(presets: List<Float>, ratio: Float): Int {
        if (presets.isEmpty()) return -1
        val atOrBelow = presets.indices.filter { ratio >= presets[it] * (1f - ON_PRESET_TOLERANCE) }
        return atOrBelow.maxByOrNull { presets[it] } ?: presets.indices.minBy { presets[it] }
    }

    /** Whether [ratio] is sitting on [preset], rather than somewhere between it and the next. */
    fun isOnPreset(preset: Float, ratio: Float): Boolean = abs(ratio - preset) <= preset * ON_PRESET_TOLERANCE

    /** "0.6", "1", "2", "2.5": a preset's own number, no trailing ".0". */
    fun presetNumber(preset: Float): String {
        val tenths = (preset * 10f).roundToInt()
        return if (tenths % 10 == 0) (tenths / 10).toString() else String.format(Locale.US, "%.1f", tenths / 10f)
    }

    /** The text on one chip. */
    fun chipLabel(preset: Float, ratio: Float, active: Boolean): String = when {
        !active -> presetNumber(preset)
        isOnPreset(preset, ratio) -> "${presetNumber(preset)}×"
        else -> ZoomChipFormatter.format(ratio)
    }

    /** TalkBack's reading of one chip. */
    fun chipDescription(preset: Float): String = "Zoom ${presetNumber(preset)}×"
}
