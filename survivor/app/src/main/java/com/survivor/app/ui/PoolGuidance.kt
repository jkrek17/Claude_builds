package com.survivor.app.ui

import com.survivor.engine.REGULAR_SEASON_WEEKS
import kotlin.math.roundToInt

/**
 * Pure helpers for the pool-size quick setup shown on first run and in Settings: preset buttons for
 * entry count and the plain-language line explaining what the model optimizes for at that pool size.
 * No Android or engine-mutating dependencies, so it is unit-testable on its own.
 */
object PoolGuidance {
    /** Quick-pick entry-count buttons; the last one reads as "250+" and matches anything >= it. */
    val presets = listOf(10, 25, 50, 100, 250)

    fun presetLabel(preset: Int): String = if (preset == presets.last()) "${preset}+" else preset.toString()

    /** Which preset button (if any) should render selected for [entries]. */
    fun selectedPreset(entries: Int): Int? = when {
        entries >= presets.last() -> presets.last()
        else -> presets.firstOrNull { it == entries }
    }

    /**
     * "With N entries the model expects the last other entry to fall around Week X, so it optimizes
     * for ...". [expectedPoolEndWeek] is [com.survivor.engine.Evaluation.expectedPoolEndWeek]; null
     * before any data has loaded.
     */
    fun text(entries: Int, expectedPoolEndWeek: Double?): String {
        val weekPhrase = when {
            expectedPoolEndWeek == null -> "partway through the season"
            expectedPoolEndWeek > REGULAR_SEASON_WEEKS -> "after Week $REGULAR_SEASON_WEEKS"
            else -> "around Week ${expectedPoolEndWeek.roundToInt().coerceIn(1, REGULAR_SEASON_WEEKS)}"
        }
        val focus = when {
            entries <= 25 -> "protecting your best teams for the next few weeks, since a pool this size usually resolves early"
            entries <= 100 -> "balancing safety now against saving strong teams for the second half"
            else -> "surviving deep into the season, since a pool this size rarely finishes early"
        }
        return "With $entries entr${if (entries == 1) "y" else "ies"} the model expects the last other entry to fall $weekPhrase, so it optimizes for $focus."
    }
}
