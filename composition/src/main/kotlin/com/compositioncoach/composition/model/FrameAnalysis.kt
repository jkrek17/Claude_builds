package com.compositioncoach.composition.model

/**
 * Device attitude from the rotation-vector / accelerometer, expressed relative to the *upright preview*.
 *
 * @param rollDegrees camera tilt around the viewing axis. Positive = the horizon appears rotated clockwise on
 *   screen (the user needs to rotate the phone counter-clockwise to level it). 0 = level.
 * @param pitchDegrees camera tilt up/down. 0 = pointing at the horizon, positive = pointing up.
 * @param isReliable false when the sensor is unavailable or the device is pointing straight up/down.
 */
data class DeviceOrientation(
    val rollDegrees: Float,
    val pitchDegrees: Float,
    val isReliable: Boolean = true,
)

/**
 * Everything the vision layer knows about one sampled camera frame. This is the *only* input to the
 * composition engine, which keeps the engine free of Android/camera types and unit-testable.
 *
 * Geometry is normalized to the upright, display-oriented (and front-camera-mirrored) frame; see [NormalizedPoint].
 *
 * @param frameWidth / frameHeight size of the upright analysis frame in pixels (used for aspect ratio only).
 * @param detectorTimings per-detector wall time in ms, for the debug overlay.
 */
data class FrameAnalysis(
    val timestampNanos: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val faces: List<DetectedFace> = emptyList(),
    val bodies: List<DetectedBody> = emptyList(),
    val stats: ImageStatistics? = null,
    val orientation: DeviceOrientation? = null,
    val isFrontCamera: Boolean = false,
    val analysisLatencyMs: Long = 0L,
    val detectorTimings: Map<String, Long> = emptyMap(),
) {
    val aspectRatio: Float get() = if (frameHeight == 0) 1f else frameWidth.toFloat() / frameHeight
}
