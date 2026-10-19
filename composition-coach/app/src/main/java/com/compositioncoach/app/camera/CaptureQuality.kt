package com.compositioncoach.app.camera

/**
 * Pure decisions behind the quality configuration [CameraController] applies at bind time, split out so they
 * are unit-testable without a camera.
 */
object CaptureQuality {

    /** The JPEG quality every capture path encodes at. See [CameraController]'s class doc for why 100. */
    const val JPEG_QUALITY = 100

    /** A width/height pair with no Android dependency (unlike `android.util.Size`, usable in JVM tests). */
    data class PixelSize(val width: Int, val height: Int) {
        val area: Long get() = width.toLong() * height
        override fun toString(): String = "${width}x$height"
    }

    /**
     * The largest 4:3 (or 3:4) size in [sizes] by pixel count, or null if there is none. Tolerates the
     * few-pixel rounding some sensors report (e.g. 4032x3024 vs 4080x3072, both exact; 4000x2992 is within
     * 0.5% and counts too).
     */
    fun largestFourByThree(sizes: List<PixelSize>): PixelSize? =
        sizes.filter { isFourByThree(it) }.maxByOrNull { it.area }

    fun isFourByThree(size: PixelSize): Boolean {
        if (size.width <= 0 || size.height <= 0) return false
        val long = maxOf(size.width, size.height).toDouble()
        val short = minOf(size.width, size.height).toDouble()
        return kotlin.math.abs(long / short - 4.0 / 3.0) <= 0.01
    }

    /**
     * Which enhancements to offer the user for a lens, given which extension modes the vendor library reports
     * as available and which of those can also carry `ImageAnalysis`.
     *
     * OFF is always offered. HDR, NIGHT and BOKEH are offered whenever available: choosing one is an explicit trade
     * (vendor multi-frame processing in exchange for pausing live coaching if the session cannot carry
     * analysis). AUTO is different because it is the **default**: it is only offered when its session keeps
     * live coaching running (or when there is no analyzer to keep), since a default that silently switched
     * off the app's core feature on first launch would be a regression, not an enhancement. When AUTO is not
     * offered, the default resolves to OFF, which turns on lucky imaging instead.
     */
    fun offeredEnhancements(
        extensionAvailable: Set<CaptureEnhancement>,
        analysisSupported: Set<CaptureEnhancement>,
        needsAnalysis: Boolean,
    ): Set<CaptureEnhancement> {
        val offered = linkedSetOf(CaptureEnhancement.OFF)
        for (enhancement in listOf(CaptureEnhancement.AUTO, CaptureEnhancement.HDR, CaptureEnhancement.NIGHT, CaptureEnhancement.BOKEH)) {
            if (enhancement !in extensionAvailable) continue
            if (enhancement == CaptureEnhancement.AUTO && needsAnalysis && enhancement !in analysisSupported) continue
            offered += enhancement
        }
        return offered
    }

    /**
     * The enhancement a bind should actually use: the [requested] one when the lens offers it, otherwise
     * [CaptureEnhancement.OFF] (the persisted preference is untouched, so it applies again on a lens that
     * does offer it). OFF is always "available".
     */
    fun resolveEnhancement(requested: CaptureEnhancement, available: Set<CaptureEnhancement>): CaptureEnhancement =
        if (requested == CaptureEnhancement.OFF || requested in available) requested else CaptureEnhancement.OFF
}

/**
 * EXIF `Orientation` tag values (TIFF/EXIF 2.3 numbering, identical to `ExifInterface.ORIENTATION_*`), and the
 * one mapping the app needs: from CameraX's `rotationDegrees` plus an optional front-camera mirror to the tag.
 *
 * The mirror is applied in **display space, after rotation**: the saved selfie must look exactly like the
 * mirrored preview the photographer framed with, whichever way the phone was held. Composing "rotate clockwise
 * by r, then flip the upright image left-right" gives FLIP_HORIZONTAL (r = 0), TRANSPOSE (90), FLIP_VERTICAL
 * (180) and TRANSVERSE (270). Flipping the *stored buffer* first instead would turn a portrait selfie upside
 * down, which is why the table is spelled out here rather than delegated to a library helper whose flip
 * convention would have to be taken on trust.
 */
object ExifOrientations {
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    fun tagFor(rotationDegrees: Int, mirrorHorizontally: Boolean): Int {
        val r = ((rotationDegrees % 360) + 360) % 360
        return if (!mirrorHorizontally) {
            when (r) {
                90 -> ROTATE_90
                180 -> ROTATE_180
                270 -> ROTATE_270
                else -> NORMAL
            }
        } else {
            when (r) {
                90 -> TRANSPOSE
                180 -> FLIP_VERTICAL
                270 -> TRANSVERSE
                else -> FLIP_HORIZONTAL
            }
        }
    }
}
