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
) {
    /** "1/120 s" style shutter speed, or null. */
    val shutterText: String?
        get() = exposureTimeNs?.let { ns ->
            val seconds = ns / 1_000_000_000.0
            if (seconds >= 1.0) String.format(java.util.Locale.US, "%.1f s", seconds)
            else "1/${Math.round(1.0 / seconds)} s"
        }
}
