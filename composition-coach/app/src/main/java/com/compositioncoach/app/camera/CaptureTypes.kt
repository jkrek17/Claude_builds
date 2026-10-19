package com.compositioncoach.app.camera

/**
 * Vendor-provided computational photography pipelines CameraX can route a still capture through
 * (CameraX Extensions). On Pixels, NIGHT and (where offered) HDR run Google's own multi-frame merge, which
 * is the single biggest lever for matching the stock camera's sharpness. [AUTO] lets the device choose.
 * Availability is per lens and is reported in [com.compositioncoach.app.ui.camera.CameraUiState.availableEnhancements].
 */
enum class CaptureEnhancement(val label: String) {
    OFF("Off"),
    AUTO("Auto"),
    HDR("HDR"),
    NIGHT("Night"),
    /** Vendor portrait/bokeh pipeline (`ExtensionMode.BOKEH`), when the lens offers it. */
    BOKEH("Portrait"),
}

/**
 * How steady the phone is right now, from the gyroscope, used to delay the shutter by a few hundred
 * milliseconds until hand shake settles ("steady shot") and to show a "Hold still" hint.
 */
enum class Steadiness { STEADY, SLIGHT_MOTION, MOVING }

/**
 * What the camera actually did for a capture, shown on the review card so a photo can be compared with
 * the stock app on equal terms. Any field may be null when the device does not report it.
 */
data class CaptureDiagnostics(
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val aperture: Float? = null,
    val focalLengthMm: Float? = null,
    val lensFacing: LensFacing? = null,
    val enhancement: CaptureEnhancement = CaptureEnhancement.OFF,
    val captureMode: String? = null,
    val jpegQuality: Int? = null,
    val steadiness: Steadiness? = null,
    val waitedForSteadyMs: Long? = null,
    val zoomRatio: Float? = null,
    val exposureCompensationIndex: Int? = null,
    val captureLatencyMs: Long? = null,
    // --- Additive fields (camera engineer) -----------------------------------------------------------
    /**
     * Lucky imaging: the variance-of-Laplacian sharpness score of each burst frame, in capture order
     * (see [SharpnessScorer]), and the 0-based index of the frame that was kept. Null when the capture
     * was a single frame (burst off, an extension handled the capture, flash was armed, or the burst
     * failed and fell back to a single capture).
     */
    val burstScores: List<Float>? = null,
    val burstPicked: Int? = null,
    /**
     * Degrees the saved photo was rotated by the auto-level step (the negation of the measured roll, so
     * positive = the image was turned clockwise), or null when auto-level did not run (off, roll outside
     * the 0.7-8 degree band, orientation unreliable or stale) or has not finished yet.
     */
    val autoLevelAppliedDegrees: Float? = null,
    /**
     * The `ImageCapture` stream size CameraX configured at bind time ("4080x3072"), and whether it equals
     * the largest 4:3 JPEG size the camera reports with an uncropped ViewPort - i.e. whether this photo
     * used every pixel the sensor offers a third-party app. False is the first thing to check when a
     * capture looks softer than the stock app.
     */
    val configuredCaptureSize: String? = null,
    val isFullResolution: Boolean? = null,
    /**
     * What the HAL reports it actually did for the still request (`TotalCaptureResult`), when a
     * capture-result tap was available (not under an extension): noise reduction and edge
     * (sharpening) modes by name, and whether optical image stabilization was on.
     */
    val noiseReductionMode: String? = null,
    val edgeMode: String? = null,
    val opticalStabilization: Boolean? = null,
) {
    /** "1/120 s" style shutter speed, or null. */
    val shutterText: String?
        get() = exposureTimeNs?.let { ns ->
            val seconds = ns / 1_000_000_000.0
            if (seconds >= 1.0) String.format(java.util.Locale.US, "%.1f s", seconds)
            else "1/${Math.round(1.0 / seconds)} s"
        }

    /**
     * The single INFO log line written per capture (tag `CaptureDiagnostics`), with every field in a
     * fixed `key=value` order so a tester can grep logcat (`adb logcat -s CaptureDiagnostics`) and compare
     * captures side by side. Unknown values print as `-`.
     */
    fun toLogLine(): String {
        fun Any?.v(): String = this?.toString() ?: "-"
        val scores = burstScores?.joinToString(prefix = "[", postfix = "]", separator = ",") {
            String.format(java.util.Locale.US, "%.1f", it)
        }
        val ev = exposureCompensationIndex
        val zoom = zoomRatio?.let { String.format(java.util.Locale.US, "%.2f", it) }
        val level = autoLevelAppliedDegrees?.let { String.format(java.util.Locale.US, "%.2f", it) }
        return "capture size=${widthPx.v()}x${heightPx.v()} configured=${configuredCaptureSize.v()} " +
            "fullRes=${isFullResolution.v()} lens=${lensFacing.v()} enhancement=$enhancement " +
            "mode=${captureMode.v()} jpegQ=${jpegQuality.v()} iso=${iso.v()} shutter=${shutterText.v()} " +
            "f=${aperture.v()} focal=${focalLengthMm.v()}mm nr=${noiseReductionMode.v()} edge=${edgeMode.v()} " +
            "ois=${opticalStabilization.v()} steadiness=${steadiness.v()} waitedMs=${waitedForSteadyMs.v()} " +
            "zoom=${zoom.v()} ev=${ev.v()} burst=${scores.v()} picked=${burstPicked.v()} " +
            "autoLevel=${level.v()} latencyMs=${captureLatencyMs.v()}"
    }
}
