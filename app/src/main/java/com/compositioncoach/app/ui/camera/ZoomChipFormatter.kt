package com.compositioncoach.app.ui.camera

/**
 * Pure text formatting for the pinch-zoom chip ([ZoomChip]) — kept free of Compose so it is
 * unit-testable on the plain JVM (see ZoomChipFormatterTest).
 */
object ZoomChipFormatter {
    /** "1.0×", "2.5×" — one decimal place, always with the multiplication sign, never a bare number. */
    fun format(zoomRatio: Float): String = "${"%.1f".format(zoomRatio)}×"
}
