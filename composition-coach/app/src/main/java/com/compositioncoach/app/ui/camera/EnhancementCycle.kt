package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.camera.CaptureEnhancement

/**
 * What the enhancement pill offers and what a tap moves to — pure, so the cycling order is unit-tested
 * (`EnhancementCycleTest`) rather than discovered on a device.
 */
object EnhancementCycle {

    /** The pill exists only when the lens offers something beyond OFF. */
    fun isVisible(available: Set<CaptureEnhancement>): Boolean = available.any { it != CaptureEnhancement.OFF }

    /**
     * The choices, in the enum's own order (Off, Auto, HDR, Night), with OFF always present so the user
     * can always get back to a plain capture. Empty when nothing beyond OFF is available.
     */
    fun options(available: Set<CaptureEnhancement>): List<CaptureEnhancement> =
        if (!isVisible(available)) {
            emptyList()
        } else {
            CaptureEnhancement.entries.filter { it == CaptureEnhancement.OFF || it in available }
        }

    /**
     * What one tap on the pill selects: the next option after [current], wrapping, starting with the first
     * real enhancement so a tap from Off does something visible. A [current] the lens no longer offers (a
     * lens switch away from Night) restarts at the first real enhancement too.
     */
    fun next(current: CaptureEnhancement, available: Set<CaptureEnhancement>): CaptureEnhancement {
        val options = options(available)
        if (options.isEmpty()) return CaptureEnhancement.OFF
        val index = options.indexOf(current)
        if (index < 0) return options.firstOrNull { it != CaptureEnhancement.OFF } ?: CaptureEnhancement.OFF
        return options[(index + 1) % options.size]
    }

    /** The word on the pill: the enhancement's own label ("Auto", "HDR", "Night", "Off"). */
    fun label(enhancement: CaptureEnhancement): String = enhancement.label

    /** TalkBack's reading of the pill. */
    fun description(enhancement: CaptureEnhancement): String = "Photo enhancement: ${enhancement.label}"

    /** The one-line chip shown while an enhancement has paused live analysis (e.g. Night). */
    fun pausedText(enhancement: CaptureEnhancement): String = "Coaching paused in ${enhancement.label}"

    /**
     * The coaching layer's paused chip for the camera state: [pausedText] of the enhancement actually running,
     * only while that session cannot carry live analysis; null (coaching runs) otherwise.
     */
    fun pausedTextFor(running: CaptureEnhancement, analysisPausedByEnhancement: Boolean): String? =
        pausedText(running).takeIf { analysisPausedByEnhancement && running != CaptureEnhancement.OFF }

    /** The note beside an option in the long-press menu: "pauses coaching" for a session without live analysis. */
    fun menuNote(option: CaptureEnhancement, pausingCoaching: Set<CaptureEnhancement>): String? =
        PAUSES_COACHING_NOTE.takeIf { option in pausingCoaching }

    const val PAUSES_COACHING_NOTE = "pauses coaching"
}
