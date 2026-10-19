package com.compositioncoach.app.camera

import kotlin.math.abs
import kotlin.math.ceil

/**
 * The zoom chips offered above the shutter, computed from the bound camera's zoom range.
 *
 * Photographic reasoning behind each preset:
 *  - **Ultra-wide (0.6x / 0.7x)**, only when `minZoom < 1` (a logical multi-camera with an ultra-wide lens
 *    behind it). Never below 0.6x even when the lens reaches further: the extreme corners of a phone
 *    ultra-wide are its softest, most distorted and most vignetted pixels, so stopping at 0.6x (or the
 *    lens minimum rounded *up* to one decimal, whichever is larger) keeps the frame inside the lens's good
 *    image circle.
 *  - **1x**: the main sensor's native field of view, its sharpest and lowest-noise configuration.
 *  - **2x**: on a 50 MP-class main sensor this is a centre crop of the full-resolution readout, and on
 *    smaller sensors it is still the classic portrait framing; offered only when the camera can reach it.
 *  - **5x**: the telephoto lens on phones that have one (a logical camera switches to it at ~5x). Offered
 *    whenever `maxZoom >= 5`; note that on a phone *without* a tele lens whose digital zoom still reaches 5x,
 *    this chip is a digital crop (the HAL's own upscaling), so it is never sharper than cropping later.
 */
object ZoomPresets {

    const val ULTRA_WIDE_FLOOR = 0.6f

    fun compute(minZoom: Float, maxZoom: Float): List<Float> {
        if (!(minZoom > 0f) || !(maxZoom >= minZoom)) return listOf(1f)
        val presets = mutableListOf<Float>()
        if (minZoom < 1f) {
            val roundedUp = ceil(minZoom * 10f - 1e-3f) / 10f
            val wide = maxOf(ULTRA_WIDE_FLOOR, roundedUp)
            if (wide < 1f) presets += wide
        }
        if (maxZoom >= 1f) presets += 1f
        if (maxZoom >= 2f) presets += 2f
        if (maxZoom >= 5f) presets += 5f
        return presets.ifEmpty { listOf(minZoom) }
    }

    /**
     * Which preset chip should read as selected for the live [zoomRatio]: the preset within 5% of it, or
     * null when the user has pinched to an in-between ratio.
     */
    fun selectedPreset(presets: List<Float>, zoomRatio: Float): Float? =
        presets.firstOrNull { abs(it - zoomRatio) <= it * 0.05f }
}
